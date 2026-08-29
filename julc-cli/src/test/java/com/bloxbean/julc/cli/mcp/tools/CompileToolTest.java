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

/**
 * Direct unit tests against {@link CompileTool#handle} — bypasses the MCP
 * stdio transport so we can exercise the closed-loop behavior fast.
 *
 * <p>The CallToolResult shape is also asserted (structuredContent + a JSON
 * text content) so MCP clients that consume either form keep working.
 */
class CompileToolTest {

    private static McpJsonMapper jsonMapper;

    @BeforeAll
    static void loadMapper() {
        jsonMapper = ServiceLoader.load(McpJsonMapperSupplier.class).stream()
                .map(ServiceLoader.Provider::get)
                .map(McpJsonMapperSupplier::get)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void compilesValidValidator() {
        String src = """
                import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
                import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;

                @SpendingValidator
                public class AlwaysOk {
                    record Datum() {}
                    record Redeemer() {}
                    @Entrypoint
                    public static boolean validate(Datum d, Redeemer r, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var req = new McpSchema.CallToolRequest("julc_compile", Map.of("source", src));
        var res = CompileTool.handle(req, jsonMapper);

        assertNotEquals(Boolean.TRUE, res.isError(), "happy path should not be flagged isError");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        assertEquals(Boolean.TRUE, body.get("ok"));
        assertTrue(((Number) body.get("scriptSizeBytes")).intValue() > 0);
        @SuppressWarnings("unchecked")
        var diags = (List<Map<String, Object>>) body.get("diagnostics");
        assertTrue(diags.stream().noneMatch(d -> "error".equals(d.get("level"))),
                "happy path diagnostics must not include errors: " + diags);
    }

    @Test
    void surfacesDiagnosticCodeForBannedFeature() {
        // try/catch is rejected by SubsetValidator with code JULC0015.
        String src = """
                import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
                import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;

                @SpendingValidator
                public class BadValidator {
                    record Datum() {}
                    record Redeemer() {}
                    @Entrypoint
                    public static boolean validate(Datum d, Redeemer r, ScriptContext ctx) {
                        try { return true; } catch (Exception e) { return false; }
                    }
                }
                """;
        var req = new McpSchema.CallToolRequest("julc_compile", Map.of("source", src));
        var res = CompileTool.handle(req, jsonMapper);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        assertEquals(Boolean.FALSE, body.get("ok"));
        @SuppressWarnings("unchecked")
        var diags = (List<Map<String, Object>>) body.get("diagnostics");
        boolean foundJulc0015 = diags.stream()
                .anyMatch(d -> "JULC0015".equals(d.get("code")));
        assertTrue(foundJulc0015,
                "Expected JULC0015 (try/catch) in diagnostics, got: " + diags);
        // Suggestion should be present so the agent has an actionable fix.
        var first = diags.get(0);
        assertTrue(first.containsKey("suggestion"),
                "Diagnostic must carry a suggestion for AI agent recovery");
    }

    @Test
    void surfacesDiagnosticCodeForMissingValidatorAnnotation() {
        String src = """
                import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;

                public class MissingValidator {
                    record Redeemer() {}
                    @Entrypoint
                    public static boolean validate(Redeemer r, ScriptContext ctx) {
                        return true;
                    }
                }
                """;

        assertDiagnosticCode(src, "JULC0010");
    }

    @Test
    void surfacesDiagnosticCodeForMissingEntrypoint() {
        String src = """
                import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;

                @SpendingValidator
                public class MissingEntrypoint {
                    record Redeemer() {}
                    public static boolean validate(Redeemer r, ScriptContext ctx) {
                        return true;
                    }
                }
                """;

        assertDiagnosticCode(src, "JULC0009");
    }

    @Test
    void surfacesDiagnosticCodeForWrongEntrypointArity() {
        String src = """
                import com.bloxbean.cardano.julc.stdlib.annotation.MintingValidator;
                import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;

                @MintingValidator
                public class WrongArity {
                    record Datum() {}
                    record Redeemer() {}
                    @Entrypoint
                    public static boolean validate(Datum d, Redeemer r, ScriptContext ctx) {
                        return true;
                    }
                }
                """;

        assertDiagnosticCode(src, "JULC0008");
    }

    @Test
    void rejectsMissingSource() {
        var req = new McpSchema.CallToolRequest("julc_compile", Map.of());
        var res = CompileTool.handle(req, jsonMapper);
        assertEquals(Boolean.TRUE, res.isError(),
                "missing 'source' arg must be flagged as a tool-call error");
    }

    @Test
    void rejectsOversizedSource() {
        String huge = "/*".repeat(McpLimits.MAX_SOURCE_BYTES / 2 + 1);
        var req = new McpSchema.CallToolRequest("julc_compile", Map.of("source", huge));
        var res = CompileTool.handle(req, jsonMapper);
        assertEquals(Boolean.TRUE, res.isError(),
                "oversized source must be rejected before compile");
    }

    @Test
    void includesUplcWhenRequested() {
        String src = """
                import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
                import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;

                @SpendingValidator
                public class V {
                    record D() {}
                    record R() {}
                    @Entrypoint
                    public static boolean validate(D d, R r, ScriptContext ctx) { return true; }
                }
                """;
        var req = new McpSchema.CallToolRequest("julc_compile",
                Map.of("source", src, "includeUplc", true));
        var res = CompileTool.handle(req, jsonMapper);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        assertEquals(Boolean.TRUE, body.get("ok"));
        assertNotNull(body.get("uplc"),
                "uplc must be present when includeUplc=true");
        assertEquals("plutus-v3-pv11-uplc-1.1.0", body.get("compilerTarget"));
        @SuppressWarnings("unchecked")
        var optimization = (Map<String, Object>) body.get("optimization");
        assertEquals("pv11-safe", optimization.get("level"));
    }

    @Test
    void rejectsUnknownCompilerTargetWithStableDiagnostic() {
        var req = new McpSchema.CallToolRequest("julc_compile", Map.of(
                "source", "class Empty {}",
                "target", "plutus-v3-pv12-uplc-1.1.0"));
        var res = CompileTool.handle(req, jsonMapper);
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) res.structuredContent();
        @SuppressWarnings("unchecked")
        var diagnostics = (List<Map<String, Object>>) body.get("diagnostics");
        assertEquals(Boolean.FALSE, body.get("ok"));
        assertTrue(diagnostics.stream().anyMatch(
                diagnostic -> "JULC0031".equals(diagnostic.get("code"))));
    }

    @Test
    void rejectsUnknownOptimizationWithStableDiagnostic() {
        var req = new McpSchema.CallToolRequest("julc_compile", Map.of(
                "source", "class Empty {}",
                "optimization", "PV11_SAFE"));
        var res = CompileTool.handle(req, jsonMapper);
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) res.structuredContent();
        @SuppressWarnings("unchecked")
        var diagnostics = (List<Map<String, Object>>) body.get("diagnostics");
        assertEquals(Boolean.FALSE, body.get("ok"));
        assertTrue(diagnostics.stream().anyMatch(
                diagnostic -> "JULC0039".equals(diagnostic.get("code"))));
    }

    @Test
    void compilesWithLibrarySource() {
        // Phase C review found no test for librarySources — closing the gap.
        // A validator that calls into a separate @OnchainLibrary class.
        String librarySrc = """
                import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                public class Helper {
                    public static boolean alwaysTrue() { return true; }
                }
                """;
        String validatorSrc = """
                import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
                import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;

                @SpendingValidator
                public class WithLib {
                    record D() {} record R() {}
                    @Entrypoint
                    public static boolean validate(D d, R r, ScriptContext ctx) {
                        return Helper.alwaysTrue();
                    }
                }
                """;
        var req = new McpSchema.CallToolRequest("julc_compile", Map.of(
                "source", validatorSrc,
                "librarySources", List.of(librarySrc)
        ));
        var res = CompileTool.handle(req, jsonMapper);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        assertEquals(Boolean.TRUE, body.get("ok"),
                "compile-with-library should succeed: " + body);
    }

    @Test
    void rejectsLibrarySourcesAsString() {
        // Strict validation per Phase C review: passing a String for
        // `librarySources` (instead of an array) must produce a clear error.
        var req = new McpSchema.CallToolRequest("julc_compile", Map.of(
                "source", "class X {}",
                "librarySources", "not-an-array"
        ));
        var res = CompileTool.handle(req, jsonMapper);
        assertEquals(Boolean.TRUE, res.isError(),
                "non-array librarySources must be flagged as a tool-call error");
    }

    @Test
    void rejectsLibrarySourcesWithNonStringItem() {
        var req = new McpSchema.CallToolRequest("julc_compile", Map.of(
                "source", "class X {}",
                "librarySources", List.of(42)
        ));
        var res = CompileTool.handle(req, jsonMapper);
        assertEquals(Boolean.TRUE, res.isError());
    }

    @Test
    void rejectsOversizedLibrarySource() {
        String hugeLibrary = "/*".repeat(McpLimits.MAX_LIBRARY_SOURCE_BYTES / 2 + 1);
        var req = new McpSchema.CallToolRequest("julc_compile", Map.of(
                "source", "class X {}",
                "librarySources", List.of(hugeLibrary)
        ));
        var res = CompileTool.handle(req, jsonMapper);
        assertEquals(Boolean.TRUE, res.isError(),
                "oversized librarySources entry must be rejected before compile");
    }

    @Test
    void resultCarriesBothStructuredAndTextContent() {
        // MCP spec recommends tools return structured AND text content so all
        // client implementations can consume the result. Verify both are set.
        var req = new McpSchema.CallToolRequest("julc_compile",
                Map.of("source", "class Empty {}"));
        var res = CompileTool.handle(req, jsonMapper);
        assertNotNull(res.structuredContent(), "structuredContent must be populated");
        assertFalse(res.content().isEmpty(), "text content must be populated");
        // The first content block should be parseable JSON.
        var first = res.content().get(0);
        assertInstanceOf(McpSchema.TextContent.class, first);
        var text = ((McpSchema.TextContent) first).text();
        assertTrue(text.startsWith("{"), "text content should be a JSON object: " + text);
    }

    private void assertDiagnosticCode(String src, String expectedCode) {
        var req = new McpSchema.CallToolRequest("julc_compile", Map.of("source", src));
        var res = CompileTool.handle(req, jsonMapper);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        assertEquals(Boolean.FALSE, body.get("ok"));
        @SuppressWarnings("unchecked")
        var diags = (List<Map<String, Object>>) body.get("diagnostics");
        assertTrue(diags.stream().anyMatch(d -> expectedCode.equals(d.get("code"))),
                "Expected " + expectedCode + " in diagnostics, got: " + diags);
        assertTrue(diags.stream().anyMatch(d -> d.containsKey("suggestion")),
                "Expected an actionable suggestion, got: " + diags);
    }
}
