package com.bloxbean.cardano.julc.analysis;

import com.bloxbean.cardano.julc.decompiler.input.ScriptAnalyzer;

import java.util.List;

/**
 * Complete security analysis result.
 *
 * @param findings all detected findings
 * @param stats    script structural statistics
 * @param summary  aggregated summary
 */
public record AnalysisReport(
        List<Finding> findings,
        ScriptAnalyzer.ScriptStats stats,
        AnalysisSummary summary
) {
    public record AnalysisSummary(
            int criticalCount,
            int highCount,
            int mediumCount,
            int lowCount,
            int infoCount,
            String riskLevel,
            String scriptType
    ) {}

    public List<Finding> criticals() {
        return findings.stream().filter(f -> f.severity() == Severity.CRITICAL).toList();
    }

    public List<Finding> highs() {
        return findings.stream().filter(f -> f.severity() == Severity.HIGH).toList();
    }

    public String prettyPrint() {
        var sb = new StringBuilder();
        sb.append("=== Security Analysis Report ===\n");
        sb.append("Risk Level: ").append(summary.riskLevel()).append('\n');
        if (summary.scriptType() != null) {
            sb.append("Script Type: ").append(summary.scriptType()).append('\n');
        }
        sb.append('\n');
        sb.append("Findings: ").append(findings.size()).append(" total\n");
        sb.append("  CRITICAL: ").append(summary.criticalCount()).append('\n');
        sb.append("  HIGH:     ").append(summary.highCount()).append('\n');
        sb.append("  MEDIUM:   ").append(summary.mediumCount()).append('\n');
        sb.append("  LOW:      ").append(summary.lowCount()).append('\n');
        sb.append("  INFO:     ").append(summary.infoCount()).append('\n');
        sb.append('\n');

        for (var finding : findings) {
            sb.append("[").append(finding.severity()).append("] ")
                    .append(finding.category()).append(": ")
                    .append(finding.title()).append('\n');
            sb.append("  ").append(finding.description()).append('\n');
            if (finding.location() != null && !finding.location().isEmpty()) {
                sb.append("  Location: ").append(finding.location()).append('\n');
            }
            if (finding.recommendation() != null && !finding.recommendation().isEmpty()) {
                sb.append("  Fix: ").append(finding.recommendation()).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
