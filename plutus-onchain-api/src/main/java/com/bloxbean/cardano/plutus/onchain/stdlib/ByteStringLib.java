package com.bloxbean.cardano.plutus.onchain.stdlib;

import java.util.Arrays;

/**
 * On-chain byte-level bytestring operations.
 * <p>
 * These methods are executable both on-chain (compiled to UPLC via StdlibRegistry)
 * and off-chain (as plain Java for debugging and testing).
 */
public final class ByteStringLib {

    private ByteStringLib() {}

    /**
     * Get the byte at a given index in a bytestring.
     *
     * @param bs    the bytestring
     * @param index the index (0-based)
     * @return the unsigned byte value (0-255)
     */
    public static int at(byte[] bs, int index) {
        return Byte.toUnsignedInt(bs[index]);
    }

    /**
     * Prepend a byte to a bytestring.
     *
     * @param b  the byte value (0-255)
     * @param bs the bytestring
     * @return a new bytestring with b prepended
     */
    public static byte[] cons(int b, byte[] bs) {
        byte[] result = new byte[bs.length + 1];
        result[0] = (byte) b;
        System.arraycopy(bs, 0, result, 1, bs.length);
        return result;
    }

    /**
     * Extract a slice of a bytestring.
     *
     * @param bs     the bytestring
     * @param start  the start index
     * @param length the number of bytes to extract
     * @return the extracted slice
     */
    public static byte[] slice(byte[] bs, int start, int length) {
        return Arrays.copyOfRange(bs, start, start + length);
    }

    /**
     * Get the length of a bytestring.
     *
     * @param bs the bytestring
     * @return the length
     */
    public static int length(byte[] bs) {
        return bs.length;
    }

    /**
     * Drop the first n bytes from a bytestring.
     *
     * @param bs the bytestring
     * @param n  number of bytes to drop
     * @return the remaining bytestring
     */
    public static byte[] drop(byte[] bs, int n) {
        return Arrays.copyOfRange(bs, n, bs.length);
    }

    /**
     * Concatenate two bytestrings.
     *
     * @param a first bytestring
     * @param b second bytestring
     * @return the concatenation
     */
    public static byte[] append(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    /**
     * An empty bytestring.
     *
     * @return an empty byte array
     */
    public static byte[] empty() {
        return new byte[0];
    }

    /**
     * Create a bytestring of n zero bytes.
     *
     * @param n the number of zero bytes
     * @return a byte array of n zeros
     */
    public static byte[] zeros(int n) {
        return new byte[n];
    }

    /**
     * Compare two bytestrings for equality.
     *
     * @param a first bytestring
     * @param b second bytestring
     * @return true if the bytestrings are equal
     */
    public static boolean equals(byte[] a, byte[] b) {
        return java.util.Arrays.equals(a, b);
    }
}
