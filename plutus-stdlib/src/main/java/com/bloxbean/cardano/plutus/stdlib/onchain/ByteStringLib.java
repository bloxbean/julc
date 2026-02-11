package com.bloxbean.cardano.plutus.stdlib.onchain;

import com.bloxbean.cardano.plutus.core.PlutusData;
import com.bloxbean.cardano.plutus.onchain.annotation.OnchainLibrary;
import com.bloxbean.cardano.plutus.onchain.stdlib.Builtins;

/**
 * ByteString operations compiled from Java source to UPLC.
 */
@OnchainLibrary
public class ByteStringLib {

    /** Get the byte at a given index (0-255). */
    public static long at(PlutusData bs, long index) {
        return Builtins.indexByteString(bs, index);
    }

    /** Prepend a byte (0-255) to a bytestring. */
    public static PlutusData cons(long byte_, PlutusData bs) {
        return Builtins.consByteString(byte_, bs);
    }

    /** Extract a slice: slice(bs, start, length). */
    public static PlutusData slice(PlutusData bs, long start, long length) {
        return Builtins.sliceByteString(start, length, bs);
    }

    /** Get the length of a bytestring. */
    public static long length(PlutusData bs) {
        return Builtins.lengthOfByteString(bs);
    }

    /** Drop the first n bytes from a bytestring. */
    public static PlutusData drop(PlutusData bs, long n) {
        var len = Builtins.lengthOfByteString(bs);
        return Builtins.sliceByteString(n, len - n, bs);
    }

    /** Take the first n bytes from a bytestring. */
    public static PlutusData take(PlutusData bs, long n) {
        return Builtins.sliceByteString(0, n, bs);
    }

    /** Concatenate two bytestrings. */
    public static PlutusData append(PlutusData a, PlutusData b) {
        return Builtins.appendByteString(a, b);
    }

    /** An empty bytestring. */
    public static PlutusData empty() {
        return Builtins.emptyByteString();
    }

    /** Create a bytestring of n zero bytes. */
    public static PlutusData zeros(long n) {
        return Builtins.replicateByte(n, 0);
    }

    /** Compare two bytestrings for equality. */
    public static boolean equals(PlutusData a, PlutusData b) {
        return Builtins.equalsByteString(a, b);
    }

    /** Lexicographic comparison: a < b. */
    public static boolean lessThan(PlutusData a, PlutusData b) {
        return Builtins.lessThanByteString(a, b);
    }

    /** Lexicographic comparison: a <= b. */
    public static boolean lessThanEquals(PlutusData a, PlutusData b) {
        return Builtins.lessThanEqualsByteString(a, b);
    }

    /** Convert integer to bytestring. */
    public static PlutusData integerToByteString(boolean endian, long width, long i) {
        return Builtins.integerToByteString(endian, width, i);
    }

    /** Convert bytestring to integer. */
    public static long byteStringToInteger(boolean endian, PlutusData bs) {
        return Builtins.byteStringToInteger(endian, bs);
    }

    /** Encode a string as UTF-8 bytestring. */
    public static PlutusData encodeUtf8(PlutusData s) {
        return Builtins.encodeUtf8(s);
    }

    /** Decode a UTF-8 bytestring. */
    public static PlutusData decodeUtf8(PlutusData bs) {
        return Builtins.decodeUtf8(bs);
    }

    /** Serialise a Data value to CBOR bytes. */
    public static PlutusData serialiseData(PlutusData d) {
        return Builtins.serialiseData(d);
    }
}
