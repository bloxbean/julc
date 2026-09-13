package org.julclang.compiler.pir;

import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTestVm;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.resolve.SymbolTable;
import org.julclang.compiler.resolve.TypeResolver;
import org.julclang.compiler.uplc.UplcGenerator;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.source.SourceLocation;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Tag("pair-case-backends")
class PairDestructuringPassTest {
    private static final PirType INT = new PirType.IntegerType();
    private static final PirType DATA = new PirType.DataType();
    private static final PirType.PairType PAIR = new PirType.PairType(INT, new PirType.ListType(DATA));
    private static final PirTerm.Var P = new PirTerm.Var("p", PAIR);

    @Test
    void preservesStrictProducerRawBindingsAndDecodeTraceOrder() {
        var body = new PirTerm.Let("tag", trace("tag", first(P)),
                new PirTerm.Let("field", trace("field", call(DefaultFun.UnIData,
                        call(DefaultFun.HeadList, second(P)))),
                        add(new PirTerm.Var("tag", INT), new PirTerm.Var("field", INT))));
        for (var data : List.of(PlutusData.constr(3, PlutusData.integer(9)), PlutusData.constr(3),
                PlutusData.constr(3, PlutusData.bytes(new byte[]{1})), PlutusData.integer(5))) {
            var term = binding(trace("input", new PirTerm.Const(Constant.data(data))), body);
            var result = equivalent(term);
            if (data.equals(PlutusData.constr(3, PlutusData.integer(9)))) {
                assertEquals(Term.const_(Constant.integer(12)), ((EvalResult.Success) result).resultTerm());
                assertEquals(List.of("input", "tag", "field"), result.traces());
            } else {
                assertFalse(result.isSuccess());
                // Trace evaluates its value argument strictly; a failed field decode emits no "field".
                assertEquals(data instanceof PlutusData.ConstrData ? List.of("input", "tag") : List.of("input"), result.traces());
            }
        }
    }

    @Test
    void branchLocalDecodeRemainsLazyAndBothProjectionOrdersWork() {
        var body = new PirTerm.IfThenElse(call2(DefaultFun.EqualsInteger, first(P), integer(99)),
                call(DefaultFun.UnIData, call(DefaultFun.HeadList, second(P))), integer(7));
        var result = equivalent(binding(new PirTerm.Const(Constant.data(PlutusData.constr(0))), body));
        assertEquals(Term.const_(Constant.integer(7)), ((EvalResult.Success) result).resultTerm());
        assertFalse(equivalent(binding(new PirTerm.Const(Constant.data(PlutusData.constr(99))), body)).isSuccess());
        var reverse = new PirTerm.Let("fields", second(P), add(first(P), call(DefaultFun.UnIData,
                call(DefaultFun.HeadList, new PirTerm.Var("fields", PAIR.second())))));
        assertEquals(Term.const_(Constant.integer(12)), ((EvalResult.Success) equivalent(
                binding(data(), reverse))).resultTerm());
    }

    @Test
    void rejectsOneSidedEscapingAliasedUntypedAndUnprovenPairs() {
        var both = add(first(P), call(DefaultFun.UnIData, call(DefaultFun.HeadList, second(P))));
        var wrong = new PirTerm.Var("p", DATA);
        for (var term : List.of(binding(data(), first(P)), binding(data(), second(P)), binding(data(), P),
                binding(data(), new PirTerm.Let("alias", P, both)),
                binding(data(), add(first(wrong), second(wrong))),
                new PirTerm.Let("p", new PirTerm.Var("input", PAIR), both),
                new PirTerm.Let("p", data(), both))) {
            assertSame(term, lower(term));
        }
    }

    @Test
    void shadowedUsesAndFreshNamesCannotCaptureOuterFields() {
        var innerP = new PirTerm.Var("p", PAIR);
        var shadow = new PirTerm.App(new PirTerm.Lam("p", PAIR, first(innerP)),
                call(DefaultFun.UnConstrData, new PirTerm.Const(Constant.data(PlutusData.constr(20)))));
        var body = new PirTerm.Let("#pair-first-0", integer(100),
                add(new PirTerm.Var("#pair-first-0", INT), add(shadow,
                        add(first(P), call(DefaultFun.UnIData, call(DefaultFun.HeadList, second(P)))))));
        var term = binding(data(), body);
        assertEquals(Set.of(), PirSubstitution.collectFreeVarNames(lower(term)));
        assertEquals(Term.const_(Constant.integer(132)), ((EvalResult.Success) equivalent(term)).resultTerm());
        var escapedShadow = binding(data(), new PirTerm.Lam("p", PAIR, add(first(P), second(P))));
        assertSame(escapedShadow, lower(escapedShadow));
    }

