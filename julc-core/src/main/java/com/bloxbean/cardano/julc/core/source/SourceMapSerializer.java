package com.bloxbean.cardano.julc.core.source;

import com.bloxbean.cardano.julc.core.Term;

import java.util.*;

/**
 * Serializes and deserializes {@link SourceMap} instances to/from a position-based
 * index format suitable for JSON persistence.
 * <p>
 * The key insight: {@link SourceMap} uses {@link IdentityHashMap} keyed by Term object
 * identity. After serialization/deserialization, Term objects are different instances.
 * This class solves that by assigning each Term node a deterministic DFS traversal index
 * during compilation, then rebuilding the IdentityHashMap from the same DFS order on
 * the deserialized Term tree.
 * <p>
 * DFS order matches {@code UplcFlatDecoder.readTerm()} — leaf nodes (Var, Const, Builtin,
 * Error) are visited, composite nodes recurse into children left-to-right.
 */
public final class SourceMapSerializer {

    private SourceMapSerializer() {}

    /**
     * Walk the term tree in DFS order and assign an integer index to each node.
     * Returns a sparse map of only those indices that have source locations.
     *
     * @param positions the identity-based source map entries
     * @param rootTerm  the root of the UPLC term tree
     * @return sparse index → SourceLocation map
     */
    public static Map<Integer, SourceLocation> toIndexed(
            IdentityHashMap<Term, SourceLocation> positions, Term rootTerm) {
        var result = new LinkedHashMap<Integer, SourceLocation>();
        var counter = new int[]{0};
        walkToIndex(rootTerm, positions, result, counter);
        return result;
    }

    /**
     * Walk a (deserialized) term tree in DFS order and rebuild an IdentityHashMap
     * using the indexed source locations.
     *
     * @param indexed  the sparse index → SourceLocation map
     * @param rootTerm the root of the deserialized UPLC term tree
     * @return identity-based map suitable for constructing a SourceMap
     */
    public static IdentityHashMap<Term, SourceLocation> fromIndexed(
            Map<Integer, SourceLocation> indexed, Term rootTerm) {
        var result = new IdentityHashMap<Term, SourceLocation>();
        var counter = new int[]{0};
        walkFromIndex(rootTerm, indexed, result, counter);
        return result;
    }

    // ---- DFS walkers ----

    private static void walkToIndex(Term term,
                                     IdentityHashMap<Term, SourceLocation> positions,
                                     Map<Integer, SourceLocation> result,
                                     int[] counter) {
        int idx = counter[0]++;
        SourceLocation loc = positions.get(term);
        if (loc != null) {
            result.put(idx, loc);
        }
        // Recurse into children in the same order as UplcFlatDecoder
        switch (term) {
            case Term.Var _, Term.Const _, Term.Builtin _, Term.Error _ -> {
                // leaf nodes — no children
            }
            case Term.Lam(var _, var body) -> walkToIndex(body, positions, result, counter);
            case Term.Apply(var function, var argument) -> {
                walkToIndex(function, positions, result, counter);
                walkToIndex(argument, positions, result, counter);
            }
            case Term.Force(var child) -> walkToIndex(child, positions, result, counter);
            case Term.Delay(var child) -> walkToIndex(child, positions, result, counter);
            case Term.Constr(var _, var fields) -> {
                for (Term field : fields) {
                    walkToIndex(field, positions, result, counter);
                }
            }
            case Term.Case(var scrutinee, var branches) -> {
                walkToIndex(scrutinee, positions, result, counter);
                for (Term branch : branches) {
                    walkToIndex(branch, positions, result, counter);
                }
            }
        }
    }

    private static void walkFromIndex(Term term,
                                       Map<Integer, SourceLocation> indexed,
                                       IdentityHashMap<Term, SourceLocation> result,
                                       int[] counter) {
        int idx = counter[0]++;
        SourceLocation loc = indexed.get(idx);
        if (loc != null) {
            result.put(term, loc);
        }
        switch (term) {
            case Term.Var _, Term.Const _, Term.Builtin _, Term.Error _ -> {
                // leaf nodes
            }
            case Term.Lam(var _, var body) -> walkFromIndex(body, indexed, result, counter);
            case Term.Apply(var function, var argument) -> {
                walkFromIndex(function, indexed, result, counter);
                walkFromIndex(argument, indexed, result, counter);
            }
            case Term.Force(var child) -> walkFromIndex(child, indexed, result, counter);
            case Term.Delay(var child) -> walkFromIndex(child, indexed, result, counter);
            case Term.Constr(var _, var fields) -> {
                for (Term field : fields) {
                    walkFromIndex(field, indexed, result, counter);
                }
            }
            case Term.Case(var scrutinee, var branches) -> {
                walkFromIndex(scrutinee, indexed, result, counter);
                for (Term branch : branches) {
                    walkFromIndex(branch, indexed, result, counter);
                }
            }
        }
    }

    // ---- JSON serialization (hand-rolled, no external deps) ----

