package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Minimal deterministic normalization for pure schema-3 property expressions. */
public final class DslPropertyCanonicalizer {
    private DslPropertyCanonicalizer() { }

    public static DslPropertySet normalize(DslPropertySet propertySet) {
        if (propertySet.schemaVersion() != DslPropertySet.COMPOSITION_SCHEMA_VERSION) {
            return propertySet;
        }
        List<DslProperty> properties = propertySet.properties().stream()
                .map(property -> new DslProperty(
                        property.id(), property.domain(), normalize(property.expression())))
                .sorted(Comparator.comparing(DslProperty::id))
                .toList();
        return new DslPropertySet(propertySet.schemaVersion(), propertySet.purpose(), properties);
    }

    static PropertyNode normalize(PropertyNode node) {
        if (node instanceof RootNode || node instanceof LiteralNode
                || node instanceof BytesLiteralNode
                || node instanceof TxOutRefLiteralNode) {
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
        throw new IllegalArgumentException("Unsupported property node " + node.getClass());
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
        List<PropertyNode> normalized = flattened.stream()
                .sorted(DslPropertyCanonicalizer::compareCanonical)
                .distinct()
                .toList();
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

    private static int compareCanonical(PropertyNode left, PropertyNode right) {
        return Arrays.compareUnsigned(PropertyIrCodec.canonicalNodeBytes(left),
                PropertyIrCodec.canonicalNodeBytes(right));
    }
}
