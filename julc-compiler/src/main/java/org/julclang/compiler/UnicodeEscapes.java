package org.julclang.compiler;

import com.github.javaparser.Position;
import com.github.javaparser.Range;

import java.util.Arrays;

/**
 * Translates the Unicode escapes of a Java source as javac does (JLS 21 §3.3), for {@link JavaSourceParser}
 * (ADR-062 T24), and maps positions in the translated text back to the source as written.
 * <p>
 * An escape is a backslash preceded by an even number of raw backslashes, one or more {@code u}, and four
 * ASCII hex digits. A backslash an escape produces starts no further escape. The source is rejected
 * where javac rejects it, and where javac's reading differs from the JLS or cannot be kept:
 * <ul>
 *   <li>a backslash that can start an escape and is followed by {@code u}, but not by four ASCII hex digits.
 *       javac rejects this too, except that it also takes other scripts' digits ({@code Character.digit});</li>
 *   <li>a backslash produced by an escape, at the end of an odd run of such escapes, followed by a raw
 *       backslash. javac counts the escaped backslash toward the parity of the raw backslashes after it, so
 *       it reads a later escape differently from the JLS. (A raw backslash followed by {@code u} there is
 *       read alike by both, and is not rejected.);</li>
 *   <li>an unpaired surrogate. javac's lookahead for the low half of a pair changes how it counts the
 *       backslashes after an unpaired high half, and a UPLC string cannot hold one.</li>
 * </ul>
 * Positions follow JavaParser's convention: lines and columns from 1, one column per UTF-16 unit, and
 * CR, LF or CR LF ending a line.
 */
final class UnicodeEscapes {

    /** A source JuLC rejects, with the position of the offending text as written. */
    static final class IllegalSourceException extends Exception {
        private final Position position;

        IllegalSourceException(String message, Position position) {
            super(message);
            this.position = position;
        }

        Position position() {
            return position;
        }
    }

    private final String text;
    /** Per translated unit, the position as written of its first and last character; null for an identity. */
    private final int[] beginLine;
    private final int[] beginColumn;
    private final int[] endLine;
    private final int[] endColumn;
    /** The index of the first unit of each translated line, from line 1; slot 0 is unused. */
    private final int[] lineStarts;

    private UnicodeEscapes(String text, int[] beginLine, int[] beginColumn, int[] endLine, int[] endColumn) {
        this.text = text;
        this.beginLine = beginLine;
        this.beginColumn = beginColumn;
        this.endLine = endLine;
        this.endColumn = endColumn;
        this.lineStarts = beginLine == null ? null : lineStarts(text);
    }

    /** The translated source. */
    String text() {
        return text;
    }

    /** Whether the source has no escape, so the translated text is the source and positions need no mapping. */
    boolean isIdentity() {
        return beginLine == null;
    }

    static UnicodeEscapes translate(String source) throws IllegalSourceException {
        if (source.indexOf('\\') < 0) {
            requirePairedSurrogates(source, null, null);
            return new UnicodeEscapes(source, null, null, null, null);
        }
        int n = source.length();
        var s = new StringBuilder(n);
        int[] bl = new int[n], bc = new int[n], el = new int[n], ec = new int[n];
        boolean translated = false;
        int line = 1, column = 1;   // the position as written of source index i
        int backslashes = 0;        // raw backslashes immediately before i
        int escapedBackslashes = 0; // escapes producing a backslash immediately before i
        int i = 0;
        while (i < n) {
            char c = source.charAt(i);
            if (c == '\\' && backslashes % 2 == 0 && i + 1 < n && source.charAt(i + 1) == 'u') {
                int digits = i + 1;
                while (digits < n && source.charAt(digits) == 'u') digits++;
                if (digits + 4 > n || !isHex(source, digits)) {
                    throw new IllegalSourceException("Illegal Unicode escape: a backslash and u must be followed by"
                            + " four hex digits 0-9, a-f or A-F (JLS 21 §3.3)", new Position(line, column));
                }
                char unit = (char) Integer.parseInt(source.substring(digits, digits + 4), 16);
                int k = s.length();
                s.append(unit);
                bl[k] = line;
                bc[k] = column;
                el[k] = line;
                ec[k] = column + (digits + 3 - i);
                column += digits + 4 - i;
                translated = true;
                escapedBackslashes = unit == '\\' ? escapedBackslashes + 1 : 0;
                backslashes = 0;
                i = digits + 4;
                continue;
            }
            if (c == '\\' && escapedBackslashes % 2 == 1) {
                throw new IllegalSourceException("A backslash written as a Unicode escape is followed by a raw"
                        + " backslash. javac counts the escaped backslash toward the raw backslashes after it, and"
                        + " the JLS does not, so they read a later Unicode escape differently. Write the first"
                        + " backslash as itself", new Position(line, column));
            }
            int k = s.length();
            s.append(c);
            bl[k] = el[k] = line;
            bc[k] = ec[k] = column;
            if (c == '\n' || c == '\r' && (i + 1 == n || source.charAt(i + 1) != '\n')) {
                line++;
                column = 1;
            } else {
                column++;
            }
            backslashes = c == '\\' ? backslashes + 1 : 0;
            escapedBackslashes = 0;
            i++;
        }
        String text = s.toString();
        if (!translated) {
            requirePairedSurrogates(source, null, null);
            return new UnicodeEscapes(source, null, null, null, null);
        }
        int m = text.length();
        bl = Arrays.copyOf(bl, m);
        bc = Arrays.copyOf(bc, m);
        requirePairedSurrogates(text, bl, bc);
        return new UnicodeEscapes(text, bl, bc, Arrays.copyOf(el, m), Arrays.copyOf(ec, m));
    }

