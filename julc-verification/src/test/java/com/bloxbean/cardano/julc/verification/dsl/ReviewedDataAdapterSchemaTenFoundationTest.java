package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.compiler.schema.ContractSchema;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslDomain;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPurpose;
import com.bloxbean.cardano.julc.verification.dsl.type.ContractTypeProjection;
import com.bloxbean.cardano.julc.verification.dsl.type.NominalTypeRef;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReviewedDataAdapterSchemaTenFoundationTest {
    @Test
    void schemaTenIsOptInAndInheritsOnlyPreviouslyReviewedVocabulary() {
        ContractSchema schema = schema();
        String hash = ContractTypeProjection.sha256(ContractTypeProjection.project(schema));
        var claim = VerificationDsl.property("schema-ten.foundation",
                DslDomain.VALID_SPENDING_V3_PINNED,
                LedgerExpressions.context().txInfo().proposals()
                        .exists(proposal -> proposal.deposit()
                                .ge(VerificationDsl.integer(0))));
        var candidate = DslPropertySet.typedV10(DslPurpose.SPENDING, hash, claim);

        var normalized = DslPropertyValidator.validateAndNormalize(candidate, schema, 10_000);
        assertEquals(10, normalized.schemaVersion());
        var promoted = ComposedDslPromotion.promote(
                normalized, schema, "SchemaTenGate", "SchemaTenProperties.java");
        assertEquals(10, ComposedDslPromotion.verifyIntegrity(promoted).schemaVersion());

        String generated = ContractMetamodelGenerator.generateTypedV10(
                schema, "verification", "SchemaTenModel");
        assertTrue(generated.contains("DslPropertySet.typedV10"), generated);
        assertTrue(generated.contains("LedgerExpressions.context()"), generated);
        assertTrue(generated.contains("public BoolExpr decodeDatum"), generated);
        assertTrue(generated.contains("TypedExpressions.strictDecode"), generated);
        assertEquals(1, VerificationDslApi.API_VERSION);
        assertEquals(10, VerificationDslApi.STABLE_PROPERTY_SCHEMA_VERSION);
        assertEquals(1, VerificationDslApi.MIN_READABLE_PROPERTY_SCHEMA_VERSION);
    }

    @Test
    void strictDataDecodeAcceptsOnlyCompilerProjectedNominalTypes() {
        ContractSchema schema = schema();
        var projection = ContractTypeProjection.project(schema);
        var datumType = projection.datumType();
        var valid = DslPropertySet.typedV10(DslPurpose.SPENDING,
                ContractTypeProjection.sha256(projection),
                VerificationDsl.property("strict.decode", DslDomain.NONE,
                        LedgerExpressions.context().txInfo().outputs().at(
                                VerificationDsl.integer(0)).exists(output ->
                                output.datum().whenInlineDecoded(
                                        datumType, ignored -> VerificationDsl.bool(true)))));
        DslPropertyValidator.validate(valid, schema, 100);

        var forged = new NominalTypeRef("forged.Datum",
                NominalTypeRef.NominalKind.RECORD);
        var invalid = DslPropertySet.typedV10(DslPurpose.SPENDING,
                ContractTypeProjection.sha256(projection),
                VerificationDsl.property("strict.forged", DslDomain.NONE,
                        LedgerExpressions.context().txInfo().outputs().at(
                                VerificationDsl.integer(0)).exists(output ->
                                output.datum().whenInlineDecoded(
                                        forged, ignored -> VerificationDsl.bool(true)))));
        assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(invalid, schema, 100));
    }

    @Test
    void singletonListEliminationIsClosedCanonicalAndSchemaTenOnly() {
        ContractSchema schema = schema();
        var projection = ContractTypeProjection.project(schema);
        var claim = VerificationDsl.property("singleton.output", DslDomain.NONE,
                LedgerExpressions.context().txInfo().outputs().whenSingleton(
                        output -> output.value().structurallyEquals(output.value())));
        var candidate = DslPropertySet.typedV10(DslPurpose.SPENDING,
                ContractTypeProjection.sha256(projection), claim);

        var normalized = DslPropertyValidator.validateAndNormalize(candidate, schema, 100);
        assertEquals(PropertyIrCodec.canonicalJson(normalized), PropertyIrCodec.canonicalJson(
                DslPropertyCanonicalizer.normalize(normalized)));
        assertTrue(TypedPropertyLeanRenderer.renderExpression(
                normalized.properties().getFirst().expression(), projection)
                .contains("with | [v0] =>"));

        var oldSchema = DslPropertySet.typedV9(DslPurpose.SPENDING,
                ContractTypeProjection.sha256(projection), claim);
        assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(oldSchema, schema, 100));
    }

    @Test
    void schemaNineCanonicalBytesAndRawFieldApiStayFrozen() {
        ContractSchema schema = schema();
        String hash = ContractTypeProjection.sha256(ContractTypeProjection.project(schema));
        var schemaNine = DslPropertySet.typedV9(DslPurpose.SPENDING, hash,
                VerificationDsl.property("frozen.schema-nine", DslDomain.NONE,
                        VerificationDsl.bool(true)));

        assertEquals("{\"contractSchemaSha256\":\"" + hash
                + "\",\"properties\":[{\"domain\":\"NONE\",\"expression\":{"
                + "\"op\":\"bool-literal\",\"value\":true},"
                + "\"id\":\"frozen.schema-nine\"}],\"purpose\":\"SPENDING\","
                + "\"schemaVersion\":9}", PropertyIrCodec.canonicalJson(schemaNine));

        var methods = Arrays.stream(LedgerTxInfoExpr.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName).toList();
        assertFalse(methods.contains("validityRangeStrict"));
        assertFalse(methods.contains("currentTreasuryAmountStrict"));
        assertTrue(methods.contains("validityRangeReviewed"));
        assertTrue(methods.contains("currentTreasuryStrict"));
        assertTrue(methods.contains("treasuryDonationStrict"));
    }

    private static ContractSchema schema() {
        return new JulcCompiler().compileContract("""
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                @SpendingValidator class SchemaTenGate {
                    record Datum(byte[] owner) {}
                    record Redeemer() {}
                    @Entrypoint static boolean validate(Datum d, Redeemer r, ScriptContext c) {
                        return true;
                    }
                }
                """).contractSchema();
    }
}
