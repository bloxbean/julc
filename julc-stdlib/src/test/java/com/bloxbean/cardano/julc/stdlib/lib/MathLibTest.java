package com.bloxbean.cardano.julc.stdlib.lib;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.testkit.JulcEval;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

class MathLibTest {

    static JulcEval eval;

    @BeforeAll
    static void setUp() {
        eval = JulcEval.forClass(MathLib.class);
    }

    @Nested
    class Abs {

        @Test
        void positiveUnchanged() {
            assertEquals(BigInteger.valueOf(42), eval.call("abs", BigInteger.valueOf(42)).asInteger());
        }

        @Test
        void negativeBecomesPositive() {
            assertEquals(BigInteger.valueOf(7), eval.call("abs", BigInteger.valueOf(-7)).asInteger());
        }

        @Test
        void zeroIsZero() {
            assertEquals(BigInteger.ZERO, eval.call("abs", BigInteger.ZERO).asInteger());
        }
    }

    @Nested
    class MaxMin {

        @Test
        void maxReturnsLarger() {
            assertEquals(BigInteger.valueOf(10), eval.call("max", BigInteger.valueOf(3), BigInteger.valueOf(10)).asInteger());
        }

        @Test
        void maxReturnsFirstWhenEqual() {
            assertEquals(BigInteger.valueOf(5), eval.call("max", BigInteger.valueOf(5), BigInteger.valueOf(5)).asInteger());
        }

        @Test
        void minReturnsSmaller() {
            assertEquals(BigInteger.valueOf(3), eval.call("min", BigInteger.valueOf(3), BigInteger.valueOf(10)).asInteger());
        }

        @Test
        void minReturnsFirstWhenEqual() {
            assertEquals(BigInteger.valueOf(5), eval.call("min", BigInteger.valueOf(5), BigInteger.valueOf(5)).asInteger());
        }
    }

    @Nested
    class DivMod {

        @Test
        void exactDivision() {
            PlutusData result = eval.call("divMod", BigInteger.valueOf(10), BigInteger.valueOf(5)).asData();
            assertInstanceOf(PlutusData.ConstrData.class, result);
            var fields = ((PlutusData.ConstrData) result).fields();
            assertEquals(PlutusData.integer(2), fields.get(0));  // 10 / 5 = 2
            assertEquals(PlutusData.integer(0), fields.get(1));  // 10 % 5 = 0
        }

        @Test
        void withRemainder() {
            PlutusData result = eval.call("divMod", BigInteger.valueOf(7), BigInteger.valueOf(3)).asData();
            var fields = ((PlutusData.ConstrData) result).fields();
            assertEquals(PlutusData.integer(2), fields.get(0));  // 7 / 3 = 2
            assertEquals(PlutusData.integer(1), fields.get(1));  // 7 % 3 = 1
        }
    }

    @Nested
    class QuotRem {

        @Test
        void exactDivision() {
            PlutusData result = eval.call("quotRem", BigInteger.valueOf(12), BigInteger.valueOf(4)).asData();
            var fields = ((PlutusData.ConstrData) result).fields();
            assertEquals(PlutusData.integer(3), fields.get(0));  // 12 / 4 = 3
            assertEquals(PlutusData.integer(0), fields.get(1));  // 12 % 4 = 0
        }

        @Test
        void withRemainder() {
            PlutusData result = eval.call("quotRem", BigInteger.valueOf(11), BigInteger.valueOf(3)).asData();
            var fields = ((PlutusData.ConstrData) result).fields();
            assertEquals(PlutusData.integer(3), fields.get(0));  // 11 / 3 = 3
            assertEquals(PlutusData.integer(2), fields.get(1));  // 11 % 3 = 2
        }
    }

    @Nested
    class Pow {

        @Test
        void zeroExponent() {
            assertEquals(BigInteger.ONE, eval.call("pow", BigInteger.valueOf(5), BigInteger.ZERO).asInteger());
        }

        @Test
        void positiveExponent() {
            assertEquals(BigInteger.valueOf(8), eval.call("pow", BigInteger.valueOf(2), BigInteger.valueOf(3)).asInteger());
        }

        @Test
        void negativeExponentReturnsOne() {
            assertEquals(BigInteger.ONE, eval.call("pow", BigInteger.valueOf(2), BigInteger.valueOf(-1)).asInteger());
        }
    }

    @Nested
    class ExpMod {

        @Test
        void basicModularExponentiation() {
            // 2^10 mod 1000 = 1024 mod 1000 = 24
            assertEquals(BigInteger.valueOf(24),
                    eval.call("expMod", BigInteger.valueOf(2), BigInteger.valueOf(10), BigInteger.valueOf(1000)).asInteger());
        }
    }

    @Nested
    class Sign {

        @Test
        void negativeReturnsMinusOne() {
            assertEquals(BigInteger.valueOf(-1), eval.call("sign", BigInteger.valueOf(-42)).asInteger());
        }

        @Test
        void zeroReturnsZero() {
            assertEquals(BigInteger.ZERO, eval.call("sign", BigInteger.ZERO).asInteger());
        }

        @Test
        void positiveReturnsOne() {
            assertEquals(BigInteger.ONE, eval.call("sign", BigInteger.valueOf(99)).asInteger());
        }
    }
}
