package org.julclang.tools.adr078;

import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LibrarySourceResolver;
import org.julclang.tools.debug.SourceDebugService;
import org.julclang.tools.model.MockTransaction;
import org.julclang.tools.model.MockTransaction.DataInput;
import org.julclang.tools.model.SourceDebugModels.ActionRequest;
import org.julclang.tools.model.SourceDebugModels.CloseRequest;
import org.julclang.tools.model.SourceDebugModels.LocalsRequest;
import org.julclang.tools.model.SourceDebugModels.OpenRequest;
import org.julclang.tools.model.UplcModels.Breakpoints;
import org.julclang.tools.model.UplcModels.JavaLocation;
import org.julclang.tools.model.UplcModels.Snapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * ADR-078 D5's debug oracle: what a developer sees in JuLC's source debugger (the playground's and the Wasm engine's
 * {@link SourceDebugService}) for programs whose library methods have equal content keys. Shared by
 * {@code LibraryTieDebugOracleTest} and the recorder of {@code 0.1.0-pre21}'s traces ({@link #main},
 * {@code src/test/resources/adr078/pre21-debug.txt}); it uses only the service's public API, so it also runs on the
 * pre21 artifacts.
 * <p>
 * A trace has three parts:
 * <ul>
 *   <li><b>Breakpoint hits:</b> a Java breakpoint on every executable line, then {@code continue} to the end. It
 *       records the line and column of every stop.</li>
 *   <li><b>Locals:</b> the visible Java locals (name and value) at each of those stops.</li>
 *   <li><b>Source steps:</b> every step of the machine, keeping each change of Java location (line and column).</li>
 * </ul>
 */
public final class Adr078DebugFixtures {

    private Adr078DebugFixtures() {
    }

    /** A validator and its one library source. */
    public record Program(String label, String validator, String library) {
    }

    /** A fixture: a base program and variants, each a rename of the base (the same lines, other identifiers). */
    public record Fixture(String name, List<Program> programs) {
    }

    /** What the debugger shows for one program. */
    public record Trace(List<Integer> executableLines, List<String> hits, List<String> locals, List<String> steps,
                        String status) {
    }

    private static String library(String body) {
        return """
                package q;

                import java.math.BigInteger;
                import java.util.Optional;
                import org.julclang.core.types.JulcList;
                import org.julclang.stdlib.annotation.OnchainLibrary;

                @OnchainLibrary
                public class L {
                %s}
                """.formatted(body);
    }

    private static String spending(String helpers, String body) {
        return """
                import java.math.BigInteger;
                import java.util.Optional;
                import org.julclang.core.PlutusData;
                import org.julclang.core.types.JulcList;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.Builtins;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.SpendingValidator;
                import q.L;

                @SpendingValidator
                class DebugTwins {
                %s
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                %s
                    }
                }
                """.formatted(helpers, body);
    }

    private static Fixture renamed(String name, String validator, String library, String from, String to) {
        return new Fixture(name, List.of(new Program("base", validator, library),
                new Program(from + " -> " + to, validator.replace(from, to), library.replace(from, to))));
    }

