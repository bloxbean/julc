package com.bloxbean.cardano.julc.stdlib.lib;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.stdlib.Builtins;

import java.math.BigInteger;

/**
 * ByteString operations compiled from Java source to UPLC.
 */
@OnchainLibrary
public class ByteStringLib {

    /** Get the byte at a given index (0-255). */
    public static long at(byte[] bs, long index) {
        return Builtins.indexByteString(bs, index);
    }

    /** Prepend a byte (0-255) to a bytestring. */
    public static byte[] cons(long byte_, byte[] bs) {
        return Builtins.consByteString(byte_, bs);
    }

    /** Extract a slice: slice(bs, start, length). */
    public static byte[] slice(byte[] bs, long start, long length) {
        return Builtins.sliceByteString(start, length, bs);
    }

    /** Get the length of a bytestring. */
    public static long length(byte[] bs) {
        return Builtins.lengthOfByteString(bs);
    }

    /** Drop the first n bytes from a bytestring. */
    public static byte[] drop(byte[] bs, long n) {
        var len = Builtins.lengthOfByteString(bs);
        return Builtins.sliceByteString(n, len - n, bs);
    }

    /** Take the first n bytes from a bytestring. */
    public static byte[] take(byte[] bs, long n) {
        return Builtins.sliceByteString(0, n, bs);
    }

    /** Concatenate two bytestrings. */
    public static byte[] append(byte[] a, byte[] b) {
        return Builtins.appendByteString(a, b);
    }

    /** An empty bytestring. */
    public static byte[] empty() {
        return Builtins.emptyByteString();
    }

    /** Create a bytestring of n zero bytes. */
    public static byte[] zeros(long n) {
        return Builtins.replicateByte(n, 0);
    }

    /** Compare two bytestrings for equality. */
    public static boolean equals(byte[] a, byte[] b) {
        return Builtins.equalsByteString(a, b);
    }

    /** Lexicographic comparison: a < b. */
    public static boolean lessThan(byte[] a, byte[] b) {
        return Builtins.lessThanByteString(a, b);
    }

    /** Lexicographic comparison: a <= b. */
    public static boolean lessThanEquals(byte[] a, byte[] b) {
        return Builtins.lessThanEqualsByteString(a, b);
    }

    /** Convert integer to bytestring. */
    public static byte[] integerToByteString(boolean endian, long width, long i) {
        return Builtins.integerToByteString(endian, width, i);
    }

    /** Convert integer to bytestring. Accepts arbitrary-precision BigInteger. */
    public static byte[] integerToByteString(boolean endian, long width, BigInteger i) {
        return Builtins.integerToByteString(endian, width, i);
    }

    /** Convert bytestring to integer. Returns arbitrary-precision BigInteger. */
    public static BigInteger byteStringToInteger(boolean endian, byte[] bs) {
        return Builtins.byteStringToInteger(endian, bs);
    }

    /** Encode a string as UTF-8 bytestring. */
    public static byte[] encodeUtf8(PlutusData s) {
        return Builtins.encodeUtf8(s);
    }

    /** Decode a UTF-8 bytestring. */
    public static PlutusData decodeUtf8(byte[] bs) {
        return Builtins.decodeUtf8(bs);
    }

    /** Serialise a Data value to CBOR bytes. */
    public static byte[] serialiseData(PlutusData d) {
        return Builtins.serialiseData(d);
    }

    // --- Hex encoding ---

    /** Map a nibble (0-15) to its lowercase ASCII hex char ('0'-'9', 'a'-'f'). */
    public static long hexNibble(long n) {
        if (n < 10) return 48 + n;   // '0' + n
        return 87 + n;               // 'a' + (n - 10) = 87 + n
    }

    /**
     * Convert a bytestring to its lowercase hex representation.
     * Each byte becomes two hex characters (e.g. 0xDE → "de").
     */
    public static byte[] toHex(byte[] bs) {
        long len = Builtins.lengthOfByteString(bs);
        if (len == 0) return Builtins.emptyByteString();
        return toHexStep(bs, len - 1, Builtins.emptyByteString());
    }

    /** Recursive helper: process bytes from index down to 0, prepending hex chars. */
    public static byte[] toHexStep(byte[] bs, long idx, byte[] acc) {
        long b = Builtins.indexByteString(bs, idx);
        long hi = b / 16;
        long lo = b % 16;
        byte[] updated = Builtins.consByteString(hexNibble(hi),
                Builtins.consByteString(hexNibble(lo), acc));
        if (idx == 0) return updated;
        return toHexStep(bs, idx - 1, updated);
    }

    // --- Integer to decimal string ---

    /**
     * Convert a non-negative integer to its decimal string representation as UTF-8 bytes.
     * E.g. 42 → "42" (as byte[]{52, 50}).
     */
    public static byte[] intToDecimalString(BigInteger n) {
        if (n.equals(BigInteger.ZERO)) {
            return Builtins.consByteString(48, Builtins.emptyByteString()); // "0"
        }
        return intToDecimalStep(n, Builtins.emptyByteString());
    }

    /** Recursive helper: divmod by 10, prepend digit char. */
    public static byte[] intToDecimalStep(BigInteger n, byte[] acc) {
        if (n.equals(BigInteger.ZERO)) return acc;
        BigInteger div = n.divide(BigInteger.TEN);
        long digit = n.remainder(BigInteger.TEN).longValue();
        byte[] updated = Builtins.consByteString(48 + digit, acc);
        return intToDecimalStep(div, updated);
    }

    // --- Decimal string (UTF-8) to integer ---

    /**
     * Parse a UTF-8 decimal string (e.g. bytes of "42") to an integer.
     * Inverse of {@link #intToDecimalString(BigInteger)}.
     * Assumes all bytes are ASCII digits ('0'-'9'). No sign handling.
     */
    public static BigInteger utf8ToInteger(byte[] bs) {
        long len = Builtins.lengthOfByteString(bs);
        if (len == 0) {
            return BigInteger.ZERO;
        }
        return utf8ToIntegerStep(bs, 0L, len, BigInteger.ZERO);
    }

    /** Recursive helper: accumulate decimal value left-to-right. */
    public static BigInteger utf8ToIntegerStep(byte[] bs, long idx, long len, BigInteger acc) {
        if (idx >= len) return acc;
        long digitByte = Builtins.indexByteString(bs, idx);
        BigInteger digit = BigInteger.valueOf(digitByte - 48);
        BigInteger newAcc = acc.multiply(BigInteger.TEN).add(digit);
        return utf8ToIntegerStep(bs, idx + 1, len, newAcc);
    }
}
