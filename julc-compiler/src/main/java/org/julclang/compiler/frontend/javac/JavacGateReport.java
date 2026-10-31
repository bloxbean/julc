package org.julclang.compiler.frontend.javac;

import org.julclang.core.corpus.CorpusFiles;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * The javac gate's report (ADR-068 M1): every compilation the gate would reject in {@code enforce} mode, written
 * under the directory {@value JavacGate#REPORT_PROPERTY} names. Layout, every name a SHA-256 digest:
 * <ul>
 *   <li>{@code sources/<digest>.java}: one source text, as {@link CorpusFiles} stores it;</li>
 *   <li>{@code cases/<key>/case.txt}: the javac environment, the roots and pool (digest and file name) and the
 *       diagnostics, one per line; {@code key} is the digest of the inputs;</li>
 *   <li>{@code cases/<key>/entry-<name>} and {@code origin-<digest>}: each entry point and test that compiled it;</li>
 *   <li>{@code failures/<key>/failure.txt}: an internal error of the gate, with its stack trace.</li>
 * </ul>
 * Files are written once, atomically, so concurrent test JVMs can share the directory; writing is best-effort and
 * never fails a compilation. {@link #main} summarizes it.
 */
public final class JavacGateReport {

    private JavacGateReport() {
    }

    private static final AtomicBoolean WARNED = new AtomicBoolean();

    /** The report directory, or null when none is named or the name is not a path. */
    private static Path directory() {
        String dir = System.getProperty(JavacGate.REPORT_PROPERTY);
        if (dir == null || dir.isBlank()) return null;
        try {
            return Path.of(dir).toAbsolutePath();
        } catch (InvalidPathException e) {
            warn(dir, e);
            return null;
        }
    }

    /**
     * Reporting is best-effort: it never fails a compilation. The first failure of a process is printed to standard
     * error, so a report that could not be written is not mistaken for a complete one.
     */
    static void warn(Object where, Throwable e) {
        if (WARNED.compareAndSet(false, true)) {
            System.err.println("[julc] WARN: javac gate report " + where + " is incomplete: " + e);
        }
    }

    static void record(String entry, List<JavacFrontend.Unit> roots, List<JavacFrontend.Unit> pool,
                       JavacResult result) {
        Path root = directory();
        if (root == null) return;
        try {
            var text = new StringBuilder();
            text.append("javac: ").append(result.javacVersion()).append('\n');
            text.append("platform: ").append(result.platformDigest()).append('\n');
            text.append("stubs: ").append(result.stubsDigest()).append('\n');
            for (var unit : roots) text.append("root: ").append(write(root, unit)).append('\n');
            for (var unit : pool) text.append("pool: ").append(write(root, unit)).append('\n');
            for (var d : result.diagnostics()) {
                text.append("diagnostic: ").append(d.code()).append(' ').append(d.key() == null ? "-" : d.key())
                        .append(' ').append(d.fileName()).append(':').append(d.line()).append(':')
                        .append(d.column()).append(' ').append(oneLine(d.message())).append('\n');
            }
            Path dir = root.resolve("cases").resolve(JavacGate.key(roots, pool));
            Files.createDirectories(dir);
            CorpusFiles.writeOnce(dir.resolve("case.txt"), text.toString());
            CorpusFiles.touch(dir.resolve("entry-" + entry));
            String origin = CorpusFiles.origin();
            CorpusFiles.writeOnce(dir.resolve("origin-" + CorpusFiles.sha256(origin).substring(0, 16)), origin + "\n");
        } catch (IOException | RuntimeException e) {
            warn(root, e);
        }
    }

    static void recordFailure(String entry, List<JavacFrontend.Unit> roots, List<JavacFrontend.Unit> pool,
                              Throwable failure) {
        if (failure instanceof BrokenJulcBuildException) warn("(not written: the gate cannot run)", failure);
        Path root = directory();
        if (root == null) return;
        try {
            var trace = new StringWriter();
            failure.printStackTrace(new PrintWriter(trace));
            var text = new StringBuilder("entry: " + entry + "\n");
            for (var unit : roots) text.append("root: ").append(write(root, unit)).append('\n');
            for (var unit : pool) text.append("pool: ").append(write(root, unit)).append('\n');
            text.append(trace);
            Path dir = root.resolve("failures").resolve(JavacGate.key(roots, pool));
            Files.createDirectories(dir);
            CorpusFiles.writeOnce(dir.resolve("failure.txt"), text.toString());
        } catch (IOException | RuntimeException e) {
            warn(root, e);
        }
    }

    private static String write(Path root, JavacFrontend.Unit unit) throws IOException {
        String digest = CorpusFiles.sha256(unit.text());
        Path sources = root.resolve("sources");
        Files.createDirectories(sources);
        CorpusFiles.writeOnce(sources.resolve(digest + ".java"), unit.text());
        return unit.path() == null ? digest : digest + " " + unit.path().getFileName();
    }

    private static String oneLine(String message) {
        return message.replace("\r", "").replace("\n", " | ");
    }

    /**
     * Summarizes a report directory into {@code summary.txt} (totals by javac key) and {@code rejections.tsv} (one
     * line per rejected input: origins, root file names, first diagnostic), both sorted, and prints the summary.
     */
    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : System.getProperty(JavacGate.REPORT_PROPERTY)).toAbsolutePath();
        var byKey = new TreeMap<String, int[]>();   // key -> {cases, diagnostics}
        var rootsByKey = new TreeMap<String, Set<String>>();
        var rejectedRoots = new TreeSet<String>();
        var lines = new TreeSet<String>();
        int cases = 0;
        Path casesDir = root.resolve("cases");
        List<Path> dirs = new ArrayList<>();
        if (Files.isDirectory(casesDir)) {
            try (Stream<Path> list = Files.list(casesDir)) {
                list.sorted().forEach(dirs::add);
            }
        }
        for (Path dir : dirs) {
            cases++;
            var caseLines = Files.readAllLines(dir.resolve("case.txt"), StandardCharsets.UTF_8);
            var rootNames = new ArrayList<String>();
            var rootDigests = new ArrayList<String>();
            var keys = new TreeMap<String, Integer>();
            String first = null;
            for (String line : caseLines) {
                if (line.startsWith("root: ")) {
                    String[] parts = line.substring(6).split(" ", 2);
                    rootDigests.add(parts[0]);
                    rootNames.add(parts.length > 1 ? parts[1] : parts[0].substring(0, 12));
                } else if (line.startsWith("diagnostic: ")) {
                    String[] parts = line.substring(12).split(" ", 3);
                    String key = parts[1].equals("-") ? parts[0] : parts[1];
                    keys.merge(key, 1, Integer::sum);
                    if (first == null) first = line.substring(12);
                }
            }
            rejectedRoots.addAll(rootDigests);
            keys.forEach((key, n) -> {
                int[] counts = byKey.computeIfAbsent(key, k -> new int[2]);
                counts[0]++;
                counts[1] += n;
                rootsByKey.computeIfAbsent(key, k -> new TreeSet<>()).addAll(rootDigests);
            });
            var origins = new TreeSet<String>();
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.toList()) {
                    String name = f.getFileName().toString();
                    if (name.startsWith("origin-")) origins.add(Files.readString(f).strip());
                }
            }
            lines.add(String.join("\t", String.join(",", origins), String.join(",", rootNames),
                    String.join(",", keys.keySet()), first == null ? "" : first, dir.getFileName().toString()));
        }
        int failures = 0;
        Path failuresDir = root.resolve("failures");
        if (Files.isDirectory(failuresDir)) {
            try (Stream<Path> list = Files.list(failuresDir)) {
                failures = (int) list.count();
            }
        }
        var summary = new StringBuilder();
        summary.append("javac gate report: ").append(root).append('\n');
        summary.append("rejected inputs (distinct roots + pool): ").append(cases).append('\n');
        summary.append("rejected root sources (distinct texts): ").append(rejectedRoots.size()).append('\n');
        summary.append("gate failures (internal errors): ").append(failures).append('\n');
        summary.append('\n').append(String.format("%-60s %8s %8s %12s%n", "javac key (or JuLC code)", "inputs",
                "roots", "diagnostics"));
        for (Map.Entry<String, int[]> e : byKey.entrySet()) {
            summary.append(String.format("%-60s %8d %8d %12d%n", e.getKey(), e.getValue()[0],
                    rootsByKey.get(e.getKey()).size(), e.getValue()[1]));
        }
        Files.writeString(root.resolve("summary.txt"), summary.toString());
        var tsv = new StringBuilder("origins\troots\tkeys\tfirst diagnostic\tcase\n");
        lines.forEach(line -> tsv.append(line).append('\n'));
        Files.writeString(root.resolve("rejections.tsv"), tsv.toString());
        System.out.print(summary);
        System.out.println("\nper input: " + root.resolve("rejections.tsv"));
    }
}
