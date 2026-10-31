package org.julclang.difftest;

import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.cbor.PlutusDataCborDecoder;
import org.julclang.core.corpus.CorpusFiles;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;
import org.julclang.vm.JulcVm;
import org.julclang.vm.PlutusLanguage;
import org.julclang.vm.corpus.FileEvaluationRecorder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * ADR-068 M0's UPLC-only semantic observations (D13, F303): every evaluation the tests made through {@code JulcVm},
 * as {@code FileEvaluationRecorder} recorded it (program FLAT digest, provider, target, budget limit, argument
 * digests, outcome, result digest, traces, failure digest, consumed budget, the VM's effective cost configuration,
 * and the tests that made it). Each is matched to the ledger cases and levels that compile to the same program,
 * and replayed where it can be: the case is compiled again, its recorded arguments are read back from their CBOR,
 * and a Java VM configured as the recorded one was (F315) evaluates it, so that a later frontend's program can be
 * evaluated on the same inputs.
 */
final class Adr068Evaluations {

    /**
     * One recorded evaluation: its fields, its argument digests, its {@code config:} lines (the provider's effective
     * cost configuration, F315) and the tests that made it.
     */
    record Evaluation(String key, Map<String, String> fields, List<String> args, List<String> configs,
                      TreeSet<String> origins) {
        String field(String name) {
            return fields.getOrDefault(name, "-");
        }
    }

    /** Reads one recorded evaluation directory ({@code evaluations/<key>}). */
    static Evaluation read(Path evaluationDir) throws IOException {
        var fields = new LinkedHashMap<String, String>();
        var args = new ArrayList<String>();
        var configs = new ArrayList<String>();
        for (String line : Files.readAllLines(evaluationDir.resolve("evaluation.txt"))) {
            int colon = line.indexOf(": ");
            if (colon < 0) continue;
            String key = line.substring(0, colon), value = line.substring(colon + 2);
            if (key.equals("arg")) args.add(value);
            else if (key.equals("config")) configs.add(value);
            else fields.put(key, value);
        }
        var origins = new TreeSet<String>();
        try (Stream<Path> files = Files.list(evaluationDir)) {
            for (Path file : files.sorted().toList()) {
                if (file.getFileName().toString().startsWith("origin-")) origins.add(Files.readString(file).strip());
            }
        }
        return new Evaluation(evaluationDir.getFileName().toString(), fields, args, configs, origins);
    }

    /**
     * Tests ({@code Class#method}) whose evaluations differ from run to run: jqwik properties without a fixed seed,
     * and tests that build fresh keys or transaction ids, draw new inputs each time. Their evaluations are not part
     * of the frozen observations. Found by recording twice and comparing ({@code adr068/nondeterministic-evaluations.txt}).
     */
    static final Set<String> NONDETERMINISTIC_EVALUATIONS = loadNondeterministic();

    private static Set<String> loadNondeterministic() {
        try (var in = Adr068Evaluations.class.getResourceAsStream("/adr068/nondeterministic-evaluations.txt")) {
            if (in == null) throw new IllegalStateException("adr068/nondeterministic-evaluations.txt is missing");
            var set = new TreeSet<String>();
            new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).forEach(set::add);
            return set;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    final List<Evaluation> evaluations = new ArrayList<>();
    final Map<String, Integer> counts = new TreeMap<>();
    private final Map<String, String> replays = new TreeMap<>();
    private final Set<String> excludedTests = new TreeSet<>();

    /** Loads the evaluations recorded under {@code dir}, in key order. */
    void load(Path dir) throws IOException {
        Path root = dir.resolve("evaluations");
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> list = Files.list(root)) {
            for (Path evaluationDir : list.sorted().toList()) {
                var read = read(evaluationDir);
                var origins = read.origins();
                var deterministic = new TreeSet<>(origins);
                deterministic.removeAll(Adr068Corpus.NONDETERMINISTIC_ORIGINS);
                deterministic.removeAll(NONDETERMINISTIC_EVALUATIONS);
                if (deterministic.isEmpty()) {
                    // How many evaluations such a test makes varies from run to run: only the tests are counted.
                    excludedTests.addAll(origins);
                    continue;
                }
                if (deterministic.stream().allMatch(o -> o.startsWith("org.julclang.difftest."))) {
                    // The harness's own evaluations are D13's runs ledger (ledger-b0-semantic/-budget.tsv).
                    counts.merge("excluded.harness", 1, Integer::sum);
                    continue;
                }
                evaluations.add(new Evaluation(read.key(), read.fields(), read.args(), read.configs(), deterministic));
            }
        }
        if (!excludedTests.isEmpty()) counts.put("excluded.nondeterministic-tests", excludedTests.size());
    }

