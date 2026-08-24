package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;

import java.util.Objects;
import java.util.function.Function;

/** Checked value arithmetic result. Malformed inputs produce none. */
public record ValueDeltaOptionExpr(PropertyNode node) implements Expr {
    public ValueDeltaOptionExpr { node = Objects.requireNonNull(node, "node"); }
    public BoolExpr exists(Function<ValueDeltaExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return BinderScope.bind(variable -> {
            var delta = new ValueDeltaExpr(new TypedVariableNode(
                    variable, LedgerTypeAuthority.VALUE_DELTA));
            return new BoolExpr(new OptionExistsNode(node, variable,
                    LedgerTypeAuthority.VALUE_DELTA, predicate.apply(delta).node()));
        });
    }
    public BoolExpr isPresent() {
        return new BoolExpr(new OptionStateNode(node, LedgerTypeAuthority.VALUE_DELTA,
                OptionState.PRESENT));
    }
    public BoolExpr isEmpty() {
        return new BoolExpr(new OptionStateNode(node, LedgerTypeAuthority.VALUE_DELTA,
                OptionState.EMPTY));
    }
}
