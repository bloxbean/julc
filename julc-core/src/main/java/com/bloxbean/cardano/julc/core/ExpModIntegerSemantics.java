package com.bloxbean.cardano.julc.core;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Pinned Plutus semantics for {@link DefaultFun#ExpModInteger}.
 *
 * <p>The operand bounds and guard order match the Plutus reference used by
 * ADR-032. Keeping this in {@code julc-core} gives the compiler fold and VM
 * runtime one source of truth.</p>
 */
public final class ExpModIntegerSemantics {

    public static final int MAX_OPERAND_BITS = 8191;

    private static final BigInteger MAX_SIGNED = BigInteger.ONE
            .shiftLeft(MAX_OPERAND_BITS).subtract(BigInteger.ONE);
    private static final BigInteger MIN_SIGNED = BigInteger.ONE
            .shiftLeft(MAX_OPERAND_BITS).negate();
    private static final BigInteger MAX_MODULUS = MAX_SIGNED;

    private ExpModIntegerSemantics() {}

    /** Evaluate a saturated ExpModInteger call or throw its pinned failure. */
    public static BigInteger evaluate(
            BigInteger base, BigInteger exponent, BigInteger modulus) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(exponent, "exponent");
        Objects.requireNonNull(modulus, "modulus");

        if (modulus.signum() <= 0 || modulus.compareTo(MAX_MODULUS) > 0) {
            throw new EvaluationFailure("expMod: invalid modulus");
        }
        if (modulus.equals(BigInteger.ONE)) {
            return BigInteger.ZERO;
        }
        if (base.signum() == 0 && exponent.signum() < 0) {
            throw notInvertible(base, modulus);
        }
        if (outsideSignedBounds(base) || outsideSignedBounds(exponent)) {
            throw new EvaluationFailure("expMod: out of bounds");
        }

        try {
            return exponent.signum() < 0
                    ? base.modInverse(modulus).modPow(exponent.negate(), modulus)
                    : base.modPow(exponent, modulus);
        } catch (ArithmeticException nonInvertible) {
            throw notInvertible(base, modulus);
        }
    }

    private static boolean outsideSignedBounds(BigInteger value) {
        return value.compareTo(MIN_SIGNED) < 0 || value.compareTo(MAX_SIGNED) > 0;
    }

    private static EvaluationFailure notInvertible(
            BigInteger base, BigInteger modulus) {
        return new EvaluationFailure(
                "expMod: " + base + " is not invertible modulo " + modulus);
    }

    /** Expected reference-semantic failure for an ExpModInteger call. */
    public static final class EvaluationFailure extends RuntimeException {
        public EvaluationFailure(String message) {
            super(message);
        }
    }
}
