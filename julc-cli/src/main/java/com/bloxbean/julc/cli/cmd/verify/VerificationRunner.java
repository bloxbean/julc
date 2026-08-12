package com.bloxbean.julc.cli.cmd.verify;

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
    private static final int PLAN_SCHEMA_VERSION = 1;
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
        Path workspace = requireWorkspace(workspaceDirectory);
        // A result is evidence for one run. Never leave an older success in place
        // while validating a new or tampered plan.
        Files.deleteIfExists(workspace.resolve(RESULT_FILE));
        Path logs = workspace.resolve("verification-results");
        VerificationFiles.deleteTree(logs);
        Path planFile = VerificationFiles.containedRegularFile(workspace, PLAN_FILE, false);
        VerificationRunPlan plan = STRICT_JSON.readValue(planFile.toFile(), VerificationRunPlan.class);
        validatePlan(plan, workspace);
        if (plan.resultManifest() != null) {
            Files.deleteIfExists(VerificationFiles.containedPath(
                    workspace, plan.resultManifest()));
        }
        Path manifestFile = VerificationFiles.containedRegularFile(
                workspace, plan.manifest(), false);
        JsonNode manifest = VerificationFiles.JSON.readTree(manifestFile.toFile());
        Files.createDirectories(logs);
        var phases = new ArrayList<VerificationRunResult.Phase>();
        var properties = new ArrayList<VerificationRunResult.Property>();
        var diagnostic = new StringBuilder();
        Map<String, Object> artifact;
        try {
            artifact = preflight(workspace, plan, manifest);
        } catch (IOException e) {
            String reason = preflightReason(e.getMessage());
            return writeEarlyFailure(workspace, requestedBackend, artifactFallback(manifest),
                    planFile, manifestFile, logs, "preflight", reason, e.getMessage());
        }
        VerificationExecutionBackend backend;
        try {
            backend = selectBackend(requestedBackend, plan, workspace);
        } catch (IOException e) {
            return writeEarlyFailure(workspace, requestedBackend, artifact,
                    planFile, manifestFile, logs, "backend-selection",
                    "backend-unavailable", e.getMessage());
        }
        VerificationExecutionBackend.BackendContext backendContext = null;
        Map<String, String> verifiedDependencies = Map.of();
        VerificationOutcome overall = VerificationOutcome.COULD_NOT_EVALUATE;
        String reason = "runner-not-started";
        Duration timeout = Duration.ofSeconds(plan.timeoutSeconds());

        try {
            Path setupLog = logs.resolve("backend.log");
            try {
                backendContext = backend.prepare(workspace, process, timeout, setupLog);
                phases.add(phase("backend", "acquire", "PASSED", 0, workspace, setupLog));
            } catch (IOException e) {
                reason = "backend-unavailable";
                diagnostic.append(e.getMessage() == null ? "Backend setup failed" : e.getMessage());
                phases.add(phase("backend", "acquire", "FAILED", null, workspace, setupLog));
            }

            if (backendContext != null) {
                StepFailure failure = executeSteps(
                        plan.acquire(), "acquire", false, backend, backendContext,
                        workspace, logs, timeout, phases, properties);
                if (failure != null) {
                    reason = failure.reason();
                    diagnostic.append(failure.diagnostic());
                } else {
                    StepFailure revisions = validateDependencyRevisions(
                            manifest, plan.kind(), backend, backendContext,
                            workspace, logs, timeout, phases);
                    if (revisions != null) {
                        reason = revisions.reason();
                        diagnostic.append(revisions.diagnostic());
                    } else {
                        verifiedDependencies = dependencyPins(manifest, plan.kind());
                        failure = executeSteps(
                                plan.verify(), "verify", true, backend, backendContext,
                                workspace, logs, timeout, phases, properties);
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
                false,
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
                false,
                artifact,
                inputs,
                List.of(phase),
                List.of());
        VerificationFiles.writeJsonAtomically(workspace.resolve(RESULT_FILE), result);
        return new RunExecution(result, diagnostic == null ? reason : diagnostic);
    }

    private static String preflightReason(String diagnostic) {
        if (diagnostic == null) return "preflight-failed";
        String lower = diagnostic.toLowerCase(Locale.ROOT);
        if (lower.contains("artifact hash mismatch")
                || lower.contains("artifact lock hash mismatch")) {
            return "artifact-identity-mismatch";
        }
        if (lower.contains("executable hash mismatch")) return "executable-integrity-mismatch";
        if (lower.contains("runner plan hash mismatch")) return "runner-plan-integrity-mismatch";
        if (lower.contains("admission")) return "project-admission-detected";
        if (lower.contains("builtin")) return "unsupported-builtin";
        if (lower.contains("semantics profile")) return "unsupported-semantics-profile";
        return "preflight-failed";
    }

    private static Map<String, Object> artifactFallback(JsonNode manifest) {
        var result = new LinkedHashMap<String, Object>();
        result.put("kind", "unvalidated");
        for (String field : List.of("artifactId", "validatorTitle", "compiledCodeSha256",
                "cardanoScriptHash", "scriptPurpose")) {
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
            List<VerificationRunResult.Property> properties)
            throws IOException, InterruptedException {
        int index = 0;
        for (var step : steps) {
            index++;
            Path log = logs.resolve("%s-%02d-%s.log".formatted(phase, index, step.id()));
            List<String> command = backend.command(step.command(), workspace, offline);
            int maxAttempts = step.maxAttempts() == null ? 1 : step.maxAttempts();
            VerificationProcess.ProcessResult result = null;
            var attemptLogs = new ArrayList<Path>();
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                Path attemptLog = maxAttempts == 1 ? log : logs.resolve(
                        "%s-%02d-%s-attempt-%d.log".formatted(phase, index, step.id(), attempt));
                attemptLogs.add(attemptLog);
                result = process.execute(command, workspace,
                        backend.environment(backendContext, offline), timeout, attemptLog);
                boolean complete = !result.timedOut()
                        && step.expectedExitCodes().contains(result.exitCode())
                        && (step.requiredOutput() == null
                            || result.outputTail().contains(step.requiredOutput()));
                if (complete || result.timedOut()) break;
            }
            if (maxAttempts > 1) combineAttemptLogs(log, attemptLogs);
            boolean exitMatches = step.expectedExitCodes().contains(result.exitCode());
            boolean markerMatches = step.requiredOutput() == null
                    || result.outputTail().contains(step.requiredOutput());
            String status = result.timedOut() ? "TIMED-OUT"
                    : exitMatches && markerMatches ? "PASSED" : "FAILED";
            phases.add(phase(step.id(), phase, status, result.exitCode(), workspace, log));
            if (result.timedOut()) {
                return new StepFailure("acquire".equals(phase)
                        ? "acquisition-timeout" : "verification-timeout",
                        "Step '" + step.id() + "' exceeded " + timeout.toSeconds() + " seconds");
            }
            if (!exitMatches) {
                return new StepFailure("unexpected-exit-code",
                        "Step '" + step.id() + "' exited " + result.exitCode());
            }
            if (!markerMatches) {
                return new StepFailure("missing-result-marker",
                        "Step '" + step.id() + "' did not emit its required result marker");
            }
            if ("verify".equals(phase) && step.propertyId() != null) {
                var outcome = VerificationOutcome.parse(step.result());
                properties.add(new VerificationRunResult.Property(
                        step.propertyId(), outcome.externalName(), step.reason()));
            }
        }
        return null;
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
        if (plan.schemaVersion() != PLAN_SCHEMA_VERSION) {
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
            if (step.expectedExitCodes() == null || step.expectedExitCodes().isEmpty()
                    || step.expectedExitCodes().stream().anyMatch(
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
            if (step.propertyId() == null || !ID.matcher(step.propertyId()).matches()
                    || step.result() == null || step.reason() == null || step.reason().isBlank()
                    || step.requiredOutput() == null || step.requiredOutput().isBlank()) {
                throw new IOException("Verification step '" + step.id()
                        + "' requires a property, result, reason, and output marker");
            }
            VerificationOutcome.parse(step.result());
        }
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
        if (!Set.of("spending", "minting").contains(purpose)) {
            throw new IOException("Unsupported verification script purpose " + purpose);
        }
        var artifact = new LinkedHashMap<String, Object>();
        artifact.put("kind", "validator");
        artifact.put("artifactId", artifactId);
        artifact.put("validatorTitle", requiredText(manifest, "validatorTitle"));
        artifact.put("compiledCodeSha256", expectedHash);
        artifact.put("cardanoScriptHash", cardanoHash);
        artifact.put("scriptPurpose", purpose);
        artifact.put("protocolVersion", 11);
        artifact.put("builtinSemanticsVariant", "E");
        artifact.put("fuel", fuel);
        artifact.put("recursiveDepth", recursiveDepth);
        artifact.put("builtins", List.copyOf(builtinEvidence));
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
        result.put("projectLeanSha256", leanTreeHash(workspace));
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

    private static String leanTreeHash(Path workspace) throws IOException {
        var output = new ByteArrayOutputStream();
        for (Path path : VerificationFiles.projectLeanFiles(workspace)) {
            output.write(workspace.relativize(path).toString().replace('\\', '/')
                    .getBytes(StandardCharsets.UTF_8));
            output.write(0);
            output.write(Files.readAllBytes(path));
            output.write(0);
        }
        return VerificationFiles.sha256(output.toByteArray());
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
