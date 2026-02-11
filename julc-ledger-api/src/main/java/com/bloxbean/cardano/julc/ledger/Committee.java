package com.bloxbean.cardano.julc.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * A governance committee.
 */
public record Committee(Map<Credential, BigInteger> members, Rational quorum) implements PlutusDataConvertible {

    @Override
    public PlutusData toPlutusData() {
        return new PlutusData.Constr(0, List.of(
                PlutusDataHelper.encodeMap(members,
                        Credential::toPlutusData, PlutusDataHelper::encodeInteger),
                quorum.toPlutusData()));
    }

    public static Committee fromPlutusData(PlutusData data) {
        var fields = PlutusDataHelper.expectConstr(data, 0);
        return new Committee(
                PlutusDataHelper.decodeMap(fields.get(0),
                        Credential::fromPlutusData, PlutusDataHelper::decodeInteger),
                Rational.fromPlutusData(fields.get(1)));
    }
}
