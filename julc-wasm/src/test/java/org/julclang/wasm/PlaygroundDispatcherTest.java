package org.julclang.wasm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.testtools.HttpClient;
import io.javalin.testtools.JavalinTest;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LibrarySourceResolver;
import org.julclang.playground.api.CheckController;
import org.julclang.playground.api.CompileController;
import org.julclang.playground.api.EvaluateController;
import org.julclang.playground.api.ExamplesController;
import org.julclang.playground.api.ExpressionEvalController;
import org.julclang.playground.api.ScenariosController;
import org.julclang.playground.api.SourceDebugController;
import org.julclang.playground.api.UplcController;
import org.julclang.playground.sandbox.CompilationSandbox;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.tools.model.MockTransaction;
import org.julclang.tools.model.MockTransaction.DataInput;
import org.julclang.tools.model.SourceDebugModels.ActionRequest;
import org.julclang.tools.model.SourceDebugModels.CloseRequest;
import org.julclang.tools.model.SourceDebugModels.OpenRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The WebAssembly engine must expose the same JSON contract as the REST server: every parity fixture is sent to
 * the Javalin controllers and to the dispatcher, and status codes and bodies must be identical.
 */
class PlaygroundDispatcherTest {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final PlaygroundDispatcher DISPATCHER = PlaygroundDispatcher.create();
    static final CompilationSandbox SANDBOX = new CompilationSandbox(2, 60);

    @AfterAll
    static void tearDown() {
        SANDBOX.shutdown();
    }

