package com.bloxbean.julc.cli.mcp.tools;

import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.compiler.CompilerException;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.compiler.LibrarySource;
import com.bloxbean.cardano.julc.compiler.LibrarySourceResolver;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.JulcVm;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP tool: discover {@code @Test}-annotated {@code public static boolean}
 * methods in a JuLC source string, compile + evaluate each, and return
 * a structured pass/fail summary plus per-test traces and budget usage.
 *
 * <p>Closes the loop for "is my code <em>correct</em>, not just compilable?"
 * (Phase D6.) Mirrors the on-disk {@code julc check} flow but operates on
 * an in-memory source string so AI agents can run tests without scaffolding
 * a project.
 *
 * <h2>Input</h2>
 * <pre>{@code
 * {
 *   "source": "string  // required — test class with @Test methods",
 *   "librarySources": ["..."]?,
 *   "method": "string?  // optional — run only this test method"
 * }
 * }</pre>
 *
 * <h2>Output</h2>
 * <pre>{@code
 * {
 *   "ok": boolean,         // true iff all selected tests passed
 *   "passed": number,
 *   "failed": number,
 *   "results": [
 *     { "method":..., "passed":..., "cpu":..., "memory":..., "traces":[...], "error":...? }
 *   ]
 * }
 * }</pre>
 */
public final class TestTool {

    /** Match `public static boolean foo(` — discovers the method name. */
    private static final Pattern STATIC_METHOD = Pattern.compile(
            "public\\s+static\\s+boolean\\s+(\\w+)\\s*\\(");

    private TestTool() {}

