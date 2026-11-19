package org.julclang.tools.adr078;

import org.julclang.tools.adr078.Adr078DebugFixtures.Fixture;
import org.julclang.tools.adr078.Adr078DebugFixtures.Program;
import org.julclang.tools.adr078.Adr078DebugFixtures.Trace;
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
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-078 I4 and D5's debug oracle: debugging is not lost. For each fixture program, what JuLC's source debugger shows
 * on this build is compared with what it showed on {@code 0.1.0-pre21} ({@code adr078/pre21-debug.txt}, recorded by
 * {@link Adr078DebugFixtures#main}):
 * <ul>
 *   <li>the executable Java lines;</li>
 *   <li>the line and column of every Java-breakpoint stop, with a breakpoint on every executable line;</li>
 *   <li>the visible locals (names and values) at each stop;</li>
 *   <li>every change of Java location while stepping through the whole run;</li>
 *   <li>the final status.</li>
 * </ul>
 * <ol>
 *   <li><b>Same source:</b> every program shows exactly what pre21 showed for it.</li>
 *   <li><b>Through the rename:</b> a renamed variant shows what the base showed on pre21, with each location carried
 *       through the rename mapping.</li>
 *   <li><b>Negative controls:</b> a deliberately mismatched mapping (the identity on a line whose columns move, or
 *       every line shifted by one) and another fixture's trace are told apart, so the comparison is not vacuous.</li>
 * </ol>
 */
class LibraryTieDebugOracleTest {

    private static final Map<String, String> PRE21 = new LinkedHashMap<>();
    private static final Map<String, Trace> NOW = new HashMap<>();
    private static final Pattern LOCATION = Pattern.compile("(\\d+):(\\d+)");

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = LibraryTieDebugOracleTest.class.getResourceAsStream("/adr078/pre21-debug.txt")) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                int at = line.indexOf(" = ");
                PRE21.put(line.substring(0, at), line.substring(at + 3));
            }
        }
        for (var fixture : Adr078DebugFixtures.fixtures()) {
            for (var program : fixture.programs()) NOW.put(key(fixture, program), Adr078DebugFixtures.trace(program));
        }
    }

    private static String key(Fixture fixture, Program program) {
        return fixture.name() + " | " + program.label();
    }

    /** pre21's trace of a program, rebuilt from its recorded lines. */
    private static Trace pre21(String key) {
        var locals = new ArrayList<String>();
        for (int i = 0; PRE21.containsKey(key + " | locals " + i); i++) locals.add(PRE21.get(key + " | locals " + i));
        assertTrue(PRE21.containsKey(key + " | hits"), "no pre21 record for " + key);
        return new Trace(integers(PRE21.get(key + " | executable")), strings(PRE21.get(key + " | hits")), locals,
                strings(PRE21.get(key + " | steps")), PRE21.get(key + " | status"));
    }

    private static List<String> strings(String list) {
        String inner = list.substring(1, list.length() - 1).trim();
        return inner.isEmpty() ? List.of() : List.of(inner.split(", "));
    }

    private static List<Integer> integers(String list) {
        return strings(list).stream().map(Integer::valueOf).toList();
    }

    /** A trace with every location carried through {@code mapping}. */
    private static Trace mapped(Trace trace, UnaryOperator<String> mapping) {
        return new Trace(trace.executableLines(), trace.hits().stream().map(mapping).toList(), trace.locals(),
                trace.steps().stream().map(mapping).toList(), trace.status());
    }

    /**
     * The rename mapping of a location from {@code base} to {@code renamed}: the same line, and the column carried
     * token by token (identifiers and single other characters), so a column after a renamed identifier moves by the
     * difference of their lengths.
     */
    static UnaryOperator<String> renameMapping(String base, String renamed) {
        String[] baseLines = base.split("\n", -1);
        String[] renamedLines = renamed.split("\n", -1);
        assertEquals(baseLines.length, renamedLines.length, "a rename keeps the lines");
        return location -> {
            Matcher m = LOCATION.matcher(location);
            assertTrue(m.matches(), location);
            int line = Integer.parseInt(m.group(1));
            int column = Integer.parseInt(m.group(2));
            return line + ":" + mapColumn(baseLines[line - 1], renamedLines[line - 1], column);
        };
    }

    private static int mapColumn(String baseLine, String renamedLine, int column) {
        var baseTokens = tokens(baseLine);
        var renamedTokens = tokens(renamedLine);
        assertEquals(baseTokens.size(), renamedTokens.size(), "a rename keeps the tokens of " + baseLine);
        int start = 0, renamedStart = 0;
        for (int i = 0; i < baseTokens.size(); i++) {
            int length = baseTokens.get(i).length();
            if (column - 1 < start + length) {
                return renamedStart + Math.min(column - 1 - start, renamedTokens.get(i).length() - 1) + 1;
            }
            start += length;
            renamedStart += renamedTokens.get(i).length();
        }
        return column + (renamedLine.length() - baseLine.length());
    }

    private static List<String> tokens(String line) {
        var tokens = new ArrayList<String>();
        Matcher m = Pattern.compile("\\w+|\\W").matcher(line);
        while (m.find()) tokens.add(m.group());
        return tokens;
    }

    @Test
    void everyProgramShowsWhatPre21ShowedForIt() {
        var differences = new ArrayList<String>();
        for (var fixture : Adr078DebugFixtures.fixtures()) {
            for (var program : fixture.programs()) {
                String key = key(fixture, program);
                var now = NOW.get(key);
                var before = pre21(key);
                assertFalse(now.hits().isEmpty(), key + ": no breakpoint was hit");
                assertTrue(now.locals().stream().anyMatch(l -> l.startsWith("available{") && l.length() > "available{}".length()),
                        key + ": no local was ever visible");
                if (!before.equals(now)) differences.add(key + "\n  pre21 " + before + "\n  now   " + now);
            }
        }
        assertEquals(List.of(), differences);
    }

    @Test
    void renamedVariantsShowTheBasesTraceThroughTheRenameMapping() {
        var differences = new ArrayList<String>();
        int moved = 0;
        for (var fixture : Adr078DebugFixtures.fixtures()) {
            var base = fixture.programs().getFirst();
            var expected = pre21(key(fixture, base));
            for (var program : fixture.programs().subList(1, fixture.programs().size())) {
                var mapping = renameMapping(base.validator(), program.validator());
                var carried = mapped(expected, mapping);
                if (!carried.hits().equals(expected.hits()) || !carried.steps().equals(expected.steps())) moved++;
                var now = NOW.get(key(fixture, program));
                if (!carried.equals(now)) differences.add(key(fixture, program) + "\n  expected " + carried + "\n  now      " + now);
            }
        }
        assertEquals(List.of(), differences);
        assertTrue(moved > 0, "at least one fixture must have locations that the rename moves, or the mapping is untested");
    }

    /** The comparison is not vacuous: a mismatched mapping, or another program's trace, is told apart. */
    @Test
    void mismatchedMappingsAndOtherProgramsAreToldApart() {
        int identityFailures = 0;
        for (var fixture : Adr078DebugFixtures.fixtures()) {
            var base = fixture.programs().getFirst();
            var expected = pre21(key(fixture, base));
            for (var program : fixture.programs().subList(1, fixture.programs().size())) {
                var now = NOW.get(key(fixture, program));
                // Every line shifted by one: always wrong.
                var shifted = mapped(expected, location -> {
                    Matcher m = LOCATION.matcher(location);
                    assertTrue(m.matches());
                    return (Integer.parseInt(m.group(1)) + 1) + ":" + m.group(2);
                });
                assertNotEquals(shifted, now, key(fixture, program) + ": a line-shifted mapping must not match");
                // The identity, where the rename moves columns: wrong on those fixtures.
                var mapping = renameMapping(base.validator(), program.validator());
                if (!mapped(expected, mapping).equals(expected)) {
                    assertNotEquals(expected, now, key(fixture, program) + ": the identity mapping must not match");
                    identityFailures++;
                }
            }
        }
        assertTrue(identityFailures > 0, "some fixture must move columns, so the identity mapping is refuted somewhere");
        // Another fixture's trace never matches.
        var fixtures = Adr078DebugFixtures.fixtures();
        for (int i = 0; i + 1 < fixtures.size(); i++) {
            var one = NOW.get(key(fixtures.get(i), fixtures.get(i).programs().getFirst()));
            var other = NOW.get(key(fixtures.get(i + 1), fixtures.get(i + 1).programs().getFirst()));
            assertNotEquals(one, other, fixtures.get(i).name() + " and " + fixtures.get(i + 1).name());
        }
    }
}
