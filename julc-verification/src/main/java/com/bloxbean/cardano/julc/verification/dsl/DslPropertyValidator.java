package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.compiler.schema.ContractSchema;
import com.bloxbean.cardano.julc.verification.dsl.ir.*;

import java.util.HashMap;
import java.util.Map;

/** Authoritative post-worker validation against compiler-owned contract types. */
public final class DslPropertyValidator {
    private DslPropertyValidator() { }

    public static void validate(
            DslPropertySet propertySet, ContractSchema schema, int maxNodes) {
        if (schema.purpose() != ContractSchema.Purpose.SPEND) {
            throw new IllegalArgumentException("DSL v1 supports only spending contracts");
        }
        int[] nodes = {0};
        for (DslProperty property : propertySet.properties()) {
            DslType type = validateNode(property.expression(), schema, new HashMap<>(), nodes,
                    maxNodes);
            if (type != DslType.BOOL) {
                throw new IllegalArgumentException("Property " + property.id()
                        + " must have BOOL type, found " + type);
            }
        }
    }

    private static DslType validateNode(
            PropertyNode node,
            ContractSchema schema,
            Map<String, DslType> variables,
            int[] count,
            int maxNodes) {
        if (++count[0] > maxNodes) {
            throw new IllegalArgumentException("Property AST exceeds " + maxNodes + " nodes");
        }
        if (node instanceof RootNode root) {
            DslType expected = switch (root.name()) {
                case "datum" -> DslType.DATA;
                case "context" -> DslType.SCRIPT_CONTEXT;
                case "exactUplcSucceeds" -> DslType.BOOL;
                case "validSpendingContext" -> DslType.BOOL;
                default -> variables.get(root.name());
            };
            if (expected == null || expected != root.resultType()) {
                throw new IllegalArgumentException("Unknown or mistyped symbolic root "
                        + root.name());
            }
            return expected;
        }
        if (node instanceof FieldNode field) {
            DslType target = validateNode(field.target(), schema, variables, count, maxNodes);
            DslType expected = fieldType(target, field.name(), schema);
            if (expected != field.resultType()) {
                throw new IllegalArgumentException("Field " + field.name() + " on " + target
                        + " has type " + expected + ", not " + field.resultType());
            }
            return expected;
        }
        if (node instanceof BoolBinaryNode binary) {
            require(DslType.BOOL, validateNode(binary.left(), schema, variables, count, maxNodes));
            require(DslType.BOOL, validateNode(binary.right(), schema, variables, count, maxNodes));
            return DslType.BOOL;
        }
        if (node instanceof ContainsNode contains) {
            DslType collection = validateNode(
                    contains.collection(), schema, variables, count, maxNodes);
            DslType value = validateNode(contains.value(), schema, variables, count, maxNodes);
            if (collection != DslType.LIST_BYTE_STRING || value != DslType.BYTE_STRING) {
                throw new IllegalArgumentException("contains requires byte-string list and value");
            }
            return DslType.BOOL;
        }
        if (node instanceof CompareNode comparison) {
            DslType left = validateNode(comparison.left(), schema, variables, count, maxNodes);
            DslType right = validateNode(comparison.right(), schema, variables, count, maxNodes);
            if (left != right || !switch (left) {
                case INTEGER, BYTE_STRING, CREDENTIAL -> true;
                default -> false;
            }) {
                throw new IllegalArgumentException("Invalid comparison between " + left
                        + " and " + right);
            }
            if (left != DslType.INTEGER && comparison.operator() != CompareOperator.EQ
                    && comparison.operator() != CompareOperator.NE) {
                throw new IllegalArgumentException("Ordering is supported only for integers");
            }
            return DslType.BOOL;
        }
        if (node instanceof CredentialKeyHashNode match) {
            require(DslType.CREDENTIAL,
                    validateNode(match.credential(), schema, variables, count, maxNodes));
            require(DslType.BYTE_STRING,
                    validateNode(match.keyHash(), schema, variables, count, maxNodes));
            return DslType.BOOL;
        }
        if (node instanceof ExistsNode exists) {
            require(DslType.LIST_TX_OUT,
                    validateNode(exists.collection(), schema, variables, count, maxNodes));
            if (!exists.variable().matches("[A-Za-z][A-Za-z0-9_]{0,31}")) {
                throw new IllegalArgumentException("Invalid quantified variable name");
            }
            var nested = new HashMap<>(variables);
            nested.put(exists.variable(), DslType.TX_OUT);
            require(DslType.BOOL,
                    validateNode(exists.predicate(), schema, nested, count, maxNodes));
            return DslType.BOOL;
        }
        if (node instanceof LiteralNode literal) {
            if (literal.resultType() != DslType.INTEGER) {
                throw new IllegalArgumentException(
                        "DSL v1 supports only integer literals");
            }
            if (!literal.value().matches("-?(0|[1-9][0-9]*)")) {
                throw new IllegalArgumentException("Invalid canonical integer literal");
            }
            return literal.resultType();
        }
        throw new IllegalArgumentException("Unsupported property node " + node.getClass());
    }

    private static DslType fieldType(
            DslType target, String field, ContractSchema schema) {
        if (target == DslType.DATA) {
            PirType datumType = resolve(schema.datum().type(), schema);
            if (!(datumType instanceof PirType.RecordType record)) {
                throw new IllegalArgumentException("Datum root must be a named record");
            }
            PirType selected = record.fields().stream()
                    .filter(candidate -> candidate.name().equals(field))
                    .map(PirType.Field::type)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown datum field '" + field + "'"));
            return dslType(resolve(selected, schema));
        }
        return switch (target + "." + field) {
            case "SCRIPT_CONTEXT.txInfo" -> DslType.TX_INFO;
            case "TX_INFO.signatories" -> DslType.LIST_BYTE_STRING;
            case "TX_INFO.outputs" -> DslType.LIST_TX_OUT;
            case "TX_OUT.address" -> DslType.ADDRESS;
            case "TX_OUT.value" -> DslType.VALUE;
            case "ADDRESS.credential" -> DslType.CREDENTIAL;
            case "VALUE.lovelace" -> DslType.INTEGER;
            default -> throw new IllegalArgumentException(
                    "Unsupported field " + field + " on " + target);
        };
    }

    private static PirType resolve(PirType type, ContractSchema schema) {
        if (type instanceof PirType.NamedTypeRef ref) {
            PirType resolved = schema.namedDefinitions().get(ref.stableId());
            if (resolved == null) {
                resolved = schema.namedDefinitions().get(ref.name());
            }
            if (resolved == null) {
                throw new IllegalArgumentException("Unknown named contract type " + ref.name());
            }
            return resolved;
        }
        return type;
    }

    private static DslType dslType(PirType type) {
        return switch (type) {
            case PirType.ByteStringType ignored -> DslType.BYTE_STRING;
            case PirType.IntegerType ignored -> DslType.INTEGER;
            case PirType.BoolType ignored -> DslType.BOOL;
            default -> throw new IllegalArgumentException(
                    "Contract field type is not supported by DSL v1: " + type);
        };
    }

    private static void require(DslType expected, DslType observed) {
        if (expected != observed) {
            throw new IllegalArgumentException("Expected " + expected + ", found " + observed);
        }
    }
}
