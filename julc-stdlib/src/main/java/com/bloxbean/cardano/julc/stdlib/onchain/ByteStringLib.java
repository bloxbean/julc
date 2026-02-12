package com.bloxbean.cardano.julc.stdlib.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.onchain.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.onchain.stdlib.Builtins;

/**
 * ByteString operations compiled from Java source to UPLC.
 */
@OnchainLibrary
public class ByteStringLib {

    /** Get the byte at a given index (0-255). */
    public static long at(PlutusData.BytesData bs, long index) {
        return Builtins.indexByteString(bs, index);
    }

    /** Prepend a byte (0-255) to a bytestring. */
    public static PlutusData.BytesData cons(long byte_, PlutusData.BytesData bs) {
        return Builtins.consByteString(byte_, bs);
    }

    /** Extract a slice: slice(bs, start, length). */
    public static PlutusData.BytesData slice(PlutusData.BytesData bs, long start, long length) {
        return Builtins.sliceByteString(start, length, bs);
    }

    /** Get the length of a bytestring. */
    public static long length(PlutusData.BytesData bs) {
        return Builtins.lengthOfByteString(bs);
    }

    /** Drop the first n bytes from a bytestring. */
    public static PlutusData.BytesData drop(PlutusData.BytesData bs, long n) {
        var len = Builtins.lengthOfByteString(bs);
        return Builtins.sliceByteString(n, len - n, bs);
    }

    /** Take the first n bytes from a bytestring. */
    public static PlutusData.BytesData take(PlutusData.BytesData bs, long n) {
        return Builtins.sliceByteString(0, n, bs);
    }

    /** Concatenate two bytestrings. */
    public static PlutusData.BytesData append(PlutusData.BytesData a, PlutusData.BytesData b) {
        return Builtins.appendByteString(a, b);
    }

    /** An empty bytestring. */
    public static PlutusData.BytesData empty() {
        return Builtins.emptyByteString();
    }

    /** Create a bytestring of n zero bytes. */
    public static PlutusData.BytesData zeros(long n) {
        return Builtins.replicateByte(n, 0);
    }

    /** Compare two bytestrings for equality. */
    public static boolean equals(PlutusData.BytesData a, PlutusData.BytesData b) {
        return Builtins.equalsByteString(a, b);
    }

    /** Lexicographic comparison: a < b. */
    public static boolean lessThan(PlutusData.BytesData a, PlutusData.BytesData b) {
        return Builtins.lessThanByteString(a, b);
    }

    /** Lexicographic comparison: a <= b. */
    public static boolean lessThanEquals(PlutusData.BytesData a, PlutusData.BytesData b) {
        return Builtins.lessThanEqualsByteString(a, b);
    }

    /** Convert integer to bytestring. */
    public static PlutusData.BytesData integerToByteString(boolean endian, long width, long i) {
        return Builtins.integerToByteString(endian, width, i);
    }

    /** Convert bytestring to integer. */
    public static long byteStringToInteger(boolean endian, PlutusData.BytesData bs) {
        return Builtins.byteStringToInteger(endian, bs);
    }

    /** Encode a string as UTF-8 bytestring. */
    public static PlutusData.BytesData encodeUtf8(PlutusData s) {
        return Builtins.encodeUtf8(s);
    }

    /** Decode a UTF-8 bytestring. */
    public static PlutusData decodeUtf8(PlutusData.BytesData bs) {
        return Builtins.decodeUtf8(bs);
    }

    /** Serialise a Data value to CBOR bytes. */
    public static PlutusData.BytesData serialiseData(PlutusData d) {
        return Builtins.serialiseData(d);
    }
}
