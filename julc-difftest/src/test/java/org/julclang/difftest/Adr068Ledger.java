package org.julclang.difftest;

import java.util.Set;
import java.util.TreeSet;
import java.util.HashSet;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import org.julclang.compiler.CompileResult;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JavaLibraryProvider;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.corpus.CorpusRecorder;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.pir.PirFormatter;
import org.julclang.compiler.pir.StdlibLookup;
import org.julclang.core.PlutusData;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;

import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * ADR-068 M0: the D13 ledger's reference rows, compiled with the JavaParser frontend (the reference, B0), and the
 * I2a rejection inventory derived from them. Every file it writes is sorted and holds digests, not programs, so two
 * runs over the same corpus write the same bytes.
 */
final class Adr068Ledger {

    static final List<OptimizationLevel> LEVELS = List.of(OptimizationLevel.values());

    /** What one compilation produced at one level. */
    record Outcome(boolean accepted, String digest, int size, String code, String message, int line, int column,
                   String exception) {

        /** A recorded case the ledger cannot compile again (a test's own stdlib lookup). */
        boolean unreplayable() {
            return "UnreplayableException".equals(exception);
        }

        /**
         * The ledger cell: {@code A:<flat>:<size>}, {@code R:<code>@<line>:<column>}, {@code R:uncoded/<message>@…},
         * or {@code U:<reason>} for a case that cannot be compiled again.
         */
        String cell() {
            if (accepted) return "A:" + digest + ":" + (size < 0 ? "-" : size);
            if (unreplayable()) return "U:" + message.replace(' ', '-');
            String what = code != null ? code : "uncoded/" + exception + "/" + short16(message);
            return "R:" + what + "@" + line + ":" + column;
        }
    }

    /** The result of one case: an outcome per level and the source-map digest. */
    record CaseResult(Map<OptimizationLevel, Outcome> outcomes, String sourceMap) {
    }

    private final Adr068Corpus corpus;
    private final Map<String, CaseResult> results = new ConcurrentHashMap<>();
    private final Map<String, String> ledgerTypeDigests = new TreeMap<>();

    Adr068Ledger(Adr068Corpus corpus) {
        this.corpus = corpus;
    }

    // --- Compilation ---

    void compileAll(int threads) throws Exception {
        precomputeLedgerTypes();
        var pool = new ForkJoinPool(threads);
        try {
            pool.submit(() -> corpus.sorted().parallelStream().forEach(c -> results.put(c.key, compile(c)))).get();
        } finally {
            pool.shutdown();
        }
    }

    private CaseResult compile(Adr068Corpus.Case c) {
        var outcomes = new LinkedHashMap<OptimizationLevel, Outcome>();
        for (var level : LEVELS) outcomes.put(level, compileAt(c, level, c.sourceMap));
        String sourceMap = "-";
        if (isProgramEntry(c.entry) && outcomes.values().stream().noneMatch(Outcome::unreplayable)) {
            try {
                sourceMap = sourceMapDigest(invoke(c, options(c, OptimizationLevel.DEFAULT, true)));
            } catch (Throwable t) {
                sourceMap = "R";
            }
        }
        return new CaseResult(outcomes, sourceMap);
    }

    private Outcome compileAt(Adr068Corpus.Case c, OptimizationLevel level, boolean sourceMap) {
        try {
            return switch (c.entry) {
                case "JavaLibraryProvider" -> {
                    var provider = new JavaLibraryProvider(texts(c.libraries), lookup(c.lookup),
                            options(c, level, false));
                    yield new Outcome(true, short32(describe(provider, c.libraries)), -1, null, null, 0, 0, null);
                }
                case "ledger-source" -> new Outcome(true, ledgerTypeDigests.getOrDefault(c.main.digest(), "none"),
                        -1, null, null, 0, 0, null);
                default -> {
                    var result = invoke(c, options(c, level, sourceMap));
                    byte[] flat = UplcFlatEncoder.encodeProgram(result.program());
                    yield new Outcome(true, short32(HexFormat.of().formatHex(flat)), flat.length, null, null, 0, 0, null);
                }
            };
        } catch (AssertionError e) {
            throw e;
        } catch (Throwable t) {
            return rejection(t);
        }
    }

