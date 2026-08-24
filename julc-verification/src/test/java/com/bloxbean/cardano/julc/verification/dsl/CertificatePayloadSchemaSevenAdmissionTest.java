package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.compiler.schema.ContractCompileResult;
import com.bloxbean.cardano.julc.verification.ComposedDslProperty;
import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import com.bloxbean.cardano.julc.verification.dsl.type.ContractTypeProjection;
import com.bloxbean.cardano.julc.verification.dsl.worker.DslWorkerRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CertificatePayloadSchemaSevenAdmissionTest {
    @TempDir Path tempDir;

    @Test
    void generatedSchemaSevenModelCompilesAndRunsInBoundedWorker() throws Exception {
        var compiled = compileCertifying();
        Path sources = tempDir.resolve("sources/evidence");
        Files.createDirectories(sources);
        Path model = sources.resolve("CertificateModel.java");
        Files.writeString(model, ContractMetamodelGenerator.generateTypedV7(
                compiled.contractSchema(), "evidence", "CertificateModel"));
        Path specification = sources.resolve("CertificateProperties.java");
        Files.writeString(specification, """
                package evidence;
                import com.bloxbean.cardano.julc.verification.dsl.*;
                import com.bloxbean.cardano.julc.verification.dsl.ir.*;
                import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.*;
                public final class CertificateProperties implements VerificationSpecification {
                    public DslPropertySet properties() {
                        var contract = new CertificateModel();
                        var guarantee = contract.certificate().whenPoolRetire((pool, epoch) ->
                            epoch.ge(integer(10)).and(contract.context().txInfo()
                                .certificates().containsAt(
                                    contract.certificateIndex(), contract.certificate())));
                        return contract.properties(property("certificate.pool-retirement",
                            DslDomain.VALID_CERTIFYING_V3_PINNED, guarantee));
                    }
                }
                """);
        Path classes = compileJava(model, specification);
        DslPropertySet candidate = new DslWorkerRunner().run(
                classes + File.pathSeparator + System.getProperty("java.class.path"),
                "evidence.CertificateProperties", compiled.contractSchema(),
                tempDir.resolve("worker"), Duration.ofSeconds(10));

        assertEquals(DslPropertySet.CERTIFICATE_PAYLOAD_SCHEMA_VERSION,
                candidate.schemaVersion());
        ComposedDslPromotion.promote(candidate, compiled.contractSchema(),
                "CertificatePayloadGate", "CertificateProperties.java");
    }

    @Test
    void admitsEveryPinnedCertificatePayloadAndNestedDelegationConstructor() {
        var compiled = compileCertifying();
        var certificate = LedgerExpressions.currentCertificate();
        var txCertificates = LedgerExpressions.context().txInfo().certificates();

        BoolExpr guarantee = certificate.whenRegStaking((credential, deposit) ->
                credential.isPubKey().and(deposit.isEmpty().or(
                        deposit.exists(amount -> amount.ge(VerificationDsl.integer(0))))))
                .or(certificate.whenUnRegStaking((credential, refund) ->
                        credential.isScript().and(refund.isPresent())))
                .or(certificate.whenDelegStaking((credential, delegatee) ->
                        credential.isPubKey().and(delegatee.whenStake(pool ->
                                pool.eq(pool)))))
                .or(certificate.whenRegDeleg((credential, delegatee, deposit) ->
                        credential.isScript()
                                .and(delegatee.whenVote(drep -> drep.isAlwaysAbstain()
                                        .or(drep.isAlwaysNoConfidence())
                                        .or(drep.whenCredential(
                                                drepCredential ->
                                                        drepCredential.isPubKey()))))
                                .and(deposit.gt(VerificationDsl.integer(0)))))
                .or(certificate.whenRegDRep((credential, deposit) ->
                        credential.isPubKey()
                                .and(deposit.ge(VerificationDsl.integer(0)))))
                .or(certificate.whenUpdateDRep(credential -> credential.isScript()))
                .or(certificate.whenUnRegDRep((credential, refund) ->
                        credential.isPubKey()
                                .and(refund.ge(VerificationDsl.integer(0)))))
                .or(certificate.whenPoolRegister((pool, vrf) ->
                        pool.eq(pool).and(vrf.eq(vrf))))
                .or(certificate.whenPoolRetire((pool, epoch) ->
                        pool.eq(pool).and(epoch.ge(VerificationDsl.integer(0)))))
                .or(certificate.whenAuthHotCommittee((cold, hot) ->
                        cold.isPubKey().and(hot.isScript())))
                .or(certificate.whenResignColdCommittee(
                        credential -> credential.isPubKey()))
                .and(txCertificates.exists(candidate ->
                        candidate.whenDelegStaking((credential, delegatee) ->
                                credential.isPubKey().and(delegatee.whenStakeVote(
                                        (pool, drep) -> pool.eq(pool)
                                                .and(drep.isAlwaysAbstain()))))))
                .and(txCertificates.containsAt(
                        LedgerExpressions.currentCertificateIndex(), certificate));

        var candidate = candidate(compiled, guarantee);
        DslPropertyValidator.validate(candidate, compiled.contractSchema(), 10_000);
        var promoted = ComposedDslPromotion.promote(candidate,
                compiled.contractSchema(), "CertificatePayloadGate",
                "CertificatePayloadSpec.java");
        assertEquals(candidate.schemaVersion(),
                ComposedDslPromotion.verifyIntegrity(promoted).schemaVersion());

        assertEquals(ComposedDslProperty.LEDGER_SCHEMA_VERSION,
                promoted.schemaVersion());
        assertEquals(11, promoted.claims().getFirst().capabilities().stream()
                .filter(capability -> capability.startsWith("constructor.txCert."))
                .count());
        assertTrue(promoted.claims().getFirst().capabilities()
                .contains("constructor.delegatee.stakeVote"));
        assertTrue(promoted.claims().getFirst().capabilities()
                .contains("constructor.drep.abstain"));
        String lean = TypedPropertyLeanRenderer.renderExpression(
                candidate.properties().getFirst().expression(),
                ContractTypeProjection.project(compiled.contractSchema()));
        assertTrue(lean.contains("certificateOf ctx"), lean);
        assertTrue(lean.contains(".TxCertRegDeleg"), lean);
        assertTrue(lean.contains(".DelegStakeVote"), lean);
        assertTrue(lean.contains(".DRepAlwaysAbstain"), lean);
        assertTrue(lean.contains("txInfoTxCerts"), lean);
        assertTrue(lean.contains("julcListAt"), lean);
    }

    @Test
    void promotedSchemaSevenPayloadMetadataCannotBeRewrittenConsistentlyByAccident() {
        var compiled = compileCertifying();
        var valid = ComposedDslPromotion.promote(candidate(compiled,
                        LedgerExpressions.currentCertificate().whenPoolRetire(
                                (pool, epoch) -> epoch.eq(VerificationDsl.integer(8)))),
                compiled.contractSchema(), "CertificatePayloadGate",
                "CertificatePayloadSpec.java");
        var tampered = new ComposedDslProperty(
                valid.schemaVersion(), valid.template(), valid.propertyId(),
                valid.validatorTitle(), valid.scriptPurpose(), valid.sourcePath(),
                valid.canonicalDslJson().replace(
                        "TxCertPoolRetire", "TxCertPoolRegister"),
                valid.claims(), valid.domainAssumptions(), valid.guaranteeRules(),
                valid.ledgerValidityModeled(), valid.projectedContractTypesJson(),
                valid.contractSchemaSha256());

        assertThrows(IllegalArgumentException.class,
                () -> ComposedDslPromotion.verifyIntegrity(tampered));
    }

    @Test
    void schemaSixAndUnguardedOrMistypedPayloadsFailClosed() {
        var compiled = compileCertifying();
        var certificate = LedgerExpressions.currentCertificate();
        BoolExpr guarded = certificate.whenPoolRetire((pool, epoch) ->
                pool.eq(pool).and(epoch.ge(VerificationDsl.integer(0))));
        String hash = ContractTypeProjection.sha256(
                ContractTypeProjection.project(compiled.contractSchema()));
        var schemaSix = DslPropertySet.typedV6(DslPurpose.CERTIFYING, hash,
                VerificationDsl.property("certificate.schema-six", DslDomain.NONE,
                        guarded));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(
                        schemaSix, compiled.contractSchema(), 1_000))
                .getMessage().contains("schema-7"));

        var unguarded = new LedgerVariantFieldNode(certificate.node(),
                LedgerTypeAuthority.TX_CERT, "TxCertPoolRetire", "epoch",
                LedgerTypeAuthority.INTEGER);
        assertRejected(compiled, new IntegerExpr(unguarded)
                .ge(VerificationDsl.integer(0)), "guarded constructor binder");

        var forged = BinderScope.bind(variable -> {
            var bound = new TypedVariableNode(variable, LedgerTypeAuthority.TX_CERT);
            var wrong = new LedgerVariantFieldNode(bound,
                    LedgerTypeAuthority.TX_CERT, "TxCertPoolRetire", "epoch",
                    LedgerTypeAuthority.PUB_KEY_HASH);
            return new BoolExpr(new LedgerVariantWhenNode(certificate.node(),
                    LedgerTypeAuthority.TX_CERT, "TxCertPoolRetire", variable,
                    new TypedEqualityNode(wrong, wrong,
                            LedgerTypeAuthority.PUB_KEY_HASH, false)));
        });
        assertRejected(compiled, forged, "does not match pinned model");

        assertRoleCannotBeForged(LedgerDRepCredentialExpr.class);
        assertRoleCannotBeForged(LedgerColdCommitteeCredentialExpr.class);
        assertRoleCannotBeForged(LedgerHotCommitteeCredentialExpr.class);
    }

    private static void assertRoleCannotBeForged(Class<?> role) {
        assertTrue(java.util.Arrays.stream(role.getDeclaredConstructors())
                .noneMatch(constructor -> Modifier.isPublic(constructor.getModifiers())),
                () -> role.getSimpleName() + " must only come from a guarded eliminator");
    }

    @Test
    void schemaSevenCanonicalizationIsStableAndUnknownJsonFails() throws Exception {
        var compiled = compileCertifying();
        var certificate = LedgerExpressions.currentCertificate();
        var first = candidate(compiled, certificate.whenPoolRegister(
                (pool, vrf) -> pool.eq(pool).and(vrf.eq(vrf))));
        var second = DslPropertyCanonicalizer.normalize(first);
        assertEquals(PropertyIrCodec.canonicalJson(second),
                PropertyIrCodec.canonicalJson(
                        DslPropertyCanonicalizer.normalize(second)));
        String canonical = PropertyIrCodec.canonicalJson(second);
        String tampered = canonical.replaceFirst(
                "\\\"constructor\\\":\\\"TxCertPoolRegister\\\"",
                "\\\"constructor\\\":\\\"TxCertPoolRegister\\\","
                        + "\\\"rawLean\\\":\\\"true\\\"");
        assertThrows(Exception.class, () -> PropertyIrCodec.readCanonical(
                tampered, PropertyIrCodec.MAX_CANONICAL_BYTES));

        BoolExpr helper = certificate.whenPoolRetire((pool, epoch) ->
                epoch.eq(VerificationDsl.integer(8)));
        BoolExpr manual = BinderScope.bind(variable -> {
            var bound = new TypedVariableNode(variable, LedgerTypeAuthority.TX_CERT);
            var epoch = new LedgerVariantFieldNode(bound,
                    LedgerTypeAuthority.TX_CERT, "TxCertPoolRetire", "epoch",
                    LedgerTypeAuthority.INTEGER);
            return new BoolExpr(new LedgerVariantWhenNode(certificate.node(),
                    LedgerTypeAuthority.TX_CERT, "TxCertPoolRetire", variable,
                    new CompareNode(CompareOperator.EQ, epoch,
                            VerificationDsl.integer(8).node())));
        });
        assertEquals(PropertyIrCodec.canonicalJson(
                        DslPropertyCanonicalizer.normalize(candidate(compiled, helper))),
                PropertyIrCodec.canonicalJson(
                        DslPropertyCanonicalizer.normalize(candidate(compiled, manual))),
                "Guarded helper and reviewed primitive composition must share canonical IR");
    }

    private static DslPropertySet candidate(
            ContractCompileResult compiled, BoolExpr guarantee) {
        String hash = ContractTypeProjection.sha256(
                ContractTypeProjection.project(compiled.contractSchema()));
        return DslPropertySet.typedV7(DslPurpose.CERTIFYING, hash,
                VerificationDsl.property("certificate.payloads",
                        DslDomain.VALID_CERTIFYING_V3_PINNED, guarantee));
    }

    private static void assertRejected(
            ContractCompileResult compiled, BoolExpr guarantee, String message) {
        var rejection = assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(
                        candidate(compiled, guarantee),
                        compiled.contractSchema(), 1_000));
        assertTrue(rejection.getMessage().contains(message), rejection.getMessage());
    }

    private static ContractCompileResult compileCertifying() {
        return new JulcCompiler().compileContract("""
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                @CertifyingValidator class CertificatePayloadGate {
                    record Redeemer() {}
                    @Entrypoint static boolean validate(
                            Redeemer redeemer, ScriptContext context) { return true; }
                }
                """);
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
            assertTrue(result, "Generated schema-7 model and specification must compile");
        }
        return classes;
    }
}
