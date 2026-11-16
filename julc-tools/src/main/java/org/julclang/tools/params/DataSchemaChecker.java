package org.julclang.tools.params;

import com.fasterxml.jackson.databind.JsonNode;
import org.julclang.core.PlutusData;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Checks Plutus data values against CIP-57 data schemas (ADR-077 D6b, I5).
 * <p>
 * Two kinds of failure are never mixed. {@link #compile} finds every fault of a schema (an invalid schema) in a pass
 * over the parameter's schema and the definitions it reaches, before any value is looked at; {@link #check} then
 * reports a value that does not match (a mismatch).
 * <p>
 * The supported domain is exactly the keywords of CIP-57's table, with local {@code $ref}s, and reference cycles only
 * when every cycle consumes data and none passes through {@code not}. Results are memoised per pair of schema node and
 * value node, under a bound on the work; schemas and values are walked with explicit stacks, never Java recursion.
 */
final class DataSchemaChecker {

    /** Work units (pair visits plus their children) one check may spend. */
    static final long DEFAULT_WORK_BOUND = 1_000_000;

    private static final BigInteger WORD64_LIMIT = BigInteger.ONE.shiftLeft(64);
    private static final String DEFINITIONS = "#/definitions/";
    private static final Set<String> ANNOTATIONS = Set.of("title", "description", "$comment");
    private static final Set<String> APPLICATORS = Set.of("allOf", "anyOf", "oneOf", "not");
    private static final Map<String, Set<String>> TYPE_KEYWORDS = Map.of(
            "bytes", Set.of("enum", "maxLength", "minLength"),
            "integer", Set.of("multipleOf", "maximum", "exclusiveMaximum", "minimum", "exclusiveMinimum"),
            "list", Set.of("items", "maxItems", "minItems", "uniqueItems"),
            "map", Set.of("keys", "values", "maxItems", "minItems"),
            "constructor", Set.of("index", "fields"));
    private static final Set<String> ALL_TYPE_KEYWORDS = Set.of("enum", "maxLength", "minLength", "multipleOf",
            "maximum", "exclusiveMaximum", "minimum", "exclusiveMinimum", "items", "maxItems", "minItems",
            "uniqueItems", "keys", "values", "index", "fields");

    /** How an edge of the schema graph moves through a value. */
    private enum Edge { REF, ALL_OF, ANY_OF, ONE_OF, NOT, CONSUME }

    /** A compiled schema node. Only the fields of its keywords are set. */
    static final class Schema {
        final int id;
        /** The schema this one is written inside, or {@code null} for a parameter's schema or a definition. */
        final Schema parent;
        /** Its keyword path below {@link #parent}, or its root name: {@code schema} or {@code definitions/<name>}. */
        final String step;
        Schema ref;
        String dataType;
        List<String> enumHex;
        BigInteger minLength;
        BigInteger maxLength;
        BigInteger multipleOf;
        BigInteger minimum;
        BigInteger maximum;
        BigInteger exclusiveMinimum;
        BigInteger exclusiveMaximum;
        Schema items;
        List<Schema> itemsArray;
        BigInteger minItems;
        BigInteger maxItems;
        boolean uniqueItems;
        Schema keys;
        Schema values;
        BigInteger index;
        List<Schema> fields;
        List<Schema> allOf = List.of();
        List<Schema> anyOf = List.of();
        List<Schema> oneOf = List.of();
        Schema not;

        Schema(int id, Schema parent, String step) {
            this.id = id;
            this.parent = parent;
            this.step = step;
        }

        /**
         * Where the schema is, for errors: {@code definitions/Name/anyOf/1}. Computed only when an error needs it, so
         * that deeply nested schemas cost no quadratic memory; a very deep location is shortened in the middle.
         */
        String location() {
            var steps = new ArrayList<String>();
            for (Schema s = this; s != null; s = s.parent) steps.add(s.step);
            Collections.reverse(steps);
            return render(steps, "/");
        }

        boolean isDefinition() {
            return parent == null && step.startsWith("definitions/");
        }

        /** Its outgoing edges, in keyword order. */
        List<Map.Entry<Schema, Edge>> edges() {
            var edges = new ArrayList<Map.Entry<Schema, Edge>>();
            if (ref != null) edges.add(Map.entry(ref, Edge.REF));
            if (items != null) edges.add(Map.entry(items, Edge.CONSUME));
            if (itemsArray != null) itemsArray.forEach(s -> edges.add(Map.entry(s, Edge.CONSUME)));
            if (keys != null) edges.add(Map.entry(keys, Edge.CONSUME));
            if (values != null) edges.add(Map.entry(values, Edge.CONSUME));
            if (fields != null) fields.forEach(s -> edges.add(Map.entry(s, Edge.CONSUME)));
            allOf.forEach(s -> edges.add(Map.entry(s, Edge.ALL_OF)));
            anyOf.forEach(s -> edges.add(Map.entry(s, Edge.ANY_OF)));
            oneOf.forEach(s -> edges.add(Map.entry(s, Edge.ONE_OF)));
            if (not != null) edges.add(Map.entry(not, Edge.NOT));
            return edges;
        }
    }

    private final JsonNode definitions;
    private final long workBound;
    private final IdentityHashMap<JsonNode, Schema> compiled = new IdentityHashMap<>();
    private final List<Schema> nodes = new ArrayList<>();

    /**
     * @param definitions the blueprint's {@code definitions} object, or {@code null}
     * @param workBound   the work one {@link #check} may spend
     */
    DataSchemaChecker(JsonNode definitions, long workBound) {
        this.definitions = definitions;
        this.workBound = workBound;
    }

    // ==== references ====

    /** The JSON schema a local {@code $ref} names; an invalid schema for any other form or a missing definition. */
    private JsonNode resolve(JsonNode ref, Supplier<String> location, String label) {
        if (ref == null || !ref.isTextual()) throw invalid(label, location, "$ref must be a string");
        String text = ref.textValue();
        if (!text.startsWith(DEFINITIONS)) {
            throw invalid(label, location, "$ref '" + text + "' is not local; only #/definitions/<name> is supported");
        }
        String name = unescape(text.substring(DEFINITIONS.length()), label, location, text);
        JsonNode target = definitions == null || !definitions.isObject() ? null : definitions.get(name);
        if (target == null) throw invalid(label, location, "$ref '" + text + "' names a missing definition");
        return target;
    }

    /** JSON Pointer unescaping of one reference token: {@code ~1} is {@code /}, {@code ~0} is {@code ~}. */
    private static String unescape(String token, String label, Supplier<String> location, String ref) {
        var out = new StringBuilder(token.length());
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '/') throw invalid(label, location, "$ref '" + ref + "' is not a single definition name");
            if (c == '~') {
                char next = i + 1 < token.length() ? token.charAt(i + 1) : 0;
                if (next == '0') out.append('~');
                else if (next == '1') out.append('/');
                else throw invalid(label, location, "$ref '" + ref + "' has an invalid ~ escape");
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * The parameter's schema with its {@code $ref}s followed, so that the caller can refuse a builtin type (D6a).
     * A reference that cannot be followed is an invalid schema.
     */
    JsonNode resolveTop(JsonNode schema, String label) {
        JsonNode current = schema;
        String[] location = {"schema"};
        var seen = Collections.newSetFromMap(new IdentityHashMap<JsonNode, Boolean>());
        while (current != null && current.isObject() && current.has("$ref")) {
            if (!seen.add(current)) throw invalid(label, location[0], "the parameter's $ref chain is a cycle");
            String text = current.get("$ref").asText();
            current = resolve(current.get("$ref"), () -> location[0], label);
            location[0] = "definitions/" + text.substring(Math.min(text.length(), DEFINITIONS.length()));
        }
        return current;
    }

    // ==== phase 1: the schema pass ====

    /**
     * Compiles a parameter's schema and the definitions it reaches, and checks that they are in D6b's domain.
     *
     * @param label names the parameter in errors, for example {@code Parameter 1 (cfg)}
     */
    Schema compile(JsonNode schema, String label) {
        var work = new ArrayDeque<Map.Entry<Schema, JsonNode>>();
        Schema root = node(schema, null, "schema", work);
        while (!work.isEmpty()) {
            var next = work.pop();
            parse(next.getKey(), next.getValue(), label, work);
        }
        checkCycles(root, label);
        return root;
    }

    private Schema node(JsonNode json, Schema parent, String step, ArrayDeque<Map.Entry<Schema, JsonNode>> work) {
        Schema existing = compiled.get(json);
        if (existing != null) return existing;
        var schema = new Schema(nodes.size(), parent, step);
        nodes.add(schema);
        compiled.put(json, schema);
        work.push(Map.entry(schema, json));
        return schema;
    }

    private void parse(Schema schema, JsonNode json, String label, ArrayDeque<Map.Entry<Schema, JsonNode>> work) {
        Supplier<String> at = schema::location;
        if (!json.isObject()) throw invalid(label, at, "a schema must be a JSON object");
        for (String annotation : ANNOTATIONS) {
            if (json.has(annotation) && !json.get(annotation).isTextual()) {
                throw invalid(label, at, "'" + annotation + "' must be a string");
            }
        }
        if (json.has("$ref")) {
            for (String name : names(json)) {
                if (!name.equals("$ref") && !ANNOTATIONS.contains(name)) {
                    throw invalid(label, at, "a schema with $ref may carry only title, description and $comment"
                            + " beside it; found '" + name + "'");
                }
            }
            JsonNode target = resolve(json.get("$ref"), at, label);
            String name = json.get("$ref").textValue().substring(DEFINITIONS.length());
            schema.ref = node(target, null, "definitions/" + name, work);
            return;
        }
        String dataType = null;
        if (json.has("dataType")) {
            JsonNode type = json.get("dataType");
            if (!type.isTextual()) throw invalid(label, at, "dataType must be a string");
            dataType = type.textValue();
            if (dataType.startsWith("#")) {
                throw invalid(label, at, "the builtin type " + dataType + " is not Plutus data, and cannot appear"
                        + " inside a data schema");
            }
            if (!TYPE_KEYWORDS.containsKey(dataType)) {
                throw invalid(label, at, "unknown dataType '" + dataType + "'");
            }
        }
        schema.dataType = dataType;
        Set<String> typeKeywords = dataType == null ? Set.of() : TYPE_KEYWORDS.get(dataType);
        for (String name : names(json)) {
            if (name.equals("dataType") || ANNOTATIONS.contains(name) || APPLICATORS.contains(name)
                    || typeKeywords.contains(name)) {
                continue;
            }
            if (ALL_TYPE_KEYWORDS.contains(name)) {
                throw invalid(label, at, "keyword '" + name + "' does not apply to "
                        + (dataType == null ? "a schema without dataType" : "dataType " + dataType));
            }
            throw invalid(label, at, "unknown keyword '" + name + "'");
        }
        if (dataType != null) {
            switch (dataType) {
                case "bytes" -> {
                    if (json.has("enum")) {
                        JsonNode values = json.get("enum");
                        if (!values.isArray() || values.isEmpty()) {
                            throw invalid(label, at, "enum must be a non-empty array of hex strings");
                        }
                        var hex = new ArrayList<String>();
                        for (JsonNode value : values) {
                            if (!value.isTextual() || !value.textValue().matches("(?:[0-9a-fA-F]{2})*")) {
                                throw invalid(label, at, "enum must hold even-length hex strings");
                            }
                            hex.add(value.textValue().toLowerCase(Locale.ROOT));
                        }
                        schema.enumHex = hex;
                    }
                    schema.minLength = natural(json, "minLength", label, at);
                    schema.maxLength = natural(json, "maxLength", label, at);
                }
                case "integer" -> {
                    schema.multipleOf = integer(json, "multipleOf", label, at);
                    if (schema.multipleOf != null && schema.multipleOf.signum() <= 0) {
                        throw invalid(label, at, "multipleOf must be greater than 0");
                    }
                    schema.minimum = integer(json, "minimum", label, at);
                    schema.maximum = integer(json, "maximum", label, at);
                    schema.exclusiveMinimum = integer(json, "exclusiveMinimum", label, at);
                    schema.exclusiveMaximum = integer(json, "exclusiveMaximum", label, at);
                }
                case "list" -> {
                    if (json.has("items")) {
                        JsonNode items = json.get("items");
                        if (items.isObject()) {
                            schema.items = node(items, schema, "items", work);
                        } else if (items.isArray()) {
                            schema.itemsArray = schemas(items, schema, "items", label, work);
                        } else {
                            throw invalid(label, at, "items must be a schema or an array of schemas");
                        }
                    }
                    schema.minItems = natural(json, "minItems", label, at);
                    schema.maxItems = natural(json, "maxItems", label, at);
                    if (json.has("uniqueItems")) {
                        if (!json.get("uniqueItems").isBoolean()) {
                            throw invalid(label, at, "uniqueItems must be true or false");
                        }
                        schema.uniqueItems = json.get("uniqueItems").booleanValue();
                    }
                }
                case "map" -> {
                    schema.keys = subschema(json, "keys", label, schema, work);
                    schema.values = subschema(json, "values", label, schema, work);
                    schema.minItems = natural(json, "minItems", label, at);
                    schema.maxItems = natural(json, "maxItems", label, at);
                }
                case "constructor" -> {
                    if (!json.has("index")) throw invalid(label, at, "a constructor schema needs its index");
                    if (!json.has("fields")) throw invalid(label, at, "a constructor schema needs its fields");
                    schema.index = natural(json, "index", label, at);
                    if (schema.index.compareTo(WORD64_LIMIT) >= 0) {
                        throw invalid(label, at, "index must be below 2^64");
                    }
                    if (!json.get("fields").isArray()) throw invalid(label, at, "fields must be an array of schemas");
                    schema.fields = schemas(json.get("fields"), schema, "fields", label, work);
                }
                default -> throw new IllegalStateException(dataType);
            }
        }
        schema.allOf = applicator(json, "allOf", label, schema, work);
        schema.anyOf = applicator(json, "anyOf", label, schema, work);
        schema.oneOf = applicator(json, "oneOf", label, schema, work);
        schema.not = subschema(json, "not", label, schema, work);
    }

    private Schema subschema(JsonNode json, String keyword, String label, Schema owner,
                             ArrayDeque<Map.Entry<Schema, JsonNode>> work) {
        if (!json.has(keyword)) return null;
        if (!json.get(keyword).isObject()) throw invalid(label, owner::location, keyword + " must be a schema object");
        return node(json.get(keyword), owner, keyword, work);
    }

    private List<Schema> applicator(JsonNode json, String keyword, String label, Schema owner,
                                    ArrayDeque<Map.Entry<Schema, JsonNode>> work) {
        if (!json.has(keyword)) return List.of();
        JsonNode list = json.get(keyword);
        if (!list.isArray() || list.isEmpty()) {
            throw invalid(label, owner::location, keyword + " must be a non-empty array of schemas");
        }
        return schemas(list, owner, keyword, label, work);
    }

    private List<Schema> schemas(JsonNode array, Schema owner, String keyword, String label,
                                 ArrayDeque<Map.Entry<Schema, JsonNode>> work) {
        var result = new ArrayList<Schema>(array.size());
        for (int i = 0; i < array.size(); i++) {
            int index = i;
            if (!array.get(i).isObject()) {
                throw invalid(label, () -> owner.location() + "/" + keyword + "/" + index, "a schema must be a JSON object");
            }
            result.add(node(array.get(i), owner, keyword + "/" + i, work));
        }
        return result;
    }

    private static BigInteger integer(JsonNode json, String keyword, String label, Supplier<String> at) {
        if (!json.has(keyword)) return null;
        JsonNode value = json.get(keyword);
        if (!value.isIntegralNumber()) throw invalid(label, at, keyword + " must be an integer");
        return value.bigIntegerValue();
    }

    private static BigInteger natural(JsonNode json, String keyword, String label, Supplier<String> at) {
        BigInteger value = integer(json, keyword, label, at);
        if (value != null && value.signum() < 0) throw invalid(label, at, keyword + " must not be negative");
        return value;
    }

    private static List<String> names(JsonNode json) {
        var names = new ArrayList<String>();
        for (Iterator<String> it = json.fieldNames(); it.hasNext(); ) names.add(it.next());
        return names;
    }

    // ---- cycles ----

    /**
     * Refuses a cycle whose edges consume no data (checking it would not end), and any cycle through {@code not}.
     */
    private void checkCycles(Schema root, String label) {
        List<Schema> reachable = reachable(root);
        // 1. Edges that stay at the same value form no cycle: depth-first search with an explicit stack.
        int[] color = new int[nodes.size()];
        int[] stackPosition = new int[nodes.size()];
        for (Schema start : reachable) {
            if (color[start.id] != 0) continue;
            var path = new ArrayList<Schema>();
            var cursors = new ArrayList<Iterator<Map.Entry<Schema, Edge>>>();
            path.add(start);
            cursors.add(start.edges().iterator());
            color[start.id] = 1;
            stackPosition[start.id] = 0;
            while (!path.isEmpty()) {
                var cursor = cursors.getLast();
                if (!cursor.hasNext()) {
                    color[path.getLast().id] = 2;
                    path.removeLast();
                    cursors.removeLast();
                    continue;
                }
                var edge = cursor.next();
                if (edge.getValue() == Edge.CONSUME) continue;
                Schema next = edge.getKey();
                if (color[next.id] == 1) {
                    var cycle = new ArrayList<>(path.subList(stackPosition[next.id], path.size()));
                    throw invalid(label, (String) null, describeCycle(cycle));
                }
                if (color[next.id] == 0) {
                    color[next.id] = 1;
                    stackPosition[next.id] = path.size();
                    path.add(next);
                    cursors.add(next.edges().iterator());
                }
            }
        }
        // 2. No cycle passes through not: an edge u -> u.not is on a cycle exactly when both are in one strongly
        // connected component.
        int[] component = components(reachable);
        for (Schema schema : reachable) {
            if (schema.not != null && component[schema.id] == component[schema.not.id]) {
                throw invalid(label, schema.location() + "/not", "a reference cycle passes through not, which is not"
                        + " supported");
            }
        }
    }

    private static String describeCycle(List<Schema> cycle) {
        Schema first = definitionOf(cycle);
        if (cycle.size() == 1) return first.location() + " refers to itself without consuming data";
        var steps = new ArrayList<String>();
        for (Schema schema : cycle) steps.add(schema.location());
        steps.add(cycle.getFirst().location());
        return first.location() + " is part of a reference cycle that consumes no data: " + render(steps, " -> ");
    }

    /** The first definition on the cycle, so that the error names a definition when there is one. */
    private static Schema definitionOf(List<Schema> cycle) {
        for (Schema schema : cycle) {
            if (schema.isDefinition()) return schema;
        }
        return cycle.getFirst();
    }

    private List<Schema> reachable(Schema root) {
        var seen = new boolean[nodes.size()];
        var order = new ArrayList<Schema>();
        var stack = new ArrayDeque<Schema>();
        stack.push(root);
        seen[root.id] = true;
        while (!stack.isEmpty()) {
            Schema schema = stack.pop();
            order.add(schema);
            for (var edge : schema.edges()) {
                if (!seen[edge.getKey().id]) {
                    seen[edge.getKey().id] = true;
                    stack.push(edge.getKey());
                }
            }
        }
        return order;
    }

    /** Tarjan's strongly connected components over every edge, with an explicit stack. */
    private int[] components(List<Schema> reachable) {
        int n = nodes.size();
        int[] index = new int[n];
        int[] low = new int[n];
        int[] component = new int[n];
        boolean[] onStack = new boolean[n];
        Arrays.fill(index, -1);
        Arrays.fill(component, -1);
        var sccStack = new ArrayDeque<Schema>();
        int counter = 0;
        int components = 0;
        for (Schema start : reachable) {
            if (index[start.id] >= 0) continue;
            var path = new ArrayList<Schema>();
            var cursors = new ArrayList<Iterator<Map.Entry<Schema, Edge>>>();
            index[start.id] = low[start.id] = counter++;
            sccStack.push(start);
            onStack[start.id] = true;
            path.add(start);
            cursors.add(start.edges().iterator());
            while (!path.isEmpty()) {
                Schema v = path.getLast();
                var cursor = cursors.getLast();
                if (cursor.hasNext()) {
                    Schema w = cursor.next().getKey();
                    if (index[w.id] < 0) {
                        index[w.id] = low[w.id] = counter++;
                        sccStack.push(w);
                        onStack[w.id] = true;
                        path.add(w);
                        cursors.add(w.edges().iterator());
                    } else if (onStack[w.id]) {
                        low[v.id] = Math.min(low[v.id], index[w.id]);
                    }
                    continue;
                }
                path.removeLast();
                cursors.removeLast();
                if (!path.isEmpty()) {
                    Schema parent = path.getLast();
                    low[parent.id] = Math.min(low[parent.id], low[v.id]);
                }
                if (low[v.id] == index[v.id]) {
                    Schema w;
                    do {
                        w = sccStack.pop();
                        onStack[w.id] = false;
                        component[w.id] = components;
                    } while (w != v);
                    components++;
                }
            }
        }
        return component;
    }

    // ==== phase 2: values ====

    /** The value nodes of one value, each with an id, its children and a structural identity. */
    private static final class Values {
        final List<PlutusData> nodes = new ArrayList<>();
        final List<int[]> children = new ArrayList<>();
        int[] identity;

        /** Numbers every occurrence of a node, so that a child's id is always greater than its parent's. */
        Values(PlutusData root) {
            var stack = new ArrayDeque<Integer>();
            nodes.add(root);
            children.add(null);
            stack.push(0);
            while (!stack.isEmpty()) {
                int id = stack.pop();
                List<PlutusData> kids = switch (nodes.get(id)) {
                    case PlutusData.ConstrData c -> c.fields();
                    case PlutusData.ListData l -> l.items();
                    case PlutusData.MapData m -> {
                        var flat = new ArrayList<PlutusData>(2 * m.entries().size());
                        for (var pair : m.entries()) {
                            flat.add(pair.key());
                            flat.add(pair.value());
                        }
                        yield flat;
                    }
                    default -> List.of();
                };
                int[] kidIds = new int[kids.size()];
                for (int i = 0; i < kids.size(); i++) {
                    kidIds[i] = nodes.size();
                    nodes.add(kids.get(i));
                    children.add(null);
                    stack.push(kidIds[i]);
                }
                children.set(id, kidIds);
            }
        }

        /**
         * Gives equal ids to equal Plutus data, so that {@code uniqueItems} compares elements as Plutus data. Two
         * values have equal canonical encodings exactly when they are equal, so this is the same comparison as by
         * encoding, without encoding (and without recursion). Children have higher ids than their parent, so a
         * reverse pass sees them first.
         */
        void identify() {
            if (identity != null) return;
            identity = new int[nodes.size()];
            var interned = new HashMap<List<Object>, Integer>();
            for (int id = nodes.size() - 1; id >= 0; id--) {
                PlutusData data = nodes.get(id);
                var key = new ArrayList<Object>();
                switch (data) {
                    case PlutusData.IntData i -> key.addAll(List.of("int", i.value()));
                    case PlutusData.BytesData b -> key.addAll(List.of("bytes", ByteBuffer.wrap(b.value())));
                    case PlutusData.ListData ignored -> key.add("list");
                    case PlutusData.MapData ignored -> key.add("map");
                    case PlutusData.ConstrData c -> key.addAll(List.of("constructor", c.constructorTag()));
                }
                for (int kid : children.get(id)) key.add(identity[kid]);
                identity[id] = interned.computeIfAbsent(key, k -> interned.size());
            }
        }
    }

    /** The work spent and the memo of one check. */
    private final class Run {
        final Values values;
        final LongBooleanMap memo = new LongBooleanMap();
        long work;

        Run(Values values) {
            this.values = values;
        }

        boolean result(Schema schema, int value) {
            int state = memo.get(key(schema, value));
            if (state < 0) throw new IllegalStateException("Schema check visited a pair before its dependencies");
            return state == 1;
        }

        void spend(long units) {
            work += units;
            if (work > workBound) {
                throw ApplyParamsException.invalidRequest("The schema check exceeded its work bound of " + workBound
                        + " steps; the value or the schema is too large to check");
            }
        }
    }

    private static long key(Schema schema, int value) {
        return ((long) schema.id << 32) | (value & 0xFFFFFFFFL);
    }

    /** A child pair a schema's own keywords lead to, with the path step for errors. */
    private record Child(Schema schema, int value, String step) {}

    /**
     * Checks one value; throws a mismatch naming the first wrong path.
     *
     * @param label names the parameter in errors
     */
    void check(Schema root, PlutusData value, String label) {
        var values = new Values(value);
        var run = new Run(values);
        if (!evaluate(run, root, 0)) {
            throw ApplyParamsException.mismatch(label + ": " + explain(run, root, 0, true));
        }
    }

    /** Memoised evaluation with an explicit stack. The pair graph has no cycle, as compile() made sure. */
    private boolean evaluate(Run run, Schema root, int rootValue) {
        var stack = new ArrayDeque<long[]>();
        stack.push(new long[]{key(root, rootValue), 0});
        while (!stack.isEmpty()) {
            long[] frame = stack.peek();
            long key = frame[0];
            if (run.memo.get(key) >= 0) {
                stack.pop();
                continue;
            }
            Schema schema = nodes.get((int) (key >>> 32));
            int value = (int) key;
            if (frame[1] == 0) {
                run.spend(1);
                if (localFailure(run, schema, value) != null) {
                    run.memo.put(key, false);
                    stack.pop();
                    continue;
                }
                frame[1] = 1;
                List<Long> dependencies = dependencies(run, schema, value);
                run.spend(dependencies.size());
                for (int i = dependencies.size() - 1; i >= 0; i--) {
                    long dependency = dependencies.get(i);
                    if (run.memo.get(dependency) < 0) stack.push(new long[]{dependency, 0});
                }
                continue;
            }
            run.memo.put(key, combine(run, schema, value));
            stack.pop();
        }
        return run.result(root, rootValue);
    }

    private List<Child> typeChildren(Run run, Schema schema, int value) {
        if (schema.dataType == null) return List.of();
        int[] kids = run.values.children.get(value);
        var children = new ArrayList<Child>();
        switch (schema.dataType) {
            case "list" -> {
                for (int i = 0; i < kids.length; i++) {
                    Schema item = schema.items != null ? schema.items
                            : schema.itemsArray != null ? schema.itemsArray.get(i) : null;
                    if (item != null) children.add(new Child(item, kids[i], "list[" + i + "]"));
                }
            }
            case "map" -> {
                for (int i = 0; i < kids.length / 2; i++) {
                    if (schema.keys != null) children.add(new Child(schema.keys, kids[2 * i], "map[" + i + "].k"));
                    if (schema.values != null) {
                        children.add(new Child(schema.values, kids[2 * i + 1], "map[" + i + "].v"));
                    }
                }
            }
            case "constructor" -> {
                for (int i = 0; i < kids.length; i++) {
                    children.add(new Child(schema.fields.get(i), kids[i], "fields[" + i + "]"));
                }
            }
            default -> {
            }
        }
        return children;
    }

    private List<Long> dependencies(Run run, Schema schema, int value) {
        var dependencies = new ArrayList<Long>();
        if (schema.ref != null) {
            dependencies.add(key(schema.ref, value));
            return dependencies;
        }
        for (Child child : typeChildren(run, schema, value)) dependencies.add(key(child.schema(), child.value()));
        for (Schema s : schema.allOf) dependencies.add(key(s, value));
        for (Schema s : schema.anyOf) dependencies.add(key(s, value));
        for (Schema s : schema.oneOf) dependencies.add(key(s, value));
        if (schema.not != null) dependencies.add(key(schema.not, value));
        return dependencies;
    }

    private boolean combine(Run run, Schema schema, int value) {
        if (schema.ref != null) return run.result(schema.ref, value);
        for (Child child : typeChildren(run, schema, value)) {
            if (!run.result(child.schema(), child.value())) return false;
        }
        for (Schema s : schema.allOf) {
            if (!run.result(s, value)) return false;
        }
        if (!schema.anyOf.isEmpty() && schema.anyOf.stream().noneMatch(s -> run.result(s, value))) return false;
        if (!schema.oneOf.isEmpty() && schema.oneOf.stream().filter(s -> run.result(s, value)).count() != 1) {
            return false;
        }
        return schema.not == null || !run.result(schema.not, value);
    }

    /** The schema's own keywords against the value, without its subschemas; {@code null} when they hold. */
    private String localFailure(Run run, Schema schema, int value) {
        if (schema.ref != null || schema.dataType == null) return null;
        PlutusData data = run.values.nodes.get(value);
        switch (schema.dataType) {
            case "integer" -> {
                if (!(data instanceof PlutusData.IntData(BigInteger x))) return expected("int", data);
                if (schema.minimum != null && x.compareTo(schema.minimum) < 0) {
                    return "expected an integer of at least " + schema.minimum + ", found " + x;
                }
                if (schema.exclusiveMinimum != null && x.compareTo(schema.exclusiveMinimum) <= 0) {
                    return "expected an integer greater than " + schema.exclusiveMinimum + ", found " + x;
                }
                if (schema.maximum != null && x.compareTo(schema.maximum) > 0) {
                    return "expected an integer of at most " + schema.maximum + ", found " + x;
                }
                if (schema.exclusiveMaximum != null && x.compareTo(schema.exclusiveMaximum) >= 0) {
                    return "expected an integer less than " + schema.exclusiveMaximum + ", found " + x;
                }
                if (schema.multipleOf != null && x.mod(schema.multipleOf).signum() != 0) {
                    return "expected a multiple of " + schema.multipleOf + ", found " + x;
                }
            }
            case "bytes" -> {
                if (!(data instanceof PlutusData.BytesData bytes)) return expected("bytes", data);
                BigInteger length = BigInteger.valueOf(bytes.size());
                if (schema.minLength != null && length.compareTo(schema.minLength) < 0) {
                    return "expected at least " + schema.minLength + " bytes, found " + length;
                }
                if (schema.maxLength != null && length.compareTo(schema.maxLength) > 0) {
                    return "expected at most " + schema.maxLength + " bytes, found " + length;
                }
                if (schema.enumHex != null) {
                    String hex = HexFormat.of().formatHex(bytes.value());
                    if (!schema.enumHex.contains(hex)) {
                        return "expected one of " + schema.enumHex + ", found " + (hex.isEmpty() ? "empty bytes" : hex);
                    }
                }
            }
            case "list" -> {
                if (!(data instanceof PlutusData.ListData list)) return expected("list", data);
                BigInteger size = BigInteger.valueOf(list.items().size());
                if (schema.minItems != null && size.compareTo(schema.minItems) < 0) {
                    return "expected at least " + schema.minItems + " elements, found " + size;
                }
                if (schema.maxItems != null && size.compareTo(schema.maxItems) > 0) {
                    return "expected at most " + schema.maxItems + " elements, found " + size;
                }
                if (schema.itemsArray != null && list.items().size() != schema.itemsArray.size()) {
                    return "expected exactly " + schema.itemsArray.size() + " elements, found " + size;
                }
                if (schema.uniqueItems) {
                    run.values.identify();
                    int[] kids = run.values.children.get(value);
                    run.spend(kids.length);
                    var first = new HashMap<Integer, Integer>();
                    for (int i = 0; i < kids.length; i++) {
                        Integer earlier = first.putIfAbsent(run.values.identity[kids[i]], i);
                        if (earlier != null) {
                            return "list[" + earlier + "] and list[" + i + "] are equal, but the elements must be"
                                    + " unique";
                        }
                    }
                }
            }
            case "map" -> {
                if (!(data instanceof PlutusData.MapData map)) return expected("map", data);
                BigInteger size = BigInteger.valueOf(map.entries().size());
                if (schema.minItems != null && size.compareTo(schema.minItems) < 0) {
                    return "expected at least " + schema.minItems + " pairs, found " + size;
                }
                if (schema.maxItems != null && size.compareTo(schema.maxItems) > 0) {
                    return "expected at most " + schema.maxItems + " pairs, found " + size;
                }
            }
            case "constructor" -> {
                if (!(data instanceof PlutusData.ConstrData constr)) return expected("constructor", data);
                if (!constr.constructorTag().equals(schema.index)) {
                    return "expected constructor index " + schema.index + ", found " + constr.constructorTag();
                }
                if (constr.fields().size() != schema.fields.size()) {
                    return "expected " + schema.fields.size() + " fields for constructor " + schema.index
                            + ", found " + constr.fields().size();
                }
            }
            default -> throw new IllegalStateException(schema.dataType);
        }
        return null;
    }

    private static String expected(String kind, PlutusData found) {
        String actual = switch (found) {
            case PlutusData.IntData ignored -> "int";
            case PlutusData.BytesData ignored -> "bytes";
            case PlutusData.ListData ignored -> "list";
            case PlutusData.MapData ignored -> "map";
            case PlutusData.ConstrData ignored -> "constructor";
        };
        return "expected " + kind + ", found " + actual;
    }

    /**
     * Describes why a pair that evaluated to false fails: it follows the first failing child along one path. Only
     * the first {@code anyOf} or {@code oneOf} met lists its branches, each explained without expanding further, so an
     * explanation is never larger than the branches times the depth.
     */
    private String explain(Run run, Schema schema, int value, boolean expand) {
        var path = new ArrayList<String>();
        while (true) {
            if (schema.ref != null) {
                schema = schema.ref;
                continue;
            }
            String local = localFailure(run, schema, value);
            if (local != null) return at(path, local + " (schema " + schema.location() + ")");
            Child failing = null;
            for (Child child : typeChildren(run, schema, value)) {
                if (!run.result(child.schema(), child.value())) {
                    failing = child;
                    break;
                }
            }
            if (failing != null) {
                path.add(failing.step());
                schema = failing.schema();
                value = failing.value();
                continue;
            }
            Schema failingAll = null;
            for (Schema s : schema.allOf) {
                if (!run.result(s, value)) {
                    failingAll = s;
                    break;
                }
            }
            if (failingAll != null) {
                schema = failingAll;
                continue;
            }
            final int v = value;
            if (!schema.anyOf.isEmpty() && schema.anyOf.stream().noneMatch(s -> run.result(s, v))) {
                return at(path, branches(run, schema, schema.anyOf, "anyOf", value, expand));
            }
            if (!schema.oneOf.isEmpty()) {
                var matched = new ArrayList<Integer>();
                for (int i = 0; i < schema.oneOf.size(); i++) {
                    if (run.result(schema.oneOf.get(i), value)) matched.add(i);
                }
                if (matched.isEmpty()) return at(path, branches(run, schema, schema.oneOf, "oneOf", value, expand));
                if (matched.size() > 1) {
                    return at(path, "branches " + matched + " of oneOf all matched, but exactly one must (schema "
                            + schema.location() + "/oneOf)");
                }
            }
            if (schema.not != null && run.result(schema.not, value)) {
                return at(path, "the value matched the schema it must not match (schema " + schema.location() + "/not)");
            }
            throw new IllegalStateException("No failure found at " + schema.location());
        }
    }

    private String branches(Run run, Schema schema, List<Schema> branches, String applicator, int value,
                            boolean expand) {
        var text = new StringBuilder("no branch of " + applicator + " matched (schema " + schema.location() + "/"
                + applicator + ")");
        if (expand) {
            var reasons = new ArrayList<String>();
            for (int i = 0; i < branches.size(); i++) {
                reasons.add("branch " + i + ": " + explain(run, branches.get(i), value, false));
            }
            text.append(": ").append(String.join("; ", reasons));
        }
        return text.toString();
    }

    private static String at(List<String> path, String message) {
        return path.isEmpty() ? message : "at " + render(path, ".") + ": " + message;
    }

    private static ApplyParamsException invalid(String label, String location, String message) {
        return ApplyParamsException.invalidSchema(label + ": invalid schema: "
                + (location == null ? "" : location + ": ") + message);
    }

    private static ApplyParamsException invalid(String label, Supplier<String> location, String message) {
        return invalid(label, location.get(), message);
    }

    /** Joins path steps; a path of more than 40 steps keeps its first 10 and last 20, so that errors stay small. */
    static String render(List<String> steps, String separator) {
        if (steps.size() <= 40) return String.join(separator, steps);
        return String.join(separator, steps.subList(0, 10)) + separator + "...(" + (steps.size() - 30) + " more)..."
                + separator + String.join(separator, steps.subList(steps.size() - 20, steps.size()));
    }

    /** An open-addressing map from long keys to booleans; {@link #get} gives -1 when absent. */
    static final class LongBooleanMap {
        private long[] keys = new long[64];
        private byte[] states = new byte[64];
        private int size;

        int get(long key) {
            int mask = keys.length - 1;
            for (int i = slot(key, mask); ; i = (i + 1) & mask) {
                if (states[i] == 0) return -1;
                if (keys[i] == key) return states[i] - 1;
            }
        }

        void put(long key, boolean value) {
            if (2 * (size + 1) > keys.length) grow();
            int mask = keys.length - 1;
            for (int i = slot(key, mask); ; i = (i + 1) & mask) {
                if (states[i] == 0) {
                    keys[i] = key;
                    states[i] = (byte) (value ? 2 : 1);
                    size++;
                    return;
                }
                if (keys[i] == key) {
                    states[i] = (byte) (value ? 2 : 1);
                    return;
                }
            }
        }

        private void grow() {
            long[] oldKeys = keys;
            byte[] oldStates = states;
            keys = new long[oldKeys.length * 2];
            states = new byte[oldKeys.length * 2];
            size = 0;
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldStates[i] != 0) put(oldKeys[i], oldStates[i] == 2);
            }
        }

        private static int slot(long key, int mask) {
            long h = key * 0x9E3779B97F4A7C15L;
            return (int) (h ^ (h >>> 32)) & mask;
        }
    }
}
