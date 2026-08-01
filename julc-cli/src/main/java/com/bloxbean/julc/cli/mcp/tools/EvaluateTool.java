package com.bloxbean.julc.cli.mcp.tools;

import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.compiler.CompilerException;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.ExBudget;
import com.bloxbean.cardano.julc.vm.JulcVm;
import com.bloxbean.cardano.julc.vm.TermExtractor;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.MethodDeclaration;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool: compile and evaluate a single static method, returning the
 * extracted result + CPU/memory budget.
 *
 * <h2>Input</h2>
 * <pre>{@code
 * {
 *   "source": "string  // required",
 *   "method": "string  // required — name of the static method to invoke",
 *   "args":   [Arg, ...]  // optional — arguments to apply
 * }
 * }</pre>
 *
 * <p>{@code Arg} is a recursive PlutusData JSON shape; one of:
 * <pre>{@code
 *   { "int":   <number-or-string> }                    // PlutusData integer
 *   { "bytes": "0x...hex..." }                         // PlutusData bytes
 *   { "string": "..." }                                // utf-8 bytes
 *   { "bool":  true | false }                          // PlutusData constr 0/1
 *   { "unit":  true }                                  // PlutusData UNIT
 *   { "constr": { "tag": <int>, "fields": [Arg, ...] }} // arbitrary ConstrData
 *   { "list":  [Arg, ...] }                            // PlutusData list
 *   { "map":   [{ "key": Arg, "value": Arg }, ...] }   // PlutusData map
 * }</pre>
 *
 * <h2>Safety limits</h2>
 * <ul>
 *   <li>{@code McpLimits.MAX_SOURCE_BYTES} = 200 KB — guards against runaway parser input.</li>
 *   <li>{@code MAX_ARG_DEPTH} = 16 — caps recursive PlutusData arg trees.</li>
 *   <li>{@code DEFAULT_BUDGET} = 10× Plutus mainnet limit — caps unbounded VM work.</li>
 * </ul>
 *
 * <h2>Output</h2>
 * <pre>{@code
 * {
 *   "ok": boolean,
 *   "result": <auto-extracted: number | hex-string | boolean | string | recursive PlutusData object>,
 *   "resultType": "integer|bytes|boolean|string|data|none",
 *   "cpu": number,
 *   "memory": number,
 *   "traces": [string, ...],
 *   "error":   "string  // only when ok=false"
 * }
 * }</pre>
 */
public final class EvaluateTool {

    /** Maximum recursion depth for PlutusData arg trees. */
    static final int MAX_ARG_DEPTH = 16;
    /**
     * Default budget cap for VM evaluation: 10× Plutus mainnet block limit.
     * This is generous for test scenarios but prevents an LLM-driven infinite
     * loop from consuming an entire core. Codex P1.6.
     */
    static final ExBudget DEFAULT_BUDGET = new ExBudget(100_000_000_000L, 140_000_000L);

    private EvaluateTool() {}

    public static McpServerFeatures.SyncToolSpecification spec(McpJsonMapper jsonMapper) {
        var schema = """
                {
                  "type": "object",
                  "properties": {
                    "source": { "type": "string", "description": "JuLC Java source." },
                    "method": { "type": "string", "description": "Name of the static method to compile and evaluate." },
                    "args": {
                      "type": "array",
                      "description": "Arguments to apply. Each is a PlutusData JSON shape: {int:n}, {bytes:'0x..'}, {string:'..'}, {bool:b}, {unit:true}, {constr:{tag:int,fields:[..]}}, {list:[..]}, or {map:[{key,value}]}.",
                      "items": { "type": "object" }
                    }
                  },
                  "required": ["source", "method"],
                  "additionalProperties": false
                }
                """;
        var tool = McpSchema.Tool.builder()
                .name("julc_evaluate")
                .title("Evaluate a JuLC method")
                .description("Compile and evaluate a single static method on the JuLC VM. " +
                        "Returns the extracted result + CPU/memory budget. Args take a " +
                        "recursive PlutusData JSON shape: {int}, {bytes}, {string}, {bool}, " +
                        "{unit}, {constr:{tag,fields}}, {list:[...]}, {map:[{key,value}]}.")
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
            return errorResult("Missing required 'source' argument.");
        }
        String sourceLimitError = McpLimits.validateSource("source", src);
        if (sourceLimitError != null) return errorResult(sourceLimitError);
        if (!(args.get("method") instanceof String method) || method.isBlank()) {
            return errorResult("Missing required 'method' argument.");
        }

