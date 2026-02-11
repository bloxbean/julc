package com.bloxbean.cardano.julc.core.flat;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

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
     * Decode an unsigned long (Word64) from vli7 encoding.
     */
    public long word64() {
        BigInteger value = decodeVli7();
        if (value.bitLength() > 64) {
            throw new FlatDecodingException("Word64 value exceeds 64 bits: " + value);
        }
        return value.longValue(); // correctly handles unsigned values via two's complement
    }

    /** Maximum number of bytes for a vli7-encoded integer (prevents DoS from malicious input). */
    private static final int MAX_VLI_BYTES = 128; // 128 * 7 = 896 bits, far exceeding any realistic use

    private BigInteger decodeVli7() {
        var result = BigInteger.ZERO;
        int shift = 0;
        int bytesRead = 0;
        while (true) {
            if (bytesRead++ > MAX_VLI_BYTES) {
                throw new FlatDecodingException(
                        "Variable-length integer exceeded maximum size of " + MAX_VLI_BYTES + " bytes");
            }
            int b = byte_();
            result = result.or(BigInteger.valueOf(b & 0x7F).shiftLeft(shift));
            shift += 7;
            if ((b & 0x80) == 0) {
                return result;
            }
        }
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
