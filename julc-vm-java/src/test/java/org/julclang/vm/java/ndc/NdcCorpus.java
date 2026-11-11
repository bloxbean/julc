package org.julclang.vm.java.ndc;

import com.github.luben.zstd.ZstdInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * A pinned input of the node-diversity scenarios: where it can be downloaded from, its SHA-256, the most bytes a
 * download may have, and how it is unpacked.
 *
 * <ul>
 *   <li>The corpora are downloaded at run time, not kept in the repository. The URLs are tried in order; a download
 *       that fails, is larger than the limit, or whose digest differs from the pin is refused, and the next URL is
 *       tried. The first is the upstream location; the others are mirrors that hold the same bytes.</li>
 *   <li>The cache keeps only the downloaded file, named by its digest, and its digest is checked again on every use.
 *       Every run unpacks it afresh into a new directory, so nothing extracted is ever reused.</li>
 *   <li>Archives are untrusted data: only regular files and directories are extracted, an entry whose path would
 *       leave the target directory is refused, and the extracted size is limited.</li>
 * </ul>
 *
 * <p>Changing a pin is a deliberate corpus upgrade: run both scenarios and review the differences.</p>
 *
 * @param name     a short name, used in messages
 * @param urls     where it is downloaded from, in the order tried
 * @param sha256   the SHA-256 of the downloaded bytes, lower-case hex
 * @param maxBytes the most bytes a download may have
 * @param kind     how the download is unpacked
 */
public record NdcCorpus(String name, List<URI> urls, String sha256, int maxBytes, Kind kind) {

    /** How a download is unpacked. */
    public enum Kind {
        /** a tar archive compressed with zstd, extracted into a directory */
        TAR_ZSTD,
        /** one file compressed with zstd, decompressed */
        ZSTD,
        /** one file, kept as downloaded */
        PLAIN
    }

    public NdcCorpus {
        urls = List.copyOf(urls);
        if (urls.isEmpty()) {
            throw new IllegalArgumentException(name + ": no URL");
        }
    }

    /** The node-diversity repository revision whose scenario pages, expectations and reference reports are used. */
    public static final String NDC_REVISION = "e02a9e1c7dc15cc6d618c7ffdbd41167a38de2a4";

    private static final String NDC_RAW = "https://raw.githubusercontent.com/pragma-org/node-diversity-conformance/"
            + NDC_REVISION + "/";

    private static final String NDC_R2 = "https://pub-2239d82d9a074482b2eb2c886191cb4e.r2.dev/";

    /**
     * JuLC's copy of the pinned tarballs, as assets of the GitHub release {@code ndc-corpora-v1}: the fallback when the
     * upstream location is unreachable or serves other bytes.
     */
    static final String MIRROR = "https://github.com/bloxbean/julc/releases/download/ndc-corpora-v1/";

    private static final int MIB = 1024 * 1024;

    /** The most bytes an archive may extract to. */
    static final long MAX_EXTRACTED_BYTES = 1024L * MIB;

    /** The uplc-conformance vectors and the cost models they are run with (the scenario page's tarball). */
    public static final NdcCorpus UPLC_CONFORMANCE = new NdcCorpus("uplc-conformance",
            List.of(URI.create(NDC_R2 + "uplc-conformance.tar.zst"), URI.create(MIRROR + "uplc-conformance.tar.zst")),
            "8bd4ad4f9f4c8c6b8ffa1f06ed1db0b43043afbba77ef6a14bcfb5a52345ed3f", 64 * MIB, Kind.TAR_ZSTD);

    /** What the site scores a uplc-conformance report against. */
    public static final NdcCorpus UPLC_EXPECTATIONS = new NdcCorpus("uplc-conformance expectations",
            List.of(URI.create(NDC_RAW + "scenarios/uplc-conformance/expectations.json")),
            "1c93d210b4bf155b1d53fecbb98c380a35b647ea3738efffa674e3b348001357", 16 * MIB, Kind.PLAIN);

    /** The uplc-turbo mainnet scripts (the scenario page's tarball). */
    public static final NdcCorpus TURBO = new NdcCorpus("uplc-turbo",
            List.of(URI.create(NDC_R2 + "turbo.tar.zst"), URI.create(MIRROR + "turbo.tar.zst")),
            "821af55e3323c826b5598c964d634b46dd468014834d0e97ebdc5edb0a158576", 64 * MIB, Kind.TAR_ZSTD);

    /** cardano-node's uplc-turbo report, as the site carries it: the reference budgets. */
    public static final NdcCorpus TURBO_CARDANO_NODE = new NdcCorpus("uplc-turbo cardano-node report",
            List.of(URI.create(NDC_RAW + "scenarios/uplc-turbo/preview/cardano-node.json.zst")),
            "2244d4d9eae376dcb4f085be0e14a7f931fb1c8adf05303a7ceeb190030b19f1", 16 * MIB, Kind.ZSTD);

