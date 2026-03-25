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
 * Property-based tests for a realistic vesting validator.
 * <p>
 * Demonstrates the core PBT pattern: compile once, run hundreds of random scenarios.
 * Uses jqwik {@code @Property} with {@code @ForAll} injection via CardanoArbitraries.
 */
class VestingPropertyTest {

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

    static final Program VESTING = ValidatorTest.compile(VESTING_SOURCE);

    final BudgetCollector budgetCollector = new BudgetCollector();

    /**
     * Property: the beneficiary can always unlock the vesting contract.
     */
    @Property(tries = 200)
    void beneficiaryAlwaysSucceeds(@ForAll("pkh") PubKeyHash beneficiary) {
        var ctx = buildVestingCtx(beneficiary, beneficiary);

        var result = ValidatorTest.evaluate(VESTING, ctx);
        BudgetAssertions.assertSuccess(result);
        budgetCollector.record(result);
    }

    /**
     * Property: a non-beneficiary can never unlock the vesting contract.
     */
    @Property(tries = 200)
    void nonBeneficiaryAlwaysFails(
            @ForAll("pkh") PubKeyHash beneficiary,
            @ForAll("pkh") PubKeyHash attacker) {

        Assume.that(!Arrays.equals(beneficiary.hash(), attacker.hash()));

        var ctx = buildVestingCtx(beneficiary, attacker);

        var result = ValidatorTest.evaluate(VESTING, ctx);
        BudgetAssertions.assertFailure(result);
    }

    /**
     * Property: the evaluation budget is bounded.
     */
    @Property(tries = 200)
    void budgetIsBounded(@ForAll("pkh") PubKeyHash beneficiary) {
        var ctx = buildVestingCtx(beneficiary, beneficiary);

        var result = ValidatorTest.evaluate(VESTING, ctx);
        BudgetAssertions.assertSuccess(result);
        BudgetAssertions.assertBudgetUnder(result, 50_000_000, 200_000);
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

    /** Build a spending context with the given beneficiary in datum and signer. */
    private static PlutusData buildVestingCtx(PubKeyHash beneficiary, PubKeyHash signer) {
        var datum = PlutusData.constr(0,
                PlutusData.bytes(beneficiary.hash()),
                PlutusData.integer(1000));
        var spentRef = TestDataBuilder.randomTxOutRef_typed();
        return ScriptContextTestBuilder.spending(spentRef, datum)
                .signer(signer)
                .input(TestDataBuilder.txIn(spentRef,
                        TestDataBuilder.txOut(
                                TestDataBuilder.pubKeyAddress(beneficiary),
                                Value.lovelace(BigInteger.valueOf(5_000_000)))))
                .buildPlutusData();
    }
}
