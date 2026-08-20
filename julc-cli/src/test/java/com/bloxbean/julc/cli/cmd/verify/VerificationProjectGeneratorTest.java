package com.bloxbean.julc.cli.cmd.verify;

import com.bloxbean.cardano.julc.blueprint.BlueprintConfig;
import com.bloxbean.cardano.julc.blueprint.BlueprintGenerator;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.compiler.DataBoundarySemantics;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.verification.RequiresSignerProperty;
import com.bloxbean.cardano.julc.verification.StatefulSpendingProperty;
import com.bloxbean.cardano.julc.verification.ControlledMintProperty;
import com.bloxbean.cardano.julc.verification.SellerPaymentProperty;
import com.bloxbean.cardano.julc.verification.OneShotMintProperty;
import com.bloxbean.cardano.julc.verification.dsl.ComposedDslPromotion;
import com.bloxbean.cardano.julc.verification.dsl.SpendingContractModel;
import com.bloxbean.cardano.julc.verification.dsl.MintingContractModel;
import com.bloxbean.cardano.julc.verification.dsl.RewardingContractModel;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslDomain;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPurpose;
import com.bloxbean.cardano.julc.verification.dsl.PropertyIrCodec;
import com.bloxbean.cardano.julc.verification.dsl.SellerPaymentDsl;
import com.bloxbean.cardano.julc.verification.dsl.MintingDsl;
import com.bloxbean.julc.cli.JulcCommand;
import com.bloxbean.julc.cli.cmd.blueprint.ArtifactCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.*;

class VerificationProjectGeneratorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void generatesDeterministicStrictWorkspace() throws Exception {
        Path blueprint = writeBlueprint();
        Path output = tempDir.resolve("verification");

        var result = VerificationProjectGenerator.generate(
                blueprint, "StateGate", "spending", 12345, output, false);

        assertEquals("state-gate", result.artifactId());
        assertTrue(Files.isExecutable(output.resolve("scripts/verify.sh")));
        assertTrue(Files.readString(output.resolve(".gitignore"))
                .contains("/verification-result.json"));
        assertTrue(Files.readString(output.resolve("CheckedExecution.lean"))
                .contains("defaultFunSemanticsVariantE"));
        assertTrue(Files.readString(output.resolve("CheckedExecution.lean"))
                .contains("stepExhausted"));
        assertTrue(Files.readString(output.resolve("scripts/verify.sh"))
                .contains(manifestHash(output)));
        String schemas = Files.readString(output.resolve("GeneratedSchemas.lean"));
        assertTrue(schemas.contains("structure StateDatum where"));
        assertTrue(schemas.contains("owner : ByteString"));
        assertTrue(schemas.contains("state : Integer"));
        assertTrue(schemas.contains("Data.Constr 0 [r_owner, r_state]"));
        assertTrue(schemas.contains("| _, _ => none"));

        var manifest = JSON.readTree(output.resolve("verification-manifest.json").toFile());
        assertEquals("E", manifest.path("builtinSemanticsVariant").asText());
        assertEquals(11, manifest.path("protocolVersion").asInt());
        assertEquals(12345, manifest.path("fuel").asInt());
        assertEquals(4, manifest.path("recursiveDepth").asInt());
        assertEquals(DataBoundarySemantics.STRICT_V1,
                manifest.path("boundarySemantics").asText());
        assertEquals("COULD-NOT-EVALUATE",
                manifest.path("properties").get(0).path("result").asText());

