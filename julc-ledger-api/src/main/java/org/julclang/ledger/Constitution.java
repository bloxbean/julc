package org.julclang.ledger;

import org.julclang.core.PlutusData;

import java.util.List;
import java.util.Optional;

/**
 * A constitution: the optional guardrails script hash. The anchor is omitted, as in plutus-ledger-api V3.
 * Encoded as {@code Constr 0 [Maybe ScriptHash]}.
 */
public record Constitution(Optional<ScriptHash> script) implements PlutusDataConvertible {
    @Override
    public PlutusData.ConstrData toPlutusData() {
        return new PlutusData.ConstrData(0, List.of(
                PlutusDataHelper.encodeOptional(script, ScriptHash::toPlutusData)));
    }

    public static Constitution fromPlutusData(PlutusData data) {
        var fields = PlutusDataHelper.expectConstr(data, 0);
        return new Constitution(PlutusDataHelper.decodeOptional(fields.get(0), ScriptHash::fromPlutusData));
    }
}