    /**
     * Serialize an indexed source map to JSON.
     *
     * @param indexed        the sparse index → SourceLocation map
     * @param validatorClass the validator class name (for metadata)
     * @return JSON string
     */
    public static String toJson(Map<Integer, SourceLocation> indexed, String validatorClass) {
        var sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"version\": 1,\n");
        sb.append("  \"validatorClass\": \"").append(escapeJson(validatorClass)).append("\",\n");
        sb.append("  \"entries\": [");

        boolean first = true;
        // Sort by index for deterministic output
        var sorted = new TreeMap<>(indexed);
        for (var entry : sorted.entrySet()) {
            if (!first) sb.append(',');
            sb.append("\n    {");
            sb.append("\"index\": ").append(entry.getKey());
            var loc = entry.getValue();
            sb.append(", \"file\": \"").append(escapeJson(loc.fileName())).append('"');
            sb.append(", \"line\": ").append(loc.line());
            sb.append(", \"col\": ").append(loc.column());
            if (loc.fragment() != null) {
                sb.append(", \"fragment\": \"").append(escapeJson(loc.fragment())).append('"');
            }
            sb.append('}');
            first = false;
        }

        if (!indexed.isEmpty()) sb.append('\n');
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Deserialize an indexed source map from JSON.
     *
     * @param json the JSON string
     * @return parsed IndexedSourceMap
     */
    public static IndexedSourceMap fromJson(String json) {
        int version = extractInt(json, "\"version\"");
        String validatorClass = extractString(json, "\"validatorClass\"");

        var entries = new LinkedHashMap<Integer, SourceLocation>();

        // Find entries array
        int arrStart = json.indexOf("\"entries\"");
        if (arrStart < 0) {
            return new IndexedSourceMap(version, validatorClass, entries);
        }
        int bracketStart = json.indexOf('[', arrStart);
        int bracketEnd = findMatchingBracket(json, bracketStart);

        String arrContent = json.substring(bracketStart + 1, bracketEnd);

        // Parse each entry object
        int pos = 0;
        while (pos < arrContent.length()) {
            int objStart = arrContent.indexOf('{', pos);
            if (objStart < 0) break;
            int objEnd = findMatchingBrace(arrContent, objStart);
            if (objEnd < 0) break;

            String obj = arrContent.substring(objStart, objEnd + 1);

            int index = extractInt(obj, "\"index\"");
            String file = extractString(obj, "\"file\"");
            int line = extractInt(obj, "\"line\"");
            int col = extractInt(obj, "\"col\"");
            String fragment = extractStringOptional(obj, "\"fragment\"");

            entries.put(index, new SourceLocation(file, line, col, fragment));
            pos = objEnd + 1;
        }

        return new IndexedSourceMap(version, validatorClass, entries);
    }

    /**
     * Parsed source map with version metadata.
     */
    public record IndexedSourceMap(int version, String validatorClass,
                                    Map<Integer, SourceLocation> entries) {}

    // ---- JSON helpers ----

    private static String escapeJson(String s) {
        if (s == null) return "";
        var sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String unescapeJson(String s) {
        if (s == null) return null;
        var sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case '"' -> { sb.append('"'); i++; }
                    case '\\' -> { sb.append('\\'); i++; }
                    case 'n' -> { sb.append('\n'); i++; }
                    case 'r' -> { sb.append('\r'); i++; }
                    case 't' -> { sb.append('\t'); i++; }
                    default -> sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static int extractInt(String json, String key) {
        int keyIdx = json.indexOf(key);
        if (keyIdx < 0) return 0;
        int colonIdx = json.indexOf(':', keyIdx + key.length());
        int start = colonIdx + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        int end = start;
        if (end < json.length() && json.charAt(end) == '-') end++;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String extractString(String json, String key) {
        int keyIdx = json.indexOf(key);
        if (keyIdx < 0) return "";
        int colonIdx = json.indexOf(':', keyIdx + key.length());
        int quoteStart = json.indexOf('"', colonIdx + 1);
        int quoteEnd = findClosingQuote(json, quoteStart + 1);
        return unescapeJson(json.substring(quoteStart + 1, quoteEnd));
    }

    private static String extractStringOptional(String json, String key) {
        int keyIdx = json.indexOf(key);
        if (keyIdx < 0) return null;
        int colonIdx = json.indexOf(':', keyIdx + key.length());
        int quoteStart = json.indexOf('"', colonIdx + 1);
        if (quoteStart < 0) return null;
        int quoteEnd = findClosingQuote(json, quoteStart + 1);
        return unescapeJson(json.substring(quoteStart + 1, quoteEnd));
    }

    /**
     * Find the closing quote, handling escape sequences.
     */
    private static int findClosingQuote(String json, int start) {
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\') {
                i++; // skip escaped char
            } else if (c == '"') {
                return i;
            }
        }
        return json.length();
    }

    /**
     * Find matching '}' for a '{' at the given position, skipping string contents.
     */
    private static int findMatchingBrace(String json, int openPos) {
        int depth = 1;
        for (int i = openPos + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {
                i = findClosingQuote(json, i + 1);
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return json.length() - 1;
    }

    /**
     * Find matching ']' for a '[' at the given position.
     */
    private static int findMatchingBracket(String json, int openPos) {
        int depth = 1;
        for (int i = openPos + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {
                i = findClosingQuote(json, i + 1);
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return json.length() - 1;
    }
}
