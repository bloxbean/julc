package com.bloxbean.cardano.julc.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;

import java.math.BigInteger;
import java.util.List;

/**
 * A governance proposal procedure.
 */
public record ProposalProcedure(BigInteger deposit, Credential returnAddress,
                                GovernanceAction governanceAction) implements PlutusDataConvertible {

    @Override
    public PlutusData.ConstrData toPlutusData() {
        return new PlutusData.ConstrData(0, List.of(
                new PlutusData.IntData(deposit),
                returnAddress.toPlutusData(),
                governanceAction.toPlutusData()));
    }

    public static ProposalProcedure fromPlutusData(PlutusData data) {
        var fields = PlutusDataHelper.expectConstr(data, 0);
        return new ProposalProcedure(
                PlutusDataHelper.decodeInteger(fields.get(0)),
                Credential.fromPlutusData(fields.get(1)),
                GovernanceAction.fromPlutusData(fields.get(2)));
    }
}
