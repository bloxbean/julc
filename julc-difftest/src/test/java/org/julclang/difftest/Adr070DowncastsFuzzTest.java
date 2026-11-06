package org.julclang.difftest;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;

import java.util.ArrayList;

/**
 * {@link Adr070DowncastsPropertyTest} under Jazzer's coverage guidance (ADR-070 verification 5): Jazzer's bytes decode to
 * the input sequence, then to the plan, as {@link G1FuzzTest}'s do, under {@code G1.Profile.DOWNCASTS_309}, and every
 * configuration must agree with the JVM. {@code ./gradlew :julc-difftest:test} replays
 * {@code Adr070DowncastsFuzzTestInputs/generatedProgramsAgree/};
 * {@code ./gradlew :julc-difftest:fuzz --tests '*Adr070DowncastsFuzzTest.generatedProgramsAgree'} fuzzes.
 */
class Adr070DowncastsFuzzTest {

    private static final int MAX_PLAN = 200;
    private static final int MAX_INPUTS = 40;

    @FuzzTest(maxDuration = "60s")
    void generatedProgramsAgree(FuzzedDataProvider data) {
        var inputs = new ArrayList<Integer>();
        for (int n = data.consumeInt(0, MAX_INPUTS); n > 0 && data.remainingBytes() > 0; n--) inputs.add(data.consumeByte() & 0xff);
        var plan = new ArrayList<Integer>();
        while (data.remainingBytes() > 0 && plan.size() < MAX_PLAN) plan.add(data.consumeByte() & 0xff);
        // No oracle (javac's VerifyError, ADR-069 R4; Adr069PatternFlowPropertyTest.noOracle): skip the input.
        if (Adr070DowncastsPropertyTest.check("Adr070DowncastsFuzzTest", plan, inputs) == null) {
            System.err.println("Adr070DowncastsFuzzTest: no oracle (javac VerifyError), input skipped");
        }
    }
}
