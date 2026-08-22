package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.DslType;
import com.bloxbean.cardano.julc.verification.dsl.ir.RootNode;

/** Stable symbolic roots for a selected certifying/publish interface. */
public final class CertifyingContractModel {
    private final ContextExpr context = new ContextExpr(
            new RootNode("context", DslType.SCRIPT_CONTEXT));
    private final TxCertExpr certificate = new TxCertExpr(
            new RootNode("certificate", DslType.TX_CERT));
    private final IntegerExpr certificateIndex = new IntegerExpr(
            new RootNode("certificateIndex", DslType.INTEGER));
    private final BoolExpr strictRedeemer = new BoolExpr(
            new RootNode("redeemerStrictlyDecodes", DslType.BOOL));

    public ContextExpr context() { return context; }
    public TxCertExpr certificate() { return certificate; }
    public IntegerExpr certificateIndex() { return certificateIndex; }
    public BoolExpr redeemerStrictlyDecodes() { return strictRedeemer; }
}
