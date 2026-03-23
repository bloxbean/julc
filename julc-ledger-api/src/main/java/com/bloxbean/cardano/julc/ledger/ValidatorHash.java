package com.bloxbean.cardano.julc.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * A validator hash (typically 28 bytes, semantically an alias for ScriptHash).
 * No byte-length validation — length enforcement is a ledger rule, not a type invariant.
 */
public record ValidatorHash(byte[] hash) implements PlutusDataConvertible {

    public ValidatorHash {
        Objects.requireNonNull(hash, "hash must not be null");
        hash = hash.clone();
    }

    public byte[] hash() { return hash.clone(); }

    @Override
    public PlutusData.BytesData toPlutusData() {
        return new PlutusData.BytesData(hash);
    }

    public static ValidatorHash of(byte[] hash) { return new ValidatorHash(hash); }

    public static ValidatorHash fromPlutusData(PlutusData data) {
        return new ValidatorHash(PlutusDataHelper.decodeBytes(data));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ValidatorHash other && Arrays.equals(this.hash, other.hash);
    }

    @Override
    public int hashCode() { return Arrays.hashCode(hash); }

    @Override
    public String toString() { return "ValidatorHash[" + HexFormat.of().formatHex(hash) + "]"; }
}
