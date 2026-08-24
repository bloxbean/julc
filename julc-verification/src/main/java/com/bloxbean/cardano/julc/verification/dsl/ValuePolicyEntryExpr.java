package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;

import java.util.Objects;
import java.util.function.BiFunction;

/** Raw policy entry, inspectable only through its strict shape guard. */
public record ValuePolicyEntryExpr(PropertyNode node) implements Expr {
    public ValuePolicyEntryExpr { node = Objects.requireNonNull(node, "node"); }
    public BoolExpr whenWellFormed(
            BiFunction<LedgerByteAliasExpr, ValueTokenEntriesExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return BinderScope.bind(key -> BinderScope.bind(tokens -> {
            var policy = new LedgerByteAliasExpr(
                    new TypedVariableNode(key, LedgerTypeAuthority.CURRENCY_SYMBOL),
                    LedgerTypeAuthority.CURRENCY_SYMBOL);
            var entries = new ValueTokenEntriesExpr(
                    new TypedVariableNode(tokens,
                            new com.bloxbean.cardano.julc.verification.dsl.type.ListTypeRef(
                                    LedgerTypeAuthority.VALUE_TOKEN_ENTRY)));
            return new BoolExpr(new ValueEntryWhenNode(
                    ValueEntryWhenNode.ValueEntryKind.POLICY, node,
                    LedgerTypeAuthority.VALUE_POLICY_ENTRY,
                    key, LedgerTypeAuthority.CURRENCY_SYMBOL,
                    tokens, new com.bloxbean.cardano.julc.verification.dsl.type.ListTypeRef(
                            LedgerTypeAuthority.VALUE_TOKEN_ENTRY),
                    predicate.apply(policy, entries).node()));
        }));
    }
}
