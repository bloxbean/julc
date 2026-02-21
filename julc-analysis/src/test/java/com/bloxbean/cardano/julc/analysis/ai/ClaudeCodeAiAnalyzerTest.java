package com.bloxbean.cardano.julc.analysis.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeCodeAiAnalyzerTest {

    @Test
    void extractResultText_simpleResult() {
        String json = """
                {"result": "Hello, world!", "cost": 0.01}
                """;
        assertEquals("Hello, world!", ClaudeCodeAiAnalyzer.extractResultText(json));
    }

    @Test
    void extractResultText_withEscapes() {
        // Jackson handles JSON escaping — quotes, backslashes, newlines
        String json = """
                {"result": "line1\\nline2\\tindented \\"quoted\\" and backslash\\\\end"}
                """;
        String result = ClaudeCodeAiAnalyzer.extractResultText(json);
        assertTrue(result.contains("line1\nline2"));
        assertTrue(result.contains("\"quoted\""));
        assertTrue(result.contains("backslash\\end"));
    }

    @Test
    void extractResultText_largeResult() {
        // 100KB+ string — would have caused StackOverflowError with regex
        String bigContent = "A".repeat(150_000);
        String json = "{\"result\": \"" + bigContent + "\"}";
        String result = ClaudeCodeAiAnalyzer.extractResultText(json);
        assertEquals(bigContent, result);
    }

    @Test
    void extractResultText_noResultField() {
        String json = """
                {"output": "something", "status": "ok"}
                """;
        // Falls back to returning raw input
        assertEquals(json, ClaudeCodeAiAnalyzer.extractResultText(json));
    }

    @Test
    void extractResultText_emptyResult() {
        String json = """
                {"result": ""}
                """;
        assertEquals("", ClaudeCodeAiAnalyzer.extractResultText(json));
    }

    @Test
    void extractResultText_rawText_fallback() {
        String raw = "This is not JSON at all";
        assertEquals(raw, ClaudeCodeAiAnalyzer.extractResultText(raw));
    }

    @Test
    void extractResultText_resultIsNotString() {
        // result is a number, not text — should fall back to raw
        String json = """
                {"result": 42}
                """;
        assertEquals(json, ClaudeCodeAiAnalyzer.extractResultText(json));
    }
}
