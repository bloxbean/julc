package org.julclang.difftest;

import org.julclang.compiler.CompileResult;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JavaLibraryProvider;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.frontend.javac.JavacEvidence;
import org.julclang.compiler.frontend.javac.NotPorted;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.corpus.CorpusFiles;
import org.julclang.core.flat.UplcFlatEncoder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * ADR-068 I1 over one corpus case: the case compiled on {@code frontend=JAVAC} and classified against the reference (the
 * JavaParser frontend at B2, gate off) and against the reference's PIR, method by method.
 * <p>
 * The classes are deliberately not tolerant. A row states what happened, a digest of everything it compared stands in
 * the row whatever is shown of it, and every row is compared with a committed table in {@link Adr068LoweringTest}, so a
 * frontend that rejected more, or a case that left the corpus, moves a row: no class means "fine". A {@code DIFFERENT}
 * row must also be listed, with its D13 class. The reference is held still by a table of its own, {@link Reference}.
 */
final class Adr068Lowering {

    /** The codes of what javac itself rejects, before any lowering (D3, D5). */
    private static final Set<String> JAVAC_CODES = Set.of("JULC0085", "JULC0081", "JULC0089");

    /** How much of a row's detail the table shows. What is compared is the digest of all of it. */
    private static final int SHOWN = 160;

    /** One compilation: what the ledger records of it, the construct that stopped it, and what it threw, if either happened. */
    record Compilation(Adr068Ledger.Outcome outcome, CompileResult result, String construct, Throwable thrown) {
        Compilation(Adr068Ledger.Outcome outcome, CompileResult result, String construct) {
            this(outcome, result, construct, null);
        }

        boolean accepted() {
            return outcome.accepted();
        }
    }

    /** What the tool takes from the javac frontend, so that a test can inject the difference a class must report. */
    interface Javac {
        Compilation compile(Adr068Corpus.Case c, CompilerOptions options);

        List<JavacEvidence.Attempt> attempts(Adr068Corpus.Case c, CompilerOptions options);
    }

    /** The javac frontend itself. */
    static Javac frontend() {
        return FRONTEND;
    }

    private static final Javac FRONTEND = new Javac() {
        @Override
        public Compilation compile(Adr068Corpus.Case c, CompilerOptions options) {
            return Adr068Lowering.compile(c, options);
        }

        @Override
        public List<JavacEvidence.Attempt> attempts(Adr068Corpus.Case c, CompilerOptions options) {
            return JavacEvidence.attempts(c.main.text(), Adr068Ledger.texts(c.libraries),
                    c.entry.equals("compileMethod") ? c.method : null, options);
        }
    };

    /**
     * One case's row.
     *
     * @param kind       what happened, one of the classes of {@link #rank}
     * @param covered    the methods the javac frontend lowered to the reference's term and type
     * @param total      the methods the reference lowered, library methods included; the javac frontend attempts only
     *                   the root's
     * @param construct  the first construct the javac frontend stopped at, or -
     * @param provenance how the source maps compare: {@code same}, {@code fragment} (the text differs), or -
     * @param detail     what the class compared, in full
     * @param pir        the canonical terms of the covered methods, not written to the table
     * @param program    the FLAT digest at the default level, when both accept
     */
    record Row(String key, String kind, int covered, int total, String construct, String provenance, String detail,
               List<String> pir, String program) {

        String line() {
            String shown = detail.replace('\t', ' ').replace('\n', ' ');
            shown = shown.length() > SHOWN ? shown.substring(0, SHOWN) + "..." : shown;
            String digest = detail.isEmpty() ? "-" : CorpusFiles.sha256(detail).substring(0, 16);
            return key + "\t" + kind + "\t" + covered + "/" + total + "\t" + construct + "\t" + provenance + "\t" + digest
                    + "\t" + shown;
        }

        boolean fullyCovered() {
            return kind.equals("i1a-identical");
        }
    }

