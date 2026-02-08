package com.bloxbean.cardano.plutus.examples;

import com.bloxbean.cardano.plutus.core.PlutusData;
import com.bloxbean.cardano.plutus.core.Program;
import com.bloxbean.cardano.plutus.stdlib.StdlibRegistry;
import com.bloxbean.cardano.plutus.testkit.ValidatorTest;
import com.bloxbean.cardano.plutus.vm.PlutusVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Output value check examples demonstrating:
 * - Head-based output access (first output check)
 * - Accumulator-based for-each (boolean fold: any output meets criteria)
 * - Accumulator-based for-each (integer fold: sum of output lovelace)
 * - Typed ledger access (ScriptContext, TxInfo, TxOut, Value)
 * - ValuesLib.lovelaceOf stdlib call
 */
class OutputValueCheckTest {

    // ---- Validator A: Check first output has minimum ADA ----

    static final String MIN_PAYMENT_SOURCE = """
            import java.math.BigInteger;

            @Validator
            class MinPaymentValidator {
                record MinPayment(BigInteger minLovelace) {}

                @Entrypoint
                static boolean validate(MinPayment datum, PlutusData redeemer, ScriptContext ctx) {
                    TxInfo txInfo = ctx.txInfo();
                    var firstOutput = txInfo.outputs().head();
                    BigInteger lovelace = ValuesLib.lovelaceOf(firstOutput.value());
                    return lovelace >= datum.minLovelace();
                }
            }
            """;

    // ---- Validator B: Any output has enough ADA (boolean accumulator) ----

    static final String ANY_OUTPUT_SOURCE = """
            import java.math.BigInteger;

            @Validator
            class AnyOutputValidator {
                record MinPayment(BigInteger minLovelace) {}

                @Entrypoint
                static boolean validate(MinPayment datum, PlutusData redeemer, ScriptContext ctx) {
                    TxInfo txInfo = ctx.txInfo();
                    boolean found = false;
                    for (var output : txInfo.outputs()) {
                        BigInteger lovelace = ValuesLib.lovelaceOf(output.value());
                        found = found || lovelace >= datum.minLovelace();
                    }
                    return found;
                }
            }
            """;

    // ---- Validator C: Sum output lovelace (integer accumulator) ----

    static final String SUM_OUTPUTS_SOURCE = """
            import java.math.BigInteger;

            @Validator
            class SumOutputsValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    TxInfo txInfo = ctx.txInfo();
                    BigInteger total = 0;
                    for (var output : txInfo.outputs()) {
                        total = total + ValuesLib.lovelaceOf(output.value());
                    }
                    return total >= 10000000;
                }
            }
            """;

    static PlutusVm vm;
    static StdlibRegistry stdlib;

    @BeforeAll
    static void setUp() {
        vm = PlutusVm.create();
        stdlib = StdlibRegistry.defaultRegistry();
    }

    private Program compileWithStdlib(String source) {
        return ValidatorTest.compile(source, stdlib::lookup);
    }

    // ---- Test data builders ----

    /**
     * Build a TxOut as PlutusData with the given lovelace amount.
     * TxOut = Constr(0, [address, value, datum, referenceScript])
     */
    static PlutusData buildTxOut(long lovelaceAmount) {
        // Address = Constr(0, [credential, stakingCredential])
        // PubKeyCredential = Constr(0, [hash])
        var credential = PlutusData.constr(0, PlutusData.bytes(new byte[28]));
        var address = PlutusData.constr(0, credential, PlutusData.constr(1));

        // Value = Map[ Pair(B"", Map[ Pair(B"", I(amount)) ]) ]
        var value = PlutusData.map(
                new PlutusData.Pair(PlutusData.bytes(new byte[0]),
                        PlutusData.map(new PlutusData.Pair(
                                PlutusData.bytes(new byte[0]),
                                PlutusData.integer(lovelaceAmount)))));

        // OutputDatum: NoOutputDatum = Constr(0, [])
        var datum = PlutusData.constr(0);

        // referenceScript: None = Constr(1, [])
        var refScript = PlutusData.constr(1);

        return PlutusData.constr(0, address, value, datum, refScript);
    }

    /**
     * Build a full TxInfo with the given outputs at field index 2.
     * All 16 fields present for correct indexing.
     */
    static PlutusData buildTxInfoWithOutputs(PlutusData... outputs) {
        var outputsList = PlutusData.list(outputs);
        return PlutusData.constr(0,
                PlutusData.list(),                          // 0: inputs
                PlutusData.list(),                          // 1: referenceInputs
                outputsList,                                // 2: outputs
                PlutusData.integer(2000000),                // 3: fee
                PlutusData.map(),                           // 4: mint
                PlutusData.list(),                          // 5: certificates
                PlutusData.map(),                           // 6: withdrawals
                alwaysInterval(),                           // 7: validRange
                PlutusData.list(),                          // 8: signatories
                PlutusData.map(),                           // 9: redeemers
                PlutusData.map(),                           // 10: datums
                PlutusData.bytes(new byte[32]),             // 11: txId
                PlutusData.map(),                           // 12: votes
                PlutusData.list(),                          // 13: proposalProcedures
                PlutusData.constr(1),                       // 14: currentTreasuryAmount (None)
                PlutusData.constr(1)                        // 15: treasuryDonation (None)
        );
    }

