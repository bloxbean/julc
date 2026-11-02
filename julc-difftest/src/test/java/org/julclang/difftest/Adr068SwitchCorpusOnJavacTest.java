package org.julclang.difftest;

import org.julclang.compiler.CompileResult;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068 D10 on ADR-067's generated corpus, without a javac 21: every label sequence, guard kind and collision of
 * {@link SwitchParityCorpusTest} on both frontends. The same program is accepted by both, with the same bytes, or rejected
 * by both, with the same code, message and position. A rejection is not a pass here for an accepted program, so the counts
 * below say what ran.
 */
class Adr068SwitchCorpusOnJavacTest {

    private sealed interface Outcome {
    }

    private record Bytes(byte[] flat) implements Outcome {
    }

    private record Rejected(String diagnostic) implements Outcome {
    }

    private static Outcome compile(String source, CompilerFrontend frontend, OptimizationLevel level) {
        try {
            CompileResult result = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                    new CompilerOptions().setOptimizationLevel(level).setFrontend(frontend)).compileMethod(source, "m");
            return new Bytes(UplcFlatEncoder.encodeProgram(result.program()));
        } catch (CompilerException e) {
            return new Rejected(e.diagnostics().stream().filter(d -> d.level() == CompilerDiagnostic.Level.ERROR).findFirst()
                    .map(d -> d.code() + " " + d.message() + " @" + d.line() + ":" + d.column()).orElse(e.getMessage()));
        }
    }

    @Test
    void everyProgramTakesTheReferencesAnswer() {
        var problems = new ArrayList<String>();
        int accepted = 0;
        int rejected = 0;
        int programs = 0;
        for (var program : SwitchParityCorpusTest.corpus()) {
            // Loops are their own slice, and stop.
            if (program.source().contains("for (")) continue;
            programs++;
            for (var level : OptimizationLevel.values()) {
                var reference = compile(program.source(), CompilerFrontend.JAVAPARSER, level);
                var javac = compile(program.source(), CompilerFrontend.JAVAC, level);
                if (reference instanceof Bytes expected && javac instanceof Bytes actual) {
                    if (!Arrays.equals(expected.flat(), actual.flat())) problems.add(program.name() + " at " + level + ": bytes differ");
                    accepted++;
                } else if (reference instanceof Rejected expected && javac instanceof Rejected actual) {
                    if (!expected.equals(actual)) problems.add(program.name() + ": " + expected.diagnostic() + "\n    on javac " + actual.diagnostic());
                    rejected++;
                } else {
                    problems.add(program.name() + " at " + level + ": reference "
                            + (reference instanceof Rejected r ? r.diagnostic() : "accepts") + "\n    javac "
                            + (javac instanceof Rejected r ? r.diagnostic() : "accepts"));
                }
                if (reference instanceof Rejected) break;
            }
        }
        System.out.println("[ADR-068 switch corpus] " + programs + " programs: " + accepted + " accepted (program, level), " + rejected + " rejected");
        assertEquals(0, problems.size(), () -> problems.size() + " differences:\n" + String.join("\n", problems.stream().limit(60).toList()));
        assertTrue(accepted >= 400, "too few programs reached a comparison of bytes: " + accepted);
        assertTrue(rejected >= 900, "too few rejections: " + rejected);
    }
}
