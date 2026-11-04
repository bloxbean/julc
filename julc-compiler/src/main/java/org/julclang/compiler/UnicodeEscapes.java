package org.julclang.compiler;


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
 *   <li>a backslash produced by an escape, at the end of an odd run of such escapes, followed by two or more
 *       raw backslashes and then {@code u}. javac counts the escaped backslash toward the parity of the raw
 *       backslashes after it, and the JLS does not, so they disagree on whether that {@code u} starts an
 *       escape. With one raw backslash, or no {@code u} after them, both read the text alike;</li>
 *   <li>an unpaired surrogate. javac's lookahead for the low half of a pair changes how it counts the
 *       backslashes after an unpaired high half, and a UPLC string cannot hold one.</li>
 * </ul>
 * Positions follow JavaParser's convention: lines and columns from 1, one column per UTF-16 unit, and
 * CR, LF or CR LF ending a line.
 */
final class UnicodeEscapes {
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

    /**
     * A source read without validation: its well-formed Unicode escapes translated as §3.3 states, and a
     * malformed escape, an unpaired surrogate or the parity shape {@link #translate} rejects left as it is.
     *
     * @param text            the translated text
     * @param javacMayDiffer whether javac could read the source differently from {@code text}: the source holds
     *                        the parity shape, an escape whose digits javac takes but §3.3 does not, or an unpaired
     *                        high surrogate before a backslash (javac's lookahead then counts that backslash twice)
     */
    record LenientReading(String text, boolean javacMayDiffer) {
    }

    /** For quick text tests and decisions that must not reject a source, such as the annotation pre-filters. */
    static LenientReading readLeniently(String source) {
        if (source.indexOf('\\') < 0) return new LenientReading(source, false);
        var s = new StringBuilder(source.length());
        boolean differs = false;
        int backslashes = 0;
        int escapedBackslashes = 0;
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\' && backslashes % 2 == 0 && i + 1 < source.length() && source.charAt(i + 1) == 'u') {
                int digits = i + 1;
                while (digits < source.length() && source.charAt(digits) == 'u') digits++;
                if (digits + 4 <= source.length() && isHex(source, digits)) {
                    char unit = (char) Integer.parseInt(source.substring(digits, digits + 4), 16);
                    if (afterHighSurrogate(s) && !Character.isLowSurrogate(unit)) differs = true;
                    s.append(unit);
                    escapedBackslashes = unit == '\\' ? escapedBackslashes + 1 : 0;
                    backslashes = 0;
                    i = digits + 4;
                    continue;
                }
                if (digits + 4 <= source.length() && isJavacHex(source, digits)) differs = true;
            }
            if (c == '\\' && escapedBackslashes % 2 == 1 && startsDisputedEscape(source, i)) differs = true;
            if (c == '\\' && afterHighSurrogate(s)) differs = true;
            s.append(c);
            backslashes = c == '\\' ? backslashes + 1 : 0;
            escapedBackslashes = 0;
            i++;
        }
        return new LenientReading(s.toString(), differs);
    }

    private static boolean afterHighSurrogate(StringBuilder s) {
        return !s.isEmpty() && Character.isHighSurrogate(s.charAt(s.length() - 1));
    }

    /** Four digits javac's {@code Character.digit} accepts in an escape. */
    private static boolean isJavacHex(String text, int from) {
        for (int i = from; i < from + 4; i++) if (Character.digit(text.charAt(i), 16) < 0) return false;
        return true;
    }

    /** Whether the raw backslashes from {@code i} are two or more and followed by {@code u}. */
    private static boolean startsDisputedEscape(String source, int i) {
        int end = i;
        while (end < source.length() && source.charAt(end) == '\\') end++;
        return end - i >= 2 && end < source.length() && source.charAt(end) == 'u';
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

    private static boolean isHex(String text, int from) {
        for (int i = from; i < from + 4; i++) {
            char c = text.charAt(i);
            // ASCII only, as §3.3 requires.
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F')) return false;
        }
        return true;
    }
}
