package org.julclang.core.cbor;

import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import org.julclang.core.PlutusData;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
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

    // ---- Canonical indefinite-length List/Constr (issue #48) ----
    // Golden hex values verified byte-for-byte against cardano-client-lib 0.7.x and Scalus 0.17.0.
    // Non-empty lists and constructor fields MUST use indefinite-length arrays (0x9f ... 0xff);
    // empty collections stay definite (0x80). A regression here changes on-chain script hashes /
    // serialiseData output.

    @Test
    void listNonEmptyIsIndefinite() {
        assertHex("9f0102ff", PlutusData.list(PlutusData.integer(1), PlutusData.integer(2)));
    }

    @Test
    void listEmptyIsDefinite() {
        assertHex("80", PlutusData.list());
    }

    @Test
    void constrNonEmptyFieldsAreIndefinite() {
        assertHex("d8799f182aff", PlutusData.constr(0, PlutusData.integer(42)));
        assertHex("d8799f0102ff", PlutusData.constr(0, PlutusData.integer(1), PlutusData.integer(2)));
        assertHex("d905009f01ff", PlutusData.constr(7, PlutusData.integer(1)));
    }

    @Test
    void constrEmptyFieldsAreDefinite() {
        assertHex("d87980", PlutusData.constr(0));
    }

    @Test
    void constrGeneralFormHasDefiniteOuterIndefiniteInner() {
        // tag 130 -> CBOR tag 102, [130, 9f 05 ff]: outer 2-elem array definite, inner fields indefinite
        assertHex("d8668218829f05ff", PlutusData.constr(130, PlutusData.integer(5)));
    }

    @Test
    void nestedConstrListBytesMatchesCanonical() {
        assertHex("d8799f9fd87a9f09ffff41abff",
                PlutusData.constr(0,
                        PlutusData.list(PlutusData.constr(1, PlutusData.integer(9))),
                        PlutusData.bytes(new byte[]{(byte) 0xab})));
    }

    @Test
    void constrWithLongByteStringHasBothBreaks() {
        // >64-byte bytestring chunks (indefinite bytestring) inside indefinite constr fields:
        // both the array and the bytestring must close with 0xff.
        byte[] big = new byte[100];
        for (int i = 0; i < big.length; i++) big[i] = (byte) i;
        byte[] encoded = PlutusDataCborEncoder.encode(PlutusData.constr(0, PlutusData.bytes(big)));
        String hex = HEX.formatHex(encoded);
        assertEquals("d8799f", hex.substring(0, 6), "constr tag + indefinite array start");
        assertEquals("ffff", hex.substring(hex.length() - 4), "bytestring break then array break");
        assertRoundTrip(PlutusData.constr(0, PlutusData.bytes(big)));
    }

    private static void assertHex(String expected, PlutusData data) {
        assertEquals(expected, HEX.formatHex(PlutusDataCborEncoder.encode(data)));
        // and confirm the canonical bytes still decode back to the same value
        assertEquals(data, PlutusDataCborDecoder.decode(PlutusDataCborEncoder.encode(data)));
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
        // Constr(0, [42]) → tag(121) + INDEFINITE-length array([42]) per canonical Plutus Data.
        byte[] encoded = PlutusDataCborEncoder.encode(PlutusData.constr(0, PlutusData.integer(42)));
        assertEquals("d879" + "9f" + "182a" + "ff", HEX.formatHex(encoded));
        // d8 79 = tag(121), 9f = indefinite array start, 18 2a = uint(42), ff = break
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
    class MapOrderPreservation {
        // Canonical Plutus Data preserves map entry order and duplicate keys (#54): the on-chain
        // serialiseData folds the entry list as-is and the ledger memoizes Plutus-data bytes.
        // Canonical key sorting is a transaction-body concern handled by cardano-client-lib, NOT
        // the Plutus-data encoder. Golden values verified against DataSerializer (Java VM) and the
        // Plutus Data.hs reference. A regression changes on-chain script hashes / serialiseData output.

        @Test
        void mapPreservesInsertionOrder() {
            // keys [int(2), int(1)] stay in that order — NOT sorted to int(1) first
            var data = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(2), PlutusData.integer(20)),
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)));
            // a2 = map(2), 02 14 = {2:20}, 01 0a = {1:10}
            assertEquals("a20214010a", HEX.formatHex(PlutusDataCborEncoder.encode(data)));
        }

        @Test
        void mapMixedTypeKeysPreserveOrder() {
            // keys [bytes(#01), int(1)] stay in insertion order — no shorter-key-first sorting
            var data = PlutusData.map(
                    new PlutusData.Pair(PlutusData.bytes(new byte[]{0x01}), PlutusData.integer(2)),
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(1)));
            // a2, 41 01 = bytes(#01) key, 02 = value; 01 = int(1) key, 01 = value
            assertEquals("a24101020101", HEX.formatHex(PlutusDataCborEncoder.encode(data)));
        }

        @Test
        void mapPreservesDuplicateKeys() {
            // {1:10, 1:20} keeps BOTH entries — Plutus Data allows duplicate keys
            var data = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)),
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(20)));
            assertEquals("a2010a0114", HEX.formatHex(PlutusDataCborEncoder.encode(data)));
        }

        @Test
        void nestedMapsPreserveOrderAndDuplicateKeys() {
            var inner = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)),
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(20)));
            var data = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(2), inner),
                    new PlutusData.Pair(
                            PlutusData.integer(1),
                            PlutusData.list(PlutusData.integer(3))));

            assertEquals("a202a2010a0114019f03ff", HEX.formatHex(PlutusDataCborEncoder.encode(data)));
            assertEquals(data, PlutusDataCborDecoder.fromDataItem(PlutusDataCborEncoder.toDataItem(data)));
        }

        @Test
        void mapLengthAboveDirectArgumentRangeIsEncodedCorrectly() {
            var entries = new ArrayList<PlutusData.Pair>();
            for (int i = 0; i < 30; i++) {
                entries.add(new PlutusData.Pair(PlutusData.integer(i), PlutusData.integer(i + 1)));
            }
            var data = new PlutusData.MapData(entries);

            byte[] encoded = PlutusDataCborEncoder.encode(data);
            assertEquals("b81e", HEX.formatHex(Arrays.copyOf(encoded, 2)));
            assertEquals(data, PlutusDataCborDecoder.decode(encoded));
        }

        @Test
        void toDataItemMapIsCompatibleWithStandardCborEncoder() throws CborException {
            var data = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(2), PlutusData.integer(20)),
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)));

            DataItem dataItem = PlutusDataCborEncoder.toDataItem(data);
            assertInstanceOf(Map.class, dataItem);

            // A stock cbor-java encoder uses its normal canonical Map view. This differs from
            // Julc's authoritative Plutus encoding, but must remain valid and must not throw.
            assertEquals("a2010a0214", HEX.formatHex(encodeWithStandardCborEncoder(dataItem)));
            assertEquals("a20214010a", HEX.formatHex(PlutusDataCborEncoder.encode(data)));
        }

        @Test
        void standardCborEncoderGetsValidDeduplicatedMapView() throws CborException {
            var data = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)),
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(20)));

            DataItem dataItem = PlutusDataCborEncoder.toDataItem(data);

            // cbor-java's Map retains the last value for an equal key. Keeping this compatibility
            // view separate prevents its canonical encoder from emitting a mismatched map length.
            byte[] standardCbor = encodeWithStandardCborEncoder(dataItem);
            assertEquals("a10114", HEX.formatHex(standardCbor));
            assertDoesNotThrow(() -> PlutusDataCborDecoder.decode(standardCbor));

            // Direct Julc conversion and on-chain serialization remain lossless.
            assertEquals(data, PlutusDataCborDecoder.fromDataItem(dataItem));
            assertEquals("a2010a0114", HEX.formatHex(PlutusDataCborEncoder.encode(data)));
        }

        @Test
        void mapRoundTripPreservesOrder() {
            // out-of-order keys stay in input order through encode/decode (no sorting)
            var data = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(100), PlutusData.integer(1)),
                    new PlutusData.Pair(PlutusData.integer(2), PlutusData.integer(2)),
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(3)));
            var decoded = PlutusDataCborDecoder.decode(PlutusDataCborEncoder.encode(data));
            assertInstanceOf(PlutusData.MapData.class, decoded);
            var entries = ((PlutusData.MapData) decoded).entries();
            assertEquals(3, entries.size());
            assertEquals(PlutusData.integer(100), entries.get(0).key());  // input order preserved
            assertEquals(PlutusData.integer(2), entries.get(1).key());
            assertEquals(PlutusData.integer(1), entries.get(2).key());
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
        void constrTagAboveIntRangeRoundTrips() {
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
            var decoded = assertInstanceOf(
                    PlutusData.ConstrData.class, PlutusDataCborDecoder.decode(cbor));
            assertEquals(BigInteger.valueOf(bigTag), decoded.constructorTag());
            byte[] canonical = PlutusDataCborEncoder.encode(decoded);
            assertEquals("d866821ab2d05e0080", HEX.formatHex(canonical));
            assertEquals(decoded, PlutusDataCborDecoder.decode(canonical));
        }

        @Test
        void constrTagMaxWord64RoundTrips() {
            BigInteger maximum = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
            var data = PlutusData.ConstrData.fromUnsignedTag(maximum, List.of());

            byte[] encoded = PlutusDataCborEncoder.encode(data);
            assertEquals("d866821bffffffffffffffff80", HEX.formatHex(encoded));
            assertEquals(data, PlutusDataCborDecoder.decode(encoded));
        }

        @Test
        void constrDecoderRejectsNegativeTagLikePinnedHaskell() {
            // tag(102), [negative integer -1, []]
            byte[] encoded = HEX.parseHex("d866822080");
            assertThrows(CborDecodingException.class,
                    () -> PlutusDataCborDecoder.decode(encoded));
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

        @Test
        void toDataItemSerializesIndefiniteArraysWithStandardCborEncoder() throws CborException {
            var list = PlutusData.list(PlutusData.integer(1));
            byte[] listCbor = encodeWithStandardCborEncoder(PlutusDataCborEncoder.toDataItem(list));
            assertEquals("9f01ff", HEX.formatHex(listCbor));
            assertEquals(list, PlutusDataCborDecoder.decode(listCbor));

            var constr = PlutusData.constr(0, PlutusData.integer(1));
            byte[] constrCbor = encodeWithStandardCborEncoder(PlutusDataCborEncoder.toDataItem(constr));
            assertEquals("d8799f01ff", HEX.formatHex(constrCbor));
            assertEquals(constr, PlutusDataCborDecoder.decode(constrCbor));
        }

        @Test
        void indefiniteArraysNestedInMapsRetainTheirBreak() {
            var listValue = PlutusData.map(new PlutusData.Pair(
                    PlutusData.integer(1),
                    PlutusData.list(PlutusData.integer(2), PlutusData.integer(3))));
            assertEquals("a1019f0203ff", HEX.formatHex(PlutusDataCborEncoder.encode(listValue)));
            assertRoundTrip(listValue);

            var constrKey = PlutusData.map(new PlutusData.Pair(
                    PlutusData.constr(0, PlutusData.integer(7)),
                    PlutusData.integer(1)));
            assertEquals("a1d8799f07ff01", HEX.formatHex(PlutusDataCborEncoder.encode(constrKey)));
            assertRoundTrip(constrKey);
        }
    }

    /**
     * Golden corpus for issue #229: the bytes plutus-core's {@code encodeData} writes for each shape.
     * <p>
     * Each value is derived by hand from plutus 1.65.0.0 {@code PlutusCore/Data.hs:146-198} and cborg
     * {@code Codec/CBOR/Write.hs} (a CBOR head is {@code major << 5 | argument}, with the shortest argument),
     * then cross-checked against the oracle in {@code src/test/resources/plutus-oracle} ({@code Golden.hs},
     * output {@code golden.tsv}): that {@code encodeData} and {@code decodeData}, copied verbatim, run on the
     * real cborg 0.2.8.0 and serialise 0.2.6.0. The decode column is the {@code decodeData} verdict: a tag 102
     * constructor tag must be a Word64 ({@code Data.hs:296-299}), so a bignum or negative tag encodes but does
     * not decode.
     */
    @Nested
    class PlutusEncodeDataGoldenCorpus {

        private static final BigInteger WORD64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

        record Case(String name, PlutusData data, String hex, boolean plutusDecodes) {}

        private static BigInteger pow2(int exponent) {
            return BigInteger.ONE.shiftLeft(exponent);
        }

        private static PlutusData constr(BigInteger tag, PlutusData... fields) {
            return new PlutusData.ConstrData(tag, List.of(fields));
        }

        private static PlutusData integer(BigInteger value) {
            return PlutusData.integer(value);
        }

        /** Bytes 0, 1, ..., n-1. */
        private static byte[] sequence(int n) {
            var bytes = new byte[n];
            for (int i = 0; i < n; i++) bytes[i] = (byte) i;
            return bytes;
        }

        private static String hexSequence(int from, int to) {
            return HEX.formatHex(Arrays.copyOfRange(sequence(to), from, to));
        }

        private static List<Case> corpus() {
            // 2^528 is 67 bytes, 01 then 66 zeros: two chunks, 64 bytes (5840) and 3 bytes (43)
            String chunked2Pow528 = "c25f5840" + "01" + "00".repeat(63) + "43" + "00".repeat(3) + "ff";
            return List.of(
                    // I: in the 64-bit CBOR range cborg writes a plain integer (major 0, or major 1 with -1-n)
                    new Case("int 0", integer(BigInteger.ZERO), "00", true),
                    new Case("int 2^63", integer(pow2(63)), "1b8000000000000000", true),
                    new Case("int -2^63", integer(pow2(63).negate()), "3b7fffffffffffffff", true),
                    new Case("int 2^64-1", integer(WORD64_MAX), "1b" + "ff".repeat(8), true),
                    new Case("int -(2^64-1)", integer(WORD64_MAX.negate()), "3b" + "ff".repeat(7) + "fe", true),
                    new Case("int -2^64", integer(pow2(64).negate()), "3b" + "ff".repeat(8), true),
                    // Outside it, tag 2 (c2) or tag 3 (c3, of -1-n) and encodeBs of the minimal big-endian bytes
                    new Case("int 2^64", integer(pow2(64)), "c249" + "01" + "00".repeat(8), true),
                    new Case("int -2^64-1", integer(pow2(64).negate().subtract(BigInteger.ONE)),
                            "c349" + "01" + "00".repeat(8), true),
                    new Case("int 2^511", integer(pow2(511)), "c25840" + "80" + "00".repeat(63), true),
                    new Case("int 2^512", integer(pow2(512)),
                            "c25f5840" + "01" + "00".repeat(63) + "4100" + "ff", true),
                    new Case("int 2^528", integer(pow2(528)), chunked2Pow528, true),
                    // -2^528 is stored as 2^528 - 1: 66 bytes of ff
                    new Case("int -2^528", integer(pow2(528).negate()),
                            "c35f5840" + "ff".repeat(64) + "42ffff" + "ff", true),
                    // B: encodeBs, definite up to 64 bytes, then 5f, 64-byte chunks, ff
                    new Case("bytes 0", PlutusData.bytes(new byte[0]), "40", true),
                    new Case("bytes 64", PlutusData.bytes(sequence(64)), "5840" + hexSequence(0, 64), true),
                    new Case("bytes 65", PlutusData.bytes(sequence(65)),
                            "5f5840" + hexSequence(0, 64) + "4140" + "ff", true),
                    new Case("bytes 128", PlutusData.bytes(sequence(128)),
                            "5f5840" + hexSequence(0, 64) + "5840" + hexSequence(64, 128) + "ff", true),
                    // Constr: tag 121+i (d879..d87f) for 0-6, tag 1280+(i-7) (d90500..d90578) for 7-127
                    new Case("constr 0", constr(BigInteger.ZERO), "d87980", true),
                    new Case("constr 6", constr(BigInteger.valueOf(6), PlutusData.integer(1)), "d87f9f01ff", true),
                    new Case("constr 7", constr(BigInteger.valueOf(7), PlutusData.integer(1)), "d905009f01ff", true),
                    new Case("constr 127", constr(BigInteger.valueOf(127)), "d9057880", true),
                    // Otherwise tag 102 (d866), a definite pair (82), the tag, then the fields
                    new Case("constr 128", constr(BigInteger.valueOf(128), PlutusData.integer(5)),
                            "d8668218809f05ff", true),
                    new Case("constr 2^64-1", constr(WORD64_MAX), "d866821b" + "ff".repeat(8) + "80", true),
                    // Beyond Word64 the tag is cborg encodeInteger: one definite byte string, never chunked
                    new Case("constr 2^64", constr(pow2(64)), "d86682c249" + "01" + "00".repeat(8) + "80", false),
                    new Case("constr 2^511", constr(pow2(511)), "d86682c25840" + "80" + "00".repeat(63) + "80", false),
                    new Case("constr 2^512", constr(pow2(512), PlutusData.integer(1)),
                            "d86682c25841" + "01" + "00".repeat(64) + "9f01ff", false),
                    new Case("constr 2^600", constr(pow2(600)), "d86682c2584c" + "01" + "00".repeat(75) + "80", false),
                    // A negative tag is not in 0..127, so it takes the general form too (Data.hs:151-160)
                    new Case("constr -1", constr(BigInteger.ONE.negate(), PlutusData.integer(1)),
                            "d86682209f01ff", false),
                    new Case("constr -2^64", constr(pow2(64).negate()), "d866823b" + "ff".repeat(8) + "80", false),
                    new Case("constr -2^600", constr(pow2(600).negate()),
                            "d86682c3584b" + "ff".repeat(75) + "80", false),
                    // List: serialise defaultEncodeList, 80 when empty, else 9f ... ff
                    new Case("list empty", PlutusData.list(), "80", true),
                    new Case("list [1,2]", PlutusData.list(PlutusData.integer(1), PlutusData.integer(2)),
                            "9f0102ff", true),
                    // Map: definite (a0+n), entries in order, duplicates kept
                    new Case("map empty", PlutusData.map(), "a0", true),
                    new Case("map duplicate keys", PlutusData.map(
                            new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)),
                            new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(20))), "a2010a0114", true),
                    new Case("map unsorted", PlutusData.map(
                            new PlutusData.Pair(PlutusData.integer(3), PlutusData.integer(30)),
                            new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10))), "a203181e010a", true),
                    new Case("nested", constr(BigInteger.ZERO,
                                    PlutusData.list(PlutusData.constr(1, PlutusData.integer(9))),
                                    PlutusData.bytes(new byte[]{(byte) 0xab}),
                                    PlutusData.map(new PlutusData.Pair(
                                            PlutusData.bytes(sequence(65)), PlutusData.list(integer(pow2(528))))),
                                    constr(pow2(600), PlutusData.map(), PlutusData.list())),
                            "d8799f" + "9fd87a9f09ffff" + "41ab"
                                    + "a1" + "5f5840" + hexSequence(0, 64) + "4140" + "ff"
                                    + "9f" + chunked2Pow528 + "ff"
                                    + "d86682c2584c" + "01" + "00".repeat(75) + "9fa080ff"
                                    + "ff",
                            false));
        }

        @Test
        void encodeMatchesPlutus() {
            for (var c : corpus()) {
                assertEquals(c.hex(), HEX.formatHex(PlutusDataCborEncoder.encode(c.data())), c.name());
            }
        }

        @Test
        void decodeAcceptsAndRejectsTheCorpusAsPlutusDoes() {
            for (var c : corpus()) {
                byte[] bytes = HEX.parseHex(c.hex());
                if (c.plutusDecodes()) {
                    assertEquals(c.data(), PlutusDataCborDecoder.decode(bytes), c.name());
                } else {
                    assertThrows(CborDecodingException.class, () -> PlutusDataCborDecoder.decode(bytes), c.name());
                }
            }
        }

        @Test
        void toDataItemRoundTrips() {
            for (var c : corpus()) {
                if (c.plutusDecodes()) {
                    DataItem item = PlutusDataCborEncoder.toDataItem(c.data());
                    assertEquals(c.data(), PlutusDataCborDecoder.fromDataItem(item), c.name());
                }
            }
        }

        @Test
        void toDataItemWritesAGeneralFormBignumTagAsOneDefiniteByteString() {
            var outer = assertInstanceOf(Array.class,
                    PlutusDataCborEncoder.toDataItem(constr(pow2(600))));
            var tag = assertInstanceOf(ByteString.class, outer.getDataItems().getFirst());
            assertEquals(2, tag.getTag().getValue());
            assertFalse(tag.isChunked());
            assertEquals(76, tag.getBytes().length);
        }
    }

    // ---- Helper ----

    private void assertRoundTrip(PlutusData original) {
        byte[] encoded = PlutusDataCborEncoder.encode(original);
        PlutusData decoded = PlutusDataCborDecoder.decode(encoded);
        assertEquals(original, decoded, "Round-trip failed for: " + original);
    }

    private static byte[] encodeWithStandardCborEncoder(DataItem dataItem) throws CborException {
        var output = new ByteArrayOutputStream();
        new CborEncoder(output).encode(dataItem);
        return output.toByteArray();
    }
}