    /**
     * The reference's results for one case, as {@code lowering-reference.tsv} holds them. The table is a record of the
     * reference and a prefilter for its drift: a run still compiles the reference, since a comparison reads more than a row
     * holds. Each cell is a digest or ledger cell, of a form the javac frontend's result can be put in too.
     *
     * @param status      {@code compiled}, or what the row says instead of compiling
     * @param cells       {@link Adr068Ledger.Outcome#cell()} per level, at the case's source-map setting
     * @param diagnostics {@link Adr068Ledger.Outcome#diagnosticsCell()} at the default level
     * @param params      a digest of the {@code @Param} infos, or -
     * @param located     a digest of the source map's lines, columns and file names at the default level, or -
     * @param methods     per method, in lowering order, a digest of its binder, type and canonical term
     */
    record Reference(String key, String status, List<String> cells, String diagnostics, String params, String located,
                     List<String> methods) {

        static final String HEADER = "case\tstatus\t" + String.join("\t", Adr068Ledger.LEVELS.stream().map(Enum::name).toList())
                + "\tdiagnostics\tparams\tlocated\tmethods";

        static Reference unread(Adr068Corpus.Case c, String status) {
            return new Reference(c.key, status, Adr068Ledger.LEVELS.stream().map(level -> "-").toList(), "-", "-", "-", List.of());
        }

        String line() {
            return key + "\t" + status + "\t" + String.join("\t", cells) + "\t" + diagnostics + "\t" + params + "\t" + located
                    + "\t" + (methods.isEmpty() ? "-" : String.join(",", methods));
        }

        /** The column that differs from a committed line, for a person; null when they are equal. */
        String difference(String committed) {
            if (committed == null) return "no committed row";
            String[] names = HEADER.split("\t"), left = committed.split("\t", -1), right = line().split("\t", -1);
            for (int i = 0; i < names.length; i++) {
                String was = i < left.length ? left[i] : "(none)";
                if (!was.equals(right[i])) return names[i] + ": " + was + " -> " + right[i];
            }
            return null;
        }
    }

    /** A case's row, and the reference's results it was read against. */
    record Classified(Row row, Reference reference) {
    }

    private final Map<String, String> committed;
    private final Javac javac;

    /** @param committed the lines of {@code lowering-reference.tsv} by case, or null to take the reference as it is */
    Adr068Lowering(Map<String, String> committed) {
        this(committed, FRONTEND);
    }

    Adr068Lowering(Map<String, String> committed, Javac javac) {
        this.committed = committed;
        this.javac = javac;
    }

    /**
     * How much a class compares, so that a row moving down has lost a comparison (F926). One number says no more than that:
     * a row leaving {@code javac-rejects} moves up it, and {@code Adr068LoweringTest.shrunk} counts that move by itself.
     */
    static int rank(String kind) {
        return switch (kind) {
            case "i1a-identical" -> 5;
            case "both-reject", "DIFFERENT-bytes", "DIFFERENT-pir", "DIFFERENT-coverage", "DIFFERENT-provenance",
                 "DIFFERENT-rejection", "DIFFERENT-javac-rejects", "DIFFERENT-javac-accepts", "DIFFERENT-root-alone" -> 4;
            case "both-reject-javac-first" -> 3;
            case "not-ported", "reference-rejects-not-ported" -> 2;
            case "javac-rejects" -> 1;
            case "ledger-source", "unreplayable", "REFERENCE-DRIFT" -> 0;
            default -> throw new IllegalArgumentException("no such class: " + kind);
        };
    }

    /** D13's classes, each with the rows it can explain. A row class not listed under one cannot be accepted. */
    private static final Map<String, Set<String>> D13 = Map.of(
            "Semantic fix", Set.of("DIFFERENT-bytes"),
            "UPLC-only semantic fix", Set.of("DIFFERENT-bytes"),
            "Policy rejection", Set.of("DIFFERENT-javac-rejects"),
            "Accepted by decision", Set.of("DIFFERENT-javac-accepts"),
            "Diagnostic change", Set.of("DIFFERENT-rejection"),
            "Provenance change", Set.of("DIFFERENT-provenance"),
            "Representation only", Set.of("DIFFERENT-bytes"),
            "Cost change", Set.of("DIFFERENT-bytes"));

    private static final Pattern ACCEPTED_CLASS = Pattern.compile("([^:(]+?)(?: \\(.*?\\))?: \\S.*");

    /**
     * {@code lowering-accepted-differences.tsv}: each entry's row class, by case. The table says why a difference is
     * accepted, so an entry must name a D13 class that can explain its row, and a case has one entry.
     *
     * @throws IllegalArgumentException for an entry that does not
     */
    static Map<String, String> acceptedDifferences(List<String> lines) {
        var accepted = new TreeMap<String, String>();
        for (String line : lines) {
            if (line.startsWith("#") || line.isBlank()) continue;
            String[] cells = line.split("\t", -1);
            if (cells.length != 3) throw new IllegalArgumentException("not three cells: " + line);
            var written = ACCEPTED_CLASS.matcher(cells[2]);
            if (!written.matches()) throw new IllegalArgumentException("no D13 class and reason: " + line);
            var rows = D13.get(written.group(1));
            if (rows == null) throw new IllegalArgumentException("not a D13 class of a lowering row: " + line);
            if (!rows.contains(cells[1])) throw new IllegalArgumentException(written.group(1) + " cannot explain " + line);
            if (accepted.put(cells[0], cells[1]) != null) throw new IllegalArgumentException("listed twice: " + line);
        }
        return accepted;
    }

