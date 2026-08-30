package com.bloxbean.cardano.julc.core;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExpModIntegerSemanticsTest {

    private static final BigInteger TWO_TO_8191 = BigInteger.ONE.shiftLeft(8191);
    private static final BigInteger MAX = TWO_TO_8191.subtract(BigInteger.ONE);
    private static final BigInteger MIN = TWO_TO_8191.negate();

    @Test
    void acceptsExactSignedAndModulusBounds() {
        assertEquals(MAX.mod(BigInteger.valueOf(7)),
                ExpModIntegerSemantics.evaluate(MAX, BigInteger.ONE, BigInteger.valueOf(7)));
        assertEquals(MIN.mod(BigInteger.valueOf(7)),
                ExpModIntegerSemantics.evaluate(MIN, BigInteger.ONE, BigInteger.valueOf(7)));
        assertEquals(BigInteger.TWO,
                ExpModIntegerSemantics.evaluate(BigInteger.TWO, BigInteger.ONE, MAX));
    }

    @Test
    void rejectsOperandsOutsidePinnedBounds() {
        assertFailure("expMod: out of bounds",
                TWO_TO_8191, BigInteger.ONE, BigInteger.valueOf(7));
        assertFailure("expMod: out of bounds",
                BigInteger.TWO, TWO_TO_8191, BigInteger.valueOf(7));
        assertFailure("expMod: invalid modulus",
                BigInteger.TWO, BigInteger.ONE, TWO_TO_8191);
        assertFailure("expMod: out of bounds",
                MIN.subtract(BigInteger.ONE), BigInteger.ONE, BigInteger.valueOf(7));
    }

    @Test
    void modulusOneShortcutPrecedesSignedOperandBounds() {
        assertEquals(BigInteger.ZERO,
                ExpModIntegerSemantics.evaluate(TWO_TO_8191, TWO_TO_8191, BigInteger.ONE));
    }

    @Test
    void preservesPinnedFailureOrderAndMessages() {
        assertFailure("expMod: invalid modulus",
                BigInteger.ZERO, BigInteger.valueOf(-1), BigInteger.ZERO);
        assertFailure("expMod: 0 is not invertible modulo 7",
                BigInteger.ZERO, BigInteger.valueOf(-1), BigInteger.valueOf(7));
        assertFailure("expMod: 2 is not invertible modulo 4",
                BigInteger.TWO, BigInteger.valueOf(-1), BigInteger.valueOf(4));
    }

    private static void assertFailure(
            String expected, BigInteger base, BigInteger exponent, BigInteger modulus) {
        var failure = assertThrows(ExpModIntegerSemantics.EvaluationFailure.class,
                () -> ExpModIntegerSemantics.evaluate(base, exponent, modulus));
        assertEquals(expected, failure.getMessage());
    }
}