    /** Amaru's uplc-turbo report, as the site carries it: shown beside JuLC's outliers. */
    public static final NdcCorpus TURBO_AMARU = new NdcCorpus("uplc-turbo Amaru report",
            List.of(URI.create(NDC_RAW + "scenarios/uplc-turbo/preview/amaru.json.zst")),
            "8ff205d7aaf459c0582c35b6defcc0775522051e7ec6547b051084180bcfbfcc", 16 * MIB, Kind.ZSTD);

    /** Every pin, for the cache key and the tests. */
    static final List<NdcCorpus> ALL = List.of(UPLC_CONFORMANCE, UPLC_EXPECTATIONS, TURBO, TURBO_CARDANO_NODE,
            TURBO_AMARU);

    private static final int ATTEMPTS_PER_URL = 3;

    /** How long one download attempt may take, body included. */
    static final Duration DOWNLOAD_DEADLINE = Duration.ofMinutes(5);

    /** The pause before the second attempt at a URL; the third waits twice as long. */
    static final Duration RETRY_PAUSE = Duration.ofSeconds(2);

    /**
     * Returns the pinned bytes, unpacked into a new directory under {@code cacheRoot/work}: from the cache if the
     * cached file still has the pinned digest, otherwise downloaded (and then cached).
     *
     * @return the extracted directory ({@link Kind#TAR_ZSTD}) or the file
     */
    public Path fetch(Path cacheRoot) {
        byte[] bytes = cached(cacheRoot);
        if (bytes == null) {
            bytes = download();
            store(cacheRoot, bytes);
        }
        try {
            Files.createDirectories(cacheRoot.resolve("work"));
            Path work = Files.createTempDirectory(cacheRoot.resolve("work"), sha256.substring(0, 12) + "-");
            deleteOnExit(work);
            return unpack(bytes, work);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot unpack " + name + " under " + cacheRoot, e);
        }
    }

