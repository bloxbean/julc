package org.julclang.difftest;

import org.julclang.compiler.frontend.javac.EqualityOperations;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
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

    /** The recorded corpora: in-repo (required), the external julc-examples and Elci (when given), by tag. */
    private static Map<String, Path> recordedCorpora() {
        var corpora = new LinkedHashMap<String, Path>();
        String recorded = System.getProperty("adr068.corpus");
        assertTrue(recorded != null, "-Padr068.corpus=<dir recorded with -Pjulc.corpus.record> is required");
        corpora.put("recorded", Path.of(recorded));
        String external = System.getProperty("adr068.corpus.external");
        if (external != null) corpora.put("external", Path.of(external));
        String elci = System.getProperty("adr068.corpus.elci");
        if (elci != null) corpora.put("elci", Path.of(elci));
        return corpora;
    }

    @Test
    void ledgerAndRejectionInventory() throws Exception {
        var corpus = new Adr068Corpus(REPO);
        var corpora = recordedCorpora();
        for (var entry : corpora.entrySet()) corpus.addRecorded(entry.getValue(), entry.getKey());
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
        var programIndex = ledger.programIndex();
        var evaluations = new Adr068Evaluations();
        for (var entry : corpora.entrySet()) {
            // Elci's evaluations are of Elci programs, which reach JuLC through its PIR backend, not a Java case,
            // and its smoke runs property tests with fresh seeds: its corpus contributes its library cases only.
            if (entry.getKey().equals("elci")) continue;
            var part = new Adr068Evaluations();
            part.load(entry.getValue());
            part.replay(corpus, programIndex, entry.getValue());
            evaluations.evaluations.addAll(part.evaluations);
            part.counts.forEach((k, n) -> evaluations.counts.merge(entry.getKey() + "." + k, n, Integer::sum));
            write("ledger-b0-evaluations" + (entry.getKey().equals("recorded") ? "" : "-" + entry.getKey()) + ".tsv",
                    "# ADR-068 D13 UPLC-only semantic observations: every JulcVm evaluation the " + entry.getKey()
                            + " tests made (F303). Generated; do not edit.\n# program/value: FLAT sha256/16[:size];"
                            + " cases: <count>:<first case (sha256/16)>/<level> compiling to that program; replay: the Java VM on the"
                            + " recorded arguments (agree | DISAGREE, +budget-differs)\n", part.lines(programIndex));
        }

        Files.createDirectories(OUT);
        write("ledger-b0.tsv", Adr068Ledger.LEDGER_HEADER, ledger.ledgerLines());
        write("ledger-b0-instantiations.tsv", "# ADR-068 D13 generic specializations (LibraryRequest.Instantiate) made of"
                + " provider cases, replayed (F302). Generated; do not edit.\n# cell: A:<sha256/32 of the binding and the"
                + " specialized PIR>:- | R:<code>/<message sha256/16>@0:0\n", ledger.instantiationLines());
        write("corpus-b0.tsv", "# ADR-068 M0 corpus: each case's tags (part:item) and the tests that compiled it."
                + " Generated; do not edit.\n", ledger.corpusLines());
        write("ledger-b0-semantic.tsv", "# ADR-068 D13 semantic observations (value, traces, failure) of the runnable"
                + " cases, reference frontend. Generated; do not edit.\n# observation: <kind>:<value cbor sha256/16>:"
                + "<trace count>/<traces sha256/16>:<detail sha256/16>\n", Adr068Ledger.semanticLines(runs));
        write("ledger-b0-budget.tsv", "# ADR-068 D13 budgets (cpu/memory/script bytes) of the runnable cases, reference"
                + " frontend. Generated; do not edit.\n", Adr068Ledger.budgetLines(runs));
        var inventory = ledger.inventory();
        write("rejection-inventory-b0.tsv", "# ADR-068 I2a rejection inventory: every error diagnostic of every rejected"
                + " compilation and specialization of the reference frontend on the corpus, with its anchor, its full"
                + " message's digest and its class under D13 (permanent | scheduled for acceptance).\n# Generated; do not"
                + " edit.\n", Adr068Ledger.inventoryLines(inventory));
        var coverage = Adr068RejectionSites.coverage(REPO.resolve("julc-difftest/src/test/resources/adr068/rejection-sites.tsv"),
                REPO.resolve("julc-compiler/src/main/resources/diagnostics.json"), inventory);
        write("rejection-coverage-b0.tsv", "# ADR-068 I2a coverage: every rejection site of the reference frontend (each"
                + " emitted diagnostics.json code and each uncoded rejection), with the inventory rows that show it."
                + " Generated from julc-difftest/src/test/resources/adr068/rejection-sites.tsv; do not edit.\n",
                coverage.lines());

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
        ledger.instantiationTotals().forEach((level, t) -> totals.add("instantiations." + level + "\taccepted " + t[0]
                + "\trejected " + t[1] + "\tunreplayable " + t[2]));
        long permanent = inventory.stream().filter(r -> !r.classification().scheduled()).count();
        totals.add("inventory.rows\tpermanent " + permanent + "\tscheduled " + (inventory.size() - permanent));
        long cases = inventory.stream().map(Adr068Ledger.InventoryRow::caseKey).distinct().count();
        totals.add("inventory.cases\t" + cases);
        totals.add("coverage\tsites " + coverage.sites() + "\tcovered " + coverage.covered() + "\tnot-source "
                + coverage.notSource() + "\tuncovered " + coverage.uncovered().size() + "\tuncatalogued "
                + coverage.uncatalogued().size());
        totals.add("runs\t" + runs.size());
        var verdicts = new TreeMap<String, Integer>();
        runs.forEach(r -> r.verdicts().values().forEach(v -> verdicts.merge(v, 1, Integer::sum)));
        verdicts.forEach((v, n) -> totals.add("runs.verdicts." + v + "\t" + n));
        totals.add("evaluations\t" + evaluations.evaluations.size());
        evaluations.counts.forEach((k, n) -> totals.add("evaluations." + k + "\t" + n));
        var unreplayable = new TreeMap<String, Integer>();
        corpus.cases.values().forEach(c -> {
            var o = ledger.result(c.key).outcomes().values().iterator().next();
            if (o.unreplayable()) unreplayable.merge(o.unreplayableReason(), 1, Integer::sum);
        });
        unreplayable.forEach((m, n) -> totals.add("unreplayable\t" + m + "\t" + n));
        write("totals-b0.tsv", "# ADR-068 M0 totals. Generated; do not edit.\n", totals);
        assertTrue(coverage.uncovered().isEmpty(), "rejection sites with no inventory row: " + coverage.uncovered());
        assertTrue(coverage.uncatalogued().isEmpty(), "inventory rejections no site explains: " + coverage.uncatalogued());
    }

    @Test
    void equalityScan() throws Exception {
        var scan = new Adr068EqualityScan(System.getProperty("java.class.path"));
        var inRepo = new ArrayList<Adr068EqualityScan.Unit>();
        String recorded = System.getProperty("adr068.corpus");
        if (recorded != null) {
            var corpus = new Adr068Corpus(REPO);
            corpus.addRecorded(Path.of(recorded), "recorded");
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
        write("equality-scan-b0-skipped.tsv", header + "# sites whose compared type javac did not attribute (units javac"
                + " rejects): not decided (F204)\n", Adr068EqualityScan.lines(scan.skippedSites));
        var totals = new ArrayList<String>();
        scan.unitCounts.forEach((corpus, n) -> totals.add("units." + corpus + "\taccepted " + n[0]
                + "\taccepted+imports " + n[1] + "\tpartial " + n[2]));
        var kinds = new TreeMap<String, Integer>();
        scan.hits.stream().map(Adr068EqualityScan.Site::row).distinct()
                .forEach(r -> kinds.merge(r.split("\t")[0] + "." + r.split("\t")[1] + "." + r.split("\t")[5], 1, Integer::sum));
        kinds.forEach((k, n) -> totals.add("hits." + k + "\t" + n));
        totals.add("type-variable-sites\t" + scan.typeVariableSites.stream().map(Adr068EqualityScan.Site::row).distinct().count());
        totals.add("nested-collection-sites\t" + scan.nestedCollectionSites.stream().map(Adr068EqualityScan.Site::row).distinct().count());
        totals.add("skipped-error-type-sites\t" + scan.skippedSites.stream().map(Adr068EqualityScan.Site::row).distinct().count());
        totals.add("operations\t" + String.join("; ", EqualityOperations.TABLE));
        write("equality-scan-b0-totals.tsv", "# Generated; do not edit.\n", totals);
    }

    /**
     * D4's accepted-divergence list, made explicit and hand-reviewed (F204): every ADR-062 catalogue row must have a
     * decision in {@code adr068/accepted-divergences-review.tsv} ({@code open} or {@code closed}, with the reason),
     * and the generated list joins those decisions with the open {@code DIVERGE} pins and D4's named issues.
     */
    @Test
    void acceptedDivergences() throws IOException {
        var review = new TreeMap<String, String[]>();
        for (String line : Files.readAllLines(REPO.resolve("julc-difftest/src/test/resources/adr068/accepted-divergences-review.tsv"))) {
            if (line.isBlank() || line.startsWith("# ")) continue;
            String[] cols = line.split("\t", -1);
            review.put(cols[0], cols);
        }
        var rows = new ArrayList<String>();
        var row = Pattern.compile("^\\| (T\\d+) \\| ([^|]*) \\|[^|]*\\|[^|]*\\| ([^|]*) \\| ([^|]*) \\|$");
        var catalogue = new TreeSet<String>();
        for (String line : Files.readAllLines(REPO.resolve("adr/062-jvm-uplc-semantic-consistency-programme.md"))) {
            var m = row.matcher(line);
            if (!m.matches()) continue;
            String id = m.group(1);
            catalogue.add(id);
            var decision = review.get(id);
            assertTrue(decision != null, "ADR-062 row " + id + " has no decision in accepted-divergences-review.tsv");
            if (!decision[1].equals("open")) continue;
            rows.add(id + "\tadr062-row\tADR-062\t" + Adr068Ledger.oneLine(m.group(3).strip(), 80) + "\t"
                    + Adr068Ledger.oneLine(m.group(2).strip(), 60) + "; " + decision[2]);
        }
        var stale = new TreeSet<>(review.keySet());
        stale.removeIf(id -> catalogue.contains(id) || !id.startsWith("T"));
        assertTrue(stale.isEmpty(), "review decisions for rows ADR-062 does not have: " + stale);
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
        review.forEach((id, cols) -> {
            if (id.startsWith("T")) return;
            if (cols[1].equals("open")) rows.add(id + "\td4-issue\tADR-068 D4\topen\t" + cols[2]);
        });
        rows.sort(Comparator.naturalOrder());
        rows.addFirst("id\tkind\tsource\tstatus\tdetail");
        Files.createDirectories(OUT);
        write("accepted-divergences.tsv", "# ADR-068 D4 accepted-divergence list, frozen at M0: the ADR-062 rows decided"
                + " open in julc-difftest/src/test/resources/adr068/accepted-divergences-review.tsv (hand-reviewed), the"
                + " open DIVERGE pins with their IDs, and D4's named issues. Generated; do not edit.\n# registry-fixture:"
                + " a KnownDivergencesTest input, not a pin.\n", rows);
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
