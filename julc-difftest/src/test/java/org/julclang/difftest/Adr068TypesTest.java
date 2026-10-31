package org.julclang.difftest;

import org.julclang.compiler.resolve.TypeRegistry;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068 M2's exit (I11): registry equality between the frontends over the whole corpus, written to
 * {@code types-m2.tsv} under {@code -Padr068.out}. Run by {@code ./gradlew :julc-difftest:adr068Types}; never part of
 * {@code test}, since it needs the recorded corpora. See {@code adr/evidence/068-m2-types.md}.
 */
@Tag("adr068-types")
class Adr068TypesTest {

    private static final Path REPO = Path.of(System.getProperty("adr068.repo", "..")).toAbsolutePath().normalize();
    private static final Path OUT = Path.of(System.getProperty("adr068.out", REPO.resolve("build/adr068-types").toString()));

    /** One case's row: its key, its class, and what the class needs said. */
    record Row(String key, String kind, String detail) {
    }

    @Test
    void registriesAgreeOverTheCorpus() throws Exception {
        var corpus = corpus(REPO);

        var rows = new ConcurrentHashMap<String, Row>();
        var parts = new ConcurrentHashMap<String, Integer>();
        int threads = Integer.parseInt(System.getProperty("adr068.threads",
                String.valueOf(Math.max(1, Runtime.getRuntime().availableProcessors() - 2))));
        var pool = new ForkJoinPool(threads);
        try {
            pool.submit(() -> corpus.sorted().parallelStream().forEach(c -> {
                var row = row(c);
                rows.put(c.key, row);
                for (String part : c.parts()) parts.merge(part + "\t" + row.kind(), 1, Integer::sum);
            })).get();
        } finally {
            pool.shutdown();
        }

        var totals = new TreeMap<String, Integer>();
        var lines = new ArrayList<String>();
        for (var row : new TreeMap<>(rows).values()) {
            totals.merge(row.kind(), 1, Integer::sum);
            lines.add(row.key() + "\t" + row.kind() + "\t" + row.detail());
        }
        Files.createDirectories(OUT);
        Files.write(OUT.resolve("types-m2.tsv"), lines);
        var summary = new ArrayList<String>();
        summary.add("cases\t" + corpus.cases.size());
        totals.forEach((kind, n) -> summary.add(kind + "\t" + n));
        new TreeMap<>(parts).forEach((part, n) -> summary.add("part\t" + part + "\t" + n));
        Files.write(OUT.resolve("types-m2-totals.tsv"), summary);
        summary.stream().filter(line -> !line.startsWith("part")).forEach(System.out::println);
        assertEquals(corpus.cases.size(), lines.size());
        var accepted = Adr068Types.acceptedDifferences(REPO, "registry");
        var different = new TreeMap<String, String>();
        rows.values().forEach(row -> {
            if (row.kind().startsWith("DIFFERENT")) different.put(row.key(), row.detail());
        });
        assertEquals(accepted.keySet(), different.keySet(), "an unclassified difference (D13), or a stale entry");
        assertTrue(totals.getOrDefault("equal", 0) > 0, "no case was compared");
    }

    /** The corpus of {@code adr068Baseline}, from the recordings given. */
    static Adr068Corpus corpus(Path repository) throws Exception {
        var corpus = new Adr068Corpus(repository);
        for (var entry : recorded().entrySet()) corpus.addRecorded(entry.getValue(), entry.getKey());
        corpus.addStdlib();
        corpus.addLedger();
        corpus.addFixtures("blaster", "verification/blaster/fixtures");
        corpus.addFixtures("c7", "verification/c7/fixtures");
        corpus.addDiffcases();
        corpus.addG0Exhaustive();
        corpus.addG1FixedSeed(Path.of(Adr068G1Capture.FILE));
        return corpus;
    }

    private static Map<String, Path> recorded() {
        var corpora = new TreeMap<String, Path>();
        String recorded = System.getProperty("adr068.corpus");
        assertTrue(recorded != null, "-Padr068.corpus=<dir recorded with -Pjulc.corpus.record> is required");
        corpora.put("recorded", Path.of(recorded));
        String external = System.getProperty("adr068.corpus.external");
        if (external != null) corpora.put("external", Path.of(external));
        String elci = System.getProperty("adr068.corpus.elci");
        if (elci != null) corpora.put("elci", Path.of(elci));
        String head = System.getProperty("adr068.corpus.head");
        if (head != null) corpora.put("head", Path.of(head));
        return corpora;
    }

