package com.bloxbean.cardano.julc.examples;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.testkit.BudgetAssertions;
import com.bloxbean.cardano.julc.testkit.ScriptContextTestBuilder;
import com.bloxbean.cardano.julc.testkit.TestDataBuilder;
import com.bloxbean.cardano.julc.testkit.ValidatorTest;
import com.bloxbean.cardano.julc.testkit.jqwik.BudgetCollector;
import com.bloxbean.cardano.julc.testkit.jqwik.CardanoArbitraries;
import net.jqwik.api.*;
import net.jqwik.api.lifecycle.AfterProperty;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * Property-based tests for a minting validator.
 * <p>
 * Demonstrates minting-specific patterns with budget statistics via BudgetCollector.
 */
class MintingPropertyTest {

    static final String MINTING_SOURCE = """
            @Validator
            class SignedMintPolicy {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    PlutusData txInfo = ContextsLib.getTxInfo(ctx);
                    return ContextsLib.signedBy(txInfo, redeemer);
                }
            }
            """;

    static final Program MINTING_POLICY = ValidatorTest.compile(MINTING_SOURCE);

    final BudgetCollector budgetCollector = new BudgetCollector();

    /**
     * Property: minting succeeds when the redeemer PKH is in signatories.
     */
    @Property(tries = 200)
    void authorizedSignerCanMint(
            @ForAll("policyId") PolicyId policy,
            @ForAll("pkh") PubKeyHash signer,
            @ForAll("tokenName") TokenName tokenName) {

        var ctx = buildMintingCtx(policy, signer, signer, tokenName);

        var result = ValidatorTest.evaluate(MINTING_POLICY, ctx);
        BudgetAssertions.assertSuccess(result);
        budgetCollector.record(result);
    }

    /**
     * Property: minting fails when the redeemer PKH is NOT in signatories.
     */
    @Property(tries = 200)
    void unauthorizedSignerCannotMint(
            @ForAll("policyId") PolicyId policy,
            @ForAll("pkh") PubKeyHash requiredSigner,
            @ForAll("pkh") PubKeyHash actualSigner) {

        Assume.that(!Arrays.equals(requiredSigner.hash(), actualSigner.hash()));

        var ctx = buildMintingCtx(policy, requiredSigner, actualSigner, TokenName.EMPTY);

        var result = ValidatorTest.evaluate(MINTING_POLICY, ctx);
        BudgetAssertions.assertFailure(result);
    }

    /**
     * Property: budget is bounded regardless of input variety.
     */
    @Property(tries = 200)
    void mintingBudgetIsBounded(
            @ForAll("policyId") PolicyId policy,
            @ForAll("pkh") PubKeyHash signer) {

        var ctx = buildMintingCtx(policy, signer, signer, TokenName.EMPTY);

        var result = ValidatorTest.evaluate(MINTING_POLICY, ctx);
        BudgetAssertions.assertSuccess(result);
        BudgetAssertions.assertBudgetUnder(result, 50_000_000, 200_000);
        budgetCollector.record(result);
    }

    @AfterProperty
    void reportBudget() {
        if (budgetCollector.count() > 0) {
            System.out.println(budgetCollector.summary());
        }
    }

    @Provide
    Arbitrary<PubKeyHash> pkh() {
        return CardanoArbitraries.pubKeyHash();
    }

    @Provide
    Arbitrary<PolicyId> policyId() {
        return CardanoArbitraries.policyId();
    }

    @Provide
    Arbitrary<TokenName> tokenName() {
        return CardanoArbitraries.tokenName();
    }

    /** Build a minting context with the given redeemer PKH as signer. */
    private static PlutusData buildMintingCtx(PolicyId policy, PubKeyHash redeemerPkh,
                                               PubKeyHash signer, TokenName tn) {
        return ScriptContextTestBuilder.minting(policy)
                .redeemer(PlutusData.bytes(redeemerPkh.hash()))
                .signer(signer)
                .mint(Value.singleton(policy, tn, BigInteger.ONE))
                .input(TestDataBuilder.txIn(
                        TestDataBuilder.randomTxOutRef_typed(),
                        TestDataBuilder.txOut(
                                TestDataBuilder.pubKeyAddress(signer),
                                Value.lovelace(BigInteger.valueOf(10_000_000)))))
                .buildPlutusData();
    }
}
