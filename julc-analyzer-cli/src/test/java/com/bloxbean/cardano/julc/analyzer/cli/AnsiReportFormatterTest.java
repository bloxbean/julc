package com.bloxbean.cardano.julc.analyzer.cli;

import com.bloxbean.cardano.julc.analysis.*;
import com.bloxbean.cardano.julc.decompiler.DecompileResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnsiReportFormatterTest {

    private final AnsiReportFormatter formatter = new AnsiReportFormatter();

    @Test
    void emptyFindings() {
        var report = makeReport(List.of());
        var output = formatter.format(report, false, false);
        assertTrue(output.contains("JuLC Security Analysis Report"));
        assertTrue(output.contains("SAFE"));
    }

    @Test
    void containsAnsiCodes() {
        var finding = new Finding(Severity.HIGH, Category.VALUE_LEAK,
                "Value leak", "Description", "line 42", "Fix it");
        var report = makeReport(List.of(finding));
        var output = formatter.format(report, false, false);
        assertTrue(output.contains("\u001b["));
    }

    @Test
    void allSeverityLevels() {
        var findings = List.of(
                new Finding(Severity.CRITICAL, Category.MISSING_AUTHORIZATION, "Critical", "Desc", null, null),
                new Finding(Severity.HIGH, Category.VALUE_LEAK, "High", "Desc", null, null),
                new Finding(Severity.MEDIUM, Category.TIME_VALIDATION, "Medium", "Desc", null, null),
                new Finding(Severity.LOW, Category.STATE_TRANSITION, "Low", "Desc", null, null),
                new Finding(Severity.INFO, Category.GENERAL, "Info", "Desc", null, null)
        );
        var report = makeReport(findings);
        var output = formatter.format(report, false, false);
        assertTrue(output.contains("CRITICAL"));
        assertTrue(output.contains("HIGH"));
        assertTrue(output.contains("MEDIUM"));
        assertTrue(output.contains("LOW"));
        assertTrue(output.contains("INFO"));
    }

    @Test
    void verboseMode_withNullStats() {
        var report = makeReport(List.of());
        // null stats should not crash
        var output = formatter.format(report, true, false);
        assertNotNull(output);
    }

    @Test
    void quietMode() {
        var finding = new Finding(Severity.HIGH, Category.VALUE_LEAK,
                "Value leak found", "Description", null, null);
        var report = makeReport(List.of(finding));
        var output = formatter.format(report, false, true);
        assertTrue(output.contains("Value leak found"));
        assertFalse(output.contains("JuLC Security Analysis Report"));
    }

    @Test
    void findingWithLocation() {
        var finding = new Finding(Severity.MEDIUM, Category.GENERAL,
                "Title", "Desc", "line 42", null);
        var report = makeReport(List.of(finding));
        var output = formatter.format(report, false, false);
        assertTrue(output.contains("Location: line 42"));
    }

    @Test
    void findingWithRecommendation() {
        var finding = new Finding(Severity.MEDIUM, Category.GENERAL,
                "Title", "Desc", null, "Fix this issue");
        var report = makeReport(List.of(finding));
        var output = formatter.format(report, false, false);
        assertTrue(output.contains("Fix: Fix this issue"));
    }

    @Test
    void formatCode_java() {
        var decompiled = new DecompileResult(null, "(lam x x)", null, null, "public class Foo {}");
        var output = formatter.formatCode(decompiled, "java");
        assertTrue(output.contains("Decompiled Code (java)"));
        assertTrue(output.contains("public class Foo {}"));
    }

    @Test
    void formatCode_uplc() {
        var decompiled = new DecompileResult(null, "(lam x x)", null, null, "public class Foo {}");
        var output = formatter.formatCode(decompiled, "uplc");
        assertTrue(output.contains("Decompiled Code (uplc)"));
        assertTrue(output.contains("(lam x x)"));
    }

    @Test
    void formatCode_nullJavaSource() {
        var decompiled = new DecompileResult(null, "(lam x x)", null);
        var output = formatter.formatCode(decompiled, "java");
        assertTrue(output.contains("no java source available"));
    }

    private AnalysisReport makeReport(List<Finding> findings) {
        int critical = 0, high = 0, medium = 0, low = 0, info = 0;
        for (var f : findings) {
            switch (f.severity()) {
                case CRITICAL -> critical++;
                case HIGH -> high++;
                case MEDIUM -> medium++;
                case LOW -> low++;
                case INFO -> info++;
            }
        }
        String risk = critical > 0 ? "CRITICAL" : high > 0 ? "HIGH" : medium > 0 ? "MEDIUM" :
                low > 0 ? "LOW" : info > 0 ? "INFO" : "SAFE";
        return new AnalysisReport(findings, null,
                new AnalysisReport.AnalysisSummary(critical, high, medium, low, info, risk, null));
    }
}
