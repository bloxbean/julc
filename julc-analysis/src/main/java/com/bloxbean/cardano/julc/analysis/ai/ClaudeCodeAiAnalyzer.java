package com.bloxbean.cardano.julc.analysis.ai;

import com.bloxbean.cardano.julc.analysis.Finding;
import com.bloxbean.cardano.julc.decompiler.input.ScriptAnalyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * AI analyzer that delegates to the Claude Code CLI ({@code claude} command).
 * <p>
 * This is a zero-dependency option for Claude Max plan users — it invokes
 * the local Claude Code CLI as a subprocess and parses its JSON output.
 */
public final class ClaudeCodeAiAnalyzer implements AiAnalyzer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String claudePath;
    private final Duration timeout;

    public ClaudeCodeAiAnalyzer() {
        this("claude", Duration.ofMinutes(10));
    }

    public ClaudeCodeAiAnalyzer(String claudePath, Duration timeout) {
        this.claudePath = claudePath;
        this.timeout = timeout;
    }

    @Override
    public List<Finding> analyze(String javaSource, ScriptAnalyzer.ScriptStats stats) {
        String systemPrompt = PromptBuilder.systemPrompt();
        String userPrompt = PromptBuilder.userPrompt(javaSource, stats);

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    claudePath, "-p", "-",
                    "--append-system-prompt", systemPrompt,
                    "--output-format", "json");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Read stdout in background thread to prevent pipe buffer deadlock
            var outputHolder = new StringBuilder();
            var stdoutReader = Thread.ofVirtual().start(() -> {
                try {
                    outputHolder.append(new String(
                            process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    outputHolder.append("ERROR: ").append(e.getMessage());
                }
            });

            // Write user prompt to stdin
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(userPrompt.getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("Claude Code CLI timed out after " + timeout);
            }

            stdoutReader.join(5000);
            String output = outputHolder.toString();

            if (process.exitValue() != 0) {
                throw new RuntimeException("Claude Code CLI exited with code "
                        + process.exitValue() + ": " + output);
            }

            String resultText = extractResultText(output);
            return ResponseParser.parse(resultText);

        } catch (IOException e) {
            throw new RuntimeException("Failed to invoke Claude Code CLI at '"
                    + claudePath + "'. Is Claude Code installed?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Claude Code CLI interrupted", e);
        }
    }

    /**
     * Extract the result text from Claude Code JSON output.
     * The JSON output has a "result" field containing the actual response text.
     * Uses Jackson for robust JSON parsing (regex caused StackOverflow on large outputs).
     */
    static String extractResultText(String jsonOutput) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(jsonOutput);
            JsonNode resultNode = root.get("result");
            if (resultNode != null && resultNode.isTextual()) {
                return resultNode.asText();
            }
        } catch (Exception e) {
            // Not valid JSON — fall through to raw text fallback
        }
        return jsonOutput;
    }
}
