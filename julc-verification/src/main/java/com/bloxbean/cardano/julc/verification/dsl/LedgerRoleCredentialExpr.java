package com.bloxbean.cardano.julc.verification.dsl;

import java.util.function.Function;

/** A role-preserving view of an aliased pinned V2 credential payload. */
public sealed interface LedgerRoleCredentialExpr extends Expr permits
        LedgerDRepCredentialExpr, LedgerColdCommitteeCredentialExpr,
        LedgerHotCommitteeCredentialExpr {
    default LedgerCredentialExpr credential() {
        return new LedgerCredentialExpr(node());
    }
    default BoolExpr isPubKey() { return credential().isPubKey(); }
    default BoolExpr isScript() { return credential().isScript(); }
    default BoolExpr whenPubKey(Function<TypedValueExpr, BoolExpr> predicate) {
        return credential().whenPubKey(predicate);
    }
    default BoolExpr whenScript(Function<TypedValueExpr, BoolExpr> predicate) {
        return credential().whenScript(predicate);
    }
}
