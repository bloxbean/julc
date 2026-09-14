package org.julclang.compiler.pir;

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
    /** Opaque BLS12-381 G1 point ({@code bls12_381_G1_element}); never a byte string (ADR-047). */
    record NativeG1Type() implements PirType {}
    /** Opaque BLS12-381 G2 point ({@code bls12_381_G2_element}); never a byte string (ADR-047). */
    record NativeG2Type() implements PirType {}
    /** Opaque BLS12-381 Miller-loop result ({@code bls12_381_mlresult}) (ADR-047). */
    record NativeMlResultType() implements PirType {}
    /**
     * A native UPLC list whose elements are constants of {@code elemType}'s universe, not Data
     * (ADR-047): {@code list integer} for scalars, {@code list bls12_381_G1_element} or
     * {@code list bls12_381_G2_element} for points. Never interchangeable with {@link ListType}.
     */
    record NativeListType(PirType elemType) implements PirType {}

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
        return type instanceof NativeValueType || type instanceof NativeG1Type || type instanceof NativeG2Type
                || type instanceof NativeMlResultType || type instanceof NativeListType;
    }

    /**
     * Return whether this structural type contains a native opaque type (a PV11 Value, a BLS
     * point, a Miller result or a native list).
     * Nominal recursive references require a {@code TypeResolver} and are
     * intentionally handled by its resolver-aware overload.
     */
    static boolean containsNativeOpaque(PirType type) {
        return switch (type) {
            case NativeValueType _, NativeG1Type _, NativeG2Type _, NativeMlResultType _, NativeListType _ -> true;
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
