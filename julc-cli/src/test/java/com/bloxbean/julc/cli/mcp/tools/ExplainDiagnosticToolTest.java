package com.bloxbean.julc.cli.mcp.tools;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

class ExplainDiagnosticToolTest {

    private static McpJsonMapper jsonMapper;
    private static McpServerFeaturesSpecHolder spec;

    // Wraps the registered tool spec so each test can call handle() against
    // the same eagerly-loaded catalog (mirrors what the SDK does at runtime).
    record McpServerFeaturesSpecHolder(io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification spec) {}

    @BeforeAll
    static void init() {
        jsonMapper = ServiceLoader.load(McpJsonMapperSupplier.class).stream()
                .map(ServiceLoader.Provider::get)
                .map(McpJsonMapperSupplier::get)
                .findFirst().orElseThrow();
        spec = new McpServerFeaturesSpecHolder(ExplainDiagnosticTool.spec(jsonMapper));
    }

    private static McpSchema.CallToolResult call(String code) {
        Map<String, Object> args = code == null ? Map.of() : Map.of("code", code);
        return spec.spec().callHandler().apply(null,
                new McpSchema.CallToolRequest("julc_explain_diagnostic", args));
    }

    @Test
    void looks_up_known_code() {
        var res = call("JULC0015");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        assertEquals(Boolean.TRUE, body.get("found"), "JULC0015 must be in the catalog: " + body);
        assertEquals("JULC0015", body.get("code"));
        assertNotNull(body.get("title"));
        assertNotNull(body.get("fix"));
    }

    @Test
    void normalizes_lower_case() {
        var res = call("julc0015");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        assertEquals(Boolean.TRUE, body.get("found"));
    }

    @Test
    void normalizes_brackets() {
        var res = call("[JULC0015]");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        assertEquals(Boolean.TRUE, body.get("found"));
    }

    @Test
    void returns_candidates_for_unknown_code() {
        var res = call("JULC9999");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        assertEquals(Boolean.FALSE, body.get("found"));
        assertEquals("JULC9999", body.get("code"));
        @SuppressWarnings("unchecked")
        var candidates = (List<String>) body.get("candidates");
        assertNotNull(candidates);
        // Should give nearby JULC#### codes; catalog has JULC0001-JULC0030,
        // so all candidates should be in that range.
        for (String c : candidates) {
            assertTrue(c.startsWith("JULC00"), "candidate must be JULC00xx: " + c);
        }
    }

    @Test
    void short_code_form_resolves_to_padded() {
        // "JULC15" → "JULC0015"
        var res = call("JULC15");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        // Either the candidates suggestion includes JULC0015, or (better)
        // the tool normalizes silently. At minimum, candidates should hit it.
        @SuppressWarnings("unchecked")
        var candidates = (List<String>) body.get("candidates");
        assertTrue(candidates != null && candidates.contains("JULC0015"),
                "short JULC15 should suggest JULC0015: " + body);
    }

    @Test
    void rejects_missing_code() {
        var res = call(null);
        assertEquals(Boolean.TRUE, res.isError());
    }
}
