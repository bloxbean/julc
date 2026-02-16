package com.bloxbean.cardano.julc.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * A 28-byte public key hash.
 */
public record PubKeyHash(byte[] hash) implements PlutusDataConvertible {

    public PubKeyHash {
        Objects.requireNonNull(hash, "hash must not be null");
        if (hash.length != 28) {
            throw new IllegalArgumentException("PubKeyHash must be 28 bytes, got: " + hash.length);
        }
        hash = hash.clone();
    }

    public byte[] hash() { return hash.clone(); }

    @Override
    public PlutusData.BytesData toPlutusData() {
        return new PlutusData.BytesData(hash);
    }

    public static PubKeyHash of(byte[] hash) { return new PubKeyHash(hash); }

    public static PubKeyHash fromPlutusData(PlutusData data) {
        return new PubKeyHash(PlutusDataHelper.decodeBytes(data));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PubKeyHash other && Arrays.equals(this.hash, other.hash);
    }

    @Override
    public int hashCode() { return Arrays.hashCode(hash); }

    @Override
    public String toString() { return "PubKeyHash[" + HexFormat.of().formatHex(hash) + "]"; }
}
