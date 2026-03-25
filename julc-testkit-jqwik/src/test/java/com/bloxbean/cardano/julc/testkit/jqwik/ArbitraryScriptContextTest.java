package com.bloxbean.cardano.julc.testkit.jqwik;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.*;
import net.jqwik.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ArbitraryScriptContext} — verifies consistency of generated ScriptContexts.
 */
class ArbitraryScriptContextTest {

    // --- Spending context tests ---

    @Property(tries = 50)
    void spendingContextHasSpentRefInInputs(@ForAll("spendingCtx") ScriptContext ctx) {
        var scriptInfo = ctx.scriptInfo();
        assertInstanceOf(ScriptInfo.SpendingScript.class, scriptInfo);
        var spendingScript = (ScriptInfo.SpendingScript) scriptInfo;
        var spentRef = spendingScript.txOutRef();

        boolean found = false;
        for (int i = 0; i < ctx.txInfo().inputs().size(); i++) {
            var input = ctx.txInfo().inputs().get(i);
            if (input.outRef().equals(spentRef)) {
                found = true;
                break;
            }
        }
        assertTrue(found, "Spent TxOutRef must appear in inputs list");
    }

    @Provide
    Arbitrary<ScriptContext> spendingCtx() {
        return ArbitraryScriptContext.spending()
                .signers(1, 3)
                .inputs(1, 5)
                .outputs(1, 3)
                .build();
    }

    @Property(tries = 50)
    void spendingContextHasSignatories(@ForAll("spendingCtx") ScriptContext ctx) {
        var signatories = ctx.txInfo().signatories();
        assertTrue(signatories.size() >= 1);
        assertTrue(signatories.size() <= 3);
    }

    @Property(tries = 50)
    void spendingContextConvertibleToPlutusData(@ForAll("spendingCtx") ScriptContext ctx) {
        PlutusData pd = ctx.toPlutusData();
        assertNotNull(pd);
        assertInstanceOf(PlutusData.ConstrData.class, pd);
    }

    @Property(tries = 50)
    void spendingContextHasOutputs(@ForAll("spendingCtx") ScriptContext ctx) {
        var outputs = ctx.txInfo().outputs();
        assertFalse(outputs.isEmpty(), "Spending context should have at least one output");
    }

    // --- Minting context tests ---

    @Property(tries = 50)
    void mintingContextHasPolicyInScriptInfo(@ForAll("mintingCtx") ScriptContext ctx) {
        var scriptInfo = ctx.scriptInfo();
        assertInstanceOf(ScriptInfo.MintingScript.class, scriptInfo);
        var mintingScript = (ScriptInfo.MintingScript) scriptInfo;
        assertNotNull(mintingScript.policyId());
        assertEquals(28, mintingScript.policyId().hash().length);
    }

    @Provide
    Arbitrary<ScriptContext> mintingCtx() {
        return ArbitraryScriptContext.minting()
                .signers(1, 2)
                .inputs(1, 3)
                .outputs(1, 2)
                .build();
    }

    @Property(tries = 50)
    void mintingContextHasNonZeroMint(@ForAll("mintingCtx") ScriptContext ctx) {
        assertFalse(ctx.txInfo().mint().isEmpty(),
                "Minting context should have non-zero mint value");
    }

    // --- Rewarding context tests ---

    @Property(tries = 50)
    void rewardingContextHasRewardingScriptInfo(@ForAll("rewardingCtx") ScriptContext ctx) {
        assertInstanceOf(ScriptInfo.RewardingScript.class, ctx.scriptInfo());
    }

    @Provide
    Arbitrary<ScriptContext> rewardingCtx() {
        return ArbitraryScriptContext.rewarding()
                .signers(1, 2)
                .inputs(1, 2)
                .outputs(1, 2)
                .build();
    }

    // --- Custom datum/redeemer ---

    @Property(tries = 30)
    void customDatumAppearsInScriptInfo(@ForAll("spendingCtxWithCustomDatum") ScriptContext ctx) {
        var scriptInfo = (ScriptInfo.SpendingScript) ctx.scriptInfo();
        assertTrue(scriptInfo.datum().isPresent());
        var datum = scriptInfo.datum().get();
        // Our custom datum is IntData in range [42..100]
        assertInstanceOf(PlutusData.IntData.class, datum);
        var val = ((PlutusData.IntData) datum).value();
        assertTrue(val.intValue() >= 42 && val.intValue() <= 100,
                "Custom datum should be in range [42, 100], got: " + val);
    }

    @Provide
    Arbitrary<ScriptContext> spendingCtxWithCustomDatum() {
        return ArbitraryScriptContext.spending()
                .withDatum(CardanoArbitraries.intData(
                        java.math.BigInteger.valueOf(42),
                        java.math.BigInteger.valueOf(100)))
                .build();
    }