    private static boolean isProgramEntry(String entry) {
        return !entry.equals("JavaLibraryProvider") && !entry.equals("ledger-source");
    }

    private static CompilerOptions options(Adr068Corpus.Case c, OptimizationLevel level, boolean sourceMap) {
        var options = new CompilerOptions().setOptimizationLevel(level).setSourceMapEnabled(sourceMap)
                .setLogger(message -> { });
        c.disabledRules.forEach(options::disableOptimizationRule);
        return options;
    }

    private static StdlibLookup lookup(String name) {
        return switch (name) {
            case "none" -> null;
            case Adr068Corpus.STDLIB_LOOKUP -> StdlibRegistry.defaultRegistry();
            default -> throw new UnreplayableException("custom stdlib lookup " + name);
        };
    }

    /** A recorded case the ledger cannot compile again, such as one built with a test's own stdlib lookup. */
    static final class UnreplayableException extends RuntimeException {
        UnreplayableException(String message) {
            super(message);
        }
    }

    private CompileResult invoke(Adr068Corpus.Case c, CompilerOptions options) throws IOException {
        var compiler = new JulcCompiler(lookup(c.lookup), options);
        var libraries = texts(c.libraries);
        return switch (c.entry) {
            case "compile", "compileWithDetails" -> compiler.compile(c.main.text(), libraries);
            case "compileContract", "compileContractWithDetails" ->
                    compiler.compileContract(c.main.text(), libraries).compileResult();
            case "compileForDebug" -> options.isSourceMapEnabled()
                    ? compiler.compileForDebug(c.main.text(), libraries).compileResult()
                    : compiler.compile(c.main.text(), libraries);
            case "compileMethod" -> compiler.compileMethod(c.main.text(), c.method, libraries);
            case "compilePath", "compileContractPath" -> withFiles(c, (main, libs) -> c.entry.equals("compilePath")
                    ? compiler.compile(main, libs) : compiler.compileContract(main, libs).compileResult());
            default -> throw new IllegalStateException("unknown entry " + c.entry);
        };
    }

    private interface PathCompile {
        CompileResult compile(Path main, List<Path> libraries) throws IOException;
    }

