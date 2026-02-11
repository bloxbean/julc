package com.bloxbean.cardano.plutus.stdlib;

import com.bloxbean.cardano.plutus.compiler.pir.PirTerm;
import com.bloxbean.cardano.plutus.core.Constant;
import com.bloxbean.cardano.plutus.core.DefaultFun;

import java.math.BigInteger;

/**
 * Byte-level bytestring operations as PIR term builders.
 * <p>
 * These are thin wrappers around the corresponding UPLC builtins:
 * IndexByteString, ConsByteString, SliceByteString, LengthOfByteString,
 * and AppendByteString.
 */
public final class ByteStringLib {

    private ByteStringLib() {}

    /**
     * Get the byte at a given index in a bytestring.
     * <p>
     * Maps to UPLC builtin {@code IndexByteString}.
     *
     * @param bs    PIR term representing a ByteString
     * @param index PIR term representing an Integer index
     * @return PIR term that evaluates to Integer (0-255)
     */
    public static PirTerm at(PirTerm bs, PirTerm index) {
        return new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.IndexByteString), bs),
                index);
    }

    /**
     * Prepend a byte to a bytestring.
     * <p>
     * Maps to UPLC builtin {@code ConsByteString}.
     *
     * @param byte_ PIR term representing an Integer (0-255)
     * @param bs    PIR term representing a ByteString
     * @return PIR term that evaluates to ByteString
     */
    public static PirTerm cons(PirTerm byte_, PirTerm bs) {
        return new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.ConsByteString), byte_),
                bs);
    }

    /**
     * Extract a slice of a bytestring.
     * <p>
     * Maps to UPLC builtin {@code SliceByteString(start, length, bs)}.
     *
     * @param bs     PIR term representing a ByteString
     * @param start  PIR term representing the start index (Integer)
     * @param length PIR term representing the length (Integer)
     * @return PIR term that evaluates to ByteString
     */
    public static PirTerm slice(PirTerm bs, PirTerm start, PirTerm length) {
        return new PirTerm.App(
                new PirTerm.App(
                        new PirTerm.App(new PirTerm.Builtin(DefaultFun.SliceByteString), start),
                        length),
                bs);
    }

    /**
     * Get the length of a bytestring.
     * <p>
     * Maps to UPLC builtin {@code LengthOfByteString}.
     *
     * @param bs PIR term representing a ByteString
     * @return PIR term that evaluates to Integer
     */
    public static PirTerm length(PirTerm bs) {
        return new PirTerm.App(new PirTerm.Builtin(DefaultFun.LengthOfByteString), bs);
    }

    /**
     * Drop the first n bytes from a bytestring.
     * <p>
     * Implemented as {@code SliceByteString(n, LengthOfByteString(bs) - n, bs)}.
     *
     * @param bs PIR term representing a ByteString
     * @param n  PIR term representing the number of bytes to drop (Integer)
     * @return PIR term that evaluates to ByteString
     */
    public static PirTerm drop(PirTerm bs, PirTerm n) {
        // let x = bs in slice(x, n, length(x) - n)
        var xVar = new PirTerm.Var("__bs_drop", new com.bloxbean.cardano.plutus.compiler.pir.PirType.ByteStringType());
        var len = length(xVar);
        var remaining = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.SubtractInteger), len),
                n);
        return new PirTerm.Let("__bs_drop", bs, slice(xVar, n, remaining));
    }

    /**
     * Concatenate two bytestrings.
     * <p>
     * Maps to UPLC builtin {@code AppendByteString}.
     *
     * @param a PIR term representing first ByteString
     * @param b PIR term representing second ByteString
     * @return PIR term that evaluates to ByteString
     */
    public static PirTerm append(PirTerm a, PirTerm b) {
        return new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.AppendByteString), a),
                b);
    }

    /**
     * An empty bytestring constant.
     *
     * @return PIR term that evaluates to an empty ByteString
     */
    public static PirTerm empty() {
        return new PirTerm.Const(Constant.byteString(new byte[0]));
    }

    /**
     * Create a bytestring of n zero bytes.
     * <p>
     * Maps to UPLC builtin {@code ReplicateByte(n, 0)}.
     *
     * @param n PIR term representing an Integer (number of zero bytes)
     * @return PIR term that evaluates to ByteString
     */
    public static PirTerm zeros(PirTerm n) {
        return new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.ReplicateByte), n),
                new PirTerm.Const(Constant.integer(BigInteger.ZERO)));
    }

    /**
     * Compare two bytestrings for equality.
     * <p>
     * Maps to UPLC builtin {@code EqualsByteString}.
     *
     * @param a PIR term representing first ByteString
     * @param b PIR term representing second ByteString
     * @return PIR term that evaluates to Bool
     */
    public static PirTerm equals(PirTerm a, PirTerm b) {
        return new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsByteString), a),
                b);
    }
}
