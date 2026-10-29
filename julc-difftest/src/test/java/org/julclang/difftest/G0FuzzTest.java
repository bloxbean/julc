package org.julclang.difftest;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import org.julclang.difftest.gen.G0;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The G0 campaign with Jazzer's coverage guidance in place of jqwik's sampling (ADR-063 D13). Jazzer's
 * bytes decode to the inputs and to G0's choice sequence, one byte per choice, and each program is checked
 * exactly as {@link G0PropertyTest} checks it, against {@link G0PropertyTest#KNOWN}: a conclusive mismatch
 * passes only as an exact pin from the saved cases, an inconclusive UPLC side only by a rule with its exact
 * reason, and a crash never (D6). Anything else is a finding, written to {@code build/difftest/last-failure.diffcase}
 * with {@code found.by: G0FuzzTest} and its plan.
 * <p>
 * Every match is counted and reported (H2): each explained input prints one line to standard error (the
 * fuzz target's standard output does not reach the test report), and the run ends with a
 * tally per catalogue ID, printed and written to {@code build/difftest/g0-fuzz-explained.txt}.
 * <p>
 * {@code ./gradlew :julc-difftest:test} replays every input in {@code G0FuzzTestInputs/generatedProgramsAgree/}.
 * {@code ./gradlew :julc-difftest:fuzz --tests '*G0FuzzTest'} fuzzes, saving a failing input as
 * {@code crash-*} in that directory. Long seeds matter: Jazzer's JUnit mode grows inputs from the
 * largest seed, so short seeds keep the programs short.
 */
class G0FuzzTest {

    /** As many choices as the longest jqwik plan. */
    private static final int MAX_PLAN = 150;
    static final Path TALLY = Path.of("build/difftest/g0-fuzz-explained.txt");

    /** Inputs run, and inputs that some configuration needed the registry for, by catalogue ID. */
    private static long inputs;
    private static final Map<String, Long> explained = new TreeMap<>();

    @BeforeAll
    static void startTally() {
        inputs = 0;
        explained.clear();
    }

    @AfterAll
    static void reportTally() {
        String report = tally();
        System.out.println(report);
        try {
            Files.createDirectories(TALLY.getParent());
            Files.writeString(TALLY, report + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static synchronized String tally() {
        return "G0FuzzTest: " + inputs + " inputs, explained by the registry: " + (explained.isEmpty() ? "none" : explained);
    }

    private static synchronized void count(List<Integer> plan, G0PropertyTest.Outcome outcome) {
        inputs++;
        if (outcome.explained().isEmpty()) return;
        for (String id : outcome.explained()) explained.merge(id, 1L, Long::sum);
        System.err.println("G0FuzzTest explained " + outcome.explained() + " " + outcome.verdicts() + " plan " + plan);
    }

    @FuzzTest(maxDuration = "60s")
    void generatedProgramsAgree(FuzzedDataProvider data) {
        var a = BigInteger.valueOf(data.consumeInt(-6, 6));
        var b = BigInteger.valueOf(data.consumeInt(-6, 6));
        var xs = new ArrayList<BigInteger>();
        for (int i = data.consumeInt(0, 4); i > 0; i--) xs.add(BigInteger.valueOf(data.consumeInt(-6, 6)));
        var plan = new ArrayList<Integer>();
        while (data.remainingBytes() > 0 && plan.size() < MAX_PLAN) plan.add(data.consumeByte() & 0xff);
        count(plan, G0PropertyTest.verify("G0FuzzTest", plan, G0.decode(plan), a, b, List.copyOf(xs)));
    }
}