    // --- PlutusData output mode ---

    @Property(tries = 30)
    void buildAsPlutusDataProducesConstrData(@ForAll("spendingPlutusData") PlutusData pd) {
        assertInstanceOf(PlutusData.ConstrData.class, pd);
        var constr = (PlutusData.ConstrData) pd;
        assertEquals(0, constr.tag(), "ScriptContext should be Constr(0, ...)");
        assertEquals(3, constr.fields().size(), "ScriptContext should have 3 fields");
    }

    @Provide
    Arbitrary<PlutusData> spendingPlutusData() {
        return ArbitraryScriptContext.spending().buildAsPlutusData();
    }

    // --- Fee range ---

    @Property(tries = 50)
    void feeIsInRange(@ForAll("customFeeCtx") ScriptContext ctx) {
        var fee = ctx.txInfo().fee();
        assertTrue(fee.compareTo(java.math.BigInteger.valueOf(200_000)) >= 0);
        assertTrue(fee.compareTo(java.math.BigInteger.valueOf(500_000)) <= 0);
    }

    @Provide
    Arbitrary<ScriptContext> customFeeCtx() {
        return ArbitraryScriptContext.spending()
                .fee(200_000, 500_000)
                .build();
    }

    // --- Minting PolicyId cross-field consistency ---

    @Property(tries = 50)
    void mintingPolicyIdMatchesMintValue(@ForAll("mintingCtx") ScriptContext ctx) {
        var scriptInfo = (ScriptInfo.MintingScript) ctx.scriptInfo();
        var policyId = scriptInfo.policyId();
        assertTrue(ctx.txInfo().mint().containsPolicy(policyId),
                "Mint value should contain the minting PolicyId");
    }

    // --- Custom redeemer ---

    @Property(tries = 30)
    void customRedeemerAppearsInContext(@ForAll("ctxWithRedeemer") ScriptContext ctx) {
        var redeemer = ctx.redeemer();
        assertInstanceOf(PlutusData.IntData.class, redeemer);
        var val = ((PlutusData.IntData) redeemer).value();
        assertTrue(val.intValue() >= 100 && val.intValue() <= 200,
                "Custom redeemer should be in range [100, 200], got: " + val);
    }

    @Provide
    Arbitrary<ScriptContext> ctxWithRedeemer() {
        return ArbitraryScriptContext.spending()
                .withRedeemer(CardanoArbitraries.intData(
                        java.math.BigInteger.valueOf(100),
                        java.math.BigInteger.valueOf(200)))
                .build();
    }

    // --- Custom valid range ---

    @Property(tries = 30)
    void customValidRangeAppearsInContext(@ForAll("ctxWithRange") ScriptContext ctx) {
        var range = ctx.txInfo().validRange();
        assertNotNull(range);
        // We configured to always use Interval.never()
        // Never = (+inf lower, -inf upper)
        assertInstanceOf(com.bloxbean.cardano.julc.ledger.IntervalBoundType.PosInf.class,
                range.from().boundType());
        assertInstanceOf(com.bloxbean.cardano.julc.ledger.IntervalBoundType.NegInf.class,
                range.to().boundType());
    }

    @Provide
    Arbitrary<ScriptContext> ctxWithRange() {
        return ArbitraryScriptContext.spending()
                .withValidRange(net.jqwik.api.Arbitraries.just(
                        com.bloxbean.cardano.julc.ledger.Interval.never()))
                .build();
    }

    // --- Spending context requires at least 1 signer ---

    @Example
    void spendingWithZeroSignersThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                ArbitraryScriptContext.spending().signers(0, 3));
    }

    // --- Minting PlutusData output ---

    @Property(tries = 30)
    void mintingBuildAsPlutusDataProducesConstrData(@ForAll("mintingPlutusData") PlutusData pd) {
        assertInstanceOf(PlutusData.ConstrData.class, pd);
        var constr = (PlutusData.ConstrData) pd;
        assertEquals(0, constr.tag());
        assertEquals(3, constr.fields().size());
    }

    @Provide
    Arbitrary<PlutusData> mintingPlutusData() {
        return ArbitraryScriptContext.minting().buildAsPlutusData();
    }

    // --- Rewarding PlutusData conversion ---

    @Property(tries = 30)
    void rewardingContextConvertibleToPlutusData(@ForAll("rewardingCtx") ScriptContext ctx) {
        PlutusData pd = ctx.toPlutusData();
        assertNotNull(pd);
        assertInstanceOf(PlutusData.ConstrData.class, pd);
    }
}
