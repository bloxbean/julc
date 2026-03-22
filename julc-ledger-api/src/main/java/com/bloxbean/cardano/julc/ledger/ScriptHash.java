package com.bloxbean.cardano.julc.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * A script hash (typically 28 bytes).
 * No byte-length validation — length enforcement is a ledger rule, not a type invariant.
 */
public record ScriptHash(byte[] hash) implements PlutusDataConvertible {

    public ScriptHash {
        Objects.requireNonNull(hash, "hash must not be null");
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
