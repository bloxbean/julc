package com.bloxbean.julc.cli.mcp.tools;

import com.bloxbean.julc.cli.JulcVersionProvider;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * MCP tool: look up a JULC#### diagnostic code in the catalog and return
 * its title, root cause, fix, and bad/good example.
 *
 * <p>Closes the question agents otherwise have to ask the user: "I got
 * JULC0015, what does it mean?". The tool reads
 * {@code /diagnostics.json} from the {@code julc-compiler} classpath so it
 * works fully offline — no HTTP fetch needed.
 *
 * <h2>Input</h2>
 * <pre>{@code { "code": "JULC0015" }}</pre>
 *
 * <h2>Output</h2>
 * <pre>{@code
 * {
 *   "found": boolean,
 *   "code": "JULC0015",
 *   "title": "...",
 *   "category": "...",
 *   "severity": "error|warning",
 *   "summary": "...",
 *   "fix": "...",
 *   "example": { "bad": "...", "good": "..." }
 * }
 * }</pre>
 *
 * <p>If the code is not in the catalog, returns
 * {@code { "found": false, "code": "...", "candidates": [...] }}
 * with up to 5 fuzzy-prefix matches so an agent that mistypes can self-correct.
 */
public final class ExplainDiagnosticTool {

    private static final String CATALOG_RESOURCE = "/diagnostics.json";

    private ExplainDiagnosticTool() {}

