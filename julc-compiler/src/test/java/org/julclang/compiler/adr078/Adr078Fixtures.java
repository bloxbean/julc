package org.julclang.compiler.adr078;

import org.julclang.compiler.CompileResult;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationConfiguration;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.core.source.SourceLocation;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;
import org.julclang.vm.JulcVm;
import org.julclang.vm.OptimizationCostProfiles;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * ADR-078 D5's fixed fixtures and the modes they are compiled in, shared by {@code LibraryTieOrderFixturesTest} and
 * by the recorder that captured {@code 0.1.0-pre21}'s outcomes ({@code src/test/resources/adr078/pre21-fixtures.txt},
 * see {@code adr/evidence/078/m1a-pre21-recording.md}). It uses only JuLC's public compiler API, so the same file
 * compiles against the pre21 artifacts.
 */
public final class Adr078Fixtures {

    private Adr078Fixtures() {
    }

    /**
     * One compilation: a {@code compileMethod} target and its method, or, where {@code method} is null, a validator
     * compiled whole; and its library sources in pool order.
     */
    public record Variant(String label, String target, String method, List<String> libraries) {
    }

    /**
     * A fixture: its variants, the first being the base. {@code expectation} says how the other variants compare with
     * the base in every mode: {@code SAME} (ADR-078's tie rule), {@code DIFFERENT_IN_SOME_MODE} (a known limit), or
     * {@code RECORDED} (compared with pre21 only, such as the two pool orders of C20).
     */
    public record Fixture(String name, String expectation, List<Variant> variants) {
    }

    /** A compilation mode: an optimisation profile, with source maps on or off. */
    public record Mode(String profile, boolean sourceMaps) {
        @Override
        public String toString() {
            return profile + (sourceMaps ? "+maps" : "");
        }

        public CompilerOptions options(Consumer<CompilerOptions> more) {
            var options = OptimizationConfiguration.apply(new CompilerOptions().setTarget(CompilerTarget.PLUTUS_V3_PV11)
                    .setSourceMapEnabled(sourceMaps), profile, null);
            if (more != null) more.accept(options);
            return options;
        }
    }

    /** The modes of ADR-078 D5: every optimisation level, source maps on and off. */
    public static List<Mode> modes() {
        var modes = new ArrayList<Mode>();
        for (var level : List.of("none", "baseline", "pv11-safe", "pv11-costed")) {
            modes.add(new Mode(level, false));
            modes.add(new Mode(level, true));
        }
        return modes;
    }

    /**
     * The outcome of compiling a variant in a mode: its FLAT digest and size, or its whole rejection message; the
     * multiset of method binders in its PIR (I3); and, with source maps, the complete map, every DFS index with its full
     * location (I4).
     */
    public record Outcome(String flat, int size, String rejection, Map<Integer, SourceLocation> sourceMap, Program program,
                          String pir, String binders) {
        public boolean compiled() {
            return rejection == null;
        }

        @Override
        public String toString() {
            return compiled() ? flat + " " + size + " B" : "REJECTED " + rejection;
        }

        /**
         * The complete source map: one {@code index=file:line:column:fragment} entry per mapped DFS index, the entries
         * separated by tabs (escaped in fragments).
         */
        public String fullSourceMap() {
            if (sourceMap == null) return "none";
            var entries = new ArrayList<String>();
            sourceMap.forEach((index, location) -> entries.add(index + "=" + location.fileName() + ":" + location.line() + ":"
                    + location.column() + ":" + escape(String.valueOf(location.fragment()))));
            return String.join("\t", entries);
        }
    }

