package org.julclang.difftest;

import org.julclang.compiler.frontend.javac.EqualityOperations;
import java.util.HashSet;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068 M0: writes the baseline evidence under {@code adr/evidence/068/} (or {@code -Padr068.out}). Run by
 * {@code ./gradlew :julc-difftest:adr068Baseline -Padr068.corpus=<recorded corpus>}; never part of {@code test}.
 * Every file is generated, sorted and deterministic: two runs over the same corpus write the same bytes.
 */
@Tag("adr068-baseline")
class Adr068BaselineTest {

    /** A validator or on-chain library: the sources of a test tree or an external project the scan reads. */
    private static final Pattern ON_CHAIN = Pattern.compile("@(SpendingValidator|MintingValidator|WithdrawValidator"
            + "|CertifyingValidator|VotingValidator|ProposingValidator|MultiValidator|OnchainLibrary)\\b");

    private static final Path REPO = Path.of(System.getProperty("adr068.repo", "..")).toAbsolutePath().normalize();
    private static final Path OUT = Path.of(System.getProperty("adr068.out",
            REPO.resolve("adr/evidence/068").toString()));

    @Test
    void ledgerAndRejectionInventory() throws Exception {
        String recorded = System.getProperty("adr068.corpus");
        assertTrue(recorded != null, "-Padr068.corpus=<dir recorded with -Pjulc.corpus.record> is required");
        var corpus = new Adr068Corpus(REPO);
        corpus.addRecorded(Path.of(recorded));
        corpus.addStdlib();
        corpus.addLedger();
        corpus.addFixtures("blaster", "verification/blaster/fixtures");
        corpus.addFixtures("c7", "verification/c7/fixtures");
        corpus.addDiffcases();
        corpus.addG0Exhaustive();
        int g1 = corpus.addG1FixedSeed(Path.of(Adr068G1Capture.FILE));

        System.out.println("adr068: max heap " + Runtime.getRuntime().maxMemory() / (1 << 20) + " MiB");
        var ledger = new Adr068Ledger(corpus);
        int threads = Integer.parseInt(System.getProperty("adr068.threads",
                String.valueOf(Math.max(1, Runtime.getRuntime().availableProcessors() - 2))));
        ledger.compileAll(threads);
        var runs = ledger.runAll();

        Files.createDirectories(OUT);
        write("ledger-b0.tsv", Adr068Ledger.LEDGER_HEADER, ledger.ledgerLines());
        write("corpus-b0.tsv", "# ADR-068 M0 corpus: each case's tags (part:item) and the tests that compiled it."
                + " Generated; do not edit.\n", ledger.corpusLines());
        write("ledger-b0-semantic.tsv", "# ADR-068 D13 semantic observations (value, traces, failure) of the runnable"
                + " cases, reference frontend. Generated; do not edit.\n# observation: <kind>:<value cbor sha256/16>:"
                + "<trace count>/<traces sha256/16>:<detail sha256/16>\n", Adr068Ledger.semanticLines(runs));
        write("ledger-b0-budget.tsv", "# ADR-068 D13 budgets (cpu/memory/script bytes) of the runnable cases, reference"
                + " frontend. Generated; do not edit.\n", Adr068Ledger.budgetLines(runs));
        int[] inventory = new int[2];
        var inventoryLines = ledger.inventoryLines(inventory);
        write("rejection-inventory-b0.tsv", "# ADR-068 I2a rejection inventory: every rejection of the reference"
                + " frontend on the corpus, with its class under D13 (permanent | scheduled for acceptance).\n"
                + "# Generated; do not edit.\n", inventoryLines);

        var totals = new ArrayList<String>();
        totals.add("cases\t" + corpus.cases.size());
        var parts = new TreeMap<String, Integer>();
        corpus.cases.values().forEach(c -> c.parts().forEach(p -> parts.merge(p, 1, Integer::sum)));
        parts.forEach((part, n) -> totals.add("cases." + part + "\t" + n));
        totals.add("stdlib.sources\t" + corpus.stdlibSources());
        totals.add("g1.tries\t" + g1);
        long g1Recorded = corpus.cases.values().stream()
                .filter(c -> c.tags.stream().anyMatch(t -> t.startsWith("g1:")) && c.tags.contains("recorded")).count();
        long g1Cases = corpus.cases.values().stream().filter(c -> c.tags.stream().anyMatch(t -> t.startsWith("g1:"))).count();
        totals.add("g1.cases.also-recorded\t" + g1Recorded + "/" + g1Cases);
        long g0Recorded = corpus.cases.values().stream()
                .filter(c -> c.tags.stream().anyMatch(t -> t.startsWith("g0:")) && c.tags.contains("recorded")).count();
        long g0Cases = corpus.cases.values().stream().filter(c -> c.tags.stream().anyMatch(t -> t.startsWith("g0:"))).count();
        totals.add("g0.cases.also-recorded\t" + g0Recorded + "/" + g0Cases);
        corpus.excluded.forEach((origin, n) -> totals.add("excluded.nondeterministic\t" + origin + "\t" + n));
        ledger.totals().forEach((level, t) -> totals.add("ledger." + level + "\taccepted " + t[0] + "\trejected " + t[1]
                + "\tunreplayable " + t[2]));
        totals.add("inventory\tpermanent " + inventory[0] + "\tscheduled " + inventory[1]);
        totals.add("runs\t" + runs.size());
        var verdicts = new TreeMap<String, Integer>();
        runs.forEach(r -> r.verdicts().values().forEach(v -> verdicts.merge(v, 1, Integer::sum)));
        verdicts.forEach((v, n) -> totals.add("runs.verdicts." + v + "\t" + n));
        var unreplayable = new TreeMap<String, Integer>();
        corpus.cases.values().forEach(c -> {
            var o = ledger.result(c.key).outcomes().values().iterator().next();
            if (o.unreplayable()) unreplayable.merge(o.message(), 1, Integer::sum);
        });
        unreplayable.forEach((m, n) -> totals.add("unreplayable\t" + m + "\t" + n));
        write("totals-b0.tsv", "# ADR-068 M0 totals. Generated; do not edit.\n", totals);
    }

