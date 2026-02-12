package com.bloxbean.cardano.julc.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;

import java.util.List;

/**
 * A delegated representative (DRep).
 */
public sealed interface DRep extends PlutusDataConvertible {

    record DRepCredential(Credential credential) implements DRep {
        @Override
        public PlutusData.ConstrData toPlutusData() {
            return new PlutusData.ConstrData(0, List.of(credential.toPlutusData()));
        }
    }

    record AlwaysAbstain() implements DRep {
        @Override
        public PlutusData.ConstrData toPlutusData() { return new PlutusData.ConstrData(1, List.of()); }
    }

    record AlwaysNoConfidence() implements DRep {
        @Override
        public PlutusData.ConstrData toPlutusData() { return new PlutusData.ConstrData(2, List.of()); }
    }

    static DRep fromPlutusData(PlutusData data) {
        var c = PlutusDataHelper.expectConstr(data);
        return switch (c.tag()) {
            case 0 -> new DRepCredential(Credential.fromPlutusData(c.fields().getFirst()));
            case 1 -> new AlwaysAbstain();
            case 2 -> new AlwaysNoConfidence();
            default -> throw new IllegalArgumentException("Invalid DRep tag: " + c.tag());
        };
    }
}
