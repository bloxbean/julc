package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Minimal deterministic normalization for pure schema-3 property expressions. */
public final class DslPropertyCanonicalizer {
    private DslPropertyCanonicalizer() { }

    public static DslPropertySet normalize(DslPropertySet propertySet) {
        if (propertySet.schemaVersion() != DslPropertySet.COMPOSITION_SCHEMA_VERSION
                && propertySet.schemaVersion() != DslPropertySet.TYPED_SCHEMA_VERSION
                && propertySet.schemaVersion() != DslPropertySet.LEDGER_SCHEMA_VERSION
                && propertySet.schemaVersion()
                        != DslPropertySet.AUTHORIZATION_SCHEMA_VERSION
                && propertySet.schemaVersion()
                        != DslPropertySet.CERTIFICATE_PAYLOAD_SCHEMA_VERSION) {
            return propertySet;
        }
        List<DslProperty> properties = propertySet.properties().stream()
                .map(property -> new DslProperty(
                        property.id(), property.domain(), alphaNormalize(
                                normalize(alphaNormalize(property.expression())))))
                .sorted(Comparator.comparing(DslProperty::id))
                .toList();
        return new DslPropertySet(propertySet.schemaVersion(), propertySet.purpose(),
                propertySet.contractSchemaSha256(), properties);
    }

