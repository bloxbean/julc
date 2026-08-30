package com.bloxbean.julc.cli.cmd.verify;

import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CertificatePayloadExecutionTest {
    private static final byte[] EXPECTED_POOL = repeated((byte) 65);

    @Test
    void committedFixtureInspectsPoolRetirementPayloadStrictly() throws Exception {
        String source = Files.readString(Path.of("..",
                "verification/e4i/fixtures/certificates/src/"
                        + "AuthorizedPoolRetirement.java"));
        CompileResult compiled = new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compile(source);

        assertTrue(evaluate(compiled, poolRetirement(EXPECTED_POOL, 100)));
        assertTrue(evaluate(compiled, poolRetirement(EXPECTED_POOL, 0)));
        assertFalse(evaluate(compiled, poolRetirement(repeated((byte) 66), 100)),
                "Wrong pool payload must reject");
        assertFalse(evaluate(compiled, poolRetirement(EXPECTED_POOL, 101)),
                "Epoch above the declared bound must reject");
        assertFalse(evaluate(compiled,
                PlutusData.constr(8, PlutusData.bytes(EXPECTED_POOL))),
                "Missing epoch payload must reject at the strict ScriptContext boundary");
        assertFalse(evaluate(compiled, PlutusData.constr(5,
                PlutusData.constr(1, PlutusData.bytes(EXPECTED_POOL)))),
                "A different certificate constructor must reject");
    }

    @Test
    void committedDRepFixtureInspectsNestedCredentialPayload() throws Exception {
        String source = Files.readString(Path.of("..",
                "verification/e4i/fixtures/certificates/src/AuthorizedDRepUpdate.java"));
        CompileResult compiled = new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compile(source);
        PlutusData expected = PlutusData.constr(5,
                PlutusData.constr(0, PlutusData.bytes(EXPECTED_POOL)));
        PlutusData wrongKey = PlutusData.constr(5,
                PlutusData.constr(0, PlutusData.bytes(repeated((byte) 66))));
        PlutusData scriptCredential = PlutusData.constr(5,
                PlutusData.constr(1, PlutusData.bytes(EXPECTED_POOL)));

        assertTrue(evaluate(compiled, expected));
        assertFalse(evaluate(compiled, wrongKey));
        assertFalse(evaluate(compiled, scriptCredential));
    }

    @Test
    void committedRegistrationFixtureInspectsDepositPayload() throws Exception {
        String source = Files.readString(Path.of("..",
                "verification/e4i/fixtures/certificates/src/"
                        + "AuthorizedDRepRegistration.java"));
        CompileResult compiled = new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compile(source);
        PlutusData credential = PlutusData.constr(0,
                PlutusData.bytes(EXPECTED_POOL));

        assertTrue(evaluate(compiled,
                PlutusData.constr(4, credential, PlutusData.integer(1))));
        assertFalse(evaluate(compiled,
                PlutusData.constr(4, credential, PlutusData.integer(2))));
        assertFalse(evaluate(compiled,
                PlutusData.constr(4, credential)));
    }

    private static boolean evaluate(CompileResult compiled, PlutusData certificate) {
        return VerificationExecution.evaluate(
                compiled, certifyingContext(certificate)).isSuccess();
    }

    private static PlutusData poolRetirement(byte[] pool, long epoch) {
        return PlutusData.constr(8, PlutusData.bytes(pool), PlutusData.integer(epoch));
    }

    private static PlutusData certifyingContext(PlutusData certificate) {
        PlutusData txInfo = PlutusData.constr(0,
                PlutusData.list(), PlutusData.list(), PlutusData.list(),
                PlutusData.integer(0), PlutusData.map(),
                PlutusData.list(certificate), PlutusData.map(),
                alwaysInterval(), PlutusData.list(),
                PlutusData.map(), PlutusData.map(), PlutusData.bytes(new byte[32]),
                PlutusData.map(), PlutusData.list(), PlutusData.constr(1),
                PlutusData.constr(1));
        return PlutusData.constr(0, txInfo, PlutusData.constr(0),
                PlutusData.constr(3, PlutusData.integer(0), certificate));
    }

    private static PlutusData alwaysInterval() {
        return PlutusData.constr(0,
                PlutusData.constr(0, PlutusData.constr(0), PlutusData.constr(1)),
                PlutusData.constr(0, PlutusData.constr(2), PlutusData.constr(1)));
    }

    private static byte[] repeated(byte value) {
        byte[] result = new byte[28];
        java.util.Arrays.fill(result, value);
        return result;
    }
}
