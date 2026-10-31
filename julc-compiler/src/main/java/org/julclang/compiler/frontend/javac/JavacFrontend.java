package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.Trees;
import org.julclang.compiler.error.DiagnosticCodes;

import javax.lang.model.SourceVersion;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The javac compilation environment of every JuLC host (ADR-068 D3). It builds a {@link JavacTask} from
 * {@link ToolProvider#getSystemJavaCompiler()} over the {@link EmbeddedFileManager}, with the fixed options
 * {@link #OPTIONS} and the English locale, and uses only exported APIs ({@code javax.tools}, {@code javax.lang.model},
 * {@code com.sun.source}; I6). It never reads the host's {@code jrt:/}, {@code ct.sym} or class path.
 * <p>
 * {@link #analyze} runs, in order: the supported-javac check ({@code JULC0089}), the ownership check by provenance
 * ({@code JULC0081}), then javac's {@code parse()} and {@code analyze()}, whose errors become {@code JULC0085}
 * (D5). Diagnostics follow javac's emission order, which depends only on the inputs (I4).
 */
public final class JavacFrontend {

    /** javac's options (D3). No {@code --release}: the platform blob is the platform. */
    public static final List<String> OPTIONS = List.of("-proc:none", "-source", "21", "-implicit:none", "-Xlint:none");

    /** The javac feature release JuLC supports (D3). */
    public static final String SUPPORTED_RELEASE = "RELEASE_25";

    private final JavacPlatform platform;
    private final Supplier<JavaCompiler> compilerSupplier;
    private final BundledSources bundled;
    private volatile JavaCompiler compiler;
    private volatile String unsupported;
    /** Results of this environment, by input digest (JavacGate); a frontend's environment never changes. */
    private final Map<String, JavacResult> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, JavacResult> eldest) {
            return size() > CACHE_LIMIT;
        }
    };
    private static final int CACHE_LIMIT = 512;

    /** A source given to javac: its text and, for the path entry points, its file. */
    public record Unit(String text, Path path) {
        public static Unit of(String text) {
            return new Unit(text, null);
        }
    }

    private static volatile JavacFrontend shared;

    /**
     * The host's javac and this build's platform; the platform is parsed once per process. The bundled set is
     * looked up again while it is empty (a later caller may bring the loader that has julc-stdlib), and
     * {@link BundledSources#load()} fails loudly when julc-stdlib is present without its manifest.
     */
    public static JavacFrontend shared() {
        JavacFrontend frontend = shared;
        if (frontend != null && !frontend.bundled.digests().isEmpty()) return frontend;
        synchronized (JavacFrontend.class) {
            if (shared == null || shared.bundled.digests().isEmpty()) {
                var bundled = BundledSources.load();
                if (shared == null || !bundled.digests().isEmpty()) {
                    shared = new JavacFrontend(JavacPlatform.shared(), ToolProvider::getSystemJavaCompiler, bundled);
                }
            }
            return shared;
        }
    }

    /** A frontend over an explicit platform, compiler and bundled set (tests). */
    public JavacFrontend(JavacPlatform platform, Supplier<JavaCompiler> compilerSupplier, BundledSources bundled) {
        this.platform = platform;
        this.compilerSupplier = compilerSupplier;
        this.bundled = bundled;
    }

    public JavacPlatform platform() {
        return platform;
    }

    public BundledSources bundled() {
        return bundled;
    }

    /** The cached result for {@code key} in this environment, else {@code analysis}'s, cached. */
    JavacResult cached(String key, Supplier<JavacResult> analysis) {
        synchronized (cache) {
            JavacResult hit = cache.get(key);
            if (hit != null) return hit;
        }
        JavacResult result = analysis.get();
        synchronized (cache) {
            cache.put(key, result);
        }
        return result;
    }

    /** The host's Java runtime version, which is javac's on a JVM host (D3: recorded with the result). */
    public static String javacVersion() {
        return Runtime.version().toString();
    }

    /**
     * Analyzes {@code roots} with {@code pool}, then the bundled sources, on the source path (D8): javac attributes
     * the roots and the pool units they reference, through any of their top-level declarations. Precedence is per
     * qualified name ({@link Precedence}): a root's name wins over the pool's, and a name two pool units declare is
     * listed once; every other declaration stays visible. javac cannot read part of a unit, so a compilation that
     * reads a unit with a contested name fails closed with {@code duplicate class}, naming it.
     */
    public JavacResult analyze(List<Unit> roots, List<Unit> pool) {
        return analyze(roots, pool, null, true);
    }

    /**
     * For the build-time platform derivation only: {@link #analyze(List, List)} without the ownership check (it
     * attributes JuLC's own ledger sources), reporting each class file javac opens to {@code opened}.
     */
    public JavacResult observe(List<Unit> roots, List<Unit> pool, Consumer<String> opened) {
        return analyze(roots, pool, opened, false);
    }

    private JavacResult analyze(List<Unit> roots, List<Unit> pool, Consumer<String> opened, boolean checkOwnership) {
        JavaCompiler javac = compiler();
        if (javac == null) return supported();
        if (roots.isEmpty()) return JavacResult.accepted(platform);
        var offered = new ArrayList<Unit>(roots);
        offered.addAll(pool);
        for (String text : bundled.texts().values()) offered.add(Unit.of(text));
        var all = files(javac, offered);
        var rootFiles = all.subList(0, roots.size());
        // A pool unit with a root's text, or an earlier pool unit's, is that unit (a host may offer it twice).
        var texts = new HashSet<String>();
        for (var root : rootFiles) texts.add(JavacPlatform.identity(root.text()));
        var poolFiles = new ArrayList<JavacSourceFile>();
        for (var file : all.subList(roots.size(), all.size())) {
            if (texts.add(JavacPlatform.identity(file.text()))) poolFiles.add(file);
        }
        var precedence = Precedence.of(rootFiles, poolFiles);

        if (checkOwnership) {
            var owned = Ownership.check(platform, bundled, rootFiles, poolFiles);
            if (!owned.isEmpty()) return JavacResult.rejected(owned, platform);
        }

        var fileManager = new EmbeddedFileManager(platform, precedence.listed(), opened);
        var collector = new DiagnosticCollector<JavaFileObject>();
        var task = (JavacTask) javac.getTask(null, fileManager, collector, OPTIONS, null, rootFiles);
        task.setLocale(Locale.ENGLISH);
        // Every parsed unit, roots and the pool units javac reads, for the hints' context (D5).
        var parsed = new HashMap<URI, CompilationUnitTree>();
        task.addTaskListener(new TaskListener() {
            @Override
            public void finished(TaskEvent e) {
                if (e.getKind() == TaskEvent.Kind.PARSE && e.getSourceFile() != null) {
                    parsed.putIfAbsent(e.getSourceFile().toUri(), e.getCompilationUnit());
                }
            }
        });
        try {
            task.analyze();
        } catch (IOException e) {
            throw new IllegalStateException("javac could not read an in-memory source", e);
        }
        var byUri = new HashMap<URI, JavacSourceFile>();
        for (var file : rootFiles) byUri.put(file.toUri(), file);
        for (var file : poolFiles) byUri.putIfAbsent(file.toUri(), file);
        var diagnostics = new ArrayList<JavacDiagnostic>();
        var contexts = new HashMap<URI, JavacHints.UnitContext>();
        for (Diagnostic<? extends JavaFileObject> d : collector.getDiagnostics()) {
            if (d.getKind() != Diagnostic.Kind.ERROR) continue;
            URI uri = d.getSource() == null ? null : d.getSource().toUri();
            var context = uri == null || !parsed.containsKey(uri) ? JavacHints.UnitContext.NONE
                    : contexts.computeIfAbsent(uri, u -> JavacHints.UnitContext.of(parsed.get(u),
                            Trees.instance(task).getSourcePositions()));
            diagnostics.add(JavacHints.diagnostic(d, uri == null ? null : byUri.get(uri), context));
        }
        diagnostics.addAll(precedence.conflicts(parsed.keySet(), diagnostics));
        return diagnostics.isEmpty() ? JavacResult.accepted(platform) : JavacResult.rejected(diagnostics, platform);
    }

    /**
     * A task with no compilation units over the same file manager (D8's empty provider), exposing only its
     * {@code Elements} and {@code Types}.
     */
    public JavacMetadata metadata() {
        JavaCompiler javac = compiler();
        if (javac == null) throw new IllegalStateException(unsupported + " (check supported() first)");
        var task = (JavacTask) javac.getTask(null, new EmbeddedFileManager(platform, List.of(), null),
                diagnostic -> { }, OPTIONS, null, List.of());
        task.setLocale(Locale.ENGLISH);
        return new JavacMetadata(task.getElements(), task.getTypes());
    }

    /** Accepted when the host's javac is supported (D3); otherwise rejected with {@code JULC0089}. */
    public JavacResult supported() {
        if (compiler() != null) return JavacResult.accepted(platform);
        return JavacResult.rejected(List.of(JavacDiagnostic.of(DiagnosticCodes.JAVAC_UNSUPPORTED, null,
                unsupported, null, 0, 0, -1, -1, null)), platform);
    }

    /** The host's javac when it is supported, else null with {@link #unsupported} set (D3, I6). */
    private JavaCompiler compiler() {
        JavaCompiler javac = compiler;
        if (javac != null || unsupported != null) return javac;
        synchronized (this) {
            if (compiler != null || unsupported != null) return compiler;
            JavaCompiler found;
            try {
                found = compilerSupplier.get();
            } catch (LinkageError e) {
                found = null;
            }
            if (found == null) {
                unsupported = DiagnosticCodes.JAVAC_UNSUPPORTED.format(javacVersion(),
                        "this runtime has no Java compiler (module jdk.compiler)");
                return null;
            }
            String highest = found.getSourceVersions().stream().max(Enum::compareTo).map(SourceVersion::name)
                    .orElse("none");
            if (!SUPPORTED_RELEASE.equals(highest)) {
                unsupported = DiagnosticCodes.JAVAC_UNSUPPORTED.format(javacVersion(),
                        "its highest source version is " + highest + ", and JuLC supports javac feature release 25");
                return null;
            }
            compiler = found;
            return found;
        }
    }

    /** The units' file objects, each with a URI of its own (a repeated name gets a distinct URI authority). */
    private List<JavacSourceFile> files(JavaCompiler javac, List<Unit> units) {
        var infos = JavacUnits.infos(javac, platform, units.stream().map(Unit::text).toList());
        var files = new ArrayList<JavacSourceFile>(units.size());
        var uris = new HashSet<URI>();
        for (int i = 0; i < units.size(); i++) {
            Unit unit = units.get(i);
            JavacSourceFile file = unit.path() == null ? JavacSourceFile.ofText(unit.text(), infos.get(i), 0)
                    : JavacSourceFile.ofPath(unit.path(), unit.text(), infos.get(i));
            for (int copy = 1; !uris.add(file.toUri()); copy++) {
                file = JavacSourceFile.ofText(unit.text(), infos.get(i), copy);
            }
            files.add(file);
        }
        return files;
    }

    /**
     * The bundled sources (D3): {@code julc-stdlib}'s manifest, qualified name -> SHA-256 of the text, and the texts
     * shipped with it. Every analysis offers the texts at the end of its pool, after the host's: the stdlib lookup
     * resolves some {@code ContextsLib}, {@code ListsLib} and {@code MathLib} methods without their sources
     * ({@code StdlibRegistry}), so a compilation may use a bundled library its host did not pass. javac reads only
     * the ones a unit uses.
     */
    public record BundledSources(Map<String, String> digests, Map<String, String> texts) {
        public static final String RESOURCE = "META-INF/julc/bundled-sources.sha256";
        private static final String SOURCES = "META-INF/plutus-sources/";

        public BundledSources {
            digests = Map.copyOf(digests);
            texts = Collections.unmodifiableMap(new LinkedHashMap<>(texts));
        }

        public static BundledSources none() {
            return new BundledSources(Map.of(), Map.of());
        }

        /** The bundled set of the given texts, by qualified name (the build-time derivation). */
        public static BundledSources of(Map<String, String> texts) {
            var digests = new TreeMap<String, String>();
            texts.forEach((fqcn, text) -> digests.put(fqcn, JavacPlatform.sha256(text.getBytes(StandardCharsets.UTF_8))));
            return new BundledSources(digests, texts);
        }

        /** A class of julc-stdlib: where it is visible, the manifest must be too. */
        static final String STDLIB_MARKER = "org/julclang/stdlib/Builtins.class";

        /**
         * The first manifest on this class's loader, else on the thread context loader; none when neither loader
         * has julc-stdlib (the compiler alone). A loader that has julc-stdlib's classes but not its manifest, or a
         * manifest whose sources are missing or differ, is {@link BrokenJulcBuildException}.
         */
        public static BundledSources load() {
            return load(JavacFrontend.class.getClassLoader(), Thread.currentThread().getContextClassLoader());
        }

        static BundledSources load(ClassLoader... loaders) {
            for (ClassLoader loader : loaders) {
                if (loader == null) continue;
                try (var in = loader.getResourceAsStream(RESOURCE)) {
                    if (in != null) return parse(loader, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                if (loader.getResource(STDLIB_MARKER) != null) {
                    throw new BrokenJulcBuildException("julc-stdlib is on the class path without " + RESOURCE);
                }
            }
            return none();
        }

        /** Lines {@code <sha256> <path>}, the path relative to {@code META-INF/plutus-sources/}. */
        static BundledSources parse(ClassLoader loader, String manifest) throws IOException {
            var digests = new TreeMap<String, String>();
            var texts = new LinkedHashMap<String, String>();
            for (String line : manifest.split("\n")) {
                line = line.strip();
                if (line.isEmpty()) continue;
                int space = line.indexOf(' ');
                String digest = line.substring(0, space);
                String path = line.substring(space + 1).strip();
                String fqcn = path.substring(0, path.length() - ".java".length()).replace('/', '.');
                digests.put(fqcn, digest);
                try (var in = loader.getResourceAsStream(SOURCES + path)) {
                    if (in == null) throw new BrokenJulcBuildException("the bundled source " + path + " is missing");
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    if (!digest.equals(JavacPlatform.sha256(text.getBytes(StandardCharsets.UTF_8)))) {
                        throw new BrokenJulcBuildException("the bundled source " + path + " differs from its digest");
                    }
                    texts.put(fqcn, text);
                }
            }
            if (digests.isEmpty() && loader.getResource(STDLIB_MARKER) != null) {
                throw new BrokenJulcBuildException("julc-stdlib's " + RESOURCE + " lists no bundled source");
            }
            return new BundledSources(digests, texts);
        }

        public Set<String> packages() {
            var packages = new HashSet<String>();
            for (String fqcn : digests.keySet()) {
                int dot = fqcn.lastIndexOf('.');
                packages.add(dot < 0 ? "" : fqcn.substring(0, dot));
            }
            return packages;
        }
    }
}
