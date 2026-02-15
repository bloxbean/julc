package com.bloxbean.cardano.julc.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcMap;

import java.math.BigInteger;
import java.util.List;

/**
 * A governance committee.
 */
public record Committee(JulcMap<Credential, BigInteger> members, Rational quorum) implements PlutusDataConvertible {

    @Override
    public PlutusData.ConstrData toPlutusData() {
        return new PlutusData.ConstrData(0, List.of(
                PlutusDataHelper.encodeJulcMap(members,
                        Credential::toPlutusData, PlutusDataHelper::encodeInteger),
                quorum.toPlutusData()));
    }

    public static Committee fromPlutusData(PlutusData data) {
        var fields = PlutusDataHelper.expectConstr(data, 0);
        return new Committee(
                PlutusDataHelper.decodeJulcMap(fields.get(0),
                        Credential::fromPlutusData, PlutusDataHelper::decodeInteger),
                Rational.fromPlutusData(fields.get(1)));
    }
}
