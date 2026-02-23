package com.bloxbean.cardano.julc.analyzer.cli;

import com.bloxbean.cardano.julc.analysis.SecurityAnalyzer;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.time.Duration;

/**
 * Creates a {@link SecurityAnalyzer} from CLI model specification.
 */
public final class ModelFactory {

    private static final String ENV_API_KEY = "JULC_MODEL_API_KEY";

    private ModelFactory() {}

    /**
     * Create a SecurityAnalyzer based on the CLI arguments.
     *
     * @param modelSpec  format "provider:model" (e.g. "ollama:llama3"), "claude-code", or null
     * @param apiKey     API key (nullable, falls back to env)
     * @param baseUrl    base URL override (nullable)
     * @param rulesOnly  force rules-only mode
     * @param timeout    timeout for AI analysis (used by claude-code)
     * @return configured SecurityAnalyzer
     */
    public static SecurityAnalyzer create(String modelSpec, String apiKey, String baseUrl,
                                          boolean rulesOnly, Duration timeout) {
        if (rulesOnly || modelSpec == null || modelSpec.isBlank()) {
            return SecurityAnalyzer.rulesOnly();
        }

        if ("claude-code".equalsIgnoreCase(modelSpec)) {
            return SecurityAnalyzer.withClaudeCode(timeout);
        }

        var parts = modelSpec.split(":", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException(
                    "Invalid model format: '" + modelSpec + "'. Expected 'provider:model' (e.g. 'ollama:llama3')");
        }

        var provider = parts[0].toLowerCase();
        var modelName = parts[1];
        var resolvedKey = resolveApiKey(apiKey);

        return switch (provider) {
            case "ollama" -> {
                var builder = OllamaChatModel.builder()
                        .modelName(modelName);
                if (baseUrl != null && !baseUrl.isBlank()) {
                    builder.baseUrl(baseUrl);
                } else {
                    builder.baseUrl("http://localhost:11434");
                }
                yield SecurityAnalyzer.withLangChain(builder.build());
            }
            case "openai" -> {
                if (resolvedKey == null) {
                    throw new IllegalArgumentException("API key required for OpenAI. Set --model-api-key or JULC_MODEL_API_KEY env var");
                }
                var builder = OpenAiChatModel.builder()
                        .apiKey(resolvedKey)
                        .modelName(modelName);
                if (baseUrl != null && !baseUrl.isBlank()) {
                    builder.baseUrl(baseUrl);
                }
                yield SecurityAnalyzer.withLangChain(builder.build());
            }
            case "anthropic" -> {
                if (resolvedKey == null) {
                    throw new IllegalArgumentException("API key required for Anthropic. Set --model-api-key or JULC_MODEL_API_KEY env var");
                }
                var builder = AnthropicChatModel.builder()
                        .apiKey(resolvedKey)
                        .modelName(modelName);
                if (baseUrl != null && !baseUrl.isBlank()) {
                    builder.baseUrl(baseUrl);
                }
                yield SecurityAnalyzer.withLangChain(builder.build());
            }
            default -> throw new IllegalArgumentException(
                    "Unknown provider: '" + provider + "'. Supported: ollama, openai, anthropic, claude-code");
        };
    }

    static String resolveApiKey(String cliKey) {
        if (cliKey != null && !cliKey.isBlank()) {
            return cliKey;
        }
        return System.getenv(ENV_API_KEY);
    }
}