    static PropertyNode normalize(PropertyNode node) {
        if (node instanceof RootNode || node instanceof LiteralNode
                || node instanceof BytesLiteralNode
                || node instanceof TxOutRefLiteralNode
                || node instanceof BoolLiteralNode
                || node instanceof TypedRootNode
                || node instanceof TypedVariableNode
                || node instanceof LedgerRootNode
                || node instanceof NoSignersNode) {
            return node;
        }
        if (node instanceof FieldNode field) {
            return new FieldNode(normalize(field.target()), field.name(), field.resultType());
        }
        if (node instanceof BoolBinaryNode binary) {
            if (binary.operator() == BoolOperator.AND || binary.operator() == BoolOperator.OR) {
                return normalizeCommutative(binary.operator(), binary);
            }
            return new BoolBinaryNode(binary.operator(), normalize(binary.left()),
                    normalize(binary.right()));
        }
        if (node instanceof ContainsNode contains) {
            return new ContainsNode(normalize(contains.collection()), normalize(contains.value()));
        }
        if (node instanceof ConsumesNode consumes) {
            return new ConsumesNode(normalize(consumes.inputs()),
                    normalize(consumes.outputReference()));
        }
        if (node instanceof ExactOwnPolicyAssetNode exact) {
            return new ExactOwnPolicyAssetNode(normalize(exact.mint()),
                    normalize(exact.policy()), normalize(exact.tokenName()),
                    normalize(exact.quantity()));
        }
        if (node instanceof TxCertKindNode kind) {
            return new TxCertKindNode(normalize(kind.certificate()), kind.kind());
        }
        if (node instanceof KnownCertificateNode known) {
            return new KnownCertificateNode(normalize(known.certificate()),
                    normalize(known.index()), normalize(known.certificates()));
        }
        if (node instanceof CompareNode comparison) {
            return new CompareNode(comparison.operator(), normalize(comparison.left()),
                    normalize(comparison.right()));
        }
        if (node instanceof CredentialKeyHashNode credential) {
            return new CredentialKeyHashNode(normalize(credential.credential()),
                    normalize(credential.keyHash()));
        }
        if (node instanceof ExistsNode exists) {
            return new ExistsNode(normalize(exists.collection()), exists.variable(),
                    normalize(exists.predicate()));
        }
        if (node instanceof TypedFieldNode field) {
            return new TypedFieldNode(normalize(field.target()), field.ownerType(),
                    field.name(), field.valueType());
        }
        if (node instanceof VariantFieldNode field) {
            return new VariantFieldNode(normalize(field.target()), field.sumType(),
                    field.constructor(), field.name(), field.valueType());
        }
        if (node instanceof OptionExistsNode exists) {
            return new OptionExistsNode(normalize(exists.optional()), exists.variable(),
                    exists.elementType(), normalize(exists.predicate()));
        }
        if (node instanceof VariantIsNode variant) {
            return new VariantIsNode(normalize(variant.value()), variant.sumType(),
                    variant.constructor());
        }
        if (node instanceof VariantWhenNode variant) {
            return new VariantWhenNode(normalize(variant.value()), variant.sumType(),
                    variant.constructor(), variant.variable(),
                    normalize(variant.predicate()));
        }
        if (node instanceof LedgerFieldNode field) {
            return new LedgerFieldNode(normalize(field.target()), field.ownerType(),
                    field.name(), field.valueType());
        }
        if (node instanceof LedgerVariantFieldNode field) {
            return new LedgerVariantFieldNode(normalize(field.target()), field.sumType(),
                    field.constructor(), field.name(), field.valueType());
        }
        if (node instanceof LedgerVariantIsNode variant) {
            return new LedgerVariantIsNode(normalize(variant.value()), variant.sumType(),
                    variant.constructor());
        }
        if (node instanceof LedgerVariantWhenNode variant) {
            return new LedgerVariantWhenNode(normalize(variant.value()), variant.sumType(),
                    variant.constructor(), variant.variable(),
                    normalize(variant.predicate()));
        }
        if (node instanceof LedgerHelperNode helper) {
            return new LedgerHelperNode(helper.helper(), helper.arguments().stream()
                    .map(DslPropertyCanonicalizer::normalize).toList(), helper.valueType());
        }
        if (node instanceof LedgerByteAliasNode alias) {
            return new LedgerByteAliasNode(normalize(alias.bytes()), alias.aliasType());
        }
        if (node instanceof AuthorityKeyHashNode authority) {
            return new AuthorityKeyHashNode(
                    authority.sourceKind(), normalize(authority.bytes()));
        }
        if (node instanceof AuthorityListNode authorities) {
            return normalizeAuthorityList(authorities);
        }
        if (node instanceof AuthorityListFromBytesNode authorities) {
            return new AuthorityListFromBytesNode(normalize(authorities.bytesList()));
        }
        if (node instanceof AuthorizationNode authorization) {
            return new AuthorizationNode(authorization.relation(),
                    normalize(authorization.authorities()), authorization.threshold());
        }
        if (node instanceof BoolNotNode not) {
            return new BoolNotNode(normalize(not.value()));
        }
        if (node instanceof IntegerArithmeticNode arithmetic) {
            return new IntegerArithmeticNode(arithmetic.operator(),
                    normalize(arithmetic.left()),
                    arithmetic.right() == null ? null : normalize(arithmetic.right()),
                    arithmetic.constant());
        }
        if (node instanceof TypedEqualityNode equality) {
            return new TypedEqualityNode(normalize(equality.left()),
                    normalize(equality.right()), equality.valueType(), equality.negated());
        }
        if (node instanceof OptionStateNode option) {
            return new OptionStateNode(normalize(option.optional()),
                    option.elementType(), option.state());
        }
        if (node instanceof ListStateNode list) {
            return new ListStateNode(normalize(list.list()), list.elementType(), list.state());
        }
        if (node instanceof ListQuantifierNode list) {
            return new ListQuantifierNode(normalize(list.list()), list.elementType(),
                    list.quantifier(), list.variable(), normalize(list.predicate()));
        }
        if (node instanceof ListContainsNode list) {
            return new ListContainsNode(normalize(list.list()), normalize(list.value()),
                    list.elementType());
        }
        if (node instanceof ListCountNode list) {
            return new ListCountNode(normalize(list.list()), list.elementType(),
                    list.variable(), normalize(list.predicate()));
        }
        if (node instanceof ListAtNode list) {
            return new ListAtNode(normalize(list.list()), list.elementType(),
                    normalize(list.index()));
        }
        if (node instanceof StructuralEqualsNode equality) {
            return new StructuralEqualsNode(normalize(equality.left()),
                    normalize(equality.right()), equality.valueType(), equality.negated());
        }
        if (node instanceof MapQuantifierNode map) {
            return new MapQuantifierNode(normalize(map.map()), map.keyType(), map.valueType(),
                    map.quantifier(), map.keyVariable(), map.valueVariable(),
                    normalize(map.predicate()));
        }
        if (node instanceof MapCountEntryNode map) {
            return new MapCountEntryNode(normalize(map.map()), map.keyType(), map.valueType(),
                    map.keyVariable(), map.valueVariable(), normalize(map.predicate()));
        }
        if (node instanceof MapContainsKeyNode map) {
            return new MapContainsKeyNode(normalize(map.map()), map.keyType(), map.valueType(),
                    normalize(map.key()));
        }
        if (node instanceof MapCountKeyNode map) {
            return new MapCountKeyNode(normalize(map.map()), map.keyType(), map.valueType(),
                    normalize(map.key()));
        }
        if (node instanceof MapLookupFirstNode map) {
            return new MapLookupFirstNode(normalize(map.map()), map.keyType(), map.valueType(),
                    normalize(map.key()));
        }
        if (node instanceof MapLookupAllNode map) {
            return new MapLookupAllNode(normalize(map.map()), map.keyType(), map.valueType(),
                    normalize(map.key()));
        }
        throw new IllegalArgumentException("Unsupported property node " + node.getClass());
    }

