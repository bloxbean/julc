package com.bloxbean.cardano.julc.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;

import java.util.List;

/**
 * A credential: either a public key hash or a script hash.
 */
public sealed interface Credential extends PlutusDataConvertible {

    record PubKeyCredential(PubKeyHash hash) implements Credential {
        @Override
        public PlutusData.ConstrData toPlutusData() {
            return new PlutusData.ConstrData(0, List.of(hash.toPlutusData()));
        }
    }

    record ScriptCredential(ScriptHash hash) implements Credential {
        @Override
        public PlutusData.ConstrData toPlutusData() {
            return new PlutusData.ConstrData(1, List.of(hash.toPlutusData()));
        }
    }

    static Credential fromPlutusData(PlutusData data) {
        var c = PlutusDataHelper.expectConstr(data);
        return switch (c.tag()) {
            case 0 -> new PubKeyCredential(PubKeyHash.fromPlutusData(c.fields().getFirst()));
            case 1 -> new ScriptCredential(ScriptHash.fromPlutusData(c.fields().getFirst()));
            default -> throw new IllegalArgumentException("Invalid Credential tag: " + c.tag());
        };
    }
}
