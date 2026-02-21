package com.bloxbean.cardano.julc.analyzer.cli;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AI integration tests — disabled by default.
 * Enable individually when running with access to respective providers.
 */
class CliAiIntegrationTest {

    private static final String SAMPLE_HEX = TestData.SAMPLE_HEX;

    private CommandLine createCli() {
        return new CommandLine(new JulcAnalyzerCommand());
    }

    @Test
    @Disabled("Requires Ollama running locally with llama3 model")
    void analyzeWithOllama() {
        var cli = createCli();
        int code = cli.execute("analyze", "--compiled-code", SAMPLE_HEX,
                "--model", "ollama:llama3");
        assertTrue(code == 0 || code == 1);
    }

    @Test
    @Disabled("Requires OpenAI API key")
    void analyzeWithOpenAi() {
        var cli = createCli();
        int code = cli.execute("analyze", "--compiled-code", SAMPLE_HEX,
                "--model", "openai:gpt-4o",
                "--model-api-key", System.getenv("OPENAI_API_KEY"));
        assertTrue(code == 0 || code == 1);
    }

    @Test
    @Disabled("Requires Anthropic API key")
    void analyzeWithAnthropic() {
        var cli = createCli();
        int code = cli.execute("analyze", "--compiled-code", SAMPLE_HEX,
                "--model", "anthropic:claude-sonnet-4-20250514",
                "--model-api-key", System.getenv("ANTHROPIC_API_KEY"));
        assertTrue(code == 0 || code == 1);
    }

    @Test
    @Disabled("Requires Claude Code CLI installed")
    void analyzeWithClaudeCode() {
        var cli = createCli();
        int code = cli.execute("analyze", "--compiled-code", SAMPLE_HEX,
                "--model", "claude-code");
        assertTrue(code == 0 || code == 1);
    }
}
