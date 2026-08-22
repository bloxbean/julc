package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.LedgerVariantFieldNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.LedgerVariantIsNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.LedgerVariantWhenNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.TypedEqualityNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.TypedVariableNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.TxCertKind;
import com.bloxbean.cardano.julc.verification.dsl.ir.TxCertKindNode;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Symbolic pinned V3 transaction certificate. */
public record TxCertExpr(PropertyNode node) implements Expr {
    public TxCertExpr { node = Objects.requireNonNull(node, "node"); }

    public BoolExpr isKind(TxCertKind kind) {
        Objects.requireNonNull(kind, "kind");
        if (!(node instanceof com.bloxbean.cardano.julc.verification.dsl.ir.RootNode)) {
            return new BoolExpr(new LedgerVariantIsNode(
                    node, LedgerTypeAuthority.TX_CERT, constructor(kind)));
        }
        return new BoolExpr(new TxCertKindNode(node, kind));
    }

    public BoolExpr eq(TxCertExpr other) { return equality(other, false); }
    public BoolExpr ne(TxCertExpr other) { return equality(other, true); }

    public BoolExpr whenRegStaking(
            BiFunction<LedgerCredentialExpr, LedgerIntegerOptionExpr, BoolExpr> predicate) {
        return when("TxCertRegStaking", bound -> predicate.apply(
                credential(bound, "TxCertRegStaking", "credential"),
                optionalInteger(bound, "TxCertRegStaking", "deposit")).node());
    }

    public BoolExpr whenUnRegStaking(
            BiFunction<LedgerCredentialExpr, LedgerIntegerOptionExpr, BoolExpr> predicate) {
        return when("TxCertUnRegStaking", bound -> predicate.apply(
                credential(bound, "TxCertUnRegStaking", "credential"),
                optionalInteger(bound, "TxCertUnRegStaking", "refund")).node());
    }

    public BoolExpr whenDelegStaking(
            BiFunction<LedgerCredentialExpr, LedgerDelegateeExpr, BoolExpr> predicate) {
        return when("TxCertDelegStaking", bound -> predicate.apply(
                credential(bound, "TxCertDelegStaking", "credential"),
                delegatee(bound, "TxCertDelegStaking")).node());
    }

    public BoolExpr whenRegDeleg(
            TriFunction<LedgerCredentialExpr, LedgerDelegateeExpr, IntegerExpr, BoolExpr>
                    predicate) {
        return when("TxCertRegDeleg", bound -> predicate.apply(
                credential(bound, "TxCertRegDeleg", "credential"),
                delegatee(bound, "TxCertRegDeleg"),
                integer(bound, "TxCertRegDeleg", "deposit")).node());
    }

    public BoolExpr whenRegDRep(
            BiFunction<LedgerDRepCredentialExpr, IntegerExpr, BoolExpr> predicate) {
        return when("TxCertRegDRep", bound -> predicate.apply(
                drepCredential(bound, "TxCertRegDRep"),
                integer(bound, "TxCertRegDRep", "deposit")).node());
    }

    public BoolExpr whenUpdateDRep(
            Function<LedgerDRepCredentialExpr, BoolExpr> predicate) {
        return when("TxCertUpdateDRep", bound -> predicate.apply(
                drepCredential(bound, "TxCertUpdateDRep")).node());
    }

    public BoolExpr whenUnRegDRep(
            BiFunction<LedgerDRepCredentialExpr, IntegerExpr, BoolExpr> predicate) {
        return when("TxCertUnRegDRep", bound -> predicate.apply(
                drepCredential(bound, "TxCertUnRegDRep"),
                integer(bound, "TxCertUnRegDRep", "refund")).node());
    }

    public BoolExpr whenPoolRegister(
            BiFunction<LedgerByteAliasExpr, LedgerByteAliasExpr, BoolExpr> predicate) {
        return when("TxCertPoolRegister", bound -> predicate.apply(
                publicKeyHash(bound, "TxCertPoolRegister", "pool"),
                publicKeyHash(bound, "TxCertPoolRegister", "vrf")).node());
    }

    public BoolExpr whenPoolRetire(
            BiFunction<LedgerByteAliasExpr, IntegerExpr, BoolExpr> predicate) {
        return when("TxCertPoolRetire", bound -> predicate.apply(
                publicKeyHash(bound, "TxCertPoolRetire", "pool"),
                integer(bound, "TxCertPoolRetire", "epoch")).node());
    }

    public BoolExpr whenAuthHotCommittee(
            BiFunction<LedgerColdCommitteeCredentialExpr,
                    LedgerHotCommitteeCredentialExpr, BoolExpr> predicate) {
        return when("TxCertAuthHotCommittee", bound -> predicate.apply(
                coldCredential(bound, "TxCertAuthHotCommittee"),
                hotCredential(bound, "TxCertAuthHotCommittee")).node());
    }

