package com.bloxbean.cardano.julc.examples;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.testkit.BudgetAssertions;
import com.bloxbean.cardano.julc.testkit.ValidatorTest;
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

            @Validator
            class MultiSigValidator {
                static boolean check1(BigInteger x) {
                    return x > 0;
                }

                static boolean check2(BigInteger x) {
                    return x < 1000;
                }

                static boolean check3(BigInteger x) {
                    return x == 42;
                }

                static BigInteger boolToInt(boolean b) {
                    if (b) {
                        return 1;
                    } else {
                        return 0;
                    }
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    BigInteger value = 42;
                    BigInteger trueCount = boolToInt(check1(value))
                                         + boolToInt(check2(value))
                                         + boolToInt(check3(value));
                    return trueCount >= 2;
                }
            }
            """;

    /**
     * Failing variant — value doesn't satisfy enough checks.
     */
    static final String MULTISIG_FAILING = """
            import java.math.BigInteger;

            @Validator
            class MultiSigValidator {
                static boolean check1(BigInteger x) {
                    return x > 0;
                }

                static boolean check2(BigInteger x) {
                    return x < 1000;
                }

                static boolean check3(BigInteger x) {
                    return x == 42;
                }

                static BigInteger boolToInt(boolean b) {
                    if (b) {
                        return 1;
                    } else {
                        return 0;
                    }
                }

                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    BigInteger value = -5;
                    BigInteger trueCount = boolToInt(check1(value))
                                         + boolToInt(check2(value))
                                         + boolToInt(check3(value));
                    return trueCount >= 3;
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
