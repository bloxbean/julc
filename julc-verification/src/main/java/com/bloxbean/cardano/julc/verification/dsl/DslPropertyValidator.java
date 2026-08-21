package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.compiler.schema.ContractSchema;
import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import com.bloxbean.cardano.julc.verification.dsl.type.*;

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
        boolean typedV4 = propertySet.schemaVersion()
                == DslPropertySet.TYPED_SCHEMA_VERSION;
        boolean composition = compositionV3 || typedV4;
        ContractSchema.Purpose expectedPurpose = composition
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
        ProjectedContractTypes projection = typedV4
                ? ContractTypeProjection.project(schema) : null;
        TypeAuthority typeAuthority = projection == null
                ? null : new TypeAuthority(projection);
        if (typedV4) {
            String expectedHash = ContractTypeProjection.sha256(projection);
            if (!expectedHash.equals(propertySet.contractSchemaSha256())) {
                throw new IllegalArgumentException(
                        "DSL schema 4 contract schema hash does not match compiler-owned types");
            }
        }
        int[] nodes = {0};
        var normalizedIds = new HashSet<String>();
        for (DslProperty property : propertySet.properties()) {
            if (composition && property.id().toLowerCase(java.util.Locale.ROOT)
                    .endsWith(".non-vacuity")) {
                throw new IllegalArgumentException(
                        "Property ID uses reserved runner suffix .non-vacuity: "
                                + property.id());
            }
            if (composition && !normalizedIds.add(normalizedIdentifier(property.id()))) {
                throw new IllegalArgumentException(
                        "Property IDs collide after generated-name normalization: "
                                + property.id());
            }
            if (composition) validateDomain(property.domain(), propertySet.purpose());
            DslType type = containsTyped(property.expression())
                    ? validateTypedExpression(property.expression(), schema, typeAuthority,
                            new HashMap<>(), nodes, maxNodes, propertySet.schemaVersion())
                    : validateNode(property.expression(), schema, new HashMap<>(), nodes,
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
        if (isTypedNode(node)) {
            throw new IllegalArgumentException(
                    "Schema-4 typed node reached the legacy validator path");
        }
        boolean legacySpending = schemaVersion == DslPropertySet.SCHEMA_VERSION;
        boolean composition = schemaVersion == DslPropertySet.COMPOSITION_SCHEMA_VERSION
                || schemaVersion == DslPropertySet.TYPED_SCHEMA_VERSION;
        if (legacySpending && isMintingSchemaNode(node)) {
            throw new IllegalArgumentException("DSL schema 1 does not admit minting node "
                    + node.getClass().getSimpleName());
        }
        if (!composition && (node instanceof TxCertKindNode
                || node instanceof KnownCertificateNode)) {
            throw new IllegalArgumentException(
                    "Certificate nodes require compositional DSL schema 3");
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
                case "validMintingContext" -> schema.purpose()
                        == ContractSchema.Purpose.MINT ? DslType.BOOL : null;
                case "validRewardingContext" -> schema.purpose()
                        == ContractSchema.Purpose.WITHDRAW ? DslType.BOOL : null;
                case "validCertifyingContext" -> schema.purpose()
                        == ContractSchema.Purpose.CERTIFY ? DslType.BOOL : null;
                case "redeemerStrictlyDecodes" -> switch (schema.purpose()) {
                    case MINT, WITHDRAW, CERTIFY -> DslType.BOOL;
                    default -> null;
                };
                case "ownPolicy" -> schema.purpose() == ContractSchema.Purpose.MINT
                        ? DslType.POLICY_ID : null;
                case "rewardingCredential" -> schema.purpose()
                        == ContractSchema.Purpose.WITHDRAW ? DslType.CREDENTIAL : null;
                case "certificate" -> schema.purpose()
                        == ContractSchema.Purpose.CERTIFY ? DslType.TX_CERT : null;
                case "certificateIndex" -> schema.purpose()
                        == ContractSchema.Purpose.CERTIFY ? DslType.INTEGER : null;
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
        if (node instanceof TxCertKindNode kind) {
            if (schema.purpose() != ContractSchema.Purpose.CERTIFY) {
                throw new IllegalArgumentException(
                        "Certificate-kind recognition requires a CERTIFY contract interface");
            }
            require(DslType.TX_CERT,
                    validateNode(kind.certificate(), schema, variables, count, maxNodes,
                            schemaVersion));
            return DslType.BOOL;
        }
        if (node instanceof KnownCertificateNode known) {
            if (schema.purpose() != ContractSchema.Purpose.CERTIFY) {
                throw new IllegalArgumentException(
                        "Known-certificate lookup requires a CERTIFY contract interface");
            }
            require(DslType.TX_CERT,
                    validateNode(known.certificate(), schema, variables, count, maxNodes,
                            schemaVersion));
            require(DslType.INTEGER,
                    validateNode(known.index(), schema, variables, count, maxNodes,
                            schemaVersion));
            require(DslType.LIST_TX_CERT,
                    validateNode(known.certificates(), schema, variables, count, maxNodes,
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
            DslType collection = validateNode(
                    exists.collection(), schema, variables, count, maxNodes, schemaVersion);
            DslType element = switch (collection) {
                case LIST_TX_OUT -> DslType.TX_OUT;
                case WITHDRAWALS -> DslType.WITHDRAWAL_ENTRY;
                default -> throw new IllegalArgumentException(
                        "exists requires a supported typed collection, found " + collection);
            };
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
            nested.put(exists.variable(), element);
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
            return validateBytesLiteral(literal);
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

    private static DslType validateTypedExpression(
            PropertyNode node,
            ContractSchema schema,
            TypeAuthority authority,
            Map<String, TypedBinding> variables,
            int[] count,
            int maxNodes,
            int schemaVersion) {
        if (schemaVersion != DslPropertySet.TYPED_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Structural typed nodes require DSL schema 4");
        }
        if (++count[0] > maxNodes) {
            throw new IllegalArgumentException("Property AST exceeds " + maxNodes + " nodes");
        }
        if (node instanceof BoolBinaryNode binary) {
            require(DslType.BOOL, validateTypedExpressionOrLegacy(
                    binary.left(), schema, authority, variables, count, maxNodes, schemaVersion));
            require(DslType.BOOL, validateTypedExpressionOrLegacy(
                    binary.right(), schema, authority, variables, count, maxNodes, schemaVersion));
            return DslType.BOOL;
        }
        if (node instanceof BoolLiteralNode) return DslType.BOOL;
        if (node instanceof BoolNotNode not) {
            require(DslType.BOOL, validateTypedExpressionOrLegacy(
                    not.value(), schema, authority, variables, count, maxNodes, schemaVersion));
            return DslType.BOOL;
        }
        if (node instanceof IntegerArithmeticNode arithmetic) {
            requireIntegerValue(arithmetic.left(), schema, authority, variables,
                    count, maxNodes, schemaVersion);
            switch (arithmetic.operator()) {
                case ADD, SUBTRACT -> requireIntegerValue(arithmetic.right(), schema,
                        authority, variables, count, maxNodes, schemaVersion);
                case NEGATE -> {
                    if (arithmetic.right() != null || arithmetic.constant() != null) {
                        throw new IllegalArgumentException(
                                "Integer negation cannot have a right operand or constant");
                    }
                }
                case SCALE -> validateLinearConstant(arithmetic.constant());
            }
            return DslType.INTEGER;
        }
        if (node instanceof TypedEqualityNode equality) {
            VerificationTypeRef left = validateStructuralValue(equality.left(), schema,
                    authority, variables, count, maxNodes, schemaVersion);
            VerificationTypeRef right = validateStructuralValue(equality.right(), schema,
                    authority, variables, count, maxNodes, schemaVersion);
            authority.requireKnown(equality.valueType());
            if (!left.equals(right) || !left.equals(equality.valueType())) {
                throw new IllegalArgumentException(
                        "Typed equality operands do not match their declared structural type");
            }
            requireAdmittedEquality(left);
            return DslType.BOOL;
        }
        if (node instanceof OptionExistsNode exists) {
            VerificationTypeRef optional = validateTypedValue(
                    exists.optional(), authority, variables, count, maxNodes);
            if (!(optional instanceof OptionalTypeRef expected)
                    || !expected.elementType().equals(exists.elementType())) {
                throw new IllegalArgumentException(
                        "Option exists element type does not match its structural optional");
            }
            validateBinder(exists.variable(), variables);
            var nested = new HashMap<>(variables);
            nested.put(exists.variable(), new TypedBinding(exists.elementType(), null));
            require(DslType.BOOL, validateTypedExpressionOrLegacy(
                    exists.predicate(), schema, authority, nested, count, maxNodes, schemaVersion));
            return DslType.BOOL;
        }
        if (node instanceof OptionStateNode option) {
            VerificationTypeRef actual = validateTypedValue(
                    option.optional(), authority, variables, count, maxNodes);
            if (!actual.equals(new OptionalTypeRef(option.elementType()))) {
                throw new IllegalArgumentException(
                        "Option state element type does not match its structural optional");
            }
            authority.requireKnown(option.elementType());
            return DslType.BOOL;
        }
        if (node instanceof VariantIsNode variant) {
            VerificationTypeRef actual = validateTypedValue(
                    variant.value(), authority, variables, count, maxNodes);
            authority.requireSumConstructor(actual, variant.sumType(), variant.constructor());
            return DslType.BOOL;
        }
        if (node instanceof VariantWhenNode variant) {
            VerificationTypeRef actual = validateTypedValue(
                    variant.value(), authority, variables, count, maxNodes);
            authority.requireSumConstructor(actual, variant.sumType(), variant.constructor());
            validateBinder(variant.variable(), variables);
            var nested = new HashMap<>(variables);
            nested.put(variant.variable(),
                    new TypedBinding(variant.sumType(), variant.constructor()));
            require(DslType.BOOL, validateTypedExpressionOrLegacy(
                    variant.predicate(), schema, authority, nested, count, maxNodes, schemaVersion));
            return DslType.BOOL;
        }
        if (node instanceof ListStateNode list) {
            requireList(list.list(), list.elementType(), authority, variables,
                    count, maxNodes);
            return DslType.BOOL;
        }
        if (node instanceof ListContainsNode list) {
            requireList(list.list(), list.elementType(), authority, variables,
                    count, maxNodes);
            VerificationTypeRef value = validateStructuralValue(list.value(), schema,
                    authority, variables, count, maxNodes, schemaVersion);
            if (!value.equals(list.elementType())) {
                throw new IllegalArgumentException("List membership value type does not match");
            }
            requireAdmittedEquality(value);
            return DslType.BOOL;
        }
        if (node instanceof ListQuantifierNode list) {
            requireList(list.list(), list.elementType(), authority, variables,
                    count, maxNodes);
            validateBinder(list.variable(), variables);
            var nested = new HashMap<>(variables);
            nested.put(list.variable(), new TypedBinding(list.elementType(), null));
            require(DslType.BOOL, validateTypedExpressionOrLegacy(list.predicate(), schema,
                    authority, nested, count, maxNodes, schemaVersion));
            return DslType.BOOL;
        }
        if (node instanceof ListCountNode list) {
            requireList(list.list(), list.elementType(), authority, variables,
                    count, maxNodes);
            validateBinder(list.variable(), variables);
            var nested = new HashMap<>(variables);
            nested.put(list.variable(), new TypedBinding(list.elementType(), null));
            require(DslType.BOOL, validateTypedExpressionOrLegacy(list.predicate(), schema,
                    authority, nested, count, maxNodes, schemaVersion));
            return DslType.INTEGER;
        }
        if (node instanceof StructuralEqualsNode equality) {
            VerificationTypeRef left = validateTypedValue(
                    equality.left(), authority, variables, count, maxNodes);
            VerificationTypeRef right = validateTypedValue(
                    equality.right(), authority, variables, count, maxNodes);
            authority.requireKnown(equality.valueType());
            if (!left.equals(right) || !left.equals(equality.valueType())) {
                throw new IllegalArgumentException(
                        "Structural equality operands do not match their declared type");
            }
            requireAdmittedEquality(left);
            return DslType.BOOL;
        }
        if (node instanceof MapQuantifierNode map) {
            requireMap(map.map(), map.keyType(), map.valueType(), authority, variables,
                    count, maxNodes);
            validateBinder(map.keyVariable(), variables);
            var nested = new HashMap<>(variables);
            nested.put(map.keyVariable(), new TypedBinding(map.keyType(), null));
            validateBinder(map.valueVariable(), nested);
            nested.put(map.valueVariable(), new TypedBinding(map.valueType(), null));
            require(DslType.BOOL, validateTypedExpressionOrLegacy(map.predicate(), schema,
                    authority, nested, count, maxNodes, schemaVersion));
            return DslType.BOOL;
        }
        if (node instanceof MapCountEntryNode map) {
            requireMap(map.map(), map.keyType(), map.valueType(), authority, variables,
                    count, maxNodes);
            validateBinder(map.keyVariable(), variables);
            var nested = new HashMap<>(variables);
            nested.put(map.keyVariable(), new TypedBinding(map.keyType(), null));
            validateBinder(map.valueVariable(), nested);
            nested.put(map.valueVariable(), new TypedBinding(map.valueType(), null));
            require(DslType.BOOL, validateTypedExpressionOrLegacy(map.predicate(), schema,
                    authority, nested, count, maxNodes, schemaVersion));
            return DslType.INTEGER;
        }
        if (node instanceof MapContainsKeyNode map) {
            validateMapKeyOperation(map.map(), map.key(), map.keyType(), map.valueType(),
                    schema, authority, variables, count, maxNodes, schemaVersion);
            return DslType.BOOL;
        }
        if (node instanceof MapCountKeyNode map) {
            validateMapKeyOperation(map.map(), map.key(), map.keyType(), map.valueType(),
                    schema, authority, variables, count, maxNodes, schemaVersion);
            return DslType.INTEGER;
        }
        if (node instanceof CompareNode comparison) {
            Object left = validateTypedOrLegacyScalar(
                    comparison.left(), schema, authority, variables, count, maxNodes,
                    schemaVersion);
            Object right = validateTypedOrLegacyScalar(
                    comparison.right(), schema, authority, variables, count, maxNodes,
                    schemaVersion);
            if (!sameScalarType(left, right)) {
                throw new IllegalArgumentException(
                        "Invalid comparison between incompatible structural types");
            }
            boolean integer = left.equals(DslType.INTEGER)
                    || left.equals(new BuiltinTypeRef(BuiltinTypeRef.BuiltinKind.INTEGER));
            if (!integer && comparison.operator() != CompareOperator.EQ
                    && comparison.operator() != CompareOperator.NE) {
                throw new IllegalArgumentException("Ordering is supported only for integers");
            }
            return DslType.BOOL;
        }
        if (node instanceof ContainsNode contains) {
            DslType collection = validateNode(
                    contains.collection(), schema, new HashMap<>(), count, maxNodes,
                    schemaVersion);
            if (collection != DslType.LIST_BYTE_STRING) {
                throw new IllegalArgumentException(
                        "contains requires a byte-string list");
            }
            Object value = validateTypedOrLegacyScalar(
                    contains.value(), schema, authority, variables, count, maxNodes,
                    schemaVersion);
            if (!value.equals(DslType.BYTE_STRING)
                    && !value.equals(new BuiltinTypeRef(
                            BuiltinTypeRef.BuiltinKind.BYTE_STRING))) {
                throw new IllegalArgumentException(
                        "contains requires a byte-string value");
            }
            return DslType.BOOL;
        }
        if (!containsTyped(node)) {
            // Undo this method's node count because the legacy validator owns it.
            count[0]--;
            return validateNode(node, schema, new HashMap<>(), count, maxNodes, schemaVersion);
        }
        throw new IllegalArgumentException(
                "Unsupported schema-4 typed expression node " + node.getClass().getSimpleName());
    }

    private static DslType validateTypedExpressionOrLegacy(
            PropertyNode node,
            ContractSchema schema,
            TypeAuthority authority,
            Map<String, TypedBinding> variables,
            int[] count,
            int maxNodes,
            int schemaVersion) {
        return containsTyped(node)
                ? validateTypedExpression(
                        node, schema, authority, variables, count, maxNodes, schemaVersion)
                : validateNode(node, schema, new HashMap<>(), count, maxNodes, schemaVersion);
    }

    private static Object validateTypedOrLegacyScalar(
            PropertyNode node,
            ContractSchema schema,
            TypeAuthority authority,
            Map<String, TypedBinding> variables,
            int[] count,
            int maxNodes,
            int schemaVersion) {
        if (isTypedValueNode(node)) {
            return validateTypedValue(node, authority, variables, count, maxNodes);
        }
        if (containsTyped(node)) {
            return validateTypedExpression(
                    node, schema, authority, variables, count, maxNodes, schemaVersion);
        }
        return validateNode(node, schema, new HashMap<>(), count, maxNodes, schemaVersion);
    }

    private static VerificationTypeRef validateStructuralValue(
            PropertyNode node,
            ContractSchema schema,
            TypeAuthority authority,
            Map<String, TypedBinding> variables,
            int[] count,
            int maxNodes,
            int schemaVersion) {
        if (isTypedValueNode(node)) {
            return validateTypedValue(node, authority, variables, count, maxNodes);
        }
        if (node instanceof BoolLiteralNode) {
            countNode(count, maxNodes);
            return builtin(BuiltinTypeRef.BuiltinKind.BOOLEAN);
        }
        if (containsTyped(node)) {
            DslType typed = validateTypedExpression(
                    node, schema, authority, variables, count, maxNodes, schemaVersion);
            Object converted = legacyBuiltin(typed);
            if (converted instanceof VerificationTypeRef type) return type;
            throw new IllegalArgumentException(
                    "Typed expression is not an admitted structural value: " + typed);
        }
        DslType legacy = validateNode(
                node, schema, new HashMap<>(), count, maxNodes, schemaVersion);
        Object converted = legacyBuiltin(legacy);
        if (converted instanceof VerificationTypeRef type) return type;
        throw new IllegalArgumentException(
                "Expression is not an admitted structural value: " + legacy);
    }

    private static void requireIntegerValue(
            PropertyNode node,
            ContractSchema schema,
            TypeAuthority authority,
            Map<String, TypedBinding> variables,
            int[] count,
            int maxNodes,
            int schemaVersion) {
        VerificationTypeRef type = validateStructuralValue(node, schema, authority,
                variables, count, maxNodes, schemaVersion);
        if (!type.equals(builtin(BuiltinTypeRef.BuiltinKind.INTEGER))) {
            throw new IllegalArgumentException("Linear arithmetic requires integers");
        }
    }

    private static void validateLinearConstant(String constant) {
        if (constant == null || !constant.matches("0|-?[1-9][0-9]*")) {
            throw new IllegalArgumentException(
                    "Linear scale requires a canonical signed integer constant");
        }
        try {
            Long.parseLong(constant);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(
                    "Linear scale constant must fit signed 64-bit range", invalid);
        }
    }

    private static void requireList(
            PropertyNode node,
            VerificationTypeRef elementType,
            TypeAuthority authority,
            Map<String, TypedBinding> variables,
            int[] count,
            int maxNodes) {
        authority.requireKnown(elementType);
        VerificationTypeRef actual = validateTypedValue(
                node, authority, variables, count, maxNodes);
        if (!actual.equals(new ListTypeRef(elementType))) {
            throw new IllegalArgumentException(
                    "List operation element type does not match its structural list");
        }
    }

    private static void requireMap(
            PropertyNode node,
            VerificationTypeRef keyType,
            VerificationTypeRef valueType,
            TypeAuthority authority,
            Map<String, TypedBinding> variables,
            int[] count,
            int maxNodes) {
        authority.requireKnown(keyType);
        authority.requireKnown(valueType);
        VerificationTypeRef actual = validateTypedValue(
                node, authority, variables, count, maxNodes);
        if (!actual.equals(new AssocMapTypeRef(keyType, valueType))) {
            throw new IllegalArgumentException(
                    "Map operation types do not match its structural association map");
        }
    }

    private static void validateMapKeyOperation(
            PropertyNode map,
            PropertyNode key,
            VerificationTypeRef keyType,
            VerificationTypeRef valueType,
            ContractSchema schema,
            TypeAuthority authority,
            Map<String, TypedBinding> variables,
            int[] count,
            int maxNodes,
            int schemaVersion) {
        requireMap(map, keyType, valueType, authority, variables, count, maxNodes);
        VerificationTypeRef actualKey = validateStructuralValue(key, schema, authority,
                variables, count, maxNodes, schemaVersion);
        if (!actualKey.equals(keyType)) {
            throw new IllegalArgumentException("Map lookup key type does not match");
        }
        requireAdmittedEquality(keyType);
    }

    private static BuiltinTypeRef builtin(BuiltinTypeRef.BuiltinKind kind) {
        return new BuiltinTypeRef(kind);
    }

    private static void requireAdmittedEquality(VerificationTypeRef type) {
        switch (type) {
            case BuiltinTypeRef builtin -> {
                if (builtin.builtin() == BuiltinTypeRef.BuiltinKind.DATA) {
                    throw new IllegalArgumentException(
                            "Raw Data equality is not admitted in DSL schema 4");
                }
            }
            case NominalTypeRef ignored -> { }
            case OptionalTypeRef optional -> requireAdmittedEquality(optional.elementType());
            case ListTypeRef list -> requireAdmittedEquality(list.elementType());
            case AssocMapTypeRef map -> {
                requireAdmittedEquality(map.keyType());
                requireAdmittedEquality(map.valueType());
            }
        }
    }

    private static void countNode(int[] count, int maxNodes) {
        if (++count[0] > maxNodes) {
            throw new IllegalArgumentException("Property AST exceeds " + maxNodes + " nodes");
        }
    }

    private static boolean sameScalarType(Object left, Object right) {
        if (left.equals(right)) return true;
        return legacyBuiltin(left).equals(right) || legacyBuiltin(right).equals(left);
    }

    private static Object legacyBuiltin(Object type) {
        if (!(type instanceof DslType legacy)) return type;
        return switch (legacy) {
            case INTEGER -> new BuiltinTypeRef(BuiltinTypeRef.BuiltinKind.INTEGER);
            case BYTE_STRING -> new BuiltinTypeRef(BuiltinTypeRef.BuiltinKind.BYTE_STRING);
            default -> legacy;
        };
    }

    private static VerificationTypeRef validateTypedValue(
            PropertyNode node,
            TypeAuthority authority,
            Map<String, TypedBinding> variables,
            int[] count,
            int maxNodes) {
        if (++count[0] > maxNodes) {
            throw new IllegalArgumentException("Property AST exceeds " + maxNodes + " nodes");
        }
        if (node instanceof TypedRootNode root) {
            VerificationTypeRef expected = switch (root.name()) {
                case "typedDatum" -> authority.datumOptional();
                case "typedRedeemer" -> authority.redeemerOptional();
                default -> null;
            };
            if (expected == null || !expected.equals(root.valueType())) {
                throw new IllegalArgumentException(
                        "Unknown or mistyped schema-4 root " + root.name());
            }
            return expected;
        }
        if (node instanceof TypedVariableNode variable) {
            TypedBinding binding = variables.get(variable.variable());
            if (binding == null || !binding.type().equals(variable.valueType())) {
                throw new IllegalArgumentException(
                        "Unknown or mistyped schema-4 binder " + variable.variable());
            }
            return binding.type();
        }
        if (node instanceof TypedFieldNode field) {
            VerificationTypeRef target = validateTypedValue(
                    field.target(), authority, variables, count, maxNodes);
            if (!target.equals(field.ownerType())) {
                throw new IllegalArgumentException(
                        "Typed field owner does not match target type");
            }
            VerificationTypeRef expected = authority.recordField(
                    field.ownerType(), field.name());
            if (!expected.equals(field.valueType())) {
                throw new IllegalArgumentException(
                        "Typed field result does not match compiler-owned schema");
            }
            return expected;
        }
        if (node instanceof VariantFieldNode field) {
            VerificationTypeRef target = validateTypedValue(
                    field.target(), authority, variables, count, maxNodes);
            if (!target.equals(field.sumType())
                    || !(field.target() instanceof TypedVariableNode variable)) {
                throw new IllegalArgumentException(
                        "Variant payload field requires its guarded constructor binder");
            }
            TypedBinding binding = variables.get(variable.variable());
            if (binding == null || !field.constructor().equals(binding.constructor())) {
                throw new IllegalArgumentException(
                        "Variant payload field is outside its constructor guard");
            }
            VerificationTypeRef expected = authority.variantField(
                    field.sumType(), field.constructor(), field.name());
            if (!expected.equals(field.valueType())) {
                throw new IllegalArgumentException(
                        "Variant field result does not match compiler-owned schema");
            }
            return expected;
        }
        if (node instanceof ListAtNode list) {
            requireList(list.list(), list.elementType(), authority, variables,
                    count, maxNodes);
            VerificationTypeRef index = validateTypedIntegerValue(
                    list.index(), authority, variables, count, maxNodes);
            if (!index.equals(builtin(BuiltinTypeRef.BuiltinKind.INTEGER))) {
                throw new IllegalArgumentException("List index must be an integer");
            }
            return new OptionalTypeRef(list.elementType());
        }
        if (node instanceof MapLookupFirstNode map) {
            requireMap(map.map(), map.keyType(), map.valueType(), authority, variables,
                    count, maxNodes);
            VerificationTypeRef key = validateTypedValueOrIntegerLiteral(
                    map.key(), authority, variables, count, maxNodes);
            if (!key.equals(map.keyType())) {
                throw new IllegalArgumentException("Map lookup key type does not match");
            }
            requireAdmittedEquality(key);
            return new OptionalTypeRef(map.valueType());
        }
        if (node instanceof MapLookupAllNode map) {
            requireMap(map.map(), map.keyType(), map.valueType(), authority, variables,
                    count, maxNodes);
            VerificationTypeRef key = validateTypedValueOrIntegerLiteral(
                    map.key(), authority, variables, count, maxNodes);
            if (!key.equals(map.keyType())) {
                throw new IllegalArgumentException("Map lookup key type does not match");
            }
            requireAdmittedEquality(key);
            return new ListTypeRef(map.valueType());
        }
        throw new IllegalArgumentException(
                "Expected schema-4 typed value, found " + node.getClass().getSimpleName());
    }

    private static VerificationTypeRef validateTypedValueOrIntegerLiteral(
            PropertyNode node,
            TypeAuthority authority,
            Map<String, TypedBinding> variables,
            int[] count,
            int maxNodes) {
        if (node instanceof LiteralNode literal && literal.resultType() == DslType.INTEGER) {
            countNode(count, maxNodes);
            if (!literal.value().matches("0|-?[1-9][0-9]*")) {
                throw new IllegalArgumentException("Invalid canonical integer literal");
            }
            return builtin(BuiltinTypeRef.BuiltinKind.INTEGER);
        }
        if (node instanceof BytesLiteralNode literal) {
            DslType type = validateBytesLiteral(literal);
            countNode(count, maxNodes);
            if (type != DslType.BYTE_STRING) {
                throw new IllegalArgumentException(
                        "Structural map lookup requires a byte-string literal");
            }
            return builtin(BuiltinTypeRef.BuiltinKind.BYTE_STRING);
        }
        return validateTypedValue(node, authority, variables, count, maxNodes);
    }

    private static DslType validateBytesLiteral(BytesLiteralNode literal) {
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

    /** Validate the closed linear-integer subset where a structural value is required. */
    private static VerificationTypeRef validateTypedIntegerValue(
            PropertyNode node,
            TypeAuthority authority,
            Map<String, TypedBinding> variables,
            int[] count,
            int maxNodes) {
        if (node instanceof IntegerArithmeticNode arithmetic) {
            countNode(count, maxNodes);
            requireIntegerType(validateTypedIntegerValue(
                    arithmetic.left(), authority, variables, count, maxNodes));
            switch (arithmetic.operator()) {
                case ADD, SUBTRACT -> requireIntegerType(validateTypedIntegerValue(
                        arithmetic.right(), authority, variables, count, maxNodes));
                case NEGATE -> {
                    if (arithmetic.right() != null || arithmetic.constant() != null) {
                        throw new IllegalArgumentException(
                                "Integer negation cannot have a right operand or constant");
                    }
                }
                case SCALE -> validateLinearConstant(arithmetic.constant());
            }
            return builtin(BuiltinTypeRef.BuiltinKind.INTEGER);
        }
        return validateTypedValueOrIntegerLiteral(
                node, authority, variables, count, maxNodes);
    }

    private static void requireIntegerType(VerificationTypeRef type) {
        if (!type.equals(builtin(BuiltinTypeRef.BuiltinKind.INTEGER))) {
            throw new IllegalArgumentException("Linear arithmetic requires integers");
        }
    }

    private static boolean containsTyped(PropertyNode node) {
        if (isTypedNode(node)) return true;
        if (node instanceof BoolBinaryNode binary) {
            return containsTyped(binary.left()) || containsTyped(binary.right());
        }
        if (node instanceof CompareNode comparison) {
            return containsTyped(comparison.left()) || containsTyped(comparison.right());
        }
        if (node instanceof ContainsNode contains) {
            return containsTyped(contains.collection()) || containsTyped(contains.value());
        }
        if (node instanceof BoolNotNode value) return containsTyped(value.value());
        if (node instanceof IntegerArithmeticNode value) {
            return containsTyped(value.left())
                    || value.right() != null && containsTyped(value.right());
        }
        if (node instanceof TypedEqualityNode || node instanceof OptionStateNode
                || node instanceof ListStateNode || node instanceof ListQuantifierNode
                || node instanceof ListContainsNode || node instanceof ListCountNode
                || node instanceof ListAtNode || node instanceof StructuralEqualsNode
                || node instanceof MapQuantifierNode || node instanceof MapCountEntryNode
                || node instanceof MapContainsKeyNode || node instanceof MapCountKeyNode
                || node instanceof MapLookupFirstNode || node instanceof MapLookupAllNode) {
            return true;
        }
        return false;
    }

    private static boolean isTypedNode(PropertyNode node) {
        return node instanceof TypedRootNode || node instanceof TypedVariableNode
                || node instanceof TypedFieldNode || node instanceof VariantFieldNode
                || node instanceof OptionExistsNode || node instanceof VariantIsNode
                || node instanceof VariantWhenNode || node instanceof BoolLiteralNode
                || node instanceof BoolNotNode || node instanceof IntegerArithmeticNode
                || node instanceof TypedEqualityNode || node instanceof OptionStateNode
                || node instanceof ListStateNode || node instanceof ListQuantifierNode
                || node instanceof ListContainsNode || node instanceof ListCountNode
                || node instanceof ListAtNode || node instanceof StructuralEqualsNode
                || node instanceof MapQuantifierNode || node instanceof MapCountEntryNode
                || node instanceof MapContainsKeyNode || node instanceof MapCountKeyNode
                || node instanceof MapLookupFirstNode || node instanceof MapLookupAllNode;
    }

    private static boolean isTypedValueNode(PropertyNode node) {
        return node instanceof TypedRootNode || node instanceof TypedVariableNode
                || node instanceof TypedFieldNode || node instanceof VariantFieldNode
                || node instanceof ListAtNode || node instanceof MapLookupFirstNode
                || node instanceof MapLookupAllNode;
    }

    private static void validateBinder(
            String variable, Map<String, TypedBinding> variables) {
        if (!variable.matches("v[0-9]{1,2}") || variables.containsKey(variable)) {
            throw new IllegalArgumentException(
                    "Invalid or shadowing schema-4 canonical binder " + variable);
        }
        if (variables.size() >= 32) {
            throw new IllegalArgumentException(
                    "Property binder depth exceeds 32 active variables");
        }
    }

    private record TypedBinding(VerificationTypeRef type, String constructor) { }

    private static final class TypeAuthority {
        private final ProjectedContractTypes projection;
        private final Map<String, ProjectedContractTypes.NominalDefinition> definitions;

        private TypeAuthority(ProjectedContractTypes projection) {
            this.projection = projection;
            this.definitions = projection.definitions().stream().collect(
                    java.util.stream.Collectors.toUnmodifiableMap(
                            ProjectedContractTypes.NominalDefinition::stableId,
                            definition -> definition));
        }

        private VerificationTypeRef datumOptional() {
            return projection.datumType() == null
                    ? null : new OptionalTypeRef(projection.datumType());
        }

        private VerificationTypeRef redeemerOptional() {
            return new OptionalTypeRef(projection.redeemerType());
        }

        private VerificationTypeRef recordField(NominalTypeRef owner, String name) {
            var definition = definition(owner, NominalTypeRef.NominalKind.RECORD);
            return definition.fields().stream()
                    .filter(field -> field.name().equals(name))
                    .map(ProjectedContractTypes.Field::type)
                    .findFirst().orElseThrow(() -> new IllegalArgumentException(
                            "Unknown compiler-owned record field " + owner.stableId() + "." + name));
        }

        private VerificationTypeRef variantField(
                NominalTypeRef sum, String constructor, String field) {
            return constructor(sum, constructor).fields().stream()
                    .filter(candidate -> candidate.name().equals(field))
                    .map(ProjectedContractTypes.Field::type)
                    .findFirst().orElseThrow(() -> new IllegalArgumentException(
                            "Unknown compiler-owned variant field " + constructor + "." + field));
        }

        private void requireSumConstructor(
                VerificationTypeRef actual, NominalTypeRef sum, String constructor) {
            if (!actual.equals(sum)) {
                throw new IllegalArgumentException(
                        "Variant operation target does not match its nominal sum");
            }
            constructor(sum, constructor);
        }

        private void requireKnown(VerificationTypeRef type) {
            switch (type) {
                case BuiltinTypeRef ignored -> { }
                case NominalTypeRef nominal -> definition(
                        nominal, nominal.nominalKind());
                case OptionalTypeRef optional -> requireKnown(optional.elementType());
                case ListTypeRef list -> requireKnown(list.elementType());
                case AssocMapTypeRef map -> {
                    requireKnown(map.keyType());
                    requireKnown(map.valueType());
                }
            }
        }

        private ProjectedContractTypes.Constructor constructor(
                NominalTypeRef sum, String name) {
            var definition = definition(sum, NominalTypeRef.NominalKind.SUM);
            return definition.constructors().stream()
                    .filter(constructor -> constructor.name().equals(name))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException(
                            "Unknown compiler-owned constructor " + sum.stableId() + "." + name));
        }

        private ProjectedContractTypes.NominalDefinition definition(
                NominalTypeRef reference, NominalTypeRef.NominalKind kind) {
            var definition = definitions.get(reference.stableId());
            if (definition == null || reference.nominalKind() != kind
                    || definition.nominalKind() != kind) {
                throw new IllegalArgumentException(
                        "Unknown or mismatched compiler-owned nominal type "
                                + reference.stableId());
            }
            return definition;
        }
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
        if (selected.equals("TX_INFO.withdrawals")
                && schema.purpose() != ContractSchema.Purpose.WITHDRAW) {
            throw new IllegalArgumentException(
                    "Field TX_INFO.withdrawals requires a WITHDRAW contract interface");
        }
        if (selected.equals("TX_INFO.certificates")
                && schema.purpose() != ContractSchema.Purpose.CERTIFY) {
            throw new IllegalArgumentException(
                    "Field TX_INFO.certificates requires a CERTIFY contract interface");
        }
        return switch (selected) {
            case "SCRIPT_CONTEXT.txInfo" -> DslType.TX_INFO;
            case "TX_INFO.signatories" -> DslType.LIST_BYTE_STRING;
            case "TX_INFO.outputs" -> DslType.LIST_TX_OUT;
            case "TX_INFO.inputs" -> DslType.LIST_TX_IN_INFO;
            case "TX_INFO.mint" -> DslType.MINT_VALUE;
            case "TX_INFO.withdrawals" -> DslType.WITHDRAWALS;
            case "TX_INFO.certificates" -> DslType.LIST_TX_CERT;
            case "TX_OUT.address" -> DslType.ADDRESS;
            case "TX_OUT.value" -> DslType.VALUE;
            case "ADDRESS.credential" -> DslType.CREDENTIAL;
            case "VALUE.lovelace" -> DslType.INTEGER;
            case "WITHDRAWAL_ENTRY.credential" -> DslType.CREDENTIAL;
            case "WITHDRAWAL_ENTRY.amount" -> DslType.INTEGER;
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
                || node instanceof ExactOwnPolicyAssetNode
                || node instanceof TxCertKindNode
                || node instanceof KnownCertificateNode;
    }

    private static ContractSchema.Purpose contractPurpose(DslPurpose purpose) {
        return switch (purpose) {
            case SPENDING -> ContractSchema.Purpose.SPEND;
            case MINTING -> ContractSchema.Purpose.MINT;
            case REWARDING -> ContractSchema.Purpose.WITHDRAW;
            case CERTIFYING -> ContractSchema.Purpose.CERTIFY;
        };
    }

    private static void validateDomain(DslDomain domain, DslPurpose purpose) {
        boolean valid = switch (domain) {
            case NONE -> true;
            case VALID_SPENDING_V3_PINNED -> purpose == DslPurpose.SPENDING;
            case VALID_MINTING_V3_PINNED -> purpose == DslPurpose.MINTING;
            case VALID_REWARDING_V3_PINNED -> purpose == DslPurpose.REWARDING;
            case VALID_CERTIFYING_V3_PINNED -> purpose == DslPurpose.CERTIFYING;
        };
        if (!valid) {
            throw new IllegalArgumentException(
                    "Domain " + domain + " is incompatible with purpose " + purpose);
        }
    }

    private static boolean isEnvelopeRoot(String name) {
        return name.equals("exactUplcSucceeds")
                || name.equals("validSpendingContext")
                || name.equals("validMintingContext")
                || name.equals("validRewardingContext")
                || name.equals("validCertifyingContext");
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
