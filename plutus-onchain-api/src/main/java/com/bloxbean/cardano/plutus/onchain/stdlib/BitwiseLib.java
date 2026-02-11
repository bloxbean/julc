package com.bloxbean.cardano.plutus.onchain.stdlib;

import java.math.BigInteger;
import java.util.List;

/**
 * On-chain bitwise operations on ByteStrings.
 * <p>
 * These methods are executable on-chain (compiled to UPLC via StdlibRegistry).
 * Off-chain implementations throw UnsupportedOperationException as bitwise operations
 * on ByteStrings have complex semantics that are difficult to replicate exactly in Java.
 * <p>
 * For testing purposes, use on-chain execution or mock these operations.
 */
public final class BitwiseLib {

    private BitwiseLib() {}

    /**
     * Bitwise AND of two ByteStrings.
     *
     * @param semantics padding semantics (boolean)
     * @param a         first ByteString
     * @param b         second ByteString
     * @return bitwise AND result
     */
    public static byte[] andByteString(boolean semantics, byte[] a, byte[] b) {
        throw new UnsupportedOperationException(
                "BitwiseLib.andByteString() is not supported off-chain. "
                + "Use on-chain execution for bitwise operations.");
    }

    /**
     * Bitwise OR of two ByteStrings.
     *
     * @param semantics padding semantics (boolean)
     * @param a         first ByteString
     * @param b         second ByteString
     * @return bitwise OR result
     */
    public static byte[] orByteString(boolean semantics, byte[] a, byte[] b) {
        throw new UnsupportedOperationException(
                "BitwiseLib.orByteString() is not supported off-chain. "
                + "Use on-chain execution for bitwise operations.");
    }

    /**
     * Bitwise XOR of two ByteStrings.
     *
     * @param semantics padding semantics (boolean)
     * @param a         first ByteString
     * @param b         second ByteString
     * @return bitwise XOR result
     */
    public static byte[] xorByteString(boolean semantics, byte[] a, byte[] b) {
        throw new UnsupportedOperationException(
                "BitwiseLib.xorByteString() is not supported off-chain. "
                + "Use on-chain execution for bitwise operations.");
    }

    /**
     * Bitwise complement of a ByteString.
     *
     * @param bs the ByteString
     * @return bitwise complement result
     */
    public static byte[] complementByteString(byte[] bs) {
        throw new UnsupportedOperationException(
                "BitwiseLib.complementByteString() is not supported off-chain. "
                + "Use on-chain execution for bitwise operations.");
    }

    /**
     * Read a bit at a given index in a ByteString.
     *
     * @param bs    the ByteString
     * @param index the bit index
     * @return true if bit is set, false otherwise
     */
    public static boolean readBit(byte[] bs, BigInteger index) {
        throw new UnsupportedOperationException(
                "BitwiseLib.readBit() is not supported off-chain. "
                + "Use on-chain execution for bitwise operations.");
    }

    /**
     * Write bits at given indices in a ByteString.
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
        throw new UnsupportedOperationException(
                "BitwiseLib.countSetBits() is not supported off-chain. "
                + "Use on-chain execution for bitwise operations.");
    }

    /**
     * Find the index of the first set bit in a ByteString.
     *
     * @param bs the ByteString
     * @return index of first set bit, or -1 if no bits are set
     */
    public static BigInteger findFirstSetBit(byte[] bs) {
        throw new UnsupportedOperationException(
                "BitwiseLib.findFirstSetBit() is not supported off-chain. "
                + "Use on-chain execution for bitwise operations.");
    }
}
