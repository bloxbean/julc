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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * ADR-068 M5a, part 3 ({@code adr/evidence/068-m5a.md}): the D13 class of a {@code DIFFERENT-bytes} or {@code DIFFERENT-pir}
 * row, settled by running its program rather than by the test that recorded it. Each listed case is run on the JVM and on
 * both frontends at the four levels, on every input the recording evaluated either frontend's program with (matched by
 * FLAT digest) and on inputs drawn from its parameter types where they are simple. The class follows D13: a {@code JAVAC}
 * answer that is not the JVM's is a finding; else a reference answer that is not the JVM's is a Semantic fix; else a budget
 * that differs is a Cost change; else Representation only.
 * <p>
 * Run by {@code ./gradlew :julc-difftest:adr068Recheck -Padr068.corpus.head=<recording> -Padr068.recheck.keys=<tsv>
 * -Padr068.out=<dir>}; never part of {@code test}. The keys file has a key and a label per line.
 */
@Tag("adr068-recheck")
class Adr068BytesRecheckTest {

    private static final Path REPO = Path.of(System.getProperty("adr068.repo", "..")).toAbsolutePath().normalize();
    private static final int MOST_INPUTS = 12;
    private static final int MOST_RECORDED = 200;

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
        for (var frontend : List.of(CompilerFrontend.JAVAC, CompilerFrontend.JAVAPARSER)) {
            for (var level : OptimizationLevel.values()) {
                try {
                    var program = new JulcCompiler(Adr068Ledger.lookup(c.lookup), Adr068Ledger.options(c, level, c.sourceMap)
                            .setFrontend(frontend)).compileMethod(source, c.method).program();
                    String digest = CorpusFiles.sha256(UplcFlatEncoder.encodeProgram(program)).substring(0, 32);
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
        int generated = 0;
        for (var args : drawn(source, c.method)) if (generated < MOST_INPUTS && inputs.add(args)) generated++;
        String className = className(source);
        TagTable tags;
        try {
            tags = tags(source, className);
        } catch (RuntimeException e) {
            tags = TagTable.EMPTY;
        }
        String ledgerType = ledgerDecoder(source);
        int usable = 0, grows = 0, shrinks = 0;
        long[] spread = {Long.MAX_VALUE, Long.MIN_VALUE};
        // What each frontend did against the JVM, over every input and level: agree, differs (from the JVM and the other
        // frontend), alike (from the JVM, as the other frontend does), strict (fails where the JVM's decoder ignored a part).
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
                    else what = result.uplc().equals(other.uplc()) ? "alike" : "differs";
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
        String budget = grows + " grow/" + shrinks + " shrink" + (spread[0] == Long.MAX_VALUE ? "" : ", cpu " + spread[0] + ".." + spread[1]);
        String kind;
        if (usable == 0) kind = "NO-INPUT";
        else if (javac.contains("unobserved") || reference.contains("unobserved")) kind = "BY-HAND";
        else if (javac.contains("differs") || javac.contains("lenient")) kind = "FINDING";
        else if (reference.contains("differs")) kind = "Semantic fix";
        else if (reference.contains("lenient")) kind = "UPLC-only semantic fix";
        else if (grows + shrinks > 0) kind = "Cost change";
        else kind = "Representation only";
        return head + "\t" + usable + " (" + recorded + " recorded, " + generated + " drawn)\t" + String.join(",", javac) + "\t"
                + String.join(",", reference) + "\t" + budget + "\t" + kind + "\t" + note;
    }

    /**
     * A case the JVM side cannot run (a validator, a pool of sources, no lookup): both frontends' programs at each level, on
     * every input the recording evaluated either with, against each other. {@code same} when every outcome, value and trace
     * is equal; else the class is the JVM's to decide, by hand.
     */
    private static String frontendsAlone(Adr068Corpus.Case c, Map<String, List<List<String>>> evaluated, Path recording) {
        var programs = new LinkedHashMap<String, Program>();
        var inputs = new LinkedHashSet<List<PlutusData>>();
        try {
            for (var frontend : List.of(CompilerFrontend.JAVAC, CompilerFrontend.JAVAPARSER)) {
                for (var level : OptimizationLevel.values()) {
                    var program = Adr068Ledger.invoke(c, Adr068Ledger.options(c, level, c.sourceMap).setFrontend(frontend)).program();
                    programs.put(frontend + " " + level, program);
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
        for (var args : inputs) {
            for (var level : OptimizationLevel.values()) {
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
        String kind = !same ? "BY-HAND" : grows + shrinks > 0 ? "Cost change" : "Representation only";
        return inputs.size() + " recorded, frontends alone\t" + (same ? "same" : "differs") + "\t-\t" + grows + " grow/" + shrinks
                + " shrink, cpu " + spread[0] + ".." + spread[1] + "\t" + kind + "\t" + note;
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
        return flat.length() > 300 ? flat.substring(0, 300) + "…" : flat;
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
