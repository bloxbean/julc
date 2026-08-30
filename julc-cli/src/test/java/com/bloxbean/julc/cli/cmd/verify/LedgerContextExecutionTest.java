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

class LedgerContextExecutionTest {
    @Test
    void committedFixtureStrictlyDecodesLedgerContextAndPreservesRawMaps()
            throws Exception {
        String source = Files.readString(Path.of("..",
                "verification/e4g/fixtures/contracts/src/AuthorizedLedgerContextGate.java"));
        CompileResult compiled = new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compile(source);

        assertTrue(evaluate(compiled, context(referenceInput(
                output(scriptCredential(), inlineDatum(), noReferenceScript())),
                witnessMap(), redeemerMap())),
                "Expected the exact committed validator to accept canonical V3 context Data");

        assertFalse(evaluate(compiled, contextWithRawReferenceInputs(
                PlutusData.map(), witnessMap(), redeemerMap())),
                "Reference inputs must use the ledger List encoding");
        assertFalse(evaluate(compiled, context(referenceInput(
                output(scriptCredential(), PlutusData.constr(2), noReferenceScript())),
                witnessMap(), redeemerMap())),
                "Inline output datum must have exactly one payload");
        assertFalse(evaluate(compiled, context(referenceInput(
                output(PlutusData.constr(2, PlutusData.bytes(new byte[28])),
                        inlineDatum(), noReferenceScript())),
                witnessMap(), redeemerMap())),
                "Unknown credential constructors must reject");
        assertFalse(evaluate(compiled, context(referenceInput(
                output(scriptCredential(), inlineDatum(), PlutusData.constr(0))),
                witnessMap(), redeemerMap())),
                "Some reference script must carry a script hash");
        assertFalse(evaluate(compiled, context(referenceInput(
                output(scriptCredential(), inlineDatum(), noReferenceScript())),
                PlutusData.list(PlutusData.integer(2)),
                redeemerMap())),
                "Datum witnesses must use the ledger Map encoding");

        PlutusData duplicateWitnesses = PlutusData.map(
                new PlutusData.Pair(PlutusData.bytes(new byte[]{1}),
                        PlutusData.integer(1)),
                new PlutusData.Pair(PlutusData.bytes(new byte[]{1}),
                        PlutusData.integer(2)));
        assertTrue(evaluate(compiled, context(referenceInput(
                output(scriptCredential(), inlineDatum(), noReferenceScript())),
                duplicateWitnesses, redeemerMap())),
                "Ledger association maps preserve duplicate entries");
    }

    private static boolean evaluate(CompileResult compiled, PlutusData context) {
        return VerificationExecution.evaluate(compiled, context)
                .isSuccess();
    }

    private static PlutusData context(
            PlutusData referenceInput, PlutusData datums, PlutusData redeemers) {
        return contextWithRawReferenceInputs(
                PlutusData.list(referenceInput), datums, redeemers);
    }

    private static PlutusData contextWithRawReferenceInputs(
            PlutusData referenceInputs, PlutusData datums, PlutusData redeemers) {
        PlutusData ownRef = outRef(0);
        PlutusData txInfo = PlutusData.constr(0,
                PlutusData.list(), referenceInputs, PlutusData.list(),
                PlutusData.integer(1), PlutusData.map(), PlutusData.list(),
                PlutusData.map(), PlutusData.constr(0), PlutusData.list(),
                redeemers, datums, PlutusData.bytes(new byte[32]),
                PlutusData.map(), PlutusData.list(), PlutusData.constr(1),
                PlutusData.constr(1));
        PlutusData datum = PlutusData.constr(0);
        PlutusData redeemer = PlutusData.constr(0);
        PlutusData scriptInfo = PlutusData.constr(1, ownRef,
                PlutusData.constr(0, datum));
        return PlutusData.constr(0, txInfo, redeemer, scriptInfo);
    }

    private static PlutusData referenceInput(PlutusData output) {
        return PlutusData.constr(0, outRef(1), output);
    }

    private static PlutusData outRef(long index) {
        return PlutusData.constr(0, PlutusData.bytes(new byte[32]),
                PlutusData.integer(index));
    }

    private static PlutusData output(
            PlutusData credential, PlutusData datum, PlutusData referenceScript) {
        PlutusData address = PlutusData.constr(0, credential, PlutusData.constr(1));
        return PlutusData.constr(0, address, PlutusData.map(), datum, referenceScript);
    }

    private static PlutusData scriptCredential() {
        return PlutusData.constr(1, PlutusData.bytes(new byte[28]));
    }

    private static PlutusData inlineDatum() {
        return PlutusData.constr(2, PlutusData.integer(7));
    }

    private static PlutusData noReferenceScript() {
        return PlutusData.constr(1);
    }

    private static PlutusData witnessMap() {
        return PlutusData.map(new PlutusData.Pair(
                PlutusData.bytes(new byte[]{1}), PlutusData.integer(7)));
    }

    private static PlutusData redeemerMap() {
        return PlutusData.map(new PlutusData.Pair(
                PlutusData.constr(1, outRef(0)), PlutusData.constr(0)));
    }
}
