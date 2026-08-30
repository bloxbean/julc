package com.bloxbean.julc.cli.cmd.verify;

import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlledMintExecutionTest {
    @Test
    void committedMintFixtureHasAConcreteSuccessfulExecution() throws Exception {
        assertConcreteExecution(
                "verification/c7/fixtures/mint/src/ControlledMintPolicy.java", 1);
    }

    @Test
    void committedBurnFixtureHasAConcreteSuccessfulExecution() throws Exception {
        assertConcreteExecution(
                "verification/c7/fixtures/burn/src/ControlledBurnPolicy.java", -1);
    }

    private static void assertConcreteExecution(String relativeSource, long quantity)
            throws Exception {
        Path sourceFile = Path.of("..", relativeSource);
        String source = Files.readString(sourceFile);
        var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry()).compile(source);

        byte[] authority = "JULC_VERIFY_AUTHORITY_000001".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        byte[] token = "JULC".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] policy = new byte[]{1, 2, 3, 4};
        PlutusData mint = PlutusData.map(new PlutusData.Pair(
                PlutusData.bytes(policy),
                PlutusData.map(new PlutusData.Pair(
                        PlutusData.bytes(token), PlutusData.integer(quantity)))));
        PlutusData txInfo = PlutusData.constr(0,
                PlutusData.list(), PlutusData.list(), PlutusData.list(),
                PlutusData.integer(0), mint, PlutusData.list(), PlutusData.map(),
                alwaysInterval(), PlutusData.list(PlutusData.bytes(authority)),
                PlutusData.map(), PlutusData.map(), PlutusData.bytes(new byte[32]),
                PlutusData.map(), PlutusData.list(), PlutusData.constr(1),
                PlutusData.constr(1));
        PlutusData context = PlutusData.constr(0, txInfo, PlutusData.constr(0),
                PlutusData.constr(0, PlutusData.bytes(policy)));

        var result = VerificationExecution.evaluate(compiled, context);
        assertTrue(result.isSuccess(), () -> "Expected concrete policy acceptance: " + result);
    }

    private static PlutusData alwaysInterval() {
        return PlutusData.constr(0,
                PlutusData.constr(0, PlutusData.constr(0), PlutusData.constr(1)),
                PlutusData.constr(0, PlutusData.constr(2), PlutusData.constr(1)));
    }
}
