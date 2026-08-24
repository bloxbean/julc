package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.ValueRelationNode;

import java.util.Objects;

/** V3 mint value, kept distinct from an output Value despite its representation alias. */
public record LedgerMintValueExpr(PropertyNode node) implements Expr {
    public LedgerMintValueExpr { node = Objects.requireNonNull(node, "node"); }
    public ValuePolicyEntriesExpr rawPolicies() {
        return ValueAlgebra.rawPolicies(node, LedgerTypeAuthority.MINT_VALUE);
    }
    public IntegerExpr quantityFirst(
            LedgerByteAliasExpr policy, LedgerByteAliasExpr token) {
        return ValueAlgebra.quantityFirst(
                node, LedgerTypeAuthority.MINT_VALUE, policy, token);
    }
    public TypedOptionExpr quantitySumStrict(
            LedgerByteAliasExpr policy, LedgerByteAliasExpr token) {
        return ValueAlgebra.quantitySumStrict(
                node, LedgerTypeAuthority.MINT_VALUE, policy, token);
    }
    public BoolExpr structurallyEquals(LedgerMintValueExpr other) {
        return relation(ValueRelationNode.ValueRelationKind.STRUCTURAL_EQ, other);
    }
    public BoolExpr extensionallyEquals(LedgerMintValueExpr other) {
        return relation(ValueRelationNode.ValueRelationKind.EXTENSIONAL_EQ, other);
    }
    public BoolExpr pointwiseLe(LedgerMintValueExpr other) {
        return relation(ValueRelationNode.ValueRelationKind.LE, other);
    }
    public BoolExpr pointwiseLt(LedgerMintValueExpr other) {
        return relation(ValueRelationNode.ValueRelationKind.LT, other);
    }
    public BoolExpr pointwiseGe(LedgerMintValueExpr other) {
        return relation(ValueRelationNode.ValueRelationKind.GE, other);
    }
    public BoolExpr pointwiseGt(LedgerMintValueExpr other) {
        return relation(ValueRelationNode.ValueRelationKind.GT, other);
    }
    public ValueDeltaOptionExpr checkedDelta() {
        return ValueAlgebra.arithmetic(
                com.bloxbean.cardano.julc.verification.dsl.ir.ValueArithmeticNode
                        .ValueArithmeticKind.VALIDATE,
                java.util.List.of(node), java.util.List.of(LedgerTypeAuthority.MINT_VALUE));
    }
    private BoolExpr relation(
            ValueRelationNode.ValueRelationKind relation, LedgerMintValueExpr other) {
        Objects.requireNonNull(other, "other");
        return ValueAlgebra.relation(relation, node, LedgerTypeAuthority.MINT_VALUE,
                other.node, LedgerTypeAuthority.MINT_VALUE);
    }
}
