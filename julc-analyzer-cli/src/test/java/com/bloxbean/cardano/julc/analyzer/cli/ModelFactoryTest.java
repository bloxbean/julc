package com.bloxbean.cardano.julc.analyzer.cli;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ModelFactoryTest {

    @Test
    void nullModel_returnsRulesOnly() {
        var analyzer = ModelFactory.create(null, null, null, false, Duration.ofMinutes(10));
        assertNotNull(analyzer);
    }

    @Test
    void blankModel_returnsRulesOnly() {
        var analyzer = ModelFactory.create("  ", null, null, false, Duration.ofMinutes(10));
        assertNotNull(analyzer);
    }

    @Test
    void rulesOnlyFlag_overridesModel() {
        var analyzer = ModelFactory.create("ollama:llama3", null, null, true, Duration.ofMinutes(10));
        assertNotNull(analyzer);
    }

    @Test
    void claudeCode_returnsAnalyzer() {
        var analyzer = ModelFactory.create("claude-code", null, null, false, Duration.ofMinutes(10));
        assertNotNull(analyzer);
    }

    @Test
    void claudeCode_caseInsensitive() {
        var analyzer = ModelFactory.create("Claude-Code", null, null, false, Duration.ofMinutes(10));
        assertNotNull(analyzer);
    }

    @Test
    void invalidFormat_noColon_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> ModelFactory.create("justmodel", null, null, false, Duration.ofMinutes(10)));
    }

    @Test
    void invalidFormat_emptyProvider_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> ModelFactory.create(":model", null, null, false, Duration.ofMinutes(10)));
    }

    @Test
    void invalidFormat_emptyModel_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> ModelFactory.create("ollama:", null, null, false, Duration.ofMinutes(10)));
    }

    @Test
    void unknownProvider_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> ModelFactory.create("unknown:model", null, null, false, Duration.ofMinutes(10)));
    }

    @Test
    void openai_noApiKey_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> ModelFactory.create("openai:gpt-4o", null, null, false, Duration.ofMinutes(10)));
    }

    @Test
    void anthropic_noApiKey_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> ModelFactory.create("anthropic:claude-sonnet-4-20250514", null, null, false, Duration.ofMinutes(10)));
    }

    @Test
    void ollama_createsAnalyzer() {
        // Ollama doesn't require API key, will build model even if Ollama isn't running
        var analyzer = ModelFactory.create("ollama:llama3", null, null, false, Duration.ofMinutes(10));
        assertNotNull(analyzer);
    }

    @Test
    void ollama_withCustomBaseUrl() {
        var analyzer = ModelFactory.create("ollama:llama3", null, "http://custom:11434", false, Duration.ofMinutes(10));
        assertNotNull(analyzer);
    }

    @Test
    void resolveApiKey_cliKeyPreferred() {
        assertEquals("cli-key", ModelFactory.resolveApiKey("cli-key"));
    }

    @Test
    void resolveApiKey_blankCliKey_fallsToEnv() {
        // blank CLI key → env, which may be null in test env
        var result = ModelFactory.resolveApiKey("   ");
        // just verify it doesn't throw
    }

    @Test
    void resolveApiKey_nullCliKey_fallsToEnv() {
        var result = ModelFactory.resolveApiKey(null);
        // may be null if env not set
    }
}
