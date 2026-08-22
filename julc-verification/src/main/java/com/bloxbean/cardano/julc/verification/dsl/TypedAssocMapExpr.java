package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import com.bloxbean.cardano.julc.verification.dsl.type.AssocMapTypeRef;
import com.bloxbean.cardano.julc.verification.dsl.type.VerificationTypeRef;

import java.util.Objects;
import java.util.function.BiFunction;

/** Raw ordered duplicate-preserving association-map carrier. */
public record TypedAssocMapExpr(
        PropertyNode node,
        VerificationTypeRef keyType,
        VerificationTypeRef valueType) implements Expr {
    public TypedAssocMapExpr {
        node = Objects.requireNonNull(node, "node");
        keyType = Objects.requireNonNull(keyType, "keyType");
        valueType = Objects.requireNonNull(valueType, "valueType");
    }

    public BoolExpr existsEntry(
            BiFunction<TypedValueExpr, TypedValueExpr, BoolExpr> predicate) {
        return quantify(QuantifierKind.EXISTS, predicate);
    }
    public BoolExpr allEntries(
            BiFunction<TypedValueExpr, TypedValueExpr, BoolExpr> predicate) {
        return quantify(QuantifierKind.ALL, predicate);
    }
    public BoolExpr noneEntries(
            BiFunction<TypedValueExpr, TypedValueExpr, BoolExpr> predicate) {
        return quantify(QuantifierKind.NONE, predicate);
    }
    public IntegerExpr countEntry(
            BiFunction<TypedValueExpr, TypedValueExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return BinderScope.bind(keyVariable -> BinderScope.bind(valueVariable -> {
            var key = variable(keyVariable, keyType);
            var value = variable(valueVariable, valueType);
            return new IntegerExpr(new MapCountEntryNode(node, keyType, valueType,
                    keyVariable, valueVariable, predicate.apply(key, value).node()));
        }));
    }
    public BoolExpr containsKey(TypedValueExpr key) {
        requireType(key, keyType, "Map key");
        return new BoolExpr(new MapContainsKeyNode(
                node, keyType, valueType, key.node()));
    }
    public IntegerExpr countKey(TypedValueExpr key) {
        requireType(key, keyType, "Map key");
        return new IntegerExpr(new MapCountKeyNode(
                node, keyType, valueType, key.node()));
    }
    public TypedOptionExpr lookupFirst(TypedValueExpr key) {
        requireType(key, keyType, "Map key");
        return new TypedOptionExpr(new MapLookupFirstNode(
                node, keyType, valueType, key.node()), valueType);
    }
    public TypedListExpr lookupAll(TypedValueExpr key) {
        requireType(key, keyType, "Map key");
        return new TypedListExpr(new MapLookupAllNode(
                node, keyType, valueType, key.node()), valueType);
    }
    public BoolExpr structurallyEquals(TypedAssocMapExpr other) {
        return structuralEquality(other, false);
    }
    public BoolExpr structurallyNotEquals(TypedAssocMapExpr other) {
        return structuralEquality(other, true);
    }
    private BoolExpr quantify(
            QuantifierKind kind,
            BiFunction<TypedValueExpr, TypedValueExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return BinderScope.bind(keyVariable -> BinderScope.bind(valueVariable -> {
            var key = variable(keyVariable, keyType);
            var value = variable(valueVariable, valueType);
            return new BoolExpr(new MapQuantifierNode(node, keyType, valueType, kind,
                    keyVariable, valueVariable, predicate.apply(key, value).node()));
        }));
    }
    private BoolExpr structuralEquality(TypedAssocMapExpr other, boolean negated) {
        Objects.requireNonNull(other, "other");
        if (!keyType.equals(other.keyType) || !valueType.equals(other.valueType)) {
            throw new IllegalArgumentException("Association-map types do not match");
        }
        return new BoolExpr(new StructuralEqualsNode(node, other.node,
                new AssocMapTypeRef(keyType, valueType), negated));
    }
    private static TypedValueExpr variable(String name, VerificationTypeRef type) {
        return new TypedValueExpr(new TypedVariableNode(name, type), type);
    }
    private static void requireType(
            TypedValueExpr value, VerificationTypeRef expected, String label) {
        Objects.requireNonNull(value, "value");
        if (!expected.equals(value.valueType())) {
            throw new IllegalArgumentException(label + " type does not match");
        }
    }
}
