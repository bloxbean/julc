package com.bloxbean.cardano.julc.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * A 28-byte validator hash (semantically an alias for ScriptHash).
 */
public record ValidatorHash(byte[] hash) implements PlutusDataConvertible {

    public ValidatorHash {
        Objects.requireNonNull(hash, "hash must not be null");
        if (hash.length != 28) {
            throw new IllegalArgumentException("ValidatorHash must be 28 bytes, got: " + hash.length);
        }
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