    @Test
    void equalityScan() throws Exception {
        var scan = new Adr068EqualityScan(System.getProperty("java.class.path"));
        var inRepo = new ArrayList<Adr068EqualityScan.Unit>();
        String recorded = System.getProperty("adr068.corpus");
        if (recorded != null) {
            var corpus = new Adr068Corpus(REPO);
            corpus.addRecorded(Path.of(recorded));
            var seen = new HashSet<String>();
            for (var c : corpus.sorted()) {
                if (c.main != null && seen.add(c.main.digest())) {
                    inRepo.add(new Adr068EqualityScan.Unit("recorded:" + c.main.digest().substring(0, 16), c.main.text()));
                }
            }
        }
        for (String dir : List.of("julc-stdlib/src/main/java", "julc-ledger-api/src/main/java",
                "verification/blaster/fixtures", "verification/c7/fixtures", "julc-examples/src/test/java")) {
            for (Path file : javaFiles(REPO.resolve(dir))) {
                String text = Files.readString(file);
                if (dir.startsWith("julc-examples") && !ON_CHAIN.matcher(text).find()) continue;
                inRepo.add(new Adr068EqualityScan.Unit(REPO.relativize(file).toString(), text));
            }
        }
        for (String dir : List.of("regressions", "known-divergences")) {
            for (var saved : RegressionStore.load(REPO.resolve("julc-difftest/src/test/resources").resolve(dir))) {
                inRepo.add(new Adr068EqualityScan.Unit("diffcase:" + saved.file().getFileName(), saved.diffCase().source()));
            }
        }
        scan.scanEach("in-repo", inRepo);
        String external = System.getProperty("adr068.external");
        if (external != null) {
            Path root = Path.of(external);
            var units = new ArrayList<Adr068EqualityScan.Unit>();
            for (Path file : javaFiles(root.resolve("src/main/java"))) {
                String text = Files.readString(file);
                if (!ON_CHAIN.matcher(text).find()) continue;
                units.add(new Adr068EqualityScan.Unit("julc-examples/" + root.relativize(file), text));
            }
            scan.scanTogether("external", units);
        }
        Files.createDirectories(OUT);
        String header = "# ADR-068 D6 equality call-site scan (JULC0087 predicate, ArrayIdentityEquality). Generated by"
                + " Adr068BaselineTest; do not edit.\n# unit: accepted | accepted+imports (dialect imports added) |"
                + " partial (javac rejects the unit; site attributed)\n";
        write("equality-scan-b0.tsv", header, Adr068EqualityScan.lines(scan.hits));
        write("equality-scan-b0-type-variables.tsv", header, Adr068EqualityScan.lines(scan.typeVariableSites));
        write("equality-scan-b0-nested-collections.tsv", header + "# collection operations whose compared type is a JuLC"
                + " collection (#297)\n", Adr068EqualityScan.lines(scan.nestedCollectionSites));
        var totals = new ArrayList<String>();
        scan.unitCounts.forEach((corpus, n) -> totals.add("units." + corpus + "\taccepted " + n[0]
                + "\taccepted+imports " + n[1] + "\tpartial " + n[2]));
        var kinds = new TreeMap<String, Integer>();
        scan.hits.stream().map(Adr068EqualityScan.Site::row).distinct()
                .forEach(r -> kinds.merge(r.split("\t")[0] + "." + r.split("\t")[1] + "." + r.split("\t")[5], 1, Integer::sum));
        kinds.forEach((k, n) -> totals.add("hits." + k + "\t" + n));
        totals.add("type-variable-sites\t" + scan.typeVariableSites.stream().map(Adr068EqualityScan.Site::row).distinct().count());
        totals.add("nested-collection-sites\t" + scan.nestedCollectionSites.stream().map(Adr068EqualityScan.Site::row).distinct().count());
        totals.add("operations\t" + String.join("; ", EqualityOperations.TABLE));
        write("equality-scan-b0-totals.tsv", "# Generated; do not edit.\n", totals);
    }

