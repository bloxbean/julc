package com.bloxbean.cardano.julc.bls;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertThrows;

class BlsOperationsTest {

    @Test
    void g1MultiScalarMulChecksScalarsBeyondTheZippedPrefix() {
        var oversized = BigInteger.ONE.shiftLeft(4095);

        assertThrows(BlsException.class, () -> BlsOperations.g1MultiScalarMul(
                new BigInteger[]{BigInteger.ONE, oversized}, new byte[0][]));
    }

    @Test
    void g2MultiScalarMulChecksScalarsBeyondTheZippedPrefix() {
        var oversized = BigInteger.ONE.shiftLeft(4095);

        assertThrows(BlsException.class, () -> BlsOperations.g2MultiScalarMul(
                new BigInteger[]{BigInteger.ONE, oversized}, new byte[0][]));
    }
}
