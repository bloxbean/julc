package com.bloxbean.julc.cli.cmd.verify;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DockerVerificationBackend implements VerificationExecutionBackend {

    static final String DOCKERFILE_RESOURCE = "META-INF/julc/verification/Dockerfile";
    static final String IMAGE_TAG = "julc-verification:lean-4.24.0-z3-4.15.2-v2";
    private String imageId;

    DockerVerificationBackend() {
    }

    DockerVerificationBackend(String imageId) {
        this.imageId = imageId;
    }

    @Override
    public BackendContext prepare(
            Path workspace,
            VerificationProcess process,
            Duration timeout,
            Path logFile) throws IOException, InterruptedException {
        Path docker = ExecutableLocator.find("docker", System.getenv())
                .orElseThrow(() -> new IOException("Docker executable is not available"));
        var version = process.execute(List.of(docker.toString(), "version", "--format",
                        "{{.Server.Version}}"), workspace, Map.of(), timeout, logFile);
        if (version.timedOut() || version.exitCode() != 0 || version.outputTail().isBlank()) {
            throw new IOException("Docker daemon is not available");
        }

        Path context = Files.createTempDirectory("julc-verification-image-");
        try {
            Path dockerfile = context.resolve("Dockerfile");
            try (InputStream resource = DockerVerificationBackend.class.getClassLoader()
                    .getResourceAsStream(DOCKERFILE_RESOURCE)) {
                if (resource == null) throw new IOException("Embedded verification Dockerfile is missing");
                Files.copy(resource, dockerfile);
            }
            Path iid = context.resolve("image.id");
            var build = process.execute(List.of(
                            docker.toString(), "build",
                            "--tag", IMAGE_TAG,
                            "--iidfile", iid.toString(),
                            "--file", dockerfile.toString(),
                            context.toString()),
                    workspace, Map.of("DOCKER_BUILDKIT", "1"),
                    Duration.ofMinutes(Math.max(30, timeout.toMinutes())), logFile);
            if (build.timedOut() || build.exitCode() != 0 || !Files.isRegularFile(iid)) {
                throw new IOException("Unable to build the pinned JuLC verification image");
            }
            imageId = Files.readString(iid).trim();
            if (!imageId.matches("sha256:[0-9a-f]{64}")) {
                throw new IOException("Docker returned an invalid verification image ID");
            }
        } finally {
            VerificationFiles.deleteTree(context);
        }
        Path home = workspace.resolve(".julc/docker-home");
        Files.createDirectories(home);
        return new BackendContext(imageId, Map.of("docker", docker.toString()));
    }

    @Override
    public List<String> command(List<String> workspaceCommand, Path workspace, boolean offline)
            throws IOException {
        LocalVerificationBackend.validateCommand(workspaceCommand, workspace);
        if (imageId == null) throw new IOException("Docker backend is not prepared");
        var result = new ArrayList<String>();
        result.add("docker");
        result.add("run");
        result.add("--rm");
        if (offline) {
            result.add("--network");
            result.add("none");
        }
        unixOwnership(workspace).ifPresent(ownership -> {
            result.add("--user");
            result.add(ownership);
        });
        result.add("--env");
        result.add("HOME=/workspace/.julc/docker-home");
        result.add("--env");
        result.add("JULC_VERIFY_OFFLINE=" + offline);
        result.add("--volume");
        result.add(workspace.toRealPath() + ":/workspace");
        result.add("--workdir");
        result.add("/workspace");
        result.add(imageId);
        result.addAll(workspaceCommand);
        return List.copyOf(result);
    }

    @Override
    public Map<String, String> environment(BackendContext context, boolean offline) {
        return Map.of();
    }

    @Override
    public String name() {
        return "docker";
    }

    private static java.util.Optional<String> unixOwnership(Path workspace) {
        try {
            Object uid = Files.getAttribute(workspace, "unix:uid");
            Object gid = Files.getAttribute(workspace, "unix:gid");
            return java.util.Optional.of(uid + ":" + gid);
        } catch (IOException | UnsupportedOperationException e) {
            return java.util.Optional.empty();
        }
    }
}
