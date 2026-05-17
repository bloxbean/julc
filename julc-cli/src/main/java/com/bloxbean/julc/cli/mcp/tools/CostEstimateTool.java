package com.bloxbean.julc.cli.mcp.tools;

import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.compiler.CompilerException;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Conservative cost-estimation MCP tool.
 *
 * <p>JuLC does not currently expose a standalone validator benchmark runner in
 * {@code julc-cli}. This tool therefore reports the costs the CLI can measure
 * safely today:
 *
 * <ul>
 *   <li>validator compile: script size only</li>
 *   <li>method + args: single-method VM CPU/memory via {@link EvaluateTool}</li>
 * </ul>
 */
public final class CostEstimateTool {

    private CostEstimateTool() {}

    public static McpServerFeatures.SyncToolSpecification spec(McpJsonMapper jsonMapper) {
        var schema = """
                {
                  "type": "object",
                  "properties": {
                    "source": { "type": "string", "description": "JuLC Java source." },
                    "method": { "type": "string", "description": "Optional static method name to evaluate for CPU/memory budget." },
                    "args": {
                      "type": "array",
                      "description": "Optional PlutusData JSON args for method evaluation. Same shape as julc_evaluate args.",
                      "items": { "type": "object" }
                    }
                  },
                  "required": ["source"],
                  "additionalProperties": false
                }
                """;
        var tool = McpSchema.Tool.builder()
                .name("julc_estimate_costs")
                .title("Estimate JuLC script/evaluation costs")
                .description("Compile source to report script size. If method+args are supplied, also " +
                        "runs the method through the JuLC VM and reports CPU/memory budget. This is " +
                        "not a full transaction-context validator benchmark; use julc_test for behavior checks.")
                .inputSchema(jsonMapper, schema)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, req) -> handle(req, jsonMapper))
                .build();
    }

    static McpSchema.CallToolResult handle(McpSchema.CallToolRequest req, McpJsonMapper jsonMapper) {
        var args = req.arguments() == null ? Map.<String, Object>of() : req.arguments();
        if (!(args.get("source") instanceof String source) || source.isBlank()) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Missing required 'source' argument.")
                    .isError(true)
                    .build();
        }
        String sourceLimitError = McpLimits.validateSource("source", source);
        if (sourceLimitError != null) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent(sourceLimitError)
                    .isError(true)
                    .build();
        }

        if (args.get("method") instanceof String method && !method.isBlank()) {
            return estimateMethod(req.name(), source, method, args, jsonMapper);
        }
        return estimateValidatorSize(source, jsonMapper);
    }

    private static McpSchema.CallToolResult estimateValidatorSize(String source, McpJsonMapper jsonMapper) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("basis", "validator compilation");
        body.put("cpuMemoryAvailable", false);
        body.put("cpuMemoryNote",
                "No method+args were supplied, so only script size can be measured. " +
                "Use julc_test or pass method+args for VM CPU/memory.");

        try {
            var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry());
            CompileResult cr = compiler.compileWithDetails(source);
            body.put("ok", !cr.hasErrors());
            body.put("diagnostics", CompileTool.renderDiagnostics(cr.diagnostics()));
            if (!cr.hasErrors() && cr.program() != null) {
                body.put("scriptSizeBytes", cr.scriptSizeBytes());
                body.put("scriptSizeFormatted", cr.scriptSizeFormatted());
            }
        } catch (CompilerException e) {
            body.put("ok", false);
            var rendered = CompileTool.renderDiagnostics(e.diagnostics());
            if (rendered.isEmpty() && e.getMessage() != null) {
                rendered = List.of(CompileTool.synthesizeDiagnostic(e.getMessage()));
            }
            body.put("diagnostics", rendered);
        } catch (Exception e) {
            body.put("ok", false);
            body.put("diagnostics", List.of(Map.of(
                    "level", "error",
                    "message", "Cost estimate compile failed: " + e.getMessage()
            )));
        }

        return CompileTool.buildResultPublic(body, jsonMapper);
    }

    private static McpSchema.CallToolResult estimateMethod(String toolName, String source, String method,
                                                           Map<String, Object> args, McpJsonMapper jsonMapper) {
        Map<String, Object> evalArgs = new LinkedHashMap<>();
        evalArgs.put("source", source);
        evalArgs.put("method", method);
        if (args.containsKey("args")) evalArgs.put("args", args.get("args"));

        var evalReq = new McpSchema.CallToolRequest(toolName, evalArgs);
        var evalResult = EvaluateTool.handle(evalReq, jsonMapper);
        Object structured = evalResult.structuredContent();
        if (!(structured instanceof Map<?, ?> evalBodyRaw)) {
            return evalResult;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("basis", "single-method VM evaluation");
        body.put("method", method);
        body.put("ok", evalBodyRaw.get("ok"));
        if (evalBodyRaw.containsKey("cpu")) body.put("cpu", evalBodyRaw.get("cpu"));
        if (evalBodyRaw.containsKey("memory")) body.put("memory", evalBodyRaw.get("memory"));
        if (evalBodyRaw.containsKey("traces")) body.put("traces", evalBodyRaw.get("traces"));
        if (evalBodyRaw.containsKey("resultType")) body.put("resultType", evalBodyRaw.get("resultType"));
        if (evalBodyRaw.containsKey("error")) body.put("error", evalBodyRaw.get("error"));
        if (evalBodyRaw.containsKey("diagnostics")) body.put("diagnostics", evalBodyRaw.get("diagnostics"));
        body.put("note",
                "CPU/memory are for this method invocation and supplied args, not a full transaction-context benchmark.");
        return CompileTool.buildResultPublic(body, jsonMapper);
    }
}
