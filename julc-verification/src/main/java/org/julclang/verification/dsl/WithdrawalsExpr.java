package org.julclang.verification.dsl;

import org.julclang.verification.dsl.ir.DslType;
import org.julclang.verification.dsl.ir.ExistsNode;
import org.julclang.verification.dsl.ir.PropertyNode;
import org.julclang.verification.dsl.ir.RootNode;

import java.util.Objects;
import java.util.function.Function;

/** Raw V3 withdrawal association list; duplicate credential entries remain observable. */
public record WithdrawalsExpr(PropertyNode node) implements Expr {
    public WithdrawalsExpr { node = Objects.requireNonNull(node, "node"); }

    public BoolExpr exists(Function<WithdrawalEntryExpr, BoolExpr> predicate) {
        String variable = "withdrawal";
        var value = new WithdrawalEntryExpr(
                new RootNode(variable, DslType.WITHDRAWAL_ENTRY));
        return new BoolExpr(new ExistsNode(node, variable, predicate.apply(value).node()));
    }
}
