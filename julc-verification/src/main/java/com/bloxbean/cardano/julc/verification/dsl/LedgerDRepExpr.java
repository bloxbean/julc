package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;

import java.util.Objects;
import java.util.function.Function;

/** Closed pinned V3 DRep sum. */
public record LedgerDRepExpr(PropertyNode node) implements Expr {
    public LedgerDRepExpr { node = Objects.requireNonNull(node, "node"); }

    public BoolExpr isCredential() { return is("DRep"); }
    public BoolExpr isAlwaysAbstain() { return is("DRepAlwaysAbstain"); }
    public BoolExpr isAlwaysNoConfidence() { return is("DRepAlwaysNoConfidence"); }

    public BoolExpr whenCredential(
            Function<LedgerDRepCredentialExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return BinderScope.bind(variable -> {
            var bound = new TypedVariableNode(variable, LedgerTypeAuthority.DREP);
            var credential = new LedgerDRepCredentialExpr(new LedgerVariantFieldNode(
                    bound, LedgerTypeAuthority.DREP, "DRep", "credential",
                    LedgerTypeAuthority.CREDENTIAL));
            return new BoolExpr(new LedgerVariantWhenNode(node,
                    LedgerTypeAuthority.DREP, "DRep", variable,
                    predicate.apply(credential).node()));
        });
    }

    private BoolExpr is(String constructor) {
        return new BoolExpr(new LedgerVariantIsNode(
                node, LedgerTypeAuthority.DREP, constructor));
    }
}
