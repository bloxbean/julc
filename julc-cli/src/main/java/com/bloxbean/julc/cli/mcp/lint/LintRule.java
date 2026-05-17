package com.bloxbean.julc.cli.mcp.lint;

import com.github.javaparser.ast.CompilationUnit;

import java.util.List;

/**
 * A static-analysis rule applied to a parsed JuLC compilation unit.
 *
 * <p>Rules are deliberately narrow: each catches one specific anti-pattern
 * that is either silently accepted by the compiler (e.g. switch-field shadow)
 * or fails late with a confusing error (e.g. {@code Optional.mkSome}). Rules
 * are designed to be cheap to run so they fit inside an MCP request roundtrip.
 */
public interface LintRule {

    /** Stable identifier — referenced in starter pack section 7 and AGENTS.md. */
    String id();

    /** One-line description, used in {@code julc_lint} tool listing output. */
    String description();

    /** Apply the rule to a parsed CU and return any findings. */
    List<LintFinding> check(CompilationUnit cu);
}
