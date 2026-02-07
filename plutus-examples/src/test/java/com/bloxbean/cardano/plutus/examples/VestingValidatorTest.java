package com.bloxbean.cardano.plutus.examples;

import com.bloxbean.cardano.plutus.core.PlutusData;
import com.bloxbean.cardano.plutus.testkit.BudgetAssertions;
import com.bloxbean.cardano.plutus.testkit.ValidatorTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Vesting validator example: demonstrates helper methods, comparisons,
 * boolean logic, and if/else.
 * <p>
 * Tests both compilation and end-to-end evaluation.
 */
class VestingValidatorTest {

    /**
     * A simplified vesting validator with helper methods.
     * Uses only constants and boolean logic (doesn't reference params)
     * to demonstrate the compilation features while remaining evaluable.
     */
    static final String ALWAYS_TRUE_VESTING = """
            import java.math.BigInteger;

            @Validator
            class VestingValidator {
                static boolean isPastDeadline(BigInteger currentTime, BigInteger deadline) {
                    return currentTime > deadline;
                }

                static boolean checkBeneficiary(BigInteger pkh, BigInteger expected) {
                    return pkh == expected;
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return isPastDeadline(2000, 1000) && checkBeneficiary(42, 42);
                }
            }
            """;

    /**
     * Validator that rejects — deadline check fails.
     */
    static final String REJECTING_VESTING = """
            import java.math.BigInteger;

            @Validator
            class VestingValidator {
                static boolean isPastDeadline(BigInteger currentTime, BigInteger deadline) {
                    return currentTime > deadline;
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return isPastDeadline(500, 1000);
                }
            }
            """;

    /**
     * Validator with nested helper calls and if/else.
     */
    static final String COMPLEX_VESTING = """
            import java.math.BigInteger;

            @Validator
            class VestingValidator {
                static BigInteger double_(BigInteger x) {
                    return x + x;
                }

                static boolean isPositive(BigInteger x) {
                    return x > 0;
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    BigInteger doubled = double_(21);
                    if (isPositive(doubled)) {
                        return doubled == 42;
                    } else {
                        return false;
                    }
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
    void compilesAlwaysTrueVesting() {
        var program = ValidatorTest.compile(ALWAYS_TRUE_VESTING);
        assertNotNull(program);
    }

    @Test
    void compilesRejectingVesting() {
        var program = ValidatorTest.compile(REJECTING_VESTING);
        assertNotNull(program);
    }

    @Test
    void compilesComplexVesting() {
        var program = ValidatorTest.compile(COMPLEX_VESTING);
        assertNotNull(program);
    }

    @Test
    void alwaysTrueVestingAccepts() {
        var ctx = mockCtx(PlutusData.integer(0));
        var result = ValidatorTest.evaluate(ALWAYS_TRUE_VESTING, ctx);
        BudgetAssertions.assertSuccess(result);
    }

    @Test
    void rejectingVestingRejects() {
        var ctx = mockCtx(PlutusData.integer(0));
        var result = ValidatorTest.evaluate(REJECTING_VESTING, ctx);
        BudgetAssertions.assertFailure(result);
    }

    @Test
    void complexVestingAccepts() {
        var ctx = mockCtx(PlutusData.integer(0));
        var result = ValidatorTest.evaluate(COMPLEX_VESTING, ctx);
        BudgetAssertions.assertSuccess(result);
    }

    @Test
    void budgetIsReasonable() {
        var ctx = mockCtx(PlutusData.integer(0));
        var result = ValidatorTest.evaluate(ALWAYS_TRUE_VESTING, ctx);
        BudgetAssertions.assertBudgetUnder(result, 10_000_000_000L, 10_000_000_000L);
    }
}
