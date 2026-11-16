package org.julclang.wasm;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.julclang.tools.model.MockTransaction.DataInput;
import org.julclang.tools.model.VmModels;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lossless private JSON wire format. Only small Java ints become JSON numbers. */
public final class JsMarshalling {
    static final ObjectMapper JSON = new ObjectMapper();

    /**
     * For requests read strictly ({@link ApiRegistry#addStrict}): a repeated member, anything after the body, and a
     * value of the wrong JSON type are refused. A string field takes only a JSON string (Jackson would otherwise turn
     * {@code 80} or {@code true} into {@code "80"} or {@code "true"}), and a boolean or number field takes no string.
     */
    static final ObjectMapper STRICT_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .addModule(new SimpleModule("julc-strict-strings").addDeserializer(String.class, new StrictString()))
            .build();

    /** A string field's value: a JSON string, or null. Numbers, booleans, arrays and objects are refused. */
    static final class StrictString extends StdScalarDeserializer<String> {
        StrictString() {
            super(String.class);
        }

        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.hasToken(JsonToken.VALUE_STRING)) return parser.getText();
            return (String) context.handleUnexpectedToken(String.class, parser.currentToken(), parser,
                    "expected a JSON string, found %s", parser.currentToken());
        }
    }

    /** In the schema of JuLC's cost model record. It holds {@code true}, which no field's schema is. */
    static final String COST_MODEL_MARK = "$costModel";

    private JsMarshalling() {}

    public static Object schema(Type type) {
        return schema(type, new ArrayDeque<>());
    }

    private static Object schema(Type type, ArrayDeque<Class<?>> records) {
        if (type == DataInput.class) return "data";
        if (type == long.class || type == Long.class) return "long";
        if (type == BigInteger.class) return "bigint";
        if (type == int.class || type == Integer.class) return "int";
        if (type == String.class) return "string";
        if (type == boolean.class || type == Boolean.class) return "boolean";
        if (type instanceof ParameterizedType p) {
            if (p.getRawType() == List.class) return List.of(schema(p.getActualTypeArguments()[0], records));
            if (p.getRawType() == Map.class && p.getActualTypeArguments()[0] == String.class) {
                return Map.of("$map", schema(p.getActualTypeArguments()[1], records));
            }
        }
        if (type instanceof Class<?> cls) {
            if (cls.isArray()) return List.of(schema(cls.getComponentType(), records));
            if (cls.isRecord()) {
                if (records.contains(cls)) throw new IllegalArgumentException("No JavaScript codec for a recursive record: " + cls.getName());
                records.push(cls);
                var result = new LinkedHashMap<String, Object>();
                for (var component : cls.getRecordComponents()) {
                    result.put(component.getName(), schema(component.getGenericType(), records));
                }
                records.pop();
                // The runtime's profile-name shorthand is for this record alone, not one that resembles it.
                if (cls == VmModels.CostModel.class) result.put(COST_MODEL_MARK, true);
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