        // Validate args shape *before* compiling so a bad-args request fails
        // fast with a clear message and doesn't waste a compile cycle.
        Object argsObj = args.get("args");
        if (argsObj != null && !(argsObj instanceof List<?>)) {
            return errorResult("'args' must be an array; got: " + argsObj.getClass().getSimpleName());
        }
        List<PlutusData> pdArgs;
        try {
            pdArgs = parseArgs((List<Object>) argsObj);
        } catch (IllegalArgumentException e) {
            return errorResult("Invalid args: " + e.getMessage());
        }

        // Arity check: parse the source, find the named method, count its
        // parameters. Mismatch → fast structured error. Codex P1.4.
        int expectedArity = expectedMethodArity(src, method);
        if (expectedArity >= 0 && pdArgs.size() != expectedArity) {
            return errorResult("Arity mismatch for method '" + method + "': expected " +
                    expectedArity + " arg(s), got " + pdArgs.size() +
                    ". Pass each parameter explicitly via the `args` array.");
        }

        // Compile (with diagnostic-fallback for bare CompilerException).
        Program program;
        try {
            var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry());
            CompileResult cr = compiler.compileMethod(src, method);
            if (cr.hasErrors() || cr.program() == null) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("ok", false);
                body.put("error", "Compilation failed before evaluation.");
                body.put("diagnostics", CompileTool.renderDiagnostics(cr.diagnostics()));
                return buildResult(body, jsonMapper);
            }
            program = cr.program();
        } catch (CompilerException ce) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", false);
            body.put("error", "Compilation failed before evaluation.");
            var rendered = CompileTool.renderDiagnostics(ce.diagnostics());
            if (rendered.isEmpty() && ce.getMessage() != null) {
                rendered = List.of(CompileTool.synthesizeDiagnostic(ce.getMessage()));
            }
            body.put("diagnostics", rendered);
            return buildResult(body, jsonMapper);
        } catch (Exception e) {
            return errorResult("Compile failure: " + e.getMessage());
        }

        // Evaluate with a generous-but-finite budget cap. Per Codex P1.6:
        // unbounded eval in an MCP subprocess controlled by an LLM client is
        // a real availability risk.
        var vm = JulcVm.create();
        EvalResult result;
        try {
            result = pdArgs.isEmpty()
                    ? vm.evaluate(program, DEFAULT_BUDGET)
                    : vm.evaluateWithArgs(program, pdArgs, DEFAULT_BUDGET);
        } catch (Exception e) {
            return errorResult("VM error: " + e.getMessage());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        if (result instanceof EvalResult.Success s) {
            body.put("ok", true);
            body.put("cpu", s.consumed().cpuSteps());
            body.put("memory", s.consumed().memoryUnits());
            body.put("traces", s.traces());
            Object extracted;
            try {
                extracted = TermExtractor.extract(s.resultTerm());
            } catch (Exception e) {
                extracted = s.resultTerm().toString();
            }
            renderResult(extracted, body);
        } else if (result instanceof EvalResult.Failure f) {
            body.put("ok", false);
            body.put("error", f.error());
            body.put("cpu", f.consumed().cpuSteps());
            body.put("memory", f.consumed().memoryUnits());
            body.put("traces", f.traces());
        } else {
            body.put("ok", false);
            body.put("error", "unknown EvalResult variant: " + result.getClass());
        }
        return buildResult(body, jsonMapper);
    }

    /**
     * Find the named method in the source, return its parameter count.
     * Returns -1 if the source can't be parsed or the method isn't found —
     * in which case we skip the arity check rather than failing the call
     * (the compiler will catch the real error).
     */
    static int expectedMethodArity(String source, String methodName) {
        try {
            var parser = new JavaParser(new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
            var parsed = parser.parse(source);
            if (parsed.getResult().isEmpty()) return -1;
            return parsed.getResult().get().findAll(MethodDeclaration.class).stream()
                    .filter(m -> methodName.equals(m.getNameAsString()))
                    .mapToInt(m -> m.getParameters().size())
                    .findFirst()
                    .orElse(-1);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Parse the JSON-shape args into PlutusData values. */
    @SuppressWarnings("unchecked")
    static List<PlutusData> parseArgs(List<Object> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        var out = new ArrayList<PlutusData>(raw.size());
        for (var item : raw) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException(
                        "Each arg must be an object like {int:42} or {bytes:'0x..'}; got: " + item);
            }
            out.add(parseSingleArg((Map<String, Object>) map, 0));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static PlutusData parseSingleArg(Map<String, Object> m, int depth) {
        if (depth > MAX_ARG_DEPTH) {
            throw new IllegalArgumentException("Arg recursion depth exceeded MAX_ARG_DEPTH=" + MAX_ARG_DEPTH);
        }
        if (m.containsKey("int")) {
            Object v = m.get("int");
            return PlutusData.integer(toBigInteger(v));
        }
        if (m.containsKey("bytes")) {
            Object v = m.get("bytes");
            if (!(v instanceof String s)) {
                throw new IllegalArgumentException("'bytes' must be a hex string: " + v);
            }
            String hex = s.startsWith("0x") || s.startsWith("0X") ? s.substring(2) : s;
            return PlutusData.bytes(HexFormat.of().parseHex(hex));
        }
        if (m.containsKey("string")) {
            Object v = m.get("string");
            if (!(v instanceof String s)) {
                throw new IllegalArgumentException("'string' must be a string: " + v);
            }
            return PlutusData.bytes(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        if (m.containsKey("bool")) {
            Object v = m.get("bool");
            if (!(v instanceof Boolean b)) {
                throw new IllegalArgumentException("'bool' must be true/false: " + v);
            }
            return PlutusData.constr(b ? 1 : 0);
        }
        if (m.containsKey("unit")) {
            return PlutusData.UNIT;
        }
        if (m.containsKey("constr")) {
            Object v = m.get("constr");
            if (!(v instanceof Map<?, ?> cm)) {
                throw new IllegalArgumentException("'constr' must be an object {tag:int, fields:[...]}: " + v);
            }
            Map<String, Object> cmap = (Map<String, Object>) cm;
            Object tagObj = cmap.get("tag");
            if (!(tagObj instanceof Number)) {
                throw new IllegalArgumentException("'constr.tag' must be a number: " + tagObj);
            }
            // Use the same hardened path as 'int' values, then verify it fits int.
            BigInteger tagBig = toBigInteger(tagObj);
            if (tagBig.bitLength() > 31) {
                throw new IllegalArgumentException(
                        "'constr.tag' must fit in a signed 32-bit int; got: " + tagBig);
            }
            int tag = tagBig.intValueExact();
            Object fieldsObj = cmap.get("fields");
            if (fieldsObj == null) {
                return PlutusData.constr(tag);
            }
            if (!(fieldsObj instanceof List<?> fieldsList)) {
                throw new IllegalArgumentException("'constr.fields' must be an array: " + fieldsObj);
            }
            var fields = new PlutusData[fieldsList.size()];
            for (int i = 0; i < fieldsList.size(); i++) {
                Object item = fieldsList.get(i);
                if (!(item instanceof Map<?, ?> im)) {
                    throw new IllegalArgumentException("'constr.fields[" + i + "]' must be an object: " + item);
                }
                fields[i] = parseSingleArg((Map<String, Object>) im, depth + 1);
            }
            return PlutusData.constr(tag, fields);
        }
        if (m.containsKey("list")) {
            Object v = m.get("list");
            if (!(v instanceof List<?> items)) {
                throw new IllegalArgumentException("'list' must be an array: " + v);
            }
            var pds = new PlutusData[items.size()];
            for (int i = 0; i < items.size(); i++) {
                Object item = items.get(i);
                if (!(item instanceof Map<?, ?> im)) {
                    throw new IllegalArgumentException("'list[" + i + "]' must be a PlutusData object: " + item);
                }
                pds[i] = parseSingleArg((Map<String, Object>) im, depth + 1);
            }
            return PlutusData.list(pds);
        }
        if (m.containsKey("map")) {
            Object v = m.get("map");
            if (!(v instanceof List<?> entries)) {
                throw new IllegalArgumentException("'map' must be an array of {key,value} objects: " + v);
            }
            var pairs = new PlutusData.Pair[entries.size()];
            for (int i = 0; i < entries.size(); i++) {
                Object item = entries.get(i);
                if (!(item instanceof Map<?, ?> em)) {
                    throw new IllegalArgumentException("'map[" + i + "]' must be {key,value}: " + item);
                }
                Map<String, Object> entry = (Map<String, Object>) em;
                Object key = entry.get("key");
                Object val = entry.get("value");
                if (!(key instanceof Map<?, ?> km) || !(val instanceof Map<?, ?> vm)) {
                    throw new IllegalArgumentException("'map[" + i + "]' must have key:Arg, value:Arg objects: " + item);
                }
                pairs[i] = new PlutusData.Pair(
                        parseSingleArg((Map<String, Object>) km, depth + 1),
                        parseSingleArg((Map<String, Object>) vm, depth + 1));
            }
            return PlutusData.map(pairs);
        }
        throw new IllegalArgumentException(
                "Unknown arg shape (expected one of int/bytes/string/bool/unit/constr/list/map): " + m);
    }

    private static BigInteger toBigInteger(Object v) {
        return switch (v) {
            case BigInteger bi -> bi;
            case java.math.BigDecimal bd -> {
                // Some JSON layers parse large or precise numbers as BigDecimal.
                // Accept only integral values; preserve full precision.
                try {
                    yield bd.toBigIntegerExact();
                } catch (ArithmeticException ex) {
                    throw new IllegalArgumentException(
                            "'int' must be integral; got fractional: " + bd);
                }
            }
            case Float f -> {
                if (!Float.isFinite(f) || f != Math.floor(f)) {
                    throw new IllegalArgumentException(
                            "'int' must be an integral finite value; got: " + f);
                }
                long longVal = f.longValue();
                if ((float) longVal != f) {
                    throw new IllegalArgumentException(
                            "'int' value " + f + " exceeds long precision; " +
                                    "pass as string to use arbitrary-precision BigInteger.");
                }
                yield BigInteger.valueOf(longVal);
            }
            case Double d -> {
                if (!Double.isFinite(d) || d != Math.floor(d)) {
                    throw new IllegalArgumentException(
                            "'int' must be an integral finite value; got: " + d);
                }
                long longVal = d.longValue();
                if ((double) longVal != d) {
                    throw new IllegalArgumentException(
                            "'int' value " + d + " exceeds long precision; " +
                                    "pass as string to use arbitrary-precision BigInteger.");
                }
                yield BigInteger.valueOf(longVal);
            }
            case Number n -> BigInteger.valueOf(n.longValue());
            case String s -> new BigInteger(s);
            default -> throw new IllegalArgumentException("'int' must be number or string: " + v);
        };
    }

    /**
     * Render an extracted result into the response body. Adds {@code result}
     * and {@code resultType} keys.
     */
    private static void renderResult(Object extracted, Map<String, Object> body) {
        if (extracted == null) {
            body.put("result", null);
            body.put("resultType", "none");
            return;
        }
        switch (extracted) {
            case BigInteger bi -> {
                body.put("result", bi.toString());
                body.put("resultType", "integer");
            }
            case byte[] bytes -> {
                body.put("result", "0x" + HexFormat.of().formatHex(bytes));
                body.put("resultType", "bytes");
            }
            case Boolean b -> {
                body.put("result", b);
                body.put("resultType", "boolean");
            }
            case String s -> {
                body.put("result", s);
                body.put("resultType", "string");
            }
            case PlutusData pd -> {
                body.put("result", renderPlutusData(pd, 0));
                body.put("resultType", "data");
            }
            case java.util.Optional<?> opt -> {
                if (opt.isEmpty()) {
                    body.put("result", Map.of("none", true));
                } else if (opt.get() instanceof PlutusData pd) {
                    body.put("result", Map.of("some", renderPlutusData(pd, 0)));
                } else {
                    body.put("result", Map.of("some", opt.get().toString()));
                }
                body.put("resultType", "data");
            }
            case List<?> list -> {
                var rendered = new ArrayList<Object>(list.size());
                for (var item : list) {
                    if (item instanceof PlutusData pd) rendered.add(renderPlutusData(pd, 0));
                    else rendered.add(item == null ? null : item.toString());
                }
                body.put("result", rendered);
                body.put("resultType", "data");
            }
            default -> {
                body.put("result", extracted.toString());
                body.put("resultType", "data");
            }
        }
    }

    /**
     * Recursively render a PlutusData tree as JSON-friendly nested maps so
     * agents get a stable, parseable shape instead of a Java toString().
     * Codex P2.10.
     */
    static Object renderPlutusData(PlutusData pd, int depth) {
        if (pd == null) return null;
        if (depth > 64) return Map.of("truncated", "max-depth"); // sanity
        return switch (pd) {
            case PlutusData.IntData i -> Map.of("int", i.value().toString());
            case PlutusData.BytesData b -> Map.of("bytes", "0x" + HexFormat.of().formatHex(b.value()));
            case PlutusData.ConstrData c -> {
                var fields = new ArrayList<Object>(c.fields().size());
                for (var f : c.fields()) fields.add(renderPlutusData(f, depth + 1));
                yield Map.of("constr", Map.of(
                        "tag", c.constructorTag(), "fields", fields));
            }
            case PlutusData.ListData l -> {
                var items = new ArrayList<Object>(l.items().size());
                for (var item : l.items()) items.add(renderPlutusData(item, depth + 1));
                yield Map.of("list", items);
            }
            case PlutusData.MapData m -> {
                var rendered = new ArrayList<Object>(m.entries().size());
                for (var p : m.entries()) {
                    rendered.add(Map.of(
                            "key", renderPlutusData(p.key(), depth + 1),
                            "value", renderPlutusData(p.value(), depth + 1)));
                }
                yield Map.of("map", rendered);
            }
        };
    }

    private static McpSchema.CallToolResult errorResult(String message) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(message)
                .isError(true)
                .build();
    }

    private static McpSchema.CallToolResult buildResult(Map<String, Object> body, McpJsonMapper jsonMapper) {
        String json;
        try {
            json = jsonMapper.writeValueAsString(body);
        } catch (Exception e) {
            json = "{\"ok\":false,\"error\":\"serialize failed: " + e.getMessage().replace("\"", "\\\"") + "\"}";
        }
        return McpSchema.CallToolResult.builder()
                .addTextContent(json)
                .structuredContent(body)
                .isError(Boolean.FALSE.equals(body.get("ok")))
                .build();
    }
}
