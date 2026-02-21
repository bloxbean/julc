package com.bloxbean.cardano.julc.analyzer.cli;

import com.bloxbean.cardano.julc.analysis.AnalysisOptions;
import com.bloxbean.cardano.julc.analysis.Category;
import com.bloxbean.cardano.julc.analysis.Severity;
import com.bloxbean.cardano.julc.decompiler.JulcDecompiler;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * Main analyze subcommand — decompile + security analyze a Plutus script.
 */
@Command(
        name = "analyze",
        description = "Analyze a Plutus script for security vulnerabilities",
        mixinStandardHelpOptions = true
)
public class AnalyzeSubcommand implements Callable<Integer> {

    // --- Input options ---
    @Option(names = "--compiled-code", description = "CBOR hex string directly, or '-' for stdin")
    String compiledCode;

    @Option(names = {"-f", "--file"}, description = "Path to file containing CBOR hex (.hex/.txt)")
    Path file;

    // --- AI Model options ---
    @Option(names = "--model", description = "AI model: ollama:model, openai:model, anthropic:model, or claude-code")
    String model;

    @Option(names = "--model-api-key", description = "API key (or env JULC_MODEL_API_KEY)")
    String modelApiKey;

    @Option(names = "--model-base-url", description = "Base URL override for model provider")
    String modelBaseUrl;

    // --- Analysis options ---
    @Option(names = "--rules-only", description = "Force rules-only analysis (skip AI)")
    boolean rulesOnly;

    @Option(names = "--timeout", description = "AI analysis timeout in minutes (default: 10)",
            defaultValue = "10")
    int timeoutMinutes;

    @Option(names = "--skip-categories", split = ",", description = "Categories to exclude (comma-separated)")
    String[] skipCategories;

    // --- Output options ---
    @Option(names = "--json", description = "JSON output for machine consumption")
    boolean json;

    @Option(names = "--no-color", description = "Disable ANSI colors")
    boolean noColor;

    @Option(names = {"-q", "--quiet"}, description = "Minimal output (findings only)")
    boolean quiet;

    @Option(names = {"-v", "--verbose"}, description = "Include decompiled source and script stats")
    boolean verbose;

    @Option(names = "--show-code", description = "Show decompiled code: java (default) or uplc",
            arity = "0..1", defaultValue = "none", fallbackValue = "java")
    String showCode;

    @Option(names = {"-o", "--output"}, description = "Write to file instead of stdout")
    Path outputFile;

    // --- Interactive ---
    @Option(names = {"-i", "--interactive"}, description = "Prompt for missing inputs step by step")
    boolean interactive;

    @Override
    public Integer call() {
        try {
            return execute();
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        }
    }

    int execute() throws Exception {
        String cborHex;
        String effectiveModel = model;
        String effectiveApiKey = modelApiKey;
        String effectiveBaseUrl = modelBaseUrl;
        boolean effectiveRulesOnly = rulesOnly;

        // Interactive mode
        if (interactive || (compiledCode == null && file == null)) {
            if (interactive || (!quiet && compiledCode == null && file == null)) {
                var prompter = new InteractivePrompter();
                var params = prompter.prompt();
                cborHex = params.cborHex();
                if (effectiveModel == null) effectiveModel = params.modelSpec();
                if (effectiveApiKey == null) effectiveApiKey = params.apiKey();
                if (effectiveBaseUrl == null) effectiveBaseUrl = params.baseUrl();
                if (!effectiveRulesOnly) effectiveRulesOnly = params.rulesOnly();
            } else {
                System.err.println("Error: No input provided. Use --compiled-code, --file, or --interactive");
                return 2;
            }
        } else {
            // Resolve input
            if (file != null) {
                cborHex = InputReader.readFile(file);
            } else {
                cborHex = InputReader.readHex(compiledCode);
            }
        }

        // Build analyzer
        var analyzer = ModelFactory.create(effectiveModel, effectiveApiKey, effectiveBaseUrl,
                effectiveRulesOnly, Duration.ofMinutes(timeoutMinutes));

        // Build analysis options
        boolean enableAi = !effectiveRulesOnly && effectiveModel != null && !effectiveModel.isBlank();
        Set<Category> skipped = parseSkipCategories();
        var options = new AnalysisOptions(true, enableAi, skipped);

        // Validate --show-code
        if (showCode != null && !showCode.equalsIgnoreCase("none")
                && !showCode.equalsIgnoreCase("java") && !showCode.equalsIgnoreCase("uplc")) {
            throw new IllegalArgumentException("Invalid --show-code value: '" + showCode
                    + "'. Valid values: none, java, uplc");
        }

        // Decompile and analyze
        var decompiled = JulcDecompiler.decompile(cborHex);
        var report = analyzer.analyze(decompiled, options);

        // Format output
        var formatter = OutputFormatter.create(json, noColor);
        var output = formatter.format(report, verbose, quiet, decompiled, showCode);

        // Write output
        if (outputFile != null) {
            Files.writeString(outputFile, output);
            if (!quiet) {
                System.out.println("Report written to: " + outputFile);
            }
        } else {
            System.out.print(output);
        }

        // Exit code: 1 if CRITICAL/HIGH findings
        boolean hasCriticalOrHigh = report.findings().stream()
                .anyMatch(f -> f.severity() == Severity.CRITICAL || f.severity() == Severity.HIGH);
        return hasCriticalOrHigh ? 1 : 0;
    }

    private Set<Category> parseSkipCategories() {
        if (skipCategories == null || skipCategories.length == 0) {
            return Set.of();
        }
        return Arrays.stream(skipCategories)
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(s -> {
                    try {
                        return Category.valueOf(s.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("Unknown category: '" + s + "'. Valid: " +
                                Arrays.stream(Category.values()).map(Enum::name).collect(Collectors.joining(", ")));
                    }
                })
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Category.class)));
    }
}
