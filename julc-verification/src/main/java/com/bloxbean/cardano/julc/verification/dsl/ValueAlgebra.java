package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import com.bloxbean.cardano.julc.verification.dsl.type.OptionalTypeRef;
import com.bloxbean.cardano.julc.verification.dsl.type.VerificationTypeRef;

import java.util.List;
import java.util.Objects;

/** Package-local constructors shared by the distinct Value/MintValue/ValueDelta APIs. */
final class ValueAlgebra {
    private ValueAlgebra() { }

    static ValuePolicyEntriesExpr rawPolicies(PropertyNode value, VerificationTypeRef type) {
        return new ValuePolicyEntriesExpr(new ValueEntriesNode(
                value, type, LedgerTypeAuthority.VALUE_POLICY_ENTRY));
    }

    static IntegerExpr quantityFirst(
            PropertyNode value, VerificationTypeRef type,
            LedgerByteAliasExpr policy, LedgerByteAliasExpr token) {
        requireAliases(policy, token);
        return new IntegerExpr(new ValueQuantityNode(
                ValueQuantityNode.ValueQuantityKind.FIRST_MATCH,
                value, type, policy.node(), token.node()));
    }

    static TypedOptionExpr quantitySumStrict(
            PropertyNode value, VerificationTypeRef type,
            LedgerByteAliasExpr policy, LedgerByteAliasExpr token) {
        requireAliases(policy, token);
        return new TypedOptionExpr(new ValueQuantityNode(
                ValueQuantityNode.ValueQuantityKind.STRICT_SUMMED,
                value, type, policy.node(), token.node()), LedgerTypeAuthority.INTEGER);
    }

    static BoolExpr relation(
            ValueRelationNode.ValueRelationKind relation,
            PropertyNode left, VerificationTypeRef leftType,
            PropertyNode right, VerificationTypeRef rightType) {
        return new BoolExpr(new ValueRelationNode(
                relation, left, leftType, right, rightType));
    }

    static ValueDeltaOptionExpr arithmetic(
            ValueArithmeticNode.ValueArithmeticKind operation,
            List<PropertyNode> arguments,
            List<VerificationTypeRef> types) {
        return new ValueDeltaOptionExpr(new ValueArithmeticNode(
                operation, arguments, types,
                new OptionalTypeRef(LedgerTypeAuthority.VALUE_DELTA)));
    }

    static void requireAliases(
            LedgerByteAliasExpr policy, LedgerByteAliasExpr token) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(token, "token");
        if (!policy.aliasType().equals(LedgerTypeAuthority.CURRENCY_SYMBOL)) {
            throw new IllegalArgumentException("Policy must be a CurrencySymbol");
        }
        if (!token.aliasType().equals(LedgerTypeAuthority.TOKEN_NAME)) {
            throw new IllegalArgumentException("Token must be a TokenName");
        }
    }
}