    /** The fixtures of ADR-078 D5's debug oracle. */
    public static List<Fixture> fixtures() {
        var fixtures = new ArrayList<Fixture>();
        String twins = """
                    public static BigInteger alpha(BigInteger x) { if (x.signum() > 0) { return x; } else { return BigInteger.ZERO; } }
                    public static BigInteger beta(BigInteger x) { if (x.signum() > 0) { return x; } else { return BigInteger.ZERO; } }
                """;
        fixtures.add(renamed("twins", spending("", """
                        BigInteger n = Builtins.unIData(redeemer);
                        BigInteger a = L.alpha(n);
                        BigInteger b = L.beta(n.negate());
                        return a.add(b).signum() >= 0;"""), library(twins), "alpha", "zeta"));

        // JOUST's Crowdfund shape: two generated `?? 0` helpers with identical bodies, used by two branches.
        String orElse = """
                    public static BigInteger contribute__orElse1(Optional<BigInteger> o) { if (o.isPresent()) { return o.get(); } else { return BigInteger.valueOf(0L); } }
                    public static BigInteger refund__orElse2(Optional<BigInteger> o) { if (o.isPresent()) { return o.get(); } else { return BigInteger.valueOf(0L); } }
                """;
        fixtures.add(renamed("crowdfund-like", spending("", """
                        BigInteger r = Builtins.unIData(redeemer);
                        BigInteger got = r.signum() > 0 ? L.contribute__orElse1(Optional.of(r)) : L.refund__orElse2(Optional.empty());
                        BigInteger prior = L.refund__orElse2(Optional.of(got));
                        return prior.add(got).signum() >= 0;"""), library(orElse), "contribute", "zqaai"));

        // C11: an unread helper reads one twin.
        fixtures.add(renamed("C11-unread-helper", spending("""
                    static BigInteger unused(BigInteger x) { return L.alpha(x); }
                """, """
                        BigInteger n = Builtins.unIData(redeemer);
                        BigInteger b = L.beta(n);
                        return b.signum() >= 0;"""), library(twins), "alpha", "zeta"));

        // C12: an order change crosses FLAT's 128 index boundary.
        var c = new StringBuilder("    public static long c(long n, JulcList<BigInteger> xs) {\n        long v0 = alpha(n);\n");
        for (int i = 1; i <= 121; i++) c.append("        long v").append(i).append(" = v").append(i - 1).append(" + 1;\n");
        c.append("        return v121 + 0");
        for (int i = 0; i < 12; i++) c.append(" + xs.head().longValue()");
        c.append(";\n    }\n");
        fixtures.add(renamed("C12-index-width", spending("", """
                        long n = Builtins.unIData(redeemer).longValue();
                        long total = L.c(n, JulcList.of(BigInteger.ONE)) + L.beta(n);
                        return total > 0;"""),
                library("    public static long alpha(long x) { return x; }\n    public static long beta(long y) { return y; }\n" + c),
                "alpha", "zeta"));

        // A static initialiser is the only use of one twin.
        fixtures.add(renamed("static-initialiser-only", spending("""
                    static final BigInteger K = L.zeta(BigInteger.TWO);
                """, """
                        BigInteger n = Builtins.unIData(redeemer);
                        BigInteger a = L.alpha(n).add(K);
                        return a.signum() >= 0;"""), library(twins.replace("beta", "zeta")), "zeta", "aaaa"));

        // A multi-purpose validator whose handlers first use opposite twins (debugged on its spend handler).
        String multi = """
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.Builtins;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.MultiValidator;
                import org.julclang.stdlib.annotation.Purpose;
                import q.L;

                @MultiValidator
                class DebugHandlers {
                    @Entrypoint(purpose = Purpose.SPEND)
                    static boolean spend(PlutusData redeemer, ScriptContext ctx) {
                        BigInteger n = Builtins.unIData(redeemer);
                        BigInteger s = L.beta(n);
                        return s.signum() >= 0;
                    }

                    @Entrypoint(purpose = Purpose.MINT)
                    static boolean mint(PlutusData redeemer, ScriptContext ctx) {
                        BigInteger n = Builtins.unIData(redeemer);
                        BigInteger m = L.alpha(n);
                        return m.signum() >= 0;
                    }
                }
                """;
        fixtures.add(renamed("multi-purpose-opposite-first-uses", multi, library(twins), "alpha", "zeta"));
        return fixtures;
    }

