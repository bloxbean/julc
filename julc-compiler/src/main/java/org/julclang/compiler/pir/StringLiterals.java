package org.julclang.compiler.pir;

/**
 * Decodes a Java string literal, so that it compiles to the string javac gives it or is rejected (ADR-062
 * T22). javac translates Unicode escapes first (JLS 21 §3.3), then reads escape sequences (§3.10.7).
 * <p>
 * {@link org.julclang.compiler.JavaSourceParser} translates Unicode escapes before parsing (T24), so
 * {@code StringLiteralExpr.getValue()} is the text between the quotes after that step, and only escape
 * sequences are read here. JavaParser's {@code asString()} is not a substitute: it reads the escape for a
 * space as {@code s}, and drops the backslash of an escape it does not know.
 * <p>
 * A literal that holds an unpaired surrogate is rejected although javac accepts it. A UPLC string is
 * Unicode text, so the script cannot hold one: its FLAT encoding would carry a {@code ?} in its place.
 * {@code JavaSourceParser} rejects the other shapes JuLC cannot read as javac does.
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
     * @param text the literal's text between the quotes, as {@code StringLiteralExpr.getValue()} returns it
     * @throws IllegalLiteralException where javac rejects the literal (a backslash that starts no escape
     *         sequence, or one that ends the literal), and where the literal holds an unpaired surrogate
     */
    public static String decode(String text) {
        String decoded = text.indexOf('\\') < 0 ? text : escapeSequences(text);
        requirePairedSurrogates(decoded);
        return decoded;
    }

    /** §3.10.7, on the text after Unicode escapes. */
    private static String escapeSequences(String text) {
        var s = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
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

    private static boolean isOctal(char c) {
        return c >= '0' && c <= '7';
    }
}
