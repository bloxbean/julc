package com.bloxbean.cardano.plutus.stdlib;

import com.bloxbean.cardano.plutus.compiler.pir.PirTerm;
import com.bloxbean.cardano.plutus.core.DefaultFun;

/**
 * Bitwise operations on ByteStrings built as PIR term builders.
 * <p>
 * Each method returns a {@link PirTerm} that wraps a UPLC builtin for bitwise manipulation.
 * All operations work on ByteString types.
 */
public final class BitwiseLib {

    private BitwiseLib() {}

    /**
     * Bitwise AND of two ByteStrings.
     * <p>
     * Wrapper around UPLC builtin AndByteString (75).
     *
     * @param semantics PIR term representing Bool (padding semantics)
     * @param a         PIR term representing a ByteString
     * @param b         PIR term representing a ByteString
     * @return PIR term that evaluates to the bitwise AND result
     */
    public static PirTerm andByteString(PirTerm semantics, PirTerm a, PirTerm b) {
        return builtinApp3(DefaultFun.AndByteString, semantics, a, b);
    }

    /**
     * Bitwise OR of two ByteStrings.
     * <p>
     * Wrapper around UPLC builtin OrByteString (76).
     *
     * @param semantics PIR term representing Bool (padding semantics)
     * @param a         PIR term representing a ByteString
     * @param b         PIR term representing a ByteString
     * @return PIR term that evaluates to the bitwise OR result
     */
    public static PirTerm orByteString(PirTerm semantics, PirTerm a, PirTerm b) {
        return builtinApp3(DefaultFun.OrByteString, semantics, a, b);
    }

    /**
     * Bitwise XOR of two ByteStrings.
     * <p>
     * Wrapper around UPLC builtin XorByteString (77).
     *
     * @param semantics PIR term representing Bool (padding semantics)
     * @param a         PIR term representing a ByteString
     * @param b         PIR term representing a ByteString
     * @return PIR term that evaluates to the bitwise XOR result
     */
    public static PirTerm xorByteString(PirTerm semantics, PirTerm a, PirTerm b) {
        return builtinApp3(DefaultFun.XorByteString, semantics, a, b);
    }

    /**
     * Bitwise complement of a ByteString.
     * <p>
     * Wrapper around UPLC builtin ComplementByteString (78).
     *
     * @param bs PIR term representing a ByteString
     * @return PIR term that evaluates to the bitwise complement
     */
    public static PirTerm complementByteString(PirTerm bs) {
        return builtinApp1(DefaultFun.ComplementByteString, bs);
    }

    /**
     * Read a bit at a given index in a ByteString.
     * <p>
     * Wrapper around UPLC builtin ReadBit (79).
     *
     * @param bs    PIR term representing a ByteString
     * @param index PIR term representing an Integer index
     * @return PIR term that evaluates to Bool (bit value)
     */
    public static PirTerm readBit(PirTerm bs, PirTerm index) {
        return builtinApp2(DefaultFun.ReadBit, bs, index);
    }

    /**
     * Write bits at given indices in a ByteString.
     * <p>
     * Wrapper around UPLC builtin WriteBits (80).
     *
     * @param bs      PIR term representing a ByteString
     * @param indices PIR term representing a list of Integer indices
     * @param values  PIR term representing a list of Bool values
     * @return PIR term that evaluates to the modified ByteString
     */
    public static PirTerm writeBits(PirTerm bs, PirTerm indices, PirTerm values) {
        return builtinApp3(DefaultFun.WriteBits, bs, indices, values);
    }

    /**
     * Shift a ByteString by n bits.
     * <p>
     * Wrapper around UPLC builtin ShiftByteString (82).
     *
     * @param bs PIR term representing a ByteString
     * @param n  PIR term representing an Integer (shift amount)
     * @return PIR term that evaluates to the shifted ByteString
     */
    public static PirTerm shiftByteString(PirTerm bs, PirTerm n) {
        return builtinApp2(DefaultFun.ShiftByteString, bs, n);
    }

    /**
     * Rotate a ByteString by n bits.
     * <p>
     * Wrapper around UPLC builtin RotateByteString (83).
     *
     * @param bs PIR term representing a ByteString
     * @param n  PIR term representing an Integer (rotation amount)
     * @return PIR term that evaluates to the rotated ByteString
     */
    public static PirTerm rotateByteString(PirTerm bs, PirTerm n) {
        return builtinApp2(DefaultFun.RotateByteString, bs, n);
    }

    /**
     * Count the number of set bits (1s) in a ByteString.
     * <p>
     * Wrapper around UPLC builtin CountSetBits (84).
     *
     * @param bs PIR term representing a ByteString
     * @return PIR term that evaluates to Integer (count of set bits)
     */
    public static PirTerm countSetBits(PirTerm bs) {
        return builtinApp1(DefaultFun.CountSetBits, bs);
    }

    /**
     * Find the index of the first set bit in a ByteString.
     * <p>
     * Wrapper around UPLC builtin FindFirstSetBit (85).
     *
     * @param bs PIR term representing a ByteString
     * @return PIR term that evaluates to Integer (index of first set bit, or -1)
     */
    public static PirTerm findFirstSetBit(PirTerm bs) {
        return builtinApp1(DefaultFun.FindFirstSetBit, bs);
    }

    // =========================================================================
    // Helper methods for builtin application
    // =========================================================================

    private static PirTerm builtinApp1(DefaultFun fun, PirTerm arg) {
        return new PirTerm.App(new PirTerm.Builtin(fun), arg);
    }

    private static PirTerm builtinApp2(DefaultFun fun, PirTerm a, PirTerm b) {
        return new PirTerm.App(new PirTerm.App(new PirTerm.Builtin(fun), a), b);
    }

    private static PirTerm builtinApp3(DefaultFun fun, PirTerm a, PirTerm b, PirTerm c) {
        return new PirTerm.App(
                new PirTerm.App(
                        new PirTerm.App(new PirTerm.Builtin(fun), a),
                        b),
                c);
    }
}