        String firstManifest = Files.readString(output.resolve("verification-manifest.json"));
        VerificationProjectGenerator.generate(
                blueprint, "StateGate", "spending", 12345, output, true);
        assertEquals(firstManifest, Files.readString(output.resolve("verification-manifest.json")));
    }

    @Test
    void generatesTypedRequiresSignerWorkspaceAndObservedResultProtocol() throws Exception {
        Path output = tempDir.resolve("requires-signer");
        var property = new RequiresSignerProperty(
                1, RequiresSignerProperty.TEMPLATE,
                "StateGate.requires-signer.owner", "StateGate", "spending",
                "datum.owner",
                List.of(
                        new RequiresSignerProperty.PathSegment(
                                "root", "datum", "record:StateDatum"),
                        new RequiresSignerProperty.PathSegment(
                                "field", "owner", "bytes")),
                "StateDatum", "bytes",
                new RequiresSignerProperty.SourceReference(
                        "StateGate.java", 4, 1, "@RequiresSigner"),
                List.of(),
                List.of("strict datum decoding", "complete signatory membership"),
                false);

        VerificationProjectGenerator.generateRequiresSigner(
                writeBlueprint(), property, 1000, 4, output, false);

        assertTrue(Files.isExecutable(output.resolve("scripts/verify.sh")));
        assertTrue(Files.isExecutable(output.resolve("scripts/verify-non-vacuity.sh")));
        String lean = Files.readString(output.resolve("SecurityProperty.lean"));
        assertTrue(lean.contains("Option JulcGenerated.Schemas.StateDatum"));
        assertTrue(lean.contains("txSignedBy datum.owner"));
        assertFalse(lean.contains("firstSignerAuthorized"));
        assertTrue(Files.readString(output.resolve("StateGateProof.lean"))
                .contains("by\n  blaster"));
        assertTrue(Files.readString(output.resolve("StateGateCounterexample.lean"))
                .contains("gen-cex: 1"));

        var plan = JSON.readTree(output.resolve("verification-runner.json").toFile());
        assertEquals(2, plan.path("schemaVersion").asInt());
        assertEquals("SMT-VALID",
                plan.path("verify").get(1).path("outcomes").get(0).path("result").asText());
        assertEquals("REFUTED",
                plan.path("verify").get(1).path("outcomes").get(1).path("result").asText());
        var manifest = JSON.readTree(output.resolve("verification-manifest.json").toFile());
        assertEquals(VerificationFiles.sha256(output.resolve("verification-property.json")),
                manifest.path("propertyIr").path("sha256").asText());
        assertEquals(VerificationFiles.leanTreeHash(output),
                manifest.path("generatedLeanSha256").asText());
        assertFalse(manifest.path("ledgerValidityModeled").asBoolean(true));

        Files.writeString(output.resolve("SecurityProperty.lean"), "stale generated property\n");
        VerificationProjectGenerator.generateRequiresSigner(
                writeBlueprint(), property, 1000, 4, output, true);
        assertFalse(Files.readString(output.resolve("SecurityProperty.lean"))
                .contains("stale generated property"));
    }

    @Test
    void generatesCompleteStatefulSpendingProfile() throws Exception {
        Path output = tempDir.resolve("stateful-spending");
        var property = new StatefulSpendingProperty(
                1, StatefulSpendingProperty.TEMPLATE,
                "StateGate.stateful-spending-v1", "StateGate", "spending",
                "datum.owner|datum.state|redeemer.nextState",
                new StatefulSpendingProperty.Selection("datum", "owner", "bytes"),
                new StatefulSpendingProperty.Selection("datum", "state", "integer"),
                new StatefulSpendingProperty.Selection(
                        "redeemer", "nextState", "integer"),
                "StateDatum", "Transition", "GREATER_THAN",
                "SINGLE_CONTINUING_OUTPUT",
                List.of(new StatefulSpendingProperty.SourceReference(
                        "Monotonic", "StateGate.java", 4, 1, "@Monotonic")),
                List.of(), List.of("complete stateful profile"), false);

        VerificationProjectGenerator.generateStatefulSpending(
                writeBlueprint(), property, 2000, 4, output, false);

        String lean = Files.readString(output.resolve("SecurityProperty.lean"));
        assertTrue(lean.contains("findOwnInput ctx"));
        assertTrue(lean.contains("Recursor.findAll out in outputs"));
        assertTrue(lean.contains("| [successor] =>"));
        assertTrue(lean.contains("successor.txOutValue ="));
        assertTrue(lean.contains("nextDatum.owner = datum.owner"));
        assertTrue(lean.contains("nextDatum.state = redeemer.nextState"));
        assertTrue(lean.contains("datum.state < redeemer.nextState"));
        assertTrue(lean.contains("txSignedBy datum.owner"));
        var plan = JSON.readTree(output.resolve("verification-runner.json").toFile());
        assertEquals("stateful-spending-v1-established",
                plan.path("verify").get(1).path("outcomes").get(0)
                        .path("reason").asText());
        var manifest = JSON.readTree(output.resolve("verification-manifest.json").toFile());
        assertEquals(StatefulSpendingProperty.TEMPLATE,
                manifest.path("propertyIr").path("template").asText());
        assertEquals(VerificationFiles.leanTreeHash(output),
                manifest.path("generatedLeanSha256").asText());
    }

    @Test
    void generatesExactControlledMintProfile() throws Exception {
        Path output = tempDir.resolve("controlled-mint");
        String propertyId = "TokenPolicy.controlled-mint-v1";
        String authority = "4a554c435f5645524946595f415554484f524954595f303030303031";
        String tokenName = "4a554c43";
        var property = new ControlledMintProperty(
                1, ControlledMintProperty.TEMPLATE,
                propertyId, "TokenPolicy", "minting",
                "authority:" + authority + "|tokenName:" + tokenName + "|quantity:1",
                authority, tokenName, "1", "MINT", "Redeemer",
                PropertyIrCodec.canonicalJson(MintingDsl.controlledMintPropertySet(
                        propertyId, authority, tokenName, "1")),
                new ControlledMintProperty.SourceReference(
                        "TokenPolicy.java", 3, 1, "@ControlledMint"),
                List.of(), List.of("exact own-policy asset"), false);

        VerificationProjectGenerator.generateControlledMint(
                writeMintBlueprint(), property, 2000, 4, output, false);

        String lean = Files.readString(output.resolve("SecurityProperty.lean"));
        assertTrue(lean.contains("IsData.fromData ctx.scriptContextRedeemer"));
        assertTrue(lean.contains("exactOwnPolicyAsset"));
        assertTrue(lean.contains("List.elem"));
        assertTrue(lean.contains("actualPolicy == policy"));
        assertTrue(lean.contains("actualToken == token"));
        assertTrue(lean.contains("actualQuantity == quantity"));
        assertTrue(lean.contains("1 > 0"));
        assertTrue(Files.readString(output.resolve("TokenPolicyObligation.lean"))
                .contains("mintingInputs"));
        var plan = JSON.readTree(output.resolve("verification-runner.json").toFile());
        assertEquals("controlled-mint-v1-established",
                plan.path("verify").get(1).path("outcomes").get(0)
                        .path("reason").asText());
        var manifest = JSON.readTree(output.resolve("verification-manifest.json").toFile());
        assertEquals(ControlledMintProperty.TEMPLATE,
                manifest.path("propertyIr").path("template").asText());
        assertEquals(VerificationFiles.leanTreeHash(output),
                manifest.path("generatedLeanSha256").asText());
    }

    @Test
    void generatesDomainAwareOneShotMintAndKernelBridge() throws Exception {
        String propertyId = "TokenPolicy.one-shot-authorized-mint";
        String authority = "4a554c435f5645524946595f415554484f524954595f303030303031";
        String txId = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20";
        String token = "4a554c43";
        var dsl = MintingDsl.oneShotPropertySet(
                propertyId, authority, txId, 0, token);
        var property = new OneShotMintProperty(
                1, OneShotMintProperty.TEMPLATE, propertyId, "TokenPolicy", "minting",
                "OneShotSpec.java", authority, txId, "0", token, "1", "Redeemer",
                PropertyIrCodec.canonicalJson(dsl),
                List.of("validMintingContext/v3-pinned"),
                List.of("one-shot authorized mint"), true);
        Path output = tempDir.resolve("one-shot-mint");

        VerificationProjectGenerator.generateOneShotMint(
                writeMintBlueprint(), property, 5000, 4, output, false);

        String security = Files.readString(output.resolve("SecurityProperty.lean"));
        assertTrue(security.contains("blasterValidMintingContext"));
        assertTrue(security.contains("utxoConsumed"));
        assertTrue(security.contains("exactOwnPolicyAsset"));
        assertTrue(security.contains("redeemerStrictlyDecodes"));
        String bridge = Files.readString(output.resolve("LedgerDomainEquivalence.lean"));
        assertTrue(bridge.contains("validMintingContext_implies_blasterDomain"));
        assertFalse(bridge.contains("sorry"));
        assertFalse(bridge.contains("admit"));
        assertTrue(Files.readString(output.resolve("TokenPolicyObligation.lean"))
                .contains("mintingInputs"));
        assertTrue(Files.readString(output.resolve("TokenPolicyLedgerCorollary.lean"))
                .contains("ledgerValidSuccessfulImpliesOneShotAuthorizedMint"));
        String semantics = Files.readString(output.resolve("MintingSemanticsTests.lean"));
        assertTrue(semantics.contains("Duplicate current-policy entries"));
        assertTrue(semantics.contains("Malformed matching policy value"));
        assertTrue(semantics.contains("CardanoLedgerApi.V3.valueOf"));
        assertTrue(semantics.contains("native_decide"));

        var manifest = JSON.readTree(output.resolve("verification-manifest.json").toFile());
        assertTrue(manifest.path("ledgerValidityModeled").asBoolean());
        assertEquals("validMintingContext/v3-pinned",
                manifest.path("domainAssumptions").get(0).asText());
        assertEquals(OneShotMintProperty.TEMPLATE,
                manifest.path("propertyIr").path("template").asText());
        assertEquals(2, manifest.path("dslIr").path("schemaVersion").asInt());
        assertEquals(64, manifest.path("dslIr").path("sha256").asText().length());
        assertEquals(64, manifest.path("capabilityInventory").path("sha256")
                .asText().length());
        var plan = JSON.readTree(output.resolve("verification-runner.json").toFile());
        assertEquals("prove-one-shot-authorized-mint-v1",
                plan.path("verify").get(1).path("id").asText());
    }

    @Test
    void generatesExactSellerPaymentDslProfileWithLedgerDomain() throws Exception {
        Path output = tempDir.resolve("seller-payment");
        var dsl = SellerPaymentDsl.propertySet(
                "StateGate.seller-paid-at-least", "owner", "state");
        var property = new SellerPaymentProperty(
                1, SellerPaymentProperty.TEMPLATE,
                "StateGate.seller-paid-at-least", "StateGate", "spending",
                "StateGatePayment.java", "owner", "state", "StateDatum",
                PropertyIrCodec.canonicalJson(dsl),
                List.of("validSpendingContext/v3-pinned"),
                List.of("strict-datum", "public-key-seller-output",
                        "lovelace-paid-at-least"), true);

        VerificationProjectGenerator.generateSellerPayment(
                writeBlueprint(), property, 2000, 4, output, false);

        String security = Files.readString(output.resolve("SecurityProperty.lean"));
        assertTrue(security.contains("Option JulcGenerated.Schemas.StateDatum"));
        assertTrue(security.contains(".PubKeyCredential actualSeller"));
        assertTrue(security.contains("lovelaceOf out.txOutValue >= price"));
        assertTrue(security.contains("datum.owner datum.state out"));
        assertTrue(security.contains("Recursor.any out in"));
        String equivalence = Files.readString(output.resolve("LedgerDomainEquivalence.lean"));
        assertTrue(equivalence.contains(
                "theorem validSpendingContext_implies_blasterDomain"));
        assertTrue(equivalence.contains("validSpendingContext"));
        assertTrue(Files.readString(output.resolve("StateGateLedgerCorollary.lean"))
                .contains("ledgerValidSuccessfulImpliesSellerPaidAtLeast"));
        String obligation = Files.readString(output.resolve("StateGateObligation.lean"));
        assertTrue(obligation.contains("blasterValidSpendingContext ctx"));
        assertTrue(obligation.contains("isSuccessful (appliedValidator.prop ctx)"));
        var plan = JSON.readTree(output.resolve("verification-runner.json").toFile());
        assertEquals("seller-payment-v1-established",
                plan.path("verify").get(1).path("outcomes").get(0)
                        .path("reason").asText());
        var manifest = JSON.readTree(output.resolve("verification-manifest.json").toFile());
        assertTrue(manifest.path("ledgerValidityModeled").asBoolean());
        assertEquals("validSpendingContext/v3-pinned",
                manifest.path("domainAssumptions").get(0).asText());
        assertEquals(VerificationFiles.leanTreeHash(output),
                manifest.path("generatedLeanSha256").asText());
    }

    @Test
    void generatesIndependentGenericClaimsWithoutTemplateShapeMatching() throws Exception {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                @SpendingValidator class StateGate {
                    record StateDatum(byte[] owner, BigInteger state) {}
                    record Transition(BigInteger nextState) {}
                    @Entrypoint static boolean validate(StateDatum datum,
                            Transition redeemer, ScriptContext ctx) { return true; }
                }
                """;
        var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compileContract(source);
        var model = new SpendingContractModel();
        String authority = "4a554c435f5645524946595f415554484f524954595f303030303031";
        var candidate = DslPropertySet.composed(DslPurpose.SPENDING,
                property("StateGate.state-nonnegative",
                        DslDomain.VALID_SPENDING_V3_PINNED,
                        model.datum().integerField("state").ge(integer(0))),
                property("StateGate.authorized-or-owned", DslDomain.NONE,
                        model.context().txInfo().signatories().contains(keyHash(authority))
                                .or(model.context().txInfo().signatories().contains(
                                        model.datum().bytesField("owner")))));
        var promoted = ComposedDslPromotion.promote(candidate,
                compiled.contractSchema(), "StateGate", "StateGateProperties.java");
        Path output = tempDir.resolve("composed-spending");

        VerificationProjectGenerator.generateComposedDsl(
                writeBlueprint(), promoted, 3000, 4, output, false);

        String security = Files.readString(output.resolve("SecurityProperty.lean"));
        assertTrue(security.contains("dslGuarantee_StateGate_state_nonnegative"));
        assertTrue(security.contains("dslGuarantee_StateGate_authorized_or_owned"));
        assertTrue(security.contains("Option JulcGenerated.Schemas.StateDatum"));
        assertTrue(security.contains("List.elem"));
        assertTrue(Files.readString(output.resolve(
                "StateGate_StateGate_state_nonnegativeObligation.lean"))
                .contains("blasterValidSpendingContext ctx = true"));
        assertTrue(Files.isExecutable(output.resolve(
                "scripts/verify-stategate_state_nonnegative.sh")));
        assertTrue(Files.readString(output.resolve(
                "scripts/verify-stategate_state_nonnegative.sh"))
                .contains("StateGate_StateGate_state_nonnegativeLedgerCorollary.lean"));

        var plan = JSON.readTree(output.resolve("verification-runner.json").toFile());
        assertEquals(4, plan.path("verify").size());
        assertEquals("StateGate.authorized-or-owned.non-vacuity",
                plan.path("verify").get(1).path("nonVacuityGuardPropertyId").asText());
        var manifest = JSON.readTree(output.resolve("verification-manifest.json").toFile());
        assertEquals(2, manifest.path("claims").size());
        assertEquals(4, manifest.path("properties").size());
        assertEquals(3, manifest.path("dslIr").path("schemaVersion").asInt());
        assertEquals(ComposedDslPromotion.generatedName(
                        manifest.path("claims").get(0).path("id").asText()),
                manifest.path("claims").get(0).path("generatedName").asText());

        Files.writeString(output.resolve("review-notes.txt"), "preserve me\n");
        var reduced = DslPropertySet.composed(DslPurpose.SPENDING,
                property("StateGate.state-nonnegative",
                        DslDomain.NONE,
                        model.datum().integerField("state").ge(integer(0))));
        VerificationProjectGenerator.generateComposedDsl(writeBlueprint(),
                ComposedDslPromotion.promote(reduced, compiled.contractSchema(),
                        "StateGate", "StateGateProperties.java"),
                3000, 4, output, true);
        assertFalse(Files.exists(output.resolve(
                "StateGate_StateGate_authorized_or_ownedProof.lean")));
        assertFalse(Files.exists(output.resolve(
                "scripts/verify-stategate_authorized_or_owned.sh")));
        assertFalse(Files.exists(output.resolve("LedgerDomainEquivalence.lean")));
        assertTrue(Files.exists(output.resolve("review-notes.txt")));
        assertEquals(VerificationFiles.leanTreeHash(output),
                JSON.readTree(output.resolve("verification-manifest.json").toFile())
                        .path("generatedLeanSha256").asText());
    }

    @Test
    void generatesNovelGenericMintCompositionAndReviewedDomainBridge() throws Exception {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                @MintingValidator class TokenPolicy {
                    record Redeemer() {}
                    @Entrypoint static boolean validate(Redeemer redeemer,
                            ScriptContext ctx) { return true; }
                }
                """;
        var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compileContract(source);
        var model = new MintingContractModel();
        String authority = "4a554c435f5645524946595f415554484f524954595f303030303031";
        String txId = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20";
        var quantity = integer(2);
        var guarantee = model.redeemerStrictlyDecodes()
                .and(model.context().txInfo().signatories().contains(keyHash(authority)))
                .and(model.context().txInfo().inputs().consumes(txOutRef(txId, 1)))
                .and(model.context().txInfo().mint().exactOwnPolicyAsset(
                        model.ownPolicy(), tokenName("4a554c43"), quantity))
                .and(quantity.gt(integer(0)).or(quantity.eq(integer(0))));
        var candidate = DslPropertySet.composed(DslPurpose.MINTING,
                property("TokenPolicy.composed-mint",
                        DslDomain.VALID_MINTING_V3_PINNED, guarantee));
        var promoted = ComposedDslPromotion.promote(candidate,
                compiled.contractSchema(), "TokenPolicy", "TokenPolicyProperties.java");
        Path output = tempDir.resolve("composed-mint");

        VerificationProjectGenerator.generateComposedDsl(
                writeMintBlueprint(), promoted, 5000, 4, output, false);

        String security = Files.readString(output.resolve("SecurityProperty.lean"));
        assertTrue(security.contains("redeemerStrictlyDecodes"));
        assertTrue(security.contains("exactOwnPolicyAsset"));
        assertTrue(security.contains("utxoConsumed"));
        assertTrue(security.contains("(2 == 0) || (2 > 0)"));
        assertTrue(Files.readString(output.resolve("LedgerDomainEquivalence.lean"))
                .contains("validMintingContext_implies_blasterDomain"));
        assertTrue(Files.readString(output.resolve(
                "TokenPolicy_TokenPolicy_composed_mintLedgerCorollary.lean"))
                .contains("composedLedgerCorollary"));
        assertTrue(Files.readString(output.resolve(
                "scripts/verify-tokenpolicy_composed_mint.sh"))
                .contains("TokenPolicy_TokenPolicy_composed_mintLedgerCorollary.lean"));
    }

    @Test
    void generatesRewardingCompositionAndExecutesReviewedDomainBridge() throws Exception {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                @WithdrawValidator class Rewards {
                    record Redeemer() {}
                    @Entrypoint static boolean validate(Redeemer redeemer,
                            ScriptContext ctx) { return true; }
                }
                """;
        var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compileContract(source);
        var model = new RewardingContractModel();
        String authority = "4a554c435f5645524946595f415554484f524954595f303030303031";
        var guarantee = model.context().txInfo().withdrawals().exists(entry ->
                        entry.credential().eq(model.rewardingCredential())
                                .and(entry.amount().ge(integer(1_000_000))))
                .and(model.context().txInfo().signatories().contains(keyHash(authority)));
        var candidate = DslPropertySet.composed(DslPurpose.REWARDING,
                property("Rewards.authorized", DslDomain.VALID_REWARDING_V3_PINNED,
                        guarantee));
        var promoted = ComposedDslPromotion.promote(candidate,
                compiled.contractSchema(), "Rewards", "RewardProperties.java");
        Path output = tempDir.resolve("composed-rewarding");

        VerificationProjectGenerator.generateComposedDsl(
                writeRewardingBlueprint(), promoted, 5000, 4, output, false);

        String security = Files.readString(output.resolve("SecurityProperty.lean"));
        assertTrue(security.contains("rewardingCredentialOf"));
        assertTrue(security.contains("txInfoWdrl"));
        assertTrue(security.contains("blasterValidRewardingContext"));
        String obligation = Files.readString(output.resolve(
                "Rewards_Rewards_authorizedObligation.lean"));
        assertTrue(obligation.contains("rewardingInputs"));
        assertTrue(obligation.contains("blasterValidRewardingContext"));
        assertTrue(Files.readString(output.resolve("LedgerDomainEquivalence.lean"))
                .contains("validRewardingContext_implies_blasterDomain"));
        assertTrue(Files.readString(output.resolve(
                "Rewards_Rewards_authorizedLedgerCorollary.lean"))
                .contains("validRewardingContext"));
        assertTrue(Files.readString(output.resolve(
                "scripts/verify-rewards_authorized.sh"))
                .contains("Rewards_Rewards_authorizedLedgerCorollary.lean"));
        String rewardingSemantics = Files.readString(
                output.resolve("RewardingSemanticsTests.lean"));
        assertTrue(rewardingSemantics.contains("matchingMinimum"));
        assertTrue(rewardingSemantics.contains(
                "Data.Map [(Data.I 0, Data.I 1)]"));
        assertTrue(rewardingSemantics.contains("Data.B \"bad\""));
        var manifest = JSON.readTree(
                output.resolve("verification-manifest.json").toFile());
        assertEquals("rewarding", manifest.path("scriptPurpose").asText());
        assertEquals("Rewards", manifest.path("blueprintEntryTitle").asText());
    }

    @Test
    void generatesStrictVariantEncoding() throws Exception {
        var document = JSON.readTree("""
                {
                  "validators": [{
                    "title": "Gate",
                    "redeemer": {"schema": {"$ref": "#/definitions/Action"}}
                  }],
                  "definitions": {
                    "Int": {"dataType": "integer"},
                    "Action": {"anyOf": [
                      {"title": "Stop", "dataType": "constructor", "index": 0,
                       "fields": []},
                      {"title": "Advance", "dataType": "constructor", "index": 1,
                       "fields": [{"title": "amount", "$ref": "#/definitions/Int"}]}
                    ]}
                  }
                }
                """);

        var result = VerificationProjectGenerator.generateSchemas(
                document.path("definitions"), document.path("validators").get(0));

        assertTrue(result.source().contains("inductive Action where"));
        assertTrue(result.source().contains("| Stop"));
        assertTrue(result.source().contains("| Advance (amount : Integer)"));
        assertTrue(result.source().contains("Data.Constr 0 []"));
        assertTrue(result.source().contains("Data.Constr 1 [r_amount]"));
    }

    @Test
    void rejectsUnsupportedSchemaWithoutWritingWorkspace() throws Exception {
        Path blueprint = writeBlueprint();
        var root = (com.fasterxml.jackson.databind.node.ObjectNode)
                JSON.readTree(blueprint.toFile());
        var unsupportedField = (com.fasterxml.jackson.databind.node.ObjectNode)
                root.path("definitions").path("StateDatum").path("anyOf")
                        .get(0).path("fields").get(1);
        unsupportedField.remove("$ref");
        unsupportedField.put("dataType", "future-container");
        JSON.writerWithDefaultPrettyPrinter().writeValue(blueprint.toFile(), root);
        Path output = tempDir.resolve("unsupported");

        var error = assertThrows(UnsupportedVerificationException.class,
                () -> VerificationProjectGenerator.generate(
                        blueprint, "StateGate", "spending", 100, output, false));

        assertTrue(error.getMessage().toLowerCase(java.util.Locale.ROOT).contains("unsupported"));
        assertFalse(Files.exists(output));
    }

    @Test
    void generatesStrictBooleanOptionalListMapAndNestedTypes() throws Exception {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                import java.util.List;
                import java.util.Map;
                import java.util.Optional;

                @MintingValidator
                class ContainerGate {
                    record Redeemer(List<BigInteger> values,
                                    Map<byte[], BigInteger> balances,
                                    Optional<List<Map<byte[], BigInteger>>> nested,
                                    boolean enabled) {}
                    @Entrypoint
                    static boolean validate(Redeemer redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry()).compileContract(source);
        var blueprint = BlueprintGenerator.generate(
                new BlueprintConfig("container-generator-test", "1"),
                List.of(new BlueprintGenerator.CompiledValidator(
                        "ContainerGate", compiled.compileResult(), compiled.contractSchema())));
        var document = JSON.readTree(blueprint.toJson());

        var result = VerificationProjectGenerator.generateSchemas(
                document.path("definitions"), document.path("validators").get(0));

        assertTrue(result.source().contains("structure JulcList (α : Type)"));
        assertTrue(result.source().contains("structure JulcMap (κ υ : Type)"));
        assertTrue(result.source().contains("values : JulcList (Integer)"));
        assertTrue(result.source().contains("balances : JulcMap (ByteString) (Integer)"));
        assertTrue(result.source().contains(
                "nested : Option (JulcList (JulcMap (ByteString) (Integer)))"));
        assertTrue(result.source().contains("enabled : Bool"));
        assertTrue(result.source().contains("Data.List (encodeDataList values.items)"));
        assertTrue(result.source().contains("Data.Map (encodeDataMap values.entries)"));
        assertFalse(result.source().contains("inductive JulcOptional"));
        assertEquals("Redeemer", result.leanTypes().get("Redeemer"));
    }

    @Test
    void generatesProductiveRecursiveSumsAndContainerCodecs() throws Exception {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                import java.util.List;
                import java.util.Map;
                import java.util.Optional;

                @MintingValidator
                class RecursiveContainerGate {
                    sealed interface Node permits End, Cons {}
                    record End() implements Node {}
                    record Cons(BigInteger value, Optional<Node> next) implements Node {}
                    record Tree(List<Tree> children) {}
                    record Graph(Map<BigInteger, Graph> edges) {}
                    record Redeemer(Node node, Tree tree, Graph graph) {}

                    @Entrypoint
                    static boolean validate(Redeemer redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry()).compileContract(source);
        var blueprint = BlueprintGenerator.generate(
                new BlueprintConfig("recursive-generator-test", "1"),
                List.of(new BlueprintGenerator.CompiledValidator(
                        "RecursiveContainerGate", compiled.compileResult(),
                        compiled.contractSchema())));
        var document = JSON.readTree(blueprint.toJson());

        var result = VerificationProjectGenerator.generateSchemas(
                document.path("definitions"), document.path("validators").get(0));

        assertTrue(result.source().contains("inductive Node where"));
        assertTrue(result.source().contains("| End"));
        assertTrue(result.source().contains("| Cons (value : Integer) (next : Option (Node))"));
        assertTrue(result.source().contains("inductive Tree where"));
        assertTrue(result.source().contains("children : JulcList (Tree)"));
        assertTrue(result.source().contains("inductive Graph where"));
        assertTrue(result.source().contains("edges : JulcMap (Integer) (Graph)"));
        assertTrue(result.source().contains("def encodeNode : Node → Data"));
        assertTrue(result.source().contains("def decodeNode : Nat → Data → Option Node"));
        assertTrue(result.source().contains("decodeOptionalWith"));
        assertTrue(result.source().contains("decodeJulcListWith"));
        assertTrue(result.source().contains("decodeJulcMapWith"));
        assertFalse(result.source().contains("partial def"));
        assertFalse(result.source().contains("sorry"));
    }

    @Test
    void rejectsMalformedContainerSchema() throws Exception {
        var document = JSON.readTree("""
                {
                  "validators": [{
                    "title": "Gate",
                    "redeemer": {"schema": {"$ref": "#/definitions/Values"}}
                  }],
                  "definitions": {
                    "Values": {"dataType": "list"}
                  }
                }
                """);

        var error = assertThrows(UnsupportedVerificationException.class,
                () -> VerificationProjectGenerator.generateSchemas(
                        document.path("definitions"), document.path("validators").get(0)));

        assertTrue(error.getMessage().contains("requires items"));
    }

    @Test
    void rejectsMapWithoutKeysOrValues() throws Exception {
        for (String mapSchema : List.of(
                "{\"dataType\": \"map\", \"values\": {\"dataType\": \"integer\"}}",
                "{\"dataType\": \"map\", \"keys\": {\"dataType\": \"bytes\"}}")) {
            var document = JSON.readTree("""
                    {
                      "validators": [{
                        "title": "Gate",
                        "redeemer": {"schema": {"$ref": "#/definitions/Balances"}}
                      }],
                      "definitions": {"Balances": %s}
                    }
                    """.formatted(mapSchema));

            var error = assertThrows(UnsupportedVerificationException.class,
                    () -> VerificationProjectGenerator.generateSchemas(
                            document.path("definitions"),
                            document.path("validators").get(0)));

            assertTrue(error.getMessage().contains("requires keys and values"));
        }
    }

    @Test
    void refusesNonEmptyOutputWithoutForceAndPreservesUnknownFiles() throws Exception {
        Path blueprint = writeBlueprint();
        Path output = tempDir.resolve("existing");
        Files.createDirectories(output);
        Path userFile = output.resolve("UserProperty.lean");
        Files.writeString(userFile, "-- user owned\n");

        assertThrows(IllegalArgumentException.class,
                () -> VerificationProjectGenerator.generate(
                        blueprint, "StateGate", "spending", 100, output, false));

        VerificationProjectGenerator.generate(
                blueprint, "StateGate", "spending", 100, output, true);
        assertEquals("-- user owned\n", Files.readString(userFile));

        Path securityProperty = output.resolve("SecurityProperty.lean");
        Files.writeString(securityProperty, "-- specialized property\n");
        VerificationProjectGenerator.generate(
                blueprint, "StateGate", "spending", 100, output, true);
        assertEquals("-- specialized property\n", Files.readString(securityProperty));

        Path gitignore = output.resolve(".gitignore");
        Files.writeString(gitignore, "/local-review-notes/\n");
        VerificationProjectGenerator.generate(
                blueprint, "StateGate", "spending", 100, output, true);
        assertEquals("/local-review-notes/\n", Files.readString(gitignore));
    }

    @Test
    void exposesVerifyCommandInRootCli() {
        var commandLine = new CommandLine(new JulcCommand());
        assertTrue(commandLine.getSubcommands().containsKey("verify"));
        assertTrue(commandLine.getSubcommands().get("verify")
                .getSubcommands().containsKey("init"));
        assertTrue(commandLine.getSubcommands().get("verify")
                .getSubcommands().containsKey("run"));
        assertTrue(commandLine.getSubcommands().get("verify")
                .getSubcommands().containsKey("dsl-init"));
        assertTrue(commandLine.getSubcommands().get("verify")
                .getSubcommands().containsKey("dsl"));
        assertTrue(commandLine.getSubcommands().get("verify")
                .getCommandSpec().findOption("--validator") != null);
        assertTrue(commandLine.getSubcommands().get("verify").getSubcommands().get("init")
                .getCommandSpec().findOption("--recursive-depth") != null);
        assertTrue(commandLine.getSubcommands().get("verify").getSubcommands().get("run")
                .getCommandSpec().findOption("--backend") != null);
    }

    @Test
    void rejectsNonPositiveFuel() throws Exception {
        Path blueprint = writeBlueprint();
        var error = assertThrows(IllegalArgumentException.class,
                () -> VerificationProjectGenerator.generate(
                        blueprint, "StateGate", "spending", 0,
                        tempDir.resolve("zero"), false));
        assertTrue(error.getMessage().contains("positive"));
    }

    @Test
    void recordsRecursiveDepthSeparatelyFromCekFuel() throws Exception {
        Path output = tempDir.resolve("recursive-depth");
        VerificationProjectGenerator.generate(
                writeBlueprint(), "StateGate", "spending", 20000, 7, output, false);

        var manifest = JSON.readTree(
                output.resolve("verification-manifest.json").toFile());
        assertEquals(20000, manifest.path("fuel").asInt());
        assertEquals(7, manifest.path("recursiveDepth").asInt());
        assertTrue(Files.readString(output.resolve("PropertyTemplates.lean"))
                .contains("recursiveVerificationDepth : Nat := 7"));

        var error = assertThrows(IllegalArgumentException.class,
                () -> VerificationProjectGenerator.generate(
                        writeBlueprint(), "StateGate", "spending", 20000, 0,
                        tempDir.resolve("bad-depth"), false));
        assertTrue(error.getMessage().contains("Recursive verification depth"));
    }

    @Test
    void rejectsPurposeThatContradictsBlueprintShape() throws Exception {
        Path blueprint = writeBlueprint();
        var error = assertThrows(UnsupportedVerificationException.class,
                () -> VerificationProjectGenerator.generate(
                        blueprint, "StateGate", "minting", 100,
                        tempDir.resolve("wrong-purpose"), false));
        assertTrue(error.getMessage().contains("Minting"));
    }

    @Test
    void asksVerifyInitUsersToRebuildLegacyPurposeFreeBlueprint() throws Exception {
        Path blueprint = writeBlueprint();
        String legacyJson = Files.readString(blueprint)
                .replace("        \"purpose\": \"spend\",\n", "");
        Files.writeString(blueprint, legacyJson);

        var error = assertThrows(ArtifactCommand.ArtifactSelectionException.class,
                () -> VerificationProjectGenerator.generate(
                        blueprint, "StateGate", "spending", 100,
                        tempDir.resolve("legacy-purpose-free"), false));

        assertTrue(error.getMessage().contains("Rebuild plutus.json"), error.getMessage());
        assertFalse(Files.exists(tempDir.resolve("legacy-purpose-free")));
    }

    @Test
    void rejectsBuiltinOutsidePinnedBlasterCoverage() {
        var error = assertThrows(UnsupportedVerificationException.class,
                () -> VerificationProjectGenerator.ensureSupportedBuiltins(List.of(
                        new ArtifactCommand.BuiltinUse("LengthOfArray", 89))));
        assertTrue(error.getMessage().contains("tag 89"));
    }

    @Test
    void rejectsRecursiveSchema() throws Exception {
        var document = JSON.readTree("""
                {
                  "validators": [{
                    "title": "Gate",
                    "redeemer": {"schema": {"$ref": "#/definitions/Node"}}
                  }],
                  "definitions": {
                    "Node": {"anyOf": [{
                      "title": "Node", "dataType": "constructor", "index": 0,
                      "fields": [{"title": "next", "$ref": "#/definitions/Node"}]
                    }]}
                  }
                }
                """);
        var error = assertThrows(UnsupportedVerificationException.class,
                () -> VerificationProjectGenerator.generateSchemas(
                        document.path("definitions"), document.path("validators").get(0)));
        assertTrue(error.getMessage().contains("Recursive"));
    }

    @Test
    void generatesProductiveMutualRecursiveGroup() throws Exception {
        var document = JSON.readTree("""
                {
                  "validators": [{
                    "title": "Gate",
                    "redeemer": {"schema": {"$ref": "#/definitions/Left"}}
                  }],
                  "definitions": {
                    "Left": {"anyOf": [
                      {"title": "LeftEnd", "dataType": "constructor", "index": 0,
                       "fields": []},
                      {"title": "ToRight", "dataType": "constructor", "index": 1,
                       "fields": [{"title": "next", "$ref": "#/definitions/Right"}]}
                    ]},
                    "Right": {"anyOf": [
                      {"title": "RightEnd", "dataType": "constructor", "index": 0,
                       "fields": []},
                      {"title": "ToLeft", "dataType": "constructor", "index": 1,
                       "fields": [{"title": "next", "$ref": "#/definitions/Left"}]}
                    ]}
                  }
                }
                """);

        var result = VerificationProjectGenerator.generateSchemas(
                document.path("definitions"), document.path("validators").get(0));

        assertTrue(result.source().contains("mutual\n  inductive Right where"));
        assertTrue(result.source().contains("  inductive Left where"));
        assertTrue(result.source().contains("def encodeRight : Right → Data"));
        assertTrue(result.source().contains("def decodeLeft : Nat → Data → Option Left"));
    }

    @Test
    void rejectsNonproductiveMutualSchema() throws Exception {
        var document = JSON.readTree("""
                {
                  "validators": [{
                    "title": "Gate",
                    "redeemer": {"schema": {"$ref": "#/definitions/Left"}}
                  }],
                  "definitions": {
                    "Left": {"anyOf": [{
                      "title": "Left", "dataType": "constructor", "index": 0,
                      "fields": [{"title": "next", "$ref": "#/definitions/Right"}]
                    }]},
                    "Right": {"anyOf": [{
                      "title": "Right", "dataType": "constructor", "index": 0,
                      "fields": [{"title": "next", "$ref": "#/definitions/Left"}]
                    }]}
                  }
                }
                """);

        var error = assertThrows(UnsupportedVerificationException.class,
                () -> VerificationProjectGenerator.generateSchemas(
                        document.path("definitions"), document.path("validators").get(0)));

        assertTrue(error.getMessage().contains("no finite base constructor"));
        assertTrue(error.getMessage().contains("Left"));
        assertTrue(error.getMessage().contains("Right"));
    }

    @Test
    void rejectsDanglingRecursiveReference() throws Exception {
        var document = JSON.readTree("""
                {
                  "validators": [{
                    "title": "Gate",
                    "redeemer": {"schema": {"$ref": "#/definitions/Node"}}
                  }],
                  "definitions": {
                    "Node": {"anyOf": [
                      {"title": "End", "dataType": "constructor", "index": 0,
                       "fields": []},
                      {"title": "Cons", "dataType": "constructor", "index": 1,
                       "fields": [{"title": "next", "$ref": "#/definitions/Missing"}]}
                    ]}
                  }
                }
                """);

        var error = assertThrows(UnsupportedVerificationException.class,
                () -> VerificationProjectGenerator.generateSchemas(
                        document.path("definitions"), document.path("validators").get(0)));

        assertTrue(error.getMessage().contains("Unknown schema definition 'Missing'"));
    }

    @Test
    void followsContainerAliasesWhenOrderingNamedLeanDefinitions() throws Exception {
        var document = JSON.readTree("""
                {
                  "validators": [{
                    "title": "Gate",
                    "redeemer": {"schema": {"$ref": "#/definitions/Envelope"}}
                  }],
                  "definitions": {
                    "Envelope": {"anyOf": [{
                      "title": "Envelope", "dataType": "constructor", "index": 0,
                      "fields": [{"title": "nodes", "$ref": "#/definitions/NodeList"}]
                    }]},
                    "NodeList": {
                      "dataType": "list", "items": {"$ref": "#/definitions/Node"}
                    },
                    "Node": {"anyOf": [{
                      "title": "Node", "dataType": "constructor", "index": 0,
                      "fields": [{"title": "value", "dataType": "integer"}]
                    }]}
                  }
                }
                """);

        var result = VerificationProjectGenerator.generateSchemas(
                document.path("definitions"), document.path("validators").get(0));

        int node = result.source().indexOf("structure Node where");
        int envelope = result.source().indexOf("structure Envelope where");
        assertTrue(node >= 0 && envelope > node,
                "named dependencies reached through an alias must be declared first");
        assertEquals("JulcList (Node)", result.leanTypes().get("NodeList"));
    }

    @Test
    void rejectsRecursionThroughContainerItems() throws Exception {
        var document = JSON.readTree("""
                {
                  "validators": [{
                    "title": "Gate",
                    "redeemer": {"schema": {"$ref": "#/definitions/Values"}}
                  }],
                  "definitions": {
                    "Values": {
                      "dataType": "list",
                      "items": {"$ref": "#/definitions/Values"}
                    }
                  }
                }
                """);

        var error = assertThrows(UnsupportedVerificationException.class,
                () -> VerificationProjectGenerator.generateSchemas(
                        document.path("definitions"), document.path("validators").get(0)));

        assertTrue(error.getMessage().contains("Recursive"));
    }

    @Test
    void rejectsSchemaNamesThatShadowGeneratedBoolOrOptionTypes() throws Exception {
        for (String schemaName : List.of("Bool", "bool", "Option", "option")) {
            var document = JSON.readTree("""
                    {
                      "validators": [{
                        "title": "Gate",
                        "redeemer": {"schema": {"$ref": "#/definitions/%s"}}
                      }],
                      "definitions": {
                        "%s": {"anyOf": [{
                          "title": "Wrapped", "dataType": "constructor", "index": 0,
                          "fields": [{"title": "value", "dataType": "integer"}]
                        }]}
                      }
                    }
                    """.formatted(schemaName, schemaName));

            var error = assertThrows(UnsupportedVerificationException.class,
                    () -> VerificationProjectGenerator.generateSchemas(
                            document.path("definitions"),
                            document.path("validators").get(0)));

            assertTrue(error.getMessage().contains("conflicts with generated Lean imports"));
            assertTrue(error.getMessage().contains(
                    Character.toUpperCase(schemaName.charAt(0)) + schemaName.substring(1)));
        }
    }

    @Test
    void rejectsSchemaNamesThatCollideAfterLeanNormalization() throws Exception {
        var document = JSON.readTree("""
                {
                  "validators": [{
                    "title": "Gate",
                    "datum": {"schema": {"$ref": "#/definitions/Foo-Bar"}},
                    "redeemer": {"schema": {"$ref": "#/definitions/Foo_Bar"}}
                  }],
                  "definitions": {
                    "Foo-Bar": {"anyOf": [{
                      "title": "First", "dataType": "constructor", "index": 0,
                      "fields": []
                    }]},
                    "Foo_Bar": {"anyOf": [{
                      "title": "Second", "dataType": "constructor", "index": 0,
                      "fields": []
                    }]}
                  }
                }
                """);
        var error = assertThrows(UnsupportedVerificationException.class,
                () -> VerificationProjectGenerator.generateSchemas(
                        document.path("definitions"), document.path("validators").get(0)));
        assertTrue(error.getMessage().contains("collide"));
    }

    @Test
    void rejectsSchemaNameWithoutLeanIdentifierCharacters() throws Exception {
        var document = JSON.readTree("""
                {
                  "validators": [{
                    "title": "Gate",
                    "redeemer": {"schema": {"$ref": "#/definitions/!!!"}}
                  }],
                  "definitions": {
                    "!!!": {"anyOf": [{
                      "title": "Only", "dataType": "constructor", "index": 0,
                      "fields": []
                    }]}
                  }
                }
                """);
        var error = assertThrows(UnsupportedVerificationException.class,
                () -> VerificationProjectGenerator.generateSchemas(
                        document.path("definitions"), document.path("validators").get(0)));
        assertTrue(error.getMessage().contains("cannot form"));
    }

    @Test
    void resolvesEscapedJsonPointerDefinitionNames() throws Exception {
        var document = JSON.readTree("""
                {
                  "validators": [{
                    "title": "Gate",
                    "redeemer": {"schema": {"$ref": "#/definitions/Foo~0Bar"}}
                  }],
                  "definitions": {
                    "Foo~Bar": {"anyOf": [{
                      "title": "Only", "dataType": "constructor", "index": 0,
                      "fields": []
                    }]}
                  }
                }
                """);

        var result = VerificationProjectGenerator.generateSchemas(
                document.path("definitions"), document.path("validators").get(0));

        assertTrue(result.source().contains("inductive FooBar where"));
    }

    @Test
    void rejectsAmbiguousValidatorTitleWithoutWritingWorkspace() throws Exception {
        Path blueprint = writeBlueprint();
        var root = (com.fasterxml.jackson.databind.node.ObjectNode)
                JSON.readTree(blueprint.toFile());
        var validators = (com.fasterxml.jackson.databind.node.ArrayNode)
                root.path("validators");
        validators.add(validators.get(0).deepCopy());
        JSON.writerWithDefaultPrettyPrinter().writeValue(blueprint.toFile(), root);
        Path output = tempDir.resolve("ambiguous");

        var error = assertThrows(IllegalArgumentException.class,
                () -> VerificationProjectGenerator.generate(
                        blueprint, "StateGate", "spending", 100, output, false));
        assertTrue(error.getMessage().contains("found 2"));
        assertFalse(Files.exists(output));
    }

    @Test
    void rejectsNonV3Blueprint() throws Exception {
        Path blueprint = writeBlueprint();
        var root = (com.fasterxml.jackson.databind.node.ObjectNode)
                JSON.readTree(blueprint.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("preamble"))
                .put("plutusVersion", "v2");
        JSON.writerWithDefaultPrettyPrinter().writeValue(blueprint.toFile(), root);

        var error = assertThrows(UnsupportedVerificationException.class,
                () -> VerificationProjectGenerator.generate(
                        blueprint, "StateGate", "spending", 100,
                        tempDir.resolve("v2"), false));
        assertTrue(error.getMessage().contains("Plutus V3"));
    }

    @Test
    void generatesPurposeSpecificWorkspacesBoundToOneSharedArtifact() throws Exception {
        Path blueprint = writeMultiBlueprint();
        Path spendOutput = tempDir.resolve("protocol-spend");
        Path mintOutput = tempDir.resolve("protocol-mint");

        VerificationProjectGenerator.generate(
                blueprint, "Protocol", "spending", 1000, spendOutput, false);
        VerificationProjectGenerator.generate(
                blueprint, "Protocol", "minting", 1000, mintOutput, false);

        var spend = JSON.readTree(
                spendOutput.resolve("verification-manifest.json").toFile());
        var mint = JSON.readTree(
                mintOutput.resolve("verification-manifest.json").toFile());
        assertEquals("Protocol", spend.path("validatorTitle").asText());
        assertEquals("Protocol", mint.path("validatorTitle").asText());
        assertEquals("Protocol.spend", spend.path("blueprintEntryTitle").asText());
        assertEquals("Protocol.mint", mint.path("blueprintEntryTitle").asText());
        assertEquals(spend.path("compiledCodeSha256").asText(),
                mint.path("compiledCodeSha256").asText());
        assertEquals(spend.path("cardanoScriptHash").asText(),
                mint.path("cardanoScriptHash").asText());
        assertTrue(Files.readString(spendOutput.resolve("GeneratedSchemas.lean"))
                .contains("structure ProtocolDatum"));
        assertTrue(Files.readString(mintOutput.resolve("GeneratedSchemas.lean"))
                .contains("structure ProtocolMint"));
    }

    private Path writeBlueprint() throws Exception {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                @SpendingValidator
                class StateGate {
                    record StateDatum(byte[] owner, BigInteger state) {}
                    record Transition(BigInteger nextState) {}
                    @Entrypoint
                    static boolean validate(StateDatum datum, Transition redeemer,
                            ScriptContext ctx) {
                        return redeemer.nextState().compareTo(datum.state()) > 0;
                    }
                }
                """;
        var result = new JulcCompiler(StdlibRegistry.defaultRegistry()).compileContract(source);
        var generated = BlueprintGenerator.generate(
                new BlueprintConfig("verification-generator-test", "1"),
                List.of(new BlueprintGenerator.CompiledValidator(
                        "StateGate", result.compileResult(), result.contractSchema())));
        Path blueprint = tempDir.resolve("plutus-" + System.nanoTime() + ".json");
        Files.writeString(blueprint, generated.toJson());
        return blueprint;
    }

    private Path writeMintBlueprint() throws Exception {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                @MintingValidator
                class TokenPolicy {
                    record Redeemer() {}
                    @Entrypoint
                    static boolean validate(Redeemer redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var result = new JulcCompiler(StdlibRegistry.defaultRegistry()).compileContract(source);
        var generated = BlueprintGenerator.generate(
                new BlueprintConfig("controlled-mint-generator-test", "1"),
                List.of(new BlueprintGenerator.CompiledValidator(
                        "TokenPolicy", result.compileResult(), result.contractSchema())));
        Path blueprint = tempDir.resolve("mint-" + System.nanoTime() + ".json");
        Files.writeString(blueprint, generated.toJson());
        return blueprint;
    }

    private Path writeRewardingBlueprint() throws Exception {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                @WithdrawValidator class Rewards {
                    record Redeemer() {}
                    @Entrypoint
                    static boolean validate(Redeemer redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var result = new JulcCompiler(StdlibRegistry.defaultRegistry()).compileContract(source);
        var generated = BlueprintGenerator.generate(
                new BlueprintConfig("rewarding-generator-test", "1"),
                List.of(new BlueprintGenerator.CompiledValidator(
                        "Rewards", result.compileResult(), result.contractSchema())));
        Path blueprint = tempDir.resolve("rewarding-" + System.nanoTime() + ".json");
        Files.writeString(blueprint, generated.toJson());
        return blueprint;
    }

    private Path writeMultiBlueprint() throws Exception {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                @MultiValidator class Protocol {
                    record Datum(BigInteger state) {}
                    record Spend(BigInteger next) {}
                    record Mint(byte[] tokenName) {}
                    @Entrypoint(purpose = Purpose.SPEND)
                    static boolean spend(Datum datum, Spend redeemer, ScriptContext ctx) {
                        return true;
                    }
                    @Entrypoint(purpose = Purpose.MINT)
                    static boolean mint(Mint redeemer, ScriptContext ctx) { return true; }
                }
                """;
        var result = new JulcCompiler(StdlibRegistry.defaultRegistry()).compileContract(source);
        var generated = BlueprintGenerator.generate(
                new BlueprintConfig("verification-multi-test", "1"),
                List.of(new BlueprintGenerator.CompiledValidator(
                        "Protocol", result.compileResult(), result.contractSchema())));
        Path blueprint = tempDir.resolve("multi-" + System.nanoTime() + ".json");
        Files.writeString(blueprint, generated.toJson());
        return blueprint;
    }

    private String manifestHash(Path output) throws Exception {
        return JSON.readTree(output.resolve("verification-manifest.json").toFile())
                .path("compiledCodeSha256").asText();
    }
}
