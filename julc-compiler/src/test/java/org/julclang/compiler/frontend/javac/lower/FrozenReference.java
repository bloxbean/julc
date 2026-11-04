package org.julclang.compiler.frontend.javac.lower;

import org.julclang.core.Program;
import org.julclang.core.flat.UplcFlatEncoder;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * What the JavaParser frontend answered, as data (ADR-068 M6). Until M6 the lowering tests compiled each source on that
 * frontend and on javac and compared; the frontend left, and its answers stay in {@code adr068/frozen-reference.tsv}, one
 * digest per question. A row was written at M6 from a head where those comparisons passed, so it holds both frontends'
 * answer. A question with no row fails: a new test states what it expects.
 * <p>
 * A change that moves an answer on purpose, in a later reference change, regenerates the table with
 * {@code -Pjulc.frozenReference.record=<file>}, and the diff shows every answer it moved.
 */
public final class FrozenReference {

    /** Set by the build to write the rows instead of comparing with them. */
    private static final String RECORD = System.getProperty("julc.frozenReference.record");

    private static final Map<String, String> ROWS = RECORD != null ? Map.of() : load();

    private FrozenReference() {
    }

    /** {@code actual} is what the reference answered to {@code what}. */
    public static void assertSame(String what, String actual) {
        String key = digest(what);
        if (RECORD != null) {
            record(key, digest(actual));
            return;
        }
        String row = ROWS.get(key);
        assertNotNull(row, () -> "the reference has no answer frozen for " + what);
        assertEquals(row, digest(actual), () -> "the reference answered otherwise to " + what + ": javac says " + actual);
    }

    /** {@code program}'s FLAT bytes are what the reference compiled for {@code what}. */
    public static void assertSameBytes(String what, Program program) {
        assertSame(what, HexFormat.of().formatHex(UplcFlatEncoder.encodeProgram(program)));
    }

    private static synchronized void record(String key, String value) {
        try {
            Files.writeString(Path.of(RECORD), key + "\t" + value + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, String> load() {
        var rows = new TreeMap<String, String>();
        try (InputStream in = FrozenReference.class.getResourceAsStream("/adr068/frozen-reference.tsv")) {
            if (in == null) return rows;
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank()) continue;
                int tab = line.indexOf('\t');
                rows.put(line.substring(0, tab), line.substring(tab + 1));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return rows;
    }

    private static String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
