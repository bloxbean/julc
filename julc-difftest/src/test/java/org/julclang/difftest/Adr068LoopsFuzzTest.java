package org.julclang.difftest;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;

import java.util.ArrayList;

/**
 * {@link Adr068LoopsPropertyTest} under Jazzer's coverage guidance: its bytes decode to the input and then the plan, one byte
 * per choice. {@code ./gradlew :julc-difftest:test} replays what {@code Adr068LoopsFuzzTestInputs/} holds;
 * {@code ./gradlew :julc-difftest:fuzz --tests '*Adr068LoopsFuzzTest'} fuzzes.
 */
class Adr068LoopsFuzzTest {

    private static final int MAX_PLAN = 240;

    @FuzzTest(maxDuration = "60s")
    void generatedProgramsAgree(FuzzedDataProvider data) {
        var inputs = new ArrayList<Integer>();
        for (int i = 0; i < 7; i++) inputs.add(data.consumeByte() & 0xff);
        var plan = new ArrayList<Integer>();
        while (data.remainingBytes() > 0 && plan.size() < MAX_PLAN) plan.add(data.consumeByte() & 0xff);
        Adr068LoopsPropertyTest.verify(LoopPrograms.decode(plan, inputs));
    }
}
