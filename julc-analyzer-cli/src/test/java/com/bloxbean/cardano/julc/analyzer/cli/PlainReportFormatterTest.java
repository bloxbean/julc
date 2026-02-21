package com.bloxbean.cardano.julc.analyzer.cli;

import com.bloxbean.cardano.julc.analysis.*;
import com.bloxbean.cardano.julc.decompiler.DecompileResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlainReportFormatterTest {

    private final PlainReportFormatter formatter = new PlainReportFormatter();

    @Test
    void emptyFindings() {
        var report = makeReport(List.of());
        var output = formatter.format(report, false, false);
        assertTrue(output.contains("Risk Level: SAFE"));
        assertTrue(output.contains("Findings: 0 total"));
    }

    @Test
    void matchesPrettyPrint() {
        var report = makeReport(List.of());
        var output = formatter.format(report, false, false);
        assertTrue(output.contains(report.prettyPrint()));
    }

    @Test
    void noAnsiCodes() {
        var finding = new Finding(Severity.HIGH, Category.VALUE_LEAK,
                "Test finding", "Detailed desc", "line 42", "Fix it");
        var report = makeReport(List.of(finding));
        var output = formatter.format(report, false, false);
        assertFalse(output.contains("\u001b["));
    }

    @Test
    void quietMode() {
        var finding = new Finding(Severity.HIGH, Category.VALUE_LEAK,
                "Value leak found", "Detailed desc", "line 42", "Fix it");
        var report = makeReport(List.of(finding));
        var output = formatter.format(report, false, true);
        assertTrue(output.contains("[HIGH] Value leak found"));
        assertFalse(output.contains("Risk Level"));
    }

    @Test
    void nullLocationAndRecommendation() {
        var finding = new Finding(Severity.MEDIUM, Category.GENERAL,
                "Title", "Desc", null, null);
        var report = makeReport(List.of(finding));
        var output = formatter.format(report, false, false);
        assertFalse(output.contains("Location:"));
        assertFalse(output.contains("Fix:"));
    }

    @Test
    void formatCode_uplc() {
        var decompiled = new DecompileResult(null, "(lam x x)", null, null, "public class Foo {}");
        var output = formatter.formatCode(decompiled, "uplc");
        assertTrue(output.contains("Decompiled Code (uplc)"));
        assertTrue(output.contains("(lam x x)"));
        assertFalse(output.contains("\u001b["));
    }

    @Test
    void formatCode_java() {
        var decompiled = new DecompileResult(null, "(lam x x)", null, null, "public class Foo {}");
        var output = formatter.formatCode(decompiled, "java");
        assertTrue(output.contains("Decompiled Code (java)"));
        assertTrue(output.contains("public class Foo {}"));
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
