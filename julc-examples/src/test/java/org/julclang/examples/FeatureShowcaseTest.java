package org.julclang.examples;

import org.julclang.core.PlutusData;
import org.julclang.testkit.BudgetAssertions;
import org.julclang.testkit.ValidatorTest;
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
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.SpendingValidator;

                @SpendingValidator
                class HelperDemo {
                    static BigInteger add(BigInteger a, BigInteger b) {
                        return a.add(b);
                    }

                    static BigInteger multiply(BigInteger a, BigInteger b) {
                        return a.multiply(b);
                    }

                    static BigInteger compute(BigInteger x) {
                        return multiply(add(x, BigInteger.ONE), BigInteger.TWO);
                    }

                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        return compute(BigInteger.valueOf(5)).equals(BigInteger.valueOf(12));
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
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.SpendingValidator;

                @SpendingValidator
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
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.SpendingValidator;

                @SpendingValidator
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
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.SpendingValidator;

                @SpendingValidator
                class IfElseDemo {
                    static BigInteger abs(BigInteger x) {
                        if (x.compareTo(BigInteger.ZERO) < 0) {
                            return BigInteger.ZERO.subtract(x);
                        } else {
                            return x;
                        }
                    }

                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        return abs(BigInteger.valueOf(-42)).equals(BigInteger.valueOf(42));
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
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.SpendingValidator;

                @SpendingValidator
                class ArithmeticDemo {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        BigInteger a = BigInteger.TEN;
                        BigInteger b = BigInteger.valueOf(3);
                        BigInteger sum = a.add(b);
                        BigInteger diff = a.subtract(b);
                        BigInteger product = sum.multiply(diff);
                        return product.equals(BigInteger.valueOf(91));
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
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.SpendingValidator;

                @SpendingValidator
                class ChainDemo {
                    static BigInteger double_(BigInteger x) {
                        return x.add(x);
                    }

                    static BigInteger quadruple(BigInteger x) {
                        return double_(double_(x));
                    }

                    static BigInteger octuple(BigInteger x) {
                        return double_(quadruple(x));
                    }

                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        return octuple(BigInteger.valueOf(3)).equals(BigInteger.valueOf(24));
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
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.SpendingValidator;

                @SpendingValidator
                class LetDemo {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        BigInteger x = BigInteger.valueOf(5);
                        BigInteger y = x.multiply(BigInteger.TWO);
                        BigInteger z = y.add(x);
                        BigInteger w = z.multiply(z);
                        return w.equals(BigInteger.valueOf(225));
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
