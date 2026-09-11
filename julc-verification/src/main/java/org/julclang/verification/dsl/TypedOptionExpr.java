package org.julclang.verification.dsl;

import org.julclang.verification.dsl.ir.OptionExistsNode;
import org.julclang.verification.dsl.ir.OptionState;
import org.julclang.verification.dsl.ir.OptionStateNode;
import org.julclang.verification.dsl.ir.PropertyNode;
import org.julclang.verification.dsl.ir.TypedVariableNode;
import org.julclang.verification.dsl.type.VerificationTypeRef;

import java.util.Objects;
import java.util.function.Function;

/** Guarded symbolic optional. There is deliberately no unchecked value accessor. */
public record TypedOptionExpr(PropertyNode node, VerificationTypeRef elementType)
        implements Expr {
    public TypedOptionExpr {
        node = Objects.requireNonNull(node, "node");
        elementType = Objects.requireNonNull(elementType, "elementType");
    }

    public BoolExpr exists(Function<TypedValueExpr, BoolExpr> predicate) {
        return BinderScope.bind(variable -> {
            var value = new TypedValueExpr(
                    new TypedVariableNode(variable, elementType), elementType);
            return new BoolExpr(new OptionExistsNode(
                    node, variable, elementType, predicate.apply(value).node()));
        });
    }

    public BoolExpr isPresent() {
        return new BoolExpr(new OptionStateNode(node, elementType, OptionState.PRESENT));
    }

    public BoolExpr isEmpty() {
        return new BoolExpr(new OptionStateNode(node, elementType, OptionState.EMPTY));
    }
}
