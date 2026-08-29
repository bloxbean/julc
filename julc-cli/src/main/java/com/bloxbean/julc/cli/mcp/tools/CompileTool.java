package com.bloxbean.julc.cli.mcp.tools;

import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.compiler.CompilerException;
import com.bloxbean.cardano.julc.compiler.CompilerOptions;
import com.bloxbean.cardano.julc.compiler.CompilerTarget;
import com.bloxbean.cardano.julc.compiler.CompilerTargetRegistry;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.compiler.error.CompilerDiagnostic;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool: compile JuLC source and return diagnostics + UPLC + script size.
 *
 * <p>Closes the AI compile-feedback loop: an agent generates source, calls
 * this tool, reads the structured diagnostics (with stable JULC#### codes),
 * and corrects its own output. Without this tool agents write blind.
 *
 * <h2>Input schema</h2>
 * <pre>{@code
 * {
 *   "source": "string  // required — Java source of the validator class",
 *   "target": "string  // optional — exact compiler target profile ID",
 *   "librarySources": "string[]  // optional — @OnchainLibrary source files",
 *   "includeUplc": "boolean  // optional, default false — include formatted UPLC",
 *   "includePir": "boolean  // optional, default false — include formatted PIR"
 * }
 * }</pre>
 *
 * <h2>Output (structured content)</h2>
 * <pre>{@code
 * {
 *   "ok": boolean,
 *   "compilerTarget": "plutus-v3-pv11-uplc-1.1.0",
 *   "diagnostics": [
 *     { "level":"error|warning|info", "code":"JULC0003"?, "message":..., "line":..., "column":..., "suggestion":... }
 *   ],
 *   "scriptSizeBytes": number?,
 *   "uplc": string?,    // only when includeUplc and ok
 *   "pir":  string?     // only when includePir and ok
 * }
 * }</pre>
 */
public final class CompileTool {

    private CompileTool() {}

    public static McpServerFeatures.SyncToolSpecification spec(McpJsonMapper jsonMapper) {
        var schema = """
                {
                  "type": "object",
                  "properties": {
                    "source": {
                      "type": "string",
                      "description": "Java source of the validator (or @OnchainLibrary) to compile."
                    },
                    "librarySources": {
                      "type": "array",
                      "items": { "type": "string" },
                      "description": "Optional @OnchainLibrary source files to make available."
                    },
                    "target": {
                      "type": "string",
                      "description": "Exact compiler target profile ID. Defaults to plutus-v3-pv11-uplc-1.1.0."
                    },
                    "includeUplc": {
                      "type": "boolean",
                      "description": "Include formatted UPLC in the response. Default false; UPLC can be large."
                    },
                    "includePir": {
                      "type": "boolean",
                      "description": "Include the PIR (intermediate representation) for debugging."
                    }
                  },
                  "required": ["source"],
                  "additionalProperties": false
                }
                """;
        var tool = McpSchema.Tool.builder()
                .name("julc_compile")
                .title("Compile JuLC source")
                .description("Compile JuLC (Java→UPLC) source and return diagnostics, UPLC, and " +
                        "script size. Diagnostics carry stable JULC#### codes — look them up at " +
                        "https://julc.dev/ai/diagnostics.json. Use this to verify generated " +
                        "validators before suggesting them to the user.")
                .inputSchema(jsonMapper, schema)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, req) -> handle(req, jsonMapper))
                .build();
    }

    @SuppressWarnings("unchecked")
    static McpSchema.CallToolResult handle(McpSchema.CallToolRequest req, McpJsonMapper jsonMapper) {
        Map<String, Object> args = req.arguments() == null ? Map.of() : req.arguments();
        Object srcObj = args.get("source");
        if (!(srcObj instanceof String src) || src.isBlank()) {
            return errorResult("Missing required argument 'source' (a non-empty Java source string).");
        }
        String sourceLimitError = McpLimits.validateSource("source", src);
        if (sourceLimitError != null) return errorResult(sourceLimitError);
        List<String> librarySources = List.of();
        Object libsObj = args.get("librarySources");
        if (libsObj != null) {
            // Strict validation per Phase C review: silently dropping non-string
            // items or accepting a String here masked agent mistakes.
            if (!(libsObj instanceof List<?> raw)) {
                return errorResult("'librarySources' must be an array of strings; got: " +
                        libsObj.getClass().getSimpleName());
            }
            var libs = new ArrayList<String>(raw.size());
            for (int i = 0; i < raw.size(); i++) {
                Object item = raw.get(i);
                if (!(item instanceof String s)) {
                    return errorResult("'librarySources[" + i + "]' must be a string; got: " +
                            (item == null ? "null" : item.getClass().getSimpleName()));
                }
                libs.add(s);
            }
            String libraryLimitError = McpLimits.validateLibrarySources(libs);
            if (libraryLimitError != null) return errorResult(libraryLimitError);
            librarySources = libs;
        }
        boolean includeUplc = Boolean.TRUE.equals(args.get("includeUplc"));
        boolean includePir = Boolean.TRUE.equals(args.get("includePir"));
        Object targetObj = args.getOrDefault(
                "target", CompilerTarget.PLUTUS_V3_PV11.profileId());
        if (!(targetObj instanceof String targetProfile) || targetProfile.isBlank()) {
            return errorResult("'target' must be a non-empty compiler target profile ID.");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        try {
            var target = CompilerTargetRegistry.targetForProfileId(targetProfile);
            var options = new CompilerOptions().setTarget(target);
            var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), options);
            CompileResult cr = compiler.compileWithDetails(src, librarySources);

            result.put("ok", !cr.hasErrors());
            result.put("compilerTarget", cr.target().profileId());
            result.put("diagnostics", renderDiagnostics(cr.diagnostics()));
            if (!cr.hasErrors() && cr.program() != null) {
                result.put("scriptSizeBytes", cr.scriptSizeBytes());
                result.put("scriptSizeFormatted", cr.scriptSizeFormatted());
                if (cr.isParameterized()) {
                    var params = new ArrayList<Map<String, Object>>(cr.params().size());
                    for (var p : cr.params()) {
                        params.add(Map.of("name", p.name(), "type", p.type()));
                    }
                    result.put("params", params);
                }
                if (includeUplc) {
                    result.put("uplc", cr.uplcFormatted());
                }
                if (includePir) {
                    result.put("pir", cr.pirFormatted());
                }
            }
        } catch (CompilerException e) {
            // Errors collected before throwIfErrors() are surfaced via the
            // diagnostics list; the exception itself is mostly redundant.
            // BUT: CompilerException(String) constructor leaves diagnostics
            // empty — we synthesize one in that case so the agent gets
            // *something* actionable. Codex P1.2.
            result.put("ok", false);
            var rendered = renderDiagnostics(e.diagnostics());
            if (rendered.isEmpty() && e.getMessage() != null) {
                rendered = List.of(synthesizeDiagnostic(e.getMessage()));
            }
            result.put("diagnostics", rendered);
        } catch (Exception e) {
            // Last-resort: parser blew up, etc. Wrap as a single synthetic
            // diagnostic so the agent has something to act on.
            result.put("ok", false);
            result.put("diagnostics", List.of(Map.of(
                    "level", "error",
                    "message", "Internal compile error: " + e.getMessage(),
                    "suggestion", "Check the source for malformed Java; the parser may have failed before " +
                            "JuLC's own error checks could run."
            )));
        }

        return buildResult(result, jsonMapper);
    }

    /**
     * Build a minimal diagnostic from a bare exception message. Used as a
     * fallback when {@link CompilerException} carries only a message string
     * with no underlying {@link CompilerDiagnostic} list — otherwise the
     * agent would receive {@code "ok":false, "diagnostics":[]} and have no
     * idea what went wrong.
     */
    static Map<String, Object> synthesizeDiagnostic(String message) {
        var m = new LinkedHashMap<String, Object>();
        m.put("level", "error");
        m.put("message", message);
        return m;
    }

    static List<Map<String, Object>> renderDiagnostics(List<CompilerDiagnostic> diags) {
        var out = new ArrayList<Map<String, Object>>(diags.size());
        for (var d : diags) {
            var m = new LinkedHashMap<String, Object>();
            m.put("level", d.level().name().toLowerCase());
            if (d.hasCode()) m.put("code", d.code());
            m.put("message", d.message());
            if (d.line() > 0) m.put("line", d.line());
            if (d.column() > 0) m.put("column", d.column());
            if (d.hasSuggestion()) m.put("suggestion", d.suggestion());
            if (d.fileName() != null && !d.fileName().equals("<source>")) {
                m.put("file", d.fileName());
            }
            out.add(m);
        }
        return out;
    }

    private static McpSchema.CallToolResult errorResult(String message) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(message)
                .isError(true)
                .build();
    }

    /**
     * Public re-export so other MCP tools can produce results with the same
     * dual text+structuredContent + isError-on-ok=false convention. Avoids
     * each tool reimplementing the same envelope. Phase D / R-anything.
     */
    public static McpSchema.CallToolResult buildResultPublic(Map<String, Object> body, McpJsonMapper jsonMapper) {
        return buildResult(body, jsonMapper);
    }

    private static McpSchema.CallToolResult buildResult(Map<String, Object> body, McpJsonMapper jsonMapper) {
        // We attach BOTH a JSON text content (so naive clients that only read
        // text get a readable payload) AND structuredContent (for clients
        // that consume the typed result map directly).
        String json;
        try {
            json = jsonMapper.writeValueAsString(body);
        } catch (Exception e) {
            json = "{\"ok\":false,\"diagnostics\":[{\"level\":\"error\",\"message\":\"Failed to serialize result: "
                    + e.getMessage().replace("\"", "\\\"") + "\"}]}";
        }
        return McpSchema.CallToolResult.builder()
                .addTextContent(json)
                .structuredContent(body)
                .isError(Boolean.FALSE.equals(body.get("ok")))
                .build();
    }
}