    /** Writes the case's sources under their file names into a fresh directory, compiles, and deletes it. */
    private static CompileResult withFiles(Adr068Corpus.Case c, PathCompile compile) throws IOException {
        Path dir = Files.createTempDirectory("adr068-");
        try {
            var used = new HashSet<String>();
            Path main = place(dir, c.main, used, 0);
            var libraries = new ArrayList<Path>();
            for (int i = 0; i < c.libraries.size(); i++) libraries.add(place(dir, c.libraries.get(i), used, i + 1));
            return compile.compile(main, libraries);
        } finally {
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }

    private static Path place(Path dir, Adr068Corpus.Source source, Set<String> used, int index)
            throws IOException {
        String name = source.fileName() == null ? "Source" + index + ".java" : source.fileName();
        Path target = used.add(name) ? dir.resolve(name) : dir.resolve("d" + index).resolve(name);
        Files.createDirectories(target.getParent());
        Files.writeString(target, source.text());
        return target;
    }

    private static List<String> texts(List<Adr068Corpus.Source> sources) {
        return sources.stream().map(Adr068Corpus.Source::text).toList();
    }

    /** An object's default {@code toString}, such as {@code [B@1a2b3c}: never stable from run to run. */
    private static final Pattern IDENTITY_HASH = Pattern.compile("\\[[A-Z]@[0-9a-f]{5,}|\\w@[0-9a-f]{6,}\\b");

    /**
     * The message without what varies between runs: JavaParser appends the parser's stack trace to a parse problem,
     * whose frames depend on the caller.
     */
    static String stableMessage(String message) {
        if (message == null) return "";
        int trace = message.indexOf("Problem stacktrace");
        return (trace < 0 ? message : message.substring(0, trace)).strip();
    }

    static Outcome rejection(Throwable t) {
        if (t instanceof CompilerException e) {
            var error = e.diagnostics().stream().filter(d -> d.level() == CompilerDiagnostic.Level.ERROR).findFirst();
            if (error.isPresent()) {
                var d = error.get();
                return new Outcome(false, null, 0, d.code(), stableMessage(d.message()), d.line(), d.column(),
                        "CompilerException");
            }
            return new Outcome(false, null, 0, null, stableMessage(e.getMessage()), 0, 0, "CompilerException");
        }
        String message = stableMessage(t.getMessage());
        return new Outcome(false, null, 0, null, message, 0, 0, t.getClass().getSimpleName());
    }

    /** The digest of the source map's located terms, by index: file name, line, column and fragment. */
    private static String sourceMapDigest(CompileResult result) {
        if (result.sourceMap() == null) return "none";
        var indexed = new TreeMap<>(result.sourceMap().toIndexed(result.program().term()));
        var text = new StringBuilder();
        indexed.forEach((index, location) -> {
            String file = location.fileName() == null ? "" : Path.of(location.fileName()).getFileName().toString();
            text.append(index).append('\t').append(file).append('\t').append(location.line()).append('\t')
                    .append(location.column()).append('\t').append(location.fragment()).append('\n');
        });
        return indexed.size() + ":" + short16(text.toString());
    }

    /**
     * Everything a library provider exposes, as text: for each type it declares (in FQCN order), its exports with
     * their materialized PIR, its schemes; then its unsupported exports, types and named definitions.
     */
    private static String describe(JavaLibraryProvider provider, List<Adr068Corpus.Source> sources) {
        var text = new StringBuilder();
        for (String module : declaredTypes(sources)) {
            text.append("module ").append(module).append('\n');
            try {
                for (var export : provider.describe(module)) {
                    text.append("  export ").append(export).append('\n');
                    try {
                        text.append("  pir ").append(PirFormatter.format(provider.materialize(export.symbol()))).append('\n');
                    } catch (RuntimeException e) {
                        text.append("  pir failed ").append(e.getClass().getSimpleName()).append('\n');
                    }
                }
                text.append("  schemes ").append(provider.schemes(module)).append('\n');
            } catch (RuntimeException e) {
                text.append("  failed ").append(e.getClass().getSimpleName()).append(' ').append(e.getMessage()).append('\n');
            }
        }
        text.append("unsupported ").append(new TreeMap<>(provider.unsupportedExports())).append('\n');
        new TreeMap<>(provider.types()).forEach((k, v) -> text.append("type ").append(k).append(' ').append(v).append('\n'));
        new TreeMap<>(provider.namedDefinitions()).forEach((k, v) -> text.append("definition ").append(k).append(' ')
                .append(v).append('\n'));
        if (IDENTITY_HASH.matcher(text).find()) throw new AssertionError("an identity hash in a library description: the ledger would not be deterministic");
        return text.toString();
    }

    /** The qualified names of the classes, interfaces and records the sources declare, sorted (javac parse only). */
    static List<String> declaredTypes(List<Adr068Corpus.Source> sources) {
        if (sources.isEmpty()) return List.of();
        var files = new ArrayList<JavaFileObject>();
        for (int i = 0; i < sources.size(); i++) {
            String text = sources.get(i).text();
            files.add(new SimpleJavaFileObject(URI.create("string:///Unit" + i + ".java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return text;
                }
            });
        }
        var task = (JavacTask) ToolProvider.getSystemJavaCompiler()
                .getTask(null, null, d -> { }, List.of("-proc:none"), null, files);
        var names = new TreeSet<String>();
        try {
            for (CompilationUnitTree unit : task.parse()) {
                String pkg = unit.getPackageName() == null ? "" : unit.getPackageName().toString() + ".";
                new TreeScanner<Void, String>() {
                    @Override
                    public Void visitClass(ClassTree node, String outer) {
                        String name = outer + node.getSimpleName();
                        names.add(name);
                        return super.visitClass(node, name + ".");
                    }
                }.scan(unit, pkg);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return List.copyOf(names);
    }

    /**
     * For each ledger source: the ledger metadata ({@code LedgerTypeProvider}: an empty {@code JavaLibraryProvider})
     * of the types it declares, as one digest.
     */
    private void precomputeLedgerTypes() {
        var provider = new JavaLibraryProvider(List.of(), null, new CompilerOptions().setLogger(m -> { }));
        for (var c : corpus.sorted()) {
            if (!c.entry.equals("ledger-source")) continue;
            var declared = declaredTypes(List.of(c.main));
            var text = new StringBuilder();
            for (String name : declared) {
                var type = provider.types().get(name);
                if (type != null) text.append("type ").append(name).append(' ').append(type).append('\n');
                var definition = provider.namedDefinitions().get(name);
                if (definition != null) text.append("definition ").append(name).append(' ').append(definition).append('\n');
            }
            ledgerTypeDigests.put(c.main.digest(), text.isEmpty() ? "none" : short32(text.toString()));
        }
    }

    // --- Runs ---

    /** One harness run: the JVM observation and, per level, the UPLC observation, the verdict and the budget. */
    record RunResult(String caseKey, String tag, String args, String jvm, Map<OptimizationLevel, String> semantic,
                     Map<OptimizationLevel, String> verdicts, Map<OptimizationLevel, String> budgets) {
    }

    List<RunResult> runAll() {
        var runner = DiffRunner.standard();
        var runs = new ArrayList<RunResult>();
        for (var c : corpus.sorted()) {
            for (var run : c.runs) {
                var report = runner.run(run.diffCase());
                var semantic = new LinkedHashMap<OptimizationLevel, String>();
                var verdicts = new LinkedHashMap<OptimizationLevel, String>();
                var budgets = new LinkedHashMap<OptimizationLevel, String>();
                report.results().forEach((config, result) -> {
                    semantic.put(config.level(), observation(result.uplc()));
                    verdicts.put(config.level(), result.verdict().kind().name());
                    var m = result.measurement();
                    budgets.put(config.level(), m == null ? "-" : m.cpu() + "/" + m.memory() + "/" + m.scriptBytes());
                });
                runs.add(new RunResult(c.key, run.tag(), argsDigest(run.diffCase().args()), observation(report.jvm()),
                        semantic, verdicts, budgets));
            }
        }
        runs.sort(Comparator.comparing(RunResult::caseKey).thenComparing(RunResult::tag));
        return runs;
    }

    /** {@code kind:value:traces:detail}, each a short digest ({@code -} when absent). */
    static String observation(Observation o) {
        return switch (o) {
            case Observation.Value v -> "Value:" + short16(HexFormat.of().formatHex(PlutusDataCborEncoder.encode(v.data())))
                    + ":" + v.traces().size() + "/" + short16(String.join("\n", v.traces())) + ":-";
            case Observation.Failure f -> "Failure:-:" + f.traces().size() + "/" + short16(String.join("\n", f.traces()))
                    + ":" + short16(f.detail() == null ? "" : f.detail());
            case Observation.Rejected r -> "Rejected:" + (r.code() == null ? "none" : r.code()) + ":-:" + short16(r.message());
            case Observation.Crash c -> "Crash:-:-:" + short16(c.detail());
            case Observation.Inconclusive i -> "Inconclusive:-:-:" + short16(i.reason());
        };
    }

    private static String argsDigest(List<PlutusData> args) {
        var text = new StringBuilder();
        for (var a : args) text.append(HexFormat.of().formatHex(PlutusDataCborEncoder.encode(a))).append(',');
        return short16(text.toString());
    }

    // --- Output ---

    static final String LEDGER_HEADER = "# ADR-068 D13 ledger, reference rows (JavaParser frontend). Generated by Adr068BaselineTest;"
            + " do not edit.\n# cell: A:<flat sha256/32>:<size> | A:<provider sha256/32>:- | R:<code>@<line>:<col>"
            + " | R:uncoded/<exception>/<message sha256/16>@<line>:<col>\n# srcmap: <located terms>:<sha256/16>, R (rejected),"
            + " - (no program)\n";

    List<String> ledgerLines() {
        var lines = new ArrayList<String>();
        lines.add("case\tsource\tlibraries\tentry\tmethod\tlookup\tparts\tNONE\tBASELINE\tPV11_SAFE\tPV11_COSTED\tsrcmap");
        for (var c : corpus.sorted()) {
            var r = results.get(c.key);
            var row = new StringBuilder();
            row.append(c.key).append('\t').append(c.main == null ? "-" : c.main.digest()).append('\t')
                    .append(c.libraries.isEmpty() ? "0" : c.libraries.size() + ":" + short16(String.join(",",
                            c.libraries.stream().map(Adr068Corpus.Source::digest).toList())))
                    .append('\t').append(c.entry).append('\t').append(c.method == null ? "-" : c.method)
                    .append('\t').append(c.lookup.equals(Adr068Corpus.STDLIB_LOOKUP) ? "stdlib" : c.lookup)
                    .append('\t').append(String.join(",", c.parts()));
            for (var level : LEVELS) row.append('\t').append(r.outcomes().get(level).cell());
            row.append('\t').append(r.sourceMap());
            lines.add(row.toString());
        }
        return lines;
    }

    List<String> corpusLines() {
        var lines = new ArrayList<String>();
        lines.add("case\ttags\torigins");
        for (var c : corpus.sorted()) {
            lines.add(c.key + "\t" + String.join(",", c.tags) + "\t" + (c.origins.isEmpty() ? "-" : String.join(",", c.origins)));
        }
        return lines;
    }

    static List<String> semanticLines(List<RunResult> runs) {
        var lines = new ArrayList<String>();
        lines.add("case\trun\targs\tJVM\tNONE\tBASELINE\tPV11_SAFE\tPV11_COSTED\tverdicts");
        for (var r : runs) {
            var row = new StringBuilder(r.caseKey() + "\t" + r.tag() + "\t" + r.args() + "\t" + r.jvm());
            for (var level : LEVELS) row.append('\t').append(r.semantic().get(level));
            row.append('\t').append(String.join(",", LEVELS.stream().map(l -> r.verdicts().get(l)).toList()));
            lines.add(row.toString());
        }
        return lines;
    }

    static List<String> budgetLines(List<RunResult> runs) {
        var lines = new ArrayList<String>();
        lines.add("case\trun\targs\tNONE\tBASELINE\tPV11_SAFE\tPV11_COSTED");
        for (var r : runs) {
            var row = new StringBuilder(r.caseKey() + "\t" + r.tag() + "\t" + r.args());
            for (var level : LEVELS) row.append('\t').append(r.budgets().get(level));
            lines.add(row.toString());
        }
        return lines;
    }

    /** Per level: accepted, rejected, unreplayable. */
    Map<OptimizationLevel, int[]> totals() {
        var totals = new LinkedHashMap<OptimizationLevel, int[]>();
        for (var level : LEVELS) totals.put(level, new int[3]);
        for (var r : results.values()) {
            r.outcomes().forEach((level, o) -> totals.get(level)[o.accepted() ? 0 : o.unreplayable() ? 2 : 1]++);
        }
        return totals;
    }

    CaseResult result(String key) {
        return results.get(key);
    }

    // --- The I2a rejection inventory ---

    private static final Pattern NON_ASCII_DIGIT_ESCAPE = Pattern.compile("\\\\u+(\\p{Nd}|[a-fA-F]){4}");

    /** Whether the rejection is a frontend limitation a decision lifts (D13), and the decision. */
    record Classification(boolean scheduled, String basis) {
    }

    static Classification classify(Adr068Corpus.Case c, Outcome o) {
        if (c.tags.contains("diffcase:regressions/reject-javac-21-25-pair-shape.diffcase")) {
            return new Classification(true, "D11: javac 25's switch shape (ADR-067 M2b)");
        }
        String message = o.message() == null ? "" : o.message();
        if ("JULC0076".equals(o.code()) && message.contains("not yet supported: record patterns")) {
            for (String permanent : List.of("reject-record-pattern-on-value.diffcase",
                    "reject-record-pattern-on-boolean-newtype.diffcase",
                    "reject-parameterized-sealed-component-pattern.diffcase")) {
                if (c.tags.contains("diffcase:regressions/" + permanent)) {
                    return new Classification(false, "ADR-067 outcome matrix: final verdict REJECT JULC0076 (another reason)");
                }
            }
            return new Classification(true, "ADR-067 M2a/M2b record patterns (interim JULC0076, F35), on the javac frontend in M3 (D11)");
        }
        if (message.contains("A backslash written as a Unicode escape is followed by raw")) {
            return new Classification(true, "ADR-065 I2c raw-text shape: escaped backslash before raw backslashes and u");
        }
        if (message.contains("Illegal Unicode escape") && c.main != null && hasNonAsciiDigitEscape(c)) {
            return new Classification(true, "ADR-065 I2c raw-text shape: escape digits that are not ASCII hex digits");
        }
        return new Classification(false, "subset or invalid-Java rejection");
    }

    private static boolean hasNonAsciiDigitEscape(Adr068Corpus.Case c) {
        var sources = new ArrayList<Adr068Corpus.Source>();
        sources.add(c.main);
        sources.addAll(c.libraries);
        for (var s : sources) {
            var m = NON_ASCII_DIGIT_ESCAPE.matcher(s.text());
            while (m.find()) {
                String digits = m.group().substring(m.group().length() - 4);
                boolean ascii = digits.chars().allMatch(ch -> ch < 128);
                boolean unicodeDigits = digits.chars().allMatch(ch -> Character.digit(ch, 16) >= 0);
                if (!ascii && unicodeDigits) return true;
            }
        }
        return false;
    }

    List<String> inventoryLines(int[] counts) {
        var lines = new ArrayList<String>();
        lines.add("case\tclass\tcode\tposition\tlevels\tmessage\tbasis\ttest-sources");
        for (var c : corpus.sorted()) {
            var r = results.get(c.key);
            var rejected = r.outcomes().entrySet().stream().filter(e -> !e.getValue().accepted()).toList();
            if (rejected.isEmpty()) continue;
            var first = rejected.getFirst().getValue();
            if (first.unreplayable()) continue;
            var classification = classify(c, first);
            counts[classification.scheduled() ? 1 : 0]++;
            String levels = rejected.size() == LEVELS.size() ? "all"
                    : String.join(",", rejected.stream().map(e -> e.getKey().name()).toList());
            var sources = new TreeSet<String>();
            c.tags.stream().filter(t -> !t.equals("recorded")).forEach(sources::add);
            sources.addAll(c.origins);
            lines.add(c.key + "\t" + (classification.scheduled() ? "scheduled" : "permanent") + "\t"
                    + (first.code() == null ? "uncoded:" + first.exception() : first.code()) + "\t"
                    + first.line() + ":" + first.column() + "\t" + levels + "\t" + oneLine(first.message(), 160) + "\t"
                    + classification.basis() + "\t" + String.join(";", sources));
        }
        return lines;
    }

    static String oneLine(String text, int max) {
        if (text == null) return "";
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    static String short16(String text) {
        return CorpusRecorder.sha256(text == null ? "" : text).substring(0, 16);
    }

    static String short32(String text) {
        return CorpusRecorder.sha256(text).substring(0, 32);
    }
}
