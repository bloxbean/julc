package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;

import java.util.Objects;
import java.util.function.Function;

/** Ordered raw policy entries. No malformed entry is filtered out. */
public record ValuePolicyEntriesExpr(PropertyNode node) implements Expr {
    public ValuePolicyEntriesExpr { node = Objects.requireNonNull(node, "node"); }
    public BoolExpr exists(Function<ValuePolicyEntryExpr, BoolExpr> predicate) {
        return quantify(QuantifierKind.EXISTS, predicate);
    }
    public BoolExpr all(Function<ValuePolicyEntryExpr, BoolExpr> predicate) {
        return quantify(QuantifierKind.ALL, predicate);
    }
    public BoolExpr none(Function<ValuePolicyEntryExpr, BoolExpr> predicate) {
        return quantify(QuantifierKind.NONE, predicate);
    }
    private BoolExpr quantify(
            QuantifierKind kind, Function<ValuePolicyEntryExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return BinderScope.bind(variable -> {
            var entry = new ValuePolicyEntryExpr(new TypedVariableNode(
                    variable, LedgerTypeAuthority.VALUE_POLICY_ENTRY));
            return new BoolExpr(new ListQuantifierNode(node,
                    LedgerTypeAuthority.VALUE_POLICY_ENTRY, kind, variable,
                    predicate.apply(entry).node()));
        });
    }
}
