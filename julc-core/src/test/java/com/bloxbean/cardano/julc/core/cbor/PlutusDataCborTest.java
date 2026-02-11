package com.bloxbean.cardano.julc.core.cbor;

import com.bloxbean.cardano.julc.core.PlutusData;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PlutusData CBOR encoding/decoding.
 */
class PlutusDataCborTest {

    private static final HexFormat HEX = HexFormat.of();

    // ---- Integer encoding/decoding ----

    @Test
    void integerZero() {
        assertRoundTrip(PlutusData.integer(0));
    }

    @Test
    void integerPositiveSmall() {
        assertRoundTrip(PlutusData.integer(1));
        assertRoundTrip(PlutusData.integer(23));
        assertRoundTrip(PlutusData.integer(100));
    }

    @Test
    void integerNegativeSmall() {
        assertRoundTrip(PlutusData.integer(-1));
        assertRoundTrip(PlutusData.integer(-100));
    }

    @Test
    void integerLargePositive() {
        assertRoundTrip(PlutusData.integer(Long.MAX_VALUE));
        assertRoundTrip(PlutusData.integer(new BigInteger("18446744073709551615"))); // 2^64 - 1
    }

    @Test
    void integerLargeNegative() {
        assertRoundTrip(PlutusData.integer(Long.MIN_VALUE));
    }

    @Test
    void integerBigNumPositive() {
        var big = BigInteger.TWO.pow(65); // larger than 2^64
        assertRoundTrip(PlutusData.integer(big));
    }

    @Test
    void integerBigNumNegative() {
        var big = BigInteger.TWO.pow(65).negate();
        assertRoundTrip(PlutusData.integer(big));
    }

    @Test
    void integerVeryLargeBigNum() {
        var big = BigInteger.TWO.pow(256);
        assertRoundTrip(PlutusData.integer(big));
    }

    @Test
    void integerCborEncoding() {
        // Verify exact CBOR bytes for known values
        byte[] encoded = PlutusDataCborEncoder.encode(PlutusData.integer(0));
        assertEquals("00", HEX.formatHex(encoded)); // CBOR 0

        encoded = PlutusDataCborEncoder.encode(PlutusData.integer(1));
        assertEquals("01", HEX.formatHex(encoded)); // CBOR 1

        encoded = PlutusDataCborEncoder.encode(PlutusData.integer(-1));
        assertEquals("20", HEX.formatHex(encoded)); // CBOR -1

        encoded = PlutusDataCborEncoder.encode(PlutusData.integer(100));
        assertEquals("1864", HEX.formatHex(encoded)); // CBOR 100

        encoded = PlutusDataCborEncoder.encode(PlutusData.integer(1000));
        assertEquals("1903e8", HEX.formatHex(encoded)); // CBOR 1000
    }

    // ---- ByteString encoding/decoding ----

    @Test
    void byteStringEmpty() {
        assertRoundTrip(PlutusData.bytes(new byte[]{}));
    }

    @Test
    void byteStringSmall() {
        assertRoundTrip(PlutusData.bytes(new byte[]{0x01, 0x02, 0x03}));
    }

