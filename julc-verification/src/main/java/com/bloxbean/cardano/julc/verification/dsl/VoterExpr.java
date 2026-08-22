package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import java.util.Objects;
import java.util.function.Function;

/** Guarded symbolic pinned V3 voter. */
public record VoterExpr(PropertyNode node) implements Expr {
    public VoterExpr { node = Objects.requireNonNull(node, "node"); }
    public TypedValueExpr typed() { return new TypedValueExpr(node, LedgerTypeAuthority.VOTER); }
    public BoolExpr whenCommittee(Function<LedgerHotCommitteeCredentialExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return when("CommitteeVoter", bound -> predicate.apply(
                new LedgerHotCommitteeCredentialExpr(field(bound, "CommitteeVoter",
                        "credential", LedgerTypeAuthority.CREDENTIAL))).node());
    }
    public BoolExpr whenDRep(Function<LedgerDRepCredentialExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return when("DRepVoter", bound -> predicate.apply(new LedgerDRepCredentialExpr(
                field(bound, "DRepVoter", "credential", LedgerTypeAuthority.CREDENTIAL))).node());
    }
    public BoolExpr whenStakePool(Function<LedgerByteAliasExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return when("StakePoolVoter", bound -> predicate.apply(new LedgerByteAliasExpr(
                field(bound, "StakePoolVoter", "pool", LedgerTypeAuthority.PUB_KEY_HASH),
                LedgerTypeAuthority.PUB_KEY_HASH)).node());
    }
    private BoolExpr when(String constructor, Function<TypedVariableNode, PropertyNode> body) {
        return BinderScope.bind(name -> {
            var bound = new TypedVariableNode(name, LedgerTypeAuthority.VOTER);
            return new BoolExpr(new LedgerVariantWhenNode(node, LedgerTypeAuthority.VOTER,
                    constructor, name, body.apply(bound)));
        });
    }
    private static PropertyNode field(TypedVariableNode bound, String constructor,
            String name, com.bloxbean.cardano.julc.verification.dsl.type.VerificationTypeRef type) {
        return new LedgerVariantFieldNode(bound, LedgerTypeAuthority.VOTER,
                constructor, name, type);
    }
}
