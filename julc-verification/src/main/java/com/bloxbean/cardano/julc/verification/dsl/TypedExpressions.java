package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import com.bloxbean.cardano.julc.verification.dsl.type.NominalTypeRef;
import com.bloxbean.cardano.julc.verification.dsl.type.OptionalTypeRef;
import com.bloxbean.cardano.julc.verification.dsl.type.VerificationTypeRef;

import java.util.function.Function;

/** Primitive builders used by generated contract-specific schema-4 wrappers. */
public final class TypedExpressions {
    private TypedExpressions() { }

    public static TypedOptionExpr optionalRoot(
            String name, VerificationTypeRef elementType) {
        return new TypedOptionExpr(new TypedRootNode(
                name, new OptionalTypeRef(elementType)), elementType);
    }

    public static TypedValueExpr field(
            TypedValueExpr target,
            NominalTypeRef owner,
            String name,
            VerificationTypeRef type) {
        return new TypedValueExpr(
                new TypedFieldNode(target.node(), owner, name, type), type);
    }

    public static TypedValueExpr variantField(
            TypedValueExpr target,
            NominalTypeRef sum,
            String constructor,
            String name,
            VerificationTypeRef type) {
        return new TypedValueExpr(new VariantFieldNode(
                target.node(), sum, constructor, name, type), type);
    }

    public static BoolExpr isConstructor(
            TypedValueExpr value, NominalTypeRef sum, String constructor) {
        return new BoolExpr(new VariantIsNode(value.node(), sum, constructor));
    }

    public static BoolExpr whenConstructor(
            TypedValueExpr value,
            NominalTypeRef sum,
            String constructor,
            Function<TypedValueExpr, BoolExpr> predicate) {
        return BinderScope.bind(variable -> {
            var bound = new TypedValueExpr(
                    new TypedVariableNode(variable, sum), sum);
            return new BoolExpr(new VariantWhenNode(value.node(), sum, constructor,
                    variable, predicate.apply(bound).node()));
        });
    }
}
