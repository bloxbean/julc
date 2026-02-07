package com.bloxbean.cardano.plutus.onchain.stdlib;

import com.bloxbean.cardano.plutus.core.PlutusData;

/**
 * On-chain time interval operations.
 * <p>
 * These are compile-time stubs for IDE support. The actual on-chain implementation
 * is provided by the PlutusCompiler via {@code StdlibRegistry}.
 */
public final class IntervalLib {

    private IntervalLib() {}

    /** Check if a time point falls within the interval bounds. */
    public static boolean contains(PlutusData interval, PlutusData time) {
        throw new UnsupportedOperationException("On-chain only");
    }

    /** Return the "always" interval: (-inf, +inf). */
    public static PlutusData always() {
        throw new UnsupportedOperationException("On-chain only");
    }

    /** Return interval [time, +inf). */
    public static PlutusData after(PlutusData time) {
        throw new UnsupportedOperationException("On-chain only");
    }

    /** Return interval (-inf, time]. */
    public static PlutusData before(PlutusData time) {
        throw new UnsupportedOperationException("On-chain only");
    }
}
