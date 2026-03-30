package com.bloxbean.cardano.julc.vm.trace;

import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.source.SourceLocation;
import com.bloxbean.cardano.julc.vm.ExBudget;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FailureReportFormatterTest {

    @Test
    void format_withSourceLocation_andBuiltinContext() {
        var report = new FailureReport(
                "Error term encountered",
                new SourceLocation("VestingValidator.java", 42, 5, "return deadline <= currentSlot"),
                List.of(
                        new BuiltinExecution(DefaultFun.UnIData, "<Data>", "5"),
                        new BuiltinExecution(DefaultFun.UnIData, "<Data>", "3"),
                        new BuiltinExecution(DefaultFun.LessThanEqualsInteger, "5, 3", "False")
                ),
                List.of(),
                new ExBudget(1_234_567, 45_678),
                List.of()
        );

        var output = FailureReportFormatter.format(report);
        assertTrue(output.contains("FAIL"));
        assertTrue(output.contains("VestingValidator.java:42"));
        assertTrue(output.contains("return deadline <= currentSlot"));
        assertTrue(output.contains("LessThanEqualsInteger(5, 3) → False"));
        assertTrue(output.contains("Last builtins:"));
        assertTrue(output.contains("UnIData(<Data>) → 5"));
        assertTrue(output.contains("UnIData(<Data>) → 3"));
        assertTrue(output.contains("CPU=1,234,567"));
        assertTrue(output.contains("Mem=45,678"));
    }

    @Test
    void format_withoutSourceLocation() {
        var report = new FailureReport(
                "Error term encountered",
                null, // no source location
                List.of(
                        new BuiltinExecution(DefaultFun.EqualsInteger, "42, 99", "False")
                ),
                List.of(),
                new ExBudget(500, 200),
                List.of()
        );

        var output = FailureReportFormatter.format(report);
        assertTrue(output.contains("FAIL: Error term encountered"));
        assertTrue(output.contains("EqualsInteger(42, 99) → False"));
        assertTrue(output.contains("CPU=500"));
    }

    @Test
    void format_withoutBuiltinContext() {
        var report = new FailureReport(
                "Error term encountered",
                new SourceLocation("Test.java", 10, 1, "throw new Error()"),
                List.of(), // no builtins
                List.of(),
                new ExBudget(100, 50),
                List.of()
        );

        var output = FailureReportFormatter.format(report);
        assertTrue(output.contains("FAIL"));
        assertTrue(output.contains("Test.java:10"));
        assertFalse(output.contains("Last builtins:"));
        assertTrue(output.contains("CPU=100"));
    }

    @Test
    void format_withTraceMessages() {
        var report = new FailureReport(
                "Error term encountered",
                null,
                List.of(),
                List.of(),
                new ExBudget(100, 50),
                List.of("debug: checking amount", "debug: amount is negative")
        );

        var output = FailureReportFormatter.format(report);
        assertTrue(output.contains("Traces:"));
        assertTrue(output.contains("debug: checking amount"));
        assertTrue(output.contains("debug: amount is negative"));
    }

    @Test
    void format_budgetExhausted() {
        var report = new FailureReport(
                "Budget exhausted",
                new SourceLocation("HeavyValidator.java", 99, 1, "while(true)"),
                List.of(
                        new BuiltinExecution(DefaultFun.AddInteger, "1000000, 1", "1000001")
                ),
                List.of(),
                new ExBudget(10_000_000_000L, 14_000_000),
                List.of()
        );

        var output = FailureReportFormatter.format(report);
        assertTrue(output.contains("FAIL"));
        assertTrue(output.contains("HeavyValidator.java:99"));
        // AddInteger doesn't produce False so no "cause" highlight
        assertTrue(output.contains("Last builtins:"));
        assertTrue(output.contains("AddInteger(1000000, 1) → 1000001"));
    }

    @Test
    void format_emptyReport() {
        var report = new FailureReport(
                "Error term encountered",
                null,
                List.of(),
                List.of(),
                ExBudget.ZERO,
                List.of()
        );

        var output = FailureReportFormatter.format(report);
        assertTrue(output.contains("FAIL: Error term encountered"));
        assertFalse(output.contains("Last builtins:"));
        assertFalse(output.contains("Traces:"));
        assertTrue(output.contains("CPU=0"));
    }

    @Test
    void findCauseBuiltin_findsLastComparison() {
        var report = new FailureReport(
                "Error",
                null,
                List.of(
                        new BuiltinExecution(DefaultFun.UnIData, "<Data>", "5"),
                        new BuiltinExecution(DefaultFun.EqualsInteger, "5, 3", "False"),
                        new BuiltinExecution(DefaultFun.AddInteger, "1, 2", "3")
                ),
                List.of(),
                ExBudget.ZERO,
                List.of()
        );

        var cause = report.findCauseBuiltin();
        assertNotNull(cause);
        assertEquals(DefaultFun.EqualsInteger, cause.fun());
    }

    @Test
    void findCauseBuiltin_ignoresTrue() {
        var report = new FailureReport(
                "Error",
                null,
                List.of(
                        new BuiltinExecution(DefaultFun.EqualsInteger, "5, 5", "True")
                ),
                List.of(),
                ExBudget.ZERO,
                List.of()
        );

        assertNull(report.findCauseBuiltin());
    }

    @Test
    void findCauseBuiltin_emptyBuiltins() {
        var report = new FailureReport(
                "Error",
                null,
                List.of(),
                List.of(),
                ExBudget.ZERO,
                List.of()
        );

        assertNull(report.findCauseBuiltin());
    }
}
