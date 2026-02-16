package com.bloxbean.cardano.julc.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * A 28-byte script hash.
 */
public record ScriptHash(byte[] hash) implements PlutusDataConvertible {

    public ScriptHash {
        Objects.requireNonNull(hash, "hash must not be null");
        if (hash.length != 28) {
            throw new IllegalArgumentException("ScriptHash must be 28 bytes, got: " + hash.length);
        }
        hash = hash.clone();
    }

    public byte[] hash() { return hash.clone(); }

    @Override
    public PlutusData.BytesData toPlutusData() {
        return new PlutusData.BytesData(hash);
    }

    public static ScriptHash of(byte[] hash) { return new ScriptHash(hash); }

    public static ScriptHash fromPlutusData(PlutusData data) {
        return new ScriptHash(PlutusDataHelper.decodeBytes(data));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ScriptHash other && Arrays.equals(this.hash, other.hash);
    }

    @Override
    public int hashCode() { return Arrays.hashCode(hash); }

    @Override
    public String toString() { return "ScriptHash[" + HexFormat.of().formatHex(hash) + "]"; }
}
