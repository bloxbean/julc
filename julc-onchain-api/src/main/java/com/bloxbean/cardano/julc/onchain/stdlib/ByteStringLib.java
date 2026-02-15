package com.bloxbean.cardano.julc.onchain.stdlib;

import java.math.BigInteger;

/**
 * Off-chain ByteString operation stubs.
 * <p>
 * Methods that delegate to Builtins with compatible signatures are in the on-chain
 * {@code com.bloxbean.cardano.julc.stdlib.lib.ByteStringLib}.
 * <p>
 * This file retains only methods with fundamentally different signatures
 * (e.g., BigInteger params, String params) or that require JVM-specific
 * implementations (CBOR encoding, proper integer-to-bytestring conversion).
 */
public final class ByteStringLib {

    private ByteStringLib() {}

    /** An empty bytestring (off-chain, no cast needed). */
    public static byte[] empty() {
        return new byte[0];
    }

    /** Create a bytestring of n zero bytes (off-chain, no cast needed). */
    public static byte[] zeros(long n) {
        return new byte[(int) n];
    }

    /**
     * Convert integer to bytestring.
     * <p>
     * Off-chain overload accepting BigInteger params (on-chain uses long).
     * Endianness: false = big-endian, true = little-endian.
     * Width: the target byte width. If 0, use minimal encoding.
     *
     * @param endianness false = big-endian, true = little-endian
     * @param width      target width in bytes (0 = minimal)
     * @param i          the integer to convert (must be non-negative)
     * @return the byte array representation
     */
    public static byte[] integerToByteString(boolean endianness, BigInteger width, BigInteger i) {
        if (i.signum() < 0) {
            throw new IllegalArgumentException("integerToByteString: input must be non-negative, got " + i);
        }
        int targetWidth = width.intValueExact();

        if (i.signum() == 0) {
            if (targetWidth == 0) {
                return new byte[0];
            }
            return new byte[targetWidth];
        }

        byte[] raw = i.toByteArray();
        int offset = 0;
        if (raw.length > 1 && raw[0] == 0) {
            offset = 1;
        }
        int minLen = raw.length - offset;

        byte[] bigEndian;
        if (targetWidth == 0) {
            bigEndian = new byte[minLen];
            System.arraycopy(raw, offset, bigEndian, 0, minLen);
        } else {
            if (minLen > targetWidth) {
                throw new IllegalArgumentException(
                        "integerToByteString: value requires " + minLen + " bytes but width is " + targetWidth);
            }
            bigEndian = new byte[targetWidth];
            System.arraycopy(raw, offset, bigEndian, targetWidth - minLen, minLen);
        }

        if (endianness) {
            reverse(bigEndian);
        }
        return bigEndian;
    }

    /**
     * Convert bytestring to integer (off-chain, returns BigInteger).
     * On-chain version returns long.
     *
     * @param endianness false = big-endian, true = little-endian
     * @param bs         the byte array
     * @return the integer value (non-negative)
     */
    public static BigInteger byteStringToInteger(boolean endianness, byte[] bs) {
        if (bs.length == 0) {
            return BigInteger.ZERO;
        }
        byte[] bigEndian;
        if (endianness) {
            bigEndian = bs.clone();
            reverse(bigEndian);
        } else {
            bigEndian = bs;
        }
        return new BigInteger(1, bigEndian);
    }

    private static void reverse(byte[] arr) {
        for (int left = 0, right = arr.length - 1; left < right; left++, right--) {
            byte tmp = arr[left];
            arr[left] = arr[right];
            arr[right] = tmp;
        }
    }

    /** Encode string as UTF-8 (off-chain, takes String; on-chain takes PlutusData). */
    public static byte[] encodeUtf8(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Decode UTF-8 bytestring to string (off-chain, returns String; on-chain returns PlutusData). */
    public static String decodeUtf8(byte[] bs) {
        return new String(bs, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Serialise Data to CBOR bytestring (off-chain implementation using PlutusDataCborEncoder).
     */
    public static byte[] serialiseData(com.bloxbean.cardano.julc.core.PlutusData d) {
        return com.bloxbean.cardano.julc.core.cbor.PlutusDataCborEncoder.encode(d);
    }
}
