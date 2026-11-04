package org.julclang.compiler.error;

import org.julclang.compiler.CompilerException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticCollectorTest {

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
