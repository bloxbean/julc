package com.bloxbean.cardano.plutus.stdlib;

import com.bloxbean.cardano.plutus.compiler.pir.PirTerm;
import com.bloxbean.cardano.plutus.compiler.pir.PirType;
import com.bloxbean.cardano.plutus.core.Constant;
import com.bloxbean.cardano.plutus.core.DefaultFun;

import java.math.BigInteger;
import java.util.List;

/**
 * Interval / POSIXTimeRange operations built as PIR term builders.
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
 * <p>
 * The {@code contains} check tests whether a given time falls within the interval bounds,
 * accounting for inclusive/exclusive endpoints and infinity.
 */
public final class IntervalLib {

    private IntervalLib() {}

    /**
     * Checks whether a point in time is contained within an interval.
     * <p>
     * contains(interval, time) returns True if the time is within the interval bounds.
     * <p>
     * This performs: fromBound &lt;= time &amp;&amp; time &lt;= toBound,
     * accounting for NegInf/PosInf/Finite and inclusive/exclusive.
     *
     * @param interval PIR term representing an Interval (as Data)
     * @param time     PIR term representing a POSIXTime (as Data: IData(integer))
     * @return PIR term that evaluates to Bool
     */
    public static PirTerm contains(PirTerm interval, PirTerm time) {
        // Extract the interval bounds
        var intervalVar = new PirTerm.Var("interval_", new PirType.DataType());
        var timeVar = new PirTerm.Var("time_", new PirType.IntegerType());

        // Extract time integer from IData
        var timeExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData), time);

        // Interval = Constr(0, [from, to])
        var fields = sndPairUnconstData(intervalVar);
        var fromBound = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), fields);
        var toBound = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), fields));

        var fromVar = new PirTerm.Var("from_", new PirType.DataType());
        var toVar = new PirTerm.Var("to_", new PirType.DataType());

        // Check from bound: time >= fromBound
        var fromCheck = checkLowerBound(fromVar, timeVar);
        // Check to bound: time <= toBound
        var toCheck = checkUpperBound(toVar, timeVar);

        // Both must be true
        var result = new PirTerm.IfThenElse(fromCheck, toCheck,
                new PirTerm.Const(Constant.bool(false)));

        return new PirTerm.Let("interval_", interval,
                new PirTerm.Let("time_", timeExpr,
                        new PirTerm.Let("from_", fromBound,
                                new PirTerm.Let("to_", toBound, result))));
    }

    /**
     * Builds a PIR term representing the "always" interval: (-inf, +inf).
     * <p>
     * This is a constant Data value representing an interval that contains all times.
     *
     * @return PIR term evaluating to Data (an Interval)
     */
    public static PirTerm always() {
        // Interval(Bound(NegInf, True), Bound(PosInf, True))
        var negInf = constrData(0, List.of()); // NegInf
        var posInf = constrData(2, List.of()); // PosInf
        var trueData = constrData(1, List.of()); // True
        var fromBound = constrData(0, List.of(negInf, trueData));
        var toBound = constrData(0, List.of(posInf, trueData));
        return constrData(0, List.of(fromBound, toBound));
    }

    /**
     * Builds a PIR term representing the interval [time, +inf).
     *
     * @param time PIR term representing a POSIXTime integer
     * @return PIR term evaluating to Data (an Interval)
     */
    public static PirTerm after(PirTerm time) {
        // Interval(Bound(Finite(time), True), Bound(PosInf, True))
        var timeData = new PirTerm.App(new PirTerm.Builtin(DefaultFun.IData), time);
        var finite = constrData(1, List.of(timeData));
        var posInf = constrData(2, List.of());
        var trueData = constrData(1, List.of());
        var fromBound = constrData(0, List.of(finite, trueData));
        var toBound = constrData(0, List.of(posInf, trueData));
        return constrData(0, List.of(fromBound, toBound));
    }

    /**
     * Builds a PIR term representing the interval (-inf, time].
     *
     * @param time PIR term representing a POSIXTime integer
     * @return PIR term evaluating to Data (an Interval)
     */
    public static PirTerm before(PirTerm time) {
        // Interval(Bound(NegInf, True), Bound(Finite(time), True))
        var timeData = new PirTerm.App(new PirTerm.Builtin(DefaultFun.IData), time);
        var negInf = constrData(0, List.of());
        var finite = constrData(1, List.of(timeData));
        var trueData = constrData(1, List.of());
        var fromBound = constrData(0, List.of(negInf, trueData));
        var toBound = constrData(0, List.of(finite, trueData));
        return constrData(0, List.of(fromBound, toBound));
    }

    // ---- Internal helpers ----

    /**
     * Check lower bound: time >= fromBound.
     * <p>
     * IntervalBound = Constr(0, [boundType, isInclusive])
     * BoundType: NegInf=0, Finite(t)=1, PosInf=2
     * <p>
     * NegInf: always true (any time is >= -inf)
     * Finite(t): if inclusive then time >= t, else time > t
     * PosInf: always false (no time is >= +inf)
     */
    private static PirTerm checkLowerBound(PirTerm bound, PirTerm time) {
        // UnConstrData(bound) -> (tag=0, [boundType, isInclusive])
        var boundFields = sndPairUnconstData(bound);
        var boundType = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), boundFields);
        var isInclusiveData = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), boundFields));

        // UnConstrData(boundType) -> (tag, fields)
        var unConstrBoundType = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnConstrData), boundType);
        var boundTypeTag = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), unConstrBoundType);
        var boundTypeFields = new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair), unConstrBoundType);

        // isInclusive: UnConstrData(isInclusiveData).fstPair
        var isInclusiveTag = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnConstrData), isInclusiveData));

        // tag == 0 (NegInf) -> True
        // tag == 2 (PosInf) -> False
        // tag == 1 (Finite) -> check time vs t
        var tagVar = new PirTerm.Var("lbTag_", new PirType.IntegerType());
        var fieldsVar = new PirTerm.Var("lbFields_", new PirType.ListType(new PirType.DataType()));
        var inclVar = new PirTerm.Var("lbIncl_", new PirType.IntegerType());

        // Finite case: t = UnIData(HeadList(fieldsVar))
        var t = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), fieldsVar));
        // inclusive: time >= t = LessThanEqualsInteger(t, time)
        var geq = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.LessThanEqualsInteger), t),
                time);
        // exclusive: time > t = LessThanInteger(t, time)
        var gt = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.LessThanInteger), t),
                time);
        // isInclusive? (tag=1 means True, tag=0 means False)
        var isInclusive = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger), inclVar),
                new PirTerm.Const(Constant.integer(BigInteger.ONE)));
        var finiteResult = new PirTerm.IfThenElse(isInclusive, geq, gt);

        // tag == 0? (NegInf)
        var isNegInf = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger), tagVar),
                new PirTerm.Const(Constant.integer(BigInteger.ZERO)));

        // tag == 1? (Finite)
        var isFinite = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger), tagVar),
                new PirTerm.Const(Constant.integer(BigInteger.ONE)));

        // if NegInf -> True, elif Finite -> finiteResult, else (PosInf) -> False
        var result = new PirTerm.IfThenElse(isNegInf,
                new PirTerm.Const(Constant.bool(true)),
                new PirTerm.IfThenElse(isFinite, finiteResult,
                        new PirTerm.Const(Constant.bool(false))));

        return new PirTerm.Let("lbTag_", boundTypeTag,
                new PirTerm.Let("lbFields_", boundTypeFields,
                        new PirTerm.Let("lbIncl_", isInclusiveTag, result)));
    }

    /**
     * Check upper bound: time <= toBound.
     * <p>
     * NegInf: always false (no time is <= -inf)
     * Finite(t): if inclusive then time <= t, else time < t
     * PosInf: always true (any time is <= +inf)
     */
    private static PirTerm checkUpperBound(PirTerm bound, PirTerm time) {
        var boundFields = sndPairUnconstData(bound);
        var boundType = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), boundFields);
        var isInclusiveData = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), boundFields));

        var unConstrBoundType = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnConstrData), boundType);
        var boundTypeTag = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), unConstrBoundType);
        var boundTypeFields = new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair), unConstrBoundType);

        var isInclusiveTag = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnConstrData), isInclusiveData));

        var tagVar = new PirTerm.Var("ubTag_", new PirType.IntegerType());
        var fieldsVar = new PirTerm.Var("ubFields_", new PirType.ListType(new PirType.DataType()));
        var inclVar = new PirTerm.Var("ubIncl_", new PirType.IntegerType());

        // Finite case
        var t = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), fieldsVar));
        // inclusive: time <= t = LessThanEqualsInteger(time, t)
        var leq = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.LessThanEqualsInteger), time),
                t);
        // exclusive: time < t = LessThanInteger(time, t)
        var lt = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.LessThanInteger), time),
                t);
        var isInclusive = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger), inclVar),
                new PirTerm.Const(Constant.integer(BigInteger.ONE)));
        var finiteResult = new PirTerm.IfThenElse(isInclusive, leq, lt);

        // tag == 0 (NegInf) -> False
        var isNegInf = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger), tagVar),
                new PirTerm.Const(Constant.integer(BigInteger.ZERO)));
        // tag == 1 (Finite) -> finiteResult
        var isFinite = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger), tagVar),
                new PirTerm.Const(Constant.integer(BigInteger.ONE)));

        var result = new PirTerm.IfThenElse(isNegInf,
                new PirTerm.Const(Constant.bool(false)),
                new PirTerm.IfThenElse(isFinite, finiteResult,
                        new PirTerm.Const(Constant.bool(true))));

        return new PirTerm.Let("ubTag_", boundTypeTag,
                new PirTerm.Let("ubFields_", boundTypeFields,
                        new PirTerm.Let("ubIncl_", isInclusiveTag, result)));
    }

    /**
     * SndPair(UnConstrData(term)) — extracts the fields list from a Constr-encoded Data.
     */
    private static PirTerm sndPairUnconstData(PirTerm term) {
        return new PirTerm.App(
                new PirTerm.Builtin(DefaultFun.SndPair),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnConstrData), term));
    }

    /**
     * Build a Constr Data value at the PIR level using ConstrData builtin.
     */
    private static PirTerm constrData(int tag, List<PirTerm> fields) {
        // ConstrData(tag, ListData(fields))
        // Actually: ConstrData takes (Integer, List<Data>)
        // Build the list: MkCons(field_n, MkCons(field_n-1, ... MkNilData(())))
        PirTerm list = new PirTerm.App(
                new PirTerm.Builtin(DefaultFun.MkNilData),
                new PirTerm.Const(Constant.unit()));
        for (int i = fields.size() - 1; i >= 0; i--) {
            list = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), fields.get(i)),
                    list);
        }
        return new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.ConstrData),
                        new PirTerm.Const(Constant.integer(BigInteger.valueOf(tag)))),
                list);
    }
}
