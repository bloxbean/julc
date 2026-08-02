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
    final class ConstrData implements PlutusData {
        private final BigInteger constructorTag;
        private final List<PlutusData> fields;

        /**
         * Compatibility constructor for ordinary schema tags. Negative int
         * tags remain rejected as they were by the original record API.
         */
        public ConstrData(int tag, List<PlutusData> fields) {
            if (tag < 0) {
                throw new IllegalArgumentException(
                        "ConstrData tag must be non-negative: " + tag);
            }
            this.constructorTag = BigInteger.valueOf(tag);
            this.fields = List.copyOf(fields);
        }

        /** Construct the underlying Plutus {@code Data.Constr Integer [Data]}. */
        public ConstrData(BigInteger tag, List<PlutusData> fields) {
            this.constructorTag = Objects.requireNonNull(
                    tag, "ConstrData tag must not be null");
            this.fields = List.copyOf(fields);
        }

        /** Construct from the complete unsigned Word64 domain used by D/E. */
        public static ConstrData fromUnsignedTag(
                BigInteger tag, List<PlutusData> fields) {
            Objects.requireNonNull(tag, "ConstrData tag must not be null");
            if (tag.signum() < 0 || tag.bitLength() > 64) {
                throw new IllegalArgumentException(
                        "ConstrData tag must fit in unsigned Word64: " + tag);
            }
            return new ConstrData(tag, fields);
        }

        /**
         * Checked compatibility view for callers whose schemas use int tags.
         * Use {@link #constructorTag()} for the complete Plutus value.
         */
        public int tag() {
            return constructorTag.intValueExact();
        }

        public BigInteger constructorTag() {
            return constructorTag;
        }

        public List<PlutusData> fields() {
            return fields;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ConstrData other
                    && constructorTag.equals(other.constructorTag)
                    && fields.equals(other.fields);
        }

        @Override
        public int hashCode() {
            // Match the former record's generated component hash formula so
            // existing int-tag values remain stable as hash-map keys.
            return 31 * constructorTag.hashCode() + fields.hashCode();
        }

        @Override
        public String toString() {
            return "ConstrData[tag=" + constructorTag + ", fields=" + fields + "]";
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

    /**
     * Cast an untyped value to a target type. On-chain this compiles to identity
     * (zero cost), replacing the ugly {@code (TargetType)(Object) data} pattern.
     *
     * @param data the value to cast (typically PlutusData, JulcList, or JulcMap)
     * @param type the target type class
     * @param <T>  the target type
     * @return the value cast to T
     */
    @SuppressWarnings("unchecked")
    static <T> T cast(Object data, Class<T> type) {
        return (T) data;
    }

    /** The unit value: Constr 0 [] */
    PlutusData UNIT = new ConstrData(0, List.of());

    /**
     * Count the total number of nodes in this PlutusData tree.
     * Useful for comparing structural sizes of two PlutusData trees.
     */
    default int countNodes() {
        return switch (this) {
            case ConstrData constr -> {
                int count = 1;
                for (PlutusData f : constr.fields()) count += f.countNodes();
                yield count;
            }
            case MapData(var entries) -> {
                int count = 1;
                for (Pair p : entries) count += p.key().countNodes() + p.value().countNodes();
                yield count;
            }
            case ListData(var items) -> {
                int count = 1;
                for (PlutusData item : items) count += item.countNodes();
                yield count;
            }
            case IntData _, BytesData _ -> 1;
        };
    }

    /**
     * Pretty-print this PlutusData tree with indentation for readability.
     */
    default String prettyPrint() {
        var sb = new StringBuilder();
        prettyPrint(sb, 0);
        return sb.toString();
    }

    private void prettyPrint(StringBuilder sb, int indent) {
        String pad = "  ".repeat(indent);
        switch (this) {
            case IntData(var v) -> sb.append(pad).append("I ").append(v);
            case BytesData bd -> sb.append(pad).append("B #").append(HexFormat.of().formatHex(bd.value));
            case ConstrData constr -> {
                sb.append(pad).append("Constr ").append(constr.constructorTag());
                if (constr.fields().isEmpty()) {
                    sb.append(" []");
                } else {
                    sb.append(" [\n");
                    for (int i = 0; i < constr.fields().size(); i++) {
                        constr.fields().get(i).prettyPrint(sb, indent + 1);
                        if (i < constr.fields().size() - 1) sb.append(",");
                        sb.append("\n");
                    }
                    sb.append(pad).append("]");
                }
            }
            case ListData(var items) -> {
                sb.append(pad).append("List");
                if (items.isEmpty()) {
                    sb.append(" []");
                } else {
                    sb.append(" [\n");
                    for (int i = 0; i < items.size(); i++) {
                        items.get(i).prettyPrint(sb, indent + 1);
                        if (i < items.size() - 1) sb.append(",");
                        sb.append("\n");
                    }
                    sb.append(pad).append("]");
                }
            }
            case MapData(var entries) -> {
                sb.append(pad).append("Map");
                if (entries.isEmpty()) {
                    sb.append(" {}");
                } else {
                    sb.append(" {\n");
                    for (int i = 0; i < entries.size(); i++) {
                        var p = entries.get(i);
                        p.key().prettyPrint(sb, indent + 1);
                        sb.append(" =>\n");
                        p.value().prettyPrint(sb, indent + 2);
                        if (i < entries.size() - 1) sb.append(",");
                        sb.append("\n");
                    }
                    sb.append(pad).append("}");
                }
            }
        }
    }
}
