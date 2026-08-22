package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import com.bloxbean.cardano.julc.verification.dsl.type.*;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Guarded pinned V3 governance action; raw changed-parameter/quorum fields stay hidden. */
public record GovernanceActionExpr(PropertyNode node) implements Expr {
    public GovernanceActionExpr { node = Objects.requireNonNull(node, "node"); }
    public BoolExpr whenParameterChange(BiFunction<TypedOptionExpr, TypedOptionExpr, BoolExpr> p) {
        return when("ParameterChange", b -> p.apply(optional(b,"ParameterChange","previous",
                LedgerTypeAuthority.GOVERNANCE_ACTION_ID), optional(b,"ParameterChange",
                "constitutionScript", LedgerTypeAuthority.SCRIPT_HASH)).node());
    }
    public BoolExpr whenParameterChange(ParameterChangeFunction p) {
        Objects.requireNonNull(p, "predicate");
        return when("ParameterChange", b -> p.apply(
                optional(b,"ParameterChange","previous",
                        LedgerTypeAuthority.GOVERNANCE_ACTION_ID),
                new ChangedParametersExpr(b),
                optional(b,"ParameterChange","constitutionScript",
                        LedgerTypeAuthority.SCRIPT_HASH)).node());
    }
    public BoolExpr whenHardFork(BiFunction<TypedOptionExpr, ProtocolVersionExpr, BoolExpr> p) {
        return when("HardForkInitiation", b -> p.apply(optional(b,"HardForkInitiation","previous",
                LedgerTypeAuthority.GOVERNANCE_ACTION_ID), new ProtocolVersionExpr(field(b,
                "HardForkInitiation","version",LedgerTypeAuthority.PROTOCOL_VERSION))).node());
    }
    public BoolExpr whenTreasuryWithdrawals(BiFunction<TypedAssocMapExpr, TypedOptionExpr, BoolExpr> p) {
        var withdrawals = new AssocMapTypeRef(LedgerTypeAuthority.CREDENTIAL, LedgerTypeAuthority.INTEGER);
        return when("TreasuryWithdrawals", b -> p.apply(new TypedAssocMapExpr(field(b,
                "TreasuryWithdrawals","withdrawals",withdrawals), LedgerTypeAuthority.CREDENTIAL,
                LedgerTypeAuthority.INTEGER), optional(b,"TreasuryWithdrawals","constitutionScript",
                LedgerTypeAuthority.SCRIPT_HASH)).node());
    }
    public BoolExpr whenNoConfidence(Function<TypedOptionExpr, BoolExpr> p) {
        return when("NoConfidence", b -> p.apply(optional(b,"NoConfidence","previous",
                LedgerTypeAuthority.GOVERNANCE_ACTION_ID)).node());
    }
    public BoolExpr whenUpdateCommittee(TriFunction<TypedOptionExpr, TypedListExpr,
            TypedAssocMapExpr, BoolExpr> p) {
        var members = new AssocMapTypeRef(LedgerTypeAuthority.CREDENTIAL, LedgerTypeAuthority.INTEGER);
        return when("UpdateCommittee", b -> p.apply(optional(b,"UpdateCommittee","previous",
                LedgerTypeAuthority.GOVERNANCE_ACTION_ID), new TypedListExpr(field(b,
                "UpdateCommittee","oldMembers",new ListTypeRef(LedgerTypeAuthority.CREDENTIAL)),
                LedgerTypeAuthority.CREDENTIAL), new TypedAssocMapExpr(field(b,"UpdateCommittee",
                "newMembers",members), LedgerTypeAuthority.CREDENTIAL,
                LedgerTypeAuthority.INTEGER)).node());
    }
    public BoolExpr whenUpdateCommittee(UpdateCommitteeFunction p) {
        Objects.requireNonNull(p, "predicate");
        var members = new AssocMapTypeRef(
                LedgerTypeAuthority.CREDENTIAL, LedgerTypeAuthority.INTEGER);
        return when("UpdateCommittee", b -> p.apply(
                optional(b,"UpdateCommittee","previous",
                        LedgerTypeAuthority.GOVERNANCE_ACTION_ID),
                new TypedListExpr(field(b,"UpdateCommittee","oldMembers",
                        new ListTypeRef(LedgerTypeAuthority.CREDENTIAL)),
                        LedgerTypeAuthority.CREDENTIAL),
                new TypedAssocMapExpr(field(b,"UpdateCommittee","newMembers",members),
                        LedgerTypeAuthority.CREDENTIAL, LedgerTypeAuthority.INTEGER),
                new QuorumExpr(b)).node());
    }
    public BoolExpr whenNewConstitution(BiFunction<TypedOptionExpr, TypedOptionExpr, BoolExpr> p) {
        return when("NewConstitution", b -> p.apply(optional(b,"NewConstitution","previous",
                LedgerTypeAuthority.GOVERNANCE_ACTION_ID), optional(b,"NewConstitution",
                "constitutionScript",LedgerTypeAuthority.SCRIPT_HASH)).node());
    }
    public BoolExpr isInfo() { return new BoolExpr(new LedgerVariantIsNode(node,
            LedgerTypeAuthority.GOVERNANCE_ACTION, "InfoAction")); }
    private BoolExpr when(String c, Function<TypedVariableNode,PropertyNode> body) {
        return BinderScope.bind(v -> { var b = new TypedVariableNode(v,
                LedgerTypeAuthority.GOVERNANCE_ACTION); return new BoolExpr(new LedgerVariantWhenNode(
                node, LedgerTypeAuthority.GOVERNANCE_ACTION,c,v,body.apply(b))); });
    }
    private static PropertyNode field(TypedVariableNode b,String c,String n,VerificationTypeRef t) {
        return new LedgerVariantFieldNode(b,LedgerTypeAuthority.GOVERNANCE_ACTION,c,n,t);
    }
    private static TypedOptionExpr optional(TypedVariableNode b,String c,String n,VerificationTypeRef t) {
        return new TypedOptionExpr(field(b,c,n,new OptionalTypeRef(t)),t);
    }
    @FunctionalInterface public interface TriFunction<A,B,C,R> { R apply(A a,B b,C c); }
    @FunctionalInterface public interface ParameterChangeFunction {
        BoolExpr apply(TypedOptionExpr previous, ChangedParametersExpr changedParameters,
                       TypedOptionExpr constitutionScript);
    }
    @FunctionalInterface public interface UpdateCommitteeFunction {
        BoolExpr apply(TypedOptionExpr previous, TypedListExpr oldMembers,
                       TypedAssocMapExpr newMembers, QuorumExpr quorum);
    }
}
