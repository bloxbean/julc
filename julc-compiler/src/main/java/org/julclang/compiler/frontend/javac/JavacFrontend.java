package org.julclang.compiler.frontend.javac;

import com.sun.source.util.JavacTask;
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

    /** A source given to javac: its text and, for the path entry points, its file. */
    public record Unit(String text, Path path) {
        public static Unit of(String text) {
            return new Unit(text, null);
        }
    }

    /** The host's javac and this build's platform; the platform is parsed once per process. */
    public static JavacFrontend shared() {
        return Shared.INSTANCE;
    }

    private static final class Shared {
        static final JavacFrontend INSTANCE = new JavacFrontend(JavacPlatform.shared(),
                ToolProvider::getSystemJavaCompiler, BundledSources.load());
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

    /** The host's Java runtime version, which is javac's on a JVM host (D3: recorded with the result). */
    public static String javacVersion() {
        return Runtime.version().toString();
    }

    /**
     * Analyzes {@code roots} with {@code pool}, then the bundled sources, on the source path (D8): javac attributes
     * the roots and the pool units they reference. A root's qualified name wins over the pool's; within the pool the
     * first unit of a name wins, so javac never sees a name twice.
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
        var rootFiles = files(javac, roots);
        var poolFiles = new ArrayList<JavacSourceFile>();
        var names = new HashSet<String>();
        for (var root : rootFiles) names.addAll(root.topLevelNames());
        var offered = new ArrayList<>(pool);
        for (String text : bundled.texts().values()) offered.add(Unit.of(text));
        for (var file : files(javac, offered)) {
            if (names.add(file.binaryName())) poolFiles.add(file);
        }

        if (checkOwnership) {
            var owned = Ownership.check(platform, bundled, rootFiles, poolFiles);
            if (!owned.isEmpty()) return JavacResult.rejected(owned, platform);
        }

        var fileManager = new EmbeddedFileManager(platform, poolFiles, opened);
        var collector = new DiagnosticCollector<JavaFileObject>();
        var task = (JavacTask) javac.getTask(null, fileManager, collector, OPTIONS, null, rootFiles);
        task.setLocale(Locale.ENGLISH);
        try {
            task.analyze();
        } catch (IOException e) {
            throw new IllegalStateException("javac could not read an in-memory source", e);
        }
        var byUri = new HashMap<URI, JavacSourceFile>();
        for (var file : rootFiles) byUri.put(file.toUri(), file);
        for (var file : poolFiles) byUri.putIfAbsent(file.toUri(), file);
        var diagnostics = new ArrayList<JavacDiagnostic>();
        for (Diagnostic<? extends JavaFileObject> d : collector.getDiagnostics()) {
            if (d.getKind() != Diagnostic.Kind.ERROR) continue;
            diagnostics.add(JavacHints.diagnostic(d, d.getSource() == null ? null : byUri.get(d.getSource().toUri())));
        }
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

    private List<JavacSourceFile> files(JavaCompiler javac, List<Unit> units) {
        var infos = JavacUnits.infos(javac, platform, units.stream().map(Unit::text).toList());
        var files = new ArrayList<JavacSourceFile>(units.size());
        for (int i = 0; i < units.size(); i++) {
            Unit unit = units.get(i);
            files.add(unit.path() == null ? JavacSourceFile.ofText(unit.text(), infos.get(i))
                    : JavacSourceFile.ofPath(unit.path(), unit.text(), infos.get(i)));
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

        /** The first manifest on this class's loader, else on the thread context loader; none is empty. */
        public static BundledSources load() {
            for (ClassLoader loader : new ClassLoader[]{JavacFrontend.class.getClassLoader(),
                    Thread.currentThread().getContextClassLoader()}) {
                if (loader == null) continue;
                try (var in = loader.getResourceAsStream(RESOURCE)) {
                    if (in != null) return load(loader, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return none();
        }

        /** Lines {@code <sha256> <path>}, the path relative to {@code META-INF/plutus-sources/}. */
        static BundledSources load(ClassLoader loader, String manifest) throws IOException {
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
                    if (in == null) continue;
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    if (digest.equals(JavacPlatform.sha256(text.getBytes(StandardCharsets.UTF_8)))) {
                        texts.put(fqcn, text);
                    }
                }
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
