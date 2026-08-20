package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.compiler.schema.ContractSchema;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.*;
import static org.junit.jupiter.api.Assertions.*;

class ComposedDslAdmissionTest {
    private static final String AUTHORITY_A =
            "4a554c435f5645524946595f415554484f524954595f303030303031";
    private static final String AUTHORITY_B =
            "4a554c435f5645524946595f415554484f524954595f303030303032";
    private static final String TX_ID =
            "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20";

    @TempDir
    Path tempDir;

    @Test
    void schemaThreeAdmitsNovelSupportedSpendingComposition() {
        var model = new SpendingContractModel();
        var sellerPaid = model.context().txInfo().outputs().exists(output ->
                output.address().credential().matchesKeyHash(
                                model.datum().bytesField("owner"))
                        .and(output.value().lovelace().ge(
                                model.datum().integerField("minimum"))));
        var eitherAuthority = model.context().txInfo().signatories().contains(
                        keyHash(AUTHORITY_A))
                .or(model.context().txInfo().signatories().contains(
                        keyHash(AUTHORITY_B)));
        var candidate = DslPropertySet.composed(DslPurpose.SPENDING,
                property("Gate.paid-or-authorized",
                        DslDomain.VALID_SPENDING_V3_PINNED,
                        sellerPaid.or(eitherAuthority)));

        DslPropertySet normalized = DslPropertyValidator.validateAndNormalize(
                candidate, spendingSchema(), 10_000);

        assertEquals(3, normalized.schemaVersion());
        assertEquals(DslPurpose.SPENDING, normalized.purpose());
        assertEquals(DslDomain.VALID_SPENDING_V3_PINNED,
                normalized.properties().getFirst().domain());
        assertFalse(PropertyIrCodec.canonicalJson(normalized)
                .contains("exactUplcSucceeds"));
    }

    @Test
    void schemaThreeAdmitsNovelSupportedMintingComposition() {
        var model = new MintingContractModel();
        var quantity = integer(2);
        var guarantee = model.redeemerStrictlyDecodes()
                .and(model.context().txInfo().signatories().contains(keyHash(AUTHORITY_A)))
                .and(model.context().txInfo().inputs().consumes(txOutRef(TX_ID, 1)))
                .and(model.context().txInfo().mint().exactOwnPolicyAsset(
                        model.ownPolicy(), tokenName("4a554c43"), quantity))
                .and(quantity.gt(integer(0))
                        .or(quantity.eq(integer(0))));
        var candidate = DslPropertySet.composed(DslPurpose.MINTING,
                property("TokenPolicy.composed", DslDomain.VALID_MINTING_V3_PINNED,
                        guarantee));

        DslPropertySet normalized = DslPropertyValidator.validateAndNormalize(
                candidate, mintingSchema(), 10_000);

        assertEquals(DslPurpose.MINTING, normalized.purpose());
        assertTrue(PropertyIrCodec.canonicalJson(normalized)
                .contains("exact-own-policy-asset"));
    }

    @Test
    void schemaThreeAdmitsRewardingCredentialAmountAndSignerComposition() {
        var model = new RewardingContractModel();
        var matchingWithdrawal = model.context().txInfo().withdrawals().exists(entry ->
                entry.credential().eq(model.rewardingCredential())
                        .and(entry.amount().ge(integer(1_000_000))));
        var guarantee = matchingWithdrawal.and(
                model.context().txInfo().signatories().contains(keyHash(AUTHORITY_A)));
        var candidate = DslPropertySet.composed(DslPurpose.REWARDING,
                property("Rewards.authorized", DslDomain.VALID_REWARDING_V3_PINNED,
                        guarantee));

        DslPropertySet normalized = DslPropertyValidator.validateAndNormalize(
                candidate, rewardingSchema(), 10_000);
        var promoted = ComposedDslPromotion.promote(
                normalized, rewardingSchema(), "Rewards", "RewardProperties.java");

        assertEquals(DslPurpose.REWARDING, normalized.purpose());
        assertEquals("rewarding", promoted.scriptPurpose());
        assertEquals("BLASTER_VALID_REWARDING_SUPERSET",
                promoted.claims().getFirst().counterexampleDomain());
        assertTrue(promoted.claims().getFirst().capabilities()
                .contains("field.txInfo.withdrawals"));
        assertTrue(promoted.claims().getFirst().capabilities()
                .contains("ledger.validRewardingContext"));
        assertTrue(PropertyLeanRenderer.renderExpression(
                normalized.properties().getFirst().expression())
                .contains("txInfoWdrl"));
    }

