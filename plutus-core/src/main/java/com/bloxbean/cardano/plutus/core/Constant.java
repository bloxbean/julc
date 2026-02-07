package com.bloxbean.cardano.plutus.core;

import java.math.BigInteger;
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

    // Convenience factory methods
    static Constant integer(long value) { return new IntegerConst(value); }
    static Constant integer(BigInteger value) { return new IntegerConst(value); }
    static Constant byteString(byte[] value) { return new ByteStringConst(value); }
    static Constant string(String value) { return new StringConst(value); }
    static Constant unit() { return new UnitConst(); }
    static Constant bool(boolean value) { return new BoolConst(value); }
    static Constant data(PlutusData value) { return new DataConst(value); }
}
