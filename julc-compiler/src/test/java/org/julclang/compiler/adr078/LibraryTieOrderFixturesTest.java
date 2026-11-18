package org.julclang.compiler.adr078;

import org.julclang.compiler.adr078.Adr078Fixtures.Evaluation;
import org.julclang.compiler.CompileResult;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.JavaLibraryProvider;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.codegen.ValidatorAssembly;
import org.julclang.compiler.frontend.javac.Adr078AssemblyBridge;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.adr078.Adr078Fixtures.Fixture;
import org.julclang.compiler.adr078.Adr078Fixtures.Mode;
import org.julclang.compiler.adr078.Adr078Fixtures.Outcome;
import org.julclang.compiler.adr078.Adr078Fixtures.Variant;
import org.julclang.core.PlutusData;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-078 D5: the fixed fixtures, compiled in every mode, against what {@code 0.1.0-pre21} gave for them
 * ({@code adr078/pre21-fixtures.txt}, recorded by {@link Adr078Fixtures#main}).
 * <ul>
 *   <li>A variant compiles exactly where it compiled on pre21, and a rejection says what pre21's said, with the
 *       binder-namespace check off and on (C29, C5).</li>
 *   <li>A {@code SAME} fixture's variants give the base's bytes in every mode: the tie rule (D1, D2).</li>
 *   <li>A {@code DIFFERENT_IN_SOME_MODE} fixture is a known limit or a negative control: some mode differs.</li>
 *   <li>The assembly's roots decide the order (C2): handlers in purpose tag order, and the target before unread
 *       helpers.</li>
 *   <li>I3: every variant binds the same multiset of method binders as on pre21.</li>
 *   <li>I4: every source-map build has pre21's complete map (every DFS index, file, line, column and fragment), and a
 *       renamed variant has its base's map through the rename mapping.</li>
 *   <li>I6a: every evaluation pre21 completed has the same result (as a FLAT term), failure reason and traces; a
 *       returned function is applied and compared too; a one-sided exhaustion is replayed at a higher limit. Budgets
 *       are reported, not compared (I6b).</li>
 * </ul>
 */
class LibraryTieOrderFixturesTest {

    private static final Map<String, String> PRE21 = new LinkedHashMap<>();
    private static final Map<String, Outcome> NOW = new HashMap<>();
    private static final Pattern ENTRY = Pattern.compile("(\\d+)=([^:]*):(\\d+):(\\d+):(.*)");

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = LibraryTieOrderFixturesTest.class.getResourceAsStream("/adr078/pre21-fixtures.txt")) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                int at = line.indexOf(" = ");
                PRE21.put(line.substring(0, at), line.substring(at + 3));
            }
        }
        for (var fixture : Adr078Fixtures.fixtures()) {
            for (var variant : fixture.variants()) {
                for (var mode : Adr078Fixtures.modes()) NOW.put(key(fixture, variant, mode), Adr078Fixtures.compile(variant, mode));
            }
        }
    }

    private static String key(Fixture fixture, Variant variant, Mode mode) {
        return fixture.name() + " | " + variant.label() + " | " + mode;
    }

    private static Fixture fixture(String prefix) {
        return Adr078Fixtures.fixtures().stream().filter(f -> f.name().startsWith(prefix)).findFirst().orElseThrow();
    }

    @Test
    void everyVariantCompilesExactlyWherePre21CompiledIt() {
        var mismatches = new ArrayList<String>();
        for (var fixture : Adr078Fixtures.fixtures()) {
            for (var variant : fixture.variants()) {
                for (var mode : Adr078Fixtures.modes()) {
                    String key = key(fixture, variant, mode);
                    String pre21 = PRE21.get(key);
                    assertTrue(pre21 != null, "no pre21 record for " + key + ": re-record pre21-fixtures.txt");
                    if (pre21.startsWith("REJECTED") == NOW.get(key).compiled()) mismatches.add(key + ": pre21 " + pre21 + ", now " + NOW.get(key));
                }
            }
        }
        assertEquals(List.of(), mismatches);
    }

    /** C5: a rejected program's diagnostic is pre21's, with julc.verifyBinderNamespace off and on. */
    @Test
    void rejectionsSayWhatPre21Said() {
        int pinned = 0;
        String before = System.getProperty("julc.verifyBinderNamespace");
        try {
            for (var fixture : Adr078Fixtures.fixtures()) {
                for (var variant : fixture.variants()) {
                    for (boolean verify : List.of(false, true)) {
                        String key = fixture.name() + " | " + variant.label() + " | diagnostic verify=" + verify;
                        if (!PRE21.containsKey(key)) continue;
                        System.setProperty("julc.verifyBinderNamespace", String.valueOf(verify));
                        assertEquals(PRE21.get(key), Adr078Fixtures.compile(variant, new Mode("pv11-safe", false)).rejection(), key);
                        pinned++;
                    }
                }
            }
        } finally {
            if (before == null) System.clearProperty("julc.verifyBinderNamespace");
            else System.setProperty("julc.verifyBinderNamespace", before);
        }
        assertTrue(pinned >= 4, "diagnostics pinned: " + pinned);
    }

    @Test
    void sameFixturesGiveTheBasesBytesInEveryMode() {
        var differences = new ArrayList<String>();
        for (var fixture : Adr078Fixtures.fixtures()) {
            if (!fixture.expectation().equals("SAME")) continue;
            var base = fixture.variants().getFirst();
            for (var mode : Adr078Fixtures.modes()) {
                var expected = NOW.get(key(fixture, base, mode));
                assertTrue(expected.compiled(), key(fixture, base, mode) + ": " + expected);
                for (var variant : fixture.variants()) {
                    var actual = NOW.get(key(fixture, variant, mode));
                    if (!expected.toString().equals(actual.toString())) differences.add(key(fixture, variant, mode) + ": " + actual + ", base " + expected);
                }
            }
        }
        assertEquals(List.of(), differences);
    }

    @Test
    void knownLimitsAndNegativeControlsDifferInSomeMode() {
        var report = new StringBuilder();
        for (var fixture : Adr078Fixtures.fixtures()) {
            if (!fixture.expectation().equals("DIFFERENT_IN_SOME_MODE")) continue;
            var base = fixture.variants().getFirst();
            var other = fixture.variants().get(1);
            var modes = new ArrayList<String>();
            for (var mode : Adr078Fixtures.modes()) {
                if (!NOW.get(key(fixture, base, mode)).toString().equals(NOW.get(key(fixture, other, mode)).toString())) modes.add(mode.toString());
            }
            report.append(fixture.name()).append(" differs in ").append(modes).append('\n');
            assertTrue(!modes.isEmpty(), fixture.name() + " no longer differs in any mode: update ADR-078's known limits");
        }
        System.out.print(report);
    }

    /**
     * C2: the assembly's roots decide which twin is bound outermost. A multi-purpose validator whose handlers are
     * declared against tag order binds the mint handler's twin first; a compileMethod target binds its own twin before
     * that of an unread helper declared above it. Neither follows declaration order or names.
     */
    @Test
    void theAssemblysRootsDecideTheEmittedOrder() {
        for (String name : List.of("assembly-handlers-against-tag-order", "assembly-unread-helper-before-target")) {
            var fixture = fixture(name);
            for (var variant : fixture.variants()) {
                String first = "q.L." + variant.label();
                for (var mode : Adr078Fixtures.modes()) {
                    String pir = NOW.get(key(fixture, variant, mode)).pir();
                    int outer = pir.indexOf("(let " + first + " =");
                    int inner = pir.indexOf("(let q.L.beta =");
                    assertTrue(outer >= 0 && inner >= 0, key(fixture, variant, mode) + ": both twins are bound");
                    assertTrue(outer < inner, key(fixture, variant, mode) + ": " + first + " is bound outside q.L.beta");
                }
            }
        }
    }

    /**
     * C11: a static initialiser's first use comes before the entry point's, through both assembly entry points
     * (compileMethod and a compiled validator): the twin the initialiser reads is bound outermost.
     */
    @Test
    void aStaticInitialiserFirstUseIsBoundOutermost() {
        var expected = Map.of(
                "static-initialiser-first-use | zeta in K", List.of("q.L.zeta", "q.L.alpha"),
                "static-initialiser-first-use | renamed: aaa in K, zzz in f", List.of("q.L.aaa", "q.L.zzz"),
                "validator-static-initialiser | zeta in K", List.of("q.L.zeta", "q.L.alpha"),
                "validator-static-initialiser | aaaa in K", List.of("q.L.aaaa", "q.L.alpha"));
        expected.forEach((variant, order) -> {
            for (var mode : Adr078Fixtures.modes()) {
                String pir = NOW.get(variant + " | " + mode).pir();
                int outer = pir.indexOf("(let " + order.get(0) + " =");
                int inner = pir.indexOf("(let " + order.get(1) + " =");
                assertTrue(outer >= 0 && inner >= 0, variant + " | " + mode + ": both twins are bound");
                assertTrue(outer < inner, variant + " | " + mode + ": " + order.get(0) + " is bound outside " + order.get(1));
            }
        });
    }

    /** A variant compiled through the test bridge with {@code observer}: the same compilation, observed (C26). */
    private static CompileResult observed(Variant variant, Mode mode, ValidatorAssembly.AssemblyObserver observer) {
        var options = mode.options(null);
        return variant.method() == null ? Adr078AssemblyBridge.validator(variant.target(), variant.libraries(), options, observer)
                : Adr078AssemblyBridge.method(variant.target(), variant.method(), variant.libraries(), options, observer);
    }

    /**
     * The let spines of method binders in a PIR term, by the purpose tag of the handler branch that holds them (C32): a
     * spine starts at a let of a qualified (dotted) binder that no such spine encloses, and runs down the lets' bodies.
     * A spine inside the value of the dispatcher's {@code #result__tag<n>} binding belongs to handler {@code n}; any
     * other spine to {@code -1}.
     */
    private static Map<Integer, List<PirTerm>> handlerSpines(PirTerm term) {
        var spines = new TreeMap<Integer, List<PirTerm>>();
        var pending = new ArrayDeque<Map.Entry<PirTerm, Integer>>();
        pending.push(Map.entry(term, -1));
        while (!pending.isEmpty()) {
            var popped = pending.pop();
            var next = popped.getKey();
            int tag = popped.getValue();
            if (startsSpine(next)) {
                spines.computeIfAbsent(tag, t -> new ArrayList<>()).add(next);
                continue;
            }
            var children = new ArrayList<PirTerm>();
            switch (next) {
                case PirTerm.Let let -> {
                    Matcher result = HANDLER_RESULT.matcher(let.name());
                    pending.push(Map.entry(let.value(), result.matches() ? Integer.parseInt(result.group(1)) : tag));
                    children.add(let.body());
                }
                case PirTerm.LetRec rec -> { rec.bindings().forEach(b -> children.add(b.value())); children.add(rec.body()); }
                case PirTerm.Lam lam -> children.add(lam.body());
                case PirTerm.App app -> { children.add(app.function()); children.add(app.argument()); }
                case PirTerm.IfThenElse ite -> { children.add(ite.cond()); children.add(ite.thenBranch()); children.add(ite.elseBranch()); }
                case PirTerm.Trace trace -> { children.add(trace.message()); children.add(trace.body()); }
                case PirTerm.DataMatch match -> { children.add(match.scrutinee()); match.branches().forEach(b -> children.add(b.body())); }
                case PirTerm.ListMatch match -> { children.add(match.scrutinee()); children.add(match.nilBranch()); children.add(match.consBranch()); }
                case PirTerm.PairMatch match -> { children.add(match.scrutinee()); children.add(match.body()); }
                case PirTerm.IntegerCase integerCase -> { children.add(integerCase.scrutinee()); children.addAll(integerCase.branches()); }
                case PirTerm.DataConstr constr -> children.addAll(constr.fields());
                default -> {
                }
            }
            children.forEach(child -> pending.push(Map.entry(child, tag)));
        }
        return spines;
    }

    private static final Pattern HANDLER_RESULT = Pattern.compile("#result__tag(\\d+)");

    /** The sorted method binders down a spine's let bodies. */
    private static List<String> spineBinders(PirTerm start) {
        var names = new ArrayList<String>();
        var at = start;
        while (at instanceof PirTerm.Let || at instanceof PirTerm.LetRec) {
            if (at instanceof PirTerm.Let let) {
                if (let.name().contains(".")) names.add(let.name());
                at = let.body();
            } else {
                var rec = (PirTerm.LetRec) at;
                for (var binding : rec.bindings()) if (binding.name().contains(".")) names.add(binding.name());
                at = rec.body();
            }
        }
        Collections.sort(names);
        return names;
    }

    /** Whether {@code inner} is {@code outer} or lies down the chain of {@code outer}'s let bodies. */
    private static boolean encloses(PirTerm outer, PirTerm inner) {
        var at = outer;
        while (true) {
            if (at == inner) return true;
            if (at instanceof PirTerm.Let let) at = let.body();
            else if (at instanceof PirTerm.LetRec rec) at = rec.body();
            else return false;
        }
    }

    private static boolean startsSpine(PirTerm term) {
        return term instanceof PirTerm.Let let && let.name().contains(".")
                || term instanceof PirTerm.LetRec rec && rec.bindings().stream().anyMatch(b -> b.name().contains("."));
    }

    /**
     * I3 (Codex M1a C17, C25): every method binding reaches the frontend's {@code methodBound} callback, recorded where
     * the callback receives it, once per binding and handler copy, unread ones and C22's helper and library copies
     * included. Per handler, the binders the callback received equal the binders of the let spine in the dispatcher
     * branch for that handler's purpose tag (C32); where the emitted PIR keeps the assembled terms, the received chain is
     * that branch's spine itself.
     */
    @Test
    void everyBindingReachesTheMethodBoundCallbackOfItsHandler() {
        int compared = 0;
        int compilationsMatchedByIdentity = 0;
        for (var fixture : Adr078Fixtures.fixtures()) {
            for (var variant : fixture.variants()) {
                for (var mode : Adr078Fixtures.modes()) {
                    var byHandler = new TreeMap<Integer, List<String>>();
                    var terms = new TreeMap<Integer, List<PirTerm>>();
                    CompileResult result;
                    try {
                        result = observed(variant, mode, new ValidatorAssembly.AssemblyObserver() {
                            @Override
                            public void bound(int handler, String binder, PirTerm binding) {
                                byHandler.computeIfAbsent(handler, h -> new ArrayList<>()).add(binder);
                                terms.computeIfAbsent(handler, h -> new ArrayList<>()).add(binding);
                            }
                        });
                    } catch (RuntimeException rejected) {
                        assertTrue(!NOW.get(key(fixture, variant, mode)).compiled(), key(fixture, variant, mode) + ": " + rejected);
                        continue;
                    }
                    if (result.hasErrors()) continue;
                    boolean multi = fixture.name().contains("multi-purpose") || fixture.name().contains("handlers-against-tag-order");
                    var emitted = handlerSpines(result.pirTerm());
                    String at = key(fixture, variant, mode);
                    assertEquals(multi ? List.of(0, 1) : List.of(-1), List.copyOf(emitted.keySet()), at + ": the emitted handlers");
                    boolean assembledTermsKept = emitted.values().stream().flatMap(List::stream)
                            .anyMatch(start -> terms.values().stream().anyMatch(c -> c.getLast() == start));
                    // C32: each tag's callbacks match the spine in the branch the dispatcher runs for that purpose tag.
                    byHandler.forEach((handler, names) -> {
                        var spine = emitted.get(handler);
                        assertTrue(spine != null && spine.size() == 1, at + ": handler " + handler + "'s spines " + spine);
                        var sorted = new ArrayList<>(names);
                        Collections.sort(sorted);
                        assertEquals(spineBinders(spine.getFirst()), sorted, at + ": handler " + handler);
                        // Each binding term the callback received encloses the one reported before it. Where the
                        // emitted PIR still holds the assembled terms (no pass rebuilt them), the outermost one is the
                        // spine of this tag's branch: a binding reported under another tag is told apart even where
                        // both branches bind the same names in the same order.
                        var chain = terms.get(handler);
                        for (int i = 1; i < chain.size(); i++) {
                            assertTrue(encloses(chain.get(i), chain.get(i - 1)), at + ": handler " + handler
                                    + "'s binding " + names.get(i) + " does not enclose " + names.get(i - 1));
                        }
                        if (assembledTermsKept) {
                            assertTrue(chain.getLast() == spine.getFirst(), at + ": handler " + handler
                                    + "'s bindings are not the spine of its branch");
                        }
                    });
                    // C41: the floor counts compilations whose every handler branch was matched by identity.
                    if (multi && assembledTermsKept) compilationsMatchedByIdentity++;
                    assertEquals(multi ? List.of(0, 1) : List.of(-1), List.copyOf(byHandler.keySet()), at + ": the handlers called back");
                    compared++;
                }
            }
        }
        assertTrue(compared > 100, "compared " + compared);
        System.out.println("I3: " + compared + " compilations compared; multi-handler compilations matched by identity: "
                + compilationsMatchedByIdentity);
        assertTrue(compilationsMatchedByIdentity >= 32, "multi-handler compilations matched by identity: " + compilationsMatchedByIdentity);
    }

    /**
     * C18: the equal-key choices of a compiled artifact's assembly: every one takes the lowest first-use rank, and the
     * twins fixture makes one.
     */
    @Test
    void aCompiledArtifactsEqualKeyChoicesTakeTheLowestRank() {
        int choices = 0;
        for (var fixture : Adr078Fixtures.fixtures()) {
            for (var variant : fixture.variants()) {
                var made = new ArrayList<String>();
                try {
                    observed(variant, new Mode("none", false), new ValidatorAssembly.AssemblyObserver() {
                        @Override
                        public void chose(String key, Map<String, Integer> contenders, String winner) {
                            int lowest = contenders.values().stream().mapToInt(Integer::intValue).min().orElseThrow();
                            assertEquals(lowest, contenders.get(winner), variant.label() + ": " + contenders);
                            made.add(winner);
                        }
                    });
                } catch (RuntimeException rejected) {
                    continue;
                }
                choices += made.size();
                if (fixture.name().equals("twins-reached")) assertTrue(!made.isEmpty(), variant.label() + ": no equal-key choice");
            }
        }
        assertTrue(choices > 10, "choices " + choices);
    }

    /**
     * Codex M1a C22 (ADR-078 r9's compatibility exception): a custom registry whose {@code Math.abs} builder applies a
     * materialised {@code q.M.b}, a closed {@code let q.M.b = λy.y in q.M.b}, inside library method {@code a}. The
     * spelling search of pre21 read that as a call of the program's own {@code q.M.b}, which calls {@code a}: a false
     * cycle, which pre21 keyed with group tokens and bound as z, a, b (FLAT 9aa526d2…, 48 B, recorded on the pre21
     * artifacts). With one lexical graph, a is bound outside b, which calls it: a, b, z. Java-only programs are not
     * affected (the corpus replay and the julc-examples gate are identical to pre21's).
     */
    @Test
    void aCustomRegistrysMaterialisedBindingIsNoCall() {
        var result = compileWithMaterialisedAbs("""
                    public static long a(long x) { return Math.abs(x); }
                    public static long b(long x) { return a(x) + 9; }
                    public static long z(long x) { return x + 9; }
                """, "M.b(x) + M.z(x)");
        String pir = result.pirFormatted();
        int a = pir.indexOf("(let q.M.a ="), b = pir.indexOf("(let q.M.b ="), z = pir.indexOf("(let q.M.z =");
        assertTrue(a >= 0 && a < b && b < z, "outermost first a, b, z: " + a + ", " + b + ", " + z);
        assertEquals("d0e16561f2dc5cc2323efa67c78ba7a380b84dcec13a808e074ee77459ed5b53 48 B",
                Adr078Fixtures.outcome(result).toString());
    }

    /**
     * Codex M1a C22: the diagnostic of a genuine cycle can change next to such a registry. Beside the false a/b cycle
     * above, c and d call each other. pre21's stalled group held all four, and it reported {@code Unbound variable:
     * q.M.a} (recorded on the pre21 artifacts). With one lexical graph, a and b are bound, and the stalled group is the
     * genuine cycle: the program is still rejected, naming {@code q.M.d}.
     */
    @Test
    void aGenuineCycleBesideACustomRegistrysBindingIsTheOneReported() {
        var rejection = assertThrows(CompilerException.class, () -> compileWithMaterialisedAbs("""
                    public static long a(long x) { return Math.abs(x); }
                    public static long b(long x) { return a(x) + 1; }
                    public static long c(long x) { return d(x) + 1; }
                    public static long d(long x) { return c(x) - 1; }
                """, "M.b(x)"));
        assertEquals("Unbound variable: q.M.d", rejection.getMessage());
    }

    /**
     * Compiles {@code f(x) = body} against library {@code q.M} with the given methods, through a registry whose
     * {@code Math.abs} builder applies a materialised {@code q.M.b = λy.y}, at {@code NONE}.
     */
    private static CompileResult compileWithMaterialisedAbs(String methods, String body) {
        String provided = "package q;\nimport org.julclang.stdlib.annotation.OnchainLibrary;\n@OnchainLibrary\npublic class M {\n"
                + "    public static long b(long y) { return y; }\n}\n";
        var materialised = new JavaLibraryProvider(List.of(provided), null, new CompilerOptions()).materialize("q.M.b");
        var registry = new StdlibRegistry();
        registry.register("Math", "abs", args -> new PirTerm.App(materialised, args.getFirst()));
        String library = "package q;\nimport org.julclang.stdlib.annotation.OnchainLibrary;\n@OnchainLibrary\npublic class M {\n"
                + methods + "}\n";
        String target = "package v;\nimport q.M;\nclass T {\n    static long f(long x) { return " + body + "; }\n}\n";
        var options = new CompilerOptions().setTarget(CompilerTarget.PLUTUS_V3_PV11).setOptimizationLevel(OptimizationLevel.NONE);
        // The materialised term's binders were built by the provider, not for this program's source: the test JVM's
        // I13 binder check (julc.verifyBinderNamespace) does not apply to such a registry, and production never runs it.
        String before = System.getProperty("julc.verifyBinderNamespace");
        System.setProperty("julc.verifyBinderNamespace", "false");
        try {
            return new JulcCompiler(registry, options).compileMethod(target, "f", List.of(library));
        } finally {
            if (before == null) System.clearProperty("julc.verifyBinderNamespace");
            else System.setProperty("julc.verifyBinderNamespace", before);
        }
    }

    /** C14: the trace encoding tells two traces from one trace that contains a quote and a comma. */
    @Test
    void theTraceEncodingIsUnambiguous() {
        assertTrue(!Adr078Fixtures.traces(List.of("a", "b")).equals(Adr078Fixtures.traces(List.of("a\", \"b"))),
                Adr078Fixtures.traces(List.of("a", "b")) + " / " + Adr078Fixtures.traces(List.of("a\", \"b")));
        assertEquals("[\"a\",\"b\"]", Adr078Fixtures.traces(List.of("a", "b")));
        assertEquals("[\"a\\\", \\\"b\"]", Adr078Fixtures.traces(List.of("a\", \"b")));
    }

    /** I3: the multiset of method binders, one per binding and assembly copy, is pre21's (C22's helper copies too). */
    @Test
    void everyVariantBindsPre21sMethodBinders() {
        var differences = new ArrayList<String>();
        int compared = 0;
        for (var fixture : Adr078Fixtures.fixtures()) {
            for (var variant : fixture.variants()) {
                for (var mode : Adr078Fixtures.modes()) {
                    String key = key(fixture, variant, mode) + " | binders";
                    var outcome = NOW.get(key(fixture, variant, mode));
                    if (!outcome.compiled()) continue;
                    compared++;
                    if (!PRE21.get(key).equals(outcome.binders())) differences.add(key + ": pre21 " + PRE21.get(key) + ", now " + outcome.binders());
                }
            }
        }
        assertEquals(List.of(), differences);
        String rootAsLibrary = NOW.get("C22-root-as-own-library | root | pv11-safe").binders();
        assertTrue(rootAsLibrary.indexOf("q.H.g") != rootAsLibrary.lastIndexOf("q.H.g"),
                "C22 binds the helper and the library copy of q.H.g: " + rootAsLibrary);
        assertTrue(compared > 100);
    }

    /** I4: every source-map build has pre21's complete source map. */
    @Test
    void sourceMapsArePre21s() {
        var differences = new ArrayList<String>();
        int compared = 0;
        for (var fixture : Adr078Fixtures.fixtures()) {
            for (var variant : fixture.variants()) {
                for (var mode : Adr078Fixtures.modes()) {
                    if (!mode.sourceMaps() || !NOW.get(key(fixture, variant, mode)).compiled()) continue;
                    String key = key(fixture, variant, mode) + " | source map";
                    compared++;
                    if (!PRE21.get(key).equals(NOW.get(key(fixture, variant, mode)).fullSourceMap())) differences.add(key);
                }
            }
        }
        assertEquals(List.of(), differences);
        assertTrue(compared > 50, "source maps compared: " + compared);
    }

    /** I4 through the rename: a renamed variant's map is its base's pre21 map, each location carried by the rename. */
    @Test
    void renamedSourceMapsAreTheBasesThroughTheRenameMapping() {
        int carried = 0, moved = 0;
        for (var fixture : Adr078Fixtures.fixtures()) {
            if (!fixture.expectation().equals("SAME")) continue;
            var base = fixture.variants().getFirst();
            for (var variant : fixture.variants().subList(1, fixture.variants().size())) {
                for (var mode : Adr078Fixtures.modes()) {
                    if (!mode.sourceMaps()) continue;
                    String expected = PRE21.get(key(fixture, base, mode) + " | source map");
                    String through = carry(expected, base.target(), variant.target());
                    if (!through.equals(expected)) moved++;
                    assertSameMap(through, NOW.get(key(fixture, variant, mode)).fullSourceMap(), key(fixture, variant, mode));
                    carried++;
                }
            }
        }
        assertTrue(carried > 20 && moved > 0, "carried " + carried + ", moved by the rename " + moved);
        // A negative control: without the mapping, a renamed variant's map is not its base's.
        var fixture = fixture("twins-reached");
        var mode = new Mode("none", true);
        String base = PRE21.get(key(fixture, fixture.variants().getFirst(), mode) + " | source map");
        String renamed = NOW.get(key(fixture, fixture.variants().get(1), mode)).fullSourceMap();
        assertTrue(!base.equals(renamed), "the renamed variant's map differs from its base's without the mapping");
    }

    /**
     * Two full maps agree: the same indices, files, lines and columns, and the same fragments. A fragment the compiler
     * truncated ({@code ...}) is compared on the prefix both have, since a rename changes where the truncation falls.
     */
    private static void assertSameMap(String expected, String actual, String message) {
        String[] want = expected.split("\t"), got = actual.split("\t");
        assertEquals(want.length, got.length, message + ": entries");
        for (int i = 0; i < want.length; i++) {
            Matcher w = ENTRY.matcher(want[i]), g = ENTRY.matcher(got[i]);
            assertTrue(w.matches() && g.matches(), message + ": " + want[i] + " / " + got[i]);
            for (int part = 1; part <= 4; part++) assertEquals(w.group(part), g.group(part), message + ": " + want[i] + " / " + got[i]);
            String a = w.group(5), b = g.group(5);
            if (a.endsWith("...") || b.endsWith("...")) {
                a = a.endsWith("...") ? a.substring(0, a.length() - 3) : a;
                b = b.endsWith("...") ? b.substring(0, b.length() - 3) : b;
                int common = Math.min(a.length(), b.length());
                assertEquals(a.substring(0, common), b.substring(0, common), message + ": " + want[i] + " / " + got[i]);
            } else {
                assertEquals(a, b, message + ": " + want[i] + " / " + got[i]);
            }
        }
    }

    /** {@code map} (a full source map of {@code base}) with every location carried to {@code renamed}, token by token. */
    private static String carry(String map, String base, String renamed) {
        String[] baseLines = base.split("\n", -1), renamedLines = renamed.split("\n", -1);
        var entries = new ArrayList<String>();
        for (String entry : map.split("\t")) {
            if (entry.isEmpty()) continue;
            Matcher m = ENTRY.matcher(entry);
            assertTrue(m.matches(), entry);
            int line = Integer.parseInt(m.group(3)), column = Integer.parseInt(m.group(4));
            var baseTokens = tokens(baseLines[line - 1]);
            var renamedTokens = tokens(renamedLines[line - 1]);
            assertEquals(baseTokens.size(), renamedTokens.size(), "a rename keeps the tokens of line " + line);
            int start = 0, renamedStart = 0, mapped = column;
            for (int i = 0; i < baseTokens.size(); i++) {
                if (column - 1 < start + baseTokens.get(i).length()) {
                    mapped = renamedStart + Math.min(column - 1 - start, renamedTokens.get(i).length() - 1) + 1;
                    break;
                }
                start += baseTokens.get(i).length();
                renamedStart += renamedTokens.get(i).length();
            }
            // The fragment's identifiers are renamed all at once, token by token, so that a swap stays a swap.
            var renames = new HashMap<String, String>();
            for (int i = 0; i < baseTokens.size(); i++) {
                if (!baseTokens.get(i).equals(renamedTokens.get(i))) renames.put(baseTokens.get(i), renamedTokens.get(i));
            }
            var fragment = new StringBuilder();
            for (String token : tokens(m.group(5))) fragment.append(renames.getOrDefault(token, token));
            entries.add(m.group(1) + "=" + m.group(2) + ":" + line + ":" + mapped + ":" + fragment);
        }
        return String.join("\t", entries);
    }

    private static List<String> tokens(String line) {
        var tokens = new ArrayList<String>();
        Matcher m = Pattern.compile("\\w+|\\W").matcher(line);
        while (m.find()) tokens.add(m.group());
        return tokens;
    }

    /** C12: both spellings give the pre21 alpha program, which succeeds under the limit where pre21's zeta exhausted. */
    @Test
    void c12BothSpellingsGiveTheAlphaProgramAndSucceedUnderTheLimit() {
        var fixture = fixture("C12");
        var mode = new Mode("pv11-safe", false);
        assertTrue(PRE21.get(key(fixture, fixture.variants().get(0), mode) + " | eval 1 c12-limit").startsWith("SUCCESS"), "pre21 alpha");
        assertTrue(PRE21.get(key(fixture, fixture.variants().get(1), mode) + " | eval 1 c12-limit").startsWith("EXHAUSTED"), "pre21 zeta");
        for (var variant : fixture.variants()) {
            var outcome = NOW.get(key(fixture, variant, mode));
            assertEquals("c8489246affdc535e5b559c5b75c027ef31b5285aa003384721ca78b25c95380 1010 B", outcome.toString(), variant.label());
            var bounded = Adr078Fixtures.evaluate(outcome.program(), List.of(PlutusData.integer(5)), Adr078Fixtures.C12_LIMIT);
            assertTrue(bounded.outcome().startsWith("SUCCESS"), variant.label() + ": " + bounded.outcome());
        }
    }

    /** C20: the pool order still decides whether the program compiles (L-C4), exactly as on pre21. */
    @Test
    void c20PoolOrdersArePinned() {
        var fixture = fixture("C20");
        for (var mode : Adr078Fixtures.modes()) {
            assertTrue(NOW.get(key(fixture, fixture.variants().get(0), mode)).compiled(), "[B, A] " + mode);
            assertTrue(NOW.get(key(fixture, fixture.variants().get(1), mode)).toString()
                    .startsWith("REJECTED CompilerException: Undefined variable: B"), "[A, B] " + mode);
        }
    }

    /**
     * I6a: what pre21 computed, failed with or traced is unchanged, compared structurally; a returned function is
     * applied to the same arguments and compared; an evaluation that exhausts the transaction limit on either side is
     * decided at the higher limit ({@link #afterAnExhaustion}). Budgets are reported (I6b), those of the completed
     * evaluations after an exhaustion included (C38).
     */
    @Test
    void evaluationsMatchPre21() {
        var mismatches = new ArrayList<String>();
        var budgets = new StringBuilder();
        int compared = 0, inconclusive = 0, applied = 0, traced = 0;
        for (var fixture : Adr078Fixtures.fixtures()) {
            for (var variant : fixture.variants()) {
                if (variant.method() == null) continue;
                for (var mode : Adr078Fixtures.modes()) {
                    var outcome = NOW.get(key(fixture, variant, mode));
                    if (!outcome.compiled()) continue;
                    String prefix = key(fixture, variant, mode) + " | ";
                    Map<String, Evaluation> now = Adr078Fixtures.evaluations(fixture, outcome);
                    // Codex M1a C24: the evaluations made are exactly the evaluations pre21 recorded, both ways.
                    var recorded = new TreeSet<String>();
                    for (String recordedKey : PRE21.keySet()) {
                        if (!recordedKey.startsWith(prefix)) continue;
                        String suffix = recordedKey.substring(prefix.length());
                        if (suffix.startsWith("eval ") || suffix.startsWith("applied")) recorded.add(suffix);
                    }
                    assertEquals(recorded, new TreeSet<>(now.keySet()), prefix + "evaluation ids");
                    for (var entry : now.entrySet()) {
                        String suffix = entry.getKey();
                        if (suffix.endsWith(" high") || suffix.endsWith("c12-limit")) continue;
                        // A replay at the higher limit is recorded for every evaluation; it is compared only where the
                        // transaction limit exhausted on one side.
                        String pre21 = PRE21.get(prefix + suffix);
                        assertTrue(pre21 != null, "no pre21 record for " + prefix + suffix);
                        var evaluation = entry.getValue();
                        if (pre21.startsWith("EXHAUSTED") || evaluation.exhausted()) {
                            var high = now.get(suffix + " high");
                            String verdict = afterAnExhaustion(pre21, PRE21.get(prefix + suffix + " high"), evaluation.outcome(),
                                    high == null ? null : high.outcome());
                            if (verdict.equals(INCONCLUSIVE)) inconclusive++;
                            else if (!verdict.equals(COMPARED)) mismatches.add(prefix + suffix + ": " + verdict);
                            else {
                                compared++;
                                // C38: the completed evaluations' budgets are reported here too (I6b).
                                var completed = evaluation.exhausted() ? high : evaluation;
                                String budget = completed.budget().cpuSteps() + " cpu, " + completed.budget().memoryUnits() + " mem";
                                String pre21Budget = PRE21.get(prefix + "budget " + suffix + (pre21.startsWith("EXHAUSTED") ? " high" : ""));
                                if (!budget.equals(pre21Budget)) budgets.append(prefix).append(suffix).append(": ").append(pre21Budget).append(" -> ").append(budget).append('\n');
                            }
                            continue;
                        }
                        compared++;
                        if (suffix.equals("applied")) applied++;
                        if (evaluation.outcome().contains("traces=[\"")) traced++;
                        if (!pre21.equals(evaluation.outcome())) mismatches.add(prefix + suffix + ": pre21 " + pre21 + ", now " + evaluation.outcome());
                        String budget = evaluation.budget().cpuSteps() + " cpu, " + evaluation.budget().memoryUnits() + " mem";
                        String pre21Budget = PRE21.get(prefix + "budget " + suffix);
                        if (!budget.equals(pre21Budget)) budgets.append(prefix).append(suffix).append(": ").append(pre21Budget).append(" -> ").append(budget).append('\n');
                    }
                }
            }
        }
        System.out.println("I6a: " + compared + " evaluations compared (" + applied + " returned functions applied, " + traced
                + " with traces); demonstrated mismatches: " + mismatches.size()
                + "; inconclusive (an exhaustion that persists at the higher limit on either side): " + inconclusive);
        System.out.print(budgets.isEmpty() ? "I6b: every budget as on pre21\n" : "I6b: budgets that changed\n" + budgets);
        assertEquals(List.of(), mismatches);
        assertTrue(compared > 0 && applied > 0 && traced > 0, "compared " + compared + ", applied " + applied + ", traced " + traced);
    }

    static final String COMPARED = "compared", INCONCLUSIVE = "inconclusive";

    /**
     * ADR-078's I6a oracle for an evaluation that exhausted the transaction limit on either side, or both (Codex M1a
     * C30, C36). Each side's evidence is its transaction-limit outcome, or its higher-limit replay where the transaction
     * limit exhausted; a transaction-limit exhaustion's partial traces are superseded by the replay. Where both
     * terminate, their results and complete traces must be equal. Where an exhaustion persists, the case is
     * inconclusive only if the traces it observed are a prefix of the other side's complete traces (or, both
     * exhausted, one is a prefix of the other); otherwise the traces already disagree, a mismatch. Returns
     * {@link #COMPARED}, {@link #INCONCLUSIVE}, or the mismatch.
     */
    static String afterAnExhaustion(String pre21, String pre21High, String now, String nowHigh) {
        String a = pre21.startsWith("EXHAUSTED") ? pre21High : pre21;
        String b = now.startsWith("EXHAUSTED") ? nowHigh : now;
        if (a == null || b == null) return "an exhaustion with no higher-limit replay";
        boolean ea = a.startsWith("EXHAUSTED"), eb = b.startsWith("EXHAUSTED");
        if (!ea && !eb) return a.equals(b) ? COMPARED : "pre21 " + a + ", now " + b;
        String ta = tracesOf(a), tb = tracesOf(b);
        boolean consistent = ea && eb ? isPrefix(ta, tb) || isPrefix(tb, ta) : ea ? isPrefix(ta, tb) : isPrefix(tb, ta);
        return consistent ? INCONCLUSIVE : "the traces disagree before the exhaustion: pre21 " + a + ", now " + b;
    }

    /** The JSON trace array that ends an evaluation's outcome line (Adr078Fixtures.describe). */
    private static String tracesOf(String outcome) {
        return outcome.substring(outcome.indexOf(" traces=[") + " traces=".length());
    }

    /**
     * Whether the trace list {@code a} is a prefix of {@code b}, both as JSON arrays of strings: each element is a
     * complete JSON string, so {@code a} without its {@code ]} must start {@code b}, at an element boundary.
     */
    static boolean isPrefix(String a, String b) {
        if (a.equals("[]")) return true;
        String open = a.substring(0, a.length() - 1);
        return b.startsWith(open) && b.length() > open.length() && (b.charAt(open.length()) == ',' || b.charAt(open.length()) == ']');
    }

    /** C30's and C36's controls: the higher limit decides an exhaustion, and traces that disagree are a mismatch. */
    @Test
    void theHigherLimitDecidesAnExhaustion() {
        String success = "SUCCESS term=010100480781 traces=[]", failure = "FAILURE reason=\"error\" traces=[]";
        String exhausted = "EXHAUSTED traces=[]";
        // C30: both exhaust the transaction limit; the replays decide.
        assertEquals("pre21 " + success + ", now " + failure, afterAnExhaustion(exhausted, success, exhausted, failure));
        assertEquals(COMPARED, afterAnExhaustion(exhausted, success, exhausted, success));
        assertEquals(INCONCLUSIVE, afterAnExhaustion(exhausted, exhausted, exhausted, exhausted));
        assertEquals(INCONCLUSIVE, afterAnExhaustion(exhausted, success, exhausted, exhausted));
        assertEquals(INCONCLUSIVE, afterAnExhaustion(success, null, exhausted, exhausted));
        assertEquals("an exhaustion with no higher-limit replay", afterAnExhaustion(exhausted, success, exhausted, null));
        // C36: pre21's replay succeeds tracing ["A"], this build's still exhausts having traced ["B"]: a mismatch.
        String tracedA = "SUCCESS term=010100480781 traces=[\"A\"]", exhaustedB = "EXHAUSTED traces=[\"B\"]";
        assertTrue(afterAnExhaustion(exhausted, tracedA, exhausted, exhaustedB).startsWith("the traces disagree"));
        assertTrue(afterAnExhaustion(exhausted, exhaustedB, exhausted, tracedA).startsWith("the traces disagree"));
        // An exhaustion whose traces are a prefix of the other side's complete traces stays inconclusive.
        assertEquals(INCONCLUSIVE, afterAnExhaustion(exhausted, "EXHAUSTED traces=[\"A\"]", exhausted,
                "SUCCESS term=010100480781 traces=[\"A\",\"B\"]"));
        assertEquals(INCONCLUSIVE, afterAnExhaustion(exhausted, "EXHAUSTED traces=[\"A\",\"B\"]", exhausted,
                "EXHAUSTED traces=[\"A\"]"));
        assertTrue(afterAnExhaustion(exhausted, "EXHAUSTED traces=[\"AB\"]", exhausted, "EXHAUSTED traces=[\"A\"]")
                .startsWith("the traces disagree"));
        // C36, the reverse: transaction-limit prefixes [] and ["start"] differ, the replays agree: compared, no mismatch.
        String done = "SUCCESS term=010100480781 traces=[\"start\",\"done\"]";
        assertEquals(COMPARED, afterAnExhaustion(exhausted, done, "EXHAUSTED traces=[\"start\"]", done));
    }

    /** A report of the fixtures whose bytes M1a changed from pre21: only programs with equal-key methods may change. */
    @Test
    void onlyFixturesWithEqualKeyMethodsChangedFromPre21() {
        var changed = new TreeMap<String, String>();
        for (var fixture : Adr078Fixtures.fixtures()) {
            for (var variant : fixture.variants()) {
                for (var mode : Adr078Fixtures.modes()) {
                    String key = key(fixture, variant, mode);
                    // A rejection's text depends on the test JVM's I13 check; rejectionsSayWhatPre21Said pins it.
                    boolean bothRejected = PRE21.get(key).startsWith("REJECTED") && !NOW.get(key).compiled();
                    if (!bothRejected && !PRE21.get(key).equals(NOW.get(key).toString())) changed.put(key, PRE21.get(key) + " -> " + NOW.get(key));
                }
            }
        }
        changed.forEach((key, value) -> System.out.println("changed from pre21: " + key + ": " + value));
        for (String key : changed.keySet()) {
            // Only these fixtures bind library methods with equal content keys.
            assertTrue(key.startsWith("twins-reached") || key.startsWith("C11") || key.startsWith("C12") || key.startsWith("C19-")
                    || key.startsWith("C26") || key.startsWith("static-initialiser") || key.startsWith("validator-")
                    || key.startsWith("traces-") || key.startsWith("assembly-")
                    || key.startsWith("negative-constant | twins"), "unexpected change: " + key);
        }
    }
}
