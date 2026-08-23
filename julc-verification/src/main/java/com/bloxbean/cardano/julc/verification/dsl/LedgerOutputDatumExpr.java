package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;

import java.util.Objects;
import java.util.function.Function;
import com.bloxbean.cardano.julc.verification.dsl.type.VerificationTypeRef;

public record LedgerOutputDatumExpr(PropertyNode node) implements Expr {
    public LedgerOutputDatumExpr { node = Objects.requireNonNull(node, "node"); }
    public BoolExpr isNone() { return is("NoOutputDatum"); }
    public BoolExpr isHash() { return is("OutputDatumHash"); }
    public BoolExpr isInline() { return is("OutputDatum"); }
    public BoolExpr whenHash(Function<TypedValueExpr, BoolExpr> predicate) {
        return when("OutputDatumHash", "datumHash", LedgerTypeAuthority.DATUM_HASH, predicate);
    }
    public BoolExpr whenInline(Function<TypedValueExpr, BoolExpr> predicate) {
        return when("OutputDatum", "datum", LedgerTypeAuthority.DATA, predicate);
    }
    public BoolExpr whenInlineDecoded(
            VerificationTypeRef decodedType,
            Function<TypedValueExpr, BoolExpr> predicate) {
        return whenInline(data -> TypedExpressions.strictDecode(
                data, decodedType, predicate));
    }
    private BoolExpr is(String constructor) {
        return new BoolExpr(new LedgerVariantIsNode(
                node, LedgerTypeAuthority.OUTPUT_DATUM, constructor));
    }
    private BoolExpr when(String constructor, String field,
            com.bloxbean.cardano.julc.verification.dsl.type.VerificationTypeRef type,
            Function<TypedValueExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return BinderScope.bind(variable -> {
            var bound = new TypedVariableNode(variable, LedgerTypeAuthority.OUTPUT_DATUM);
            var payload = new TypedValueExpr(new LedgerVariantFieldNode(bound,
                    LedgerTypeAuthority.OUTPUT_DATUM, constructor, field, type), type);
            return new BoolExpr(new LedgerVariantWhenNode(node,
                    LedgerTypeAuthority.OUTPUT_DATUM, constructor, variable,
                    predicate.apply(payload).node()));
        });
    }
}
