package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import com.bloxbean.cardano.julc.verification.dsl.type.ListTypeRef;
import com.bloxbean.cardano.julc.verification.dsl.type.VerificationTypeRef;

import java.util.Objects;
import java.util.function.Function;

/** Ordered duplicate-preserving symbolic list. */
public record TypedListExpr(PropertyNode node, VerificationTypeRef elementType)
        implements Expr {
    public TypedListExpr {
        node = Objects.requireNonNull(node, "node");
        elementType = Objects.requireNonNull(elementType, "elementType");
    }

    public BoolExpr isEmpty() {
        return new BoolExpr(new ListStateNode(node, elementType, ListState.EMPTY));
    }
    public BoolExpr isNotEmpty() {
        return new BoolExpr(new ListStateNode(node, elementType, ListState.NON_EMPTY));
    }
    public BoolExpr contains(TypedValueExpr value) {
        requireElement(value);
        return new BoolExpr(new ListContainsNode(node, value.node(), elementType));
    }
    public BoolExpr exists(Function<TypedValueExpr, BoolExpr> predicate) {
        return quantify(QuantifierKind.EXISTS, predicate);
    }
    public BoolExpr all(Function<TypedValueExpr, BoolExpr> predicate) {
        return quantify(QuantifierKind.ALL, predicate);
    }
    public BoolExpr none(Function<TypedValueExpr, BoolExpr> predicate) {
        return quantify(QuantifierKind.NONE, predicate);
    }
    public BoolExpr whenSingleton(Function<TypedValueExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return BinderScope.bind(variable -> new BoolExpr(new ListSingletonWhenNode(
                node, elementType, variable, predicate.apply(new TypedValueExpr(
                        new TypedVariableNode(variable, elementType), elementType)).node())));
    }
    public IntegerExpr count(Function<TypedValueExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return BinderScope.bind(variable -> {
            var value = new TypedValueExpr(
                    new TypedVariableNode(variable, elementType), elementType);
            return new IntegerExpr(new ListCountNode(
                    node, elementType, variable, predicate.apply(value).node()));
        });
    }
    public BoolExpr exactlyOne(Function<TypedValueExpr, BoolExpr> predicate) {
        return count(predicate).eq(VerificationDsl.integer(1));
    }
    public TypedOptionExpr at(IntegerExpr index) {
        Objects.requireNonNull(index, "index");
        return new TypedOptionExpr(new ListAtNode(node, elementType, index.node()), elementType);
    }
    public BoolExpr structurallyEquals(TypedListExpr other) {
        return structuralEquality(other, false);
    }
    public BoolExpr structurallyNotEquals(TypedListExpr other) {
        return structuralEquality(other, true);
    }
    private BoolExpr quantify(
            QuantifierKind kind, Function<TypedValueExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return BinderScope.bind(variable -> {
            var value = new TypedValueExpr(
                    new TypedVariableNode(variable, elementType), elementType);
            return new BoolExpr(new ListQuantifierNode(
                    node, elementType, kind, variable, predicate.apply(value).node()));
        });
    }
    private BoolExpr structuralEquality(TypedListExpr other, boolean negated) {
        Objects.requireNonNull(other, "other");
        if (!elementType.equals(other.elementType)) {
            throw new IllegalArgumentException("List element types do not match");
        }
        return new BoolExpr(new StructuralEqualsNode(node, other.node,
                new ListTypeRef(elementType), negated));
    }
    private void requireElement(TypedValueExpr value) {
        Objects.requireNonNull(value, "value");
        if (!elementType.equals(value.valueType())) {
            throw new IllegalArgumentException("List element type does not match");
        }
    }
}
