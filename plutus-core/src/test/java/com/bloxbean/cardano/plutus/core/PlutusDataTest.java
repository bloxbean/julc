package com.bloxbean.cardano.plutus.core;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlutusDataTest {

    // --- Constr ---

    @Test
    void constrWithTagAndFields() {
        var c = new PlutusData.Constr(0, List.of(PlutusData.integer(42)));
        assertEquals(0, c.tag());
        assertEquals(1, c.fields().size());
    }

    @Test
    void constrFieldsAreImmutable() {
        var c = (PlutusData.Constr) PlutusData.constr(1, PlutusData.integer(1), PlutusData.integer(2));
        assertThrows(UnsupportedOperationException.class, () -> c.fields().add(PlutusData.integer(3)));
    }

    @Test
    void constrEmptyFields() {
        var c = PlutusData.constr(0);
        assertTrue(c instanceof PlutusData.Constr);
        assertEquals(0, ((PlutusData.Constr) c).fields().size());
    }

    @Test
    void constrFactoryMethod() {
        var c = PlutusData.constr(2, PlutusData.integer(10), PlutusData.bytes(new byte[]{1, 2}));
        var constr = (PlutusData.Constr) c;
        assertEquals(2, constr.tag());
        assertEquals(2, constr.fields().size());
    }

    // --- IntData ---

    @Test
    void intDataFromLong() {
        var d = PlutusData.integer(42);
        assertInstanceOf(PlutusData.IntData.class, d);
        assertEquals(BigInteger.valueOf(42), ((PlutusData.IntData) d).value());
    }

    @Test
    void intDataFromBigInteger() {
        var big = new BigInteger("99999999999999999999");
        var d = PlutusData.integer(big);
        assertEquals(big, ((PlutusData.IntData) d).value());
    }

    @Test
    void intDataNullRejected() {
        assertThrows(NullPointerException.class, () -> new PlutusData.IntData(null));
    }

    @Test
    void intDataZero() {
        var d = new PlutusData.IntData(0);
        assertEquals(BigInteger.ZERO, d.value());
    }

    @Test
    void intDataNegative() {
        var d = PlutusData.integer(-100);
        assertEquals(BigInteger.valueOf(-100), ((PlutusData.IntData) d).value());
    }

    @Test
    void intDataEquality() {
        assertEquals(new PlutusData.IntData(42), new PlutusData.IntData(BigInteger.valueOf(42)));
    }

    // --- BytesData ---

    @Test
    void bytesDataValue() {
        var d = PlutusData.bytes(new byte[]{0x01, 0x02, 0x03});
        assertInstanceOf(PlutusData.BytesData.class, d);
        assertArrayEquals(new byte[]{0x01, 0x02, 0x03}, ((PlutusData.BytesData) d).value());
    }

    @Test
    void bytesDataDefensiveCopy() {
        byte[] original = {1, 2, 3};
        var d = new PlutusData.BytesData(original);
        original[0] = 99; // mutate original
        assertArrayEquals(new byte[]{1, 2, 3}, d.value()); // should not be affected
    }

    @Test
    void bytesDataValueDefensiveCopy() {
        var d = new PlutusData.BytesData(new byte[]{1, 2, 3});
        byte[] val = d.value();
        val[0] = 99; // mutate returned value
        assertArrayEquals(new byte[]{1, 2, 3}, d.value()); // should not be affected
    }

    @Test
    void bytesDataNullRejected() {
        assertThrows(NullPointerException.class, () -> new PlutusData.BytesData(null));
    }

    @Test
    void bytesDataEmpty() {
        var d = new PlutusData.BytesData(new byte[]{});
        assertArrayEquals(new byte[]{}, d.value());
    }

    @Test
    void bytesDataEquality() {
        var a = new PlutusData.BytesData(new byte[]{1, 2, 3});
        var b = new PlutusData.BytesData(new byte[]{1, 2, 3});
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void bytesDataInequality() {
        var a = new PlutusData.BytesData(new byte[]{1, 2, 3});
        var b = new PlutusData.BytesData(new byte[]{1, 2, 4});
        assertNotEquals(a, b);
    }

    @Test
    void bytesDataToString() {
        var d = new PlutusData.BytesData(new byte[]{(byte) 0xCA, (byte) 0xFE});
        assertTrue(d.toString().contains("cafe"));
    }

    // --- ListData ---

    @Test
    void listData() {
        var d = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2));
        assertInstanceOf(PlutusData.ListData.class, d);
        assertEquals(2, ((PlutusData.ListData) d).items().size());
    }

    @Test
    void listDataEmpty() {
        var d = PlutusData.list();
        assertEquals(0, ((PlutusData.ListData) d).items().size());
    }

    @Test
    void listDataImmutable() {
        var d = new PlutusData.ListData(List.of(PlutusData.integer(1)));
        assertThrows(UnsupportedOperationException.class, () -> d.items().add(PlutusData.integer(2)));
    }

    // --- Map ---

    @Test
    void mapData() {
        var entry = new PlutusData.Pair(PlutusData.integer(1), PlutusData.bytes(new byte[]{0x0A}));
        var m = new PlutusData.Map(List.of(entry));
        assertEquals(1, m.entries().size());
        assertEquals(PlutusData.integer(1), m.entries().getFirst().key());
    }

    @Test
    void mapDataEmpty() {
        var m = new PlutusData.Map(List.of());
        assertTrue(m.entries().isEmpty());
    }

    @Test
    void mapDataImmutable() {
        var m = new PlutusData.Map(List.of());
        assertThrows(UnsupportedOperationException.class,
                () -> m.entries().add(new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(2))));
    }

    // --- UNIT constant ---

    @Test
    void unitConstant() {
        var u = PlutusData.UNIT;
        assertInstanceOf(PlutusData.Constr.class, u);
        assertEquals(0, ((PlutusData.Constr) u).tag());
        assertTrue(((PlutusData.Constr) u).fields().isEmpty());
    }

    // --- Constr negative tag ---

    @Test
    void constrNegativeTagRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PlutusData.Constr(-1, List.of()));
    }

    // --- Pair null validation ---

    @Test
    void pairNullKeyRejected() {
        assertThrows(NullPointerException.class, () -> new PlutusData.Pair(null, PlutusData.integer(1)));
    }

    @Test
    void pairNullValueRejected() {
        assertThrows(NullPointerException.class, () -> new PlutusData.Pair(PlutusData.integer(1), null));
    }

    // --- Map factory method ---

    @Test
    void mapFactoryMethod() {
        var m = PlutusData.map(
                new PlutusData.Pair(PlutusData.integer(1), PlutusData.bytes(new byte[]{0x0A})),
                new PlutusData.Pair(PlutusData.integer(2), PlutusData.bytes(new byte[]{0x0B})));
        assertInstanceOf(PlutusData.Map.class, m);
        assertEquals(2, ((PlutusData.Map) m).entries().size());
    }

    // --- Sealed interface exhaustiveness ---

    @Test
    void sealedInterfacePatternMatch() {
        PlutusData data = PlutusData.integer(42);
        String result = switch (data) {
            case PlutusData.Constr c -> "constr";
            case PlutusData.Map m -> "map";
            case PlutusData.ListData l -> "list";
            case PlutusData.IntData i -> "int:" + i.value();
            case PlutusData.BytesData b -> "bytes";
        };
        assertEquals("int:42", result);
    }

    // --- Nested structures ---

    @Test
    void nestedData() {
        // Constr(0, [List([Int(1), Int(2)]), Map([(Int(3), Bytes(0xAB))])])
        var inner = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2));
        var mapEntry = new PlutusData.Pair(PlutusData.integer(3), PlutusData.bytes(new byte[]{(byte) 0xAB}));
        var map = new PlutusData.Map(List.of(mapEntry));
        var outer = PlutusData.constr(0, inner, map);

        var constr = (PlutusData.Constr) outer;
        assertEquals(2, constr.fields().size());
        assertInstanceOf(PlutusData.ListData.class, constr.fields().get(0));
        assertInstanceOf(PlutusData.Map.class, constr.fields().get(1));
    }
}