    private static PropertyNode alphaNormalize(PropertyNode node) {
        return alphaNormalize(node, new HashMap<>(), new int[]{0}, "v");
    }

    private static PropertyNode alphaNormalize(
            PropertyNode node, Map<String, String> binders, int[] next, String prefix) {
        if (node instanceof TypedVariableNode variable) {
            String canonical = binders.get(variable.variable());
            return canonical == null ? variable
                    : new TypedVariableNode(canonical, variable.valueType());
        }
        if (node instanceof RootNode root && binders.containsKey(root.name())) {
            return new RootNode(binders.get(root.name()), root.resultType());
        }
        if (node instanceof OptionExistsNode exists) {
            String variable = bind(exists.variable(), binders, next, prefix);
            PropertyNode predicate = alphaNormalize(exists.predicate(), binders, next, prefix);
            binders.remove(exists.variable());
            return new OptionExistsNode(alphaNormalize(exists.optional(), binders, next, prefix),
                    variable, exists.elementType(), predicate);
        }
        if (node instanceof ExistsNode exists) {
            String variable = bind(exists.variable(), binders, next, prefix);
            PropertyNode predicate = alphaNormalize(exists.predicate(), binders, next, prefix);
            binders.remove(exists.variable());
            return new ExistsNode(alphaNormalize(exists.collection(), binders, next, prefix),
                    variable, predicate);
        }
        if (node instanceof VariantWhenNode variant) {
            String variable = bind(variant.variable(), binders, next, prefix);
            PropertyNode predicate = alphaNormalize(variant.predicate(), binders, next, prefix);
            binders.remove(variant.variable());
            return new VariantWhenNode(alphaNormalize(variant.value(), binders, next, prefix),
                    variant.sumType(), variant.constructor(), variable, predicate);
        }
        if (node instanceof LedgerVariantWhenNode variant) {
            String variable = bind(variant.variable(), binders, next, prefix);
            PropertyNode predicate = alphaNormalize(
                    variant.predicate(), binders, next, prefix);
            binders.remove(variant.variable());
            return new LedgerVariantWhenNode(alphaNormalize(
                    variant.value(), binders, next, prefix), variant.sumType(),
                    variant.constructor(), variable, predicate);
        }
        if (node instanceof ListQuantifierNode list) {
            String variable = bind(list.variable(), binders, next, prefix);
            PropertyNode predicate = alphaNormalize(list.predicate(), binders, next, prefix);
            binders.remove(list.variable());
            return new ListQuantifierNode(alphaNormalize(list.list(), binders, next, prefix),
                    list.elementType(), list.quantifier(), variable, predicate);
        }
        if (node instanceof ListCountNode list) {
            String variable = bind(list.variable(), binders, next, prefix);
            PropertyNode predicate = alphaNormalize(list.predicate(), binders, next, prefix);
            binders.remove(list.variable());
            return new ListCountNode(alphaNormalize(list.list(), binders, next, prefix),
                    list.elementType(), variable, predicate);
        }
        if (node instanceof MapQuantifierNode map) {
            String key = bind(map.keyVariable(), binders, next, prefix);
            String value = bind(map.valueVariable(), binders, next, prefix);
            PropertyNode predicate = alphaNormalize(map.predicate(), binders, next, prefix);
            binders.remove(map.valueVariable());
            binders.remove(map.keyVariable());
            return new MapQuantifierNode(alphaNormalize(map.map(), binders, next, prefix),
                    map.keyType(), map.valueType(), map.quantifier(), key, value, predicate);
        }
        if (node instanceof MapCountEntryNode map) {
            String key = bind(map.keyVariable(), binders, next, prefix);
            String value = bind(map.valueVariable(), binders, next, prefix);
            PropertyNode predicate = alphaNormalize(map.predicate(), binders, next, prefix);
            binders.remove(map.valueVariable());
            binders.remove(map.keyVariable());
            return new MapCountEntryNode(alphaNormalize(map.map(), binders, next, prefix),
                    map.keyType(), map.valueType(), key, value, predicate);
        }
        return mapChildren(node, child -> alphaNormalize(child, binders, next, prefix));
    }