    @Test
    void acceptedDivergences() throws IOException {
        var rows = new ArrayList<String>();
        rows.add("id\tkind\tsource\tstatus\tdetail");
        var row = Pattern.compile("^\\| (T\\d+) \\| ([^|]*) \\|[^|]*\\|[^|]*\\| ([^|]*) \\| ([^|]*) \\|$");
        for (String line : Files.readAllLines(REPO.resolve("adr/062-jvm-uplc-semantic-consistency-programme.md"))) {
            var m = row.matcher(line);
            if (!m.matches()) continue;
            String status = m.group(3).strip();
            if (Stream.of("Resolved", "Fixed", "Refuted", "Contained", "Verified (history)").anyMatch(status::startsWith)) continue;
            rows.add(m.group(1) + "\tadr062-row\tADR-062\t" + Adr068Ledger.oneLine(status, 100) + "\t"
                    + Adr068Ledger.oneLine(m.group(2).strip(), 100));
        }
        var expect = Pattern.compile("^expect(\\.[A-Z0-9_]+)?: DIVERGE (\\S+) (.*)$");
        for (String dir : List.of("regressions", "known-divergences")) {
            for (Path file : files(REPO.resolve("julc-difftest/src/test/resources").resolve(dir), ".diffcase")) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.equals("---")) break;
                    var m = expect.matcher(line);
                    if (!m.matches()) continue;
                    rows.add(m.group(2) + "\t" + (dir.equals("regressions") ? "diverge-pin" : "registry-fixture") + "\t"
                            + dir + "/" + file.getFileName() + "\t" + (m.group(1) == null ? "all levels" : m.group(1).substring(1))
                            + "\t" + m.group(3));
                }
            }
        }
        for (var issue : Map.of("#288", "named in ADR-068 D4", "#289", "named in ADR-068 D4 (T3, T4)",
                "#295", "comparison operand order (D6)", "#296", "\"a\" + 1 (D6, JULC0090)",
                "#297", "nested JuLC collection contains (ADR-068 r6 D6)").entrySet()) {
            rows.add(issue.getKey() + "\td4-issue\tADR-068 D4\topen\t" + issue.getValue());
        }
        var sorted = new ArrayList<>(rows.subList(1, rows.size()));
        sorted.sort(Comparator.naturalOrder());
        sorted.addFirst(rows.getFirst());
        Files.createDirectories(OUT);
        write("accepted-divergences.tsv", "# ADR-068 D4 accepted-divergence list, frozen at M0: ADR-062 rows open at B0, the"
                + " open DIVERGE pins with their IDs, and D4's named issues. Generated; do not edit.\n# Open ADR-062 rows: status"
                + " not Resolved, Fixed, Refuted, Contained or Verified (history). registry-fixture: a KnownDivergencesTest"
                + " input, not a pin.\n", sorted);
    }

    private static void write(String name, String header, List<String> lines) throws IOException {
        var text = new StringBuilder(header);
        for (String line : lines) text.append(line).append('\n');
        Files.writeString(OUT.resolve(name), text.toString(), StandardCharsets.UTF_8);
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        return files(root, ".java");
    }

    private static List<Path> files(Path root, String suffix) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(suffix)).sorted(Comparator.comparing(Path::toString)).toList();
        }
    }
}
