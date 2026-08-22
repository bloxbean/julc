package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;

import java.util.Objects;
import java.util.function.Function;

/** Ordered raw token entries within one strictly decoded policy entry. */
public record ValueTokenEntriesExpr(PropertyNode node) implements Expr {
    public ValueTokenEntriesExpr { node = Objects.requireNonNull(node, "node"); }
    public BoolExpr exists(Function<ValueTokenEntryExpr, BoolExpr> predicate) {
        return quantify(QuantifierKind.EXISTS, predicate);
    }
    public BoolExpr all(Function<ValueTokenEntryExpr, BoolExpr> predicate) {
        return quantify(QuantifierKind.ALL, predicate);
    }
    private BoolExpr quantify(
            QuantifierKind kind, Function<ValueTokenEntryExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return BinderScope.bind(variable -> {
            var entry = new ValueTokenEntryExpr(new TypedVariableNode(
                    variable, LedgerTypeAuthority.VALUE_TOKEN_ENTRY));
            return new BoolExpr(new ListQuantifierNode(node,
                    LedgerTypeAuthority.VALUE_TOKEN_ENTRY, kind, variable,
                    predicate.apply(entry).node()));
        });
    }
}
