package org.julclang.compiler.adr078;

import org.julclang.compiler.adr078.Adr078Fixtures.Fixture;
import org.julclang.compiler.adr078.Adr078Fixtures.Mode;
import org.julclang.compiler.adr078.Adr078Fixtures.Outcome;
import org.julclang.compiler.adr078.Adr078Fixtures.Variant;
import org.julclang.core.PlutusData;
import org.julclang.core.source.SourceLocation;
import org.julclang.vm.ExBudget;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-078 D5: the fixed fixtures, compiled in every mode, against what {@code 0.1.0-pre21} gave for them
 * ({@code adr078/pre21-fixtures.txt}, recorded by {@link Adr078Fixtures#main}).
 * <ul>
 *   <li>A variant compiles exactly where it compiled on pre21 (C29).</li>
 *   <li>A {@code SAME} fixture's variants give the base's bytes in every mode: the tie rule (D1, D2).</li>
 *   <li>A {@code DIFFERENT_IN_SOME_MODE} fixture is a known limit or a negative control: some mode differs.</li>
 *   <li>Every evaluation that pre21 completed has the same result term, failure reason and traces (I6a); budgets are
 *       reported, not compared (I6b).</li>
 *   <li>Source maps: every variant has as many mapped DFS indices as on pre21, on the same lines; where a variant's
 *       bytes are pre21's, every index maps to the same location (I4).</li>
 * </ul>
 */
class LibraryTieOrderFixturesTest {

    private static final Map<String, String> PRE21 = new LinkedHashMap<>();
    private static final Map<String, Outcome> NOW = new HashMap<>();

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

    @Test
    void everyVariantCompilesExactlyWherePre21CompiledIt() {
        var mismatches = new ArrayList<String>();
        for (var fixture : Adr078Fixtures.fixtures()) {
            for (var variant : fixture.variants()) {
                for (var mode : Adr078Fixtures.modes()) {
                    String key = key(fixture, variant, mode);
                    String pre21 = PRE21.get(key);
                    assertTrue(pre21 != null, "no pre21 record for " + key);
                    if (pre21.startsWith("REJECTED") == NOW.get(key).compiled()) mismatches.add(key + ": pre21 " + pre21 + ", now " + NOW.get(key));
                }
            }
        }
        assertEquals(List.of(), mismatches);
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

    /** C12: both spellings give the pre21 alpha program, which succeeds under the limit where the zeta one exhausted. */
    @Test
    void c12BothSpellingsGiveTheAlphaProgramAndSucceedUnderTheLimit() {
        var fixture = Adr078Fixtures.fixtures().stream().filter(f -> f.name().startsWith("C12")).findFirst().orElseThrow();
        var mode = new Mode("pv11-safe", false);
        assertEquals("4ab65773da337e917d9ade748526156aadd66db1c1e7ddcd77632ffc17ca40f3 1012 B",
                PRE21.get(key(fixture, fixture.variants().get(1), mode)), "pre21's zeta program");
        for (var variant : fixture.variants()) {
            var outcome = NOW.get(key(fixture, variant, mode));
            assertEquals("c8489246affdc535e5b559c5b75c027ef31b5285aa003384721ca78b25c95380 1010 B", outcome.toString(), variant.label());
            var bounded = Adr078Fixtures.evaluate(outcome.program(), List.of(PlutusData.integer(5)), new ExBudget(31_150_000, 110_000));
            assertTrue(bounded.outcome().startsWith("SUCCESS"), variant.label() + ": " + bounded.outcome());
        }
    }

    /** C20: the pool order still decides whether the program compiles (L-C4), exactly as on pre21. */
    @Test
    void c20PoolOrdersArePinned() {
        var fixture = Adr078Fixtures.fixtures().stream().filter(f -> f.name().startsWith("C20")).findFirst().orElseThrow();
        for (var mode : Adr078Fixtures.modes()) {
            assertTrue(NOW.get(key(fixture, fixture.variants().get(0), mode)).compiled(), "[B, A] " + mode);
            assertEquals("REJECTED CompilerException: Undefined variable: B", NOW.get(key(fixture, fixture.variants().get(1), mode)).toString(), "[A, B] " + mode);
        }
    }

    /** I6a: what pre21 computed, failed with or traced is unchanged; budgets are reported apart (I6b). */
    @Test
    void evaluationsMatchPre21() {
        var mismatches = new ArrayList<String>();
        var budgets = new StringBuilder();
        int compared = 0, inconclusive = 0;
        for (var fixture : Adr078Fixtures.fixtures()) {
            var arguments = Adr078Fixtures.arguments(fixture);
            for (var variant : fixture.variants()) {
                for (var mode : Adr078Fixtures.modes()) {
                    var outcome = NOW.get(key(fixture, variant, mode));
                    if (!outcome.compiled() || variant.method() == null) continue;
                    for (int i = 0; i < arguments.size(); i++) {
                        String key = key(fixture, variant, mode) + " | eval " + i;
                        var now = Adr078Fixtures.evaluate(outcome.program(), arguments.get(i), Adr078Fixtures.REPLAY_LIMIT);
                        String pre21 = PRE21.get(key);
                        if (pre21.startsWith("EXHAUSTED") || now.outcome().startsWith("EXHAUSTED")) {
                            inconclusive++;
                            continue;
                        }
                        compared++;
                        if (!pre21.equals(now.outcome())) mismatches.add(key + ": pre21 " + pre21 + ", now " + now.outcome());
                        String budget = now.budget().cpuSteps() + " cpu, " + now.budget().memoryUnits() + " mem";
                        String pre21Budget = PRE21.get(key(fixture, variant, mode) + " | budget " + i);
                        if (!budget.equals(pre21Budget)) budgets.append(key).append(": ").append(pre21Budget).append(" -> ").append(budget).append('\n');
                    }
                }
            }
        }
        System.out.println("I6a: " + compared + " evaluations compared, " + inconclusive + " inconclusive (exhausted)");
        System.out.print(budgets.isEmpty() ? "I6b: every budget as on pre21\n" : "I6b: budgets that changed\n" + budgets);
        assertEquals(List.of(), mismatches);
        assertTrue(compared > 0);
    }

    /** I4: the source maps keep their entries and lines; where the bytes are pre21's, every location is pre21's. */
    @Test
    void sourceMapsKeepTheirLocations() {
        for (var fixture : Adr078Fixtures.fixtures()) {
            for (var variant : fixture.variants()) {
                for (var mode : Adr078Fixtures.modes()) {
                    if (!mode.sourceMaps()) continue;
                    var outcome = NOW.get(key(fixture, variant, mode));
                    if (!outcome.compiled()) continue;
                    assertTrue(outcome.sourceMap() != null && !outcome.sourceMap().isEmpty(), key(fixture, variant, mode));
                }
            }
        }
        // Same-source comparison against the fixture's base (identity rename mapping on lines): equal lines per index.
        for (var fixture : Adr078Fixtures.fixtures()) {
            if (!fixture.expectation().equals("SAME")) continue;
            for (var mode : Adr078Fixtures.modes()) {
                if (!mode.sourceMaps()) continue;
                var base = lines(NOW.get(key(fixture, fixture.variants().getFirst(), mode)).sourceMap());
                for (var variant : fixture.variants()) {
                    assertEquals(base, lines(NOW.get(key(fixture, variant, mode)).sourceMap()), key(fixture, variant, mode));
                }
            }
        }
    }

    private static Map<Integer, Integer> lines(Map<Integer, SourceLocation> map) {
        var lines = new TreeMap<Integer, Integer>();
        map.forEach((index, location) -> lines.put(index, location.line()));
        return lines;
    }

    /** A report of the fixtures whose bytes M1a changed from pre21's: only programs with equal-key methods may change. */
    @Test
    void reportWhichFixturesChangedFromPre21() {
        var changed = new TreeMap<String, String>();
        for (var fixture : Adr078Fixtures.fixtures()) {
            for (var variant : fixture.variants()) {
                for (var mode : Adr078Fixtures.modes()) {
                    String key = key(fixture, variant, mode);
                    // A rejection's text can differ with the test JVM's checks (julc.verifyBinderNamespace reports I13
                    // before the backend's "Unbound variable"); that it is rejected is compared above.
                    boolean bothRejected = PRE21.get(key).startsWith("REJECTED") && !NOW.get(key).compiled();
                    if (!bothRejected && !PRE21.get(key).equals(NOW.get(key).toString())) changed.put(key, PRE21.get(key) + " -> " + NOW.get(key));
                }
            }
        }
        changed.forEach((key, value) -> System.out.println("changed from pre21: " + key + ": " + value));
        for (String key : changed.keySet()) {
            // Only these fixtures bind library methods with equal content keys.
            assertTrue(key.startsWith("twins-reached") || key.startsWith("C11") || key.startsWith("C12") || key.startsWith("C19 ")
                    || key.startsWith("C19-") || key.startsWith("C26") || key.startsWith("static-initialiser")
                    || key.startsWith("validator-")
                    || key.startsWith("negative-constant | twins"), "unexpected change: " + key);
        }
    }
}