    /**
     * Replays each evaluation that a ledger case compiles to, on the Java VM at the language target it was recorded
     * with and under the cost configuration that VM had (F315), and records whether outcome, result, traces and
     * failure message agree (consumed budgets are compared apart). An evaluation whose VM configuration was not
     * recorded (a provider the {@code JulcVm} was handed) is not replayed.
     */
    void replay(Adr068Corpus corpus, Map<String, List<String>> programIndex, Path dir) {
        var cases = corpus.cases;
        var programs = new TreeMap<String, Program>();
        for (var e : evaluations) {
            String flat = e.field("program").split(" ")[0];
            var matches = programIndex.get(flat.length() >= 32 ? flat.substring(0, 32) : flat);
            if (matches == null) {
                replays.put(e.key(), "unmatched");
                counts.merge("replay.unmatched", 1, Integer::sum);
                continue;
            }
            if (!e.field("provider").equals("Java") || !isLanguage(e.field("target"))) {
                replays.put(e.key(), "matched-not-replayed");
                counts.merge("replay.matched-not-replayed", 1, Integer::sum);
                continue;
            }
            if (!configurationKnown(e)) {
                replays.put(e.key(), "matched-configuration-unknown");
                counts.merge("replay.matched-configuration-unknown", 1, Integer::sum);
                continue;
            }
            String match = matches.getFirst();
            try {
                var program = programs.computeIfAbsent(match, m -> compile(cases.get(m.substring(0, m.indexOf('/'))),
                        OptimizationLevel.valueOf(m.substring(m.indexOf('/') + 1))));
                String verdict = verdict(e, program, dir);
                replays.put(e.key(), verdict);
                counts.merge("replay." + verdict, 1, Integer::sum);
            } catch (RuntimeException | IOException ex) {
                replays.put(e.key(), "replay-failed:" + ex.getClass().getSimpleName());
                counts.merge("replay.failed", 1, Integer::sum);
            }
        }
    }

    /** Whether the evaluation recorded its VM's cost configuration (format 2, and a provider its facade created). */
    static boolean configurationKnown(Evaluation e) {
        return !e.configs().isEmpty() && !e.configs().contains("unknown");
    }

    /**
     * Evaluates {@code program} on the recorded arguments, on a Java VM configured as the recorded one was, and
     * compares: {@code agree} when the outcome, the result, the traces and (for a failure) the failure message's
     * digest, computed as the recorder computes it, are the recorded ones, else {@code DISAGREE}; with
     * {@code +budget-differs} when the consumed budget differs.
     */
    static String verdict(Evaluation e, Program program, Path dir) throws IOException {
        if (!configurationKnown(e)) throw new IllegalArgumentException("no recorded VM configuration: " + e.key());
        var args = new ArrayList<PlutusData>();
        for (String digest : e.args()) {
            args.add(PlutusDataCborDecoder.decode(Files.readAllBytes(dir.resolve("data").resolve(digest + ".cbor"))));
        }
        var vm = JulcVm.create("Java", PlutusLanguage.valueOf(e.field("target")));
        for (String config : e.configs()) {
            if (config.equals("default")) continue;
            String[] parts = config.split(" ");
            String[] version = parts[1].split("\\.");
            var values = CorpusFiles.text(Files.readAllBytes(dir.resolve("data").resolve(parts[2] + ".costs")))
                    .lines().mapToLong(Long::parseLong).toArray();
            vm.setCostModelParams(values, PlutusLanguage.valueOf(parts[0]), Integer.parseInt(version[0]),
                    Integer.parseInt(version[1]));
        }
        String[] budget = e.field("budget").split(" ");
        var limit = budget.length == 2 ? new ExBudget(Long.parseLong(budget[0]), Long.parseLong(budget[1])) : null;
        var result = vm.evaluateWithArgs(program, args, limit);
        boolean semantic = result.getClass().getSimpleName().equals(e.field("outcome"))
                && (!(result instanceof EvalResult.Success s) || termDigest(program, s).equals(e.field("value")))
                && (!(result instanceof EvalResult.Failure f) || FileEvaluationRecorder.failureDigest(f).equals(e.field("error")))
                && traces(result).equals(e.field("traces"));
        var consumed = result.budgetConsumed();
        boolean budgetEqual = (consumed.cpuSteps() + " " + consumed.memoryUnits()).equals(e.field("consumed"));
        return (semantic ? "agree" : "DISAGREE") + (budgetEqual ? "" : "+budget-differs");
    }

