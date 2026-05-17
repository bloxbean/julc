package com.bloxbean.julc.cli.mcp.tools;

import com.bloxbean.julc.cli.mcp.catalog.LedgerCatalog;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tool-layer tests for the D2 discovery tools (StdlibTools.* and LedgerTypeTool).
 * Phase D review (impl-validator P1) — previously only the catalog layer was tested.
 */
class DiscoveryToolsHandlerTest {

    private static McpJsonMapper jsonMapper;

    @BeforeAll
    static void init() {
        jsonMapper = ServiceLoader.load(McpJsonMapperSupplier.class).stream()
                .map(ServiceLoader.Provider::get)
                .map(McpJsonMapperSupplier::get)
                .findFirst().orElseThrow();
    }

    private static McpSchema.CallToolResult callListSpec() {
        return StdlibTools.listSpec(jsonMapper).callHandler().apply(null,
                new McpSchema.CallToolRequest("julc_stdlib_list", Map.of()));
    }

    private static McpSchema.CallToolResult callMethodSpec(Map<String, Object> args) {
        return StdlibTools.methodSpec(jsonMapper).callHandler().apply(null,
                new McpSchema.CallToolRequest("julc_stdlib_method", args));
    }

    private static McpSchema.CallToolResult callBuiltinsSpec() {
        return StdlibTools.builtinsSpec(jsonMapper).callHandler().apply(null,
                new McpSchema.CallToolRequest("julc_builtins_list", Map.of()));
    }

    private static McpSchema.CallToolResult callLedgerType(Map<String, Object> args) {
        return LedgerTypeTool.handle(
                new McpSchema.CallToolRequest("julc_ledger_type", args), jsonMapper);
    }

    @Test
    void lint_tool_description_does_not_advertise_retired_biginteger_param_rule() {
        String description = LintTool.spec(jsonMapper).tool().description();
        assertFalse(description.contains("@Param BigInteger"),
                "julc_lint description must not advertise the retired @Param BigInteger warning");
        assertTrue(description.contains("banned @Param PlutusData subtypes"),
                "julc_lint description should point at the active banned-param rule");
    }

    @Test
    void builtins_tool_description_does_not_advertise_nonexistent_addInteger_api() {
        String description = StdlibTools.builtinsSpec(jsonMapper).tool().description();
        assertFalse(description.contains("addInteger"),
                "Builtins tool description must not encourage nonexistent Builtins.addInteger API");
        assertTrue(description.contains("equalsData"));
        assertTrue(description.contains("unIData"));
    }

    // ---------- julc_stdlib_list ----------

    @Test
    void stdlib_list_returns_all_libraries() {
        var res = callListSpec();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        assertTrue(((Number) body.get("count")).intValue() >= 10);
        @SuppressWarnings("unchecked")
        var libs = (List<Map<String, Object>>) body.get("libraries");
        var names = libs.stream().map(m -> (String) m.get("name")).toList();
        // Strengthened per Codex P2#10: assert exact known names rather than
        // a loose >= count.
        assertTrue(names.contains("ListsLib"));
        assertTrue(names.contains("ValuesLib"));
        assertTrue(names.contains("ContextsLib"));
        assertTrue(names.contains("MapLib"));
        assertTrue(names.contains("OutputLib"));
        assertTrue(names.contains("MathLib"));
        assertTrue(names.contains("IntervalLib"));
        assertTrue(names.contains("CryptoLib"));
        assertTrue(names.contains("AddressLib"));
    }

    // ---------- julc_stdlib_method ----------

    @Test
    void stdlib_method_rejects_missing_library_arg() {
        var res = callMethodSpec(Map.of());
        assertEquals(Boolean.TRUE, res.isError());
    }

    @Test
    void stdlib_method_rejects_blank_library() {
        var res = callMethodSpec(Map.of("library", ""));
        assertEquals(Boolean.TRUE, res.isError());
    }

    @Test
    void stdlib_method_unknown_lib_returns_found_false() {
        var res = callMethodSpec(Map.of("library", "NonExistentLib"));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        assertEquals(Boolean.FALSE, body.get("found"));
    }

