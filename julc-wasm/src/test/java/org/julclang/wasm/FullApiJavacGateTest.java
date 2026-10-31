package org.julclang.wasm;

import org.julclang.compiler.frontend.javac.JavacGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-068 D8, M1: the {@code julc-wasm} full engine's host adapter, on the JVM (the Web Image runs the same code;
 * its image smoke is separate). The editor source is the root and the engine's class-path scan is the pool.
 */
class FullApiJavacGateTest {

    private String saved;

    @BeforeEach
    void enforce() {
        saved = System.getProperty(JavacGate.MODE_PROPERTY);
        System.setProperty(JavacGate.MODE_PROPERTY, "enforce");
    }

    @AfterEach
    void restore() {
        if (saved == null) System.clearProperty(JavacGate.MODE_PROPERTY);
        else System.setProperty(JavacGate.MODE_PROPERTY, saved);
    }

    static String compile(String condition) throws Exception {
        String source = """
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.SpendingValidator;
                import org.julclang.stdlib.lib.MathLib;
                import org.julclang.verification.annotation.RequiresSigner;
                import java.math.BigInteger;

                @SpendingValidator
                @RequiresSigner("datum.owner")
                class FeeGate {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return %s;
                    }
                }
                """.formatted(condition);
        return JulcWasm.create().invoke("compiler.compile", JsMarshalling.JSON.writeValueAsString(Map.of("source", source)));
    }

    @Test
    void javaSourceWithTheStdlibCompiles() throws Exception {
        String result = compile("MathLib.max(ctx.txInfo().fee(), BigInteger.ONE).signum() > 0");
        assertFalse(result.contains("JULC0085"), result);
        assertTrue(result.contains("(program"), result);
    }

    @Test
    void javacErrorIsReported() throws Exception {
        String result = compile("ctx.txInfo().fee() > 1");
        assertTrue(result.contains("JULC0085"), result);
        assertTrue(result.contains("compiler.err.operator.cant.be.applied.1"), result);
    }
}