    @Test
    void pairMatchBindersTypeAndSourcePositionsArePreserved() {
        var fst = first(P);
        var body = new PirTerm.Let("fields", second(P), fst);
        var term = binding(data(), body);
        var locations = new IdentityHashMap<PirTerm, SourceLocation>();
        var rootLocation = new SourceLocation("Pair.java", 10, 1, "pair binding");
        var projectionLocation = new SourceLocation("Pair.java", 12, 3, "first projection");
        locations.put(term, rootLocation);
        locations.put(fst, projectionLocation);
        var result = new PairDestructuringPass(context(OptimizationLevel.PV11_SAFE), locations).lower(term);
        var match = assertInstanceOf(PirTerm.PairMatch.class, result.term());
        assertEquals(rootLocation, result.positions().get(match));
        assertEquals(projectionLocation, result.positions().get(((PirTerm.Let) match.body()).body()));
        var generator = new UplcGenerator(context(OptimizationLevel.PV11_SAFE), result.positions());
        var uplc = generator.generate(match);
        assertEquals(rootLocation, generator.getUplcPositions().get(uplc));
        assertTrue(generator.getUplcPositions().containsValue(projectionLocation));
        var open = new PirTerm.PairMatch(new PirTerm.Var("outside", PAIR), PAIR, "a", "b",
                add(new PirTerm.Var("a", INT), new PirTerm.Var("free", INT)));
        assertEquals(Set.of("outside", "free"), PirSubstitution.collectFreeVarNames(open));
        var substituted = PirSubstitution.substitute(open, "a", integer(999));
        assertEquals(open, substituted);
        assertFalse(PirSubstitution.collectFreeVarNames(PirSubstitution.substitute(open, "free", integer(7))).contains("free"));
        var inference = new TypeInferenceHelper(new SymbolTable(), new TypeResolver(), null, null);
        assertEquals(INT, inference.inferPirType(new PirTerm.PairMatch(data(), PAIR, "a", "b", new PirTerm.Error(INT))));
    }

    @Test
    void baselineIsIdentityAndPairMatchFailsClosedOutsideSafeProfile() {
        var term = binding(data(), new PirTerm.Let("fields", second(P), first(P)));
        for (var level : List.of(OptimizationLevel.NONE, OptimizationLevel.BASELINE)) {
            assertSame(term, new PairDestructuringPass(context(level), null).lower(term).term());
            assertThrows(CompilerException.class, () -> new UplcGenerator(context(level), null).generate(lower(term)));
        }
    }

    @Test
    void recursiveClosureKeepsCapturedPairFields() {
        var n = new PirTerm.Var("n", INT);
        var functionType = new PirType.FunType(INT, INT);
        var recur = new PirTerm.Var("sum", functionType);
        var function = new PirTerm.Lam("n", INT, new PirTerm.IfThenElse(
                call2(DefaultFun.EqualsInteger, n, integer(0)),
                call(DefaultFun.UnIData, call(DefaultFun.HeadList, second(P))),
                add(first(P), new PirTerm.App(recur, call2(DefaultFun.SubtractInteger, n, integer(1))))));
        var term = binding(data(), new PirTerm.LetRec(List.of(new PirTerm.Binding("sum", function)),
                new PirTerm.App(recur, integer(4))));
        assertEquals(Set.of(), PirSubstitution.collectFreeVarNames(lower(term)));
        assertEquals(Term.const_(Constant.integer(21)), ((EvalResult.Success) equivalent(term)).resultTerm());
        var shadowed = binding(data(), new PirTerm.LetRec(List.of(new PirTerm.Binding("p", integer(2))),
                add(first(P), second(P))));
        assertSame(shadowed, lower(shadowed));
    }

