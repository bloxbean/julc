package org.julclang.stdlib.lib;

import org.julclang.core.types.JulcG1;
import org.julclang.core.types.JulcG1Points;
import org.julclang.core.types.JulcG2;
import org.julclang.core.types.JulcG2Points;
import org.julclang.core.types.JulcMlResult;
import org.julclang.core.types.JulcScalars;
import org.julclang.stdlib.annotation.OnchainLibrary;
import org.julclang.stdlib.Builtins;

import java.math.BigInteger;

/**
 * BLS12-381 elliptic curve operations compiled from Java source to UPLC.
 * <p>
 * Provides ergonomic wrappers around the raw {@link Builtins} BLS12-381 stubs.
 * On-chain, calls compile to the corresponding UPLC BLS12-381 builtins.
 * Base BLS operations are available on Plutus V3/PV10; the two multi-scalar
 * multiplication methods require PV11.
 * <p>
 * ADR-047: points are {@link JulcG1}/{@link JulcG2}, Miller results {@link JulcMlResult},
 * and the multi-scalar-multiplication lists {@link JulcScalars}/{@link JulcG1Points}/
 * {@link JulcG2Points}, built with {@code Builtins.scalars}, {@code Builtins.g1Points} or
 * the {@code ...FromList}/{@code ...FromCompressed} converters. A byte string is only a
 * compressed encoding: {@code g1Uncompress} turns it into a point and {@code g1Compress}
 * back.
 * <p>
 * <b>Off-chain testing:</b> These methods throw {@link UnsupportedOperationException} when
 * called directly on the JVM. To test code that uses this library:
 * <ul>
 *   <li>Use {@code JulcEval.forSource(...)} to compile and evaluate through the UPLC VM</li>
 *   <li>Or mock these calls using a test framework such as Mockito</li>
 * </ul>
 */
@OnchainLibrary
public class BlsLib {

    // ---- G1 operations ----

    /** Add two G1 elements. */
    public static JulcG1 g1Add(JulcG1 a, JulcG1 b) {
        return Builtins.bls12_381_G1_add(a, b);
    }

    /** Negate a G1 element. */
    public static JulcG1 g1Neg(JulcG1 a) {
        return Builtins.bls12_381_G1_neg(a);
    }

    /** Scalar multiplication of a G1 element. */
    public static JulcG1 g1ScalarMul(BigInteger scalar, JulcG1 g1) {
        return Builtins.bls12_381_G1_scalarMul(scalar, g1);
    }

    /** Check equality of two G1 elements. */
    public static boolean g1Equal(JulcG1 a, JulcG1 b) {
        return Builtins.bls12_381_G1_equal(a, b);
    }

    /** Compress a G1 element to 48 bytes. */
    public static byte[] g1Compress(JulcG1 g1) {
        return Builtins.bls12_381_G1_compress(g1);
    }

    /** Uncompress a 48-byte compressed G1 element. */
    public static JulcG1 g1Uncompress(byte[] compressed) {
        return Builtins.bls12_381_G1_uncompress(compressed);
    }

    /** Hash a message to a G1 element using the given domain separation tag. */
    public static JulcG1 g1HashToGroup(byte[] msg, byte[] dst) {
        return Builtins.bls12_381_G1_hashToGroup(msg, dst);
    }

    // ---- G2 operations ----

    /** Add two G2 elements. */
    public static JulcG2 g2Add(JulcG2 a, JulcG2 b) {
        return Builtins.bls12_381_G2_add(a, b);
    }

    /** Negate a G2 element. */
    public static JulcG2 g2Neg(JulcG2 a) {
        return Builtins.bls12_381_G2_neg(a);
    }

    /** Scalar multiplication of a G2 element. */
    public static JulcG2 g2ScalarMul(BigInteger scalar, JulcG2 g2) {
        return Builtins.bls12_381_G2_scalarMul(scalar, g2);
    }

    /** Check equality of two G2 elements. */
    public static boolean g2Equal(JulcG2 a, JulcG2 b) {
        return Builtins.bls12_381_G2_equal(a, b);
    }

    /** Compress a G2 element to 96 bytes. */
    public static byte[] g2Compress(JulcG2 g2) {
        return Builtins.bls12_381_G2_compress(g2);
    }

    /** Uncompress a 96-byte compressed G2 element. */
    public static JulcG2 g2Uncompress(byte[] compressed) {
        return Builtins.bls12_381_G2_uncompress(compressed);
    }

    /** Hash a message to a G2 element using the given domain separation tag. */
    public static JulcG2 g2HashToGroup(byte[] msg, byte[] dst) {
        return Builtins.bls12_381_G2_hashToGroup(msg, dst);
    }

    // ---- Pairing operations ----

    /** Compute the Miller loop pairing of a G1 and G2 element. */
    public static JulcMlResult millerLoop(JulcG1 g1, JulcG2 g2) {
        return Builtins.bls12_381_millerLoop(g1, g2);
    }

    /** Multiply two Miller loop results. */
    public static JulcMlResult mulMlResult(JulcMlResult a, JulcMlResult b) {
        return Builtins.bls12_381_mulMlResult(a, b);
    }

    /** Final verification of two Miller loop results. Returns true if the pairing check passes. */
    public static boolean finalVerify(JulcMlResult a, JulcMlResult b) {
        return Builtins.bls12_381_finalVerify(a, b);
    }

    // ---- Multi-Scalar Multiplication ----

    /**
     * Multi-scalar multiplication on G1: {@code Σ scalars[i] · points[i]} over the shorter of
     * the two lists (extra entries ignored, an empty list gives the identity); every scalar
     * must fit in 512 bytes and all are checked before any pair is used. PV11 only (CIP-133).
     */
    public static JulcG1 g1MultiScalarMul(JulcScalars scalars, JulcG1Points points) {
        return Builtins.bls12_381_G1_multiScalarMul(scalars, points);
    }

    /** Multi-scalar multiplication on G2, with the same semantics as the G1 form. PV11 only (CIP-133). */
    public static JulcG2 g2MultiScalarMul(JulcScalars scalars, JulcG2Points points) {
        return Builtins.bls12_381_G2_multiScalarMul(scalars, points);
    }
}
