package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.JavacFrontend;
import org.julclang.compiler.frontend.javac.JavacProgram;
import org.julclang.compiler.frontend.javac.NotPorted;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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

    /** If nothing were recorded, I13's check would pass every term: it reads this table and nothing else. */
    @Test
    void everyVarAndBinderOfASourceElementIsRecorded() {
        withMethod("class P { static long m(long a, long b) { return b; } }", new CompilerOptions(), (facts, lowered) -> {
            var outer = assertInstanceOf(PirTerm.Lam.class, lowered.term());
            var inner = assertInstanceOf(PirTerm.Lam.class, outer.body());
            var read = assertInstanceOf(PirTerm.Var.class, inner.body());
            var provenance = lowered.lowering().terms().provenance();
            assertSame(facts.element(lowered.parameter(0)), provenance.get(outer));
            assertSame(facts.element(lowered.parameter(1)), provenance.get(inner));
            assertSame(facts.element(lowered.parameter(1)), provenance.get(read));
            assertEquals(3, provenance.size());
        });
    }

    @Test
    void whatTheCheckReadsMakesItFailOnARealTerm() {
        withMethod("class P { static long m(long a, long b) { return b; } }", new CompilerOptions(), (facts, lowered) -> {
            var term = lowered.term();
            var provenance = lowered.lowering().terms().provenance();
            BindingCheck.check(term, provenance);
            // The Var read b; say it was built for a, as a binding bug would.
            var inner = (PirTerm.Lam) ((PirTerm.Lam) term).body();
            provenance.put(inner.body(), facts.element(lowered.parameter(0)));
            assertThrows(IllegalStateException.class, () -> BindingCheck.check(term, provenance));
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
                assertEquals("NEW_ARRAY", assertThrows(NotPorted.class, lowered::term).construct()));
        withMethod("class P { static byte[] m(boolean b) { return b ? new byte[2] : new byte[]{1}; } }", new CompilerOptions(), (facts, lowered) ->
                assertEquals("NEW_ARRAY", assertThrows(NotPorted.class, lowered::term).construct()));
    }

    /** Each of these is the subset's rule in a compilation; lowering must still build no term on what the rule stops. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "static String m(String a) { return a + 1; }                         | a string + with another type",
            "static String m(String a) { return 1 + a; }                         | a string + with another type",
            "static boolean m(java.math.BigInteger a) { return a == a; }         | reference equality",
            "static boolean m(byte[] a, byte[] b) { return a.equals(b); }        | an equals on an array"})
    void whatTheSubsetRejectsIsNotLoweredHereToo(String method, String construct) {
        withMethod("class P { " + method + " }", new CompilerOptions(), (facts, lowered) ->
                assertEquals(construct, assertThrows(NotPorted.class, lowered::term).construct()));
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
            assertEquals("NEW_ARRAY", assertThrows(NotPorted.class,
                    () -> lowering.initializer(initializers.getFirst(), Scope.empty())).construct());
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