    private static String bind(
            String source, Map<String, String> binders, int[] next, String prefix) {
        String canonical = prefix + next[0]++;
        binders.put(source, canonical);
        return canonical;
    }

    private static PropertyNode mapChildren(
            PropertyNode node, java.util.function.Function<PropertyNode, PropertyNode> map) {
        if (node instanceof RootNode || node instanceof LiteralNode
                || node instanceof BytesLiteralNode || node instanceof TxOutRefLiteralNode
                || node instanceof BoolLiteralNode || node instanceof TypedRootNode
                || node instanceof TypedVariableNode
                || node instanceof LedgerRootNode
                || node instanceof NoSignersNode) return node;
        if (node instanceof FieldNode value) return new FieldNode(
                map.apply(value.target()), value.name(), value.resultType());
        if (node instanceof BoolBinaryNode value) return new BoolBinaryNode(
                value.operator(), map.apply(value.left()), map.apply(value.right()));
        if (node instanceof ContainsNode value) return new ContainsNode(
                map.apply(value.collection()), map.apply(value.value()));
        if (node instanceof ConsumesNode value) return new ConsumesNode(
                map.apply(value.inputs()), map.apply(value.outputReference()));
        if (node instanceof ExactOwnPolicyAssetNode value) return new ExactOwnPolicyAssetNode(
                map.apply(value.mint()), map.apply(value.policy()),
                map.apply(value.tokenName()), map.apply(value.quantity()));
        if (node instanceof TxCertKindNode value) return new TxCertKindNode(
                map.apply(value.certificate()), value.kind());
        if (node instanceof KnownCertificateNode value) return new KnownCertificateNode(
                map.apply(value.certificate()), map.apply(value.index()),
                map.apply(value.certificates()));
        if (node instanceof CompareNode value) return new CompareNode(
                value.operator(), map.apply(value.left()), map.apply(value.right()));
        if (node instanceof CredentialKeyHashNode value) return new CredentialKeyHashNode(
                map.apply(value.credential()), map.apply(value.keyHash()));
        if (node instanceof TypedFieldNode value) return new TypedFieldNode(
                map.apply(value.target()), value.ownerType(), value.name(), value.valueType());
        if (node instanceof VariantFieldNode value) return new VariantFieldNode(
                map.apply(value.target()), value.sumType(), value.constructor(),
                value.name(), value.valueType());
        if (node instanceof VariantIsNode value) return new VariantIsNode(
                map.apply(value.value()), value.sumType(), value.constructor());
        if (node instanceof LedgerFieldNode value) return new LedgerFieldNode(
                map.apply(value.target()), value.ownerType(), value.name(), value.valueType());
        if (node instanceof LedgerVariantFieldNode value) return new LedgerVariantFieldNode(
                map.apply(value.target()), value.sumType(), value.constructor(),
                value.name(), value.valueType());
        if (node instanceof LedgerVariantIsNode value) return new LedgerVariantIsNode(
                map.apply(value.value()), value.sumType(), value.constructor());
        if (node instanceof LedgerHelperNode value) return new LedgerHelperNode(
                value.helper(), value.arguments().stream().map(map).toList(),
                value.valueType());
        if (node instanceof LedgerByteAliasNode value) return new LedgerByteAliasNode(
                map.apply(value.bytes()), value.aliasType());
        if (node instanceof AuthorityKeyHashNode value) return new AuthorityKeyHashNode(
                value.sourceKind(), map.apply(value.bytes()));
        if (node instanceof AuthorityListNode value) return new AuthorityListNode(
                value.authorities().stream().map(map).toList());
        if (node instanceof AuthorityListFromBytesNode value) {
            return new AuthorityListFromBytesNode(map.apply(value.bytesList()));
        }
        if (node instanceof AuthorizationNode value) return new AuthorizationNode(
                value.relation(), map.apply(value.authorities()), value.threshold());
        if (node instanceof BoolNotNode value) return new BoolNotNode(map.apply(value.value()));
        if (node instanceof IntegerArithmeticNode value) return new IntegerArithmeticNode(
                value.operator(), map.apply(value.left()),
                value.right() == null ? null : map.apply(value.right()), value.constant());
        if (node instanceof TypedEqualityNode value) return new TypedEqualityNode(
                map.apply(value.left()), map.apply(value.right()),
                value.valueType(), value.negated());
        if (node instanceof OptionStateNode value) return new OptionStateNode(
                map.apply(value.optional()), value.elementType(), value.state());
        if (node instanceof ListStateNode value) return new ListStateNode(
                map.apply(value.list()), value.elementType(), value.state());
        if (node instanceof ListContainsNode value) return new ListContainsNode(
                map.apply(value.list()), map.apply(value.value()), value.elementType());
        if (node instanceof ListAtNode value) return new ListAtNode(
                map.apply(value.list()), value.elementType(), map.apply(value.index()));
        if (node instanceof StructuralEqualsNode value) return new StructuralEqualsNode(
                map.apply(value.left()), map.apply(value.right()),
                value.valueType(), value.negated());
        if (node instanceof MapContainsKeyNode value) return new MapContainsKeyNode(
                map.apply(value.map()), value.keyType(), value.valueType(), map.apply(value.key()));
        if (node instanceof MapCountKeyNode value) return new MapCountKeyNode(
                map.apply(value.map()), value.keyType(), value.valueType(), map.apply(value.key()));
        if (node instanceof MapLookupFirstNode value) return new MapLookupFirstNode(
                map.apply(value.map()), value.keyType(), value.valueType(), map.apply(value.key()));
        if (node instanceof MapLookupAllNode value) return new MapLookupAllNode(
                map.apply(value.map()), value.keyType(), value.valueType(), map.apply(value.key()));
        throw new IllegalArgumentException("Unsupported alpha-normalization node "
                + node.getClass());
    }

