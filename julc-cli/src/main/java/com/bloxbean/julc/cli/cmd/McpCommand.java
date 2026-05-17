package com.bloxbean.julc.cli.cmd;

import com.bloxbean.julc.cli.mcp.JulcMcpServer;
import picocli.CommandLine.Command;

/**
 * Run the JuLC MCP server over stdio.
 *
 * <p>Designed to be invoked by AI tools (Claude Code, Cursor, Continue, …)
 * via their standard MCP server-launch mechanism. Configuration snippet:
 *
 * <pre>{@code
 * { "mcpServers": { "julc": { "command": "julc", "args": ["mcp"] } } }
 * }</pre>
 *
 * <p>This command runs until the MCP client ends the session, typically by
 * sending SIGTERM to the process. All application logging goes to stderr;
 * stdout is reserved for the JSON-RPC framing.
 */
@Command(name = "mcp",
        mixinStandardHelpOptions = true,
        description = "Run the JuLC MCP server over stdio (for AI agents).")
public class McpCommand implements Runnable {

    @Override
    public void run() {
        JulcMcpServer.run();
    }
}
