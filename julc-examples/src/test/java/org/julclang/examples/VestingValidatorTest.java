package org.julclang.examples;

import org.julclang.core.PlutusData;
import org.julclang.testkit.BudgetAssertions;
import org.julclang.testkit.ValidatorTest;
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
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;

            @SpendingValidator
            class VestingValidator {
                static boolean isPastDeadline(BigInteger currentTime, BigInteger deadline) {
                    return currentTime.compareTo(deadline) > 0;
                }

                static boolean checkBeneficiary(BigInteger pkh, BigInteger expected) {
                    return pkh == expected;
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return isPastDeadline(BigInteger.valueOf(2000), BigInteger.valueOf(1000)) && checkBeneficiary(BigInteger.valueOf(42), BigInteger.valueOf(42));
                }
            }
            """;

    /**
     * Validator that rejects — deadline check fails.
     */
    static final String REJECTING_VESTING = """
            import java.math.BigInteger;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;

            @SpendingValidator
            class VestingValidator {
                static boolean isPastDeadline(BigInteger currentTime, BigInteger deadline) {
                    return currentTime.compareTo(deadline) > 0;
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    return isPastDeadline(BigInteger.valueOf(500), BigInteger.valueOf(1000));
                }
            }
            """;

    /**
     * Validator with nested helper calls and if/else.
     */
    static final String COMPLEX_VESTING = """
            import java.math.BigInteger;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;

            @SpendingValidator
            class VestingValidator {
                static BigInteger double_(BigInteger x) {
                    return x.add(x);
                }

                static boolean isPositive(BigInteger x) {
                    return x.compareTo(BigInteger.ZERO) > 0;
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    BigInteger doubled = double_(BigInteger.valueOf(21));
                    if (isPositive(doubled)) {
                        return doubled.equals(BigInteger.valueOf(42));
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