    @Test
    void rewardingNodesFailClosedForOtherPurposes() {
        var rewarding = new RewardingContractModel();
        var wrongPurpose = DslPropertySet.composed(DslPurpose.SPENDING,
                property("wrong-rewarding-root", DslDomain.NONE,
                        rewarding.context().txInfo().withdrawals().exists(entry ->
                                entry.credential().eq(rewarding.rewardingCredential()))));

        var error = assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(wrongPurpose, spendingSchema(), 100));
        assertTrue(error.getMessage().contains("withdrawals")
                || error.getMessage().contains("rewardingCredential"));
    }

    @Test
    void rewardingMetamodelExposesOnlyReviewedPurposeRoots() {
        String source = ContractMetamodelGenerator.generate(
                rewardingSchema(), "evidence", "RewardsModel");

        assertTrue(source.contains("RewardingContractModel"));
        assertTrue(source.contains("CredentialExpr rewardingCredential()"));
        assertTrue(source.contains("BoolExpr redeemerStrictlyDecodes()"));
        assertFalse(source.contains("datum()"));
        assertFalse(source.contains("ownPolicy()"));
    }

    @Test
    void schemaThreeAdmitsCertifyingKindIndexAndSignerComposition() {
        var model = new CertifyingContractModel();
        var known = model.context().txInfo().certificates().containsAt(
                model.certificateIndex(), model.certificate());
        var guarantee = model.redeemerStrictlyDecodes()
                .and(model.certificate().isKind(TxCertKind.UPDATE_DREP))
                .and(known)
                .and(model.context().txInfo().signatories()
                        .contains(keyHash(AUTHORITY_A)));
        var candidate = DslPropertySet.composed(DslPurpose.CERTIFYING,
                property("Certificates.authorized-update",
                        DslDomain.VALID_CERTIFYING_V3_PINNED, guarantee));

        DslPropertySet normalized = DslPropertyValidator.validateAndNormalize(
                candidate, certifyingSchema(), 10_000);
        var promoted = ComposedDslPromotion.promote(
                normalized, certifyingSchema(), "Certificates", "CertSpec.java");

        assertEquals(DslPurpose.CERTIFYING, normalized.purpose());
        assertEquals("certifying", promoted.scriptPurpose());
        assertEquals("BLASTER_VALID_CERTIFYING_SUPERSET",
                promoted.claims().getFirst().counterexampleDomain());
        assertTrue(promoted.claims().getFirst().capabilities()
                .contains("helper.isKnownCertificate"));
        assertTrue(promoted.claims().getFirst().capabilities()
                .contains("ledger.validCertifyingContext"));
        String lean = PropertyLeanRenderer.renderExpression(
                normalized.properties().getFirst().expression());
        assertTrue(lean.contains("TxCertUpdateDRep"));
        assertTrue(lean.contains("isKnownCertificate"));
    }

    @Test
    void certifyingNodesAndUnknownKindsFailClosed() {
        var certifying = new CertifyingContractModel();
        var wrongPurpose = DslPropertySet.composed(DslPurpose.SPENDING,
                property("wrong-certifying-root", DslDomain.NONE,
                        certifying.certificate().isKind(TxCertKind.UPDATE_DREP)));
        assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(
                        wrongPurpose, spendingSchema(), 100));

        var valid = DslPropertySet.composed(DslPurpose.CERTIFYING,
                property("Certificates.kind", DslDomain.NONE,
                        certifying.certificate().isKind(TxCertKind.UPDATE_DREP)));
        String forged = PropertyIrCodec.canonicalJson(valid)
                .replace("UPDATE_DREP", "FUTURE_CERTIFICATE");
        assertThrows(java.io.IOException.class,
                () -> PropertyIrCodec.readCanonical(forged, 1_000_000));
    }

    @Test
    void certifyingMetamodelExposesOnlyReviewedPurposeRoots() {
        String source = ContractMetamodelGenerator.generate(
                certifyingSchema(), "evidence", "CertificateModel");

        assertTrue(source.contains("CertifyingContractModel"));
        assertTrue(source.contains("TxCertExpr certificate()"));
        assertTrue(source.contains("IntegerExpr certificateIndex()"));
        assertTrue(source.contains("BoolExpr redeemerStrictlyDecodes()"));
        assertFalse(source.contains("datum()"));
        assertFalse(source.contains("ownPolicy()"));
    }

    @Test
    void andOrAssociationOrderingAndDuplicatesCanonicalizeIdentically() {
        var model = new SpendingContractModel();
        BoolExpr first = model.context().txInfo().signatories().contains(keyHash(AUTHORITY_A));
        BoolExpr second = model.context().txInfo().signatories().contains(keyHash(AUTHORITY_B));
        BoolExpr third = model.datum().integerField("minimum").ge(integer(0));

        var left = DslPropertySet.composed(DslPurpose.SPENDING,
                property("Gate.canonical", DslDomain.NONE,
                        first.and(second.and(third)).and(first)));
        var right = DslPropertySet.composed(DslPurpose.SPENDING,
                property("Gate.canonical", DslDomain.NONE,
                        third.and(first).and(second)));

        DslPropertySet normalizedLeft = DslPropertyValidator.validateAndNormalize(
                left, spendingSchema(), 10_000);
        DslPropertySet normalizedRight = DslPropertyValidator.validateAndNormalize(
                right, spendingSchema(), 10_000);

        assertEquals(PropertyIrCodec.canonicalJson(normalizedLeft),
                PropertyIrCodec.canonicalJson(normalizedRight));
        assertNotEquals(PropertyIrCodec.canonicalJson(left),
                PropertyIrCodec.canonicalJson(normalizedLeft));
    }

    @Test
    void canonicalizationIsIdempotentWhenDeduplicationExposesSameOperator() {
        var model = new SpendingContractModel();
        BoolExpr first = model.context().txInfo().signatories().contains(keyHash(AUTHORITY_A));
        BoolExpr second = model.context().txInfo().signatories().contains(keyHash(AUTHORITY_B));
        BoolExpr third = model.datum().integerField("minimum").ge(integer(0));
        BoolExpr duplicatedConjunction = first.and(second).or(first.and(second));
        var nested = DslPropertySet.composed(DslPurpose.SPENDING,
                property("Gate.idempotent", DslDomain.NONE,
                        duplicatedConjunction.and(third)));
        var flat = DslPropertySet.composed(DslPurpose.SPENDING,
                property("Gate.idempotent", DslDomain.NONE,
                        first.and(second).and(third)));

        DslPropertySet normalized = DslPropertyValidator.validateAndNormalize(
                nested, spendingSchema(), 10_000);
        DslPropertySet normalizedAgain = DslPropertyCanonicalizer.normalize(normalized);
        DslPropertySet normalizedFlat = DslPropertyValidator.validateAndNormalize(
                flat, spendingSchema(), 10_000);

        assertEquals(PropertyIrCodec.canonicalJson(normalized),
                PropertyIrCodec.canonicalJson(normalizedAgain));
        assertEquals(PropertyIrCodec.canonicalJson(normalizedFlat),
                PropertyIrCodec.canonicalJson(normalized));
    }

    @Test
    void multiplePropertiesAreSortedButRemainIndependent() {
        var model = new SpendingContractModel();
        var candidate = DslPropertySet.composed(DslPurpose.SPENDING,
                property("z-last", DslDomain.NONE,
                        model.context().txInfo().signatories().contains(keyHash(AUTHORITY_A))),
                property("a-first", DslDomain.VALID_SPENDING_V3_PINNED,
                        model.datum().integerField("minimum").ge(integer(0))));

        DslPropertySet normalized = DslPropertyValidator.validateAndNormalize(
                candidate, spendingSchema(), 10_000);

        assertEquals(List.of("a-first", "z-last"), normalized.properties().stream()
                .map(DslProperty::id).toList());
        assertEquals(2, normalized.properties().size());
    }

    @Test
    void genericPromotionDerivesIndependentClaimsCapabilitiesAndHashes() {
        var model = new SpendingContractModel();
        var candidate = DslPropertySet.composed(DslPurpose.SPENDING,
                property("z-signer", DslDomain.NONE,
                        model.context().txInfo().signatories().contains(keyHash(AUTHORITY_A))),
                property("a-payment", DslDomain.VALID_SPENDING_V3_PINNED,
                        model.context().txInfo().outputs().exists(output ->
                                output.address().credential().matchesKeyHash(
                                        model.datum().bytesField("owner"))
                                .and(output.value().lovelace().ge(
                                        model.datum().integerField("minimum"))))));

        var promoted = ComposedDslPromotion.promote(
                candidate, spendingSchema(), "Gate", "SecurityProperties.java");

        assertEquals("julc.dsl-composed/v1", promoted.template());
        assertEquals(List.of("a-payment", "z-signer"), promoted.claims().stream()
                .map(com.bloxbean.cardano.julc.verification.ComposedDslProperty.Claim::id)
                .toList());
        assertEquals(2, promoted.claims().stream()
                .map(com.bloxbean.cardano.julc.verification.ComposedDslProperty.Claim::guaranteeSha256)
                .distinct().count());
        assertTrue(promoted.claims().getFirst().capabilities()
                .contains("ledger.validSpendingContext"));
        assertTrue(promoted.claims().getFirst().capabilities()
                .contains("dsl.schema.datum.field"));
        assertEquals(PropertyIrCodec.canonicalJson(
                        DslPropertyValidator.validateAndNormalize(
                                candidate, spendingSchema(), 10_000)),
                promoted.canonicalDslJson());
    }

    @Test
    void reviewedHelpersAndManualCompositionHaveIdenticalGenericSemantics() {
        var spending = new SpendingContractModel();
        var manualPayment = DslPropertySet.composed(DslPurpose.SPENDING,
                property("sale.paid", DslDomain.VALID_SPENDING_V3_PINNED,
                        spending.context().txInfo().outputs().exists(output ->
                                output.address().credential().matchesKeyHash(
                                                spending.datum().bytesField("owner"))
                                        .and(output.value().lovelace().ge(
                                                spending.datum().integerField("minimum"))))));
        var helperPayment = SellerPaymentDsl.composedPropertySet(
                "sale.paid", "owner", "minimum");

        var minting = new MintingContractModel();
        var manualMint = DslPropertySet.composed(DslPurpose.MINTING,
                property("policy.one-shot", DslDomain.VALID_MINTING_V3_PINNED,
                        minting.redeemerStrictlyDecodes()
                                .and(minting.context().txInfo().signatories()
                                        .contains(keyHash(AUTHORITY_A)))
                                .and(minting.context().txInfo().inputs()
                                        .consumes(txOutRef(TX_ID, 1)))
                                .and(minting.context().txInfo().mint()
                                        .exactOwnPolicyAsset(minting.ownPolicy(),
                                                tokenName("4a554c43"), integer(1)))));
        var helperMint = MintingDsl.composedOneShotPropertySet(
                "policy.one-shot", AUTHORITY_A, TX_ID, 1, "4a554c43");

        assertEquivalentPromotion(helperPayment, manualPayment, spendingSchema(), "Sale");
        assertEquivalentPromotion(helperMint, manualMint, mintingSchema(), "Policy");
    }

    @Test
    void rejectsEnvelopeRootsWrongDomainsPurposesAndGeneratedNameCollisions() {
        var spending = spendingSchema();
        var spendingModel = new SpendingContractModel();
        var hiddenExecution = DslPropertySet.composed(DslPurpose.SPENDING,
                property("hidden-execution", DslDomain.NONE,
                        spendingModel.exactUplcSucceeds()));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(hiddenExecution, spending, 100))
                .getMessage().contains("envelope root exactUplcSucceeds"));

        var hiddenDomain = DslPropertySet.composed(DslPurpose.SPENDING,
                property("hidden-domain", DslDomain.NONE,
                        spendingModel.validSpendingContext()));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(hiddenDomain, spending, 100))
                .getMessage().contains("envelope root validSpendingContext"));

        var wrongDomain = DslPropertySet.composed(DslPurpose.SPENDING,
                property("wrong-domain", DslDomain.VALID_MINTING_V3_PINNED,
                        spendingModel.datum().integerField("minimum").ge(integer(0))));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(wrongDomain, spending, 100))
                .getMessage().contains("incompatible"));

        var wrongInterface = DslPropertySet.composed(DslPurpose.MINTING,
                property("wrong-interface", DslDomain.NONE,
                        new MintingContractModel().redeemerStrictlyDecodes()));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(wrongInterface, spending, 100))
                .getMessage().contains("requires a MINT"));

        var collisions = DslPropertySet.composed(DslPurpose.SPENDING,
                property("same-name", DslDomain.NONE,
                        spendingModel.datum().integerField("minimum").ge(integer(0))),
                property("same.name", DslDomain.NONE,
                        spendingModel.datum().integerField("minimum").ge(integer(1))));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(collisions, spending, 100))
                .getMessage().contains("collide"));

        var caseInsensitiveFilesystemCollision = DslPropertySet.composed(
                DslPurpose.SPENDING,
                property("Case", DslDomain.NONE,
                        spendingModel.datum().integerField("minimum").ge(integer(0))),
                property("case", DslDomain.NONE,
                        spendingModel.datum().integerField("minimum").ge(integer(1))));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(
                        caseInsensitiveFilesystemCollision, spending, 100))
                .getMessage().contains("collide"));
    }

    @Test
    void rejectsPropertyIdsUsingReservedNonVacuitySuffix() {
        var model = new SpendingContractModel();
        var candidate = DslPropertySet.composed(DslPurpose.SPENDING,
                property("sale.PAID.NON-VACUITY", DslDomain.NONE,
                        model.datum().integerField("minimum").ge(integer(0))));

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(candidate, spendingSchema(), 100))
                .getMessage().contains("reserved runner suffix"));
    }

    @Test
    void rejectsMintOnlySemanticNodeForSpendingPurpose() {
        var model = new SpendingContractModel();
        PropertyNode context = model.context().node();
        PropertyNode txInfo = new FieldNode(context, "txInfo", DslType.TX_INFO);
        PropertyNode forgedMint = new FieldNode(txInfo, "mint", DslType.MINT_VALUE);
        var property = DslPropertySet.composed(DslPurpose.SPENDING,
                new DslProperty("mint-in-spending", DslDomain.NONE,
                        new ExactOwnPolicyAssetNode(forgedMint,
                                new BytesLiteralNode(DslType.POLICY_ID,
                                        BytesLiteralKind.POLICY_ID, AUTHORITY_A),
                                new BytesLiteralNode(DslType.BYTE_STRING,
                                        BytesLiteralKind.TOKEN_NAME, ""),
                                new LiteralNode(DslType.INTEGER, "1"))));

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DslPropertyValidator.validate(property, spendingSchema(), 100))
                .getMessage().contains("requires a MINT"));
    }

    @Test
    void schemaOneAndTwoCanonicalBytesRemainFrozen() {
        var root = new RootNode("exactUplcSucceeds", DslType.BOOL);
        String schemaOne = PropertyIrCodec.canonicalJson(
                DslPropertySet.of(new DslProperty("legacy", root)));
        String schemaTwo = PropertyIrCodec.canonicalJson(
                DslPropertySet.minting(new DslProperty("legacy", root)));

        assertEquals("{\"properties\":[{\"expression\":{\"op\":\"root\","
                + "\"name\":\"exactUplcSucceeds\",\"resultType\":\"BOOL\"},"
                + "\"id\":\"legacy\"}],\"schemaVersion\":1}", schemaOne);
        assertEquals(schemaOne.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                schemaTwo);
    }

    @Test
    void schemaThreeStrictCodecRejectsUnknownFields() throws Exception {
        var model = new SpendingContractModel();
        var candidate = DslPropertySet.composed(DslPurpose.SPENDING,
                property("strict", DslDomain.NONE,
                        model.datum().integerField("minimum").ge(integer(0))));
        String json = PropertyIrCodec.canonicalJson(candidate);
        Path unknown = tempDir.resolve("unknown.json");
        Files.writeString(unknown,
                json.substring(0, json.length() - 1) + ",\"rawLean\":\"False\"}",
                StandardCharsets.UTF_8);

        assertThrows(java.io.IOException.class,
                () -> PropertyIrCodec.read(unknown, 1_000_000));
    }

    private static ContractSchema spendingSchema() {
        return compiler().compileContract("""
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                @SpendingValidator class Gate {
                    record Datum(byte[] owner, BigInteger minimum) {}
                    record Redeemer() {}
                    @Entrypoint static boolean validate(Datum d, Redeemer r, ScriptContext c) {
                        return true;
                    }
                }
                """).contractSchema();
    }

    private static void assertEquivalentPromotion(
            DslPropertySet helper,
            DslPropertySet manual,
            ContractSchema schema,
            String validator) {
        var helperProperty = ComposedDslPromotion.promote(
                helper, schema, validator, "Properties.java");
        var manualProperty = ComposedDslPromotion.promote(
                manual, schema, validator, "Properties.java");
        assertEquals(helperProperty.canonicalDslJson(), manualProperty.canonicalDslJson());
        assertEquals(helperProperty.claims(), manualProperty.claims());
    }

    private static ContractSchema mintingSchema() {
        return compiler().compileContract("""
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                @MintingValidator class TokenPolicy {
                    record Redeemer() {}
                    @Entrypoint static boolean validate(Redeemer r, ScriptContext c) {
                        return true;
                    }
                }
                """).contractSchema();
    }

    private static ContractSchema rewardingSchema() {
        return compiler().compileContract("""
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                @WithdrawValidator class Rewards {
                    record Redeemer() {}
                    @Entrypoint static boolean validate(Redeemer r, ScriptContext c) {
                        return true;
                    }
                }
                """).contractSchema();
    }

    private static ContractSchema certifyingSchema() {
        return compiler().compileContract("""
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                @CertifyingValidator class Certificates {
                    record Redeemer() {}
                    @Entrypoint static boolean validate(Redeemer r, ScriptContext c) {
                        return true;
                    }
                }
                """).contractSchema();
    }

    private static JulcCompiler compiler() {
        return new JulcCompiler(StdlibRegistry.defaultRegistry());
    }
}
