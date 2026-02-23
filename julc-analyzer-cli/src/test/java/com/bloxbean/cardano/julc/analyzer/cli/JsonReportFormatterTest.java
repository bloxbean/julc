package com.bloxbean.cardano.julc.analyzer.cli;

import com.bloxbean.cardano.julc.analysis.*;
import com.bloxbean.cardano.julc.decompiler.DecompileResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonReportFormatterTest {

    private final JsonReportFormatter formatter = new JsonReportFormatter();

    @Test
    void emptyFindings_validJson() {
        var report = makeReport(List.of());
        var output = formatter.format(report, false, false);
        assertTrue(output.contains("\"riskLevel\": \"SAFE\""));
        assertTrue(output.contains("\"findings\": ["));
        assertTrue(output.contains("\"total\": 0"));
    }

    @Test
    void allFields_present() {
        var finding = new Finding(Severity.HIGH, Category.VALUE_LEAK,
                "Title", "Desc", "line 42", "Fix it");
        var report = makeReport(List.of(finding));
        var output = formatter.format(report, false, false);
        assertTrue(output.contains("\"severity\": \"HIGH\""));
        assertTrue(output.contains("\"category\": \"VALUE_LEAK\""));
        assertTrue(output.contains("\"title\": \"Title\""));
        assertTrue(output.contains("\"description\": \"Desc\""));
        assertTrue(output.contains("\"location\": \"line 42\""));
        assertTrue(output.contains("\"recommendation\": \"Fix it\""));
    }

    @Test
    void nullFields_outputNull() {
        var finding = new Finding(Severity.MEDIUM, Category.GENERAL,
                "Title", "Desc", null, null);
        var report = makeReport(List.of(finding));
        var output = formatter.format(report, false, false);
        assertTrue(output.contains("\"location\": null"));
        assertTrue(output.contains("\"recommendation\": null"));
    }

    @Test
    void specialCharEscaping() {
        assertEquals("\"test\\\"quoted\\\"\"", JsonReportFormatter.jsonString("test\"quoted\""));
        assertEquals("\"line1\\nline2\"", JsonReportFormatter.jsonString("line1\nline2"));
        assertEquals("\"tab\\there\"", JsonReportFormatter.jsonString("tab\there"));
        assertEquals("\"back\\\\slash\"", JsonReportFormatter.jsonString("back\\slash"));
    }

    @Test
    void nullValue_returnsNull() {
        assertEquals("null", JsonReportFormatter.jsonString(null));
    }

    @Test
    void multipleFindings_commaDelimited() {
        var findings = List.of(
                new Finding(Severity.HIGH, Category.VALUE_LEAK, "F1", "D1", null, null),
                new Finding(Severity.LOW, Category.GENERAL, "F2", "D2", null, null)
        );
        var report = makeReport(findings);
        var output = formatter.format(report, false, false);
        assertTrue(output.contains("\"total\": 2"));
        // Count finding blocks
        int count = 0;
        int idx = 0;
        while ((idx = output.indexOf("\"severity\":", idx)) != -1) {
            count++;
            idx++;
        }
        assertEquals(2, count);
    }

    @Test
    void summaryCountsCorrect() {
        var findings = List.of(
                new Finding(Severity.CRITICAL, Category.MISSING_AUTHORIZATION, "C1", "D", null, null),
                new Finding(Severity.HIGH, Category.VALUE_LEAK, "H1", "D", null, null),
                new Finding(Severity.HIGH, Category.VALUE_LEAK, "H2", "D", null, null),
                new Finding(Severity.MEDIUM, Category.GENERAL, "M1", "D", null, null)
        );
        var report = makeReport(findings);
        var output = formatter.format(report, false, false);
        assertTrue(output.contains("\"critical\": 1"));
        assertTrue(output.contains("\"high\": 2"));
        assertTrue(output.contains("\"medium\": 1"));
        assertTrue(output.contains("\"low\": 0"));
        assertTrue(output.contains("\"info\": 0"));
    }

    @Test
    void noAnsiCodes() {
        var finding = new Finding(Severity.CRITICAL, Category.MISSING_AUTHORIZATION,
                "Test", "Desc", null, null);
        var report = makeReport(List.of(finding));
        var output = formatter.format(report, false, false);
        assertFalse(output.contains("\u001b["));
    }

    @Test
    void formatCode_java() {
        var decompiled = new DecompileResult(null, "(lam x x)", null, null,
                "public class Foo {\n    // test\n}");
        var report = makeReport(List.of());
        var output = formatter.format(report, false, false, decompiled, "java");
        assertTrue(output.contains("\"code\""));
        assertTrue(output.contains("\"format\": \"java\""));
        assertTrue(output.contains("\"content\":"));
        // Ensure the code block is inside the JSON (before final })
        int codeIdx = output.indexOf("\"code\"");
        int lastBrace = output.lastIndexOf("}");
        assertTrue(codeIdx < lastBrace, "code field should be inside JSON object");
    }

    @Test
    void formatCode_none_excludes() {
        var decompiled = new DecompileResult(null, "(lam x x)", null, null, "public class Foo {}");
        var report = makeReport(List.of());
        var output = formatter.format(report, false, false, decompiled, "none");
        assertFalse(output.contains("\"code\""), "none should not include code field");
    }

    @Test
    void formatCode_withSpecialChars() {
        var decompiled = new DecompileResult(null, "(lam x x)", null, null,
                "class Foo {\n    String s = \"hello\\nworld\";\n}");
        var report = makeReport(List.of());
        var output = formatter.format(report, false, false, decompiled, "java");
        assertTrue(output.contains("\"code\""));
        // Verify the content is properly escaped
        assertTrue(output.contains("\"content\":"));
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
