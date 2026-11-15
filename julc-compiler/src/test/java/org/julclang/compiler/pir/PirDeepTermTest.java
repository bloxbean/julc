package org.julclang.compiler.pir;

import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.PlutusData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #327: the PIR walks of PirSubstitution, PirHelpers, TermTypes, PirFormatter, the literal folds and the pair
 * destructuring run on a small stack at any depth, and agree with the recursive forms they replaced
 * ({@code Reference*}, the code before the change) on random terms and on deep ones.
 */
class PirDeepTermTest {

    private static final int DEPTH = 50_000;
    /** The depth at which a result is compared with the recursive reference, whose scopes are copied per binder. */
    private static final int REFERENCE_DEPTH = 10_000;
    /**
     * The passes' depth. A pass keeps an unchanged node as the same object by comparing it with its rebuild, and a
     * change deep down is compared once per level above it, so a fold at the bottom of a spine costs the square of its
     * depth, as it did before #327; this depth is still far beyond what a 256 KB stack held recursively.
     */
    private static final int PASS_DEPTH = 5_000;
    private static final long SMALL_STACK = 256 * 1024;
    private static final long LARGE_STACK = 2L * 1024 * 1024 * 1024;

    private static final PirType INT = new PirType.IntegerType();
    private static final PirType DATA = new PirType.DataType();
    private static final PirType FUN = new PirType.FunType(INT, new PirType.FunType(INT, new PirType.BoolType()));
    private static final PirType.PairType PAIR = new PirType.PairType(INT, new PirType.ListType(DATA));
    private static final List<String> NAMES = List.of("x", "y", "z", "v", "w");

    // --- Deep spines -----------------------------------------------------------------------------------------------

    /** {@code let x0 = y0 in let x1 = y1 in ... body}. */
    static PirTerm letSpine(int depth, PirTerm body) {
        PirTerm term = body;
        for (int i = depth - 1; i >= 0; i--) term = new PirTerm.Let("x" + i, new PirTerm.Var("y" + i, INT), term);
        return term;
    }

    /** {@code if c0 then 0 else if c1 then 1 else ... body}. */
    static PirTerm elseSpine(int depth, PirTerm body) {
        PirTerm term = body;
        for (int i = depth - 1; i >= 0; i--) {
            term = new PirTerm.IfThenElse(new PirTerm.Var("c" + i, new PirType.BoolType()), integer(i), term);
        }
        return term;
    }

    /** {@code 0 + (1 + (2 + ... body))}: the deep child is an application's argument. */
    static PirTerm argumentSpine(int depth, PirTerm body) {
        PirTerm term = body;
        for (int i = depth - 1; i >= 0; i--) term = add(integer(i), term);
        return term;
    }

    /** {@code \a0 -> \a1 -> ... body}. */
    static PirTerm lambdaSpine(int depth, PirTerm body) {
        PirTerm term = body;
        for (int i = depth - 1; i >= 0; i--) term = new PirTerm.Lam("a" + i, INT, term);
        return term;
    }

    /** The innermost term under a chain of single-path forms. */
    static PirTerm bottom(PirTerm term) {
        while (true) {
            switch (term) {
                case PirTerm.Let let -> term = let.body();
                case PirTerm.Lam lam -> term = lam.body();
                case PirTerm.IfThenElse ite -> term = ite.elseBranch();
                case PirTerm.PairMatch match -> term = match.body();
                default -> {
                    return term;
                }
            }
        }
    }

    @Test
    void freeVariablesOfDeepTermsAgreeWithTheRecursiveForm() throws InterruptedException {
        var body = new PirTerm.App(new PirTerm.Var("x7", FUN), new PirTerm.Var("z", INT));
        for (var term : List.of(letSpine(REFERENCE_DEPTH, body), elseSpine(REFERENCE_DEPTH, body),
                argumentSpine(REFERENCE_DEPTH, body), lambdaSpine(REFERENCE_DEPTH, body))) {
            var actual = onSmallStack(() -> List.copyOf(PirSubstitution.collectFreeVarNames(term)));
            var expected = onLargeStack(() -> List.copyOf(ReferencePirSubstitution.collectFreeVarNames(term)));
            assertEquals(expected, actual);
        }
        var letFree = onSmallStack(() -> PirSubstitution.collectFreeVarNames(letSpine(DEPTH, body)));
        assertEquals(DEPTH + 1, letFree.size(), "every y_i, then z; x7 is bound");
        assertFalse(letFree.contains("x7"));
    }

