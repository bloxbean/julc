package org.julclang.compiler.frontend.javac.tool;

import org.julclang.core.corpus.CorpusFiles;
import org.julclang.compiler.frontend.javac.JavacFrontend;
import org.julclang.compiler.frontend.javac.JavacPlatform;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModulePackagesAttribute;
import java.lang.constant.ModuleDesc;
import java.lang.constant.PackageDesc;
import java.lang.module.ModuleDescriptor;
import java.lang.reflect.AccessFlag;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * Build-time tool for ADR-068 D3's javac environment; it runs on the build JDK (25).
 * <pre>
 *   platform OUT.bin LIST           the java.base blob: the classes in LIST, signature-only, from this JDK's jrt:/
 *   stubs OUT.bin LEDGER INPUT...   the API stubs: every class of each jar or class directory, signature-only;
 *                                   LEDGER is the ledger's source root, for I11's check ({@link StubAttributes})
 *   derive STUBS.bin LIST MODE [--corpus DIR] SOURCES...
 *                                   attribute JuLC's in-repo sources against the whole exported java.base, record
 *                                   the platform classes javac opens, close them under supertypes; MODE "write"
 *                                   writes LIST, "check" fails unless every derived class is in LIST
 * Signature-only means: no {@code Code} attribute and no private methods or fields, as {@code ct.sym}; every other
 * attribute is kept ({@code PermittedSubclasses}, {@code Record}, {@code Signature}, annotations, constant values;
 * ADR-068 I11). Blobs are written uncompressed and sorted, so they are deterministic for a given JDK and input.
 *   gate-corpus PLATFORM.bin STUBS.bin CORPUS OUT SOURCES...
 *                                   the M1 gate over a recorded corpus, with the shipped blobs and the bundled
 *                                   sources of SOURCES: rejections by javac key, and every case's outcome, under OUT;
 *                                   each case is also analyzed against the whole exported java.base, and a different
 *                                   diagnostic is reported (the derived platform must change nothing)
 * </pre>
 * {@code SOURCES} are directories: each one's {@code .java} files are attributed together as roots, with the
 * {@code @OnchainLibrary} sources of the previous directories on the source path; a corpus case (ADR-068 M0's
 * recording) is attributed as its entry point compiled it.
 */
public final class JavacPlatformTool {