    public static McpServerFeatures.SyncToolSpecification spec(McpJsonMapper jsonMapper) {
        var schema = """
                {
                  "type": "object",
                  "properties": {
                    "source":         { "type": "string", "description": "Test class source. Discovers @Test-annotated public static boolean methods." },
                    "librarySources": { "type": "array", "items": {"type": "string"}, "description": "Optional @OnchainLibrary sources." },
                    "method":         { "type": "string", "description": "Optional: run only the named test method." }
                  },
                  "required": ["source"],
                  "additionalProperties": false
                }
                """;
        var tool = McpSchema.Tool.builder()
                .name("julc_test")
                .title("Run JuLC tests")
                .description("Discover @Test-annotated `public static boolean` methods in the " +
                        "given source, compile + evaluate each on the JuLC VM, and report " +
                        "pass/fail per test plus aggregate counts. Use to verify that a " +
                        "validator behaves as intended (e.g. signatory check actually rejects " +
                        "the wrong PKH), not just that it compiles.")
                .inputSchema(jsonMapper, schema)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, req) -> handle(req, jsonMapper))
                .build();
    }

    @SuppressWarnings("unchecked")
    static McpSchema.CallToolResult handle(McpSchema.CallToolRequest req, McpJsonMapper jsonMapper) {
        var args = req.arguments() == null ? Map.<String, Object>of() : req.arguments();
        if (!(args.get("source") instanceof String src) || src.isBlank()) {
            return error("Missing required 'source' argument.");
        }
        String sourceLimitError = McpLimits.validateSource("source", src);
        if (sourceLimitError != null) return error(sourceLimitError);
        // librarySources validation mirrors CompileTool.
        List<String> librarySources = List.of();
        Object libsObj = args.get("librarySources");
        if (libsObj != null) {
            if (!(libsObj instanceof List<?> raw)) {
                return error("'librarySources' must be an array of strings.");
            }
            var libs = new ArrayList<String>(raw.size());
            for (var item : raw) {
                if (!(item instanceof String s)) {
                    return error("Each librarySources entry must be a string.");
                }
                libs.add(s);
            }
            String libraryLimitError = McpLimits.validateLibrarySources(libs);
            if (libraryLimitError != null) return error(libraryLimitError);
            librarySources = libs;
        }
        String filter = args.get("method") instanceof String s ? s : null;

        // Discover methods inline.
        List<String> methodNames = discoverTestMethods(src);
        if (filter != null) {
            methodNames = methodNames.stream().filter(filter::equals).toList();
        }
        if (methodNames.isEmpty()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("passed", 0);
            body.put("failed", 0);
            body.put("results", List.of());
            body.put("warning", filter == null
                    ? "No @Test-annotated public static boolean methods found in the source."
                    : "Method '" + filter + "' not found among @Test methods.");
            return CompileTool.buildResultPublic(body, jsonMapper);
        }

        var results = new ArrayList<Map<String, Object>>(methodNames.size());
        int passed = 0, failed = 0;
        // Build the library pool for cross-source resolution. We pretend each
        // librarySource has a class name matching its declared class — same
        // shape TestRunner uses on disk.
        Map<String, LibrarySource> libraryPool = new LinkedHashMap<>();
        for (var ls : librarySources) {
            String name = extractFirstClassName(ls);
            if (name != null) LibrarySourceResolver.putLibrarySource(libraryPool, name, ls);
        }
        var resolvedLibs = LibrarySourceResolver.resolve(src, libraryPool);
        var vm = JulcVm.create();

        for (String method : methodNames) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("method", method);
            try {
                var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry());
                CompileResult cr = compiler.compileMethod(src, method, resolvedLibs);
                if (cr.hasErrors()) {
                    entry.put("passed", false);
                    entry.put("error", "compile error: " +
                            (cr.diagnostics().isEmpty() ? "(no diagnostic)" : cr.diagnostics().get(0).message()));
                    failed++;
                    results.add(entry);
                    continue;
                }
                // Apply the same budget cap as EvaluateTool — an agent-generated
                // infinite loop in a @Test method must NOT hang the MCP server.
                // Phase D review (Codex P1#2 + impl-validator P0).
                EvalResult ev = vm.evaluate(cr.program(), EvaluateTool.DEFAULT_BUDGET);
                if (ev instanceof EvalResult.Success s) {
                    boolean truth = isTruthy(s.resultTerm());
                    entry.put("passed", truth);
                    entry.put("cpu", s.consumed().cpuSteps());
                    entry.put("memory", s.consumed().memoryUnits());
                    if (!s.traces().isEmpty()) entry.put("traces", s.traces());
                    if (!truth) entry.put("error", "test returned false");
                    if (truth) passed++; else failed++;
                } else if (ev instanceof EvalResult.Failure f) {
                    entry.put("passed", false);
                    entry.put("cpu", f.consumed().cpuSteps());
                    entry.put("memory", f.consumed().memoryUnits());
                    if (!f.traces().isEmpty()) entry.put("traces", f.traces());
                    entry.put("error", f.error());
                    failed++;
                } else {
                    entry.put("passed", false);
                    entry.put("error", "unexpected EvalResult: " + ev.getClass().getSimpleName());
                    failed++;
                }
            } catch (CompilerException ce) {
                entry.put("passed", false);
                var diags = ce.diagnostics();
                entry.put("error", diags.isEmpty()
                        ? ("compile error: " + ce.getMessage())
                        : ("compile error: " + diags.get(0).message()));
                failed++;
            } catch (Exception e) {
                entry.put("passed", false);
                entry.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                failed++;
            }
            results.add(entry);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", failed == 0);
        body.put("passed", passed);
        body.put("failed", failed);
        body.put("results", results);
        return CompileTool.buildResultPublic(body, jsonMapper);
    }

    /**
     * Scan source for @Test annotation on the line(s) immediately preceding
     * a public static boolean declaration. Handles both conventions:
     * <pre>{@code
     *   @Test
     *   public static boolean foo() { ... }      // multi-line
     *
     *   @Test public static boolean foo() { ... }  // single-line
     * }</pre>
     * Mirrors {@link com.bloxbean.julc.cli.check.TestDiscovery#discover}'s
     * heuristic but on a raw string instead of a directory, and additionally
     * supports the single-line shape that AI-generated source frequently uses.
     */
    static List<String> discoverTestMethods(String source) {
        var out = new ArrayList<String>();
        var lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String stripped = lines[i].strip();
            if (!stripped.startsWith("@Test")) continue;
            // Same-line shape: `@Test public static boolean foo()`
            Matcher sameLine = STATIC_METHOD.matcher(lines[i]);
            if (sameLine.find()) {
                out.add(sameLine.group(1));
                continue;
            }
            // Multi-line shape: look ahead up to 4 lines.
            for (int j = i + 1; j < Math.min(i + 5, lines.length); j++) {
                Matcher m = STATIC_METHOD.matcher(lines[j]);
                if (m.find()) {
                    out.add(m.group(1));
                    break;
                }
            }
        }
        return out;
    }

    private static String extractFirstClassName(String source) {
        var m = Pattern.compile("(?:public\\s+)?class\\s+(\\w+)").matcher(source);
        return m.find() ? m.group(1) : null;
    }

    private static boolean isTruthy(Term term) {
        if (term instanceof Term.Const c && c.value() instanceof Constant.BoolConst b) {
            return b.value();
        }
        if (term instanceof Term.Constr constr) {
            return constr.tag() == 1;
        }
        return false;
    }

    private static McpSchema.CallToolResult error(String msg) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(msg)
                .isError(true)
                .build();
    }
}
