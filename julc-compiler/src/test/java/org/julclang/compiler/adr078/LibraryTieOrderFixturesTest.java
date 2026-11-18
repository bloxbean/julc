package org.julclang.compiler.adr078;

import org.julclang.compiler.adr078.Adr078Fixtures.Evaluation;
import org.julclang.compiler.codegen.ValidatorAssembly;
import org.julclang.compiler.adr078.Adr078Fixtures.Fixture;
import org.julclang.compiler.adr078.Adr078Fixtures.Mode;
import org.julclang.compiler.adr078.Adr078Fixtures.Outcome;
import org.julclang.compiler.adr078.Adr078Fixtures.Variant;
import org.julclang.core.PlutusData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

    /**
     * I3 (Codex M1a C17): every method binding is reported to the frontend's {@code methodBound} callback, once per
     * binding and handler copy, unread ones and C22's helper and library copies included: the bindings the callback is
     * told equal the method binders in the emitted PIR.
     */
    @Test
    void everyBindingReachesTheMethodBoundCallback() {
        int compared = 0;
        for (var fixture : Adr078Fixtures.fixtures()) {
            for (var variant : fixture.variants()) {
                for (var mode : Adr078Fixtures.modes()) {
                    var bound = new ArrayList<String>();
                    var handlers = new TreeSet<Integer>();
                    var outcome = ValidatorAssembly.observing((handler, binder) -> {
                        bound.add(binder);
                        handlers.add(handler);
                    }, () -> Adr078Fixtures.compile(variant, mode));
                    if (!outcome.compiled()) continue;
                    Collections.sort(bound);
                    assertEquals(outcome.binders(), bound.toString(), key(fixture, variant, mode));
                    if (fixture.name().contains("multi-purpose") || fixture.name().contains("handlers-against-tag-order")) {
                        assertEquals(List.of(0, 1), List.copyOf(handlers), key(fixture, variant, mode) + ": both handlers bind");
                    }
                    compared++;
                }
            }
        }
        assertTrue(compared > 100, "compared " + compared);
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
                ValidatorAssembly.observing(new ValidatorAssembly.AssemblyObserver() {
                    @Override
                    public void bound(int handler, String binder) {
                    }

                    @Override
                    public void chose(String key, Map<String, Integer> contenders, String winner) {
                        int lowest = contenders.values().stream().mapToInt(Integer::intValue).min().orElseThrow();
                        assertEquals(lowest, contenders.get(winner), variant.label() + ": " + contenders);
                        made.add(winner);
                    }
                }, () -> Adr078Fixtures.compile(variant, new Mode("none", false)));
                choices += made.size();
                if (fixture.name().equals("twins-reached")) assertTrue(!made.isEmpty(), variant.label() + ": no equal-key choice");
            }
        }
        assertTrue(choices > 10, "choices " + choices);
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
     * applied to the same arguments and compared; a one-sided exhaustion is replayed at the higher limit, where pre21
     * terminating and this build not is a failure and both exhausting is inconclusive. Budgets are reported (I6b).
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
                    for (var entry : now.entrySet()) {
                        String suffix = entry.getKey();
                        if (suffix.endsWith(" high") || suffix.endsWith("c12-limit")) continue;
                        // A replay at the higher limit is recorded for every evaluation; it is compared only where the
                        // transaction limit exhausted on one side.
                        String pre21 = PRE21.get(prefix + suffix);
                        assertTrue(pre21 != null, "no pre21 record for " + prefix + suffix);
                        var evaluation = entry.getValue();
                        if (pre21.startsWith("EXHAUSTED") != evaluation.exhausted()) {
                            String pre21High = PRE21.get(prefix + suffix + " high");
                            var high = now.get(suffix + " high");
                            if (pre21High == null || high == null) {
                                mismatches.add(prefix + suffix + ": one-sided exhaustion with no replay");
                            } else if (!pre21High.startsWith("EXHAUSTED") && high.exhausted()) {
                                mismatches.add(prefix + suffix + ": pre21 terminated at the higher limit, this build did not");
                            } else if (pre21High.startsWith("EXHAUSTED") && high.exhausted()) {
                                inconclusive++;
                            } else if (!pre21High.equals(high.outcome())) {
                                mismatches.add(prefix + suffix + " high: pre21 " + pre21High + ", now " + high.outcome());
                            } else {
                                compared++;
                            }
                            continue;
                        }
                        if (evaluation.exhausted()) {
                            inconclusive++;
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
                + " with traces); demonstrated mismatches: " + mismatches.size() + "; inconclusive (exhausted at the higher limit"
                + " on both sides): " + inconclusive);
        System.out.print(budgets.isEmpty() ? "I6b: every budget as on pre21\n" : "I6b: budgets that changed\n" + budgets);
        assertEquals(List.of(), mismatches);
        assertTrue(compared > 0 && applied > 0 && traced > 0, "compared " + compared + ", applied " + applied + ", traced " + traced);
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
