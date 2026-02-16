package com.bloxbean.cardano.julc.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * A 32-byte transaction ID.
 */
public record TxId(byte[] hash) implements PlutusDataConvertible {

    public TxId {
        Objects.requireNonNull(hash, "hash must not be null");
        if (hash.length != 32) {
            throw new IllegalArgumentException("TxId must be 32 bytes, got: " + hash.length);
        }
        hash = hash.clone();
    }

    public byte[] hash() { return hash.clone(); }

    @Override
    public PlutusData.BytesData toPlutusData() {
        return new PlutusData.BytesData(hash);
    }

    public static TxId of(byte[] hash) { return new TxId(hash); }

    public static TxId fromPlutusData(PlutusData data) {
        return new TxId(PlutusDataHelper.decodeBytes(data));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TxId other && Arrays.equals(this.hash, other.hash);
    }

    @Override
    public int hashCode() { return Arrays.hashCode(hash); }

    @Override
    public String toString() { return "TxId[" + HexFormat.of().formatHex(hash) + "]"; }
}
