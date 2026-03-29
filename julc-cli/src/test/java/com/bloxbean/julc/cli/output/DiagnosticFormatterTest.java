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
