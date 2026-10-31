package org.julclang.difftest;

import org.julclang.compiler.CompileResult;
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

/**
 * ADR-068 I1 over one corpus case: the case compiled on {@code frontend=JAVAC} and classified against the reference (the
 * JavaParser frontend at B1, gate off) and against the reference's PIR, method by method.
 * <p>
 * The classes are deliberately not tolerant. A row states what happened, and every row is compared with a committed
 * table in {@link Adr068LoweringTest}, so a frontend that rejected more, or a case that left the corpus, moves a row: no
 * class means "fine". A {@code DIFFERENT} row must also be listed, with its D13 class.
 */
final class Adr068Lowering {

    /** The codes of what javac itself rejects, before any lowering (D3, D5). */
    private static final Set<String> JAVAC_CODES = Set.of("JULC0085", "JULC0081", "JULC0089");

    /** One compilation: what the ledger records of it, and the construct that stopped it, if one did. */
    record Compilation(Adr068Ledger.Outcome outcome, CompileResult result, String construct) {
        boolean accepted() {
            return outcome.accepted();
        }
    }

    /**
     * One case's row.
     *
     * @param kind       what happened, one of the classes of {@link #classify}
     * @param covered    the methods the javac frontend lowered to the reference's term and type
     * @param total      the methods the reference lowered, library methods included
     * @param construct  the first construct the javac frontend stopped at, or -
     * @param provenance how the source maps compare: {@code same}, {@code fragment} (the text differs), or -
     * @param detail     what the class needs said
     * @param pir        the canonical terms of the covered methods, not written to the table
     * @param program    the FLAT digest at the default level, when both accept
     */
    record Row(String key, String kind, int covered, int total, String construct, String provenance, String detail,
               List<String> pir, String program) {

        String line() {
            String shown = detail.replace('\t', ' ').replace('\n', ' ');
            shown = shown.length() > 300 ? shown.substring(0, 300) + "..." : shown;
            return key + "\t" + kind + "\t" + covered + "/" + total + "\t" + construct + "\t" + provenance + "\t" + shown;
        }

        boolean fullyCovered() {
            return kind.equals("i1a-identical");
        }
    }

    private final Map<String, Map<String, String>> b1;

