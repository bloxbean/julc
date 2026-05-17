package com.bloxbean.julc.cli.mcp.tools;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Examples discovery tools — wraps the {@code julc-examples} repo's
 * {@code ai/examples-index.json}.
 *
 * <ul>
 *   <li>{@code julc_examples_search} — query by tag, concept, difficulty, or text.</li>
 *   <li>{@code julc_example_get} — fetch a single example's full source.</li>
 * </ul>
 *
 * <p>The index is loaded from one of these locations, in order:
 * <ol>
 *   <li>{@code JULC_EXAMPLES_DIR} env var, if set.</li>
 *   <li>Sibling {@code ../julc-examples/ai/examples-index.json}.</li>
 *   <li>If none is present, the tool returns a graceful "not configured" message
 *       pointing at the hosted endpoint.</li>
 * </ol>
 */
public final class ExamplesTools {

    private ExamplesTools() {}

    /** Lazy: do the disk scan once, on first call. */
    private static final AtomicReference<ExamplesIndex> CACHED = new AtomicReference<>();

    public static McpServerFeatures.SyncToolSpecification searchSpec(McpJsonMapper jsonMapper) {
        var schema = """
                {
                  "type": "object",
                  "properties": {
                    "concept":    { "type": "string", "description": "Concept tag (e.g. \\"sealed-interface-redeemer\\", \\"interval-deadline\\"). See julc.dev/ai/examples.json for the full concepts dict." },
                    "difficulty": { "type": "string", "enum": ["beginner", "intermediate", "advanced"] },
                    "kind":       { "type": "string", "description": "Filter by kind (e.g. \\"spending-validator\\", \\"minting-policy\\", \\"onchain-library\\")." },
                    "canonical":  { "type": "boolean", "description": "If true, return only examples flagged canonical." },
                    "query":      { "type": "string", "description": "Free-text search across name/title/summary." }
                  },
                  "additionalProperties": false
                }
                """;
        var tool = McpSchema.Tool.builder()
                .name("julc_examples_search")
                .title("Search the JuLC examples corpus")
                .description("Query the curated julc-examples index by concept tag, difficulty, " +
                        "kind, canonical flag, or free-text. Use to find a starting point for a " +
                        "specific pattern (e.g. concept=auction). Returns metadata only; call " +
                        "julc_example_get to fetch a single example's source.")
                .inputSchema(jsonMapper, schema)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, req) -> handleSearch(req, jsonMapper))
                .build();
    }

    public static McpServerFeatures.SyncToolSpecification getSpec(McpJsonMapper jsonMapper) {
        var schema = """
                {
                  "type": "object",
                  "properties": {
                    "id":          { "type": "string", "description": "Example id (e.g. \\"vesting-validator\\")." },
                    "includeSource": { "type": "boolean", "description": "Include the full source. Default true." }
                  },
                  "required": ["id"],
                  "additionalProperties": false
                }
                """;
        var tool = McpSchema.Tool.builder()
                .name("julc_example_get")
                .title("Fetch a JuLC example")
                .description("Fetch a single example's metadata + full source. Use after " +
                        "julc_examples_search has narrowed down the candidate.")
                .inputSchema(jsonMapper, schema)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, req) -> handleGet(req, jsonMapper))
                .build();
    }

    @SuppressWarnings("unchecked")
    static McpSchema.CallToolResult handleSearch(McpSchema.CallToolRequest req,
                                                  McpJsonMapper jsonMapper) {
        var args = req.arguments() == null ? Map.<String, Object>of() : req.arguments();
        ExamplesIndex idx = ensureLoaded(jsonMapper);
        if (idx == null) {
            return missingIndexResult(jsonMapper);
        }

        String concept = stringArg(args, "concept");
        String difficulty = stringArg(args, "difficulty");
        String kind = stringArg(args, "kind");
        String query = stringArg(args, "query");
        Boolean canonical = args.get("canonical") instanceof Boolean b ? b : null;
        String q = query == null ? null : query.toLowerCase(Locale.ROOT);

        var matched = new ArrayList<Map<String, Object>>();
        for (var ex : idx.examples) {
            if (concept != null) {
                var concepts = (List<String>) ex.getOrDefault("concepts", List.of());
                if (!concepts.contains(concept)) continue;
            }
            if (difficulty != null && !difficulty.equals(ex.get("difficulty"))) continue;
            if (kind != null && !kind.equals(ex.get("kind"))) continue;
            if (canonical != null && !canonical.equals(ex.get("canonical"))) continue;
            if (q != null) {
                String hay = ((ex.getOrDefault("name", "") + " " +
                        ex.getOrDefault("title", "") + " " +
                        ex.getOrDefault("summary", ""))).toString().toLowerCase(Locale.ROOT);
                if (!hay.contains(q)) continue;
            }
            // Strip the heavy 'source' field from search results — the
            // get tool fetches it separately. Keep id/name/title/summary/tags.
            var slim = new LinkedHashMap<String, Object>();
            for (var k : List.of("id", "name", "title", "summary", "kind", "difficulty",
                    "canonical", "concepts", "cipRelevance")) {
                if (ex.containsKey(k)) slim.put(k, ex.get(k));
            }
            matched.add(slim);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", matched.size());
        body.put("totalIndexed", idx.examples.size());
        body.put("examples", matched);
        return CompileTool.buildResultPublic(body, jsonMapper);
    }

    @SuppressWarnings("unchecked")
    static McpSchema.CallToolResult handleGet(McpSchema.CallToolRequest req,
                                               McpJsonMapper jsonMapper) {
        var args = req.arguments() == null ? Map.<String, Object>of() : req.arguments();
        if (!(args.get("id") instanceof String id) || id.isBlank()) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Missing required 'id' argument.")
                    .isError(true).build();
        }
        boolean includeSource = !Boolean.FALSE.equals(args.get("includeSource"));
        return CompileTool.buildResultPublic(getExampleBody(id, includeSource, jsonMapper), jsonMapper);
    }

    /**
     * Shared implementation for {@code julc_example_get} and the MCP
     * {@code julc://examples/{id}} resource template.
     */
    public static Map<String, Object> getExampleBody(String id, boolean includeSource,
                                                     McpJsonMapper jsonMapper) {
        if (id == null || id.isBlank()) {
            return Map.of(
                    "found", false,
                    "message", "Missing required example id."
            );
        }
        ExamplesIndex idx = ensureLoaded(jsonMapper);
        if (idx == null) return missingIndexBody();

        Map<String, Object> match = null;
        for (var ex : idx.examples) {
            if (id.equals(ex.get("id"))) {
                match = ex;
                break;
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (match == null) {
            body.put("found", false);
            body.put("id", id);
            body.put("message", "No example with id '" + id +
                    "'. Use julc_examples_search to find available ids.");
        } else {
            body.put("found", true);
            // Echo metadata, optionally inline the source from disk.
            for (var entry : match.entrySet()) {
                if ("source".equals(entry.getKey())) continue;
                body.put(entry.getKey(), entry.getValue());
            }
            String relativeSource = (String) match.get("source");
            body.put("sourcePath", relativeSource);
            if (includeSource && relativeSource != null && idx.repoRoot != null) {
                // Phase D review (Codex P1#1): the index is external data
                // and a malicious entry could set source: "../../.ssh/id_rsa".
                // Resolve+normalize and require the result to live under the
                // repo root before reading.
                Path raw = Path.of(relativeSource);
                if (raw.isAbsolute()) {
                    body.put("sourceTextError",
                            "Refusing absolute source path: " + relativeSource);
                } else {
                    Path resolved = idx.repoRoot.resolve(raw).normalize();
                    if (!resolved.startsWith(idx.repoRoot)) {
                        body.put("sourceTextError",
                                "Refusing source path that escapes repo root: " + relativeSource);
                    } else {
                        try {
                            body.put("sourceText", Files.readString(resolved));
                        } catch (IOException e) {
                            body.put("sourceTextError", "Could not read source: " + e.getMessage());
                        }
                    }
                }
            }
        }
        return body;
    }

    // ---------- helpers ----------

    private static String stringArg(Map<String, Object> args, String key) {
        return args.get(key) instanceof String s && !s.isBlank() ? s : null;
    }

    private static McpSchema.CallToolResult missingIndexResult(McpJsonMapper jsonMapper) {
        return CompileTool.buildResultPublic(missingIndexBody(), jsonMapper);
    }

    private static Map<String, Object> missingIndexBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("found", false);
        body.put("message",
                "examples-index.json is not bundled with julc-cli. Set JULC_EXAMPLES_DIR " +
                        "to a local https://github.com/bloxbean/julc-examples checkout, " +
                        "or fetch the hosted index from https://julc.dev/ai/examples.json.");
        return body;
    }

    @SuppressWarnings("unchecked")
    static ExamplesIndex ensureLoaded(McpJsonMapper jsonMapper) {
        ExamplesIndex existing = CACHED.get();
        if (existing != null) return existing == ExamplesIndex.MISSING ? null : existing;

        // Probe locations in order.
        String envDir = System.getenv("JULC_EXAMPLES_DIR");
        Path[] candidates = envDir == null
                ? new Path[]{
                    Path.of("../julc-examples"),
                    Path.of("../../julc-examples")
                }
                : new Path[]{ Path.of(envDir) };
        for (Path repoRoot : candidates) {
            Path indexFile = repoRoot.resolve("ai/examples-index.json");
            if (!Files.isRegularFile(indexFile)) continue;
            try {
                String body = Files.readString(indexFile);
                Map<String, Object> root = jsonMapper.readValue(body, Map.class);
                List<Map<String, Object>> examples = (List<Map<String, Object>>) root.get("examples");
                if (examples == null) continue;
                var idx = new ExamplesIndex(repoRoot.toAbsolutePath().normalize(),
                        examples);
                CACHED.set(idx);
                return idx;
            } catch (Exception e) {
                // Try next candidate.
            }
        }

        CACHED.set(ExamplesIndex.MISSING);
        return null;
    }

    /** Internal cache shape. */
    static final class ExamplesIndex {
        static final ExamplesIndex MISSING = new ExamplesIndex(null, List.of());
        final Path repoRoot;
        final List<Map<String, Object>> examples;
        ExamplesIndex(Path repoRoot, List<Map<String, Object>> examples) {
            this.repoRoot = repoRoot;
            this.examples = examples;
        }
    }
}
