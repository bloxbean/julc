package com.bloxbean.cardano.julc.stdlib.lib;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.testkit.JulcEval;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

class ByteStringLibTest {

    static JulcEval eval;

    @BeforeAll
    static void setUp() {
        eval = JulcEval.forClass(ByteStringLib.class);
    }

    @Nested
    class At {

        @Test
        void firstByte() {
            assertEquals(0xAB, eval.call("at", new byte[]{(byte) 0xAB, (byte) 0xCD}, 0L).asLong());
        }

        @Test
        void lastByte() {
            assertEquals(0xCD, eval.call("at", new byte[]{(byte) 0xAB, (byte) 0xCD}, 1L).asLong());
        }
    }

    @Nested
    class Cons {

        @Test
        void prependToEmpty() {
            byte[] result = eval.call("cons", 65L, new byte[]{}).asByteString();
            assertArrayEquals(new byte[]{65}, result);
        }

        @Test
        void prependToNonEmpty() {
            byte[] result = eval.call("cons", 1L, new byte[]{2, 3}).asByteString();
            assertArrayEquals(new byte[]{1, 2, 3}, result);
        }
    }

    @Nested
    class Slice {

        @Test
        void middlePortion() {
            byte[] result = eval.call("slice", new byte[]{1, 2, 3, 4, 5}, 1L, 3L).asByteString();
            assertArrayEquals(new byte[]{2, 3, 4}, result);
        }

        @Test
        void fullLength() {
            byte[] result = eval.call("slice", new byte[]{1, 2, 3}, 0L, 3L).asByteString();
            assertArrayEquals(new byte[]{1, 2, 3}, result);
        }
    }

    @Nested
    class Length {

        @Test
        void emptyIsZero() {
            assertEquals(0, eval.call("length", new byte[]{}).asLong());
        }

        @Test
        void nonEmpty() {
            assertEquals(4, eval.call("length", new byte[]{1, 2, 3, 4}).asLong());
        }
    }

    @Nested
    class DropTake {

        @Test
        void dropPartial() {
            byte[] result = eval.call("drop", new byte[]{1, 2, 3, 4}, 2L).asByteString();
            assertArrayEquals(new byte[]{3, 4}, result);
        }

        @Test
        void takePartial() {
            byte[] result = eval.call("take", new byte[]{1, 2, 3, 4}, 2L).asByteString();
            assertArrayEquals(new byte[]{1, 2}, result);
        }
    }

    @Nested
    class Append {

        @Test
        void appendTwoNonEmpty() {
            byte[] result = eval.call("append", new byte[]{1, 2}, new byte[]{3, 4}).asByteString();
            assertArrayEquals(new byte[]{1, 2, 3, 4}, result);
        }

        @Test
        void appendWithEmpty() {
            byte[] result = eval.call("append", new byte[]{1, 2}, new byte[]{}).asByteString();
            assertArrayEquals(new byte[]{1, 2}, result);
        }
    }

    @Nested
    class Empty {

        @Test
        void returnsZeroLength() {
            byte[] result = eval.call("empty").asByteString();
            assertEquals(0, result.length);
        }
    }

    @Nested
    class Zeros {

        @Test
        void createsCorrectLength() {
            byte[] result = eval.call("zeros", 4L).asByteString();
            assertEquals(4, result.length);
            assertArrayEquals(new byte[]{0, 0, 0, 0}, result);
        }
    }

    @Nested
    class Equality {

        @Test
        void sameAreEqual() {
            assertTrue(eval.call("equals", new byte[]{1, 2, 3}, new byte[]{1, 2, 3}).asBoolean());
        }

        @Test
        void differentAreNotEqual() {
            assertFalse(eval.call("equals", new byte[]{1, 2, 3}, new byte[]{1, 2, 4}).asBoolean());
        }
    }

    @Nested
    class Comparison {

        @Test
        void lessThanTrue() {
            assertTrue(eval.call("lessThan", new byte[]{1}, new byte[]{2}).asBoolean());
        }

        @Test
        void lessThanFalse() {
            assertFalse(eval.call("lessThan", new byte[]{2}, new byte[]{1}).asBoolean());
        }

        @Test
        void lessThanEqualsSame() {
            assertTrue(eval.call("lessThanEquals", new byte[]{1, 2}, new byte[]{1, 2}).asBoolean());
        }

        @Test
        void lessThanEqualsSmaller() {
            assertTrue(eval.call("lessThanEquals", new byte[]{1}, new byte[]{2}).asBoolean());
        }
    }

    @Nested
    class Conversion {

        @Test
        void integerToByteStringAndBack() {
            byte[] bs = eval.call("integerToByteString", true, 4L, 256L).asByteString();
            assertEquals(4, bs.length);
            BigInteger back = eval.call("byteStringToInteger", true, bs).asInteger();
            assertEquals(BigInteger.valueOf(256), back);
        }
    }

    @Nested
    class SerialisedData {

        @Test
        @Disabled("PlutusData param triggers compileMethod entry-point issue (Force instead of Apply)")
        void serialiseProducesNonEmpty() {
            byte[] result = eval.call("serialiseData", PlutusData.integer(42)).asByteString();
            assertTrue(result.length > 0);
        }
    }

    @Nested
    class ToHex {

        @Test
        void emptyInput() {
            byte[] result = eval.call("toHex", new byte[]{}).asByteString();
            assertEquals(0, result.length);
        }

        @Test
        void singleByte() {
            // 0xDE → "de" → {100, 101}
            byte[] result = eval.call("toHex", new byte[]{(byte) 0xDE}).asByteString();
            assertArrayEquals(new byte[]{100, 101}, result); // 'd'=100, 'e'=101
        }

        @Test
        void multipleBytes() {
            // 0xABCD → "abcd"
            byte[] result = eval.call("toHex", new byte[]{(byte) 0xAB, (byte) 0xCD}).asByteString();
            assertEquals(4, result.length);
        }
    }

    @Nested
    class IntToDecimal {

        @Test
        void zero() {
            byte[] result = eval.call("intToDecimalString", BigInteger.ZERO).asByteString();
            assertArrayEquals(new byte[]{48}, result); // '0' = 48
        }

        @Test
        void singleDigit() {
            byte[] result = eval.call("intToDecimalString", BigInteger.valueOf(7)).asByteString();
            assertArrayEquals(new byte[]{55}, result); // '7' = 55
        }

        @Test
        void multiDigit() {
            byte[] result = eval.call("intToDecimalString", BigInteger.valueOf(42)).asByteString();
            assertArrayEquals(new byte[]{52, 50}, result); // '4'=52, '2'=50
        }
    }
}
