package org.julclang.difftest;

import org.julclang.compiler.frontend.javac.JavacEvidence;
import org.julclang.compiler.resolve.TypeRegistry;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068 M2's exit (I11): registry equality between the frontends over the whole corpus. Run by
 * {@code ./gradlew :julc-difftest:adr068Types}; never part of {@code test}, since it needs the recorded corpora. See
 * {@code adr/evidence/068-m2-types.md}.
 * <p>
 * Each case gets a class and a detail, and the gate is on all of them: every row must equal the committed
 * {@code adr/evidence/068/types-m2.tsv}, so a case that moves to another class fails, in either direction, as does a
 * case that is missing or new. On top of that, the classes that say no comparison happened ({@code reference-rejects},
 * {@code javac-rejects-unreadable}) and every {@code DIFFERENT} must each be listed, by key, in
 * {@code m2-accepted-differences.tsv}. {@code -Padr068.freeze=true} writes the table instead of reading it.
 */
@Tag("adr068-types")
class Adr068TypesTest {

    private static final Path REPO = Path.of(System.getProperty("adr068.repo", "..")).toAbsolutePath().normalize();
    private static final Path OUT = Path.of(System.getProperty("adr068.out", REPO.resolve("build/adr068-types").toString()));
    private static final Path FROZEN = REPO.resolve("adr/evidence/068/types-m2.tsv");
    private static final boolean FREEZE = Boolean.parseBoolean(System.getProperty("adr068.freeze", "false"));
    private static final int DETAIL_LIMIT = 200;

    /** One case's row: its key, its class, what the class needs said, and what was compared in it. */
    record Row(String key, String kind, String detail, Compared compared) {
        String line() {
            String shown = detail.length() > DETAIL_LIMIT ? detail.substring(0, DETAIL_LIMIT) + "..." : detail;
            return key + "\t" + kind + "\t" + shown;
        }
    }

    /** The user's declarations a row compared, for reporting what a case count means (F825). */
    record Compared(int records, int variants, int sums, int newTypes, int fields, Set<String> definitions) {
        static final Compared NOTHING = new Compared(0, 0, 0, 0, 0, Set.of());

        static Compared of(TypeRegistry.Snapshot registry) {
            var definitions = new TreeSet<String>();
            int records = 0, variants = 0, sums = 0, newTypes = 0, fields = 0;
            for (var entry : registry.records().entrySet()) {
                if (Adr068Encodings.ledger(entry.getKey())) continue;
                if (registry.variants().containsKey(entry.getKey())) variants++;
                else records++;
                fields += entry.getValue().fields().size();
                definitions.add(Adr068Encodings.layout(entry.getKey(), entry.getValue()));
            }
            for (var entry : registry.sums().entrySet()) {
                if (Adr068Encodings.ledger(entry.getKey())) continue;
                sums++;
                definitions.add(Adr068Encodings.layout(entry.getKey(), entry.getValue()));
            }
            for (var entry : registry.newTypes().entrySet()) {
                if (Adr068Encodings.ledger(entry.getKey())) continue;
                newTypes++;
                definitions.add(Adr068Encodings.layout(entry.getKey(), entry.getValue()));
            }
            return new Compared(records, variants, sums, newTypes, fields, definitions);
        }