    @Test
    void substitutionOfDeepTermsAgreesWithTheRecursiveForm() throws InterruptedException {
        // The replacement mentions x, which the outer lambda binds: the binder is renamed (hygiene) on the deep term.
        var body = new PirTerm.App(new PirTerm.Var("v", FUN), new PirTerm.Var("x", INT));
        var replacement = new PirTerm.Var("x", FUN);
        for (var spine : List.of(letSpine(REFERENCE_DEPTH, body), elseSpine(REFERENCE_DEPTH, body),
                argumentSpine(REFERENCE_DEPTH, body), lambdaSpine(REFERENCE_DEPTH, body))) {
            var term = new PirTerm.Lam("x", INT, spine);
            var actual = onSmallStack(() -> PirSubstitution.substitute(term, "v", replacement));
            var expected = onLargeStack(() -> ReferencePirSubstitution.substitute(term, "v", replacement));
            assertTrue(onSmallStack(() -> expected.equals(actual)), "deep substitution differs from the recursive form");
            var lam = assertInstanceOf(PirTerm.Lam.class, actual);
            assertEquals("#$pir$subst$0", lam.param());
        }
        var deep = new PirTerm.Lam("x", INT, letSpine(DEPTH, body));
        var substituted = assertInstanceOf(PirTerm.Lam.class, onSmallStack(() -> PirSubstitution.substitute(deep, "v", replacement)));
        assertEquals(new PirTerm.App(replacement, new PirTerm.Var(substituted.param(), INT)), bottom(substituted.body()));
    }

    @Test
    void containsVarRefOnDeepTerms() throws InterruptedException {
        var body = new PirTerm.Var("target", INT);
        for (var term : List.of(letSpine(DEPTH, body), elseSpine(DEPTH, body), argumentSpine(DEPTH, body),
                lambdaSpine(DEPTH, body))) {
            assertTrue(onSmallStack(() -> PirHelpers.containsVarRef(term, "target")));
            assertFalse(onSmallStack(() -> PirHelpers.containsVarRef(term, "absent")));
        }
        // Only a pair match's fields hide the name.
        var hidden = new PirTerm.PairMatch(new PirTerm.Var("p", PAIR), PAIR, "target", "other", letSpine(DEPTH, body));
        assertFalse(onSmallStack(() -> PirHelpers.containsVarRef(hidden, "target")));
    }

    @Test
    void constantArithmeticOfAnyLengthIsReadInPlace() throws InterruptedException {
        PirTerm sum = integer(0);
        for (int i = 1; i <= DEPTH; i++) sum = add(sum, integer(i));
        var constantSum = sum;
        // Constant arithmetic is trivial, so it is not bound.
        assertSame(constantSum, onSmallStack(() -> PirHelpers.readOnce(constantSum, INT, read -> read)));
        // One variable among the operands makes it a term to bind once.
        var withVariable = add(constantSum, new PirTerm.Var("n", INT));
        assertInstanceOf(PirTerm.Let.class, onSmallStack(() -> PirHelpers.readOnce(withVariable, INT, read -> read)));
    }

    @Test
    void inferenceOfDeepTermsAgreesWithTheRecursiveForm() throws InterruptedException {
        var call = new PirTerm.App(new PirTerm.App(new PirTerm.Var("f", FUN), integer(1)), integer(2));
        for (var term : List.of(letSpine(DEPTH, call), elseSpine(DEPTH, call), lambdaSpine(DEPTH, call))) {
            var actual = onSmallStack(() -> new TermTypes().infer(term));
            var expected = onLargeStack(() -> new ReferenceTermTypes().infer(term));
            assertEquals(expected, actual);
        }
        assertEquals(new PirType.BoolType(), onSmallStack(() -> new TermTypes().infer(letSpine(DEPTH, call))));
    }

