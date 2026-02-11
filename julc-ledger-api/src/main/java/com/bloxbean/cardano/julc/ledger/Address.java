package com.bloxbean.cardano.julc.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;

import java.util.List;
import java.util.Optional;

/**
 * A Cardano address: a payment credential and optional staking credential.
 */
public record Address(Credential credential, Optional<StakingCredential> stakingCredential)
        implements PlutusDataConvertible {

    @Override
    public PlutusData toPlutusData() {
        return new PlutusData.Constr(0, List.of(
                credential.toPlutusData(),
                PlutusDataHelper.encodeOptional(stakingCredential, StakingCredential::toPlutusData)));
    }

    public static Address fromPlutusData(PlutusData data) {
        var fields = PlutusDataHelper.expectConstr(data, 0);
        return new Address(
                Credential.fromPlutusData(fields.get(0)),
                PlutusDataHelper.decodeOptional(fields.get(1), StakingCredential::fromPlutusData));
    }
}
