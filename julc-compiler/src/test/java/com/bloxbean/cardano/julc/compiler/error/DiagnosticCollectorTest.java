package com.bloxbean.cardano.julc.compiler.error;

import com.bloxbean.cardano.julc.compiler.CompilerException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticCollectorTest {

    @Test
    void collectsErrorsWithPosition() {
        var collector = new DiagnosticCollector("test.java");

        // Use a parsed expression to get a real Node with position
        var cu = StaticJavaParser.parse("class A { void m() { int x = 42; } }");
        var literal = cu.findFirst(IntegerLiteralExpr.class).orElseThrow();

        collector.error(literal, "Test error", "Fix suggestion");

        assertTrue(collector.hasErrors());
        assertEquals(1, collector.size());
        var diag = collector.getDiagnostics().get(0);
        assertTrue(diag.isError());
        assertEquals("Test error", diag.message());
        assertEquals("Fix suggestion", diag.suggestion());
        assertEquals("test.java", diag.fileName());
        assertTrue(diag.line() > 0);
    }

    @Test
    void collectsWarnings() {
        var collector = new DiagnosticCollector("test.java");
        collector.warning(null, "Test warning");

        assertFalse(collector.hasErrors());
        assertEquals(1, collector.size());
        assertEquals(CompilerDiagnostic.Level.WARNING, collector.getDiagnostics().get(0).level());
    }

    @Test
    void throwIfErrorsDoesNothingWhenNoErrors() {
        var collector = new DiagnosticCollector();
        collector.warning(null, "Just a warning");
        assertDoesNotThrow(collector::throwIfErrors);
    }

    @Test
    void throwIfErrorsThrowsCompilerException() {
        var collector = new DiagnosticCollector();
        collector.error("Test error");

        var ex = assertThrows(CompilerException.class, collector::throwIfErrors);
        assertFalse(ex.diagnostics().isEmpty());
        assertEquals("Test error", ex.diagnostics().get(0).message());
    }

    @Test
    void addAllMergesCollectors() {
        var collector1 = new DiagnosticCollector("file1.java");
        collector1.error("Error 1");

        var collector2 = new DiagnosticCollector("file2.java");
        collector2.error("Error 2");
        collector2.warning(null, "Warning 1");

        collector1.addAll(collector2);
        assertEquals(3, collector1.size());
        assertEquals(2, collector1.getErrors().size());
        assertEquals(1, collector1.getWarnings().size());
    }

    @Test
    void clearRemovesAllDiagnostics() {
        var collector = new DiagnosticCollector();
        collector.error("Error 1");
        collector.warning(null, "Warning 1");
        collector.clear();

        assertTrue(collector.isEmpty());
        assertFalse(collector.hasErrors());
    }

    @Test
    void errorsWithoutNodeUseZeroPosition() {
        var collector = new DiagnosticCollector("test.java");
        collector.error("Structural error");

        var diag = collector.getDiagnostics().get(0);
        assertEquals(0, diag.line());
        assertEquals(0, diag.column());
    }

    @Test
    void errorWithCode_attachesStableCode() {
        var collector = new DiagnosticCollector("test.java");
        var cu = StaticJavaParser.parse("class A { void m() { int x = 42; } }");
        var literal = cu.findFirst(IntegerLiteralExpr.class).orElseThrow();

        collector.errorWithCode(literal, "JULC0003", "Cannot return inside while loop",
                "Use a boolean accumulator and return after the loop");

        var diag = collector.getDiagnostics().get(0);
        assertEquals("JULC0003", diag.code());
        assertTrue(diag.hasCode());
        assertTrue(diag.toString().contains("[JULC0003]"),
                "toString should surface the code for log readability");
    }

    @Test
    void errorWithGeneratedCode_formatsTemplateAndUsesCatalogFix() {
        var collector = new DiagnosticCollector("test.java");
        var cu = StaticJavaParser.parse("class A { void m() { int x = 42; } }");
        var literal = cu.findFirst(IntegerLiteralExpr.class).orElseThrow();

        collector.errorWithCode(literal, DiagnosticCodes.PARAM_RAW_PLUTUS_DATA,
                "PlutusData.BytesData");

        var diag = collector.getDiagnostics().get(0);
        assertEquals("JULC0013", diag.code());
        assertEquals("@Param type 'PlutusData.BytesData' is not allowed. "
                + "@Param values are always raw Data at runtime; using a typed Data subtype "
                + "causes the compiler to misinterpret the runtime representation.", diag.message());
        assertEquals(DiagnosticCodes.PARAM_RAW_PLUTUS_DATA.fix(), diag.suggestion());
    }

    @Test
    void errorWithCode_withoutNode_isStillStructured() {
        var collector = new DiagnosticCollector("test.java");
        collector.errorWithCode("JULC0010", "No validator annotation found",
                "Add @SpendingValidator / @MintingValidator / etc.");

        var diag = collector.getDiagnostics().get(0);
        assertEquals("JULC0010", diag.code());
        assertEquals(0, diag.line());
    }

    @Test
    void diagnosticsWithoutCode_remainBackwardsCompatible() {
        var collector = new DiagnosticCollector("test.java");
        collector.error("Legacy error without code");

        var diag = collector.getDiagnostics().get(0);
        assertNull(diag.code());
        assertFalse(diag.hasCode());
        assertFalse(diag.toString().contains("[null]"),
                "toString must not embed [null] for code-less diagnostics");
    }
}