    @Test
    void byteStringLarger() {
        var data = new byte[100];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i & 0xFF);
        assertRoundTrip(PlutusData.bytes(data));
    }

    @Test
    void byteStringCborEncoding() {
        byte[] encoded = PlutusDataCborEncoder.encode(PlutusData.bytes(new byte[]{}));
        assertEquals("40", HEX.formatHex(encoded)); // CBOR empty bytes

        encoded = PlutusDataCborEncoder.encode(PlutusData.bytes(new byte[]{0x01, 0x02}));
        assertEquals("420102", HEX.formatHex(encoded)); // CBOR 2-byte bytes
    }

    // ---- List encoding/decoding ----

    @Test
    void listEmpty() {
        assertRoundTrip(PlutusData.list());
    }

    @Test
    void listOfIntegers() {
        assertRoundTrip(PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3)));
    }

    @Test
    void listOfMixedTypes() {
        assertRoundTrip(PlutusData.list(
                PlutusData.integer(1),
                PlutusData.bytes(new byte[]{0x0A}),
                PlutusData.list(PlutusData.integer(2))));
    }

    // ---- Map encoding/decoding ----

    @Test
    void mapEmpty() {
        assertRoundTrip(PlutusData.map());
    }

    @Test
    void mapSingleEntry() {
        assertRoundTrip(PlutusData.map(
                new PlutusData.Pair(PlutusData.integer(1), PlutusData.bytes(new byte[]{0x0A}))));
    }

    @Test
    void mapMultipleEntries() {
        assertRoundTrip(PlutusData.map(
                new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)),
                new PlutusData.Pair(PlutusData.integer(2), PlutusData.integer(20))));
    }

    // ---- Constr encoding/decoding ----

    @Test
    void constrTag0Compact() {
        assertRoundTrip(PlutusData.constr(0));
        assertRoundTrip(PlutusData.constr(0, PlutusData.integer(1)));
    }

    @Test
    void constrTag6Compact() {
        assertRoundTrip(PlutusData.constr(6));
        assertRoundTrip(PlutusData.constr(6, PlutusData.integer(42)));
    }

    @Test
    void constrTag7Extended() {
        assertRoundTrip(PlutusData.constr(7));
        assertRoundTrip(PlutusData.constr(7, PlutusData.integer(1)));
    }

    @Test
    void constrTag127Extended() {
        assertRoundTrip(PlutusData.constr(127));
    }

    @Test
    void constrTag128General() {
        assertRoundTrip(PlutusData.constr(128));
        assertRoundTrip(PlutusData.constr(128, PlutusData.integer(1)));
    }

    @Test
    void constrTag1000General() {
        assertRoundTrip(PlutusData.constr(1000, PlutusData.integer(42)));
    }

    @Test
    void constrCompactCborTag() {
        // Constr(0, []) → CBOR tag 121 + empty array
        byte[] encoded = PlutusDataCborEncoder.encode(PlutusData.constr(0));
        // d8 79 80 = tag(121) + array(0)
        assertEquals("d87980", HEX.formatHex(encoded));

        // Constr(3, []) → CBOR tag 124 + empty array
        encoded = PlutusDataCborEncoder.encode(PlutusData.constr(3));
        assertEquals("d87c80", HEX.formatHex(encoded));
    }

    @Test
    void constrExtendedCborTag() {
        // Constr(7, []) → CBOR tag 1280 + empty array
        byte[] encoded = PlutusDataCborEncoder.encode(PlutusData.constr(7));
        // d9 0500 80 = tag(1280) + array(0)
        assertEquals("d9050080", HEX.formatHex(encoded));
    }

    @Test
    void constrGeneralCborTag() {
        // Constr(128, []) → CBOR tag 102, [128, []]
        byte[] encoded = PlutusDataCborEncoder.encode(PlutusData.constr(128));
        // d8 66 = tag(102), 82 = array(2), 18 80 = uint(128), 80 = array(0)
        assertEquals("d866821880" + "80", HEX.formatHex(encoded));
    }

    @Test
    void constrWithFields() {
        // Constr(0, [42]) → tag(121) + array([42])
        byte[] encoded = PlutusDataCborEncoder.encode(PlutusData.constr(0, PlutusData.integer(42)));
        assertEquals("d879" + "81" + "182a", HEX.formatHex(encoded));
        // d8 79 = tag(121), 81 = array(1), 18 2a = uint(42)
    }

    // ---- Nested structures ----

    @Test
    void nestedConstr() {
        var inner = PlutusData.constr(1, PlutusData.integer(42));
        var outer = PlutusData.constr(0, inner);
        assertRoundTrip(outer);
    }

    @Test
    void complexNested() {
        var data = PlutusData.constr(0,
                PlutusData.list(PlutusData.integer(1), PlutusData.integer(2)),
                PlutusData.map(
                        new PlutusData.Pair(PlutusData.bytes(new byte[]{0x01}), PlutusData.integer(100))),
                PlutusData.constr(1));
        assertRoundTrip(data);
    }

    // ---- Unit (Constr 0 []) ----

    @Test
    void unitValue() {
        assertRoundTrip(PlutusData.UNIT);
    }

    // ---- Error handling ----

    @Test
    void decodeEmptyBytesThrows() {
        assertThrows(CborDecodingException.class, () -> PlutusDataCborDecoder.decode(new byte[]{}));
    }

    @Test
    void decodeInvalidCborThrows() {
        assertThrows(CborDecodingException.class, () -> PlutusDataCborDecoder.decode(new byte[]{(byte) 0xFF}));
    }

    // ---- Helper ----

    private void assertRoundTrip(PlutusData original) {
        byte[] encoded = PlutusDataCborEncoder.encode(original);
        PlutusData decoded = PlutusDataCborDecoder.decode(encoded);
        assertEquals(original, decoded, "Round-trip failed for: " + original);
    }
}
