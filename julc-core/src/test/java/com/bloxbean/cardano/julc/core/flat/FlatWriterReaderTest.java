package com.bloxbean.cardano.julc.core.flat;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FlatWriter and FlatReader low-level bit operations.
 */
class FlatWriterReaderTest {

    // --- Bit writing and reading ---

    @Test
    void singleBitTrue() {
        var w = new FlatWriter();
        w.bit(true);
        w.filler();
        var bytes = w.toByteArray();

        var r = new FlatReader(bytes);
        assertTrue(r.bit());
    }

    @Test
    void singleBitFalse() {
        var w = new FlatWriter();
        w.bit(false);
        w.filler();
        var bytes = w.toByteArray();

        var r = new FlatReader(bytes);
        assertFalse(r.bit());
    }

    @Test
    void writeThenReadByte() {
        var w = new FlatWriter();
        w.byte_(0xAB);
        var bytes = w.toByteArray();
        assertEquals(1, bytes.length);
        assertEquals((byte) 0xAB, bytes[0]);

        var r = new FlatReader(bytes);
        assertEquals(0xAB, r.byte_());
    }

    @Test
    void write4BitsRoundTrip() {
        var w = new FlatWriter();
        w.bits(4, 0x0F); // 1111
        w.bits(4, 0x05); // 0101
        var bytes = w.toByteArray();
        assertEquals(1, bytes.length);
        assertEquals((byte) 0xF5, bytes[0]);

        var r = new FlatReader(bytes);
        assertEquals(0x0F, r.bits8(4));
        assertEquals(0x05, r.bits8(4));
    }

    @Test
    void writeBitsSpanningBytes() {
        var w = new FlatWriter();
        w.bits(3, 0x07); // 111
        w.bits(7, 0x55); // 1010101
        // total 10 bits: 1111010101 → padded to 2 bytes
        var bytes = w.toByteArray();
        assertEquals(2, bytes.length);

        var r = new FlatReader(bytes);
        assertEquals(0x07, r.bits8(3));
        assertEquals(0x55, r.bits8(7));
    }

    @Test
    void multipleBytes() {
        var w = new FlatWriter();
        w.byte_(0x12);
        w.byte_(0x34);
        w.byte_(0x56);
        var bytes = w.toByteArray();
        assertEquals(3, bytes.length);

        var r = new FlatReader(bytes);
        assertEquals(0x12, r.byte_());
        assertEquals(0x34, r.byte_());
        assertEquals(0x56, r.byte_());
    }

    // --- Filler ---

    @Test
    void fillerByteAligned() {
        // If already at byte boundary, filler writes 00000001 = 0x01
        var w = new FlatWriter();
        w.byte_(0xFF);
        w.filler();
        var bytes = w.toByteArray();
        assertEquals(2, bytes.length);
        assertEquals((byte) 0x01, bytes[1]);
    }

    @Test
    void fillerNotAligned() {
        // After 3 bits (111), filler sets LSB and flushes: 111_00001 = 0xE1
        var w = new FlatWriter();
        w.bits(3, 0x07); // 111
        w.filler();
        var bytes = w.toByteArray();
        assertEquals(1, bytes.length);
        assertEquals((byte) 0xE1, bytes[0]);
    }

    @Test
    void fillerRoundTrip() {
        var w = new FlatWriter();
        w.bits(3, 0x05);
        w.filler();
        w.byte_(0xAA);
        var bytes = w.toByteArray();

        var r = new FlatReader(bytes);
        assertEquals(0x05, r.bits8(3));
        r.filler();
        assertEquals(0xAA, r.byte_());
    }

    // --- Variable-length integers ---

    @Test
    void naturalZero() {
        var w = new FlatWriter();
        w.natural(BigInteger.ZERO);
        var bytes = w.toByteArray();

        var r = new FlatReader(bytes);
        assertEquals(BigInteger.ZERO, r.natural());
    }

    @Test
    void naturalSmall() {
        var w = new FlatWriter();
        w.natural(BigInteger.valueOf(42));
        var bytes = w.toByteArray();

        var r = new FlatReader(bytes);
        assertEquals(BigInteger.valueOf(42), r.natural());
    }

    @Test
    void naturalLarge() {
        var value = BigInteger.valueOf(1000000);
        var w = new FlatWriter();
        w.natural(value);
        var bytes = w.toByteArray();

        var r = new FlatReader(bytes);
        assertEquals(value, r.natural());
    }

    @Test
    void naturalVeryLarge() {
        var value = new BigInteger("99999999999999999999999");
        var w = new FlatWriter();
        w.natural(value);
        var bytes = w.toByteArray();

        var r = new FlatReader(bytes);
        assertEquals(value, r.natural());
    }

    @Test
    void integerPositive() {
        var w = new FlatWriter();
        w.integer(BigInteger.valueOf(42));
        var bytes = w.toByteArray();

        var r = new FlatReader(bytes);
        assertEquals(BigInteger.valueOf(42), r.integer());
    }

