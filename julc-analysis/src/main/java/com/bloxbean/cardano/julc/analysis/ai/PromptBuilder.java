package com.bloxbean.cardano.julc.analysis.ai;

import com.bloxbean.cardano.julc.decompiler.input.ScriptAnalyzer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Constructs Cardano-specific prompts for LLM-based vulnerability analysis.
 */
public final class PromptBuilder {

    private static final String SYSTEM_PROMPT_RESOURCE = "/prompts/system-prompt.txt";
    private static final String USER_PROMPT_RESOURCE = "/prompts/user-prompt-template.txt";

    private PromptBuilder() {}

    /**
     * Load the system prompt that establishes the Cardano security auditor role.
     */
    public static String systemPrompt() {
        return loadResource(SYSTEM_PROMPT_RESOURCE);
    }

    /**
     * Build the user prompt including decompiled source and script stats.
     */
    public static String userPrompt(String javaSource, ScriptAnalyzer.ScriptStats stats) {
        String template = loadResource(USER_PROMPT_RESOURCE);
        return template
                .replace("{{plutusVersion}}", stats.plutusVersion().name())
                .replace("{{totalNodes}}", String.valueOf(stats.totalNodes()))
                .replace("{{builtinsUsed}}", stats.builtinsUsed().toString())
                .replace("{{errorCount}}", String.valueOf(stats.errorCount()))
                .replace("{{javaSource}}", javaSource);
    }

    /**
     * JSON output format instructions for the LLM.
     */
    public static String outputFormat() {
        return """
                Return your findings as a JSON array. Each element must have these fields:
                - "severity": one of "CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO"
                - "category": one of "DOUBLE_SATISFACTION", "MISSING_AUTHORIZATION", "VALUE_LEAK", \
                "TIME_VALIDATION", "STATE_TRANSITION", "UNBOUNDED_EXECUTION", "HARDCODED_CREDENTIAL", \
                "DATUM_INTEGRITY", "GENERAL"
                - "title": short summary (one line)
                - "description": detailed explanation
                - "location": where in the code (e.g., "line 42" or "Switch branch 3")
                - "recommendation": how to fix

                If no vulnerabilities are found, return an empty array: []

                Return ONLY the JSON array, no markdown fencing, no extra text.
                """;
    }

    private static String loadResource(String path) {
        try (InputStream is = PromptBuilder.class.getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException("Missing resource: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read resource: " + path, e);
        }
    }
}
