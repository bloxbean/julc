package com.bloxbean.cardano.julc.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Constants in UPLC — values of built-in types.
 * <p>
 * Each variant carries a value of the corresponding {@link DefaultUni} type.
 */
public sealed interface Constant {

    /** The DefaultUni type of this constant. */
    DefaultUni type();

    record IntegerConst(BigInteger value) implements Constant {
        public IntegerConst { Objects.requireNonNull(value); }
        public IntegerConst(long value) { this(BigInteger.valueOf(value)); }
        @Override public DefaultUni type() { return DefaultUni.INTEGER; }
    }

    record ByteStringConst(byte[] value) implements Constant {
        public ByteStringConst { Objects.requireNonNull(value); value = value.clone(); }
        public byte[] value() { return value.clone(); }
        @Override public DefaultUni type() { return DefaultUni.BYTESTRING; }
        @Override public boolean equals(Object o) {
            return o instanceof ByteStringConst other && Arrays.equals(this.value, other.value);
        }
        @Override public int hashCode() { return Arrays.hashCode(value); }
    }

    record StringConst(String value) implements Constant {
        public StringConst { Objects.requireNonNull(value); }
        @Override public DefaultUni type() { return DefaultUni.STRING; }
    }

    record UnitConst() implements Constant {
        @Override public DefaultUni type() { return DefaultUni.UNIT; }
    }

    record BoolConst(boolean value) implements Constant {
        @Override public DefaultUni type() { return DefaultUni.BOOL; }
    }

    record DataConst(PlutusData value) implements Constant {
        public DataConst { Objects.requireNonNull(value); }
        @Override public DefaultUni type() { return DefaultUni.DATA; }
    }

    record ListConst(DefaultUni elemType, List<Constant> values) implements Constant {
        public ListConst { Objects.requireNonNull(elemType); values = List.copyOf(values); }
        @Override public DefaultUni type() { return DefaultUni.listOf(elemType); }
    }

    record PairConst(Constant first, Constant second) implements Constant {
        public PairConst { Objects.requireNonNull(first); Objects.requireNonNull(second); }
        @Override public DefaultUni type() { return DefaultUni.pairOf(first.type(), second.type()); }
    }

    record Bls12_381_G1Element(byte[] value) implements Constant {
        public Bls12_381_G1Element { Objects.requireNonNull(value); value = value.clone(); }
        public byte[] value() { return value.clone(); }
        @Override public DefaultUni type() { return DefaultUni.BLS12_381_G1; }
        @Override public boolean equals(Object o) {
            return o instanceof Bls12_381_G1Element other && Arrays.equals(this.value, other.value);
        }
        @Override public int hashCode() { return Arrays.hashCode(value); }
    }

    record Bls12_381_G2Element(byte[] value) implements Constant {
        public Bls12_381_G2Element { Objects.requireNonNull(value); value = value.clone(); }
        public byte[] value() { return value.clone(); }
        @Override public DefaultUni type() { return DefaultUni.BLS12_381_G2; }
        @Override public boolean equals(Object o) {
            return o instanceof Bls12_381_G2Element other && Arrays.equals(this.value, other.value);
        }
        @Override public int hashCode() { return Arrays.hashCode(value); }
    }

    record Bls12_381_MlResult(byte[] value) implements Constant {
        public Bls12_381_MlResult { Objects.requireNonNull(value); value = value.clone(); }
        public byte[] value() { return value.clone(); }
        @Override public DefaultUni type() { return DefaultUni.BLS12_381_ML; }
        @Override public boolean equals(Object o) {
            return o instanceof Bls12_381_MlResult other && Arrays.equals(this.value, other.value);
        }
        @Override public int hashCode() { return Arrays.hashCode(value); }
    }

    record ArrayConst(DefaultUni elemType, List<Constant> values) implements Constant {
        public ArrayConst { Objects.requireNonNull(elemType); values = List.copyOf(values); }
        @Override public DefaultUni type() { return DefaultUni.arrayOf(elemType); }
    }

    record ValueConst(List<ValueEntry> entries) implements Constant {
        public ValueConst { Objects.requireNonNull(entries); entries = List.copyOf(entries); }
        @Override public DefaultUni type() { return new DefaultUni.ProtoValue(); }

