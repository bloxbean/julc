package com.bloxbean.cardano.julc.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * A datum hash (typically 32 bytes).
 * No byte-length validation — length enforcement is a ledger rule, not a type invariant.
 */
public record DatumHash(byte[] hash) implements PlutusDataConvertible {

    public DatumHash {
        Objects.requireNonNull(hash, "hash must not be null");
        hash = hash.clone();
    }

    public byte[] hash() { return hash.clone(); }

    @Override
    public PlutusData.BytesData toPlutusData() {
        return new PlutusData.BytesData(hash);
    }

    public static DatumHash of(byte[] hash) { return new DatumHash(hash); }

    public static DatumHash fromPlutusData(PlutusData data) {
        return new DatumHash(PlutusDataHelper.decodeBytes(data));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof DatumHash other && Arrays.equals(this.hash, other.hash);
    }

    @Override
    public int hashCode() { return Arrays.hashCode(hash); }

    @Override
    public String toString() { return "DatumHash[" + HexFormat.of().formatHex(hash) + "]"; }
}
