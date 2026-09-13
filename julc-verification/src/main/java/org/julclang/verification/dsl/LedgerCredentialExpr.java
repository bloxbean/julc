package org.julclang.verification.dsl;

import org.julclang.verification.dsl.ir.*;

import java.util.Objects;
import java.util.function.Function;

public record LedgerCredentialExpr(PropertyNode node) implements Expr {
    public LedgerCredentialExpr { node = Objects.requireNonNull(node, "node"); }
    public TypedValueExpr typed() {
        return new TypedValueExpr(node, LedgerTypeAuthority.CREDENTIAL);
    }
    public BoolExpr isPubKey() { return is("PubKeyCredential"); }
    public BoolExpr isScript() { return is("ScriptCredential"); }
    public BoolExpr whenPubKey(Function<TypedValueExpr, BoolExpr> predicate) {
        return when("PubKeyCredential", "keyHash", LedgerTypeAuthority.PUB_KEY_HASH, predicate);
    }
    public BoolExpr whenScript(Function<TypedValueExpr, BoolExpr> predicate) {
        return when("ScriptCredential", "scriptHash", LedgerTypeAuthority.SCRIPT_HASH, predicate);
    }
    private BoolExpr is(String constructor) {
        return new BoolExpr(new LedgerVariantIsNode(
                node, LedgerTypeAuthority.CREDENTIAL, constructor));
    }
    private BoolExpr when(String constructor, String field,
            org.julclang.verification.dsl.type.VerificationTypeRef type,
            Function<TypedValueExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return BinderScope.bind(variable -> {
            var bound = new TypedVariableNode(variable, LedgerTypeAuthority.CREDENTIAL);
            var payload = new TypedValueExpr(new LedgerVariantFieldNode(bound,
                    LedgerTypeAuthority.CREDENTIAL, constructor, field, type), type);
            return new BoolExpr(new LedgerVariantWhenNode(node,
                    LedgerTypeAuthority.CREDENTIAL, constructor, variable,
                    predicate.apply(payload).node()));
        });
    }
}
