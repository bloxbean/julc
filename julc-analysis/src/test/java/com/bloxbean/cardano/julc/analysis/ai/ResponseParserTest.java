package com.bloxbean.cardano.julc.analysis.ai;

import com.bloxbean.cardano.julc.analysis.Category;
import com.bloxbean.cardano.julc.analysis.Severity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResponseParserTest {

    @Test
    void parse_validSingleFinding() {
        String json = """
                [
                  {
                    "severity": "HIGH",
                    "category": "MISSING_AUTHORIZATION",
                    "title": "Missing signatory check",
                    "description": "The withdrawal action does not verify the signer.",
                    "location": "Switch branch 2",
                    "recommendation": "Add a signatory check."
                  }
                ]
                """;

        var findings = ResponseParser.parse(json);
        assertEquals(1, findings.size());
        var f = findings.getFirst();
        assertEquals(Severity.HIGH, f.severity());
        assertEquals(Category.MISSING_AUTHORIZATION, f.category());
        assertEquals("Missing signatory check", f.title());
        assertEquals("Switch branch 2", f.location());
    }

    @Test
    void parse_multipleFindings() {
        String json = """
                [
                  {
                    "severity": "CRITICAL",
                    "category": "VALUE_LEAK",
                    "title": "Value not preserved",
                    "description": "Output value is less than input.",
                    "location": "line 10",
                    "recommendation": "Check value equality."
                  },
                  {
                    "severity": "LOW",
                    "category": "GENERAL",
                    "title": "Minor concern",
                    "description": "Some observation.",
                    "location": "",
                    "recommendation": "Consider reviewing."
                  }
                ]
                """;

        var findings = ResponseParser.parse(json);
        assertEquals(2, findings.size());
        assertEquals(Severity.CRITICAL, findings.get(0).severity());
        assertEquals(Severity.LOW, findings.get(1).severity());
    }

    @Test
    void parse_emptyArray() {
        var findings = ResponseParser.parse("[]");
        assertTrue(findings.isEmpty());
    }

    @Test
    void parse_nullInput() {
        var findings = ResponseParser.parse(null);
        assertTrue(findings.isEmpty());
    }

    @Test
    void parse_blankInput() {
        var findings = ResponseParser.parse("   ");
        assertTrue(findings.isEmpty());
    }

    @Test
    void parse_markdownFencedJson() {
        String json = """
                ```json
                [
                  {
                    "severity": "MEDIUM",
                    "category": "HARDCODED_CREDENTIAL",
                    "title": "Hardcoded hash",
                    "description": "Found 28-byte hash.",
                    "location": "line 5",
                    "recommendation": "Use parameterized script."
                  }
                ]
                ```
                """;

        var findings = ResponseParser.parse(json);
        assertEquals(1, findings.size());
        assertEquals(Category.HARDCODED_CREDENTIAL, findings.getFirst().category());
    }

    @Test
    void parse_unknownSeverity_defaultsToInfo() {
        String json = """
                [
                  {
                    "severity": "UNKNOWN",
                    "category": "GENERAL",
                    "title": "Something",
                    "description": "Details.",
                    "location": "",
                    "recommendation": ""
                  }
                ]
                """;

        var findings = ResponseParser.parse(json);
        assertEquals(1, findings.size());
        assertEquals(Severity.INFO, findings.getFirst().severity());
    }

    @Test
    void parse_unknownCategory_defaultsToGeneral() {
        String json = """
                [
                  {
                    "severity": "HIGH",
                    "category": "EXOTIC_BUG",
                    "title": "Exotic issue",
                    "description": "Some exotic bug.",
                    "location": "",
                    "recommendation": ""
                  }
                ]
                """;

        var findings = ResponseParser.parse(json);
        assertEquals(1, findings.size());
        assertEquals(Category.GENERAL, findings.getFirst().category());
    }

    @Test
    void parse_malformedJson_returnsPartial() {
        // First object is valid, second is broken
        String json = """
                [
                  {
                    "severity": "HIGH",
                    "category": "GENERAL",
                    "title": "Good finding",
                    "description": "Valid.",
                    "location": "",
                    "recommendation": ""
                  },
                  {
                    "severity": "MEDIUM",
                    broken json here
                  }
                ]
                """;

        var findings = ResponseParser.parse(json);
        // First should parse; second has no title so returns null
        assertEquals(1, findings.size());
        assertEquals("Good finding", findings.getFirst().title());
    }

    @Test
    void parse_jsonWithSurroundingText() {
        String json = """
                Here are my findings:
                [
                  {
                    "severity": "INFO",
                    "category": "GENERAL",
                    "title": "Observation",
                    "description": "Just a note.",
                    "location": "",
                    "recommendation": ""
                  }
                ]
                That concludes my analysis.
                """;

        var findings = ResponseParser.parse(json);
        assertEquals(1, findings.size());
    }

    @Test
    void parse_escapedStrings() {
        String json = """
                [
                  {
                    "severity": "HIGH",
                    "category": "GENERAL",
                    "title": "Issue with \\"quotes\\"",
                    "description": "Has\\nnewlines.",
                    "location": "line 1",
                    "recommendation": "Fix it."
                  }
                ]
                """;

        var findings = ResponseParser.parse(json);
        assertEquals(1, findings.size());
        assertEquals("Issue with \"quotes\"", findings.getFirst().title());
        assertTrue(findings.getFirst().description().contains("\n"));
    }

    @Test
    void extractField_findsCorrectField() {
        String json = """
                {"severity": "HIGH", "title": "Test", "description": "Desc"}
                """;
        assertEquals("HIGH", ResponseParser.extractField(json, "severity"));
        assertEquals("Test", ResponseParser.extractField(json, "title"));
    }

    @Test
    void extractField_returnsNull_whenMissing() {
        String json = """
                {"title": "Test"}
                """;
        assertNull(ResponseParser.extractField(json, "severity"));
    }
}
