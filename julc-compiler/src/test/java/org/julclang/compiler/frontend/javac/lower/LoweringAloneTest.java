package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.codegen.StrictRecordEntrypoint;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.JavacFrontend;
import org.julclang.compiler.frontend.javac.JavacProgram;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirSubstitution;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.Constant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.lang.model.element.ElementKind;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Lowering} on its own, with no {@code SubsetScan} in front of it: what {@link Terms} records, which I13's check and
 * the source map depend on, and that lowering fails closed by itself. A compilation cannot show either: a check with
 * nothing recorded passes everything, and the scan stops a tree first.
 */
class LoweringAloneTest {

    private static void withMethod(String source, CompilerOptions options, BiConsumer<JavacFacts, Lowered> check) {
        var program = JavacProgram.analyze(List.of(JavacFrontend.Unit.of(source)), List.of());
        program.read(facts -> {
            var methods = new ArrayList<MethodTree>();
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitMethod(MethodTree tree, Void unused) {
                    if (tree.getName().contentEquals("m")) methods.add(tree);
                    return null;
                }
            }.scan(program.roots().getFirst(), null);
            check.accept(facts, new Lowered(methods.getFirst(), new Lowering(facts, CompilationContext.resolve(options))));
            return null;
        });
    }

    private record Lowered(MethodTree method, Lowering lowering) {
        PirTerm term() {
            return lowering.method(method, Scope.empty(), false);
        }

        VariableTree parameter(int index) {
            return method.getParameters().get(index);
        }
    }

    /** A read of a class's method is recorded too, or the check would pass it by its dot. */
    @Test
    void aMethodReadIsRecordedForItsMethod() {
        withMethod("class P { static long h(long a) { return a; } static long m(long a) { return h(a); } }", new CompilerOptions(), (facts, lowered) -> {
            var body = ((PirTerm.Lam) lowered.term()).body();
            var read = (PirTerm.Var) ((PirTerm.App) body).function();
            assertEquals("h", lowered.lowering().terms().provenance().readOf(read).getSimpleName().toString());
        });
    }

    /** If nothing were recorded, I13's check would pass every term: it reads this table and nothing else. */
    @Test
    void everyVarAndBinderOfASourceElementIsRecorded() {
        withMethod("class P { static long m(long a, long b) { return b; } }", new CompilerOptions(), (facts, lowered) -> {
            var outer = assertInstanceOf(PirTerm.Lam.class, lowered.term());
            var inner = assertInstanceOf(PirTerm.Lam.class, outer.body());
            var read = assertInstanceOf(PirTerm.Var.class, inner.body());
            var provenance = lowered.lowering().terms().provenance();
            assertSame(facts.element(lowered.parameter(0)), provenance.binderOf(outer, "a"));
            assertSame(facts.element(lowered.parameter(1)), provenance.binderOf(inner, "b"));
            assertSame(facts.element(lowered.parameter(1)), provenance.readOf(read));
            assertEquals(3, provenance.size());
        });
    }

    /**
     * The two sides of the check come from two places: the read from what javac bound the name to, the binder from the
     * scope. A wrong lookup makes them differ, with no table touched: a {@code Var} of the outer {@code K}'s binder, built
     * for the parameter {@code K}, runs and returns the wrong value unless this check stops it.
     */
    @Test
    void aWrongLookupFailsTheCheck() {
        withMethod("class P { static long K = 1; static long m(long K) { return K; } }", new CompilerOptions(), (facts, lowered) -> {
            var field = facts.elements().getTypeElement("P").getEnclosedElements().stream()
                    .filter(e -> e.getSimpleName().contentEquals("K") && e.getKind() == ElementKind.FIELD).findFirst().orElseThrow();
            var parameter = facts.element(lowered.parameter(0));
            var type = new PirType.IntegerType();
            var outer = Scope.empty().bind(field, type);
            var inner = outer.bind(parameter, type);
            var terms = lowered.lowering().terms();

            var right = new PirTerm.Let("K", new PirTerm.Const(Constant.integer(1)),
                    terms.lam(inner.lookup(parameter), terms.var(parameter, inner.lookup(parameter))));
            terms.assembled(right, "K", field);
            BindingCheck.check(right, terms.provenance());

            var wrong = new PirTerm.Let("K", new PirTerm.Const(Constant.integer(1)),
                    terms.lam(inner.lookup(parameter), terms.var(parameter, inner.lookup(field))));
            terms.assembled(wrong, "K", field);
            var failure = assertThrows(IllegalStateException.class, () -> BindingCheck.check(wrong, terms.provenance()));
            assertTrue(failure.getMessage().startsWith("I13"), failure.getMessage());
        });
    }

    private static PirType.IntegerType integer() {
        return new PirType.IntegerType();
    }

    /**
     * I13 with a continuation: what follows the {@code if} reads the static, and sits inside the branch that declares a
     * local of its name. Built once in the enclosing scope, so the local takes a mark instead of capturing the read.
     */
    @Test
    void aBranchLocalTakesAMarkSoWhatFollowsReadsTheStatic() {
        withMethod("class P { static final long K = 1; static long m(long a) { if (a > 0) { long K = a; if (K > 5) { return K; } } return a + K; } }",
                new CompilerOptions(), (facts, lowered) -> {
                    var field = facts.elements().getTypeElement("P").getEnclosedElements().stream()
                            .filter(e -> e.getKind() == ElementKind.FIELD).findFirst().orElseThrow();
                    var scope = Scope.empty().bind(field, integer());
                    var terms = lowered.lowering().terms();
                    var body = (PirTerm.Lam) lowered.lowering().method(lowered.method(), scope, false);
                    var branch = (PirTerm.IfThenElse) body.body();
                    assertEquals("K'1", ((PirTerm.Let) branch.thenBranch()).name());
                    var bound = new PirTerm.Let("K", new PirTerm.Const(Constant.integer(1)), body);
                    terms.assembled(bound, "K", field);
                    BindingCheck.check(bound, terms.provenance());
                });
    }

    /** One binder name per element (I13): every copy of what follows an {@code if} is the one term, built once. */
    @Test
    void whatFollowsAnIfIsOneTerm() {
        withMethod("class P { static long m(long a) { if (a > 0) { if (a > 1) { return 1; } } long b = a; return b; } }",
                new CompilerOptions(), (facts, lowered) -> {
                    var outer = (PirTerm.IfThenElse) ((PirTerm.Lam) lowered.term()).body();
                    var inner = (PirTerm.IfThenElse) outer.thenBranch();
                    assertSame(outer.elseBranch(), inner.elseBranch());
                    assertInstanceOf(PirTerm.Let.class, outer.elseBranch());
                });
    }

    /**
     * No operand of a type a program can hold is outside the variant's interface yet: javac rejects the unrelated ones, and
     * {@code Object} comes only by a cast, which is not ported. When it is, this is the line that stops it: a tag compared
     * on what may be any value would be another answer.
     */
    @Test
    void anOperandOutsideTheVariantsInterfaceStops() {
        withMethod("class P { sealed interface S { record A() implements S { } } static Object O; static boolean m() { return O instanceof S.A a; } }",
                new CompilerOptions(), (facts, lowered) -> {
                    var field = facts.elements().getTypeElement("P").getEnclosedElements().stream()
                            .filter(e -> e.getKind() == ElementKind.FIELD).findFirst().orElseThrow();
                    var scope = Scope.empty().bind(field, new PirType.DataType());
                    assertEquals("JULC0126", assertThrows(CompilerException.class,
                            () -> lowered.lowering().method(lowered.method(), scope, false)).diagnostics().getFirst().code());
                });
    }

    /** What follows an {@code if} around a loop is bound once, as a function, however the loop stands in its branches (ADR-050). */
    @ParameterizedTest
    @ValueSource(strings = {"if (a > 0) { while (a > 0) { } }", "if (a > 0) { if (a > 1) { while (a > 0) { } } }",
            "if (a > 0) { if (a > 1) { } else { while (a > 0) { } } }", "if (a > 0) if (a > 1) { } else while (a > 0) { }"})
    void whatFollowsAnIfAroundALoopIsBoundOnce(String statement) {
        withMethod("class P { static long m(long a) { " + statement + " return a; } }", new CompilerOptions(), (facts, lowered) -> {
            var join = (PirTerm.Let) ((PirTerm.Lam) lowered.term()).body();
            assertInstanceOf(PirTerm.Lam.class, join.value());
            assertInstanceOf(PirTerm.IfThenElse.class, join.body());
            assertTrue(join.name().startsWith("#if-join-"), join.name());
        });
    }

    /**
     * The strict transform rebuilds every binder of a body whose root is a record, so what is recorded of each must follow
     * it to the copy. A {@code let} is built here as {@link Statements} builds it, by {@link Terms}.
     */
    @Test
    void aBinderKeepsItsProvenanceThroughTheStrictTransform() {
        withMethod("record R(long a) { } class P { static long m(R r, long y) { return y; } }", new CompilerOptions(), (facts, lowered) -> {
            var terms = lowered.lowering().terms();
            var root = facts.element(lowered.parameter(0));
            var local = facts.element(lowered.parameter(1));
            var rootType = facts.parameterType(lowered.parameter(0));
            var scope = Scope.empty().bind(root, rootType).bind(local, new PirType.IntegerType());
            var read = terms.var(local, scope.lookup(local));
            var binding = new PirTerm.Let(scope.lookup(local).name(), new PirTerm.Const(Constant.integer(1)), read);
            terms.assembled(binding, binding.name(), local);
            var function = terms.lam(scope.lookup(root), binding);
            BindingCheck.check(function, terms.provenance());

            var roots = List.of(new StrictRecordEntrypoint.Root(scope.lookup(root).name(), rootType));
            var definitions = facts.registry().namedDefinitions();
            var kept = StrictRecordEntrypoint.transform(function, roots, definitions, terms.provenance().rebuildsOf(function));
            BindingCheck.check(kept.function(), terms.provenance());

            var lost = StrictRecordEntrypoint.transform(function, roots, definitions);
            assertThrows(IllegalStateException.class, () -> BindingCheck.check(lost.function(), terms.provenance()),
                    "with nothing told, the check sees the copies as binders of generated names");
        });
    }

    @Test
    void positionsAreRecordedOnlyForASourceMap() {
        String source = "class P {\n    static long m(long a) {\n        return a;\n    }\n}\n";
        withMethod(source, new CompilerOptions(), (facts, lowered) -> {
            lowered.term();
            assertTrue(lowered.lowering().terms().positions().isEmpty());
        });
        withMethod(source, new CompilerOptions().setSourceMapEnabled(true), (facts, lowered) -> {
            var term = (PirTerm.Lam) lowered.term();
            var positions = lowered.lowering().terms().positions();
            assertEquals(2, positions.get(term).line(), "the method");
            assertEquals(3, positions.get(term.body()).line(), "the return");
            assertEquals(9, positions.get(term.body()).column());
        });
    }

    /** The scan stops these first in a compilation; lowering must still not lower what it does not know. */
    @Test
    void anArrayOtherThanALiteralByteArrayIsNotPortedHereToo() {
        withMethod("class P { static byte[] m() { return new byte[2]; } }", new CompilerOptions(), (facts, lowered) ->
                assertEquals("JULC0014", assertThrows(CompilerException.class, lowered::term).diagnostics().getFirst().code()));
        withMethod("class P { static byte[] m(boolean b) { return b ? new byte[2] : new byte[]{1}; } }", new CompilerOptions(), (facts, lowered) ->
                assertEquals("JULC0014", assertThrows(CompilerException.class, lowered::term).diagnostics().getFirst().code()));
    }

    /** Each of these is the subset's rule in a compilation; lowering must still build no term on what the rule stops. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "static String m(String a) { return a + 1; }                         | JULC0090",
            "static String m(String a) { return 1 + a; }                         | JULC0090"})
    void whatTheSubsetRejectsIsNotLoweredHereToo(String method, String code) {
        withMethod("class P { " + method + " }", new CompilerOptions(), (facts, lowered) ->
                assertEquals(code, assertThrows(CompilerException.class, lowered::term).diagnostics().getFirst().code()));
    }

    /** On an array {@code equals} is the scan's {@code JULC0087}, and the lowering's too, in the scan's words. */
    @Test
    void anEqualsOnAnArrayIsTheScansRejectionHereToo() {
        withMethod("class P { static boolean m(byte[] a, byte[] b) { return a.equals(b); } }", new CompilerOptions(), (facts, lowered) -> {
            var diagnostic = assertThrows(CompilerException.class, lowered::term).diagnostics().getFirst();
            assertEquals("JULC0087", diagnostic.code());
            assertEquals("Object.equals compares byte[], whose equality on the JVM compares an array by identity", diagnostic.message());
        });
    }

    /** A char and a variant of two sealed parents are the scan's codes, and the lowering's too, so no term holds one. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "static long m() { long c = 'x'; return c; }                                                       | JULC0125",
            "static boolean m(java.math.BigInteger a) { S s = new B(a); return true; }                          | JULC0079",
            "static boolean m(S s) { return s instanceof B b; }                                                 | JULC0079",
            "static long m(S s) { return switch (s) { case A a -> 1; case B b -> 2; }; }                         | JULC0079"})
    void whatTheScanCodesIsCodedHereToo(String method, String code) {
        var types = "sealed interface S permits A, B { } sealed interface T permits B { } record A(java.math.BigInteger v) implements S { }"
                + " record B(java.math.BigInteger v) implements S, T { } ";
        withMethod("class P { " + types + method + " }", new CompilerOptions(), (facts, lowered) ->
                assertEquals(code, assertThrows(CompilerException.class, lowered::term).diagnostics().getFirst().code()));
    }

    /** D6's rule is the lowering's too, so no {@code ==} on references reaches a term, read by the scan or not. */
    @Test
    void anIdentityComparisonIsNotLoweredHereToo() {
        withMethod("class P { static boolean m(java.math.BigInteger a) { return a == a; } }", new CompilerOptions(), (facts, lowered) ->
                assertEquals("JULC0086", assertThrows(CompilerException.class, lowered::term).diagnostics().getFirst().code()));
    }

    /** A generic library method is a template, which Java cannot call (ADR-059): the reference's rejection, in its words. */
    @Test
    void aGenericLibraryMethodIsRejected() {
        var root = "import lib.L;\nclass P { static long m() { return L.f(1L); } }\n";
        var library = "package lib;\npublic class L { public static <T> T f(T a) { return a; } }\n";
        var program = JavacProgram.analyze(List.of(JavacFrontend.Unit.of(root)), List.of(JavacFrontend.Unit.of(library)));
        program.read(facts -> {
            var methods = new ArrayList<MethodTree>();
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitMethod(MethodTree tree, Void unused) {
                    if (tree.getName().contentEquals("m")) methods.add(tree);
                    return null;
                }
            }.scan(program.roots().getFirst(), null);
            var lowering = new Lowering(facts, CompilationContext.resolve(new CompilerOptions()));
            var failure = assertThrows(CompilerException.class, () -> lowering.method(methods.getFirst(), Scope.empty(), false));
            assertTrue(failure.getMessage().contains("Generic library method lib.L.f cannot be called from Java yet"), failure::getMessage);
            return null;
        });
    }

    /** ADR-060: a decoded parameter keeps its name, the {@code let} reads the {@code Lam} under it, and each is built for the element. */
    @Test
    void aDecodedParameterIsOneNameOverTwoBinders() {
        withMethod("""
                import java.math.BigInteger;
                import org.julclang.core.types.JulcList;
                class C {
                    static boolean m(JulcList<BigInteger> xs) { return xs.any(x -> x.signum() > 0); }
                }
                """, new CompilerOptions(), (facts, lowered) -> {
            var term = lowered.term();
            var names = new ArrayList<String>();
            new Object() {
                void walk(PirTerm at) {
                    switch (at) {
                        case PirTerm.Lam lam -> {
                            if (lam.param().equals("x")) {
                                var let = assertInstanceOf(PirTerm.Let.class, lam.body());
                                names.add(let.name());
                                assertEquals(new PirType.DataType(), lam.paramType());
                                var read = assertInstanceOf(PirTerm.Var.class, assertInstanceOf(PirTerm.App.class, let.value()).argument());
                                assertEquals(new PirType.DataType(), read.type(), "the let reads the Lam, which holds Data");
                                assertTrue(PirSubstitution.collectFreeVarNames(let.value()).contains("x"));
                            }
                            walk(lam.body());
                        }
                        case PirTerm.LetRec rec -> {
                            rec.bindings().forEach(binding -> walk(binding.value()));
                            walk(rec.body());
                        }
                        case PirTerm.App app -> {
                            walk(app.function());
                            walk(app.argument());
                        }
                        case PirTerm.IfThenElse branch -> {
                            walk(branch.cond());
                            walk(branch.thenBranch());
                            walk(branch.elseBranch());
                        }
                        default -> { }
                    }
                }
            }.walk(term);
            assertEquals(List.of("x"), names);
            BindingCheck.check(term, lowered.lowering().terms().provenance());
        });
    }

    /** The method is the API's and the receiver is not what its handler lowers, so the key must agree with both. */
    @Test
    void aSubtypeOfAnApiTypeDoesNotDispatch() {
        withMethod("""
                class P {
                    sealed interface S extends org.julclang.core.types.JulcArray<java.math.BigInteger> permits A { }
                    record A(java.math.BigInteger x) implements S {
                        public java.math.BigInteger get(long index) { return x; }
                        public long length() { return 1; }
                    }
                    static long m(S s) { return s.length(); }
                }
                """, new CompilerOptions(), (facts, lowered) -> assertEquals("JULC0055",
                assertThrows(CompilerException.class, lowered::term).diagnostics().getFirst().code()));
    }

    /** A field's initializer, where a type the subset rejects has not stopped the lowering first. */
    @ParameterizedTest
    @ValueSource(strings = {"long[] f = new long[]{1};", "byte[] f = {1};", "byte[][] f = new byte[][]{};"})
    void anInitializerThatIsNotALiteralByteArrayIsNotPorted(String field) {
        var program = JavacProgram.analyze(List.of(JavacFrontend.Unit.of("class P { static " + field + " }")), List.of());
        program.read(facts -> {
            var initializers = new ArrayList<ExpressionTree>();
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitVariable(VariableTree tree, Void unused) {
                    if (tree.getName().contentEquals("f")) initializers.add(tree.getInitializer());
                    return null;
                }
            }.scan(program.roots().getFirst(), null);
            var lowering = new Lowering(facts, CompilationContext.pv11Defaults());
            assertEquals("JULC0014", assertThrows(CompilerException.class,
                    () -> lowering.initializer(initializers.getFirst(), Scope.empty())).diagnostics().getFirst().code());
            return null;
        });
    }

    @Test
    void anOuterScopeIsKept() {
        withMethod("class P { static long K = 1; static long m(long K) { return K; } }", new CompilerOptions(), (facts, lowered) -> {
            var field = facts.elements().getTypeElement("P").getEnclosedElements().stream()
                    .filter(e -> e.getSimpleName().contentEquals("K")).findFirst().orElseThrow();
            var term = (PirTerm.Lam) lowered.lowering().method(lowered.method(), Scope.empty().bind(field, new PirType.IntegerType()), false);
            assertEquals("K'1", term.param(), "the static's binder is visible, so the parameter takes the mark (I13)");
            assertEquals("K'1", ((PirTerm.Var) term.body()).name());
        });
    }
}
