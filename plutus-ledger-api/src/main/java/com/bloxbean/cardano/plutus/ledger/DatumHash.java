package com.bloxbean.cardano.plutus.ledger;

import com.bloxbean.cardano.plutus.core.PlutusData;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * A 32-byte datum hash.
 */
public record DatumHash(byte[] hash) implements PlutusDataConvertible {

    public DatumHash {
        Objects.requireNonNull(hash, "hash must not be null");
        if (hash.length != 32) {
            throw new IllegalArgumentException("DatumHash must be 32 bytes, got: " + hash.length);
        }
        hash = hash.clone();
    }

    public byte[] hash() { return hash.clone(); }

    @Override
    public PlutusData toPlutusData() {
        return new PlutusData.BytesData(hash);
    }

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
