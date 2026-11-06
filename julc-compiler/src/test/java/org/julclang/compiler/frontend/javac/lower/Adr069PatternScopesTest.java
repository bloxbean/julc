package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.BindingPatternTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.TreeScanner;
import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.frontend.javac.JavacFrontend;
import org.julclang.compiler.frontend.javac.JavacProgram;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.lang.model.element.Element;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-069 in the compiler alone: I1's check on terms built by hand, so each way of breaking it is seen to fail, and the
 * diagnostics of D6 and D8 with their words. Agreement with the JVM is {@code Adr069PatternScopesHarnessTest}'s.
 */
class Adr069PatternScopesTest {

    private static final String SUM = """
            import java.math.BigInteger;
            class C {
                sealed interface S { record A(BigInteger v) implements S { } record B(BigInteger v, BigInteger w) implements S { } }
                %s
            }
            """;

    // --- I1's check ---

    /** The element of the pattern variable {@code a} in {@code static boolean m(S s) { return s instanceof S.A a; }}. */
    private static void withVariable(Consumer<Element> check) {
        var program = JavacProgram.analyze(List.of(JavacFrontend.Unit.of(SUM.formatted("static boolean m(S s) { return s instanceof S.A a; }"))), List.of());
        program.read(facts -> {
            var patterns = new ArrayList<BindingPatternTree>();
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitBindingPattern(BindingPatternTree tree, Void unused) {
                    patterns.add(tree);
                    return null;
                }
            }.scan(program.roots().getFirst(), null);
            check.accept(facts.element(patterns.getFirst().getVariable()));
            return null;
        });
    }

    private static PirTerm tagTest(PirTerm operand, long tag) {
        var fields = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnConstrData), operand);
        return new PirTerm.App(new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger), new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), fields)),
                new PirTerm.Const(Constant.integer(BigInteger.valueOf(tag))));
    }

    private static final PirTerm.Var S = new PirTerm.Var("s", new PirType.DataType());
    private static final PirTerm.Var OTHER = new PirTerm.Var("t", new PirType.DataType());
    private static final PirTerm BODY = new PirTerm.Const(Constant.bool(true));
    private static final PirTerm ELSE = new PirTerm.Const(Constant.bool(false));

    /** {@code let a = value in body}, recorded as an introduction of {@code a} for {@code s} at tag 0. */
    private static PirTerm.Let introduction(Provenance provenance, Element a, PirTerm value, PirTerm body) {
        var let = new PirTerm.Let("a", value, body);
        provenance.binder(let, "a", a);
        provenance.introduction(let, new Provenance.Introduction(a, S, BigInteger.ZERO));
        return let;
    }

    @Test
    void anIntroductionUnderItsTestPasses() {
        withVariable(a -> {
            var provenance = new Provenance();
            var term = new PirTerm.IfThenElse(tagTest(S, 0), introduction(provenance, a, S, BODY), ELSE);
            assertDoesNotThrow(() -> PatternBindingCheck.check(term, provenance));
        });
    }

    @Test
    void anIntroductionUnderAnotherTagFails() {
        withVariable(a -> {
            var provenance = new Provenance();
            var term = new PirTerm.IfThenElse(tagTest(S, 1), introduction(provenance, a, S, BODY), ELSE);
            assertI1(term, provenance);
        });
    }

    @Test
    void anIntroductionUnderAnotherOperandsTestFails() {
        withVariable(a -> {
            var provenance = new Provenance();
            var term = new PirTerm.IfThenElse(tagTest(OTHER, 0), introduction(provenance, a, S, BODY), ELSE);
            assertI1(term, provenance);
        });
    }

    @Test
    void anIntroductionOfAnotherTermFails() {
        withVariable(a -> {
            var provenance = new Provenance();
            var term = new PirTerm.IfThenElse(tagTest(S, 0), introduction(provenance, a, OTHER, BODY), ELSE);
            assertI1(term, provenance);
        });
    }

    @Test
    void anIntroductionInTheElseBranchFails() {
        withVariable(a -> {
            var provenance = new Provenance();
            var term = new PirTerm.IfThenElse(tagTest(S, 0), BODY, introduction(provenance, a, S, ELSE));
            assertI1(term, provenance);
        });
    }

    @Test
    void anIntroductionAboveItsTestFails() {
        withVariable(a -> {
            var provenance = new Provenance();
            var term = introduction(provenance, a, S, new PirTerm.IfThenElse(tagTest(S, 0), BODY, ELSE));
            assertI1(term, provenance);
        });
    }

    /** A loop's {@code let a = a} inside the introduction is a rebinding, and passes; outside every introduction it fails. */
    @Test
    void aRebindingMustLieInsideAnIntroduction() {
        withVariable(a -> {
            var provenance = new Provenance();
            var inside = new PirTerm.Let("a", new PirTerm.Var("a", new PirType.DataType()), BODY);
            provenance.binder(inside, "a", a);
            var term = new PirTerm.IfThenElse(tagTest(S, 0), introduction(provenance, a, S, inside), ELSE);
            assertDoesNotThrow(() -> PatternBindingCheck.check(term, provenance));

            var outside = new PirTerm.Let("a", S, BODY);
            provenance.binder(outside, "a", a);
            assertI1(new PirTerm.IfThenElse(ELSE, BODY, outside), provenance);
        });
    }

    private static void assertI1(PirTerm term, Provenance provenance) {
        var failure = assertThrows(IllegalStateException.class, () -> PatternBindingCheck.check(term, provenance));
        assertTrue(failure.getMessage().startsWith("I1"), failure.getMessage());
    }

    // --- D2's joins, on the term as lowering builds it ---

    /** The term {@code m} lowers to, alone, with no stdlib. */
    private static PirTerm lowered(String members) {
        var term = new PirTerm[1];
        var program = JavacProgram.analyze(List.of(JavacFrontend.Unit.of(SUM.formatted(members))), List.of());
        program.read(facts -> {
            var methods = new ArrayList<MethodTree>();
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitMethod(MethodTree tree, Void unused) {
                    if (tree.getName().contentEquals("m")) methods.add(tree);
                    return null;
                }
            }.scan(program.roots().getFirst(), null);
            term[0] = new Lowering(facts, CompilationContext.resolve(new CompilerOptions())).method(methods.getFirst(), Scope.empty(), false);
            return null;
        });
        return term[0];
    }

    /**
     * The names of the joins in {@code term}, and whether any node other than a variable is placed twice: a pattern test's
     * trivial operand is one {@code Var} read by its test and its {@code let}, as {@code patternIf} has always built it. The
     * programs compare with {@code equals}: some lowerings, {@code signum}'s, place a node of their own twice.
     */
    private record Shape(List<String> joins, List<String> shared) {
        boolean sharesANode() {
            return !shared.isEmpty();
        }

        static Shape of(PirTerm term) {
            var joins = new ArrayList<String>();
            var seen = Collections.newSetFromMap(new IdentityHashMap<PirTerm, Boolean>());
            var shared = new ArrayList<String>();
            walk(term, joins, seen, shared);
            return new Shape(joins, shared);
        }

        private static void walk(PirTerm term, List<String> joins, Set<PirTerm> seen, List<String> shared) {
            if (!seen.add(term) && !(term instanceof PirTerm.Var)) shared.add(term.toString());
            switch (term) {
                case PirTerm.Let let -> {
                    if (let.name().startsWith("#cond-join-")) joins.add(let.name());
                    walk(let.value(), joins, seen, shared);
                    walk(let.body(), joins, seen, shared);
                }
                case PirTerm.Lam lam -> walk(lam.body(), joins, seen, shared);
                case PirTerm.App app -> {
                    walk(app.function(), joins, seen, shared);
                    walk(app.argument(), joins, seen, shared);
                }
                case PirTerm.IfThenElse branch -> {
                    walk(branch.cond(), joins, seen, shared);
                    walk(branch.thenBranch(), joins, seen, shared);
                    walk(branch.elseBranch(), joins, seen, shared);
                }
                default -> { }
            }
        }
    }

    /** A trivial continuation that both paths of {@code &&} reach is copied, never joined, and never one node twice. */
    @Test
    void aTrivialSharedContinuationIsCopied() {
        var shape = Shape.of(lowered("static BigInteger m(S s) { if (s instanceof S.A a && a.v().equals(BigInteger.ONE)) { return a.v(); } else { return BigInteger.ZERO; } }"));
        assertEquals(List.of(), shape.joins());
        assertFalse(shape.sharesANode(), shape.shared()::toString);
    }

    /**
     * Any other is lowered once and bound once as a join, whatever the number of paths: the else branch, which both
     * operands of {@code &&} reach, and the then-branch, which both operands of the inner {@code ||} reach.
     */
    @Test
    void aSharedContinuationIsJoinedOnce() {
        var shape = Shape.of(lowered("static BigInteger m(S s) { if (s instanceof S.A a && (a.v().equals(BigInteger.ONE) || a.v().equals(BigInteger.TWO))) { return a.v(); } "
                + "else { return BigInteger.TEN.add(BigInteger.TWO); } }"));
        assertEquals(List.of("#cond-join-1", "#cond-join-2"), shape.joins());
        assertFalse(shape.sharesANode(), shape.shared()::toString);
    }

    /** I7: sibling joins (a boolean ?: as a condition) and nested ones have distinct names. */
    @Test
    void joinsHaveDistinctNames() {
        var shape = Shape.of(lowered("static BigInteger m(S s, BigInteger t) { if ((s instanceof S.A a ? a.v().equals(t) : s instanceof S.B b && b.w().equals(t)) "
                + "&& t.equals(BigInteger.TEN)) { return BigInteger.TEN.add(BigInteger.ONE); } return BigInteger.TEN.add(BigInteger.TWO); }"));
        assertTrue(shape.joins().size() >= 2, shape.joins()::toString);
        assertEquals(shape.joins().size(), Set.copyOf(shape.joins()).size(), shape.joins()::toString);
        assertFalse(shape.sharesANode(), shape.shared()::toString);
    }

    // --- D6 and D8: the words ---

    private static CompilerException rejected(String members) {
        return assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions())
                .compileMethod(SUM.formatted(members), "m", List.of()));
    }

    @Test
    void aVariableAfterItsWhileNamesTheLoopAndTheTest() {
        var diagnostic = rejected("static BigInteger m(S s) { S x = s; while (!(x instanceof S.A a)) { x = new S.A(BigInteger.ONE); } return a.v(); }")
                .diagnostics().getFirst();
        assertEquals("JULC0139", diagnostic.code());
        assertEquals("'a' is in scope here in Java, but JuLC does not bind a pattern variable after the while loop that tests it", diagnostic.message());
        // The first test drops the variable: declaring it again where javac still has it in scope would not compile.
        assertEquals("Test without the variable where it is declared, x instanceof S.A, and test again with it where you use it: "
                + "if (x instanceof S.A a) { ... }", diagnostic.suggestion());
    }

    @Test
    void aVariableInItsWhileBodyNamesTheBody() {
        var diagnostic = rejected("static BigInteger m(S s) { S x = s; BigInteger t = BigInteger.ZERO; "
                + "while (x instanceof S.B b) { t = t.add(b.w()); x = new S.A(BigInteger.ONE); } return t; }").diagnostics().getFirst();
        assertEquals("JULC0139", diagnostic.code());
        assertTrue(diagnostic.message().endsWith("in the body of the while loop whose condition tests it"), diagnostic.message());
    }

    /** I5: a pattern variable javac resolved and JuLC did not bind is JULC0139, never an undefined variable. */
    @ParameterizedTest
    @ValueSource(strings = {
            "static BigInteger m(S s) { BigInteger t = BigInteger.ZERO; while (s instanceof S.A a) { t = t.add(a.v()); s = new S.B(t, t); } return t; }",
            "static BigInteger m(S s) { while (!(s instanceof S.A a)) { s = new S.A(BigInteger.ONE); } return a.v(); }",
    })
    void anUnboundVariableIsNeverUndefined(String members) {
        var diagnostic = rejected(members).diagnostics().getFirst();
        assertEquals("JULC0139", diagnostic.code(), diagnostic.message());
        assertEquals("Test without the variable where it is declared, s instanceof S.A, and test again with it where you use it: "
                + "if (s instanceof S.A a) { ... }", diagnostic.suggestion());
    }

    /** D8 rule 4: an assignment to a pattern variable outside a loop. The loop forms are the harness's. */
    @Test
    void aReassignedVariableIsJulc0140() {
        var diagnostic = rejected("static BigInteger m(S s) { if (s instanceof S.A a) { a = new S.A(BigInteger.ONE); return a.v(); } "
                + "return BigInteger.ZERO; }").diagnostics().getFirst();
        assertEquals("JULC0140", diagnostic.code(), diagnostic.message());
        assertEquals("Reassigning pattern variable 'a' is not supported", diagnostic.message());
        assertEquals("Declare a new local with the value you need instead of assigning to a: S.A a2 = ...;", diagnostic.suggestion());
    }

    /** An operand that is no variable would run again in a second test: the fix stores it first. */
    @Test
    void anUnboundVariableOfACallSaysStoreItFirst() {
        var diagnostic = rejected("static S pick(S s) { return s; } static BigInteger m(S s) { while (!(pick(s) instanceof S.A a)) { s = new S.A(BigInteger.ONE); } "
                + "return a.v(); }")
                .diagnostics().getFirst();
        assertEquals("JULC0139", diagnostic.code(), diagnostic.message());
        assertTrue(diagnostic.suggestion().startsWith("Store pick(s) in a local"), diagnostic.suggestion());
    }
}
