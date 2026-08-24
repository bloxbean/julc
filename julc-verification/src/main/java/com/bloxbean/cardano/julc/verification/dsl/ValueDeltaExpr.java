package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.ValueArithmeticNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.ValueRelationNode;

import java.util.List;
import java.util.Objects;

/** A symbolic multi-asset delta; it is deliberately not a ledger-valid output Value. */
public record ValueDeltaExpr(PropertyNode node) implements Expr {
    public ValueDeltaExpr { node = Objects.requireNonNull(node, "node"); }
    public ValuePolicyEntriesExpr rawPolicies() {
        return ValueAlgebra.rawPolicies(node, LedgerTypeAuthority.VALUE_DELTA);
    }
    public IntegerExpr quantityFirst(LedgerByteAliasExpr policy, LedgerByteAliasExpr token) {
        return ValueAlgebra.quantityFirst(node, LedgerTypeAuthority.VALUE_DELTA, policy, token);
    }
    public TypedOptionExpr quantitySumStrict(
            LedgerByteAliasExpr policy, LedgerByteAliasExpr token) {
        return ValueAlgebra.quantitySumStrict(
                node, LedgerTypeAuthority.VALUE_DELTA, policy, token);
    }
    public BoolExpr structurallyEquals(ValueDeltaExpr other) {
        return relation(ValueRelationNode.ValueRelationKind.STRUCTURAL_EQ, other);
    }
    public BoolExpr extensionallyEquals(ValueDeltaExpr other) {
        return relation(ValueRelationNode.ValueRelationKind.EXTENSIONAL_EQ, other);
    }
    public BoolExpr pointwiseLe(ValueDeltaExpr other) {
        return relation(ValueRelationNode.ValueRelationKind.LE, other);
    }
    public BoolExpr pointwiseLt(ValueDeltaExpr other) {
        return relation(ValueRelationNode.ValueRelationKind.LT, other);
    }
    public BoolExpr pointwiseGe(ValueDeltaExpr other) {
        return relation(ValueRelationNode.ValueRelationKind.GE, other);
    }
    public BoolExpr pointwiseGt(ValueDeltaExpr other) {
        return relation(ValueRelationNode.ValueRelationKind.GT, other);
    }
    public BoolExpr extensionallyEquals(LedgerValueExpr other) {
        Objects.requireNonNull(other, "other");
        return ValueAlgebra.relation(ValueRelationNode.ValueRelationKind.EXTENSIONAL_EQ,
                node, LedgerTypeAuthority.VALUE_DELTA,
                other.node(), LedgerTypeAuthority.VALUE);
    }
    public BoolExpr extensionallyEquals(LedgerMintValueExpr other) {
        Objects.requireNonNull(other, "other");
        return ValueAlgebra.relation(ValueRelationNode.ValueRelationKind.EXTENSIONAL_EQ,
                node, LedgerTypeAuthority.VALUE_DELTA,
                other.node(), LedgerTypeAuthority.MINT_VALUE);
    }
    public ValueDeltaOptionExpr plus(ValueDeltaExpr other) {
        Objects.requireNonNull(other, "other");
        return ValueAlgebra.arithmetic(ValueArithmeticNode.ValueArithmeticKind.ADD,
                List.of(node, other.node), List.of(
                        LedgerTypeAuthority.VALUE_DELTA, LedgerTypeAuthority.VALUE_DELTA));
    }
    public ValueDeltaOptionExpr negate() {
        return ValueAlgebra.arithmetic(ValueArithmeticNode.ValueArithmeticKind.NEGATE,
                List.of(node), List.of(LedgerTypeAuthority.VALUE_DELTA));
    }
    public ValueDeltaOptionExpr scale(IntegerExpr factor) {
        Objects.requireNonNull(factor, "factor");
        if (!(factor.node() instanceof com.bloxbean.cardano.julc.verification.dsl.ir.LiteralNode
                literal) || literal.resultType()
                != com.bloxbean.cardano.julc.verification.dsl.ir.DslType.INTEGER) {
            throw new IllegalArgumentException(
                    "ValueDelta scale currently requires a canonical integer literal");
        }
        return ValueAlgebra.arithmetic(ValueArithmeticNode.ValueArithmeticKind.SCALE,
                List.of(node, factor.node()), List.of(
                        LedgerTypeAuthority.VALUE_DELTA, LedgerTypeAuthority.INTEGER));
    }
    private BoolExpr relation(ValueRelationNode.ValueRelationKind kind, ValueDeltaExpr other) {
        Objects.requireNonNull(other, "other");
        return ValueAlgebra.relation(kind, node, LedgerTypeAuthority.VALUE_DELTA,
                other.node, LedgerTypeAuthority.VALUE_DELTA);
    }
}
