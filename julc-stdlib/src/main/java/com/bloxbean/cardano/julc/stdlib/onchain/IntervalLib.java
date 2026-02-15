package com.bloxbean.cardano.julc.stdlib.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.onchain.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.onchain.stdlib.Builtins;

import java.math.BigInteger;

/**
 * Interval / POSIXTimeRange operations compiled from Java source to UPLC.
 * <p>
 * Interval encoding in Plutus Data:
 * <pre>
 * Interval = Constr(0, [from: IntervalBound, to: IntervalBound])
 * IntervalBound = Constr(0, [boundType: IntervalBoundType, isInclusive: Bool])
 * IntervalBoundType:
 *   NegInf  = Constr(0, [])
 *   Finite  = Constr(1, [time: Integer])
 *   PosInf  = Constr(2, [])
 * Bool: False = Constr(0, []), True = Constr(1, [])
 * </pre>
 */
@OnchainLibrary
public class IntervalLib {

    /** Checks whether a point in time is contained within an interval. */
    public static boolean contains(PlutusData.ConstrData interval, BigInteger time) {
        var fields = Builtins.constrFields(interval);
        var fromBound = Builtins.headList(fields);
        var toBound = Builtins.headList(Builtins.tailList(fields));
        if (checkLowerBound((PlutusData.ConstrData) fromBound, time)) {
            return checkUpperBound((PlutusData.ConstrData) toBound, time);
        } else {
            return false;
        }
    }

    /** Builds the "always" interval: (-inf, +inf). */
    public static PlutusData.ConstrData always() {
        var fromBound = inclusiveBound(Builtins.constrData(0, Builtins.mkNilData()));
        var toBound = inclusiveBound(Builtins.constrData(2, Builtins.mkNilData()));
        return makeInterval(fromBound, toBound);
    }

    /** Builds the interval [time, +inf). */
    public static PlutusData.ConstrData after(BigInteger time) {
        var finiteFields = Builtins.mkCons(Builtins.iData(time), Builtins.mkNilData());
        var finite = Builtins.constrData(1, finiteFields);
        var fromBound = inclusiveBound(finite);
        var toBound = inclusiveBound(Builtins.constrData(2, Builtins.mkNilData()));
        return makeInterval(fromBound, toBound);
    }

    /** Builds the interval (-inf, time]. */
    public static PlutusData.ConstrData before(BigInteger time) {
        var finiteFields = Builtins.mkCons(Builtins.iData(time), Builtins.mkNilData());
        var finite = Builtins.constrData(1, finiteFields);
        var fromBound = inclusiveBound(Builtins.constrData(0, Builtins.mkNilData()));
        var toBound = inclusiveBound(finite);
        return makeInterval(fromBound, toBound);
    }

    /** Builds the interval [low, high] (both inclusive). */
    public static PlutusData.ConstrData between(BigInteger low, BigInteger high) {
        var lowFields = Builtins.mkCons(Builtins.iData(low), Builtins.mkNilData());
        var lowFinite = Builtins.constrData(1, lowFields);
        var highFields = Builtins.mkCons(Builtins.iData(high), Builtins.mkNilData());
        var highFinite = Builtins.constrData(1, highFields);
        var fromBound = inclusiveBound(lowFinite);
        var toBound = inclusiveBound(highFinite);
        return makeInterval(fromBound, toBound);
    }

    /** Builds the empty interval (PosInf, NegInf). */
    public static PlutusData.ConstrData never() {
        var fromBound = exclusiveBound(Builtins.constrData(2, Builtins.mkNilData()));
        var toBound = exclusiveBound(Builtins.constrData(0, Builtins.mkNilData()));
        return makeInterval(fromBound, toBound);
    }

    /** Checks if an interval is empty (lower is PosInf or upper is NegInf). */
    public static boolean isEmpty(PlutusData.ConstrData interval) {
        var fields = Builtins.constrFields(interval);
        var fromBound = Builtins.headList(fields);
        var toBound = Builtins.headList(Builtins.tailList(fields));
        var fromFields = Builtins.constrFields(fromBound);
        var fromType = Builtins.headList(fromFields);
        var fromTypeTag = Builtins.constrTag(fromType);
        var toFields = Builtins.constrFields(toBound);
        var toType = Builtins.headList(toFields);
        var toTypeTag = Builtins.constrTag(toType);
        if (fromTypeTag == 2) {
            return true;
        } else {
            return toTypeTag == 0;
        }
    }

