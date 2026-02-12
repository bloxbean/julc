package com.bloxbean.cardano.julc.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;

import java.math.BigInteger;
import java.util.List;

/**
 * A governance action identifier.
 */
public record GovernanceActionId(TxId txId, BigInteger govActionIx) implements PlutusDataConvertible {

    @Override
    public PlutusData.ConstrData toPlutusData() {
        return new PlutusData.ConstrData(0, List.of(
                txId.toPlutusData(),
                new PlutusData.IntData(govActionIx)));
    }

    public static GovernanceActionId fromPlutusData(PlutusData data) {
        var fields = PlutusDataHelper.expectConstr(data, 0);
        return new GovernanceActionId(
                TxId.fromPlutusData(fields.get(0)),
                PlutusDataHelper.decodeInteger(fields.get(1)));
    }
}
