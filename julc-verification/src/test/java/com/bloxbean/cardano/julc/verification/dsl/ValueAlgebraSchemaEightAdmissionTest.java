package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.compiler.schema.ContractSchema;
import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import com.bloxbean.cardano.julc.verification.dsl.type.ContractTypeProjection;
import com.bloxbean.cardano.julc.verification.dsl.worker.DslWorkerRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ValueAlgebraSchemaEightAdmissionTest {
    @TempDir Path tempDir;

    @Test
    void generatedSchemaEightModelCompilesAndRunsInBoundedWorker() throws Exception {
        ContractSchema schema = schema();
        Path sources = tempDir.resolve("sources/evidence");
        Files.createDirectories(sources);
        Path model = sources.resolve("ValueModel.java");
        Files.writeString(model, ContractMetamodelGenerator.generate(
                schema, "evidence", "ValueModel"));
        Path specification = sources.resolve("ValueProperties.java");
        Files.writeString(specification, """
                package evidence;
                import com.bloxbean.cardano.julc.verification.dsl.*;
                import com.bloxbean.cardano.julc.verification.dsl.ir.*;
                import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.*;
                public final class ValueProperties implements VerificationSpecification {
                    public DslPropertySet properties() {
                        var contract = new ValueModel();
                        var policy = LedgerExpressions.currencySymbol(bytes("11".repeat(28)));
                        var token = LedgerExpressions.tokenName(bytes("aa"));
                        var guarantee = contract.context().txInfo().outputs().at(integer(0))
                            .exists(output -> output.value().quantitySumStrict(policy, token)
                                .exists(quantity -> new IntegerExpr(quantity.node()).ge(integer(1))));
                        return contract.properties(property("value.strict-payment",
                            DslDomain.VALID_SPENDING_V3_PINNED, guarantee));
                    }
                }
                """);
        Path classes = compileJava(model, specification);
        DslPropertySet candidate = new DslWorkerRunner().run(
                classes + File.pathSeparator + System.getProperty("java.class.path"),
                "evidence.ValueProperties", schema, tempDir.resolve("worker"),
                Duration.ofSeconds(10));

        assertEquals(DslPropertySet.SCHEMA_VERSION,
                candidate.schemaVersion());
        ComposedDslPromotion.promote(candidate, schema,
                "ValueGate", "ValueProperties.java");
    }

    @Test
    void admitsSeparateRawFirstMatchStrictAndExtensionalMeanings() {
        ContractSchema schema = schema();
        String hash = ContractTypeProjection.sha256(ContractTypeProjection.project(schema));
        var context = LedgerExpressions.context();
        var value = context.txInfo().outputs().at(VerificationDsl.integer(0));
        var policy = LedgerExpressions.currencySymbol(VerificationDsl.bytes("11"));
        var token = LedgerExpressions.tokenName(VerificationDsl.bytes("aa"));

        BoolExpr outputProperty = value.exists(output -> {
            var outputValue = output.value();
            return outputValue.rawPolicies().exists(entry ->
                    entry.whenWellFormed((actualPolicy, tokens) ->
                            actualPolicy.eq(policy).and(tokens.exists(tokenEntry ->
                                    tokenEntry.whenWellFormed((actualToken, quantity) ->
                                            actualToken.eq(token).and(quantity.gt(
                                                    VerificationDsl.integer(0))))))))
                    .and(outputValue.quantityFirst(policy, token)
                            .ge(VerificationDsl.integer(0)))
                    .and(outputValue.quantitySumStrict(policy, token)
                            .exists(quantity -> new IntegerExpr(quantity.node())
                                    .ge(VerificationDsl.integer(0))))
                    .and(outputValue.extensionallyEquals(outputValue))
                    .and(outputValue.structurallyEquals(outputValue));
        });
        BoolExpr arithmetic = LedgerExpressions.singletonValueDelta(
                policy, token, VerificationDsl.integer(2)).exists(singleton ->
                singleton.negate().exists(negated ->
                        singleton.plus(negated).exists(sum ->
                                sum.quantitySumStrict(policy, token).exists(quantity ->
                                        new IntegerExpr(quantity.node()).eq(
                                                VerificationDsl.integer(0))))));
        BoolExpr guarantee = outputProperty
                .and(context.valueSpent().pointwiseLe(context.valueProduced()))
                .and(context.isBalanced())
                .and(arithmetic);
        var candidate = DslPropertySet.schema1(DslPurpose.SPENDING, hash,
                VerificationDsl.property("value.views",
                        DslDomain.VALID_SPENDING_V3_PINNED, guarantee));

        var normalized = DslPropertyValidator.validateAndNormalize(
                candidate, schema, 10_000);
        var promoted = ComposedDslPromotion.promote(normalized, schema,
                "ValueGate", "ValueProperties.java");
        assertEquals(1, normalized.schemaVersion());
        assertEquals(1, ComposedDslPromotion.verifyIntegrity(promoted).schemaVersion());
        assertTrue(promoted.claims().getFirst().capabilities()
                .contains("helper.valueOf"));
        assertTrue(promoted.claims().getFirst().capabilities()
                .contains("dsl.value.quantity-sum-strict"));

        String lean = TypedPropertyLeanRenderer.renderExpression(
                normalized.properties().getFirst().expression(),
                ContractTypeProjection.project(schema));
        assertTrue(lean.contains("valueOf"), lean);
        assertTrue(lean.contains("julcValueQuantitySumStrict"), lean);
        assertTrue(lean.contains("julcValueExtensionalEq"), lean);
        assertTrue(lean.contains("julcValueSingletonStrict"), lean);
        assertTrue(lean.contains("julcValueNegateStrict"), lean);
        assertTrue(lean.contains("julcValueAddStrict"), lean);
        assertTrue(lean.contains("isBalanced"), lean);
    }

    @Test
    void schemaSevenAndForgedValueRolesFailClosed() {
        ContractSchema schema = schema();
        String hash = ContractTypeProjection.sha256(ContractTypeProjection.project(schema));
        var forged = new ValueQuantityNode(
                ValueQuantityNode.ValueQuantityKind.STRICT_SUMMED,
                LedgerExpressions.context().txInfo().fee().node(),
                LedgerTypeAuthority.VALUE,
                LedgerExpressions.currencySymbol(VerificationDsl.bytes("11")).node(),
                LedgerExpressions.tokenName(VerificationDsl.bytes("aa")).node());
        var forgedSet = DslPropertySet.schema1(DslPurpose.SPENDING, hash,
                VerificationDsl.property("value.forged", DslDomain.NONE,
                        new TypedOptionExpr(forged, LedgerTypeAuthority.INTEGER).isPresent()));
        assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(forgedSet, schema, 1_000));

        var forgedCrossRole = new ValueRelationNode(
                ValueRelationNode.ValueRelationKind.STRUCTURAL_EQ,
                LedgerExpressions.context().valueProduced().node(),
                LedgerTypeAuthority.VALUE,
                LedgerExpressions.context().txInfo().mint().node(),
                LedgerTypeAuthority.MINT_VALUE);
        var forgedCrossRoleSet = DslPropertySet.schema1(DslPurpose.SPENDING, hash,
                VerificationDsl.property("value.forged-cross-role", DslDomain.NONE,
                        new BoolExpr(forgedCrossRole)));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(forgedCrossRoleSet, schema, 1_000))
                .getMessage().contains("ValueDelta"));

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> LedgerExpressions.singletonValueDelta(
                        LedgerExpressions.currencySymbol(
                                VerificationDsl.bytes("11".repeat(28))),
                        LedgerExpressions.tokenName(VerificationDsl.bytes("aa")),
                        VerificationDsl.integer(1)).exists(delta ->
                                delta.scale(LedgerExpressions.context().txInfo().fee())
                                        .isPresent()))
                .getMessage().contains("integer literal"));
    }

    @Test
    void schemaEightCanonicalizationIsStableAndSchemaSevenBytesStayFrozen() {
        ContractSchema schema = schema();
        String hash = ContractTypeProjection.sha256(ContractTypeProjection.project(schema));
        var policy = LedgerExpressions.currencySymbol(VerificationDsl.bytes("11"));
        var token = LedgerExpressions.tokenName(VerificationDsl.bytes("aa"));
        var mint = LedgerExpressions.context().txInfo().mint();
        var first = DslPropertySet.schema1(DslPurpose.SPENDING, hash,
                VerificationDsl.property("value.canonical", DslDomain.NONE,
                        mint.quantitySumStrict(policy, token).isPresent()));
        var normalized = DslPropertyCanonicalizer.normalize(first);
        assertEquals(PropertyIrCodec.canonicalJson(normalized),
                PropertyIrCodec.canonicalJson(DslPropertyCanonicalizer.normalize(normalized)));

        var seven = DslPropertySet.schema1(DslPurpose.SPENDING, hash,
                VerificationDsl.property("frozen", DslDomain.NONE,
                        VerificationDsl.bool(true)));
        assertEquals(1, seven.schemaVersion());
        assertFalse(PropertyIrCodec.canonicalJson(seven).contains("value-"));
    }

    @Test
    void transactionValueAlgebraComposesAcrossAllSupportedPurposes() {
        for (DslPurpose purpose : DslPurpose.values()) {
            ContractSchema schema = purposeSchema(purpose);
            String hash = ContractTypeProjection.sha256(
                    ContractTypeProjection.project(schema));
            var context = LedgerExpressions.context();
            var policy = LedgerExpressions.currencySymbol(
                    VerificationDsl.bytes("11".repeat(28)));
            var token = LedgerExpressions.tokenName(VerificationDsl.bytes("aa"));
            BoolExpr guarantee = context.txInfo().mint()
                    .quantitySumStrict(policy, token).isPresent()
                    .and(context.valueProduced()
                            .extensionallyEquals(context.valueProduced()));
            var candidate = DslPropertySet.schema1(purpose, hash,
                    VerificationDsl.property("value." + purpose.name().toLowerCase(),
                            domain(purpose), guarantee));

            var normalized = DslPropertyValidator.validateAndNormalize(
                    candidate, schema, 10_000);
            assertEquals(purpose, normalized.purpose());
            assertEquals(1, normalized.schemaVersion());
        }
    }

    private static ContractSchema schema() {
        return new JulcCompiler().compileContract("""
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                @SpendingValidator class ValueGate {
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
                @%s class PurposeValueGate {
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

    private Path compileJava(Path... sources) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        try (var files = compiler.getStandardFileManager(null, null, null)) {
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
            var units = files.getJavaFileObjects(sources);
            Boolean result = compiler.getTask(null, files, null,
                    List.of("-classpath", System.getProperty("java.class.path")),
                    null, units).call();
            assertTrue(result, "Generated schema-8 model and specification must compile");
        }
        return classes;
    }
}
