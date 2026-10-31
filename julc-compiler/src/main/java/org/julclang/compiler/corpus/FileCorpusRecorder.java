package org.julclang.compiler.corpus;

import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.backend.LibraryRequest;
import org.julclang.compiler.pir.StdlibLookup;
import org.julclang.core.corpus.CorpusFiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The {@link CorpusRecorder} that writes ADR-068 M0's corpus under a directory, every name a SHA-256 digest in
 * lower-case hex:
 * <ul>
 *   <li>{@code sources/<digest>.java}: one source text ({@link CorpusFiles#bytes(String)}), named by the digest of
 *       those bytes;</li>
 *   <li>{@code cases/<key>/case.txt}: the case ({@link CorpusRecorder#caseText}), named by the digest of this
 *       text;</li>
 *   <li>{@code cases/<key>/level-<LEVEL>}: an empty marker per optimization level the case was compiled at;</li>
 *   <li>{@code cases/<key>/origin-<digest>}: a test that compiled it, {@code Class#method}, as content;</li>
 *   <li>{@code cases/<key>/instantiate-<digest>}: a specialization request ({@link RequestText}) made of the
 *       provider of a {@code JavaLibraryProvider} case.</li>
 * </ul>
 * Loaded by name only (see {@link CorpusRecorder}); never referred to from a compilation path.
 */
public final class FileCorpusRecorder implements CorpusRecorder {

    private static final String STDLIB_REGISTRY = "org.julclang.stdlib.StdlibRegistry";

    private final Path root;
    private volatile Map<String, String> defaultShape;

    public FileCorpusRecorder(String root) {
        this.root = Path.of(root).toAbsolutePath();
    }

    @Override
    public void record(String entry, String method, Source main, List<Source> libraries, StdlibLookup lookup,
                       CompilerOptions options) {
        try {
            Path dir = caseDir(entry, method, main, libraries, lookup, options);
            CorpusFiles.touch(dir.resolve("level-" + options.getOptimizationLevel().name()));
            writeOrigin(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("corpus recorder: " + root, e);
        }
    }

    @Override
    public void recordInstantiate(List<Source> libraries, StdlibLookup lookup, CompilerOptions options,
                                  LibraryRequest.Instantiate request) {
        try {
            Path dir = caseDir("JavaLibraryProvider", null, null, libraries, lookup, options);
            String text = RequestText.encode(request) + "\n";
            CorpusFiles.writeOnce(dir.resolve("instantiate-" + CorpusFiles.sha256(text).substring(0, 16)), text);
            writeOrigin(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("corpus recorder: " + root, e);
        }
    }

    private Path caseDir(String entry, String method, Source main, List<Source> libraries, StdlibLookup lookup,
                         CompilerOptions options) throws IOException {
        String mainRef = main == null ? null : write(main);
        var libraryRefs = new ArrayList<String>();
        for (var library : libraries) libraryRefs.add(write(library));
        String caseText = CorpusRecorder.caseText(entry, method, identity(lookup), options, mainRef, libraryRefs);
        Path dir = root.resolve("cases").resolve(CorpusFiles.sha256(caseText));
        CorpusFiles.writeOnce(dir.resolve("case.txt"), caseText);
        return dir;
    }

    private void writeOrigin(Path dir) throws IOException {
        String origin = CorpusFiles.origin();
        CorpusFiles.writeOnce(dir.resolve("origin-" + CorpusFiles.sha256(origin).substring(0, 16)), origin + "\n");
    }

    /** Writes the source once and returns its digest, with the file name when it has one. */
    private String write(Source source) throws IOException {
        String digest = CorpusFiles.sha256(source.text());
        CorpusFiles.writeOnce(root.resolve("sources").resolve(digest + ".java"), source.text());
        return source.fileName() == null ? digest : digest + " " + source.fileName();
    }

    /**
     * The lookup's identity: {@code none}; {@code default:org.julclang.stdlib.StdlibRegistry} when it is a
     * {@code StdlibRegistry} with exactly the registrations of {@code StdlibRegistry.defaultRegistry()} (each key with
     * the same builder class and lowering requirements); otherwise {@code custom:<class>:<fingerprint>}, the
     * fingerprint a digest of its registered class names (and, for a registry, its registration keys). The ledger
     * replays only the first two; a custom lookup is never replayed as the default (F201, F300).
     */
    String identity(StdlibLookup lookup) {
        if (lookup == null) return "none";
        String type = lookup.getClass().getName();
        Map<String, String> shape = type.equals(STDLIB_REGISTRY) ? shape(lookup) : null;
        if (shape != null && shape.equals(defaultShape())) return "default:" + type;
        var fingerprint = new StringBuilder();
        new TreeSet<>(lookup.registeredClassNames()).forEach(name -> fingerprint.append(name).append('\n'));
        if (shape != null) shape.forEach((key, value) -> fingerprint.append(key).append('\n'));
        return "custom:" + type + ":" + CorpusFiles.sha256(fingerprint.toString()).substring(0, 16);
    }

    private Map<String, String> defaultShape() {
        if (defaultShape == null) {
            try {
                Object registry = Class.forName(STDLIB_REGISTRY).getMethod("defaultRegistry").invoke(null);
                defaultShape = shape(registry);
            } catch (ReflectiveOperationException e) {
                defaultShape = Map.of();
            }
        }
        return defaultShape;
    }

    /** Each registration key with its builder's class and its lowering requirements, read reflectively. */
    private static Map<String, String> shape(Object registry) {
        try {
            Field field = registry.getClass().getDeclaredField("registry");
            field.setAccessible(true);
            var shape = new TreeMap<String, String>();
            for (var entry : ((Map<?, ?>) field.get(registry)).entrySet()) {
                var value = new StringBuilder();
                Object registration = entry.getValue();
                for (RecordComponent component : registration.getClass().getRecordComponents()) {
                    var accessor = component.getAccessor();
                    accessor.setAccessible(true);
                    Object part = accessor.invoke(registration);
                    value.append(part == null ? "null" : part instanceof Record ? part.toString() : part.getClass().getName()).append(";");
                }
                shape.put(String.valueOf(entry.getKey()), value.toString());
            }
            return shape;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
