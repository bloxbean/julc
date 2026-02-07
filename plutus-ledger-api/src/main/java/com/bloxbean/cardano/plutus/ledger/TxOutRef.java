package com.bloxbean.cardano.plutus.ledger;

import com.bloxbean.cardano.plutus.core.PlutusData;

import java.math.BigInteger;
import java.util.List;

/**
 * A reference to a transaction output (TxId + output index).
 */
public record TxOutRef(TxId txId, BigInteger index) implements PlutusDataConvertible {

    @Override
    public PlutusData toPlutusData() {
        return new PlutusData.Constr(0, List.of(
                txId.toPlutusData(),
                new PlutusData.IntData(index)));
    }

    public static TxOutRef fromPlutusData(PlutusData data) {
        var fields = PlutusDataHelper.expectConstr(data, 0);
        return new TxOutRef(
                TxId.fromPlutusData(fields.get(0)),
                PlutusDataHelper.decodeInteger(fields.get(1)));
    }
}
