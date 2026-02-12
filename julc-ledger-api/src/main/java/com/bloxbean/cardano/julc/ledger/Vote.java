package com.bloxbean.cardano.julc.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;

import java.util.List;

/**
 * A governance vote.
 */
public sealed interface Vote extends PlutusDataConvertible {

    record VoteNo() implements Vote {
        @Override
        public PlutusData.ConstrData toPlutusData() { return new PlutusData.ConstrData(0, List.of()); }
    }

    record VoteYes() implements Vote {
        @Override
        public PlutusData.ConstrData toPlutusData() { return new PlutusData.ConstrData(1, List.of()); }
    }

    record Abstain() implements Vote {
        @Override
        public PlutusData.ConstrData toPlutusData() { return new PlutusData.ConstrData(2, List.of()); }
    }

    static Vote fromPlutusData(PlutusData data) {
        var c = PlutusDataHelper.expectConstr(data);
        return switch (c.tag()) {
            case 0 -> new VoteNo();
            case 1 -> new VoteYes();
            case 2 -> new Abstain();
            default -> throw new IllegalArgumentException("Invalid Vote tag: " + c.tag());
        };
    }
}