    @Test
    void integerNegative() {
        var w = new FlatWriter();
        w.integer(BigInteger.valueOf(-42));
        var bytes = w.toByteArray();

        var r = new FlatReader(bytes);
        assertEquals(BigInteger.valueOf(-42), r.integer());
    }

    @Test
    void integerZero() {
        var w = new FlatWriter();
        w.integer(BigInteger.ZERO);
        var bytes = w.toByteArray();

        var r = new FlatReader(bytes);
        assertEquals(BigInteger.ZERO, r.integer());
    }

    @Test
    void integerMinusOne() {
        var w = new FlatWriter();
        w.integer(BigInteger.valueOf(-1));
        var bytes = w.toByteArray();

        var r = new FlatReader(bytes);
        assertEquals(BigInteger.valueOf(-1), r.integer());
    }

    @Test
    void integerLargeNegative() {
        var value = new BigInteger("-99999999999999999999999");
        var w = new FlatWriter();
        w.integer(value);
        var bytes = w.toByteArray();

        var r = new FlatReader(bytes);
        assertEquals(value, r.integer());
    }

    // --- ZigZag encoding ---

    @Test
    void zigZagEncoding() {
        assertEquals(BigInteger.ZERO, FlatWriter.zigZagEncode(BigInteger.ZERO));           // 0 → 0
        assertEquals(BigInteger.ONE, FlatWriter.zigZagEncode(BigInteger.valueOf(-1)));       // -1 → 1
        assertEquals(BigInteger.TWO, FlatWriter.zigZagEncode(BigInteger.ONE));               // 1 → 2
        assertEquals(BigInteger.valueOf(3), FlatWriter.zigZagEncode(BigInteger.valueOf(-2)));  // -2 → 3
        assertEquals(BigInteger.valueOf(4), FlatWriter.zigZagEncode(BigInteger.valueOf(2)));   // 2 → 4
    }

    @Test
    void zigZagDecoding() {
        assertEquals(BigInteger.ZERO, FlatReader.zigZagDecode(BigInteger.ZERO));
        assertEquals(BigInteger.valueOf(-1), FlatReader.zigZagDecode(BigInteger.ONE));
        assertEquals(BigInteger.ONE, FlatReader.zigZagDecode(BigInteger.TWO));
        assertEquals(BigInteger.valueOf(-2), FlatReader.zigZagDecode(BigInteger.valueOf(3)));
        assertEquals(BigInteger.valueOf(2), FlatReader.zigZagDecode(BigInteger.valueOf(4)));
    }

    @Test
    void zigZagRoundTrip() {
        for (long v = -1000; v <= 1000; v++) {
            var value = BigInteger.valueOf(v);
            assertEquals(value, FlatReader.zigZagDecode(FlatWriter.zigZagEncode(value)),
                    "ZigZag round-trip failed for " + v);
        }
    }

    // --- Word64 ---

    @Test
    void word64Zero() {
        var w = new FlatWriter();
        w.word64(0);
        var bytes = w.toByteArray();

        var r = new FlatReader(bytes);
        assertEquals(0, r.word64());
    }

    @Test
    void word64SmallValue() {
        var w = new FlatWriter();
        w.word64(127);
        var bytes = w.toByteArray();

        var r = new FlatReader(bytes);
        assertEquals(127, r.word64());
    }

    @Test
    void word64LargeValue() {
        var w = new FlatWriter();
        w.word64(Long.MAX_VALUE);
        var bytes = w.toByteArray();

        var r = new FlatReader(bytes);
        assertEquals(Long.MAX_VALUE, r.word64());
    }

    // --- ByteString ---

    @Test
    void byteStringEmpty() {
        var w = new FlatWriter();
        w.byteString(new byte[]{});
        var bytes = w.toByteArray();

        var r = new FlatReader(bytes);
        assertArrayEquals(new byte[]{}, r.byteString());
    }

    @Test
    void byteStringSmall() {
        var data = new byte[]{0x01, 0x02, 0x03};
        var w = new FlatWriter();
        w.byteString(data);
        var bytes = w.toByteArray();

        var r = new FlatReader(bytes);
        assertArrayEquals(data, r.byteString());
    }

    @Test
    void byteStringChunked() {
        // 300 bytes → 255-byte chunk + 45-byte chunk
        var data = new byte[300];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i & 0xFF);

        var w = new FlatWriter();
        w.byteString(data);
        var bytes = w.toByteArray();

