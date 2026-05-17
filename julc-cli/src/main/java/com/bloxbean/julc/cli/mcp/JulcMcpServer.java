package com.bloxbean.julc.cli.mcp;

import com.bloxbean.julc.cli.JulcVersionProvider;
import com.bloxbean.julc.cli.mcp.prompts.JulcPrompts;
import com.bloxbean.julc.cli.mcp.resources.JulcResources;
import com.bloxbean.julc.cli.mcp.tools.CompileTool;
import com.bloxbean.julc.cli.mcp.tools.CostEstimateTool;
import com.bloxbean.julc.cli.mcp.tools.EvaluateTool;
import com.bloxbean.julc.cli.mcp.tools.ExamplesTools;
import com.bloxbean.julc.cli.mcp.tools.ExplainDiagnosticTool;
import com.bloxbean.julc.cli.mcp.tools.LedgerTypeTool;
import com.bloxbean.julc.cli.mcp.tools.LintTool;
import com.bloxbean.julc.cli.mcp.tools.PingTool;
import com.bloxbean.julc.cli.mcp.tools.StdlibTools;
import com.bloxbean.julc.cli.mcp.tools.TestTool;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.ServiceLoader;

/**
 * Bootstraps the JuLC MCP server.
 *
 * <p>The server exposes the JuLC compiler, linter, evaluator, and discovery
 * surface to AI agents (Claude Code, Cursor, Continue, …) over the Model
 * Context Protocol. It runs as a long-lived stdio process — clients connect
 * by spawning {@code julc mcp} and exchanging JSON-RPC over stdin/stdout.
 *
 * <p>See ADR-020 Phase C for the full tool/resource catalog.
 */
public final class JulcMcpServer {

    private JulcMcpServer() {}

    public static void run() {
        // Stdio transport: stdin/stdout are the only IO channels. Anything we
        // write to stdout that isn't a JSON-RPC frame will corrupt the session,
        // so application logging must go to stderr.
        var jsonMapper = loadJsonMapper();
        ExplainDiagnosticTool.warnIfCatalogVersionMismatch(jsonMapper);
        var transport = new StdioServerTransportProvider(jsonMapper);

        List<McpServerFeatures.SyncToolSpecification> tools = List.of(
                PingTool.spec(jsonMapper),
                CompileTool.spec(jsonMapper),
                LintTool.spec(jsonMapper),
                EvaluateTool.spec(jsonMapper),
                CostEstimateTool.spec(jsonMapper),
                ExplainDiagnosticTool.spec(jsonMapper),
                StdlibTools.listSpec(jsonMapper),
                StdlibTools.methodSpec(jsonMapper),
                StdlibTools.builtinsSpec(jsonMapper),
                LedgerTypeTool.spec(jsonMapper),
                ExamplesTools.searchSpec(jsonMapper),
                ExamplesTools.getSpec(jsonMapper),
                TestTool.spec(jsonMapper)
        );

        var resources = JulcResources.all(jsonMapper);
        var resourceTemplates = JulcResources.templates(jsonMapper);
        var prompts = JulcPrompts.all();

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo(new McpSchema.Implementation(
                        "julc-mcp", JulcVersionProvider.VERSION))
                .instructions(serverInstructions())
                .capabilities(McpSchema.ServerCapabilities.builder()
                        // Static tool catalog — we do not emit
                        // notifications/tools/list_changed, so advertise
                        // listChanged=false. Per Codex Phase C review.
                        .tools(false)
                        // Static resource catalog (subscribe=false, listChanged=false).
                        .resources(false, false)
                        // Static prompt catalog.
                        .prompts(false)
                        .build())
                .tools(tools)
                .resources(resources)
                .resourceTemplates(resourceTemplates)
                .prompts(prompts)
                .build();

        // Lifecycle:
        //   * The SDK's stdio transport processes JSON-RPC on a Reactor
        //     scheduler. Its threads are daemons, so without the parking
        //     below, main returns and the JVM exits before any frames are
        //     read.
        //   * In practice, MCP clients (Claude Code, Cursor, Continue, …)
        //     keep stdin open for the entire session and SIGTERM the
        //     process on disconnect — the shutdown hook handles that.
        //   * Codex Phase C review flagged that this prevents a clean exit
        //     on stdin EOF. That's a real edge case, but the SDK does not
        //     expose a transport-closed signal we can publicly await on,
        //     so we accept the trade-off: the hook covers SIGTERM cleanly,
        //     and clients close via signal, not EOF.
        Runtime.getRuntime().addShutdownHook(new Thread(server::closeGracefully,
                "julc-mcp-shutdown"));
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Resolve the JSON mapper via {@link ServiceLoader}. The jackson3 module
     * registers {@code JacksonMcpJsonMapperSupplier} via {@code META-INF/services},
     * so this works as long as {@code mcp-json-jackson3} is on the classpath
     * (transitively pulled in by the {@code mcp} aggregate dependency).
     */
    private static McpJsonMapper loadJsonMapper() {
        return ServiceLoader.load(McpJsonMapperSupplier.class).stream()
                .map(ServiceLoader.Provider::get)
                .map(McpJsonMapperSupplier::get)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No McpJsonMapperSupplier found on the classpath. " +
                        "Ensure io.modelcontextprotocol.sdk:mcp-json-jackson3 is " +
                        "a runtime dependency of julc-cli."));
    }

    private static String serverInstructions() {
        return """
                JuLC MCP server — exposes the Java-to-UPLC compiler for Cardano
                smart contracts as MCP tools. Use these tools to compile, lint,
                and evaluate JuLC source.

                When generating JuLC source, follow the rules at
                https://julc.dev/ai/starter-pack/. Critical:
                  * prefer high-level type classes over raw PlutusData
                  * no return inside while loops
                  * initialize variables at declaration (no var x; x = 5;)
                  * use Optional.of(x) / Optional.empty() (NOT mkSome/mkNone)
                  * @SpendingValidator entrypoint takes (redeemer, ScriptContext) or (datum, redeemer, ScriptContext)

                Errors are returned with stable JULC#### codes — look them up at
                https://julc.dev/ai/diagnostics.json for canonical fixes.
                """;
    }
}
