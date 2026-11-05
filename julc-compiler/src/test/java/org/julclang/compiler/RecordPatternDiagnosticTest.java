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
    void aNestedPatternIsReadTheSameWay() {
        var source = method(TYPES + "record W(A inner) {} ", "n",
                "W w = new W(new A(n)); if (w instanceof W(A(var deep))) { return deep; } return BigInteger.ZERO;");
        assertEquals("Bind the record and read its fields: write `w instanceof W w2`, then use `w2.inner()` and match that value the same way.",
                rejected(source, "JULC0077").suggestion());
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
        assertEquals("Bind the record and read its fields: write `s instanceof A a`, then use `a.v()` for `v`.", diagnostic.suggestion());
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
}
