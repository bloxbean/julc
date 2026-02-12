package com.bloxbean.cardano.julc.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;

import java.math.BigInteger;
import java.util.List;

/**
 * A time interval with lower and upper bounds.
 */
public record Interval(IntervalBound from, IntervalBound to) implements PlutusDataConvertible {

    @Override
    public PlutusData.ConstrData toPlutusData() {
        return new PlutusData.ConstrData(0, List.of(
                from.toPlutusData(),
                to.toPlutusData()));
    }

    public static Interval fromPlutusData(PlutusData data) {
        var fields = PlutusDataHelper.expectConstr(data, 0);
        return new Interval(
                IntervalBound.fromPlutusData(fields.get(0)),
                IntervalBound.fromPlutusData(fields.get(1)));
    }

    /** The interval containing all values: (-inf, +inf). */
    public static Interval always() {
        return new Interval(
                new IntervalBound(new IntervalBoundType.NegInf(), true),
                new IntervalBound(new IntervalBoundType.PosInf(), true));
    }

    /** The empty interval: (+inf, -inf). */
    public static Interval never() {
        return new Interval(
                new IntervalBound(new IntervalBoundType.PosInf(), true),
                new IntervalBound(new IntervalBoundType.NegInf(), true));
    }

    /** The interval [time, +inf). */
    public static Interval after(BigInteger time) {
        return new Interval(
                new IntervalBound(new IntervalBoundType.Finite(time), true),
                new IntervalBound(new IntervalBoundType.PosInf(), true));
    }

    /** The interval (-inf, time]. */
    public static Interval before(BigInteger time) {
        return new Interval(
                new IntervalBound(new IntervalBoundType.NegInf(), true),
                new IntervalBound(new IntervalBoundType.Finite(time), true));
    }

    /** The interval [from, to]. */
    public static Interval between(BigInteger from, BigInteger to) {
        return new Interval(
                new IntervalBound(new IntervalBoundType.Finite(from), true),
                new IntervalBound(new IntervalBoundType.Finite(to), true));
    }
}