    @Test
    void rawPairCasePinsFirstThenSecondAndStrictScrutineeAcrossBackends() {
        var nativePair = new PirTerm.Const(new Constant.PairConst(Constant.integer(3), Constant.integer(9)));
        var type = new PirType.PairType(INT, INT);
        var body = call2(DefaultFun.SubtractInteger, new PirTerm.Var("first", INT), new PirTerm.Var("second", INT));
        for (String provider : List.of("Java", "Truffle", "Scalus")) {
            var match = new PirTerm.PairMatch(trace("scrutinee", nativePair), type, "first", "second", trace("body", body));
            var result = evaluate(match, OptimizationLevel.PV11_SAFE, provider);
            assertEquals(Term.const_(Constant.integer(-6)), ((EvalResult.Success) result).resultTerm());
            assertEquals(List.of("scrutinee", "body"), result.traces());
            var failed = new PirTerm.PairMatch(new PirTerm.Error(type), type, "first", "second", trace("body", integer(7)));
            var failure = evaluate(failed, OptimizationLevel.PV11_SAFE, provider);
            assertInstanceOf(EvalResult.Failure.class, failure);
            assertEquals(List.of(), failure.traces());
            // Scalus language-only evaluation accepts additional Case shapes; it is not
            // the ledger oracle for this deliberately invalid native-pair precondition.
            if (!provider.equals("Scalus")) {
                var dataPair = new PirTerm.PairMatch(data(), type, "first", "second", integer(7));
                assertInstanceOf(EvalResult.Failure.class, evaluate(dataPair, OptimizationLevel.PV11_SAFE, provider), provider);
            }
        }
    }

    @Test
    void publicPirEntryPointAppliesTheSameTypedPass() {
        var term = binding(data(), add(first(P), call(DefaultFun.UnIData, call(DefaultFun.HeadList, second(P)))));
        var compiler = new JulcCompiler(null, new CompilerOptions().setOptimizationLevel(OptimizationLevel.PV11_SAFE));
        var program = compiler.compilePirToProgram(term);
        var result = CompilerTestVm.pv11("Java").evaluate(program,
                CompilerTarget.PLUTUS_V3_PV11.ledgerTarget(), null, EvalOptions.DEFAULT);
        assertEquals(Term.const_(Constant.integer(12)), ((EvalResult.Success) result).resultTerm());
        assertThrows(IllegalArgumentException.class, () -> new PirTerm.PairMatch(data(), PAIR, "same", "same", integer(0)));
    }

    private static EvalResult equivalent(PirTerm term) {
        var lowered = lower(term);
        assertInstanceOf(PirTerm.PairMatch.class, lowered);
        EvalResult javaResult = null;
        for (String provider : List.of("Java", "Truffle", "Scalus")) {
            var old = evaluate(term, OptimizationLevel.BASELINE, provider);
            var now = evaluate(lowered, OptimizationLevel.PV11_SAFE, provider);
            assertEquals(old.getClass(), now.getClass(), provider);
            assertEquals(old.traces(), now.traces(), provider);
            if (old instanceof EvalResult.Success success) assertEquals(success.resultTerm(), ((EvalResult.Success) now).resultTerm(), provider);
            if (old instanceof EvalResult.Failure failure) assertEquals(failure.error(), ((EvalResult.Failure) now).error(), provider);
            if (provider.equals("Java")) javaResult = now;
            if (provider.equals("Truffle")) assertEquals(javaResult.budgetConsumed(), now.budgetConsumed());
        }
        return javaResult;
    }

    private static EvalResult evaluate(PirTerm term, OptimizationLevel level, String provider) {
        var program = Program.plutusV3(new UplcGenerator(context(level), null).generate(term));
        var vm = CompilerTestVm.pv11(provider);
        return provider.equals("Scalus") ? vm.evaluate(program)
                : vm.evaluate(program, CompilerTarget.PLUTUS_V3_PV11.ledgerTarget(), null, EvalOptions.DEFAULT);
    }
    private static CompilationContext context(OptimizationLevel level) {
        return CompilationContext.resolve(new CompilerOptions().setOptimizationLevel(level));
    }
    private static PirTerm lower(PirTerm term) { return new PairDestructuringPass(context(OptimizationLevel.PV11_SAFE), Map.of()).lower(term).term(); }
    private static PirTerm binding(PirTerm data, PirTerm body) { return new PirTerm.Let("p", call(DefaultFun.UnConstrData, data), body); }
    private static PirTerm data() { return new PirTerm.Const(Constant.data(PlutusData.constr(3, PlutusData.integer(9)))); }
    private static PirTerm integer(long value) { return new PirTerm.Const(Constant.integer(value)); }
    private static PirTerm first(PirTerm pair) { return call(DefaultFun.FstPair, pair); }
    private static PirTerm second(PirTerm pair) { return call(DefaultFun.SndPair, pair); }
    private static PirTerm call(DefaultFun fun, PirTerm argument) { return new PirTerm.App(new PirTerm.Builtin(fun), argument); }
    private static PirTerm call2(DefaultFun fun, PirTerm a, PirTerm b) { return new PirTerm.App(call(fun, a), b); }
    private static PirTerm add(PirTerm a, PirTerm b) { return call2(DefaultFun.AddInteger, a, b); }
    private static PirTerm trace(String message, PirTerm body) { return new PirTerm.Trace(new PirTerm.Const(Constant.string(message)), body); }
}
