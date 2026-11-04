package org.julclang.compiler;

import org.julclang.core.flat.UplcFlatEncoder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-060 byte oracle. {@code optimization/adr060-pre-change-bytes.txt} holds the FLAT bytes of every
 * {@link Adr060Corpus} program at every optimization level, with and without source maps, captured at
 * PR #186 head {@code ef932b21} before any binder was renamed. UPLC FLAT carries de Bruijn indices,
 * not names, so renaming compiler-generated binders must leave every row unchanged, except rows
 * listed in {@link #EXPLAINED} with the reason the bytes legitimately moved. ADR-068 M1 checked its rows by
 * disabling its comparison rule locally, which left every row as captured.
 * <p>
 * Recapture only from the base commit: set {@code JULC_ADR060_CAPTURE} to an output path.
 */
class Adr060ByteSnapshotTest {
    private static final String RESOURCE = "/optimization/adr060-pre-change-bytes.txt";

    /** Row id (or id prefix ending in '-') to the reason its bytes differ from the base commit. */
    static final Map<String, String> EXPLAINED = new LinkedHashMap<>();

    static {
        // The hygiene validators import MapLib, whose unused methods stay in the program at NONE
        // and with source maps; optimized builds without source maps drop them and keep their bytes.
        for (var entry : Adr060Corpus.entries()) {
            if (!entry.id().startsWith("hygiene-")) continue;
            for (var row : List.of("NONE-false", "NONE-true", "BASELINE-true", "PV11_SAFE-true", "PV11_COSTED-true"))
                EXPLAINED.put(entry.id() + "-" + row,
                        "MapLib.keys/values lower JulcMap keys()/values() with a right fold that keeps map order");
        }
        // ADR-068 M1 (D6, D4 exception 1) re-freezes the reference: a.compareTo(b) OP 0 lowers to one builtin over
        // its operands. These programs compare that way, so they move at every level.
        String compareTo = "ADR-068 D6: a.compareTo(b) OP 0 lowers to one integer comparison";
        for (var id : List.of("o5-0", "o5-1", "o5-3", "o5-4", "o15-0", "o15-1", "o15-3", "o15-13", "switch-0",
                "switch-1", "switch-2", "class-spend", "class-mint", "class-multi", "shapes", "statements"))
            EXPLAINED.put(id + "-", compareTo);
        // The optimization fixtures import stdlib libraries that compare that way; their unused methods stay in the
        // program at NONE and with source maps, so those rows move.
        for (var entry : Adr060Corpus.entries()) {
            if (!entry.id().matches("o(8|9|10|11|14|15)-\\d+")) continue;
            for (var row : List.of("NONE-false", "NONE-true", "BASELINE-true", "PV11_SAFE-true", "PV11_COSTED-true"))
                EXPLAINED.putIfAbsent(entry.id() + "-" + row, compareTo + " in the stdlib's unused methods");
        }
        // ADR-068 F904: an entry point's last parameter is the script context, so this golden validator reads its second
        // integer as its datum, as a spending entry point of three parameters.
        EXPLAINED.put("golden-0-", "ADR-068 F904: the golden validator's second integer is its datum");
        // ADR-068 M6: this table compares the javac frontend from here. Where a helper has several call sites it promotes a list
        // read to an array at the fourth level on javac, where the reference did not (068-followups.md, O9ListIndexFixtures).
        for (var id : List.of("o9-20-", "o9-21-")) EXPLAINED.put(id, "ADR-068 M5: the javac frontend promotes the read in the helper");
        // ADR-068 B3: this program's drop takes a local count, which takes the check a negative count fails.
        EXPLAINED.put("hygiene-17-", "ADR-068 B3: JulcList.drop fails on a negative count");
        // ADR-068 B4: each of these compiles a method with no parameters that may fail, which is a function of a unit now.
        for (int id : List.of(0, 1, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 16, 18, 19, 21, 22, 23, 25)) delayed("o10-" + id);
        for (int id : List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 28, 30)) delayed("o14-" + id);
        // Where the folds do not reduce such a method to a constant, the safe profile takes the function too.
        for (var id : List.of("o10-3", "o10-4", "o10-5", "o10-9", "o10-11", "o10-12", "o10-16", "o10-19", "o10-21", "o10-23", "o14-4", "o14-6", "o14-14", "o14-16", "o14-18", "o14-20", "o14-21", "o14-22", "o14-23", "o14-24", "o14-30")) {
            for (var row : List.of("PV11_SAFE-false", "PV11_COSTED-false"))
                EXPLAINED.put(id + "-" + row, "ADR-068 B4: a method with no parameters that may fail runs at each call");
        }
        // ADR-032 O2: a condition the proof does not establish, such as a lambda parameter, lowers to builtin
        // IfThenElse at the levels that have Case. Source-map rows of these programs are explained above.
        String condition = "ADR-032 O2: a condition not proven boolean lowers to builtin IfThenElse";
        var conditions = new ArrayList<>(List.of("o8-4", "o8-5", "o9-5", "o9-11", "o10-17", "o11-10", "o11-16",
                "o14-25", "o15-4", "o15-20"));
        for (int hygiene : List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 21)) conditions.add("hygiene-" + hygiene);
        for (var id : conditions) {
            EXPLAINED.put(id + "-PV11_SAFE-false", condition);
            EXPLAINED.put(id + "-PV11_COSTED-false", condition);
        }
    }

    private static void delayed(String id) {
        EXPLAINED.put(id + "-BASELINE-false", "ADR-068 B4: a method with no parameters that may fail runs at each call");
    }

    @Test
    void generatedNamesDoNotChangeBytes() throws IOException {
        var rows = new LinkedHashMap<String, String>();
        for (var entry : Adr060Corpus.entries())
            for (var level : Adr060Corpus.LEVELS)
                for (boolean maps : new boolean[] {false, true}) {
                    String row;
                    try {
                        var result = Adr060Corpus.compile(entry, level, maps);
                        row = result.hasErrors() ? "ERROR"
                                : HexFormat.of().formatHex(UplcFlatEncoder.encodeProgram(result.program()));
                    } catch (RuntimeException e) {
                        row = "ERROR";
                    }
                    rows.put(entry.id() + "-" + level + "-" + maps, row);
                }

        String capture = System.getenv("JULC_ADR060_CAPTURE");
        if (capture != null) {
            var out = new StringBuilder("# ADR-060 pre-change FLAT bytes for Adr060Corpus, captured at PR #186 head ef932b21\n"
                    + "# before any generated binder was renamed. Format: <id>-<LEVEL>-<sourceMaps> <hex|ERROR>.\n");
            rows.forEach((id, hex) -> out.append(id).append(' ').append(hex).append('\n'));
            Files.writeString(Path.of(capture), out.toString());
            return;
        }

        var expected = new LinkedHashMap<String, String>();
        try (var input = Adr060ByteSnapshotTest.class.getResourceAsStream(RESOURCE)) {
            assertNotNull(input, RESOURCE);
            new String(input.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.startsWith("#") && !line.isBlank())
                    .forEach(line -> {
                        int space = line.indexOf(' ');
                        expected.put(line.substring(0, space), line.substring(space + 1));
                    });
        }
        assertEquals(expected.keySet(), rows.keySet(), "corpus rows changed; recapture from the base commit");

        var unexplained = new ArrayList<String>();
        rows.forEach((id, hex) -> {
            if (hex.equals(expected.get(id))) return;
            if (explanation(id) == null) unexplained.add(id + (hex.equals("ERROR") ? " (now fails to compile)" : ""));
        });
        assertTrue(unexplained.isEmpty(), "unexplained byte changes: " + unexplained);
    }

    private static String explanation(String id) {
        for (var e : EXPLAINED.entrySet())
            if (id.equals(e.getKey()) || (e.getKey().endsWith("-") && id.startsWith(e.getKey()))) return e.getValue();
        return null;
    }
}