    private static CompilerOptions options(Adr068Corpus.Case c, OptimizationLevel level, boolean sourceMap, CompilerFrontend frontend) {
        return Adr068Ledger.options(c, level, sourceMap).setFrontend(frontend);
    }

    private static Compilation compile(Adr068Corpus.Case c, CompilerOptions options) {
        try {
            if (c.entry.equals("JavaLibraryProvider")) {
                var provider = new JavaLibraryProvider(Adr068Ledger.texts(c.libraries), Adr068Ledger.lookup(c.lookup), options);
                var failures = new ArrayList<Adr068Ledger.Diagnostic>();
                String description = Adr068Ledger.describe(provider, c.libraries, failures);
                return new Compilation(Adr068Ledger.Outcome.accepted(CorpusFiles.sha256(description).substring(0, 32), -1, failures),
                        null, null);
            }
            var result = Adr068Ledger.invoke(c, options);
            byte[] flat = UplcFlatEncoder.encodeProgram(result.program());
            return new Compilation(Adr068Ledger.Outcome.accepted(CorpusFiles.sha256(flat).substring(0, 32), flat.length,
                    Adr068Ledger.diagnostics(result.diagnostics())), result, null);
        } catch (AssertionError e) {
            throw e;
        } catch (NotPorted e) {
            return new Compilation(Adr068Ledger.rejection(e), null, e.construct(), e);
        } catch (Throwable t) {
            return new Compilation(Adr068Ledger.rejection(t), null, null, t);
        }
    }

    /**
     * The javac side of a comparison. Only a {@link CompilerException} is a rejection: anything else, a failed I13 check or
     * a crash, is the frontend breaking, and that is no row to classify. {@link Javac#attempts} is the other way into the
     * frontend, and whatever it throws but a {@code CompilerException} goes through {@link #coverage} untouched. A
     * {@link JavaLibraryProvider} is the one other API: on both frontends it refuses a source it cannot export with an
     * {@link IllegalArgumentException}.
     */
    private Compilation onJavac(Adr068Corpus.Case c, CompilerOptions options) {
        var compilation = javac.compile(c, options);
        var thrown = compilation.thrown();
        boolean rejection = thrown == null || thrown instanceof CompilerException
                || c.entry.equals("JavaLibraryProvider") && thrown instanceof IllegalArgumentException;
        if (!rejection) throw new AssertionError(c.key + ": " + thrown, thrown);
        return compilation;
    }

    /** The source-map settings a comparison reads: both, for a program. A provider's row has no map to set. */
    private static List<Boolean> sourceMapSettings(Adr068Corpus.Case c) {
        return Adr068Ledger.isProgramEntry(c.entry) ? List.of(false, true) : List.of(c.sourceMap);
    }

    private static String cellName(OptimizationLevel level, boolean sourceMap) {
        return level + (sourceMap ? " with a map " : " ");
    }

    Classified classify(Adr068Corpus.Case c) {
        if (c.entry.equals("ledger-source")) {
            return read(c, Reference.unread(c, "ledger-source"), row(c, "ledger-source", 0, 0, "-", "-", ""));
        }
        String unreplayable = Adr068Ledger.unreplayableReason(c);
        if (unreplayable != null) {
            return read(c, Reference.unread(c, "unreplayable"), row(c, "unreplayable", 0, 0, "-", "-", unreplayable));
        }

        var level = OptimizationLevel.DEFAULT;
        var methods = new LinkedHashMap<String, Method>();
        var referenceOptions = options(c, level, c.sourceMap, CompilerFrontend.JAVAPARSER)
                .setMethodObserver((binder, type, body) -> methods.put(binder, new Method(type, body)));
        var reference = compile(c, referenceOptions);
        var frozen = reference(c, reference, methods);
        var row = classify(c, reference, methods);
        return read(c, frozen, row);
    }