    @Test
    void formattingOfDeepTermsAgreesWithTheRecursiveForm() throws InterruptedException {
        var body = new PirTerm.Var("z", INT);
        for (var term : List.of(letSpine(DEPTH, body), elseSpine(DEPTH, body), argumentSpine(DEPTH, body),
                lambdaSpine(DEPTH, body))) {
            assertEquals(onLargeStack(() -> ReferencePirFormatter.format(term)), onSmallStack(() -> PirFormatter.format(term)));
        }
        // Indented output grows with the square of the depth (every level indents its line), so it is compared shallower.
        for (var term : List.of(letSpine(2_000, body), elseSpine(2_000, body), argumentSpine(2_000, body),
                lambdaSpine(2_000, body))) {
            assertEquals(onLargeStack(() -> ReferencePirFormatter.formatPretty(term)),
                    onSmallStack(() -> PirFormatter.formatPretty(term)));
        }
    }

    @Test
    void literalFoldsRunOnDeepTerms() throws InterruptedException {
        var context = CompilationContext.resolve(new CompilerOptions().setOptimizationLevel(OptimizationLevel.PV11_SAFE));
        // lengthOfArray(listToArray([iData 1])) folds to 1 at the bottom of a deep term.
        var literal = call(DefaultFun.LengthOfArray, call(DefaultFun.ListToArray,
                call2(DefaultFun.MkCons, call(DefaultFun.IData, integer(1)),
                        call(DefaultFun.MkNilData, new PirTerm.Const(Constant.unit())))));
        var shallow = new ArrayLiteralFoldPass(context, Map.of()).lower(literal).term();
        assertEquals(integer(1), shallow, "the shallow fold, the expectation below");
        for (var term : List.of(letSpine(PASS_DEPTH, literal), elseSpine(PASS_DEPTH, literal), lambdaSpine(PASS_DEPTH, literal))) {
            var folded = onSmallStack(() -> new ArrayLiteralFoldPass(context, Map.of()).lower(term).term());
            assertEquals(shallow, bottom(folded));
            var values = onSmallStack(() -> new ValueLiteralFoldPass(context, Map.of()).lower(term).term());
            assertSame(term, values, "nothing for the value fold to do: the term is kept as it is");
        }
        var argument = onSmallStack(() -> new ArrayLiteralFoldPass(context, Map.of()).lower(argumentSpine(PASS_DEPTH, literal)).term());
        PirTerm inner = argument;
        for (int i = 0; i < PASS_DEPTH; i++) inner = ((PirTerm.App) inner).argument();
        assertEquals(shallow, inner);
    }

    @Test
    void pairDestructuringRunsOnDeepTerms() throws InterruptedException {
        var context = CompilationContext.resolve(new CompilerOptions().setOptimizationLevel(OptimizationLevel.PV11_SAFE));
        var p = new PirTerm.Var("p", PAIR);
        var reads = add(call(DefaultFun.FstPair, p),
                call(DefaultFun.UnIData, call(DefaultFun.HeadList, call(DefaultFun.SndPair, p))));
        var data = new PirTerm.Const(Constant.data(PlutusData.constr(3, PlutusData.integer(9))));
        for (var spine : List.of(letSpine(PASS_DEPTH, reads), elseSpine(PASS_DEPTH, reads), lambdaSpine(PASS_DEPTH, reads))) {
            var term = new PirTerm.Let("p", call(DefaultFun.UnConstrData, data), spine);
            var lowered = onSmallStack(() -> new PairDestructuringPass(context, Map.of()).lower(term).term());
            var match = assertInstanceOf(PirTerm.PairMatch.class, lowered);
            var expected = add(new PirTerm.Var(match.firstName(), INT),
                    call(DefaultFun.UnIData, call(DefaultFun.HeadList, new PirTerm.Var(match.secondName(), PAIR.second()))));
            assertEquals(expected, bottom(match.body()));
        }
        // A deep binding whose pair escapes stays a let.
        var escapes = new PirTerm.Let("p", call(DefaultFun.UnConstrData, data), letSpine(PASS_DEPTH, p));
        assertInstanceOf(PirTerm.Let.class,
                onSmallStack(() -> new PairDestructuringPass(context, Map.of()).lower(escapes).term()));
    }

