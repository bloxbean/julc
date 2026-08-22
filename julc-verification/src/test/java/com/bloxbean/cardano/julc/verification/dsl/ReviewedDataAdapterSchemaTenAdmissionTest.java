package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.compiler.schema.ContractSchema;
import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import com.bloxbean.cardano.julc.verification.dsl.type.ContractTypeProjection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReviewedDataAdapterSchemaTenAdmissionTest {
    @Test
    void admitsEveryReviewedAdapterAndRendersOnlyPinnedHelpers() {
        ContractSchema schema = schema();
        String hash = ContractTypeProjection.sha256(ContractTypeProjection.project(schema));
        var tx = LedgerExpressions.context().txInfo();
        BoolExpr timeAndTreasury = tx.validityRangeReviewed()
                .contains(VerificationDsl.integer(10))
                .and(tx.validityRangeReviewed().decoderValid())
                .and(tx.currentTreasuryStrict().whenPresent(
                        amount -> amount.ge(VerificationDsl.integer(0))))
                .and(tx.treasuryDonationStrict().isWellFormed());
        BoolExpr governance = tx.proposals().exists(proposal ->
                proposal.actionStrict().exists(action -> {
                    BoolExpr parameters = action.whenParameterChange(
                            (previous, changed, script) -> changed.isWellFormed()
                                    .and(changed.isStrictlyAscendingUnique())
                                    .and(changed.containsId(VerificationDsl.integer(1))));
                    BoolExpr quorum = action.whenUpdateCommittee(
                            (previous, oldMembers, newMembers, value) ->
                                    value.decoderValid()
                                            .and(value.canonicalEncoding())
                                            .and(value.isUnitInterval())
                                            .and(value.whenDecoded((numerator, denominator) ->
                                                    numerator.le(denominator))));
                    return parameters.or(quorum);
                }));
        var candidate = DslPropertySet.typedV10(DslPurpose.SPENDING, hash,
                VerificationDsl.property("reviewed.adapters",
                        DslDomain.VALID_SPENDING_V3_PINNED,
                        timeAndTreasury.and(governance)));

        var normalized = DslPropertyValidator.validateAndNormalize(
                candidate, schema, 10_000);
        var promoted = ComposedDslPromotion.promote(normalized, schema,
                "ReviewedAdapterGate", "ReviewedAdapterProperties.java");
        var capabilities = promoted.claims().getFirst().capabilities();
        assertTrue(capabilities.contains("adapter.validity-range.pinned-v1"));
        assertTrue(capabilities.contains(
                "adapter.current-treasury.strict-optional-integer"));
        assertTrue(capabilities.contains(
                "adapter.treasury-donation.strict-optional-integer"));
        assertTrue(capabilities.contains(
                "adapter.changed-parameters.integer-key-index"));
        assertTrue(capabilities.contains("adapter.quorum.plutus-rational-v1"));

        String lean = TypedPropertyLeanRenderer.renderExpression(
                normalized.properties().getFirst().expression(),
                ContractTypeProjection.project(schema));
        assertTrue(lean.contains("julcValidityContains"), lean);
        assertTrue(lean.contains("julcDecodeTreasury"), lean);
        assertTrue(lean.contains("julcChangedParametersStrictlyAscendingUnique"), lean);
        assertTrue(lean.contains("julcDecodeQuorum"), lean);
        assertFalse(lean.contains("raw Lean"), lean);
    }

    @Test
    void oldSchemaAndWrongParentsFailBeforeLeanGeneration() {
        ContractSchema schema = schema();
        String hash = ContractTypeProjection.sha256(ContractTypeProjection.project(schema));
        BoolExpr time = LedgerExpressions.context().txInfo().validityRangeReviewed()
                .decoderValid();
        var old = DslPropertySet.typedV9(DslPurpose.SPENDING, hash,
                VerificationDsl.property("old.adapter", DslDomain.NONE, time));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(old, schema, 10_000))
                .getMessage().contains("schema 10"));

        var wrongParent = new ReviewedAdapterPredicateNode(
                ReviewedAdapterPredicateNode.ReviewedAdapterPredicate
                        .VALIDITY_DECODER_VALID,
                List.of(LedgerExpressions.context().node()));
        var forged = DslPropertySet.typedV10(DslPurpose.SPENDING, hash,
                VerificationDsl.property("wrong.parent", DslDomain.NONE,
                        new BoolExpr(wrongParent)));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(forged, schema, 10_000))
                .getMessage().contains("TxInfo"));

        var unguarded = new TypedVariableNode("v0", LedgerTypeAuthority.GOVERNANCE_ACTION);
        var rawPayload = new ReviewedAdapterPredicateNode(
                ReviewedAdapterPredicateNode.ReviewedAdapterPredicate
                        .CHANGED_PARAMETERS_WELL_FORMED, List.of(unguarded));
        var forgedPayload = DslPropertySet.typedV10(DslPurpose.SPENDING, hash,
                VerificationDsl.property("wrong.guard", DslDomain.NONE,
                        new BoolExpr(rawPayload)));
        assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(forgedPayload, schema, 10_000));
    }

    @Test
    void canonicalCodecRoundTripsAndRejectsUnknownAdapterOperation() throws Exception {
        ContractSchema schema = schema();
        String hash = ContractTypeProjection.sha256(ContractTypeProjection.project(schema));
        var candidate = DslPropertySet.typedV10(DslPurpose.SPENDING, hash,
                VerificationDsl.property("codec.adapter", DslDomain.NONE,
                        LedgerExpressions.context().txInfo()
                                .treasuryDonationStrict().isAbsent()));
        var normalized = DslPropertyValidator.validateAndNormalize(
                candidate, schema, 10_000);
        String canonical = PropertyIrCodec.canonicalJson(normalized);
        assertEquals(normalized, PropertyIrCodec.readCanonical(
                canonical, PropertyIrCodec.MAX_CANONICAL_BYTES));
        String tampered = canonical.replace("TREASURY_DONATION_ABSENT", "RAW_CAST");
        assertThrows(Exception.class, () -> PropertyIrCodec.readCanonical(
                tampered, PropertyIrCodec.MAX_CANONICAL_BYTES));
    }

    private static ContractSchema schema() {
        return new JulcCompiler().compileContract("""
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                @SpendingValidator class ReviewedAdapterGate {
                    record Datum(byte[] owner, java.math.BigInteger deadline) {}
                    record Redeemer() {}
                    @Entrypoint static boolean validate(Datum d, Redeemer r, ScriptContext c) {
                        return true;
                    }
                }
                """).contractSchema();
    }
}