    @Test
    void stdlib_method_describes_library() {
        var res = callMethodSpec(Map.of("library", "ListsLib"));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        assertEquals(Boolean.TRUE, body.get("found"));
        assertEquals("ListsLib", body.get("name"));
        @SuppressWarnings("unchecked")
        var methods = (List<Map<String, Object>>) body.get("methods");
        assertFalse(methods.isEmpty());
    }

    @Test
    void stdlib_method_filters_by_method_name() {
        var res = callMethodSpec(Map.of("library", "ListsLib", "method", "head"));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        @SuppressWarnings("unchecked")
        var methods = (List<Map<String, Object>>) body.get("methods");
        assertTrue(methods.stream().allMatch(m -> "head".equals(m.get("name"))),
                "filter must isolate to 'head': " + methods);
    }

    // ---------- julc_builtins_list ----------

    @Test
    void builtins_list_includes_raw_data_builtins() {
        // Codex P2#6 fix: tailList (returns PlutusData.ListData) was being
        // filtered out by the stdlib helper rule. Verify it now appears.
        var res = callBuiltinsSpec();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        @SuppressWarnings("unchecked")
        var methods = (List<Map<String, Object>>) body.get("methods");
        var names = methods.stream().map(m -> (String) m.get("name")).toList();
        assertTrue(names.contains("tailList"),
                "tailList must appear in builtins list (was incorrectly filtered): " + names);
        assertTrue(names.contains("headList"));
        assertTrue(names.contains("equalsByteString"));
        assertTrue(names.contains("unBData"));
    }

    @Test
    void builtins_list_excludes_setup_plumbing() {
        var res = callBuiltinsSpec();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        @SuppressWarnings("unchecked")
        var methods = (List<Map<String, Object>>) body.get("methods");
        var names = methods.stream().map(m -> (String) m.get("name")).toList();
        assertFalse(names.contains("setCryptoProvider"),
                "setCryptoProvider is non-on-chain plumbing and must be excluded: " + names);
    }

    // ---------- julc_ledger_type ----------

    @Test
    void ledger_type_no_args_lists_all_types() {
        var res = callLedgerType(Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        assertTrue(((Number) body.get("count")).intValue() >= 25);
        @SuppressWarnings("unchecked")
        var types = (List<Map<String, Object>>) body.get("types");
        var names = types.stream().map(m -> (String) m.get("name")).toList();
        assertTrue(names.contains("TxOut"));
        assertTrue(names.contains("Credential"));
        assertTrue(names.contains("ScriptContext"));
    }

    @Test
    void ledger_type_describes_known_record() {
        var res = callLedgerType(Map.of("type", "TxOut"));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        assertEquals(Boolean.TRUE, body.get("found"));
        assertEquals("record", body.get("kind"));
    }

    @Test
    void ledger_type_unknown_returns_found_false() {
        var res = callLedgerType(Map.of("type", "NoSuchType"));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        assertEquals(Boolean.FALSE, body.get("found"));
    }

    @Test
    void ledger_type_rejects_internal_helper_via_allowlist() {
        // Codex P1#4: previously Class.forName allowed describing internal
        // helpers like PlutusDataHelper. The describeType allowlist must
        // reject anything not in TYPES.
        var res = callLedgerType(Map.of("type", "PlutusDataHelper"));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        assertEquals(Boolean.FALSE, body.get("found"),
                "Internal helper PlutusDataHelper must NOT be describable: " + body);
    }

    // ---------- LedgerCatalog.TYPES ↔ package consistency ----------

    @Test
    void every_TYPES_entry_resolves_to_an_actual_class() {
        // User-perspective review + Codex: catch silent drift between
        // LedgerCatalog.TYPES and the actual ledger package.
        for (var name : LedgerCatalog.listTypes()) {
            var info = LedgerCatalog.describeType((String) name.get("name"));
            assertNotNull(info, "TYPES entry must resolve: " + name.get("name"));
        }
    }
}
