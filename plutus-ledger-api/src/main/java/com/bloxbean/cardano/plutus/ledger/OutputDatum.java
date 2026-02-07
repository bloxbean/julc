package com.bloxbean.cardano.plutus.ledger;

import com.bloxbean.cardano.plutus.core.PlutusData;

import java.util.List;

/**
 * Datum attached to a transaction output.
 */
public sealed interface OutputDatum extends PlutusDataConvertible {

    record NoOutputDatum() implements OutputDatum {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(0, List.of());
        }
    }

    record OutputDatumHash(DatumHash datumHash) implements OutputDatum {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(1, List.of(datumHash.toPlutusData()));
        }
    }

    record OutputDatumInline(PlutusData datum) implements OutputDatum {
        @Override
        public PlutusData toPlutusData() {
            return new PlutusData.Constr(2, List.of(datum));
        }
    }

    static OutputDatum fromPlutusData(PlutusData data) {
        var c = PlutusDataHelper.expectConstr(data);
        return switch (c.tag()) {
            case 0 -> new NoOutputDatum();
            case 1 -> new OutputDatumHash(DatumHash.fromPlutusData(c.fields().getFirst()));
            case 2 -> new OutputDatumInline(c.fields().getFirst());
            default -> throw new IllegalArgumentException("Invalid OutputDatum tag: " + c.tag());
        };
    }
}
