package com.bloxbean.cardano.julc.analyzer.cli;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Step-by-step interactive input gathering for analyze command.
 */
public final class InteractivePrompter {

    /**
     * Collected parameters from interactive prompting.
     */
    public record AnalyzeParams(
            String cborHex,
            String modelSpec,
            String apiKey,
            String baseUrl,
            boolean rulesOnly
    ) {}

    private final Console console;
    private final BufferedReader fallbackReader;

    public InteractivePrompter() {
        this.console = System.console();
        this.fallbackReader = (console == null)
                ? new BufferedReader(new InputStreamReader(System.in))
                : null;
    }

    // Visible for testing
    InteractivePrompter(BufferedReader reader) {
        this.console = null;
        this.fallbackReader = reader;
    }

    public AnalyzeParams prompt() throws IOException {
        println("JuLC Security Analyzer - Interactive Mode");
        println("==========================================");
        println();

        // Step 1: Input
        println("Enter path to file, or paste hex directly:");
        String cborHex;
        String rawInput;
        if (console != null) {
            // Switch to non-canonical mode BEFORE showing the prompt.
            // Canonical mode has a ~1024-char buffer limit (MAX_CANON on macOS)
            // that silently discards extra characters from long pastes —
            // including the Enter keypress — causing readLine() to hang.
            setCanonicalMode(false);
            try {
                System.out.print("> ");
                System.out.flush();
                rawInput = readUntilEnter().strip();
            } finally {
                setCanonicalMode(true);
            }
        } else {
            rawInput = readLine("> ").strip();
        }
        if (looksLikeFilePath(rawInput)) {
            cborHex = InputReader.readFile(Path.of(rawInput));
        } else {
            cborHex = InputReader.normalize(rawInput);
        }

        // Step 2: Analysis mode
        println();
        println("Select analysis mode:");
        println("  [1] Rules only (no AI, fast)");
        println("  [2] Ollama (local LLM)");
        println("  [3] OpenAI");
        println("  [4] Anthropic");
        println("  [5] Claude Code CLI");
        String modeChoice = readLine("Choice [1]: ").strip();
        if (modeChoice.isEmpty()) modeChoice = "1";

        String modelSpec = null;
        String apiKey = null;
        String baseUrl = null;
        boolean rulesOnly = false;

        switch (modeChoice) {
            case "1" -> rulesOnly = true;
            case "2" -> {
                println("Model name (e.g. llama3):");
                String model = readLine("> ").strip();
                if (model.isEmpty()) model = "llama3";
                modelSpec = "ollama:" + model;
                println("Base URL [http://localhost:11434]:");
                String url = readLine("> ").strip();
                if (!url.isEmpty()) baseUrl = url;
            }
            case "3" -> {
                println("Model name (e.g. gpt-4o):");
                String model = readLine("> ").strip();
                if (model.isEmpty()) model = "gpt-4o";
                modelSpec = "openai:" + model;
                apiKey = readPassword("API key: ");
            }
            case "4" -> {
                println("Model name (e.g. claude-sonnet-4-20250514):");
                String model = readLine("> ").strip();
                if (model.isEmpty()) model = "claude-sonnet-4-20250514";
                modelSpec = "anthropic:" + model;
                apiKey = readPassword("API key: ");
            }
            case "5" -> modelSpec = "claude-code";
            default -> {
                println("Unknown choice, defaulting to rules-only");
                rulesOnly = true;
            }
        }

        return new AnalyzeParams(cborHex, modelSpec, apiKey, baseUrl, rulesOnly);
    }

    /**
     * Read characters from System.in until Enter (or EOF).
     * Caller must have already switched to non-canonical terminal mode.
     */
    private static String readUntilEnter() throws IOException {
        var sb = new StringBuilder();
        int ch;
        while ((ch = System.in.read()) != -1) {
            if (ch == '\r' || ch == '\n') break;
            sb.append((char) ch);
        }
        System.out.println(); // move to next line after Enter
        return sb.toString();
    }

    private static void setCanonicalMode(boolean canonical) {
        try {
            if (canonical) {
                new ProcessBuilder("/bin/stty", "icanon")
                        .inheritIO().start().waitFor();
            } else {
                new ProcessBuilder("/bin/stty", "-icanon", "min", "1")
                        .inheritIO().start().waitFor();
            }
        } catch (Exception e) {
            // Ignore — stty unavailable (e.g. non-Unix) or interrupted.
            // Falls back to default terminal behavior.
        }
    }

    private String readLine(String prompt) throws IOException {
        if (console != null) {
            return console.readLine(prompt);
        }
        System.out.print(prompt);
        System.out.flush();
        return fallbackReader.readLine();
    }

    private String readPassword(String prompt) throws IOException {
        if (console != null) {
            char[] pw = console.readPassword(prompt);
            return pw != null ? new String(pw) : null;
        }
        return readLine(prompt);
    }

    private void println(String msg) {
        System.out.println(msg);
    }

    private void println() {
        System.out.println();
    }

    /**
     * Heuristic to detect file paths vs raw hex input.
     * Checks common extensions, path separators, and whether the file exists.
     */
    static boolean looksLikeFilePath(String input) {
        if (input.contains("/") || input.contains("\\")) {
            return true;
        }
        String lower = input.toLowerCase();
        if (lower.endsWith(".hex") || lower.endsWith(".txt") || lower.endsWith(".cbor")) {
            return true;
        }
        // Fallback: if a file with that name exists, treat as path
        try {
            return Files.exists(Path.of(input));
        } catch (Exception e) {
            return false;
        }
    }
}
