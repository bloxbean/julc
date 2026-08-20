package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.compiler.schema.ContractSchema;
import com.bloxbean.cardano.julc.verification.dsl.ir.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/** Authoritative post-worker validation against compiler-owned contract types. */
public final class DslPropertyValidator {
    public static final int MAX_AST_NODES = 10_000;

    private DslPropertyValidator() { }

    public static void validate(
            DslPropertySet propertySet, ContractSchema schema, int maxNodes) {
        validateAndNormalize(propertySet, schema, maxNodes);
    }

    public static DslPropertySet validateAndNormalize(
            DslPropertySet propertySet, ContractSchema schema, int maxNodes) {
        validateInternal(propertySet, schema, maxNodes);
        DslPropertySet normalized = DslPropertyCanonicalizer.normalize(propertySet);
        if (normalized != propertySet) validateInternal(normalized, schema, maxNodes);
        return normalized;
    }

    private static void validateInternal(
            DslPropertySet propertySet, ContractSchema schema, int maxNodes) {
        boolean mintingV2 = propertySet.schemaVersion()
                == DslPropertySet.MINTING_SCHEMA_VERSION;
        boolean compositionV3 = propertySet.schemaVersion()
                == DslPropertySet.COMPOSITION_SCHEMA_VERSION;
        ContractSchema.Purpose expectedPurpose = compositionV3
                ? contractPurpose(propertySet.purpose())
                : mintingV2 ? ContractSchema.Purpose.MINT : ContractSchema.Purpose.SPEND;
        if (schema.purpose() != expectedPurpose) {
            throw new IllegalArgumentException("DSL schema " + propertySet.schemaVersion()
                    + " requires a " + expectedPurpose + " contract interface");
        }
        if (mintingV2 && propertySet.properties().size() != 1) {
            throw new IllegalArgumentException(
                    "Minting DSL schema 2 requires exactly one property");
        }
        int[] nodes = {0};
        var normalizedIds = new HashSet<String>();
        for (DslProperty property : propertySet.properties()) {
            if (compositionV3 && !normalizedIds.add(normalizedIdentifier(property.id()))) {
                throw new IllegalArgumentException(
                        "Property IDs collide after generated-name normalization: "
                                + property.id());
            }
            if (compositionV3) validateDomain(property.domain(), propertySet.purpose());
            DslType type = validateNode(property.expression(), schema, new HashMap<>(), nodes,
                    maxNodes, propertySet.schemaVersion());
            if (type != DslType.BOOL) {
                throw new IllegalArgumentException("Property " + property.id()
                        + " must have BOOL type, found " + type);
            }
            if (mintingV2) validateMintingPremise(property.expression());
        }
    }

