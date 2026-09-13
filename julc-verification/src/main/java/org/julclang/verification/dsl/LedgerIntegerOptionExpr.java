package org.julclang.verification.dsl;

import org.julclang.verification.dsl.ir.OptionExistsNode;
import org.julclang.verification.dsl.ir.OptionState;
import org.julclang.verification.dsl.ir.OptionStateNode;
import org.julclang.verification.dsl.ir.PropertyNode;
import org.julclang.verification.dsl.ir.TypedVariableNode;

import java.util.Objects;
import java.util.function.Function;

/** Guarded optional integer used by certificate deposits and refunds. */
public record LedgerIntegerOptionExpr(PropertyNode node) implements Expr {
    public LedgerIntegerOptionExpr { node = Objects.requireNonNull(node, "node"); }

    public BoolExpr isPresent() { return state(OptionState.PRESENT); }
    public BoolExpr isEmpty() { return state(OptionState.EMPTY); }

    public BoolExpr exists(Function<IntegerExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return BinderScope.bind(variable -> new BoolExpr(new OptionExistsNode(
                node, variable, LedgerTypeAuthority.INTEGER,
                predicate.apply(new IntegerExpr(new TypedVariableNode(
                        variable, LedgerTypeAuthority.INTEGER))).node())));
    }

    private BoolExpr state(OptionState state) {
        return new BoolExpr(new OptionStateNode(
                node, LedgerTypeAuthority.INTEGER, state));
    }
}
