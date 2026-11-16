package org.julclang.tools.params;

import org.julclang.core.PlutusData;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Parses one Plutus data value in UPLC's data notation, such as {@code Constr 0 [I 42, B #cafe]}, for
 * {@code vm.applyParams}' {@code uplc} values (ADR-077 M2).
 * <p>
 * The grammar is that of {@code UplcParser}'s data constants: {@code I <integer>}, {@code B #<hex>},
 * {@code Constr <index> [..]}, {@code List [..]} and {@code Map [(k, v), ..]}, each optionally in parentheses, with
 * whitespace and {@code --} line comments between tokens. Unlike a data constant wrapped in a program, it is parsed on
 * its own and must end at the end of the text. Constructor indices are read as unsigned 64-bit words, and nesting
 * uses an explicit stack, so a deep value needs no Java stack.
 */
final class UplcDataText {

    private static final int MAX_INTEGER_DIGITS = 100_000;
    private static final BigInteger WORD64_LIMIT = BigInteger.ONE.shiftLeft(64);

    private final String text;
    private int pos;

    private UplcDataText(String text) {
        this.text = text;
    }

    /** Parses {@code text}; any error is an {@code invalid-request}. */
    static PlutusData parse(String text) {
        return new UplcDataText(text).value();
    }

    private static final int PAREN = 0;
    private static final int LIST = 1;
    private static final int FIELDS = 2;
    private static final int MAP_KEY = 3;
    private static final int MAP_VALUE = 4;

    /** An open parenthesis, list, constructor or map. */
    private static final class Frame {
        int kind;
        final BigInteger constructor;
        final List<PlutusData> items = new ArrayList<>();
        PlutusData key;

        Frame(int kind, BigInteger constructor) {
            this.kind = kind;
            this.constructor = constructor;
        }
    }

    private PlutusData value() {
        var stack = new ArrayDeque<Frame>();
        while (true) {
            // The start of a value.
            skip();
            if (peek() == '(' && keywordAfterParenthesis()) {
                pos++;
                stack.push(new Frame(PAREN, null));
                continue;
            }
            PlutusData value;
            String word = word();
            skip();
            switch (word) {
                case "I" -> value = new PlutusData.IntData(integer(true));
                case "B" -> {
                    expect('#');
                    value = new PlutusData.BytesData(hex());
                }
                case "Constr", "List" -> {
                    BigInteger constructor = null;
                    if (word.equals("Constr")) {
                        constructor = integer(false);
                        if (constructor.compareTo(WORD64_LIMIT) >= 0) {
                            throw error("constructor index " + constructor + " is outside 0 to 2^64 - 1; Plutus data"
                                    + " constructor tags are unsigned 64-bit words");
                        }
                        skip();
                    }
                    expect('[');
                    skip();
                    var frame = new Frame(constructor == null ? LIST : FIELDS, constructor);
                    if (peek() == ']') {
                        pos++;
                        value = build(frame);
                    } else {
                        stack.push(frame);
                        continue;
                    }
                }
                case "Map" -> {
                    expect('[');
                    skip();
                    if (peek() == ']') {
                        pos++;
                        value = new PlutusData.MapData(List.of());
                    } else {
                        expect('(');
                        stack.push(new Frame(MAP_KEY, null));
                        continue;
                    }
                }
                default -> throw error("expected Plutus data (I, B, Constr, List or Map), found '" + word + "'");
            }
            // Hand the value to the open frames it completes.
            while (value != null) {
                Frame top = stack.peek();
                skip();
                if (top == null) {
                    if (pos != text.length()) throw error("unexpected text after the value");
                    return value;
                }
                switch (top.kind) {
                    case PAREN -> {
                        expect(')');
                        stack.pop();
                    }
                    case LIST, FIELDS -> {
                        top.items.add(value);
                        value = null;
                        if (peek() == ',') {
                            pos++;
                        } else {
                            expect(']');
                            stack.pop();
                            value = build(top);
                        }
                    }
                    case MAP_KEY -> {
                        top.key = value;
                        value = null;
                        expect(',');
                        top.kind = MAP_VALUE;
                    }
                    default -> {
                        top.items.add(top.key);
                        top.items.add(value);
                        value = null;
                        expect(')');
                        skip();
                        top.kind = MAP_KEY;
                        if (peek() == ',') {
                            pos++;
                            skip();
                            expect('(');
                        } else {
                            expect(']');
                            stack.pop();
                            var pairs = new ArrayList<PlutusData.Pair>();
                            for (int i = 0; i < top.items.size(); i += 2) {
                                pairs.add(new PlutusData.Pair(top.items.get(i), top.items.get(i + 1)));
                            }
                            value = new PlutusData.MapData(pairs);
                        }
                    }
                }
            }
        }
    }

