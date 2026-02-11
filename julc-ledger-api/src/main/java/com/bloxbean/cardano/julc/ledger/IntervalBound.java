package com.bloxbean.cardano.julc.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;

import java.util.List;

/**
 * A bound of an interval with inclusivity flag.
 */
public record IntervalBound(IntervalBoundType boundType, boolean isInclusive) implements PlutusDataConvertible {

    @Override
    public PlutusData toPlutusData() {
        return new PlutusData.Constr(0, List.of(
                boundType.toPlutusData(),
                PlutusDataHelper.encodeBool(isInclusive)));
    }

    public static IntervalBound fromPlutusData(PlutusData data) {
        var fields = PlutusDataHelper.expectConstr(data, 0);
        return new IntervalBound(
                IntervalBoundType.fromPlutusData(fields.get(0)),
                PlutusDataHelper.decodeBool(fields.get(1)));
    }
}
