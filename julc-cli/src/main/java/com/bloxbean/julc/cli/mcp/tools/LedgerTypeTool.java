package com.bloxbean.julc.cli.mcp.tools;

import com.bloxbean.julc.cli.mcp.catalog.LedgerCatalog;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP tool: describe a JuLC ledger type — record fields, sealed-interface
 * variants, instance methods. Stops AI from guessing field names like
 * "txInfo.outputs" vs "txInfo.txOuts".
 *
 * <h2>Input</h2>
 * <pre>{@code { "type": "TxOut" }}</pre>
 *
 * <p>Or omit {@code type} to list all known ledger type names.
 */
public final class LedgerTypeTool {

    private LedgerTypeTool() {}

    public static McpServerFeatures.SyncToolSpecification spec(McpJsonMapper jsonMapper) {
        var schema = """
                {
                  "type": "object",
                  "properties": {
                    "type": {
                      "type": "string",
                      "description": "Ledger type name to describe (e.g. \\"TxOut\\", \\"Credential\\"). Omit to list all."
                    }
                  },
                  "additionalProperties": false
                }
                """;
        var tool = McpSchema.Tool.builder()
                .name("julc_ledger_type")
                .title("Describe a JuLC ledger type")
                .description("Return record fields, sealed-interface variants, and instance " +
                        "methods for a JuLC ledger type from com.bloxbean.cardano.julc.ledger. " +
                        "Without 'type', lists all known type names. Use this before referencing " +
                        "a field like ctx.txInfo().outputs() to confirm the exact name. " +
                        "Prefer this over reading julc://ledger.json for single-type lookups. " +
                        "For richer docs (Javadoc), fetch https://julc.dev/ai/catalog.json.")
                .inputSchema(jsonMapper, schema)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, req) -> handle(req, jsonMapper))
                .build();
    }

    static McpSchema.CallToolResult handle(McpSchema.CallToolRequest req, McpJsonMapper jsonMapper) {
        var args = req.arguments() == null ? Map.<String, Object>of() : req.arguments();
        Object typeObj = args.get("type");
        Map<String, Object> body = new LinkedHashMap<>();
        if (!(typeObj instanceof String typeName) || typeName.isBlank()) {
            // List mode.
            var types = LedgerCatalog.listTypes();
            body.put("count", types.size());
            body.put("types", types);
            return CompileTool.buildResultPublic(body, jsonMapper);
        }
        var info = LedgerCatalog.describeType(typeName);
        if (info == null) {
            body.put("found", false);
            body.put("type", typeName);
            body.put("message", "Unknown ledger type: " + typeName +
                    ". Call this tool without arguments to list all available types.");
        } else {
            body.put("found", true);
            body.putAll(info);
        }
        return CompileTool.buildResultPublic(body, jsonMapper);
    }
}
