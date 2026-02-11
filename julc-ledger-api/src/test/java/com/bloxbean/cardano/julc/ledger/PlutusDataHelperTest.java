package com.bloxbean.cardano.julc.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class PlutusDataHelperTest {

    // --- Boolean ---

    @Nested
    class BooleanTests {
        @Test
        void encodeTrue() {
            var data = PlutusDataHelper.encodeBool(true);
            assertEquals(new PlutusData.Constr(1, List.of()), data);
        }

        @Test
        void encodeFalse() {
            var data = PlutusDataHelper.encodeBool(false);
            assertEquals(new PlutusData.Constr(0, List.of()), data);
        }

        @Test
        void decodeTrue() {
            assertTrue(PlutusDataHelper.decodeBool(new PlutusData.Constr(1, List.of())));
        }

        @Test
        void decodeFalse() {
            assertFalse(PlutusDataHelper.decodeBool(new PlutusData.Constr(0, List.of())));
        }

        @Test
        void roundTripTrue() {
            assertTrue(PlutusDataHelper.decodeBool(PlutusDataHelper.encodeBool(true)));
        }

        @Test
        void roundTripFalse() {
            assertFalse(PlutusDataHelper.decodeBool(PlutusDataHelper.encodeBool(false)));
        }

        @Test
        void decodeInvalidTag() {
            assertThrows(IllegalArgumentException.class,
                    () -> PlutusDataHelper.decodeBool(new PlutusData.Constr(2, List.of())));
        }

        @Test
        void decodeNonConstr() {
            assertThrows(IllegalArgumentException.class,
                    () -> PlutusDataHelper.decodeBool(new PlutusData.IntData(0)));
        }
    }

    // --- Optional ---

    @Nested
    class OptionalTests {
        Function<BigInteger, PlutusData> intEncoder = PlutusDataHelper::encodeInteger;
        Function<PlutusData, BigInteger> intDecoder = PlutusDataHelper::decodeInteger;

        @Test
        void encodeSome() {
            var data = PlutusDataHelper.encodeOptional(Optional.of(BigInteger.TEN), intEncoder);
            assertEquals(new PlutusData.Constr(0, List.of(new PlutusData.IntData(BigInteger.TEN))), data);
        }

        @Test
        void encodeNone() {
            var data = PlutusDataHelper.encodeOptional(Optional.empty(), intEncoder);
            assertEquals(new PlutusData.Constr(1, List.of()), data);
        }

        @Test
        void decodeSome() {
            var data = new PlutusData.Constr(0, List.of(new PlutusData.IntData(42)));
            var result = PlutusDataHelper.decodeOptional(data, intDecoder);
            assertTrue(result.isPresent());
            assertEquals(BigInteger.valueOf(42), result.get());
        }

        @Test
        void decodeNone() {
            var data = new PlutusData.Constr(1, List.of());
            var result = PlutusDataHelper.decodeOptional(data, intDecoder);
            assertTrue(result.isEmpty());
        }

        @Test
        void roundTripSome() {
            var original = Optional.of(BigInteger.valueOf(999));
            var encoded = PlutusDataHelper.encodeOptional(original, intEncoder);
            var decoded = PlutusDataHelper.decodeOptional(encoded, intDecoder);
            assertEquals(original, decoded);
        }

        @Test
        void roundTripNone() {
            Optional<BigInteger> original = Optional.empty();
            var encoded = PlutusDataHelper.encodeOptional(original, intEncoder);
            var decoded = PlutusDataHelper.decodeOptional(encoded, intDecoder);
            assertEquals(original, decoded);
        }

        @Test
        void decodeInvalidTag() {
            assertThrows(IllegalArgumentException.class,
                    () -> PlutusDataHelper.decodeOptional(new PlutusData.Constr(2, List.of()), intDecoder));
        }
    }

    // --- List ---

    @Nested
    class ListTests {
        @Test
        void encodeEmptyList() {
            var data = PlutusDataHelper.encodeList(List.of(), PlutusDataHelper::encodeInteger);
            assertEquals(new PlutusData.ListData(List.of()), data);
        }

        @Test
        void encodeNonEmptyList() {
            var data = PlutusDataHelper.encodeList(
                    List.of(BigInteger.ONE, BigInteger.TWO),
                    PlutusDataHelper::encodeInteger);
            assertEquals(new PlutusData.ListData(List.of(
                    new PlutusData.IntData(1),
                    new PlutusData.IntData(2))), data);
        }

        @Test
        void decodeList() {
            var data = new PlutusData.ListData(List.of(
                    new PlutusData.IntData(10),
                    new PlutusData.IntData(20)));
            var result = PlutusDataHelper.decodeList(data, PlutusDataHelper::decodeInteger);
            assertEquals(List.of(BigInteger.TEN, BigInteger.valueOf(20)), result);
        }

        @Test
        void roundTrip() {
            var original = List.of(BigInteger.ZERO, BigInteger.ONE, BigInteger.TWO);
            var encoded = PlutusDataHelper.encodeList(original, PlutusDataHelper::encodeInteger);
            var decoded = PlutusDataHelper.decodeList(encoded, PlutusDataHelper::decodeInteger);
            assertEquals(original, decoded);
        }

        @Test
        void decodeNonListData() {
            assertThrows(IllegalArgumentException.class,
                    () -> PlutusDataHelper.decodeList(new PlutusData.IntData(0), PlutusDataHelper::decodeInteger));
        }
    }

    // --- Map ---

    @Nested
    class MapTests {
        @Test
        void encodeEmptyMap() {
            var data = PlutusDataHelper.encodeMap(
                    Map.of(),
                    PlutusDataHelper::encodeInteger,
                    PlutusDataHelper::encodeInteger);
            assertEquals(new PlutusData.Map(List.of()), data);
        }

        @Test
        void encodeNonEmptyMap() {
            var map = new LinkedHashMap<BigInteger, BigInteger>();
            map.put(BigInteger.ONE, BigInteger.TEN);
            var data = PlutusDataHelper.encodeMap(map,
                    PlutusDataHelper::encodeInteger,
                    PlutusDataHelper::encodeInteger);
            assertEquals(new PlutusData.Map(List.of(
                    new PlutusData.Pair(new PlutusData.IntData(1), new PlutusData.IntData(10))
            )), data);
        }

        @Test
        void decodeMap() {
            var data = new PlutusData.Map(List.of(
                    new PlutusData.Pair(new PlutusData.IntData(1), new PlutusData.IntData(100))));
            var result = PlutusDataHelper.decodeMap(data,
                    PlutusDataHelper::decodeInteger,
                    PlutusDataHelper::decodeInteger);
            assertEquals(java.util.Map.of(BigInteger.ONE, BigInteger.valueOf(100)), result);
        }

        @Test
        void roundTrip() {
            var original = new LinkedHashMap<BigInteger, BigInteger>();
            original.put(BigInteger.ONE, BigInteger.TEN);
            original.put(BigInteger.TWO, BigInteger.valueOf(20));
            var encoded = PlutusDataHelper.encodeMap(original,
                    PlutusDataHelper::encodeInteger,
                    PlutusDataHelper::encodeInteger);
            var decoded = PlutusDataHelper.decodeMap(encoded,
                    PlutusDataHelper::decodeInteger,
                    PlutusDataHelper::decodeInteger);
            assertEquals(original, decoded);
        }

        @Test
        void decodeNonMap() {
            assertThrows(IllegalArgumentException.class,
                    () -> PlutusDataHelper.decodeMap(new PlutusData.IntData(0),
                            PlutusDataHelper::decodeInteger,
                            PlutusDataHelper::decodeInteger));
        }
    }

    // --- Integer ---

    @Nested
    class IntegerTests {
        @Test
        void encode() {
            assertEquals(new PlutusData.IntData(42), PlutusDataHelper.encodeInteger(BigInteger.valueOf(42)));
        }

        @Test
        void decode() {
            assertEquals(BigInteger.valueOf(42), PlutusDataHelper.decodeInteger(new PlutusData.IntData(42)));
        }

        @Test
        void decodeNonInt() {
            assertThrows(IllegalArgumentException.class,
                    () -> PlutusDataHelper.decodeInteger(new PlutusData.BytesData(new byte[0])));
        }
    }

    // --- Bytes ---

    @Nested
    class BytesTests {
        @Test
        void encode() {
            var data = PlutusDataHelper.encodeBytes(new byte[]{1, 2, 3});
            assertEquals(new PlutusData.BytesData(new byte[]{1, 2, 3}), data);
        }

        @Test
        void decode() {
            assertArrayEquals(new byte[]{1, 2, 3},
                    PlutusDataHelper.decodeBytes(new PlutusData.BytesData(new byte[]{1, 2, 3})));
        }

        @Test
        void decodeNonBytes() {
            assertThrows(IllegalArgumentException.class,
                    () -> PlutusDataHelper.decodeBytes(new PlutusData.IntData(0)));
        }
    }

    // --- Constr helpers ---

    @Nested
    class ConstrHelperTests {
        @Test
        void expectConstrWithTag() {
            var fields = PlutusDataHelper.expectConstr(
                    new PlutusData.Constr(0, List.of(new PlutusData.IntData(1))), 0);
            assertEquals(1, fields.size());
            assertEquals(new PlutusData.IntData(1), fields.getFirst());
        }

        @Test
        void expectConstrWrongTag() {
            assertThrows(IllegalArgumentException.class,
                    () -> PlutusDataHelper.expectConstr(new PlutusData.Constr(1, List.of()), 0));
        }

        @Test
        void expectConstrNonConstr() {
            assertThrows(IllegalArgumentException.class,
                    () -> PlutusDataHelper.expectConstr(new PlutusData.IntData(0), 0));
        }

        @Test
        void expectConstrAny() {
            var c = PlutusDataHelper.expectConstr(new PlutusData.Constr(5, List.of()));
            assertEquals(5, c.tag());
        }
    }
}
