package com.bloxbean.julc.cli.cmd.verify;

import com.bloxbean.cardano.julc.blueprint.BlueprintConfig;
import com.bloxbean.cardano.julc.blueprint.BlueprintGenerator;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.verification.OneShotMintProperty;
import com.bloxbean.cardano.julc.verification.dsl.MintingDsl;
import com.bloxbean.cardano.julc.verification.dsl.PropertyIrCodec;
import com.bloxbean.cardano.julc.verification.dsl.ComposedDslPromotion;
import com.bloxbean.cardano.julc.verification.dsl.SpendingContractModel;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslDomain;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPurpose;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.*;

class VerificationRunnerTest {

    private static final String BLASTER = "083bae7971414d894b56b5bbf4108c63e17bc42a";
    private static final String PLUTUS = "7cf5a78c54b9694ef093bf49edb5d3799b2a49c9";
    private static final String LEDGER = "5dab3c43f042b8735b6d067223baaa8d32ed28a1";

    @TempDir
    Path tempDir;

    @Test
    void classifiesAllFiveOutcomesAndWritesDeterministicResult() throws Exception {
        for (var outcome : VerificationOutcome.values()) {
            Path workspace = workspace("outcome-" + outcome.name(), outcome, false);
            var process = new FakeProcess(false, true);
            var runner = runner(process);

            var first = runner.run(workspace, VerificationBackendKind.LOCAL);
            String firstJson = Files.readString(workspace.resolve(VerificationRunner.RESULT_FILE));
            var second = runner.run(workspace, VerificationBackendKind.LOCAL);

            assertEquals(outcome.externalName(), first.result().outcome());
            assertEquals(outcome.exitCode(), VerificationOutcome.parse(
                    first.result().outcome()).exitCode());
            assertEquals(firstJson,
                    Files.readString(workspace.resolve(VerificationRunner.RESULT_FILE)));
            assertEquals(first.result(), second.result());
            assertEquals("4.24.0", first.result().toolchain().get("lean"));
            assertEquals(BLASTER,
                    first.result().dependencyCommits().get("Lean-blaster"));
            assertEquals("legacy-leading-field-v0",
                    first.result().artifact().get("boundarySemantics"));
            assertTrue(Files.isRegularFile(workspace.resolve("verification-results/acquire.log")));
            assertTrue(Files.isRegularFile(workspace.resolve("verification-results/verify.log")));
        }
    }

    @Test
    void refutationHasOrderIndependentPriorityAndExpectedControlsAreExcluded() {
        var inconclusive = new VerificationRunResult.Property(
                "first", "COULD-NOT-EVALUATE", "unsupported");
        var refuted = new VerificationRunResult.Property(
                "second", "REFUTED", "counterexample");
        var control = new VerificationRunResult.Property(
                "control", "REFUTED", "expected-negative-control");
        var proved = new VerificationRunResult.Property(
                "proved", "KERNEL-PROVED", "established");

        assertEquals(VerificationOutcome.REFUTED,
                VerificationRunner.aggregate(List.of(inconclusive, refuted)));
        assertEquals(VerificationOutcome.REFUTED,
                VerificationRunner.aggregate(List.of(refuted, inconclusive)));
        assertEquals(VerificationOutcome.KERNEL_PROVED,
                VerificationRunner.aggregate(List.of(control, proved)));
    }

    @Test
    void valueCertificateMetadataRetainsMeaningAndAggregationScope() {
        assertEquals(List.of("EXTENSIONAL", "FIRST_MATCH", "STRICT_SUMMED", "STRUCTURAL"),
                VerificationRunner.valueSemantics(List.of(
                        "value-quantity:first_match",
                        "value-quantity:strict_summed",
                        "value-relation:le",
                        "value-entry-when:POLICY")));
        assertEquals(List.of("COMPLETE_ADDRESS", "SELECTED_OUTPUTS"),
                VerificationRunner.paymentAggregationScopes(List.of(
                        "dsl.value.aggregate-outputs",
                        "dsl.value.filter-full-address")));
        assertEquals(List.of("WHOLE_TRANSACTION_BALANCE"),
                VerificationRunner.paymentAggregationScopes(
                        List.of("ledger.isBalanced")));
    }

    @Test
    void unexpectedExitAndMissingMarkerFailClosed() throws Exception {
        Path exitWorkspace = workspace("bad-exit", VerificationOutcome.SMT_VALID, false);
        var exitResult = runner(new FakeProcess(false, true, 7))
                .run(exitWorkspace, VerificationBackendKind.LOCAL);
        assertEquals("COULD-NOT-EVALUATE", exitResult.result().outcome());
        assertEquals("unexpected-exit-code", exitResult.result().reason());

        Path markerWorkspace = workspace("missing-marker", VerificationOutcome.SMT_VALID, false);
        var markerResult = runner(new FakeProcess(false, false))
                .run(markerWorkspace, VerificationBackendKind.LOCAL);
        assertEquals("COULD-NOT-EVALUATE", markerResult.result().outcome());
        assertEquals("missing-result-marker", markerResult.result().reason());
    }

    @Test
    void versionTwoMapsObservedExitAndMarkerToResult() throws Exception {
        Path workspace = workspace("observed-result", VerificationOutcome.SMT_VALID, false);
        Path planFile = workspace.resolve(VerificationRunner.PLAN_FILE);
        var plan = (com.fasterxml.jackson.databind.node.ObjectNode)
                VerificationFiles.JSON.readTree(planFile.toFile());
        plan.put("schemaVersion", 2);
        var step = (com.fasterxml.jackson.databind.node.ObjectNode)
                plan.path("verify").get(0);
        step.remove(List.of("expectedExitCodes", "requiredOutput", "result", "reason"));
        var outcomes = step.putArray("outcomes");
        outcomes.addObject()
                .put("exitCode", 3)
                .put("requiredOutput", "RESULT-MARKER")
                .put("result", "REFUTED")
                .put("reason", "counterexample");
        VerificationFiles.JSON.writeValue(planFile.toFile(), plan);
        bindPlan(workspace);

        var result = runner(new FakeProcess(false, true, 3))
                .run(workspace, VerificationBackendKind.LOCAL);

        assertEquals("REFUTED", result.result().outcome());
        assertEquals("counterexample", result.result().properties().getFirst().reason());
    }

