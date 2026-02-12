package com.bloxbean.cardano.julc.core;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Represents the Plutus Data type — the universal serializable representation
 * used for script datums, redeemers, and encoding arbitrary data structures on Cardano.
 * <p>
 * PlutusData has exactly 5 constructors matching the Cardano specification:
 * <ul>
 *   <li>{@link ConstrData} — constructor application (tag + fields)</li>
 *   <li>{@link MapData} — association list of key-value pairs</li>
 *   <li>{@link ListData} — list of data values</li>
 *   <li>{@link IntData} — arbitrary-precision integer</li>
 *   <li>{@link BytesData} — byte string</li>
 * </ul>
 */
public sealed interface PlutusData {

    /**
     * A constructor application with a tag and list of fields.
     * Used to encode sum types (tagged unions) and product types.
     */
    record ConstrData(int tag, List<PlutusData> fields) implements PlutusData {
        public ConstrData {
            if (tag < 0) throw new IllegalArgumentException("ConstrData tag must be non-negative: " + tag);
            fields = List.copyOf(fields);
        }
    }

    /**
     * An association list of key-value pairs.
     */
    record MapData(List<Pair> entries) implements PlutusData {
        public MapData {
            entries = List.copyOf(entries);
        }
    }

    /**
     * A list of PlutusData values.
     */
    record ListData(List<PlutusData> items) implements PlutusData {
        public ListData {
            items = List.copyOf(items);
        }
    }

    /**
     * An arbitrary-precision integer.
     */
    record IntData(BigInteger value) implements PlutusData {
        public IntData {
            Objects.requireNonNull(value, "IntData value must not be null");
        }

        public IntData(long value) {
            this(BigInteger.valueOf(value));
        }
    }

    /**
     * A byte string.
     */
    record BytesData(byte[] value) implements PlutusData {
        public BytesData {
            Objects.requireNonNull(value, "BytesData value must not be null");
            value = value.clone();
        }

        public byte[] value() {
            return value.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof BytesData other && Arrays.equals(this.value, other.value);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(value);
        }

        @Override
        public String toString() {
            return "BytesData[" + HexFormat.of().formatHex(value) + "]";
        }
    }

    /**
     * A key-value pair used in {@link MapData}.
     */
    record Pair(PlutusData key, PlutusData value) {
        public Pair { Objects.requireNonNull(key); Objects.requireNonNull(value); }
    }

    // Convenience factory methods

    static PlutusData constr(int tag, PlutusData... fields) {
        return new ConstrData(tag, List.of(fields));
    }

    static PlutusData integer(long value) {
        return new IntData(value);
    }

    static PlutusData integer(BigInteger value) {
        return new IntData(value);
    }

    static PlutusData bytes(byte[] value) {
        return new BytesData(value);
    }

    static PlutusData list(PlutusData... items) {
        return new ListData(List.of(items));
    }

    static PlutusData map(Pair... entries) {
        return new MapData(List.of(entries));
    }

    /** The unit value: Constr 0 [] */
    PlutusData UNIT = new ConstrData(0, List.of());
}