    // --- Random terms against the recursive forms ------------------------------------------------------------------

    @Test
    void randomTermsAgreeWithTheRecursiveForms() {
        var random = new Random(327);
        for (int i = 0; i < 3_000; i++) {
            var term = random(random, 6);
            assertEquals(List.copyOf(ReferencePirSubstitution.collectFreeVarNames(term)),
                    List.copyOf(PirSubstitution.collectFreeVarNames(term)), () -> "free variables of " + term);
            assertEquals(ReferencePirFormatter.format(term), PirFormatter.format(term));
            assertEquals(ReferencePirFormatter.formatPretty(term), PirFormatter.formatPretty(term));
            assertEquals(new ReferenceTermTypes().infer(term), new TermTypes().infer(term), () -> "type of " + term);
            for (var name : NAMES) {
                assertEquals(referenceContainsVarRef(term, name), PirHelpers.containsVarRef(term, name),
                        () -> "containsVarRef " + name + " in " + term);
                var replacement = random(random, 2);
                var expected = ReferencePirSubstitution.substitute(term, name, replacement);
                var actual = PirSubstitution.substitute(term, name, replacement);
                assertEquals(expected, actual, () -> "substitute " + name + " := " + replacement + " in " + term);
                assertEquals(expected == term, actual == term, "an untouched term comes back as the same object");
            }
        }
    }

    /** {@code PirHelpers.containsVarRef} as it was before issue #327. */
    private static boolean referenceContainsVarRef(PirTerm term, String name) {
        return switch (term) {
            case PirTerm.Var v -> v.name().equals(name);
            case PirTerm.Let l -> referenceContainsVarRef(l.value(), name) || referenceContainsVarRef(l.body(), name);
            case PirTerm.LetRec lr -> lr.bindings().stream().anyMatch(b -> referenceContainsVarRef(b.value(), name))
                    || referenceContainsVarRef(lr.body(), name);
            case PirTerm.Lam lam -> referenceContainsVarRef(lam.body(), name);
            case PirTerm.App app -> referenceContainsVarRef(app.function(), name) || referenceContainsVarRef(app.argument(), name);
            case PirTerm.IfThenElse ite -> referenceContainsVarRef(ite.cond(), name)
                    || referenceContainsVarRef(ite.thenBranch(), name) || referenceContainsVarRef(ite.elseBranch(), name);
            case PirTerm.DataConstr dc -> dc.fields().stream().anyMatch(f -> referenceContainsVarRef(f, name));
            case PirTerm.IntegerCase c -> referenceContainsVarRef(c.scrutinee(), name)
                    || c.branches().stream().anyMatch(b -> referenceContainsVarRef(b, name));
            case PirTerm.PairMatch m -> referenceContainsVarRef(m.scrutinee(), name)
                    || (!m.firstName().equals(name) && !m.secondName().equals(name) && referenceContainsVarRef(m.body(), name));
            case PirTerm.ListMatch m -> referenceContainsVarRef(m.scrutinee(), name)
                    || referenceContainsVarRef(m.nilBranch(), name) || referenceContainsVarRef(m.consBranch(), name);
            case PirTerm.DataMatch dm -> referenceContainsVarRef(dm.scrutinee(), name)
                    || dm.branches().stream().anyMatch(b -> referenceContainsVarRef(b.body(), name));
            case PirTerm.Trace t -> referenceContainsVarRef(t.message(), name) || referenceContainsVarRef(t.body(), name);
            case PirTerm.Const _, PirTerm.Builtin _, PirTerm.Error _ -> false;
        };
    }

