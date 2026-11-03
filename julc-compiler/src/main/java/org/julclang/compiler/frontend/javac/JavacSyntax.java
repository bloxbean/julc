package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.util.List;
import java.util.Locale;

/**
 * One source as javac's parser reads it, with no attribution: the tree, its positions and the parser's errors. It is
 * for JuLC's tools that read a source's shape without compiling it (lint rules, {@code julc check}'s test discovery, the
 * MCP tools, the metadata extractor and the verification resolvers; ADR-068 M6), so that they read Java as the
 * compiler does. It comes from {@link JavacFrontend#parse}, in the frontend's environment and with its options (D3).
 * <p>
 * Lines are javac's; columns are character offsets from the line start, 1-based (I7), so a tab counts as one column.
 * A position is that of the source as written, Unicode escapes included.
 *
 * @param text      the source
 * @param unit      javac's tree of it; after a syntax error, the tree javac's parser recovered
 * @param positions the trees' start and end offsets
 * @param errors    the parser's errors, in emission order
 */
public record JavacSyntax(String text, CompilationUnitTree unit, SourcePositions positions,
                          List<Diagnostic<? extends JavaFileObject>> errors) {

    public JavacSyntax {
        errors = List.copyOf(errors);
    }

    /**
     * {@code text} read by the shared environment's parser ({@link JavacFrontend#shared()}). Throws
     * {@link IllegalStateException} with {@code JULC0089}'s message when the host's javac is not supported.
     */
    public static JavacSyntax read(String text) {
        JavacFrontend frontend = JavacFrontend.shared();
        return frontend.parse(text).orElseThrow(() -> new IllegalStateException(
                frontend.supported().diagnostics().getFirst().message()));
    }

    /** Whether javac's parser rejected the source. */
    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    /** Each error as {@code line:column: message}, in English, the column as {@link #column(long)} gives it. */
    public List<String> errorMessages() {
        return errors.stream()
                .map(d -> d.getLineNumber() + ":" + column(d.getPosition()) + ": " + d.getMessage(Locale.ENGLISH))
                .toList();
    }

    /** The start offset of {@code tree}, or -1. */
    public long start(Tree tree) {
        return positions.getStartPosition(unit, tree);
    }

    /** The end offset of {@code tree} (exclusive), or -1. */
    public long end(Tree tree) {
        return positions.getEndPosition(unit, tree);
    }

    /** The 1-based line of {@code tree}'s start, or 0 when it has no position. */
    public int line(Tree tree) {
        return line(start(tree));
    }

    /** The 1-based column of {@code tree}'s start, or 0 when it has no position (I7). */
    public int column(Tree tree) {
        return column(start(tree));
    }

    /** The 1-based line of {@code offset}, or 0 when it is negative. */
    public int line(long offset) {
        if (offset < 0) return 0;
        return (int) lineMap().getLineNumber(offset);
    }

    /** The 1-based column of {@code offset} as a character offset from its line's start, or 0 (I7). */
    public int column(long offset) {
        if (offset < 0) return 0;
        LineMap map = lineMap();
        return (int) (offset - map.getStartPosition(map.getLineNumber(offset)) + 1);
    }

    /** The source text of {@code tree} as written, or the empty string when it has no position. */
    public String source(Tree tree) {
        long start = start(tree);
        long end = end(tree);
        if (start < 0 || end < start || end > text.length()) return "";
        return text.substring((int) start, (int) end);
    }

    /** The path from the unit to {@code tree}, or null when the unit does not hold it. */
    public TreePath path(Tree tree) {
        return TreePath.getPath(unit, tree);
    }

    /**
     * The offset of the first character at or after {@code from} that is not whitespace or in a comment, or -1. javac's
     * API gives a declaration's start, not its name's, which is the next token after its type.
     */
    public long nextToken(long from) {
        if (from < 0) return -1;
        int i = (int) from;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (text.startsWith("//", i)) {
                int eol = text.indexOf('\n', i);
                i = eol < 0 ? n : eol + 1;
            } else if (text.startsWith("/*", i)) {
                int close = text.indexOf("*/", i + 2);
                i = close < 0 ? n : close + 2;
            } else {
                return i;
            }
        }
        return -1;
    }

    private LineMap lineMap() {
        return unit.getLineMap();
    }
}
