package org.julclang.difftest;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068 I1, from M3a to M5: every corpus case on {@code frontend=JAVAC}, classified against B1 and the reference's PIR.
 * Run by {@code ./gradlew :julc-difftest:adr068Lowering}; never part of {@code test}, since it needs the recorded corpora.
 * See {@code adr/evidence/068-m3a-lowering-foundation.md}.
 * <p>
 * M2's lessons are the shape of this gate. Every row must equal the committed {@code lowering-i1.tsv}, in both directions,
 * so a case that moves to another class, gains or loses a covered method, or leaves the corpus fails. No class is
 * tolerant: a frontend that rejects everything moves every covered row, and rejections have their own classes rather
 * than falling into a passing one. Every {@code DIFFERENT} row must be listed by key in
 * {@code lowering-accepted-differences.tsv}, and a stale entry fails too. {@code -Padr068.freeze=true} writes the table,
 * and refuses to lose covered cases, or cases at all, without {@code -Padr068.allowShrink=true}.
 */
@Tag("adr068-lowering")
class Adr068LoweringTest {

    private static final Path REPO = Path.of(System.getProperty("adr068.repo", "..")).toAbsolutePath().normalize();
    private static final Path OUT = Path.of(System.getProperty("adr068.out", REPO.resolve("build/adr068-lowering").toString()));
    private static final Path EVIDENCE = REPO.resolve("adr/evidence/068");
    private static final Path FROZEN = EVIDENCE.resolve("lowering-i1.tsv");
    private static final boolean FREEZE = Boolean.parseBoolean(System.getProperty("adr068.freeze", "false"));
    private static final boolean ALLOW_SHRINK = Boolean.parseBoolean(System.getProperty("adr068.allowShrink", "false"));

    @Test
    void theJavacFrontendAgainstTheReference() throws Exception {
        var corpus = Adr068TypesTest.corpus(REPO);
        var tool = new Adr068Lowering(b1());
        var rows = new ConcurrentHashMap<String, Adr068Lowering.Row>();
        var parts = new ConcurrentHashMap<String, Integer>();
        int threads = Integer.parseInt(System.getProperty("adr068.threads",
                String.valueOf(Math.max(1, Runtime.getRuntime().availableProcessors() - 2))));
        var pool = new ForkJoinPool(threads);
        try {
            pool.submit(() -> corpus.sorted().parallelStream().forEach(c -> {
                var row = tool.classify(c);
                rows.put(c.key, row);
                for (String part : c.parts()) parts.merge(part + "\t" + row.kind(), 1, Integer::sum);
            })).get();
        } finally {
            pool.shutdown();
        }
        assertEquals(corpus.cases.size(), rows.size());

        var lines = new TreeMap<String, String>();
        rows.forEach((key, row) -> lines.put(key, row.line()));
        var summary = summary(rows, parts);
        Files.createDirectories(OUT);
        Files.write(OUT.resolve("lowering-i1.tsv"), lines.values());
        Files.write(OUT.resolve("lowering-i1-totals.tsv"), summary);
        summary.stream().filter(line -> !line.startsWith("part")).forEach(System.out::println);

        var frozen = frozen();
        var moved = moved(frozen, lines);
        var shrunk = shrunk(frozen, lines);
        var differences = new ArrayList<String>();
        for (var c : corpus.sorted()) {
            var row = rows.get(c.key);
            if (LISTED.matcher(row.kind()).matches()) differences.add(Adr068Lowering.describe(c, row));
        }
        Files.write(OUT.resolve("lowering-i1-differences.txt"), differences);
        Files.write(OUT.resolve("lowering-i1-moved.tsv"), moved);
        Files.write(OUT.resolve("lowering-i1-shrunk.tsv"), shrunk);
        System.out.println("moved\t" + moved.size() + "\nshrunk\t" + shrunk.size());
        moved.stream().limit(20).forEach(line -> System.out.println("moved\t" + line));

        // Every difference is listed, with its D13 class, and every entry is still found.
        assertListed(rows);
        assertTrue(rows.values().stream().anyMatch(Adr068Lowering.Row::fullyCovered), "no case was compared");
        assertTrue(rows.values().stream().noneMatch(row -> row.kind().equals("REFERENCE-DRIFT")),
                "the reference is not B1: the frozen frontend changed");
        if (FREEZE) {
            assertTrue(ALLOW_SHRINK || shrunk.isEmpty(), shrunk.size() + " rows lost coverage or left the corpus; see "
                    + "lowering-i1-shrunk.tsv, and pass -Padr068.allowShrink=true if that is meant");
            Files.write(FROZEN, lines.values());
            Files.write(EVIDENCE.resolve("lowering-i1-totals.tsv"), summary);
            return;
        }
        assertEquals(0, moved.size(), moved.size() + " rows differ from " + FROZEN + ", " + shrunk.size()
                + " of them losing coverage; see lowering-i1-moved.tsv");
    }

