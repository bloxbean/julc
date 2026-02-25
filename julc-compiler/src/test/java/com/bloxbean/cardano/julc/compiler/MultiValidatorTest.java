package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for @MultiValidator support (auto-dispatch and manual dispatch modes).
 */
class MultiValidatorTest {

    static JulcVm vm;

    @BeforeAll
    static void setUp() {
        vm = JulcVm.create();
    }

    // --- Helper methods for building ScriptContext Data ---

    static PlutusData buildScriptContext(PlutusData txInfo, PlutusData redeemer, PlutusData scriptInfo) {
        return PlutusData.constr(0, txInfo, redeemer, scriptInfo);
    }

    static PlutusData buildTxInfo(PlutusData[] signatories) {
        return PlutusData.constr(0,
                PlutusData.list(),                                     // 0: inputs
                PlutusData.list(),                                     // 1: referenceInputs
                PlutusData.list(),                                     // 2: outputs
                PlutusData.integer(2000000),                           // 3: fee
                PlutusData.map(),                                      // 4: mint
                PlutusData.list(),                                     // 5: certificates
                PlutusData.map(),                                      // 6: withdrawals
                alwaysInterval(),                                      // 7: validRange
                PlutusData.list(signatories),                          // 8: signatories
                PlutusData.map(),                                      // 9: redeemers
                PlutusData.map(),                                      // 10: datums
                PlutusData.bytes(new byte[32]),                        // 11: txId
                PlutusData.map(),                                      // 12: votes
                PlutusData.list(),                                     // 13: proposalProcedures
                PlutusData.constr(1),                                  // 14: currentTreasuryAmount (None)
                PlutusData.constr(1)                                   // 15: treasuryDonation (None)
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

    /** MintingScript = Constr(0, [policyId]) */
    static PlutusData mintingScriptInfo(byte[] policyId) {
        return PlutusData.constr(0, PlutusData.bytes(policyId));
    }

    /** SpendingScript = Constr(1, [txOutRef, optionalDatum]) */
    static PlutusData spendingScriptInfo(PlutusData optDatum) {
        var txOutRef = PlutusData.constr(0,
                PlutusData.bytes(new byte[32]),
                PlutusData.integer(0));
        return PlutusData.constr(1, txOutRef, optDatum);
    }

    /** RewardingScript = Constr(2, [credential]) */
    static PlutusData rewardingScriptInfo(byte[] credHash) {
        var cred = PlutusData.constr(1, PlutusData.bytes(credHash)); // ScriptCredential
        return PlutusData.constr(2, cred);
    }

    // ==================== Mode 2: Manual Dispatch ====================

    @Test
    void compilesMultiValidatorSingleEntrypoint() {
        var source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;

                @MultiValidator
                class ProxyValidator {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var result = new JulcCompiler().compile(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
    }

    @Test
    void multiValidatorManualDispatchWithMintingContext() {
        var source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;

                @MultiValidator
                class ManualDispatch {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var ctx = buildScriptContext(
                buildTxInfo(new PlutusData[0]),
                PlutusData.integer(0),
                mintingScriptInfo(new byte[28]));
        var evalResult = vm.evaluateWithArgs(new JulcCompiler().compile(source).program(), List.of(ctx));
        assertTrue(evalResult.isSuccess(), "Manual dispatch should succeed with minting context: " + evalResult);
    }

    @Test
    void multiValidatorManualDispatchWithSpendingContext() {
        var source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;

                @MultiValidator
                class ManualDispatch2 {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var optDatum = PlutusData.constr(0, PlutusData.integer(42)); // Some(42)
        var ctx = buildScriptContext(
                buildTxInfo(new PlutusData[0]),
                PlutusData.integer(0),
                spendingScriptInfo(optDatum));
        var evalResult = vm.evaluateWithArgs(new JulcCompiler().compile(source).program(), List.of(ctx));
        assertTrue(evalResult.isSuccess(), "Manual dispatch should succeed with spending context: " + evalResult);
    }

    @Test
    void multiValidatorRejectsDefaultWith3Params() {
        var source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;

                @MultiValidator
                class BadManualDispatch {
                    @Entrypoint
                    static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        assertThrows(CompilerException.class, () -> new JulcCompiler().compile(source),
                "Manual dispatch with 3 params should fail");
    }

    // ==================== Mode 1: Auto-Dispatch ====================

    @Test
    void compilesMultiValidatorAutoDispatch() {
        var source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;
                import com.bloxbean.cardano.julc.stdlib.annotation.Purpose;

                @MultiValidator
                class AutoDispatch {
                    @Entrypoint(purpose = Purpose.MINT)
                    static boolean handleMint(PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }

                    @Entrypoint(purpose = Purpose.SPEND)
                    static boolean handleSpend(PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var result = new JulcCompiler().compile(source);
        assertNotNull(result.program());
        assertFalse(result.hasErrors());
    }

    @Test
    void autoDispatchMintingPurpose() {
        var source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;
                import com.bloxbean.cardano.julc.stdlib.annotation.Purpose;

                @MultiValidator
                class MintSpendDispatch {
                    @Entrypoint(purpose = Purpose.MINT)
                    static boolean handleMint(PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }

                    @Entrypoint(purpose = Purpose.SPEND)
                    static boolean handleSpend(PlutusData redeemer, ScriptContext ctx) {
                        return false;
                    }
                }
                """;
        var program = new JulcCompiler().compile(source).program();

        // Call with MintingScript (tag 0) — should route to handleMint and succeed
        var ctx = buildScriptContext(
                buildTxInfo(new PlutusData[0]),
                PlutusData.integer(0),
                mintingScriptInfo(new byte[28]));
        var evalResult = vm.evaluateWithArgs(program, List.of(ctx));
        assertTrue(evalResult.isSuccess(), "Auto-dispatch should route minting to handleMint: " + evalResult);
    }

    @Test
    void autoDispatchSpendingPurpose() {
        var source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;
                import com.bloxbean.cardano.julc.stdlib.annotation.Purpose;

                @MultiValidator
                class MintSpendDispatch2 {
                    @Entrypoint(purpose = Purpose.MINT)
                    static boolean handleMint(PlutusData redeemer, ScriptContext ctx) {
                        return false;
                    }

                    @Entrypoint(purpose = Purpose.SPEND)
                    static boolean handleSpend(PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var program = new JulcCompiler().compile(source).program();

        // Call with SpendingScript (tag 1) — should route to handleSpend and succeed
        var optDatum = PlutusData.constr(1); // None (no datum)
        var ctx = buildScriptContext(
                buildTxInfo(new PlutusData[0]),
                PlutusData.integer(0),
                spendingScriptInfo(optDatum));
        var evalResult = vm.evaluateWithArgs(program, List.of(ctx));
        assertTrue(evalResult.isSuccess(), "Auto-dispatch should route spending to handleSpend: " + evalResult);
    }

    @Test
    void autoDispatchSpendingWithDatum() {
        var source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;
                import com.bloxbean.cardano.julc.stdlib.annotation.Purpose;

                @MultiValidator
                class SpendWithDatum {
                    @Entrypoint(purpose = Purpose.MINT)
                    static boolean handleMint(PlutusData redeemer, ScriptContext ctx) {
                        return false;
                    }

                    @Entrypoint(purpose = Purpose.SPEND)
                    static boolean handleSpend(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var program = new JulcCompiler().compile(source).program();

        // Call with SpendingScript + datum
        var optDatum = PlutusData.constr(0, PlutusData.integer(42)); // Some(42)
        var ctx = buildScriptContext(
                buildTxInfo(new PlutusData[0]),
                PlutusData.integer(0),
                spendingScriptInfo(optDatum));
        var evalResult = vm.evaluateWithArgs(program, List.of(ctx));
        assertTrue(evalResult.isSuccess(), "3-param spend handler should extract datum and succeed: " + evalResult);
    }

    @Test
    void autoDispatchRejectsUnhandledPurpose() {
        var source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;
                import com.bloxbean.cardano.julc.stdlib.annotation.Purpose;

                @MultiValidator
                class MintOnly {
                    @Entrypoint(purpose = Purpose.MINT)
                    static boolean handleMint(PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var program = new JulcCompiler().compile(source).program();

        // Call with RewardingScript (tag 2) — no handler, should Error
        var ctx = buildScriptContext(
                buildTxInfo(new PlutusData[0]),
                PlutusData.integer(0),
                rewardingScriptInfo(new byte[28]));
        var evalResult = vm.evaluateWithArgs(program, List.of(ctx));
        assertFalse(evalResult.isSuccess(), "Unhandled purpose should Error: " + evalResult);
    }

    @Test
    void autoDispatchRejectsUnhandledPurposeMultiHandler() {
        var source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;
                import com.bloxbean.cardano.julc.stdlib.annotation.Purpose;

                @MultiValidator
                class MintSpendOnly {
                    @Entrypoint(purpose = Purpose.MINT)
                    static boolean handleMint(PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }

                    @Entrypoint(purpose = Purpose.SPEND)
                    static boolean handleSpend(PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var program = new JulcCompiler().compile(source).program();

        // Call with RewardingScript (tag 2) — no handler, should Error
        var ctx = buildScriptContext(
                buildTxInfo(new PlutusData[0]),
                PlutusData.integer(0),
                rewardingScriptInfo(new byte[28]));
        var evalResult = vm.evaluateWithArgs(program, List.of(ctx));
        assertFalse(evalResult.isSuccess(), "Unhandled purpose should Error: " + evalResult);
    }

    @Test
    void autoDispatchWithParams() {
        var source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;
                import com.bloxbean.cardano.julc.stdlib.annotation.Purpose;
                import java.math.BigInteger;

                @MultiValidator
                class ParamMulti {
                    @Param BigInteger threshold;

                    @Entrypoint(purpose = Purpose.MINT)
                    static boolean handleMint(PlutusData redeemer, ScriptContext ctx) {
                        return threshold > 0;
                    }

                    @Entrypoint(purpose = Purpose.SPEND)
                    static boolean handleSpend(PlutusData redeemer, ScriptContext ctx) {
                        return threshold > 10;
                    }
                }
                """;
        var result = new JulcCompiler().compile(source);
        assertTrue(result.isParameterized());
        assertEquals(1, result.params().size());
        assertEquals("threshold", result.params().get(0).name());

        var concrete = result.program().applyParams(PlutusData.integer(5));

        // MINT: threshold=5 > 0 → true
        var mintCtx = buildScriptContext(
                buildTxInfo(new PlutusData[0]),
                PlutusData.integer(0),
                mintingScriptInfo(new byte[28]));
        var mintResult = vm.evaluateWithArgs(concrete, List.of(mintCtx));
        assertTrue(mintResult.isSuccess(), "Param mint: threshold=5 > 0 should succeed: " + mintResult);

        // SPEND: threshold=5 > 10 → false
        var spendCtx = buildScriptContext(
                buildTxInfo(new PlutusData[0]),
                PlutusData.integer(0),
                spendingScriptInfo(PlutusData.constr(1))); // None
        var spendResult = vm.evaluateWithArgs(concrete, List.of(spendCtx));
        assertFalse(spendResult.isSuccess(), "Param spend: threshold=5 > 10 should fail: " + spendResult);
    }

    @Test
    void autoDispatchWithHelperMethod() {
        var source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;
                import com.bloxbean.cardano.julc.stdlib.annotation.Purpose;

                @MultiValidator
                class HelperMulti {
                    static boolean alwaysTrue() {
                        return true;
                    }

                    @Entrypoint(purpose = Purpose.MINT)
                    static boolean handleMint(PlutusData redeemer, ScriptContext ctx) {
                        return alwaysTrue();
                    }

                    @Entrypoint(purpose = Purpose.WITHDRAW)
                    static boolean handleWithdraw(PlutusData redeemer, ScriptContext ctx) {
                        return alwaysTrue();
                    }
                }
                """;
        var program = new JulcCompiler().compile(source).program();

        // WITHDRAW (tag 2) should route to handleWithdraw
        var ctx = buildScriptContext(
                buildTxInfo(new PlutusData[0]),
                PlutusData.integer(0),
                rewardingScriptInfo(new byte[28]));
        var evalResult = vm.evaluateWithArgs(program, List.of(ctx));
        assertTrue(evalResult.isSuccess(), "Helper method should work in auto-dispatch: " + evalResult);
    }

    // ==================== Optional Datum Tests ====================

    @Test
    void spendingWithOptionalDatumSome() {
        var source = """
                import java.util.Optional;

                @SpendingValidator
                class OptionalDatumSome {
                    @Entrypoint
                    static boolean validate(Optional<PlutusData> datum, PlutusData redeemer, ScriptContext ctx) {
                        if (datum.isPresent()) {
                            return true;
                        }
                        return false;
                    }
                }
                """;
        var program = new JulcCompiler().compile(source).program();

        // Some(42) — isPresent() should return true
        var optDatum = PlutusData.constr(0, PlutusData.integer(42));
        var ctx = buildScriptContext(
                buildTxInfo(new PlutusData[0]),
                PlutusData.integer(0),
                spendingScriptInfo(optDatum));
        var evalResult = vm.evaluateWithArgs(program, List.of(ctx));
        assertTrue(evalResult.isSuccess(), "Optional datum Some should succeed: " + evalResult);
    }

    @Test
    void spendingWithOptionalDatumNone() {
        var source = """
                import java.util.Optional;

                @SpendingValidator
                class OptionalDatumNone {
                    @Entrypoint
                    static boolean validate(Optional<PlutusData> datum, PlutusData redeemer, ScriptContext ctx) {
                        if (datum.isEmpty()) {
                            return true;
                        }
                        return false;
                    }
                }
                """;
        var program = new JulcCompiler().compile(source).program();

        // None — isEmpty() should return true
        var optDatum = PlutusData.constr(1);
        var ctx = buildScriptContext(
                buildTxInfo(new PlutusData[0]),
                PlutusData.integer(0),
                spendingScriptInfo(optDatum));
        var evalResult = vm.evaluateWithArgs(program, List.of(ctx));
        assertTrue(evalResult.isSuccess(), "Optional datum None should succeed: " + evalResult);
    }

    @Test
    void autoDispatchSpendWithOptionalDatum() {
        var source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;
                import com.bloxbean.cardano.julc.stdlib.annotation.Purpose;
                import java.util.Optional;

                @MultiValidator
                class OptionalDatumMulti {
                    @Entrypoint(purpose = Purpose.MINT)
                    static boolean handleMint(PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }

                    @Entrypoint(purpose = Purpose.SPEND)
                    static boolean handleSpend(Optional<PlutusData> datum, PlutusData redeemer, ScriptContext ctx) {
                        if (datum.isPresent()) {
                            return true;
                        }
                        return false;
                    }
                }
                """;
        var program = new JulcCompiler().compile(source).program();

        // SPEND with Some(42) — should succeed
        var optDatumSome = PlutusData.constr(0, PlutusData.integer(42));
        var ctxSome = buildScriptContext(
                buildTxInfo(new PlutusData[0]),
                PlutusData.integer(0),
                spendingScriptInfo(optDatumSome));
        var resultSome = vm.evaluateWithArgs(program, List.of(ctxSome));
        assertTrue(resultSome.isSuccess(), "Auto-dispatch Optional datum Some should succeed: " + resultSome);

        // SPEND with None — should fail (returns false)
        var optDatumNone = PlutusData.constr(1);
        var ctxNone = buildScriptContext(
                buildTxInfo(new PlutusData[0]),
                PlutusData.integer(0),
                spendingScriptInfo(optDatumNone));
        var resultNone = vm.evaluateWithArgs(program, List.of(ctxNone));
        assertFalse(resultNone.isSuccess(), "Auto-dispatch Optional datum None should fail: " + resultNone);
    }

    @Test
    void spendingWithOptionalCustomDatumRecord() {
        var source = """
                import java.util.Optional;
                import java.math.BigInteger;

                @SpendingValidator
                class OptionalCustomDatum {
                    record MyDatum(BigInteger amount) {}

                    @Entrypoint
                    static boolean validate(Optional<MyDatum> datum, PlutusData redeemer, ScriptContext ctx) {
                        if (datum.isPresent()) {
                            MyDatum d = datum.get();
                            return d.amount() > 0;
                        }
                        return false;
                    }
                }
                """;
        var program = new JulcCompiler().compile(source).program();

        // Some(MyDatum(amount=100)) — Constr(0, [Constr(0, [IData(100)])])
        var myDatum = PlutusData.constr(0, PlutusData.integer(100));
        var optDatumSome = PlutusData.constr(0, myDatum);
        var ctxSome = buildScriptContext(
                buildTxInfo(new PlutusData[0]),
                PlutusData.integer(0),
                spendingScriptInfo(optDatumSome));
        var resultSome = vm.evaluateWithArgs(program, List.of(ctxSome));
        assertTrue(resultSome.isSuccess(), "Optional custom datum Some with amount>0 should succeed: " + resultSome);

        // Some(MyDatum(amount=0)) — should fail (amount not > 0)
        var myDatumZero = PlutusData.constr(0, PlutusData.integer(0));
        var optDatumZero = PlutusData.constr(0, myDatumZero);
        var ctxZero = buildScriptContext(
                buildTxInfo(new PlutusData[0]),
                PlutusData.integer(0),
                spendingScriptInfo(optDatumZero));
        var resultZero = vm.evaluateWithArgs(program, List.of(ctxZero));
        assertFalse(resultZero.isSuccess(), "Optional custom datum Some with amount=0 should fail: " + resultZero);

        // None — should fail (datum not present)
        var optDatumNone = PlutusData.constr(1);
        var ctxNone = buildScriptContext(
                buildTxInfo(new PlutusData[0]),
                PlutusData.integer(0),
                spendingScriptInfo(optDatumNone));
        var resultNone = vm.evaluateWithArgs(program, List.of(ctxNone));
        assertFalse(resultNone.isSuccess(), "Optional custom datum None should fail: " + resultNone);
    }

    // ==================== Validation / Error Tests ====================

    @Test
    void rejectsDuplicatePurpose() {
        var source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;
                import com.bloxbean.cardano.julc.stdlib.annotation.Purpose;

                @MultiValidator
                class DupPurpose {
                    @Entrypoint(purpose = Purpose.MINT)
                    static boolean handleMint1(PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }

                    @Entrypoint(purpose = Purpose.MINT)
                    static boolean handleMint2(PlutusData redeemer, ScriptContext ctx) {
                        return false;
                    }
                }
                """;
        var ex = assertThrows(CompilerException.class, () -> new JulcCompiler().compile(source));
        assertTrue(ex.getMessage().contains("Duplicate purpose"), "Error should mention duplicate: " + ex.getMessage());
    }

    @Test
    void rejectsMixedDefaultAndExplicit() {
        var source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;
                import com.bloxbean.cardano.julc.stdlib.annotation.Purpose;

                @MultiValidator
                class MixedPurposes {
                    @Entrypoint
                    static boolean handleDefault(PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }

                    @Entrypoint(purpose = Purpose.MINT)
                    static boolean handleMint(PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var ex = assertThrows(CompilerException.class, () -> new JulcCompiler().compile(source));
        assertTrue(ex.getMessage().contains("mixes"), "Error should mention mixing: " + ex.getMessage());
    }

    @Test
    void duplicateAnnotationsRejected() {
        var source = """
                @SpendingValidator
                @MintingValidator
                class DualAnnotation {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var ex = assertThrows(CompilerException.class, () -> new JulcCompiler().compile(source));
        assertTrue(ex.getMessage().contains("multiple validator annotations"),
                "Error should mention multiple annotations: " + ex.getMessage());
    }
}
