package com.bloxbean.julc.cli.cmd.verify;

import com.bloxbean.cardano.julc.compiler.DataBoundarySemantics;
import com.bloxbean.cardano.julc.verification.ComposedDslProperty;
import com.bloxbean.cardano.julc.verification.dsl.ComposedDslPromotion;
import com.bloxbean.cardano.julc.verification.capability.LedgerCapabilityInventories;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Executes a versioned JuLC verification workspace and emits a canonical result. */
public final class VerificationRunner {

    public static final String PLAN_FILE = "verification-runner.json";
    public static final String RESULT_FILE = "verification-result.json";
    private static final Set<Integer> PLAN_SCHEMA_VERSIONS = Set.of(1, 2);
    private static final int RESULT_SCHEMA_VERSION = 1;
    private static final Pattern ID = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]*");
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
    private static final Set<Integer> SUPPORTED_BUILTINS = supportedBuiltins();
    private static final ObjectMapper STRICT_JSON = VerificationFiles.JSON.copy()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final VerificationProcess process;
    private final BackendFactory backendFactory;

    public VerificationRunner() {
        this(new VerificationProcess.SystemProcess(), VerificationRunner::createBackend);
    }

    VerificationRunner(VerificationProcess process, BackendFactory backendFactory) {
        this.process = process;
        this.backendFactory = backendFactory;
    }

    public RunExecution run(Path workspaceDirectory, VerificationBackendKind requestedBackend)
            throws IOException, InterruptedException {
        return run(workspaceDirectory, requestedBackend, VerificationProgress.silent());
    }

    RunExecution run(
            Path workspaceDirectory,
            VerificationBackendKind requestedBackend,
            VerificationProgress progress) throws IOException, InterruptedException {
        Path workspace;
        Path logs;
        Path planFile;
        VerificationRunPlan plan;
        Path manifestFile;
        JsonNode manifest;
        try (var task = progress.start("Validating workspace and runner plan")) {
            workspace = requireWorkspace(workspaceDirectory);
            // A result is evidence for one run. Never leave an older success in place
            // while validating a new or tampered plan.
            Files.deleteIfExists(workspace.resolve(RESULT_FILE));
            logs = workspace.resolve("verification-results");
            VerificationFiles.deleteTree(logs);
            planFile = VerificationFiles.containedRegularFile(workspace, PLAN_FILE, false);
            plan = STRICT_JSON.readValue(planFile.toFile(), VerificationRunPlan.class);
            validatePlan(plan, workspace);
            if (plan.resultManifest() != null) {
                Files.deleteIfExists(VerificationFiles.containedPath(
                        workspace, plan.resultManifest()));
            }
            manifestFile = VerificationFiles.containedRegularFile(
                    workspace, plan.manifest(), false);
            manifest = VerificationFiles.JSON.readTree(manifestFile.toFile());
            Files.createDirectories(logs);
            task.succeed();
        }
        var phases = new ArrayList<VerificationRunResult.Phase>();
        var properties = new ArrayList<VerificationRunResult.Property>();
        var diagnostic = new StringBuilder();
        Map<String, Object> artifact;
        try (var task = progress.start("Checking artifact, property, and generated-source hashes")) {
            try {
                artifact = preflight(workspace, plan, manifest);
                task.succeed();
            } catch (IOException e) {
                String reason = preflightReason(e.getMessage());
                task.fail(reason);
                return writeEarlyFailure(workspace, requestedBackend, artifactFallback(manifest),
                        planFile, manifestFile, logs, "preflight", reason, e.getMessage());
            }
        }
        VerificationExecutionBackend backend;
        try (var task = progress.start("Selecting verification backend")) {
            try {
                backend = selectBackend(requestedBackend, plan, workspace);
                task.succeed(backend.name());
            } catch (IOException e) {
                task.fail("backend-unavailable");
                return writeEarlyFailure(workspace, requestedBackend, artifact,
                        planFile, manifestFile, logs, "backend-selection",
                        "backend-unavailable", e.getMessage());
            }
        }
        VerificationExecutionBackend.BackendContext backendContext = null;
        Map<String, String> verifiedDependencies = Map.of();
        VerificationOutcome overall = VerificationOutcome.COULD_NOT_EVALUATE;
        String reason = "runner-not-started";
        Duration timeout = Duration.ofSeconds(plan.timeoutSeconds());

        try {
            Path setupLog = logs.resolve("backend.log");
            String backendSetup = "docker".equals(backend.name())
                    ? "Preparing Docker backend (first run may take several minutes)"
                    : "Checking local Lean and Z3 toolchain (may download Z3)";
            try (var task = progress.start(backendSetup)) {
                try {
                    backendContext = backend.prepare(workspace, process, timeout, setupLog);
                    phases.add(phase("backend", "acquire", "PASSED", 0, workspace, setupLog));
                    task.succeed(backendContext.identity());
                } catch (IOException e) {
                    reason = "backend-unavailable";
                    diagnostic.append(e.getMessage() == null ? "Backend setup failed" : e.getMessage());
                    phases.add(phase("backend", "acquire", "FAILED", null, workspace, setupLog));
                    task.fail("see verification-results/backend.log");
                }
            }

            if (backendContext != null) {
                StepFailure failure = executeSteps(
                        plan.acquire(), "acquire", false, backend, backendContext,
                        workspace, logs, timeout, phases, properties, progress);
                if (failure != null) {
                    reason = failure.reason();
                    diagnostic.append(failure.diagnostic());
                } else {
                    StepFailure revisions;
                    try (var task = progress.start("Checking pinned dependency revisions")) {
                        revisions = validateDependencyRevisions(
                                manifest, plan.kind(), backend, backendContext,
                                workspace, logs, timeout, phases);
                        if (revisions == null) task.succeed();
                        else task.fail(revisions.reason());
                    }
                    if (revisions != null) {
                        reason = revisions.reason();
                        diagnostic.append(revisions.diagnostic());
                    } else {
                        verifiedDependencies = dependencyPins(manifest, plan.kind());
                        failure = executeSteps(
                                plan.verify(), "verify", true, backend, backendContext,
                                workspace, logs, timeout, phases, properties, progress);
                        if (failure != null) {
                            reason = failure.reason();
                            diagnostic.append(failure.diagnostic());
                        } else {
                            if ("evidence-suite".equals(plan.kind())) {
                                Path evidenceResult = VerificationFiles.containedRegularFile(
                                        workspace, plan.resultManifest(), false);
                                JsonNode refreshed = VerificationFiles.JSON.readTree(
                                        evidenceResult.toFile());
                                appendEvidenceProperties(refreshed, properties);
                            }
                            overall = aggregate(properties);
                            reason = aggregateReason(properties, overall);
                        }
                    }
                }
            }
        } catch (IOException | InterruptedException e) {
            reason = e instanceof java.net.http.HttpTimeoutException
                    ? "tool-acquisition-timeout" : "runner-execution-failed";
            diagnostic.append(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            if (e instanceof InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
        } catch (RuntimeException e) {
            reason = "runner-execution-failed";
            diagnostic.append(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }

        combineLogs(logs, "acquire", phases);
        combineLogs(logs, "verify", phases);
        attachClaimMetadata(manifest, properties);
        Map<String, String> inputs = inputHashes(
                workspace, plan, planFile, manifestFile, logs);
        String backendIdentity = backendContext == null ? "unavailable" : backendContext.identity();
        var result = new VerificationRunResult(
                RESULT_SCHEMA_VERSION,
                "julc verify run",
                overall.externalName(),
                reason,
                backend.name(),
                backendIdentity,
                toolchainEvidence(backend, backendContext),
                verifiedDependencies,
                true,
                ledgerValidityModeled(artifact),
                artifact,
                inputs,
                List.copyOf(phases),
                List.copyOf(properties));
        VerificationFiles.writeJsonAtomically(workspace.resolve(RESULT_FILE), result);
        return new RunExecution(result, diagnostic.toString());
    }

    private static RunExecution writeEarlyFailure(
            Path workspace,
            VerificationBackendKind requestedBackend,
            Map<String, Object> artifact,
            Path planFile,
            Path manifestFile,
            Path logs,
            String phaseId,
        String reason,
        String diagnostic) throws IOException {
        var phase = new VerificationRunResult.Phase(
                phaseId, "preflight".equals(phaseId) ? "preflight" : "acquire",
                "FAILED", null, null, null);
        VerificationRunPlan plan = STRICT_JSON.readValue(planFile.toFile(), VerificationRunPlan.class);
        Map<String, String> inputs = inputHashes(
                workspace, plan, planFile, manifestFile, logs);
        var result = new VerificationRunResult(
                RESULT_SCHEMA_VERSION,
                "julc verify run",
                VerificationOutcome.COULD_NOT_EVALUATE.externalName(),
                reason,
                requestedBackend.name().toLowerCase(Locale.ROOT),
                "unavailable",
                Map.of(),
                Map.of(),
                true,
                ledgerValidityModeled(artifact),
                artifact,
                inputs,
                List.of(phase),
                List.of());
        VerificationFiles.writeJsonAtomically(workspace.resolve(RESULT_FILE), result);
        return new RunExecution(result, diagnostic == null ? reason : diagnostic);
    }

    private static boolean ledgerValidityModeled(Map<String, Object> artifact) {
        return Boolean.TRUE.equals(artifact.get("ledgerValidityModeled"));
    }

    private static String preflightReason(String diagnostic) {
        if (diagnostic == null) return "preflight-failed";
        String lower = diagnostic.toLowerCase(Locale.ROOT);
        if (lower.contains("artifact hash mismatch")
                || lower.contains("artifact lock hash mismatch")
                || lower.contains("property ir hash mismatch")) {
            return "artifact-identity-mismatch";
        }
        if (lower.contains("executable hash mismatch")) return "executable-integrity-mismatch";
        if (lower.contains("generated lean source hash mismatch")) {
            return "generated-source-integrity-mismatch";
        }
        if (lower.contains("runner plan hash mismatch")) return "runner-plan-integrity-mismatch";
        if (lower.contains("admission")) return "project-admission-detected";
        if (lower.contains("builtin")) return "unsupported-builtin";
        if (lower.contains("semantics profile")
                || lower.contains("boundary semantics")) {
            return "unsupported-semantics-profile";
        }
        return "preflight-failed";
    }

    private static Map<String, Object> artifactFallback(JsonNode manifest) {
        var result = new LinkedHashMap<String, Object>();
        result.put("kind", "unvalidated");
        for (String field : List.of("artifactId", "validatorTitle", "blueprintEntryTitle",
                "compiledCodeSha256", "cardanoScriptHash", "scriptPurpose",
                "boundarySemantics")) {
            if (manifest.hasNonNull(field)) result.put(field, manifest.path(field).asText());
        }
        return result;
    }

    private StepFailure executeSteps(
            List<VerificationRunPlan.Step> steps,
            String phase,
            boolean offline,
            VerificationExecutionBackend backend,
            VerificationExecutionBackend.BackendContext backendContext,
            Path workspace,
            Path logs,
            Duration timeout,
            List<VerificationRunResult.Phase> phases,
            List<VerificationRunResult.Property> properties,
            VerificationProgress progress)
            throws IOException, InterruptedException {
        var blockedNonVacuityGuards = new LinkedHashMap<String, String>();
        for (int stepIndex = 0; stepIndex < steps.size(); stepIndex++) {
            var step = steps.get(stepIndex);
            if (step.nonVacuityGuardPropertyId() != null
                    && blockedNonVacuityGuards.containsKey(
                            step.nonVacuityGuardPropertyId())) {
                String guardReason = blockedNonVacuityGuards.get(
                        step.nonVacuityGuardPropertyId());
                boolean vacuous = "property-vacuous".equals(guardReason);
                phases.add(new VerificationRunResult.Phase(
                        step.id(), phase, "SKIPPED", null, null, null));
                properties.add(new VerificationRunResult.Property(
                        step.propertyId(),
                        VerificationOutcome.COULD_NOT_EVALUATE.externalName(),
                        vacuous ? "not-evaluated-vacuous"
                                : "not-evaluated-non-vacuity-undetermined"));
                progress.skipped(stepDescription(step.id(), phase), vacuous
                        ? "property is vacuous" : "non-vacuity was not established");
                continue;
            }
            try (var task = progress.start(stepDescription(step.id(), phase))) {
                int displayIndex = stepIndex + 1;
                Path log = logs.resolve("%s-%02d-%s.log".formatted(
                        phase, displayIndex, step.id()));
                List<String> command = backend.command(step.command(), workspace, offline);
                int maxAttempts = step.maxAttempts() == null ? 1 : step.maxAttempts();
                VerificationProcess.ProcessResult result = null;
                var attemptLogs = new ArrayList<Path>();
                for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                    Path attemptLog = maxAttempts == 1 ? log : logs.resolve(
                            "%s-%02d-%s-attempt-%d.log".formatted(
                                    phase, displayIndex, step.id(), attempt));
                    attemptLogs.add(attemptLog);
                    result = process.execute(command, workspace,
                            backend.environment(backendContext, offline), timeout, attemptLog);
                    boolean complete = !result.timedOut() && observedOutcome(step, result) != null;
                    if (complete || result.timedOut()) break;
                }
                if (maxAttempts > 1) combineAttemptLogs(log, attemptLogs);
                VerificationRunPlan.ObservedOutcome observed = observedOutcome(step, result);
                boolean exitMatches = expectedExitCodes(step).contains(result.exitCode());
                boolean markerMatches = observed != null;
                String status = result.timedOut() ? "TIMED-OUT"
                        : exitMatches && markerMatches ? "PASSED" : "FAILED";
                phases.add(phase(step.id(), phase, status, result.exitCode(), workspace, log));
                if (result.timedOut()) {
                    task.fail("timed out; see " + workspace.relativize(log));
                    return new StepFailure("acquire".equals(phase)
                            ? "acquisition-timeout" : "verification-timeout",
                            "Step '" + step.id() + "' exceeded " + timeout.toSeconds() + " seconds");
                }
                if (!exitMatches) {
                    task.fail("exit " + result.exitCode() + "; see " + workspace.relativize(log));
                    return new StepFailure("unexpected-exit-code",
                            "Step '" + step.id() + "' exited " + result.exitCode());
                }
                if (!markerMatches) {
                    task.fail("missing result marker; see " + workspace.relativize(log));
                    return new StepFailure("missing-result-marker",
                            "Step '" + step.id() + "' did not emit its required result marker");
                }
                if ("verify".equals(phase)) {
                    task.complete(verificationDetail(step, observed));
                } else {
                    task.succeed();
                }
                if ("verify".equals(phase) && step.propertyId() != null) {
                    var outcome = VerificationOutcome.parse(observed.result());
                    properties.add(new VerificationRunResult.Property(
                            step.propertyId(), outcome.externalName(), observed.reason()));
                    if (!"expected-negative-control".equals(observed.reason())) {
                        boolean guardedProofExists = steps.subList(stepIndex + 1, steps.size())
                                .stream().anyMatch(candidate -> step.propertyId().equals(
                                        candidate.nonVacuityGuardPropertyId()));
                        if (guardedProofExists) {
                            blockedNonVacuityGuards.put(step.propertyId(), observed.reason());
                        } else if ("property-vacuous".equals(observed.reason())) {
                            appendVacuitySkippedSteps(
                                    steps, stepIndex + 1, phase, phases, properties, progress);
                            break;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static String stepDescription(String id, String phase) {
        if ("verify".equals(phase) && id.startsWith("check-non-vacuity-")) {
            return "Checking " + id.substring("check-non-vacuity-".length())
                    .replace('_', ' ') + " non-vacuity";
        }
        if ("verify".equals(phase) && id.startsWith("prove-")) {
            return "Proving " + id.substring("prove-".length())
                    .replace('-', ' ').replace('_', ' ');
        }
        return switch (id) {
            case "lake-update" -> "Acquiring pinned Lean dependencies";
            case "build-pinned-dependencies" -> "Building pinned Lean dependencies";
            case "check-non-vacuity" -> "Checking property non-vacuity";
            case "compile-unspecialized-property" -> "Checking generated property workspace";
            default -> "verify".equals(phase)
                    ? "Running proof step '" + id + "'"
                    : "Running acquisition step '" + id + "'";
        };
    }

    private static String verificationDetail(
            VerificationRunPlan.Step step,
            VerificationRunPlan.ObservedOutcome observed) {
        if ("check-non-vacuity".equals(step.id())) {
            if ("expected-negative-control".equals(observed.reason())) {
                return "non-vacuous";
            }
            if ("property-vacuous".equals(observed.reason())) {
                return "COULD-NOT-EVALUATE - property is vacuous";
            }
        }
        String reason = observed.reason() == null
                ? "classified" : observed.reason().replace('-', ' ');
        return observed.result() + " - " + reason;
    }

    private static void appendVacuitySkippedSteps(
            List<VerificationRunPlan.Step> steps,
            int firstSkipped,
            String phase,
            List<VerificationRunResult.Phase> phases,
            List<VerificationRunResult.Property> properties,
            VerificationProgress progress) {
        for (int index = firstSkipped; index < steps.size(); index++) {
            var skipped = steps.get(index);
            phases.add(new VerificationRunResult.Phase(
                    skipped.id(), phase, "SKIPPED", null, null, null));
            if (skipped.propertyId() != null) {
                properties.add(new VerificationRunResult.Property(
                        skipped.propertyId(),
                        VerificationOutcome.COULD_NOT_EVALUATE.externalName(),
                        "not-evaluated-vacuous"));
            }
            progress.skipped(stepDescription(skipped.id(), phase), "property is vacuous");
        }
    }

    private static void combineAttemptLogs(Path target, List<Path> attempts) throws IOException {
        var bytes = new ByteArrayOutputStream();
        int number = 0;
        for (Path attempt : attempts) {
            number++;
            bytes.write(("== attempt " + number + " ==\n").getBytes(StandardCharsets.UTF_8));
            if (Files.isRegularFile(attempt)) bytes.write(Files.readAllBytes(attempt));
            bytes.write('\n');
        }
        VerificationFiles.writeAtomically(target, bytes.toByteArray());
        for (Path attempt : attempts) Files.deleteIfExists(attempt);
    }

    private StepFailure validateDependencyRevisions(
            JsonNode manifest,
            String kind,
            VerificationExecutionBackend backend,
            VerificationExecutionBackend.BackendContext backendContext,
            Path workspace,
            Path logs,
            Duration timeout,
            List<VerificationRunResult.Phase> phases)
            throws IOException, InterruptedException {
        Map<String, String> pins = dependencyPins(manifest, kind);
        List<Map.Entry<String, String>> packages = List.of(
                Map.entry("Lean-blaster", "Blaster"),
                Map.entry("PlutusCoreBlaster", "PlutusCore"),
                Map.entry("CardanoLedgerApiBlaster", "CardanoLedgerApi"));
        for (var entry : packages) {
            String expected = pins.get(entry.getKey());
            if (!COMMIT.matcher(expected).matches()) {
                return new StepFailure("invalid-dependency-pin",
                        "Dependency " + entry.getKey() + " is not pinned to a full commit");
            }
            String id = "revision-" + entry.getValue().toLowerCase(Locale.ROOT);
            Path log = logs.resolve(id + ".log");
            var result = process.execute(backend.command(List.of(
                            "git", "-C", ".lake/packages/" + entry.getValue(),
                            "rev-parse", "HEAD"), workspace, false),
                    workspace, backend.environment(backendContext, false), timeout, log);
            boolean valid = !result.timedOut() && result.exitCode() == 0
                    && result.outputTail().trim().endsWith(expected);
            phases.add(phase(id, "acquire", valid ? "PASSED" : "FAILED",
                    result.exitCode(), workspace, log));
            if (!valid) {
                return new StepFailure("dependency-revision-mismatch",
                        "Dependency " + entry.getKey() + " does not match " + expected);
            }
        }
        return null;
    }

    private static void validatePlan(VerificationRunPlan plan, Path workspace) throws IOException {
        if (!PLAN_SCHEMA_VERSIONS.contains(plan.schemaVersion())) {
            throw new IOException("Unsupported verification runner schema " + plan.schemaVersion());
        }
        if (!Set.of("generated-workspace", "evidence-suite").contains(plan.kind())) {
            throw new IOException("Unsupported verification runner kind '" + plan.kind() + "'");
        }
        if ("evidence-suite".equals(plan.kind())) {
            VerificationFiles.containedPath(workspace, plan.resultManifest());
            if (Set.of(PLAN_FILE, RESULT_FILE, plan.manifest())
                    .contains(plan.resultManifest())) {
                throw new IOException("Evidence result manifest overlaps a runner input/output");
            }
        } else if (plan.resultManifest() != null) {
            throw new IOException("Generated workspace plans must not declare a result manifest");
        }
        if (plan.timeoutSeconds() <= 0 || plan.timeoutSeconds() > 7200) {
            throw new IOException("Verification timeout must be between 1 and 7200 seconds");
        }
        if (plan.acquire() == null || plan.verify() == null || plan.verify().isEmpty()) {
            throw new IOException("Verification runner plan requires acquisition and verification steps");
        }
        var ids = new LinkedHashSet<String>();
        for (var step : concat(plan.acquire(), plan.verify())) {
            if (step == null || step.id() == null || !ID.matcher(step.id()).matches()
                    || !ids.add(step.id())) {
                throw new IOException("Verification plan step IDs must be unique safe identifiers");
            }
            if (step.command() == null || step.command().isEmpty()
                    || step.command().stream().anyMatch(token -> token == null
                            || token.isBlank() || token.contains("\n") || token.contains("\0"))) {
                throw new IOException("Verification step '" + step.id() + "' has an invalid command");
            }
            LocalVerificationBackend.validateCommand(step.command(), workspace);
            String executable = step.command().getFirst();
            boolean relativeExecutable = executable.contains("/") || executable.contains("\\");
            if (relativeExecutable) {
                if (step.executableSha256() == null
                        || !step.executableSha256().matches("[0-9a-f]{64}")) {
                    throw new IOException("Verification step '" + step.id()
                            + "' must pin its workspace executable SHA-256");
                }
            } else if (step.executableSha256() != null) {
                throw new IOException("Bare tool step '" + step.id()
                        + "' must not declare a workspace executable hash");
            }
            if (expectedExitCodes(step).isEmpty()
                    || expectedExitCodes(step).stream().anyMatch(
                            code -> code == null || code < 0 || code > 255)) {
                throw new IOException("Verification step '" + step.id() + "' has no expected exit code");
            }
            int maxAttempts = step.maxAttempts() == null ? 1 : step.maxAttempts();
            if (maxAttempts < 1 || maxAttempts > 3
                    || (!plan.acquire().contains(step) && maxAttempts != 1)) {
                throw new IOException("Verification step '" + step.id()
                        + "' has an invalid retry count");
            }
        }
        for (var step : plan.verify()) {
            if (step.propertyId() == null || !ID.matcher(step.propertyId()).matches()) {
                throw new IOException("Verification step '" + step.id()
                        + "' requires a valid property ID");
            }
            if (step.outcomes() != null && !step.outcomes().isEmpty()) {
                if (plan.schemaVersion() < 2 || step.result() != null || step.reason() != null
                        || step.requiredOutput() != null || step.expectedExitCodes() != null) {
                    throw new IOException("Verification step '" + step.id()
                            + "' mixes static and observed outcome protocols");
                }
                var observations = new LinkedHashSet<String>();
                for (var outcome : step.outcomes()) {
                    if (outcome == null || outcome.exitCode() < 0 || outcome.exitCode() > 255
                            || outcome.requiredOutput() == null
                            || outcome.requiredOutput().isBlank()
                            || outcome.reason() == null || outcome.reason().isBlank()
                            || !observations.add(outcome.exitCode() + "\0"
                                    + outcome.requiredOutput())) {
                        throw new IOException("Verification step '" + step.id()
                                + "' has an invalid observed outcome");
                    }
                    VerificationOutcome.parse(outcome.result());
                }
            } else {
                if (step.result() == null || step.reason() == null || step.reason().isBlank()
                        || step.requiredOutput() == null || step.requiredOutput().isBlank()) {
                    throw new IOException("Verification step '" + step.id()
                            + "' requires a result, reason, and output marker");
                }
                VerificationOutcome.parse(step.result());
            }
        }
        for (int index = 0; index < plan.verify().size(); index++) {
            var step = plan.verify().get(index);
            if (step.nonVacuityGuardPropertyId() == null) continue;
            if (plan.schemaVersion() < 2
                    || !ID.matcher(step.nonVacuityGuardPropertyId()).matches()) {
                throw new IOException("Verification step '" + step.id()
                        + "' has an invalid vacuity guard");
            }
            boolean guardedByPriorStep = plan.verify().subList(0, index).stream()
                    .anyMatch(candidate -> step.nonVacuityGuardPropertyId()
                            .equals(candidate.propertyId())
                            && candidate.outcomes() != null
                            && candidate.outcomes().stream().anyMatch(outcome ->
                                    "property-vacuous".equals(outcome.reason())));
            if (!guardedByPriorStep) {
                throw new IOException("Verification step '" + step.id()
                        + "' refers to no prior vacuity observation");
            }
        }
    }

    private static List<Integer> expectedExitCodes(VerificationRunPlan.Step step) {
        if (step.outcomes() != null && !step.outcomes().isEmpty()) {
            return step.outcomes().stream()
                    .map(VerificationRunPlan.ObservedOutcome::exitCode).distinct().toList();
        }
        return step.expectedExitCodes() == null ? List.of() : step.expectedExitCodes();
    }

    private static VerificationRunPlan.ObservedOutcome observedOutcome(
            VerificationRunPlan.Step step, VerificationProcess.ProcessResult result) {
        if (result == null || result.timedOut()) return null;
        if (step.outcomes() != null && !step.outcomes().isEmpty()) {
            return step.outcomes().stream()
                    .filter(outcome -> outcome.exitCode() == result.exitCode()
                            && result.outputTail().contains(outcome.requiredOutput()))
                    .findFirst().orElse(null);
        }
        if (step.expectedExitCodes() != null
                && step.expectedExitCodes().contains(result.exitCode())
                && (step.requiredOutput() == null
                    || result.outputTail().contains(step.requiredOutput()))) {
            return new VerificationRunPlan.ObservedOutcome(
                    result.exitCode(), step.requiredOutput(), step.result(), step.reason());
        }
        return null;
    }

    private static Map<String, Object> preflight(
            Path workspace, VerificationRunPlan plan, JsonNode manifest) throws IOException {
        validateExecutableHashes(workspace, plan);
        List<String> admissions = VerificationFiles.findAdmissions(workspace);
        if (!admissions.isEmpty()) {
            throw new IOException("Project Lean contains an admission at " + admissions.getFirst());
        }
        if ("evidence-suite".equals(plan.kind())) {
            if (manifest.path("schemaVersion").asInt(-1) != 1
                    || !manifest.has("verificationProfile") || !manifest.has("artifactLock")) {
                throw new IOException("Evidence-suite manifest is incomplete");
            }
            validateRunnerPlanHash(workspace, manifest);
            validateProfile(manifest.path("verificationProfile"), false);
            validateDependencyPins(manifest, plan.kind());
            String lockPath = requiredText(manifest.path("artifactLock"), "path");
            String expectedLockHash = requiredHash(
                    manifest.path("artifactLock"), "sha256", 64);
            Path lockFile = VerificationFiles.containedRegularFile(workspace, lockPath, false);
            String actualLockHash = VerificationFiles.sha256(lockFile);
            if (!actualLockHash.equals(expectedLockHash)) {
                throw new IOException("Evidence artifact lock hash mismatch");
            }
            return Map.of("kind", "evidence-suite",
                    "artifactLockSha256", actualLockHash);
        }
        if (manifest.path("schemaVersion").asInt(-1) != 1) {
            throw new IOException("Unsupported verification manifest schema");
        }
        String artifactId = requiredText(manifest, "artifactId");
        validateRunnerPlanHash(workspace, manifest);
        validateProfile(manifest, true);
        validateDependencyPins(manifest, plan.kind());
        String expectedHash = requiredHash(manifest, "compiledCodeSha256", 64);
        String cardanoHash = requiredHash(manifest, "cardanoScriptHash", 56);
        String hex = Files.readString(VerificationFiles.containedRegularFile(
                workspace, "artifacts/" + artifactId + ".compiledCode.hex", false))
                .replaceAll("\\s+", "");
        byte[] artifactBytes;
        try {
            artifactBytes = HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException e) {
            throw new IOException("Compiled-code artifact is not valid hexadecimal", e);
        }
        if (!VerificationFiles.sha256(artifactBytes).equals(expectedHash)) {
            throw new IOException("Compiled-code artifact hash mismatch");
        }
        JsonNode builtins = manifest.path("builtins");
        if (!builtins.isArray()) throw new IOException("Verification manifest is missing builtins");
        var builtinEvidence = new ArrayList<Map<String, Object>>();
        var seenBuiltinTags = new LinkedHashSet<Integer>();
        for (JsonNode builtin : builtins) {
            int tag = builtin.path("flatTag").asInt(-1);
            String name = builtin.path("name").asText();
            if (name.isBlank() || !SUPPORTED_BUILTINS.contains(tag)
                    || !seenBuiltinTags.add(tag)) {
                throw new IOException("Unsupported builtin tag " + tag + " in verification manifest");
            }
            builtinEvidence.add(Map.of("name", name, "flatTag", tag));
        }
        int fuel = manifest.path("fuel").asInt(-1);
        int recursiveDepth = manifest.path("recursiveDepth").asInt(-1);
        if (fuel <= 0 || recursiveDepth <= 0) {
            throw new IOException("Verification manifest has invalid fuel or recursive depth");
        }
        String purpose = requiredText(manifest, "scriptPurpose");
        if (!Set.of("spending", "minting", "rewarding", "certifying")
                .contains(purpose)) {
            throw new IOException("Unsupported verification script purpose " + purpose);
        }
        var artifact = new LinkedHashMap<String, Object>();
        artifact.put("kind", "validator");
        artifact.put("artifactId", artifactId);
        artifact.put("validatorTitle", requiredText(manifest, "validatorTitle"));
        artifact.put("blueprintEntryTitle", manifest.path("blueprintEntryTitle")
                .asText(requiredText(manifest, "validatorTitle")));
        artifact.put("compiledCodeSha256", expectedHash);
        artifact.put("cardanoScriptHash", cardanoHash);
        artifact.put("scriptPurpose", purpose);
        String boundarySemantics = manifest.path("boundarySemantics")
                .asText(DataBoundarySemantics.LEGACY_V0);
        if (!Set.of(DataBoundarySemantics.STRICT_V1,
                DataBoundarySemantics.LEGACY_V0,
                DataBoundarySemantics.EXTERNAL_UNCLASSIFIED).contains(boundarySemantics)) {
            throw new IOException("Unsupported data-boundary semantics " + boundarySemantics);
        }
        artifact.put("boundarySemantics", boundarySemantics);
        artifact.put("protocolVersion", 11);
        artifact.put("builtinSemanticsVariant", "E");
        artifact.put("fuel", fuel);
        artifact.put("recursiveDepth", recursiveDepth);
        artifact.put("builtins", List.copyOf(builtinEvidence));
        if (manifest.has("generatedLeanSha256")) {
            String expectedLeanHash = requiredHash(manifest, "generatedLeanSha256", 64);
            if (!VerificationFiles.leanTreeHash(workspace).equals(expectedLeanHash)) {
                throw new IOException("Generated Lean source hash mismatch");
            }
            artifact.put("generatedLeanSha256", expectedLeanHash);
        }
        if (manifest.has("propertyIr")) {
            JsonNode propertyIr = manifest.path("propertyIr");
            String propertyPath = requiredText(propertyIr, "path");
            String propertyHash = requiredHash(propertyIr, "sha256", 64);
            Path propertyFile = VerificationFiles.containedRegularFile(
                    workspace, propertyPath, false);
            if (!VerificationFiles.sha256(propertyFile).equals(propertyHash)) {
                throw new IOException("Verification property IR hash mismatch");
            }
            JsonNode property = VerificationFiles.JSON.readTree(propertyFile.toFile());
            String template = requiredText(property, "template");
            boolean sellerPayment = "julc.dsl.seller-paid-at-least/v1".equals(template);
            boolean oneShotMint = "julc.dsl.one-shot-authorized-mint/v1".equals(template);
            boolean composedDsl = ComposedDslProperty.TEMPLATE.equals(template)
                    || ComposedDslProperty.TYPED_TEMPLATE.equals(template);
            boolean ledgerValidityModeled = composedDsl
                    ? property.path("ledgerValidityModeled").asBoolean(false)
                    : sellerPayment || oneShotMint;
            int expectedPropertySchema = ComposedDslProperty.TYPED_TEMPLATE.equals(template)
                    ? ComposedDslProperty.TYPED_SCHEMA_VERSION : 1;
            if (property.path("schemaVersion").asInt(-1) != expectedPropertySchema
                    || property.path("schemaVersion").asInt(-1)
                        != propertyIr.path("schemaVersion").asInt(-2)
                    || !requiredText(property, "template")
                        .equals(requiredText(propertyIr, "template"))
                    || !Set.of("julc.requires-signer/v1",
                            "julc.stateful-spending/v1",
                            "julc.controlled-mint/v1",
                            "julc.dsl.seller-paid-at-least/v1",
                            "julc.dsl.one-shot-authorized-mint/v1",
                            ComposedDslProperty.TEMPLATE,
                            ComposedDslProperty.TYPED_TEMPLATE).contains(template)
                    || !requiredText(property, "propertyId")
                        .equals(requiredText(propertyIr, "propertyId"))
                    || !requiredText(property, "validatorTitle")
                        .equals(requiredText(manifest, "validatorTitle"))
                    || !requiredText(property, "scriptPurpose").equals(purpose)
                    || !requiredText(property, "sourcePath")
                        .equals(requiredText(propertyIr, "sourcePath"))
                    || property.path("ledgerValidityModeled")
                        .asBoolean(!ledgerValidityModeled) != ledgerValidityModeled
                    || manifest.path("ledgerValidityModeled")
                        .asBoolean(!ledgerValidityModeled) != ledgerValidityModeled
                    || !property.path("domainAssumptions").isArray()) {
                throw new IOException("Verification property IR does not match its manifest");
            }
            if (sellerPayment
                    && (!property.path("domainAssumptions").equals(
                            manifest.path("domainAssumptions"))
                        || property.path("domainAssumptions").size() != 1
                        || !"validSpendingContext/v3-pinned".equals(
                            property.path("domainAssumptions").path(0).asText()))) {
                throw new IOException("Verification seller-payment domain is unsupported");
            }
            if (oneShotMint
                    && (!property.path("domainAssumptions").equals(
                            manifest.path("domainAssumptions"))
                        || property.path("domainAssumptions").size() != 1
                        || !"validMintingContext/v3-pinned".equals(
                            property.path("domainAssumptions").path(0).asText()))) {
                throw new IOException("Verification one-shot mint domain is unsupported");
            }
            if (oneShotMint) validateCapabilityInventory(manifest);
            if (composedDsl) {
                if (!property.path("domainAssumptions").equals(
                        manifest.path("domainAssumptions"))
                        || !property.path("claims").isArray()
                        || property.path("claims").isEmpty()
                        || !property.path("claims").equals(manifest.path("claims"))) {
                    throw new IOException(
                            "Verification composed DSL claims or domains are tampered");
                }
                for (JsonNode claim : property.path("claims")) {
                    if (!claim.path("ledgerValidCounterexampleEstablished").isBoolean()
                            || !claim.path("concreteVmCounterexampleReproduced").isBoolean()
                            || claim.path("ledgerValidCounterexampleEstablished").asBoolean()
                            || claim.path("concreteVmCounterexampleReproduced").asBoolean()) {
                        throw new IOException(
                                "Verification composed DSL counterexample qualification is invalid");
                    }
                }
                try {
                    var composedProperty = VerificationFiles.JSON.treeToValue(
                            property, ComposedDslProperty.class);
                    ComposedDslPromotion.verifyIntegrity(composedProperty);
                    validateComposedPlanAndManifest(plan, manifest, composedProperty);
                } catch (IllegalArgumentException invalid) {
                    throw new IOException(
                            "Verification composed DSL property IR is inconsistent", invalid);
                }
                validateCapabilityInventory(manifest);
            }
            if (sellerPayment || oneShotMint
                    || "julc.controlled-mint/v1".equals(template) || composedDsl) {
                int expectedDslSchema = ComposedDslProperty.TYPED_TEMPLATE.equals(template)
                        ? 4 : composedDsl ? 3 : oneShotMint
                        || "julc.controlled-mint/v1".equals(template) ? 2 : 1;
                validateCanonicalDslIr(manifest, property, expectedDslSchema);
            }
            if ("julc.stateful-spending/v1".equals(template)
                    && (!"GREATER_THAN".equals(requiredText(property, "relation"))
                        || !"SINGLE_CONTINUING_OUTPUT".equals(
                            requiredText(property, "outputSelection")))) {
                throw new IOException("Verification stateful profile is unsupported");
            }
            if ("julc.controlled-mint/v1".equals(template)) {
                String authority = requiredText(property, "authorityHex");
                String tokenName = property.path("tokenNameHex").asText(null);
                String action = requiredText(property, "action");
                java.math.BigInteger quantity;
                try {
                    quantity = new java.math.BigInteger(requiredText(property, "quantity"));
                } catch (NumberFormatException invalid) {
                    throw new IOException("Verification controlled-mint quantity is invalid",
                            invalid);
                }
                if (!authority.matches("[0-9a-f]{56}")
                        || tokenName == null
                        || !tokenName.matches("(?:[0-9a-f]{2}){0,32}")
                        || !("MINT".equals(action) && quantity.signum() > 0
                            || "BURN".equals(action) && quantity.signum() < 0)) {
                    throw new IOException("Verification controlled-mint profile is unsupported");
                }
            }
            if (oneShotMint) {
                if (!requiredText(property, "authorityHex").matches("[0-9a-f]{56}")
                        || !requiredText(property, "anchorTransactionIdHex")
                            .matches("[0-9a-f]{64}")
                        || !requiredText(property, "anchorOutputIndex")
                            .matches("0|[1-9][0-9]{0,18}")
                        || !property.path("tokenNameHex").asText("!")
                            .matches("(?:[0-9a-f]{2}){0,32}")
                        || !"1".equals(requiredText(property, "quantity"))) {
                    throw new IOException("Verification one-shot mint profile is unsupported");
                }
            }
            artifact.put("propertyIrSha256", propertyHash);
            artifact.put("propertyTemplate", requiredText(propertyIr, "template"));
            artifact.put("propertyId", requiredText(propertyIr, "propertyId"));
            artifact.put("propertyPath", requiredText(propertyIr, "sourcePath"));
            if (manifest.has("dslIr")) {
                artifact.put("dslIrSchemaVersion",
                        manifest.path("dslIr").path("schemaVersion").asInt());
                artifact.put("dslIrSha256",
                        manifest.path("dslIr").path("sha256").asText());
            }
            artifact.put("ledgerValidityModeled", ledgerValidityModeled);
            artifact.put("fuelBounded", true);
            artifact.put("fuelScope", "Only executions completing within the pinned CEK fuel "
                    + "bound are covered; fuel-exhausted executions are outside the claim.");
            if ("julc.stateful-spending/v1".equals(template)) {
                artifact.put("relation", "GREATER_THAN");
                artifact.put("outputSelection", "SINGLE_CONTINUING_OUTPUT");
                artifact.put("valueEquality", "STRUCTURAL");
                artifact.put("globalMultiInputLinkageModeled", false);
            } else if ("julc.controlled-mint/v1".equals(template)) {
                artifact.put("action", requiredText(property, "action"));
                artifact.put("authorityHex", requiredText(property, "authorityHex"));
                artifact.put("tokenNameHex", property.path("tokenNameHex").asText());
                artifact.put("quantity", requiredText(property, "quantity"));
                artifact.put("ownPolicyLinkage", "SCRIPT_INFO_CURRENCY_SYMBOL");
                artifact.put("ownPolicyAssetShape", "EXACT_SINGLETON_RAW_ASSOCIATION_LIST");
                artifact.put("otherPoliciesPermitted", true);
            } else if (sellerPayment) {
                artifact.put("domainAssumptions", List.of(
                        "validSpendingContext/v3-pinned"));
                artifact.put("globalMultiInputLinkageModeled", false);
            } else if (oneShotMint) {
                artifact.put("domainAssumptions", List.of(
                        "validMintingContext/v3-pinned"));
                artifact.put("nonVacuityDomain", "BLASTER_VALID_MINTING_SUPERSET");
                artifact.put("ledgerValidNonVacuityWitnessEstablished", false);
                artifact.put("concreteVmSuccessfulWitnessReproduced", false);
                artifact.put("counterexampleDomain", "BLASTER_VALID_MINTING_SUPERSET");
                artifact.put("ledgerValidCounterexampleEstablished", false);
                artifact.put("concreteVmCounterexampleReproduced", false);
                artifact.put("anchorTransactionIdHex",
                        requiredText(property, "anchorTransactionIdHex"));
                artifact.put("anchorOutputIndex",
                        requiredText(property, "anchorOutputIndex"));
                artifact.put("ownPolicyAssetShape",
                        "EXACT_SINGLETON_RAW_ASSOCIATION_LIST");
                artifact.put("otherPoliciesPermitted", true);
            }
        }
        return Map.copyOf(artifact);
    }

    private static void validateExecutableHashes(Path workspace, VerificationRunPlan plan)
            throws IOException {
        for (var step : concat(plan.acquire(), plan.verify())) {
            String executable = step.command().getFirst();
            if (!executable.contains("/") && !executable.contains("\\")) continue;
            Path commandFile = VerificationFiles.containedRegularFile(
                    workspace, executable, true);
            if (!VerificationFiles.sha256(commandFile).equals(step.executableSha256())) {
                throw new IOException("Verification step '" + step.id()
                        + "' executable hash mismatch");
            }
        }
    }

    private static void validateRunnerPlanHash(Path workspace, JsonNode manifest)
            throws IOException {
        String expectedPlanHash = requiredHash(manifest, "runnerPlanSha256", 64);
        Path runnerPlan = VerificationFiles.containedRegularFile(
                workspace, PLAN_FILE, false);
        if (!VerificationFiles.sha256(runnerPlan).equals(expectedPlanHash)) {
            throw new IOException("Verification runner plan hash mismatch");
        }
    }

    private static void validateProfile(JsonNode profile, boolean requireExecutionBounds)
            throws IOException {
        if (!"E".equals(requiredText(profile, "builtinSemanticsVariant"))
                || profile.path("protocolVersion").asInt(-1) != 11
                || !"PlutusV3".equals(requiredText(profile, "plutusLanguage"))
                || !"4.24.0".equals(requiredText(profile, "leanVersion"))
                || !"4.15.2".equals(requiredText(profile, "z3Version"))) {
            throw new IOException("Unsupported verification semantics profile");
        }
        if (requireExecutionBounds && (!profile.has("fuel") || !profile.has("recursiveDepth"))) {
            throw new IOException("Verification semantics profile is missing execution bounds");
        }
    }

    private static void validateDependencyPins(JsonNode manifest, String kind) throws IOException {
        for (var entry : dependencyPins(manifest, kind).entrySet()) {
            if (!COMMIT.matcher(entry.getValue()).matches()) {
                throw new IOException("Dependency " + entry.getKey()
                        + " is not pinned to a full commit");
            }
        }
    }

    private static void validateCapabilityInventory(JsonNode manifest) throws IOException {
        JsonNode recorded = manifest.path("capabilityInventory");
        var inventory = LedgerCapabilityInventories.pinnedV3();
        String expectedHash = VerificationFiles.sha256(
                LedgerCapabilityInventories.pinnedV3Bytes());
        if (recorded.path("schemaVersion").asInt(-1) != inventory.schemaVersion()
                || !inventory.ledgerApi().equals(recorded.path("ledgerApi").asText())
                || !inventory.ledgerVersion().equals(recorded.path("ledgerVersion").asText())
                || !inventory.revision().equals(recorded.path("revision").asText())
                || !expectedHash.equals(recorded.path("sha256").asText())) {
            throw new IOException(
                    "Verification capability inventory is missing, stale, or tampered");
        }
    }

    private static void validateCanonicalDslIr(
            JsonNode manifest, JsonNode property, int expectedSchema) throws IOException {
        JsonNode recorded = manifest.path("dslIr");
        String canonicalDsl = property.path("canonicalDslJson").asText(null);
        if (canonicalDsl == null
                || recorded.path("schemaVersion").asInt(-1) != expectedSchema
                || !VerificationFiles.sha256(canonicalDsl.getBytes(
                        java.nio.charset.StandardCharsets.UTF_8))
                    .equals(recorded.path("sha256").asText())) {
            throw new IOException("Verification canonical DSL IR is missing or tampered");
        }
        JsonNode dsl;
        try {
            dsl = VerificationFiles.JSON.readTree(canonicalDsl);
        } catch (IOException invalid) {
            throw new IOException("Verification canonical DSL IR is invalid", invalid);
        }
        if (dsl.path("schemaVersion").asInt(-1) != expectedSchema) {
            throw new IOException("Verification canonical DSL schema is unsupported");
        }
    }

    private static void validateComposedPlanAndManifest(
            VerificationRunPlan plan,
            JsonNode manifest,
            ComposedDslProperty property) throws IOException {
        var expected = new ArrayList<String>();
        for (var claim : property.claims()) {
            expected.add(claim.id() + ".non-vacuity");
            expected.add(claim.id());
        }
        List<String> manifestIds = java.util.stream.StreamSupport.stream(
                        manifest.path("properties").spliterator(), false)
                .map(node -> node.path("id").asText()).toList();
        List<String> planIds = plan.verify().stream()
                .map(VerificationRunPlan.Step::propertyId).toList();
        if (!expected.equals(manifestIds) || !expected.equals(planIds)) {
            throw new IOException(
                    "Composed runner plan does not cover every claim exactly once");
        }
        for (int index = 0; index < property.claims().size(); index++) {
            var claim = property.claims().get(index);
            String nonVacuityId = claim.id() + ".non-vacuity";
            String safe = ComposedDslPromotion.generatedName(claim.id())
                    .toLowerCase(Locale.ROOT);
            var nonVacuity = plan.verify().get(index * 2);
            var proof = plan.verify().get(index * 2 + 1);
            if (nonVacuity.nonVacuityGuardPropertyId() != null
                    || !nonVacuityId.equals(proof.nonVacuityGuardPropertyId())
                    || !("check-non-vacuity-" + safe).equals(nonVacuity.id())
                    || !("prove-dsl-" + safe).equals(proof.id())
                    || !List.of("scripts/verify-" + safe + "-non-vacuity.sh")
                            .equals(nonVacuity.command())
                    || !List.of("scripts/verify-" + safe + ".sh")
                            .equals(proof.command())
                    || !composedNonVacuityOutcomes().equals(nonVacuity.outcomes())
                    || !composedProofOutcomes(claim.id()).equals(proof.outcomes())) {
                throw new IOException(
                        "Composed proof protocol is not bound to its canonical claim");
            }
        }
    }

    private static List<VerificationRunPlan.ObservedOutcome> composedNonVacuityOutcomes() {
        return List.of(
                new VerificationRunPlan.ObservedOutcome(0,
                        "NON-VACUOUS: successful input witness exists",
                        "REFUTED", "expected-negative-control"),
                new VerificationRunPlan.ObservedOutcome(4,
                        "VACUOUS: validator has no successful input",
                        "COULD-NOT-EVALUATE", "property-vacuous"),
                new VerificationRunPlan.ObservedOutcome(2,
                        "COULD-NOT-EVALUATE: non-vacuity",
                        "COULD-NOT-EVALUATE", "non-vacuity-undetermined"));
    }

    private static List<VerificationRunPlan.ObservedOutcome> composedProofOutcomes(
            String claimId) {
        return List.of(
                new VerificationRunPlan.ObservedOutcome(0,
                        "SMT-VALID: DSL property " + claimId + " established",
                        "SMT-VALID", "dsl-property-established"),
                new VerificationRunPlan.ObservedOutcome(3,
                        "REFUTED: DSL property " + claimId + " counterexample found",
                        "REFUTED", "dsl-property-counterexample"),
                new VerificationRunPlan.ObservedOutcome(2,
                        "COULD-NOT-EVALUATE: DSL property " + claimId,
                        "COULD-NOT-EVALUATE", "dsl-property-undetermined"));
    }

    private VerificationExecutionBackend selectBackend(
            VerificationBackendKind requested,
            VerificationRunPlan plan,
            Path workspace) throws IOException, InterruptedException {
        if (requested == VerificationBackendKind.DOCKER && "evidence-suite".equals(plan.kind())) {
            throw new IOException("The repository evidence suite currently requires --backend local");
        }
        VerificationBackendKind selected = requested;
        if (selected == VerificationBackendKind.AUTO) {
            selected = localToolchainReady(workspace)
                    ? VerificationBackendKind.LOCAL
                    : dockerReady() && !"evidence-suite".equals(plan.kind())
                            ? VerificationBackendKind.DOCKER
                            : VerificationBackendKind.LOCAL;
        }
        return backendFactory.create(selected);
    }

    private boolean localToolchainReady(Path workspace) throws IOException, InterruptedException {
        Map<String, String> env = System.getenv();
        var lean = ExecutableLocator.find("lean", env);
        var lake = ExecutableLocator.find("lake", env);
        if (lean.isEmpty() || lake.isEmpty()
                || ExecutableLocator.find("git", env).isEmpty()) return false;
        Path leanLog = workspace.resolve("verification-results/.backend-selection-lean.log");
        Path lakeLog = workspace.resolve("verification-results/.backend-selection-lake.log");
        try {
            var leanVersion = process.execute(List.of(lean.get().toString(), "--version"),
                    workspace, env, Duration.ofSeconds(60), leanLog);
            var lakeVersion = process.execute(List.of(lake.get().toString(), "--version"),
                    workspace, env, Duration.ofSeconds(60), lakeLog);
            return !leanVersion.timedOut() && leanVersion.exitCode() == 0
                    && leanVersion.outputTail().contains("version 4.24.0")
                    && !lakeVersion.timedOut() && lakeVersion.exitCode() == 0
                    && lakeVersion.outputTail().contains("Lean version 4.24.0");
        } finally {
            Files.deleteIfExists(leanLog);
            Files.deleteIfExists(lakeLog);
        }
    }

    private boolean dockerReady() {
        return ExecutableLocator.find("docker", System.getenv()).isPresent();
    }

    private static VerificationExecutionBackend createBackend(VerificationBackendKind kind) {
        return switch (kind) {
            case LOCAL -> new LocalVerificationBackend();
            case DOCKER -> new DockerVerificationBackend();
            case AUTO -> throw new IllegalArgumentException("AUTO must be resolved before backend creation");
        };
    }

    private static Map<String, String> dependencyPins(JsonNode manifest, String kind) {
        JsonNode dependencies = "evidence-suite".equals(kind)
                ? manifest.path("verificationProfile").path("dependencies")
                : manifest.path("dependencies");
        var pins = new LinkedHashMap<String, String>();
        for (String name : List.of("Lean-blaster", "PlutusCoreBlaster",
                "CardanoLedgerApiBlaster")) {
            pins.put(name, dependencies.path(name).asText());
        }
        return Map.copyOf(pins);
    }

    private static Map<String, String> toolchainEvidence(
            VerificationExecutionBackend backend,
            VerificationExecutionBackend.BackendContext context) {
        if (context == null) return Map.of();
        var tools = new LinkedHashMap<String, String>();
        tools.put("lean", "4.24.0");
        tools.put("lake", "5.0.0-src+797c613");
        tools.put("z3", "4.15.2");
        if ("docker".equals(backend.name())) tools.put("dockerImageId", context.identity());
        return Map.copyOf(tools);
    }

    private static void appendEvidenceProperties(
            JsonNode manifest, List<VerificationRunResult.Property> target) {
        target.clear();
        for (JsonNode property : manifest.path("properties")) {
            String evidence = property.path("evidence").asText();
            String result = property.path("result").asText();
            VerificationOutcome outcome;
            if ("KERNEL-PROVED".equals(evidence)) outcome = VerificationOutcome.KERNEL_PROVED;
            else if ("SMT-VALID".equals(evidence)) outcome = VerificationOutcome.SMT_VALID;
            else if ("REFUTED".equals(result)) outcome = VerificationOutcome.REFUTED;
            else outcome = VerificationOutcome.COULD_NOT_EVALUATE;
            target.add(new VerificationRunResult.Property(
                    property.path("id").asText("unknown"),
                    outcome.externalName(),
                    "REFUTED".equals(result) ? "expected-negative-control" : "established"));
        }
    }

    private static void attachClaimMetadata(
            JsonNode manifest, List<VerificationRunResult.Property> properties) {
        if (!manifest.path("claims").isArray()) return;
        var claims = new LinkedHashMap<String, JsonNode>();
        for (JsonNode claim : manifest.path("claims")) {
            claims.put(claim.path("id").asText(), claim);
        }
        for (int index = 0; index < properties.size(); index++) {
            var result = properties.get(index);
            String claimId = result.id().endsWith(".non-vacuity")
                    ? result.id().substring(0,
                            result.id().length() - ".non-vacuity".length())
                    : result.id();
            JsonNode claim = claims.get(claimId);
            if (claim == null) continue;
            properties.set(index, new VerificationRunResult.Property(
                    result.id(), result.outcome(), result.reason(),
                    requiredTextUnchecked(claim, "domain"),
                    requiredTextUnchecked(claim, "guaranteeSha256"),
                    requiredTextUnchecked(claim, "envelopeSha256"),
                    java.util.stream.StreamSupport.stream(
                            claim.path("capabilities").spliterator(), false)
                            .map(JsonNode::asText).toList(),
                    requiredTextUnchecked(claim, "counterexampleDomain"),
                    claim.path("ledgerValidCounterexampleEstablished").asBoolean(),
                    claim.path("concreteVmCounterexampleReproduced").asBoolean()));
        }
    }

    private static String requiredTextUnchecked(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalStateException("Composed claim is missing " + field);
        }
        return value;
    }

    static VerificationOutcome aggregate(List<VerificationRunResult.Property> properties) {
        if (properties.isEmpty()) return VerificationOutcome.COULD_NOT_EVALUATE;
        boolean refuted = false;
        boolean couldNotEvaluate = false;
        boolean undetermined = false;
        boolean smt = false;
        boolean kernel = false;
        for (var property : properties) {
            VerificationOutcome outcome = VerificationOutcome.parse(property.outcome());
            if ("expected-negative-control".equals(property.reason())
                    && outcome == VerificationOutcome.REFUTED) continue;
            switch (outcome) {
                case REFUTED -> refuted = true;
                case COULD_NOT_EVALUATE -> couldNotEvaluate = true;
                case UNDETERMINED -> undetermined = true;
                case SMT_VALID -> smt = true;
                case KERNEL_PROVED -> kernel = true;
            }
        }
        return refuted ? VerificationOutcome.REFUTED
                : couldNotEvaluate ? VerificationOutcome.COULD_NOT_EVALUATE
                : undetermined ? VerificationOutcome.UNDETERMINED
                : smt ? VerificationOutcome.SMT_VALID
                : kernel ? VerificationOutcome.KERNEL_PROVED
                : VerificationOutcome.COULD_NOT_EVALUATE;
    }

    private static String aggregateReason(
            List<VerificationRunResult.Property> properties, VerificationOutcome outcome) {
        if (properties.stream().anyMatch(
                property -> "property-vacuous".equals(property.reason()))) {
            return "property-vacuous";
        }
        if (properties.size() == 1 && properties.getFirst().reason() != null) {
            return properties.getFirst().reason();
        }
        return switch (outcome) {
            case SMT_VALID, KERNEL_PROVED -> "all-properties-established";
            case REFUTED -> "property-refuted";
            case UNDETERMINED -> "solver-undetermined";
            case COULD_NOT_EVALUATE -> "property-could-not-be-evaluated";
        };
    }

    private static Map<String, String> inputHashes(
            Path workspace, VerificationRunPlan runPlan,
            Path plan, Path manifest, Path logs) throws IOException {
        var result = new LinkedHashMap<String, String>();
        result.put("verificationManifestSha256", VerificationFiles.sha256(manifest));
        result.put("runnerPlanSha256", VerificationFiles.sha256(plan));
        result.put("projectLeanSha256", VerificationFiles.leanTreeHash(workspace));
        JsonNode manifestJson = VerificationFiles.JSON.readTree(manifest.toFile());
        if (manifestJson.has("propertyIr")) {
            Path propertyIr = VerificationFiles.containedRegularFile(
                    workspace, requiredText(manifestJson.path("propertyIr"), "path"), false);
            result.put("propertyIrSha256", VerificationFiles.sha256(propertyIr));
        }
        if (runPlan.resultManifest() != null) {
            Path evidenceResult = VerificationFiles.containedPath(
                    workspace, runPlan.resultManifest());
            if (Files.isRegularFile(evidenceResult) && !Files.isSymbolicLink(evidenceResult)) {
                result.put("evidenceRunManifestSha256",
                        VerificationFiles.sha256(VerificationFiles.containedRegularFile(
                                workspace, runPlan.resultManifest(), false)));
            }
        }
        Path acquire = logs.resolve("acquire.log");
        Path verify = logs.resolve("verify.log");
        if (Files.isRegularFile(acquire)) result.put("acquireLogSha256", VerificationFiles.sha256(acquire));
        if (Files.isRegularFile(verify)) result.put("verifyLogSha256", VerificationFiles.sha256(verify));
        return result;
    }

    private static VerificationRunResult.Phase phase(
            String id, String phase, String status, Integer exitCode,
            Path workspace, Path log) throws IOException {
        return new VerificationRunResult.Phase(
                id, phase, status, exitCode,
                workspace.relativize(log).toString(),
                Files.isRegularFile(log) ? VerificationFiles.sha256(log) : null);
    }

    private static void combineLogs(
            Path logs, String phase, List<VerificationRunResult.Phase> phases) throws IOException {
        var bytes = new ByteArrayOutputStream();
        for (var item : phases) {
            if (!phase.equals(item.phase()) || item.log() == null) continue;
            Path source = logs.getParent().resolve(item.log());
            if (!Files.isRegularFile(source)) continue;
            bytes.write(("== " + item.id() + " ==\n").getBytes(StandardCharsets.UTF_8));
            bytes.write(Files.readAllBytes(source));
            bytes.write('\n');
        }
        if (bytes.size() > 0) {
            VerificationFiles.writeAtomically(logs.resolve(phase + ".log"), bytes.toByteArray());
        }
    }

    private static Path requireWorkspace(Path input) throws IOException {
        if (input == null || !Files.isDirectory(input)) {
            throw new IOException("Verification workspace not found: " + input);
        }
        return input.toRealPath();
    }

    private static String requiredText(JsonNode root, String name) throws IOException {
        String value = root.path(name).asText();
        if (value.isBlank()) throw new IOException("Verification manifest is missing " + name);
        return value;
    }

    private static String requiredHash(JsonNode root, String name, int length) throws IOException {
        String value = requiredText(root, name).toLowerCase(Locale.ROOT);
        if (!value.matches("[0-9a-f]{" + length + "}")) {
            throw new IOException("Verification manifest has invalid " + name);
        }
        return value;
    }

    private static List<VerificationRunPlan.Step> concat(
            List<VerificationRunPlan.Step> first,
            List<VerificationRunPlan.Step> second) {
        var result = new ArrayList<VerificationRunPlan.Step>(first);
        result.addAll(second);
        return result;
    }

    private static Set<Integer> supportedBuiltins() {
        var result = new java.util.HashSet<Integer>();
        for (int tag = 0; tag <= 88; tag++) result.add(tag);
        result.add(92);
        result.add(93);
        return Set.copyOf(result);
    }

    public record RunExecution(VerificationRunResult result, String diagnostic) {
    }

    private record StepFailure(String reason, String diagnostic) {
    }

    @FunctionalInterface
    interface BackendFactory {
        VerificationExecutionBackend create(VerificationBackendKind kind);
    }
}
