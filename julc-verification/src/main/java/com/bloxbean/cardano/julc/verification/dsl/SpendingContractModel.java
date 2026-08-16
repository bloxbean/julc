package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.DslType;
import com.bloxbean.cardano.julc.verification.dsl.ir.RootNode;

/** Stable symbolic roots wrapped by generated contract-specific metamodels. */
public final class SpendingContractModel {
    private final DatumExpr datum = new DatumExpr(new RootNode("datum", DslType.DATA));
    private final ContextExpr context = new ContextExpr(
            new RootNode("context", DslType.SCRIPT_CONTEXT));
    private final BoolExpr execution = new BoolExpr(
            new RootNode("exactUplcSucceeds", DslType.BOOL));
    private final BoolExpr ledgerValid = new BoolExpr(
            new RootNode("validSpendingContext", DslType.BOOL));

    public DatumExpr datum() { return datum; }
    public ContextExpr context() { return context; }
    public BoolExpr exactUplcSucceeds() { return execution; }
    public BoolExpr validSpendingContext() { return ledgerValid; }
}
