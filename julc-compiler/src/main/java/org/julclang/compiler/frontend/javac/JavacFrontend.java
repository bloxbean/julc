package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.Trees;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.frontend.javac.subset.SpelledTypes;
import org.julclang.compiler.resolve.TypeRegistry;

import javax.lang.model.SourceVersion;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
 * {@link #analyze} runs, in order: the supported-javac check, of the feature release and of the options' effect
 * ({@code JULC0089}), the ownership check by provenance
 * ({@code JULC0081}), then javac's {@code parse()} and {@code analyze()}, whose errors become {@code JULC0085}
 * (D5). Diagnostics follow javac's emission order, which depends only on the inputs (I4).
 */
public final class JavacFrontend {

    /**
     * javac's options (D3). No {@code --release}: the platform blob is the platform. {@code -XDallowStringFolding=false}
     * keeps a {@code +} of string literals in the tree as written, so that lowering decides what it means; the parser
     * would otherwise join the literals and no tree would show it. It is a hidden option, so {@link #compiler()} checks
     * that this javac honours it.
     */
    public static final List<String> OPTIONS = List.of("-proc:none", "-source", "21", "-implicit:none", "-Xlint:none",
            "-XDallowStringFolding=false");

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

    /**
     * Where a pool unit comes from (D8: roots, then same-project sources, then JAR sources). A host that merges a
     * class-path pool with project sources tags the JAR ones; every other unit is a project unit, so untagged units
     * are of equal standing.
     */
    public enum Origin { PROJECT, JAR }

    /** A source given to javac: its text, for the path entry points its file, and its origin. */
    public record Unit(String text, Path path, Origin origin) {
        public Unit {
            origin = origin == null ? Origin.PROJECT : origin;
        }

        public Unit(String text, Path path) {
            this(text, path, Origin.PROJECT);
        }

        public static Unit of(String text) {
            return new Unit(text, null);
        }

        public static Unit jar(String text) {
            return new Unit(text, null, Origin.JAR);
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

    /** The first class or interface, at any depth, that {@code text} declares, or null. */
    String firstClassOrInterface(String text) {
        return JavacUnits.infos(compiler(), platform, List.of(text)).getFirst().firstClassOrInterface();
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
     * the roots and the pool units they reference, through any of their top-level declarations. Precedence selects
     * whole units ({@link Precedence}): roots, then project units, then JAR units; a shadowed unit is not read, and
     * if javac then rejects, a note names it and the declaration that takes precedence; two units of equal standing
     * declaring one name are rejected before javac runs. The result says which pool units were left out
     * ({@link JavacResult#effective}).
     */
    public JavacResult analyze(List<Unit> roots, List<Unit> pool) {
        return analyze(roots, pool, null, true, null).result();
    }

    /** {@link #analyze(List, List)}, also reporting javac's task events to {@code events} (tests). */
    JavacResult analyze(List<Unit> roots, List<Unit> pool, Consumer<TaskEvent> events) {
        return analyze(roots, pool, null, true, events).result();
    }

    /**
     * An analysis with its attributed trees: the result, the roots' trees in order (empty when javac did not run), the
     * pool units javac read, in qualified-name order (I4), and the task's {@link Trees}, {@link Elements} and
     * {@link Types}. For the javac frontend, and for JuLC's own tools that read javac's types, such as the REPL
     * wrapper (D9). {@code offered} is what the reference compiles as libraries, of what javac read: each unit the host
     * offered in its pool, in the host's order, a root whose text the host offered there too included, and the bundled
     * sources it did not offer left out (D8).
     */
    record Attribution(JavacResult result, List<CompilationUnitTree> roots, List<CompilationUnitTree> libraries,
                       List<CompilationUnitTree> offered, Map<CompilationUnitTree, String> names, Trees trees,
                       Elements elements, Types types) {

        static Attribution none(JavacResult result) {
            return new Attribution(result, List.of(), List.of(), List.of(), Map.of(), null, null, null);
        }

        /** {@link #offered}, in qualified-name order (I4): by each unit's public type's name, or its first type's. */
        List<CompilationUnitTree> offeredByName() {
            var byName = new ArrayList<>(offered);
            byName.sort(Comparator.comparing(names::get));
            return List.copyOf(byName);
        }

        /** This attribution, with its roots as what is compiled as libraries: a library provider's units (D8). */
        Attribution rootsAsLibraries() {
            return new Attribution(result, roots, libraries, roots, names, trees, elements, types);
        }
    }

    /** {@link #analyze(List, List)}, keeping the attributed trees. */
    Attribution attribute(List<Unit> roots, List<Unit> pool) {
        return analyze(roots, pool, null, true, null);
    }

    /** {@link #analyze(List, List)}, then, on what javac accepted, the subset's rules ({@link SubsetRules}). */
    JavacResult analyzeWithRules(List<Unit> roots, List<Unit> pool, JavacGate.Compiled compiled) {
        var attribution = attribute(roots, pool);
        if (!attribution.result().isAccepted() || attribution.trees() == null) return attribution.result();
        try {
            var facts = JavacFacts.of(attribution, new TypeRegistry());
            SubsetRules.checkSpellings(facts, attribution.roots(), attribution.libraries(), compiled, reading(roots, pool, compiled, attribution.result().dropped()));
            SubsetRules.check(facts, attribution.roots(), attribution.libraries(), compiled);
            return attribution.result().withWarnings(SubsetRules.warnings(facts, attribution.roots()));
        } catch (CompilerException e) {
            var rejected = e.diagnostics().stream().map(diagnostic -> new JavacDiagnostic(diagnostic.code(), null,
                    diagnostic.message(), diagnostic.fileName(), diagnostic.line(), diagnostic.column(), -1, -1,
                    diagnostic.suggestion())).toList();
            return new JavacResult(rejected, javacVersion(), platform.platformDigest(), platform.stubsDigest(),
                    attribution.result().dropped());
        }
    }

    /** What the JavaParser frontend reads in this environment, for these units: every one it compiles, read by javac or not. */
    private SpelledTypes.Reading reading(List<Unit> roots, List<Unit> pool, JavacGate.Compiled compiled, Set<String> dropped) {
        var rootTexts = new LinkedHashSet<String>();
        roots.forEach(unit -> rootTexts.add(unit.text()));
        var libraries = new LinkedHashSet<>(bundled.texts().values());
        pool.forEach(unit -> libraries.add(unit.text()));
        libraries.removeAll(rootTexts);
        libraries.removeIf(text -> dropped.contains(JavacPlatform.identity(text)));
        if (compiled == JavacGate.Compiled.LIBRARY) {
            libraries.addAll(rootTexts);
            rootTexts.clear();
        }
        var libraryInfos = JavacUnits.infos(compiler(), platform, List.copyOf(libraries));
        var rootInfos = JavacUnits.infos(compiler(), platform, List.copyOf(rootTexts));
        var registered = new HashSet<String>();
        libraryInfos.forEach(info -> registered.addAll(info.classes()));
        var packages = new HashSet<String>();
        var unnamed = new HashSet<String>();
        var offered = new HashSet<String>();
        var repeated = new HashSet<String>();
        for (var infos : List.of(libraryInfos, rootInfos)) {
            for (var info : infos) {
                registered.addAll(info.dataTypes());
                packages.add(firstName(info.packageName()));
                if (info.packageName().isEmpty()) unnamed.addAll(info.topLevelNames());
                for (var names : List.of(info.dataTypes(), info.classes())) {
                    for (String name : names) {
                        if (!offered.add(name)) repeated.add(name);
                    }
                }
            }
        }
        var identities = new HashSet<>(platform.stubIdentities());
        identities.addAll(bundled.digests().keySet());
        return SpelledTypes.Reading.of(identities, registered, offered, repeated, packages, unnamed);
    }

    private static String firstName(String packageName) {
        int dot = packageName.indexOf('.');
        return dot < 0 ? packageName : packageName.substring(0, dot);
    }

    /**
     * For the build-time platform derivation only: {@link #analyze(List, List)} without the ownership check (it
     * attributes JuLC's own ledger sources), reporting each class file javac opens to {@code opened}.
     */
    public JavacResult observe(List<Unit> roots, List<Unit> pool, Consumer<String> opened) {
        return analyze(roots, pool, opened, false, null).result();
    }

    private Attribution analyze(List<Unit> roots, List<Unit> pool, Consumer<String> opened, boolean checkOwnership,
                                Consumer<TaskEvent> events) {
        JavaCompiler javac = compiler();
        if (javac == null) return Attribution.none(supported());
        if (roots.isEmpty()) return Attribution.none(JavacResult.accepted(platform));
        var offered = new ArrayList<Unit>(roots);
        offered.addAll(pool);
        for (String text : bundled.texts().values()) offered.add(Unit.jar(text));
        var all = files(javac, offered);
        var rootFiles = all.subList(0, roots.size());
        // A pool unit with a root's text, or an earlier pool unit's, is that unit (a host may offer it twice); it is
        // a project unit if any of its offers is.
        var texts = new HashSet<String>();
        for (var root : rootFiles) texts.add(JavacPlatform.identity(root.text()));
        var poolFiles = new ArrayList<JavacSourceFile>();
        var origins = new ArrayList<Origin>();
        var positions = new HashMap<String, Integer>();
        // The host's pool in its order, by text: the bundled sources come after it, and are not the host's.
        var hostOrder = new LinkedHashSet<String>();
        for (int i = roots.size(); i < all.size(); i++) {
            var file = all.get(i);
            String identity = JavacPlatform.identity(file.text());
            Origin origin = offered.get(i).origin();
            if (i < roots.size() + pool.size()) hostOrder.add(identity);
            if (texts.add(identity)) {
                positions.put(identity, poolFiles.size());
                poolFiles.add(file);
                origins.add(origin);
            } else if (origin == Origin.PROJECT && positions.containsKey(identity)) {
                origins.set(positions.get(identity), Origin.PROJECT);
            }
        }
        var precedence = Precedence.of(rootFiles, poolFiles, origins);

        if (checkOwnership) {
            var owned = Ownership.check(platform, bundled, rootFiles, poolFiles);
            if (!owned.isEmpty()) return Attribution.none(JavacResult.rejected(owned, platform));
        }
        if (!precedence.duplicates().isEmpty()) {
            return Attribution.none(JavacResult.rejected(precedence.duplicates(), platform));
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
                if (events != null) events.accept(e);
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
        // javac's diagnostics stay as they are; a shadowed unit javac did not read is named in a note (D8).
        if (!diagnostics.isEmpty()) diagnostics.addAll(precedence.notes());
        var result = diagnostics.isEmpty() ? JavacResult.accepted(platform) : JavacResult.rejected(diagnostics, platform);
        var rootTrees = rootFiles.stream().map(f -> parsed.get(f.toUri())).filter(t -> t != null).toList();
        var rootUris = new HashSet<URI>();
        for (var file : rootFiles) rootUris.add(file.toUri());
        var libraries = new TreeMap<String, CompilationUnitTree>();
        for (var file : precedence.listed()) {
            var tree = parsed.get(file.toUri());
            if (tree != null && !rootUris.contains(file.toUri())) libraries.put(file.primaryName(), tree);
        }
        var read = new HashMap<String, CompilationUnitTree>();
        var names = new IdentityHashMap<CompilationUnitTree, String>();
        for (var tree : rootTrees) {
            var file = byUri.get(tree.getSourceFile().toUri());
            read.put(JavacPlatform.identity(file.text()), tree);
            names.put(tree, file.primaryName());
        }
        libraries.forEach((name, tree) -> {
            read.put(JavacPlatform.identity(byUri.get(tree.getSourceFile().toUri()).text()), tree);
            names.put(tree, name);
        });
        var offeredRead = hostOrder.stream().map(read::get).filter(tree -> tree != null).toList();
        return new Attribution(result.withDropped(precedence.droppedIdentities()), rootTrees, List.copyOf(libraries.values()),
                offeredRead, Collections.unmodifiableMap(names), Trees.instance(task), task.getElements(), task.getTypes());
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

    /**
     * The qualified names of every top-level type each text declares, read by javac's parser as the gate reads them;
     * empty when the host's javac is not supported (the gate then rejects with {@code JULC0089}).
     */
    public Optional<List<List<String>>> declaredNames(List<String> texts) {
        JavaCompiler javac = compiler();
        if (javac == null) return Optional.empty();
        return Optional.of(files(javac, texts.stream().map(Unit::of).toList()).stream()
                .map(JavacSourceFile::topLevelNames).toList());
    }

    /**
     * {@code text} read by javac's parser alone, with {@link #OPTIONS} and no attribution, for JuLC's tools that read a
     * source's shape ({@link JavacSyntax}); empty when the host's javac is not supported ({@link #supported()} says why).
     */
    public Optional<JavacSyntax> parse(String text) {
        JavaCompiler javac = compiler();
        if (javac == null) return Optional.empty();
        var file = new SimpleJavaFileObject(URI.create("string:///Source.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return text;
            }
        };
        var errors = new ArrayList<Diagnostic<? extends JavaFileObject>>();
        DiagnosticListener<JavaFileObject> listener = d -> {
            if (d.getKind() == Diagnostic.Kind.ERROR) errors.add(d);
        };
        var task = (JavacTask) javac.getTask(null, new EmbeddedFileManager(platform, List.of(), null), listener,
                OPTIONS, null, List.of(file));
        task.setLocale(Locale.ENGLISH);
        try {
            CompilationUnitTree unit = task.parse().iterator().next();
            return Optional.of(new JavacSyntax(text, unit, Trees.instance(task).getSourcePositions(), errors));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
            String folding = foldingProblem(found);
            if (folding != null) {
                unsupported = DiagnosticCodes.JAVAC_UNSUPPORTED.format(javacVersion(), folding);
                return null;
            }
            compiler = found;
            return found;
        }
    }

    /**
     * Whether {@code javac} keeps a {@code +} of string literals as written, read from a parse with {@link #OPTIONS}:
     * null when it does, otherwise why it is not supported. Nothing else would notice a javac that stopped honouring the
     * hidden option, since the joined literal is a valid tree.
     */
    private String foldingProblem(JavaCompiler javac) {
        var probe = new SimpleJavaFileObject(URI.create("string:///FoldingProbe.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return "class FoldingProbe { String s = \"a\" + \"b\"; }";
            }
        };
        try {
            var task = (JavacTask) javac.getTask(null, new EmbeddedFileManager(platform, List.of(), null),
                    diagnostic -> { }, OPTIONS, null, List.of(probe));
            var unit = task.parse().iterator().next();
            var type = (ClassTree) unit.getTypeDecls().getFirst();
            var field = (VariableTree) type.getMembers().getFirst();
            var read = field.getInitializer().getKind();
            if (read == Tree.Kind.PLUS) return null;
            return "with " + OPTIONS.getLast() + " it reads a + of string literals as " + read
                    + ", and JuLC needs the + as written";
        } catch (IOException | RuntimeException e) {
            return "it could not parse with " + OPTIONS + ": " + e;
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
