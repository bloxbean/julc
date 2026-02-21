package com.bloxbean.cardano.julc.analyzer.cli;

import com.bloxbean.cardano.julc.analysis.AnalysisReport;
import com.bloxbean.cardano.julc.decompiler.DecompileResult;

/**
 * Formats an {@link AnalysisReport} for output.
 */
public sealed interface OutputFormatter
        permits AnsiReportFormatter, PlainReportFormatter, JsonReportFormatter {

    String format(AnalysisReport report, boolean verbose, boolean quiet);

    default String format(AnalysisReport report, boolean verbose, boolean quiet,
                          DecompileResult decompiled, String showCode) {
        var sb = new StringBuilder();
        sb.append(format(report, verbose, quiet));
        if (showCode != null && !"none".equalsIgnoreCase(showCode) && decompiled != null) {
            sb.append(formatCode(decompiled, showCode));
        }
        return sb.toString();
    }

    default String formatCode(DecompileResult decompiled, String showCode) {
        return "";
    }

    static OutputFormatter create(boolean json, boolean noColor) {
        if (json) {
            return new JsonReportFormatter();
        }
        if (noColor || System.getenv("NO_COLOR") != null) {
            return new PlainReportFormatter();
        }
        return new AnsiReportFormatter();
    }
}
