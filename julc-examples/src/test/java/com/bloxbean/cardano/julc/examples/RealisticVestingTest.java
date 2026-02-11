package com.bloxbean.cardano.julc.examples;

import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.testkit.BudgetAssertions;
import com.bloxbean.cardano.julc.testkit.TestDataBuilder;
import com.bloxbean.cardano.julc.testkit.ValidatorTest;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Realistic vesting validator using stdlib calls and proper ScriptContext.
 * <p>
 * Demonstrates:
 * - Record types for datum
 * - Record field access compiled to Data navigation
 * - ContextsLib.getTxInfo and ContextsLib.signedBy stdlib calls
 * - 3-param spending validator with datum from ScriptInfo
 * - Proper V3 ScriptContext construction
 */
class RealisticVestingTest {

    /**
     * A realistic vesting validator.
     * <p>
     * The datum contains a beneficiary PubKeyHash and a deadline.
     * The validator checks that the transaction is signed by the beneficiary.
     * Uses ContextsLib.getTxInfo and ContextsLib.signedBy.
     */
    static final String VESTING_SOURCE = """
            @Validator
            class VestingValidator {
                record VestingDatum(PlutusData beneficiary, PlutusData deadline) {}

                @Entrypoint
                static boolean validate(VestingDatum datum, PlutusData redeemer, PlutusData ctx) {
                    PlutusData txInfo = ContextsLib.getTxInfo(ctx);
                    PlutusData pkh = datum.beneficiary();
                    return ContextsLib.signedBy(txInfo, pkh);
                }
            }
            """;

    /**
     * A simpler 2-param minting-style validator that uses signedBy.
     * The redeemer is a PubKeyHash, and the validator checks that
     * the tx is signed by that key.
     */
    static final String SIGNED_BY_SOURCE = """
            @Validator
            class SignedByValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    PlutusData txInfo = ContextsLib.getTxInfo(ctx);
                    return ContextsLib.signedBy(txInfo, redeemer);
                }
            }
            """;

    static JulcVm vm;
    static StdlibRegistry stdlib;

    @BeforeAll
    static void setUp() {
        vm = JulcVm.create();
        stdlib = StdlibRegistry.defaultRegistry();
    }

    private Program compileWithStdlib(String source) {
        return ValidatorTest.compile(source, stdlib::lookup);
    }

    /**
     * Build a V3 ScriptContext for spending with signatories list.
     * <p>
     * ScriptContext = Constr(0, [txInfo, redeemer, scriptInfo])
     * TxInfo = Constr(0, [field0..field7, signatories, field9..field15])
     * Spending ScriptInfo = Constr(1, [txOutRef, optionalDatum])
     */
    private PlutusData buildSpendingCtx(PlutusData datum, PlutusData redeemer,
                                         PlutusData... signatories) {
        // Build TxInfo with signatories at field 8
        var sigsList = PlutusData.list(signatories);
        var zero = PlutusData.integer(0);
        // TxInfo fields: 0-7 placeholders, 8=signatories, 9-15 placeholders
        var txInfo = PlutusData.constr(0,
                zero, zero, zero, zero, zero, zero, zero, zero,
                sigsList,
                zero, zero, zero, zero, zero, zero, zero);

        // Spending ScriptInfo: Constr(1, [txOutRef, optionalDatum])
        var txOutRef = TestDataBuilder.randomTxOutRef();
        // Optional datum: Constr(0, [datum]) for Some
        var optDatum = PlutusData.constr(0, datum);
        var scriptInfo = PlutusData.constr(1, txOutRef, optDatum);

        return PlutusData.constr(0, txInfo, redeemer, scriptInfo);
    }

    /**
     * Build a V3 ScriptContext for a 2-param validator with signatories.
     */
    private PlutusData build2ParamCtx(PlutusData redeemer, PlutusData... signatories) {
        var sigsList = PlutusData.list(signatories);
        var zero = PlutusData.integer(0);
        var txInfo = PlutusData.constr(0,
                zero, zero, zero, zero, zero, zero, zero, zero,
                sigsList,
                zero, zero, zero, zero, zero, zero, zero);
        var scriptInfo = PlutusData.integer(0);
        return PlutusData.constr(0, txInfo, redeemer, scriptInfo);
    }

    // ---- Vesting Validator Tests ----

    @Test
    void compilesVestingValidator() {
        var program = compileWithStdlib(VESTING_SOURCE);
        assertNotNull(program);
        assertEquals(1, program.major());
        assertEquals(1, program.minor());
    }