    /** @return the cached bytes, if the cached file has the pinned digest; a cached file that does not is deleted */
    byte[] cached(Path cacheRoot) {
        Path file = cacheFile(cacheRoot);
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > maxBytes) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(file);
            if (sha256(bytes).equals(sha256)) {
                return bytes;
            }
            System.out.println("The cached " + name + " (" + file + ") does not have the pinned SHA-256; it is "
                    + "downloaded again.");
            Files.deleteIfExists(file);
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the cached " + name + " at " + file, e);
        }
    }

    /** @return the cached download: {@code <sha256>.download} */
    Path cacheFile(Path cacheRoot) {
        return cacheRoot.resolve(sha256 + ".download");
    }

    /** Writes verified bytes to the cache through a temporary file, so a concurrent run never sees a partial file. */
    private void store(Path cacheRoot, byte[] bytes) {
        try {
            Files.createDirectories(cacheRoot);
            Path temporary = Files.createTempFile(cacheRoot, sha256.substring(0, 12) + "-", ".part");
            try {
                Files.write(temporary, bytes);
                try {
                    Files.move(temporary, cacheFile(cacheRoot), StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, cacheFile(cacheRoot), StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot cache " + name + " under " + cacheRoot, e);
        }
    }

    /** @return the first download, in URL order, that is within the limit and has the pinned digest */
    byte[] download() {
        return download(DOWNLOAD_DEADLINE, RETRY_PAUSE);
    }

    /**
     * @param deadline   how long one attempt may take, from the request to the last byte of the body; an attempt that
     *                   takes longer is cancelled and the next URL is tried
     * @param retryPause the pause before the second attempt at a URL; it grows linearly with the attempt
     */
    byte[] download(Duration deadline, Duration retryPause) {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20)).build();
        List<String> failures = new ArrayList<>();
        for (URI url : urls) {
            System.out.println("Downloading " + name + " from " + url);
            try {
                byte[] bytes = downloadWithRetries(client, url, deadline, retryPause);
                String actual = sha256(bytes);
                if (actual.equals(sha256)) {
                    return bytes;
                }
                failures.add(url + ": SHA-256 " + actual + ", but the pin is " + sha256
                        + " (the file changed there, or the download was corrupted)");
            } catch (IOException e) {
                failures.add(url + ": " + e.getMessage());
            }
            System.out.println("  refused: " + failures.getLast());
        }
        throw new IllegalStateException("No location has " + name + " with the pinned SHA-256 " + sha256
                + ". Nothing was unpacked.\n  " + String.join("\n  ", failures));
    }

    /** Retries a URL on network errors and server errors (5xx); a client error (4xx) is final. */
    private byte[] downloadWithRetries(HttpClient client, URI url, Duration deadline, Duration retryPause)
            throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS_PER_URL; attempt++) {
            try {
                return downloadOnce(client, url, deadline);
            } catch (NotRetriedException e) {
                throw e;
            } catch (IOException e) {
                last = e;
                if (attempt < ATTEMPTS_PER_URL) {
                    pause(retryPause.multipliedBy(attempt));
                }
            }
        }
        throw last;
    }

    /**
     * One download. The body of a response other than 200 is not read: its subscription is cancelled as soon as the
     * status is known. A body over the limit is stopped, and the whole exchange, body included, must finish within
     * {@code deadline}, or it is cancelled.
     */
    private byte[] downloadOnce(HttpClient client, URI url, Duration deadline) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(url).timeout(deadline).GET().build();
        HttpResponse.BodyHandler<byte[]> handler = info -> info.statusCode() == 200
                ? HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), maxBytes).apply(info)
                : new UnreadBody();
        CompletableFuture<HttpResponse<byte[]>> exchange = client.sendAsync(request, handler);
        HttpResponse<byte[]> response;
        try {
            response = exchange.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            exchange.cancel(true);
            throw new NotRetriedException("no complete response within " + deadline.toSeconds() + " s");
        } catch (InterruptedException e) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io && String.valueOf(io.getMessage()).contains("exceeds")) {
                throw new NotRetriedException("more than the limit of " + maxBytes + " bytes");
            }
            throw cause instanceof IOException io ? io : new IOException(cause);
        }
        int status = response.statusCode();
        if (status >= 400 && status < 500) {
            throw new NotRetriedException("HTTP " + status);
        }
        if (status != 200) {
            throw new IOException("HTTP " + status);
        }
        return response.body();
    }

    /** A client error (4xx), a body over the limit or a stalled exchange: not retried at the same URL. */
    static final class NotRetriedException extends IOException {
        NotRetriedException(String message) {
            super(message);
        }
    }

    /**
     * The body of a response that is refused on its status: cancelled at once, so a body that stalls or never ends
     * holds nothing up. ({@code BodySubscribers.replacing} would read it to the end.)
     */
    static final class UnreadBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.cancel();
            body.complete(null);
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
            // cancelled: nothing is read
        }

        @Override
        public void onError(Throwable throwable) {
            body.complete(null);
        }

        @Override
        public void onComplete() {
            body.complete(null);
        }
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Unpacks verified bytes into {@code dir}, which must be empty.
     *
     * @return the extracted directory or the file
     */
    Path unpack(byte[] bytes, Path dir) throws IOException {
        if (!sha256(bytes).equals(sha256)) {
            throw new IllegalStateException(name + ": bytes without the pinned SHA-256 are never unpacked");
        }
        Path content = dir.resolve("content");
        switch (kind) {
            case TAR_ZSTD -> {
                try (InputStream in = new ZstdInputStream(new ByteArrayInputStream(bytes))) {
                    extractTar(in, content);
                }
            }
            case ZSTD -> {
                try (InputStream in = new ZstdInputStream(new ByteArrayInputStream(bytes));
                     OutputStream out = Files.newOutputStream(content)) {
                    copyLimited(in, out, MAX_EXTRACTED_BYTES, name);
                }
            }
            case PLAIN -> Files.write(content, bytes);
        }
        return content;
    }

    /** Extracts regular files and directories only; refuses links, paths outside {@code target} and oversize. */
    static void extractTar(InputStream tar, Path target) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        Files.createDirectories(root);
        long remaining = MAX_EXTRACTED_BYTES;
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
                    try (OutputStream out = Files.newOutputStream(path)) {
                        remaining -= copyLimited(entries, out, remaining, entry.getName());
                    }
                } else {
                    throw new IOException("archive entry is not a file or a directory: " + entry.getName());
                }
            }
        }
    }

    /** @return the bytes copied; fails once more than {@code limit} would be */
    private static long copyLimited(InputStream in, OutputStream out, long limit, String what) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long copied = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            copied += read;
            if (copied > limit) {
                throw new IOException(what + ": the archive extracts to more than " + MAX_EXTRACTED_BYTES + " bytes");
            }
            out.write(buffer, 0, read);
        }
        return copied;
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

    /** Deletes an extracted directory when the run ends (best effort). */
    private static void deleteOnExit(Path dir) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try (Stream<Path> paths = Files.walk(dir)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            } catch (IOException e) {
                // a leftover directory under the cache's work folder is harmless
            }
        }));
    }
}
