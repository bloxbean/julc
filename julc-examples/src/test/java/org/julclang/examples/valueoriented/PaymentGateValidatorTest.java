package org.julclang.examples.valueoriented;

import org.julclang.compiler.CompileResult;
import org.julclang.core.PlutusData;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;
import org.julclang.testkit.BudgetAssertions;
import org.julclang.testkit.ScriptContextTestBuilder;
import org.julclang.testkit.TestDataBuilder;
import org.julclang.testkit.ValidatorTest;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The testing example on the Value-Oriented Contract Code page: the real validator is compiled to UPLC
 * and evaluated with ScriptContexts for accepting, rejecting and malformed transactions, and against an
 * independent model of the rule.
 */
class PaymentGateValidatorTest {
    // region gate-setup
    static final CompileResult compiled =
            ValidatorTest.compileValidator(PaymentGateValidator.class, Path.of("src/test/java"));

    static final PubKeyHash OWNER = TestDataBuilder.randomPubKeyHash_typed();
    static final PubKeyHash PAYEE = TestDataBuilder.randomPubKeyHash_typed();
    static final PubKeyHash OTHER = TestDataBuilder.randomPubKeyHash_typed();
    static final BigInteger MINIMUM = BigInteger.valueOf(5_000_000);

    static final PlutusData CLAIM = PlutusData.constr(0);
    static final PlutusData REFUND = PlutusData.constr(1);

    static TxOut pay(PubKeyHash to, long lovelace) {
        return TestDataBuilder.txOut(TestDataBuilder.pubKeyAddress(to), Value.lovelace(BigInteger.valueOf(lovelace)));
    }

    static PlutusData context(PlutusData redeemer, List<TxOut> outputs, PubKeyHash... signers) {
        PlutusData datum = PlutusData.constr(0,
                PlutusData.bytes(OWNER.hash()), PlutusData.bytes(PAYEE.hash()), PlutusData.integer(MINIMUM));
        TxOutRef spent = TestDataBuilder.randomTxOutRef_typed();
        var builder = ScriptContextTestBuilder.spending(spent, datum)
                .redeemer(redeemer)
                .input(TestDataBuilder.txIn(spent, pay(OWNER, 10_000_000)));
        outputs.forEach(builder::output);
        for (PubKeyHash signer : signers) builder.signer(signer);
        return builder.buildPlutusData();
    }
    // endregion

    // region gate-cases
    @Test
    void claimAcceptsTheMinimumExactly() {
        ValidatorTest.assertValidates(compiled, context(CLAIM, List.of(pay(PAYEE, 5_000_000))));
    }

    @Test
    void claimRejectsOneLovelaceShort() {
        ValidatorTest.assertRejects(compiled, context(CLAIM, List.of(pay(PAYEE, 4_999_999))));
    }

    @Test
    void malformedRedeemerFails() {
        ValidatorTest.assertRejects(compiled, context(PlutusData.constr(2), List.of(pay(PAYEE, 5_000_000))));
        ValidatorTest.assertRejects(compiled, context(PlutusData.integer(0), List.of(pay(PAYEE, 5_000_000))));
    }

    @Test
    void agreesWithAnIndependentModel() {
        Random random = new Random(163);
        for (int i = 0; i < 40; i++) {
            List<TxOut> outputs = new ArrayList<>();
            long toPayee = 0;
            for (int n = random.nextInt(5); n > 0; n--) {
                long lovelace = random.nextLong(3_000_001);
                boolean payee = random.nextBoolean();
                outputs.add(pay(payee ? PAYEE : OTHER, lovelace));
                if (payee) toPayee += lovelace;
            }
            boolean expected = toPayee >= MINIMUM.longValueExact();
            EvalResult result = ValidatorTest.evaluate(compiled, context(CLAIM, outputs));
            assertEquals(expected, result.isSuccess(), "case " + i + ": " + toPayee + " lovelace to the payee");
        }
    }

    @Test
    void staysWithinBudget() {
        var outputs = List.of(pay(PAYEE, 2_000_000), pay(OTHER, 9_000_000), pay(PAYEE, 3_000_000));
        EvalResult result = ValidatorTest.evaluate(compiled, context(CLAIM, outputs));
        BudgetAssertions.assertSuccess(result);
        BudgetAssertions.assertBudgetUnder(result, 35_000_000, 140_000);
        BudgetAssertions.assertScriptSizeUnder(compiled, 1_000);
    }
    // endregion

    @Test
    void claimAddsUpSeveralPayments() {
        ValidatorTest.assertValidates(compiled,
                context(CLAIM, List.of(pay(PAYEE, 2_000_000), pay(OTHER, 9_000_000), pay(PAYEE, 3_000_000))));
    }

    @Test
    void claimRejectsPaymentToSomeoneElse() {
        ValidatorTest.assertRejects(compiled, context(CLAIM, List.of(pay(OTHER, 50_000_000))));
    }

    @Test
    void claimRejectsATransactionWithNoOutputs() {
        ValidatorTest.assertRejects(compiled, context(CLAIM, List.of()));
    }

    @Test
    void refundNeedsTheOwnerSignature() {
        ValidatorTest.assertValidates(compiled, context(REFUND, List.of(), OWNER));
        ValidatorTest.assertRejects(compiled, context(REFUND, List.of(), PAYEE));
        ValidatorTest.assertRejects(compiled, context(REFUND, List.of()));
    }
}
