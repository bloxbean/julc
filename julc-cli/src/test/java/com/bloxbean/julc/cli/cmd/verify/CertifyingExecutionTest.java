package com.bloxbean.julc.cli.cmd.verify;

import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CertifyingExecutionTest {
    @Test
    void committedCertifyingFixtureChecksStrictRedeemerSignerAndCertificateKind()
            throws Exception {
        String source = Files.readString(Path.of("..",
                "verification/e4d/fixtures/certifying/src/AuthorizedCertificates.java"));
        CompileResult compiled = new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compile(source);
        PlutusData signer = PlutusData.bytes(
                "JULC_VERIFY_AUTHORITY_000001".getBytes(StandardCharsets.UTF_8));
        PlutusData credential = PlutusData.constr(1, PlutusData.bytes(new byte[28]));
        PlutusData updateDRep = PlutusData.constr(5, credential);

        assertTrue(evaluate(compiled, certifyingContext(
                PlutusData.constr(0), updateDRep, PlutusData.list(signer))),
                "Expected exact certifying acceptance");
        assertFalse(evaluate(compiled, certifyingContext(
                PlutusData.integer(0), updateDRep, PlutusData.list(signer))),
                "Strict boundary must reject malformed redeemer Data");
        assertFalse(evaluate(compiled, certifyingContext(
                PlutusData.constr(0), updateDRep, PlutusData.list())),
                "Missing authority must reject");
        assertFalse(evaluate(compiled, certifyingContext(
                PlutusData.constr(0), PlutusData.constr(10, credential),
                PlutusData.list(signer))),
                "A different certificate constructor must reject");
    }

    private static boolean evaluate(CompileResult compiled, PlutusData context) {
        return VerificationExecution.evaluate(compiled, context)
                .isSuccess();
    }

    private static PlutusData certifyingContext(
            PlutusData redeemer, PlutusData certificate, PlutusData signatories) {
        PlutusData txInfo = PlutusData.constr(0,
                PlutusData.list(), PlutusData.list(), PlutusData.list(),
                PlutusData.integer(0), PlutusData.map(),
                PlutusData.list(certificate), PlutusData.map(),
                alwaysInterval(), signatories,
                PlutusData.map(), PlutusData.map(), PlutusData.bytes(new byte[32]),
                PlutusData.map(), PlutusData.list(), PlutusData.constr(1),
                PlutusData.constr(1));
        return PlutusData.constr(0, txInfo, redeemer,
                PlutusData.constr(3, PlutusData.integer(0), certificate));
    }

    private static PlutusData alwaysInterval() {
        return PlutusData.constr(0,
                PlutusData.constr(0, PlutusData.constr(0), PlutusData.constr(1)),
                PlutusData.constr(0, PlutusData.constr(2), PlutusData.constr(1)));
    }
}
