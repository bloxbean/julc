package com.bloxbean.cardano.plutus.ledger;

import com.bloxbean.cardano.plutus.core.PlutusData;

import java.math.BigInteger;
import java.util.List;

/**
 * A staking credential: either a hash-based credential or a pointer.
 */
public sealed interface StakingCredential extends PlutusDataConvertible {

    record StakingHash(Credential credential) implements StakingCredential {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(0, List.of(credential.toPlutusData()));
        }
    }

    record StakingPtr(BigInteger slot, BigInteger txIndex, BigInteger certIndex) implements StakingCredential {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(1, List.of(
                    new PlutusData.IntData(slot),
                    new PlutusData.IntData(txIndex),
                    new PlutusData.IntData(certIndex)));
        }
    }

    static StakingCredential fromPlutusData(PlutusData data) {
        var c = PlutusDataHelper.expectConstr(data);
        return switch (c.tag()) {
            case 0 -> new StakingHash(Credential.fromPlutusData(c.fields().getFirst()));
            case 1 -> new StakingPtr(
                    PlutusDataHelper.decodeInteger(c.fields().get(0)),
                    PlutusDataHelper.decodeInteger(c.fields().get(1)),
                    PlutusDataHelper.decodeInteger(c.fields().get(2)));
            default -> throw new IllegalArgumentException("Invalid StakingCredential tag: " + c.tag());
        };
    }
}
