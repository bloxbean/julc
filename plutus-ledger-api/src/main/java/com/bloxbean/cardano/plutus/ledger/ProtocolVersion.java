package com.bloxbean.cardano.plutus.ledger;

import com.bloxbean.cardano.plutus.core.PlutusData;

import java.math.BigInteger;
import java.util.List;

/**
 * A protocol version (major, minor).
 */
public record ProtocolVersion(BigInteger major, BigInteger minor) implements PlutusDataConvertible {

    @Override
    public PlutusData toPlutusData() {
        return new PlutusData.Constr(0, List.of(
                new PlutusData.IntData(major),
                new PlutusData.IntData(minor)));
    }

    public static ProtocolVersion fromPlutusData(PlutusData data) {
        var fields = PlutusDataHelper.expectConstr(data, 0);
        return new ProtocolVersion(
                PlutusDataHelper.decodeInteger(fields.get(0)),
                PlutusDataHelper.decodeInteger(fields.get(1)));
    }
}
