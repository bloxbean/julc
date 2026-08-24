package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import com.bloxbean.cardano.julc.verification.dsl.type.OptionalTypeRef;
import java.util.List;
import java.util.Objects;

/** Strict symbolic pinned V3 proposal procedure. */
public record ProposalProcedureExpr(PropertyNode node) implements Expr {
    public ProposalProcedureExpr { node = Objects.requireNonNull(node, "node"); }
    public IntegerExpr deposit() { return new IntegerExpr(field("deposit", LedgerTypeAuthority.INTEGER)); }
    public LedgerCredentialExpr returnAddress() {
        return new LedgerCredentialExpr(field("returnAddress", LedgerTypeAuthority.CREDENTIAL));
    }
    public GovernanceActionOptionExpr actionStrict() {
        return new GovernanceActionOptionExpr(new LedgerHelperNode(
                LedgerHelperNode.LedgerHelperKind.DECODE_GOVERNANCE_ACTION,
                List.of(node), new OptionalTypeRef(LedgerTypeAuthority.GOVERNANCE_ACTION)));
    }
    public TypedValueExpr typed() { return new TypedValueExpr(node, LedgerTypeAuthority.PROPOSAL_PROCEDURE); }
    private PropertyNode field(String name,
            com.bloxbean.cardano.julc.verification.dsl.type.VerificationTypeRef type) {
        return new LedgerFieldNode(node, LedgerTypeAuthority.PROPOSAL_PROCEDURE, name, type);
    }
}
