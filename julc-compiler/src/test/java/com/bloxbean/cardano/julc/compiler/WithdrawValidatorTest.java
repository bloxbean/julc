package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for @WithdrawValidator with proper RewardingScript ScriptInfo context.
 */
class WithdrawValidatorTest {

    static JulcVm vm;

    @BeforeAll
    static void setUp() {
        vm = JulcVm.create();
    }

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

    static PlutusData buildTxInfoWithWithdrawals(PlutusData[] signatories, PlutusData withdrawals) {
        return PlutusData.constr(0,
                PlutusData.list(),                                     // 0: inputs
                PlutusData.list(),                                     // 1: referenceInputs
                PlutusData.list(),                                     // 2: outputs
                PlutusData.integer(2000000),                           // 3: fee
                PlutusData.map(),                                      // 4: mint
                PlutusData.list(),                                     // 5: certificates
                withdrawals,                                           // 6: withdrawals
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

    /** RewardingScript = Constr(2, [credential]) */
    static PlutusData rewardingScriptInfo(byte[] credHash) {
        // ScriptCredential = Constr(1, [hash])
        var cred = PlutusData.constr(1, PlutusData.bytes(credHash));
        return PlutusData.constr(2, cred);
    }

    @Test
    void compilesAndEvaluatesWithdrawValidator() {
        var source = """
                @WithdrawValidator
                class SimpleWithdraw {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var program = new JulcCompiler().compile(source).program();
        assertNotNull(program);

        var ctx = buildScriptContext(
                buildTxInfo(new PlutusData[0]),
                PlutusData.integer(0),
                rewardingScriptInfo(new byte[28]));
        var evalResult = vm.evaluateWithArgs(program, List.of(ctx));
        assertTrue(evalResult.isSuccess(), "Simple withdraw validator should succeed: " + evalResult);
    }

    @Test
    void withdrawValidatorChecksSigner() {
        var source = """
                @WithdrawValidator
                class SignerWithdraw {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        var signatories = ctx.txInfo().signatories();
                        return !signatories.isEmpty();
                    }
                }
                """;
        var program = new JulcCompiler().compile(source).program();

        // With signers present
        var signer = PlutusData.bytes(new byte[28]);
        var ctx = buildScriptContext(
                buildTxInfo(new PlutusData[]{signer}),
                PlutusData.integer(0),
                rewardingScriptInfo(new byte[28]));
        var evalResult = vm.evaluateWithArgs(program, List.of(ctx));
        assertTrue(evalResult.isSuccess(), "Should succeed with signer present: " + evalResult);

        // Without signers
        var ctxNoSigner = buildScriptContext(
                buildTxInfo(new PlutusData[0]),
                PlutusData.integer(0),
                rewardingScriptInfo(new byte[28]));
        var evalResultFail = vm.evaluateWithArgs(program, List.of(ctxNoSigner));
        assertFalse(evalResultFail.isSuccess(), "Should fail without signers: " + evalResultFail);
    }

    @Test
    void withdrawValidatorRejectsThreeParams() {
        var source = """
                @WithdrawValidator
                class BadWithdraw {
                    @Entrypoint
                    static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var ex = assertThrows(CompilerException.class, () -> new JulcCompiler().compile(source));
        assertTrue(ex.getMessage().contains("2 parameters"),
                "Should reject 3-param withdraw validator: " + ex.getMessage());
    }
}
