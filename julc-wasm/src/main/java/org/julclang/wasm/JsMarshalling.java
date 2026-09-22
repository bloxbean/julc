package org.julclang.wasm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.julclang.tools.model.MockTransaction.DataInput;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lossless private JSON wire format. Only small Java ints become JSON numbers. */
public final class JsMarshalling {
    static final ObjectMapper JSON = new ObjectMapper();

    private JsMarshalling() {}

    public static Object schema(Type type) {
        if (type == DataInput.class) return "data";
        if (type == long.class || type == Long.class) return "long";
        if (type == BigInteger.class) return "bigint";
        if (type == int.class || type == Integer.class) return "int";
        if (type == String.class) return "string";
        if (type == boolean.class || type == Boolean.class) return "boolean";
        if (type instanceof ParameterizedType p) {
            if (p.getRawType() == List.class) return List.of(schema(p.getActualTypeArguments()[0]));
            if (p.getRawType() == Map.class && p.getActualTypeArguments()[0] == String.class) {
                return Map.of("$map", schema(p.getActualTypeArguments()[1]));
            }
        }
        if (type instanceof Class<?> cls) {
            if (cls.isArray()) return List.of(schema(cls.getComponentType()));
            if (cls.isRecord()) {
                var result = new LinkedHashMap<String, Object>();
                for (var component : cls.getRecordComponents()) {
                    result.put(component.getName(), schema(component.getGenericType()));
                }
                return result;
            }
        }
        throw new IllegalArgumentException("No JavaScript codec for " + type);
    }

    public static String envelope(int status, Object body) {
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("status", status);
        // Jackson's TokenBuffer retains the Java numeric type without a lossy text
        // parse. Convert LongNode/BigIntegerNode to strings BEFORE any JS sees them.
        JsonNode tree = JSON.valueToTree(body);
        var positions = new ArrayList<List<String>>();
        envelope.set("body", encode(tree, List.of(), positions));
        envelope.set("integers", JSON.valueToTree(positions));
        return envelope.toString();
    }

    private static JsonNode encode(JsonNode node, List<String> path, List<List<String>> positions) {
        if (node.isLong() || node.isBigInteger()) {
            positions.add(path);
            return JSON.getNodeFactory().textNode(node.asText());
        }
        if (node instanceof ObjectNode object) {
            var fields = new ArrayList<String>();
            object.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) object.set(field, encode(object.get(field), child(path, field), positions));
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) array.set(i, encode(array.get(i), child(path, Integer.toString(i)), positions));
        }
        return node;
    }

    private static List<String> child(List<String> path, String field) {
        var result = new ArrayList<>(path);
        result.add(field);
        return result;
    }
}
