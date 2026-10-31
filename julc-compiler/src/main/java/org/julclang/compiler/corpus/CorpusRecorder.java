package org.julclang.compiler.corpus;

import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.backend.LibraryRequest;
import org.julclang.compiler.pir.StdlibLookup;

import java.util.List;

/**
 * Records every source compiled through a public compiler entry point, and every generic specialization a
 * {@code JavaLibraryProvider} is asked for, for ADR-068 M0's corpus (D13). Test-only: it is active only when the
 * system property {@value #PROPERTY} names a directory. {@link #ACTIVE} is then a {@link FileCorpusRecorder},
 * loaded by a class name read from a system property, so that nothing on a compilation path refers to the
 * implementation (and a native or Web image, which never sets the property, does not reach {@code StackWalker},
 * {@code Files} or {@code MessageDigest} through it). When the property is unset {@link #ACTIVE} is {@code null},
 * and every hook is one test of this {@code static final} field.
 * <p>
 * Recording never changes a compilation: a hook only reads the arguments it is given, after the entry point has
 * resolved them. A write failure is an {@code UncheckedIOException}, so a broken corpus is never mistaken for a
 * complete one.
 */
public interface CorpusRecorder {

    /** The system property naming the corpus directory. */
    String PROPERTY = "julc.corpus.record";

    /** The version of the {@code case.txt} format. */
    int FORMAT = 2;

    /** The recorder of this JVM, or {@code null} when {@value #PROPERTY} is unset. */
    CorpusRecorder ACTIVE = Loader.load();

    /** A source compiled by a string entry point: {@code fileName} is null. */
    record Source(String text, String fileName) {
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
    void record(String entry, String method, Source main, List<Source> libraries, StdlibLookup lookup,
                CompilerOptions options);

    /** Records one specialization request made of the provider built from these sources, lookup and options. */
    void recordInstantiate(List<Source> libraries, StdlibLookup lookup, CompilerOptions options,
                           LibraryRequest.Instantiate request);

    /**
     * The text of {@code case.txt}, whose digest names the case. The ledger of ADR-068 D13 builds the same text for
     * the cases it adds, so a recorded case and a generated one with the same inputs are one case.
     *
     * @param lookup       {@code none}, {@code default:<class>} for the default stdlib registry, or
     *                     {@code custom:<class>:<fingerprint>}
     * @param mainRef      the main source's digest (and file name), or null
     * @param libraryRefs  each library's digest (and file name)
     */
    static String caseText(String entry, String method, String lookup, CompilerOptions options, String mainRef,
                           List<String> libraryRefs) {
        var text = new StringBuilder();
        text.append("format: ").append(FORMAT).append('\n');
        text.append("entry: ").append(entry).append('\n');
        if (method != null) text.append("method: ").append(method).append('\n');
        text.append("lookup: ").append(lookup).append('\n');
        text.append("target: ").append(options.getTarget().profileId()).append('\n');
        var profile = options.getOptimizationCostProfile();
        if (profile != null) {
            text.append("costProfile: ").append(profile.profileId()).append(' ').append(profile.parameterHash()).append('\n');
        }
        text.append("sourceMap: ").append(options.isSourceMapEnabled()).append('\n');
        if (!options.getDisabledOptimizationRules().isEmpty()) {
            text.append("disabledRules: ").append(String.join(",", options.getDisabledOptimizationRules())).append('\n');
        }
        if (mainRef != null) text.append("source: ").append(mainRef).append('\n');
        for (String library : libraryRefs) text.append("library: ").append(library).append('\n');
        return text.toString();
    }

    /** Loads the recorder class named by {@value #PROPERTY}{@code .impl}, by default {@link FileCorpusRecorder}. */
    final class Loader {
        private Loader() {
        }

        static CorpusRecorder load() {
            String dir = System.getProperty(PROPERTY);
            if (dir == null || dir.isBlank()) return null;
            String implementation = System.getProperty(PROPERTY + ".impl",
                    "org.julclang.compiler.corpus.FileCorpusRecorder");
            try {
                return (CorpusRecorder) Class.forName(implementation).getConstructor(String.class).newInstance(dir);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("corpus recorder " + implementation + " for " + dir, e);
            }
        }
    }
}