    static PlutusData alwaysInterval() {
        var negInf = PlutusData.constr(0);
        var posInf = PlutusData.constr(2);
        var trueVal = PlutusData.constr(1);
        var lowerBound = PlutusData.constr(0, negInf, trueVal);
        var upperBound = PlutusData.constr(0, posInf, trueVal);
        return PlutusData.constr(0, lowerBound, upperBound);
    }

    /**
     * Build a spending ScriptContext with datum and outputs.
     */
    PlutusData buildSpendingCtx(PlutusData datum, PlutusData txInfo) {
        var txOutRef = PlutusData.constr(0,
                PlutusData.bytes(new byte[32]),
                PlutusData.integer(0));
        var optDatum = PlutusData.constr(0, datum); // Some(datum)
        var scriptInfo = PlutusData.constr(1, txOutRef, optDatum); // SpendingScript
        return PlutusData.constr(0, txInfo, PlutusData.integer(0), scriptInfo);
    }

    /**
     * Build a 2-param ScriptContext (no datum extraction from ScriptInfo).
     */
    PlutusData build2ParamCtx(PlutusData txInfo) {
        var scriptInfo = PlutusData.constr(0, PlutusData.bytes(new byte[28])); // MintingScript
        return PlutusData.constr(0, txInfo, PlutusData.integer(0), scriptInfo);
    }

    // ---- Validator A tests: head-based ----

    @Test
    void firstOutputMeetsMinPayment() {
        var program = compileWithStdlib(MIN_PAYMENT_SOURCE);
        // datum: MinPayment(minLovelace = 3_000_000)
        var datum = PlutusData.constr(0, PlutusData.integer(3_000_000));
        var txInfo = buildTxInfoWithOutputs(buildTxOut(5_000_000));
        var ctx = buildSpendingCtx(datum, txInfo);
        var result = vm.evaluateWithArgs(program, List.of(ctx));
        assertTrue(result.isSuccess(), "5 ADA >= 3 ADA should pass. Got: " + result);
    }

    @Test
    void firstOutputBelowMinPayment() {
        var program = compileWithStdlib(MIN_PAYMENT_SOURCE);
        var datum = PlutusData.constr(0, PlutusData.integer(3_000_000));
        var txInfo = buildTxInfoWithOutputs(buildTxOut(1_000_000));
        var ctx = buildSpendingCtx(datum, txInfo);
        var result = vm.evaluateWithArgs(program, List.of(ctx));
        assertFalse(result.isSuccess(), "1 ADA < 3 ADA should fail. Got: " + result);
    }

    // ---- Validator B tests: boolean accumulator ----

    @Test
    void anyOutputHasEnoughAda() {
        var program = compileWithStdlib(ANY_OUTPUT_SOURCE);
        var datum = PlutusData.constr(0, PlutusData.integer(3_000_000));
        var txInfo = buildTxInfoWithOutputs(
                buildTxOut(1_000_000),  // 1 ADA — not enough
                buildTxOut(5_000_000)); // 5 ADA — enough
        var ctx = buildSpendingCtx(datum, txInfo);
        var result = vm.evaluateWithArgs(program, List.of(ctx));
        assertTrue(result.isSuccess(), "At least one output >= 3 ADA should pass. Got: " + result);
    }

    @Test
    void noOutputHasEnoughAda() {
        var program = compileWithStdlib(ANY_OUTPUT_SOURCE);
        var datum = PlutusData.constr(0, PlutusData.integer(3_000_000));
        var txInfo = buildTxInfoWithOutputs(
                buildTxOut(1_000_000),  // 1 ADA
                buildTxOut(2_000_000)); // 2 ADA
        var ctx = buildSpendingCtx(datum, txInfo);
        var result = vm.evaluateWithArgs(program, List.of(ctx));
        assertFalse(result.isSuccess(), "No output >= 3 ADA should fail. Got: " + result);
    }

    // ---- Validator C tests: integer accumulator ----

    @Test
    void sumOutputsAboveThreshold() {
        var program = compileWithStdlib(SUM_OUTPUTS_SOURCE);
        var txInfo = buildTxInfoWithOutputs(
                buildTxOut(3_000_000),  // 3 ADA
                buildTxOut(5_000_000),  // 5 ADA
                buildTxOut(4_000_000)); // 4 ADA → total 12 ADA
        var ctx = build2ParamCtx(txInfo);
        var result = vm.evaluateWithArgs(program, List.of(ctx));
        assertTrue(result.isSuccess(), "Total 12 ADA >= 10 ADA should pass. Got: " + result);
    }

    @Test
    void sumOutputsBelowThreshold() {
        var program = compileWithStdlib(SUM_OUTPUTS_SOURCE);
        var txInfo = buildTxInfoWithOutputs(
                buildTxOut(1_000_000),  // 1 ADA
                buildTxOut(2_000_000)); // 2 ADA → total 3 ADA
        var ctx = build2ParamCtx(txInfo);
        var result = vm.evaluateWithArgs(program, List.of(ctx));
        assertFalse(result.isSuccess(), "Total 3 ADA < 10 ADA should fail. Got: " + result);
    }
}
