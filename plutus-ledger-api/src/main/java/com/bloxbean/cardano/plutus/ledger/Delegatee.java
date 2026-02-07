package com.bloxbean.cardano.plutus.ledger;

import com.bloxbean.cardano.plutus.core.PlutusData;

import java.util.List;

/**
 * A delegation target.
 */
public sealed interface Delegatee extends PlutusDataConvertible {

    record Stake(PubKeyHash poolId) implements Delegatee {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(0, List.of(poolId.toPlutusData()));
        }
    }

    record Vote(DRep dRep) implements Delegatee {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(1, List.of(dRep.toPlutusData()));
        }
    }

    record StakeVote(PubKeyHash poolId, DRep dRep) implements Delegatee {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(2, List.of(poolId.toPlutusData(), dRep.toPlutusData()));
        }
    }

    static Delegatee fromPlutusData(PlutusData data) {
        var c = PlutusDataHelper.expectConstr(data);
        return switch (c.tag()) {
            case 0 -> new Stake(PubKeyHash.fromPlutusData(c.fields().getFirst()));
            case 1 -> new Vote(DRep.fromPlutusData(c.fields().getFirst()));
            case 2 -> new StakeVote(
                    PubKeyHash.fromPlutusData(c.fields().get(0)),
                    DRep.fromPlutusData(c.fields().get(1)));
            default -> throw new IllegalArgumentException("Invalid Delegatee tag: " + c.tag());
        };
    }
}
