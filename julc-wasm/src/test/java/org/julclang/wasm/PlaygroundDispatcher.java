package org.julclang.wasm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LibrarySourceResolver;
import org.julclang.playground.scenario.ScenarioRegistry;
import org.julclang.playground.service.ExampleCatalog;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.tools.debug.SourceDebugService;
import org.julclang.tools.model.CheckRequest;
import org.julclang.tools.model.CompileRequest;
import org.julclang.tools.model.EvalExpressionRequest;
import org.julclang.tools.model.EvaluateRequest;
import org.julclang.tools.model.SourceDebugModels;
import org.julclang.tools.model.UplcModels;
import org.julclang.tools.repl.ExpressionEvaluator;
import org.julclang.tools.service.ServiceResult;
import org.julclang.tools.service.ToolsService;
import org.julclang.tools.uplc.UplcToolsService;
import org.julclang.vm.JulcVm;
import org.julclang.vm.java.JavaVmProvider;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Routes playground API requests to {@link ToolsService} without HTTP.
 * <p>
 * The routes, status codes and JSON bodies mirror the REST server so the frontend can use either engine through
 * the same client. The result is a JSON envelope {@code {"status": <http status>, "body": <response json>}}.
 * Single-threaded: timeouts are enforced by the host (the browser terminates the worker).
 */
public final class PlaygroundDispatcher {

    private static final String API = "/api/";

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolsService service;
    private final ExpressionEvaluator evaluator;
    private final ExampleCatalog examples;
    private final UplcToolsService uplc = new UplcToolsService();
    private final SourceDebugService sourceDebug;

    PlaygroundDispatcher(ToolsService service, ExpressionEvaluator evaluator, ExampleCatalog examples,
                         SourceDebugService sourceDebug) {
        this.service = service;
        this.evaluator = evaluator;
        this.examples = examples;
        this.sourceDebug = sourceDebug;
    }

    /** Creates a dispatcher that shares one compiler, stdlib source pool and Java VM across all operations. */
    public static PlaygroundDispatcher create() {
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry());
        var libraries = LibrarySourceResolver.scanClasspathSources(JulcCompiler.class.getClassLoader());
        var vm = JulcVm.withProvider(new JavaVmProvider());
        return new PlaygroundDispatcher(
                new ToolsService(compiler, libraries, () -> vm),
                new ExpressionEvaluator(compiler, vm, libraries),
                new ExampleCatalog(),
                new SourceDebugService(libraries));
    }

    /**
     * Handles one request.
     *
     * @param method HTTP method ({@code GET} or {@code POST})
     * @param target request path, optionally with a query string, e.g. {@code /api/examples?language=java}
     * @param body   JSON request body for POST requests
     * @return JSON envelope with the HTTP status and response body
     */
    public String dispatch(String method, String target, String body) {
        ServiceResult<?> result;
        try {
            result = route(method == null ? "" : method.toUpperCase(), target == null ? "" : target, body);
        } catch (JsonProcessingException e) {
            result = ServiceResult.status(400, Map.of("error", "Invalid request body: " + e.getOriginalMessage()));
        } catch (Throwable t) {
            result = ServiceResult.failed(500, Map.of("error", "Engine error: " + describe(t)), t);
        }
        return envelope(result);
    }

    private ServiceResult<?> route(String method, String target, String body) throws Exception {
        int query = target.indexOf('?');
        String path = query >= 0 ? target.substring(0, query) : target;
        Map<String, String> params = query >= 0 ? parseQuery(target.substring(query + 1)) : Map.of();

        if (method.equals("POST")) {
            return switch (path) {
                case "/api/check" -> ToolsService.check(mapper.readValue(body, CheckRequest.class));
                case "/api/compile" -> service.compile(mapper.readValue(body, CompileRequest.class));
                case "/api/evaluate" -> service.evaluate(mapper.readValue(body, EvaluateRequest.class));
                case "/api/eval" -> ToolsService.evalExpression(evaluator,
                        mapper.readValue(body, EvalExpressionRequest.class));
                case "/api/uplc/decode" -> uplc.decode(mapper.readValue(body, UplcModels.DecodeRequest.class));
                case "/api/uplc/decompile" -> uplc.decompile(mapper.readValue(body, UplcModels.DecompileRequest.class));
                case "/api/uplc/evaluate" -> uplc.evaluate(mapper.readValue(body, UplcModels.EvaluateRequest.class));
                case "/api/uplc/debug" -> uplc.debug(mapper.readValue(body, UplcModels.DebugRequest.class));
                case "/api/source-debug/open" -> sourceDebug.open(
                        mapper.readValue(body, SourceDebugModels.OpenRequest.class));
                case "/api/source-debug/act" -> sourceDebug.act(
                        mapper.readValue(body, SourceDebugModels.ActionRequest.class));
                case "/api/source-debug/locals" -> sourceDebug.locals(
                        mapper.readValue(body, SourceDebugModels.LocalsRequest.class));
                case "/api/source-debug/children" -> sourceDebug.children(
                        mapper.readValue(body, SourceDebugModels.ChildrenRequest.class));
                case "/api/source-debug/close" -> sourceDebug.close(
                        mapper.readValue(body, SourceDebugModels.CloseRequest.class));
                default -> notFound(method, path);
            };
        }
        if (method.equals("GET")) {
            if (path.equals("/api/examples")) {
                return examples.list(params.get("language"));
            }
            if (path.startsWith("/api/examples/") && path.length() > "/api/examples/".length()) {
                return examples.get(decode(path.substring("/api/examples/".length())));
            }
            if (path.startsWith("/api/scenarios/") && path.length() > "/api/scenarios/".length()) {
                return ScenarioRegistry.scenarios(decode(path.substring("/api/scenarios/".length())));
            }
            if (path.equals("/api/health")) {
                return ServiceResult.ok(Map.of("status", "ok", "engine", "wasm"));
            }
        }
        return notFound(method, path);
    }

    private String envelope(ServiceResult<?> result) {
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("status", result.status());
        envelope.put("body", result.body());
        try {
            return mapper.writeValueAsString(envelope);
        } catch (Exception e) {
            return "{\"status\":500,\"body\":{\"error\":\"Engine error: could not serialize response\"}}";
        }
    }

    private static ServiceResult<Map<String, String>> notFound(String method, String path) {
        String what = path.startsWith(API) ? "Endpoint " + method + " " + path : path;
        return ServiceResult.status(404, Map.of("error", what + " not found"));
    }

    private static Map<String, String> parseQuery(String query) {
        var params = new LinkedHashMap<String, String>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = decode(eq >= 0 ? pair.substring(0, eq) : pair);
            params.putIfAbsent(key, eq >= 0 ? decode(pair.substring(eq + 1)) : "");
        }
        return params;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String describe(Throwable t) {
        var sb = new StringBuilder(t.getClass().getSimpleName());
        if (t.getMessage() != null) sb.append(": ").append(t.getMessage());
        return sb.toString();
    }
}
