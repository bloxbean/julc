package com.bloxbean.cardano.julc.vm.trace;

import java.util.*;

/**
 * A single entry in the execution trace — records one CEK machine step
 * mapped back to a Java source location, with optional budget deltas.
 *
 * @param fileName the Java source file name (e.g., "MyValidator.java")
 * @param line     the 1-based line number
 * @param fragment the Java expression snippet (nullable, for display)
 * @param nodeType the CEK machine step type: "Apply", "Force", "Case", or "Error"
 * @param cpuDelta CPU consumed since the previous trace point
 * @param memDelta memory consumed since the previous trace point
 */
public record ExecutionTraceEntry(String fileName, int line, String fragment, String nodeType,
                                  long cpuDelta, long memDelta) {

    /**
     * Backward-compatible constructor — budget deltas default to 0.
     */
    public ExecutionTraceEntry(String fileName, int line, String fragment, String nodeType) {
        this(fileName, line, fragment, nodeType, 0, 0);
    }

    @Override
    public String toString() {
        var sb = new StringBuilder();
        // Use "at .(File.java:line)" format for IntelliJ clickable hyperlinks
        sb.append("at .(").append(fileName).append(':').append(line).append(')');
        sb.append(" [").append(nodeType).append(']');
        if (cpuDelta > 0) {
            sb.append("  CPU: +").append(String.format("%,d", cpuDelta));
        }
        if (fragment != null && !fragment.isEmpty()) {
            // Use only the first line and truncate for readability
            var frag = fragment;
            int newline = frag.indexOf('\n');
            if (newline >= 0) frag = frag.substring(0, newline).stripTrailing();
            if (frag.length() > 80) frag = frag.substring(0, 77) + "...";
            sb.append("  ").append(frag);
        }
        return sb.toString();
    }

    /**
     * Format a list of trace entries as a readable multi-line string.
     * <p>
     * Output uses a stacktrace-like format so IntelliJ makes file:line references
     * clickable in the console — clicking jumps to the source line in the editor.
     * <p>
     * When budget deltas are present, the header shows totals.
     */
    public static String format(List<ExecutionTraceEntry> entries) {
        if (entries.isEmpty()) return "Execution trace: (empty)";
        var sb = new StringBuilder();
        long totalCpu = 0, totalMem = 0;
        for (var e : entries) {
            totalCpu += e.cpuDelta;
            totalMem += e.memDelta;
        }
        sb.append("Execution trace (").append(entries.size()).append(" steps");
        if (totalCpu > 0) {
            sb.append(", CPU: ").append(String.format("%,d", totalCpu));
            sb.append(", Mem: ").append(String.format("%,d", totalMem));
        }
        sb.append("):\n");
        for (int i = 0; i < entries.size(); i++) {
            sb.append(String.format("  %3d. %s%n", i + 1, entries.get(i)));
        }
        return sb.toString();
    }

    /**
     * Format a per-file/line budget summary, aggregating visits and costs.
     * <p>
     * Example output:
     * <pre>
     * Budget summary by source location:
     *   EscrowValidator.java:
     *     Line 20: CPU=46,200   Mem=1,200   (2 visits)
     *     Line 46: CPU=3,200,000 Mem=120,000 (1 visit)
     *   Total: CPU=27,968,396  Mem=112,182
     * </pre>
     */
    public static String formatSummary(List<ExecutionTraceEntry> entries) {
        if (entries.isEmpty()) return "Budget summary: (empty)\n";

        // Aggregate by file -> line
        var fileMap = new LinkedHashMap<String, Map<Integer, long[]>>();
        long totalCpu = 0, totalMem = 0;
        for (var e : entries) {
            var lineMap = fileMap.computeIfAbsent(e.fileName, _ -> new LinkedHashMap<>());
            // long[3] = {cpu, mem, visits}
            var acc = lineMap.computeIfAbsent(e.line, _ -> new long[3]);
            acc[0] += e.cpuDelta;
            acc[1] += e.memDelta;
            acc[2]++;
            totalCpu += e.cpuDelta;
            totalMem += e.memDelta;
        }

        var sb = new StringBuilder();
        sb.append("Budget summary by source location:\n");
        for (var fileEntry : fileMap.entrySet()) {
            var file = fileEntry.getKey();
            for (var lineEntry : fileEntry.getValue().entrySet()) {
                var acc = lineEntry.getValue();
                // Use "at .(File.java:line)" format for IntelliJ clickable hyperlinks
                sb.append(String.format("  at .(%s:%d)  CPU=%-12s Mem=%-12s (%d %s)%n",
                        file,
                        lineEntry.getKey(),
                        String.format("%,d", acc[0]),
                        String.format("%,d", acc[1]),
                        (int) acc[2],
                        acc[2] == 1 ? "visit" : "visits"));
            }
        }
        sb.append(String.format("  Total: CPU=%s  Mem=%s%n",
                String.format("%,d", totalCpu),
                String.format("%,d", totalMem)));
        return sb.toString();
    }
}