    private static boolean isLanguage(String target) {
        try {
            PlutusLanguage.valueOf(target);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static Program compile(Adr068Corpus.Case c, OptimizationLevel level) {
        var options = new CompilerOptions().setOptimizationLevel(level).setSourceMapEnabled(c.sourceMap).setLogger(m -> { });
        c.disabledRules.forEach(options::disableOptimizationRule);
        var lookup = c.lookup.equals("none") ? null : StdlibRegistry.defaultRegistry();
        var compiler = new JulcCompiler(lookup, options);
        var libraries = c.libraries.stream().map(Adr068Corpus.Source::text).toList();
        return switch (c.entry) {
            case "compileMethod" -> compiler.compileMethod(c.main.text(), c.method, libraries).program();
            case "compileContract", "compileContractWithDetails" -> compiler.compileContract(c.main.text(), libraries)
                    .compileResult().program();
            default -> compiler.compile(c.main.text(), libraries).program();
        };
    }

    private static String termDigest(Program program, EvalResult.Success success) {
        try {
            return CorpusFiles.sha256(UplcFlatEncoder.encodeProgram(
                    new Program(program.major(), program.minor(), program.patch(), success.resultTerm())));
        } catch (RuntimeException e) {
            return "unencodable";
        }
    }

    private static String traces(EvalResult result) {
        return result.traces().size() + " " + CorpusFiles.sha256(String.join("\n", result.traces())).substring(0, 16);
    }

    List<String> lines(Map<String, List<String>> programIndex) {
        var lines = new ArrayList<String>();
        lines.add("evaluation\tprogram\tprovider\ttarget\tconfig\tbudget\targs\toutcome\tvalue\ttraces\terror\tconsumed\tcases\treplay\torigins");
        for (var e : evaluations) {
            String flat = e.field("program").split(" ")[0];
            var matches = programIndex.get(flat.length() >= 32 ? flat.substring(0, 32) : flat);
            lines.add(e.key().substring(0, 16) + "\t" + shortField(e.field("program")) + "\t" + e.field("provider") + "\t"
                    + e.field("target") + "\t" + configText(e) + "\t" + e.field("budget").replace(' ', '/') + "\t"
                    + (e.args().isEmpty() ? "0" : e.args().size() + ":" + CorpusFiles.sha256(String.join(",", e.args())).substring(0, 16))
                    + "\t" + e.field("outcome") + "\t" + shortField(e.field("value")) + "\t" + e.field("traces").replace(' ', '/')
                    + "\t" + e.field("error") + "\t" + e.field("consumed").replace(' ', '/') + "\t"
                    + (matches == null ? "-" : matches.size() + ":" + matches.getFirst().substring(0, 16)
                    + matches.getFirst().substring(matches.getFirst().indexOf('/'))) + "\t"
                    + replays.getOrDefault(e.key(), "-") + "\t" + e.origins().first()
                    + (e.origins().size() > 1 ? " +" + (e.origins().size() - 1) : ""));
        }
        return lines;
    }

    /** The recorded cost configuration: {@code -} (none recorded), {@code default}, {@code unknown}, or each entry. */
    private static String configText(Evaluation e) {
        if (e.configs().isEmpty()) return "-";
        return String.join(",", e.configs().stream().map(c -> {
            String[] parts = c.split(" ");
            return parts.length == 3 ? parts[0] + ":PV" + parts[1] + ":" + parts[2].substring(0, 16) : c;
        }).toList());
    }

    private static String shortField(String value) {
        String[] parts = value.split(" ");
        String digest = parts[0].length() > 16 ? parts[0].substring(0, 16) : parts[0];
        return parts.length > 1 ? digest + ":" + parts[1] : digest;
    }
}
