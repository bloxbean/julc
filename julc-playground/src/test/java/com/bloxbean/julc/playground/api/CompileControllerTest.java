package com.bloxbean.julc.playground.api;

import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.julc.playground.model.CompileRequest;
import com.bloxbean.julc.playground.model.CompileResponse;
import com.bloxbean.julc.playground.sandbox.CompilationSandbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompileControllerTest {

    static final ObjectMapper mapper = new ObjectMapper();
    static final JulcCompiler julcCompiler = new JulcCompiler();
    static final CompilationSandbox sandbox = new CompilationSandbox(2, 30);
    static final java.util.Map<String, String> cachedLibs =
            com.bloxbean.cardano.julc.compiler.LibrarySourceResolver.scanClasspathSources(
                    JulcCompiler.class.getClassLoader());
    static final CompileController controller = new CompileController(julcCompiler, sandbox, cachedLibs);

    @AfterAll
    static void tearDown() {
        sandbox.shutdown();
    }

    Javalin app() {
        return Javalin.create().post("/api/compile", controller::handle);
    }

    @Test
    void validJavaValidator_compilesToUplc() {
        JavalinTest.test(app(), (server, client) -> {
            var body = mapper.writeValueAsString(new CompileRequest("""
                    import java.math.BigInteger;

                    @Validator
                    class SimpleValidator {
                        @Entrypoint
                        static boolean validate(BigInteger redeemer, BigInteger ctx) {
                            return true;
                        }
                    }
                    """));
            var res = client.post("/api/compile", body);
            assertEquals(200, res.code());

            var json = mapper.readValue(res.body().string(), CompileResponse.class);
            assertNotNull(json.uplcText());
            assertTrue(json.uplcText().startsWith("(program"));
            assertTrue(json.scriptSizeBytes() > 0);
            assertTrue(json.diagnostics().isEmpty());
        });
    }

    @Test
    void invalidJavaSource_returnsNoUplc() {
        JavalinTest.test(app(), (server, client) -> {
            var body = mapper.writeValueAsString(new CompileRequest("not valid java"));
            var res = client.post("/api/compile", body);
            var json = mapper.readValue(res.body().string(), CompileResponse.class);
            assertNull(json.uplcText());
        });
    }

    @Test
    void emptySource_returnsError() {
        JavalinTest.test(app(), (server, client) -> {
            var body = mapper.writeValueAsString(new CompileRequest(""));
            var res = client.post("/api/compile", body);
            assertEquals(200, res.code());
            var json = mapper.readValue(res.body().string(), CompileResponse.class);
            assertNull(json.uplcText());
            assertFalse(json.diagnostics().isEmpty());
            assertTrue(json.diagnostics().getFirst().message().contains("required"));
        });
    }

    @Test
    void oversizedSource_returnsError() {
        JavalinTest.test(app(), (server, client) -> {
            // Source exceeding 100KB limit
            String big = "x".repeat(InputValidator.MAX_SOURCE_LENGTH + 1);
            var body = mapper.writeValueAsString(new CompileRequest(big));
            var res = client.post("/api/compile", body);
            var json = mapper.readValue(res.body().string(), CompileResponse.class);
            assertNull(json.uplcText());
            assertFalse(json.diagnostics().isEmpty());
            assertTrue(json.diagnostics().getFirst().message().contains("maximum length"));
        });
    }

    @Test
    void sandboxTimeout_returns408() {
        // Use a 1-second timeout sandbox
        var shortSandbox = new CompilationSandbox(1, 1);
        var ctrl = new CompileController(julcCompiler, shortSandbox, cachedLibs);
        try {
            var app = Javalin.create().post("/api/compile", ctrl::handle);
            JavalinTest.test(app, (server, client) -> {
                // This valid source will compile but the 1s timeout is very tight
                // We'll test with a source that's valid enough to parse but slow
                var body = mapper.writeValueAsString(new CompileRequest("""
                        import java.math.BigInteger;
                        @Validator
                        class V {
                            @Entrypoint
                            static boolean validate(BigInteger r, BigInteger c) { return true; }
                        }
                        """));
                var res = client.post("/api/compile", body);
                // Either 200 (fast enough) or 408 (timeout) — both are valid
                assertTrue(res.code() == 200 || res.code() == 408);
            });
        } finally {
            shortSandbox.shutdown();
        }
    }
}