    public static Outcome compile(Variant variant, Mode mode) {
        try {
            var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), mode.options(null));
            var result = variant.method() == null ? compiler.compileWithDetails(variant.target(), variant.libraries())
                    : compiler.compileMethod(variant.target(), variant.method(), variant.libraries());
            if (result.hasErrors()) return rejected(String.valueOf(result.diagnostics()));
            return outcome(result);
        } catch (RuntimeException e) {
            return rejected(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static Outcome rejected(String message) {
        return new Outcome(null, 0, escape(message), null, null, null, null);
    }

    public static Outcome outcome(CompileResult result) {
        byte[] bytes = UplcFlatEncoder.encodeProgram(result.program());
        Map<Integer, SourceLocation> indexed = result.sourceMap() == null ? null
                : new TreeMap<>(result.sourceMap().toIndexed(result.program().term()));
        return new Outcome(sha256(bytes), bytes.length, null, indexed, result.program(), result.pirFormatted(),
                binders(result.pirTerm()));
    }

    /** The method binders of a PIR term (qualified, so dotted), as a sorted multiset: one per binding and copy. */
    static String binders(PirTerm term) {
        if (term == null) return "none";
        var names = new ArrayList<String>();
        var pending = new ArrayDeque<PirTerm>();
        pending.push(term);
        while (!pending.isEmpty()) {
            switch (pending.pop()) {
                case PirTerm.Let let -> {
                    if (let.name().contains(".")) names.add(let.name());
                    pending.push(let.value());
                    pending.push(let.body());
                }
                case PirTerm.LetRec rec -> {
                    for (var binding : rec.bindings()) {
                        if (binding.name().contains(".")) names.add(binding.name());
                        pending.push(binding.value());
                    }
                    pending.push(rec.body());
                }
                case PirTerm.Lam lam -> pending.push(lam.body());
                case PirTerm.App app -> {
                    pending.push(app.function());
                    pending.push(app.argument());
                }
                case PirTerm.IfThenElse ite -> {
                    pending.push(ite.cond());
                    pending.push(ite.thenBranch());
                    pending.push(ite.elseBranch());
                }
                case PirTerm.Trace trace -> {
                    pending.push(trace.message());
                    pending.push(trace.body());
                }
                case PirTerm.DataConstr constr -> constr.fields().forEach(pending::push);
                case PirTerm.DataMatch match -> {
                    pending.push(match.scrutinee());
                    match.branches().forEach(branch -> pending.push(branch.body()));
                }
                case PirTerm.ListMatch match -> {
                    pending.push(match.scrutinee());
                    pending.push(match.nilBranch());
                    pending.push(match.consBranch());
                }
                case PirTerm.PairMatch match -> {
                    pending.push(match.scrutinee());
                    pending.push(match.body());
                }
                case PirTerm.IntegerCase integerCase -> {
                    pending.push(integerCase.scrutinee());
                    integerCase.branches().forEach(pending::push);
                }
                default -> {
                }
            }
        }
        Collections.sort(names);
        return names.toString();
    }

    /** A value on one line: backslashes and line breaks escaped. */
    static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The argument vectors a fixture's {@code f} is applied to. The first is empty: the result is the function itself,
     * which {@link #record} then applies to the next vector, so a returned function is compared by what it computes.
     */
    public static List<List<PlutusData>> arguments(Fixture fixture) {
        if (fixture.name().startsWith("C26")) {
            return List.of(List.of(), List.of(PlutusData.list(PlutusData.integer(1), PlutusData.integer(2)), PlutusData.integer(3)));
        }
        if (fixture.name().startsWith("traces")) {
            return List.of(List.of(), List.of(PlutusData.integer(5)));
        }
        return List.of(List.of(), List.of(PlutusData.integer(5)), List.of(PlutusData.integer(2)));
    }

    private static JulcVm vm;

    /** The PV11 VM (the Java provider) with the costs the optimisation profiles assume. */
    public static synchronized JulcVm vm() {
        if (vm == null) {
            vm = JulcVm.create("Java");
            var profile = OptimizationCostProfiles.PLUTUS_V3_PV11_COSTS_V1;
            vm.setCostModelParams(profile.costModelParameters(), profile.target());
        }
        return vm;
    }

    /** The replay limit: a transaction's maximum budget. */
    public static final ExBudget REPLAY_LIMIT = new ExBudget(10_000_000_000L, 14_000_000L);

    /** The higher limit a one-sided exhaustion is replayed at; still exhausted there, it is inconclusive. */
    public static final ExBudget HIGH_LIMIT = new ExBudget(1_000_000_000_000L, 1_400_000_000L);

    /** C12's tight limit, under which pre21's two spellings differed. */
    public static final ExBudget C12_LIMIT = new ExBudget(31_150_000, 110_000);

    /**
     * An evaluation as ADR-078 I6a compares it, structurally: the result term as FLAT (hex), or the whole failure
     * reason, or exhaustion; and the traces as a list. The budget is apart (I6b); {@code result} is kept to apply a
     * returned function.
     */
    public record Evaluation(String outcome, ExBudget budget, Term result) {
        public boolean exhausted() {
            return outcome.startsWith("EXHAUSTED");
        }
    }

    public static Evaluation evaluate(Program program, List<PlutusData> arguments, ExBudget limit) {
        return describe(vm().evaluateWithArgs(program, arguments, limit));
    }

    /** {@code function} applied to {@code arguments}, each as Data, under {@code limit}. */
    public static Evaluation apply(Term function, List<PlutusData> arguments, ExBudget limit) {
        var term = function;
        for (var argument : arguments) term = Term.apply(term, Term.const_(Constant.data(argument)));
        return describe(vm().evaluate(Program.plutusV3(term), limit));
    }

    /**
     * Traces as a JSON array of strings: every quote, backslash and control character escaped, so that two traces are
     * never written as one, or one as two (Codex M1a C14).
     */
    public static String traces(List<String> traces) {
        var text = new StringBuilder("[");
        for (int i = 0; i < traces.size(); i++) text.append(i == 0 ? "" : ",").append(jsonString(traces.get(i)));
        return text.append(']').toString();
    }

    /** {@code value} as a JSON string literal. */
    static String jsonString(String value) {
        var text = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> text.append("\\\"");
                case '\\' -> text.append("\\\\");
                case '\n' -> text.append("\\n");
                case '\r' -> text.append("\\r");
                case '\t' -> text.append("\\t");
                default -> {
                    if (c < 0x20) text.append(String.format("\\u%04x", (int) c));
                    else text.append(c);
                }
            }
        }
        return text.append('"').toString();
    }

    private static Evaluation describe(EvalResult result) {
        return switch (result) {
            case EvalResult.Success s -> new Evaluation("SUCCESS term=" + HexFormat.of().formatHex(
                    UplcFlatEncoder.encodeProgram(Program.plutusV3(s.resultTerm()))) + " traces=" + traces(s.traces()), s.consumed(), s.resultTerm());
            case EvalResult.Failure f -> new Evaluation("FAILURE reason=" + escape(String.valueOf(f.error())) + " traces=" + traces(f.traces()), f.consumed(), null);
            case EvalResult.BudgetExhausted e -> new Evaluation("EXHAUSTED traces=" + traces(e.traces()), e.consumed(), null);
        };
    }

    /**
     * Every evaluation of a compiled variant, keyed by suffix: {@code eval i} on each argument vector (at the replay
     * limit, and, where it exhausts, {@code eval i high} at the higher one); for a returned function, {@code applied}
     * to the next vector; and {@code budget …} lines apart.
     */
    public static Map<String, Evaluation> evaluations(Fixture fixture, Outcome outcome) {
        var result = new LinkedHashMap<String, Evaluation>();
        var arguments = arguments(fixture);
        for (int i = 0; i < arguments.size(); i++) {
            var evaluation = evaluate(outcome.program(), arguments.get(i), REPLAY_LIMIT);
            var high = evaluate(outcome.program(), arguments.get(i), HIGH_LIMIT);
            result.put("eval " + i, evaluation);
            result.put("eval " + i + " high", high);
            // C37: a function whose evaluation exhausted the transaction limit is recovered from the higher-limit
            // replay, so both sides apply it, and make the same evaluation ids, whichever side exhausted.
            var function = evaluation.exhausted() ? high.result() : evaluation.result();
            if (i == 0 && function instanceof Term.Lam && arguments.size() > 1) {
                result.put("applied", apply(function, arguments.get(1), REPLAY_LIMIT));
                result.put("applied high", apply(function, arguments.get(1), HIGH_LIMIT));
            }
        }
        if (fixture.name().startsWith("C12")) result.put("eval 1 c12-limit", evaluate(outcome.program(), arguments.get(1), C12_LIMIT));
        return result;
    }

    // ------------------------------------------------------------------ sources

    static String library(String pkg, String cls, String body) {
        return (pkg.isEmpty() ? "" : "package " + pkg + ";\n") + """
                import java.math.BigInteger;
                import java.util.Optional;
                import org.julclang.core.PlutusData;
                import org.julclang.core.types.JulcArray;
                import org.julclang.core.types.JulcList;
                import org.julclang.stdlib.Builtins;
                import org.julclang.stdlib.annotation.OnchainLibrary;

                @OnchainLibrary
                public class %s {
                %s}
                """.formatted(cls, body);
    }

    static String target(String imports, String body) {
        return "package v;\n\nimport java.math.BigInteger;\nimport java.util.Optional;\nimport org.julclang.core.PlutusData;\n"
                + "import org.julclang.core.types.JulcList;\nimport org.julclang.stdlib.Builtins;\n" + imports + "\nclass T {\n" + body + "}\n";
    }

    private static Variant variant(String label, String target, List<String> libraries) {
        return new Variant(label, target, "f", libraries);
    }

    /** C12's library: {@code c} has 121 chained lets and 12 reads of the list's head (adr/evidence/078/C12.java.txt). */
    static String c12Library(String twin) {
        var c = new StringBuilder("    public static long c(long n, JulcList<BigInteger> xs) {\n        long v0 = " + twin + "(n);\n");
        for (int i = 1; i <= 121; i++) c.append("        long v").append(i).append(" = v").append(i - 1).append(" + 1;\n");
        c.append("        return v121 + 0");
        for (int i = 0; i < 12; i++) c.append(" + xs.head().longValue()");
        c.append(";\n    }\n");
        return library("q", "L", "    public static long " + twin + "(long x) { return x; }\n"
                + "    public static long beta(long y) { return y; }\n" + c);
    }

    public static final String C12_TARGET = target("import q.L;\n",
            "    static long f(long n) { return L.c(n, JulcList.of(BigInteger.ONE)) + L.beta(n); }\n");

    /** ADR-078 D5's fixed fixtures. */
    public static List<Fixture> fixtures() {
        var fixtures = new ArrayList<Fixture>();
        String twins = "    public static BigInteger %s(BigInteger x) { if (x.signum() > 0) { return x; } else { return BigInteger.ZERO; } }\n"
                + "    public static BigInteger %s(BigInteger x) { if (x.signum() > 0) { return x; } else { return BigInteger.ZERO; } }\n";
        String twinTarget = "    static BigInteger f(BigInteger x) { return L.%s(x).add(L.%s(BigInteger.TWO).multiply(x)); }\n";
        fixtures.add(new Fixture("twins-reached", "SAME", List.of(
                variant("alpha/beta", target("import q.L;\n", twinTarget.formatted("alpha", "beta")), List.of(library("q", "L", twins.formatted("alpha", "beta")))),
                variant("zeta/beta", target("import q.L;\n", twinTarget.formatted("zeta", "beta")), List.of(library("q", "L", twins.formatted("zeta", "beta")))),
                variant("beta/alpha swapped uses", target("import q.L;\n", twinTarget.formatted("beta", "alpha")), List.of(library("q", "L", twins.formatted("beta", "alpha")))),
                variant("class Z, package z.moved", target("import z.moved.Z;\n", twinTarget.formatted("alpha", "beta")).replace("L.", "Z."),
                        List.of(library("z.moved", "Z", twins.formatted("alpha", "beta")))))));

        // C11: an unread helper reads one twin.
        String c11Lib = "    public static long %s(long x) { return x + 1; }\n    public static long beta(long y) { return y + 1; }\n";
        String c11Target = "    static long unused(long x) { return L.%s(x); }\n    static long f(long x) { return x; }\n";
        fixtures.add(new Fixture("C11-unread-helper-reads-twin", "SAME", List.of(
                variant("alpha", target("import q.L;\n", c11Target.formatted("alpha")), List.of(library("q", "L", c11Lib.formatted("alpha")))),
                variant("zeta", target("import q.L;\n", c11Target.formatted("zeta")), List.of(library("q", "L", c11Lib.formatted("zeta")))))));

        // C12: an order change crosses FLAT's 128 index boundary (pinned in the test: c8489246…, 1,010 B).
        fixtures.add(new Fixture("C12-index-width", "SAME", List.of(
                variant("alpha", C12_TARGET, List.of(c12Library("alpha"))),
                variant("zeta", C12_TARGET, List.of(c12Library("zeta"))))));

        // C13 (L-C2): a recursive type group spanning two classes; renaming A to Z reorders its registration.
        String ra = "    public record R(Optional<%s.S> s) {}\n    public static boolean isR(R r) { return r.s().isPresent(); }\n";
        String sb = "    public record S(Optional<%s.R> r) {}\n    public static boolean isS(S s) { return s.r().isPresent(); }\n";
        String c13 = "    static boolean f(long x) { return %1$s.isR(new %1$s.R(Optional.empty())) && B.isS(new B.S(Optional.empty())); }\n";
        fixtures.add(new Fixture("C13-cross-class-recursive-types", "DIFFERENT_IN_SOME_MODE", List.of(
                variant("A", target("import q.A;\nimport q.B;\n", c13.formatted("A")), List.of(library("q", "A", ra.formatted("B")), library("q", "B", sb.formatted("A")))),
                variant("Z", target("import q.Z;\nimport q.B;\n", c13.formatted("Z")), List.of(library("q", "Z", ra.formatted("B")), library("q", "B", sb.formatted("Z")))))));

        // C14: an interchangeable overload family, renamed together (SAME) and one member alone (a known limit).
        String family = "    public static long %s(int x) { return x + 1; }\n    public static long %s(long x) { return x + 1; }\n";
        String c14 = "    static long f(long x) { return L.%s(1) + L.%s(x); }\n";
        fixtures.add(new Fixture("C14-overload-family-together", "SAME", List.of(
                variant("inc", target("import q.L;\n", c14.formatted("inc", "inc")), List.of(library("q", "L", family.formatted("inc", "inc")))),
                variant("bump", target("import q.L;\n", c14.formatted("bump", "bump")), List.of(library("q", "L", family.formatted("bump", "bump")))))));
        fixtures.add(new Fixture("C14-overload-member-alone", "DIFFERENT_IN_SOME_MODE", List.of(
                variant("inc", target("import q.L;\n", c14.formatted("inc", "inc")), List.of(library("q", "L", family.formatted("inc", "inc")))),
                variant("inc2", target("import q.L;\n", c14.formatted("inc", "inc2")), List.of(library("q", "L", family.formatted("inc", "inc2")))))));

        // C19 (L-C19): alpha-equal twins with different local spellings, unread; and C19c, a local renamed in a reached method.
        String fold = "    public static long %s(long n) { BigInteger %s = BigInteger.valueOf(7); return JulcArray.of(%s).get(0).longValue(); }\n";
        String c19Target = "    static long f(long x) { return x; }\n";
        fixtures.add(new Fixture("C19-unread-twins-different-spellings", "DIFFERENT_IN_SOME_MODE", List.of(
                variant("alpha(x)", target("import q.L;\n", c19Target), List.of(library("q", "L", fold.formatted("alpha", "x", "x") + fold.formatted("beta", "y", "y")))),
                variant("zeta(x)", target("import q.L;\n", c19Target), List.of(library("q", "L", fold.formatted("zeta", "x", "x") + fold.formatted("beta", "y", "y")))))));
        String reached = "    public static long alpha(long n) { BigInteger x = BigInteger.valueOf(7); return JulcArray.of(x).get(0).longValue() + n; }\n"
                + "    public static long other(long m) { BigInteger %1$s = BigInteger.valueOf(9); return JulcArray.of(%1$s).get(0).longValue() + m; }\n";
        String c19c = "    static long f(long k) { return L.alpha(k) + L.other(k); }\n";
        fixtures.add(new Fixture("C19c-local-rename-collides", "DIFFERENT_IN_SOME_MODE", List.of(
                variant("other's local y", target("import q.L;\n", c19c), List.of(library("q", "L", reached.formatted("y")))),
                variant("other's local x", target("import q.L;\n", c19c), List.of(library("q", "L", reached.formatted("x")))))));

        // C20 (L-C4): the order of the library sources decides whether the program compiles.
        String a = library("q", "A", "    public static long a1(long x) { return B.b0(x); }\n    public static long a2(long x) { return B.b1(x); }\n");
        String b = library("q", "B", "    public static long b0(long x) { return x + 1; }\n    public static long b1(long x) { return A.a1(x); }\n");
        String c20 = target("import q.A;\nimport q.B;\n", "    static long f(long x) { return A.a2(x) + B.b1(x); }\n");
        fixtures.add(new Fixture("C20-pool-order", "RECORDED", List.of(
                variant("[B, A]", c20, List.of(b, a)), variant("[A, B]", c20, List.of(a, b)))));

        // C22: a root that is also its own library: helper and library copy of one name are two bindings.
        String root = library("q", "H", "    static final BigInteger HALF = BigInteger.valueOf(100).divide(BigInteger.TWO);\n"
                + "    public static BigInteger g(BigInteger x) { return x.add(x); }\n"
                + "    public static BigInteger f(BigInteger x) { return g(x).add(HALF); }\n");
        fixtures.add(new Fixture("C22-root-as-own-library", "RECORDED", List.of(new Variant("root", root, "f", List.of(root)))));

        // C26: O9 list-index promotion keys by spelling; a root that is its own library.
        String c26 = library("q", "R", "    public static BigInteger %1$s(JulcList<BigInteger> xs, BigInteger k) { return xs.get(0).add(xs.get(1)).add(k); }\n"
                + "    public static BigInteger beta(JulcList<BigInteger> xs, BigInteger k) { return xs.get(0).add(xs.get(1)).add(k); }\n"
                + "    public static <U> BigInteger f(PlutusData d, BigInteger k) { return %1$s((JulcList<BigInteger>) (Object) d, k); }\n");
        fixtures.add(new Fixture("C26-list-index-promotion", "DIFFERENT_IN_SOME_MODE", List.of(
                new Variant("alpha", c26.formatted("alpha"), "f", List.of(c26.formatted("alpha"))),
                new Variant("zeta", c26.formatted("zeta"), "f", List.of(c26.formatted("zeta"))))));

        // C27: a library method renamed to a stdlib intrinsic's simple name is dispatched to the intrinsic.
        String math = library("", "Math", "    public static long %s(long a) { return a < 0 ? -a : a; }\n");
        String c27 = "class T {\n    static long f(long x) { return Math.%s(x); }\n}\n";
        fixtures.add(new Fixture("C27-intrinsic-name", "DIFFERENT_IN_SOME_MODE", List.of(
                variant("magnitude", c27.formatted("magnitude"), List.of(math.formatted("magnitude"))),
                variant("abs", c27.formatted("abs"), List.of(math.formatted("abs"))))));

        // Cycles: an unread mutually recursive library pair (rejected), a self-recursive method, mutually recursive helpers.
        String mutual = "    public static long even(long n) { return n == 0 ? 1 : odd(n - 1); }\n    public static long odd(long n) { return n == 0 ? 0 : even(n - 1); }\n";
        fixtures.add(new Fixture("cycle-unread-mutual-library", "RECORDED", List.of(
                variant("unread", target("import q.M;\n", "    static long f(long x) { return x; }\n"), List.of(library("q", "M", mutual))))));
        String self = "    public static long %s(long n) { return n <= 0 ? 0 : n + %s(n - 1); }\n";
        fixtures.add(new Fixture("self-recursive-library", "SAME", List.of(
                variant("sum", target("import q.M;\n", "    static long f(long x) { return M.sum(x); }\n"), List.of(library("q", "M", self.formatted("sum", "sum")))),
                variant("total", target("import q.M;\n", "    static long f(long x) { return M.total(x); }\n"), List.of(library("q", "M", self.formatted("total", "total")))))));
        fixtures.add(new Fixture("mutually-recursive-helpers", "RECORDED", List.of(variant("helpers", target("",
                "    static long even(long n) { return n == 0 ? 1 : odd(n - 1); }\n    static long odd(long n) { return n == 0 ? 0 : even(n - 1); }\n"
                        + "    static long f(long x) { return even(x); }\n"), List.of()))));

        // First use through a static initialiser only: zeta is first used by K, alpha by f.
        String staticTwins = twins.formatted("alpha", "zeta");
        fixtures.add(new Fixture("static-initialiser-first-use", "SAME", List.of(
                variant("zeta in K", target("import q.L;\n", "    static final BigInteger K = L.zeta(BigInteger.TWO);\n"
                        + "    static BigInteger f(BigInteger x) { return L.alpha(x).add(K); }\n"), List.of(library("q", "L", staticTwins))),
                variant("renamed: aaa in K, zzz in f", target("import q.L;\n", "    static final BigInteger K = L.aaa(BigInteger.TWO);\n"
                        + "    static BigInteger f(BigInteger x) { return L.zzz(x).add(K); }\n"), List.of(library("q", "L", twins.formatted("zzz", "aaa")))))));

        // Compiled validators (ValidatorAssembly.validator): a single entry point, a static initialiser, and a
        // multi-purpose validator whose handlers first use opposite twins (handlers ranked in purpose tag order).
        String spending = """
                package v;

                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.Builtins;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.SpendingValidator;
                import q.L;

                @SpendingValidator
                class V {
                %s
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        BigInteger n = Builtins.unIData(redeemer);
                %s
                    }
                }
                """;
        String single = spending.formatted("", "        return L.%s(n).add(L.beta(n.negate())).signum() >= 0;");
        fixtures.add(new Fixture("validator-single-entry", "SAME", List.of(
                new Variant("alpha", single.formatted("alpha"), null, List.of(library("q", "L", twins.formatted("alpha", "beta")))),
                new Variant("zeta", single.formatted("zeta"), null, List.of(library("q", "L", twins.formatted("zeta", "beta")))))));
        String withStatic = spending.formatted("    static final BigInteger K = L.%s(BigInteger.TWO);\n",
                "        return L.alpha(n).add(K).signum() >= 0;");
        fixtures.add(new Fixture("validator-static-initialiser", "SAME", List.of(
                new Variant("zeta in K", withStatic.formatted("zeta"), null, List.of(library("q", "L", twins.formatted("alpha", "zeta")))),
                new Variant("aaaa in K", withStatic.formatted("aaaa"), null, List.of(library("q", "L", twins.formatted("alpha", "aaaa")))))));
        String multi = """
                package v;

                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.Builtins;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.MultiValidator;
                import org.julclang.stdlib.annotation.Purpose;
                import q.L;

                @MultiValidator
                class M {
                    @Entrypoint(purpose = Purpose.MINT)
                    static boolean mint(PlutusData redeemer, ScriptContext ctx) {
                        return L.%1$s(Builtins.unIData(redeemer)).signum() >= 0;
                    }

                    @Entrypoint(purpose = Purpose.SPEND)
                    static boolean spend(PlutusData redeemer, ScriptContext ctx) {
                        return L.beta(Builtins.unIData(redeemer)).signum() >= 0;
                    }
                }
                """;
        fixtures.add(new Fixture("validator-multi-purpose", "SAME", List.of(
                new Variant("alpha", multi.formatted("alpha"), null, List.of(library("q", "L", twins.formatted("alpha", "beta")))),
                new Variant("zeta", multi.formatted("zeta"), null, List.of(library("q", "L", twins.formatted("zeta", "beta")))))));

        // C3: traces in a fixed order, through a library twin.
        String traced = "    public static PlutusData %s(PlutusData d) { return Builtins.trace(\"t\", d); }\n"
                + "    public static PlutusData beta(PlutusData d) { return Builtins.trace(\"t\", d); }\n";
        String tracedTarget = "    static PlutusData f(PlutusData d) { return Builtins.trace(\"one\", L.%s(L.beta(Builtins.trace(\"two\", d)))); }\n";
        fixtures.add(new Fixture("traces-ordered", "SAME", List.of(
                variant("alpha", target("import q.L;\n", tracedTarget.formatted("alpha")), List.of(library("q", "L", traced.formatted("alpha")))),
                variant("zeta", target("import q.L;\n", tracedTarget.formatted("zeta")), List.of(library("q", "L", traced.formatted("zeta")))))));

        // C2: the assembly's roots. Handlers declared against tag order, first using opposite twins: the mint handler
        // (tag 0) comes first, so its twin is bound outermost.
        String longTwins = "    public static long %s(long x) { return x + 1; }\n    public static long beta(long y) { return y + 1; }\n";
        String reverseTags = """
                package v;

                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.Builtins;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.MultiValidator;
                import org.julclang.stdlib.annotation.Purpose;
                import q.L;

                @MultiValidator
                class M {
                    @Entrypoint(purpose = Purpose.SPEND)
                    static boolean spend(PlutusData redeemer, ScriptContext ctx) {
                        return L.beta(Builtins.unIData(redeemer).longValue()) > 0;
                    }

                    @Entrypoint(purpose = Purpose.MINT)
                    static boolean mint(PlutusData redeemer, ScriptContext ctx) {
                        return L.%1$s(Builtins.unIData(redeemer).longValue()) > 0;
                    }
                }
                """;
        fixtures.add(new Fixture("assembly-handlers-against-tag-order", "SAME", List.of(
                new Variant("zeta", reverseTags.formatted("zeta"), null, List.of(library("q", "L", longTwins.formatted("zeta")))),
                new Variant("aaaa", reverseTags.formatted("aaaa"), null, List.of(library("q", "L", longTwins.formatted("aaaa")))))));
        // compileMethod: an unread helper declared before the target, first using the other twin: the target's twin is
        // bound outermost.
        String helperFirst = "    static long unused(long x) { return L.beta(x); }\n    static long f(long x) { return L.%s(x); }\n";
        fixtures.add(new Fixture("assembly-unread-helper-before-target", "SAME", List.of(
                variant("zeta", target("import q.L;\n", helperFirst.formatted("zeta")), List.of(library("q", "L", longTwins.formatted("zeta")))),
                variant("aaaa", target("import q.L;\n", helperFirst.formatted("aaaa")), List.of(library("q", "L", longTwins.formatted("aaaa")))))));

        // Negative controls: a constant changed in one twin; a body changed in an unread binding that survives at NONE.
        fixtures.add(new Fixture("negative-constant-in-one-twin", "DIFFERENT_IN_SOME_MODE", List.of(
                variant("twins", target("import q.L;\n", twinTarget.formatted("alpha", "beta")), List.of(library("q", "L", twins.formatted("alpha", "beta")))),
                variant("beta returns ONE", target("import q.L;\n", twinTarget.formatted("alpha", "beta")),
                        List.of(library("q", "L", twins.formatted("alpha", "beta").replaceFirst("(?s)(beta.*?)BigInteger.ZERO", "$1BigInteger.ONE")))))));
        String unreadLib = "    public static long used(long x) { return x + 1; }\n    public static long unread(long x) { return x * %d; }\n";
        fixtures.add(new Fixture("negative-unread-body-at-none", "DIFFERENT_IN_SOME_MODE", List.of(
                variant("unread * 2", target("import q.L;\n", "    static long f(long x) { return L.used(x); }\n"), List.of(library("q", "L", unreadLib.formatted(2)))),
                variant("unread * 3", target("import q.L;\n", "    static long f(long x) { return L.used(x); }\n"), List.of(library("q", "L", unreadLib.formatted(3)))))));
        return fixtures;
    }

    /**
     * The fixtures' outcomes in every mode, as the pre21 recording holds them: per fixture, variant and mode, the
     * compilation, the method binders (I3), the complete source map (I4, map modes), and every evaluation of a
     * compiled {@code compileMethod} variant (I6a) with its budget apart (I6b).
     */
    public static Map<String, String> record() {
        var lines = new LinkedHashMap<String, String>();
        for (var fixture : fixtures()) {
            for (var variant : fixture.variants()) {
                for (var mode : modes()) {
                    String key = fixture.name() + " | " + variant.label() + " | " + mode;
                    var outcome = compile(variant, mode);
                    lines.put(key, outcome.toString());
                    if (!outcome.compiled()) continue;
                    lines.put(key + " | binders", outcome.binders());
                    if (mode.sourceMaps()) lines.put(key + " | source map", outcome.fullSourceMap());
                    if (variant.method() == null) continue;
                    evaluations(fixture, outcome).forEach((suffix, evaluation) -> {
                        lines.put(key + " | " + suffix, evaluation.outcome());
                        lines.put(key + " | budget " + suffix, evaluation.budget().cpuSteps() + " cpu, " + evaluation.budget().memoryUnits() + " mem");
                    });
                }
            }
        }
        // C5: a rejected program's diagnostic, with the binder-namespace check off and on.
        for (var fixture : fixtures()) {
            for (var variant : fixture.variants()) {
                var mode = new Mode("pv11-safe", false);
                for (boolean verify : List.of(false, true)) {
                    String before = System.getProperty("julc.verifyBinderNamespace");
                    System.setProperty("julc.verifyBinderNamespace", String.valueOf(verify));
                    try {
                        var outcome = compile(variant, mode);
                        if (!outcome.compiled()) lines.put(fixture.name() + " | " + variant.label() + " | diagnostic verify=" + verify, outcome.rejection());
                    } finally {
                        if (before == null) System.clearProperty("julc.verifyBinderNamespace");
                        else System.setProperty("julc.verifyBinderNamespace", before);
                    }
                }
            }
        }
        return lines;
    }

    /** Prints {@link #record()}: how {@code pre21-fixtures.txt} was produced, on the pre21 class path. */
    public static void main(String[] args) {
        record().forEach((key, value) -> System.out.println(key + " = " + value));
    }
}
