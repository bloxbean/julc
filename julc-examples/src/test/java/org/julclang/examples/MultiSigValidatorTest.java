package org.julclang.examples;

import org.julclang.core.PlutusData;
import org.julclang.testkit.BudgetAssertions;
import org.julclang.testkit.ValidatorTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Multi-signature validator example: demonstrates helper methods,
 * boolean chains, and immutable functional style.
 * <p>
 * Demonstrates: multiple helpers, boolean logic, if/else expressions.
 */
class MultiSigValidatorTest {

    /**
     * A multi-sig validator using helper methods and boolean chains.
     * Counts true conditions using ternary-like if/else expressions.
     * All variables are immutable (functional style).
     */
    static final String MULTISIG_SOURCE = """
            import java.math.BigInteger;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;

            @SpendingValidator
            class MultiSigValidator {
                static boolean check1(BigInteger x) {
                    return x.compareTo(BigInteger.ZERO) > 0;
                }

                static boolean check2(BigInteger x) {
                    return x.compareTo(BigInteger.valueOf(1000)) < 0;
                }

                static boolean check3(BigInteger x) {
                    return x.equals(BigInteger.valueOf(42));
                }

                static BigInteger boolToInt(boolean b) {
                    if (b) {
                        return BigInteger.ONE;
                    } else {
                        return BigInteger.ZERO;
                    }
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    BigInteger value = BigInteger.valueOf(42);
                    BigInteger trueCount = boolToInt(check1(value)).add(boolToInt(check2(value))).add(boolToInt(check3(value)));
                    return trueCount.compareTo(BigInteger.TWO) >= 0;
                }
            }
            """;

    /**
     * Failing variant — value doesn't satisfy enough checks.
     */
    static final String MULTISIG_FAILING = """
            import java.math.BigInteger;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;

            @SpendingValidator
            class MultiSigValidator {
                static boolean check1(BigInteger x) {
                    return x.compareTo(BigInteger.ZERO) > 0;
                }

                static boolean check2(BigInteger x) {
                    return x.compareTo(BigInteger.valueOf(1000)) < 0;
                }

                static boolean check3(BigInteger x) {
                    return x.equals(BigInteger.valueOf(42));
                }

                static BigInteger boolToInt(boolean b) {
                    if (b) {
                        return BigInteger.ONE;
                    } else {
                        return BigInteger.ZERO;
                    }
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    BigInteger value = BigInteger.valueOf(-5);
                    BigInteger trueCount = boolToInt(check1(value)).add(boolToInt(check2(value))).add(boolToInt(check3(value)));
                    return trueCount.compareTo(BigInteger.valueOf(3)) >= 0;
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
    void compiles() {
        var program = ValidatorTest.compile(MULTISIG_SOURCE);
        assertNotNull(program);
    }

    @Test
    void multiSigAcceptsWithEnoughSignatures() {
        var ctx = mockCtx(PlutusData.integer(0));
        var result = ValidatorTest.evaluate(MULTISIG_SOURCE, ctx);
        BudgetAssertions.assertSuccess(result);
    }

    @Test
    void multiSigRejectsWithInsufficientSignatures() {
        var ctx = mockCtx(PlutusData.integer(0));
        var result = ValidatorTest.evaluate(MULTISIG_FAILING, ctx);
        BudgetAssertions.assertFailure(result);
    }

    @Test
    void budgetIsReasonable() {
        var ctx = mockCtx(PlutusData.integer(0));
        var result = ValidatorTest.evaluate(MULTISIG_SOURCE, ctx);
        BudgetAssertions.assertBudgetUnder(result, 10_000_000_000L, 10_000_000_000L);
    }
}
