package com.bloxbean.cardano.plutus.core.flat;

import java.nio.charset.StandardCharsets;
import java.math.BigInteger;

/**
 * Low-level bit-oriented writer for FLAT binary encoding.
 * <p>
 * Bits are written left-aligned within bytes (MSB first). The writer
 * automatically manages byte boundaries and provides padding/alignment.
 */
public final class FlatWriter {

    private byte[] buffer;
    private int nextPtr;      // index of the next byte to fill
    private int usedBits;     // bits used in the current byte [0..8]
    private int currentByte;  // accumulating byte

    public FlatWriter() {
        this(4096);
    }

    public FlatWriter(int initialCapacity) {
        this.buffer = new byte[initialCapacity];
        this.nextPtr = 0;
        this.usedBits = 0;
        this.currentByte = 0;
    }

    // --- Primitive bit operations ---

    /**
     * Write {@code numBits} bits from the low bits of {@code value}.
     *
     * @param numBits number of bits to write (1-8)
     * @param value   the value whose lowest {@code numBits} are written
     */
    public void bits(int numBits, int value) {
        if (numBits < 1 || numBits > 8) throw new IllegalArgumentException("numBits must be 1-8: " + numBits);

        int totalUsed = usedBits + numBits;
        // Mask the value to numBits
        int masked = value & ((1 << numBits) - 1);

        if (totalUsed <= 8) {
            // Fits in the current byte — shift left to align
            currentByte |= masked << (8 - totalUsed);
            usedBits = totalUsed;
            if (usedBits == 8) {
                flushByte();
            }
        } else {
            // Spans two bytes
            int bitsInCurrent = 8 - usedBits;
            int bitsInNext = numBits - bitsInCurrent;
            // Upper bits go into current byte
            currentByte |= (masked >> bitsInNext) & ((1 << bitsInCurrent) - 1);
            flushByte();
            // Lower bits go into next byte
            currentByte = (masked & ((1 << bitsInNext) - 1)) << (8 - bitsInNext);
            usedBits = bitsInNext;
        }
    }

    /** Write a single bit (0 or 1). */
    public void bit(boolean value) {
        bits(1, value ? 1 : 0);
    }

    /** Write a full byte. */
    public void byte_(int value) {
        bits(8, value & 0xFF);
    }

    // --- Filler (alignment to byte boundary) ---

    /**
     * Write a filler: zero-padding followed by a 1-bit at the LSB to align to the next byte boundary.
     * <p>
     * The convention is: the current byte's remaining low bits are 0, except the very last bit
     * (LSB, bit 0) which is set to 1. This byte is then flushed.
     */
    public void filler() {
        // Set the LSB of the current byte to 1 and flush
        currentByte |= 1;
        flushByte();
    }

    // --- Variable-length integer encoding ---

    /**
     * Encode a non-negative integer (Natural) using variable-length 7-bit encoding.
     * Each byte: bit 7 = continuation flag, bits 6-0 = 7 data bits.
     * Most significant chunks first.
     */
    public void natural(BigInteger value) {
        if (value.signum() < 0) throw new IllegalArgumentException("Natural must be non-negative: " + value);
        encodeVli7(value);
    }

    /**
     * Encode a signed integer using zigzag + vli7 encoding.
     */
    public void integer(BigInteger value) {
        encodeVli7(zigZagEncode(value));
    }

    /**
     * Encode an unsigned long (Word64) using vli7 encoding (no zigzag).
     * Values above Long.MAX_VALUE are represented as negative Java longs (two's complement).
     */
    public void word64(long value) {
        // Treat value as unsigned 64-bit: convert via toUnsignedString to BigInteger
        encodeVli7(new BigInteger(Long.toUnsignedString(value)));
    }

    private void encodeVli7(BigInteger value) {
        if (value.signum() == 0) {
            byte_(0);
            return;
        }
        // Write 7-bit chunks least significant first (standard LEB128 order)
        var v = value;
        while (v.signum() > 0) {
            int chunk = v.and(BigInteger.valueOf(0x7F)).intValue();
            v = v.shiftRight(7);
            if (v.signum() > 0) {
                byte_(0x80 | chunk); // continuation bit set — more chunks follow
            } else {
                byte_(chunk); // last chunk, no continuation
            }
        }
    }

    static BigInteger zigZagEncode(BigInteger value) {
        if (value.signum() >= 0) {
            return value.shiftLeft(1);
        } else {
            return value.shiftLeft(1).negate().subtract(BigInteger.ONE);
        }
    }

    // --- ByteString encoding (pre-aligned, chunked) ---

    /**
     * Encode a byte array using FLAT's pre-aligned chunked format.
     * Writes a filler for alignment, then 255-byte chunks, then a 0x00 terminator.
     */
    public void byteString(byte[] data) {
        filler(); // align to byte boundary
        int offset = 0;
        while (offset < data.length) {
            int chunkSize = Math.min(255, data.length - offset);
            byte_(chunkSize);
            for (int i = 0; i < chunkSize; i++) {
                byte_(data[offset + i] & 0xFF);
            }
            offset += chunkSize;
        }
        byte_(0); // terminator
    }

    /**
     * Encode a UTF-8 string (as a ByteString of UTF-8 bytes).
     */
    public void utf8String(String value) {
        byteString(value.getBytes(StandardCharsets.UTF_8));
    }

    // --- List encoding (cons-cell style) ---

    /**
     * Write a list start marker (1-bit = more elements follow).
     */
    public void listCons() {
        bit(true);
    }

    /**
     * Write a list end marker (0-bit = no more elements).
     */
    public void listNil() {
        bit(false);
    }

    // --- Result ---

    /**
     * Finalize and return the encoded bytes.
     * Flushes any remaining partial byte.
     */
    public byte[] toByteArray() {
        int totalBytes = nextPtr;
        if (usedBits > 0) {
            totalBytes++; // include partial byte
        }
        var result = new byte[totalBytes];
        System.arraycopy(buffer, 0, result, 0, nextPtr);
        if (usedBits > 0) {
            result[nextPtr] = (byte) currentByte;
        }
        return result;
    }

    // --- Internal ---

    private void flushByte() {
        ensureCapacity();
        buffer[nextPtr++] = (byte) currentByte;
        currentByte = 0;
        usedBits = 0;
    }

    private void ensureCapacity() {
        if (nextPtr >= buffer.length) {
            var newBuffer = new byte[buffer.length * 2];
            System.arraycopy(buffer, 0, newBuffer, 0, buffer.length);
            buffer = newBuffer;
        }
    }
}
