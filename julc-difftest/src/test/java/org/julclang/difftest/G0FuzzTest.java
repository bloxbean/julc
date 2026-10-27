package org.julclang.difftest;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import org.julclang.difftest.gen.G0;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * The G0 campaign with Jazzer's coverage guidance in place of jqwik's sampling (ADR-063 D13). Jazzer's
 * bytes decode to the inputs and to G0's choice sequence, one byte per choice, and each program is checked
 * exactly as {@link G0PropertyTest} checks it.
 * <p>
 * {@code ./gradlew :julc-difftest:test} replays every input in {@code G0FuzzTestInputs/generatedProgramsAgree/}.
 * {@code ./gradlew :julc-difftest:fuzz --tests '*G0FuzzTest'} fuzzes, saving a failing input as
 * {@code crash-*} in that directory. Long seeds matter: Jazzer's JUnit mode grows inputs from the
 * largest seed, so short seeds keep the programs short.
 */
class G0FuzzTest {

    /** As many choices as the longest jqwik plan. */
    private static final int MAX_PLAN = 150;

    @FuzzTest(maxDuration = "60s")
    void generatedProgramsAgree(FuzzedDataProvider data) {
        var a = BigInteger.valueOf(data.consumeInt(-6, 6));
        var b = BigInteger.valueOf(data.consumeInt(-6, 6));
        var xs = new ArrayList<BigInteger>();
        for (int i = data.consumeInt(0, 4); i > 0; i--) xs.add(BigInteger.valueOf(data.consumeInt(-6, 6)));
        var plan = new ArrayList<Integer>();
        while (data.remainingBytes() > 0 && plan.size() < MAX_PLAN) plan.add(data.consumeByte() & 0xff);
        G0PropertyTest.verify("G0FuzzTest", plan, G0.decode(plan), a, b, List.copyOf(xs));
    }
}