    public static McpServerFeatures.SyncToolSpecification spec(McpJsonMapper jsonMapper) {
        var schema = """
                {
                  "type": "object",
                  "properties": {
                    "code": {
                      "type": "string",
                      "description": "Diagnostic code to look up, e.g. \\"JULC0015\\"."
                    }
                  },
                  "required": ["code"],
                  "additionalProperties": false
                }
                """;
        var tool = McpSchema.Tool.builder()
                .name("julc_explain_diagnostic")
                .title("Explain a JuLC diagnostic code")
                .description("Look up the canonical root cause + fix for a JULC#### diagnostic " +
                        "code (e.g. emitted by julc_compile). Returns title, summary, fix snippet, " +
                        "and bad/good code example. Use this when an agent sees a code it " +
                        "doesn't immediately recognize.")
                .inputSchema(jsonMapper, schema)
                .build();
        // Load + parse the catalog once at tool registration; the file is
        // small (< 10 KB) and immutable for the process lifetime.
        Map<String, Map<String, Object>> byCode = loadCatalog(jsonMapper);
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, req) -> handle(req, byCode, jsonMapper))
                .build();
    }

    /**
     * Startup health check for AI-facing diagnostics metadata. A mismatch
     * means the CLI is running with a diagnostics catalog generated for a
     * different JuLC version, so agents may receive stale fix guidance.
     */
    public static void warnIfCatalogVersionMismatch(McpJsonMapper jsonMapper) {
        String catalogVersion = loadCatalogVersion(jsonMapper);
        if (catalogVersion == null || catalogVersion.isBlank()) {
            System.err.println("[julc-mcp] WARN: diagnostics catalog has no julcVersion metadata; " +
                    "canonical fixes may not match julc " + JulcVersionProvider.VERSION + ".");
            return;
        }
        if (!JulcVersionProvider.VERSION.equals(catalogVersion)) {
            System.err.println("[julc-mcp] WARN: diagnostics catalog julcVersion " +
                    catalogVersion + " differs from running CLI " +
                    JulcVersionProvider.VERSION + ".");
        }
    }

    static McpSchema.CallToolResult handle(McpSchema.CallToolRequest req,
                                            Map<String, Map<String, Object>> byCode,
                                            McpJsonMapper jsonMapper) {
        var args = req.arguments() == null ? Map.<String, Object>of() : req.arguments();
        if (!(args.get("code") instanceof String code) || code.isBlank()) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Missing required 'code' argument (e.g. \"JULC0015\").")
                    .isError(true)
                    .build();
        }
        // Normalize: accept lower-case input, brackets, etc.
        String normalized = code.trim().replace("[", "").replace("]", "").toUpperCase();
        var entry = byCode.get(normalized);
        Map<String, Object> body = new LinkedHashMap<>();
        if (entry == null) {
            body.put("found", false);
            body.put("code", normalized);
            body.put("candidates", suggestNearby(normalized, byCode.keySet()));
            if (byCode.isEmpty()) {
                body.put("message", "Diagnostics catalog is not bundled with this CLI build. " +
                        "Fetch the canonical catalog at https://julc.dev/ai/diagnostics.json.");
            } else {
                body.put("message", "No catalog entry for code '" + normalized +
                        "'. The full catalog is at https://julc.dev/ai/diagnostics.json.");
            }
        } else {
            body.put("found", true);
            body.putAll(entry);
        }
        return CompileTool.buildResultPublic(body, jsonMapper);
    }

    /**
     * Returns at most 5 catalog codes whose 4-digit suffix is numerically
     * close to the requested code (handles "I typed 0103 but meant 0013"
     * and "I typed JULC15 but meant JULC0015").
     */
    static List<String> suggestNearby(String code, java.util.Set<String> all) {
        // Try padding short codes to 4 digits.
        if (code.matches("JULC\\d{1,3}")) {
            String num = code.substring(4);
            String padded = "JULC" + "0".repeat(4 - num.length()) + num;
            if (all.contains(padded)) return List.of(padded);
        }
        // Otherwise, return up to 5 codes within ±2 numeric distance.
        if (!code.matches("JULC\\d{4}")) return List.of();
        int target;
        try {
            target = Integer.parseInt(code.substring(4));
        } catch (NumberFormatException nfe) {
            return List.of();
        }
        return all.stream()
                .filter(c -> c.matches("JULC\\d{4}"))
                .sorted((a, b) -> {
                    int da = Math.abs(Integer.parseInt(a.substring(4)) - target);
                    int db = Math.abs(Integer.parseInt(b.substring(4)) - target);
                    return Integer.compare(da, db);
                })
                .limit(5)
                .toList();
    }

    /**
     * Best-effort load. If the resource is missing or malformed, return an
     * empty map and let {@link #handle} surface a graceful "catalog not on
     * classpath" message at request time. Earlier versions threw at
     * registration, killing the entire MCP server before any other tool
     * could be reached. Phase D review (Codex P1#3).
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> loadCatalog(McpJsonMapper jsonMapper) {
        try (InputStream in = ExplainDiagnosticTool.class.getResourceAsStream(CATALOG_RESOURCE)) {
            if (in == null) {
                System.err.println("[julc-mcp] WARN: diagnostics catalog not on classpath at "
                        + CATALOG_RESOURCE + " — julc_explain_diagnostic will return a fallback message.");
                return Map.of();
            }
            String body = new String(in.readAllBytes());
            Map<String, Object> root = jsonMapper.readValue(body, Map.class);
            List<Map<String, Object>> entries = (List<Map<String, Object>>) root.get("diagnostics");
            if (entries == null) return Map.of();
            var byCode = new java.util.HashMap<String, Map<String, Object>>(entries.size());
            for (var e : entries) {
                Object c = e.get("code");
                if (c instanceof String s) byCode.put(s, e);
            }
            return byCode;
        } catch (Exception e) {
            // Same graceful-degradation policy: never block server startup.
            System.err.println("[julc-mcp] WARN: failed to load diagnostics catalog: " + e.getMessage());
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static String loadCatalogVersion(McpJsonMapper jsonMapper) {
        try (InputStream in = ExplainDiagnosticTool.class.getResourceAsStream(CATALOG_RESOURCE)) {
            if (in == null) return null;
            String body = new String(in.readAllBytes());
            Map<String, Object> root = jsonMapper.readValue(body, Map.class);
            Object version = root.get("julcVersion");
            return version instanceof String s ? s : null;
        } catch (Exception e) {
            System.err.println("[julc-mcp] WARN: failed to read diagnostics catalog version: " +
                    e.getMessage());
            return null;
        }
    }
}
