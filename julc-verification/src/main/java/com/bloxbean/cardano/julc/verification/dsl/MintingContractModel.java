package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.DslType;
import com.bloxbean.cardano.julc.verification.dsl.ir.RootNode;

/** Stable symbolic roots for a selected minting interface. */
public final class MintingContractModel {
    private final ContextExpr context = new ContextExpr(
            new RootNode("context", DslType.SCRIPT_CONTEXT));
    private final PolicyIdExpr ownPolicy = new PolicyIdExpr(
            new RootNode("ownPolicy", DslType.POLICY_ID));
    private final BoolExpr strictRedeemer = new BoolExpr(
            new RootNode("redeemerStrictlyDecodes", DslType.BOOL));
    private final BoolExpr execution = new BoolExpr(
            new RootNode("exactUplcSucceeds", DslType.BOOL));
    private final BoolExpr ledgerValid = new BoolExpr(
            new RootNode("validMintingContext", DslType.BOOL));

    public ContextExpr context() { return context; }
    public PolicyIdExpr ownPolicy() { return ownPolicy; }
    public BoolExpr redeemerStrictlyDecodes() { return strictRedeemer; }
    public BoolExpr exactUplcSucceeds() { return execution; }
    public BoolExpr validMintingContext() { return ledgerValid; }
}
