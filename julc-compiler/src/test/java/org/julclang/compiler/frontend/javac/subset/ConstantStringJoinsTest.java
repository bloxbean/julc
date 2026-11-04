package org.julclang.compiler.frontend.javac.subset;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.julclang.compiler.CompileResult;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.frontend.javac.JavacGateMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@code JULC0102}: one rule on javac's trees, so both frontends report it alike, and it changes no script. */
class ConstantStringJoinsTest {

    private static final String TEMPLATE = """
            class P {
                static final String K = "k";
                static String m(String s) { return %s; }
            }
            """;

    private static CompileResult compile(String expression, CompilerOptions options) {
        return new JulcCompiler(null, options).compileMethod(TEMPLATE.formatted(expression), "m", List.of());
    }

    private static List<CompilerDiagnostic> joins(CompileResult result) {
        return result.diagnostics().stream().filter(d -> "JULC0102".equals(d.code())).toList();
    }

    private static CompilerOptions javac() {
        return new CompilerOptions().setFrontend(CompilerFrontend.JAVAC);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "\"a\" + \"b\"                 | 1",
            "\"a\" + \"b\" + \"c\"           | 1",
            "\"a\" + (\"b\" + \"c\")         | 1",
            "(\"a\" + \"b\") + (\"c\" + \"d\") | 1",
            "(\"a\") + (\"b\")             | 1",
            "K + \"b\"                     | 1",
            "K + K                         | 1",
            "s + \"b\"                     | 0",
            "\"a\" + s                     | 0",
            "s + \"a\" + \"b\"               | 0",
            "(\"a\" + \"b\") + s             | 1",
            "(\"a\" + \"b\") + s + (\"c\" + \"d\") | 2",
            "\"a\"                         | 0"})
    void aJoinOfConstantsWarnsOnBothFrontends(String expression, int warnings) {
        var onDefault = joins(compile(expression, new CompilerOptions()));
        assertEquals(warnings, onDefault.size());
        assertEquals(onDefault, joins(compile(expression, javac())));
    }

    @Test
    void aValidatorWarnsOnBothFrontends() {
        var source = """
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.SpendingValidator;
                @SpendingValidator
                class V {
                    static String joined() { return "a" + "b"; }
                    @Entrypoint static boolean validate(PlutusData r, ScriptContext c) { return true; }
                }
                """;
        var onDefault = joins(new JulcCompiler(null, new CompilerOptions()).compile(source, List.of()));
        assertEquals(1, onDefault.size());
        assertEquals(onDefault, joins(new JulcCompiler(null, javac()).compile(source, List.of())));
    }

    @Test
    void itSaysWhereAndWhatToWrite() {
        var warning = joins(compile("\"a\" + \"b\"", new CompilerOptions())).getFirst();
        assertEquals(CompilerDiagnostic.Level.WARNING, warning.level());
        assertEquals(3, warning.line());
        assertEquals("Write one string literal.", warning.suggestion());
    }

    @Test
    void itChangesNoScript() {
        var off = compile("\"a\" + \"b\"", new CompilerOptions());
        var on = compile("\"a\" + \"b\"", new CompilerOptions());
        assertEquals(off.pirTerm(), on.pirTerm());
    }

    /** Only a {@code +} joins: the default frontend compiles this, and it is no join. */
    @Test
    void aComparisonOfConstantsDoesNotWarn() {
        var source = "class P { static boolean m() { return \"a\" == \"b\"; } }";
        assertEquals(List.of(), joins(new JulcCompiler(null, new CompilerOptions()).compileMethod(source, "m", List.of())));
    }

    @Test
    void anAnnotationsArgumentDoesNotWarn() {
        var source = """
                class P {
                    @SuppressWarnings("a" + "b")
                    static String m() { return "c"; }
                }
                """;
        assertEquals(List.of(), joins(new JulcCompiler(null, new CompilerOptions()).compileMethod(source, "m", List.of())));
    }

    /** A tree of {@code +}: its constant subtrees are what warns, each counted at its top, whatever the shape. */
    private record Join(String text, boolean constant, int warnings) {
        static Join of(Join left, Join right) {
            boolean constant = left.constant && right.constant;
            int warnings = constant ? 1 : outer(left) + outer(right);
            return new Join(left.text + " + " + (right.text.contains(" + ") ? "(" + right.text + ")" : right.text), constant, warnings);
        }

        private static int outer(Join join) {
            return join.warnings;
        }
    }

    @Provide
    Arbitrary<Join> joins() {
        Arbitrary<Join> leaves = Arbitraries.of(new Join("\"a\"", true, 0), new Join("K", true, 0), new Join("s", false, 0));
        return Arbitraries.recursive(() -> leaves, inner -> inner.flatMap(left -> leaves.flatMap(right ->
                Arbitraries.of(Join.of(left, right), Join.of(right, left)))), 0, 4);
    }

    @Property(tries = 60)
    void eachMaximalConstantJoinWarnsOnce(@ForAll("joins") Join join) {
        assertEquals(join.warnings(), joins(compile(join.text(), new CompilerOptions())).size(), join.text());
    }
}
