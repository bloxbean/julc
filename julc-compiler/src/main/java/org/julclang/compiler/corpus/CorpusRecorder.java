package org.julclang.compiler.corpus;

import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.pir.StdlibLookup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Records every source compiled through a public compiler entry point, for ADR-068 M0's corpus (D13). Test-only:
 * it is active only when the system property {@value #PROPERTY} names a directory, and {@link #ACTIVE} is then the
 * recorder for it. When the property is unset {@link #ACTIVE} is {@code null}, every hook is a single test of a
 * {@code static final} field, and nothing else runs.
 * <p>
 * Recording never changes a compilation: a hook only reads the arguments it is given, after the entry point has
 * resolved them, and the recorder's own failures are reported as {@link UncheckedIOException} only when a write
 * fails (so a broken corpus is never mistaken for a complete one).
 * <p>
 * Layout under the directory, every name a SHA-256 digest in lower-case hex:
 * <ul>
 *   <li>{@code sources/<digest>.java}: one source text ({@link #bytes(String)}), named by the digest of those bytes;</li>
 *   <li>{@code cases/<key>/case.txt}: the case, named by the digest of this text: its entry point, method, stdlib
 *       lookup, target, source-map flag, disabled rules, source and library digests (and file names for the
 *       path-based entry points), in a fixed order;</li>
 *   <li>{@code cases/<key>/level-<LEVEL>}: an empty marker per optimization level the case was compiled at;</li>
 *   <li>{@code cases/<key>/origin-<digest>}: the test that compiled it, {@code Class#method}, as content.</li>
 * </ul>
 * Every file is written once, through a temporary file and an atomic move, so concurrent test JVMs can share a
 * directory, and a second recording of the same tests produces the same tree.
 */
public final class CorpusRecorder {

    /** The system property naming the corpus directory. */
    public static final String PROPERTY = "julc.corpus.record";

    /** The recorder of this JVM, or {@code null} when {@value #PROPERTY} is unset. */
    public static final CorpusRecorder ACTIVE = fromProperty();

    /** The version of the {@code case.txt} format. */
    public static final int FORMAT = 1;

    private final Path root;

    /** A recorder writing under {@code root}. */
    public CorpusRecorder(Path root) {
        this.root = root.toAbsolutePath();
    }

    private static CorpusRecorder fromProperty() {
        String dir = System.getProperty(PROPERTY);
        return dir == null || dir.isBlank() ? null : new CorpusRecorder(Path.of(dir));
    }

    /** A source compiled by a string entry point: {@code fileName} is null. */
    public record Source(String text, String fileName) {
    }

    /**
     * Records one compilation.
     *
     * @param entry      the public entry point, such as {@code compile} or {@code compileMethod}
     * @param method     the method name of {@code compileMethod}, or null
     * @param main       the validator or method source, or null for {@code JavaLibraryProvider}
     * @param libraries  the library sources, after the entry point has resolved them
     * @param lookup     the stdlib lookup the compiler was built with, or null
     * @param options    the options of the compilation
     */
    public void record(String entry, String method, Source main, List<Source> libraries, StdlibLookup lookup,
                       CompilerOptions options) {
        try {
            String mainRef = main == null ? null : write(main);
            var libraryRefs = new ArrayList<String>();
            for (var library : libraries) libraryRefs.add(write(library));
            String caseText = caseText(entry, method, lookup == null ? "none" : lookup.getClass().getName(),
                    options, mainRef, libraryRefs);
            Path dir = root.resolve("cases").resolve(sha256(caseText));
            Files.createDirectories(dir);
            writeOnce(dir.resolve("case.txt"), caseText);
            touch(dir.resolve("level-" + options.getOptimizationLevel().name()));
            String origin = origin();
            writeOnce(dir.resolve("origin-" + sha256(origin).substring(0, 16)), origin + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException("corpus recorder: " + root, e);
        }
    }

    /**
     * The text of {@code case.txt}, whose digest names the case: the ledger of ADR-068 D13 builds the same text for
     * the cases it adds, so a recorded case and a generated one with the same inputs are one case.
     *
     * @param lookup       {@code none}, or the class name of the stdlib lookup
     * @param mainRef      the main source's digest (and file name), or null
     * @param libraryRefs  each library's digest (and file name)
     */
    public static String caseText(String entry, String method, String lookup, CompilerOptions options,
                                  String mainRef, List<String> libraryRefs) {
        var text = new StringBuilder();
        text.append("format: ").append(FORMAT).append('\n');
        text.append("entry: ").append(entry).append('\n');
        if (method != null) text.append("method: ").append(method).append('\n');
        text.append("lookup: ").append(lookup).append('\n');
        text.append("target: ").append(options.getTarget().profileId()).append('\n');
        text.append("sourceMap: ").append(options.isSourceMapEnabled()).append('\n');
        if (!options.getDisabledOptimizationRules().isEmpty()) {
            text.append("disabledRules: ").append(String.join(",", options.getDisabledOptimizationRules())).append('\n');
        }
        if (mainRef != null) text.append("source: ").append(mainRef).append('\n');
        for (String library : libraryRefs) text.append("library: ").append(library).append('\n');
        return text.toString();
    }

    /** Writes the source once and returns its digest, with the file name when it has one. */
    private String write(Source source) throws IOException {
        String digest = sha256(source.text());
        Path sources = root.resolve("sources");
        Files.createDirectories(sources);
        writeOnce(sources.resolve(digest + ".java"), source.text());
        return source.fileName() == null ? digest : digest + " " + source.fileName();
    }

    private static void writeOnce(Path file, String content) throws IOException {
        if (Files.exists(file)) return;
        Path temp = Files.createTempFile(file.getParent(), ".tmp-", "");
        try {
            Files.write(temp, bytes(content));
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException e) {
            // Another JVM wrote the same content first.
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void touch(Path file) throws IOException {
        try {
            Files.createFile(file);
        } catch (FileAlreadyExistsException e) {
            // Already recorded.
        }
    }

    /**
     * The outermost frame of a test class ({@code Class#method}, a class loaded from a test output directory): the
     * test method itself rather than a helper or lambda it calls. A compilation outside a test is {@code unknown}.
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

    /** The SHA-256 digest of {@link #bytes(String) the bytes} of {@code text}, in lower-case hex. */
    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(text)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final byte[] UTF16_MARK = {0, 'u', 't', 'f', '1', '6', 0};

    /**
     * The stored form of {@code text}: its UTF-8 bytes, or, for a text UTF-8 cannot encode (an unpaired surrogate,
     * which JuLC's tests compile to check its rejection) or whose UTF-8 starts with the marker, a marker followed by
     * its UTF-16 code units, big-endian, so that every source is stored without loss and distinct texts have
     * distinct stored forms.
     */
    public static byte[] bytes(String text) {
        if (StandardCharsets.UTF_8.newEncoder().canEncode(text)) {
            byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
            // A text whose UTF-8 starts with the marker is stored in the marker form too, so that no UTF-8 text can
            // equal another text's marker form: the stored form, and so its digest, identify the text (ADR-068
            // M1a F512). Source texts never start with the marker, so no recorded M0 digest changes.
            if (utf8.length < UTF16_MARK.length
                    || !Arrays.equals(utf8, 0, UTF16_MARK.length, UTF16_MARK, 0, UTF16_MARK.length)) {
                return utf8;
            }
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
        var chars = new char[(bytes.length - UTF16_MARK.length) / 2];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = (char) (((bytes[UTF16_MARK.length + 2 * i] & 0xff) << 8) | (bytes[UTF16_MARK.length + 2 * i + 1] & 0xff));
        }
        return new String(chars);
    }
}
