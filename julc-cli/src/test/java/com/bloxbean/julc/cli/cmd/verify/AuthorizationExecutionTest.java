package com.bloxbean.julc.cli.cmd.verify;

import com.bloxbean.cardano.julc.compiler.CompileResult;
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

class AuthorizationExecutionTest {
    private static final byte[] KEY_A = repeated((byte) 65);
    private static final byte[] KEY_B = repeated((byte) 66);
    private static final byte[] KEY_C = repeated((byte) 67);
    private static final byte[] OUTSIDER = repeated((byte) 68);

    @Test
    void committedThresholdFixtureEnforcesDistinctApprovedOnlySigners()
            throws Exception {
        String source = Files.readString(Path.of("..",
                "verification/e4h/fixtures/contracts/src/AuthorizedThresholdGate.java"));
        CompileResult compiled = new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compile(source);

        assertTrue(evaluate(compiled, KEY_A, KEY_B));
        assertTrue(evaluate(compiled, KEY_C, KEY_A));
        assertFalse(evaluate(compiled, KEY_A));
        assertFalse(evaluate(compiled, KEY_A, KEY_A));
        assertFalse(evaluate(compiled, KEY_A, OUTSIDER));
        assertFalse(evaluate(compiled, KEY_A, KEY_B, KEY_C));
    }

    private static boolean evaluate(CompileResult compiled, byte[]... signers) {
        PlutusData[] values = java.util.Arrays.stream(signers)
                .map(PlutusData::bytes).toArray(PlutusData[]::new);
        PlutusData txInfo = PlutusData.constr(0,
                PlutusData.list(), PlutusData.list(), PlutusData.list(),
                PlutusData.integer(1), PlutusData.map(), PlutusData.list(),
                PlutusData.map(), alwaysInterval(), PlutusData.list(values),
                PlutusData.map(), PlutusData.map(), PlutusData.bytes(new byte[32]),
                PlutusData.map(), PlutusData.list(), PlutusData.constr(1),
                PlutusData.constr(1));
        PlutusData datum = PlutusData.constr(0);
        PlutusData redeemer = PlutusData.constr(0);
        PlutusData outRef = PlutusData.constr(0,
                PlutusData.bytes(new byte[32]), PlutusData.integer(0));
        PlutusData context = PlutusData.constr(0, txInfo, redeemer,
                PlutusData.constr(1, outRef, PlutusData.constr(0, datum)));
        return JulcVm.create().evaluateWithArgs(compiled.program(), List.of(context))
                .isSuccess();
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