    /** The committed reference cells, by case and level: the reference is checked against these as it runs. */
    private static Map<String, Map<String, String>> b1() throws IOException {
        var cells = new TreeMap<String, Map<String, String>>();
        List<String> header = null;
        for (String line : Files.readAllLines(EVIDENCE.resolve("ledger-b1.tsv"))) {
            if (line.startsWith("#")) continue;
            var columns = List.of(line.split("\t", -1));
            if (header == null) {
                header = columns;
                continue;
            }
            var row = new TreeMap<String, String>();
            for (var level : Adr068Ledger.LEVELS) row.put(level.name(), columns.get(header.indexOf(level.name())));
            cells.put(columns.getFirst(), row);
        }
        assertTrue(cells.size() > 10_000, "the B1 ledger did not load: " + cells.size());
        return cells;
    }

    private static Map<String, String> frozen() throws IOException {
        var frozen = new TreeMap<String, String>();
        if (!Files.exists(FROZEN)) return frozen;
        for (String line : Files.readAllLines(FROZEN)) frozen.put(line.substring(0, line.indexOf('\t')), line);
        return frozen;
    }

    /** Both directions: a committed row not reproduced, and a row the table does not have. */
    private static List<String> moved(Map<String, String> frozen, Map<String, String> lines) {
        var moved = new ArrayList<String>();
        frozen.forEach((key, line) -> {
            if (!line.equals(lines.get(key))) moved.add(line + "  ->  " + lines.getOrDefault(key, "(not observed)"));
        });
        lines.forEach((key, line) -> {
            if (!frozen.containsKey(key)) moved.add("(new)  ->  " + line);
        });
        return moved;
    }

    /** A row that left the corpus, or covers fewer methods, or stopped being fully covered. */
    private static List<String> shrunk(Map<String, String> frozen, Map<String, String> lines) {
        var shrunk = new ArrayList<String>();
        frozen.forEach((key, line) -> {
            String now = lines.get(key);
            if (now == null || covered(now) < covered(line)
                    || line.split("\t")[1].equals("i1a-identical") && !now.split("\t")[1].equals("i1a-identical")) {
                shrunk.add(line + "  ->  " + (now == null ? "(not observed)" : now));
            }
        });
        return shrunk;
    }

    private static int covered(String line) {
        String cell = line.split("\t")[2];
        return Integer.parseInt(cell.substring(0, cell.indexOf('/')));
    }

    private static final Pattern LISTED = Pattern.compile("DIFFERENT.*");

    private static void assertListed(Map<String, Adr068Lowering.Row> rows) throws IOException {
        var accepted = new TreeMap<String, String>();
        for (String line : Files.readAllLines(EVIDENCE.resolve("lowering-accepted-differences.tsv"))) {
            if (line.startsWith("#") || line.isBlank()) continue;
            String[] cells = line.split("\t");
            assertEquals(3, cells.length, line);
            accepted.put(cells[0], cells[1]);
        }
        var found = new TreeMap<String, String>();
        rows.values().forEach(row -> {
            if (LISTED.matcher(row.kind()).matches()) found.put(row.key(), row.kind());
        });
        assertEquals(accepted, found, "an unclassified difference (D13), or a stale entry");
    }

    private static List<String> summary(Map<String, Adr068Lowering.Row> rows, Map<String, Integer> parts) {
        var totals = new TreeMap<String, Integer>();
        var constructs = new TreeMap<String, int[]>();
        var provenance = new TreeMap<String, Integer>();
        var programs = new TreeSet<String>();
        var methods = new TreeSet<String>();
        int accepted = 0, covered = 0, total = 0, nonTrivial = 0;
        for (var row : rows.values()) {
            totals.merge(row.kind(), 1, Integer::sum);
            covered += row.covered();
            total += row.total();
            methods.addAll(row.pir());
            if (Set_ACCEPTED.contains(row.kind())) accepted++;
            if (row.fullyCovered()) {
                programs.add(row.program());
                provenance.merge(row.provenance(), 1, Integer::sum);
                if (row.total() > 1) nonTrivial++;
            }
            if (!row.construct().equals("-")) {
                var counts = constructs.computeIfAbsent(row.construct(), k -> new int[1]);
                counts[0]++;
            }
        }
        var summary = new ArrayList<String>();
        summary.add("cases\t" + rows.size());
        totals.forEach((kind, n) -> summary.add(kind + "\t" + n));
        summary.add("coverage\tprograms\t" + totals.getOrDefault("i1a-identical", 0) + "\t" + accepted);
        summary.add("coverage\tdistinct programs\t" + programs.size());
        summary.add("coverage\tprograms of more than one method\t" + nonTrivial);
        summary.add("coverage\tmethods\t" + covered + "\t" + total);
        summary.add("coverage\tdistinct methods\t" + methods.size());
        provenance.forEach((kind, n) -> summary.add("provenance\t" + kind + "\t" + n));
        constructs.entrySet().stream().sorted(Comparator.comparingInt(
                (Map.Entry<String, int[]> e) -> -e.getValue()[0]).thenComparing(Map.Entry::getKey))
                .forEach(e -> summary.add("first-construct\t" + e.getKey() + "\t" + e.getValue()[0]));
        new TreeMap<>(parts).forEach((part, n) -> summary.add("part\t" + part + "\t" + n));
        return summary;
    }

    /** The classes of a case the reference accepts: coverage's denominator. */
    private static final java.util.Set<String> Set_ACCEPTED = java.util.Set.of("i1a-identical", "not-ported", "javac-rejects",
            "DIFFERENT-bytes", "DIFFERENT-javac-rejects", "DIFFERENT-coverage", "DIFFERENT-provenance");
}
