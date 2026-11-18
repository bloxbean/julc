package org.julclang.compiler.adr078;

import org.julclang.core.source.SourceLocation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-078 D5's property suite, a regression and discovery suite rather than a universal gate: generated programs
 * whose library classes hold methods with equal content keys, each rendered with {@link Adr078Programs#REWRITES}
 * rewrites (fresh method and class names, half of them in reversed name order; other packages, among them one that
 * sorts among the stdlib's; permuted library sources and imports). In every mode each rewrite must compile, as it did
 * on pre21 ({@code adr078/pre21-programs.txt}), and give the base's bytes and the base's source-map lines. On pre21
 * most rewrites differ from the base: the qualified-name tie-break.
 */
class LibraryTieOrderPropertyTest {

    @Test
    void rewritesKeepTheBytesInEveryMode() throws IOException {
        var programs = Adr078Programs.programs();
        var differences = new ArrayList<String>();
        var coverage = new StringBuilder();
        int compiled = 0;
        for (int p = 0; p < programs.size(); p++) {
            var variants = programs.get(p);
            for (var mode : Adr078Fixtures.modes()) {
                var base = Adr078Fixtures.compile(variants.getFirst(), mode);
                assertTrue(base.compiled(), "program " + p + " " + mode + ": " + base);
                int same = 0, reversed = 0;
                for (var variant : variants) {
                    var outcome = Adr078Fixtures.compile(variant, mode);
                    assertTrue(outcome.compiled(), "program " + p + " " + variant.label() + " " + mode + " compiled on pre21: " + outcome);
                    compiled++;
                    if (variant.label().contains("reversed")) reversed++;
                    if (outcome.toString().equals(base.toString())) {
                        same++;
                    } else {
                        differences.add("program " + p + " | " + variant.label() + " | " + mode + ": " + outcome + ", base " + base);
                    }
                    if (mode.sourceMaps()) {
                        assertEquals(lines(base.sourceMap()), lines(outcome.sourceMap()), "program " + p + " " + variant.label() + " " + mode);
                    }
                }
                coverage.append("program ").append(p).append(" | ").append(mode).append(": ").append(same).append('/')
                        .append(variants.size()).append(" give the base's bytes (").append(reversed).append(" rewrites in reversed name order)\n");
            }
        }
        System.out.print(coverage);
        System.out.println("pre21, for comparison:\n" + pre21());
        assertTrue(compiled > 0);
        assertEquals(List.of(), differences);
    }

    private static Map<Integer, Integer> lines(Map<Integer, SourceLocation> map) {
        var lines = new TreeMap<Integer, Integer>();
        map.forEach((index, location) -> lines.put(index, location.line()));
        return lines;
    }

    private static String pre21() throws IOException {
        try (InputStream in = LibraryTieOrderPropertyTest.class.getResourceAsStream("/adr078/pre21-programs.txt")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
