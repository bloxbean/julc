package org.julclang.tools.params;

import com.fasterxml.jackson.databind.JsonNode;
import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.core.cbor.PlutusDataCborDecoder;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.core.text.UplcParser;
import org.julclang.tools.model.MockTransaction.DataInput;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The strict, lossless value reader of {@code vm.applyParams} (ADR-077 D6a, "Values, always checked").
 * <p>
 * Unlike the legacy {@code PlutusDataJson} and {@code DataInputs}, it never coerces and never guesses:
 * <ul>
 *   <li>a value must name its format, {@code json}, {@code cbor} or {@code uplc}; none, or {@code auto}, is refused;</li>
 *   <li>a JSON value is one strict document, each object has exactly one shape, integers are integer tokens, bytes are
 *       even-length hex, map entries keep their order and duplicates, and unknown members are refused;</li>
 *   <li>a CBOR value is one item in the canonical encoding Plutus writes, with nothing after it;</li>
 *   <li>every constructor index, at every depth and in every format, is in {@code 0 <= index < 2^64}.</li>
 * </ul>
 * Values are walked with an explicit stack, so deep values need no Java stack.
 */
public final class StrictDataReader {

    private static final BigInteger WORD64_LIMIT = BigInteger.ONE.shiftLeft(64);
    private static final Pattern HEX = Pattern.compile("(?:[0-9a-fA-F]{2})*");
    private static final Set<String> SHAPES = Set.of("int", "bytes", "list", "map", "constructor");

    private StrictDataReader() {}

    /** Reads one value; the error message does not name the parameter, the caller adds it. */
    public static PlutusData read(DataInput input) {
        if (input == null) throw ApplyParamsException.invalidRequest("the value is missing");
        String format = input.format();
        if (format == null || format.isEmpty()) {
            throw ApplyParamsException.invalidRequest("the value has no format; give format json, cbor or uplc"
                    + " (a value's notation is never detected, because detection is ambiguous)");
        }
        if (input.value() == null) throw ApplyParamsException.invalidRequest("the value is missing");
        PlutusData data = switch (format) {
            case "json" -> fromJson(StrictJson.read(input.value(), "The JSON value"));
            case "cbor" -> fromCbor(input.value());
            case "uplc" -> fromUplc(input.value());
            case "auto" -> throw ApplyParamsException.invalidRequest("format auto is not accepted; give format json,"
                    + " cbor or uplc (a value's notation is never detected, because detection is ambiguous)");
            default -> throw ApplyParamsException.invalidRequest("unknown value format '" + format
                    + "'; give format json, cbor or uplc");
        };
        checkConstructorIndices(data);
        return data;
    }

    // ---- JSON ----

    /** Converts a strict JSON tree in the detailed Plutus data schema. */
    static PlutusData fromJson(JsonNode root) {
        // Pre-order validation, then construction in reverse order so that every child is built before its parent.
        var order = new ArrayList<JsonNode>();
        var stack = new ArrayDeque<Child>();
        stack.push(new Child(root, ""));
        while (!stack.isEmpty()) {
            Child next = stack.pop();
            List<Child> children = validateShape(next.node(), next.path());
            for (int i = children.size() - 1; i >= 0; i--) stack.push(children.get(i));
            order.add(next.node());
        }
        var built = new IdentityHashMap<JsonNode, PlutusData>();
        for (int i = order.size() - 1; i >= 0; i--) {
            JsonNode node = order.get(i);
            built.put(node, build(node, built));
        }
        return built.get(root);
    }

    /** A Data node inside a JSON value, with its path for errors. */
    private record Child(JsonNode node, String path) {}

