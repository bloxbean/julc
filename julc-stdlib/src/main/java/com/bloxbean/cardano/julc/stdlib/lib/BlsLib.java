package com.bloxbean.cardano.julc.stdlib.lib;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.stdlib.Builtins;

import java.math.BigInteger;

/**
 * BLS12-381 elliptic curve operations compiled from Java source to UPLC.
 * <p>
 * Provides ergonomic wrappers around the raw {@link Builtins} BLS12-381 stubs.
 * On-chain, calls compile to the corresponding UPLC BLS12-381 builtins.
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
    public static byte[] g1Add(byte[] a, byte[] b) {
        return Builtins.bls12_381_G1_add(a, b);
    }

    /** Negate a G1 element. */
    public static byte[] g1Neg(byte[] a) {
        return Builtins.bls12_381_G1_neg(a);
    }

    /** Scalar multiplication of a G1 element. */
    public static byte[] g1ScalarMul(BigInteger scalar, byte[] g1) {
        return Builtins.bls12_381_G1_scalarMul(scalar, g1);
    }

    /** Check equality of two G1 elements. */
    public static boolean g1Equal(byte[] a, byte[] b) {
        return Builtins.bls12_381_G1_equal(a, b);
    }

    /** Compress a G1 element to 48 bytes. */
    public static byte[] g1Compress(byte[] g1) {
        return Builtins.bls12_381_G1_compress(g1);
    }

    /** Uncompress a 48-byte compressed G1 element. */
    public static byte[] g1Uncompress(byte[] compressed) {
        return Builtins.bls12_381_G1_uncompress(compressed);
    }

    /** Hash a message to a G1 element using the given domain separation tag. */
    public static byte[] g1HashToGroup(byte[] msg, byte[] dst) {
        return Builtins.bls12_381_G1_hashToGroup(msg, dst);
    }

    // ---- G2 operations ----

    /** Add two G2 elements. */
    public static byte[] g2Add(byte[] a, byte[] b) {
        return Builtins.bls12_381_G2_add(a, b);
    }

    /** Negate a G2 element. */
    public static byte[] g2Neg(byte[] a) {
        return Builtins.bls12_381_G2_neg(a);
    }

    /** Scalar multiplication of a G2 element. */
    public static byte[] g2ScalarMul(BigInteger scalar, byte[] g2) {
        return Builtins.bls12_381_G2_scalarMul(scalar, g2);
    }

    /** Check equality of two G2 elements. */
    public static boolean g2Equal(byte[] a, byte[] b) {
        return Builtins.bls12_381_G2_equal(a, b);
    }

    /** Compress a G2 element to 96 bytes. */
    public static byte[] g2Compress(byte[] g2) {
        return Builtins.bls12_381_G2_compress(g2);
    }

    /** Uncompress a 96-byte compressed G2 element. */
    public static byte[] g2Uncompress(byte[] compressed) {
        return Builtins.bls12_381_G2_uncompress(compressed);
    }

    /** Hash a message to a G2 element using the given domain separation tag. */
    public static byte[] g2HashToGroup(byte[] msg, byte[] dst) {
        return Builtins.bls12_381_G2_hashToGroup(msg, dst);
    }

    // ---- Pairing operations ----

    /** Compute the Miller loop pairing of a G1 and G2 element. */
    public static byte[] millerLoop(byte[] g1, byte[] g2) {
        return Builtins.bls12_381_millerLoop(g1, g2);
    }

    /** Multiply two Miller loop results. */
    public static byte[] mulMlResult(byte[] a, byte[] b) {
        return Builtins.bls12_381_mulMlResult(a, b);
    }

    /** Final verification of two Miller loop results. Returns true if the pairing check passes. */
    public static boolean finalVerify(byte[] a, byte[] b) {
        return Builtins.bls12_381_finalVerify(a, b);
    }

    // ---- Multi-Scalar Multiplication ----

    /** Multi-scalar multiplication on G1. Takes a list of scalars and a list of G1 elements. */
    public static byte[] g1MultiScalarMul(PlutusData scalars, PlutusData points) {
        return Builtins.bls12_381_G1_multiScalarMul(scalars, points);
    }

    /** Multi-scalar multiplication on G2. Takes a list of scalars and a list of G2 elements. */
    public static byte[] g2MultiScalarMul(PlutusData scalars, PlutusData points) {
        return Builtins.bls12_381_G2_multiScalarMul(scalars, points);
    }
}