    @Test
    void beneficiaryCanUnlock() {
        var program = compileWithStdlib(VESTING_SOURCE);
        var beneficiaryPkh = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
                11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28};

        // Datum: VestingDatum(beneficiary=BytesData, deadline=IData(1000))
        var datum = PlutusData.constr(0,
                PlutusData.bytes(beneficiaryPkh),
                PlutusData.integer(1000));

        var ctx = buildSpendingCtx(datum, PlutusData.integer(0),
                PlutusData.bytes(beneficiaryPkh));  // signer matches beneficiary

        var result = vm.evaluateWithArgs(program, List.of(ctx));
        assertTrue(result.isSuccess(), "Beneficiary should be able to unlock: " + result);
    }

    @Test
    void nonBeneficiaryRejected() {
        var program = compileWithStdlib(VESTING_SOURCE);
        var beneficiaryPkh = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
                11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28};
        var attackerPkh = new byte[]{99, 98, 97, 96, 95, 94, 93, 92, 91, 90,
                89, 88, 87, 86, 85, 84, 83, 82, 81, 80, 79, 78, 77, 76, 75, 74, 73, 72};

        var datum = PlutusData.constr(0,
                PlutusData.bytes(beneficiaryPkh),
                PlutusData.integer(1000));

        var ctx = buildSpendingCtx(datum, PlutusData.integer(0),
                PlutusData.bytes(attackerPkh));  // signer doesn't match

        var result = vm.evaluateWithArgs(program, List.of(ctx));
        assertFalse(result.isSuccess(), "Non-beneficiary should be rejected: " + result);
    }

    @Test
    void noSignatoryRejected() {
        var program = compileWithStdlib(VESTING_SOURCE);
        var beneficiaryPkh = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
                11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28};

        var datum = PlutusData.constr(0,
                PlutusData.bytes(beneficiaryPkh),
                PlutusData.integer(1000));

        // No signatories at all
        var ctx = buildSpendingCtx(datum, PlutusData.integer(0));

        var result = vm.evaluateWithArgs(program, List.of(ctx));
        assertFalse(result.isSuccess(), "No signatures should cause rejection: " + result);
    }

    @Test
    void vestingBudgetIsReasonable() {
        var program = compileWithStdlib(VESTING_SOURCE);
        var pkh = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
                11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28};
        var datum = PlutusData.constr(0, PlutusData.bytes(pkh), PlutusData.integer(1000));
        var ctx = buildSpendingCtx(datum, PlutusData.integer(0), PlutusData.bytes(pkh));
        var result = vm.evaluateWithArgs(program, List.of(ctx));
        BudgetAssertions.assertBudgetUnder(result, 10_000_000_000L, 10_000_000_000L);
    }

    // ---- SignedBy Validator Tests (2-param) ----

    @Test
    void compilesSignedByValidator() {
        var program = compileWithStdlib(SIGNED_BY_SOURCE);
        assertNotNull(program);
    }

    @Test
    void signedByAuthorizedSignerAccepts() {
        var program = compileWithStdlib(SIGNED_BY_SOURCE);
        var pkh = new byte[]{10, 20, 30, 40, 50, 60, 70, 80, 90, 100,
                110, 120, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26};

        // Redeemer is the PubKeyHash to check
        var ctx = build2ParamCtx(PlutusData.bytes(pkh), PlutusData.bytes(pkh));
        var result = vm.evaluateWithArgs(program, List.of(ctx));
        assertTrue(result.isSuccess(), "Authorized signer should succeed: " + result);
    }

    @Test
    void signedByUnauthorizedSignerRejects() {
        var program = compileWithStdlib(SIGNED_BY_SOURCE);
        var redeemer = new byte[]{10, 20, 30, 40, 50, 60, 70, 80, 90, 100,
                110, 120, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26};
        var otherSigner = new byte[]{99, 98, 97, 96, 95, 94, 93, 92, 91, 90,
                89, 88, 87, 86, 85, 84, 83, 82, 81, 80, 79, 78, 77, 76, 75, 74, 73, 72};

        var ctx = build2ParamCtx(PlutusData.bytes(redeemer), PlutusData.bytes(otherSigner));
        var result = vm.evaluateWithArgs(program, List.of(ctx));
        assertFalse(result.isSuccess(), "Unauthorized signer should be rejected: " + result);
    }
}
