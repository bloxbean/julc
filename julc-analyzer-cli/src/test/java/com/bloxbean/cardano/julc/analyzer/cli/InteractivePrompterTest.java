package com.bloxbean.cardano.julc.analyzer.cli;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

class InteractivePrompterTest {

    @Test
    void rulesOnlyMode() throws IOException {
        // Input: hex directly, then select mode 1 (rules only)
        var input = "abcdef01\n1\n";
        var reader = new BufferedReader(new StringReader(input));
        var prompter = new InteractivePrompter(reader);
        var params = prompter.prompt();

        assertEquals("abcdef01", params.cborHex());
        assertTrue(params.rulesOnly());
        assertNull(params.modelSpec());
    }

    @Test
    void claudeCodeMode() throws IOException {
        var input = "abcdef01\n5\n";
        var reader = new BufferedReader(new StringReader(input));
        var prompter = new InteractivePrompter(reader);
        var params = prompter.prompt();

        assertEquals("abcdef01", params.cborHex());
        assertFalse(params.rulesOnly());
        assertEquals("claude-code", params.modelSpec());
    }

    @Test
    void ollamaMode() throws IOException {
        // hex, then 2 (ollama), model name, empty base url (default)
        var input = "abcdef01\n2\nllama3\n\n";
        var reader = new BufferedReader(new StringReader(input));
        var prompter = new InteractivePrompter(reader);
        var params = prompter.prompt();

        assertEquals("abcdef01", params.cborHex());
        assertEquals("ollama:llama3", params.modelSpec());
        assertNull(params.baseUrl());
    }

    @Test
    void ollamaMode_customBaseUrl() throws IOException {
        var input = "abcdef01\n2\nllama3\nhttp://custom:11434\n";
        var reader = new BufferedReader(new StringReader(input));
        var prompter = new InteractivePrompter(reader);
        var params = prompter.prompt();

        assertEquals("http://custom:11434", params.baseUrl());
    }

    @Test
    void openaiMode() throws IOException {
        // hex, 3 (openai), model name, api key
        var input = "abcdef01\n3\ngpt-4o\nsk-test-key\n";
        var reader = new BufferedReader(new StringReader(input));
        var prompter = new InteractivePrompter(reader);
        var params = prompter.prompt();

        assertEquals("openai:gpt-4o", params.modelSpec());
        assertEquals("sk-test-key", params.apiKey());
    }

    @Test
    void anthropicMode() throws IOException {
        var input = "abcdef01\n4\nclaude-sonnet-4-20250514\nsk-ant-key\n";
        var reader = new BufferedReader(new StringReader(input));
        var prompter = new InteractivePrompter(reader);
        var params = prompter.prompt();

        assertEquals("anthropic:claude-sonnet-4-20250514", params.modelSpec());
        assertEquals("sk-ant-key", params.apiKey());
    }

    @Test
    void defaultModeSelection() throws IOException {
        // hex, empty input (defaults to 1 / rules only)
        var input = "abcdef01\n\n";
        var reader = new BufferedReader(new StringReader(input));
        var prompter = new InteractivePrompter(reader);
        var params = prompter.prompt();

        assertTrue(params.rulesOnly());
    }

    @Test
    void unknownModeChoice_defaultsToRulesOnly() throws IOException {
        var input = "abcdef01\n9\n";
        var reader = new BufferedReader(new StringReader(input));
        var prompter = new InteractivePrompter(reader);
        var params = prompter.prompt();

        assertTrue(params.rulesOnly());
    }

    @Test
    void hexWithOxPrefix() throws IOException {
        var input = "0xabcdef01\n1\n";
        var reader = new BufferedReader(new StringReader(input));
        var prompter = new InteractivePrompter(reader);
        var params = prompter.prompt();

        assertEquals("abcdef01", params.cborHex());
    }

    @Test
    void longHexInput() throws IOException {
        // 2500+ char hex string (like test.cbor) — single readLine handles it
        String longHex = "ab".repeat(1300); // 2600 chars
        var input = longHex + "\n1\n";
        var reader = new BufferedReader(new StringReader(input));
        var prompter = new InteractivePrompter(reader);
        var params = prompter.prompt();

        assertEquals(longHex, params.cborHex());
        assertTrue(params.rulesOnly());
    }

    @Test
    void looksLikeFilePath_hexExtension() {
        assertTrue(InteractivePrompter.looksLikeFilePath("test.hex"));
    }

    @Test
    void looksLikeFilePath_txtExtension() {
        assertTrue(InteractivePrompter.looksLikeFilePath("script.txt"));
    }

    @Test
    void looksLikeFilePath_cborExtension() {
        assertTrue(InteractivePrompter.looksLikeFilePath("test.cbor"));
    }

    @Test
    void looksLikeFilePath_withSlash() {
        assertTrue(InteractivePrompter.looksLikeFilePath("/tmp/test.dat"));
    }

    @Test
    void looksLikeFilePath_rawHex() {
        assertFalse(InteractivePrompter.looksLikeFilePath("abcdef0123456789"));
    }
}
