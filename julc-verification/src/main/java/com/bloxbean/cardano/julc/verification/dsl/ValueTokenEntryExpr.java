package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;

import java.util.Objects;
import java.util.function.BiFunction;

/** Raw token entry, inspectable only through its strict shape guard. */
public record ValueTokenEntryExpr(PropertyNode node) implements Expr {
    public ValueTokenEntryExpr { node = Objects.requireNonNull(node, "node"); }
    public BoolExpr whenWellFormed(
            BiFunction<LedgerByteAliasExpr, IntegerExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return BinderScope.bind(key -> BinderScope.bind(quantity -> {
            var token = new LedgerByteAliasExpr(
                    new TypedVariableNode(key, LedgerTypeAuthority.TOKEN_NAME),
                    LedgerTypeAuthority.TOKEN_NAME);
            var amount = new IntegerExpr(
                    new TypedVariableNode(quantity, LedgerTypeAuthority.INTEGER));
            return new BoolExpr(new ValueEntryWhenNode(
                    ValueEntryWhenNode.ValueEntryKind.TOKEN, node,
                    LedgerTypeAuthority.VALUE_TOKEN_ENTRY,
                    key, LedgerTypeAuthority.TOKEN_NAME,
                    quantity, LedgerTypeAuthority.INTEGER,
                    predicate.apply(token, amount).node()));
        }));
    }
}
