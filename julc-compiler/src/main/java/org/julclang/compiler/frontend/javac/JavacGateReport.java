package org.julclang.compiler.frontend.javac;

import org.julclang.compiler.corpus.CorpusRecorder;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The javac gate's report (ADR-068 M1): every compilation the gate would reject in {@code enforce} mode, written
 * under the directory {@value JavacGate#REPORT_PROPERTY} names. Layout, every name a SHA-256 digest:
 * <ul>
 *   <li>{@code sources/<digest>.java}: one source text, as {@link CorpusRecorder} stores it;</li>
 *   <li>{@code cases/<key>/case.txt}: the javac environment, the roots and pool (digest and file name) and the
 *       diagnostics, one per line; {@code key} is the digest of the inputs;</li>
 *   <li>{@code cases/<key>/entry-<name>} and {@code origin-<digest>}: each entry point and test that compiled it;</li>
 *   <li>{@code failures/<key>/failure.txt}: an internal error of the gate, with its stack trace.</li>
 * </ul>
 * Files are written once, atomically, so concurrent test JVMs can share the directory. {@link #main} summarizes it.
 */
public final class JavacGateReport {

    private JavacGateReport() {
    }

    private static Path directory() {
        String dir = System.getProperty(JavacGate.REPORT_PROPERTY);
        return dir == null || dir.isBlank() ? null : Path.of(dir).toAbsolutePath();
    }

    static void record(String entry, List<JavacFrontend.Unit> roots, List<JavacFrontend.Unit> pool,
                       JavacResult result) {
        Path root = directory();
        if (root == null) return;
        var text = new StringBuilder();
        text.append("javac: ").append(result.javacVersion()).append('\n');
        text.append("platform: ").append(result.platformDigest()).append('\n');
        text.append("stubs: ").append(result.stubsDigest()).append('\n');
        try {
            for (var unit : roots) text.append("root: ").append(write(root, unit)).append('\n');
            for (var unit : pool) text.append("pool: ").append(write(root, unit)).append('\n');
            for (var d : result.diagnostics()) {
                text.append("diagnostic: ").append(d.code()).append(' ').append(d.key() == null ? "-" : d.key())
                        .append(' ').append(d.fileName()).append(':').append(d.line()).append(':')
                        .append(d.column()).append(' ').append(oneLine(d.message())).append('\n');
            }
            Path dir = root.resolve("cases").resolve(JavacGate.key(roots, pool));
            Files.createDirectories(dir);
            writeOnce(dir.resolve("case.txt"), text.toString());
            touch(dir.resolve("entry-" + entry));
            String origin = CorpusRecorder.origin();
            writeOnce(dir.resolve("origin-" + CorpusRecorder.sha256(origin).substring(0, 16)), origin + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException("javac gate report: " + root, e);
        }
    }

    static void recordFailure(String entry, List<JavacFrontend.Unit> roots, List<JavacFrontend.Unit> pool,
                              Throwable failure) {
        Path root = directory();
        if (root == null) return;
        var trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        try {
            var text = new StringBuilder("entry: " + entry + "\n");
            for (var unit : roots) text.append("root: ").append(write(root, unit)).append('\n');
            for (var unit : pool) text.append("pool: ").append(write(root, unit)).append('\n');
            text.append(trace);
            Path dir = root.resolve("failures").resolve(JavacGate.key(roots, pool));
            Files.createDirectories(dir);
            writeOnce(dir.resolve("failure.txt"), text.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("javac gate report: " + root, e);
        }
    }

    private static String write(Path root, JavacFrontend.Unit unit) throws IOException {
        String digest = CorpusRecorder.sha256(unit.text());
        Path sources = root.resolve("sources");
        Files.createDirectories(sources);
        writeOnce(sources.resolve(digest + ".java"), CorpusRecorder.bytes(unit.text()));
        return unit.path() == null ? digest : digest + " " + unit.path().getFileName();
    }

    private static String oneLine(String message) {
        return message.replace("\r", "").replace("\n", " | ");
    }

    private static void writeOnce(Path file, String content) throws IOException {
        writeOnce(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeOnce(Path file, byte[] content) throws IOException {
        if (Files.exists(file)) return;
        Path temp = Files.createTempFile(file.getParent(), ".tmp-", "");
        try {
            Files.write(temp, content);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException e) {
            // Another JVM wrote it first.
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void touch(Path file) throws IOException {
        try {
            Files.createFile(file);
        } catch (FileAlreadyExistsException e) {
            // Already recorded.
        }
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
