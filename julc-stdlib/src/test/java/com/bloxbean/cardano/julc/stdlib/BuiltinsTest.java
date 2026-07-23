package com.bloxbean.cardano.julc.stdlib;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.core.types.JulcMap;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinsTest {

    @Nested
    class PlutusDataConversion {

        @Test
        void objectOverloadsAcceptCoreConversionTypes() {
            var list = JulcList.of(BigInteger.ONE, BigInteger.TWO);
            var map = JulcMap.of("key", BigInteger.TEN);

            assertTrue(Builtins.equalsData(
                    list, PlutusData.list(
                            PlutusData.integer(1),
                            PlutusData.integer(2))));
            assertTrue(Builtins.equalsData(
                    map, map.toPlutusData()));
        }
    }

    @Nested
    class IntegerToByteString {

        @Test
        void rejectsNegativeWidthBeforeCasting() {
            assertThrows(IllegalArgumentException.class,
                    () -> Builtins.integerToByteString(true, -1, BigInteger.ZERO));
        }

        @Test
        void acceptsMaximumWidth() {
            byte[] result = Builtins.integerToByteString(true, 8192, BigInteger.ZERO);

            assertEquals(8192, result.length);
        }

        @Test
        void rejectsWidthAboveMaximum() {
            assertThrows(IllegalArgumentException.class,
                    () -> Builtins.integerToByteString(true, 8193, BigInteger.ZERO));
        }

        @Test
        void rejectsWidthThatWouldOverflowAnIntCast() {
            assertThrows(IllegalArgumentException.class,
                    () -> Builtins.integerToByteString(true, Long.MAX_VALUE, BigInteger.ZERO));
        }

        @Test
        void rejectsValueThatDoesNotFitRequestedWidth() {
            assertThrows(IllegalArgumentException.class,
                    () -> Builtins.integerToByteString(
                            true, 2, BigInteger.valueOf(65_536)));
        }

        @Test
        void rejectsUnboundedResultAboveMaximum() {
            BigInteger tooLarge = BigInteger.ONE.shiftLeft(8192 * 8);

            assertThrows(IllegalArgumentException.class,
                    () -> Builtins.integerToByteString(true, 0, tooLarge));
        }

        @Test
        void acceptsLargestUnboundedResult() {
            BigInteger maximum = BigInteger.ONE.shiftLeft(8192 * 8).subtract(BigInteger.ONE);

            assertEquals(8192, Builtins.integerToByteString(true, 0, maximum).length);
        }

        @Test
        void preservesExactWidthEndianness() {
            assertArrayEquals(
                    new byte[]{0, 1, 2},
                    Builtins.integerToByteString(true, 3, BigInteger.valueOf(0x0102)));
            assertArrayEquals(
                    new byte[]{2, 1, 0},
                    Builtins.integerToByteString(false, 3, BigInteger.valueOf(0x0102)));
        }
    }
}