    private static void requirePairedSurrogates(String text, int[] lines, int[] columns) throws IllegalSourceException {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                var position = lines != null ? new Position(lines[i], columns[i]) : positionOf(text, i);
                throw new IllegalSourceException(String.format("The source holds the unpaired surrogate U+%04X."
                        + " A UPLC string cannot hold one, and javac reads the backslashes after one differently"
                        + " from the JLS. Write a supplementary character as its high surrogate followed by its low"
                        + " surrogate, or remove it", (int) c), position);
            }
        }
    }

    /** The position of index {@code i} in text that needed no translation. */
    private static Position positionOf(String text, int i) {
        int line = 1, column = 1;
        for (int k = 0; k < i; k++) {
            char c = text.charAt(k);
            if (c == '\n' || c == '\r' && (k + 1 == text.length() || text.charAt(k + 1) != '\n')) {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new Position(line, column);
    }

    private static int[] lineStarts(String text) {
        var starts = new int[8];
        int lines = 1; // starts[1] = 0
        for (int k = 0; k < text.length(); k++) {
            char c = text.charAt(k);
            if (c == '\n' || c == '\r' && (k + 1 == text.length() || text.charAt(k + 1) != '\n')) {
                if (++lines >= starts.length) starts = Arrays.copyOf(starts, starts.length * 2);
                starts[lines] = k + 1;
            }
        }
        return Arrays.copyOf(starts, lines + 1);
    }

    /** A range in the translated text, as written: its end is the last character of an escape it ends on. */
    Range range(Range translated) {
        return new Range(map(translated.begin, false), map(translated.end, true));
    }

    /** A position in the translated text, as written: the first character of an escape it falls on. */
    Position begin(Position translated) {
        return map(translated, false);
    }

    private Position map(Position p, boolean end) {
        if (isIdentity() || p.line < 1 || text.isEmpty()) return p;
        int k = floorIndex(p);
        if (k < 0) return p;
        int unitLine = lineOf(k);
        int unitColumn = k - lineStarts[unitLine] + 1;
        if (unitLine == p.line && unitColumn == p.column) {
            return end ? new Position(endLine[k], endColumn[k]) : new Position(beginLine[k], beginColumn[k]);
        }
        // Past the last unit at or before p (the end of a line or of the text): keep the same offset from it.
        if (unitLine == p.line) return new Position(endLine[k], endColumn[k] + p.column - unitColumn);
        return new Position(endLine[k] + p.line - unitLine, p.column);
    }

    /** The index of the last translated unit at or before {@code p}, or -1 if there is none. */
    private int floorIndex(Position p) {
        if (p.line >= lineStarts.length) return text.length() - 1;
        int start = lineStarts[p.line];
        int limit = p.line + 1 < lineStarts.length ? lineStarts[p.line + 1] : text.length();
        int k = Math.min(start + Math.max(p.column, 1) - 1, limit - 1);
        return k >= start ? k : start - 1;
    }

    private int lineOf(int k) {
        int found = Arrays.binarySearch(lineStarts, 1, lineStarts.length, k);
        return found >= 0 ? found : -found - 2;
    }

    private static boolean isHex(String text, int from) {
        for (int i = from; i < from + 4; i++) {
            char c = text.charAt(i);
            // ASCII only, as §3.3 requires.
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F')) return false;
        }
        return true;
    }
}
