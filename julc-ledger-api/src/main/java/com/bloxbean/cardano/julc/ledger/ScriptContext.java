package com.bloxbean.cardano.julc.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;

import java.util.List;

/**
 * V3 script context: transaction info + redeemer + script info.
 */
public record ScriptContext(TxInfo txInfo, PlutusData redeemer, ScriptInfo scriptInfo)
        implements PlutusDataConvertible {

    @Override
    public PlutusData.ConstrData toPlutusData() {
        return new PlutusData.ConstrData(0, List.of(
                txInfo.toPlutusData(),
                redeemer,
                scriptInfo.toPlutusData()));
    }

    public static ScriptContext fromPlutusData(PlutusData data) {
        var fields = PlutusDataHelper.expectConstr(data, 0);
        return new ScriptContext(
                TxInfo.fromPlutusData(fields.get(0)),
                fields.get(1),
                ScriptInfo.fromPlutusData(fields.get(2)));
    }
}