    private static PlutusData build(Frame frame) {
        return frame.kind == LIST ? new PlutusData.ListData(frame.items)
                : PlutusData.ConstrData.fromUnsignedTag(frame.constructor, frame.items);
    }

    /** After {@code (}: whether a data keyword follows, so the parenthesis wraps a value. */
    private boolean keywordAfterParenthesis() {
        int saved = pos;
        pos++;
        skip();
        int start = pos;
        while (pos < text.length() && isWordChar(text.charAt(pos))) pos++;
        String word = text.substring(start, pos);
        pos = saved;
        return word.equals("I") || word.equals("B") || word.equals("Constr") || word.equals("List")
                || word.equals("Map");
    }

    private BigInteger integer(boolean signed) {
        int start = pos;
        if (signed && pos < text.length() && (text.charAt(pos) == '-' || text.charAt(pos) == '+')) pos++;
        int digits = pos;
        while (pos < text.length() && text.charAt(pos) >= '0' && text.charAt(pos) <= '9') pos++;
        if (pos == digits) throw error(signed ? "expected an integer" : "expected a constructor index");
        if (pos - digits > MAX_INTEGER_DIGITS) throw error("integer literal too large");
        if (pos < text.length() && isWordChar(text.charAt(pos))) throw error("unexpected character after a number");
        String number = text.substring(start, pos);
        return new BigInteger(number.startsWith("+") ? number.substring(1) : number);
    }

    private byte[] hex() {
        int start = pos;
        while (pos < text.length() && Character.digit(text.charAt(pos), 16) >= 0) pos++;
        if ((pos - start) % 2 != 0) throw error("a byte string needs an even number of hex digits");
        if (pos < text.length() && isWordChar(text.charAt(pos))) throw error("unexpected character in a byte string");
        return HexFormat.of().parseHex(text, start, pos);
    }

    private String word() {
        int start = pos;
        while (pos < text.length() && isWordChar(text.charAt(pos))) pos++;
        if (pos == start) {
            throw error(pos >= text.length() ? "expected Plutus data, found the end of the text"
                    : "expected Plutus data, found '" + text.charAt(pos) + "'");
        }
        return text.substring(start, pos);
    }

    /** Whitespace and {@code --} comments to the end of the line, as in UPLC text. */
    private void skip() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == '-' && pos + 1 < text.length() && text.charAt(pos + 1) == '-') {
                while (pos < text.length() && text.charAt(pos) != '\n') pos++;
            } else if (Character.isWhitespace(c)) {
                pos++;
            } else {
                return;
            }
        }
    }

    private int peek() {
        return pos < text.length() ? text.charAt(pos) : -1;
    }

    private void expect(char c) {
        if (peek() != c) {
            throw error("expected '" + c + "', found " + (pos >= text.length() ? "the end of the text"
                    : "'" + text.charAt(pos) + "'"));
        }
        pos++;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '\'' || c == '-';
    }

    private ApplyParamsException error(String message) {
        return ApplyParamsException.invalidRequest("the uplc value is not Plutus data such as Constr 0 [I 42]: "
                + message + " (at character " + pos + ")");
    }
}
