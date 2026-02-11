package com.bloxbean.cardano.julc.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;

import java.util.List;

/**
 * A governance voter.
 */
public sealed interface Voter extends PlutusDataConvertible {

    record CommitteeVoter(Credential credential) implements Voter {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(0, List.of(credential.toPlutusData()));
        }
    }

    record DRepVoter(Credential credential) implements Voter {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(1, List.of(credential.toPlutusData()));
        }
    }

    record StakePoolVoter(PubKeyHash pubKeyHash) implements Voter {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(2, List.of(pubKeyHash.toPlutusData()));
        }
    }

    static Voter fromPlutusData(PlutusData data) {
        var c = PlutusDataHelper.expectConstr(data);
        return switch (c.tag()) {
            case 0 -> new CommitteeVoter(Credential.fromPlutusData(c.fields().getFirst()));
            case 1 -> new DRepVoter(Credential.fromPlutusData(c.fields().getFirst()));
            case 2 -> new StakePoolVoter(PubKeyHash.fromPlutusData(c.fields().getFirst()));
            default -> throw new IllegalArgumentException("Invalid Voter tag: " + c.tag());
        };
    }
}