    static Javalin serverApp() {
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry());
        var libraries = LibrarySourceResolver.scanClasspathSources(JulcCompiler.class.getClassLoader());
        var examples = new ExamplesController();
        var uplc = new UplcController(SANDBOX);
        return Javalin.create()
                .post("/api/uplc/decode", uplc::decode)
                .post("/api/uplc/decompile", uplc::decompile)
                .post("/api/uplc/evaluate", uplc::evaluate)
                .post("/api/uplc/debug", uplc::debug)
                .post("/api/check", new CheckController()::handle)
                .post("/api/compile", new CompileController(compiler, SANDBOX, libraries)::handle)
                .post("/api/evaluate", new EvaluateController(compiler, SANDBOX, libraries)::handle)
                .post("/api/eval", new ExpressionEvalController(SANDBOX)::handle)
                .get("/api/examples", examples::list)
                .get("/api/examples/{name}", examples::get)
                .get("/api/scenarios/{purpose}", new ScenariosController()::handle);
    }

    @Test
    void dispatcherMatchesRestServerForAllParityFixtures() throws Exception {
        JsonNode fixtures = loadFixtures();
        JavalinTest.test(serverApp(), (server, client) -> {
            for (JsonNode fixture : fixtures) {
                String name = fixture.get("name").asText();
                String method = fixture.get("method").asText();
                String path = fixture.get("path").asText();
                String body = fixture.has("body") ? MAPPER.writeValueAsString(fixture.get("body")) : null;

                var builder = new Request.Builder().url(client.getOrigin() + path);
                if (method.equals("POST")) {
                    builder.post(RequestBody.create(body, MediaType.get("application/json")));
                }
                try (var response = client.getOkHttp().newCall(builder.build()).execute()) {
                    JsonNode expectedBody = MAPPER.readTree(response.body().string());
                    JsonNode actual = MAPPER.readTree(DISPATCHER.dispatch(method, path, body));

                    assertEquals(response.code(), actual.get("status").asInt(), name + ": status");
                    assertEquals(expectedBody, actual.get("body"), name + ": body");
                }
            }
        });
    }

    @Test
    void sourceDebugSessionLifecycleMatchesTheRestServer() throws Exception {
        var dispatcher = PlaygroundDispatcher.create();
        var sandbox = new CompilationSandbox(2, 30);
        var libraries = LibrarySourceResolver.scanClasspathSources(JulcCompiler.class.getClassLoader());
        var sourceDebug = new SourceDebugController(sandbox, libraries);
        var app = Javalin.create()
                .post("/api/source-debug/open", sourceDebug::open)
                .post("/api/source-debug/act", sourceDebug::act)
                .post("/api/source-debug/close", sourceDebug::close);
        try {
            JavalinTest.test(app, (server, client) -> {
                var openRequest = new OpenRequest("""
                        @SpendingValidator
                        class WasmParitySourceDebug {
                            @Entrypoint
                            static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                                return true;
                            }
                        }
                        """, null, List.of(), sourceDebugTransaction(), 11, null, null, null, null);
                String openBody = MAPPER.writeValueAsString(openRequest);
                JsonNode serverOpen;
                try (var response = client.post("/api/source-debug/open", openBody)) {
                    assertEquals(200, response.code());
                    serverOpen = MAPPER.readTree(response.body().string());
                }
                JsonNode wasmOpenEnvelope = MAPPER.readTree(dispatcher.dispatch(
                        "POST", "/api/source-debug/open", openBody));
                assertEquals(200, wasmOpenEnvelope.get("status").asInt());
                JsonNode wasmOpen = wasmOpenEnvelope.get("body");
                assertSourceOpenParity(serverOpen, wasmOpen);

                String serverSession = serverOpen.get("sessionId").asText();
                String wasmSession = wasmOpen.get("sessionId").asText();
                assertSessionActionParity(client, dispatcher, serverSession, wasmSession, "goto", 1L, 200);

                var serverClose = client.post("/api/source-debug/close",
                        MAPPER.writeValueAsString(new CloseRequest(serverSession)));
                JsonNode wasmClose = MAPPER.readTree(dispatcher.dispatch("POST", "/api/source-debug/close",
                        MAPPER.writeValueAsString(new CloseRequest(wasmSession))));
                try (serverClose) {
                    assertEquals(serverClose.code(), wasmClose.get("status").asInt());
                    assertEquals(MAPPER.readTree(serverClose.body().string()), wasmClose.get("body"));
                }
                assertSessionActionParity(client, dispatcher, serverSession, wasmSession, "goto", 1L, 404);
            });
        } finally {
            sandbox.shutdown();
        }
    }

    @Test
    void parityFixturesCoverSuccessAndFailurePaths() throws Exception {
        JsonNode results = MAPPER.readTree(ParityFixtures.run(loadFixtures(), DISPATCHER));

        assertTrue(body(results, "compile-simple-spending-blueprint").get("blueprintJson").isTextual());
        assertTrue(body(results, "compile-with-library").get("uplcText").asText().startsWith("(program"));
        assertTrue(body(results, "evaluate-simple-spending-signed").get("success").asBoolean());
        assertFalse(body(results, "evaluate-simple-spending-unsigned").get("success").asBoolean());
        assertTrue(body(results, "evaluate-with-library").get("success").asBoolean());
        assertTrue(body(results, "evaluate-bls").get("success").asBoolean(), "BLS evaluates on the JVM");
        assertEquals(400, result(results, "evaluate-missing-param").get("status").asInt());
        assertEquals(404, result(results, "examples-missing").get("status").asInt());
        assertEquals("5", body(results, "eval-abs").get("result").asText());
    }

    @Test
    void unknownRouteIsNotFound() throws Exception {
        JsonNode post = MAPPER.readTree(DISPATCHER.dispatch("POST", "/api/nope", "{}"));
        JsonNode get = MAPPER.readTree(DISPATCHER.dispatch("GET", "/api/check", null));

        assertEquals(404, post.get("status").asInt());
        assertEquals("Endpoint POST /api/nope not found", post.get("body").get("error").asText());
        assertEquals(404, get.get("status").asInt());
    }

    @Test
    void malformedBodyIsBadRequest() throws Exception {
        JsonNode response = MAPPER.readTree(DISPATCHER.dispatch("POST", "/api/compile", "{not json"));

        assertEquals(400, response.get("status").asInt());
        assertTrue(response.get("body").get("error").asText().startsWith("Invalid request body"));
    }

    @Test
    void healthReportsEngine() throws Exception {
        JsonNode response = MAPPER.readTree(DISPATCHER.dispatch("get", "/api/health", null));

        assertEquals(200, response.get("status").asInt());
        assertEquals("wasm", response.get("body").get("engine").asText());
    }

    static JsonNode loadFixtures() throws Exception {
        try (InputStream in = PlaygroundDispatcherTest.class.getResourceAsStream("/parity-requests.json")) {
            return MAPPER.readTree(in);
        }
    }

    private static void assertSourceOpenParity(JsonNode server, JsonNode wasm) {
        assertTrue(server.get("ok").asBoolean());
        assertTrue(wasm.get("ok").asBoolean());
        assertFalse(server.get("sessionId").asText().isBlank());
        assertFalse(wasm.get("sessionId").asText().isBlank());
        var serverWithoutSession = server.deepCopy();
        var wasmWithoutSession = wasm.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) serverWithoutSession).remove("sessionId");
        ((com.fasterxml.jackson.databind.node.ObjectNode) wasmWithoutSession).remove("sessionId");
        assertEquals(serverWithoutSession, wasmWithoutSession);
    }

    private static void assertSessionActionParity(HttpClient client, PlaygroundDispatcher dispatcher,
                                                  String serverSession, String wasmSession, String action,
                                                  long step, int expectedStatus) throws Exception {
        var serverRequest = new ActionRequest(serverSession, action, step, null);
        var wasmRequest = new ActionRequest(wasmSession, action, step, null);
        JsonNode wasmResponse = MAPPER.readTree(dispatcher.dispatch("POST", "/api/source-debug/act",
                MAPPER.writeValueAsString(wasmRequest)));
        try (var serverResponse = client.post("/api/source-debug/act", MAPPER.writeValueAsString(serverRequest))) {
            assertEquals(expectedStatus, serverResponse.code());
            assertEquals(serverResponse.code(), wasmResponse.get("status").asInt());
            assertEquals(MAPPER.readTree(serverResponse.body().string()), wasmResponse.get("body"));
        }
    }

    private static MockTransaction sourceDebugTransaction() {
        var input = new MockTransaction.TxIn("11".repeat(32), 0L,
                new MockTransaction.Address("$self", null), new MockTransaction.Value("10000000", null),
                new MockTransaction.Datum("inline", new DataInput("uplc", "I 7"), null), null);
        return new MockTransaction(new MockTransaction.Purpose("spend", 0, null, null),
                new DataInput("uplc", "Constr 0 []"), List.of(input), null, null, "0", null, null, null,
                null, null, null, "22".repeat(32), null, null, null, null);
    }

    private static JsonNode result(JsonNode results, String name) {
        for (JsonNode r : results) {
            if (r.get("name").asText().equals(name)) return r;
        }
        throw new AssertionError("No result for " + name);
    }

    private static JsonNode body(JsonNode results, String name) {
        return result(results, name).get("body");
    }
}
