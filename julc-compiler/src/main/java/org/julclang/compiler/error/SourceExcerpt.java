package org.julclang.compiler.error;

import java.util.regex.Pattern;

/**
 * The source line of a diagnostic with the problem underlined, as a compiler for people shows it:
 * <pre>
 *   12 |     case A(var v) -&gt; v;
 *      |          ^^^^^^^^
 * </pre>
 */
public final class SourceExcerpt {

    /** A longer line is cut around the column, so that a one-line generated source stays readable. */
    private static final int MAX_WIDTH = 100;
    private static final Pattern LINE_END = Pattern.compile("\r\n|\r|\n");

    private SourceExcerpt() {}

    /** The excerpt, or null when {@code source} has no such position or the line is blank. {@code line} and {@code column} count from 1. */
    public static String of(String source, int line, int column) {
        if (source == null || line < 1 || column < 1) return null;
        String[] lines = LINE_END.split(source, -1);
        if (line > lines.length) return null;
        String text = lines[line - 1];
        if (text.isBlank() || column > text.length() + 1) return null;

        int start = column - 1;
        int end = Math.max(start + 1, tokenEnd(text, start));
        int from = text.length() <= MAX_WIDTH ? 0 : Math.max(0, start - MAX_WIDTH / 4);
        int to = Math.min(text.length(), from + MAX_WIDTH);
        // A cut does not split a character that takes two UTF-16 units.
        if (from > 0 && Character.isLowSurrogate(text.charAt(from))) from++;
        if (to < text.length() && Character.isHighSurrogate(text.charAt(to - 1))) to--;
        end = Math.min(end, Math.max(to, start + 1));

        String shown = (from > 0 ? "..." : "") + text.substring(from, to) + (to < text.length() ? "..." : "");
        var pad = new StringBuilder(from > 0 ? "   " : "");
        for (int i = from; i < start; i++) pad.append(text.charAt(i) == '\t' ? '\t' : ' ');

        String number = String.valueOf(line);
        return number + " | " + shown + "\n" + " ".repeat(number.length()) + " | " + pad + "^".repeat(end - start);
    }

    /** The end of what starts at {@code start}: a name with any call or pattern parentheses after it, a string, or else one character. */
    private static int tokenEnd(String text, int start) {
        if (start >= text.length()) return start + 1;
        char first = text.charAt(start);
        if (text.startsWith("\"\"\"", start)) return start + 3;
        if (first == '"') {
            for (int i = start + 1; i < text.length(); i++) {
                if (text.charAt(i) == '\\') i++;
                else if (text.charAt(i) == '"') return i + 1;
            }
            return text.length();
        }
        boolean annotation = first == '@' && start + 1 < text.length() && Character.isJavaIdentifierPart(text.charAt(start + 1));
        if (!annotation && !Character.isJavaIdentifierPart(first)) return start + 1;
        int end = annotation ? start + 1 : start;
        while (end < text.length() && (Character.isJavaIdentifierPart(text.charAt(end))
                || text.charAt(end) == '.' && end + 1 < text.length() && Character.isJavaIdentifierPart(text.charAt(end + 1)))) {
            end++;
        }
        if (end == text.length() || text.charAt(end) != '(') return end;
        int depth = 0;
        for (int i = end; i < text.length(); i++) {
            if (text.charAt(i) == '(') depth++;
            else if (text.charAt(i) == ')' && --depth == 0) return i + 1;
        }
        return end;
    }
}
