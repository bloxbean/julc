package com.bloxbean.cardano.julc.onchain.ledger;

import java.math.BigInteger;

/**
 * A time interval with lower and upper bounds.
 * <p>
 * This is an IDE stub. The compiler uses the schema from LedgerTypeRegistry.
 */
public record Interval(IntervalBound from, IntervalBound to) {

    /** Return the "always" interval: (-inf, +inf). */
    public static Interval always() {
        return new Interval(
                new IntervalBound(new IntervalBoundType.NegInf(), true),
                new IntervalBound(new IntervalBoundType.PosInf(), true));
    }

    /** Return interval [time, +inf). */
    public static Interval after(BigInteger time) {
        return new Interval(
                new IntervalBound(new IntervalBoundType.Finite(time), true),
                new IntervalBound(new IntervalBoundType.PosInf(), true));
    }

    /** Return interval (-inf, time]. */
    public static Interval before(BigInteger time) {
        return new Interval(
                new IntervalBound(new IntervalBoundType.NegInf(), true),
                new IntervalBound(new IntervalBoundType.Finite(time), true));
    }

    /** Return interval [from, to]. */
    public static Interval between(BigInteger from, BigInteger to) {
        return new Interval(
                new IntervalBound(new IntervalBoundType.Finite(from), true),
                new IntervalBound(new IntervalBoundType.Finite(to), true));
    }
}
