package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.compiler.schema.ContractCompileResult;
import com.bloxbean.cardano.julc.verification.ComposedDslProperty;
import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import com.bloxbean.cardano.julc.verification.dsl.type.BuiltinTypeRef;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AuthorizationSchemaSixAdmissionTest {
    private static final String KEY_A = "41".repeat(28);
    private static final String KEY_B = "42".repeat(28);
    private static final String KEY_C = "43".repeat(28);

    @TempDir Path tempDir;

    @Test
    void generatedSchemaSixModelComposesFixedTypedAndDynamicAuthorities()
            throws Exception {
        var compiled = new JulcCompiler().compileContract(validatorSource());
        Path sources = tempDir.resolve("sources/evidence");
        Files.createDirectories(sources);
        Path model = sources.resolve("AuthorizationModel.java");
        Files.writeString(model, ContractMetamodelGenerator.generate(
                compiled.contractSchema(), "evidence", "AuthorizationModel"));
        Path specification = sources.resolve("AuthorizationProperties.java");
        Files.writeString(specification, """
                package evidence;
                import com.bloxbean.cardano.julc.verification.dsl.*;
                import com.bloxbean.cardano.julc.verification.dsl.ir.*;
                import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.*;
                public final class AuthorizationProperties implements VerificationSpecification {
                    public DslPropertySet properties() {
                        var contract = new AuthorizationModel();
                        var auth = contract.authorization();
                        var guarantee = contract.datum().exists(datum -> {
                            var fixedAndTyped = auth.authorities(
                                auth.fromContractBytes(datum.owner()),
                                auth.fromContractBytes(datum.recovery()),
                                auth.fixed("%s"));
                            return fixedAndTyped.atLeastSigned(2)
                                .and(fixedAndTyped.noUnexpectedSigners())
                                .and(datum.committee().asAuthorities().noneSigned()
                                    .or(auth.noSigners()));
                        });
                        return contract.properties(property("authorization.composed",
                            DslDomain.VALID_SPENDING_V3_PINNED, guarantee));
                    }
                }
                """.formatted(KEY_C));
        Path classes = compile(model, specification);

        DslPropertySet candidate = new DslWorkerRunner().run(
                classes + File.pathSeparator + System.getProperty("java.class.path"),
                "evidence.AuthorizationProperties", compiled.contractSchema(),
                tempDir.resolve("worker"), Duration.ofSeconds(10));

        assertEquals(DslPropertySet.SCHEMA_VERSION,
                candidate.schemaVersion());
        var promoted = ComposedDslPromotion.promote(candidate,
                compiled.contractSchema(), "AuthorizationGate",
                "AuthorizationProperties.java");
        assertEquals(ComposedDslProperty.LEDGER_SCHEMA_VERSION,
                promoted.schemaVersion());
        assertEquals(ComposedDslProperty.LEDGER_TEMPLATE, promoted.template());
        assertTrue(promoted.guaranteeRules().contains(
                "authorization:AT_LEAST_SIGNED:2"));
        assertTrue(promoted.guaranteeRules().contains(
                "authorization:NO_UNEXPECTED_SIGNERS"));
        assertTrue(promoted.guaranteeRules().contains(
                "authority-source:CONTRACT_BYTES"));
        assertTrue(promoted.guaranteeRules().contains(
                "authority-source:FIXED"));
        String lean = TypedPropertyLeanRenderer.renderExpression(
                candidate.properties().getFirst().expression(),
                ContractTypeProjection.project(compiled.contractSchema()));
        assertTrue(lean.contains("julcAtLeastSigned 2"), lean);
        assertTrue(lean.contains("julcNoUnexpectedSigners"), lean);
        assertTrue(lean.contains("datum.committee") || lean.contains("committee"), lean);
        assertTrue(lean.contains("txInfoSignatories"), lean);
    }

    @Test
    void canonicalizationMakesStaticAuthorityOrderIrrelevantAndIsIdempotent() {
        var compiled = new JulcCompiler().compileContract(validatorSource());
        var auth = new AuthorizationDsl();
        DslPropertySet left = candidate(compiled,
                auth.authorities(auth.fixed(KEY_B), auth.fixed(KEY_A)).atLeastSigned(1));
        DslPropertySet right = candidate(compiled,
                auth.authorities(auth.fixed(KEY_A), auth.fixed(KEY_B)).atLeastSigned(1));

        DslPropertySet normalizedLeft = DslPropertyCanonicalizer.normalize(left);
        DslPropertySet normalizedRight = DslPropertyCanonicalizer.normalize(right);
        assertEquals(PropertyIrCodec.canonicalJson(normalizedLeft),
                PropertyIrCodec.canonicalJson(normalizedRight));
        assertEquals(PropertyIrCodec.canonicalJson(normalizedLeft),
                PropertyIrCodec.canonicalJson(
                        DslPropertyCanonicalizer.normalize(normalizedLeft)));
    }

    @Test
    void rejectsMalformedStaticAuthoritiesThresholdsAndSchemaFiveForgery() {
        var compiled = new JulcCompiler().compileContract(validatorSource());
        var auth = new AuthorizationDsl();
        assertThrows(IllegalArgumentException.class,
                () -> auth.authorities());
        assertThrows(IllegalArgumentException.class,
                () -> auth.authorities(auth.fixed(KEY_A), auth.fixed(KEY_A)));

        var tooMany = new ArrayList<AuthorityExpr>();
        for (int i = 0; i <= AuthorizationDsl.MAX_STATIC_AUTHORITIES; i++) {
            tooMany.add(auth.fixed("%02x".formatted(65 + i).repeat(28)));
        }
        assertThrows(IllegalArgumentException.class,
                () -> auth.authorities(tooMany.toArray(AuthorityExpr[]::new)));

        var malformedFixed = new AuthorityKeyHashNode(AuthoritySourceKind.FIXED,
                new BytesLiteralNode(DslType.BYTE_STRING,
                        BytesLiteralKind.KEY_HASH, "00"));
        assertRejected(compiled,
                new AuthoritySetExpr(new AuthorityListNode(
                        List.of(malformedFixed))).anySigned(),
                "exactly 28 bytes");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> auth.fixed("41".repeat(10) + "00" + "41".repeat(17)))
                .getMessage().contains("symbolic translation"));
        var zeroContaining = new AuthorityKeyHashNode(AuthoritySourceKind.FIXED,
                new BytesLiteralNode(DslType.BYTE_STRING,
                        BytesLiteralKind.KEY_HASH,
                        "41".repeat(10) + "00" + "41".repeat(17)));
        assertRejected(compiled,
                new AuthoritySetExpr(new AuthorityListNode(
                        List.of(zeroContaining))).anySigned(),
                "symbolic translation");
        assertRejected(compiled,
                new BoolExpr(new AuthorizationNode(
                        AuthorizationRelation.AT_LEAST_SIGNED,
                        auth.authorities(auth.fixed(KEY_A)).node(), "-1")),
                "canonical nonnegative");
        assertRejected(compiled,
                new BoolExpr(new AuthorizationNode(
                        AuthorizationRelation.EXACTLY_SIGNED,
                        auth.authorities(auth.fixed(KEY_A)).node(), "01")),
                "canonical nonnegative");
        assertRejected(compiled,
                auth.authorities(auth.fixed(KEY_A)).atLeastSigned(17),
                "at most 16");

    }

    @Test
    void rejectsSourceImpersonationWrongContractTypeAndUnknownJson() throws Exception {
        var compiled = new JulcCompiler().compileContract(validatorSource());
        var auth = new AuthorizationDsl();
        var literalImpersonation = new AuthorityKeyHashNode(
                AuthoritySourceKind.CONTRACT_BYTES,
                new BytesLiteralNode(DslType.BYTE_STRING,
                        BytesLiteralKind.KEY_HASH, KEY_A));
        assertRejected(compiled,
                new AuthoritySetExpr(new AuthorityListNode(
                        List.of(literalImpersonation))).anySigned(),
                "cannot impersonate");

        var projection = ContractTypeProjection.project(compiled.contractSchema());
        var datum = new TypedRootNode("typedDatum",
                new com.bloxbean.cardano.julc.verification.dsl.type.OptionalTypeRef(
                        projection.datumType()));
        var wrong = new AuthorityKeyHashNode(AuthoritySourceKind.CONTRACT_BYTES,
                datum);
        assertRejected(compiled,
                new AuthoritySetExpr(new AuthorityListNode(List.of(wrong))).anySigned(),
                "byte string");

        String canonical = PropertyIrCodec.canonicalJson(candidate(compiled,
                auth.authorities(auth.fixed(KEY_A)).anySigned()));
        String unknownField = canonical.replaceFirst(
                "\\\"relation\\\":\\\"ANY_SIGNED\\\"",
                "\\\"relation\\\":\\\"ANY_SIGNED\\\",\\\"rawLean\\\":\\\"true\\\"");
        assertThrows(Exception.class,
                () -> PropertyIrCodec.readCanonical(unknownField,
                        PropertyIrCodec.MAX_CANONICAL_BYTES));
        String unknownSource = canonical.replace("\"FIXED\"",
                "\"APPLIED_PARAMETER\"");
        assertThrows(Exception.class,
                () -> PropertyIrCodec.readCanonical(unknownSource,
                        PropertyIrCodec.MAX_CANONICAL_BYTES));
    }

    @Test
    void authorizationComposesAcrossEveryCurrentlyVerifiablePurpose() {
        for (DslPurpose purpose : DslPurpose.values()) {
            var compiled = new JulcCompiler().compileContract(
                    purposeValidatorSource(purpose));
            var auth = new AuthorizationDsl();
            String hash = ContractTypeProjection.sha256(
                    ContractTypeProjection.project(compiled.contractSchema()));
            var candidate = DslPropertySet.schema1(purpose, hash,
                    VerificationDsl.property("authorization." + purpose.name().toLowerCase(),
                            DslDomain.NONE,
                            auth.authorities(auth.fixed(KEY_A), auth.fixed(KEY_B))
                                    .anySigned()
                                    .and(auth.noSigners().not())));

            var promoted = ComposedDslPromotion.promote(candidate,
                    compiled.contractSchema(), "PurposeGate", "PurposeSpec.java");
            assertEquals(purpose.name().toLowerCase(), promoted.scriptPurpose());
            assertTrue(promoted.claims().getFirst().capabilities()
                    .contains("field.txInfo.signatories"));
            assertTrue(promoted.guaranteeRules().contains(
                    "authorization:ANY_SIGNED"));
        }
    }

    @Test
    void authorizationComposesUnderStrictActionVariantGuards() throws Exception {
        var compiled = new JulcCompiler().compileContract("""
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                @SpendingValidator class ActionGate {
                    record Datum(byte[] owner) {}
                    sealed interface Redeemer permits Spend, Recover {}
                    record Spend() implements Redeemer {}
                    record Recover(byte[] recovery) implements Redeemer {}
                    @Entrypoint static boolean validate(
                            Datum datum, Redeemer redeemer, ScriptContext context) {
                        return true;
                    }
                }
                """);
        Path sources = tempDir.resolve("action/evidence");
        Files.createDirectories(sources);
        Path model = sources.resolve("ActionModel.java");
        Files.writeString(model, ContractMetamodelGenerator.generate(
                compiled.contractSchema(), "evidence", "ActionModel"));
        Path specification = sources.resolve("ActionProperties.java");
        Files.writeString(specification, """
                package evidence;
                import com.bloxbean.cardano.julc.verification.dsl.*;
                import com.bloxbean.cardano.julc.verification.dsl.ir.*;
                import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.*;
                public final class ActionProperties implements VerificationSpecification {
                    public DslPropertySet properties() {
                        var contract = new ActionModel();
                        var auth = contract.authorization();
                        return contract.properties(property("authorization.by-action",
                            DslDomain.NONE, contract.datum().exists(datum ->
                              contract.redeemer().exists(action ->
                                action.whenSpend(spend -> auth.authorities(
                                    auth.fromContractBytes(datum.owner())).exactSignerSet())
                                .or(action.whenRecover(recover -> auth.authorities(
                                    auth.fromContractBytes(recover.recovery())).anySigned()))))));
                    }
                }
                """);
        Path classes = compile(model, specification);
        DslPropertySet candidate = new DslWorkerRunner().run(
                classes + File.pathSeparator + System.getProperty("java.class.path"),
                "evidence.ActionProperties", compiled.contractSchema(),
                tempDir.resolve("action-worker"), Duration.ofSeconds(10));

        String canonical = PropertyIrCodec.canonicalJson(candidate);
        assertTrue(canonical.contains("variant-when"), canonical);
        assertTrue(canonical.contains("EXACT_SIGNER_SET"), canonical);
        assertTrue(canonical.contains("ANY_SIGNED"), canonical);
        ComposedDslPromotion.promote(candidate, compiled.contractSchema(),
                "ActionGate", "ActionProperties.java");
    }

    private static DslPropertySet candidate(
            ContractCompileResult compiled,
            BoolExpr expression) {
        String hash = ContractTypeProjection.sha256(
                ContractTypeProjection.project(compiled.contractSchema()));
        return DslPropertySet.schema1(DslPurpose.SPENDING, hash,
                VerificationDsl.property("authorization.test", DslDomain.NONE,
                        expression));
    }

    private static void assertRejected(
            ContractCompileResult compiled,
            BoolExpr expression,
            String message) {
        var invalid = candidate(compiled, expression);
        var rejection = assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(
                        invalid, compiled.contractSchema(), 1_000));
        assertTrue(rejection.getMessage().contains(message),
                rejection.getMessage());
    }

    private Path compile(Path... sources) throws Exception {
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
            assertTrue(result, "Generated schema-6 model and specification must compile");
        }
        return classes;
    }

    private static String validatorSource() {
        return """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.util.List;
                @SpendingValidator class AuthorizationGate {
                    record Datum(byte[] owner, byte[] recovery, List<byte[]> committee) {}
                    record Redeemer(byte[] delegate) {}
                    @Entrypoint static boolean validate(
                            Datum datum, Redeemer redeemer, ScriptContext context) {
                        return true;
                    }
                }
                """;
    }

    private static String purposeValidatorSource(DslPurpose purpose) {
        String annotation = switch (purpose) {
            case SPENDING -> "SpendingValidator";
            case MINTING -> "MintingValidator";
            case REWARDING -> "WithdrawValidator";
            case CERTIFYING -> "CertifyingValidator";
        };
        String parameters = purpose == DslPurpose.SPENDING
                ? "Datum datum, Redeemer redeemer, ScriptContext context"
                : "Redeemer redeemer, ScriptContext context";
        return """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                @%s class PurposeGate {
                    record Datum() {}
                    record Redeemer() {}
                    @Entrypoint static boolean validate(%s) { return true; }
                }
                """.formatted(annotation, parameters);
    }
}
