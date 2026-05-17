package com.bloxbean.julc.cli.mcp.lint;

/**
 * A single lint finding produced by a {@link LintRule}.
 *
 * @param ruleId      stable rule identifier, e.g. {@code "JULC-LINT-001"}.
 * @param diagnostic  optional JULC#### code if this lint corresponds to a
 *                    catalog entry; null otherwise.
 * @param level       one of {@code "error"}, {@code "warning"}, {@code "info"}.
 * @param message     one-line description.
 * @param line        1-based line number; 0 if unknown.
 * @param column      1-based column; 0 if unknown.
 * @param suggestion  actionable fix snippet for the AI agent.
 */
public record LintFinding(
        String ruleId,
        String diagnostic,
        String level,
        String message,
        int line,
        int column,
        String suggestion
) {

    public static LintFinding warning(String ruleId, String diagnostic, String message,
                                      int line, int column, String suggestion) {
        return new LintFinding(ruleId, diagnostic, "warning", message, line, column, suggestion);
    }

    public static LintFinding error(String ruleId, String diagnostic, String message,
                                    int line, int column, String suggestion) {
        return new LintFinding(ruleId, diagnostic, "error", message, line, column, suggestion);
    }
}
