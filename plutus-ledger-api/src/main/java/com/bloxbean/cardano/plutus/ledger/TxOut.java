package com.bloxbean.cardano.plutus.ledger;

import com.bloxbean.cardano.plutus.core.PlutusData;

import java.util.List;
import java.util.Optional;

/**
 * A transaction output.
 */
public record TxOut(Address address, Value value, OutputDatum datum, Optional<ScriptHash> referenceScript)
        implements PlutusDataConvertible {

    @Override
    public PlutusData toPlutusData() {
        return new PlutusData.Constr(0, List.of(
                address.toPlutusData(),
                value.toPlutusData(),
                datum.toPlutusData(),
                PlutusDataHelper.encodeOptional(referenceScript, ScriptHash::toPlutusData)));
    }

    public static TxOut fromPlutusData(PlutusData data) {
        var fields = PlutusDataHelper.expectConstr(data, 0);
        return new TxOut(
                Address.fromPlutusData(fields.get(0)),
                Value.fromPlutusData(fields.get(1)),
                OutputDatum.fromPlutusData(fields.get(2)),
                PlutusDataHelper.decodeOptional(fields.get(3), ScriptHash::fromPlutusData));
    }
}
