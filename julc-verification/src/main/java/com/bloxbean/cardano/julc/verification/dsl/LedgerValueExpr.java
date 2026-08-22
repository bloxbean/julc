package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.LedgerHelperNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;

import java.util.List;
import java.util.Objects;
import com.bloxbean.cardano.julc.verification.dsl.ir.ValueRelationNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.ValueArithmeticNode;

/** Bridge to the already-reviewed lovelace-only value surface. */
public record LedgerValueExpr(PropertyNode node) implements Expr {
    public LedgerValueExpr { node = Objects.requireNonNull(node, "node"); }
    public IntegerExpr lovelace() {
        return new IntegerExpr(new LedgerHelperNode(
                LedgerHelperNode.LedgerHelperKind.LOVELACE_OF,
                List.of(node), LedgerTypeAuthority.INTEGER));
    }
    public ValuePolicyEntriesExpr rawPolicies() {
        return ValueAlgebra.rawPolicies(node, LedgerTypeAuthority.VALUE);
    }
    public IntegerExpr quantityFirst(
            LedgerByteAliasExpr policy, LedgerByteAliasExpr token) {
        return ValueAlgebra.quantityFirst(node, LedgerTypeAuthority.VALUE, policy, token);
    }
    public TypedOptionExpr quantitySumStrict(
            LedgerByteAliasExpr policy, LedgerByteAliasExpr token) {
        return ValueAlgebra.quantitySumStrict(node, LedgerTypeAuthority.VALUE, policy, token);
    }
    public BoolExpr structurallyEquals(LedgerValueExpr other) {
        return relation(ValueRelationNode.ValueRelationKind.STRUCTURAL_EQ, other);
    }
    public BoolExpr extensionallyEquals(LedgerValueExpr other) {
        return relation(ValueRelationNode.ValueRelationKind.EXTENSIONAL_EQ, other);
    }
    public BoolExpr extensionallyEquals(ValueDeltaExpr other) {
        Objects.requireNonNull(other, "other");
        return ValueAlgebra.relation(ValueRelationNode.ValueRelationKind.EXTENSIONAL_EQ,
                node, LedgerTypeAuthority.VALUE,
                other.node(), LedgerTypeAuthority.VALUE_DELTA);
    }
    public BoolExpr pointwiseLe(LedgerValueExpr other) {
        return relation(ValueRelationNode.ValueRelationKind.LE, other);
    }
    public BoolExpr pointwiseLt(LedgerValueExpr other) {
        return relation(ValueRelationNode.ValueRelationKind.LT, other);
    }
    public BoolExpr pointwiseGe(LedgerValueExpr other) {
        return relation(ValueRelationNode.ValueRelationKind.GE, other);
    }
    public BoolExpr pointwiseGt(LedgerValueExpr other) {
        return relation(ValueRelationNode.ValueRelationKind.GT, other);
    }
    public ValueDeltaOptionExpr checkedDelta() {
        return ValueAlgebra.arithmetic(ValueArithmeticNode.ValueArithmeticKind.VALIDATE,
                List.of(node), List.of(LedgerTypeAuthority.VALUE));
    }
    private BoolExpr relation(ValueRelationNode.ValueRelationKind kind, LedgerValueExpr other) {
        Objects.requireNonNull(other, "other");
        return ValueAlgebra.relation(kind, node, LedgerTypeAuthority.VALUE,
                other.node, LedgerTypeAuthority.VALUE);
    }
}
