package com.bloxbean.cardano.julc.analyzer.cli;

import com.bloxbean.cardano.julc.analysis.AnalysisReport;
import com.bloxbean.cardano.julc.analysis.Finding;
import com.bloxbean.cardano.julc.decompiler.DecompileResult;

/**
 * Machine-readable JSON output formatter.
 * Uses manual JSON construction (no external JSON library).
 */
public final class JsonReportFormatter implements OutputFormatter {

    @Override
    public String format(AnalysisReport report, boolean verbose, boolean quiet) {
        var sb = new StringBuilder();
        sb.append("{\n");

        var summary = report.summary();
        sb.append("  \"riskLevel\": ").append(jsonString(summary.riskLevel())).append(",\n");

        // Summary
        sb.append("  \"summary\": {\n");
        sb.append("    \"critical\": ").append(summary.criticalCount()).append(",\n");
        sb.append("    \"high\": ").append(summary.highCount()).append(",\n");
        sb.append("    \"medium\": ").append(summary.mediumCount()).append(",\n");
        sb.append("    \"low\": ").append(summary.lowCount()).append(",\n");
        sb.append("    \"info\": ").append(summary.infoCount()).append(",\n");
        sb.append("    \"total\": ").append(report.findings().size()).append("\n");
        sb.append("  },\n");

        // Stats
        if (verbose && report.stats() != null) {
            var s = report.stats();
            sb.append("  \"stats\": {\n");
            sb.append("    \"plutusVersion\": ").append(jsonString(s.plutusVersion() != null ? s.plutusVersion().toString() : null)).append(",\n");
            sb.append("    \"totalNodes\": ").append(s.totalNodes()).append(",\n");
            sb.append("    \"maxDepth\": ").append(s.maxDepth()).append(",\n");
            sb.append("    \"lambdas\": ").append(s.lamCount()).append(",\n");
            sb.append("    \"applications\": ").append(s.applyCount()).append(",\n");
            sb.append("    \"builtinsUsed\": ").append(s.builtinsUsed().size()).append("\n");
            sb.append("  },\n");
        }

        // Findings
        sb.append("  \"findings\": [\n");
        var findings = report.findings();
        for (int i = 0; i < findings.size(); i++) {
            sb.append(formatFinding(findings.get(i)));
            if (i < findings.size() - 1) {
                sb.append(",");
            }
            sb.append('\n');
        }
        sb.append("  ]\n");

        sb.append("}\n");
        return sb.toString();
    }

    @Override
    public String format(AnalysisReport report, boolean verbose, boolean quiet,
                          DecompileResult decompiled, String showCode) {
        boolean includeCode = showCode != null && !"none".equalsIgnoreCase(showCode) && decompiled != null;
        String base = format(report, verbose, quiet);
        if (!includeCode) {
            return base;
        }
        // Insert "code" field before the closing "}\n"
        String format = "uplc".equalsIgnoreCase(showCode) ? "uplc" : "java";
        String content = "uplc".equalsIgnoreCase(showCode)
                ? decompiled.uplcText()
                : (decompiled.javaSource() != null ? decompiled.javaSource() : "");
        var codeSb = new StringBuilder();
        codeSb.append(",\n");
        codeSb.append("  \"code\": {\n");
        codeSb.append("    \"format\": ").append(jsonString(format)).append(",\n");
        codeSb.append("    \"content\": ").append(jsonString(content)).append('\n');
        codeSb.append("  }\n");
        // Replace the last "}\n" with the code block + "}\n"
        int lastBrace = base.lastIndexOf("}\n");
        if (lastBrace >= 0) {
            return base.substring(0, lastBrace) + codeSb + "}\n";
        }
        return base + codeSb + "}\n";
    }

    private String formatFinding(Finding f) {
        var sb = new StringBuilder();
        sb.append("    {\n");
        sb.append("      \"severity\": ").append(jsonString(f.severity().name())).append(",\n");
        sb.append("      \"category\": ").append(jsonString(f.category().name())).append(",\n");
        sb.append("      \"title\": ").append(jsonString(f.title())).append(",\n");
        sb.append("      \"description\": ").append(jsonString(f.description())).append(",\n");
        sb.append("      \"location\": ").append(jsonString(f.location())).append(",\n");
        sb.append("      \"recommendation\": ").append(jsonString(f.recommendation())).append('\n');
        sb.append("    }");
        return sb.toString();
    }

    static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        var sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
