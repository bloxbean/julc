package com.bloxbean.julc.cli.mcp.tools;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Examples tool tests. The tool reads from sibling julc-examples checkout;
 * tests are skipped if that checkout isn't present.
 */
class ExamplesToolsTest {

    private static McpJsonMapper jsonMapper;

    @BeforeAll
    static void init() {
        jsonMapper = ServiceLoader.load(McpJsonMapperSupplier.class).stream()
                .map(ServiceLoader.Provider::get)
                .map(McpJsonMapperSupplier::get)
                .findFirst().orElseThrow();
    }

    /**
     * Predicate used by {@code @EnabledIf}: only run these tests when the
     * sibling repo is present, so CI without it doesn't fail.
     */
    @SuppressWarnings("unused")
    static boolean siblingRepoPresent() {
        return Files.isRegularFile(Path.of("../julc-examples/ai/examples-index.json"))
                || Files.isRegularFile(Path.of("../../julc-examples/ai/examples-index.json"));
    }

    private static McpSchema.CallToolResult callSearch(Map<String, Object> args) {
        return ExamplesTools.handleSearch(
                new McpSchema.CallToolRequest("julc_examples_search", args),
                jsonMapper);
    }

    private static McpSchema.CallToolResult callGet(String id) {
        return ExamplesTools.handleGet(
                new McpSchema.CallToolRequest("julc_example_get", Map.of("id", id)),
                jsonMapper);
    }

    @Test
    @EnabledIf("siblingRepoPresent")
    void search_returns_all_examples_with_no_filters() {
        var res = callSearch(Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        @SuppressWarnings("unchecked")
        var examples = (List<Map<String, Object>>) body.get("examples");
        assertNotNull(examples);
        assertTrue(examples.size() >= 30,
                "expected the full corpus when no filters; got " + examples.size());
        // Slim shape — no 'source' field bloats the response.
        for (var ex : examples) {
            assertFalse(ex.containsKey("source"),
                    "search results must not echo the heavy 'source' field");
            assertNotNull(ex.get("id"));
        }
    }

    @Test
    @EnabledIf("siblingRepoPresent")
    void search_filters_by_concept_tag() {
        var res = callSearch(Map.of("concept", "auction"));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        @SuppressWarnings("unchecked")
        var examples = (List<Map<String, Object>>) body.get("examples");
        assertFalse(examples.isEmpty(), "concept=auction should match at least one example");
        for (var ex : examples) {
            @SuppressWarnings("unchecked")
            var concepts = (List<String>) ex.get("concepts");
            assertTrue(concepts.contains("auction"),
                    "every result must carry the auction concept: " + ex.get("id"));
        }
    }

    @Test
    @EnabledIf("siblingRepoPresent")
    void search_filters_by_canonical_flag() {
        var res = callSearch(Map.of("canonical", true));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        @SuppressWarnings("unchecked")
        var examples = (List<Map<String, Object>>) body.get("examples");
        assertFalse(examples.isEmpty(), "should have at least one canonical example");
        for (var ex : examples) {
            assertEquals(Boolean.TRUE, ex.get("canonical"));
        }
    }

    @Test
    @EnabledIf("siblingRepoPresent")
    void get_returns_metadata_and_source_for_known_id() {
        var res = callGet("vesting-validator");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        assertEquals(Boolean.TRUE, body.get("found"));
        assertEquals("vesting-validator", body.get("id"));
        assertNotNull(body.get("sourceText"),
                "default includeSource=true should attach the file content");
        assertTrue(((String) body.get("sourceText")).contains("@SpendingValidator"),
                "vesting validator source should include @SpendingValidator");
    }

    @Test
    @EnabledIf("siblingRepoPresent")
    void get_returns_not_found_for_unknown_id() {
        var res = callGet("frobnicate-validator");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        assertEquals(Boolean.FALSE, body.get("found"));
        assertEquals("frobnicate-validator", body.get("id"));
    }

    @Test
    void get_rejects_missing_id() {
        var res = ExamplesTools.handleGet(
                new McpSchema.CallToolRequest("julc_example_get", Map.of()),
                jsonMapper);
        assertEquals(Boolean.TRUE, res.isError());
    }
}
