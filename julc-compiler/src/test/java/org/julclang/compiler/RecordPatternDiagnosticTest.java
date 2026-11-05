package org.julclang.compiler;

import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The record pattern errors name the developer's own record and fields, and their rewrite compiles and means the same. */
class RecordPatternDiagnosticTest {

    private static final String TYPES = """
                sealed interface S permits A, P {}
                record A(BigInteger v) implements S {}
                record P(BigInteger x, BigInteger y) implements S {}
            """;

    private static String method(String declarations, String parameter, String body) {
        return SwitchGuardTest.probe(declarations + "static BigInteger m(BigInteger " + parameter + ") { S s = new A(" + parameter + "); " + body + " }");
    }

    private static CompilerDiagnostic rejected(String source, String code) {
        var error = assertThrows(CompilerException.class,
                () -> new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions()).compileMethod(source, "m"), source);
        var diagnostic = error.diagnostics().getFirst();
        assertEquals(code, diagnostic.code(), diagnostic.message());
        return diagnostic;
    }

    private static String switchFix(String label) {
        var source = method(TYPES, "n", "return switch (s) { " + label + " -> BigInteger.ONE; default -> BigInteger.ZERO; };");
        return rejected(source, "JULC0076").suggestion();
    }

    @Test
    void theMessageNamesThePatternAndNoInternalDocument() {
        var source = method(TYPES, "n", "return switch (s) { case A(var v) -> v; default -> BigInteger.ZERO; };");
        var diagnostic = rejected(source, "JULC0076");
        assertEquals("Unsupported case pattern 'A(var v)': JuLC does not support record patterns yet", diagnostic.message());
        assertFalse(diagnostic.suggestion().contains("ADR"), diagnostic.suggestion());
        assertFalse(diagnostic.suggestion().contains("default"), diagnostic.suggestion());
    }

    @Test
    void theFixNamesTheRecordAndItsField() {
        assertEquals("Bind the record and read its fields: write `case A a`, then use `a.v()` for `v`.", switchFix("case A(var v)"));
    }

    @Test
    void aVariableNamedOtherwiseStillReadsTheField() {
        assertEquals("Bind the record and read its fields: write `case A a`, then use `a.v()` for `amount`.", switchFix("case A(var amount)"));
    }

    @Test
    void anExplicitTypeAndGuardReadTheSame() {
        assertEquals("Bind the record and read its fields: write `case A a`, then use `a.v()` for `v`.",
                switchFix("case A(BigInteger v) when v.signum() > 0"));
    }

    @Test
    void everyFieldIsListed() {
        assertEquals("Bind the record and read its fields: write `case P p`, then use `p.x()` for `first`, `p.y()` for `second`.",
                switchFix("case P(var first, var second)"));
    }

    @Test
    void aNestedPatternIsATestTheFixKeeps() {
        var source = method(TYPES + "record W(A inner) {} ", "n",
                "W w = new W(new A(n)); if (w instanceof W(A(var deep))) { return deep; } return BigInteger.ZERO;");
        assertTrue(rejected(source, "JULC0077").suggestion().contains(
                "write `w instanceof W w2`, then check that `w2.inner()` matches `A(var deep)`"));
    }

    /** Three reviews' counterexample: case W(A x) tests the field, so accessors alone would take a B as well. */
    @Test
    void aFieldTypeThePatternNarrowsIsATestTheFixKeeps() {
        var source = method(TYPES + "sealed interface I permits W {} record W(S inner) implements I {} ", "n",
                "I w = new W(new P(n, n)); return switch (w) { case W(A x) -> BigInteger.ONE; default -> BigInteger.ZERO; };");
        var fix = rejected(source, "JULC0076").suggestion();
        assertTrue(fix.contains("then check that `w2.inner()` matches `A x`"), fix);
        assertTrue(fix.contains("does not match must go to what the next case does"), fix);
        assertFalse(fix.contains("for `x`"), fix);
    }

    @Test
    void aTypeTheFieldAlwaysFitsReadsWithoutATest() {
        var source = method(TYPES + "sealed interface I permits W {} record W(A inner) implements I {} ", "n",
                "I w = new W(new A(n)); return switch (w) { case W(A x) -> BigInteger.ONE; };");
        assertTrue(rejected(source, "JULC0076").suggestion().contains("then use `w2.inner()` for `x`"));
    }

    @Test
    void aQualifiedRecordKeepsItsQualifier() {
        var source = method("sealed interface S permits N.R, Q {} record Q(BigInteger v) implements S {} static class N { record R(BigInteger v) implements S {} } ",
                "n", "return BigInteger.ZERO; } static BigInteger k(S t) { return switch (t) { case N.R(var v) -> v; default -> BigInteger.ZERO; };")
                .replace("S s = new A(n); ", "");
        var fix = rejected(source, "JULC0076").suggestion();
        assertTrue(fix.contains("write `case N.R r`, then use `r.v()` for `v`"), fix);
    }

    @Test
    void aGenericRecordKeepsItsTypeArguments() {
        var source = SwitchGuardTest.probe("""
                    static BigInteger m(org.julclang.core.types.Tuple2<BigInteger, BigInteger> t) {
                        if (t instanceof org.julclang.core.types.Tuple2<BigInteger, BigInteger>(var a, var b)) { return a; }
                        return BigInteger.ZERO;
                    }
                """);
        var fix = rejected(source, "JULC0077").suggestion();
        assertTrue(fix.contains("`t instanceof org.julclang.core.types.Tuple2<BigInteger, BigInteger> tuple2`, then use `tuple2.first()` for `a`, `tuple2.second()` for `b`"), fix);
    }

    @Test
    void aRawGenericRecordKeepsTheCataloguesFix() {
        var source = SwitchGuardTest.probe("""
                    static BigInteger m(org.julclang.core.types.Tuple2<BigInteger, BigInteger> t) {
                        if (t instanceof org.julclang.core.types.Tuple2(var a, var b)) { return BigInteger.ONE; }
                        return BigInteger.ZERO;
                    }
                """);
        assertTrue(rejected(source, "JULC0077").suggestion().startsWith("Use a type pattern"));
    }

    @Test
    void theBindingAvoidsAParameterAndAField() {
        var parameter = method(TYPES, "a", "return switch (s) { case A(var v) -> v; default -> BigInteger.ZERO; };");
        assertTrue(rejected(parameter, "JULC0076").suggestion().contains("`case A a2`, then use `a2.v()`"));
        var field = method(TYPES + "static BigInteger a = BigInteger.ONE; ", "n", "return switch (s) { case A(var v) -> v; default -> a; };");
        assertTrue(rejected(field, "JULC0076").suggestion().contains("`case A a2`"));
    }

    @Test
    void theBindingIsNeverAKeyword() {
        var source = method(TYPES + "sealed interface K permits Case, Other {} record Case(BigInteger v) implements K {} record Other(BigInteger v) implements K {} ",
                "n", "K k = new Case(n); return switch (k) { case Case(var v) -> v; case Other o -> o.v(); };");
        assertTrue(rejected(source, "JULC0076").suggestion().contains("`case Case case2`"));
    }

    @Test
    void instanceofGetsTheSameFix() {
        var source = method(TYPES, "n", "if (s instanceof A(var v)) { return v; } return BigInteger.ZERO;");
        var diagnostic = rejected(source, "JULC0077");
        assertEquals("Unsupported instanceof pattern: s instanceof A(var v)", diagnostic.message());
        assertEquals("Bind the record and read its fields: write `s instanceof A a`, then use `a.v()` for `v`."
                + " JuLC binds the variable only when the `instanceof` is the whole condition of an `if`.", diagnostic.suggestion());
    }

    @Test
    void aPatternThatIsNotARecordPatternKeepsTheCataloguesFix() {
        var source = method(TYPES, "n", "return switch (s) { case S t -> BigInteger.ONE; };");
        assertTrue(rejected(source, "JULC0076").suggestion().startsWith("Name a permitted variant"));
    }

    @Test
    void theRewriteTheFixDescribesCompilesAndKeepsTheValue() {
        var source = method(TYPES, "n", "return switch (s) { case A a -> a.v(); default -> BigInteger.ZERO; };");
        var program = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions()).compileMethod(source, "m").program();
        var result = CompilerTestVm.pv11().evaluateWithArgs(program, List.<PlutusData>of(new PlutusData.IntData(BigInteger.valueOf(-3))));
        assertEquals(new Term.Const(Constant.integer(BigInteger.valueOf(-3))), ((EvalResult.Success) result).resultTerm());
    }

    /** The rewrite the narrowing fix describes, with its test kept, takes the same branch as the pattern on a non-matching field. */
    @Test
    void theRewriteOfANarrowingPatternKeepsItsTest() {
        var source = SwitchGuardTest.probe(TYPES + """
                    sealed interface I permits W {}
                    record W(S inner) implements I {}
                    static BigInteger m(BigInteger n) {
                        I w = new W(new P(n, n));
                        return switch (w) {
                            case W w2 -> {
                                if (w2.inner() instanceof A x) { yield BigInteger.ONE; }
                                yield BigInteger.ZERO;
                            }
                        };
                    }
                """);
        var program = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions()).compileMethod(source, "m").program();
        var result = CompilerTestVm.pv11().evaluateWithArgs(program, List.<PlutusData>of(new PlutusData.IntData(BigInteger.TWO)));
        assertEquals(new Term.Const(Constant.integer(BigInteger.ZERO)), ((EvalResult.Success) result).resultTerm());
    }

    /** Longer than a location's display fragment: a rewrite to copy has to hold the whole type and operand. */
    @Test
    void aLongTypeAndOperandAreWrittenInFull() {
        String type = "org.julclang.core.types.Tuple2<org.julclang.core.types.JulcList<java.math.BigInteger>, java.math.BigInteger>";
        var source = SwitchGuardTest.probe("""
                    static BigInteger m(%s t) {
                        if (t instanceof %s(var a, var b)) { return b; }
                        return BigInteger.ZERO;
                    }
                """.formatted(type, type));
        var fix = rejected(source, "JULC0077").suggestion();
        assertTrue(fix.contains("`t instanceof " + type + " tuple2`"), fix);
        assertFalse(fix.contains("..."), fix);
    }

    @Test
    void aLongNestedPatternIsWrittenInFull() {
        var longName = "Component".repeat(14);
        var source = method(TYPES + "sealed interface I permits W {} record W(S inner) implements I {} ", "n",
                "I w = new W(new A(n)); return switch (w) { case W(A " + longName + ") -> BigInteger.ONE; default -> BigInteger.ZERO; };");
        var fix = rejected(source, "JULC0076").suggestion();
        assertTrue(fix.contains("matches `A " + longName + "`"), fix);
    }

    /** A plain type test has no pattern node, and keeps its position. */
    @Test
    void aPlainTypeTestKeepsItsLocation() {
        var source = """
                import java.math.BigInteger;
                public class Probe {
                    sealed interface S permits A, B {}
                    record A() implements S {}
                    record B() implements S {}
                    static boolean m(S s) {
                        return s instanceof A;
                    }
                }
                """;
        var diagnostic = rejected(source, "JULC0077");
        assertEquals("Probe.java", diagnostic.fileName());
        assertEquals(7, diagnostic.line());
        assertTrue(diagnostic.excerpt().contains("return s instanceof A;"), diagnostic.toString());
    }
}
