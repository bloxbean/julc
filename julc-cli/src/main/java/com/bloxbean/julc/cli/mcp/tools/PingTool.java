package com.bloxbean.julc.cli.mcp.tools;

import com.bloxbean.julc.cli.JulcVersionProvider;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;

/**
 * Minimal smoke-test tool that verifies the MCP transport is wired correctly.
 * Returns the JuLC version + a static greeting; takes no arguments.
 *
 * <p>Kept in the long-term tool set (not just for bootstrap testing) so AI
 * agents have a no-side-effect way to confirm an MCP session is alive
 * before issuing a real compile request.
 */
public final class PingTool {

    private PingTool() {}

    public static McpServerFeatures.SyncToolSpecification spec(McpJsonMapper jsonMapper) {
        // Schema: no input parameters. Empty object schema is the standard
        // MCP convention for a parameterless tool.
        var inputSchema = """
                { "type": "object", "properties": {}, "additionalProperties": false }
                """;
        var tool = McpSchema.Tool.builder()
                .name("julc_ping")
                .title("JuLC ping")
                .description("Returns the JuLC version. Use to verify the MCP " +
                        "server is alive before issuing real compile requests. " +
                        "No arguments; no side effects.")
                .inputSchema(jsonMapper, inputSchema)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, req) ->
                        McpSchema.CallToolResult.builder()
                                .addTextContent("julc-mcp v" + JulcVersionProvider.VERSION + " — alive")
                                .build())
                .build();
    }
}