    public BoolExpr whenResignColdCommittee(
            Function<LedgerColdCommitteeCredentialExpr, BoolExpr> predicate) {
        return when("TxCertResignColdCommittee", bound -> predicate.apply(
                coldCredential(bound, "TxCertResignColdCommittee")).node());
    }

    private BoolExpr equality(TxCertExpr other, boolean negated) {
        Objects.requireNonNull(other, "other");
        return new BoolExpr(new TypedEqualityNode(
                node, other.node, LedgerTypeAuthority.TX_CERT, negated));
    }

    private BoolExpr when(
            String constructor, Function<TypedVariableNode, PropertyNode> body) {
        Objects.requireNonNull(body, "body");
        return BinderScope.bind(variable -> {
            var bound = new TypedVariableNode(variable, LedgerTypeAuthority.TX_CERT);
            return new BoolExpr(new LedgerVariantWhenNode(node,
                    LedgerTypeAuthority.TX_CERT, constructor, variable,
                    body.apply(bound)));
        });
    }

    private static LedgerCredentialExpr credential(
            TypedVariableNode bound, String constructor, String field) {
        return new LedgerCredentialExpr(new LedgerVariantFieldNode(bound,
                LedgerTypeAuthority.TX_CERT, constructor, field,
                LedgerTypeAuthority.CREDENTIAL));
    }

    private static LedgerIntegerOptionExpr optionalInteger(
            TypedVariableNode bound, String constructor, String field) {
        return new LedgerIntegerOptionExpr(new LedgerVariantFieldNode(bound,
                LedgerTypeAuthority.TX_CERT, constructor, field,
                new com.bloxbean.cardano.julc.verification.dsl.type.OptionalTypeRef(
                        LedgerTypeAuthority.INTEGER)));
    }

    private static LedgerDRepCredentialExpr drepCredential(
            TypedVariableNode bound, String constructor) {
        return new LedgerDRepCredentialExpr(new LedgerVariantFieldNode(bound,
                LedgerTypeAuthority.TX_CERT, constructor, "credential",
                LedgerTypeAuthority.CREDENTIAL));
    }

    private static LedgerColdCommitteeCredentialExpr coldCredential(
            TypedVariableNode bound, String constructor) {
        return new LedgerColdCommitteeCredentialExpr(new LedgerVariantFieldNode(bound,
                LedgerTypeAuthority.TX_CERT, constructor, "coldCredential",
                LedgerTypeAuthority.CREDENTIAL));
    }

    private static LedgerHotCommitteeCredentialExpr hotCredential(
            TypedVariableNode bound, String constructor) {
        return new LedgerHotCommitteeCredentialExpr(new LedgerVariantFieldNode(bound,
                LedgerTypeAuthority.TX_CERT, constructor, "hotCredential",
                LedgerTypeAuthority.CREDENTIAL));
    }

    private static LedgerDelegateeExpr delegatee(
            TypedVariableNode bound, String constructor) {
        return new LedgerDelegateeExpr(new LedgerVariantFieldNode(bound,
                LedgerTypeAuthority.TX_CERT, constructor, "delegatee",
                LedgerTypeAuthority.DELEGATEE));
    }

    private static IntegerExpr integer(
            TypedVariableNode bound, String constructor, String field) {
        return new IntegerExpr(new LedgerVariantFieldNode(bound,
                LedgerTypeAuthority.TX_CERT, constructor, field,
                LedgerTypeAuthority.INTEGER));
    }

    private static LedgerByteAliasExpr publicKeyHash(
            TypedVariableNode bound, String constructor, String field) {
        return new LedgerByteAliasExpr(new LedgerVariantFieldNode(bound,
                LedgerTypeAuthority.TX_CERT, constructor, field,
                LedgerTypeAuthority.PUB_KEY_HASH), LedgerTypeAuthority.PUB_KEY_HASH);
    }

    private static String constructor(TxCertKind kind) {
        return switch (kind) {
            case REG_STAKING -> "TxCertRegStaking";
            case UNREG_STAKING -> "TxCertUnRegStaking";
            case DELEG_STAKING -> "TxCertDelegStaking";
            case REG_DELEG -> "TxCertRegDeleg";
            case REG_DREP -> "TxCertRegDRep";
            case UPDATE_DREP -> "TxCertUpdateDRep";
            case UNREG_DREP -> "TxCertUnRegDRep";
            case POOL_REGISTER -> "TxCertPoolRegister";
            case POOL_RETIRE -> "TxCertPoolRetire";
            case AUTH_HOT_COMMITTEE -> "TxCertAuthHotCommittee";
            case RESIGN_COLD_COMMITTEE -> "TxCertResignColdCommittee";
        };
    }

    @FunctionalInterface
    public interface TriFunction<A, B, C, R> {
        R apply(A first, B second, C third);
    }
}
