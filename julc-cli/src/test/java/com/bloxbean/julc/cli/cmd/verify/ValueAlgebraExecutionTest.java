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

class ValueAlgebraExecutionTest {
    private static final byte[] POLICY = "1111111111111111111111111111".getBytes();
    private static final byte[] TOKEN = "token".getBytes();

    @Test
    void committedFixtureUsesExactFirstMatchValueSemanticsOnTheVm() throws Exception {
        String source = Files.readString(Path.of("..",
                "verification/e4j/fixtures/value-algebra/src/AuthorizedValue.java"));
        CompileResult compiled = new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compile(source);

        assertTrue(evaluate(compiled, value(10)));
        assertFalse(evaluate(compiled, value(9)));
        assertTrue(evaluate(compiled, duplicatePolicyValue(10, -100)),
                "The fixture deliberately uses the pinned first matching policy entry");
        assertFalse(evaluate(compiled, PlutusData.list()),
                "TxOut Value must retain its ledger Map representation");
        assertFalse(evaluate(compiled, PlutusData.map(new PlutusData.Pair(
                PlutusData.integer(1), PlutusData.map()))),
                "Malformed policy identifiers must reject at the strict ledger boundary");
    }

    private static boolean evaluate(CompileResult compiled, PlutusData value) {
        return VerificationExecution.evaluate(
                compiled, spendingContext(value)).isSuccess();
    }

    private static PlutusData value(long quantity) {
        return PlutusData.map(new PlutusData.Pair(
                PlutusData.bytes(POLICY),
                PlutusData.map(new PlutusData.Pair(
                        PlutusData.bytes(TOKEN), PlutusData.integer(quantity)))));
    }

    private static PlutusData duplicatePolicyValue(long first, long second) {
        return PlutusData.map(
                new PlutusData.Pair(PlutusData.bytes(POLICY),
                        PlutusData.map(new PlutusData.Pair(
                                PlutusData.bytes(TOKEN), PlutusData.integer(first)))),
                new PlutusData.Pair(PlutusData.bytes(POLICY),
                        PlutusData.map(new PlutusData.Pair(
                                PlutusData.bytes(TOKEN), PlutusData.integer(second)))));
    }

    private static PlutusData spendingContext(PlutusData value) {
        PlutusData ownRef = outRef(0);
        PlutusData address = PlutusData.constr(0,
                PlutusData.constr(1, PlutusData.bytes(new byte[28])),
                PlutusData.constr(1));
        PlutusData output = PlutusData.constr(0, address, value,
                PlutusData.constr(0), PlutusData.constr(1));
        PlutusData txInfo = PlutusData.constr(0,
                PlutusData.list(), PlutusData.list(), PlutusData.list(output),
                PlutusData.integer(0), PlutusData.map(), PlutusData.list(),
                PlutusData.map(), alwaysInterval(), PlutusData.list(),
                PlutusData.map(), PlutusData.map(), PlutusData.bytes(new byte[32]),
                PlutusData.map(), PlutusData.list(), PlutusData.constr(1),
                PlutusData.constr(1));
        PlutusData datum = PlutusData.constr(0);
        PlutusData redeemer = PlutusData.constr(0);
        return PlutusData.constr(0, txInfo, redeemer,
                PlutusData.constr(1, ownRef, PlutusData.constr(0, datum)));
    }

    private static PlutusData outRef(long index) {
        return PlutusData.constr(0, PlutusData.bytes(new byte[32]),
                PlutusData.integer(index));
    }

    private static PlutusData alwaysInterval() {
        return PlutusData.constr(0,
                PlutusData.constr(0, PlutusData.constr(0), PlutusData.constr(1)),
                PlutusData.constr(0, PlutusData.constr(2), PlutusData.constr(1)));
    }
}
