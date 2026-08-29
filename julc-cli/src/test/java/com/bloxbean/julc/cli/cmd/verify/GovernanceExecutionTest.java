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

class GovernanceExecutionTest {

    @Test
    void committedFixtureInspectsProposalAndGovernanceActionOnTheVm()
            throws Exception {
        String source = Files.readString(Path.of("..",
                "verification/e4k/fixtures/governance-data/src/"
                        + "AuthorizedGovernance.java"));
        CompileResult compiled = new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compile(source);

        PlutusData valid = proposal(10, hardFork(11));
        PlutusData lowDeposit = proposal(9, hardFork(11));
        PlutusData wrongVersion = proposal(10, hardFork(12));
        PlutusData wrongConstructor = proposal(10, PlutusData.constr(6));
        PlutusData malformedAction = proposal(10,
                PlutusData.constr(1, PlutusData.constr(1)));

        assertTrue(evaluate(compiled, valid));
        assertFalse(evaluate(compiled, lowDeposit));
        assertFalse(evaluate(compiled, wrongVersion));
        assertFalse(evaluate(compiled, wrongConstructor));
        assertFalse(evaluate(compiled, malformedAction));
        assertTrue(evaluate(compiled, valid, lowDeposit),
                "The committed validator deliberately inspects the first proposal");
        assertFalse(evaluate(compiled, lowDeposit, valid),
                "Proposal order and duplicate candidates remain observable");
        assertFalse(evaluate(compiled, PlutusData.constr(0,
                PlutusData.integer(10), returnCredential())),
                "A short proposal must reject");
        assertFalse(evaluate(compiled, PlutusData.constr(0,
                PlutusData.integer(10), PlutusData.integer(0), hardFork(11))),
                "A malformed return credential must reject");
    }

    private static boolean evaluate(CompileResult compiled, PlutusData... proposals) {
        return VerificationExecution.evaluate(
                compiled, spendingContext(proposals)).isSuccess();
    }

    private static PlutusData proposal(long deposit, PlutusData action) {
        return PlutusData.constr(0, PlutusData.integer(deposit),
                returnCredential(), action);
    }

    private static PlutusData hardFork(long major) {
        return PlutusData.constr(1,
                PlutusData.constr(1),
                PlutusData.constr(0, PlutusData.integer(major),
                        PlutusData.integer(0)));
    }

    private static PlutusData returnCredential() {
        return PlutusData.constr(0, PlutusData.bytes(new byte[28]));
    }

    private static PlutusData spendingContext(PlutusData... proposals) {
        PlutusData ownRef = PlutusData.constr(0,
                PlutusData.bytes(new byte[32]), PlutusData.integer(0));
        PlutusData txInfo = PlutusData.constr(0,
                PlutusData.list(), PlutusData.list(), PlutusData.list(),
                PlutusData.integer(0), PlutusData.map(), PlutusData.list(),
                PlutusData.map(), alwaysInterval(), PlutusData.list(),
                PlutusData.map(), PlutusData.map(), PlutusData.bytes(new byte[32]),
                PlutusData.map(), PlutusData.list(proposals), PlutusData.constr(1),
                PlutusData.constr(1));
        PlutusData datum = PlutusData.constr(0);
        PlutusData redeemer = PlutusData.constr(0);
        return PlutusData.constr(0, txInfo, redeemer,
                PlutusData.constr(1, ownRef, PlutusData.constr(0, datum)));
    }

    private static PlutusData alwaysInterval() {
        return PlutusData.constr(0,
                PlutusData.constr(0, PlutusData.constr(0), PlutusData.constr(1)),
                PlutusData.constr(0, PlutusData.constr(2), PlutusData.constr(1)));
    }
}