        /** A single policy entry in a Value: policyId -> list of (tokenName, quantity). */
        public record ValueEntry(byte[] policyId, List<TokenEntry> tokens) {
            public ValueEntry {
                Objects.requireNonNull(policyId); policyId = policyId.clone();
                Objects.requireNonNull(tokens); tokens = List.copyOf(tokens);
            }
            public byte[] policyId() { return policyId.clone(); }
            @Override public boolean equals(Object o) {
                return o instanceof ValueEntry other
                        && Arrays.equals(this.policyId, other.policyId)
                        && this.tokens.equals(other.tokens);
            }
            @Override public int hashCode() {
                return 31 * Arrays.hashCode(policyId) + tokens.hashCode();
            }
        }

        /** A single token entry: tokenName -> quantity. */
        public record TokenEntry(byte[] tokenName, BigInteger quantity) {
            public TokenEntry {
                Objects.requireNonNull(tokenName); tokenName = tokenName.clone();
                Objects.requireNonNull(quantity);
            }
            public byte[] tokenName() { return tokenName.clone(); }
            @Override public boolean equals(Object o) {
                return o instanceof TokenEntry other
                        && Arrays.equals(this.tokenName, other.tokenName)
                        && this.quantity.equals(other.quantity);
            }
            @Override public int hashCode() {
                return 31 * Arrays.hashCode(tokenName) + quantity.hashCode();
            }
        }

        /**
         * Normalize a Value: sort entries by policyId, then by tokenName within each policy,
         * merge duplicate tokens (add quantities), remove zero-quantity tokens,
         * and remove policies with no remaining tokens.
         */
        public static ValueConst normalize(List<ValueEntry> rawEntries) {
            // Group by policyId
            var policyMap = new java.util.TreeMap<ByteArrayKey, java.util.TreeMap<ByteArrayKey, BigInteger>>(ByteArrayKey.COMPARATOR);
            for (var entry : rawEntries) {
                var key = new ByteArrayKey(entry.policyId);
                var tokenMap = policyMap.computeIfAbsent(key, k -> new java.util.TreeMap<>(ByteArrayKey.COMPARATOR));
                for (var token : entry.tokens) {
                    var tKey = new ByteArrayKey(token.tokenName);
                    tokenMap.merge(tKey, token.quantity, BigInteger::add);
                }
            }
            // Build result, dropping zero-quantity tokens and empty policies
            var result = new ArrayList<ValueEntry>();
            for (var pEntry : policyMap.entrySet()) {
                var tokens = new ArrayList<TokenEntry>();
                for (var tEntry : pEntry.getValue().entrySet()) {
                    if (tEntry.getValue().signum() != 0) {
                        tokens.add(new TokenEntry(tEntry.getKey().bytes, tEntry.getValue()));
                    }
                }
                if (!tokens.isEmpty()) {
                    result.add(new ValueEntry(pEntry.getKey().bytes, tokens));
                }
            }
            return new ValueConst(result);
        }
    }

    /** Wrapper for byte[] that supports comparison and hashing. */
    record ByteArrayKey(byte[] bytes) implements Comparable<ByteArrayKey> {
        public static final java.util.Comparator<ByteArrayKey> COMPARATOR = ByteArrayKey::compareTo;
        @Override public int compareTo(ByteArrayKey other) {
            return Arrays.compareUnsigned(this.bytes, other.bytes);
        }
        @Override public boolean equals(Object o) {
            return o instanceof ByteArrayKey other && Arrays.equals(this.bytes, other.bytes);
        }
        @Override public int hashCode() { return Arrays.hashCode(bytes); }
    }

    // Convenience factory methods
    static Constant integer(long value) { return new IntegerConst(value); }
    static Constant integer(BigInteger value) { return new IntegerConst(value); }
    static Constant byteString(byte[] value) { return new ByteStringConst(value); }
    static Constant string(String value) { return new StringConst(value); }
    static Constant unit() { return new UnitConst(); }
    static Constant bool(boolean value) { return new BoolConst(value); }
    static Constant data(PlutusData value) { return new DataConst(value); }
    static Constant array(DefaultUni elemType, List<Constant> values) { return new ArrayConst(elemType, values); }
}
