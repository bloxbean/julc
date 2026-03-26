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

    // Nested helper types
    record Field(String name, PirType type) {}
    record Constructor(String name, int tag, List<Field> fields) {
        public Constructor { fields = List.copyOf(fields); }
    }
}