    private JavacPlatformTool() {
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "platform" -> platform(Path.of(args[1]), Path.of(args[2]));
            case "stubs" -> stubs(Path.of(args[1]), Path.of(args[2]),
                    List.of(args).subList(3, args.length).stream().map(Path::of).toList());
            case "derive" -> derive(args);
            case "gate-corpus" -> gateCorpus(args);
            default -> throw new IllegalArgumentException("unknown command " + args[0]);
        }
    }

    static void platform(Path out, Path list) throws IOException {
        var entries = new TreeMap<String, byte[]>();
        FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
        for (String path : readList(list)) {
            if (path.endsWith("module-info.class")) continue;
            entries.put(path, strip(Files.readAllBytes(jrt.getPath("/modules/" + path))));
        }
        entries.put("java.base/module-info.class", moduleInfo(entries.keySet()));
        write(out, entries, "platform (java.base, " + Runtime.version() + ")");
    }

    /**
     * java.base's descriptor for the blob: {@code module java.base} exporting exactly the blob's packages, with
     * nothing else. The JDK's own module-info also records hashes of the other modules, its version and qualified
     * exports, which differ between JDK 25 builds while the API classes do not; javac needs none of them.
     */
    static byte[] moduleInfo(Set<String> paths) {
        var packages = new TreeSet<String>();
        for (String path : paths) {
            String rel = path.substring("java.base/".length());
            packages.add(rel.substring(0, rel.lastIndexOf('/')).replace('/', '.'));
        }
        var descs = packages.stream().map(PackageDesc::of).toList();
        return ClassFile.of().buildModule(ModuleAttribute.of(ModuleDesc.of("java.base"), module -> {
            for (PackageDesc pkg : descs) module.exports(pkg, List.of());
        }), cb -> cb.with(ModulePackagesAttribute.ofNames(descs)));
    }

    static void stubs(Path out, Path ledgerSources, List<Path> inputs) throws IOException {
        var originals = new TreeMap<String, byte[]>();
        var entries = new TreeMap<String, byte[]>();
        for (Path input : inputs) {
            for (var e : classFiles(input).entrySet()) {
                originals.put(e.getKey(), e.getValue());
                if (entries.put(e.getKey(), strip(e.getValue())) != null) {
                    throw new IllegalStateException("class " + e.getKey() + " is in two stub inputs");
                }
            }
        }
        var problems = StubAttributes.check(originals, entries, ledgerSources);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("the javac stubs fail ADR-068 I11's check:\n  " + String.join("\n  ", problems));
        }
        System.out.println("stubs: I11's attribute and order check passed for " + entries.size() + " classes");
        write(out, entries, "stubs");
    }

    private static void write(Path out, Map<String, byte[]> entries, String label) throws IOException {
        byte[] blob = JavacPlatform.write(entries);
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.write(out, blob);
        System.out.printf("%s: %s, %d class files, %d bytes, sha256 %s%n", out.getFileName(), label, entries.size(),
                blob.length, JavacPlatform.sha256(blob));
    }

    /** Every class file of a jar or class directory (no module-info, nothing under META-INF), by relative path. */
    private static Map<String, byte[]> classFiles(Path input) throws IOException {
        var result = new TreeMap<String, byte[]>();
        if (Files.isDirectory(input)) {
            try (Stream<Path> walk = Files.walk(input)) {
                for (Path p : walk.filter(Files::isRegularFile).toList()) {
                    String rel = input.relativize(p).toString().replace('\\', '/');
                    if (isApiClass(rel)) result.put(rel, Files.readAllBytes(p));
                }
            }
        } else {
            try (var zip = new ZipFile(input.toFile())) {
                for (var e : Collections.list(zip.entries())) {
                    if (!e.isDirectory() && isApiClass(e.getName())) {
                        result.put(e.getName(), zip.getInputStream(e).readAllBytes());
                    }
                }
            }
        }
        return result;
    }

    private static boolean isApiClass(String rel) {
        return rel.endsWith(".class") && !rel.startsWith("META-INF/") && !rel.endsWith("module-info.class");
    }

    /** Drops method bodies and private methods and fields; keeps every other element and attribute. */
    static byte[] strip(byte[] bytes) {
        ClassFile cf = ClassFile.of(ClassFile.ConstantPoolSharingOption.NEW_POOL);
        ClassModel cm = cf.parse(bytes);
        ClassTransform t = ClassTransform.transformingMethods(MethodTransform.dropping(e -> e instanceof CodeModel))
                .andThen(ClassTransform.dropping(e ->
                        (e instanceof MethodModel m && m.flags().has(AccessFlag.PRIVATE))
                                || (e instanceof FieldModel f && f.flags().has(AccessFlag.PRIVATE))));
        return cf.transformClass(cm, t);
    }

    private static List<String> readList(Path list) throws IOException {
        return Files.readAllLines(list).stream().map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
    }

    // --- derivation ---

    static void derive(String[] args) throws Exception {
        Path stubs = Path.of(args[1]);
        Path list = Path.of(args[2]);
        String mode = args[3];
        Path corpus = null;
        var sourceDirs = new ArrayList<Path>();
        for (int i = 4; i < args.length; i++) {
            if (args[i].equals("--corpus")) corpus = Path.of(args[++i]);
            else sourceDirs.add(Path.of(args[i]));
        }
        FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
        Map<String, byte[]> javaBase = exportedJavaBase(jrt);
        var all = new TreeMap<String, byte[]>();
        javaBase.forEach((path, bytes) -> all.put(path, path.endsWith("module-info.class") ? bytes : strip(bytes)));
        var groupUnits = new ArrayList<List<JavacFrontend.Unit>>();
        var bundled = new TreeMap<String, String>();
        readGroups(sourceDirs, groupUnits, bundled);
        // The gate's environment: the bundled stdlib libraries are in every pool (JavacFrontend.BundledSources).
        var frontend = new JavacFrontend(JavacPlatform.of(JavacPlatform.write(all), Files.readAllBytes(stubs)),
                ToolProvider::getSystemJavaCompiler, JavacFrontend.BundledSources.of(bundled));
        Set<String> opened = Collections.synchronizedSet(new TreeSet<>());
        int groups = 0;
        for (var units : groupUnits) {
            frontend.observe(units, List.of(), opened::add);
            groups++;
        }
        System.out.printf("bundled sources: %d%n", bundled.size());
        int cases = corpus == null ? 0 : observeCorpus(frontend, corpus, opened);
        writeOrCheck(list, mode, closeUnderSupertypes(opened, javaBase), opened.size(), groups, cases);
    }

    /** Each directory's sources, and the bundled ones among them (julc-stdlib's @OnchainLibrary sources). */
    private static void readGroups(List<Path> sourceDirs, List<List<JavacFrontend.Unit>> groupUnits,
                                   Map<String, String> bundled) throws IOException {
        for (Path dir : sourceDirs) {
            var units = new ArrayList<JavacFrontend.Unit>();
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : walk.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                    String text = Files.readString(p);
                    units.add(new JavacFrontend.Unit(text, p));
                    String path = p.toString().replace('\\', '/');
                    if (path.contains("julc-stdlib/") && text.contains("\n@OnchainLibrary")) {
                        String rel = path.substring(path.indexOf("org/julclang/"));
                        bundled.put(rel.substring(0, rel.length() - ".java".length()).replace('/', '.'), text);
                    }
                }
            }
            if (units.isEmpty()) throw new IllegalStateException("no sources under " + dir);
            groupUnits.add(units);
        }
    }

    private static void writeOrCheck(Path list, String mode, Set<String> derived, int opened, int groups, int cases)
            throws IOException {
        if (mode.equals("write")) {
            var text = new StringBuilder();
            text.append("# ADR-068 D3: the java.base classes javac opens while attributing JuLC's in-repo sources,\n");
            text.append("# closed under supertypes. Generated by :julc-compiler:deriveJavacPlatform; do not edit.\n");
            text.append("# JDK ").append(Runtime.version()).append("; ").append(groups).append(" source groups, ")
                    .append(cases).append(" corpus cases.\n");
            derived.forEach(path -> text.append(path).append('\n'));
            Files.writeString(list, text.toString());
            System.out.printf("%s: %d classes (%d opened)%n", list, derived.size(), opened);
        } else {
            var missing = new TreeSet<>(derived);
            missing.removeAll(new TreeSet<>(readList(list)));
            if (!missing.isEmpty()) {
                throw new IllegalStateException("The javac platform list " + list + " lacks classes that in-repo"
                        + " sources need; run ./gradlew :julc-compiler:deriveJavacPlatform and commit: " + missing);
            }
            System.out.printf("%s covers the %d classes derived from %d source groups%n", list, derived.size(),
                    groups);
        }
    }

    /** Attributes each corpus case as its entry point compiled it: JavaLibraryProvider's units are all roots. */
    private static int observeCorpus(JavacFrontend frontend, Path corpus, Set<String> opened) throws Exception {
        List<Path> cases;
        try (Stream<Path> list = Files.list(corpus.resolve("cases"))) {
            cases = list.sorted().toList();
        }
        var accepted = new AtomicInteger();
        var rejected = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 2));
        try {
            var futures = new ArrayList<Future<?>>();
            for (Path dir : cases) {
                futures.add(pool.submit(() -> {
                    var c = readCase(corpus, dir);
                    var result = frontend.observe(c.roots(), c.pool(), opened::add);
                    (result.isAccepted() ? accepted : rejected).incrementAndGet();
                    return null;
                }));
            }
            for (var f : futures) f.get();
        } finally {
            pool.shutdown();
        }
        System.out.printf("corpus %s: %d cases, javac accepts %d and rejects %d%n", corpus, cases.size(),
                accepted.get(), rejected.get());
        return cases.size();
    }

    /** A recorded case: its entry point's roots and pool, and the tests that compiled it. */
    record CorpusCase(String key, String entry, List<JavacFrontend.Unit> roots, List<JavacFrontend.Unit> pool,
                      List<String> origins) {
    }

    static CorpusCase readCase(Path corpus, Path dir) throws IOException {
        var main = new ArrayList<JavacFrontend.Unit>();
        var libs = new ArrayList<JavacFrontend.Unit>();
        String entry = "?";
        for (String line : Files.readAllLines(dir.resolve("case.txt"), StandardCharsets.UTF_8)) {
            if (line.startsWith("entry: ")) entry = line.substring(7);
            if (line.startsWith("source: ")) main.add(corpusUnit(corpus, line.substring(8)));
            if (line.startsWith("library: ")) libs.add(corpusUnit(corpus, line.substring(9)));
        }
        var origins = new ArrayList<String>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.sorted().toList()) {
                if (f.getFileName().toString().startsWith("origin-")) origins.add(Files.readString(f).strip());
            }
        }
        boolean provider = entry.equals("JavaLibraryProvider");
        return new CorpusCase(dir.getFileName().toString(), entry, provider ? libs : main,
                provider ? List.of() : libs, origins);
    }

    /** The gate over a recorded corpus (see the class comment). */
    static void gateCorpus(String[] args) throws Exception {
        var shipped = JavacPlatform.of(Files.readAllBytes(Path.of(args[1])), Files.readAllBytes(Path.of(args[2])));
        Path corpus = Path.of(args[3]);
        Path out = Path.of(args[4]);
        var bundled = new TreeMap<String, String>();
        readGroups(List.of(args).subList(5, args.length).stream().map(Path::of).toList(), new ArrayList<>(), bundled);
        var bundledSources = JavacFrontend.BundledSources.of(bundled);
        var gate = new JavacFrontend(shipped, ToolProvider::getSystemJavaCompiler, bundledSources);
        var all = new TreeMap<String, byte[]>();
        exportedJavaBase(FileSystems.getFileSystem(URI.create("jrt:/"))).forEach((path, bytes) ->
                all.put(path, path.endsWith("module-info.class") ? bytes : strip(bytes)));
        var full = new JavacFrontend(JavacPlatform.of(JavacPlatform.write(all), Files.readAllBytes(Path.of(args[2]))),
                ToolProvider::getSystemJavaCompiler, bundledSources);
        List<Path> dirs;
        try (Stream<Path> list = Files.list(corpus.resolve("cases"))) {
            dirs = list.sorted().toList();
        }
        var rows = Collections.synchronizedMap(new TreeMap<String, String>());
        var byKey = Collections.synchronizedMap(new TreeMap<String, int[]>());
        var mismatches = Collections.synchronizedList(new ArrayList<String>());
        var rejected = new AtomicInteger();
        var rejectedRoots = Collections.synchronizedSet(new TreeSet<String>());
        var rootsByKey = Collections.synchronizedMap(new TreeMap<String, Set<String>>());
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 2));
        try {
            var futures = new ArrayList<Future<?>>();
            for (Path dir : dirs) {
                futures.add(pool.submit(() -> {
                    var c = readCase(corpus, dir);
                    if (c.roots().isEmpty()) {
                        rows.put(c.key(), String.join("\t", c.key(), c.entry(), "metadata", "", "",
                                String.join(",", c.origins())));
                        return null;
                    }
                    var result = gate.analyze(c.roots(), c.pool());
                    var reference = full.analyze(c.roots(), c.pool());
                    if (!result.diagnostics().equals(reference.diagnostics())) {
                        mismatches.add(c.key() + " " + result.diagnostics() + " vs " + reference.diagnostics());
                    }
                    var keys = new TreeMap<String, Integer>();
                    for (var d : result.diagnostics()) keys.merge(d.key() == null ? d.code() : d.key(), 1, Integer::sum);
                    String rootDigest = CorpusFiles.sha256(c.roots().getFirst().text());
                    if (!result.isAccepted()) {
                        rejected.incrementAndGet();
                        rejectedRoots.add(rootDigest);
                    }
                    keys.forEach((k, n) -> {
                        synchronized (byKey) {
                            int[] counts = byKey.computeIfAbsent(k, x -> new int[2]);
                            counts[0]++;
                            counts[1] += n;
                            rootsByKey.computeIfAbsent(k, x -> new TreeSet<>()).add(rootDigest);
                        }
                    });
                    String first = result.isAccepted() ? "" : result.diagnostics().getFirst().fileName() + ":"
                            + result.diagnostics().getFirst().line() + ":" + result.diagnostics().getFirst().column()
                            + " " + result.diagnostics().getFirst().message().replace("\n", " | ");
                    rows.put(c.key(), String.join("\t", c.key(), c.entry(), result.isAccepted() ? "accepted"
                            : "rejected", String.join(",", keys.keySet()), first, String.join(",", c.origins())));
                    return null;
                }));
            }
            for (var f : futures) f.get();
        } finally {
            pool.shutdown();
        }
        Files.createDirectories(out);
        var tsv = new StringBuilder("case\tentry\toutcome\tkeys\tfirst diagnostic\torigins\n");
        rows.values().forEach(r -> tsv.append(r).append('\n'));
        Files.writeString(out.resolve("gate-corpus.tsv"), tsv.toString());
        var summary = new StringBuilder();
        summary.append("ADR-068 M1 javac gate over corpus ").append(corpus.getFileName()).append('\n');
        summary.append("javac ").append(Runtime.version()).append("; platform ").append(shipped.platformDigest())
                .append(" (").append(shipped.platformClassCount()).append(" classes); stubs ")
                .append(shipped.stubsDigest()).append(" (").append(shipped.stubClassCount()).append(" classes)\n");
        summary.append("cases: ").append(dirs.size()).append("; rejected: ").append(rejected.get())
                .append(" (distinct root sources: ").append(rejectedRoots.size()).append(")\n");
        summary.append("diagnostics differing from the whole exported java.base: ").append(mismatches.size())
                .append('\n');
        summary.append('\n').append(String.format("%-60s %8s %8s %12s%n", "javac key (or JuLC code)", "cases",
                "roots", "diagnostics"));
        byKey.forEach((k, v) -> summary.append(String.format("%-60s %8d %8d %12d%n", k, v[0],
                rootsByKey.get(k).size(), v[1])));
        Files.writeString(out.resolve("gate-corpus-summary.txt"), summary.toString());
        System.out.print(summary);
        mismatches.stream().limit(5).forEach(m -> System.out.println("MISMATCH " + m));
    }

    private static JavacFrontend.Unit corpusUnit(Path corpus, String ref) throws IOException {
        String digest = ref.split(" ", 2)[0];
        return JavacFrontend.Unit.of(CorpusFiles.text(Files.readAllBytes(corpus.resolve("sources")
                .resolve(digest + ".java"))));
    }

    /** module-info plus every class of java.base's unqualified exported packages, by module path. */
    private static Map<String, byte[]> exportedJavaBase(FileSystem jrt) throws IOException {
        ModuleDescriptor md = ModuleLayer.boot().findModule("java.base").orElseThrow().getDescriptor();
        Set<String> exported = md.exports().stream().filter(e -> !e.isQualified())
                .map(e -> e.source().replace('.', '/')).collect(Collectors.toSet());
        Path base = jrt.getPath("/modules/java.base");
        var result = new TreeMap<String, byte[]>();
        try (Stream<Path> walk = Files.walk(base)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".class")).toList()) {
                String rel = base.relativize(p).toString();
                int last = rel.lastIndexOf('/');
                String pkg = last < 0 ? "" : rel.substring(0, last);
                if (rel.equals("module-info.class") || exported.contains(pkg)) {
                    result.put("java.base/" + rel, Files.readAllBytes(p));
                }
            }
        }
        return result;
    }

    /** The opened java.base classes, module-info, and every java.base supertype of them, transitively. */
    private static Set<String> closeUnderSupertypes(Set<String> opened, Map<String, byte[]> javaBase) {
        var result = new TreeSet<String>();
        var queue = new ArrayDeque<String>();
        queue.add("java.base/module-info.class");
        for (String path : opened) if (path.startsWith("java.base/")) queue.add(path);
        while (!queue.isEmpty()) {
            String path = queue.poll();
            if (!result.add(path) || path.endsWith("module-info.class")) continue;
            ClassModel cm = ClassFile.of().parse(javaBase.get(path));
            cm.superclass().ifPresent(s -> enqueue(s.asInternalName(), javaBase, queue));
            cm.interfaces().forEach(i -> enqueue(i.asInternalName(), javaBase, queue));
        }
        return result;
    }

    private static void enqueue(String internalName, Map<String, byte[]> javaBase, ArrayDeque<String> queue) {
        String path = "java.base/" + internalName + ".class";
        if (javaBase.containsKey(path)) queue.add(path);
    }
}