    @Test
    void vacuityStopsLaterPropertiesAndRecordsThemAsNotEvaluated() throws Exception {
        Path workspace = workspace("vacuity-short-circuit", VerificationOutcome.SMT_VALID, false);
        Path planFile = workspace.resolve(VerificationRunner.PLAN_FILE);
        var plan = (com.fasterxml.jackson.databind.node.ObjectNode)
                VerificationFiles.JSON.readTree(planFile.toFile());
        plan.put("schemaVersion", 2);
        var verify = (com.fasterxml.jackson.databind.node.ArrayNode) plan.path("verify");
        var nonVacuity = (com.fasterxml.jackson.databind.node.ObjectNode) verify.get(0);
        nonVacuity.put("id", "check-non-vacuity");
        nonVacuity.put("propertyId", "example.non-vacuity");
        nonVacuity.remove(List.of("expectedExitCodes", "requiredOutput", "result", "reason"));
        nonVacuity.putArray("outcomes").addObject()
                .put("exitCode", 4)
                .put("requiredOutput", "RESULT-MARKER")
                .put("result", "COULD-NOT-EVALUATE")
                .put("reason", "property-vacuous");
        var main = nonVacuity.deepCopy();
        main.put("id", "prove-property");
        main.put("propertyId", "example.property");
        verify.add(main);
        VerificationFiles.JSON.writeValue(planFile.toFile(), plan);
        bindPlan(workspace);
        var process = new FakeProcess(false, true, 4);
        var bytes = new ByteArrayOutputStream();
        var progress = VerificationProgress.testing(
                new PrintStream(bytes, true, StandardCharsets.UTF_8), () -> 0L);

        var result = runner(process).run(workspace, VerificationBackendKind.LOCAL, progress);

        assertEquals("COULD-NOT-EVALUATE", result.result().outcome());
        assertEquals("property-vacuous", result.result().reason());
        assertEquals(2, result.result().properties().size());
        assertEquals("COULD-NOT-EVALUATE",
                result.result().properties().get(1).outcome());
        assertEquals("not-evaluated-vacuous",
                result.result().properties().get(1).reason());
        assertTrue(result.result().phases().stream().anyMatch(phaseResult ->
                phaseResult.id().equals("prove-property")
                        && phaseResult.status().equals("SKIPPED")));
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains(
                "Proving property ... SKIPPED - property is vacuous"));
    }

    @Test
    void guardedVacuitySkipsOnlyItsOwnProofAndContinuesOtherProperties() throws Exception {
        Path workspace = workspace("guarded-vacuity", VerificationOutcome.SMT_VALID, false);
        Path planFile = workspace.resolve(VerificationRunner.PLAN_FILE);
        var plan = (com.fasterxml.jackson.databind.node.ObjectNode)
                VerificationFiles.JSON.readTree(planFile.toFile());
        plan.put("schemaVersion", 2);
        var original = (com.fasterxml.jackson.databind.node.ObjectNode)
                plan.path("verify").get(0);
        original.remove(List.of("expectedExitCodes", "requiredOutput", "result", "reason"));
        var verify = plan.putArray("verify");
        var firstNonVacuity = original.deepCopy();
        firstNonVacuity.put("id", "check-non-vacuity-first");
        firstNonVacuity.put("propertyId", "first.non-vacuity");
        firstNonVacuity.putArray("outcomes").addObject()
                .put("exitCode", 4).put("requiredOutput", "VACUOUS")
                .put("result", "COULD-NOT-EVALUATE").put("reason", "property-vacuous");
        verify.add(firstNonVacuity);
        var firstProof = original.deepCopy();
        firstProof.put("id", "prove-first");
        firstProof.put("propertyId", "first");
        firstProof.put("nonVacuityGuardPropertyId", "first.non-vacuity");
        firstProof.putArray("outcomes").addObject()
                .put("exitCode", 0).put("requiredOutput", "VALID")
                .put("result", "SMT-VALID").put("reason", "established");
        verify.add(firstProof);
        var secondNonVacuity = original.deepCopy();
        secondNonVacuity.put("id", "check-non-vacuity-second");
        secondNonVacuity.put("propertyId", "second.non-vacuity");
        var secondOutcomes = secondNonVacuity.putArray("outcomes");
        secondOutcomes.addObject()
                .put("exitCode", 0).put("requiredOutput", "NON-VACUOUS")
                .put("result", "REFUTED").put("reason", "expected-negative-control");
        secondOutcomes.addObject()
                .put("exitCode", 4).put("requiredOutput", "VACUOUS")
                .put("result", "COULD-NOT-EVALUATE").put("reason", "property-vacuous");
        verify.add(secondNonVacuity);
        var secondProof = original.deepCopy();
        secondProof.put("id", "prove-second");
        secondProof.put("propertyId", "second");
        secondProof.put("nonVacuityGuardPropertyId", "second.non-vacuity");
        secondProof.putArray("outcomes").addObject()
                .put("exitCode", 0).put("requiredOutput", "VALID")
                .put("result", "SMT-VALID").put("reason", "established");
        verify.add(secondProof);
        VerificationFiles.JSON.writeValue(planFile.toFile(), plan);
        bindPlan(workspace);
        var process = new FakeProcess(List.of(4, 0, 0),
                List.of("VACUOUS\n", "NON-VACUOUS\n", "VALID\n"));

        var result = runner(process).run(workspace, VerificationBackendKind.LOCAL);

        assertEquals("COULD-NOT-EVALUATE", result.result().outcome());
        assertEquals(4, result.result().properties().size());
        assertTrue(result.result().properties().stream().anyMatch(property ->
                property.id().equals("first")
                        && property.reason().equals("not-evaluated-vacuous")));
        assertTrue(result.result().properties().stream().anyMatch(property ->
                property.id().equals("second")
                        && property.outcome().equals("SMT-VALID")));
        assertEquals(3, process.verifyCalls);
    }

    @Test
    void composedWorkspacePassesBoundManifestAndIndependentProtocolPreflight()
            throws Exception {
        Path workspace = composedWorkspace("composed-preflight");
        var process = new FakeProcess(List.of(0, 0), List.of(
                "NON-VACUOUS: successful input witness exists\n",
                "SMT-VALID: DSL property StateGate.signer established\n"));

        var result = runner(process).run(workspace, VerificationBackendKind.LOCAL);

        assertEquals("SMT-VALID", result.result().outcome());
        assertEquals("all-properties-established", result.result().reason());
        assertTrue(result.result().properties().stream().anyMatch(property ->
                property.id().equals("StateGate.signer")
                        && property.outcome().equals("SMT-VALID")
                        && property.domain().equals("NONE")
                        && property.guaranteeSha256().matches("[0-9a-f]{64}")
                        && property.envelopeSha256().matches("[0-9a-f]{64}")
                        && property.capabilities().contains("purpose.spending")
                        && !property.guaranteeRules().isEmpty()
                        && property.valueSemantics() == null
                        && property.counterexampleDomain().equals(
                                "BLASTER_SPENDING_SYMBOLIC_CONTEXT")
                        && !property.ledgerValidCounterexampleEstablished()
                        && !property.concreteVmCounterexampleReproduced()));
    }

    @Test
    void composedClaimMetadataCannotBeRehashedIntoAFalseCertificate() throws Exception {
        Path workspace = composedWorkspace("composed-claim-tamper");
        Path propertyFile = workspace.resolve("verification-property.json");
        var property = (com.fasterxml.jackson.databind.node.ObjectNode)
                VerificationFiles.JSON.readTree(propertyFile.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) property.path("claims").get(0))
                .put("guaranteeSha256", "0".repeat(64));
        VerificationFiles.JSON.writeValue(propertyFile.toFile(), property);
        Path manifestFile = workspace.resolve("verification-manifest.json");
        var manifest = (com.fasterxml.jackson.databind.node.ObjectNode)
                VerificationFiles.JSON.readTree(manifestFile.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) manifest.path("claims").get(0))
                .put("guaranteeSha256", "0".repeat(64));
        ((com.fasterxml.jackson.databind.node.ObjectNode) manifest.path("propertyIr"))
                .put("sha256", VerificationFiles.sha256(propertyFile));
        VerificationFiles.JSON.writeValue(manifestFile.toFile(), manifest);
        var process = new FakeProcess(false, true);

        var result = runner(process).run(workspace, VerificationBackendKind.LOCAL);

        assertEquals("COULD-NOT-EVALUATE", result.result().outcome());
        assertEquals("preflight-failed", result.result().reason());
        assertTrue(result.diagnostic().contains("inconsistent"));
        assertTrue(process.commands.isEmpty());
    }

    @Test
    void composedPlanCannotOmitAClaimProofEvenWhenManifestHashIsUpdated() throws Exception {
        Path workspace = composedWorkspace("composed-plan-omission");
        Path planFile = workspace.resolve(VerificationRunner.PLAN_FILE);
        var plan = (com.fasterxml.jackson.databind.node.ObjectNode)
                VerificationFiles.JSON.readTree(planFile.toFile());
        ((com.fasterxml.jackson.databind.node.ArrayNode) plan.path("verify")).remove(1);
        VerificationFiles.JSON.writeValue(planFile.toFile(), plan);
        bindPlan(workspace);
        var process = new FakeProcess(false, true);

        var result = runner(process).run(workspace, VerificationBackendKind.LOCAL);

        assertEquals("COULD-NOT-EVALUATE", result.result().outcome());
        assertEquals("preflight-failed", result.result().reason());
        assertTrue(result.diagnostic().contains("every claim exactly once"));
        assertTrue(process.commands.isEmpty());
    }

    @Test
    void composedPlanCannotRelabelARefutationAfterRunnerHashIsUpdated() throws Exception {
        Path workspace = composedWorkspace("composed-outcome-tamper");
        Path planFile = workspace.resolve(VerificationRunner.PLAN_FILE);
        var plan = (com.fasterxml.jackson.databind.node.ObjectNode)
                VerificationFiles.JSON.readTree(planFile.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                plan.path("verify").get(1).path("outcomes").get(1))
                .put("result", "SMT-VALID").put("reason", "dsl-property-established");
        VerificationFiles.JSON.writeValue(planFile.toFile(), plan);
        bindPlan(workspace);
        var process = new FakeProcess(false, true);

        var result = runner(process).run(workspace, VerificationBackendKind.LOCAL);

        assertEquals("COULD-NOT-EVALUATE", result.result().outcome());
        assertEquals("preflight-failed", result.result().reason());
        assertTrue(result.diagnostic().contains("canonical claim"));
        assertTrue(process.commands.isEmpty());
    }

    @Test
    void composedCounterexampleQualificationCannotBeDeletedAndRehashed() throws Exception {
        Path workspace = composedWorkspace("composed-qualification-tamper");
        Path propertyFile = workspace.resolve("verification-property.json");
        var property = (com.fasterxml.jackson.databind.node.ObjectNode)
                VerificationFiles.JSON.readTree(propertyFile.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) property.path("claims").get(0))
                .remove("ledgerValidCounterexampleEstablished");
        VerificationFiles.JSON.writeValue(propertyFile.toFile(), property);
        Path manifestFile = workspace.resolve("verification-manifest.json");
        var manifest = (com.fasterxml.jackson.databind.node.ObjectNode)
                VerificationFiles.JSON.readTree(manifestFile.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) manifest.path("claims").get(0))
                .remove("ledgerValidCounterexampleEstablished");
        ((com.fasterxml.jackson.databind.node.ObjectNode) manifest.path("propertyIr"))
                .put("sha256", VerificationFiles.sha256(propertyFile));
        VerificationFiles.JSON.writeValue(manifestFile.toFile(), manifest);
        var process = new FakeProcess(false, true);

        var result = runner(process).run(workspace, VerificationBackendKind.LOCAL);

        assertEquals("COULD-NOT-EVALUATE", result.result().outcome());
        assertEquals("preflight-failed", result.result().reason());
        assertTrue(result.diagnostic().contains("qualification"));
        assertTrue(process.commands.isEmpty());
    }

    @Test
    void composedUndeterminedNonVacuitySkipsOnlyTheGuardedProof() throws Exception {
        Path workspace = composedWorkspace("composed-undetermined-non-vacuity");
        var process = new FakeProcess(List.of(2),
                List.of("COULD-NOT-EVALUATE: non-vacuity was undetermined\n"));

        var result = runner(process).run(workspace, VerificationBackendKind.LOCAL);

        assertEquals("COULD-NOT-EVALUATE", result.result().outcome());
        assertEquals(1, process.verifyCalls);
        assertTrue(result.result().properties().stream().anyMatch(property ->
                property.id().equals("StateGate.signer")
                        && property.reason().equals(
                                "not-evaluated-non-vacuity-undetermined")));
    }

    @Test
    void nonVacuityUndeterminedIsNotReportedAsNonVacuous() throws Exception {
        Path workspace = workspace("non-vacuity-undetermined", VerificationOutcome.SMT_VALID, false);
        Path planFile = workspace.resolve(VerificationRunner.PLAN_FILE);
        var plan = (com.fasterxml.jackson.databind.node.ObjectNode)
                VerificationFiles.JSON.readTree(planFile.toFile());
        plan.put("schemaVersion", 2);
        var step = (com.fasterxml.jackson.databind.node.ObjectNode) plan.path("verify").get(0);
        step.put("id", "check-non-vacuity");
        step.put("propertyId", "example.non-vacuity");
        step.remove(List.of("expectedExitCodes", "requiredOutput", "result", "reason"));
        step.putArray("outcomes").addObject()
                .put("exitCode", 2)
                .put("requiredOutput", "RESULT-MARKER")
                .put("result", "COULD-NOT-EVALUATE")
                .put("reason", "non-vacuity-undetermined");
        VerificationFiles.JSON.writeValue(planFile.toFile(), plan);
        bindPlan(workspace);
        var bytes = new ByteArrayOutputStream();
        var progress = VerificationProgress.testing(
                new PrintStream(bytes, true, StandardCharsets.UTF_8), () -> 0L);

        var result = runner(new FakeProcess(false, true, 2))
                .run(workspace, VerificationBackendKind.LOCAL, progress);

        assertEquals("COULD-NOT-EVALUATE", result.result().outcome());
        assertEquals("non-vacuity-undetermined", result.result().properties().getFirst().reason());
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Checking property non-vacuity ... DONE [0 ms]"
                + " - COULD-NOT-EVALUATE - non vacuity undetermined"));
        assertFalse(output.contains(" - non-vacuous"));
    }

    @Test
    void timeoutFailsClosed() throws Exception {
        Path workspace = workspace("timeout", VerificationOutcome.SMT_VALID, false);
        var result = runner(new FakeProcess(true, true))
                .run(workspace, VerificationBackendKind.LOCAL);
        assertEquals("COULD-NOT-EVALUATE", result.result().outcome());
        assertEquals("verification-timeout", result.result().reason());
    }

    @Test
    void retriesOnlyAnAcquisitionStepUpToItsPinnedBound() throws Exception {
        Path workspace = workspace("retry", VerificationOutcome.SMT_VALID, false);
        Path planFile = workspace.resolve(VerificationRunner.PLAN_FILE);
        Files.writeString(planFile, Files.readString(planFile).replace(
                "\"command\": [\"lake\", \"update\"]",
                "\"command\": [\"lake\", \"update\"], \"maxAttempts\": 3"));
        bindPlan(workspace);
        var process = new FakeProcess(false, true, null, 2);

        var result = runner(process).run(workspace, VerificationBackendKind.LOCAL);

        assertEquals("SMT-VALID", result.result().outcome());
        assertEquals(3, process.acquireAttempts);
        String log = Files.readString(workspace.resolve(
                "verification-results/acquire-01-acquire.log"));
        assertTrue(log.contains("== attempt 1 =="));
        assertTrue(log.contains("== attempt 3 =="));
    }

    @Test
    void freshEvidenceSuiteUsesTrackedPreflightAndGeneratedResultManifests() throws Exception {
        Path workspace = evidenceWorkspace("evidence");
        Path resultManifest = workspace.resolve("generated/run-manifest.json");
        Files.createDirectories(resultManifest.getParent());
        Files.writeString(resultManifest,
                "{\"properties\":[{\"id\":\"stale\",\"evidence\":\"SMT-VALID\"}]}\n");

        var result = runner(new FakeProcess(false, true))
                .run(workspace, VerificationBackendKind.LOCAL);

        assertEquals("SMT-VALID", result.result().outcome());
        assertTrue(result.result().inputs().containsKey("evidenceRunManifestSha256"));
        assertTrue(Files.isRegularFile(resultManifest));
        assertFalse(Files.readString(resultManifest).contains("stale"));
    }

    @Test
    void backendPreparationFailureProducesStructuredNonSuccess() throws Exception {
        Path workspace = workspace("backend-failure", VerificationOutcome.SMT_VALID, false);
        var runner = new VerificationRunner(new FakeProcess(false, true),
                ignored -> new FailingBackend());

        var result = runner.run(workspace, VerificationBackendKind.LOCAL);

        assertEquals("COULD-NOT-EVALUATE", result.result().outcome());
        assertEquals("backend-unavailable", result.result().reason());
        assertEquals("FAILED", result.result().phases().getFirst().status());
    }

    @Test
    void backendPreparationFailureClosesProgressLineAndPointsToLog() throws Exception {
        Path workspace = workspace("backend-progress-failure", VerificationOutcome.SMT_VALID, false);
        var bytes = new ByteArrayOutputStream();
        var progress = VerificationProgress.testing(
                new PrintStream(bytes, true, StandardCharsets.UTF_8), () -> 0L);
        var runner = new VerificationRunner(new FakeProcess(false, true),
                ignored -> new FailingBackend());

        runner.run(workspace, VerificationBackendKind.LOCAL, progress);

        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains(
                "Checking local Lean and Z3 toolchain (may download Z3) ... FAILED [0 ms]"
                        + " - see verification-results/backend.log"));
    }

    @Test
    void reportsLongRunningStagesWithoutStreamingAuthenticatedLogs() throws Exception {
        Path workspace = workspace("progress", VerificationOutcome.SMT_VALID, false);
        var bytes = new ByteArrayOutputStream();
        var clock = new AtomicLong();
        var progress = VerificationProgress.testing(
                new PrintStream(bytes, true, StandardCharsets.UTF_8),
                () -> clock.getAndAdd(1_000_000_000L));

        var result = runner(new FakeProcess(false, true))
                .run(workspace, VerificationBackendKind.LOCAL, progress);

        assertEquals("SMT-VALID", result.result().outcome());
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Validating workspace and runner plan ... OK"));
        assertTrue(output.contains("Checking artifact, property, and generated-source hashes ... OK"));
        assertTrue(output.contains("Selecting verification backend ... OK"));
        assertTrue(output.contains(
                "Checking local Lean and Z3 toolchain (may download Z3) ... OK"));
        assertTrue(output.contains("Running acquisition step 'acquire' ... OK"));
        assertTrue(output.contains("Checking pinned dependency revisions ... OK"));
        assertTrue(output.contains("Running proof step 'verify' ... DONE"));
        assertFalse(output.contains("RESULT-MARKER"));
    }

    @Test
    void artifactTamperingReplacesStaleSuccess() throws Exception {
        Path workspace = workspace("tampered", VerificationOutcome.SMT_VALID, false);
        Files.writeString(workspace.resolve(VerificationRunner.RESULT_FILE),
                "{\"outcome\":\"SMT-VALID\"}");
        Files.createDirectories(workspace.resolve("verification-results"));
        Files.writeString(workspace.resolve("verification-results/acquire.log"),
                "stale successful acquisition\n");
        Files.writeString(workspace.resolve("artifacts/example.compiledCode.hex"), "ff\n");

        var result = runner(new FakeProcess(false, true))
                .run(workspace, VerificationBackendKind.LOCAL);

        assertEquals("COULD-NOT-EVALUATE", result.result().outcome());
        assertEquals("artifact-identity-mismatch", result.result().reason());
        assertFalse(Files.readString(workspace.resolve(VerificationRunner.RESULT_FILE))
                .contains("\"outcome\":\"SMT-VALID\""));
        assertFalse(Files.exists(workspace.resolve("verification-results/acquire.log")));
        assertFalse(result.result().inputs().containsKey("acquireLogSha256"));
    }

    @Test
    void propertyIrTamperingFailsBeforeExecution() throws Exception {
        Path workspace = workspace("property-ir-tamper", VerificationOutcome.SMT_VALID, false);
        Path property = workspace.resolve("verification-property.json");
        Files.writeString(property, """
                {"schemaVersion":1,"template":"julc.requires-signer/v1",
                 "propertyId":"Example.requires-signer.owner",
                 "ledgerValidityModeled":false}
                """);
        Path manifestFile = workspace.resolve("verification-manifest.json");
        var manifest = (com.fasterxml.jackson.databind.node.ObjectNode)
                VerificationFiles.JSON.readTree(manifestFile.toFile());
        manifest.putObject("propertyIr")
                .put("path", "verification-property.json")
                .put("sha256", VerificationFiles.sha256(property))
                .put("schemaVersion", 1)
                .put("template", "julc.requires-signer/v1")
                .put("propertyId", "Example.requires-signer.owner")
                .put("sourcePath", "datum.owner");
        VerificationFiles.JSON.writeValue(manifestFile.toFile(), manifest);
        Files.writeString(property, Files.readString(property).replace("owner", "attacker"));
        var process = new FakeProcess(false, true);

        var result = runner(process).run(workspace, VerificationBackendKind.LOCAL);

        assertEquals("COULD-NOT-EVALUATE", result.result().outcome());
        assertEquals("artifact-identity-mismatch", result.result().reason());
        assertTrue(process.commands.isEmpty());
    }

    @Test
    void capabilityInventoryTamperingFailsBeforeExecution() throws Exception {
        Path workspace = oneShotWorkspace("capability-tamper");
        Path manifestFile = workspace.resolve("verification-manifest.json");
        var manifest = (com.fasterxml.jackson.databind.node.ObjectNode)
                VerificationFiles.JSON.readTree(manifestFile.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                manifest.path("capabilityInventory")).put("revision", "unreviewed");
        VerificationFiles.JSON.writeValue(manifestFile.toFile(), manifest);
        var process = new FakeProcess(false, true);

        var result = runner(process).run(workspace, VerificationBackendKind.LOCAL);

        assertEquals("COULD-NOT-EVALUATE", result.result().outcome());
        assertEquals("preflight-failed", result.result().reason());
        assertTrue(result.diagnostic().contains("capability inventory"));
        assertTrue(process.commands.isEmpty());
    }

    @Test
    void generatedLeanTamperingFailsBeforeExecution() throws Exception {
        Path workspace = workspace("generated-lean-tamper", VerificationOutcome.SMT_VALID, false);
        Path manifestFile = workspace.resolve("verification-manifest.json");
        var manifest = (com.fasterxml.jackson.databind.node.ObjectNode)
                VerificationFiles.JSON.readTree(manifestFile.toFile());
        manifest.put("generatedLeanSha256", VerificationFiles.leanTreeHash(workspace));
        VerificationFiles.JSON.writeValue(manifestFile.toFile(), manifest);
        Files.writeString(workspace.resolve("Property.lean"),
                "def safe : Prop := False\n");
        var process = new FakeProcess(false, true);

        var result = runner(process).run(workspace, VerificationBackendKind.LOCAL);

        assertEquals("COULD-NOT-EVALUATE", result.result().outcome());
        assertEquals("generated-source-integrity-mismatch", result.result().reason());
        assertTrue(process.commands.isEmpty());
    }

    @Test
    void admissionsFailBeforeAnyCommand() throws Exception {
        Path workspace = workspace("admission", VerificationOutcome.SMT_VALID, false);
        Files.writeString(workspace.resolve("Property.lean"), "theorem bad : True := by sorry\n");
        var process = new FakeProcess(false, true);

        var result = runner(process).run(workspace, VerificationBackendKind.LOCAL);

        assertEquals("project-admission-detected", result.result().reason());
        assertTrue(process.commands.isEmpty());
    }

    @Test
    void admissionWordsInLeanCommentsAndLiteralsAreIgnored() throws Exception {
        Path workspace = workspace("admission-comments", VerificationOutcome.SMT_VALID, false);
        Files.writeString(workspace.resolve("Property.lean"), """
                -- sorry admit axiom unsafe partial
                /- outer axiom /- nested sorry -/ still unsafe -/
                def message := "sorry and axiom are diagnostic words"
                def letter := 's'
                set_option warn.sorry false
                theorem safe : True := by trivial
                """);

        var result = runner(new FakeProcess(false, true))
                .run(workspace, VerificationBackendKind.LOCAL);

        assertEquals("SMT-VALID", result.result().outcome());
    }

    @Test
    void admissionScannerFindsPunctuatedAndNestedCodeTokens() throws Exception {
        String source = """
                /- ignored /- sorry -/ axiom -/
                theorem first : True := by (sorry)
                unsafe def second := 1
                """;

        String code = VerificationFiles.leanCodeOnly(source);

        assertFalse(code.contains("ignored"));
        assertTrue(code.contains("(sorry)"));
        assertTrue(code.contains("unsafe def"));
    }

    @Test
    void executableAndPlanTamperingProduceStructuredNonSuccess() throws Exception {
        Path executable = workspace("script-tamper", VerificationOutcome.SMT_VALID, false);
        Files.writeString(executable.resolve("scripts/verify.sh"),
                "#!/usr/bin/env bash\necho forged\n");
        var scriptResult = runner(new FakeProcess(false, true))
                .run(executable, VerificationBackendKind.LOCAL);
        assertEquals("COULD-NOT-EVALUATE", scriptResult.result().outcome());
        assertEquals("executable-integrity-mismatch", scriptResult.result().reason());

        Path plan = workspace("plan-tamper", VerificationOutcome.SMT_VALID, false);
        Path planFile = plan.resolve(VerificationRunner.PLAN_FILE);
        Files.writeString(planFile, Files.readString(planFile).replace(
                "\"timeoutSeconds\": 30", "\"timeoutSeconds\": 31"));
        var planResult = runner(new FakeProcess(false, true))
                .run(plan, VerificationBackendKind.LOCAL);
        assertEquals("COULD-NOT-EVALUATE", planResult.result().outcome());
        assertEquals("runner-plan-integrity-mismatch", planResult.result().reason());
    }

    @Test
    void rejectsUnknownPlanFieldsAndDeletesStaleResult() throws Exception {
        Path workspace = workspace("unknown", VerificationOutcome.SMT_VALID, false);
        Path plan = workspace.resolve(VerificationRunner.PLAN_FILE);
        String json = Files.readString(plan).replaceFirst("\\{", "{\"surprise\":true,");
        Files.writeString(plan, json);
        Files.writeString(workspace.resolve(VerificationRunner.RESULT_FILE),
                "{\"outcome\":\"SMT-VALID\"}");

        assertThrows(IOException.class, () -> runner(new FakeProcess(false, true))
                .run(workspace, VerificationBackendKind.LOCAL));
        assertFalse(Files.exists(workspace.resolve(VerificationRunner.RESULT_FILE)));
    }

    @Test
    void rejectsUnknownBoundarySemanticsBeforeExecution() throws Exception {
        Path workspace = workspace("unknown-boundary-semantics",
                VerificationOutcome.SMT_VALID, false);
        Path manifestFile = workspace.resolve("verification-manifest.json");
        var manifest = (com.fasterxml.jackson.databind.node.ObjectNode)
                VerificationFiles.JSON.readTree(manifestFile.toFile());
        manifest.put("boundarySemantics", "future-unreviewed-v2");
        VerificationFiles.JSON.writeValue(manifestFile.toFile(), manifest);
        var process = new FakeProcess(false, true);

        var result = runner(process).run(workspace, VerificationBackendKind.LOCAL);

        assertEquals("COULD-NOT-EVALUATE", result.result().outcome());
        assertEquals("unsupported-semantics-profile", result.result().reason());
        assertTrue(process.commands.isEmpty());
    }

    @Test
    void rejectsCommandTraversalAndSymlink() throws Exception {
        Path workspace = workspace("paths", VerificationOutcome.SMT_VALID, false);
        String plan = Files.readString(workspace.resolve(VerificationRunner.PLAN_FILE))
                .replace("scripts/verify.sh", "../verify.sh");
        Files.writeString(workspace.resolve(VerificationRunner.PLAN_FILE), plan);
        assertThrows(IOException.class, () -> runner(new FakeProcess(false, true))
                .run(workspace, VerificationBackendKind.LOCAL));

        Path second = workspace("symlink", VerificationOutcome.SMT_VALID, false);
        Files.delete(second.resolve("scripts/verify.sh"));
        try {
            Files.createSymbolicLink(second.resolve("scripts/verify.sh"), Path.of("/bin/true"));
            assertThrows(IOException.class, () -> runner(new FakeProcess(false, true))
                    .run(second, VerificationBackendKind.LOCAL));
        } catch (UnsupportedOperationException e) {
            // Filesystem does not support symlinks; traversal assertion still covers containment.
        }
    }

    @Test
    void rejectsLeanSourceSymlinkBeforeExecution() throws Exception {
        Path workspace = workspace("lean-symlink", VerificationOutcome.SMT_VALID, false);
        Path external = tempDir.resolve("external.lean");
        Files.writeString(external, "theorem external : True := by trivial\n");
        try {
            Files.createSymbolicLink(workspace.resolve("Linked.lean"), external);
            var process = new FakeProcess(false, true);

            assertThrows(IOException.class,
                    () -> runner(process).run(workspace, VerificationBackendKind.LOCAL));
            assertTrue(process.commands.isEmpty());
        } catch (UnsupportedOperationException e) {
            // Filesystem does not support symlinks; command symlink coverage remains active.
        }
    }

    @Test
    void resultManifestCannotDeleteThroughSymlinkedParent() throws Exception {
        Path workspace = evidenceWorkspace("result-parent-symlink");
        Path externalDirectory = tempDir.resolve("external-result-directory");
        Files.createDirectories(externalDirectory);
        Path externalResult = externalDirectory.resolve("run-manifest.json");
        Files.writeString(externalResult, "do not delete\n");
        try {
            Files.createSymbolicLink(workspace.resolve("generated"), externalDirectory);
            var process = new FakeProcess(false, true);

            assertThrows(IOException.class,
                    () -> runner(process).run(workspace, VerificationBackendKind.LOCAL));
            assertEquals("do not delete\n", Files.readString(externalResult));
            assertTrue(process.commands.isEmpty());
        } catch (UnsupportedOperationException e) {
            // Filesystem does not support symlinks; other containment tests remain active.
        }
    }

    @Test
    void dockerOfflineCommandUsesNetworkNoneAndOnlyWorkspaceMount() throws Exception {
        Path workspace = workspace("docker", VerificationOutcome.SMT_VALID, false);
        var backend = new DockerVerificationBackend("sha256:" + "a".repeat(64));

        List<String> command = backend.command(
                List.of("scripts/verify.sh"), workspace, true);

        assertTrue(command.contains("--network"));
        assertTrue(command.contains("none"));
        assertTrue(command.contains(workspace.toRealPath() + ":/workspace"));
        assertFalse(command.stream().anyMatch(token -> token.contains("docker.sock")));
        assertFalse(command.stream().anyMatch(token -> token.equals(System.getProperty("user.home"))));
        assertTrue(command.contains("sha256:" + "a".repeat(64)));
    }

    @Test
    void selectsPinnedZ3ArchivesAndRejectsZipTraversal() throws Exception {
        assertEquals("z3-4.15.2-arm64-osx-13.7.6.zip",
                Z3Provisioner.platform("Mac OS X", "aarch64").archive());
        assertEquals("z3-4.15.2-x64-glibc-2.39.zip",
                Z3Provisioner.platform("Linux", "amd64").archive());
        assertEquals("z3-4.15.2-arm64-glibc-2.34.zip",
                Z3Provisioner.platform("Linux", "arm64").archive());
        assertThrows(IOException.class, () -> Z3Provisioner.platform("Plan 9", "mips"));

        var bytes = new java.io.ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("../escape"));
            zip.write(1);
            zip.closeEntry();
        }
        assertThrows(IOException.class, () -> Z3Provisioner.extractZip(
                new ByteArrayInputStream(bytes.toByteArray()), tempDir.resolve("unzip")));
    }

    @Test
    void nativeImageMetadataIncludesDockerfileResource() throws Exception {
        var loader = VerificationRunnerTest.class.getClassLoader();
        assertTrue(loader.getResource(DockerVerificationBackend.DOCKERFILE_RESOURCE) != null);
        String dockerfile;
        try (var stream = loader.getResourceAsStream(
                DockerVerificationBackend.DOCKERFILE_RESOURCE)) {
            dockerfile = new String(stream.readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
        }
        assertTrue(dockerfile.contains("--retry-all-errors"));
        assertTrue(dockerfile.contains("lean_sha"));
        assertTrue(dockerfile.contains("z3_sha"));
        assertTrue(dockerfile.contains("sha256sum --check"));
        assertTrue(dockerfile.indexOf("/opt/lean/bin/lean --version")
                < dockerfile.indexOf("z3_archive="));
        String metadata = Files.readString(Path.of(
                "src/main/resources/META-INF/native-image/reachability-metadata.json"));
        JsonNode nativeMetadata = VerificationFiles.JSON.readTree(metadata);
        assertTrue(nativeMetadata.isObject() && nativeMetadata.path("reflection").isArray(),
                "Native reachability metadata must remain valid JSON");
        assertTrue(metadata.contains("META-INF/julc/verification/**"));
        assertTrue(metadata.contains("VerificationRunPlan"));
        assertTrue(metadata.contains("VerificationRunPlan$ObservedOutcome"));
        assertTrue(metadata.contains("ArtifactCommand$BuiltinUse"));
        assertTrue(metadata.contains("RequiresSignerProperty"));
        assertTrue(metadata.contains("ControlledMintProperty"));
        assertTrue(metadata.contains("SellerPaymentProperty"));
        assertTrue(metadata.contains("OneShotMintProperty"));
        assertTrue(metadata.contains("ComposedDslProperty"));
        assertTrue(metadata.contains("DslPurpose"));
        assertTrue(metadata.contains("DslDomain"));
        assertTrue(metadata.contains("ExactOwnPolicyAssetNode"));
        assertTrue(metadata.contains("TxCertKindNode"));
        assertTrue(metadata.contains("KnownCertificateNode"));
        assertTrue(metadata.contains("TxCertKind"));
        assertTrue(metadata.contains("LedgerCapabilityInventory"));
        assertTrue(metadata.contains("CapabilityStatus"));
        for (String schemaFourType : List.of(
                "TypedRootNode", "TypedFieldNode", "VariantWhenNode",
                "OptionExistsNode", "ListQuantifierNode", "ListAtNode",
                "MapQuantifierNode", "MapLookupFirstNode", "MapLookupAllNode",
                "StructuralEqualsNode", "VerificationTypeRef",
                "NominalTypeRef", "OptionalTypeRef", "ListTypeRef",
                "AssocMapTypeRef", "ProjectedContractTypes")) {
            assertTrue(metadata.contains(schemaFourType),
                    () -> "Missing schema-4 native metadata for " + schemaFourType);
        }
        for (String schemaFiveType : List.of(
                "LedgerRootNode", "LedgerFieldNode", "LedgerVariantWhenNode",
                "LedgerVariantFieldNode", "LedgerHelperNode", "LedgerTypeRef")) {
            assertTrue(metadata.contains(schemaFiveType),
                    () -> "Missing schema-5 native metadata for " + schemaFiveType);
        }
        for (String schemaSixType : List.of(
                "AuthorityKeyHashNode", "AuthorityListNode",
                "AuthorityListFromBytesNode", "AuthoritySourceKind",
                "AuthorizationNode", "AuthorizationRelation", "NoSignersNode")) {
            assertTrue(metadata.contains(schemaSixType),
                    () -> "Missing schema-6 native metadata for " + schemaSixType);
        }
    }

    @Test
    void repositoryEvidencePlanAndArtifactLockMatchTrackedPreflightManifest()
            throws Exception {
        Path evidence = Path.of("..", "verification", "blaster").toAbsolutePath().normalize();
        JsonNode manifest = VerificationFiles.JSON.readTree(
                evidence.resolve("verification-manifest.json").toFile());
        JsonNode plan = VerificationFiles.JSON.readTree(
                evidence.resolve(VerificationRunner.PLAN_FILE).toFile());

        assertEquals(VerificationFiles.sha256(evidence.resolve(VerificationRunner.PLAN_FILE)),
                manifest.path("runnerPlanSha256").asText());
        assertEquals(VerificationFiles.sha256(evidence.resolve("artifacts/artifact-lock.json")),
                manifest.path("artifactLock").path("sha256").asText());
        assertEquals("generated/run-manifest.json", plan.path("resultManifest").asText());
    }

    private VerificationRunner runner(VerificationProcess process) {
        return new VerificationRunner(process, ignored -> new PassthroughBackend());
    }

    private Path workspace(String name, VerificationOutcome outcome, boolean evidence)
            throws Exception {
        Path workspace = tempDir.resolve(name);
        Files.createDirectories(workspace.resolve("artifacts"));
        Files.createDirectories(workspace.resolve("scripts"));
        Files.writeString(workspace.resolve("artifacts/example.compiledCode.hex"), "00\n");
        String artifactHash = VerificationFiles.sha256(new byte[]{0});
        Files.writeString(workspace.resolve("verification-manifest.json"), """
                {
                  "schemaVersion": 1,
                  "artifactId": "example",
                  "validatorTitle": "Example",
                  "compiledCodeSha256": "%s",
                  "cardanoScriptHash": "%s",
                  "scriptPurpose": "spending",
                  "protocolVersion": 11,
                  "builtinSemanticsVariant": "E",
                  "plutusLanguage": "PlutusV3",
                  "leanVersion": "4.24.0",
                  "z3Version": "4.15.2",
                  "fuel": 100,
                  "recursiveDepth": 4,
                  "builtins": [{"name":"IfThenElse","flatTag":26}],
                  "dependencies": {
                    "Lean-blaster": "%s",
                    "PlutusCoreBlaster": "%s",
                    "CardanoLedgerApiBlaster": "%s"
                  }
                }
                """.formatted(artifactHash, "1".repeat(56), BLASTER, PLUTUS, LEDGER));
        Files.writeString(workspace.resolve("Property.lean"), "def safe : Prop := True\n");
        Path script = workspace.resolve("scripts/verify.sh");
        Files.writeString(script, "#!/usr/bin/env bash\nexit 0\n");
        script.toFile().setExecutable(true, false);
        String scriptHash = VerificationFiles.sha256(script);
        int expectedExit = outcome.exitCode();
        String plan = """
                {
                  "schemaVersion": 1,
                  "kind": "generated-workspace",
                  "manifest": "verification-manifest.json",
                  "timeoutSeconds": 30,
                  "acquire": [{
                    "id": "acquire",
                    "command": ["lake", "update"],
                    "expectedExitCodes": [0]
                  }],
                  "verify": [{
                    "id": "verify",
                    "command": ["scripts/verify.sh"],
                    "executableSha256": "%s",
                    "expectedExitCodes": [%d],
                    "requiredOutput": "RESULT-MARKER",
                    "propertyId": "example.property",
                    "result": "%s",
                    "reason": "test-outcome"
                  }]
                }
                """.formatted(scriptHash, expectedExit, outcome.externalName());
        Files.writeString(workspace.resolve(VerificationRunner.PLAN_FILE), plan);
        var manifest = (com.fasterxml.jackson.databind.node.ObjectNode)
                VerificationFiles.JSON.readTree(
                        workspace.resolve("verification-manifest.json").toFile());
        manifest.put("runnerPlanSha256", VerificationFiles.sha256(
                workspace.resolve(VerificationRunner.PLAN_FILE)));
        VerificationFiles.JSON.writeValue(
                workspace.resolve("verification-manifest.json").toFile(), manifest);
        return workspace;
    }

    private Path evidenceWorkspace(String name) throws Exception {
        Path workspace = workspace(name, VerificationOutcome.SMT_VALID, false);
        Path lock = workspace.resolve("artifacts/artifact-lock.json");
        Files.writeString(lock, "{\"schemaVersion\":1}\n");
        Path planFile = workspace.resolve(VerificationRunner.PLAN_FILE);
        Files.writeString(planFile, Files.readString(planFile)
                .replace("\"kind\": \"generated-workspace\"",
                        "\"kind\": \"evidence-suite\"")
                .replace("\"manifest\": \"verification-manifest.json\",",
                        "\"manifest\": \"verification-manifest.json\",\n"
                                + "  \"resultManifest\": \"generated/run-manifest.json\","));
        Files.writeString(workspace.resolve("verification-manifest.json"), """
                {
                  "schemaVersion": 1,
                  "runnerPlanSha256": "%s",
                  "artifactLock": {"path": "artifacts/artifact-lock.json", "sha256": "%s"},
                  "verificationProfile": {
                    "plutusLanguage": "PlutusV3",
                    "protocolVersion": 11,
                    "builtinSemanticsVariant": "E",
                    "leanVersion": "4.24.0",
                    "z3Version": "4.15.2",
                    "dependencies": {
                      "Lean-blaster": "%s",
                      "PlutusCoreBlaster": "%s",
                      "CardanoLedgerApiBlaster": "%s"
                    }
                  }
                }
                """.formatted(VerificationFiles.sha256(planFile),
                VerificationFiles.sha256(lock), BLASTER, PLUTUS, LEDGER));
        return workspace;
    }

    private Path oneShotWorkspace(String name) throws Exception {
        String source = """
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                @MintingValidator class TokenPolicy {
                    record Redeemer() {}
                    @Entrypoint static boolean validate(Redeemer redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compileContract(source);
        var blueprint = BlueprintGenerator.generate(
                new BlueprintConfig("capability-tamper-test", "1"),
                List.of(new BlueprintGenerator.CompiledValidator(
                        "TokenPolicy", compiled.compileResult(), compiled.contractSchema())));
        Path blueprintFile = tempDir.resolve(name + "-plutus.json");
        Files.writeString(blueprintFile, blueprint.toJson());
        String authority = "4a554c435f5645524946595f415554484f524954595f303030303031";
        String transactionId =
                "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20";
        var dsl = MintingDsl.oneShotPropertySet(
                "TokenPolicy.one-shot", authority, transactionId, 0, "4a554c43");
        var property = new OneShotMintProperty(
                1, OneShotMintProperty.TEMPLATE, "TokenPolicy.one-shot", "TokenPolicy",
                "minting", "OneShotSpec.java", authority, transactionId, "0",
                "4a554c43", "1", "Redeemer", PropertyIrCodec.canonicalJson(dsl),
                List.of("validMintingContext/v3-pinned"), List.of("test"), true);
        Path output = tempDir.resolve(name);
        VerificationProjectGenerator.generateOneShotMint(
                blueprintFile, property, 5000, 4, output, false);
        return output;
    }

    private Path composedWorkspace(String name) throws Exception {
        String source = """
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                @SpendingValidator class StateGate {
                    record Datum(byte[] owner) {}
                    record Redeemer() {}
                    @Entrypoint static boolean validate(
                            Datum datum, Redeemer redeemer, ScriptContext ctx) { return true; }
                }
                """;
        var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compileContract(source);
        var blueprint = BlueprintGenerator.generate(
                new BlueprintConfig("composed-preflight-test", "1"),
                List.of(new BlueprintGenerator.CompiledValidator(
                        "StateGate", compiled.compileResult(), compiled.contractSchema())));
        Path blueprintFile = tempDir.resolve(name + "-plutus.json");
        Files.writeString(blueprintFile, blueprint.toJson());
        var model = new SpendingContractModel();
        String authority = "4a554c435f5645524946595f415554484f524954595f303030303031";
        var dsl = DslPropertySet.composed(DslPurpose.SPENDING,
                property("StateGate.signer", DslDomain.NONE,
                        model.context().txInfo().signatories().contains(keyHash(authority))));
        var property = ComposedDslPromotion.promote(
                dsl, compiled.contractSchema(), "StateGate", "StateGateProperties.java");
        Path output = tempDir.resolve(name);
        VerificationProjectGenerator.generateComposedDsl(
                blueprintFile, property, 3000, 4, output, false);
        return output;
    }

    private static final class PassthroughBackend implements VerificationExecutionBackend {
        @Override
        public BackendContext prepare(Path workspace, VerificationProcess process,
                                      Duration timeout, Path logFile) {
            return new BackendContext("test-backend", Map.of());
        }

        @Override
        public List<String> command(List<String> workspaceCommand, Path workspace, boolean offline) {
            return workspaceCommand;
        }

        @Override
        public Map<String, String> environment(BackendContext context, boolean offline) {
            return Map.of();
        }

        @Override
        public String name() {
            return "local";
        }
    }

    private static final class FailingBackend implements VerificationExecutionBackend {
        @Override
        public BackendContext prepare(Path workspace, VerificationProcess process,
                                      Duration timeout, Path logFile) throws IOException {
            throw new IOException("toolchain unavailable");
        }

        @Override
        public List<String> command(List<String> command, Path workspace, boolean offline) {
            return command;
        }

        @Override
        public Map<String, String> environment(BackendContext context, boolean offline) {
            return Map.of();
        }

        @Override
        public String name() {
            return "local";
        }
    }

    private static final class FakeProcess implements VerificationProcess {
        private final boolean timeoutVerify;
        private final boolean includeMarker;
        private final Integer forcedVerifyExit;
        private final int acquisitionFailures;
        private final List<Integer> sequencedVerifyExits;
        private final List<String> sequencedVerifyOutputs;
        private int acquireAttempts;
        private int verifyCalls;
        private final List<List<String>> commands = new ArrayList<>();

        private FakeProcess(boolean timeoutVerify, boolean includeMarker) {
            this(timeoutVerify, includeMarker, null, 0);
        }

        private FakeProcess(boolean timeoutVerify, boolean includeMarker, Integer forcedVerifyExit) {
            this(timeoutVerify, includeMarker, forcedVerifyExit, 0);
        }

        private FakeProcess(boolean timeoutVerify, boolean includeMarker,
                            Integer forcedVerifyExit, int acquisitionFailures) {
            this.timeoutVerify = timeoutVerify;
            this.includeMarker = includeMarker;
            this.forcedVerifyExit = forcedVerifyExit;
            this.acquisitionFailures = acquisitionFailures;
            this.sequencedVerifyExits = List.of();
            this.sequencedVerifyOutputs = List.of();
        }

        private FakeProcess(List<Integer> exits, List<String> outputs) {
            this.timeoutVerify = false;
            this.includeMarker = true;
            this.forcedVerifyExit = null;
            this.acquisitionFailures = 0;
            this.sequencedVerifyExits = List.copyOf(exits);
            this.sequencedVerifyOutputs = List.copyOf(outputs);
        }

        @Override
        public ProcessResult execute(List<String> command, Path workingDirectory,
                                     Map<String, String> environment, Duration timeout,
                                     Path logFile) throws IOException {
            commands.add(List.copyOf(command));
            String output;
            int exit = 0;
            boolean timedOut = false;
            if (command.getFirst().equals("lake")) {
                acquireAttempts++;
                exit = acquireAttempts <= acquisitionFailures ? 1 : 0;
                output = exit == 0 ? "ok\n" : "transient acquisition failure\n";
            } else if (command.contains("rev-parse")) {
                String packageName = command.get(command.indexOf("-C") + 1);
                output = packageName.endsWith("Blaster") && !packageName.endsWith("PlutusCore")
                        ? BLASTER + "\n"
                        : packageName.endsWith("PlutusCore") ? PLUTUS + "\n" : LEDGER + "\n";
                if (packageName.endsWith("CardanoLedgerApi")) output = LEDGER + "\n";
            } else if (command.getFirst().contains("verify")) {
                JsonNode plan = VerificationFiles.JSON.readTree(
                        workingDirectory.resolve(VerificationRunner.PLAN_FILE).toFile());
                if (plan.hasNonNull("resultManifest")) {
                    Path resultManifest = workingDirectory.resolve(
                            plan.path("resultManifest").asText());
                    Files.createDirectories(resultManifest.getParent());
                    Files.writeString(resultManifest, """
                            {"properties":[{"id":"evidence.property",
                              "evidence":"SMT-VALID","result":"ESTABLISHED"}]}
                            """);
                }
                if (!sequencedVerifyExits.isEmpty()) {
                    exit = sequencedVerifyExits.get(verifyCalls);
                    output = sequencedVerifyOutputs.get(verifyCalls);
                    verifyCalls++;
                } else {
                    exit = forcedVerifyExit != null ? forcedVerifyExit
                            : plan.path("verify").get(0)
                                    .path("expectedExitCodes").get(0).asInt();
                    output = includeMarker ? "RESULT-MARKER\n" : "no protocol output\n";
                    verifyCalls++;
                }
                timedOut = timeoutVerify;
            } else {
                output = "ok\n";
            }
            Files.createDirectories(logFile.getParent());
            Files.writeString(logFile, output);
            return new ProcessResult(exit, timedOut, output);
        }
    }

    private static void bindPlan(Path workspace) throws IOException {
        Path manifestFile = workspace.resolve("verification-manifest.json");
        var manifest = (com.fasterxml.jackson.databind.node.ObjectNode)
                VerificationFiles.JSON.readTree(manifestFile.toFile());
        manifest.put("runnerPlanSha256", VerificationFiles.sha256(
                workspace.resolve(VerificationRunner.PLAN_FILE)));
        VerificationFiles.JSON.writeValue(manifestFile.toFile(), manifest);
    }
}
