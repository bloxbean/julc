package com.bloxbean.cardano.julc.analyzer.cli;

import com.bloxbean.cardano.julc.analysis.AnalysisReport;
import com.bloxbean.cardano.julc.analysis.Finding;
import com.bloxbean.cardano.julc.analysis.Severity;
import com.bloxbean.cardano.julc.decompiler.DecompileResult;

/**
 * Rich ANSI-colored terminal output formatter.
 */
public final class AnsiReportFormatter implements OutputFormatter {

    // ANSI codes
    private static final String RESET = "\u001b[0m";
    private static final String BOLD = "\u001b[1m";
    private static final String DIM = "\u001b[2m";
    private static final String RED = "\u001b[31m";
    private static final String GREEN = "\u001b[32m";
    private static final String YELLOW = "\u001b[33m";
    private static final String CYAN = "\u001b[36m";
    private static final String WHITE = "\u001b[37m";
    private static final String BG_RED = "\u001b[41m";

    @Override
    public String format(AnalysisReport report, boolean verbose, boolean quiet) {
        if (quiet) {
            return formatQuiet(report);
        }

        var sb = new StringBuilder();
        var summary = report.summary();

        // Header
        sb.append(BOLD).append(CYAN);
        sb.append("+-----------------------------------------+\n");
        sb.append("|      JuLC Security Analysis Report       |\n");
        sb.append("+-----------------------------------------+\n");
        sb.append(RESET);

        // Risk level
        sb.append(BOLD).append("Risk Level: ").append(colorForRisk(summary.riskLevel()))
                .append(summary.riskLevel()).append(RESET).append('\n');

        if (summary.scriptType() != null) {
            sb.append("Script Type: ").append(summary.scriptType()).append('\n');
        }
        sb.append('\n');

        // Summary counts
        sb.append(BOLD).append("Findings Summary").append(RESET).append(" (")
                .append(report.findings().size()).append(" total)\n");
        sb.append(severityColor(Severity.CRITICAL)).append("  CRITICAL: ").append(summary.criticalCount()).append(RESET).append('\n');
        sb.append(severityColor(Severity.HIGH)).append("  HIGH:     ").append(summary.highCount()).append(RESET).append('\n');
        sb.append(severityColor(Severity.MEDIUM)).append("  MEDIUM:   ").append(summary.mediumCount()).append(RESET).append('\n');
        sb.append(severityColor(Severity.LOW)).append("  LOW:      ").append(summary.lowCount()).append(RESET).append('\n');
        sb.append(severityColor(Severity.INFO)).append("  INFO:     ").append(summary.infoCount()).append(RESET).append('\n');
        sb.append('\n');

        // Findings detail
        var findings = report.findings();
        for (int i = 0; i < findings.size(); i++) {
            var f = findings.get(i);
            boolean last = (i == findings.size() - 1);
            sb.append(formatFinding(f, last));
        }

        // Stats (verbose)
        if (verbose && report.stats() != null) {
            sb.append(DIM);
            sb.append("--- Script Stats ---\n");
            var s = report.stats();
            if (s.plutusVersion() != null) {
                sb.append("  Plutus Version: ").append(s.plutusVersion()).append('\n');
            }
            sb.append("  Total Nodes:    ").append(s.totalNodes()).append('\n');
            sb.append("  Max Depth:      ").append(s.maxDepth()).append('\n');
            sb.append("  Lambdas:        ").append(s.lamCount()).append('\n');
            sb.append("  Applications:   ").append(s.applyCount()).append('\n');
            sb.append("  Builtins Used:  ").append(s.builtinsUsed().size()).append('\n');
            sb.append(RESET);
        }

        return sb.toString();
    }

    private String formatQuiet(AnalysisReport report) {
        var sb = new StringBuilder();
        for (var f : report.findings()) {
            sb.append(severityColor(f.severity()))
                    .append('[').append(f.severity()).append("] ")
                    .append(RESET).append(f.title()).append('\n');
        }
        return sb.toString();
    }

    private String formatFinding(Finding f, boolean last) {
        var sb = new StringBuilder();
        var prefix = last ? "  " : "  ";
        var connector = last ? "\\-- " : "|-- ";

        sb.append(severityColor(f.severity())).append(connector)
                .append('[').append(f.severity()).append("] ").append(RESET)
                .append(BOLD).append(f.category()).append(": ").append(RESET)
                .append(f.title()).append('\n');

        sb.append(prefix).append("    ").append(f.description()).append('\n');

        if (f.location() != null && !f.location().isEmpty()) {
            sb.append(prefix).append("    ").append(DIM).append("Location: ").append(f.location()).append(RESET).append('\n');
        }

        if (f.recommendation() != null && !f.recommendation().isEmpty()) {
            sb.append(prefix).append("    ").append(GREEN).append("Fix: ").append(f.recommendation()).append(RESET).append('\n');
        }

        sb.append('\n');
        return sb.toString();
    }

    private String severityColor(Severity severity) {
        return switch (severity) {
            case CRITICAL -> BOLD + BG_RED + WHITE;
            case HIGH -> BOLD + RED;
            case MEDIUM -> YELLOW;
            case LOW -> CYAN;
            case INFO -> DIM;
        };
    }

    @Override
    public String formatCode(DecompileResult decompiled, String showCode) {
        var sb = new StringBuilder();
        sb.append('\n').append(BOLD).append(CYAN);
        if ("uplc".equalsIgnoreCase(showCode)) {
            sb.append("--- Decompiled Code (uplc) ---").append(RESET).append('\n');
            sb.append(decompiled.uplcText()).append('\n');
        } else {
            sb.append("--- Decompiled Code (java) ---").append(RESET).append('\n');
            sb.append(decompiled.javaSource() != null ? decompiled.javaSource() : "/* no java source available */")
                    .append('\n');
        }
        return sb.toString();
    }

    private String colorForRisk(String riskLevel) {
        return switch (riskLevel) {
            case "CRITICAL" -> BOLD + BG_RED + WHITE;
            case "HIGH" -> BOLD + RED;
            case "MEDIUM" -> YELLOW;
            case "LOW" -> CYAN;
            case "SAFE" -> GREEN;
            default -> "";
        };
    }
}
