package com.bloxbean.cardano.julc.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;

import java.math.BigInteger;
import java.util.List;

/**
 * The type of an interval bound: negative infinity, finite, or positive infinity.
 */
public sealed interface IntervalBoundType extends PlutusDataConvertible {

    record NegInf() implements IntervalBoundType {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(0, List.of());
        }
    }

    record Finite(BigInteger time) implements IntervalBoundType {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(1, List.of(new PlutusData.IntData(time)));
        }
    }

    record PosInf() implements IntervalBoundType {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(2, List.of());
        }
    }

    static IntervalBoundType fromPlutusData(PlutusData data) {
        var c = PlutusDataHelper.expectConstr(data);
        return switch (c.tag()) {
            case 0 -> new NegInf();
            case 1 -> new Finite(PlutusDataHelper.decodeInteger(c.fields().getFirst()));
            case 2 -> new PosInf();
            default -> throw new IllegalArgumentException("Invalid IntervalBoundType tag: " + c.tag());
        };
    }
}