    /** Checks one object's shape and returns its Data children in order. */
    private static List<Child> validateShape(JsonNode node, String path) {
        if (node == null || !node.isObject()) {
            throw invalid(path, "expected a Plutus data object such as {\"int\": 42}");
        }
        String shape = null;
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (SHAPES.contains(name)) {
                if (shape != null) {
                    throw invalid(path, "an object must have exactly one shape, found both '" + shape + "' and '"
                            + name + "'");
                }
                shape = name;
            }
        }
        if (shape == null) {
            throw invalid(path, "expected one of int, bytes, list, map or constructor, found "
                    + names(node));
        }
        var allowed = shape.equals("constructor") ? Set.of("constructor", "fields") : Set.of(shape);
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!allowed.contains(name)) throw invalid(path, "unknown member '" + name + "'");
        }
        var children = new ArrayList<Child>();
        switch (shape) {
            case "int" -> {
                if (!isIntegerToken(node.get("int"))) {
                    throw invalid(path, "int must be a JSON integer, without fraction or exponent");
                }
            }
            case "bytes" -> {
                JsonNode bytes = node.get("bytes");
                if (!bytes.isTextual() || !HEX.matcher(bytes.textValue()).matches()) {
                    throw invalid(path, "bytes must be a string of even-length hex");
                }
            }
            case "list" -> addItems(node.get("list"), path, "list", children);
            case "map" -> {
                JsonNode entries = node.get("map");
                if (!entries.isArray()) throw invalid(path, "map must be an array of {\"k\", \"v\"} entries");
                for (int i = 0; i < entries.size(); i++) {
                    JsonNode entry = entries.get(i);
                    String entryPath = join(path, "map[" + i + "]");
                    if (!entry.isObject() || entry.size() != 2 || !entry.has("k") || !entry.has("v")) {
                        throw invalid(entryPath, "a map entry must be an object with exactly the members k and v");
                    }
                    children.add(new Child(entry.get("k"), entryPath + ".k"));
                    children.add(new Child(entry.get("v"), entryPath + ".v"));
                }
            }
            case "constructor" -> {
                JsonNode index = node.get("constructor");
                if (!isIntegerToken(index)) {
                    throw invalid(path, "constructor must be a JSON integer, without fraction or exponent");
                }
                checkIndex(index.bigIntegerValue(), path);
                if (!node.has("fields")) throw invalid(path, "a constructor needs its fields array");
                addItems(node.get("fields"), path, "fields", children);
            }
            default -> throw new IllegalStateException(shape);
        }
        return children;
    }

    private static void addItems(JsonNode items, String path, String member, List<Child> children) {
        if (!items.isArray()) throw invalid(path, member + " must be an array");
        for (int i = 0; i < items.size(); i++) children.add(new Child(items.get(i), join(path, member + "[" + i + "]")));
    }

    private static PlutusData build(JsonNode node, IdentityHashMap<JsonNode, PlutusData> built) {
        if (node.has("int")) return new PlutusData.IntData(node.get("int").bigIntegerValue());
        if (node.has("bytes")) return new PlutusData.BytesData(HexFormat.of().parseHex(node.get("bytes").textValue()));
        if (node.has("list")) return new PlutusData.ListData(children(node.get("list"), built));
        if (node.has("map")) {
            var pairs = new ArrayList<PlutusData.Pair>();
            for (JsonNode entry : node.get("map")) {
                pairs.add(new PlutusData.Pair(built.get(entry.get("k")), built.get(entry.get("v"))));
            }
            return new PlutusData.MapData(pairs);
        }
        return PlutusData.ConstrData.fromUnsignedTag(node.get("constructor").bigIntegerValue(),
                children(node.get("fields"), built));
    }

    private static List<PlutusData> children(JsonNode items, IdentityHashMap<JsonNode, PlutusData> built) {
        var result = new ArrayList<PlutusData>(items.size());
        for (JsonNode item : items) result.add(built.get(item));
        return result;
    }

    private static boolean isIntegerToken(JsonNode node) {
        return node != null && node.isIntegralNumber();
    }

    private static String names(JsonNode node) {
        var names = new ArrayList<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names.isEmpty() ? "an empty object" : "members " + names;
    }

    // ---- CBOR ----

    private static PlutusData fromCbor(String hex) {
        if (hex.isEmpty() || !HEX.matcher(hex).matches()) {
            throw ApplyParamsException.invalidRequest("a cbor value must be non-empty, even-length hex");
        }
        byte[] bytes = HexFormat.of().parseHex(hex);
        PlutusData data;
        try {
            // decode() refuses bytes after the item.
            data = PlutusDataCborDecoder.decode(bytes);
        } catch (RuntimeException e) {
            throw ApplyParamsException.invalidRequest("the cbor value is not one Plutus data item: " + reason(e)
                    + "; give the value as JSON (format json) instead");
        }
        byte[] canonical = PlutusDataCborEncoder.encode(data);
        if (!Arrays.equals(bytes, canonical)) {
            throw ApplyParamsException.invalidRequest("the cbor value is not in the canonical encoding Plutus writes"
                    + " (expected " + HexFormat.of().formatHex(canonical) + "), so its script hash would depend on"
                    + " how it was written; give the value as JSON (format json) instead");
        }
        return data;
    }

    // ---- UPLC ----

    private static PlutusData fromUplc(String text) {
        // The text goes inside a constant; it must not be able to close it early.
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth < 0) break;
        }
        if (depth != 0) throw ApplyParamsException.invalidRequest("the uplc value has unbalanced parentheses");
        Term term;
        try {
            term = UplcParser.parseTerm("(con data (" + text + "))");
        } catch (RuntimeException e) {
            throw ApplyParamsException.invalidRequest("the uplc value is not Plutus data such as Constr 0 [I 42]: "
                    + reason(e));
        }
        if (term instanceof Term.Const(Constant.DataConst(PlutusData data))) return data;
        throw ApplyParamsException.invalidRequest("the uplc value is not Plutus data such as Constr 0 [I 42]");
    }

    // ---- shared ----

    /** Every constructor index, at every depth, is an unsigned 64-bit word (Plutus Data's constructor tags). */
    static void checkConstructorIndices(PlutusData root) {
        // Each visit keeps its parent and step, so that a path is built only for the error.
        var visits = new ArrayList<Visit>();
        var stack = new ArrayDeque<Integer>();
        visits.add(new Visit(root, -1, null));
        stack.push(0);
        while (!stack.isEmpty()) {
            int id = stack.pop();
            PlutusData data = visits.get(id).data();
            var children = new ArrayList<Visit>();
            switch (data) {
                case PlutusData.ConstrData c -> {
                    if (c.constructorTag().signum() < 0 || c.constructorTag().compareTo(WORD64_LIMIT) >= 0) {
                        checkIndex(c.constructorTag(), pathOf(visits, id));
                    }
                    for (int i = 0; i < c.fields().size(); i++) {
                        children.add(new Visit(c.fields().get(i), id, "fields[" + i + "]"));
                    }
                }
                case PlutusData.ListData l -> {
                    for (int i = 0; i < l.items().size(); i++) children.add(new Visit(l.items().get(i), id, "list[" + i + "]"));
                }
                case PlutusData.MapData m -> {
                    for (int i = 0; i < m.entries().size(); i++) {
                        children.add(new Visit(m.entries().get(i).key(), id, "map[" + i + "].k"));
                        children.add(new Visit(m.entries().get(i).value(), id, "map[" + i + "].v"));
                    }
                }
                case PlutusData.IntData ignored -> {
                }
                case PlutusData.BytesData ignored -> {
                }
            }
            for (int i = children.size() - 1; i >= 0; i--) {
                visits.add(children.get(i));
                stack.push(visits.size() - 1);
            }
        }
    }

    private record Visit(PlutusData data, int parent, String step) {}

    private static String pathOf(List<Visit> visits, int id) {
        var steps = new ArrayList<String>();
        for (int at = id; visits.get(at).parent() >= 0; at = visits.get(at).parent()) steps.add(visits.get(at).step());
        Collections.reverse(steps);
        return DataSchemaChecker.render(steps, ".");
    }

    private static void checkIndex(BigInteger index, String path) {
        if (index.signum() < 0 || index.compareTo(WORD64_LIMIT) >= 0) {
            throw invalid(path, "constructor index " + index + " is outside 0 to 2^64 - 1; Plutus data constructor"
                    + " tags are unsigned 64-bit words");
        }
    }

    static String join(String path, String step) {
        return path.isEmpty() ? step : path + "." + step;
    }

    private static ApplyParamsException invalid(String path, String message) {
        return ApplyParamsException.invalidRequest(path.isEmpty() ? message : "at " + path + ": " + message);
    }

    private static String reason(RuntimeException e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