    /** The row, unless the reference moved: a difference there is a change of the frozen frontend, never of the case. */
    private Classified read(Adr068Corpus.Case c, Reference frozen, Row row) {
        String drift = committed == null ? null : frozen.difference(committed.get(c.key));
        return new Classified(drift == null ? row : row(c, "REFERENCE-DRIFT", 0, 0, "-", "-", drift), frozen);
    }

    private static Reference reference(Adr068Corpus.Case c, Compilation atDefault, Map<String, Method> methods) {
        var cells = new ArrayList<String>();
        for (var level : Adr068Ledger.LEVELS) {
            cells.add(level == OptimizationLevel.DEFAULT ? atDefault.outcome().cell()
                    : compile(c, options(c, level, c.sourceMap, CompilerFrontend.JAVAPARSER)).outcome().cell());
        }
        String located = "-";
        if (atDefault.accepted() && Adr068Ledger.isProgramEntry(c.entry)) {
            var mapped = compile(c, options(c, OptimizationLevel.DEFAULT, true, CompilerFrontend.JAVAPARSER));
            if (mapped.accepted()) located = positions(mapped.result());
        }
        var digests = new ArrayList<String>();
        methods.forEach((binder, method) -> digests.add(
                digest(binder + "\n" + method.type() + "\n" + PirCanonical.of(method.body()))));
        return new Reference(c.key, "compiled", cells, atDefault.outcome().diagnosticsCell(),
                atDefault.result() == null ? "-" : digest(atDefault.result().params().toString()), located, digests);
    }

    /** The source map's located terms, by index, without the text javac and the reference spell differently. */
    private static String positions(CompileResult result) {
        var text = new StringBuilder();
        located(result).forEach((index, location) -> text.append(index).append('\t').append(fileName(location.fileName()))
                .append('\t').append(location.line()).append('\t').append(location.column()).append('\n'));
        return digest(text.toString());
    }

    private static String digest(String text) {
        return CorpusFiles.sha256(text).substring(0, 16);
    }

    private Row classify(Adr068Corpus.Case c, Compilation reference, Map<String, Method> methods) {
        var level = OptimizationLevel.DEFAULT;
        var javac = onJavac(c, options(c, level, c.sourceMap, CompilerFrontend.JAVAC));

        var coverage = coverage(c, methods, reference.accepted());
        String construct = javac.construct() != null ? javac.construct() : coverage.construct();
        if (reference.accepted() && javac.accepted()) {
            String bytes = byteDifference(c, reference, javac);
            if (bytes != null) return row(c, "DIFFERENT-bytes", coverage, construct, "-", bytes);
            if (coverage.mismatch() != null) return row(c, "DIFFERENT-pir", coverage, construct, "-", coverage.mismatch());
            if (coverage.covered() != coverage.total()) {
                return row(c, "DIFFERENT-coverage", coverage, construct, "-", "accepted, with methods not lowered");
            }
            String sourceMap = sourceMapDifference(c);
            if (sourceMap.startsWith("location")) return row(c, "DIFFERENT-provenance", coverage, construct, "-", sourceMap);
            return new Row(c.key, "i1a-identical", coverage.covered(), coverage.total(), "-", sourceMap, "",
                    coverage.pir(), reference.outcome().digest());
        }
        if (coverage.mismatch() != null) return row(c, "DIFFERENT-pir", coverage, construct, "-", coverage.mismatch());
        var javacError = javac.outcome().error();
        if (reference.accepted()) {
            if (javac.construct() != null) return row(c, "not-ported", coverage, construct, "-", "");
            if (javacError != null && javacError.code() != null && JAVAC_CODES.contains(javacError.code())) {
                return row(c, "javac-rejects", coverage, construct, "-", text(javacError));
            }
            return row(c, "DIFFERENT-javac-rejects", coverage, construct, "-", text(javacError));
        }
        var referenceError = reference.outcome().error();
        if (javac.accepted()) {
            String rootAlone = rootAloneDifference(c);
            if (rootAlone != null) return row(c, "DIFFERENT-root-alone", coverage, construct, "-", rootAlone);
            return row(c, "DIFFERENT-javac-accepts", coverage, construct, "-", text(referenceError) + " / " + javacCells(c));
        }
        if (javac.construct() != null) return row(c, "reference-rejects-not-ported", coverage, construct, "-", text(referenceError));
        if (javacError != null && javacError.code() != null && JAVAC_CODES.contains(javacError.code())) {
            return row(c, "both-reject-javac-first", coverage, construct, "-", text(javacError));
        }
        return sameRejection(referenceError, javacError)
                ? row(c, "both-reject", coverage, construct, "-", text(referenceError))
                : row(c, "DIFFERENT-rejection", coverage, construct, "-", text(referenceError) + " / " + text(javacError));
    }

