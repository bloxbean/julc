package com.bloxbean.cardano.plutus.ledger;

import com.bloxbean.cardano.plutus.core.PlutusData;

import java.util.List;

/**
 * An input being consumed or referenced by a transaction.
 */
public record TxInInfo(TxOutRef outRef, TxOut resolved) implements PlutusDataConvertible {

    @Override
    public PlutusData toPlutusData() {
        return new PlutusData.Constr(0, List.of(
                outRef.toPlutusData(),
                resolved.toPlutusData()));
    }

    public static TxInInfo fromPlutusData(PlutusData data) {
        var fields = PlutusDataHelper.expectConstr(data, 0);
        return new TxInInfo(
                TxOutRef.fromPlutusData(fields.get(0)),
                TxOut.fromPlutusData(fields.get(1)));
    }
}
