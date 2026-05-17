package com.bloxbean.julc.cli.mcp.tools;

import com.bloxbean.julc.cli.mcp.lint.LintEngine;
import com.bloxbean.julc.cli.mcp.lint.LintFinding;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool: pre-compile static analysis of JuLC source. Catches anti-patterns
 * the compiler accepts (or fails late on) — switch-field shadow, raw
 * PlutusData construction, Optional.mkSome/mkNone, double .hash(), banned
 * @Param PlutusData subtypes, return-in-loop, and lambda .apply().
 *
 * <p>Useful as a fast pre-flight before {@code julc_compile} so AI agents
 * see the project's anti-patterns flagged with a code and a concrete fix
 * before they invest a full compile cycle.
 *
 * <h2>Input</h2>
 * <pre>{@code { "source": "string" }}</pre>
 *
 * <h2>Output</h2>
 * <pre>{@code
 * {
 *   "ruleCount": number,
 *   "findings": [
 *     { "rule":"JULC-LINT-...", "diagnostic":"JULC0021"?, "level":"error|warning|info",
 *       "message":..., "line":..., "column":..., "suggestion":... }
 *   ]
 * }
 * }</pre>
 */
public final class LintTool {

    private LintTool() {}

    public static McpServerFeatures.SyncToolSpecification spec(McpJsonMapper jsonMapper) {
        var schema = """
                {
                  "type": "object",
                  "properties": {
                    "source": {
                      "type": "string",
                      "description": "Java source to lint."
                    }
                  },
                  "required": ["source"],
                  "additionalProperties": false
                }
                """;
        var tool = McpSchema.Tool.builder()
                .name("julc_lint")
                .title("Pre-compile JuLC lint")
                .description("Static-analyze JuLC source for anti-patterns the compiler accepts " +
                        "or fails late on. Run before julc_compile to catch switch-field shadow, " +
                        "raw PlutusData construction, Optional.mkSome/mkNone (which doesn't exist " +
                        "— use Optional.of/empty), double .hash() calls, banned @Param " +
                        "PlutusData subtypes, return-in-loop, and lambda .apply().")
                .inputSchema(jsonMapper, schema)
                .build();
        var engine = new LintEngine();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, req) -> handle(req, engine, jsonMapper))
                .build();
    }

    static McpSchema.CallToolResult handle(McpSchema.CallToolRequest req, LintEngine engine,
                                            McpJsonMapper jsonMapper) {
        var args = req.arguments() == null ? Map.<String, Object>of() : req.arguments();
        Object srcObj = args.get("source");
        if (!(srcObj instanceof String src) || src.isBlank()) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Missing required argument 'source'.")
                    .isError(true)
                    .build();
        }
        String sourceLimitError = McpLimits.validateSource("source", src);
        if (sourceLimitError != null) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent(sourceLimitError)
                    .isError(true)
                    .build();
        }
        var findings = engine.lint(src);
        var rendered = new ArrayList<Map<String, Object>>(findings.size());
        for (var f : findings) {
            var m = new LinkedHashMap<String, Object>();
            m.put("rule", f.ruleId());
            if (f.diagnostic() != null) m.put("diagnostic", f.diagnostic());
            m.put("level", f.level());
            m.put("message", f.message());
            if (f.line() > 0) m.put("line", f.line());
            if (f.column() > 0) m.put("column", f.column());
            if (f.suggestion() != null) m.put("suggestion", f.suggestion());
            rendered.add(m);
        }
        Map<String, Object> body = Map.of(
                "ruleCount", engine.rules().size(),
                "findings", rendered
        );
        String json;
        try {
            json = jsonMapper.writeValueAsString(body);
        } catch (Exception e) {
            json = "{\"error\":\"serialize failed: " + e.getMessage().replace("\"", "\\\"") + "\"}";
        }
        return McpSchema.CallToolResult.builder()
                .addTextContent(json)
                .structuredContent(body)
                .build();
    }

    /** Build a tool-listing aid used by {@code julc_list_lint_rules} (future). */
    public static List<Map<String, String>> describeRules() {
        var out = new ArrayList<Map<String, String>>();
        for (var r : new LintEngine().rules()) {
            out.add(Map.of("id", r.id(), "description", r.description()));
        }
        return out;
    }
}
