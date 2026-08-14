package com.bloxbean.julc.cli.cmd.verify;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipInputStream;

final class Z3Provisioner {

    static final String VERSION = "4.15.2";
    private static final String RELEASE =
            "https://github.com/Z3Prover/z3/releases/download/z3-4.15.2/";
    private final HttpClient client;

    Z3Provisioner() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    Z3Provisioner(HttpClient client) {
        this.client = client;
    }

    Path provision(Path workspace) throws IOException, InterruptedException {
        Path install = workspace.resolve(".julc/tools/z3-" + VERSION);
        Path binary = install.resolve("bin").resolve(isWindows() ? "z3.exe" : "z3");
        if (Files.isRegularFile(binary) && Files.isExecutable(binary)) return binary;

        Platform platform = currentPlatform();
        Path tools = install.getParent();
        Files.createDirectories(tools);
        Path staging = Files.createTempDirectory(tools, ".z3-");
        try {
            Path archive = staging.resolve(platform.archive());
            var request = HttpRequest.newBuilder(URI.create(RELEASE + platform.archive()))
                    .timeout(Duration.ofMinutes(5))
                    .GET()
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofFile(archive));
            if (response.statusCode() != 200) {
                throw new IOException("Unable to download pinned Z3: HTTP " + response.statusCode());
            }
            String actual = VerificationFiles.sha256(archive);
            if (!actual.equals(platform.sha256())) {
                throw new IOException("Pinned Z3 archive checksum mismatch");
            }
            Path unpacked = staging.resolve("unpacked");
            extractZip(Files.newInputStream(archive), unpacked);
            Path extracted = unpacked.resolve(platform.archive().substring(0,
                    platform.archive().length() - ".zip".length()));
            Path extractedBinary = extracted.resolve("bin")
                    .resolve(isWindows() ? "z3.exe" : "z3");
            if (!Files.isRegularFile(extractedBinary)) {
                throw new IOException("Pinned Z3 archive does not contain bin/z3");
            }
            makeExecutable(extractedBinary);
            if (Files.exists(install)) VerificationFiles.deleteTree(install);
            try {
                Files.move(extracted, install, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(extracted, install);
            }
            makeExecutable(binary);
            return binary;
        } finally {
            VerificationFiles.deleteTree(staging);
        }
    }

    static Platform currentPlatform() throws IOException {
        return platform(System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    static Platform platform(String osName, String archName) throws IOException {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = archName.toLowerCase(Locale.ROOT);
        if (os.contains("mac") && (arch.equals("aarch64") || arch.equals("arm64"))) {
            return new Platform("z3-4.15.2-arm64-osx-13.7.6.zip",
                    "fdc797b046a8b1e030200d30c4c32724fc01be359c3ab88a47ce03655cf6efa4");
        }
        if (os.contains("mac") && (arch.equals("x86_64") || arch.equals("amd64"))) {
            return new Platform("z3-4.15.2-x64-osx-13.7.6.zip",
                    "2c0fb34703660cb3c182c84d702674f52b56f9454cdc6c30d58611a1c2d69851");
        }
        if (os.contains("linux") && (arch.equals("x86_64") || arch.equals("amd64"))) {
            return new Platform("z3-4.15.2-x64-glibc-2.39.zip",
                    "85d2da1bf440fca3288874c2a06e23f96d09befcc21b5a7489fe0fa40444e685");
        }
        if (os.contains("linux") && (arch.equals("aarch64") || arch.equals("arm64"))) {
            return new Platform("z3-4.15.2-arm64-glibc-2.34.zip",
                    "13ef5c1f91cae46c3de493cd1f98954331e4e7d0850bbbcf208b818d452bf99b");
        }
        throw new IOException("Unsupported Z3 bootstrap platform " + osName + "/" + archName);
    }

    static void extractZip(InputStream input, Path target) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        Files.createDirectories(root);
        try (var zip = new ZipInputStream(input)) {
            var entry = zip.getNextEntry();
            while (entry != null) {
                Path output = root.resolve(entry.getName()).normalize();
                if (!output.startsWith(root)) {
                    throw new IOException("Z3 zip entry escapes target directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Files.createDirectories(output.getParent());
                    Files.copy(zip, output, StandardCopyOption.REPLACE_EXISTING);
                }
                zip.closeEntry();
                entry = zip.getNextEntry();
            }
        }
    }

    private static void makeExecutable(Path binary) throws IOException {
        if (isWindows()) return;
        try {
            Files.setPosixFilePermissions(binary, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            if (!binary.toFile().setExecutable(true, false)) {
                throw new IOException("Cannot make Z3 executable: " + binary);
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    record Platform(String archive, String sha256) {
    }
}
