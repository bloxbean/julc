package com.bloxbean.cardano.julc.decompiler.hir;

import java.util.List;

/**
 * Decompiler type system, mirroring PirType from the compiler.
 * Represents inferred types for decompiled expressions.
 */
public sealed interface HirType {

    // Primitive types
    record IntegerType() implements HirType {
        @Override public String toString() { return "BigInteger"; }
    }
    record ByteStringType() implements HirType {
        @Override public String toString() { return "byte[]"; }
    }
    record StringType() implements HirType {
        @Override public String toString() { return "String"; }
    }
    record BoolType() implements HirType {
        @Override public String toString() { return "boolean"; }
    }
    record UnitType() implements HirType {
        @Override public String toString() { return "void"; }
    }
    record DataType() implements HirType {
        @Override public String toString() { return "PlutusData"; }
    }

    // Container types
    record ListType(HirType elemType) implements HirType {
        @Override public String toString() { return "JulcList<" + elemType + ">"; }
    }
    record PairType(HirType first, HirType second) implements HirType {
        @Override public String toString() { return "Pair<" + first + ", " + second + ">"; }
    }
    record MapType(HirType keyType, HirType valueType) implements HirType {
        @Override public String toString() { return "JulcMap<" + keyType + ", " + valueType + ">"; }
    }

    // Function type
    record FunType(List<HirType> paramTypes, HirType returnType) implements HirType {
        public FunType { paramTypes = List.copyOf(paramTypes); }
        @Override public String toString() {
            return "(" + String.join(", ", paramTypes.stream().map(Object::toString).toList()) + ") -> " + returnType;
        }
    }

    // Named record type (e.g., TxInfo, Address)
    record RecordType(String name, List<Field> fields) implements HirType {
        public RecordType { fields = List.copyOf(fields); }
        @Override public String toString() { return name; }
    }

    // Sum type (e.g., Credential, OutputDatum)
    record SumType(String name, List<Constructor> constructors) implements HirType {
        public SumType { constructors = List.copyOf(constructors); }
        @Override public String toString() { return name; }
    }

    // Unknown / not yet inferred
    record UnknownType() implements HirType {
        @Override public String toString() { return "Object"; }
    }

    // Helper records
    record Field(String name, HirType type) {}
    record Constructor(String name, int tag, List<Field> fields) {
        public Constructor { fields = List.copyOf(fields); }
    }

    // Singleton constants
    HirType INTEGER = new IntegerType();
    HirType BYTE_STRING = new ByteStringType();
    HirType STRING = new StringType();
    HirType BOOL = new BoolType();
    HirType UNIT = new UnitType();
    HirType DATA = new DataType();
    HirType UNKNOWN = new UnknownType();
}
