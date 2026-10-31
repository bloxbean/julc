package org.julclang.difftest;

import java.util.regex.Pattern;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LibrarySourceResolver;
import org.julclang.compiler.corpus.CorpusRecorder;
import org.julclang.core.corpus.CorpusFiles;
import org.julclang.difftest.gen.G0Enumeration;
import org.julclang.difftest.gen.G1;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * ADR-068 M0's corpus (D13): every case the ledger compiles, keyed by the digest of its {@code case.txt}
 * ({@link CorpusRecorder#caseText}), so a recorded case and a generated case with the same inputs are one case.
 * <p>
 * The parts, each named by the tag prefix it gives its cases:
 * <ul>
 *   <li>{@code recorded}: the corpus {@link CorpusRecorder} wrote during the julc-compiler (with {@code pairCaseTest}),
 *       julc-stdlib, julc-testkit, julc-difftest and julc-examples test runs; {@code external} and {@code elci}: the
 *       corpora recorded from the external julc-examples tests and from Elci's smoke and custom library;</li>
 *   <li>{@code stdlib}: each bundled {@code @OnchainLibrary} source (the {@code META-INF/plutus-sources} index), as a
 *       {@code JavaLibraryProvider} case, and all of them as one;</li>
 *   <li>{@code ledger}: the ledger type metadata ({@code LedgerTypeProvider}, an empty {@code JavaLibraryProvider}),
 *       and one row per ledger source file for the types it declares;</li>
 *   <li>{@code blaster}, {@code c7}: the validators of {@code verification/blaster/fixtures} and
 *       {@code verification/c7/fixtures}, compiled as {@code julc build} compiles one source;</li>
 *   <li>{@code diffcase}: every saved {@code .diffcase} ({@code regressions/} and {@code known-divergences/});</li>
 *   <li>{@code g0}: the G0 exhaustive corpus; {@code g1}: the G1 fixed-seed corpus ({@link Adr068G1Capture}).</li>
 * </ul>
 */
final class Adr068Corpus {

    /** The recorded identity of {@code StdlibRegistry.defaultRegistry()} ({@code FileCorpusRecorder.identity}). */
    static final String STDLIB_LOOKUP = "default:org.julclang.stdlib.StdlibRegistry";

    /** One source: its digest, its text, and its file name for the path entry points. */
    record Source(String digest, String text, String fileName) {
        static Source of(String text, String fileName) {
            return new Source(CorpusFiles.sha256(text), text, fileName);
        }

        String ref() {
            return fileName == null ? digest : digest + " " + fileName;
        }
    }

    /** A runnable input of a case: what the harness runs ({@link DiffCase}) under a corpus tag. */
    record Run(String tag, DiffCase diffCase) {
    }

    /** One case: what to compile and how, and where it came from. */
    static final class Case {
        final String key;
        final String entry;
        final String method;
        final String lookup;
        final String target;
        final String costProfile;
        final boolean sourceMap;
        final List<String> disabledRules;
        final Source main;
        final List<Source> libraries;
        final TreeSet<String> tags = new TreeSet<>();
        final TreeSet<String> origins = new TreeSet<>();
        final TreeSet<String> recordedLevels = new TreeSet<>();
        final List<Run> runs = new ArrayList<>();
        /** The specialization requests made of this provider case, by digest of their text ({@code RequestText}). */
        final TreeMap<String, String> instantiations = new TreeMap<>();

        Case(String key, String entry, String method, String lookup, String target, String costProfile,
             boolean sourceMap, List<String> disabledRules, Source main, List<Source> libraries) {
            this.key = key;
            this.entry = entry;
            this.method = method;
            this.lookup = lookup;
            this.target = target;
            this.costProfile = costProfile;
            this.sourceMap = sourceMap;
            this.disabledRules = List.copyOf(disabledRules);
            this.main = main;
            this.libraries = List.copyOf(libraries);
        }

        /** The tag prefixes: {@code recorded}, {@code diffcase}, … */
        TreeSet<String> parts() {
            var parts = new TreeSet<String>();
            for (String tag : tags) parts.add(tag.contains(":") ? tag.substring(0, tag.indexOf(':')) : tag);
            return parts;
        }
    }

    final Map<String, Case> cases = new TreeMap<>();
    /** Recorded cases dropped because only a nondeterministic test compiled them, by origin. */
    final Map<String, Integer> excluded = new TreeMap<>();

    /**
     * Tests whose compiled sources differ from run to run (jqwik properties without a fixed seed): their cases are
     * not part of the frozen corpus unless another origin also compiled them. Found by recording twice
     * (adr/evidence/068-javac-frontend-baseline.md).
     */
    static final List<String> NONDETERMINISTIC_ORIGINS = List.of(
            "org.julclang.compiler.IntegerLiteralTest#generatedLiteralsHaveJavacsValue");

    private final Path repository;

    Adr068Corpus(Path repository) {
        this.repository = repository;
    }

    // --- Recorded corpus ---

    /** Adds a recorded corpus under {@code tag} ({@code recorded}, {@code external}, {@code elci}). */
    void addRecorded(Path dir, String tag) throws IOException {
        Path casesDir = dir.resolve("cases");
        if (!Files.isDirectory(casesDir)) throw new IllegalStateException("no recorded corpus at " + dir);
        try (Stream<Path> list = Files.list(casesDir)) {
            for (Path caseDir : list.sorted().toList()) {
                String text = Files.readString(caseDir.resolve("case.txt"));
                var origins = new TreeSet<String>();
                var levels = new TreeSet<String>();
                var instantiations = new TreeMap<String, String>();
                try (Stream<Path> files = Files.list(caseDir)) {
                    for (Path file : files.sorted().toList()) {
                        String name = file.getFileName().toString();
                        if (name.startsWith("origin-")) origins.add(Files.readString(file).strip());
                        if (name.startsWith("level-")) levels.add(name.substring("level-".length()));
                        if (name.startsWith("instantiate-")) {
                            String request = Files.readString(file).strip();
                            instantiations.put(CorpusFiles.sha256(request).substring(0, 16), request);
                        }
                    }
                }
                var deterministic = new TreeSet<>(origins);
                deterministic.removeAll(NONDETERMINISTIC_ORIGINS);
                if (deterministic.isEmpty()) {
                    origins.forEach(o -> excluded.merge(o, 1, Integer::sum));
                    continue;
                }
                var c = parse(canonicalFileNames(text), dir);
                c.tags.add(tag);
                c.origins.addAll(deterministic);
                c.recordedLevels.addAll(levels);
                c.instantiations.putAll(instantiations);
            }
        }
    }

    /** A temporary file's random digits ({@code Files.createTempFile}), which tests put in path-based cases' names. */
    private static final Pattern TEMP_FILE_DIGITS =
            Pattern.compile("(?m)^((?:source|library): \\S+ \\S*?)\\d{8,}(\\.java)$");

    /**
     * The case text with temporary-file digits removed from file names, so that the same test's path-based case is
     * one case from run to run; the ledger compiles it under the canonical name (FLAT bytes do not hold file names).
     */
    static String canonicalFileNames(String text) {
        return TEMP_FILE_DIGITS.matcher(text).replaceAll("$1$2");
    }

    private Case parse(String text, Path dir) throws IOException {
        String entry = null, method = null, lookup = null, target = null, costProfile = null;
        boolean sourceMap = false;
        List<String> disabled = List.of();
        Source main = null;
        var libraries = new ArrayList<Source>();
        for (String line : text.lines().toList()) {
            int colon = line.indexOf(": ");
            String key = line.substring(0, colon), value = line.substring(colon + 2);
            switch (key) {
                case "entry" -> entry = value;
                case "method" -> method = value;
                case "lookup" -> lookup = value;
                case "target" -> target = value;
                case "costProfile" -> costProfile = value;
                case "sourceMap" -> sourceMap = Boolean.parseBoolean(value);
                case "disabledRules" -> disabled = List.of(value.split(","));
                case "source" -> main = readSource(dir, value);
                case "library" -> libraries.add(readSource(dir, value));
                default -> {
                }
            }
        }
        String key = CorpusFiles.sha256(text);
        Case existing = cases.get(key);
        if (existing != null) return existing;
        var c = new Case(key, entry, method, lookup, target, costProfile, sourceMap, disabled, main, libraries);
        cases.put(key, c);
        return c;
    }

    private static Source readSource(Path dir, String ref) throws IOException {
        int space = ref.indexOf(' ');
        String digest = space < 0 ? ref : ref.substring(0, space);
        String fileName = space < 0 ? null : ref.substring(space + 1);
        String text = CorpusFiles.text(Files.readAllBytes(dir.resolve("sources").resolve(digest + ".java")));
        if (!CorpusFiles.sha256(text).equals(digest)) throw new IllegalStateException("corrupt source " + digest);
        return new Source(digest, text, fileName);
    }

    // --- Generated cases ---

    /** Adds (or tags) a case built the way the entry point would record it. */
    Case add(String tag, String entry, String method, String lookup, Source main, List<Source> libraries) {
        var options = new CompilerOptions();
        String text = CorpusRecorder.caseText(entry, method, lookup, options, main == null ? null : main.ref(),
                libraries.stream().map(Source::ref).toList());
        String key = CorpusFiles.sha256(text);
        Case c = cases.computeIfAbsent(key, k -> new Case(k, entry, method, lookup, options.getTarget().profileId(), null,
                false, List.of(), main, libraries));
        c.tags.add(tag);
        return c;
    }

    /** A {@code compileMethod(source, method)} case, with the libraries the entry point resolves from the class path. */
    Case addMethod(String tag, String source, String method) {
        var available = LibrarySourceResolver.scanClasspathSources(Thread.currentThread().getContextClassLoader());
        var libraries = available.isEmpty() ? List.<String>of() : LibrarySourceResolver.resolve(source, available);
        return add(tag, "compileMethod", method, STDLIB_LOOKUP, Source.of(source, null),
                libraries.stream().map(l -> Source.of(l, null)).toList());
    }

    /** A {@code compile(source)} case, with the libraries the entry point resolves from the class path. */
    Case addValidator(String tag, String source) {
        var available = LibrarySourceResolver.scanClasspathSources(JulcCompiler.class.getClassLoader());
        var libraries = available.isEmpty() ? List.<String>of() : LibrarySourceResolver.resolve(source, available);
        return add(tag, "compile", null, STDLIB_LOOKUP, Source.of(source, null),
                libraries.stream().map(l -> Source.of(l, null)).toList());
    }

    void addStdlib() throws IOException {
        var index = Thread.currentThread().getContextClassLoader().getResource("META-INF/plutus-sources/index.txt");
        if (index == null) throw new IllegalStateException("no META-INF/plutus-sources/index.txt on the class path");
        String text;
        try (var in = index.openStream()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        var all = new ArrayList<Source>();
        for (String entry : text.lines().map(String::strip).filter(l -> !l.isEmpty()).sorted().toList()) {
            var resource = Thread.currentThread().getContextClassLoader().getResource("META-INF/plutus-sources/" + entry);
            if (resource == null) throw new IllegalStateException("missing bundled source " + entry);
            String source;
            try (var in = resource.openStream()) {
                source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            var s = Source.of(source, null);
            all.add(s);
            add("stdlib:" + entry, "JavaLibraryProvider", null, STDLIB_LOOKUP, null, List.of(s));
        }
        add("stdlib:all", "JavaLibraryProvider", null, STDLIB_LOOKUP, null, all);
    }

    int stdlibSources() {
        return (int) cases.values().stream().flatMap(c -> c.tags.stream())
                .filter(t -> t.startsWith("stdlib:") && !t.equals("stdlib:all")).count();
    }

    void addLedger() throws IOException {
        add("ledger:LedgerTypeProvider", "JavaLibraryProvider", null, "none", null, List.of());
        Path root = repository.resolve("julc-ledger-api/src/main/java");
        for (Path file : javaFiles(root)) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            add("ledger:" + relative, "ledger-source", null, "none", Source.of(Files.readString(file), null), List.of());
        }
    }

    void addFixtures(String tag, String directory) throws IOException {
        Path root = repository.resolve(directory);
        for (Path file : javaFiles(root)) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            addValidator(tag + ":" + relative, Files.readString(file));
        }
    }

    void addDiffcases() {
        for (String dir : List.of("regressions", "known-divergences")) {
            Path path = repository.resolve("julc-difftest/src/test/resources").resolve(dir);
            for (var saved : RegressionStore.load(path)) {
                var d = saved.diffCase();
                var c = addMethod("diffcase:" + dir + "/" + saved.file().getFileName(), d.source(), d.method());
                c.runs.add(new Run("diffcase:" + dir + "/" + saved.file().getFileName(), d));
            }
        }
    }

    void addG0Exhaustive() {
        var programs = G0Enumeration.all();
        for (int n = 0; n < programs.size(); n++) {
            String className = "E" + n;
            String source = programs.get(n).source(className);
            var c = addMethod("g0:" + n, source, "m");
            for (int i = 0; i < G0ExhaustiveTest.INPUTS.size(); i++) {
                c.runs.add(new Run("g0:" + n + "/input-" + i, new DiffCase("g0 enumeration " + n + " input " + i,
                        source, className, "m", G0ExhaustiveTest.INPUTS.get(i))));
            }
        }
    }

    /** The G1 fixed-seed corpus: the captured plans and inputs, decoded under the default profile. */
    int addG1FixedSeed(Path capture) throws IOException {
        var tries = Adr068G1Capture.read(capture);
        for (int t = 0; t < tries.size(); t++) {
            var plan = tries.get(t).get(0);
            var inputs = tries.get(t).get(1);
            var program = G1.decode(plan, G1.Profile.DEFAULT);
            var c = addMethod("g1:" + t, program.source(), "m");
            c.runs.add(new Run("g1:" + t, new DiffCase("g1 " + plan, program.source(), "Probe", "m",
                    program.arguments(inputs), program.tags(), program.profile().text())));
        }
        return tries.size();
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted(Comparator.comparing(Path::toString)).toList();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    List<Case> sorted() {
        return List.copyOf(cases.values());
    }
}
