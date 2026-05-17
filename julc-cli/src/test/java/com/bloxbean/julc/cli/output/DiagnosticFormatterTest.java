package com.bloxbean.julc.cli.output;

import com.bloxbean.cardano.julc.compiler.error.CompilerDiagnostic;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticFormatterTest {

    @Test
    void formatError() {
        var diag = new CompilerDiagnostic(
                CompilerDiagnostic.Level.ERROR,
                "unexpected type",
                "MyValidator.java", 14, 8);
        String formatted = DiagnosticFormatter.format(diag);
        assertTrue(formatted.contains("./MyValidator.java:14:8"));
        assertTrue(formatted.contains("unexpected type"));
    }

    @Test
    void formatWithSuggestion() {
        var diag = new CompilerDiagnostic(
                CompilerDiagnostic.Level.ERROR,
                "bad code",
                "Test.java", 1, 1, "use X instead");
        String formatted = DiagnosticFormatter.format(diag);
        assertTrue(formatted.contains("use X instead"));
    }

    @Test
    void formatShowsCodeWhenPresent() {
        // When a diagnostic carries a stable JULC#### code, the formatter
        // must surface it inline so users / AI agents can look up the
        // canonical fix in /ai/diagnostics.json.
        var diag = new CompilerDiagnostic(
                CompilerDiagnostic.Level.ERROR,
                "try/catch is not supported on-chain",
                "T.java", 5, 9, "Use if/else instead", "JULC0015");
        String formatted = DiagnosticFormatter.format(diag);
        assertTrue(formatted.contains("[JULC0015]"),
                "formatter must surface the diagnostic code");
        assertTrue(formatted.contains("try/catch is not supported"),
                "formatter must still include the message");
    }

    @Test
    void formatOmitsCodeBracketsWhenAbsent() {
        // Backwards compat: legacy code-less diagnostics must not render
        // a stray `[null]` in the output.
        var diag = new CompilerDiagnostic(
                CompilerDiagnostic.Level.ERROR,
                "legacy error",
                "T.java", 1, 1);
        String formatted = DiagnosticFormatter.format(diag);
        assertFalse(formatted.contains("[null]"),
                "code-less diagnostics must not render '[null]'");
        assertFalse(formatted.contains("[]"),
                "code-less diagnostics must not render empty brackets");
    }

    @Test
    void formatAllShowsSummary() {
        var diags = List.of(
                new CompilerDiagnostic(CompilerDiagnostic.Level.ERROR, "e1", "a.java", 1, 1),
                new CompilerDiagnostic(CompilerDiagnostic.Level.WARNING, "w1", "b.java", 2, 1),
                new CompilerDiagnostic(CompilerDiagnostic.Level.ERROR, "e2", "c.java", 3, 1)
        );
        String formatted = DiagnosticFormatter.formatAll(diags);
        assertTrue(formatted.contains("2 error"));
        assertTrue(formatted.contains("1 warning"));
    }
}
