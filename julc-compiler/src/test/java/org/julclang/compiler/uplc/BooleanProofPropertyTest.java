package org.julclang.compiler.uplc;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTestVm;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.uplc.BooleanProof.Scope;
import org.julclang.core.BuiltinSemantics;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.DefaultUni;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;
import org.julclang.vm.JulcVm;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The soundness oracle of {@link BooleanProof}: over small closed PIR terms, deliberately ill-typed, a
 * term the proof accepts is evaluated on the VM and must fail or produce a boolean. Binder names come
 * from a pool of four, so shadowing is constant. {@link #theGeneratorReachesEveryOutcome} shows the
 * generator does produce what the proof has to rule out.
 */
class BooleanProofPropertyTest {

    private static final JulcVm VM = CompilerTestVm.pv11("Java");
    private static final ExBudget BUDGET = new ExBudget(200_000_000L, 20_000_000L);
    private static final List<String> NAMES = List.of("a", "b", "f", "g");

    private enum Outcome { BOOLEAN, OTHER_VALUE, FAILED, EXHAUSTED }

    private static Outcome outcome(EvalResult result) {
        return switch (result) {
            case EvalResult.Success s -> s.resultTerm() instanceof Term.Const(Constant.BoolConst _)
                    ? Outcome.BOOLEAN : Outcome.OTHER_VALUE;
            case EvalResult.Failure _ -> Outcome.FAILED;
            case EvalResult.BudgetExhausted _ -> Outcome.EXHAUSTED;
        };
    }

    private static CompilationContext context(OptimizationLevel level) {
        return CompilationContext.resolve(new CompilerOptions().setOptimizationLevel(level));
    }

    private static EvalResult evaluate(PirTerm term, OptimizationLevel level, boolean optimize) {
        var context = context(level);
        var lowered = new UplcGenerator(context, null).generate(term);
        return VM.evaluate(Program.plutusV3(optimize ? new UplcOptimizer(context).optimize(lowered) : lowered), BUDGET);
    }

    @Provide
    Arbitrary<PirTerm> terms() {
        return Arbitraries.randomValue(random -> new Generator(random).term(5, List.of()));
    }

    /** The property: a proven term fails or produces a boolean, and is never anything else. */
    @Property(tries = 30_000)
    void aProvenTermIsABooleanOrProducesNoValue(@ForAll("terms") PirTerm term) {
        if (!BooleanProof.proves(term, Scope.EMPTY)) return;
        for (var optimize : List.of(false, true)) {
            var result = evaluate(term, OptimizationLevel.PV11_SAFE, optimize);
            assertNotEquals(Outcome.OTHER_VALUE, outcome(result),
                    () -> "proven, yet it produced a value that is not a boolean\n  term: " + term + "\n  result: " + result);
        }
    }

    /**
     * What the lowering is for: a conditional over any term has one outcome at every level, whichever
     * form it lowers to. Terms the baseline profile cannot lower ({@code ListMatch}, {@code PairMatch},
     * {@code IntegerCase}) have nothing to compare with.
     */
    @Property(tries = 30_000)
    void aConditionalHasOneOutcomeAtEveryLevel(@ForAll("terms") PirTerm condition) {
        var one = new PirTerm.Const(Constant.integer(BigInteger.ONE));
        var two = new PirTerm.Const(Constant.integer(BigInteger.TWO));
        var conditional = new PirTerm.IfThenElse(condition, one, two);
        EvalResult baseline;
        try {
            baseline = evaluate(conditional, OptimizationLevel.BASELINE, false);
        } catch (CompilerException unsupportedAtThisLevel) {
            return;
        }
        for (var level : List.of(OptimizationLevel.PV11_SAFE, OptimizationLevel.PV11_COSTED)) {
            for (var optimize : List.of(false, true)) {
                var result = evaluate(conditional, level, optimize);
                if (outcome(baseline) == Outcome.EXHAUSTED || outcome(result) == Outcome.EXHAUSTED) continue;
                assertEquals(baseline.isSuccess(), result.isSuccess(),
                        () -> level + " disagrees\n  term: " + conditional + "\n  baseline: " + baseline
                                + "\n  result: " + result);
                if (baseline instanceof EvalResult.Success expected && result instanceof EvalResult.Success actual) {
                    assertEquals(expected.resultTerm(), actual.resultTerm(), () -> "term: " + conditional);
                }
            }
        }
    }

    /**
     * The control: with a fixed seed, the terms the oracle sees reach every outcome, including proven
     * terms that succeed, and unproven ones that produce the very values a proof must rule out.
     */
    @Example
    void theGeneratorReachesEveryOutcome() {
        var random = new Random(0x0B00L);
        var proven = new EnumMap<Outcome, Integer>(Outcome.class);
        var unproven = new EnumMap<Outcome, Integer>(Outcome.class);
        var values = new ArrayList<String>();
        var generator = new Generator(random);
        for (int i = 0; i < 30_000; i++) {
            var term = generator.term(5, List.of());
            var result = evaluate(term, OptimizationLevel.PV11_SAFE, false);
            var tally = BooleanProof.proves(term, Scope.EMPTY) ? proven : unproven;
            tally.merge(outcome(result), 1, Integer::sum);
            if (tally == unproven && result instanceof EvalResult.Success s) {
                if (s.resultTerm() instanceof Term.Const(var constant)) values.add(constant.getClass().getSimpleName());
                else values.add(s.resultTerm().getClass().getSimpleName());
            }
        }
        assertAtLeast(1_000, proven, Outcome.BOOLEAN);
        assertAtLeast(200, proven, Outcome.FAILED);
        assertAtLeast(1_000, unproven, Outcome.OTHER_VALUE);
        assertAtLeast(200, unproven, Outcome.BOOLEAN);
        // What the condition of a Case can select on, besides a boolean.
        for (var kind : List.of("IntegerConst", "ListConst", "Lam")) {
            assertTrue(values.contains(kind), () -> "no unproven term produced " + kind);
        }
    }

    private static void assertAtLeast(int count, Map<Outcome, Integer> tally, Outcome outcome) {
        int actual = tally.getOrDefault(outcome, 0);
        assertTrue(actual >= count, () -> outcome + ": " + actual + " of " + tally);
    }

    /** Closed terms over the binders of {@code scope}, mostly small, mostly reaching a value. */
    private static final class Generator {
        private static final PirType.PairType PAIR =
                new PirType.PairType(new PirType.IntegerType(), new PirType.ListType(new PirType.DataType()));
        private static final PirType TYPE = new PirType.BoolType();
        private static final List<DefaultFun> BOOLEAN_BUILTINS = List.of(DefaultFun.EqualsInteger,
                DefaultFun.LessThanInteger, DefaultFun.LessThanEqualsInteger, DefaultFun.EqualsByteString,
                DefaultFun.EqualsData, DefaultFun.NullList);
        private static final List<DefaultFun> OTHER_BUILTINS = List.of(DefaultFun.AddInteger, DefaultFun.HeadList,
                DefaultFun.TailList, DefaultFun.FstPair, DefaultFun.SndPair, DefaultFun.UnConstrData,
                DefaultFun.UnIData, DefaultFun.IData, DefaultFun.MkCons, DefaultFun.IfThenElse, DefaultFun.Trace,
                DefaultFun.ChooseList, DefaultFun.ChooseUnit, DefaultFun.ConstrData);

        private final Random random;

        Generator(Random random) {
            this.random = random;
        }

        PirTerm term(int depth, List<String> scope) {
            if (depth <= 0 || random.nextInt(6) == 0) return leaf(scope);
            int next = depth - 1;
            return switch (random.nextInt(20)) {
                case 0, 1, 2 -> {
                    var name = name();
                    yield new PirTerm.Let(name, term(next, scope), term(next, with(scope, name)));
                }
                case 3 -> {
                    var name = name();
                    yield new PirTerm.Lam(name, TYPE, term(next, with(scope, name)));
                }
                case 4, 5 -> new PirTerm.App(term(next, scope), term(next, scope));
                case 6 -> scope.isEmpty() ? leaf(scope) : call(next, scope);
                case 7, 8, 9 -> new PirTerm.IfThenElse(term(next, scope), term(next, scope), term(next, scope));
                case 10, 11 -> builtin(next, scope);
                case 12, 13 -> letRec(next, scope);
                case 14 -> mutual(next, scope);
                case 15 -> dataMatch(next, scope);
                case 16 -> pairMatch(next, scope);
                case 17 -> {
                    var head = name();
                    var tail = other(head);
                    yield new PirTerm.ListMatch(list(next, scope), head, tail, term(next, scope),
                            term(next, with(with(scope, head), tail)));
                }
                case 18 -> new PirTerm.IntegerCase(term(next, scope),
                        List.of(term(next, scope), term(next, scope)));
                default -> new PirTerm.Trace(new PirTerm.Const(Constant.string("m")), term(next, scope));
            };
        }

        private PirTerm leaf(List<String> scope) {
            if (!scope.isEmpty() && random.nextInt(3) != 0) return var(scope.get(random.nextInt(scope.size())));
            return switch (random.nextInt(14)) {
                case 0, 1, 2 -> new PirTerm.Const(Constant.bool(true));
                case 3, 4 -> new PirTerm.Const(Constant.bool(false));
                case 5, 6 -> integer(0);
                case 7, 8 -> integer(1);
                case 9 -> integer(2);
                case 10 -> new PirTerm.Const(Constant.unit());
                case 11 -> new PirTerm.Const(new Constant.ListConst(DefaultUni.DATA, List.of()));
                case 12 -> new PirTerm.Const(new Constant.ListConst(DefaultUni.DATA,
                        List.of(Constant.data(new PlutusData.IntData(BigInteger.ONE)))));
                default -> new PirTerm.Error(TYPE);
            };
        }

        private PirTerm call(int depth, List<String> scope) {
            PirTerm term = var(scope.get(random.nextInt(scope.size())));
            for (int i = random.nextInt(3); i >= 0; i--) term = new PirTerm.App(term, term(depth - 1, scope));
            return term;
        }

        /** A builtin with 0 to arity + 1 arguments, usually of the types it takes, so that it runs. */
        private PirTerm builtin(int depth, List<String> scope) {
            var pool = random.nextBoolean() ? BOOLEAN_BUILTINS : OTHER_BUILTINS;
            var fun = pool.get(random.nextInt(pool.size()));
            int arity = BuiltinSemantics.find(fun).valueArity();
            int count = random.nextInt(5) == 0 ? random.nextInt(arity + 2) : arity;
            PirTerm term = new PirTerm.Builtin(fun);
            for (int i = 0; i < count; i++) term = new PirTerm.App(term, argument(fun, i, depth, scope));
            return term;
        }

        private PirTerm argument(DefaultFun fun, int index, int depth, List<String> scope) {
            if (random.nextInt(4) == 0) return term(depth - 1, scope);
            return switch (fun) {
                case EqualsInteger, LessThanInteger, LessThanEqualsInteger, AddInteger -> integer(random.nextInt(3));
                case EqualsByteString -> new PirTerm.Const(Constant.byteString(new byte[]{(byte) random.nextInt(2)}));
                case EqualsData, UnConstrData, UnIData -> data();
                case NullList, HeadList, TailList, ChooseList -> list(depth, scope);
                case FstPair, SndPair -> new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnConstrData), data());
                case ConstrData -> index == 0 ? integer(1) : list(depth, scope);
                case MkCons -> index == 0 ? data() : list(depth, scope);
                default -> term(depth - 1, scope);
            };
        }

        private PirTerm letRec(int depth, List<String> scope) {
            var name = name();
            var param = name();
            return new PirTerm.LetRec(List.of(new PirTerm.Binding(name, new PirTerm.Lam(param, TYPE,
                    term(depth, with(with(scope, name), param))))), term(depth, with(scope, name)));
        }

        private PirTerm mutual(int depth, List<String> scope) {
            var first = name();
            var second = other(first);
            var inner = with(with(scope, first), second);
            var param = name();
            var bindings = List.of(
                    new PirTerm.Binding(first, new PirTerm.Lam(param, TYPE, term(depth, with(inner, param)))),
                    new PirTerm.Binding(second, new PirTerm.Lam(param, TYPE, term(depth, with(inner, param)))));
            return new PirTerm.LetRec(bindings, term(depth, inner));
        }

        private PirTerm dataMatch(int depth, List<String> scope) {
            var field = name();
            var pattern = other(field);
            var type = random.nextBoolean() ? TYPE : new PirType.DataType();
            var fields = new PirTerm.MatchBranch("A", List.of(), List.of(), term(depth, with(scope, pattern)), pattern);
            var flag = new PirTerm.MatchBranch("B", List.of(field), List.of(type), term(depth, with(scope, field)));
            return new PirTerm.DataMatch(data(), List.of(fields, flag));
        }

        private PirTerm pairMatch(int depth, List<String> scope) {
            var first = name();
            var second = other(first);
            PirTerm scrutinee = random.nextInt(3) != 0
                    ? new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnConstrData), data())
                    : term(depth, scope);
            return new PirTerm.PairMatch(scrutinee, PAIR, first, second, term(depth, with(with(scope, first), second)));
        }

        private PirTerm list(int depth, List<String> scope) {
            if (random.nextInt(4) == 0) return term(depth, scope);
            return leafList();
        }

        private PirTerm leafList() {
            var elements = new ArrayList<Constant>();
            for (int i = random.nextInt(3); i > 0; i--) elements.add(Constant.data(new PlutusData.IntData(BigInteger.ONE)));
            return new PirTerm.Const(new Constant.ListConst(DefaultUni.DATA, elements));
        }

        /** A constructor with a boolean-shaped or integer field, or something that is no constructor. */
        private PirTerm data() {
            var field = random.nextBoolean()
                    ? new PlutusData.ConstrData(random.nextInt(2), List.of())
                    : new PlutusData.IntData(BigInteger.valueOf(random.nextInt(3)));
            return new PirTerm.Const(Constant.data(switch (random.nextInt(4)) {
                case 0 -> new PlutusData.ConstrData(0, List.of());
                case 1 -> new PlutusData.ConstrData(1, List.of(field));
                case 2 -> new PlutusData.ConstrData(2, List.of());
                default -> field;
            }));
        }

        private PirTerm integer(long value) {
            return new PirTerm.Const(Constant.integer(BigInteger.valueOf(value)));
        }

        private PirTerm var(String name) {
            return new PirTerm.Var(name, TYPE);
        }

        private String name() {
            return NAMES.get(random.nextInt(NAMES.size()));
        }

        private String other(String name) {
            var next = name();
            return next.equals(name) ? NAMES.get((NAMES.indexOf(name) + 1) % NAMES.size()) : next;
        }

        private static List<String> with(List<String> scope, String name) {
            var extended = new ArrayList<>(scope);
            if (!extended.contains(name)) extended.add(name);
            return extended;
        }
    }
}