    private static DslType validateNode(
            PropertyNode node,
            ContractSchema schema,
            Map<String, DslType> variables,
            int[] count,
            int maxNodes,
            int schemaVersion) {
        if (++count[0] > maxNodes) {
            throw new IllegalArgumentException("Property AST exceeds " + maxNodes + " nodes");
        }
        boolean legacySpending = schemaVersion == DslPropertySet.SCHEMA_VERSION;
        boolean composition = schemaVersion == DslPropertySet.COMPOSITION_SCHEMA_VERSION;
        if (legacySpending && isMintingSchemaNode(node)) {
            throw new IllegalArgumentException("DSL schema 1 does not admit minting node "
                    + node.getClass().getSimpleName());
        }
        if (node instanceof RootNode root) {
            if (composition && isEnvelopeRoot(root.name())) {
                throw new IllegalArgumentException("Schema-3 guarantee cannot contain theorem "
                        + "envelope root " + root.name());
            }
            DslType expected = switch (root.name()) {
                case "datum" -> schema.purpose() == ContractSchema.Purpose.SPEND
                        ? DslType.DATA : null;
                case "context" -> DslType.SCRIPT_CONTEXT;
                case "exactUplcSucceeds" -> DslType.BOOL;
                case "validSpendingContext" -> schema.purpose()
                        == ContractSchema.Purpose.SPEND ? DslType.BOOL : null;
                case "validMintingContext", "redeemerStrictlyDecodes" -> schema.purpose()
                        == ContractSchema.Purpose.MINT ? DslType.BOOL : null;
                case "ownPolicy" -> schema.purpose() == ContractSchema.Purpose.MINT
                        ? DslType.POLICY_ID : null;
                default -> variables.get(root.name());
            };
            if (expected == null || expected != root.resultType()) {
                throw new IllegalArgumentException("Unknown or mistyped symbolic root "
                        + root.name());
            }
            return expected;
        }
        if (node instanceof FieldNode field) {
            DslType target = validateNode(
                    field.target(), schema, variables, count, maxNodes, schemaVersion);
            DslType expected = fieldType(target, field.name(), schema, schemaVersion);
            if (expected != field.resultType()) {
                throw new IllegalArgumentException("Field " + field.name() + " on " + target
                        + " has type " + expected + ", not " + field.resultType());
            }
            return expected;
        }
        if (node instanceof BoolBinaryNode binary) {
            require(DslType.BOOL, validateNode(
                    binary.left(), schema, variables, count, maxNodes, schemaVersion));
            require(DslType.BOOL, validateNode(
                    binary.right(), schema, variables, count, maxNodes, schemaVersion));
            return DslType.BOOL;
        }
        if (node instanceof ContainsNode contains) {
            DslType collection = validateNode(
                    contains.collection(), schema, variables, count, maxNodes, schemaVersion);
            DslType value = validateNode(
                    contains.value(), schema, variables, count, maxNodes, schemaVersion);
            if (collection != DslType.LIST_BYTE_STRING || value != DslType.BYTE_STRING) {
                throw new IllegalArgumentException("contains requires byte-string list and value");
            }
            return DslType.BOOL;
        }
        if (node instanceof ConsumesNode consumes) {
            require(DslType.LIST_TX_IN_INFO,
                    validateNode(consumes.inputs(), schema, variables, count, maxNodes,
                            schemaVersion));
            require(DslType.TX_OUT_REF,
                    validateNode(consumes.outputReference(), schema, variables, count, maxNodes,
                            schemaVersion));
            return DslType.BOOL;
        }
        if (node instanceof ExactOwnPolicyAssetNode exact) {
            if (schema.purpose() != ContractSchema.Purpose.MINT) {
                throw new IllegalArgumentException(
                        "exactOwnPolicyAsset requires a MINT contract interface");
            }
            require(DslType.MINT_VALUE,
                    validateNode(exact.mint(), schema, variables, count, maxNodes,
                            schemaVersion));
            require(DslType.POLICY_ID,
                    validateNode(exact.policy(), schema, variables, count, maxNodes,
                            schemaVersion));
            require(DslType.BYTE_STRING,
                    validateNode(exact.tokenName(), schema, variables, count, maxNodes,
                            schemaVersion));
            require(DslType.INTEGER,
                    validateNode(exact.quantity(), schema, variables, count, maxNodes,
                            schemaVersion));
            return DslType.BOOL;
        }
        if (node instanceof CompareNode comparison) {
            DslType left = validateNode(
                    comparison.left(), schema, variables, count, maxNodes, schemaVersion);
            DslType right = validateNode(
                    comparison.right(), schema, variables, count, maxNodes, schemaVersion);
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
                    validateNode(match.credential(), schema, variables, count, maxNodes,
                            schemaVersion));
            require(DslType.BYTE_STRING,
                    validateNode(match.keyHash(), schema, variables, count, maxNodes,
                            schemaVersion));
            return DslType.BOOL;
        }
        if (node instanceof ExistsNode exists) {
            require(DslType.LIST_TX_OUT,
                    validateNode(exists.collection(), schema, variables, count, maxNodes,
                            schemaVersion));
            if (!exists.variable().matches("[A-Za-z][A-Za-z0-9_]{0,31}")) {
                throw new IllegalArgumentException("Invalid quantified variable name");
            }
            if (isReservedSymbol(exists.variable()) || variables.containsKey(exists.variable())) {
                throw new IllegalArgumentException(
                        "Quantified variable shadows a reserved or active symbol");
            }
            if (variables.size() >= 32) {
                throw new IllegalArgumentException(
                        "Property binder depth exceeds 32 active variables");
            }
            var nested = new HashMap<>(variables);
            nested.put(exists.variable(), DslType.TX_OUT);
            require(DslType.BOOL,
                    validateNode(exists.predicate(), schema, nested, count, maxNodes,
                            schemaVersion));
            return DslType.BOOL;
        }
        if (node instanceof LiteralNode literal) {
            if (literal.resultType() != DslType.INTEGER) {
                throw new IllegalArgumentException(
                        "DSL v1 supports only integer literals");
            }
            if (literal.value().length() > 4096
                    || !literal.value().matches("-?(0|[1-9][0-9]*)")) {
                throw new IllegalArgumentException("Invalid canonical integer literal");
            }
            return literal.resultType();
        }
        if (node instanceof BytesLiteralNode literal) {
            if (literal.hex().length() > 8192 || !literal.hex().matches("(?:[0-9a-f]{2})*")) {
                throw new IllegalArgumentException(
                        "Byte-string literal must be bounded canonical lowercase hexadecimal");
            }
            DslType expected = literal.kind() == BytesLiteralKind.POLICY_ID
                    ? DslType.POLICY_ID : DslType.BYTE_STRING;
            require(expected, literal.resultType());
            switch (literal.kind()) {
                case KEY_HASH -> {
                    if (literal.hex().length() != 56) {
                        throw new IllegalArgumentException("Key hash must encode exactly 28 bytes");
                    }
                }
                case TOKEN_NAME -> {
                    if (literal.hex().length() > 64) {
                        throw new IllegalArgumentException("Token name must encode at most 32 bytes");
                    }
                }
                case POLICY_ID -> {
                    if (literal.hex().length() != 56) {
                        throw new IllegalArgumentException("Policy ID must encode exactly 28 bytes");
                    }
                }
                case BYTES -> { }
            }
            return literal.resultType();
        }
        if (node instanceof TxOutRefLiteralNode reference) {
            require(DslType.TX_OUT_REF, reference.resultType());
            if (!reference.transactionIdHex().matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "Transaction ID must be 32-byte canonical lowercase hexadecimal");
            }
            if (!reference.outputIndex().matches("0|[1-9][0-9]{0,18}")) {
                throw new IllegalArgumentException(
                        "Output index must be a bounded canonical nonnegative integer");
            }
            try {
                Long.parseLong(reference.outputIndex());
            } catch (NumberFormatException tooLarge) {
                throw new IllegalArgumentException(
                        "Output index must not exceed signed 64-bit range", tooLarge);
            }
            return DslType.TX_OUT_REF;
        }
        throw new IllegalArgumentException("Unsupported property node " + node.getClass());
    }

    private static DslType fieldType(
            DslType target, String field, ContractSchema schema, int schemaVersion) {
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
        String selected = target + "." + field;
        if (schemaVersion == DslPropertySet.SCHEMA_VERSION
                && (selected.equals("TX_INFO.inputs")
                || selected.equals("TX_INFO.mint"))) {
            throw new IllegalArgumentException(
                    "DSL schema 1 does not admit field " + selected);
        }
        if (selected.equals("TX_INFO.mint")
                && schema.purpose() != ContractSchema.Purpose.MINT) {
            throw new IllegalArgumentException(
                    "Field TX_INFO.mint requires a MINT contract interface");
        }
        return switch (selected) {
            case "SCRIPT_CONTEXT.txInfo" -> DslType.TX_INFO;
            case "TX_INFO.signatories" -> DslType.LIST_BYTE_STRING;
            case "TX_INFO.outputs" -> DslType.LIST_TX_OUT;
            case "TX_INFO.inputs" -> DslType.LIST_TX_IN_INFO;
            case "TX_INFO.mint" -> DslType.MINT_VALUE;
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

    private static boolean isMintingSchemaNode(PropertyNode node) {
        return node instanceof BytesLiteralNode
                || node instanceof TxOutRefLiteralNode
                || node instanceof ConsumesNode
                || node instanceof ExactOwnPolicyAssetNode;
    }

    private static ContractSchema.Purpose contractPurpose(DslPurpose purpose) {
        return switch (purpose) {
            case SPENDING -> ContractSchema.Purpose.SPEND;
            case MINTING -> ContractSchema.Purpose.MINT;
        };
    }

    private static void validateDomain(DslDomain domain, DslPurpose purpose) {
        boolean valid = switch (domain) {
            case NONE -> true;
            case VALID_SPENDING_V3_PINNED -> purpose == DslPurpose.SPENDING;
            case VALID_MINTING_V3_PINNED -> purpose == DslPurpose.MINTING;
        };
        if (!valid) {
            throw new IllegalArgumentException(
                    "Domain " + domain + " is incompatible with purpose " + purpose);
        }
    }

    private static boolean isEnvelopeRoot(String name) {
        return name.equals("exactUplcSucceeds")
                || name.equals("validSpendingContext")
                || name.equals("validMintingContext");
    }

    private static String normalizedIdentifier(String id) {
        return id.replaceAll("[^A-Za-z0-9_]", "_")
                .toLowerCase(java.util.Locale.ROOT);
    }

    private static void validateMintingPremise(PropertyNode expression) {
        if (!(expression instanceof BoolBinaryNode implication)
                || implication.operator() != BoolOperator.IMPLIES) {
            throw new IllegalArgumentException(
                    "Minting DSL property must be a normalized implication");
        }
        boolean ledgerDomain;
        if (isRoot(implication.left(), "exactUplcSucceeds")) {
            ledgerDomain = false;
        } else if (implication.left() instanceof BoolBinaryNode conjunction
                && conjunction.operator() == BoolOperator.AND
                && isRoot(conjunction.left(), "validMintingContext")
                && isRoot(conjunction.right(), "exactUplcSucceeds")) {
            ledgerDomain = true;
        } else {
            throw new IllegalArgumentException(
                    "Minting DSL premise must be exactUplcSucceeds or "
                            + "validMintingContext && exactUplcSucceeds");
        }
        var use = new HashMap<String, Integer>();
        countSpecialRoots(expression, use);
        if (use.getOrDefault("exactUplcSucceeds", 0) != 1
                || use.getOrDefault("validMintingContext", 0) != (ledgerDomain ? 1 : 0)) {
            throw new IllegalArgumentException(
                    "Minting execution and domain roots may occur only once in the premise");
        }
    }

    private static boolean isRoot(PropertyNode node, String name) {
        return node instanceof RootNode root && name.equals(root.name())
                && root.resultType() == DslType.BOOL;
    }

    private static void countSpecialRoots(PropertyNode node, Map<String, Integer> use) {
        if (node instanceof RootNode root) {
            if (root.name().equals("exactUplcSucceeds")
                    || root.name().equals("validMintingContext")) {
                use.merge(root.name(), 1, Integer::sum);
            }
        } else if (node instanceof FieldNode field) {
            countSpecialRoots(field.target(), use);
        } else if (node instanceof BoolBinaryNode binary) {
            countSpecialRoots(binary.left(), use);
            countSpecialRoots(binary.right(), use);
        } else if (node instanceof ContainsNode contains) {
            countSpecialRoots(contains.collection(), use);
            countSpecialRoots(contains.value(), use);
        } else if (node instanceof CompareNode comparison) {
            countSpecialRoots(comparison.left(), use);
            countSpecialRoots(comparison.right(), use);
        } else if (node instanceof CredentialKeyHashNode credential) {
            countSpecialRoots(credential.credential(), use);
            countSpecialRoots(credential.keyHash(), use);
        } else if (node instanceof ExistsNode exists) {
            countSpecialRoots(exists.collection(), use);
            countSpecialRoots(exists.predicate(), use);
        } else if (node instanceof ConsumesNode consumes) {
            countSpecialRoots(consumes.inputs(), use);
            countSpecialRoots(consumes.outputReference(), use);
        } else if (node instanceof ExactOwnPolicyAssetNode exact) {
            countSpecialRoots(exact.mint(), use);
            countSpecialRoots(exact.policy(), use);
            countSpecialRoots(exact.tokenName(), use);
            countSpecialRoots(exact.quantity(), use);
        }
    }

    private static boolean isReservedSymbol(String name) {
        return switch (name) {
            case "datum", "context", "exactUplcSucceeds", "validSpendingContext",
                    "validMintingContext", "redeemerStrictlyDecodes", "ownPolicy",
                    "ctx", "securityProperty" -> true;
            default -> false;
        };
    }
}
