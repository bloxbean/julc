package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.compiler.schema.ContractSchema;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslDomain;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPurpose;
import com.bloxbean.cardano.julc.verification.dsl.type.ContractTypeProjection;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