    /** @param b1 the committed reference cells, by case and level name */
    Adr068Lowering(Map<String, Map<String, String>> b1) {
        this.b1 = b1;
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
            return new Compilation(Adr068Ledger.rejection(e), null, e.construct());
        } catch (Throwable t) {
            return new Compilation(Adr068Ledger.rejection(t), null, null);
        }
    }

    Row classify(Adr068Corpus.Case c) {
        if (c.entry.equals("ledger-source")) return row(c, "ledger-source", 0, 0, "-", "-", "");
        String unreplayable = Adr068Ledger.unreplayableReason(c);
        if (unreplayable != null) return row(c, "unreplayable", 0, 0, "-", "-", unreplayable);

        var level = OptimizationLevel.DEFAULT;
        var methods = new LinkedHashMap<String, Method>();
        var referenceOptions = options(c, level, c.sourceMap, CompilerFrontend.JAVAPARSER)
                .setMethodObserver((binder, type, body) -> methods.put(binder, new Method(type, body)));
        var reference = compile(c, referenceOptions);
        String drift = drift(c, level, reference);
        if (drift != null) return row(c, "REFERENCE-DRIFT", 0, 0, "-", "-", drift);
        var javac = compile(c, options(c, level, c.sourceMap, CompilerFrontend.JAVAC));

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
                return row(c, "javac-rejects", coverage, construct, "-", javacError.code());
            }
            return row(c, "DIFFERENT-javac-rejects", coverage, construct, "-", text(javacError));
        }
        var referenceError = reference.outcome().error();
        if (javac.accepted()) return row(c, "DIFFERENT-javac-accepts", coverage, construct, "-", text(referenceError));
        if (javac.construct() != null) return row(c, "reference-rejects-not-ported", coverage, construct, "-", text(referenceError));
        if (javacError != null && javacError.code() != null && JAVAC_CODES.contains(javacError.code())) {
            return row(c, "both-reject-javac-first", coverage, construct, "-", javacError.code());
        }
        return sameRejection(referenceError, javacError)
                ? row(c, "both-reject", coverage, construct, "-", text(referenceError))
                : row(c, "DIFFERENT-rejection", coverage, construct, "-", text(referenceError) + " / " + text(javacError));
    }

    /** A difference, for a person: each side in full, and where the case came from. Not written to the table. */
    static String describe(Adr068Corpus.Case c, Row row) {
        return row.line() + "\n  tags " + c.tags + "\n  entry " + c.entry + " method " + c.method + " source "
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

    /** The reference must still be B1: a difference here is a change of the frozen frontend, never of the case. */
    private String drift(Adr068Corpus.Case c, OptimizationLevel level, Compilation reference) {
        var cells = b1.get(c.key);
        if (cells == null) return null;
        String expected = cells.get(level.name());
        String actual = reference.outcome().cell();
        return expected.equals(actual) ? null : level + ": " + expected + " -> " + actual;
    }

    /** Every level, with and without what the case recorded, against the reference. Null when all are equal. */
    private String byteDifference(Adr068Corpus.Case c, Compilation reference, Compilation javac) {
        if (!reference.outcome().diagnosticsCell().equals(javac.outcome().diagnosticsCell())) {
            return "diagnostics " + reference.outcome().diagnosticsCell() + " / " + javac.outcome().diagnosticsCell();
        }
        if (reference.result() != null && !reference.result().params().equals(javac.result().params())) {
            return "params " + reference.result().params() + " / " + javac.result().params();
        }
        var levels = new ArrayList<String>();
        for (var level : Adr068Ledger.LEVELS) {
            var r = compile(c, options(c, level, c.sourceMap, CompilerFrontend.JAVAPARSER));
            var j = compile(c, options(c, level, c.sourceMap, CompilerFrontend.JAVAC));
            String drift = drift(c, level, r);
            if (drift != null) return "reference drift " + drift;
            if (!r.outcome().cell().equals(j.outcome().cell())) {
                levels.add(level + " " + r.outcome().cell() + " / " + j.outcome().cell());
            }
        }
        return levels.isEmpty() ? null : String.join("; ", levels);
    }

    /** What the frontends' source maps say of the same program: {@code location ...} if a position moved (I7). */
    private static String sourceMapDifference(Adr068Corpus.Case c) {
        if (!Adr068Ledger.isProgramEntry(c.entry)) return "-";
        var level = OptimizationLevel.DEFAULT;
        var reference = compile(c, options(c, level, true, CompilerFrontend.JAVAPARSER));
        var javac = compile(c, options(c, level, true, CompilerFrontend.JAVAC));
        if (!reference.accepted() || !javac.accepted()) {
            return reference.accepted() == javac.accepted() ? "-" : "location: only one compiles with a source map";
        }
        if (!reference.outcome().cell().equals(javac.outcome().cell())) {
            return "location: bytes with a source map " + reference.outcome().cell() + " / " + javac.outcome().cell();
        }
        var left = located(reference.result());
        var right = located(javac.result());
        if (!left.keySet().equals(right.keySet())) return "location: located terms " + left.keySet() + " / " + right.keySet();
        boolean fragment = false;
        for (var entry : left.entrySet()) {
            var l = entry.getValue();
            var r = right.get(entry.getKey());
            if (l.line() != r.line() || l.column() != r.column()) {
                return "location: term " + entry.getKey() + " " + l.line() + ":" + l.column() + " / " + r.line() + ":" + r.column();
            }
            if (!Objects.equals(fileName(l.fileName()), fileName(r.fileName()))) {
                return "location: term " + entry.getKey() + " file " + l.fileName() + " / " + r.fileName();
            }
            fragment |= !l.fragment().equals(r.fragment());
        }
        return fragment ? "fragment" : "same";
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

    private static Coverage coverage(Adr068Corpus.Case c, Map<String, Method> reference, boolean referenceAccepted) {
        if (!Adr068Ledger.isProgramEntry(c.entry) || c.main == null) return new Coverage(0, reference.size(), "-", null, List.of());
        List<JavacEvidence.Attempt> attempts;
        try {
            attempts = JavacEvidence.attempts(c.main.text(), Adr068Ledger.texts(c.libraries),
                    c.entry.equals("compileMethod") ? c.method : null, options(c, OptimizationLevel.DEFAULT, c.sourceMap, CompilerFrontend.JAVAC));
        } catch (AssertionError e) {
            throw e;
        } catch (RuntimeException e) {
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