    private static PropertyNode normalizeCommutative(
            BoolOperator operator, PropertyNode expression) {
        var operands = new ArrayList<PropertyNode>();
        collect(operator, expression, operands);
        List<PropertyNode> normalizedOperands = operands.stream()
                .map(DslPropertyCanonicalizer::normalize)
                .toList();
        var flattened = new ArrayList<PropertyNode>();
        normalizedOperands.forEach(operand -> collect(operator, operand, flattened));
        List<PropertyNode> sorted = flattened.stream()
                .sorted(DslPropertyCanonicalizer::compareCanonicalModuloBinders)
                .toList();
        var normalized = new ArrayList<PropertyNode>();
        byte[] previous = null;
        for (PropertyNode operand : sorted) {
            byte[] key = canonicalModuloBinders(operand);
            if (previous == null || !Arrays.equals(previous, key)) {
                normalized.add(operand);
                previous = key;
            }
        }
        PropertyNode result = normalized.getFirst();
        for (int index = 1; index < normalized.size(); index++) {
            result = new BoolBinaryNode(operator, result, normalized.get(index));
        }
        return result;
    }

    private static void collect(
            BoolOperator operator, PropertyNode expression, List<PropertyNode> operands) {
        if (expression instanceof BoolBinaryNode binary && binary.operator() == operator) {
            collect(operator, binary.left(), operands);
            collect(operator, binary.right(), operands);
        } else {
            operands.add(expression);
        }
    }

    private static int compareCanonicalModuloBinders(PropertyNode left, PropertyNode right) {
        return Arrays.compareUnsigned(canonicalModuloBinders(left),
                canonicalModuloBinders(right));
    }

    private static byte[] canonicalModuloBinders(PropertyNode node) {
        PropertyNode locallyNormalized = alphaNormalize(
                node, new HashMap<>(), new int[]{0}, "localBinder");
        return PropertyIrCodec.canonicalNodeBytes(locallyNormalized);
    }

    private static AuthorityListNode normalizeAuthorityList(AuthorityListNode list) {
        List<PropertyNode> sorted = list.authorities().stream()
                .map(DslPropertyCanonicalizer::normalize)
                .sorted(DslPropertyCanonicalizer::compareCanonicalModuloBinders)
                .toList();
        var unique = new ArrayList<PropertyNode>();
        byte[] previous = null;
        for (PropertyNode authority : sorted) {
            byte[] key = canonicalModuloBinders(authority);
            if (previous == null || !Arrays.equals(previous, key)) {
                unique.add(authority);
                previous = key;
            }
        }
        return new AuthorityListNode(unique);
    }
}
