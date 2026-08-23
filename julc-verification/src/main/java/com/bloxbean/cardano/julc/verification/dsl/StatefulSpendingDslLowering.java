package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.compiler.schema.ContractSchema;
import com.bloxbean.cardano.julc.verification.StatefulSpendingProperty;
import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import com.bloxbean.cardano.julc.verification.dsl.type.*;

import java.util.List;

import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.property;

/** Canonical lowering of the complete stateful annotation profile. */
public final class StatefulSpendingDslLowering {
    private static final BuiltinTypeRef BYTES =
            new BuiltinTypeRef(BuiltinTypeRef.BuiltinKind.BYTE_STRING);
    private static final BuiltinTypeRef INTEGER =
            new BuiltinTypeRef(BuiltinTypeRef.BuiltinKind.INTEGER);

    private StatefulSpendingDslLowering() { }

    public static DslPropertySet lower(
            StatefulSpendingProperty propertyIr, ContractSchema schema) {
        ProjectedContractTypes projection = ContractTypeProjection.project(schema);
        DslPropertySet candidate = lower(propertyIr.propertyId(), propertyIr.authority(),
                propertyIr.currentState(), propertyIr.nextState(), projection);
        return DslPropertyValidator.validateAndNormalize(
                candidate, schema, DslPropertyValidator.MAX_AST_NODES);
    }

    public static DslPropertySet lower(
            String propertyId,
            StatefulSpendingProperty.Selection authority,
            StatefulSpendingProperty.Selection currentState,
            StatefulSpendingProperty.Selection nextState,
            ProjectedContractTypes projection) {
        if (projection.purpose() != ContractSchema.Purpose.SPEND
                || !(projection.datumType() instanceof NominalTypeRef datumType)
                || !(projection.redeemerType() instanceof NominalTypeRef redeemerType)) {
            throw new IllegalArgumentException(
                    "Stateful DSL lowering requires nominal spending datum and redeemer types");
        }

        var datum = TypedExpressions.optionalRoot("typedDatum", datumType);
        var redeemer = TypedExpressions.optionalRoot("typedRedeemer", redeemerType);
        var ledgerContext = LedgerExpressions.context();
        var ownInput = new LedgerTxInInfoOptionExpr(new LedgerHelperNode(
                LedgerHelperNode.LedgerHelperKind.FIND_OWN_INPUT,
                List.of(ledgerContext.node()),
                new OptionalTypeRef(LedgerTypeAuthority.TX_IN_INFO)));
        var continuingOutputs = new LedgerTxOutListExpr(new LedgerHelperNode(
                LedgerHelperNode.LedgerHelperKind.CONTINUING_OUTPUTS,
                List.of(ledgerContext.node()),
                new ListTypeRef(LedgerTypeAuthority.TX_OUT)));

        BoolExpr guarantee = datum.exists(currentDatum -> {
            var owner = TypedExpressions.field(currentDatum, datumType,
                    authority.field(), BYTES);
            var current = new IntegerExpr(TypedExpressions.field(
                    currentDatum, datumType, currentState.field(), INTEGER).node());
            var signed = new SpendingContractModel().context().txInfo().signatories()
                    .contains(new ByteStringExpr(owner.node()));
            return redeemer.exists(action -> {
                var committedNext = new IntegerExpr(TypedExpressions.field(
                        action, redeemerType, nextState.field(), INTEGER).node());
                return ownInput.exists(own -> {
                    var validSuccessor = continuingOutputs.whenSingleton(successor -> {
                        var preservedValue = successor.value().structurallyEquals(
                                own.resolved().value());
                        var decodedSuccessor = successor.datum().whenInlineDecoded(
                                datumType, nextDatum -> {
                                    var nextOwner = TypedExpressions.field(nextDatum,
                                            datumType, authority.field(), BYTES);
                                    var nextValue = new IntegerExpr(TypedExpressions.field(
                                            nextDatum, datumType,
                                            currentState.field(), INTEGER).node());
                                    return nextOwner.eq(owner)
                                            .and(nextValue.eq(committedNext))
                                            .and(current.lt(committedNext));
                                });
                        return preservedValue.and(decodedSuccessor);
                    });
                    return signed.and(validSuccessor);
                });
            });
        });

        return DslPropertySet.typedV10(
                DslPurpose.SPENDING,
                ContractTypeProjection.sha256(projection),
                property(propertyId, DslDomain.NONE, guarantee));
    }
}
