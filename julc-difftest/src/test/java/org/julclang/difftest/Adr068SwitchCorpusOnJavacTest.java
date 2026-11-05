package org.julclang.difftest;

import org.julclang.compiler.CompileResult;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068 D10 on ADR-067's generated corpus, without a javac 21: every label sequence, guard kind and collision of
 * {@link SwitchParityCorpusTest} keeps the answer {@code switch-corpus.tsv} froze, written while the reference and javac both
 * answered it alike: the same bytes at each level, or the same rejection, with its code, message and position.
 * {@link SwitchParityCorpusTest} holds the same programs to javac 21.0.3 where that compiler is given; this runs in every build.
 */
class Adr068SwitchCorpusOnJavacTest {

    private static String answer(String source, OptimizationLevel level) throws NoSuchAlgorithmException {
        try {
            CompileResult result = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                    new CompilerOptions().setOptimizationLevel(level)).compileMethod(source, "m");
            return "bytes " + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(UplcFlatEncoder.encodeProgram(result.program())));
        } catch (CompilerException e) {
            return "rejected " + e.diagnostics().stream().filter(d -> d.level() == CompilerDiagnostic.Level.ERROR).findFirst()
                    .map(d -> d.code() + " " + d.message() + " @" + d.line() + ":" + d.column()).orElse(e.getMessage())
                    .replace("\n", "\\n").replace("\t", "\\t");
        }
    }

    @Test
    void everyProgramKeepsItsFrozenAnswer() throws IOException, NoSuchAlgorithmException {
        String table;
        try (InputStream in = Adr068SwitchCorpusOnJavacTest.class.getResourceAsStream("/adr068/switch-corpus.tsv")) {
            table = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        var frozen = new HashMap<String, String>();
        for (String row : table.split("\n")) {
            var cells = row.split("\t", 3);
            frozen.put(cells[0] + "\t" + cells[1], cells[2]);
        }
        var problems = new ArrayList<String>();
        int accepted = 0;
        int rejected = 0;
        int rows = 0;
        for (var program : SwitchParityCorpusTest.corpus()) {
            // Loops are their own slice, and stop.
            if (program.source().contains("for (")) continue;
            for (var level : OptimizationLevel.values()) {
                String actual = answer(program.source(), level);
                String expected = frozen.get(program.name() + "\t" + level);
                rows++;
                if (!actual.equals(expected)) problems.add(program.name() + " at " + level + ": " + expected + "\n    now " + actual);
                if (actual.startsWith("rejected")) {
                    rejected++;
                    break;
                }
                accepted++;
            }
        }
        System.out.println("[ADR-068 switch corpus] " + accepted + " accepted (program, level), " + rejected + " rejected");
        int compared = rows;
        assertEquals(0, problems.size(), () -> problems.size() + " differences:\n" + String.join("\n", problems.stream().limit(60).toList()));
        assertEquals(frozen.size(), compared, "the frozen table and the corpus have different rows");
        assertTrue(accepted >= 400, "too few programs reached a comparison of bytes: " + accepted);
        assertTrue(rejected >= 900, "too few rejections: " + rejected);
    }
}
