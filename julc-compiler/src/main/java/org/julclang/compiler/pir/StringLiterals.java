package org.julclang.compiler.pir;

/**
 * Decodes a Java string literal, so that it compiles to the string javac gives it or is rejected (ADR-062
 * T22). javac translates Unicode escapes first (JLS 21 §3.3), then reads escape sequences (§3.10.7).
 * <p>
 * JuLC's parsers leave Unicode escapes untranslated, so {@code StringLiteralExpr.getValue()} is the raw
 * text between the quotes and both steps happen here. JavaParser's {@code asString()} is not a substitute:
 * it reads the escape for a space as {@code s}, and drops the backslash of an escape it does not know. If
 * the parsers start translating Unicode escapes themselves, the first step must go, or an escaped
 * backslash followed by another escape would be translated twice.
 * <p>
 * JuLC rejects some literals javac accepts, where javac's reading is not the JLS's or cannot be kept:
 * <ul>
 *   <li>a backslash written as a Unicode escape, after an odd run of such escapes, followed by two or more
 *       raw backslashes and then {@code u}. javac counts the escaped backslash toward the parity of the raw
 *       backslashes after it, and §3.3 counts only raw backslashes, so they disagree on whether that
 *       {@code u} starts a Unicode escape;</li>
 *   <li>a Unicode escape whose four digits are not all ASCII hex digits. §3.3 allows only those; javac also
 *       takes other scripts' digits;</li>
 *   <li>a literal that holds an unpaired surrogate. A UPLC string is Unicode text, so the script cannot
 *       hold one: its FLAT encoding would carry a {@code ?} in its place;</li>
 *   <li>a Unicode escape with more than one {@code u}, which JavaParser's lexer rejects before this runs.</li>
 * </ul>
 */
public final class StringLiterals {

    private StringLiterals() {
    }

    /** A literal JuLC rejects, with a suggestion that fits the reason. */
    public static final class IllegalLiteralException extends IllegalArgumentException {
        private final String suggestion;

        IllegalLiteralException(String message, String suggestion) {
            super(message);
            this.suggestion = suggestion;
        }

        /** How to write the literal so that JuLC accepts it. */
        public String suggestion() {
            return suggestion;
        }
    }

    private static final String ESCAPES = "Use one of \\b \\s \\t \\n \\f \\r \\\" \\' \\\\ or an octal escape.";

    /**
     * The string a literal denotes.
     *
     * @param text the literal's raw text between the quotes, as {@code StringLiteralExpr.getValue()} returns it
     * @throws IllegalLiteralException where javac rejects the literal (a malformed Unicode escape, a backslash
     *         that starts no escape sequence, or a Unicode escape that ends the literal or the line), and where
     *         JuLC rejects a literal javac accepts (see the class comment)
     */
    public static String decode(String text) {
        String decoded = text.indexOf('\\') < 0 ? text : escapeSequences(unicodeEscapes(text));
        requirePairedSurrogates(decoded);
        return decoded;
    }

    /**
     * §3.3: a backslash preceded by an even number of raw backslashes, then one or more {@code u} and four
     * hex digits, is one UTF-16 code unit. A backslash it produces starts no further Unicode escape.
     */
    private static String unicodeEscapes(String text) {
        var s = new StringBuilder(text.length());
        int backslashes = 0;        // raw backslashes immediately before i
        int escapedBackslashes = 0; // Unicode escapes producing a backslash immediately before i
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && backslashes % 2 == 0 && i + 1 < text.length() && text.charAt(i + 1) == 'u') {
                int digits = i + 1;
                while (digits < text.length() && text.charAt(digits) == 'u') digits++;
                if (digits + 4 > text.length() || !isHex(text, digits)) {
                    throw new IllegalLiteralException("Illegal Unicode escape in a string literal",
                            "A Unicode escape is a backslash, the letter u, and four hex digits 0-9, a-f or A-F.");
                }
                char unit = (char) Integer.parseInt(text.substring(digits, digits + 4), 16);
                s.append(unit);
                escapedBackslashes = unit == '\\' ? escapedBackslashes + 1 : 0;
                backslashes = 0;
                i = digits + 4;
                continue;
            }
            if (c == '\\' && escapedBackslashes % 2 == 1 && startsDisputedEscape(text, i)) {
                throw new IllegalLiteralException("A backslash written as a Unicode escape is followed by raw"
                        + " backslashes and u in a string literal. javac and the JLS disagree on whether that u starts a"
                        + " Unicode escape", "Write each backslash as the escape sequence \\\\.");
            }
            s.append(c);
            backslashes = c == '\\' ? backslashes + 1 : 0;
            escapedBackslashes = 0;
            i++;
        }
        return s.toString();
    }

    /**
     * Whether the raw backslashes from {@code i} are two or more and followed by {@code u}. After an odd run of
     * escaped backslashes, javac counts the escaped one toward their parity and §3.3 does not, so the two
     * disagree on whether the last of them starts a Unicode escape. With one raw backslash, or no {@code u}
     * after them, they read the text alike.
     */
    private static boolean startsDisputedEscape(String text, int i) {
        int end = i;
        while (end < text.length() && text.charAt(end) == '\\') end++;
        return end - i >= 2 && end < text.length() && text.charAt(end) == 'u';
    }

    /** §3.10.7, on the text after Unicode escapes. */
    private static String escapeSequences(String text) {
        var s = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\n' || c == '\r') {
                // Only a Unicode escape can put these here; javac then ends the literal or the line.
                throw new IllegalLiteralException("A Unicode escape in a string literal produces "
                        + (c == '"' ? "a quote" : "a line terminator") + ", which ends the literal",
                        "Use the escape sequence " + (c == '"' ? "\\\"" : c == '\n' ? "\\n" : "\\r") + " instead.");
            }
            if (c != '\\') {
                s.append(c);
                continue;
            }
            if (++i == text.length()) {
                throw new IllegalLiteralException("A string literal ends with a lone backslash",
                        "Write a backslash as the escape sequence \\\\.");
            }
            char e = text.charAt(i);
            switch (e) {
                case 'b' -> s.append('\b');
                case 's' -> s.append(' ');
                case 't' -> s.append('\t');
                case 'n' -> s.append('\n');
                case 'f' -> s.append('\f');
                case 'r' -> s.append('\r');
                case '"', '\'', '\\' -> s.append(e);
                default -> {
                    if (!isOctal(e)) {
                        throw new IllegalLiteralException("Illegal escape sequence \\" + e + " in a string literal", ESCAPES);
                    }
                    // Up to three octal digits, and three only when the first is 0-3: at most octal 377.
                    int value = e - '0';
                    int max = e <= '3' ? 2 : 1;
                    for (int k = 0; k < max && i + 1 < text.length() && isOctal(text.charAt(i + 1)); k++) {
                        value = value * 8 + (text.charAt(++i) - '0');
                    }
                    s.append((char) value);
                }
            }
        }
        return s.toString();
    }

    /** A UPLC string is Unicode text: a surrogate must be the high half of a pair followed by its low half. */
    private static void requirePairedSurrogates(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                throw new IllegalLiteralException(String.format(
                        "A string literal holds the unpaired surrogate U+%04X, which a UPLC string cannot hold", (int) c),
                        "Write a supplementary character as its high surrogate followed by its low surrogate, or remove it.");
            }
        }
    }

    private static boolean isHex(String text, int from) {
        for (int i = from; i < from + 4; i++) {
            char c = text.charAt(i);
            // ASCII only, as §3.3 requires.
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F')) return false;
        }
        return true;
    }

    private static boolean isOctal(char c) {
        return c >= '0' && c <= '7';
    }
}
