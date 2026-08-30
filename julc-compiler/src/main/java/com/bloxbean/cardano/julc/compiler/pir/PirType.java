package com.bloxbean.cardano.julc.compiler.pir;

import java.util.List;

/**
 * PIR (Plutus Intermediate Representation) type system.
 */
public sealed interface PirType {

    // Primitive types
    record IntegerType() implements PirType {}
    record ByteStringType() implements PirType {}
    record StringType() implements PirType {}
    record BoolType() implements PirType {}
    record UnitType() implements PirType {}
    record DataType() implements PirType {}
    /** Opaque PV11 native Value; never interchangeable with DataType. */
    record NativeValueType() implements PirType {}

    // Container types
    record ListType(PirType elemType) implements PirType {}
    record PairType(PirType first, PirType second) implements PirType {}
    record MapType(PirType keyType, PirType valueType) implements PirType {}
    record OptionalType(PirType elemType) implements PirType {}
    record ArrayType(PirType elemType) implements PirType {}

    // Function type
    record FunType(PirType paramType, PirType returnType) implements PirType {}

    // Algebraic data types
    record RecordType(String name, List<Field> fields) implements PirType {
        public RecordType { fields = List.copyOf(fields); }
    }
    record SumType(String name, List<Constructor> constructors) implements PirType {
        public SumType { constructors = List.copyOf(constructors); }
    }

    /** A nominal back-reference used inside a recursive named type definition. */
    record NamedTypeRef(String stableId, String name, NamedKind kind) implements PirType {}

    enum NamedKind { RECORD, SUM }

    // Nested helper types
    record Field(String name, PirType type) {}
    record Constructor(String name, int tag, List<Field> fields) {
        public Constructor { fields = List.copyOf(fields); }
    }

    static boolean isNativeOpaque(PirType type) {
        return type instanceof NativeValueType;
    }

    /**
     * Return whether this structural type contains a PV11 native Value.
     * Nominal recursive references require a {@code TypeResolver} and are
     * intentionally handled by its resolver-aware overload.
     */
    static boolean containsNativeOpaque(PirType type) {
        return switch (type) {
            case NativeValueType _ -> true;
            case ListType list -> containsNativeOpaque(list.elemType());
            case PairType pair -> containsNativeOpaque(pair.first())
                    || containsNativeOpaque(pair.second());
            case MapType map -> containsNativeOpaque(map.keyType())
                    || containsNativeOpaque(map.valueType());
            case OptionalType optional -> containsNativeOpaque(optional.elemType());
            case ArrayType array -> containsNativeOpaque(array.elemType());
            case FunType function -> containsNativeOpaque(function.paramType())
                    || containsNativeOpaque(function.returnType());
            case RecordType record -> record.fields().stream()
                    .anyMatch(field -> containsNativeOpaque(field.type()));
            case SumType sum -> sum.constructors().stream()
                    .flatMap(constructor -> constructor.fields().stream())
                    .anyMatch(field -> containsNativeOpaque(field.type()));
            default -> false;
        };
    }
}
