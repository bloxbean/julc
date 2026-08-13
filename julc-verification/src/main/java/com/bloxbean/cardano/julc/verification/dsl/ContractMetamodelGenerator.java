package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.compiler.schema.ContractSchema;

/** Generates deterministic Java accessors from compiler-owned contract types. */
public final class ContractMetamodelGenerator {
    private ContractMetamodelGenerator() { }

    public static String generate(
            ContractSchema schema, String packageName, String className) {
        if (!"spending".equals(schema.purpose()) || schema.datum() == null) {
            throw new IllegalArgumentException("DSL metamodel v1 requires a spending datum");
        }
        if (!packageName.matches("[a-z][A-Za-z0-9_.]*")
                || !className.matches("[A-Z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid generated metamodel name");
        }
        PirType type = resolve(schema.datum().type(), schema);
        if (!(type instanceof PirType.RecordType record)) {
            throw new IllegalArgumentException("DSL metamodel v1 requires a record datum");
        }
        var accessors = new StringBuilder();
        for (PirType.Field field : record.fields()) {
            if (!field.name().matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
                throw new IllegalArgumentException("Unsupported Java field name " + field.name());
            }
            PirType fieldType = resolve(field.type(), schema);
            String wrapper;
            String selector;
            if (fieldType instanceof PirType.ByteStringType) {
                wrapper = "ByteStringExpr";
                selector = "bytesField";
            } else if (fieldType instanceof PirType.IntegerType) {
                wrapper = "IntegerExpr";
                selector = "integerField";
            } else {
                throw new IllegalArgumentException("DSL metamodel v1 does not support field "
                        + field.name() + " of type " + fieldType);
            }
            accessors.append("        public ").append(wrapper).append(" ")
                    .append(field.name()).append("() { return value.")
                    .append(selector).append("(\"").append(field.name()).append("\"); }\n");
        }
        return """
                package %s;

                import com.bloxbean.cardano.julc.verification.dsl.*;

                /** Generated from compiler-owned ContractSchema; do not edit. */
                public final class %s {
                    private final SpendingContractModel value = new SpendingContractModel();
                    private final Datum datum = new Datum(value.datum());

                    public Datum datum() { return datum; }
                    public ContextExpr context() { return value.context(); }
                    public BoolExpr exactUplcSucceeds() { return value.exactUplcSucceeds(); }

                    public static final class Datum {
                        private final DatumExpr value;
                        private Datum(DatumExpr value) { this.value = value; }
                %s    }
                }
                """.formatted(packageName, className, accessors);
    }

    private static PirType resolve(PirType type, ContractSchema schema) {
        if (type instanceof PirType.NamedTypeRef ref) {
            PirType result = schema.namedDefinitions().get(ref.stableId());
            if (result == null) result = schema.namedDefinitions().get(ref.name());
            if (result == null) {
                throw new IllegalArgumentException("Unknown named type " + ref.name());
            }
            return result;
        }
        return type;
    }
}
