package com.bloxbean.cardano.julc.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * A token name (0-32 bytes).
 */
public record TokenName(byte[] name) implements PlutusDataConvertible {

    /** Empty token name (used for ADA's token name). */
    public static final TokenName EMPTY = new TokenName(new byte[0]);

    public TokenName {
        Objects.requireNonNull(name, "name must not be null");
        if (name.length > 32) {
            throw new IllegalArgumentException("TokenName must be 0-32 bytes, got: " + name.length);
        }
        name = name.clone();
    }

    public byte[] name() { return name.clone(); }

    @Override
    public PlutusData.BytesData toPlutusData() {
        return new PlutusData.BytesData(name);
    }

    public static TokenName of(byte[] name) { return new TokenName(name); }

    public static TokenName fromPlutusData(PlutusData data) {
        return new TokenName(PlutusDataHelper.decodeBytes(data));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TokenName other && Arrays.equals(this.name, other.name);
    }

    @Override
    public int hashCode() { return Arrays.hashCode(name); }

    @Override
    public String toString() { return "TokenName[" + HexFormat.of().formatHex(name) + "]"; }
}
