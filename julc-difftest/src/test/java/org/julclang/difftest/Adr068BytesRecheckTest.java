package org.julclang.difftest;

import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.cbor.PlutusDataCborDecoder;
import org.julclang.core.corpus.CorpusFiles;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;
import org.julclang.vm.JulcVm;
import org.julclang.vm.OptimizationCostProfiles;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * ADR-068 M5a, part 3 ({@code adr/evidence/068-m5a.md}): the D13 class of a {@code DIFFERENT-bytes} or {@code DIFFERENT-pir}
 * row, settled by running its program rather than by the test that recorded it. Each listed case is run on the JVM and on
 * both frontends at the four levels, on every input the recording evaluated either frontend's program with (matched by
 * FLAT digest), on fixed samples of its parameter types, and on {@link #RANDOM_INPUTS} inputs drawn at random from them,
 * with a seed taken from the case's key (so a run is repeatable). The class follows D13:
 * <ul>
 *   <li>a {@code JAVAC} value that is not the JVM's is a {@code FINDING};
 *   <li>a {@code JAVAC} script that fails where the JVM returns, while the reference gives the JVM's answer, is
 *       {@code FAILS-CLOSED}: never a value, and a candidate for "Policy rejection at run time" only under a named decision
 *       with a control program (r17), by hand;
 *   <li>a reference answer that is not the JVM's is a Semantic fix;
 *   <li>both frontends departing from the JVM in the same way is {@code ALIKE}: a visible outcome, settled by hand with the
 *       shared divergence named, never folded into Cost change or Representation only;
 *   <li>else the frontends agree with the JVM on every input; only then, and only when at least {@link #ENOUGH_VALUES}
 *       inputs reach a JVM value (or every input of the method's finite input space was run), a cpu, memory or script size
 *       that differs is a Cost change, and equal ones are Representation only. Fewer values is {@code FEW-VALUES}: the
 *       inputs did not reach the program's value path, so they cannot show that the reference gives the JVM's value there.
 * </ul>
 * A case the JVM cannot run (a validator, a pool of sources) is compared between the frontends alone, and is never proposed
 * as Cost change or Representation only: {@code BY-HAND}.
 * <p>
 * Run by {@code ./gradlew :julc-difftest:adr068Recheck -Padr068.corpus.head=<recording> -Padr068.recheck.keys=<tsv>
 * -Padr068.out=<dir>}; never part of {@code test}. The keys file has a key and a label per line.
 */
@Tag("adr068-recheck")
class Adr068BytesRecheckTest {

    private static final Path REPO = Path.of(System.getProperty("adr068.repo", "..")).toAbsolutePath().normalize();
    private static final int MOST_INPUTS = 12;
    private static final int MOST_RECORDED = 200;
    /** Inputs drawn at random per case, beyond the recorded ones and the fixed samples. */
    static final int RANDOM_INPUTS = 200;
    /**
     * The fewest inputs on which the JVM returns a value before a row may be Cost change or Representation only: a row whose
     * inputs mostly fail on the JVM has not shown that the reference gives the JVM's value (M5a confirmation review, F1).
     */
    static final int ENOUGH_VALUES = 10;

    @Test
    void eachRowsClassByItsPrograms() throws IOException {
        Path recording = Path.of(System.getProperty("adr068.corpus.head"));
        Path keys = Path.of(System.getProperty("adr068.recheck.keys"));
        Path out = Path.of(System.getProperty("adr068.out"));
        Files.createDirectories(out);
        var corpus = new Adr068Corpus(REPO);
        corpus.addRecorded(recording, "head");
        var evaluated = evaluatedInputs(recording);
        var configs = new ArrayList<>(DiffConfig.allLevels(CompilerFrontend.JAVAC));
        configs.addAll(DiffConfig.allLevels(CompilerFrontend.JAVAPARSER));
        var runner = new DiffRunner(configs, 20_000);
        var lines = new ArrayList<String>();
        lines.add("key\tlabel\torigin\tinputs\tjavac\treference\tbudget\tclass\tnote");
        lines.add("# inputs: run (recorded, fixed, random; JVM values); budget: runs where JAVAC's cpu or memory grows or shrinks"
                + " against the reference's, and JAVAC's script size minus the reference's per level");
        for (String line : Files.readAllLines(keys)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] parts = line.split("\t");
            var c = corpus.cases.get(parts[0]);
            String label = parts.length > 1 ? parts[1] : "-";
            String row = c == null ? parts[0] + "\t" + label + "\t-\t-\t-\t-\t-\tABSENT\t-" : recheck(c, label, evaluated, runner, recording);
            lines.add(row);
            System.out.println(row);
        }
        Files.write(out.resolve("bytes-recheck.tsv"), lines);
    }

    private static String recheck(Adr068Corpus.Case c, String label, Map<String, List<List<String>>> evaluated, DiffRunner runner,
                                  Path recording) {
        String head = c.key + "\t" + label + "\t" + c.origins.first();
        if (c.entry.equals("JavaLibraryProvider")) return head + "\t-\t-\t-\t-\tBY-HAND\ta library provider: no program to run";
        if (!c.entry.equals("compileMethod") || !c.libraries.isEmpty() || !c.lookup.equals(Adr068Corpus.STDLIB_LOOKUP)) {
            return head + "\t" + frontendsAlone(c, evaluated, recording);
        }
        String source = c.main.text();
        var inputs = new LinkedHashSet<List<PlutusData>>();
        int recorded = 0;
        var refused = new TreeSet<String>();
        // Script size per level and frontend: D13's budgets are cpu, memory and script size.
        var sizes = new LinkedHashMap<String, Integer>();
        for (var frontend : List.of(CompilerFrontend.JAVAC, CompilerFrontend.JAVAPARSER)) {
            for (var level : OptimizationLevel.values()) {
                try {
                    var program = new JulcCompiler(Adr068Ledger.lookup(c.lookup), Adr068Ledger.options(c, level, c.sourceMap)
                            .setFrontend(frontend)).compileMethod(source, c.method).program();
                    byte[] flat = UplcFlatEncoder.encodeProgram(program);
                    sizes.put(frontend + " " + level, flat.length);
                    String digest = CorpusFiles.sha256(flat).substring(0, 32);
                    for (var args : evaluated.getOrDefault(digest, List.of())) {
                        if (recorded >= MOST_RECORDED) break;
                        var decoded = new ArrayList<PlutusData>();
                        for (String arg : args) {
                            decoded.add(PlutusDataCborDecoder.decode(Files.readAllBytes(recording.resolve("data").resolve(arg + ".cbor"))));
                        }
                        if (inputs.add(decoded)) recorded++;
                    }
                } catch (RuntimeException | IOException e) {
                    refused.add(frontend + " " + level + ": " + oneLine(String.valueOf(e.getMessage())));
                }
            }
        }
        if (!refused.isEmpty()) return head + "\t-\t-\t-\t-\tBY-HAND\tdoes not compile here: " + refused.first();
        // With a source map too, as I1 compares both settings: the script a debug build deploys.
        for (var frontend : List.of(CompilerFrontend.JAVAC, CompilerFrontend.JAVAPARSER)) {
            for (var level : OptimizationLevel.values()) {
                if (c.sourceMap) break;
                try {
                    var program = new JulcCompiler(Adr068Ledger.lookup(c.lookup), Adr068Ledger.options(c, level, true)
                            .setFrontend(frontend)).compileMethod(source, c.method).program();
                    sizes.put(frontend + " " + setting(level, true), UplcFlatEncoder.encodeProgram(program).length);
                } catch (RuntimeException e) {
                    return head + "\t-\t-\t-\t-\tBY-HAND\tdoes not compile with a map: " + frontend + " " + level + ": "
                            + oneLine(String.valueOf(e.getMessage()));
                }
            }
        }
        var sizeDifferences = new ArrayList<String>();
        for (var entry : sizes.entrySet()) {
            if (!entry.getKey().startsWith("JAVAC ")) continue;
            String level = entry.getKey().substring(6);
            int difference = entry.getValue() - sizes.get("JAVAPARSER " + level);
            if (difference != 0) sizeDifferences.add(level + " " + (difference > 0 ? "+" : "") + difference);
        }
        int fixed = 0;
        for (var args : drawn(source, c.method)) if (fixed < MOST_INPUTS && inputs.add(args)) fixed++;
        int random = 0;
        var generators = generators(source, c.method);
        if (generators != null) {
            var rnd = new Random(seed(c.key));
            for (int i = 0; i < RANDOM_INPUTS; i++) {
                var args = new ArrayList<PlutusData>();
                for (var g : generators) args.add(g.draw(rnd));
                if (inputs.add(args)) random++;
            }
        }
        boolean exhaustive = generators != null && parameterTypes(source, c.method).stream().allMatch(BOOLEANS::contains);
        String className = className(source);
        TagTable tags;
        try {
            tags = tags(source, className);
        } catch (RuntimeException e) {
            tags = TagTable.EMPTY;
        }
        String ledgerType = ledgerDecoder(source);
        int usable = 0, values = 0, grows = 0, shrinks = 0;
        long[] spread = {Long.MAX_VALUE, Long.MIN_VALUE};
        // What each frontend did against the JVM, over every input and level: agree, differs (from the JVM and the other
        // frontend), alike (from the JVM, as the other frontend does), strict (fails where the JVM's decoder ignored a part),
        // fails (JAVAC's script fails where the JVM returns, and the reference gives the JVM's value).
        var javac = new TreeSet<String>();
        var reference = new TreeSet<String>();
        String note = "-";
        for (var args : inputs) {
            DiffRunner.Report report;
            try {
                report = runner.run(new DiffCase(c.key.substring(0, 16), source, className, c.method, args, tags, null));
            } catch (RuntimeException e) {
                if (note.equals("-")) note = "harness: " + oneLine(String.valueOf(e.getMessage()));
                continue;
            }
            if (!(report.jvm() instanceof Observation.Value) && !(report.jvm() instanceof Observation.Failure)) {
                if (note.equals("-")) note = "JVM: " + oneLine(String.valueOf(report.jvm()));
                continue;
            }
            usable++;
            if (report.jvm() instanceof Observation.Value) values++;
            boolean ignoresAPart = ledgerType != null && report.jvm() instanceof Observation.Value && !decodesExactly(ledgerType, args);
            var byLevel = new LinkedHashMap<OptimizationLevel, DiffRunner.Result>();
            for (var entry : report.results().entrySet()) {
                if (entry.getKey().frontend() == CompilerFrontend.JAVAC) byLevel.put(entry.getKey().level(), entry.getValue());
            }
            for (var entry : report.results().entrySet()) {
                var config = entry.getKey();
                if (config.frontend() != CompilerFrontend.JAVAPARSER) continue;
                var ref = entry.getValue();
                var mine = byLevel.get(config.level());
                for (int side = 0; side < 2; side++) {
                    var result = side == 0 ? mine : ref;
                    var other = side == 0 ? ref : mine;
                    String what;
                    // A result of another representation than the declared type's is a different value (ADR-066 D4).
                    if (result.uplc() instanceof Observation.Crash crash && crash.detail().startsWith("result: representation")) what = "differs";
                    else if (result.uplc() instanceof Observation.Crash || result.uplc() instanceof Observation.Inconclusive) what = "unobserved";
                    else if (ignoresAPart) what = result.uplc() instanceof Observation.Failure ? "strict" : "lenient";
                    else if (result.verdict().kind() == Verdict.Kind.AGREE) what = "agree";
                    else if (result.uplc().equals(other.uplc())) what = "alike";
                    else if (side == 0 && report.jvm() instanceof Observation.Value && result.uplc() instanceof Observation.Failure
                            && other.verdict().kind() == Verdict.Kind.AGREE) what = "fails";
                    else what = "differs";
                    (side == 0 ? javac : reference).add(what);
                    if (!what.equals("agree") && !what.equals("strict") && (note.equals("-") || side == 0 && !note.startsWith("JAVAC"))) {
                        note = (side == 0 ? "JAVAC " : "JAVAPARSER ") + config.level() + " " + what + " on " + args + ": "
                                + oneLine(result.verdict().toString());
                    }
                }
                if (mine.measurement() == null || ref.measurement() == null) continue;
                var a = mine.measurement();
                var b = ref.measurement();
                if (a.cpu() > b.cpu() || a.memory() > b.memory()) grows++;
                else if (a.cpu() < b.cpu() || a.memory() < b.memory()) shrinks++;
                spread[0] = Math.min(spread[0], a.cpu() - b.cpu());
                spread[1] = Math.max(spread[1], a.cpu() - b.cpu());
            }
        }
        String budget = grows + " grow/" + shrinks + " shrink" + (spread[0] == Long.MAX_VALUE ? "" : ", cpu " + spread[0] + ".." + spread[1])
                + ", size " + (sizeDifferences.isEmpty() ? "equal" : String.join(" ", sizeDifferences));
        String kind = classOf(usable, javac, reference, values, exhaustive, grows + shrinks > 0 || !sizeDifferences.isEmpty());
        return head + "\t" + usable + " (" + recorded + " recorded, " + fixed + " fixed, " + random + " random; " + values
                + " JVM values" + (exhaustive ? ", every input" : "") + ")\t" + String.join(",", javac) + "\t"
                + String.join(",", reference) + "\t" + budget + "\t" + kind + "\t" + note;
    }

    /**
     * The class a row's runs propose (see the class comment). {@code javac} and {@code reference} are what each frontend did
     * against the JVM over every input and level; {@code values} the inputs on which the JVM returned a value;
     * {@code exhaustive} whether every input of the method's finite input space ran; {@code budgetsDiffer} whether a cpu,
     * memory or script size differs between the frontends.
     */
    static String classOf(int usable, Set<String> javac, Set<String> reference, int values, boolean exhaustive, boolean budgetsDiffer) {
        if (usable == 0) return "NO-INPUT";
        if (javac.contains("unobserved") || reference.contains("unobserved")) return "BY-HAND";
        if (javac.contains("differs") || javac.contains("lenient")) return "FINDING";
        if (javac.contains("fails")) return "FAILS-CLOSED";
        if (reference.contains("differs")) return "Semantic fix";
        if (reference.contains("lenient")) return "UPLC-only semantic fix";
        if (javac.contains("alike") || reference.contains("alike")) return "ALIKE";
        if (values < ENOUGH_VALUES && !exhaustive) return "FEW-VALUES";
        return budgetsDiffer ? "Cost change" : "Representation only";
    }

    /**
     * A case the JVM side cannot run (a validator, a pool of sources, no lookup): both frontends' programs at each level, on
     * every input the recording evaluated either with, against each other. {@code same} when every outcome, value and trace
     * is equal. Either way the class is decided by hand: no JVM ran, so nothing shows the inputs reach the JVM's value.
     */
    private static String frontendsAlone(Adr068Corpus.Case c, Map<String, List<List<String>>> evaluated, Path recording) {
        var programs = new LinkedHashMap<String, Program>();
        var inputs = new LinkedHashSet<List<PlutusData>>();
        try {
            for (var frontend : List.of(CompilerFrontend.JAVAC, CompilerFrontend.JAVAPARSER)) {
                for (var level : OptimizationLevel.values()) {
                  for (boolean map : sourceMapSettings(c)) {
                    var program = Adr068Ledger.invoke(c, Adr068Ledger.options(c, level, map).setFrontend(frontend)).program();
                    programs.put(frontend + " " + setting(level, map), program);
                    String digest = CorpusFiles.sha256(UplcFlatEncoder.encodeProgram(program)).substring(0, 32);
                    for (var args : evaluated.getOrDefault(digest, List.of())) {
                        if (inputs.size() >= MOST_RECORDED) break;
                        var decoded = new ArrayList<PlutusData>();
                        for (String arg : args) {
                            decoded.add(PlutusDataCborDecoder.decode(Files.readAllBytes(recording.resolve("data").resolve(arg + ".cbor"))));
                        }
                        inputs.add(decoded);
                    }
                  }
                }
            }
        } catch (RuntimeException | IOException e) {
            return "-\t-\t-\t-\tBY-HAND\tdoes not compile here: " + oneLine(String.valueOf(e.getMessage()));
        }
        if (inputs.isEmpty() && c.entry.equals("compileMethod")
                && Pattern.compile("\\b" + Pattern.quote(c.method) + "\\s*\\(\\s*\\)").matcher(c.main.text()).find()) {
            inputs.add(List.of());
        }
        if (inputs.isEmpty()) return "0\t-\t-\t-\tNO-INPUT\tnothing recorded evaluated either program";
        // The PV11 cost model and builtins, as the harness's VM has them (UplcExecutor).
        var vm = JulcVm.create("Java");
        var profile = OptimizationCostProfiles.PLUTUS_V3_PV11_COSTS_V1;
        vm.setCostModelParams(profile.costModelParameters(), profile.target());
        var budget = new ExBudget(10_000_000_000L, 14_000_000L);
        int grows = 0, shrinks = 0;
        long[] spread = {Long.MAX_VALUE, Long.MIN_VALUE};
        boolean same = true;
        String note = "-";
        var settings = new ArrayList<String>();
        for (var level : OptimizationLevel.values()) for (boolean map : sourceMapSettings(c)) settings.add(setting(level, map));
        for (var args : inputs) {
            for (String level : settings) {
                var mine = vm.evaluateWithArgs(programs.get("JAVAC " + level), args, budget);
                var theirs = vm.evaluateWithArgs(programs.get("JAVAPARSER " + level), args, budget);
                if (!outcome(mine).equals(outcome(theirs))) {
                    same = false;
                    if (note.equals("-")) note = level + " on " + args + ": JAVAC " + oneLine(outcome(mine)) + "; JAVAPARSER " + oneLine(outcome(theirs));
                }
                var a = mine.budgetConsumed();
                var b = theirs.budgetConsumed();
                if (a.cpuSteps() > b.cpuSteps() || a.memoryUnits() > b.memoryUnits()) grows++;
                else if (a.cpuSteps() < b.cpuSteps() || a.memoryUnits() < b.memoryUnits()) shrinks++;
                spread[0] = Math.min(spread[0], a.cpuSteps() - b.cpuSteps());
                spread[1] = Math.max(spread[1], a.cpuSteps() - b.cpuSteps());
            }
        }
        var sizeDifferences = new ArrayList<String>();
        for (String level : settings) {
            int difference = UplcFlatEncoder.encodeProgram(programs.get("JAVAC " + level)).length
                    - UplcFlatEncoder.encodeProgram(programs.get("JAVAPARSER " + level)).length;
            if (difference != 0) sizeDifferences.add(level + " " + (difference > 0 ? "+" : "") + difference);
        }
        // No JVM ran, so no input is known to reach the JVM's value: never Cost change or Representation only by itself.
        return inputs.size() + " recorded, frontends alone; 0 JVM values\t" + (same ? "same" : "differs") + "\t-\t" + grows + " grow/"
                + shrinks + " shrink, cpu " + spread[0] + ".." + spread[1] + ", size "
                + (sizeDifferences.isEmpty() ? "equal" : String.join(" ", sizeDifferences)) + "\tBY-HAND\t" + note;
    }

    /** The source-map settings I1 compares: both, for a program; a provider's own. */
    private static List<Boolean> sourceMapSettings(Adr068Corpus.Case c) {
        return Adr068Ledger.isProgramEntry(c.entry) ? List.of(false, true) : List.of(c.sourceMap);
    }

    private static String setting(OptimizationLevel level, boolean map) {
        return level + (map ? "+map" : "");
    }

    private static String outcome(EvalResult result) {
        return switch (result) {
            case EvalResult.Success s -> "value " + s.resultTerm() + " traces " + s.traces();
            case EvalResult.Failure f -> "failure traces " + f.traces();
            case EvalResult.BudgetExhausted b -> "budget exhausted";
        };
    }

    /** The ledger type whose {@code fromPlutusData} the source calls on its parameter, as the ledger property tests write it. */
    private static String ledgerDecoder(String source) {
        Matcher m = Pattern.compile("import org\\.julclang\\.ledger\\.(\\w+);[\\s\\S]*\\b\\1\\.fromPlutusData\\(d\\)").matcher(source);
        return m.find() ? m.group(1) : null;
    }

    private static boolean decodesExactly(String type, List<PlutusData> args) {
        try {
            return Adr068LedgerFromDataPropertyTest.decodesExactly(type, args.getFirst());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String oneLine(String text) {
        String flat = text.replaceAll("\\s+", " ");
        return flat.length() > 1200 ? flat.substring(0, 1200) + "…" : flat;
    }

    /** Every input the recording evaluated a program with, by the first 32 hex digits of the program's FLAT digest. */
    private static Map<String, List<List<String>>> evaluatedInputs(Path recording) throws IOException {
        var index = new LinkedHashMap<String, List<List<String>>>();
        try (Stream<Path> list = Files.list(recording.resolve("evaluations"))) {
            for (Path dir : list.sorted().toList()) {
                String program = null;
                var args = new ArrayList<String>();
                for (String line : Files.readAllLines(dir.resolve("evaluation.txt"))) {
                    if (line.startsWith("program: ")) program = line.substring(9).split(" ")[0];
                    if (line.startsWith("arg: ")) args.add(line.substring(5));
                }
                if (program == null || program.length() < 32) continue;
                var known = index.computeIfAbsent(program.substring(0, 32), p -> new ArrayList<>());
                if (!known.contains(args)) known.add(args);
            }
        }
        return index;
    }

    /** The binary name of the source's first top-level type. */
    static String className(String source) {
        Matcher p = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE).matcher(source);
        String prefix = p.find() ? p.group(1) + "." : "";
        Matcher m = Pattern.compile("\\b(?:class|record|interface|enum)\\s+(\\w+)").matcher(source);
        while (m.find()) {
            if (depth(source, m.start()) == 0) return prefix + m.group(1);
        }
        return prefix + "Case";
    }

    private static int depth(String source, int at) {
        int depth = 0;
        for (int i = 0; i < at; i++) {
            if (source.charAt(i) == '{') depth++;
            else if (source.charAt(i) == '}') depth--;
        }
        return depth;
    }

    // --- Inputs drawn from simple parameter types ---

    private static final Map<String, List<PlutusData>> SAMPLES = new LinkedHashMap<>();

    static {
        var integers = List.<PlutusData>of(integer(-2), integer(0), integer(1), integer(7));
        for (String t : List.of("BigInteger", "long", "int", "Long", "Integer")) SAMPLES.put(t, integers);
        var booleans = List.<PlutusData>of(new PlutusData.ConstrData(0, List.of()), new PlutusData.ConstrData(1, List.of()));
        SAMPLES.put("boolean", booleans);
        SAMPLES.put("Boolean", booleans);
        SAMPLES.put("byte[]", List.of(new PlutusData.BytesData(new byte[0]), new PlutusData.BytesData(new byte[]{1, 2, 3})));
        SAMPLES.put("PlutusData", List.of(integer(3), new PlutusData.BytesData(new byte[]{9})));
        var lists = List.<PlutusData>of(new PlutusData.ListData(List.of()),
                new PlutusData.ListData(List.of(integer(1), integer(-2), integer(3))), new PlutusData.ListData(List.of(integer(5))));
        for (String t : List.of("JulcList<BigInteger>", "JulcList<Long>", "List<BigInteger>", "JulcList", "JulcList<PlutusData>")) {
            SAMPLES.put(t, lists);
        }
    }

    private static PlutusData integer(long v) {
        return new PlutusData.IntData(BigInteger.valueOf(v));
    }

    /** Inputs for {@code method} when every parameter has a simple type, else none. */
    static List<List<PlutusData>> drawn(String source, String method) {
        Matcher m = Pattern.compile("static\\s+[\\w<>\\[\\], ?]+?\\s+" + Pattern.quote(method) + "\\s*\\(([^)]*)\\)").matcher(source);
        if (!m.find()) return List.of();
        var types = new ArrayList<List<PlutusData>>();
        String params = m.group(1).trim();
        if (!params.isEmpty()) {
            for (String param : splitTopLevel(params)) {
                String p = param.trim().replaceAll("^final\\s+", "");
                String type = p.substring(0, p.lastIndexOf(' ')).replace(" ", "");
                var samples = SAMPLES.get(type);
                if (samples == null) return List.of();
                types.add(samples);
            }
        }
        var all = new ArrayList<List<PlutusData>>();
        all.add(new ArrayList<>());
        for (var samples : types) {
            var next = new ArrayList<List<PlutusData>>();
            for (var prefix : all) {
                for (var s : types.size() > 2 ? samples.subList(0, 2) : samples) {
                    var extended = new ArrayList<>(prefix);
                    extended.add(s);
                    next.add(extended);
                }
            }
            all = next;
        }
        return all.size() > MOST_INPUTS ? all.subList(0, MOST_INPUTS) : all;
    }

    // --- Inputs drawn at random, with a seed from the case's key ---

    /** One parameter's random Data, as the harness maps it to the parameter's Java type. */
    interface Generator {
        PlutusData draw(Random random);
    }

    static final Set<String> BOOLEANS = Set.of("boolean", "Boolean");

    /** The seed of a case's random inputs: its key's first 60 bits, so a rerun draws the same inputs. */
    static long seed(String key) {
        return Long.parseLong(key.substring(0, 15), 16);
    }

    /** The declared parameter types of {@code method}, spaces removed, or none when the signature is not found. */
    static List<String> parameterTypes(String source, String method) {
        Matcher m = Pattern.compile("static\\s+[\\w<>\\[\\], ?.]+?\\s+" + Pattern.quote(method) + "\\s*\\(([^)]*)\\)").matcher(source);
        if (!m.find()) return List.of();
        var types = new ArrayList<String>();
        String params = m.group(1).trim();
        if (params.isEmpty()) return types;
        for (String param : splitTopLevel(params)) {
            String p = param.trim().replaceAll("^final\\s+", "").replaceAll("^@\\w+\\s+", "");
            types.add(p.substring(0, p.lastIndexOf(' ')).replace(" ", ""));
        }
        return types;
    }

    /**
     * A generator per parameter of {@code method}, or null when the signature is not found or a type has none. Integers are
     * drawn from -4 to 8, and one in eight from -150 to 150, lists and maps have 0 to 5 elements (a map's keys distinct), byte strings 0 to 3 bytes (a key hash
     * 28), and a record or sealed interface the source declares is the constructor of one of its records, with tags in
     * declaration order as {@link #tags} gives them. A {@code @NewType} has no constructor to draw, so none.
     */
    static List<Generator> generators(String source, String method) {
        Matcher m = Pattern.compile("static\\s+[\\w<>\\[\\], ?.]+?\\s+" + Pattern.quote(method) + "\\s*\\(([^)]*)\\)").matcher(source);
        if (!m.find()) return null;
        var generators = new ArrayList<Generator>();
        for (String type : parameterTypes(source, method)) {
            var g = generator(type, source, 0);
            if (g == null) return null;
            generators.add(g);
        }
        return generators;
    }

    private static Generator generator(String type, String source, int depth) {
        if (depth > 8) return null;
        switch (type) {
            case "BigInteger", "long", "int", "Long", "Integer" -> {
                return r -> integer(r.nextInt(8) == 0 ? r.nextInt(301) - 150 : r.nextInt(13) - 4);
            }
            case "boolean", "Boolean" -> {
                return r -> new PlutusData.ConstrData(r.nextBoolean() ? 1 : 0, List.of());
            }
            case "byte[]", "TokenName" -> {
                return r -> bytes(r, r.nextInt(4));
            }
            case "PubKeyHash", "ScriptHash", "ValidatorHash", "PolicyId" -> {
                return r -> bytes(r, 28);
            }
            case "String" -> {
                return r -> new PlutusData.BytesData(("s" + r.nextInt(5)).repeat(r.nextInt(3)).getBytes(StandardCharsets.UTF_8));
            }
            case "PlutusData" -> {
                return r -> switch (r.nextInt(4)) {
                    case 0 -> integer(r.nextInt(13) - 4);
                    case 1 -> bytes(r, r.nextInt(3));
                    case 2 -> new PlutusData.ConstrData(r.nextInt(2), List.of(integer(r.nextInt(5))));
                    default -> new PlutusData.ListData(List.of(integer(r.nextInt(5))));
                };
            }
            default -> {
            }
        }
        Matcher generic = Pattern.compile("(JulcList|List|JulcMap|Map|Optional)<(.+)>").matcher(type);
        if (generic.matches()) {
            var arguments = splitTopLevel(generic.group(2));
            switch (generic.group(1)) {
                case "JulcList", "List" -> {
                    var element = generator(arguments.getFirst(), source, depth + 1);
                    if (element == null || arguments.size() != 1) return null;
                    return r -> {
                        var items = new ArrayList<PlutusData>();
                        for (int i = r.nextInt(6); i > 0; i--) items.add(element.draw(r));
                        return new PlutusData.ListData(items);
                    };
                }
                case "JulcMap", "Map" -> {
                    if (arguments.size() != 2) return null;
                    var key = generator(arguments.get(0), source, depth + 1);
                    var value = generator(arguments.get(1), source, depth + 1);
                    if (key == null || value == null) return null;
                    return r -> {
                        var keys = new LinkedHashSet<PlutusData>();
                        var entries = new ArrayList<PlutusData.Pair>();
                        for (int i = r.nextInt(6); i > 0; i--) {
                            var k = key.draw(r);
                            if (keys.add(k)) entries.add(new PlutusData.Pair(k, value.draw(r)));
                        }
                        return new PlutusData.MapData(entries);
                    };
                }
                default -> {
                    var element = generator(arguments.getFirst(), source, depth + 1);
                    if (element == null || arguments.size() != 1) return null;
                    return r -> r.nextInt(3) == 0 ? new PlutusData.ConstrData(1, List.of())
                            : new PlutusData.ConstrData(0, List.of(element.draw(r)));
                }
            }
        }
        return declared(type, source, depth);
    }

    /** A record or sealed interface of the source, by its simple or dotted name. */
    private static Generator declared(String type, String source, int depth) {
        String simple = type.substring(type.lastIndexOf('.') + 1);
        Matcher record = Pattern.compile("(@NewType\\s+)?\\brecord\\s+" + Pattern.quote(simple) + "\\s*\\(([^)]*)\\)").matcher(source);
        if (record.find()) {
            if (record.group(1) != null) return null;
            var fields = new ArrayList<Generator>();
            String components = record.group(2).trim();
            if (!components.isEmpty()) {
                for (String component : splitTopLevel(components)) {
                    String p = component.trim().replaceAll("^@\\w+\\s+", "");
                    var g = generator(p.substring(0, p.lastIndexOf(' ')).replace(" ", ""), source, depth + 1);
                    if (g == null) return null;
                    fields.add(g);
                }
            }
            int tag = tagOf(simple, source);
            if (tag < 0) return null;
            return r -> {
                var values = new ArrayList<PlutusData>();
                for (var g : fields) values.add(g.draw(r));
                return new PlutusData.ConstrData(tag, values);
            };
        }
        if (Pattern.compile("sealed\\s+interface\\s+" + Pattern.quote(simple) + "\\b").matcher(source).find()) {
            var variants = new ArrayList<Generator>();
            Matcher variant = Pattern.compile("\\brecord\\s+(\\w+)\\s*\\([^)]*\\)\\s*implements\\s+([\\w\\s,.<>]+?)\\s*\\{").matcher(source);
            while (variant.find()) {
                boolean implementsIt = false;
                for (String t : variant.group(2).split(",")) {
                    String name = t.trim().replaceAll("<.*", "");
                    if (name.substring(name.lastIndexOf('.') + 1).equals(simple)) implementsIt = true;
                }
                if (!implementsIt) continue;
                var g = declared(variant.group(1), source, depth + 1);
                if (g == null) return null;
                variants.add(g);
            }
            if (variants.isEmpty()) return null;
            return r -> variants.get(r.nextInt(variants.size())).draw(r);
        }
        return null;
    }

    /** A record's constructor tag: its index among its sealed interface's records, or 0 for a record of no interface. */
    private static int tagOf(String record, String source) {
        try {
            for (var entry : tags(source, className(source)).entries()) {
                String name = entry.record();
                if (name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1).equals(record)) return entry.tag();
            }
        } catch (RuntimeException e) {
            return -1;
        }
        return 0;
    }

    private static PlutusData bytes(Random random, int length) {
        byte[] b = new byte[length];
        random.nextBytes(b);
        return new PlutusData.BytesData(b);
    }

    private static List<String> splitTopLevel(String params) {
        var parts = new ArrayList<String>();
        int depth = 0, start = 0;
        for (int i = 0; i < params.length(); i++) {
            char ch = params.charAt(i);
            if (ch == '<') depth++;
            else if (ch == '>') depth--;
            else if (ch == ',' && depth == 0) {
                parts.add(params.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(params.substring(start));
        return parts;
    }

    // --- Tags of the source's sealed interfaces, in declaration order ---

    private record Declaration(String kind, String name, int body, int end, String parent, List<String> supertypes) {
    }

    /** A tag table: each record that implements a sealed interface of the source gets its index among that interface's records. */
    static TagTable tags(String source, String className) {
        var declarations = new ArrayList<Declaration>();
        Matcher m = Pattern.compile("\\b(class|interface|record|enum)\\s+(\\w+)").matcher(source);
        while (m.find()) {
            int body = source.indexOf('{', m.end());
            if (body < 0) continue;
            String header = source.substring(m.end(), body);
            if (m.group(1).equals("record")) {
                int close = matchingParen(source, source.indexOf('(', m.end()));
                if (close < 0) continue;
                body = source.indexOf('{', close);
                header = source.substring(close + 1, body);
            }
            var supers = new ArrayList<String>();
            Matcher s = Pattern.compile("(?:implements|extends)\\s+([\\w\\s,.<>]+?)(?:\\bpermits\\b|$)").matcher(header.trim());
            if (s.find()) for (String t : s.group(1).split(",")) supers.add(t.trim().replaceAll("<.*", ""));
            declarations.add(new Declaration(m.group(1) + (isSealed(source, m.start()) ? "+sealed" : ""), m.group(2), body,
                    matchingBrace(source, body), null, supers));
        }
        var binary = new LinkedHashMap<Declaration, String>();
        String prefix = className.contains(".") ? className.substring(0, className.lastIndexOf('.') + 1) : "";
        for (var d : declarations) binary.put(d, prefix + innermostName(declarations, d));
        var entries = new ArrayList<TagTable.Entry>();
        for (var parent : declarations) {
            if (!parent.kind().equals("interface+sealed")) continue;
            int tag = 0;
            for (var d : declarations) {
                if (d.kind().startsWith("record") && d.supertypes().contains(parent.name())) {
                    entries.add(new TagTable.Entry(binary.get(d), binary.get(parent), tag++));
                }
            }
        }
        return new TagTable(entries);
    }

    private static String innermostName(List<Declaration> declarations, Declaration d) {
        var chain = new ArrayList<String>();
        chain.add(d.name());
        Declaration current = d;
        while (true) {
            Declaration enclosing = null;
            for (var outer : declarations) {
                if (outer != current && outer.body() < current.body() && outer.end() > current.end()
                        && (enclosing == null || outer.body() > enclosing.body())) {
                    enclosing = outer;
                }
            }
            if (enclosing == null) break;
            chain.addFirst(enclosing.name());
            current = enclosing;
        }
        return String.join("$", chain);
    }

    private static boolean isSealed(String source, int at) {
        int lineStart = Math.max(source.lastIndexOf('\n', at), Math.max(source.lastIndexOf(';', at), source.lastIndexOf('{', at)));
        return source.substring(lineStart + 1, at).contains("sealed") && !source.substring(lineStart + 1, at).contains("non-sealed");
    }

    private static int matchingParen(String source, int open) {
        if (open < 0) return -1;
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            if (source.charAt(i) == '(') depth++;
            else if (source.charAt(i) == ')' && --depth == 0) return i;
        }
        return -1;
    }

    private static int matchingBrace(String source, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            if (source.charAt(i) == '{') depth++;
            else if (source.charAt(i) == '}' && --depth == 0) return i;
        }
        return source.length();
    }
}
