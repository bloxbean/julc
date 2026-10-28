package org.julclang.compiler.pir;

import java.util.BitSet;

/**
 * Decodes a Java string literal as javac does, so that it compiles to the same string (ADR-062 T22).
 * javac translates Unicode escapes first (JLS 21 §3.3), then reads escape sequences (§3.10.7).
 * <p>
 * JuLC's parsers leave Unicode escapes untranslated, so {@code StringLiteralExpr.getValue()} is the raw
 * text between the quotes and both steps happen here. JavaParser's {@code asString()} is not a substitute:
 * it reads the escape for a space as {@code s}, and drops the backslash of an escape it does not know. If
 * the parsers start translating Unicode escapes themselves, the first step must go, or an escaped
 * backslash followed by another escape would be translated twice.
 * <p>
 * One shape is rejected although javac 25 accepts some of it: a backslash written as the Unicode escape
 * for U+005C that starts an escape sequence and is followed by {@code u}. javac reads that backslash and
 * {@code u0041} as {@code A}, but rejects it before {@code u005c} and {@code u0041}, which follows no rule
 * the JLS states.
 */
public final class StringLiterals {

    private StringLiterals() {
    }

    /**
     * The string a literal denotes.
     *
     * @param text the literal's raw text between the quotes, as {@code StringLiteralExpr.getValue()} returns it
     * @throws IllegalArgumentException where javac rejects the literal: a malformed Unicode escape, a
     *         backslash that starts no escape sequence, or a Unicode escape that ends the literal or the line
     */
    public static String decode(String text) {
        if (text.indexOf('\\') < 0) return text;
        var translatedBackslashes = new BitSet();
        return escapeSequences(unicodeEscapes(text, translatedBackslashes), translatedBackslashes);
    }

    /**
     * §3.3: a backslash preceded by an even number of backslashes, then one or more {@code u} and four hex
     * digits, is one UTF-16 code unit. A backslash it produces starts no further Unicode escape; its
     * position is recorded in {@code translatedBackslashes}.
     */
    private static String unicodeEscapes(String text, BitSet translatedBackslashes) {
        var s = new StringBuilder(text.length());
        int backslashes = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && backslashes % 2 == 0 && i + 1 < text.length() && text.charAt(i + 1) == 'u') {
                int digits = i + 1;
                while (digits < text.length() && text.charAt(digits) == 'u') digits++;
                if (digits + 4 > text.length() || !isHex(text, digits)) {
                    throw new IllegalArgumentException("Illegal Unicode escape in a string literal");
                }
                char unit = (char) Integer.parseInt(text.substring(digits, digits + 4), 16);
                if (unit == '\\') translatedBackslashes.set(s.length());
                s.append(unit);
                i = digits + 4;
                backslashes = 0;
                continue;
            }
            s.append(c);
            backslashes = c == '\\' ? backslashes + 1 : 0;
            i++;
        }
        return s.toString();
    }

    /** §3.10.7, on the text after Unicode escapes. */
    private static String escapeSequences(String text, BitSet translatedBackslashes) {
        var s = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\n' || c == '\r') {
                // Only a Unicode escape can put these here; javac then ends the literal or the line.
                throw new IllegalArgumentException("A Unicode escape in a string literal produces "
                        + (c == '"' ? "a quote" : "a line terminator") + ", which ends the literal");
            }
            if (c != '\\') {
                s.append(c);
                continue;
            }
            if (++i == text.length()) throw new IllegalArgumentException("A string literal ends with a lone backslash");
            char e = text.charAt(i);
            if (e == 'u' && translatedBackslashes.get(i - 1)) {
                throw new IllegalArgumentException(
                        "A backslash written as a Unicode escape is followed by u in a string literal");
            }
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

    private static boolean isHex(String text, int from) {
        for (int i = from; i < from + 4; i++) {
            char c = text.charAt(i);
            // ASCII only: Character.digit also accepts other scripts' digits.
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F')) return false;
        }
        return true;
    }

    private static boolean isOctal(char c) {
        return c >= '0' && c <= '7';
    }
}
