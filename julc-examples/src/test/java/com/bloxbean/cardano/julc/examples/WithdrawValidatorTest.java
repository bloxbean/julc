package com.bloxbean.cardano.julc.examples;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.testkit.BudgetAssertions;
import com.bloxbean.cardano.julc.testkit.ValidatorTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Withdraw validator example: demonstrates a staking reward withdrawal validator.
 * <p>
 * A withdraw validator controls when staking rewards can be withdrawn.
 * It takes 2 parameters: (redeemer, scriptContext).
 * <p>
 * These examples demonstrate the @WithdrawValidator annotation and validator
 * compilation. Like other examples, they use constant expressions to demonstrate
 * compilation features while remaining evaluable.
 */
class WithdrawValidatorTest {

    /**
     * A withdraw validator that checks authorization using a helper method.
     * Uses constant comparison to demonstrate the compilation pattern.
     */
    static final String AUTHORIZED_WITHDRAW = """
            import java.math.BigInteger;

            @WithdrawValidator
            class AuthorizedWithdraw {
                static boolean isAuthorized(BigInteger pkh, BigInteger expected) {
                    return pkh == expected;
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return isAuthorized(12345, 12345);
                }
            }
            """;

    /**
     * A withdraw validator that rejects — wrong public key hash.
     */
    static final String REJECTING_WITHDRAW = """
            import java.math.BigInteger;

            @WithdrawValidator
            class RejectingWithdraw {
                static boolean isAuthorized(BigInteger pkh, BigInteger expected) {
                    return pkh == expected;
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return isAuthorized(99999, 12345);
                }
            }
            """;

    /**
     * A withdraw validator that always succeeds (for testing purposes).
     */
    static final String ALWAYS_ALLOW_WITHDRAW = """
            import java.math.BigInteger;

            @WithdrawValidator
            class AlwaysAllowWithdraw {
                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return true;
                }
            }
            """;

    private PlutusData mockCtx(PlutusData redeemer) {
        return PlutusData.constr(0,
                PlutusData.integer(0),
                redeemer,
                PlutusData.integer(0));
    }

    @Test
    void compilesAuthorizedWithdraw() {
        var program = ValidatorTest.compile(AUTHORIZED_WITHDRAW);
        assertNotNull(program);
    }

    @Test
    void compilesRejectingWithdraw() {
        var program = ValidatorTest.compile(REJECTING_WITHDRAW);
        assertNotNull(program);
    }

    @Test
    void compilesAlwaysAllowWithdraw() {
        var program = ValidatorTest.compile(ALWAYS_ALLOW_WITHDRAW);
        assertNotNull(program);
    }

    @Test
    void authorizedWithdrawAccepts() {
        var ctx = mockCtx(PlutusData.integer(0));
        var result = ValidatorTest.evaluate(AUTHORIZED_WITHDRAW, ctx);
        BudgetAssertions.assertSuccess(result);
    }

    @Test
    void rejectingWithdrawRejects() {
        var ctx = mockCtx(PlutusData.integer(0));
        var result = ValidatorTest.evaluate(REJECTING_WITHDRAW, ctx);
        BudgetAssertions.assertFailure(result);
    }

    @Test
    void alwaysAllowWithdrawAccepts() {
        var ctx = mockCtx(PlutusData.integer(0));
        var result = ValidatorTest.evaluate(ALWAYS_ALLOW_WITHDRAW, ctx);
        BudgetAssertions.assertSuccess(result);
    }

    @Test
    void budgetIsReasonable() {
        var ctx = mockCtx(PlutusData.integer(0));
        var result = ValidatorTest.evaluate(AUTHORIZED_WITHDRAW, ctx);
        BudgetAssertions.assertBudgetUnder(result, 10_000_000_000L, 10_000_000_000L);
    }
}
