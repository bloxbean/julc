package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.LedgerFieldNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.LedgerHelperNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;

import java.util.Objects;
import java.util.List;

public record LedgerContextExpr(PropertyNode node) implements Expr {
    public LedgerContextExpr { node = Objects.requireNonNull(node, "node"); }

    public LedgerTxInfoExpr txInfo() {
        return new LedgerTxInfoExpr(new LedgerFieldNode(node,
                LedgerTypeAuthority.SCRIPT_CONTEXT, "txInfo", LedgerTypeAuthority.TX_INFO));
    }
    public LedgerScriptPurposeExpr scriptPurpose() {
        return new LedgerScriptPurposeExpr(new LedgerHelperNode(
                LedgerHelperNode.LedgerHelperKind.CURRENT_SCRIPT_PURPOSE,
                List.of(node), LedgerTypeAuthority.SCRIPT_PURPOSE));
    }
    public LedgerValueExpr valueSpent() {
        return new LedgerValueExpr(new LedgerHelperNode(
                LedgerHelperNode.LedgerHelperKind.VALUE_SPENT,
                List.of(node), LedgerTypeAuthority.VALUE));
    }
    public LedgerValueExpr valueProduced() {
        return new LedgerValueExpr(new LedgerHelperNode(
                LedgerHelperNode.LedgerHelperKind.VALUE_PRODUCED,
                List.of(node), LedgerTypeAuthority.VALUE));
    }
    public BoolExpr isBalanced() {
        return new BoolExpr(new LedgerHelperNode(
                LedgerHelperNode.LedgerHelperKind.IS_BALANCED,
                List.of(node), LedgerTypeAuthority.BOOL));
    }
}
