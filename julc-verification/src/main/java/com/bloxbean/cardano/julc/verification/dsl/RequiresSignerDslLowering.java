package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.compiler.schema.ContractSchema;
import com.bloxbean.cardano.julc.verification.RequiresSignerProperty;
import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import com.bloxbean.cardano.julc.verification.dsl.type.*;

import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.property;

/** Canonical annotation-to-DSL lowering used to prevent frontend semantic drift. */
public final class RequiresSignerDslLowering {
    private static final BuiltinTypeRef BYTES =
            new BuiltinTypeRef(BuiltinTypeRef.BuiltinKind.BYTE_STRING);

    private RequiresSignerDslLowering() { }

    public static DslPropertySet lower(
            RequiresSignerProperty propertyIr, ContractSchema schema) {
        ProjectedContractTypes projection = ContractTypeProjection.project(schema);
        DslPropertySet candidate = lower(
                propertyIr.propertyId(), propertyIr.path().getLast().name(), projection);
        return DslPropertyValidator.validateAndNormalize(
                candidate, schema, DslPropertyValidator.MAX_AST_NODES);
    }

    public static DslPropertySet lower(
            String propertyId,
            String ownerField,
            ProjectedContractTypes projection) {
        if (projection.purpose() != ContractSchema.Purpose.SPEND
                || !(projection.datumType() instanceof NominalTypeRef datumType)) {
            throw new IllegalArgumentException(
                    "Required-signer DSL lowering requires a nominal spending datum");
        }
        var datum = TypedExpressions.optionalRoot("typedDatum", datumType);
        var guarantee = datum.exists(value -> {
            var owner = TypedExpressions.field(value, datumType, ownerField, BYTES);
            // Preserve the annotation profile's direct membership semantics.  The
            // richer authorization algebra is available to user DSL properties,
            // but its recursive distinct-set machinery is unnecessary for a
            // singleton required signer and materially complicates SMT search.
            return new SpendingContractModel().context().txInfo().signatories()
                    .contains(new ByteStringExpr(owner.node()));
        });
        return DslPropertySet.typedV10(
                DslPurpose.SPENDING,
                ContractTypeProjection.sha256(projection),
                property(propertyId, DslDomain.NONE, guarantee));
    }
}
