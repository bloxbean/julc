package com.bloxbean.cardano.plutus.onchain.stdlib;

import com.bloxbean.cardano.plutus.onchain.ledger.Interval;
import com.bloxbean.cardano.plutus.onchain.ledger.IntervalBound;
import com.bloxbean.cardano.plutus.onchain.ledger.IntervalBoundType;

import java.math.BigInteger;

/**
 * On-chain time interval operations.
 * <p>
 * These methods are executable both on-chain (compiled to UPLC via StdlibRegistry)
 * and off-chain (as plain Java for debugging and testing).
 */
public final class IntervalLib {

    private IntervalLib() {}

    /** Check if a time point falls within the interval bounds. */
    public static boolean contains(Interval interval, BigInteger time) {
        return checkLowerBound(interval.from(), time) && checkUpperBound(interval.to(), time);
    }

    private static boolean checkLowerBound(IntervalBound bound, BigInteger time) {
        return switch (bound.boundType()) {
            case IntervalBoundType.NegInf ignored -> true;
            case IntervalBoundType.PosInf ignored -> false;
            case IntervalBoundType.Finite f -> bound.isInclusive()
                    ? time.compareTo(f.time()) >= 0
                    : time.compareTo(f.time()) > 0;
        };
    }

    private static boolean checkUpperBound(IntervalBound bound, BigInteger time) {
        return switch (bound.boundType()) {
            case IntervalBoundType.NegInf ignored -> false;
            case IntervalBoundType.PosInf ignored -> true;
            case IntervalBoundType.Finite f -> bound.isInclusive()
                    ? time.compareTo(f.time()) <= 0
                    : time.compareTo(f.time()) < 0;
        };
    }

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
}
