package com.bloxbean.julc.cli.scaffold;

import com.bloxbean.julc.cli.JulccVersionProvider;
import com.bloxbean.julc.cli.project.LockToml;
import com.bloxbean.julc.cli.project.ProjectLayout;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipInputStream;

/**
 * Extracts embedded stdlib sources to .julc/stdlib/ for IDE support.
 */
public final class StdlibInstaller {

    private static final String EMBEDDED_ZIP = "META-INF/julc/stdlib-sources.zip";

    private StdlibInstaller() {}

    /**
     * Install stdlib sources from the embedded ZIP resource.
     * Returns the SHA-256 hash of the extracted content.
     */
    public static String installFromEmbedded(Path projectRoot) throws IOException {
        Path stdlibDir = ProjectLayout.stdlibDir(projectRoot);
        try (InputStream is = StdlibInstaller.class.getClassLoader().getResourceAsStream(EMBEDDED_ZIP)) {
            if (is == null) {
                throw new IOException("Embedded stdlib sources not found: " + EMBEDDED_ZIP);
            }
            return extractZip(is, stdlibDir);
        }
    }

    /**
     * Install stdlib sources from a remote URL (for different compiler versions).
     */
    public static String installFromUrl(String url, Path projectRoot) throws IOException, InterruptedException {
        Path stdlibDir = ProjectLayout.stdlibDir(projectRoot);
        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            throw new IOException("Failed to download stdlib: HTTP " + response.statusCode());
        }

        try (InputStream is = response.body()) {
            return extractZip(is, stdlibDir);
        }
    }

    /**
     * Write lock.toml with the installed version and hash.
     */
    public static void writeLock(Path projectRoot, String sha256) throws IOException {
        var lock = new LockToml(JulccVersionProvider.VERSION, sha256);
        Path lockFile = ProjectLayout.lockFile(projectRoot);
        Files.createDirectories(lockFile.getParent());
        Files.writeString(lockFile, lock.toToml());
    }

    private static String extractZip(InputStream zipStream, Path targetDir) throws IOException {
        targetDir = targetDir.toAbsolutePath().normalize();
        // Clean existing stdlib
        if (Files.isDirectory(targetDir)) {
            try (var walk = Files.walk(targetDir)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
            }
        }
        Files.createDirectories(targetDir);

        var digest = createSha256();
        try (var zis = new ZipInputStream(zipStream)) {
            var entry = zis.getNextEntry();
            while (entry != null) {
                Path dest = targetDir.resolve(entry.getName()).normalize();
                // Zip slip protection
                if (!dest.startsWith(targetDir)) {
                    throw new IOException("Zip entry outside target directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    byte[] data = zis.readAllBytes();
                    Files.write(dest, data);
                    digest.update(data);
                }
                zis.closeEntry();
                entry = zis.getNextEntry();
            }
        }

        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest createSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
