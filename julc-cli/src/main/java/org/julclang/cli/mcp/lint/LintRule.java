package org.julclang.cli.mcp.lint;

import com.github.javaparser.ast.CompilationUnit;
import org.julclang.compiler.frontend.javac.JavacSyntax;
import org.julclang.compiler.frontend.javac.SourceReader;

import java.util.List;

/**
 * A static-analysis rule applied to a parsed JuLC compilation unit.
 *
 * <p>Rules are deliberately narrow: each catches one specific anti-pattern
 * that is either silently accepted by the compiler (e.g. switch-field shadow)
 * or fails late with a confusing error (e.g. {@code Optional.mkSome}). Rules
 * are designed to be cheap to run so they fit inside an MCP request roundtrip.
 *
 * <p>A rule reads JavaParser's tree or javac's (ADR-068 M6), with the same findings; {@link LintEngine} chooses.
 * M6 deletes the JavaParser reading.
 */
public interface LintRule {

    /** Stable identifier — referenced in starter pack section 7 and AGENTS.md. */
    String id();

    /** One-line description, used in {@code julc_lint} tool listing output. */
    String description();

    /** Apply the rule to a parsed CU and return any findings. */
    List<LintFinding> check(CompilationUnit cu);

    /**
     * Whether the rule reads sources on {@code reader}. A rule written before javac's reading (ADR-068 M6) reads only
     * JavaParser's; {@link LintEngine} reports it as not running on javac's, rather than calling it.
     */
    default boolean supports(SourceReader reader) {
        return reader == SourceReader.JAVAPARSER;
    }

    /**
     * Apply the rule to javac's tree of the source and return the findings {@link #check(CompilationUnit)} returns.
     * Only called when {@link #supports} says the rule reads javac's tree.
     */
    default List<LintFinding> check(JavacSyntax syntax) {
        throw new UnsupportedOperationException("Lint rule " + id() + " has no javac reading");
    }
}
