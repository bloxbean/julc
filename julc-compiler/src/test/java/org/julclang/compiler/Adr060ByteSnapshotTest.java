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
