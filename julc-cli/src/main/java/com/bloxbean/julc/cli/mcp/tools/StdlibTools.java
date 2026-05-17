package com.bloxbean.julc.cli.mcp.tools;

import com.bloxbean.julc.cli.mcp.catalog.StdlibCatalog;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stdlib discovery tools — three MCP tools that let agents enumerate the
 * stdlib API surface and look up specific methods, without making up names.
 *
 * <ul>
 *   <li>{@code julc_stdlib_list} — list all stdlib libraries with method counts.</li>
 *   <li>{@code julc_stdlib_method} — look up methods on a single library
 *       (optionally filtered by name).</li>
 *   <li>{@code julc_builtins_list} — list Plutus builtins exposed via
 *       {@code Builtins}.</li>
 * </ul>
 *
 * <p>For richer documentation (Javadoc), agents should fetch
 * <a href="https://julc.dev/ai/catalog.json">/ai/catalog.json</a>.
 */
public final class StdlibTools {

    private StdlibTools() {}

    public static McpServerFeatures.SyncToolSpecification listSpec(McpJsonMapper jsonMapper) {
        var schema = """
                { "type": "object", "properties": {}, "additionalProperties": false }
                """;
        var tool = McpSchema.Tool.builder()
                .name("julc_stdlib_list")
                .title("List JuLC stdlib libraries")
                .description("List all on-chain stdlib libraries (ContextsLib, ListsLib, ValuesLib, " +
                        "MapLib, OutputLib, MathLib, IntervalLib, CryptoLib, ByteStringLib, " +
                        "BitwiseLib, AddressLib, BlsLib, NativeValueLib) with method counts. " +
                        "Use julc_stdlib_method for specific signatures, or read the bulk " +
                        "julc://stdlib.json resource. For Javadoc-bearing rich docs, fetch " +
                        "https://julc.dev/ai/catalog.json.")
                .inputSchema(jsonMapper, schema)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, req) -> {
                    var libs = StdlibCatalog.listLibraries();
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("count", libs.size());
                    body.put("libraries", libs);
                    return CompileTool.buildResultPublic(body, jsonMapper);
                })
                .build();
    }

    public static McpServerFeatures.SyncToolSpecification methodSpec(McpJsonMapper jsonMapper) {
        var schema = """
                {
                  "type": "object",
                  "properties": {
                    "library": { "type": "string", "description": "Library class name (e.g. \\"ListsLib\\")." },
                    "method":  { "type": "string", "description": "Optional method name. Omit to list all methods on the library." }
                  },
                  "required": ["library"],
                  "additionalProperties": false
                }
                """;
        var tool = McpSchema.Tool.builder()
                .name("julc_stdlib_method")
                .title("Look up JuLC stdlib method signatures")
                .description("Return method signatures for a stdlib library. With `method` omitted, " +
                        "returns all methods on the library (use this to confirm a method exists " +
                        "before calling it). With `method` set, returns only that method's " +
                        "signature. Prefer this over reading the full julc://stdlib.json resource " +
                        "for single-method lookups. For richer docs (Javadoc), fetch " +
                        "https://julc.dev/ai/catalog.json.")
                .inputSchema(jsonMapper, schema)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, req) -> {
                    var args = req.arguments() == null ? Map.<String, Object>of() : req.arguments();
                    if (!(args.get("library") instanceof String lib) || lib.isBlank()) {
                        return McpSchema.CallToolResult.builder()
                                .addTextContent("Missing required 'library' argument.")
                                .isError(true).build();
                    }
                    String methodFilter = args.get("method") instanceof String s ? s : null;
                    var info = StdlibCatalog.describeLibrary(lib, methodFilter);
                    Map<String, Object> body = new LinkedHashMap<>();
                    if (info == null) {
                        body.put("found", false);
                        body.put("library", lib);
                        body.put("message", "Unknown stdlib library: " + lib +
                                ". Use julc_stdlib_list to see available libraries.");
                    } else {
                        body.put("found", true);
                        body.putAll(info);
                    }
                    return CompileTool.buildResultPublic(body, jsonMapper);
                })
                .build();
    }

    public static McpServerFeatures.SyncToolSpecification builtinsSpec(McpJsonMapper jsonMapper) {
        var schema = """
                { "type": "object", "properties": {}, "additionalProperties": false }
                """;
        var tool = McpSchema.Tool.builder()
                .name("julc_builtins_list")
                .title("List Plutus builtins exposed via Builtins")
                .description("List the Plutus builtins available via " +
                        "com.bloxbean.cardano.julc.stdlib.Builtins (equalsByteString, equalsData, " +
                        "headList, unIData, sha2_256, BLS12-381 ops, etc.). Prefer high-level type-class " +
                        "methods over raw Builtins where one exists.")
                .inputSchema(jsonMapper, schema)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, req) -> {
                    var methods = StdlibCatalog.listBuiltins();
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("count", methods.size());
                    body.put("methods", methods);
                    return CompileTool.buildResultPublic(body, jsonMapper);
                })
                .build();
    }
}
