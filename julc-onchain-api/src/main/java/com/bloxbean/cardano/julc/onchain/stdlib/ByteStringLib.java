package com.bloxbean.cardano.julc.onchain.stdlib;

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

    /** Take first n bytes from a bytestring. */
    public static byte[] take(byte[] bs, java.math.BigInteger n) {
        int count = Math.min(n.intValueExact(), bs.length);
        return java.util.Arrays.copyOf(bs, count);
    }

    /** Compare bytestrings: a < b. */
    public static boolean lessThan(byte[] a, byte[] b) {
        return java.util.Arrays.compare(a, b) < 0;
    }

    /** Compare bytestrings: a <= b. */
    public static boolean lessThanEquals(byte[] a, byte[] b) {
        return java.util.Arrays.compare(a, b) <= 0;
    }

    /**
     * Convert integer to bytestring.
     * <p>
     * Endianness: false = big-endian, true = little-endian.
     * Width: the target byte width. If 0, use minimal encoding (no leading/trailing zeros
     * beyond what is needed). If positive, pad to exactly that width.
     *
     * @param endianness false = big-endian, true = little-endian
     * @param width      target width in bytes (0 = minimal)
     * @param i          the integer to convert (must be non-negative)
     * @return the byte array representation
     * @throws IllegalArgumentException if i is negative or result exceeds width
     */
    public static byte[] integerToByteString(boolean endianness, java.math.BigInteger width, java.math.BigInteger i) {
        if (i.signum() < 0) {
            throw new IllegalArgumentException("integerToByteString: input must be non-negative, got " + i);
        }
        int targetWidth = width.intValueExact();

        if (i.signum() == 0) {
            // Zero: if width is 0, return empty bytestring; otherwise return zeros of target width
            if (targetWidth == 0) {
                return new byte[0];
            }
            return new byte[targetWidth];
        }

        // Get the big-endian minimal representation
        byte[] raw = i.toByteArray();
        // BigInteger.toByteArray() may have a leading 0x00 for sign; strip it
        int offset = 0;
        if (raw.length > 1 && raw[0] == 0) {
            offset = 1;
        }
        int minLen = raw.length - offset;

        byte[] bigEndian;
        if (targetWidth == 0) {
            // Minimal encoding
            bigEndian = new byte[minLen];
            System.arraycopy(raw, offset, bigEndian, 0, minLen);
        } else {
            if (minLen > targetWidth) {
                throw new IllegalArgumentException(
                        "integerToByteString: value requires " + minLen + " bytes but width is " + targetWidth);
            }
            // Pad with leading zeros (big-endian)
            bigEndian = new byte[targetWidth];
            System.arraycopy(raw, offset, bigEndian, targetWidth - minLen, minLen);
        }

        if (endianness) {
            // Little-endian: reverse the big-endian bytes
            reverse(bigEndian);
        }
        return bigEndian;
    }

    /**
     * Convert bytestring to integer.
     * <p>
     * Endianness: false = big-endian, true = little-endian.
     * The result is always non-negative (unsigned interpretation).
     *
     * @param endianness false = big-endian, true = little-endian
     * @param bs         the byte array
     * @return the integer value (non-negative)
     */
    public static java.math.BigInteger byteStringToInteger(boolean endianness, byte[] bs) {
        if (bs.length == 0) {
            return java.math.BigInteger.ZERO;
        }
        byte[] bigEndian;
        if (endianness) {
            // Little-endian input: reverse to get big-endian
            bigEndian = bs.clone();
            reverse(bigEndian);
        } else {
            bigEndian = bs;
        }
        // Interpret as unsigned (positive) big-endian
        return new java.math.BigInteger(1, bigEndian);
    }

    /** Reverse a byte array in place. */
    private static void reverse(byte[] arr) {
        for (int left = 0, right = arr.length - 1; left < right; left++, right--) {
            byte tmp = arr[left];
            arr[left] = arr[right];
            arr[right] = tmp;
        }
    }

    /** Encode string as UTF-8. */
    public static byte[] encodeUtf8(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Decode UTF-8 bytestring to string. */
    public static String decodeUtf8(byte[] bs) {
        return new String(bs, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Serialise Data to CBOR bytestring.
     * <p>
     * Delegates to {@link com.bloxbean.cardano.julc.core.cbor.PlutusDataCborEncoder#encode(com.bloxbean.cardano.julc.core.PlutusData)}.
     *
     * @param d the PlutusData to serialise
     * @return CBOR-encoded byte array
     */
    public static byte[] serialiseData(com.bloxbean.cardano.julc.core.PlutusData d) {
        return com.bloxbean.cardano.julc.core.cbor.PlutusDataCborEncoder.encode(d);
    }
}
