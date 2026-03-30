package com.bloxbean.julc.cli.output;

import com.bloxbean.cardano.julc.vm.trace.BuiltinExecution;
import com.bloxbean.cardano.julc.vm.trace.FailureReport;

/**
 * ANSI-colored formatter for {@link FailureReport}.
 * <p>
 * Uses {@link AnsiColors} for terminal output:
 * <ul>
 *   <li>{@code FAIL} in red, source location in bold</li>
 *   <li>Builtin names in cyan, {@code → False} in red, {@code → True} in green</li>
 *   <li>Budget numbers in dim</li>
 * </ul>
 */
public final class AnsiFailureReportFormatter {

    private AnsiFailureReportFormatter() {}

    /**
     * Format a failure report with ANSI colors.
     *
     * @param report the failure report to format
     * @return multi-line ANSI-colored string
     */
    public static String format(FailureReport report) {
        var sb = new StringBuilder();

        // Header: FAIL in red, source location in bold
        sb.append(AnsiColors.red("FAIL"));
        if (report.sourceLocation() != null) {
            sb.append(' ').append(AnsiColors.bold(report.sourceLocation().toString()));
        } else {
            sb.append(": ").append(report.errorMessage());
        }
        sb.append('\n');

        // Highlighted cause
        var causeBuiltin = report.findCauseBuiltin();
        if (causeBuiltin != null) {
            sb.append("  ").append(formatBuiltinExecution(causeBuiltin)).append('\n');
        }

        // Last builtins section
        if (!report.lastBuiltins().isEmpty()) {
            sb.append('\n');
            sb.append("  Last builtins:\n");
            for (var exec : report.lastBuiltins()) {
                sb.append("    ").append(formatBuiltinExecution(exec)).append('\n');
            }
        }

        // Trace messages
        if (!report.traceMessages().isEmpty()) {
            sb.append('\n');
            sb.append("  Traces:\n");
            for (var msg : report.traceMessages()) {
                sb.append("    ").append(msg).append('\n');
            }
        }

        // Budget in dim
        sb.append('\n');
        sb.append(AnsiColors.dim(String.format("  Budget: CPU=%s  Mem=%s",
                String.format("%,d", report.consumed().cpuSteps()),
                String.format("%,d", report.consumed().memoryUnits()))));
        sb.append('\n');

        return sb.toString();
    }

    private static String formatBuiltinExecution(BuiltinExecution exec) {
        var sb = new StringBuilder();
        sb.append(AnsiColors.cyan(exec.fun().toString()));
        sb.append('(').append(exec.argSummary()).append(") → ");
        if ("False".equals(exec.resultSummary())) {
            sb.append(AnsiColors.red(exec.resultSummary()));
        } else if ("True".equals(exec.resultSummary())) {
            sb.append(AnsiColors.green(exec.resultSummary()));
        } else {
            sb.append(exec.resultSummary());
        }
        return sb.toString();
    }
}
