package com.bloxbean.cardano.julc.analyzer.cli;

import com.bloxbean.cardano.julc.analysis.AnalysisReport;
import com.bloxbean.cardano.julc.decompiler.DecompileResult;

/**
 * Plain text formatter — no ANSI codes.
 */
public final class PlainReportFormatter implements OutputFormatter {

    @Override
    public String format(AnalysisReport report, boolean verbose, boolean quiet) {
        if (quiet) {
            return formatQuiet(report);
        }
        var sb = new StringBuilder();
        sb.append(report.prettyPrint());
        if (verbose && report.stats() != null) {
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
        }
        return sb.toString();
    }

    @Override
    public String formatCode(DecompileResult decompiled, String showCode) {
        var sb = new StringBuilder();
        if ("uplc".equalsIgnoreCase(showCode)) {
            sb.append("\n--- Decompiled Code (uplc) ---\n");
            sb.append(decompiled.uplcText()).append('\n');
        } else {
            sb.append("\n--- Decompiled Code (java) ---\n");
            sb.append(decompiled.javaSource() != null ? decompiled.javaSource() : "/* no java source available */")
                    .append('\n');
        }
        return sb.toString();
    }

    private String formatQuiet(AnalysisReport report) {
        var sb = new StringBuilder();
        for (var f : report.findings()) {
            sb.append('[').append(f.severity()).append("] ").append(f.title()).append('\n');
        }
        return sb.toString();
    }
}