    private static Row row(Adr068Corpus.Case c) {
        var row = classify(c);
        String detail = row.detail().length() > 200 ? row.detail().substring(0, 200) + "..." : row.detail();
        return new Row(row.key(), row.kind(), detail);
    }

    private static Row classify(Adr068Corpus.Case c) {
        if (c.entry.equals("ledger-source")) return new Row(c.key, "ledger-source", "");
        var lookup = c.lookup.equals(Adr068Corpus.STDLIB_LOOKUP) ? StdlibRegistry.defaultRegistry() : null;
        if (c.entry.equals("JavaLibraryProvider") && c.libraries.isEmpty()) {
            String difference = Adr068Types.providerDifference(List.of(), lookup);
            return new Row(c.key, difference == null ? "equal" : "DIFFERENT", difference == null ? "ledger" : difference);
        }
        var units = Adr068Types.units(c);
        var reference = Adr068Types.reference(units);
        var javac = Adr068Types.javac(units, List.of());
        if (!javac.accepted() && !reference.accepted()) {
            if (javac.rejection().equals(reference.rejection())) return new Row(c.key, "both-reject", reference.rejection());
            // A type rejection the reference makes, in a source javac rejects first for other reasons.
            var read = Adr068Types.tolerant(units);
            if (!read.accepted() && read.rejection().equals(reference.rejection())) {
                return new Row(c.key, "both-reject-tolerant", reference.rejection());
            }
            return new Row(c.key, "both-reject-javac-first", reference.rejection() + " / " + javac.rejection());
        }
        if (!javac.accepted()) {
            boolean byJavac = javac.rejection().startsWith("JULC0085") || javac.rejection().startsWith("JULC0081");
            if (!byJavac) return new Row(c.key, "DIFFERENT-javac-type-rejects", javac.rejection());
            var read = Adr068Types.tolerant(units);
            if (!read.accepted()) return new Row(c.key, "javac-rejects-unreadable", read.rejection());
            String difference = Adr068Types.difference(reference.registry(), read.registry());
            return difference == null ? new Row(c.key, "equal-tolerant", String.valueOf(reference.declarations()))
                    : new Row(c.key, "DIFFERENT-tolerant", difference);
        }
        if (!reference.accepted()) return new Row(c.key, "reference-rejects", reference.rejection());
        String difference = Adr068Types.difference(reference.registry(), javac.registry());
        if (difference != null) return new Row(c.key, "DIFFERENT", difference);
        var order = order(reference.registry(), javac.registry());
        if (order != null) return new Row(c.key, "DIFFERENT-order", order);
        if (c.entry.equals("JavaLibraryProvider")) {
            String provider = Adr068Types.providerDifference(units, lookup);
            if (provider != null && provider.startsWith("one rejects: ") && provider.endsWith(" / null")) {
                return new Row(c.key, "equal-reference-rejects-lowering", provider);
            }
            if (provider != null) return new Row(c.key, "DIFFERENT-provider", provider);
        }
        // D8: the root, with the libraries on the source path.
        if (c.main != null && !c.libraries.isEmpty()) {
            var read = Adr068Types.javac(List.of(c.main.text()), c.libraries.stream().map(l -> l.text()).toList());
            if (!read.accepted()) return new Row(c.key, "DIFFERENT-d8", read.rejection());
            String subset = subset(read.registry(), javac.registry());
            if (subset != null) return new Row(c.key, "DIFFERENT-d8", subset);
        }
        return new Row(c.key, "equal", String.valueOf(reference.declarations()));
    }

    /** Registration order, which the frontends share (I4): the first map whose keys are in another order, or null. */
    private static String order(TypeRegistry.Snapshot reference, TypeRegistry.Snapshot javac) {
        if (!List.copyOf(reference.records().keySet()).equals(List.copyOf(javac.records().keySet()))) return "records";
        if (!List.copyOf(reference.sums().keySet()).equals(List.copyOf(javac.sums().keySet()))) return "sums";
        return null;
    }

    /** Every declaration of {@code read} is in {@code all}, equal: what javac read is what the reference registers. */
    private static String subset(TypeRegistry.Snapshot read, TypeRegistry.Snapshot all) {
        for (var entry : read.records().entrySet()) {
            if (!entry.getValue().equals(all.records().get(entry.getKey()))) return "record " + entry.getKey();
        }
        for (var entry : read.sums().entrySet()) {
            if (!entry.getValue().equals(all.sums().get(entry.getKey()))) return "sum " + entry.getKey();
        }
        for (var entry : read.newTypes().entrySet()) {
            if (!entry.getValue().equals(all.newTypes().get(entry.getKey()))) return "newtype " + entry.getKey();
        }
        return null;
    }
}
