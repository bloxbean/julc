package com.bloxbean.julc.cli.mcp.resources;

import com.bloxbean.julc.cli.mcp.catalog.LedgerCatalog;
import com.bloxbean.julc.cli.mcp.catalog.StdlibCatalog;
import com.bloxbean.julc.cli.mcp.prompts.JulcPrompts;
import com.bloxbean.julc.cli.mcp.tools.ExamplesTools;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read-only MCP resources exposed by {@code julc mcp}.
 *
 * <p>Resources are URI-addressable text payloads agents can fetch by URI
 * (separate from tool calls). They are useful for stable reference material
 * an agent may want to ingest once at session start.
 *
 * <p>URIs:
 * <ul>
 *   <li>{@code julc://diagnostics.json} — full JULC#### catalog (same as {@code /ai/diagnostics.json}).</li>
 *   <li>{@code julc://stdlib.json} — reflection-based stdlib catalog.</li>
 *   <li>{@code julc://ledger.json} — reflection-based ledger types catalog.</li>
 *   <li>{@code julc://builtins.json} — Plutus builtins via {@code Builtins}.</li>
 *   <li>{@code julc://server-info} — short text describing the server, tools, and resource list.</li>
 * </ul>
 *
 * <p>Resource templates:
 * <ul>
 *   <li>{@code julc://stdlib/{lib}}</li>
 *   <li>{@code julc://stdlib/{lib}/{method}}</li>
 *   <li>{@code julc://ledger/{type}}</li>
 *   <li>{@code julc://examples/{id}}</li>
 * </ul>
 *
 * <p>For dynamic, tool-callable equivalents see the {@code julc_*_*} tools.
 */
public final class JulcResources {

    private JulcResources() {}

    public static List<McpServerFeatures.SyncResourceSpecification> all(McpJsonMapper jsonMapper) {
        var out = new ArrayList<McpServerFeatures.SyncResourceSpecification>();
        out.add(limitationsResource());
        out.add(diagnosticsResource(jsonMapper));
        out.add(stdlibResource(jsonMapper));
        out.add(ledgerResource(jsonMapper));
        out.add(builtinsResource(jsonMapper));
        out.add(serverInfoResource(out));
        return out;
    }

    public static List<McpServerFeatures.SyncResourceTemplateSpecification> templates(McpJsonMapper jsonMapper) {
        return List.of(
                stdlibTemplate(jsonMapper),
                stdlibMethodTemplate(jsonMapper),
                ledgerTemplate(jsonMapper),
                exampleTemplate(jsonMapper)
        );
    }

    /**
     * The encoded "Known Compiler Limitations" knowledge — the most-emphasized
     * resource in ADR-020. Surfaces every gotcha an AI agent is most likely to
     * trip on. Phase D review (impl-validator P1).
     */
    private static McpServerFeatures.SyncResourceSpecification limitationsResource() {
        var resource = McpSchema.Resource.builder()
                .uri("julc://limitations.md")
                .name("limitations")
                .title("JuLC compiler limitations & gotchas")
                .description("Authoritative list of JuLC subset restrictions and AI failure modes — read this BEFORE generating code.")
                .mimeType("text/markdown")
                .build();
        String body = """
                # JuLC compiler limitations

                These are the patterns AI agents most commonly trip on when generating JuLC.
                Everything below is enforced by the compiler today; some are detectable
                pre-compile via `julc_lint`.

                ## Forbidden in on-chain code

                1. **No mutation after declaration.** `x = x + 1` outside a `while` accumulator
                   is rejected. Use a new `var y = x + 1`.
                2. **No uninitialized variables.** `var x;` then `x = 5;` does NOT compile.
                   Initialize at declaration: `var x = BigInteger.ZERO;`.
                3. **No `return` inside a `while` loop.** Use a boolean accumulator and
                   return after the loop. Compile-time error JULC0003.
                4. **No `return` inside a switch case** — use `yield`.
                5. **No lambdas stored in variables.** `Function<X, Y> f = x -> ...; f.apply(...)`
                   does not compile. Pass lambdas inline to HOFs.
                6. **No `throw new Exception(...)`.** Use `Builtins.error()` or `return false`.
                   JULC0016.
                7. **No reflection, I/O, threading, JNI, instance fields, instance methods**
                   on user types.
                8. **No `null`.** Use `Optional<T>` or sealed interfaces with explicit
                   none-variants. JULC0017.
                9. **No try/catch.** JULC0015. UPLC has no exception model.
                10. **No C-style for, do-while, floating point.** JULC0018, JULC0019, JULC0020.

                ## Type / API gotchas

                11. **`Optional.mkSome` / `Optional.mkNone` DO NOT EXIST.** Use
                    `Optional.of(x)` / `Optional.empty()`. AI agents hallucinate this
                    constantly — `julc_lint` catches it.
                12. **Switch case binding name silently shadows method parameter.**
                    `case Finite time -> ... time ...` shadows a `BigInteger time` param.
                    JULC0021. Always use distinct names.
                13. **Tuple2/Tuple3 are NOT switchable.** Use field access: `pair.first()`,
                    `pair.second()`.
                14. **`map()` returns `JulcList<PlutusData>`** — unwrap with `Builtins.unIData(...)`
                    before using as integer.
                15. **No double `.hash()`.** `pkh.hash().hash()` is wrong — `.hash()`
                    already returns the bytes.
                16. **`@Param PlutusData.BytesData/MapData/ListData/IntData` BANNED.**
                    JULC0013. Use `byte[]`, `BigInteger`, typed records, or redeemers.

                ## Anti-patterns (lint-detected, not compile errors)

                17. **Constructing raw `PlutusData.ConstrData/IntData/BytesData/MapData/ListData`**
                    in on-chain code is the project's #1 anti-pattern. Use records, sealed
                    interfaces, JulcList, JulcMap. The compiler accepts raw construction;
                    `julc_lint` flags it (JULC-LINT-RAW-PLUTUSDATA).
                18. **`(PubKeyHash)(Object) bytes` casts** — use `PubKeyHash.of(bytes)`.
                19. **Mutually recursive group of >2 functions** — JuLC supports
                    self-recursion and 2-way mutual recursion only (Bekic's theorem).
                    JULC0023. Refactor to a single function with an accumulator.

                ## When in doubt

                - `julc_lint` catches the LLM-hallucination patterns.
                - `julc_compile` returns stable JULC#### codes — look them up via
                  `julc_explain_diagnostic`.
                - Full curated guide: <https://julc.dev/ai/starter-pack/>
                """;
        return new McpServerFeatures.SyncResourceSpecification(resource,
                (exchange, req) -> new McpSchema.ReadResourceResult(List.of(
                        new McpSchema.TextResourceContents(req.uri(), "text/markdown", body)
                )));
    }

    // ---------- diagnostics ----------

    private static McpServerFeatures.SyncResourceSpecification diagnosticsResource(McpJsonMapper jsonMapper) {
        var resource = McpSchema.Resource.builder()
                .uri("julc://diagnostics.json")
                .name("diagnostics-catalog")
                .title("JuLC diagnostic codes catalog")
                .description("Full JULC#### code catalog with title, root cause, fix, and bad/good code examples.")
                .mimeType("application/json")
                .build();
        return new McpServerFeatures.SyncResourceSpecification(resource,
                (exchange, req) -> {
                    String body = readClasspath("/diagnostics.json");
                    return new McpSchema.ReadResourceResult(List.of(
                            new McpSchema.TextResourceContents(
                                    req.uri(), "application/json",
                                    body == null ? "{\"error\":\"diagnostics.json not on classpath\"}" : body)
                    ));
                });
    }

    // ---------- stdlib ----------

    private static McpServerFeatures.SyncResourceSpecification stdlibResource(McpJsonMapper jsonMapper) {
        var resource = McpSchema.Resource.builder()
                .uri("julc://stdlib.json")
                .name("stdlib-catalog")
                .title("JuLC stdlib API surface")
                .description("All on-chain stdlib libraries with their public-static method signatures.")
                .mimeType("application/json")
                .build();
        return new McpServerFeatures.SyncResourceSpecification(resource,
                (exchange, req) -> {
                    var libs = StdlibCatalog.listLibraries();
                    var detailed = new ArrayList<Object>();
                    for (var l : libs) {
                        var info = StdlibCatalog.describeLibrary((String) l.get("name"), null);
                        if (info != null) detailed.add(info);
                    }
                    String body = serialize(jsonMapper, Map.of("libraries", detailed));
                    return new McpSchema.ReadResourceResult(List.of(
                            new McpSchema.TextResourceContents(req.uri(), "application/json", body)
                    ));
                });
    }

    // ---------- ledger ----------

    private static McpServerFeatures.SyncResourceSpecification ledgerResource(McpJsonMapper jsonMapper) {
        var resource = McpSchema.Resource.builder()
                .uri("julc://ledger.json")
                .name("ledger-catalog")
                .title("JuLC ledger types")
                .description("Record fields and sealed-interface variants for every ledger type.")
                .mimeType("application/json")
                .build();
        return new McpServerFeatures.SyncResourceSpecification(resource,
                (exchange, req) -> {
                    var types = LedgerCatalog.listTypes();
                    var detailed = new ArrayList<Object>();
                    for (var t : types) {
                        var info = LedgerCatalog.describeType((String) t.get("name"));
                        if (info != null) detailed.add(info);
                    }
                    String body = serialize(jsonMapper, Map.of("types", detailed));
                    return new McpSchema.ReadResourceResult(List.of(
                            new McpSchema.TextResourceContents(req.uri(), "application/json", body)
                    ));
                });
    }

    // ---------- builtins ----------

    private static McpServerFeatures.SyncResourceSpecification builtinsResource(McpJsonMapper jsonMapper) {
        var resource = McpSchema.Resource.builder()
                .uri("julc://builtins.json")
                .name("builtins-catalog")
                .title("Plutus builtins")
                .description("Plutus builtins exposed via com.bloxbean.cardano.julc.stdlib.Builtins.")
                .mimeType("application/json")
                .build();
        return new McpServerFeatures.SyncResourceSpecification(resource,
                (exchange, req) -> {
                    var methods = StdlibCatalog.listBuiltins();
                    String body = serialize(jsonMapper, Map.of("methods", methods));
                    return new McpSchema.ReadResourceResult(List.of(
                            new McpSchema.TextResourceContents(req.uri(), "application/json", body)
                    ));
                });
    }

    // ---------- server-info ----------

    /**
     * Server-info resource is auto-generated from the actual registered
     * resource list (passed in) and a hardcoded tool name list. Earlier
     * versions hand-coded both lists and went stale (Codex P2#12 — it
     * forgot to mention julc_test). The tool list is centralized in
     * {@code TOOL_NAMES} below; if you add a new tool to JulcMcpServer,
     * add it here too. (A full source-of-truth refactor would auto-pull
     * tool names from the registered Tools list, but the registration
     * order is built before this resource ships and circular dependency
     * makes that messy — keeping a maintained constant is good enough.)
     */
    static final List<String> TOOL_NAMES = List.of(
            "julc_ping",
            "julc_compile", "julc_lint", "julc_evaluate",
            "julc_estimate_costs",
            "julc_explain_diagnostic",
            "julc_stdlib_list", "julc_stdlib_method", "julc_builtins_list",
            "julc_ledger_type",
            "julc_examples_search", "julc_example_get",
            "julc_test"
    );

    static final List<String> RESOURCE_TEMPLATE_URIS = List.of(
            "julc://stdlib/{lib}",
            "julc://stdlib/{lib}/{method}",
            "julc://ledger/{type}",
            "julc://examples/{id}"
    );

    private static McpServerFeatures.SyncResourceSpecification serverInfoResource(
            List<McpServerFeatures.SyncResourceSpecification> registered) {
        var resource = McpSchema.Resource.builder()
                .uri("julc://server-info.md")
                .name("server-info")
                .title("JuLC MCP server overview")
                .description("Short reference card listing all registered tools and resources, plus useful URLs.")
                .mimeType("text/markdown")
                .build();
        // Snapshot the URI list at registration time. We deliberately include
        // server-info itself in the listing so the resource catalog is honest
        // about what an agent can fetch.
        var resourceUris = new ArrayList<String>(registered.size() + 1);
        for (var r : registered) resourceUris.add(r.resource().uri());
        resourceUris.add("julc://server-info.md");

        var sb = new StringBuilder();
        sb.append("# julc-mcp\n\n## Tools\n");
        for (var t : TOOL_NAMES) sb.append("- ").append(t).append('\n');
        sb.append("\n## Resources\n");
        for (var u : resourceUris) sb.append("- ").append(u).append('\n');
        sb.append("\n## Resource Templates\n");
        for (var u : RESOURCE_TEMPLATE_URIS) sb.append("- ").append(u).append('\n');
        sb.append("\n## Prompts\n");
        for (var p : JulcPrompts.PROMPT_NAMES) sb.append("- ").append(p).append('\n');
        sb.append("""

                ## Web references
                - https://julc.dev/ai/starter-pack/  (canonical authoring rules)
                - https://julc.dev/ai/catalog.json    (rich docs incl. Javadoc)
                - https://julc.dev/ai/diagnostics.json
                - https://julc.dev/ai/examples.json

                ## Critical rules
                - Prefer high-level type classes over raw PlutusData
                - No return inside while loops
                - Initialize variables at declaration
                - Use Optional.of(x) / Optional.empty() (NOT mkSome/mkNone)
                """);
        String body = sb.toString();
        return new McpServerFeatures.SyncResourceSpecification(resource,
                (exchange, req) -> new McpSchema.ReadResourceResult(List.of(
                        new McpSchema.TextResourceContents(req.uri(), "text/markdown", body)
                )));
    }

    // ---------- resource templates ----------

    private static McpServerFeatures.SyncResourceTemplateSpecification stdlibTemplate(McpJsonMapper jsonMapper) {
        var template = McpSchema.ResourceTemplate.builder()
                .uriTemplate("julc://stdlib/{lib}")
                .name("stdlib-library")
                .title("JuLC stdlib library")
                .description("Details for one JuLC stdlib library, including public static method signatures.")
                .mimeType("application/json")
                .build();
        return new McpServerFeatures.SyncResourceTemplateSpecification(template,
                (exchange, req) -> {
                    String lib = tail(req.uri(), "julc://stdlib/");
                    Map<String, Object> body = StdlibCatalog.describeLibrary(lib, null);
                    if (body == null) {
                        body = Map.of(
                                "found", false,
                                "library", lib,
                                "message", "Unknown stdlib library. Use julc://stdlib.json or julc_stdlib_list."
                        );
                    }
                    return jsonResource(req.uri(), serialize(jsonMapper, body));
                });
    }

    private static McpServerFeatures.SyncResourceTemplateSpecification stdlibMethodTemplate(McpJsonMapper jsonMapper) {
        var template = McpSchema.ResourceTemplate.builder()
                .uriTemplate("julc://stdlib/{lib}/{method}")
                .name("stdlib-method")
                .title("JuLC stdlib method")
                .description("Details for a single JuLC stdlib method.")
                .mimeType("application/json")
                .build();
        return new McpServerFeatures.SyncResourceTemplateSpecification(template,
                (exchange, req) -> {
                    String[] parts = tail(req.uri(), "julc://stdlib/").split("/", 2);
                    String lib = parts.length > 0 ? parts[0] : "";
                    String method = parts.length > 1 ? parts[1] : "";
                    Map<String, Object> body = StdlibCatalog.describeLibrary(lib, method);
                    if (body == null || ((Number) body.getOrDefault("methodCount", 0)).intValue() == 0) {
                        body = Map.of(
                                "found", false,
                                "library", lib,
                                "method", method,
                                "message", "Unknown stdlib method. Use julc://stdlib/{lib} or julc_stdlib_method."
                        );
                    }
                    return jsonResource(req.uri(), serialize(jsonMapper, body));
                });
    }

    private static McpServerFeatures.SyncResourceTemplateSpecification ledgerTemplate(McpJsonMapper jsonMapper) {
        var template = McpSchema.ResourceTemplate.builder()
                .uriTemplate("julc://ledger/{type}")
                .name("ledger-type")
                .title("JuLC ledger type")
                .description("Fields, variants, and methods for one JuLC ledger type.")
                .mimeType("application/json")
                .build();
        return new McpServerFeatures.SyncResourceTemplateSpecification(template,
                (exchange, req) -> {
                    String type = tail(req.uri(), "julc://ledger/");
                    Map<String, Object> body = LedgerCatalog.describeType(type);
                    if (body == null) {
                        body = Map.of(
                                "found", false,
                                "type", type,
                                "message", "Unknown ledger type. Use julc://ledger.json or julc_ledger_type."
                        );
                    }
                    return jsonResource(req.uri(), serialize(jsonMapper, body));
                });
    }

    private static McpServerFeatures.SyncResourceTemplateSpecification exampleTemplate(McpJsonMapper jsonMapper) {
        var template = McpSchema.ResourceTemplate.builder()
                .uriTemplate("julc://examples/{id}")
                .name("julc-example")
                .title("JuLC example")
                .description("Metadata and source for one curated JuLC example.")
                .mimeType("application/json")
                .build();
        return new McpServerFeatures.SyncResourceTemplateSpecification(template,
                (exchange, req) -> {
                    String id = tail(req.uri(), "julc://examples/");
                    Map<String, Object> body = ExamplesTools.getExampleBody(id, true, jsonMapper);
                    return jsonResource(req.uri(), serialize(jsonMapper, body));
                });
    }

    // ---------- helpers ----------

    private static McpSchema.ReadResourceResult jsonResource(String uri, String body) {
        return new McpSchema.ReadResourceResult(List.of(
                new McpSchema.TextResourceContents(uri, "application/json", body)
        ));
    }

    private static String tail(String uri, String prefix) {
        if (uri == null || !uri.startsWith(prefix)) return "";
        return URLDecoder.decode(uri.substring(prefix.length()), StandardCharsets.UTF_8);
    }

    private static String readClasspath(String path) {
        try (InputStream in = JulcResources.class.getResourceAsStream(path)) {
            if (in == null) return null;
            return new String(in.readAllBytes());
        } catch (IOException e) {
            return null;
        }
    }

    private static String serialize(McpJsonMapper jsonMapper, Object body) {
        try {
            return jsonMapper.writeValueAsString(body);
        } catch (Exception e) {
            return "{\"error\":\"serialize failed: " + e.getMessage().replace("\"", "\\\"") + "\"}";
        }
    }
}
