package org.julclang.core.corpus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;

/**
 * File plumbing of the test-only corpus recorders of ADR-068 M0 (the compiler's {@code FileCorpusRecorder} and the
 * VM's {@code FileEvaluationRecorder}) and of the ledger that reads what they write: content digests, a lossless
 * text encoding, write-once files and the origin of a recording. Nothing on a compilation or evaluation path refers
 * to this class: the recorders are loaded by name only when their system property is set, so native and Web images,
 * which never set it, do not reach {@link StackWalker}, {@link Files} or {@link MessageDigest} through them.
 */
public final class CorpusFiles {

    private static final byte[] UTF16_MARK = {0, 'u', 't', 'f', '1', '6', 0};
    private static final String UTF16_MARK_TEXT = new String(UTF16_MARK, StandardCharsets.US_ASCII);

    private CorpusFiles() {
    }

    /** The SHA-256 digest of {@link #bytes(String) the bytes} of {@code text}, in lower-case hex. */
    public static String sha256(String text) {
        return sha256(bytes(text));
    }

    /** The SHA-256 digest of {@code bytes}, in lower-case hex. */
    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The stored form of {@code text}: its UTF-8 bytes, or a reserved marker followed by its UTF-16 code units,
     * big-endian. The marker form is used for a text UTF-8 cannot encode (an unpaired surrogate, which JuLC's tests
     * compile to check its rejection) and for a text that itself begins with the marker's characters, so the
     * encoding is injective (F314): a UTF-8 form never begins with the marker (the marker is ASCII, so UTF-8 bytes
     * begin with it only when the text begins with its characters), every marker form does, and each form is
     * injective on its own. Every ordinary source (UTF-8-encodable, not beginning with a NUL) keeps its UTF-8 bytes
     * and so its digest.
     */
    public static byte[] bytes(String text) {
        if (!text.startsWith(UTF16_MARK_TEXT) && StandardCharsets.UTF_8.newEncoder().canEncode(text)) {
            return text.getBytes(StandardCharsets.UTF_8);
        }
        var out = new byte[UTF16_MARK.length + 2 * text.length()];
        System.arraycopy(UTF16_MARK, 0, out, 0, UTF16_MARK.length);
        for (int i = 0; i < text.length(); i++) {
            out[UTF16_MARK.length + 2 * i] = (byte) (text.charAt(i) >> 8);
            out[UTF16_MARK.length + 2 * i + 1] = (byte) text.charAt(i);
        }
        return out;
    }

    /** The text of {@link #bytes(String) stored bytes}. */
    public static String text(byte[] bytes) {
        if (bytes.length < UTF16_MARK.length
                || !Arrays.equals(bytes, 0, UTF16_MARK.length, UTF16_MARK, 0, UTF16_MARK.length)) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if ((bytes.length - UTF16_MARK.length) % 2 != 0) {
            throw new IllegalArgumentException("stored text: odd UTF-16 length after the marker");
        }
        var chars = new char[(bytes.length - UTF16_MARK.length) / 2];
        for (int i = 0; i < chars.length; i++) {
            int at = UTF16_MARK.length + 2 * i;
            chars[i] = (char) (((bytes[at] & 0xff) << 8) | (bytes[at + 1] & 0xff));
        }
        return new String(chars);
    }

    /**
     * Writes {@code content} to {@code file} unless it exists, through a temporary file and an atomic move, so
     * concurrent JVMs can share a directory.
     */
    public static void writeOnce(Path file, byte[] content) throws IOException {
        if (Files.exists(file)) return;
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), ".tmp-", "");
        try {
            Files.write(temp, content);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException e) {
            // Another JVM wrote the same content first.
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** {@link #writeOnce(Path, byte[])} of {@link #bytes(String) the bytes} of {@code text}. */
    public static void writeOnce(Path file, String text) throws IOException {
        writeOnce(file, bytes(text));
    }

    /** Creates an empty marker file unless it exists. */
    public static void touch(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        try {
            Files.createFile(file);
        } catch (FileAlreadyExistsException e) {
            // Already recorded.
        }
    }

    /**
     * The outermost frame of a test class ({@code Class#method}, a class loaded from a test output directory): the
     * test method itself rather than a helper or lambda it calls. A recording outside a test is {@code unknown}.
     */
    public static String origin() {
        Optional<String> frame = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(frames -> frames.filter(f -> isTestClass(f.getDeclaringClass()))
                        .map(f -> f.getDeclaringClass().getName() + "#" + f.getMethodName())
                        .reduce((inner, outer) -> outer));
        return frame.orElse("unknown");
    }

    private static boolean isTestClass(Class<?> type) {
        var domain = type.getProtectionDomain();
        var source = domain == null ? null : domain.getCodeSource();
        if (source == null || source.getLocation() == null) return false;
        String location = source.getLocation().toString();
        return location.contains("/classes/java/test/") || location.contains("/test-classes/");
    }
}
