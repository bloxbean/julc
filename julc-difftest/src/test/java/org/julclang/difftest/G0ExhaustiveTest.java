package org.julclang.difftest;

import org.julclang.core.PlutusData;
import org.julclang.difftest.gen.G0;
import org.julclang.difftest.gen.KnownDivergences;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every G0 program decoded from a choice sequence of {@value #LENGTH} numbers below
 * {@value #ALPHABET}, run on fixed boundary inputs (ADR-063 D7). An exhausted sequence decodes
 * as zeros, so this also covers every shorter sequence. It complements random generation, which
 * can miss small combinations; every failure is written to {@code build/difftest/exhaustive/}.
 */
class G0ExhaustiveTest {

    static final int LENGTH = 6;
    static final int ALPHABET = 3;

    private static final List<List<PlutusData>> INPUTS = List.of(
            inputs(0, 0),
            inputs(-1, 2, 1),
            inputs(3, -3, 2, -1, 0));

    private static List<PlutusData> inputs(long a, long b, long... xs) {
        var items = new ArrayList<PlutusData>();
        for (long x : xs) items.add(new PlutusData.IntData(BigInteger.valueOf(x)));
        return List.of(new PlutusData.IntData(BigInteger.valueOf(a)), new PlutusData.IntData(BigInteger.valueOf(b)),
                new PlutusData.ListData(items));
    }

    @Test
    void everySmallProgramAgrees() {
        Map<String, G0.Program> programs = new LinkedHashMap<>();
        long total = (long) Math.pow(ALPHABET, LENGTH);
        for (long n = 0; n < total; n++) {
            long rest = n;
            var plan = new ArrayList<Integer>();
            for (int i = 0; i < LENGTH; i++) {
                plan.add((int) (rest % ALPHABET));
                rest /= ALPHABET;
            }
            var program = G0.decode(plan);
            programs.putIfAbsent(program.source(), program);
        }
        assertTrue(programs.size() > 100, "expected well over 100 distinct programs, got " + programs.size());

        var runner = DiffRunner.standard();
        var failures = new ArrayList<String>();
        int index = 0;
        for (var program : programs.values()) {
            for (var args : INPUTS) {
                var diffCase = new DiffCase("g0 exhaustive " + index, program.source(), "Probe", "m", args);
                var report = runner.run(diffCase);
                boolean unexplained = report.results().values().stream().anyMatch(r -> r.verdict().kind() != Verdict.Kind.AGREE
                        && KnownDivergences.explain(program.features(), r.jvm(), r.uplc()).isEmpty());
                if (unexplained) {
                    RegressionStore.write(Path.of("build/difftest/exhaustive/case-" + index + ".diffcase"), diffCase,
                            new RegressionStore.Expectation(Verdict.Kind.AGREE, null, null, null), "Found by G0ExhaustiveTest");
                    failures.add(report.describe() + "\n" + program.source());
                }
            }
            index++;
        }
        if (!failures.isEmpty()) {
            fail(failures.size() + " of " + programs.size() * INPUTS.size() + " runs disagree:\n\n" + String.join("\n\n", failures));
        }
    }
}
