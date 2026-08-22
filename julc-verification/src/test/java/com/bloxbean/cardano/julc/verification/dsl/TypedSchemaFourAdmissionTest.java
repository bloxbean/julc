package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.compiler.schema.ContractSchema;
import com.bloxbean.cardano.julc.verification.ComposedDslProperty;
import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import com.bloxbean.cardano.julc.verification.dsl.type.ContractTypeProjection;
import org.junit.jupiter.api.Test;

import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.integer;
import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.property;
import static org.junit.jupiter.api.Assertions.*;

class TypedSchemaFourAdmissionTest {
    @Test
    void schemaFourBindsCompilerProjectionWhileSchemaThreeBytesStayFrozen() throws Exception {
        ContractSchema schema = spendingSchema();
        var model = new SpendingContractModel();
        var claim = property("state.nonnegative", DslDomain.NONE,
                model.datum().integerField("state").ge(integer(0)));
        String hash = ContractTypeProjection.sha256(ContractTypeProjection.project(schema));
        var schemaFour = DslPropertySet.typedV4(DslPurpose.SPENDING, hash, claim);

        var promoted = ComposedDslPromotion.promote(
                schemaFour, schema, "StateGate", "StateProperties.java");

        assertEquals(ComposedDslProperty.TYPED_SCHEMA_VERSION,
                promoted.schemaVersion());
        assertEquals(ComposedDslProperty.TYPED_TEMPLATE, promoted.template());
        assertEquals(hash, promoted.contractSchemaSha256());
        assertEquals(hash, ContractTypeProjection.sha256(
                ContractTypeProjection.readCanonical(
                        promoted.projectedContractTypesJson(), 1_048_576)));
        assertEquals(schemaFour, ComposedDslPromotion.verifyIntegrity(promoted));

        String schemaThree = PropertyIrCodec.canonicalJson(
                DslPropertySet.composed(DslPurpose.SPENDING, claim));
        assertEquals("{\"properties\":[{\"domain\":\"NONE\",\"expression\":{"
                + "\"op\":\"compare\",\"left\":{\"op\":\"field\",\"name\":\"state\","
                + "\"resultType\":\"INTEGER\",\"target\":{\"op\":\"root\",\"name\":"
                + "\"datum\",\"resultType\":\"DATA\"}},\"operator\":\"GE\",\"right\":{"
                + "\"op\":\"literal\",\"resultType\":\"INTEGER\",\"value\":\"0\"}},"
                + "\"id\":\"state.nonnegative\"}],"
                + "\"purpose\":\"SPENDING\",\"schemaVersion\":3}", schemaThree);
        assertFalse(schemaThree.contains("contractSchemaSha256"));
    }

    @Test
    void schemaFourRejectsStaleHashAndPromotedTypeGraphTampering() {
        ContractSchema schema = spendingSchema();
        var model = new SpendingContractModel();
        var claim = property("state.nonnegative", DslDomain.NONE,
                model.datum().integerField("state").ge(integer(0)));
        var stale = DslPropertySet.typedV4(DslPurpose.SPENDING, "0".repeat(64), claim);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(stale, schema, 100))
                .getMessage().contains("does not match"));

        String hash = ContractTypeProjection.sha256(ContractTypeProjection.project(schema));
        var valid = ComposedDslPromotion.promote(
                DslPropertySet.typedV4(DslPurpose.SPENDING, hash, claim),
                schema, "StateGate", "StateProperties.java");
        var tampered = new ComposedDslProperty(
                valid.schemaVersion(), valid.template(), valid.propertyId(),
                valid.validatorTitle(), valid.scriptPurpose(), valid.sourcePath(),
                valid.canonicalDslJson(), valid.claims(), valid.domainAssumptions(),
                valid.guaranteeRules(), valid.ledgerValidityModeled(),
                valid.projectedContractTypesJson().replace("StateGate.Datum", "StateGate.Other"),
                valid.contractSchemaSha256());
        assertThrows(IllegalArgumentException.class,
                () -> ComposedDslPromotion.verifyIntegrity(tampered));
    }

    private static ContractSchema spendingSchema() {
        return new JulcCompiler().compileContract("""
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                @SpendingValidator class StateGate {
                    record Datum(BigInteger state) {}
                    record Redeemer(BigInteger next) {}
                    @Entrypoint static boolean validate(
                            Datum datum, Redeemer redeemer, ScriptContext context) {
                        return true;
                    }
                }
                """).contractSchema();
    }
}