        boolean nothingOfTheUsers() {
            return definitions.isEmpty();
        }
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
                var row = classify(c);
                rows.put(c.key, row);
                for (String part : c.parts()) parts.merge(part + "\t" + row.kind(), 1, Integer::sum);
            })).get();
        } finally {
            pool.shutdown();
        }

        var totals = new TreeMap<String, Integer>();
        var lines = new TreeMap<String, String>();
        rows.forEach((key, row) -> {
            totals.merge(row.kind(), 1, Integer::sum);
            lines.put(key, row.line());
        });
        Files.createDirectories(OUT);
        Files.write(OUT.resolve("types-m2.tsv"), lines.values());
        var summary = new ArrayList<String>();
        summary.add("cases\t" + corpus.cases.size());
        totals.forEach((kind, n) -> summary.add(kind + "\t" + n));
        summary.addAll(compared(rows));
        new TreeMap<>(parts).forEach((part, n) -> summary.add("part\t" + part + "\t" + n));
        Files.write(OUT.resolve("types-m2-totals.tsv"), summary);
        summary.stream().filter(line -> !line.startsWith("part")).forEach(System.out::println);
        assertEquals(corpus.cases.size(), lines.size());

        // No row moves: not to another class, and not out of the table.
        var moved = new ArrayList<String>();
        if (!FREEZE) {
            var frozen = new TreeMap<String, String>();
            for (String line : Files.readAllLines(FROZEN)) frozen.put(line.substring(0, line.indexOf('\t')), line);
            frozen.forEach((key, line) -> {
                if (!line.equals(lines.get(key))) moved.add(line + "  ->  " + lines.getOrDefault(key, "(not observed)"));
            });
            lines.forEach((key, line) -> {
                if (!frozen.containsKey(key)) moved.add("(new)  ->  " + line);
            });
            Files.write(OUT.resolve("types-m2-moved.tsv"), moved);
            moved.stream().limit(20).forEach(line -> System.out.println("moved\t" + line));
        }

        // Every row that says nothing was compared, and every difference, is listed by key.
        listed("registry", rows, kind -> kind.startsWith("DIFFERENT"));
        listed("reference-rejects", rows, "reference-rejects"::equals);
        listed("unreadable", rows, "javac-rejects-unreadable"::equals);
        assertTrue(totals.getOrDefault("equal", 0) > 0, "no case was compared");
        assertEquals(0, moved.size(), moved.size() + " rows differ from " + FROZEN + "; see types-m2-moved.tsv");
    }

    /** What each class compared: {@code <class> cases, no user declaration, records, variants, sums, @NewTypes, fields, distinct}. */
    private static List<String> compared(Map<String, Row> rows) {
        var lines = new ArrayList<String>();
        lines.add("compared\tclass\tcases\tno-user-declaration\trecords\tvariants\tsums\tnew-types\tfields\tdistinct-definitions");
        var kinds = new TreeSet<String>();
        rows.values().forEach(row -> kinds.add(row.kind()));
        var accepted = new TreeSet<String>();
        var tolerantOnly = new TreeSet<String>();
        for (var row : rows.values()) {
            if (row.kind().equals("equal")) accepted.addAll(row.compared().definitions());
            if (row.kind().equals("equal-tolerant")) tolerantOnly.addAll(row.compared().definitions());
        }
        tolerantOnly.removeAll(accepted);
        for (String kind : kinds) {
            int cases = 0, none = 0, records = 0, variants = 0, sums = 0, newTypes = 0, fields = 0;
            var distinct = new TreeSet<String>();
            for (var row : rows.values()) {
                if (!row.kind().equals(kind)) continue;
                var c = row.compared();
                cases++;
                if (c.nothingOfTheUsers()) none++;
                records += c.records();
                variants += c.variants();
                sums += c.sums();
                newTypes += c.newTypes();
                fields += c.fields();
                distinct.addAll(c.definitions());
            }
            lines.add("compared\t" + kind + "\t" + cases + "\t" + none + "\t" + records + "\t" + variants + "\t" + sums
                    + "\t" + newTypes + "\t" + fields + "\t" + distinct.size());
        }
        lines.add("compared\tdefinitions only in sources javac rejects, variants included\t" + tolerantOnly.size());
        return lines;
    }

    private static void listed(String kind, Map<String, Row> rows, Predicate<String> classes) throws IOException {
        var found = new TreeSet<String>();
        rows.values().forEach(row -> {
            if (classes.test(row.kind())) found.add(row.key());
        });
        assertEquals(new TreeSet<>(Adr068Types.acceptedDifferences(REPO, kind).keySet()), found,
                "an unclassified row (D13), or a stale entry, of kind " + kind);
    }

    /** The corpus of {@code adr068Baseline}, from the recordings given, and the fixtures of shapes it lacks. */
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
        corpus.addFixtures("m2", "julc-difftest/src/test/resources/adr068/m2-fixtures");
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

    private static Row row(Adr068Corpus.Case c, String kind, String detail, Compared compared) {
        return new Row(c.key, kind, detail, compared);
    }

    private static Row classify(Adr068Corpus.Case c) {
        if (c.entry.equals("ledger-source")) return row(c, "ledger-source", "", Compared.NOTHING);
        var lookup = c.lookup.equals(Adr068Corpus.STDLIB_LOOKUP) ? StdlibRegistry.defaultRegistry() : null;
        if (c.entry.equals("JavaLibraryProvider") && c.libraries.isEmpty()) {
            String difference = Adr068Types.providerDifference(List.of(), lookup);
            return row(c, difference == null ? "equal" : "DIFFERENT", difference == null ? "ledger" : difference, Compared.NOTHING);
        }
        var units = Adr068Types.units(c);
        var reference = Adr068Types.reference(units);
        var javac = Adr068Types.javac(units, List.of());
        if (!javac.accepted() && !reference.accepted()) {
            if (javac.rejection().equals(reference.rejection())) {
                return row(c, "both-reject", reference.rejection(), Compared.NOTHING);
            }
            // A type rejection the reference makes, in a source javac rejects first for other reasons.
            var read = Adr068Types.tolerant(units);
            if (!read.accepted() && read.rejection().equals(reference.rejection()) && Adr068Types.byJavac(javac.rejection())) {
                return row(c, "both-reject-tolerant", reference.rejection(), Compared.NOTHING);
            }
            // Anything else must be javac's own rejection: JuLC's check rejecting with another text is a difference.
            return row(c, Adr068Types.byJavac(javac.rejection()) ? "both-reject-javac-first" : "DIFFERENT-rejection",
                    reference.rejection() + " / " + javac.rejection(), Compared.NOTHING);
        }
        if (!javac.accepted()) {
            if (!Adr068Types.byJavac(javac.rejection())) {
                return row(c, "DIFFERENT-javac-type-rejects", javac.rejection(), Compared.NOTHING);
            }
            var read = Adr068Types.tolerant(units);
            String verdict = Adr068Types.verdict(javac.rejection());
            if (!read.accepted()) return row(c, "javac-rejects-unreadable", verdict + " " + read.rejection(), Compared.NOTHING);
            String difference = Adr068Types.difference(reference.registry(), read.registry());
            return difference == null ? row(c, "equal-tolerant", reference.declarations() + " " + verdict, Compared.of(reference.registry()))
                    : row(c, "DIFFERENT-tolerant", difference, Compared.NOTHING);
        }
        if (!reference.accepted()) return row(c, "reference-rejects", reference.rejection(), Compared.NOTHING);
        String difference = Adr068Types.difference(reference.registry(), javac.registry());
        if (difference != null) return row(c, "DIFFERENT", difference, Compared.NOTHING);
        var order = order(reference.registry(), javac.registry());
        if (order != null) return row(c, "DIFFERENT-order", order, Compared.NOTHING);
        var compared = Compared.of(reference.registry());
        if (c.entry.equals("JavaLibraryProvider")) {
            String provider = Adr068Types.providerDifference(units, lookup);
            if (provider != null && provider.startsWith("one rejects: ") && provider.endsWith(" / null")) {
                return row(c, "equal-reference-rejects-lowering", provider, compared);
            }
            if (provider != null) return row(c, "DIFFERENT-provider", provider, compared);
        }
        // D8: the root, with the libraries on the source path, against the reference over exactly the units javac read.
        if (c.main != null && !c.libraries.isEmpty()) {
            String d8 = sourcePathDifference(c);
            if (d8 != null) return row(c, "DIFFERENT-d8", d8, compared);
        }
        return row(c, "equal", String.valueOf(reference.declarations()), compared);
    }

    /**
     * Two-directional: what javac read through the source path must be exactly what the reference registers from those
     * units, so a read that drops a library's declarations fails as much as one that invents some.
     */
    private static String sourcePathDifference(Adr068Corpus.Case c) {
        try {
            var program = Adr068Types.program(List.of(c.main.text()), c.libraries.stream().map(l -> l.text()).toList());
            var expected = Adr068Types.reference(JavacEvidence.readUnits(program));
            if (!expected.accepted()) return "the reference rejects the units javac read: " + expected.rejection();
            return Adr068Types.difference(expected.registry(), program.registry().snapshot());
        } catch (RuntimeException | IOException e) {
            return Adr068Types.message(e);
        }
    }

    /** Registration order, which the frontends share (I4): the first map whose keys are in another order, or null. */
    private static String order(TypeRegistry.Snapshot reference, TypeRegistry.Snapshot javac) {
        if (!List.copyOf(reference.records().keySet()).equals(List.copyOf(javac.records().keySet()))) return "records";
        if (!List.copyOf(reference.sums().keySet()).equals(List.copyOf(javac.sums().keySet()))) return "sums";
        return null;
    }
}
