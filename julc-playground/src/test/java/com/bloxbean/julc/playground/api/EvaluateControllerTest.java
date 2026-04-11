package com.bloxbean.julc.playground.api;

import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.julc.playground.model.EvaluateRequest;
import com.bloxbean.julc.playground.model.EvaluateResponse;
import com.bloxbean.julc.playground.model.ScenarioOverrides;
import com.bloxbean.julc.playground.sandbox.CompilationSandbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvaluateControllerTest {

    static final ObjectMapper mapper = new ObjectMapper();
    static final JulcCompiler julcCompiler = new JulcCompiler();
    static final CompilationSandbox sandbox = new CompilationSandbox(2, 30);
    static final java.util.Map<String, String> cachedLibs =
            com.bloxbean.cardano.julc.compiler.LibrarySourceResolver.scanClasspathSources(
                    JulcCompiler.class.getClassLoader());
    static final EvaluateController controller = new EvaluateController(julcCompiler, sandbox, cachedLibs);

    @AfterAll
    static void tearDown() {
        sandbox.shutdown();
    }

    Javalin app() {
        return Javalin.create().post("/api/evaluate", controller::handle);
    }

    @Test
    void alwaysTrueValidator_succeeds() {
        JavalinTest.test(app(), (server, client) -> {
            var req = new EvaluateRequest(
                    """
                    import java.math.BigInteger;

                    @Validator
                    class AlwaysTrue {
                        @Entrypoint
                        static boolean validate(BigInteger redeemer, BigInteger ctx) {
                            return true;
                        }
                    }
                    """,
                    null, null,
                    Map.of(),
                    new ScenarioOverrides(List.of(), null, null),
                    Map.of(),
                    null
            );
            var res = client.post("/api/evaluate", mapper.writeValueAsString(req));
            var json = mapper.readValue(res.body().string(), EvaluateResponse.class);
            assertTrue(json.success(), "Expected success but got: " + json.error());
        });
    }

    @Test
    void alwaysFalseValidator_fails() {
        JavalinTest.test(app(), (server, client) -> {
            var req = new EvaluateRequest(
                    """
                    import java.math.BigInteger;

                    @Validator
                    class AlwaysFalse {
                        @Entrypoint
                        static boolean validate(BigInteger redeemer, BigInteger ctx) {
                            return false;
                        }
                    }
                    """,
                    null, null,
                    Map.of(),
                    new ScenarioOverrides(List.of(), null, null),
                    Map.of(),
                    null
            );
            var res = client.post("/api/evaluate", mapper.writeValueAsString(req));
            var json = mapper.readValue(res.body().string(), EvaluateResponse.class);
            assertFalse(json.success());
        });
    }
}