    /** Check lower bound: time >= fromBound. NegInf=true, Finite=compare, PosInf=false. */
    public static boolean checkLowerBound(PlutusData.ConstrData bound, BigInteger time) {
        var boundFields = Builtins.constrFields(bound);
        var boundType = Builtins.headList(boundFields);
        var isInclusiveData = Builtins.headList(Builtins.tailList(boundFields));
        var typeTag = Builtins.constrTag(boundType);
        if (typeTag == 0) {
            return true;
        } else {
            if (typeTag == 1) {
                var typeFields = Builtins.constrFields(boundType);
                var t = Builtins.unIData(Builtins.headList(typeFields));
                var isInclusiveTag = Builtins.constrTag(isInclusiveData);
                if (isInclusiveTag == 1) {
                    return time.compareTo(t) >= 0;
                } else {
                    return time.compareTo(t) > 0;
                }
            } else {
                return false;
            }
        }
    }

    /** Check upper bound: time <= toBound. NegInf=false, Finite=compare, PosInf=true. */
    public static boolean checkUpperBound(PlutusData.ConstrData bound, BigInteger time) {
        var boundFields = Builtins.constrFields(bound);
        var boundType = Builtins.headList(boundFields);
        var isInclusiveData = Builtins.headList(Builtins.tailList(boundFields));
        var typeTag = Builtins.constrTag(boundType);
        if (typeTag == 0) {
            return false;
        } else {
            if (typeTag == 1) {
                var typeFields = Builtins.constrFields(boundType);
                var t = Builtins.unIData(Builtins.headList(typeFields));
                var isInclusiveTag = Builtins.constrTag(isInclusiveData);
                if (isInclusiveTag == 1) {
                    return time.compareTo(t) <= 0;
                } else {
                    return time.compareTo(t) < 0;
                }
            } else {
                return true;
            }
        }
    }

    /** Build an inclusive IntervalBound: Constr(0, [boundType, True]). */
    public static PlutusData.ConstrData inclusiveBound(PlutusData.ConstrData boundType) {
        var trueVal = Builtins.constrData(1, Builtins.mkNilData());
        var fields = Builtins.mkCons(boundType, Builtins.mkCons(trueVal, Builtins.mkNilData()));
        return Builtins.constrData(0, fields);
    }

    /** Build an exclusive IntervalBound: Constr(0, [boundType, False]). */
    public static PlutusData.ConstrData exclusiveBound(PlutusData.ConstrData boundType) {
        var falseVal = Builtins.constrData(0, Builtins.mkNilData());
        var fields = Builtins.mkCons(boundType, Builtins.mkCons(falseVal, Builtins.mkNilData()));
        return Builtins.constrData(0, fields);
    }

    /** Build an Interval from two IntervalBounds. */
    public static PlutusData.ConstrData makeInterval(PlutusData.ConstrData fromBound, PlutusData.ConstrData toBound) {
        var fields = Builtins.mkCons(fromBound, Builtins.mkCons(toBound, Builtins.mkNilData()));
        return Builtins.constrData(0, fields);
    }

    /** Extract the finite upper bound time, or return -1 if not finite.
     *  Useful for checking validity range deadlines. */
    public static BigInteger finiteUpperBound(PlutusData.ConstrData interval) {
        var fields = Builtins.constrFields(interval);
        var toBound = Builtins.headList(Builtins.tailList(fields));
        var boundFields = Builtins.constrFields(toBound);
        var boundType = Builtins.headList(boundFields);
        var typeTag = Builtins.constrTag(boundType);
        if (typeTag == 1) {
            var typeFields = Builtins.constrFields(boundType);
            return Builtins.unIData(Builtins.headList(typeFields));
        } else {
            return BigInteger.valueOf(-1);
        }
    }

    /** Extract the finite lower bound time, or return -1 if not finite.
     *  Useful for checking validity range start times. */
    public static BigInteger finiteLowerBound(PlutusData.ConstrData interval) {
        var fields = Builtins.constrFields(interval);
        var fromBound = Builtins.headList(fields);
        var boundFields = Builtins.constrFields(fromBound);
        var boundType = Builtins.headList(boundFields);
        var typeTag = Builtins.constrTag(boundType);
        if (typeTag == 1) {
            var typeFields = Builtins.constrFields(boundType);
            return Builtins.unIData(Builtins.headList(typeFields));
        } else {
            return BigInteger.valueOf(-1);
        }
    }
}
