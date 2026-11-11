package org.julclang.vm.java.ndc;

import com.github.luben.zstd.ZstdOutputStream;
import com.sun.net.httpserver.HttpServer;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NdcCorpusTest {

    /** Never contacted: an address that cannot resolve. */
    private static final URI NOWHERE = URI.create("https://example.invalid/corpus");

    /** A local server: path to the bytes it serves (a missing path is a 404). */
    private final Map<String, byte[]> served = new HashMap<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final Map<String, AtomicInteger> requestsByPath = new ConcurrentHashMap<>();
    private final ExecutorService handlers = Executors.newCachedThreadPool();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            requestsByPath.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
            byte[] body = served.get(path);
            if (path.startsWith("/stall-")) {
                // a status and its headers, then nothing: the body never comes
                exchange.sendResponseHeaders(Integer.parseInt(path.substring("/stall-".length())), 1000);
                exchange.getResponseBody().flush();
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else if (path.startsWith("/large-")) {
                // a status with an endless body
                exchange.sendResponseHeaders(Integer.parseInt(path.substring("/large-".length())), 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    byte[] chunk = new byte[64 * 1024];
                    for (int i = 0; i < 100_000; i++) {
                        out.write(chunk);
                    }
                } catch (IOException e) {
                    // the client stopped reading
                }
            } else if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
            exchange.close();
        });
        server.setExecutor(handlers);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        handlers.shutdownNow();
    }

    private URI local(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private static NdcCorpus pin(byte[] bytes, NdcCorpus.Kind kind, URI... urls) {
        return new NdcCorpus("test", List.of(urls), NdcCorpus.sha256(bytes), 1024 * 1024, kind);
    }

    @Test
    void refusesToUnpackBytesThatDoNotMatchThePin(@TempDir Path dir) {
        NdcCorpus pin = new NdcCorpus("test", List.of(NOWHERE), "00".repeat(32), 1024, NdcCorpus.Kind.PLAIN);

        assertThrows(IllegalStateException.class,
                () -> pin.unpack("tampered".getBytes(StandardCharsets.UTF_8), dir));
        assertFalse(Files.exists(dir.resolve("content")));
    }

    @Test
    void triesTheUrlsInOrderAndCachesTheFirstPinnedDownload(@TempDir Path cache) throws IOException {
        byte[] good = "pinned".getBytes(StandardCharsets.UTF_8);
        served.put("/changed", "changed upstream".getBytes(StandardCharsets.UTF_8));
        served.put("/mirror", good);
        NdcCorpus pin = pin(good, NdcCorpus.Kind.PLAIN, local("/missing"), local("/changed"), local("/mirror"));

        Path file = pin.fetch(cache);

        assertEquals("pinned", Files.readString(file));
        assertArrayEquals(good, Files.readAllBytes(pin.cacheFile(cache)));
        assertEquals(3, requests.get());
    }

    @Test
    void failsWhenNoUrlHasThePinnedBytes(@TempDir Path cache) {
        served.put("/changed", "changed upstream".getBytes(StandardCharsets.UTF_8));
        NdcCorpus pin = pin("pinned".getBytes(StandardCharsets.UTF_8), NdcCorpus.Kind.PLAIN, local("/missing"),
                local("/changed"));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> pin.fetch(cache));
        assertTrue(e.getMessage().contains("HTTP 404"), e.getMessage());
        assertTrue(e.getMessage().contains("SHA-256"), e.getMessage());
        assertFalse(Files.exists(pin.cacheFile(cache)));
    }

    @Test
    void stopsADownloadOverTheLimit(@TempDir Path cache) {
        byte[] large = new byte[4096];
        served.put("/large", large);
        NdcCorpus pin = new NdcCorpus("test", List.of(local("/large")), NdcCorpus.sha256(large), 1024,
                NdcCorpus.Kind.PLAIN);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> pin.fetch(cache));
        assertTrue(e.getMessage().contains("limit"), e.getMessage());
        assertEquals(1, requests.get());
    }

    @Test
    void movesOnFromAResponseWhoseBodyStalls() {
        byte[] good = "pinned".getBytes(StandardCharsets.UTF_8);
        served.put("/mirror", good);
        NdcCorpus pin = pin(good, NdcCorpus.Kind.PLAIN, local("/stall-200"), local("/mirror"));

        long started = System.nanoTime();
        assertArrayEquals(good, pin.download(Duration.ofSeconds(1), Duration.ZERO));
        long seconds = (System.nanoTime() - started) / 1_000_000_000;

        // the stalled exchange is cancelled at the deadline and not retried
        assertTrue(seconds < 10, () -> "took " + seconds + " s");
        assertEquals(2, requests.get());
    }

    @Test
    void refusesAnErrorWhoseBodyStallsAtOnce() {
        byte[] good = "pinned".getBytes(StandardCharsets.UTF_8);
        served.put("/mirror", good);
        NdcCorpus pin = pin(good, NdcCorpus.Kind.PLAIN, local("/stall-404"), local("/mirror"));

        // a deadline far longer than the test may take: the error body must not be waited for
        long started = System.nanoTime();
        assertArrayEquals(good, pin.download(Duration.ofSeconds(60), Duration.ZERO));
        long seconds = (System.nanoTime() - started) / 1_000_000_000;

        assertTrue(seconds < 10, () -> "took " + seconds + " s");
        assertEquals(1, requestsByPath.get("/stall-404").get());
    }

    @Test
    void doesNotReadAnEndlessErrorBody() {
        byte[] good = "pinned".getBytes(StandardCharsets.UTF_8);
        served.put("/mirror", good);
        // the limit is 1 KiB; the error body is endless
        NdcCorpus pin = new NdcCorpus("test", List.of(local("/large-404"), local("/mirror")),
                NdcCorpus.sha256(good), 1024, NdcCorpus.Kind.PLAIN);

        long started = System.nanoTime();
        assertArrayEquals(good, pin.download(Duration.ofSeconds(60), Duration.ZERO));
        long seconds = (System.nanoTime() - started) / 1_000_000_000;

        assertTrue(seconds < 10, () -> "took " + seconds + " s");
        assertEquals(1, requestsByPath.get("/large-404").get());
    }

    @Test
    void retriesAServerErrorButNotAClientError() {
        byte[] good = "pinned".getBytes(StandardCharsets.UTF_8);
        served.put("/mirror", good);
        NdcCorpus pin = pin(good, NdcCorpus.Kind.PLAIN, local("/stall-503"), local("/stall-404"), local("/mirror"));

        long started = System.nanoTime();
        assertArrayEquals(good, pin.download(Duration.ofSeconds(60), Duration.ZERO));
        long seconds = (System.nanoTime() - started) / 1_000_000_000;

        // a 5xx is tried three times, a 4xx once; neither waits for its stalled body
        assertTrue(seconds < 10, () -> "took " + seconds + " s");
        assertEquals(3, requestsByPath.get("/stall-503").get());
        assertEquals(1, requestsByPath.get("/stall-404").get());
        assertEquals(1, requestsByPath.get("/mirror").get());
    }

    @Test
    void checksTheCachedFileOnEveryUse(@TempDir Path cache) throws IOException {
        byte[] good = "pinned".getBytes(StandardCharsets.UTF_8);
        served.put("/file", good);
        NdcCorpus pin = pin(good, NdcCorpus.Kind.PLAIN, local("/file"));
        Files.createDirectories(cache);
        Files.writeString(pin.cacheFile(cache), "edited in the cache");

        assertNull(pin.cached(cache));
        assertEquals("pinned", Files.readString(pin.fetch(cache)));
        assertEquals(1, requests.get());

        // a good cached file is used without downloading
        assertEquals("pinned", Files.readString(pin.fetch(cache)));
        assertEquals(1, requests.get());
    }

    @Test
    void unpacksAfreshOnEveryUse(@TempDir Path cache) throws IOException {
        byte[] archive = zstd(tar(false, "protocol-version-11/v3/term/a/a.flat", "0100"));
        served.put("/corpus", archive);
        NdcCorpus pin = pin(archive, NdcCorpus.Kind.TAR_ZSTD, local("/corpus"));

        Path first = pin.fetch(cache);
        Files.delete(first.resolve("protocol-version-11/v3/term/a/a.flat"));
        Path second = pin.fetch(cache);

        assertNotEquals(first, second);
        assertEquals("0100", Files.readString(second.resolve("protocol-version-11/v3/term/a/a.flat")));
        assertEquals(1, requests.get());
    }

    @Test
    void decompressesAZstdFile(@TempDir Path dir) throws IOException {
        byte[] compressed = zstd("{\"benchmarks\": []}".getBytes(StandardCharsets.UTF_8));

        Path file = pin(compressed, NdcCorpus.Kind.ZSTD, NOWHERE).unpack(compressed, dir);

        assertEquals("{\"benchmarks\": []}", Files.readString(file));
    }

    @Test
    void refusesAnEntryOutsideTheTarget(@TempDir Path dir) throws IOException {
        byte[] archive = zstd(tar(false, "../escape.txt", "x"));

        assertThrows(IOException.class, () -> pin(archive, NdcCorpus.Kind.TAR_ZSTD, NOWHERE).unpack(archive, dir));
        assertFalse(Files.exists(dir.resolve("escape.txt")));
    }

    @Test
    void refusesALink(@TempDir Path dir) throws IOException {
        byte[] archive = zstd(tar(true, "link", "/etc/passwd"));

        assertThrows(IOException.class, () -> pin(archive, NdcCorpus.Kind.TAR_ZSTD, NOWHERE).unpack(archive, dir));
    }

    @Test
    void thePinsAreWellFormed() {
        for (NdcCorpus pin : NdcCorpus.ALL) {
            assertTrue(pin.sha256().matches("[0-9a-f]{64}"), pin.name());
            pin.urls().forEach(url -> assertEquals("https", url.getScheme(), pin.name()));
        }
        assertTrue(NdcCorpus.TURBO_CARDANO_NODE.urls().getFirst().toString().contains(NdcCorpus.NDC_REVISION));
        assertTrue(NdcCorpus.TURBO_AMARU.urls().getFirst().toString().contains(NdcCorpus.NDC_REVISION));
        assertTrue(NdcCorpus.TURBO.urls().getLast().toString().startsWith(NdcCorpus.MIRROR));
    }

    private static byte[] tar(boolean link, String name, String content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            if (link) {
                TarArchiveEntry entry = new TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK);
                entry.setLinkName(content);
                tar.putArchiveEntry(entry);
            } else {
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                TarArchiveEntry entry = new TarArchiveEntry(name);
                entry.setSize(bytes.length);
                tar.putArchiveEntry(entry);
                tar.write(bytes);
            }
            tar.closeArchiveEntry();
        }
        return out.toByteArray();
    }

    private static byte[] zstd(byte[] bytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZstdOutputStream zstd = new ZstdOutputStream(out)) {
            new ByteArrayInputStream(bytes).transferTo(zstd);
        }
        return out.toByteArray();
    }
}
