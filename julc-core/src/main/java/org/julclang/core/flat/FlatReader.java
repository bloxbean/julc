package org.julclang.core.flat;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Low-level bit-oriented reader for FLAT binary decoding.
 * <p>
 * Bits are read left-aligned within bytes (MSB first). The reader
 * automatically manages byte boundaries.
 */
public final class FlatReader {

    private final byte[] buffer;
    private int currPtr;     // index of the current byte
    private int usedBits;    // bits consumed in the current byte [0..7]

    public FlatReader(byte[] data) {
        this.buffer = data;
        this.currPtr = 0;
        this.usedBits = 0;
    }

    // --- Primitive bit operations ---

    /**
     * Read {@code numBits} bits, returning them in the low bits of the result.
     *
     * @param numBits number of bits to read (1-8)
     * @return the value read
     */
    public int bits8(int numBits) {
        if (numBits < 1 || numBits > 8) throw new IllegalArgumentException("numBits must be 1-8: " + numBits);
        checkAvailable();

        int currentByteVal = buffer[currPtr] & 0xFF;
        int available = 8 - usedBits;

        if (numBits <= available) {
            // All bits from current byte
            int value = (currentByteVal >> (available - numBits)) & ((1 << numBits) - 1);
            usedBits += numBits;
            if (usedBits == 8) {
                currPtr++;
                usedBits = 0;
            }
            return value;
        } else {
            // Bits span two bytes
            int bitsFromCurrent = available;
            int bitsFromNext = numBits - bitsFromCurrent;
            int upper = currentByteVal & ((1 << bitsFromCurrent) - 1);
            currPtr++;
            usedBits = 0;
            checkAvailable();
            int nextByteVal = buffer[currPtr] & 0xFF;
            int lower = (nextByteVal >> (8 - bitsFromNext)) & ((1 << bitsFromNext) - 1);
            usedBits = bitsFromNext;
            if (usedBits == 8) {
                currPtr++;
                usedBits = 0;
            }
            return (upper << bitsFromNext) | lower;
        }
    }

    /** Read a single bit. */
    public boolean bit() {
        return bits8(1) == 1;
    }

    /** Read a full byte. */
    public int byte_() {
        return bits8(8);
    }

    // --- Filler (alignment from byte boundary) ---

    /**
     * Read and consume a filler: skip zero bits until a 1-bit is found,
     * which aligns to the next byte boundary.
     */
    public void filler() {
        // Read bits until we find a 1
        while (!bit()) {
            // skip zero padding
        }
        // After finding the 1-bit, we should be byte-aligned
    }

    // --- Variable-length integer decoding ---

    /**
     * Decode a non-negative integer (Natural) from vli7 encoding.
     */
    public BigInteger natural() {
        return decodeVli7();
    }

    /**
     * Decode a signed integer from zigzag + vli7 encoding.
     */
    public BigInteger integer() {
        return zigZagDecode(decodeVli7());
    }

    /**
     * Decode an unsigned long (Word64) from vli7 encoding as plutus-core's {@code dWord64} does: at most
     * ten groups, no continuation bit on the tenth, and a tenth payload of at most one bit. A padded
     * encoding within ten groups is accepted; a longer one is rejected whatever value it would denote.
     */
    public long word64() {
        long value = 0;
        for (int shift = 0; shift < 63; shift += 7) {
            int b = byte_();
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        int b = byte_();
        if ((b & 0x80) != 0) {
            throw new FlatDecodingException("Word64 encoding has more than ten vli7 groups");
        }
        if (b > 1) {
            throw new FlatDecodingException("Word64 value exceeds 64 bits");
        }
        return value | (long) b << 63; // bit 63 set means a value above Long.MAX_VALUE, as two's complement
    }

    /**
     * Decode a vli7 integer of any size. plutus-core's {@code dUnsigned} checks the width only for
     * fixed-width types, so {@code Integer} and {@code Natural} are unbounded. Each group consumes an
     * input byte, and decoding is linear in the number of groups.
     */
    private BigInteger decodeVli7() {
        byte[] groups = new byte[10];
        int count = 0;
        int b;
        do {
            b = byte_();
            if (count == groups.length) {
                groups = Arrays.copyOf(groups, count * 2);
            }
            groups[count++] = (byte) (b & 0x7F);
        } while ((b & 0x80) != 0);

        // The groups come least significant first; pack them into a big-endian magnitude.
        byte[] magnitude = new byte[(count * 7 + 7) / 8];
        int pos = magnitude.length;
        long acc = 0;
        int bits = 0;
        for (int i = 0; i < count; i++) {
            acc |= (long) groups[i] << bits;
            bits += 7;
            if (bits >= 8) {
                magnitude[--pos] = (byte) acc;
                acc >>>= 8;
                bits -= 8;
            }
        }
        if (bits > 0) {
            magnitude[--pos] = (byte) acc;
        }
        return new BigInteger(1, magnitude);
    }

    static BigInteger zigZagDecode(BigInteger encoded) {
        if (encoded.testBit(0)) {
            // Odd → negative: -(encoded + 1) / 2
            return encoded.add(BigInteger.ONE).shiftRight(1).negate();
        } else {
            // Even → positive: encoded / 2
            return encoded.shiftRight(1);
        }
    }

    // --- ByteString decoding (pre-aligned, chunked) ---

    /**
     * Decode a byte array from FLAT's pre-aligned chunked format.
     */
    public byte[] byteString() {
        filler(); // align to byte boundary
        var baos = new java.io.ByteArrayOutputStream();
        while (true) {
            int chunkSize = byte_();
            if (chunkSize == 0) break;
            for (int i = 0; i < chunkSize; i++) {
                baos.write(byte_());
            }
        }
        return baos.toByteArray();
    }

    /**
     * Decode a UTF-8 string (from ByteString of UTF-8 bytes).
     */
    public String utf8String() {
        return new String(byteString(), StandardCharsets.UTF_8);
    }

    // --- List decoding (cons-cell style) ---

    /**
     * Read the next list element marker.
     *
     * @return true if more elements follow, false if end of list
     */
    public boolean listHasNext() {
        return bit();
    }

    // --- State ---

    /** Current bit position in the stream. */
    public int bitPosition() {
        return currPtr * 8 + usedBits;
    }

    /** Check if there are more bits available. */
    public boolean hasMore() {
        return currPtr < buffer.length;
    }

    private void checkAvailable() {
        if (currPtr >= buffer.length) {
            throw new FlatDecodingException("Unexpected end of FLAT data at byte " + currPtr);
        }
    }
}
