package org.julclang.vm.java.ndc;

import com.github.luben.zstd.ZstdInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.stream.Stream;

/**
 * A pinned input of the node-diversity scenarios: where it is downloaded from, its SHA-256 and how it is unpacked.
 *
 * <p>The corpora are downloaded at run time, not kept in the repository. A download whose digest differs from the pin
 * is refused before anything is unpacked. Archives are untrusted data: only regular files and directories are
 * extracted, and an entry whose path would leave the target directory is refused.</p>
 *
 * <p>Changing a pin is a deliberate corpus upgrade: run both scenarios and review the differences.</p>
 *
 * @param name   a short name, used in messages
 * @param url    where it is downloaded from
 * @param sha256 the SHA-256 of the downloaded bytes, lower-case hex
 * @param kind   how the download is unpacked
 */
public record NdcCorpus(String name, URI url, String sha256, Kind kind) {

    /** How a download is unpacked. */
    public enum Kind {
        /** a tar archive compressed with zstd, extracted into a directory */
        TAR_ZSTD,
        /** one file compressed with zstd, decompressed */
        ZSTD,
        /** one file, kept as downloaded */
        PLAIN
    }

    /** The node-diversity repository revision whose scenario pages, expectations and reference reports are used. */
    public static final String NDC_REVISION = "e02a9e1c7dc15cc6d618c7ffdbd41167a38de2a4";

    private static final String NDC_RAW = "https://raw.githubusercontent.com/pragma-org/node-diversity-conformance/"
            + NDC_REVISION + "/";

    /** The uplc-conformance vectors and the cost models they are run with (the scenario page's tarball). */
    public static final NdcCorpus UPLC_CONFORMANCE = new NdcCorpus("uplc-conformance",
            URI.create("https://pub-2239d82d9a074482b2eb2c886191cb4e.r2.dev/uplc-conformance.tar.zst"),
            "8bd4ad4f9f4c8c6b8ffa1f06ed1db0b43043afbba77ef6a14bcfb5a52345ed3f", Kind.TAR_ZSTD);

    /** What the site scores a uplc-conformance report against. */
    public static final NdcCorpus UPLC_EXPECTATIONS = new NdcCorpus("uplc-conformance expectations",
            URI.create(NDC_RAW + "scenarios/uplc-conformance/expectations.json"),
            "1c93d210b4bf155b1d53fecbb98c380a35b647ea3738efffa674e3b348001357", Kind.PLAIN);

    /** The uplc-turbo mainnet scripts (the scenario page's tarball). */
    public static final NdcCorpus TURBO = new NdcCorpus("uplc-turbo",
            URI.create("https://pub-2239d82d9a074482b2eb2c886191cb4e.r2.dev/turbo.tar.zst"),
            "821af55e3323c826b5598c964d634b46dd468014834d0e97ebdc5edb0a158576", Kind.TAR_ZSTD);

    /** cardano-node's uplc-turbo report, as the site carries it: the reference budgets. */
    public static final NdcCorpus TURBO_CARDANO_NODE = new NdcCorpus("uplc-turbo cardano-node report",
            URI.create(NDC_RAW + "scenarios/uplc-turbo/preview/cardano-node.json.zst"),
            "2244d4d9eae376dcb4f085be0e14a7f931fb1c8adf05303a7ceeb190030b19f1", Kind.ZSTD);

    /** Amaru's uplc-turbo report, as the site carries it: shown beside JuLC's outliers. */
    public static final NdcCorpus TURBO_AMARU = new NdcCorpus("uplc-turbo Amaru report",
            URI.create(NDC_RAW + "scenarios/uplc-turbo/preview/amaru.json.zst"),
            "8ff205d7aaf459c0582c35b6defcc0775522051e7ec6547b051084180bcfbfcc", Kind.ZSTD);

    private static final int DOWNLOAD_ATTEMPTS = 5;
    private static final String COMPLETE = ".complete";
    private static final String CONTENT = "content";

    /**
     * @param cacheRoot the download cache; each pin is kept in a directory named by its SHA-256
     * @return the extracted directory ({@link Kind#TAR_ZSTD}) or the file; downloaded on first use
     */
    public Path fetch(Path cacheRoot) {
        Path target = cacheRoot.resolve(sha256);
        if (Files.isRegularFile(target.resolve(COMPLETE))) {
            return target.resolve(CONTENT);
        }
        System.out.println("Downloading " + name + " from " + url);
        return install(download(), cacheRoot);
    }

    /**
     * Checks downloaded bytes against the pin and unpacks them into the cache.
     *
     * @return the extracted directory or the file
     */
    Path install(byte[] downloaded, Path cacheRoot) {
        String actual = sha256(downloaded);
        if (!actual.equals(sha256)) {
            throw new IllegalStateException(name + " from " + url + " has SHA-256 " + actual + ", but the pin is "
                    + sha256 + ". The file changed upstream, or the download was corrupted. Nothing was unpacked.");
        }
        Path target = cacheRoot.resolve(sha256);
        try {
            deleteRecursively(target);
            Files.createDirectories(target);
            Path content = target.resolve(CONTENT);
            switch (kind) {
                case TAR_ZSTD -> {
                    try (InputStream in = new ZstdInputStream(new ByteArrayInputStream(downloaded))) {
                        extractTar(in, content);
                    }
                }
                case ZSTD -> {
                    try (InputStream in = new ZstdInputStream(new ByteArrayInputStream(downloaded))) {
                        Files.copy(in, content);
                    }
                }
                case PLAIN -> Files.write(content, downloaded);
            }
            Files.writeString(target.resolve(COMPLETE), sha256 + "\n");
            return content;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot unpack " + name + " into " + target, e);
        }
    }

    private byte[] download() {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20)).build();
        HttpRequest request = HttpRequest.newBuilder(url).timeout(Duration.ofMinutes(5)).GET().build();
        IOException last = null;
        for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
            try {
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() == 200) {
                    return response.body();
                }
                last = new IOException("HTTP " + response.statusCode());
            } catch (IOException e) {
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while downloading " + name, e);
            }
            System.out.println("Download of " + name + " failed (attempt " + attempt + " of " + DOWNLOAD_ATTEMPTS
                    + "): " + last);
            pause(attempt);
        }
        throw new UncheckedIOException("cannot download " + name + " from " + url, last);
    }

    private static void pause(int attempt) {
        try {
            Thread.sleep(2000L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Extracts regular files and directories only; refuses links and paths outside {@code target}. */
    static void extractTar(InputStream tar, Path target) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        Files.createDirectories(root);
        try (TarArchiveInputStream entries = new TarArchiveInputStream(tar)) {
            TarArchiveEntry entry;
            while ((entry = entries.getNextEntry()) != null) {
                Path path = root.resolve(entry.getName()).normalize();
                if (!path.startsWith(root)) {
                    throw new IOException("archive entry outside the target directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(path);
                } else if (isRegularFile(entry)) {
                    Files.createDirectories(path.getParent());
                    Files.copy(entries, path, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    throw new IOException("archive entry is not a file or a directory: " + entry.getName());
                }
            }
        }
    }

    /** {@link TarArchiveEntry#isFile()} also holds for links and devices. */
    private static boolean isRegularFile(TarArchiveEntry entry) {
        return entry.isFile() && !entry.isSymbolicLink() && !entry.isLink() && !entry.isCharacterDevice()
                && !entry.isBlockDevice() && !entry.isFIFO();
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
