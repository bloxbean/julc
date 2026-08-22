package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.compiler.schema.ContractSchema;
import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import com.bloxbean.cardano.julc.verification.dsl.type.ContractTypeProjection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GovernanceSchemaNineAdmissionTest {
    @Test
    void admitsGuardedVotesProposalsAndStrictActions() {
        ContractSchema schema = schema();
        String hash = ContractTypeProjection.sha256(ContractTypeProjection.project(schema));
        var tx = LedgerExpressions.context().txInfo();
        BoolExpr vote = tx.votes().existsEntry((voter, actions) ->
                voter.whenDRep(credential -> actions.existsEntry((id, actual) ->
                        id.index().eq(VerificationDsl.integer(2)).and(actual.isYes()))));
        BoolExpr proposal = tx.proposals().exists(value -> value.deposit()
                .ge(VerificationDsl.integer(1)).and(value.actionStrict().exists(action ->
                        action.whenHardFork((previous, version) ->
                                version.major().eq(VerificationDsl.integer(11))))));
        var candidate = DslPropertySet.typedV9(DslPurpose.SPENDING, hash,
                VerificationDsl.property("governance.vote-or-hard-fork",
                        DslDomain.VALID_SPENDING_V3_PINNED, vote.or(proposal)));

        var normalized = DslPropertyValidator.validateAndNormalize(candidate, schema, 10_000);
        var promoted = ComposedDslPromotion.promote(normalized, schema,
                "GovernanceGate", "GovernanceProperties.java");
        assertEquals(9, ComposedDslPromotion.verifyIntegrity(promoted).schemaVersion());
        assertTrue(promoted.claims().getFirst().capabilities()
                .contains("field.txInfo.votes"));
        assertTrue(promoted.claims().getFirst().capabilities()
                .contains("dsl.governance.decode-action-strict"));
        String lean = TypedPropertyLeanRenderer.renderExpression(
                normalized.properties().getFirst().expression(),
                ContractTypeProjection.project(schema));
        assertTrue(lean.contains("txInfoVotes"), lean);
        assertTrue(lean.contains("IsData.fromData"), lean);
        assertTrue(lean.contains("HardForkInitiation"), lean);
    }

    @Test
    void governanceFailsClosedUnderSchemaEight() {
        ContractSchema schema = schema();
        String hash = ContractTypeProjection.sha256(ContractTypeProjection.project(schema));
        BoolExpr governance = LedgerExpressions.context().txInfo().proposals()
                .exists(proposal -> proposal.deposit().ge(VerificationDsl.integer(0)));
        var old = DslPropertySet.typedV8(DslPurpose.SPENDING, hash,
                VerificationDsl.property("old.schema",
                        DslDomain.VALID_SPENDING_V3_PINNED, governance));
        var error = assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(old, schema, 10_000));
        assertTrue(error.getMessage().contains("schema 9"), error.getMessage());
    }

    @Test
    void governanceTransactionDataComposesAcrossSelectablePurposes() {
        for (DslPurpose purpose : DslPurpose.values()) {
            ContractSchema schema = purposeSchema(purpose);
            String hash = ContractTypeProjection.sha256(ContractTypeProjection.project(schema));
            BoolExpr guarantee = LedgerExpressions.context().txInfo().proposals()
                    .exists(proposal -> proposal.deposit().ge(VerificationDsl.integer(0)));
            var candidate = DslPropertySet.typedV9(purpose, hash,
                    VerificationDsl.property("governance." + purpose.name().toLowerCase(),
                            domain(purpose), guarantee));
            assertEquals(9, DslPropertyValidator.validateAndNormalize(
                    candidate, schema, 10_000).schemaVersion());
        }
    }

    @Test
    void rawActionPayloadsHaveNoProjectionPath() {
        assertThrows(IllegalArgumentException.class, () ->
                LedgerTypeAuthority.variantField(LedgerTypeAuthority.GOVERNANCE_ACTION,
                        "ParameterChange", "changedParameters", LedgerTypeAuthority.DATA));
        assertThrows(IllegalArgumentException.class, () ->
                LedgerTypeAuthority.variantField(LedgerTypeAuthority.GOVERNANCE_ACTION,
                        "UpdateCommittee", "quorum", LedgerTypeAuthority.DATA));
        ContractSchema schema = schema();
        String hash = ContractTypeProjection.sha256(ContractTypeProjection.project(schema));
        BoolExpr forgedEquality = LedgerExpressions.context().txInfo().proposals()
                .exists(proposal -> new BoolExpr(new TypedEqualityNode(proposal.node(),
                        proposal.node(), LedgerTypeAuthority.PROPOSAL_PROCEDURE, false)));
        var forged = DslPropertySet.typedV9(DslPurpose.SPENDING, hash,
                VerificationDsl.property("forged.raw-equality",
                        DslDomain.VALID_SPENDING_V3_PINNED, forgedEquality));
        assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(forged, schema, 10_000));
    }

    private static ContractSchema schema() {
        return new JulcCompiler().compileContract("""
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                @SpendingValidator class GovernanceGate {
                    record Datum(byte[] owner) {}
                    record Redeemer() {}
                    @Entrypoint static boolean validate(Datum d, Redeemer r, ScriptContext c) {
                        return true;
                    }
                }
                """).contractSchema();
    }

    private static ContractSchema purposeSchema(DslPurpose purpose) {
        String annotation = switch (purpose) {
            case SPENDING -> "SpendingValidator";
            case MINTING -> "MintingValidator";
            case REWARDING -> "WithdrawValidator";
            case CERTIFYING -> "CertifyingValidator";
        };
        String parameters = purpose == DslPurpose.SPENDING
                ? "Datum datum, Redeemer redeemer, ScriptContext context"
                : "Redeemer redeemer, ScriptContext context";
        return new JulcCompiler().compileContract("""
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                @%s class PurposeGovernanceGate {
                    record Datum() {}
                    record Redeemer() {}
                    @Entrypoint static boolean validate(%s) { return true; }
                }
                """.formatted(annotation, parameters)).contractSchema();
    }

    private static DslDomain domain(DslPurpose purpose) {
        return switch (purpose) {
            case SPENDING -> DslDomain.VALID_SPENDING_V3_PINNED;
            case MINTING -> DslDomain.VALID_MINTING_V3_PINNED;
            case REWARDING -> DslDomain.VALID_REWARDING_V3_PINNED;
            case CERTIFYING -> DslDomain.VALID_CERTIFYING_V3_PINNED;
        };
    }
}
