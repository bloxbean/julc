package org.julclang.difftest;

import org.julclang.core.cbor.PlutusDataCborDecoder;
import org.julclang.core.cbor.PlutusDataCborEncoder;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * One line of text per {@link Observation}, for saved cases (ADR-063 D6, D8):
 * <pre>
 * Value 01 ["checked"]          (the Data as CBOR in hex, then the traces)
 * Failure ["a"] "detail"        (the detail is optional)
 * Inconclusive "budget exhausted"
 * Rejected JULC0056 "message"   (none for a rejection without a code)
 * Crash "detail"
 * </pre>
 * Strings are quoted, with {@code \\}, {@code \"}, {@code \n}, {@code \r}, {@code \t} and {@code \}{@code uXXXX}
 * escapes, so a trace that contains a line break or a quote stays one event.
 */
public final class ObservationText {

    private ObservationText() {
    }

    public static String format(Observation observation) {
        return switch (observation) {
            case Observation.Value v -> "Value " + HexFormat.of().formatHex(PlutusDataCborEncoder.encode(v.data()))
                    + " " + traces(v.traces());
            case Observation.Failure f -> "Failure " + traces(f.traces()) + (f.detail() == null ? "" : " " + quote(f.detail()));
            case Observation.Inconclusive i -> "Inconclusive " + quote(i.reason());
            case Observation.Rejected r -> "Rejected " + (r.code() == null ? RegressionStore.Expectation.NO_CODE : r.code())
                    + " " + quote(r.message());
            case Observation.Crash c -> "Crash " + quote(c.detail());
        };
    }

    public static Observation parse(String text) {
        var reader = new Reader(text.trim());
        String kind = reader.word();
        Observation observation = switch (kind) {
            case "Value" -> new Observation.Value(
                    PlutusDataCborDecoder.decode(HexFormat.of().parseHex(reader.word())), reader.list());
            case "Failure" -> {
                List<String> traces = reader.list();
                yield new Observation.Failure(reader.atEnd() ? null : reader.string(), traces);
            }
            case "Inconclusive" -> new Observation.Inconclusive(reader.string());
            case "Rejected" -> {
                String code = reader.word();
                yield new Observation.Rejected(code.equals(RegressionStore.Expectation.NO_CODE) ? null : code, reader.string());
            }
            case "Crash" -> new Observation.Crash(reader.string());
            default -> throw new HarnessException("Unknown observation kind " + kind + ": " + text);
        };
        if (!reader.atEnd()) throw new HarnessException("Unexpected text after the observation: " + text);
        return observation;
    }

    private static String traces(List<String> traces) {
        var text = new StringBuilder("[");
        for (int i = 0; i < traces.size(); i++) text.append(i == 0 ? "" : ", ").append(quote(traces.get(i)));
        return text.append(']').toString();
    }

    static String quote(String s) {
        var text = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> text.append("\\\\");
                case '"' -> text.append("\\\"");
                case '\n' -> text.append("\\n");
                case '\r' -> text.append("\\r");
                case '\t' -> text.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7f) text.append(String.format("\\u%04x", (int) c));
                    else text.append(c);
                }
            }
        }
        return text.append('"').toString();
    }

    private static final class Reader {
        private final String text;
        private int pos;

        Reader(String text) {
            this.text = text;
        }

        boolean atEnd() {
            skipSpaces();
            return pos == text.length();
        }

        String word() {
            skipSpaces();
            int start = pos;
            while (pos < text.length() && text.charAt(pos) != ' ') pos++;
            if (start == pos) throw error("a word");
            return text.substring(start, pos);
        }

        List<String> list() {
            skipSpaces();
            expect('[');
            var items = new ArrayList<String>();
            skipSpaces();
            if (peek() == ']') {
                pos++;
                return items;
            }
            while (true) {
                items.add(string());
                skipSpaces();
                char c = next();
                if (c == ']') return items;
                if (c != ',') throw error("',' or ']'");
            }
        }

        String string() {
            skipSpaces();
            expect('"');
            var s = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') return s.toString();
                if (c != '\\') {
                    s.append(c);
                    continue;
                }
                char e = next();
                switch (e) {
                    case '\\', '"' -> s.append(e);
                    case 'n' -> s.append('\n');
                    case 'r' -> s.append('\r');
                    case 't' -> s.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) throw error("four hex digits");
                        s.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw error("an escape");
                }
            }
        }

        private void expect(char c) {
            if (next() != c) throw error("'" + c + "'");
        }

        private char peek() {
            if (pos >= text.length()) throw error("more text");
            return text.charAt(pos);
        }

        private char next() {
            char c = peek();
            pos++;
            return c;
        }

        private void skipSpaces() {
            while (pos < text.length() && text.charAt(pos) == ' ') pos++;
        }

        private HarnessException error(String expected) {
            return new HarnessException("Expected " + expected + " at column " + pos + ": " + text);
        }
    }
}
