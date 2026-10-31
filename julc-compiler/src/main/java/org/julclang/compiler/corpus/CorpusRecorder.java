package org.julclang.compiler.corpus;

import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.backend.LibraryRequest;
import org.julclang.compiler.pir.StdlibLookup;

import java.util.List;
import java.util.TreeSet;

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
     * The options of one recorded compilation, as the compilation resolved them: an immutable snapshot, taken from
     * the {@link CompilationContext} the compilation uses (or, for a case the ledger builds, from its options), so
     * that a caller changing its {@link CompilerOptions} afterwards cannot change what was recorded (F313).
     *
     * @param targetProfileId     the compiler target's profile id
     * @param costProfile         the optimization cost profile's id and parameter hash, or null
     * @param sourceMap           whether a source map was requested
     * @param disabledRules       the disabled optimization rules, sorted
     * @param optimizationLevel   the optimization level
     */
    record Options(String targetProfileId, String costProfile, boolean sourceMap, List<String> disabledRules,
                   OptimizationLevel optimizationLevel) {

        public Options {
            disabledRules = List.copyOf(new TreeSet<>(disabledRules));
        }

        /** The options a compilation resolved. */
        public static Options of(CompilationContext context) {
            var profile = context.optimizationCostProfile();
            return new Options(context.target().profileId(),
                    profile == null ? null : profile.profileId() + " " + profile.parameterHash(),
                    context.isSourceMapEnabled(), List.copyOf(context.disabledOptimizationRules()),
                    context.optimizationLevel());
        }

        /** The options as they are now (for the cases the ledger builds; a compilation records its context). */
        public static Options of(CompilerOptions options) {
            var profile = options.getOptimizationCostProfile();
            return new Options(options.getTarget().profileId(),
                    profile == null ? null : profile.profileId() + " " + profile.parameterHash(),
                    options.isSourceMapEnabled(), List.copyOf(options.getDisabledOptimizationRules()),
                    options.getOptimizationLevel());
        }
    }

    /**
     * Records one compilation, and returns its case's identity (the {@code case.txt} digest), which a
     * {@code JavaLibraryProvider} keeps for the specializations it is later asked for.
     *
     * @param entry      the public entry point, such as {@code compile} or {@code compileMethod}
     * @param method     the method name of {@code compileMethod}, or null
     * @param main       the validator or method source, or null for {@code JavaLibraryProvider}
     * @param libraries  the library sources, after the entry point has resolved them
     * @param lookup     the stdlib lookup the compiler was built with, or null
     * @param options    the options of the compilation, as it resolved them
     */
    String record(String entry, String method, Source main, List<Source> libraries, StdlibLookup lookup,
                  Options options);

    /**
     * Records one specialization request made of the provider whose construction {@link #record} recorded as the
     * case {@code providerCase}, against {@code lookup}, the stdlib lookup the provider specializes with now. When
     * the lookup's identity is no longer the one the case was recorded with (a {@code StdlibRegistry} registered
     * into after the provider was built, F320), the request is recorded as unreplayable, not under the case.
     */
    void recordInstantiate(String providerCase, StdlibLookup lookup, LibraryRequest.Instantiate request);

    /**
     * The text of {@code case.txt}, whose digest names the case. The ledger of ADR-068 D13 builds the same text for
     * the cases it adds, so a recorded case and a generated one with the same inputs are one case.
     *
     * @param lookup       {@code none}, {@code default:<class>} for the default stdlib registry, or
     *                     {@code custom:<class>:<fingerprint>}
     * @param mainRef      the main source's digest (and file name), or null
     * @param libraryRefs  each library's digest (and file name)
     */
    static String caseText(String entry, String method, String lookup, Options options, String mainRef,
                           List<String> libraryRefs) {
        var text = new StringBuilder();
        text.append("format: ").append(FORMAT).append('\n');
        text.append("entry: ").append(entry).append('\n');
        if (method != null) text.append("method: ").append(method).append('\n');
        text.append("lookup: ").append(lookup).append('\n');
        text.append("target: ").append(options.targetProfileId()).append('\n');
        if (options.costProfile() != null) text.append("costProfile: ").append(options.costProfile()).append('\n');
        text.append("sourceMap: ").append(options.sourceMap()).append('\n');
        if (!options.disabledRules().isEmpty()) {
            text.append("disabledRules: ").append(String.join(",", options.disabledRules())).append('\n');
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
