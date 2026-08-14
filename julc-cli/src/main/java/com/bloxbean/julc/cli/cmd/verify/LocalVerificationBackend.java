package com.bloxbean.julc.cli.cmd.verify;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class LocalVerificationBackend implements VerificationExecutionBackend {

    private final Z3Provisioner z3Provisioner;

    LocalVerificationBackend() {
        this(new Z3Provisioner());
    }

    LocalVerificationBackend(Z3Provisioner z3Provisioner) {
        this.z3Provisioner = z3Provisioner;
    }

    @Override
    public BackendContext prepare(
            Path workspace,
            VerificationProcess process,
            Duration timeout,
            Path logFile) throws IOException, InterruptedException {
        Map<String, String> base = new LinkedHashMap<>(System.getenv());
        var required = new LinkedHashMap<String, Path>();
        for (String tool : List.of("lake", "lean", "git")) {
            required.put(tool, ExecutableLocator.find(tool, base)
                    .orElseThrow(() -> new IOException("Missing local verification tool " + tool)));
        }

        Path leanLog = logFile.resolveSibling(".local-lean-version.log");
        var lean = process.execute(List.of(required.get("lean").toString(), "--version"),
                workspace, base, timeout, leanLog);
        if (lean.timedOut() || lean.exitCode() != 0
                || !lean.outputTail().contains("version 4.24.0")) {
            publishToolLogs(logFile, List.of(leanLog));
            throw new IOException("Expected local Lean 4.24.0");
        }

        Path lakeLog = logFile.resolveSibling(".local-lake-version.log");
        var lake = process.execute(List.of(required.get("lake").toString(), "--version"),
                workspace, base, timeout, lakeLog);
        if (lake.timedOut() || lake.exitCode() != 0
                || !lake.outputTail().contains("Lean version 4.24.0")) {
            publishToolLogs(logFile, List.of(leanLog, lakeLog));
            throw new IOException("Expected Lake for Lean 4.24.0");
        }

        Path z3ProbeLog = logFile.resolveSibling(".local-z3-probe.log");
        Path z3;
        try {
            var compatibleZ3 = findCompatibleZ3(
                    process, workspace, base, timeout, z3ProbeLog);
            z3 = compatibleZ3.isPresent()
                    ? compatibleZ3.get() : z3Provisioner.provision(workspace);
        } catch (IOException | InterruptedException e) {
            publishToolLogs(logFile, List.of(leanLog, lakeLog, z3ProbeLog));
            throw e;
        }
        required.put("z3", z3);

        String pathPrefix = z3.getParent() + java.io.File.pathSeparator
                + required.get("lean").getParent() + java.io.File.pathSeparator
                + base.getOrDefault("PATH", "");
        base.put("PATH", pathPrefix);

        Path z3Log = logFile.resolveSibling(".local-z3-version.log");
        var z3Version = process.execute(List.of(z3.toString(), "--version"),
                workspace, base, timeout, z3Log);
        publishToolLogs(logFile, List.of(leanLog, lakeLog, z3ProbeLog, z3Log));
        if (z3Version.timedOut() || z3Version.exitCode() != 0
                || !z3Version.outputTail().contains("version 4.15.2")) {
            throw new IOException("Expected local Z3 4.15.2");
        }
        return new BackendContext(
                "lean-4.24.0+z3-4.15.2",
                Map.of("PATH", pathPrefix, "z3", z3.toString()));
    }

    private static void publishToolLogs(Path target, List<Path> sources) throws IOException {
        var output = new java.io.ByteArrayOutputStream();
        for (Path source : sources) {
            if (!java.nio.file.Files.isRegularFile(source)) continue;
            output.write(("== " + source.getFileName() + " ==\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.write(java.nio.file.Files.readAllBytes(source));
            output.write('\n');
        }
        if (output.size() > 0) VerificationFiles.writeAtomically(target, output.toByteArray());
        for (Path source : sources) java.nio.file.Files.deleteIfExists(source);
    }

    private java.util.Optional<Path> findCompatibleZ3(
            VerificationProcess process,
            Path workspace,
            Map<String, String> environment,
            Duration timeout,
            Path logFile) throws IOException, InterruptedException {
        var system = ExecutableLocator.find("z3", environment);
        if (system.isEmpty()) return java.util.Optional.empty();
        var result = process.execute(List.of(system.get().toString(), "--version"),
                workspace, environment, timeout, logFile);
        return !result.timedOut() && result.exitCode() == 0
                && result.outputTail().contains("version 4.15.2")
                ? system : java.util.Optional.empty();
    }

    @Override
    public List<String> command(List<String> workspaceCommand, Path workspace, boolean offline)
            throws IOException {
        validateCommand(workspaceCommand, workspace);
        String executable = workspaceCommand.getFirst();
        if (executable.contains("/") || executable.contains("\\")) {
            return List.copyOf(workspaceCommand);
        }
        Path resolved = ExecutableLocator.find(executable, System.getenv())
                .orElseThrow(() -> new IOException(
                        "Missing local verification tool " + executable));
        var command = new ArrayList<String>(workspaceCommand);
        command.set(0, resolved.toString());
        return List.copyOf(command);
    }

    @Override
    public Map<String, String> environment(BackendContext context, boolean offline) {
        var result = new LinkedHashMap<String, String>();
        result.put("PATH", context.values().get("PATH"));
        result.put("JULC_VERIFY_OFFLINE", Boolean.toString(offline));
        result.put("GIT_TERMINAL_PROMPT", "0");
        if (offline) {
            result.put("HTTP_PROXY", "http://127.0.0.1:9");
            result.put("HTTPS_PROXY", "http://127.0.0.1:9");
            result.put("ALL_PROXY", "http://127.0.0.1:9");
            result.put("NO_PROXY", "");
            result.put("GIT_CONFIG_COUNT", "1");
            result.put("GIT_CONFIG_KEY_0", "url.file:///julc-offline.invalid/.insteadOf");
            result.put("GIT_CONFIG_VALUE_0", "https://");
        }
        return result;
    }

    @Override
    public String name() {
        return "local";
    }

    static void validateCommand(List<String> command, Path workspace) throws IOException {
        if (command == null || command.isEmpty() || command.getFirst().isBlank()) {
            throw new IOException("Verification command must not be empty");
        }
        String executable = command.getFirst();
        if (executable.contains("/") || executable.contains("\\")) {
            VerificationFiles.containedRegularFile(workspace, executable, true);
            return;
        }
        if (!List.of("lake", "lean", "git", "java", "jq", "rg", "xxd").contains(executable)) {
            throw new IOException("Command is not allowed in a verification plan: " + executable);
        }
    }
}
