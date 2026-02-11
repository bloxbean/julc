package com.bloxbean.cardano.julc.onchain.stdlib;

import java.math.BigInteger;
import java.util.List;

/**
 * On-chain bitwise operations on ByteStrings.
 * <p>
 * These methods are executable on-chain (compiled to UPLC via StdlibRegistry).
 * Most methods also have off-chain JVM implementations for testing and debugging.
 * <p>
 * Methods with complex semantics that are hard to match exactly on-chain
 * (writeBits, shiftByteString, rotateByteString) still throw
 * UnsupportedOperationException off-chain.
 */
public final class BitwiseLib {

    private BitwiseLib() {}

    /**
     * Bitwise AND of two ByteStrings.
     * <p>
     * When semantics is true (padding mode), the shorter array is zero-padded on the left
     * to match the length of the longer array. When semantics is false (truncation mode),
     * the longer array is truncated on the left to match the length of the shorter array.
     *
     * @param semantics true = pad shorter with zeros, false = truncate longer
     * @param a         first ByteString
     * @param b         second ByteString
     * @return bitwise AND result
     */
    public static byte[] andByteString(boolean semantics, byte[] a, byte[] b) {
        byte[][] aligned = alignByteStrings(semantics, a, b);
        byte[] x = aligned[0];
        byte[] y = aligned[1];
        byte[] result = new byte[x.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) (x[i] & y[i]);
        }
        return result;
    }

    /**
     * Bitwise OR of two ByteStrings.
     * <p>
     * When semantics is true (padding mode), the shorter array is zero-padded on the left
     * to match the length of the longer array. When semantics is false (truncation mode),
     * the longer array is truncated on the left to match the length of the shorter array.
     *
     * @param semantics true = pad shorter with zeros, false = truncate longer
     * @param a         first ByteString
     * @param b         second ByteString
     * @return bitwise OR result
     */
    public static byte[] orByteString(boolean semantics, byte[] a, byte[] b) {
        byte[][] aligned = alignByteStrings(semantics, a, b);
        byte[] x = aligned[0];
        byte[] y = aligned[1];
        byte[] result = new byte[x.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) (x[i] | y[i]);
        }
        return result;
    }

    /**
     * Bitwise XOR of two ByteStrings.
     * <p>
     * When semantics is true (padding mode), the shorter array is zero-padded on the left
     * to match the length of the longer array. When semantics is false (truncation mode),
     * the longer array is truncated on the left to match the length of the shorter array.
     *
     * @param semantics true = pad shorter with zeros, false = truncate longer
     * @param a         first ByteString
     * @param b         second ByteString
     * @return bitwise XOR result
     */
    public static byte[] xorByteString(boolean semantics, byte[] a, byte[] b) {
        byte[][] aligned = alignByteStrings(semantics, a, b);
        byte[] x = aligned[0];
        byte[] y = aligned[1];
        byte[] result = new byte[x.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) (x[i] ^ y[i]);
        }
        return result;
    }

    /**
     * Align two byte arrays based on semantics.
     * <p>
     * If semantics is true (pad), the shorter array is zero-padded on the left
     * to match the longer. If semantics is false (truncate), the longer array
     * is truncated on the left to match the shorter.
     */
    private static byte[][] alignByteStrings(boolean pad, byte[] a, byte[] b) {
        if (a.length == b.length) {
            return new byte[][]{a, b};
        }
        if (pad) {
            // Pad the shorter one with leading zeros to match the longer
            int targetLen = Math.max(a.length, b.length);
            return new byte[][]{padLeft(a, targetLen), padLeft(b, targetLen)};
        } else {
            // Truncate the longer one from the left to match the shorter
            int targetLen = Math.min(a.length, b.length);
            return new byte[][]{truncateLeft(a, targetLen), truncateLeft(b, targetLen)};
        }
    }

    private static byte[] padLeft(byte[] bs, int targetLen) {
        if (bs.length >= targetLen) return bs;
        byte[] padded = new byte[targetLen];
        System.arraycopy(bs, 0, padded, targetLen - bs.length, bs.length);
        return padded;
    }

    private static byte[] truncateLeft(byte[] bs, int targetLen) {
        if (bs.length <= targetLen) return bs;
        byte[] truncated = new byte[targetLen];
        System.arraycopy(bs, bs.length - targetLen, truncated, 0, targetLen);
        return truncated;
    }

    /**
     * Bitwise complement of a ByteString (flip all bits).
     *
     * @param bs the ByteString
     * @return bitwise complement result
     */
    public static byte[] complementByteString(byte[] bs) {
        byte[] result = new byte[bs.length];
        for (int i = 0; i < bs.length; i++) {
            result[i] = (byte) ~bs[i];
        }
        return result;
    }

    /**
     * Read a bit at a given index in a ByteString.
     * <p>
     * Uses big-endian bit ordering: index 0 is the most significant bit of the first byte.
     *
     * @param bs    the ByteString
     * @param index the bit index (0 = MSB of first byte)
     * @return true if bit is set, false otherwise
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public static boolean readBit(byte[] bs, BigInteger index) {
        int idx = index.intValueExact();
        int totalBits = bs.length * 8;
        if (idx < 0 || idx >= totalBits) {
            throw new IndexOutOfBoundsException(
                    "Bit index " + idx + " out of range for ByteString of " + totalBits + " bits");
        }
        int byteIndex = idx / 8;
        int bitOffset = 7 - (idx % 8); // big-endian: bit 0 = MSB
        return ((bs[byteIndex] >> bitOffset) & 1) == 1;
    }

    /**
     * Write bits at given indices in a ByteString.
     * <p>
     * Off-chain: not supported due to complex semantics.
     *
     * @param bs      the ByteString
     * @param indices list of bit indices
     * @param values  list of boolean values
     * @return modified ByteString
     */
    public static byte[] writeBits(byte[] bs, List<BigInteger> indices, List<Boolean> values) {
        throw new UnsupportedOperationException(
                "BitwiseLib.writeBits() is not supported off-chain. "
                + "Use on-chain execution for bitwise operations.");
    }

    /**
     * Shift a ByteString by n bits.
     * <p>
     * Off-chain: not supported due to complex semantics.
     *
     * @param bs the ByteString
     * @param n  the shift amount (positive = left, negative = right)
     * @return shifted ByteString
     */
    public static byte[] shiftByteString(byte[] bs, BigInteger n) {
        throw new UnsupportedOperationException(
                "BitwiseLib.shiftByteString() is not supported off-chain. "
                + "Use on-chain execution for bitwise operations.");
    }

    /**
     * Rotate a ByteString by n bits.
     * <p>
     * Off-chain: not supported due to complex semantics.
     *
     * @param bs the ByteString
     * @param n  the rotation amount
     * @return rotated ByteString
     */
    public static byte[] rotateByteString(byte[] bs, BigInteger n) {
        throw new UnsupportedOperationException(
                "BitwiseLib.rotateByteString() is not supported off-chain. "
                + "Use on-chain execution for bitwise operations.");
    }

    /**
     * Count the number of set bits (1s) in a ByteString.
     *
     * @param bs the ByteString
     * @return count of set bits
     */
    public static BigInteger countSetBits(byte[] bs) {
        long count = 0;
        for (byte b : bs) {
            count += Integer.bitCount(Byte.toUnsignedInt(b));
        }
        return BigInteger.valueOf(count);
    }

    /**
     * Find the index of the first set bit in a ByteString.
     * <p>
     * Uses big-endian bit ordering with LSB-first indexing: index 0 is the least significant
     * bit of the last byte. Returns -1 if no bits are set.
     *
     * @param bs the ByteString
     * @return index of first set bit (LSB-first), or -1 if no bits are set
     */
    public static BigInteger findFirstSetBit(byte[] bs) {
        // LSB-first indexing: index 0 = LSB of last byte
        // Scan from the last byte towards the first
        for (int byteIdx = bs.length - 1; byteIdx >= 0; byteIdx--) {
            int unsignedByte = Byte.toUnsignedInt(bs[byteIdx]);
            if (unsignedByte != 0) {
                // Find the lowest set bit in this byte
                int lowestBit = Integer.numberOfTrailingZeros(unsignedByte);
                // Calculate the LSB-first index
                int distanceFromEnd = bs.length - 1 - byteIdx;
                return BigInteger.valueOf(distanceFromEnd * 8L + lowestBit);
            }
        }
        return BigInteger.valueOf(-1);
    }
}
