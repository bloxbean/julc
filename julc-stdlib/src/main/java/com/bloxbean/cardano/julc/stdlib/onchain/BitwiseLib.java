package com.bloxbean.cardano.julc.stdlib.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.onchain.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.onchain.stdlib.Builtins;

/**
 * Bitwise operations on ByteStrings compiled from Java source to UPLC.
 */
@OnchainLibrary
public class BitwiseLib {

    /** Bitwise AND of two bytestrings with padding semantics. */
    public static PlutusData.BytesData andByteString(boolean padding, PlutusData.BytesData a, PlutusData.BytesData b) {
        return Builtins.andByteString(padding, a, b);
    }

    /** Bitwise OR of two bytestrings with padding semantics. */
    public static PlutusData.BytesData orByteString(boolean padding, PlutusData.BytesData a, PlutusData.BytesData b) {
        return Builtins.orByteString(padding, a, b);
    }

    /** Bitwise XOR of two bytestrings with padding semantics. */
    public static PlutusData.BytesData xorByteString(boolean padding, PlutusData.BytesData a, PlutusData.BytesData b) {
        return Builtins.xorByteString(padding, a, b);
    }

    /** Bitwise complement of a bytestring. */
    public static PlutusData.BytesData complementByteString(PlutusData.BytesData bs) {
        return Builtins.complementByteString(bs);
    }

    /** Read a bit at a given index. */
    public static boolean readBit(PlutusData.BytesData bs, long index) {
        return Builtins.readBit(bs, index);
    }

    /** Write bits at given indices. */
    public static PlutusData.BytesData writeBits(PlutusData.BytesData bs, PlutusData.ListData indices, boolean value) {
        return Builtins.writeBits(bs, indices, value);
    }

    /** Shift a bytestring by n bits. */
    public static PlutusData.BytesData shiftByteString(PlutusData.BytesData bs, long n) {
        return Builtins.shiftByteString(bs, n);
    }

    /** Rotate a bytestring by n bits. */
    public static PlutusData.BytesData rotateByteString(PlutusData.BytesData bs, long n) {
        return Builtins.rotateByteString(bs, n);
    }

    /** Count the number of set bits (1s) in a bytestring. */
    public static long countSetBits(PlutusData.BytesData bs) {
        return Builtins.countSetBits(bs);
    }

    /** Find the index of the first set bit, or -1 if none. */
    public static long findFirstSetBit(PlutusData.BytesData bs) {
        return Builtins.findFirstSetBit(bs);
    }
}
