package org.julclang.cli.mcp.lint;

import org.julclang.compiler.frontend.javac.JavacSyntax;

import java.util.List;

/**
 * A static-analysis rule applied to a parsed JuLC compilation unit.
 *
 * <p>Rules are deliberately narrow: each catches one specific anti-pattern
 * that is either silently accepted by the compiler (e.g. switch-field shadow)
 * or fails late with a confusing error (e.g. {@code Optional.mkSome}). Rules
 * are designed to be cheap to run so they fit inside an MCP request roundtrip.
 *
 * <p>A rule reads javac's syntax tree of the source, without attribution ({@link JavacSyntax}).
 */
public interface LintRule {

    /** Stable identifier — referenced in starter pack section 7 and AGENTS.md. */
    String id();

    /** One-line description, used in {@code julc_lint} tool listing output. */
    String description();

    /** Apply the rule to javac's tree of the source and return any findings. */
    List<LintFinding> check(JavacSyntax syntax);
}
