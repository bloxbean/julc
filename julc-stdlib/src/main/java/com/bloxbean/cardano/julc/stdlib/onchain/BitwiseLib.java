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
    public static byte[] andByteString(boolean padding, byte[] a, byte[] b) {
        return Builtins.andByteString(padding, a, b);
    }

    /** Bitwise OR of two bytestrings with padding semantics. */
    public static byte[] orByteString(boolean padding, byte[] a, byte[] b) {
        return Builtins.orByteString(padding, a, b);
    }

    /** Bitwise XOR of two bytestrings with padding semantics. */
    public static byte[] xorByteString(boolean padding, byte[] a, byte[] b) {
        return Builtins.xorByteString(padding, a, b);
    }

    /** Bitwise complement of a bytestring. */
    public static byte[] complementByteString(byte[] bs) {
        return Builtins.complementByteString(bs);
    }

    /** Read a bit at a given index. */
    public static boolean readBit(byte[] bs, long index) {
        return Builtins.readBit(bs, index);
    }

    /** Write bits at given indices. */
    public static byte[] writeBits(byte[] bs, PlutusData.ListData indices, boolean value) {
        return Builtins.writeBits(bs, indices, value);
    }

    /** Shift a bytestring by n bits. */
    public static byte[] shiftByteString(byte[] bs, long n) {
        return Builtins.shiftByteString(bs, n);
    }

    /** Rotate a bytestring by n bits. */
    public static byte[] rotateByteString(byte[] bs, long n) {
        return Builtins.rotateByteString(bs, n);
    }

    /** Count the number of set bits (1s) in a bytestring. */
    public static long countSetBits(byte[] bs) {
        return Builtins.countSetBits(bs);
    }

    /** Find the index of the first set bit, or -1 if none. */
    public static long findFirstSetBit(byte[] bs) {
        return Builtins.findFirstSetBit(bs);
    }
}
