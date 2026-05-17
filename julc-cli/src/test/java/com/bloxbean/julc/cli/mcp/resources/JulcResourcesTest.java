package com.bloxbean.julc.cli.mcp.resources;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link JulcResources} — verifies all 6 registered resources
 * exist with correct URIs, MIME types, and parseable bodies.
 *
 * <p>Phase D review (impl-validator P1 + Codex P2#11): JulcResources had no
 * direct tests; protocol regressions could ship unnoticed.
 */
class JulcResourcesTest {

    private static McpJsonMapper jsonMapper;
    private static List<McpServerFeatures.SyncResourceSpecification> all;
    private static List<McpServerFeatures.SyncResourceTemplateSpecification> templates;

    @BeforeAll
    static void init() {
        jsonMapper = ServiceLoader.load(McpJsonMapperSupplier.class).stream()
                .map(ServiceLoader.Provider::get)
                .map(McpJsonMapperSupplier::get)
                .findFirst().orElseThrow();
        all = JulcResources.all(jsonMapper);
        templates = JulcResources.templates(jsonMapper);
    }

    @Test
    void registers_all_expected_resources() {
        var uris = all.stream().map(s -> s.resource().uri()).collect(java.util.stream.Collectors.toSet());
        assertEquals(new HashSet<>(List.of(
                "julc://limitations.md",
                "julc://diagnostics.json",
                "julc://stdlib.json",
                "julc://ledger.json",
                "julc://builtins.json",
                "julc://server-info.md"
        )), uris);
    }

    @Test
    void all_resources_have_a_title_and_description() {
        for (var spec : all) {
            assertNotNull(spec.resource().title(), "missing title: " + spec.resource().uri());
            assertNotNull(spec.resource().description(), "missing description: " + spec.resource().uri());
            assertNotNull(spec.resource().mimeType(), "missing mimeType: " + spec.resource().uri());
        }
    }

    @Test
    void limitations_resource_returns_markdown_with_critical_rules() {
        var body = readResource("julc://limitations.md");
        assertTrue(body.contains("Optional.mkSome"),
                "limitations doc must call out Optional.mkSome anti-pattern");
        assertTrue(body.contains("return") && body.contains("while"),
                "must mention no-return-in-while");
        assertTrue(body.contains("PlutusData"),
                "must mention raw-PlutusData rule");
    }

    @Test
    void diagnostics_resource_returns_loaded_catalog() {
        var body = readResource("julc://diagnostics.json");
        assertTrue(body.contains("JULC0001"),
                "diagnostics resource must include the catalog: " + body.substring(0, Math.min(200, body.length())));
    }

    @Test
    void stdlib_resource_returns_libraries_array() {
        var body = readResource("julc://stdlib.json");
        assertTrue(body.contains("\"libraries\""),
                "stdlib resource must wrap libraries: " + body.substring(0, Math.min(80, body.length())));
        assertTrue(body.contains("ListsLib"));
    }

    @Test
    void ledger_resource_returns_types_array() {
        var body = readResource("julc://ledger.json");
        assertTrue(body.contains("\"types\""));
        assertTrue(body.contains("TxOut"));
    }

    @Test
    void builtins_resource_includes_raw_data_methods() {
        var body = readResource("julc://builtins.json");
        assertTrue(body.contains("tailList"),
                "builtins resource must include tailList: " + body.substring(0, Math.min(200, body.length())));
    }

    @Test
    void server_info_lists_every_registered_tool() {
        // Codex P2#12: server-info was stale and missed julc_test. The list
        // is now centralized in JulcResources.TOOL_NAMES; verify it covers
        // every tool an agent will see in tools/list.
        var body = readResource("julc://server-info.md");
        for (var name : JulcResources.TOOL_NAMES) {
            assertTrue(body.contains(name),
                    "server-info missing tool: " + name);
        }
    }

    @Test
    void server_info_lists_every_registered_resource() {
        var body = readResource("julc://server-info.md");
        for (var spec : all) {
            assertTrue(body.contains(spec.resource().uri()),
                    "server-info missing resource: " + spec.resource().uri());
        }
        // server-info itself must appear in the resources list (honesty about
        // what an agent can fetch).
        assertTrue(body.contains("julc://server-info.md"));
    }

    @Test
    void registers_expected_resource_templates() {
        var uris = templates.stream()
                .map(s -> s.resourceTemplate().uriTemplate())
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(new HashSet<>(JulcResources.RESOURCE_TEMPLATE_URIS), uris);
        for (var spec : templates) {
            assertNotNull(spec.resourceTemplate().title(),
                    "missing title: " + spec.resourceTemplate().uriTemplate());
            assertNotNull(spec.resourceTemplate().description(),
                    "missing description: " + spec.resourceTemplate().uriTemplate());
        }
    }

    @Test
    void resource_templates_return_catalog_content() {
        assertTrue(readTemplate("julc://stdlib/ListsLib").contains("\"name\":\"ListsLib\""));
        assertTrue(readTemplate("julc://stdlib/ListsLib/head").contains("\"head\""));
        assertTrue(readTemplate("julc://ledger/TxOut").contains("\"name\":\"TxOut\""));
    }

    private String readResource(String uri) {
        var spec = all.stream()
                .filter(s -> uri.equals(s.resource().uri()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no resource: " + uri));
        var result = spec.readHandler().apply(null,
                new McpSchema.ReadResourceRequest(uri));
        var contents = result.contents();
        assertEquals(1, contents.size());
        var c = contents.get(0);
        assertInstanceOf(McpSchema.TextResourceContents.class, c);
        return ((McpSchema.TextResourceContents) c).text();
    }

    private String readTemplate(String uri) {
        var spec = templates.stream()
                .filter(s -> matchesTemplate(s.resourceTemplate().uriTemplate(), uri))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no template for: " + uri));
        var result = spec.readHandler().apply(null,
                new McpSchema.ReadResourceRequest(uri));
        var contents = result.contents();
        assertEquals(1, contents.size());
        var c = contents.get(0);
        assertInstanceOf(McpSchema.TextResourceContents.class, c);
        return ((McpSchema.TextResourceContents) c).text();
    }

    private boolean matchesTemplate(String template, String uri) {
        if ("julc://stdlib/{lib}".equals(template)) {
            return uri.startsWith("julc://stdlib/")
                    && uri.substring("julc://stdlib/".length()).indexOf('/') < 0;
        }
        if ("julc://stdlib/{lib}/{method}".equals(template)) {
            return uri.startsWith("julc://stdlib/")
                    && uri.substring("julc://stdlib/".length()).contains("/");
        }
        if ("julc://ledger/{type}".equals(template)) return uri.startsWith("julc://ledger/");
        if ("julc://examples/{id}".equals(template)) return uri.startsWith("julc://examples/");
        return false;
    }
}
