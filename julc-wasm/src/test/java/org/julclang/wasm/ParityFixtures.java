package org.julclang.wasm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs the parity request fixtures through the JVM dispatcher and writes the expected results used by the
 * WebAssembly smoke test ({@code wasmSmoke}).
 * <p>
 * Usage: {@code ParityFixtures <parity-requests.json> <expected.json>}
 */
public final class ParityFixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ParityFixtures() {}

    public static void main(String[] args) throws Exception {
        JsonNode fixtures = MAPPER.readTree(Path.of(args[0]).toFile());
        Path out = Path.of(args[1]);
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, run(fixtures, PlaygroundDispatcher.create()));
        System.out.println("Wrote " + fixtures.size() + " expected results to " + out);
    }

    /** Dispatches every fixture and returns {@code [{name, status, body}]} as JSON. */
    static String run(JsonNode fixtures, PlaygroundDispatcher dispatcher) throws Exception {
        ArrayNode results = MAPPER.createArrayNode();
        for (JsonNode fixture : fixtures) {
            String body = fixture.has("body") ? MAPPER.writeValueAsString(fixture.get("body")) : null;
            JsonNode envelope = MAPPER.readTree(dispatcher.dispatch(
                    fixture.get("method").asText(), fixture.get("path").asText(), body));
            ObjectNode result = results.addObject();
            result.put("name", fixture.get("name").asText());
            result.set("status", envelope.get("status"));
            result.set("body", envelope.get("body"));
        }
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(results);
    }
}
