package com.bloxbean.cardano.julc.onchain.stdlib;

import java.math.BigInteger;
import java.util.List;

/**
 * Off-chain bitwise operation stubs.
 * <p>
 * Methods that delegate to Builtins with compatible signatures are in the on-chain
 * {@code com.bloxbean.cardano.julc.stdlib.lib.BitwiseLib}.
 * <p>
 * This file retains methods with fundamentally different signatures (BigInteger vs long)
 * or that have useful JVM implementations (readBit, countSetBits, findFirstSetBit).
 */
public final class BitwiseLib {

    private BitwiseLib() {}

    /**
     * Read a bit at a given index in a ByteString (off-chain, takes BigInteger index).
     * Uses big-endian bit ordering: index 0 is the most significant bit of the first byte.
     */
    public static boolean readBit(byte[] bs, BigInteger index) {
        int idx = index.intValueExact();
        int totalBits = bs.length * 8;
        if (idx < 0 || idx >= totalBits) {
            throw new IndexOutOfBoundsException(
                    "Bit index " + idx + " out of range for ByteString of " + totalBits + " bits");
        }
        int byteIndex = idx / 8;
        int bitOffset = 7 - (idx % 8);
        return ((bs[byteIndex] >> bitOffset) & 1) == 1;
    }

    /**
     * Write bits at given indices in a ByteString.
     * Off-chain: not supported due to complex semantics (on-chain signature differs).
     */
    public static byte[] writeBits(byte[] bs, List<BigInteger> indices, List<Boolean> values) {
        throw new UnsupportedOperationException(
                "BitwiseLib.writeBits() is not supported off-chain.");
    }

    /**
     * Shift a ByteString by n bits (off-chain, takes BigInteger).
     * Off-chain: not supported.
     */
    public static byte[] shiftByteString(byte[] bs, BigInteger n) {
        throw new UnsupportedOperationException(
                "BitwiseLib.shiftByteString() is not supported off-chain.");
    }

    /**
     * Rotate a ByteString by n bits (off-chain, takes BigInteger).
     * Off-chain: not supported.
     */
    public static byte[] rotateByteString(byte[] bs, BigInteger n) {
        throw new UnsupportedOperationException(
                "BitwiseLib.rotateByteString() is not supported off-chain.");
    }

    /** Count set bits (off-chain, returns BigInteger; on-chain returns long). */
    public static BigInteger countSetBits(byte[] bs) {
        long count = 0;
        for (byte b : bs) {
            count += Integer.bitCount(Byte.toUnsignedInt(b));
        }
        return BigInteger.valueOf(count);
    }

    /** Find first set bit (off-chain, returns BigInteger; on-chain returns long). */
    public static BigInteger findFirstSetBit(byte[] bs) {
        for (int byteIdx = bs.length - 1; byteIdx >= 0; byteIdx--) {
            int unsignedByte = Byte.toUnsignedInt(bs[byteIdx]);
            if (unsignedByte != 0) {
                int lowestBit = Integer.numberOfTrailingZeros(unsignedByte);
                int distanceFromEnd = bs.length - 1 - byteIdx;
                return BigInteger.valueOf(distanceFromEnd * 8L + lowestBit);
            }
        }
        return BigInteger.valueOf(-1);
    }
}
