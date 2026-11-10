package org.julclang.vm.java.ndc;

import com.github.luben.zstd.ZstdOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NdcCorpusTest {

    /** Never contacted: every test installs bytes or finds them in the cache. */
    private static final URI NOWHERE = URI.create("https://example.invalid/corpus");

    private static NdcCorpus pin(byte[] bytes, NdcCorpus.Kind kind) {
        return new NdcCorpus("test", NOWHERE, NdcCorpus.sha256(bytes), kind);
    }

    @Test
    void refusesBytesThatDoNotMatchThePin(@TempDir Path cache) {
        NdcCorpus pin = new NdcCorpus("test", NOWHERE, "00".repeat(32), NdcCorpus.Kind.PLAIN);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> pin.install("tampered".getBytes(StandardCharsets.UTF_8), cache));
        assertTrue(e.getMessage().contains("SHA-256"));
        assertFalse(Files.exists(cache.resolve(pin.sha256())));
    }

    @Test
    void extractsATarZstdAndThenServesItFromTheCache(@TempDir Path cache) throws IOException {
        byte[] archive = zstd(tar(false, "protocol-version-11/v3/term/a/a.uplc", "(program 1.0.0 (con unit ()))"));
        NdcCorpus pin = pin(archive, NdcCorpus.Kind.TAR_ZSTD);

        Path dir = pin.install(archive, cache);

        assertEquals("(program 1.0.0 (con unit ()))",
                Files.readString(dir.resolve("protocol-version-11/v3/term/a/a.uplc")));
        // a second fetch finds it complete and does not download (the URL cannot be resolved)
        assertEquals(dir, pin.fetch(cache));
    }

    @Test
    void decompressesAZstdFile(@TempDir Path cache) throws IOException {
        byte[] compressed = zstd("{\"benchmarks\": []}".getBytes(StandardCharsets.UTF_8));

        Path file = pin(compressed, NdcCorpus.Kind.ZSTD).install(compressed, cache);

        assertEquals("{\"benchmarks\": []}", Files.readString(file));
    }

    @Test
    void refusesAnEntryOutsideTheTarget(@TempDir Path cache) throws IOException {
        byte[] archive = zstd(tar(false, "../escape.txt", "x"));

        assertThrows(RuntimeException.class, () -> pin(archive, NdcCorpus.Kind.TAR_ZSTD).install(archive, cache));
        assertFalse(Files.exists(cache.resolve("escape.txt")));
    }

    @Test
    void refusesALink(@TempDir Path cache) throws IOException {
        byte[] archive = zstd(tar(true, "link", "/etc/passwd"));

        assertThrows(RuntimeException.class, () -> pin(archive, NdcCorpus.Kind.TAR_ZSTD).install(archive, cache));
    }

    @Test
    void thePinsAreWellFormed() {
        for (NdcCorpus pin : new NdcCorpus[]{NdcCorpus.UPLC_CONFORMANCE, NdcCorpus.UPLC_EXPECTATIONS, NdcCorpus.TURBO,
                NdcCorpus.TURBO_CARDANO_NODE, NdcCorpus.TURBO_AMARU}) {
            assertTrue(pin.sha256().matches("[0-9a-f]{64}"), pin.name());
            assertEquals("https", pin.url().getScheme(), pin.name());
        }
        assertTrue(NdcCorpus.UPLC_EXPECTATIONS.url().toString().contains(NdcCorpus.NDC_REVISION));
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
