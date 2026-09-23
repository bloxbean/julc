package org.julclang.playground.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LibrarySourceResolver;
import org.julclang.playground.sandbox.CompilationSandbox;
import org.julclang.tools.model.MockTransaction;
import org.julclang.tools.model.MockTransaction.DataInput;
import org.julclang.tools.model.SourceDebugModels.ActionRequest;
import org.julclang.tools.model.SourceDebugModels.ActionResponse;
import org.julclang.tools.model.SourceDebugModels.CloseRequest;
import org.julclang.tools.model.SourceDebugModels.LocalsRequest;
import org.julclang.tools.model.SourceDebugModels.LocalsResponse;
import org.julclang.tools.model.SourceDebugModels.OpenRequest;
import org.julclang.tools.model.SourceDebugModels.OpenResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SourceDebugControllerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final CompilationSandbox SANDBOX = new CompilationSandbox(2, 30);
    private static final SourceDebugController CONTROLLER = new SourceDebugController(SANDBOX,
            LibrarySourceResolver.scanClasspathSources(JulcCompiler.class.getClassLoader()));

    @AfterAll
    static void closeSandbox() {
        SANDBOX.shutdown();
    }

    private static Javalin app() {
        return Javalin.create()
                .post("/api/source-debug/open", CONTROLLER::open)
                .post("/api/source-debug/act", CONTROLLER::act)
                .post("/api/source-debug/locals", CONTROLLER::locals)
                .post("/api/source-debug/children", CONTROLLER::children)
                .post("/api/source-debug/close", CONTROLLER::close);
    }

    @Test
    void opensActsAndClosesTheSameBoundSession() {
        JavalinTest.test(app(), (server, client) -> {
            var open = client.post("/api/source-debug/open", JSON.writeValueAsString(new OpenRequest("""
                    @SpendingValidator
                    class BrowserSourceDebug {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return true;
                        }
                    }
                    """, null, List.of(), transaction(), 11, null, null, null, null, true)));
            assertEquals(200, open.code());
            var descriptor = JSON.readValue(open.body().string(), OpenResponse.class);
            assertTrue(descriptor.ok(), descriptor.error());
            assertNotNull(descriptor.sessionId());
            assertTrue(descriptor.warning().contains("optimizer is disabled"));
            assertTrue(descriptor.localsCapability().available());

            var locals = client.post("/api/source-debug/locals", JSON.writeValueAsString(
                    new LocalsRequest(descriptor.sessionId(), descriptor.snapshot().stopGeneration())));
            assertEquals(200, locals.code());
            assertTrue(JSON.readValue(locals.body().string(), LocalsResponse.class).ok());

            var act = client.post("/api/source-debug/act", JSON.writeValueAsString(
                    new ActionRequest(descriptor.sessionId(), "goto", 1L, null)));
            assertEquals(200, act.code());
            assertTrue(JSON.readValue(act.body().string(), ActionResponse.class).ok());

            assertEquals(200, client.post("/api/source-debug/close",
                    JSON.writeValueAsString(new CloseRequest(descriptor.sessionId()))).code());
            assertEquals(404, client.post("/api/source-debug/act", JSON.writeValueAsString(
                    new ActionRequest(descriptor.sessionId(), "goto", 1L, null))).code());
        });
    }

    private static MockTransaction transaction() {
        var input = new MockTransaction.TxIn("11".repeat(32), 0L,
                new MockTransaction.Address("$self", null), new MockTransaction.Value("10000000", null),
                new MockTransaction.Datum("inline", new DataInput("uplc", "I 7"), null), null);
        return new MockTransaction(new MockTransaction.Purpose("spend", 0, null, null),
                new DataInput("uplc", "Constr 0 []"), List.of(input), null, null, "0", null, null, null,
                null, null, null, "22".repeat(32), null, null, null, null);
    }
}
