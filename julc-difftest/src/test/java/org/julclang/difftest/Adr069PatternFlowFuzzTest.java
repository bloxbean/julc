package org.julclang.difftest;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;

import java.util.ArrayList;

/**
 * {@link Adr069PatternFlowPropertyTest} under Jazzer's coverage guidance (ADR-069 verification 7): Jazzer's bytes decode
 * to the input sequence, then to the plan, one byte per choice, as {@link G1FuzzTest}'s do, under
 * {@code G1.Profile.PATTERN_FLOW_204}, and every configuration must agree with the JVM. A divergence the registry does
 * not explain is written to {@code build/difftest/last-failure.diffcase} with {@code found.by: Adr069PatternFlowFuzzTest}.
 * An input whose program has no oracle (javac 25.0.2's VerifyError, ADR-069 R4) is skipped with a line on stderr.
 * <p>
 * {@code ./gradlew :julc-difftest:test} replays every input in
 * {@code Adr069PatternFlowFuzzTestInputs/generatedProgramsAgree/};
 * {@code ./gradlew :julc-difftest:fuzz --tests '*Adr069PatternFlowFuzzTest.generatedProgramsAgree'} fuzzes.
 */
class Adr069PatternFlowFuzzTest {

    /** As many choices as the longest jqwik plan and input sequence. */
    private static final int MAX_PLAN = 200;
    private static final int MAX_INPUTS = 40;

    @FuzzTest(maxDuration = "60s")
    void generatedProgramsAgree(FuzzedDataProvider data) {
        var inputs = new ArrayList<Integer>();
        for (int n = data.consumeInt(0, MAX_INPUTS); n > 0 && data.remainingBytes() > 0; n--) inputs.add(data.consumeByte() & 0xff);
        var plan = new ArrayList<Integer>();
        while (data.remainingBytes() > 0 && plan.size() < MAX_PLAN) plan.add(data.consumeByte() & 0xff);
        // No oracle (javac 25.0.2's VerifyError, ADR-069 R4; Adr069PatternFlowPropertyTest.noOracle): skip the input.
        if (Adr069PatternFlowPropertyTest.check("Adr069PatternFlowFuzzTest", plan, inputs) == null) {
            System.err.println("Adr069PatternFlowFuzzTest: no oracle (javac VerifyError), input skipped");
        }
    }

    /**
     * ADR-069 verification 6's javac-filtered mode ({@link Adr069JavacFilteredPropertyTest}): the same decoding under
     * {@code G1.Profile.JAVAC_FILTERED_204}; programs javac rejects are skipped, and no other may diverge.
     */
    @FuzzTest(maxDuration = "60s")
    void javacFilteredProgramsDoNotDiverge(FuzzedDataProvider data) {
        var inputs = new ArrayList<Integer>();
        for (int n = data.consumeInt(0, MAX_INPUTS); n > 0 && data.remainingBytes() > 0; n--) inputs.add(data.consumeByte() & 0xff);
        var plan = new ArrayList<Integer>();
        while (data.remainingBytes() > 0 && plan.size() < MAX_PLAN) plan.add(data.consumeByte() & 0xff);
        Adr069JavacFilteredPropertyTest.check("Adr069PatternFlowFuzzTest.javacFiltered", plan, inputs);
    }
}
