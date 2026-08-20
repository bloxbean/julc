package com.bloxbean.julc.cli.cmd.verify;

import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RewardingExecutionTest {
    @Test
    void committedRewardingFixtureAcceptsAndPreservesFirstMatchMapSemantics()
            throws Exception {
        String source = Files.readString(Path.of("..",
                "verification/e4c/fixtures/rewarding/src/AuthorizedRewards.java"));
        var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry()).compile(source);
        byte[] authority = "JULC_VERIFY_AUTHORITY_000001".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        byte[] credentialHash = new byte[28];
        credentialHash[0] = 7;
        PlutusData credential = PlutusData.constr(1, PlutusData.bytes(credentialHash));
        PlutusData signer = PlutusData.bytes(authority);

        PlutusData good = rewardingContext(PlutusData.constr(0), credential,
                PlutusData.map(new PlutusData.Pair(
                        credential, PlutusData.integer(1_000_000))),
                PlutusData.list(signer));
        assertTrue(evaluate(compiled, good), "Expected exact rewarding acceptance");

        assertFalse(evaluate(compiled, rewardingContext(
                PlutusData.integer(0), credential,
                PlutusData.map(new PlutusData.Pair(
                        credential, PlutusData.integer(1_000_000))),
                PlutusData.list(signer))),
                "Strict boundary must reject malformed redeemer Data");
        assertFalse(evaluate(compiled, rewardingContext(
                PlutusData.constr(0), credential,
                PlutusData.map(new PlutusData.Pair(
                        credential, PlutusData.integer(1_000_000))),
                PlutusData.list())), "Missing authority must reject");
        assertFalse(evaluate(compiled, rewardingContext(
                PlutusData.constr(0), credential,
                PlutusData.map(new PlutusData.Pair(
                        credential, PlutusData.integer(999_999))),
                PlutusData.list(signer))), "Below-minimum withdrawal must reject");

        PlutusData badThenGood = PlutusData.map(
                new PlutusData.Pair(credential, PlutusData.integer(1)),
                new PlutusData.Pair(credential, PlutusData.integer(1_000_000)));
        assertFalse(evaluate(compiled, rewardingContext(
                PlutusData.constr(0), credential, badThenGood, PlutusData.list(signer))),
                "JuLC map lookup must retain first-match duplicate semantics");

        PlutusData goodThenBad = PlutusData.map(
                new PlutusData.Pair(credential, PlutusData.integer(1_000_000)),
                new PlutusData.Pair(credential, PlutusData.integer(1)));
        assertTrue(evaluate(compiled, rewardingContext(
                PlutusData.constr(0), credential, goodThenBad, PlutusData.list(signer))),
                "Later duplicate entries must not replace the first match");
    }

    private static boolean evaluate(
            CompileResult compiled, PlutusData context) {
        return JulcVm.create().evaluateWithArgs(compiled.program(), List.of(context))
                .isSuccess();
    }

    private static PlutusData rewardingContext(
            PlutusData redeemer,
            PlutusData credential,
            PlutusData withdrawals,
            PlutusData signatories) {
        PlutusData txInfo = PlutusData.constr(0,
                PlutusData.list(), PlutusData.list(), PlutusData.list(),
                PlutusData.integer(0), PlutusData.map(), PlutusData.list(), withdrawals,
                alwaysInterval(), signatories,
                PlutusData.map(), PlutusData.map(), PlutusData.bytes(new byte[32]),
                PlutusData.map(), PlutusData.list(), PlutusData.constr(1),
                PlutusData.constr(1));
        return PlutusData.constr(0, txInfo, redeemer,
                PlutusData.constr(2, credential));
    }

    private static PlutusData alwaysInterval() {
        return PlutusData.constr(0,
                PlutusData.constr(0, PlutusData.constr(0), PlutusData.constr(1)),
                PlutusData.constr(0, PlutusData.constr(2), PlutusData.constr(1)));
    }
}