    /** What javac produced of a program only it accepts, at every level, with and without a map: nothing else pins it. */
    private String javacCells(Adr068Corpus.Case c) {
        var cells = new ArrayList<String>();
        for (boolean sourceMap : sourceMapSettings(c)) {
            for (var level : Adr068Ledger.LEVELS) {
                cells.add(cellName(level, sourceMap) + onJavac(c, options(c, level, sourceMap, CompilerFrontend.JAVAC)).outcome().cell());
            }
        }
        return String.join("; ", cells);
    }

    /**
     * D8: the javac frontend reads what the root references, so where a pool unit is all the reference objects to, its
     * program is the reference's program of the root alone, at every level, with and without a map. That is an oracle for
     * these rows: null when it holds.
     */
    private String rootAloneDifference(Adr068Corpus.Case c) {
        if (c.libraries.isEmpty()) return null;
        var root = c.rootAlone();
        var levels = new ArrayList<String>();
        for (boolean sourceMap : sourceMapSettings(c)) {
            for (var level : Adr068Ledger.LEVELS) {
                var alone = compile(root, options(root, level, sourceMap, CompilerFrontend.JAVAPARSER)).outcome().cell();
                var cell = onJavac(c, options(c, level, sourceMap, CompilerFrontend.JAVAC)).outcome().cell();
                if (!alone.equals(cell)) levels.add(cellName(level, sourceMap) + alone + " / " + cell);
            }
        }
        return levels.isEmpty() ? null : String.join("; ", levels);
    }

    /** A difference, for a person: each side in full, and where the case came from. Not written to the table. */
    static String describe(Adr068Corpus.Case c, Row row) {
        return row.line() + "\n  " + row.detail() + "\n  tags " + c.tags + "\n  entry " + c.entry + " method " + c.method + " source "
                + (c.main == null ? "-" : c.main.digest()) + " libraries " + c.libraries.size() + "\n";
    }

    /** One code and one message, and one position wherever the reference gave one. */
    private static boolean sameRejection(Adr068Ledger.Diagnostic reference, Adr068Ledger.Diagnostic javac) {
        if (reference == null || javac == null) return reference == javac;
        return Objects.equals(reference.code(), javac.code()) && Objects.equals(reference.message(), javac.message())
                && (reference.line() == 0 || reference.line() == javac.line() && reference.column() == javac.column());
    }

    private static String text(Adr068Ledger.Diagnostic diagnostic) {
        return diagnostic == null ? "?" : diagnostic.code() + "@" + diagnostic.line() + ":" + diagnostic.column() + " " + diagnostic.message();
    }

    private static Row row(Adr068Corpus.Case c, String kind, int covered, int total, String construct, String provenance, String detail) {
        return new Row(c.key, kind, covered, total, construct, provenance, detail, List.of(), "-");
    }

    private static Row row(Adr068Corpus.Case c, String kind, Coverage coverage, String construct, String provenance, String detail) {
        return new Row(c.key, kind, coverage.covered(), coverage.total(), construct, provenance, detail, coverage.pir(), "-");
    }

    /**
     * Every level, with and without a source map, against the reference, then the diagnostics and {@code @Param} infos at
     * the default level. The cells come first, so that a row with another difference still pins the bytes. Null when all are
     * equal.
     */
    private String byteDifference(Adr068Corpus.Case c, Compilation reference, Compilation javac) {
        var differences = new ArrayList<String>();
        for (boolean sourceMap : sourceMapSettings(c)) {
            for (var level : Adr068Ledger.LEVELS) {
                var r = compile(c, options(c, level, sourceMap, CompilerFrontend.JAVAPARSER));
                var j = onJavac(c, options(c, level, sourceMap, CompilerFrontend.JAVAC));
                if (!r.outcome().cell().equals(j.outcome().cell())) {
                    differences.add(cellName(level, sourceMap) + r.outcome().cell() + " / " + j.outcome().cell());
                }
            }
        }
        if (!reference.outcome().diagnosticsCell().equals(javac.outcome().diagnosticsCell())) {
            differences.add("diagnostics " + reference.outcome().diagnosticsCell() + " / " + javac.outcome().diagnosticsCell());
        }
        if (reference.result() != null && !reference.result().params().equals(javac.result().params())) {
            differences.add("params " + reference.result().params() + " / " + javac.result().params());
        }
        return differences.isEmpty() ? null : String.join("; ", differences);
    }