    /** A random term of every form, over a few names so that binders shadow and capture. */
    static PirTerm random(Random random, int depth) {
        if (depth <= 0 || random.nextInt(6) == 0) {
            return switch (random.nextInt(5)) {
                case 0, 1 -> new PirTerm.Var(name(random), type(random));
                case 2 -> integer(random.nextInt(10));
                case 3 -> new PirTerm.Builtin(random.nextBoolean() ? DefaultFun.AddInteger : DefaultFun.HeadList);
                default -> new PirTerm.Error(type(random));
            };
        }
        int d = depth - 1;
        return switch (random.nextInt(11)) {
            case 0 -> new PirTerm.Let(name(random), random(random, d), random(random, d));
            case 1 -> {
                var bindings = new ArrayList<PirTerm.Binding>();
                for (int i = 0, n = 1 + random.nextInt(2); i < n; i++) {
                    bindings.add(new PirTerm.Binding(name(random), random(random, d)));
                }
                yield new PirTerm.LetRec(bindings, random(random, d));
            }
            case 2 -> new PirTerm.Lam(name(random), type(random), random(random, d));
            case 3 -> new PirTerm.App(random(random, d), random(random, d));
            case 4 -> new PirTerm.IfThenElse(random(random, d), random(random, d), random(random, d));
            case 5 -> {
                var fields = new ArrayList<PirTerm>();
                for (int i = 0, n = random.nextInt(3); i < n; i++) fields.add(random(random, d));
                yield new PirTerm.DataConstr(random.nextInt(3), DATA, fields);
            }
            case 6 -> {
                var branches = new ArrayList<PirTerm.MatchBranch>();
                for (int i = 0, n = 1 + random.nextInt(2); i < n; i++) {
                    var bound = random.nextBoolean() ? List.of(name(random)) : List.of(name(random), name(random));
                    branches.add(new PirTerm.MatchBranch("C" + i, bound, bound.stream().map(b -> DATA).toList(),
                            random(random, d), random.nextBoolean() ? name(random) : null));
                }
                yield new PirTerm.DataMatch(random(random, d), branches);
            }
            case 7 -> {
                var names = twoNames(random);
                yield new PirTerm.ListMatch(random(random, d), names.get(0), names.get(1), random(random, d), random(random, d));
            }
            case 8 -> {
                var names = twoNames(random);
                yield new PirTerm.PairMatch(random(random, d), PAIR, names.get(0), names.get(1), random(random, d));
            }
            case 9 -> new PirTerm.IntegerCase(random(random, d), List.of(random(random, d), random(random, d)));
            default -> new PirTerm.Trace(new PirTerm.Const(Constant.string("t")), random(random, d));
        };
    }

    private static String name(Random random) {
        return NAMES.get(random.nextInt(NAMES.size()));
    }

    private static List<String> twoNames(Random random) {
        String first = name(random);
        String second;
        do second = name(random); while (second.equals(first));
        return List.of(first, second);
    }

    private static PirType type(Random random) {
        return switch (random.nextInt(3)) {
            case 0 -> INT;
            case 1 -> DATA;
            default -> FUN;
        };
    }

    // --- Helpers ---------------------------------------------------------------------------------------------------

    static PirTerm integer(long value) {
        return new PirTerm.Const(Constant.integer(value));
    }

    static PirTerm call(DefaultFun fun, PirTerm argument) {
        return new PirTerm.App(new PirTerm.Builtin(fun), argument);
    }

    static PirTerm call2(DefaultFun fun, PirTerm a, PirTerm b) {
        return new PirTerm.App(call(fun, a), b);
    }

    static PirTerm add(PirTerm a, PirTerm b) {
        return call2(DefaultFun.AddInteger, a, b);
    }

    static <T> T onSmallStack(Supplier<T> work) throws InterruptedException {
        return onStack(SMALL_STACK, work);
    }

    /** For the recursive references, which need a stack as deep as the term. */
    static <T> T onLargeStack(Supplier<T> work) throws InterruptedException {
        return onStack(LARGE_STACK, work);
    }

    private static <T> T onStack(long size, Supplier<T> work) throws InterruptedException {
        var result = new AtomicReference<T>();
        var failure = new AtomicReference<Throwable>();
        var thread = new Thread(null, () -> {
            try {
                result.set(work.get());
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "stack-" + size, size);
        thread.start();
        thread.join();
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }
}
