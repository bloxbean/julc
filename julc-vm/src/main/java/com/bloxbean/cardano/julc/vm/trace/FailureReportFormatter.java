package com.bloxbean.cardano.julc.vm.trace;

/**
 * Plain-text formatter for {@link FailureReport}.
 * <p>
 * Produces output like:
 * <pre>
 * FAIL at .(VestingValidator.java:42)  return deadline <= currentSlot
 *   LessThanEqualsInteger(5, 3) → False
 *
 *   Last builtins:
 *     UnIData(&lt;Data&gt;) → 5
 *     UnIData(&lt;Data&gt;) → 3
 *     LessThanEqualsInteger(5, 3) → False
 *
 *   Budget: CPU=1,234,567  Mem=45,678
 * </pre>
 */
public final class FailureReportFormatter {

    private FailureReportFormatter() {}

    /**
     * Format a failure report as plain text.
     *
     * @param report the failure report to format
     * @return multi-line formatted string
     */
    public static String format(FailureReport report) {
        var sb = new StringBuilder();

        // Header: FAIL at .(File.java:line) fragment
        sb.append("FAIL");
        if (report.sourceLocation() != null) {
            sb.append(' ').append(report.sourceLocation());
        } else {
            sb.append(": ").append(report.errorMessage());
        }
        sb.append('\n');

        // Highlighted cause: the last comparison builtin that returned False
        var causeBuiltin = report.findCauseBuiltin();
        if (causeBuiltin != null) {
            sb.append("  ").append(causeBuiltin).append('\n');
        }

        // Last builtins section
        if (!report.lastBuiltins().isEmpty()) {
            sb.append('\n');
            sb.append("  Last builtins:\n");
            for (var exec : report.lastBuiltins()) {
                sb.append("    ").append(exec).append('\n');
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

        // Budget
        sb.append('\n');
        sb.append(String.format("  Budget: CPU=%s  Mem=%s%n",
                String.format("%,d", report.consumed().cpuSteps()),
                String.format("%,d", report.consumed().memoryUnits())));

        return sb.toString();
    }
}