    /** What the frontends' source maps say of the same program, at every level: {@code location ...} if one moved (I7). */
    String sourceMapDifference(Adr068Corpus.Case c) {
        if (!Adr068Ledger.isProgramEntry(c.entry)) return "-";
        var moved = new ArrayList<String>();
        boolean compared = false, fragment = false;
        for (var level : Adr068Ledger.LEVELS) {
            var reference = compile(c, options(c, level, true, CompilerFrontend.JAVAPARSER));
            var javac = onJavac(c, options(c, level, true, CompilerFrontend.JAVAC));
            if (!reference.accepted() || !javac.accepted()) {
                if (reference.accepted() != javac.accepted()) moved.add(level + " only one compiles with a source map");
                continue;
            }
            compared = true;
            var left = located(reference.result());
            var right = located(javac.result());
            if (!left.keySet().equals(right.keySet())) moved.add(level + " located terms " + left.keySet() + " / " + right.keySet());
            for (var entry : left.entrySet()) {
                var l = entry.getValue();
                var r = right.get(entry.getKey());
                if (r == null) continue;
                if (l.line() != r.line() || l.column() != r.column()) {
                    moved.add(level + " term " + entry.getKey() + " " + l.line() + ":" + l.column() + " / " + r.line() + ":" + r.column());
                }
                if (!Objects.equals(fileName(l.fileName()), fileName(r.fileName()))) {
                    moved.add(level + " term " + entry.getKey() + " file " + l.fileName() + " / " + r.fileName());
                }
                fragment |= !l.fragment().equals(r.fragment());
            }
        }
        if (!moved.isEmpty()) return "location: " + String.join("; ", moved);
        return !compared ? "-" : fragment ? "fragment" : "same";
    }

    private static String fileName(String name) {
        return name == null ? null : Path.of(name).getFileName().toString();
    }

    private static Map<Integer, org.julclang.core.source.SourceLocation> located(CompileResult result) {
        return new TreeMap<>(result.sourceMap().toIndexed(result.program().term()));
    }

    private record Method(PirType type, PirTerm body) {
    }

    /** I1b: how far the javac frontend's lowering of each method goes, and whether it equals the reference's. */
    private record Coverage(int covered, int total, String construct, String mismatch, List<String> pir) {
    }

    private Coverage coverage(Adr068Corpus.Case c, Map<String, Method> reference, boolean referenceAccepted) {
        if (!Adr068Ledger.isProgramEntry(c.entry) || c.main == null) return new Coverage(0, reference.size(), "-", null, List.of());
        List<JavacEvidence.Attempt> attempts;
        try {
            attempts = javac.attempts(c, options(c, OptimizationLevel.DEFAULT, c.sourceMap, CompilerFrontend.JAVAC));
        } catch (CompilerException e) {
            attempts = List.of();   // Rejected before its methods: the compilation's own row says how.
        }
        int covered = 0;
        String construct = "-";
        String mismatch = null;
        var pir = new ArrayList<String>();
        var attempted = new LinkedHashMap<String, JavacEvidence.Attempt>();
        for (var attempt : attempts) attempted.put(attempt.binder(), attempt);
        for (var entry : reference.entrySet()) {
            var attempt = attempted.remove(entry.getKey());
            if (attempt == null || attempt.term() == null) {
                // No attempt at all means the source was rejected first, and its row says how; it stopped at no construct.
                if (construct.equals("-") && !attempts.isEmpty()) {
                    construct = attempt == null ? "a library method" : attempt.construct();
                }
                continue;
            }
            String expected = PirCanonical.of(entry.getValue().body());
            String actual = PirCanonical.of(attempt.term());
            if (!expected.equals(actual) || !entry.getValue().type().equals(attempt.type())) {
                if (mismatch == null) mismatch = entry.getKey() + " differs from the reference";
                continue;
            }
            covered++;
            pir.add(expected);
        }
        // Only a reference that lowered the whole program says what it did not lower.
        if (mismatch == null && referenceAccepted) {
            for (var extra : attempted.values()) {
                if (extra.term() != null) {
                    mismatch = extra.binder() + " is lowered here and not by the reference";
                    break;
                }
            }
        }
        return new Coverage(covered, reference.size(), construct, mismatch, pir);
    }
}
