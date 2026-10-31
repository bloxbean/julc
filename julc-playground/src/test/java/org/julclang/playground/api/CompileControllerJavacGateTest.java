package org.julclang.playground.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LibrarySourceResolver;
import org.julclang.compiler.frontend.javac.JavacGate;
import org.julclang.playground.sandbox.CompilationSandbox;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.tools.model.CompileRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-068 D8, M1: the playground's host adapter. The editor source is the root and the server's cached class-path
 * scan is the pool, as the playground compiles them; with {@code julc.javacGate=enforce} a javac error is reported.
 */
class CompileControllerJavacGateTest {

    static final ObjectMapper JSON = new ObjectMapper();
    private String saved;
    private CompilationSandbox sandbox;

    @BeforeEach
    void enforce() {
        saved = System.getProperty(JavacGate.MODE_PROPERTY);
        System.setProperty(JavacGate.MODE_PROPERTY, "enforce");
        sandbox = new CompilationSandbox(1, 30);
    }

    @AfterEach
    void restore() {
        sandbox.shutdown();
        if (saved == null) System.clearProperty(JavacGate.MODE_PROPERTY);
        else System.setProperty(JavacGate.MODE_PROPERTY, saved);
    }

    Javalin app() {
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry());
        var libraries = LibrarySourceResolver.scanClasspathSources(JulcCompiler.class.getClassLoader());
        return Javalin.create().post("/api/compile", new CompileController(compiler, sandbox, libraries)::handle);
    }

    static String validator(String condition) {
        return """
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.SpendingValidator;
                import org.julclang.stdlib.lib.MathLib;
                import java.math.BigInteger;

                @SpendingValidator
                class FeeGate {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return %s;
                    }
                }
                """.formatted(condition);
    }

    @Test
    void javaSourceWithTheStdlibCompiles() {
        JavalinTest.test(app(), (server, client) -> {
            var res = client.post("/api/compile", JSON.writeValueAsString(new CompileRequest(
                    validator("MathLib.max(ctx.txInfo().fee(), BigInteger.ONE).signum() > 0"))));
            String body = res.body().string();
            assertEquals(200, res.code(), body);
            assertFalse(body.contains("JULC0085"), body);
            assertTrue(body.contains("(program"), body);
        });
    }

    @Test
    void javacErrorIsReported() {
        JavalinTest.test(app(), (server, client) -> {
            var res = client.post("/api/compile", JSON.writeValueAsString(new CompileRequest(
                    validator("ctx.txInfo().fee() > 1"))));
            String body = res.body().string();
            assertTrue(body.contains("JULC0085"), body);
            assertTrue(body.contains("compiler.err.operator.cant.be.applied.1"), body);
        });
    }
}
