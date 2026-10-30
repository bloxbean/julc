package org.julclang.difftest;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import org.julclang.difftest.gen.G1;

import java.util.ArrayList;

/**
 * The G1 campaign with Jazzer's coverage guidance (ADR-066 D5, as ADR-063 D13 for G0): Jazzer's bytes decode to the
 * input sequence, then to the plan, one byte per choice, under the default profile, and each case is checked as
 * {@link G1PropertyTest} checks it. A finding is written to {@code build/difftest/last-failure.diffcase} with
 * {@code found.by: G1FuzzTest}, its plan, inputs and profile.
 * <p>
 * {@code ./gradlew :julc-difftest:test} replays every input in {@code G1FuzzTestInputs/generatedProgramsAgree/};
 * {@code ./gradlew :julc-difftest:fuzz --tests '*G1FuzzTest'} fuzzes.
 */
class G1FuzzTest {

    /** As many choices as the longest jqwik plan and input sequence. */
    private static final int MAX_PLAN = 200;
    private static final int MAX_INPUTS = 40;

    @FuzzTest(maxDuration = "60s")
    void generatedProgramsAgree(FuzzedDataProvider data) {
        var inputs = new ArrayList<Integer>();
        for (int n = data.consumeInt(0, MAX_INPUTS); n > 0 && data.remainingBytes() > 0; n--) inputs.add(data.consumeByte() & 0xff);
        var plan = new ArrayList<Integer>();
        while (data.remainingBytes() > 0 && plan.size() < MAX_PLAN) plan.add(data.consumeByte() & 0xff);
        G1PropertyTest.verify("G1FuzzTest", plan, inputs, G1.decode(plan, G1.Profile.DEFAULT));
    }
}
