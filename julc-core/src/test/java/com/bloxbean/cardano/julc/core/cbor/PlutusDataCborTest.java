package com.bloxbean.cardano.julc.core.cbor;

import com.bloxbean.cardano.julc.core.PlutusData;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
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

    // ========================================================================
    // NEW TESTS: Chunked byte strings, canonical maps, edge cases
    // ========================================================================

    @Nested
    class ChunkedByteStringEncoding {

        @Test
        void byteStringExactly64Bytes() {
            // Exactly 64 bytes — should NOT be chunked (definite-length)
            var data = new byte[64];
            Arrays.fill(data, (byte) 0xAB);
            byte[] encoded = PlutusDataCborEncoder.encode(PlutusData.bytes(data));
            String hex = HEX.formatHex(encoded);
            // Should start with 5840 (major type 2, length 64) not 5F (indefinite)
            assertTrue(hex.startsWith("5840"), "64-byte string should be definite-length, got: " + hex.substring(0, Math.min(4, hex.length())));
            assertFalse(hex.startsWith("5f"), "64-byte string should not use indefinite-length");
            assertRoundTrip(PlutusData.bytes(data));
        }

        @Test
        void byteString65Bytes() {
            // 65 bytes — should be chunked: 5f 5840(64 bytes) 4101(1 byte) ff
            var data = new byte[65];
            Arrays.fill(data, (byte) 0xCD);
            byte[] encoded = PlutusDataCborEncoder.encode(PlutusData.bytes(data));
            String hex = HEX.formatHex(encoded);
            // Should start with 5f (indefinite-length byte string)
            assertTrue(hex.startsWith("5f"), "65-byte string should use chunked encoding, got: " + hex.substring(0, Math.min(4, hex.length())));
            // Should end with ff (break code)
            assertTrue(hex.endsWith("ff"), "Chunked encoding should end with break code");
            // First chunk: 5840 + 64 bytes of 0xCD
            assertTrue(hex.startsWith("5f5840"), "Should have 64-byte first chunk");
            // Second chunk: 41 + 1 byte of 0xCD
            assertTrue(hex.contains("41cd"), "Should have 1-byte second chunk");
            assertRoundTrip(PlutusData.bytes(data));
        }

        @Test
        void byteString128Bytes() {
            // 128 bytes — 2 full 64-byte chunks
            var data = new byte[128];
            for (int i = 0; i < data.length; i++) data[i] = (byte) (i & 0xFF);
            byte[] encoded = PlutusDataCborEncoder.encode(PlutusData.bytes(data));
            String hex = HEX.formatHex(encoded);
            assertTrue(hex.startsWith("5f5840"), "Should start with indefinite + 64-byte chunk");
            assertTrue(hex.endsWith("ff"), "Should end with break code");
            // Two chunks of 64 bytes each: 5f + 5840(64) + 5840(64) + ff
            // Total overhead: 1 + 2 + 64 + 2 + 64 + 1 = 134 bytes
            assertEquals(134, encoded.length, "128 bytes in 2 chunks + framing");
            assertRoundTrip(PlutusData.bytes(data));
        }

        @Test
        void byteString200Bytes() {
            // 200 bytes — 3 chunks of 64 + remainder of 8
            var data = new byte[200];
            Arrays.fill(data, (byte) 0xFF);
            byte[] encoded = PlutusDataCborEncoder.encode(PlutusData.bytes(data));
            String hex = HEX.formatHex(encoded);
            assertTrue(hex.startsWith("5f"), "Should use chunked encoding");
            // Count chunk headers: 5840 appears 3 times (64-byte chunks), 4808 for 8-byte chunk
            int chunkCount = 0;
            int idx = 2; // skip initial "5f"
            while (idx < hex.length() - 2) { // -2 for final "ff"
                if (hex.substring(idx).startsWith("5840")) {
                    chunkCount++;
                    idx += 4 + 128; // header + 64 bytes in hex
                } else if (hex.substring(idx).startsWith("48")) {
                    chunkCount++;
                    idx += 2 + 16; // header (48) + 8 bytes in hex
                } else {
                    break;
                }
            }
            assertEquals(4, chunkCount, "200 bytes = 3 full chunks + 1 partial chunk");
            assertRoundTrip(PlutusData.bytes(data));
        }

        @Test
        void bigNumPositiveOver64Bytes() {
            // 2^520 — byte representation > 64 bytes (65 bytes), should produce tag 2 + chunked
            var big = BigInteger.TWO.pow(520);
            byte[] encoded = PlutusDataCborEncoder.encode(PlutusData.integer(big));
            String hex = HEX.formatHex(encoded);
            // Should have tag 2 (c2) followed by chunked byte string (5f)
            assertTrue(hex.startsWith("c25f"), "BigNum > 64 bytes should be tag 2 + chunked, got: " + hex.substring(0, Math.min(8, hex.length())));
            assertRoundTrip(PlutusData.integer(big));
        }

        @Test
        void bigNumNegativeOver64Bytes() {
            // -(2^520) — byte representation of -(1+n) > 64 bytes, should produce tag 3 + chunked
            var big = BigInteger.TWO.pow(520).negate();
            byte[] encoded = PlutusDataCborEncoder.encode(PlutusData.integer(big));
            String hex = HEX.formatHex(encoded);
            // Should have tag 3 (c3) followed by chunked byte string (5f)
            assertTrue(hex.startsWith("c35f"), "Negative BigNum > 64 bytes should be tag 3 + chunked, got: " + hex.substring(0, Math.min(8, hex.length())));
            assertRoundTrip(PlutusData.integer(big));
        }
    }

    @Nested
    class IndefiniteLengthDecoding {

        @Test
        void decodeIndefiniteLengthArray() {
            // 9f 01 02 03 ff = indefinite-length array [1, 2, 3]
            byte[] cbor = HEX.parseHex("9f010203ff");
            PlutusData decoded = PlutusDataCborDecoder.decode(cbor);
            assertEquals(PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3)), decoded);
        }

        @Test
        void decodeIndefiniteLengthMap() {
            // bf 01 0a 02 14 ff = indefinite-length map {1: 10, 2: 20}
            byte[] cbor = HEX.parseHex("bf010a0214ff");
            PlutusData decoded = PlutusDataCborDecoder.decode(cbor);
            var expected = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)),
                    new PlutusData.Pair(PlutusData.integer(2), PlutusData.integer(20)));
            assertEquals(expected, decoded);
        }

        @Test
        void decodeChunkedByteString() {
            // Construct a 70-byte chunked byte string:
            // 5f 5840(64 bytes) 46(6 bytes) ff
            var sb = new StringBuilder();
            sb.append("5f"); // indefinite-length byte string
            sb.append("5840"); // chunk 1: 64 bytes
            var chunk1 = new byte[64];
            Arrays.fill(chunk1, (byte) 0xAA);
            sb.append(HEX.formatHex(chunk1));
            sb.append("46"); // chunk 2: 6 bytes
            var chunk2 = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05, 0x06};
            sb.append(HEX.formatHex(chunk2));
            sb.append("ff"); // break

            byte[] cbor = HEX.parseHex(sb.toString());
            PlutusData decoded = PlutusDataCborDecoder.decode(cbor);

            // Expected: 70-byte BytesData
            var expected = new byte[70];
            System.arraycopy(chunk1, 0, expected, 0, 64);
            System.arraycopy(chunk2, 0, expected, 64, 6);
            assertEquals(PlutusData.bytes(expected), decoded);
        }

        @Test
        void decodeChunkedBigNumTag2() {
            // Manually craft a chunked tag 2 CBOR for a large positive integer
            // tag 2 = c2, then chunked byte string with known bytes
            var sb = new StringBuilder();
            sb.append("c2"); // tag 2
            sb.append("5f"); // indefinite-length byte string
            // Chunk 1: 2 bytes representing a big number
            sb.append("4201ff"); // 2 bytes: 0x01, 0xFF
            sb.append("ff"); // break
            byte[] cbor = HEX.parseHex(sb.toString());
            PlutusData decoded = PlutusDataCborDecoder.decode(cbor);

            // 0x01FF = 511 as unsigned big integer
            assertEquals(PlutusData.integer(new BigInteger("511")), decoded);
        }

        @Test
        void decodeNestedIndefiniteArray() {
            // 9f 9f 01 02 ff 03 ff = indefinite [[1,2], 3]
            byte[] cbor = HEX.parseHex("9f9f0102ff03ff");
            PlutusData decoded = PlutusDataCborDecoder.decode(cbor);
            var expected = PlutusData.list(
                    PlutusData.list(PlutusData.integer(1), PlutusData.integer(2)),
                    PlutusData.integer(3));
            assertEquals(expected, decoded);
        }
    }

    @Nested
    class CanonicalMapKeyOrdering {

        @Test
        void mapKeysCanonicalOrder() {
            // Encode map with keys [int(2), int(1)] — output should have int(1) first
            var data = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(2), PlutusData.integer(20)),
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)));
            byte[] encoded = PlutusDataCborEncoder.encode(data);
            String hex = HEX.formatHex(encoded);
            // a2 = map(2), 01 0a = {1: 10}, 02 14 = {2: 20}
            assertEquals("a2010a0214", hex,
                    "Map keys should be canonically sorted: int(1) before int(2)");
        }

        @Test
        void mapKeysMixedTypes() {
            // Integer key (01) encodes to 1 byte, bytestring key (4101) encodes to 2 bytes
            // RFC 7049 §3.9: shorter keys sort first
            var data = PlutusData.map(
                    new PlutusData.Pair(PlutusData.bytes(new byte[]{0x01}), PlutusData.integer(2)),
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(1)));
            byte[] encoded = PlutusDataCborEncoder.encode(data);
            String hex = HEX.formatHex(encoded);
            // map(2): int(1)→int(1) should come before bytes([0x01])→int(2)
            // a2 01 01 41 01 02
            assertEquals("a201014101" + "02", hex,
                    "Shorter key (int) should sort before longer key (bytestring)");
        }

        @Test
        void mapCanonicalRoundTrip() {
            // Encode with out-of-order keys, decode, verify values preserved
            var data = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(100), PlutusData.integer(1)),
                    new PlutusData.Pair(PlutusData.integer(2), PlutusData.integer(2)),
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(3)));
            byte[] encoded = PlutusDataCborEncoder.encode(data);
            PlutusData decoded = PlutusDataCborDecoder.decode(encoded);

            // Decoded values should be present (order may differ from input)
            assertInstanceOf(PlutusData.MapData.class, decoded);
            var map = (PlutusData.MapData) decoded;
            assertEquals(3, map.entries().size());

            // Verify all key-value pairs exist
            var entries = map.entries();
            // After canonical sorting: int(1), int(2), int(100)
            assertEquals(PlutusData.integer(1), entries.get(0).key());
            assertEquals(PlutusData.integer(3), entries.get(0).value());
            assertEquals(PlutusData.integer(2), entries.get(1).key());
            assertEquals(PlutusData.integer(2), entries.get(1).value());
            assertEquals(PlutusData.integer(100), entries.get(2).key());
            assertEquals(PlutusData.integer(1), entries.get(2).value());
        }
    }

    @Nested
    class EdgeCases {

        @Test
        void decodeTextStringThrows() {
            // CBOR text string "hello" = 65 68656c6c6f (major type 3)
            byte[] cbor = HEX.parseHex("6568656c6c6f");
            assertThrows(CborDecodingException.class, () -> PlutusDataCborDecoder.decode(cbor));
        }

        @Test
        void constrTagMaxInt() {
            // ConstrData(Integer.MAX_VALUE, []) round-trip
            assertRoundTrip(PlutusData.constr(Integer.MAX_VALUE));
        }

        @Test
        void constrTagOverflowThrows() {
            // Tag > Integer.MAX_VALUE in general form
            // tag 102 + array(2) + uint(2^32) + empty array
            // d866 82 1a 00000000 80 — but we need > Integer.MAX_VALUE
            // uint(3000000000) = 1b 00000000b2d05e00 exceeds int range
            long bigTag = 3_000_000_000L;
            var sb = new StringBuilder();
            sb.append("d866"); // tag(102)
            sb.append("82");   // array(2)
            // Encode bigTag as CBOR unsigned integer (1b prefix = 8-byte value)
            sb.append("1b");
            sb.append(String.format("%016x", bigTag));
            sb.append("80");   // array(0) for fields
            byte[] cbor = HEX.parseHex(sb.toString());
            assertThrows(CborDecodingException.class, () -> PlutusDataCborDecoder.decode(cbor));
        }

        @Test
        void deeplyNestedStructure() {
            // 100 levels of nesting: Constr(0, Constr(0, ... Constr(0, [42]) ...))
            PlutusData current = PlutusData.integer(42);
            for (int i = 0; i < 100; i++) {
                current = PlutusData.constr(0, current);
            }
            assertRoundTrip(current);
        }

        @Test
        void emptyBigNumByteString() {
            // tag 2 with empty byte string → IntData(0)
            byte[] cbor = HEX.parseHex("c240"); // tag 2 + empty byte string
            PlutusData decoded = PlutusDataCborDecoder.decode(cbor);
            assertEquals(PlutusData.integer(0), decoded);
        }

        @Test
        void constrIndefiniteLengthFields() {
            // Constr(0) with indefinite-length fields array (as some libraries produce)
            // tag(121) + indefinite array + int(1) + int(2) + break
            byte[] cbor = HEX.parseHex("d8799f0102ff");
            PlutusData decoded = PlutusDataCborDecoder.decode(cbor);
            assertEquals(PlutusData.constr(0, PlutusData.integer(1), PlutusData.integer(2)), decoded);
        }

        @Test
        void bigNumTag2SmallValue() {
            // tag 2 + 1-byte bytestring [0x2A] = 42
            byte[] cbor = HEX.parseHex("c2412a");
            PlutusData decoded = PlutusDataCborDecoder.decode(cbor);
            assertEquals(PlutusData.integer(42), decoded);
        }

        @Test
        void bigNumTag3SmallValue() {
            // tag 3 + 1-byte bytestring [0x00] = -(1+0) = -1
            byte[] cbor = HEX.parseHex("c34100");
            PlutusData decoded = PlutusDataCborDecoder.decode(cbor);
            assertEquals(PlutusData.integer(-1), decoded);
        }
    }

    @Nested
    class CrossValidation {

        @Test
        void roundTripWithChunkedBytes70() {
            // Decode a CCL-style chunked 70-byte string, verify value, re-encode
            var sb = new StringBuilder();
            sb.append("5f5840"); // indefinite + 64-byte chunk
            var fullData = new byte[70];
            for (int i = 0; i < 70; i++) fullData[i] = (byte) (i & 0xFF);
            // First 64 bytes
            for (int i = 0; i < 64; i++) sb.append(String.format("%02x", fullData[i]));
            sb.append("46"); // 6-byte chunk
            for (int i = 64; i < 70; i++) sb.append(String.format("%02x", fullData[i]));
            sb.append("ff"); // break

            byte[] cclCbor = HEX.parseHex(sb.toString());
            PlutusData decoded = PlutusDataCborDecoder.decode(cclCbor);
            assertEquals(PlutusData.bytes(fullData), decoded);

            // Re-encode should also produce chunked format
            byte[] reEncoded = PlutusDataCborEncoder.encode(decoded);
            PlutusData reDecoded = PlutusDataCborDecoder.decode(reEncoded);
            assertEquals(decoded, reDecoded);
        }

        @Test
        void roundTripWithBigInt() {
            // A big integer that requires tag 2 + multi-byte encoding
            var big = new BigInteger("123456789012345678901234567890");
            assertRoundTrip(PlutusData.integer(big));

            // Negative variant
            assertRoundTrip(PlutusData.integer(big.negate()));
        }

        @Test
        void toDataItemPreservesStructure() {
            // Verify that toDataItem produces a well-formed DataItem tree
            var data = PlutusData.constr(0,
                    PlutusData.integer(42),
                    PlutusData.bytes(new byte[]{0x01}),
                    PlutusData.list(PlutusData.integer(1)),
                    PlutusData.map(new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(2))));

            var dataItem = PlutusDataCborEncoder.toDataItem(data);
            assertNotNull(dataItem);
            assertTrue(dataItem.hasTag());
            assertEquals(121, dataItem.getTag().getValue()); // Constr 0 → tag 121

            // Round-trip through DataItem
            PlutusData fromDi = PlutusDataCborDecoder.fromDataItem(dataItem);
            assertEquals(data, fromDi);
        }
    }

    // ---- Helper ----

    private void assertRoundTrip(PlutusData original) {
        byte[] encoded = PlutusDataCborEncoder.encode(original);
        PlutusData decoded = PlutusDataCborDecoder.decode(encoded);
        assertEquals(original, decoded, "Round-trip failed for: " + original);
    }
}
