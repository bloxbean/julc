package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.DslType;
import com.bloxbean.cardano.julc.verification.dsl.ir.RootNode;

/** Stable symbolic roots for a selected rewarding/withdraw interface. */
public final class RewardingContractModel {
    private final ContextExpr context = new ContextExpr(
            new RootNode("context", DslType.SCRIPT_CONTEXT));
    private final CredentialExpr rewardingCredential = new CredentialExpr(
            new RootNode("rewardingCredential", DslType.CREDENTIAL));
    private final BoolExpr strictRedeemer = new BoolExpr(
            new RootNode("redeemerStrictlyDecodes", DslType.BOOL));

    public ContextExpr context() { return context; }
    public CredentialExpr rewardingCredential() { return rewardingCredential; }
    public BoolExpr redeemerStrictlyDecodes() { return strictRedeemer; }
}
