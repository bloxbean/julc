package com.bloxbean.cardano.plutus.onchain.stdlib;

import com.bloxbean.cardano.plutus.onchain.ledger.Value;

import java.math.BigInteger;

/**
 * On-chain Value and asset operations.
 * <p>
 * These are compile-time stubs for IDE support. The actual on-chain implementation
 * is provided by the PlutusCompiler via {@code StdlibRegistry}.
 */
public final class ValuesLib {

    private ValuesLib() {}

    /** Extract lovelace (ADA) amount from a Value. */
    public static BigInteger lovelaceOf(Value value) {
        throw new UnsupportedOperationException("On-chain only");
    }

    /** Check if value {@code a} is greater than or equal to value {@code b} (by lovelace). */
    public static boolean geq(Value a, Value b) {
        throw new UnsupportedOperationException("On-chain only");
    }

    /** Extract the amount of a specific asset from a Value. */
    public static BigInteger assetOf(Value value, byte[] policyId, byte[] tokenName) {
        throw new UnsupportedOperationException("On-chain only");
    }
}
