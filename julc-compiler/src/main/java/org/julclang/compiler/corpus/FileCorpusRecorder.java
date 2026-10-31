package org.julclang.compiler.corpus;

import org.julclang.compiler.backend.LibraryRequest;
import org.julclang.compiler.pir.StdlibLookup;
import org.julclang.core.corpus.CorpusFiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    public FileCorpusRecorder(String root) {
        this.root = Path.of(root).toAbsolutePath();
    }

    @Override
    public String record(String entry, String method, Source main, List<Source> libraries, StdlibLookup lookup,
                         Options options) {
        try {
            String mainRef = main == null ? null : write(main);
            var libraryRefs = new ArrayList<String>();
            for (var library : libraries) libraryRefs.add(write(library));
            String caseText = CorpusRecorder.caseText(entry, method, identity(lookup), options, mainRef, libraryRefs);
            String key = CorpusFiles.sha256(caseText);
            Path dir = root.resolve("cases").resolve(key);
            CorpusFiles.writeOnce(dir.resolve("case.txt"), caseText);
            CorpusFiles.touch(dir.resolve("level-" + options.optimizationLevel().name()));
            writeOrigin(dir);
            return key;
        } catch (IOException e) {
            throw new UncheckedIOException("corpus recorder: " + root, e);
        }
    }

    @Override
    public void recordInstantiate(String providerCase, LibraryRequest.Instantiate request) {
        try {
            Path dir = root.resolve("cases").resolve(providerCase);
            String text = RequestText.encode(request) + "\n";
            CorpusFiles.writeOnce(dir.resolve("instantiate-" + CorpusFiles.sha256(text).substring(0, 16)), text);
            writeOrigin(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("corpus recorder: " + root, e);
        }
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
     * The lookup's identity: {@code none}; {@code default:org.julclang.stdlib.StdlibRegistry} only for a
     * {@code StdlibRegistry} that still holds the token {@code StdlibRegistry.defaultRegistry()} gives the registry
     * it builds, which any later registration clears (F310: equal registration keys, builder classes and
     * requirements do not make a registry the default, because table-built builders share a class but capture
     * different builtins); otherwise {@code custom:<class>:<fingerprint>}, the fingerprint a digest of its
     * registered class names (and, for a registry, its registration keys). The ledger replays only the first two;
     * a custom lookup is never replayed as the default (F201, F300, F310).
     */
    String identity(StdlibLookup lookup) {
        if (lookup == null) return "none";
        String type = lookup.getClass().getName();
        boolean registry = type.equals(STDLIB_REGISTRY);
        if (registry && pristineDefault(lookup)) return "default:" + type;
        var fingerprint = new StringBuilder();
        new TreeSet<>(lookup.registeredClassNames()).forEach(name -> fingerprint.append(name).append('\n'));
        if (registry) registrationKeys(lookup).forEach(key -> fingerprint.append(key).append('\n'));
        return "custom:" + type + ":" + CorpusFiles.sha256(fingerprint.toString()).substring(0, 16);
    }

    /** Whether the registry holds {@code StdlibRegistry}'s pristine-default token; unreadable means no. */
    private static boolean pristineDefault(Object registry) {
        try {
            Field field = registry.getClass().getDeclaredField("pristineDefault");
            field.setAccessible(true);
            return field.get(registry) != null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** The registry's registration keys, sorted, read reflectively (empty when unreadable). */
    private static TreeSet<String> registrationKeys(Object registry) {
        var keys = new TreeSet<String>();
        try {
            Field field = registry.getClass().getDeclaredField("registry");
            field.setAccessible(true);
            ((Map<?, ?>) field.get(registry)).keySet().forEach(key -> keys.add(String.valueOf(key)));
        } catch (ReflectiveOperationException | RuntimeException e) {
            keys.add("<unreadable>");
        }
        return keys;
    }
}
