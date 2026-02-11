package com.bloxbean.cardano.julc.examples;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.testkit.BudgetAssertions;
import com.bloxbean.cardano.julc.testkit.ValidatorTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Feature showcase: demonstrates all Milestone 4 compiler features.
 * Each nested class tests one feature with compilation and evaluation.
 */
class FeatureShowcaseTest {

    private PlutusData mockCtx(PlutusData redeemer) {
        return PlutusData.constr(0,
                PlutusData.integer(0), redeemer, PlutusData.integer(0));
    }

    // -------------------------------------------------------------------------
    // Feature: Helper methods (Task 4.1)
    // -------------------------------------------------------------------------

    @Nested
    class HelperMethods {

        static final String SOURCE = """
                import java.math.BigInteger;

                @Validator
                class HelperDemo {
                    static BigInteger add(BigInteger a, BigInteger b) {
                        return a + b;
                    }

                    static BigInteger multiply(BigInteger a, BigInteger b) {
                        return a * b;
                    }

                    static BigInteger compute(BigInteger x) {
                        return multiply(add(x, 1), 2);
                    }

                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        return compute(5) == 12;
                    }
                }
                """;

        @Test
        void compiles() {
            assertNotNull(ValidatorTest.compile(SOURCE));
        }

        @Test
        void evaluates() {
            var result = ValidatorTest.evaluate(SOURCE, mockCtx(PlutusData.integer(0)));
            BudgetAssertions.assertSuccess(result);
        }
    }

    // -------------------------------------------------------------------------
    // Feature: Sealed interfaces / sum types (Task 4.2)
    // -------------------------------------------------------------------------

    @Nested
    class SealedInterfaces {

        static final String SOURCE = """
                import java.math.BigInteger;

                @Validator
                class SealedDemo {
                    sealed interface Shape {
                        record Circle(BigInteger radius) implements Shape {}
                        record Rectangle(BigInteger width, BigInteger height) implements Shape {}
                    }

                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        return true;
                    }
                }
                """;

        @Test
        void compiles() {
            assertNotNull(ValidatorTest.compile(SOURCE));
        }

        @Test
        void evaluates() {
            var result = ValidatorTest.evaluate(SOURCE, mockCtx(PlutusData.integer(0)));
            BudgetAssertions.assertSuccess(result);
        }
    }

    // -------------------------------------------------------------------------
    // Feature: Boolean logic and comparisons
    // -------------------------------------------------------------------------

    @Nested
    class BooleanLogic {

        static final String SOURCE = """
                import java.math.BigInteger;

                @Validator
                class BooleanDemo {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        boolean a = 5 > 3;
                        boolean b = 10 == 10;
                        boolean c = 1 < 0;
                        return a && b && !c;
                    }
                }
                """;

        @Test
        void compiles() {
            assertNotNull(ValidatorTest.compile(SOURCE));
        }

        @Test
        void evaluates() {
            var result = ValidatorTest.evaluate(SOURCE, mockCtx(PlutusData.integer(0)));
            BudgetAssertions.assertSuccess(result);
        }
    }

    // -------------------------------------------------------------------------
    // Feature: If/else expressions (functional style)
    // -------------------------------------------------------------------------

    @Nested
    class IfElseExpressions {

        static final String SOURCE = """
                import java.math.BigInteger;

                @Validator
                class IfElseDemo {
                    static BigInteger abs(BigInteger x) {
                        if (x < 0) {
                            return 0 - x;
                        } else {
                            return x;
                        }
                    }

                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        return abs(-42) == 42;
                    }
                }
                """;

        @Test
        void compiles() {
            assertNotNull(ValidatorTest.compile(SOURCE));
        }

        @Test
        void evaluates() {
            var result = ValidatorTest.evaluate(SOURCE, mockCtx(PlutusData.integer(0)));
            BudgetAssertions.assertSuccess(result);
        }
    }

    // -------------------------------------------------------------------------
    // Feature: Arithmetic chains
    // -------------------------------------------------------------------------

    @Nested
    class ArithmeticChains {

        static final String SOURCE = """
                import java.math.BigInteger;

                @Validator
                class ArithmeticDemo {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        BigInteger a = 10;
                        BigInteger b = 3;
                        BigInteger sum = a + b;
                        BigInteger diff = a - b;
                        BigInteger product = sum * diff;
                        return product == 91;
                    }
                }
                """;

        @Test
        void compiles() {
            assertNotNull(ValidatorTest.compile(SOURCE));
        }

        @Test
        void evaluates() {
            // (10+3) * (10-3) = 13 * 7 = 91
            var result = ValidatorTest.evaluate(SOURCE, mockCtx(PlutusData.integer(0)));
            BudgetAssertions.assertSuccess(result);
        }
    }

    // -------------------------------------------------------------------------
    // Feature: Helper calling helper (chained delegation)
    // -------------------------------------------------------------------------

    @Nested
    class HelperChains {

        static final String SOURCE = """
                import java.math.BigInteger;

                @Validator
                class ChainDemo {
                    static BigInteger double_(BigInteger x) {
                        return x + x;
                    }

                    static BigInteger quadruple(BigInteger x) {
                        return double_(double_(x));
                    }

                    static BigInteger octuple(BigInteger x) {
                        return double_(quadruple(x));
                    }

                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        return octuple(3) == 24;
                    }
                }
                """;

        @Test
        void compiles() {
            assertNotNull(ValidatorTest.compile(SOURCE));
        }

        @Test
        void evaluates() {
            var result = ValidatorTest.evaluate(SOURCE, mockCtx(PlutusData.integer(0)));
            BudgetAssertions.assertSuccess(result);
        }
    }

    // -------------------------------------------------------------------------
    // Feature: Nested let bindings
    // -------------------------------------------------------------------------

    @Nested
    class NestedLetBindings {

        static final String SOURCE = """
                import java.math.BigInteger;

                @Validator
                class LetDemo {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        BigInteger x = 5;
                        BigInteger y = x * 2;
                        BigInteger z = y + x;
                        BigInteger w = z * z;
                        return w == 225;
                    }
                }
                """;

        @Test
        void compiles() {
            assertNotNull(ValidatorTest.compile(SOURCE));
        }

        @Test
        void evaluates() {
            // x=5, y=10, z=15, w=225
            var result = ValidatorTest.evaluate(SOURCE, mockCtx(PlutusData.integer(0)));
            BudgetAssertions.assertSuccess(result);
        }
    }
}
