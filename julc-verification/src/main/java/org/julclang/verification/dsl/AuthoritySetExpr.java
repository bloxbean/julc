package org.julclang.verification.dsl;

import org.julclang.verification.dsl.ir.AuthorizationNode;
import org.julclang.verification.dsl.ir.AuthorizationRelation;
import org.julclang.verification.dsl.ir.PropertyNode;

import java.util.Objects;

/** Authority collection with distinct-identity authorization relations. */
public record AuthoritySetExpr(PropertyNode node) implements Expr {
    public AuthoritySetExpr {
        node = Objects.requireNonNull(node, "node");
    }

    public BoolExpr anySigned() {
        return relation(AuthorizationRelation.ANY_SIGNED, null);
    }

    public BoolExpr allSigned() {
        return relation(AuthorizationRelation.ALL_SIGNED, null);
    }

    public BoolExpr noneSigned() {
        return relation(AuthorizationRelation.NONE_SIGNED, null);
    }

    public BoolExpr atLeastSigned(long threshold) {
        return relation(AuthorizationRelation.AT_LEAST_SIGNED,
                Long.toString(threshold));
    }

    public BoolExpr exactlySigned(long threshold) {
        return relation(AuthorizationRelation.EXACTLY_SIGNED,
                Long.toString(threshold));
    }

    public BoolExpr noUnexpectedSigners() {
        return relation(AuthorizationRelation.NO_UNEXPECTED_SIGNERS, null);
    }

    public BoolExpr exactSignerSet() {
        return relation(AuthorizationRelation.EXACT_SIGNER_SET, null);
    }

    private BoolExpr relation(AuthorizationRelation relation, String threshold) {
        return new BoolExpr(new AuthorizationNode(relation, node, threshold));
    }
}
