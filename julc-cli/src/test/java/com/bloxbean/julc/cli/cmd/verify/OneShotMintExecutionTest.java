package com.bloxbean.julc.cli.cmd.verify;

import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OneShotMintExecutionTest {
    @Test
    void committedPositiveFixtureAcceptsConcreteStrictMintingContext() throws Exception {
        String source = Files.readString(Path.of("..",
                "verification/e4a/fixtures/authorized/src/OneShotPolicy.java"));
        var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry()).compile(source);

        byte[] authority = "JULC_VERIFY_AUTHORITY_000001".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        byte[] token = "JULC".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] policy = new byte[]{1, 2, 3, 4};
        byte[] anchor = new byte[32];
        for (int index = 0; index < anchor.length; index++) anchor[index] = (byte) (index + 1);
        PlutusData correctMint = PlutusData.map(new PlutusData.Pair(
                PlutusData.bytes(policy),
                PlutusData.map(new PlutusData.Pair(
                        PlutusData.bytes(token), PlutusData.integer(1)))));
        PlutusData outRef = PlutusData.constr(0,
                PlutusData.bytes(anchor), PlutusData.integer(0));
        PlutusData input = PlutusData.constr(0, outRef, PlutusData.constr(0));
        PlutusData goodContext = mintingContext(policy, PlutusData.constr(0),
                correctMint, PlutusData.list(input), PlutusData.list(PlutusData.bytes(authority)));

        var result = JulcVm.create().evaluateWithArgs(compiled.program(), List.of(goodContext));
        assertTrue(result.isSuccess(), () -> "Expected concrete one-shot acceptance: " + result);

        PlutusData malformedRedeemer = mintingContext(policy, PlutusData.integer(0),
                correctMint, PlutusData.list(input), PlutusData.list(PlutusData.bytes(authority)));
        assertFalse(JulcVm.create().evaluateWithArgs(
                compiled.program(), List.of(malformedRedeemer)).isSuccess(),
                "Strict boundary must reject a malformed redeemer before policy code");

        PlutusData duplicateMint = PlutusData.map(
                new PlutusData.Pair(PlutusData.bytes(policy),
                        PlutusData.map(new PlutusData.Pair(
                                PlutusData.bytes(token), PlutusData.integer(1)))),
                new PlutusData.Pair(PlutusData.bytes(policy),
                        PlutusData.map(new PlutusData.Pair(
                                PlutusData.bytes(token), PlutusData.integer(1)))));
        assertFalse(JulcVm.create().evaluateWithArgs(compiled.program(), List.of(
                mintingContext(policy, PlutusData.constr(0), duplicateMint,
                        PlutusData.list(input),
                        PlutusData.list(PlutusData.bytes(authority))))).isSuccess(),
                "Duplicate current-policy entries must reject");

        PlutusData wrongQuantity = PlutusData.map(new PlutusData.Pair(
                PlutusData.bytes(policy), PlutusData.map(new PlutusData.Pair(
                        PlutusData.bytes(token), PlutusData.integer(2)))));
        assertFalse(JulcVm.create().evaluateWithArgs(compiled.program(), List.of(
                mintingContext(policy, PlutusData.constr(0), wrongQuantity,
                        PlutusData.list(input),
                        PlutusData.list(PlutusData.bytes(authority))))).isSuccess(),
                "Wrong quantity must reject");
    }

    private static PlutusData mintingContext(
            byte[] policy,
            PlutusData redeemer,
            PlutusData mint,
            PlutusData inputs,
            PlutusData signatories) {
        PlutusData txInfo = PlutusData.constr(0,
                inputs, PlutusData.list(), PlutusData.list(),
                PlutusData.integer(1), mint, PlutusData.list(), PlutusData.map(),
                alwaysInterval(), signatories,
                PlutusData.map(), PlutusData.map(), PlutusData.bytes(new byte[32]),
                PlutusData.map(), PlutusData.list(), PlutusData.constr(1),
                PlutusData.constr(1));
        return PlutusData.constr(0, txInfo, redeemer,
                PlutusData.constr(0, PlutusData.bytes(policy)));
    }

    private static PlutusData alwaysInterval() {
        return PlutusData.constr(0,
                PlutusData.constr(0, PlutusData.constr(0), PlutusData.constr(1)),
                PlutusData.constr(0, PlutusData.constr(2), PlutusData.constr(1)));
    }
}
