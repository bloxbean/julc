package org.julclang.tools.uplc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.julclang.core.PlutusData;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;

/** Detailed-schema Data parsing without a cardano-client-lib runtime dependency. */
public final class PlutusDataJson {
    private static final ObjectMapper JSON = new ObjectMapper();

    private PlutusDataJson() {}

    public static PlutusData parse(String text) throws JsonProcessingException {
        return parse(JSON.readTree(text));
    }

    private static PlutusData parse(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("Invalid PlutusData JSON: " + node);
        }
        // Preserve the existing converter's discriminator precedence and coercions.
        if (node.has("constructor")) {
            var tag = node.get("constructor");
            // ADR-057: integral tags must never wrap through a signed long. Other
            // legacy coercions are preserved; structured JS inputs validate separately.
            BigInteger value = tag.isIntegralNumber() ? tag.bigIntegerValue()
                    : BigInteger.valueOf(tag.asLong());
            return new PlutusData.ConstrData(value, items(node.get("fields")));
        }
        if (node.has("int")) return PlutusData.integer(node.get("int").bigIntegerValue());
        if (node.has("bytes")) {
            String hex = node.get("bytes").asText();
            if (hex.startsWith("0x")) hex = hex.substring(2);
            return PlutusData.bytes(HexFormat.of().parseHex(hex));
        }
        if (node.has("list")) return new PlutusData.ListData(items(node.get("list")));
        if (node.has("map")) {
            var entries = node.get("map");
            requireArray(entries);
            // The old JSON converter uses a LinkedHashMap: repeated keys replace
            // their value without moving their first position. Do not silently
            // change this to the core Data association-list semantics.
            var map = new LinkedHashMap<PlutusData, PlutusData>();
            for (JsonNode entry : entries) map.put(parse(entry.get("k")), parse(entry.get("v")));
            return new PlutusData.MapData(map.entrySet().stream()
                    .map(e -> new PlutusData.Pair(e.getKey(), e.getValue())).toList());
        }
        throw new IllegalArgumentException("Invalid PlutusData JSON: " + node);
    }

    private static List<PlutusData> items(JsonNode array) {
        requireArray(array);
        var values = new ArrayList<PlutusData>();
        for (JsonNode item : array) values.add(parse(item));
        return values;
    }

    private static void requireArray(JsonNode node) {
        if (node == null || !node.isArray()) throw new IllegalArgumentException("Expected a Data array");
    }
}
