package org.julclang.compiler.pir;

/**
 * Decodes a Java string literal as javac does, so that it compiles to the same string (ADR-062 T22).
 * javac translates Unicode escapes first (JLS 21 §3.3), then reads escape sequences (§3.10.7).
 * <p>
 * {@link org.julclang.compiler.JavaSourceParser} translates Unicode escapes before parsing (T24), so
 * {@code StringLiteralExpr.getValue()} is the text between the quotes after that step, and only escape
 * sequences are read here. JavaParser's {@code asString()} is not a substitute: it reads the escape for a
 * space as {@code s}, and drops the backslash of an escape it does not know.
 */
public final class StringLiterals {

    private StringLiterals() {
    }

    /**
     * The string a literal denotes.
     *
     * @param text the literal's text between the quotes, as {@code StringLiteralExpr.getValue()} returns it
     * @throws IllegalArgumentException where javac rejects the literal: a backslash that starts no escape
     *         sequence, or one that ends the literal
     */
    public static String decode(String text) {
        if (text.indexOf('\\') < 0) return text;
        var s = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\') {
                s.append(c);
                continue;
            }
            if (++i == text.length()) throw new IllegalArgumentException("A string literal ends with a lone backslash");
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
                    if (!isOctal(e)) throw new IllegalArgumentException("Illegal escape sequence \\" + e + " in a string literal");
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

    private static boolean isOctal(char c) {
        return c >= '0' && c <= '7';
    }
}
