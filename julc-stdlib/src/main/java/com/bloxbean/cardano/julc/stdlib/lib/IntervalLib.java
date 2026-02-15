package com.bloxbean.cardano.julc.stdlib.lib;

import com.bloxbean.cardano.julc.onchain.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.ledger.Interval;
import com.bloxbean.cardano.julc.ledger.IntervalBound;
import com.bloxbean.cardano.julc.ledger.IntervalBoundType;

import java.math.BigInteger;

/**
 * Interval / POSIXTimeRange operations compiled from Java source to UPLC.
 * <p>
 * Uses typed records (Interval, IntervalBound, IntervalBoundType) for readability.
 * Works both on-chain (compiled to UPLC) and off-chain (as plain Java).
 * <p>
 * Note: Parameter names must NOT collide with constructor field names (e.g. "time"
 * in IntervalBoundType.Finite) because the compiler's switch pattern binding shadows
 * outer-scope variables of the same name.
 */
@OnchainLibrary
public class IntervalLib {

    /** Checks whether a point in time is contained within an interval. */
    public static boolean contains(Interval interval, BigInteger point) {
        return checkLowerBound(interval.from(), point) && checkUpperBound(interval.to(), point);
    }

    /** Check lower bound: point >= fromBound. */
    private static boolean checkLowerBound(IntervalBound bound, BigInteger point) {
        // Extract isInclusive before the switch to avoid scoping issues in match arms
        boolean inclusive = bound.isInclusive();
        return switch (bound.boundType()) {
            case IntervalBoundType.NegInf ignored -> true;
            case IntervalBoundType.PosInf ignored -> false;
            case IntervalBoundType.Finite f -> inclusive
                    ? point.compareTo(f.time()) >= 0
                    : point.compareTo(f.time()) > 0;
        };
    }

    /** Check upper bound: point <= toBound. */
    private static boolean checkUpperBound(IntervalBound bound, BigInteger point) {
        // Extract isInclusive before the switch to avoid scoping issues in match arms
        boolean inclusive = bound.isInclusive();
        return switch (bound.boundType()) {
            case IntervalBoundType.NegInf ignored -> false;
            case IntervalBoundType.PosInf ignored -> true;
            case IntervalBoundType.Finite f -> inclusive
                    ? point.compareTo(f.time()) <= 0
                    : point.compareTo(f.time()) < 0;
        };
    }

    /** Builds the "always" interval: (-inf, +inf). */
    public static Interval always() {
        return new Interval(
                new IntervalBound(new IntervalBoundType.NegInf(), true),
                new IntervalBound(new IntervalBoundType.PosInf(), true));
    }

    /** Builds the interval [t, +inf). */
    public static Interval after(BigInteger t) {
        return new Interval(
                new IntervalBound(new IntervalBoundType.Finite(t), true),
                new IntervalBound(new IntervalBoundType.PosInf(), true));
    }

    /** Builds the interval (-inf, t]. */
    public static Interval before(BigInteger t) {
        return new Interval(
                new IntervalBound(new IntervalBoundType.NegInf(), true),
                new IntervalBound(new IntervalBoundType.Finite(t), true));
    }

    /** Builds the interval [low, high] (both inclusive). */
    public static Interval between(BigInteger low, BigInteger high) {
        return new Interval(
                new IntervalBound(new IntervalBoundType.Finite(low), true),
                new IntervalBound(new IntervalBoundType.Finite(high), true));
    }

    /** Builds the empty interval (PosInf, NegInf). */
    public static Interval never() {
        return new Interval(
                new IntervalBound(new IntervalBoundType.PosInf(), false),
                new IntervalBound(new IntervalBoundType.NegInf(), false));
    }

    /** Checks if an interval is empty (lower is PosInf or upper is NegInf). */
    public static boolean isEmpty(Interval interval) {
        boolean fromIsPosInf = switch (interval.from().boundType()) {
            case IntervalBoundType.PosInf ignored -> true;
            case IntervalBoundType.NegInf ignored -> false;
            case IntervalBoundType.Finite ignored -> false;
        };
        if (fromIsPosInf) {
            return true;
        }
        return switch (interval.to().boundType()) {
            case IntervalBoundType.NegInf ignored -> true;
            case IntervalBoundType.PosInf ignored -> false;
            case IntervalBoundType.Finite ignored -> false;
        };
    }

    /** Extract the finite upper bound time, or return -1 if not finite. */
    public static BigInteger finiteUpperBound(Interval interval) {
        return switch (interval.to().boundType()) {
            case IntervalBoundType.Finite f -> f.time();
            case IntervalBoundType.NegInf ignored -> BigInteger.valueOf(-1);
            case IntervalBoundType.PosInf ignored -> BigInteger.valueOf(-1);
        };
    }

    /** Extract the finite lower bound time, or return -1 if not finite. */
    public static BigInteger finiteLowerBound(Interval interval) {
        return switch (interval.from().boundType()) {
            case IntervalBoundType.Finite f -> f.time();
            case IntervalBoundType.NegInf ignored -> BigInteger.valueOf(-1);
            case IntervalBoundType.PosInf ignored -> BigInteger.valueOf(-1);
        };
    }
}
