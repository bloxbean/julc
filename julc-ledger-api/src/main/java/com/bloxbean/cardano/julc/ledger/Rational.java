package com.bloxbean.cardano.julc.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;

import java.math.BigInteger;
import java.util.List;

/**
 * A rational number (numerator / denominator).
 */
public record Rational(BigInteger numerator, BigInteger denominator) implements PlutusDataConvertible {

    @Override
    public PlutusData toPlutusData() {
        return new PlutusData.Constr(0, List.of(
                new PlutusData.IntData(numerator),
                new PlutusData.IntData(denominator)));
    }

    public static Rational fromPlutusData(PlutusData data) {
        var fields = PlutusDataHelper.expectConstr(data, 0);
        return new Rational(
                PlutusDataHelper.decodeInteger(fields.get(0)),
                PlutusDataHelper.decodeInteger(fields.get(1)));
    }
}