        var r = new FlatReader(bytes);
        assertArrayEquals(data, r.byteString());
    }

    @Test
    void byteStringExact255() {
        var data = new byte[255];
        for (int i = 0; i < 255; i++) data[i] = (byte) i;

        var w = new FlatWriter();
        w.byteString(data);
        var bytes = w.toByteArray();

        var r = new FlatReader(bytes);
        assertArrayEquals(data, r.byteString());
    }

    // --- UTF-8 String ---

    @Test
    void utf8StringEmpty() {
        var w = new FlatWriter();
        w.utf8String("");
        var bytes = w.toByteArray();

        var r = new FlatReader(bytes);
        assertEquals("", r.utf8String());
    }

    @Test
    void utf8StringAscii() {
        var w = new FlatWriter();
        w.utf8String("hello");
        var bytes = w.toByteArray();

        var r = new FlatReader(bytes);
        assertEquals("hello", r.utf8String());
    }

    @Test
    void utf8StringUnicode() {
        var w = new FlatWriter();
        w.utf8String("hello \u00e9\u00e8\u00ea");
        var bytes = w.toByteArray();

        var r = new FlatReader(bytes);
        assertEquals("hello \u00e9\u00e8\u00ea", r.utf8String());
    }

    // --- List cons/nil ---

    @Test
    void emptyList() {
        var w = new FlatWriter();
        w.listNil();
        w.filler();
        var bytes = w.toByteArray();

        var r = new FlatReader(bytes);
        assertFalse(r.listHasNext());
    }

    @Test
    void listWithElements() {
        var w = new FlatWriter();
        w.listCons();
        w.byte_(0x01);
        w.listCons();
        w.byte_(0x02);
        w.listNil();
        var bytes = w.toByteArray();

        var r = new FlatReader(bytes);
        assertTrue(r.listHasNext());
        assertEquals(0x01, r.byte_());
        assertTrue(r.listHasNext());
        assertEquals(0x02, r.byte_());
        assertFalse(r.listHasNext());
    }

    // --- Error handling ---

    @Test
    void readPastEnd() {
        var r = new FlatReader(new byte[]{});
        assertThrows(FlatDecodingException.class, r::bit);
    }

    @Test
    void naturalRejectsNegative() {
        var w = new FlatWriter();
        assertThrows(IllegalArgumentException.class, () -> w.natural(BigInteger.valueOf(-1)));
    }

    @Test
    void word64UnsignedMax() {
        // -1L as unsigned = 2^64 - 1 (max unsigned 64-bit value)
        var w = new FlatWriter();
        w.word64(-1L);
        var bytes = w.toByteArray();

        var r = new FlatReader(bytes);
        assertEquals(-1L, r.word64()); // round-trips correctly
    }

    // --- ByteString chunk edge cases ---

    @Test
    void byteString256Bytes() {
        // 256 = 255 + 1 → exactly two chunks
        var data = new byte[256];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i & 0xFF);
        var w = new FlatWriter();
        w.byteString(data);
        var bytes = w.toByteArray();
        var r = new FlatReader(bytes);
        assertArrayEquals(data, r.byteString());
    }

    @Test
    void byteString510Bytes() {
        // 510 = 255 + 255 → exactly two full chunks
        var data = new byte[510];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i & 0xFF);
        var w = new FlatWriter();
        w.byteString(data);
        var bytes = w.toByteArray();
        var r = new FlatReader(bytes);
        assertArrayEquals(data, r.byteString());
    }

    @Test
    void byteString511Bytes() {
        // 511 = 255 + 255 + 1 → three chunks
        var data = new byte[511];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i & 0xFF);
        var w = new FlatWriter();
        w.byteString(data);
        var bytes = w.toByteArray();
        var r = new FlatReader(bytes);
        assertArrayEquals(data, r.byteString());
    }

    @Test
    void byteStringLarge() {
        var data = new byte[10000];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i & 0xFF);
        var w = new FlatWriter();
        w.byteString(data);
        var bytes = w.toByteArray();
        var r = new FlatReader(bytes);
        assertArrayEquals(data, r.byteString());
    }

    // --- Consecutive byteStrings ---

    @Test
    void consecutiveByteStrings() {
        var w = new FlatWriter();
        w.byteString(new byte[]{1, 2, 3});
        w.byteString(new byte[]{4, 5});
        var bytes = w.toByteArray();

        var r = new FlatReader(bytes);
        assertArrayEquals(new byte[]{1, 2, 3}, r.byteString());
        assertArrayEquals(new byte[]{4, 5}, r.byteString());
    }

    // --- Filler when byte-aligned ---

    @Test
    void fillerAlwaysWritesAtLeastOneByte() {
        // Even when byte-aligned, filler writes 0x01 (matching Scalus behavior)
        var w = new FlatWriter();
        w.filler(); // at position 0 → writes 0x01
        w.byte_(0xAA);
        var bytes = w.toByteArray();
        assertEquals(2, bytes.length);
        assertEquals((byte) 0x01, bytes[0]);
        assertEquals((byte) 0xAA, bytes[1]);

        var r = new FlatReader(bytes);
        r.filler();
        assertEquals(0xAA, r.byte_());
    }

    // --- Error handling: malformed data ---

    @Test
    void decoderInvalidTermTag() {
        var w = new FlatWriter();
        w.bits(4, 15); // invalid term tag
        w.filler();
        var bytes = w.toByteArray();
        assertThrows(FlatDecodingException.class,
                () -> UplcFlatDecoder.decodeProgram(bytes));
    }
}