    private static MockTransaction spend() {
        var input = new MockTransaction.TxIn("11".repeat(32), 0L,
                new MockTransaction.Address("$self", null), new MockTransaction.Value("10000000", null),
                new MockTransaction.Datum("inline", new DataInput("uplc", "I 7"), null), null);
        return new MockTransaction(new MockTransaction.Purpose("spend", 0, null, null),
                new DataInput("uplc", "I 5"), List.of(input), null, null, "0", null, null, null,
                null, null, null, "22".repeat(32), null, null, null, null);
    }

    private static SourceDebugService service;

    private static synchronized SourceDebugService service() {
        if (service == null) {
            service = new SourceDebugService(LibrarySourceResolver.scanClasspathSources(JulcCompiler.class.getClassLoader()));
        }
        return service;
    }

    private static String location(JavaLocation location) {
        return location == null ? null : location.line() + ":" + location.column();
    }

    /** Opens {@code program} in the source debugger, with locals, and records what it shows. */
    public static Trace trace(Program program) {
        var debug = service();
        var opened = debug.open(new OpenRequest(program.validator(), program.library(), List.of(), spend(), 11,
                null, null, null, null, true)).body();
        if (!opened.ok()) throw new IllegalStateException(program.label() + ": " + opened.error() + " " + opened.diagnostics());
        String session = opened.sessionId();
        try {
            var lines = List.copyOf(opened.executableJavaLines());
            // Breakpoint hits and the locals at each.
            var hits = new ArrayList<String>();
            var locals = new ArrayList<String>();
            var breakpoints = new Breakpoints(List.of(), false, false, List.of(), lines);
            long from = 0;
            for (int guard = 0; guard < 10_000; guard++) {
                Snapshot stop = debug.act(new ActionRequest(session, "continue", from, breakpoints)).body().snapshot();
                if (stop.finished() || !"javaBreakpoint".equals(stop.stopReason())) break;
                hits.add(location(stop.javaLocation()));
                locals.add(locals(debug, session, stop));
                from = stop.step();
            }
            // Source steps: every change of Java location over the whole run.
            var steps = new ArrayList<String>();
            String previous = null;
            String status = null;
            for (long step = 0; step <= opened.timeline().totalSteps(); step++) {
                Snapshot snapshot = debug.act(new ActionRequest(session, "goto", step, null)).body().snapshot();
                String here = location(snapshot.javaLocation());
                if (here != null && !here.equals(previous)) steps.add(here);
                if (here != null) previous = here;
                status = snapshot.status();
            }
            return new Trace(lines, hits, locals, steps, status);
        } finally {
            debug.close(new CloseRequest(session));
        }
    }

    private static String locals(SourceDebugService debug, String session, Snapshot stop) {
        if (stop.stopGeneration() == null) return "{}";
        var response = debug.locals(new LocalsRequest(session, stop.stopGeneration())).body();
        var joiner = new StringJoiner(", ", "{", "}");
        for (var scope : response.scopes()) {
            for (var variable : scope.variables()) {
                joiner.add(variable.name() + "=" + (variable.value() == null ? variable.availability() : variable.value().summary()));
            }
        }
        return response.availability() + joiner;
    }

    /** One line per trace part, keyed {@code fixture | program | part}, as {@code pre21-debug.txt} holds them. */
    public static Map<String, String> record() {
        var lines = new LinkedHashMap<String, String>();
        for (var fixture : fixtures()) {
            for (var program : fixture.programs()) {
                String key = fixture.name() + " | " + program.label();
                var trace = trace(program);
                lines.put(key + " | executable", trace.executableLines().toString());
                lines.put(key + " | hits", trace.hits().toString());
                for (int i = 0; i < trace.locals().size(); i++) lines.put(key + " | locals " + i, trace.locals().get(i));
                lines.put(key + " | steps", trace.steps().toString());
                lines.put(key + " | status", trace.status());
            }
        }
        return lines;
    }

    /** Prints {@link #record()}: how {@code pre21-debug.txt} was produced, on the pre21 class path. */
    public static void main(String[] args) {
        record().forEach((key, value) -> System.out.println(key + " = " + value));
    }
}
