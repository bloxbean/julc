package com.bloxbean.cardano.julc.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * A minting policy ID (typically 28 bytes, or 0 bytes for ADA).
 * No byte-length validation — length enforcement is a ledger rule, not a type invariant.
 */
public record PolicyId(byte[] hash) implements PlutusDataConvertible {

    /** The ADA policy (empty byte string). */
    public static final PolicyId ADA = new PolicyId(new byte[0]);

    public PolicyId {
        Objects.requireNonNull(hash, "hash must not be null");
        hash = hash.clone();
    }

    public byte[] hash() { return hash.clone(); }

    @Override
    public PlutusData.BytesData toPlutusData() {
        return new PlutusData.BytesData(hash);
    }

    public static PolicyId of(byte[] hash) { return new PolicyId(hash); }

    public static PolicyId fromPlutusData(PlutusData data) {
        return new PolicyId(PlutusDataHelper.decodeBytes(data));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PolicyId other && Arrays.equals(this.hash, other.hash);
    }

    @Override
    public int hashCode() { return Arrays.hashCode(hash); }

    @Override
    public String toString() { return "PolicyId[" + HexFormat.of().formatHex(hash) + "]"; }
}
