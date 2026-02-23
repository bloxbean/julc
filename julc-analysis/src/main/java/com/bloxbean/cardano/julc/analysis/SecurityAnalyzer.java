package com.bloxbean.cardano.julc.analysis;

import com.bloxbean.cardano.julc.analysis.ai.AiAnalyzer;
import com.bloxbean.cardano.julc.analysis.ai.ClaudeCodeAiAnalyzer;
import com.bloxbean.cardano.julc.analysis.ai.LangChainAiAnalyzer;
import com.bloxbean.cardano.julc.analysis.rules.RuleEngine;
import com.bloxbean.cardano.julc.decompiler.DecompileResult;
import com.bloxbean.cardano.julc.decompiler.JulcDecompiler;
import com.bloxbean.cardano.julc.decompiler.input.ScriptAnalyzer;
import dev.langchain4j.model.chat.ChatModel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Main facade for security analysis of decompiled Plutus scripts.
 * <p>
 * Combines rule-based detection (fast, deterministic) with optional
 * AI-powered analysis (LLM-based, richer findings).
 *
 * <h3>Usage Examples</h3>
 * <pre>{@code
 * // Rules only (no AI, no external dependencies)
 * var analyzer = SecurityAnalyzer.rulesOnly();
 * var report = analyzer.analyze(decompileResult);
 *
 * // With LangChain4j (any provider)
 * ChatModel model = OllamaChatModel.builder()
 *     .baseUrl("http://localhost:11434")
 *     .modelName("llama3")
 *     .build();
 * var analyzer = SecurityAnalyzer.withLangChain(model);
 *
 * // With Claude Code CLI (Max plan)
 * var analyzer = SecurityAnalyzer.withClaudeCode();
 *
 * // One-shot: decompile + analyze
 * var report = SecurityAnalyzer.analyzeScript(cborHex, analyzer);
 * }</pre>
 */
public final class SecurityAnalyzer {

    private final RuleEngine ruleEngine;
    private final AiAnalyzer aiAnalyzer;

    private SecurityAnalyzer(AiAnalyzer aiAnalyzer) {
        this.ruleEngine = new RuleEngine();
        this.aiAnalyzer = aiAnalyzer;
    }

    /**
     * Create an analyzer that uses only rule-based detectors (no AI).
     */
    public static SecurityAnalyzer rulesOnly() {
        return new SecurityAnalyzer(null);
    }

    /**
     * Create an analyzer with a LangChain4j ChatModel for AI analysis.
     */
    public static SecurityAnalyzer withLangChain(ChatModel chatModel) {
        return new SecurityAnalyzer(new LangChainAiAnalyzer(chatModel));
    }

    /**
     * Create an analyzer that uses Claude Code CLI for AI analysis.
     */
    public static SecurityAnalyzer withClaudeCode() {
        return new SecurityAnalyzer(new ClaudeCodeAiAnalyzer());
    }

    /**
     * Create an analyzer that uses Claude Code CLI with a custom timeout.
     */
    public static SecurityAnalyzer withClaudeCode(Duration timeout) {
        return new SecurityAnalyzer(new ClaudeCodeAiAnalyzer("claude", timeout));
    }

    /**
     * Create an analyzer with a custom AI implementation.
     */
    public static SecurityAnalyzer withAi(AiAnalyzer aiAnalyzer) {
        return new SecurityAnalyzer(aiAnalyzer);
    }

    /**
     * Analyze a decompiled result with default options.
     */
    public AnalysisReport analyze(DecompileResult result) {
        return analyze(result, AnalysisOptions.defaults());
    }

    /**
     * Analyze a decompiled result with custom options.
     */
    public AnalysisReport analyze(DecompileResult result, AnalysisOptions options) {
        var findings = new ArrayList<Finding>();

        // Tier 1: Rule-based (fast, deterministic)
        if (options.enableRules() && result.hir() != null) {
            findings.addAll(ruleEngine.analyze(result));
        }

        // Tier 2: AI-powered (optional)
        if (options.enableAi() && aiAnalyzer != null && result.javaSource() != null) {
            findings.addAll(aiAnalyzer.analyze(result.javaSource(), result.stats()));
        }

        // Filter by skip categories
        List<Finding> filtered = findings.stream()
                .filter(f -> !options.skipCategories().contains(f.category()))
                .toList();

        return buildReport(filtered, result.stats());
    }

    /**
     * Convenience: decompile a CBOR hex script and analyze it in one call.
     */
    public static AnalysisReport analyzeScript(String cborHex, SecurityAnalyzer analyzer) {
        var result = JulcDecompiler.decompile(cborHex);
        return analyzer.analyze(result);
    }

    private AnalysisReport buildReport(List<Finding> findings, ScriptAnalyzer.ScriptStats stats) {
        int critical = 0, high = 0, medium = 0, low = 0, info = 0;
        for (var f : findings) {
            switch (f.severity()) {
                case CRITICAL -> critical++;
                case HIGH -> high++;
                case MEDIUM -> medium++;
                case LOW -> low++;
                case INFO -> info++;
            }
        }

        String riskLevel;
        if (critical > 0) riskLevel = "CRITICAL";
        else if (high > 0) riskLevel = "HIGH";
        else if (medium > 0) riskLevel = "MEDIUM";
        else if (low > 0) riskLevel = "LOW";
        else if (info > 0) riskLevel = "INFO";
        else riskLevel = "SAFE";

        var summary = new AnalysisReport.AnalysisSummary(
                critical, high, medium, low, info, riskLevel, null);

        return new AnalysisReport(findings, stats, summary);
    }
}
