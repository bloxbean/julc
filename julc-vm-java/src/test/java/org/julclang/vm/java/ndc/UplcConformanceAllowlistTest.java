package org.julclang.vm.java.ndc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The allowlist of JuLC's own checks accepts one exact problem per vector, never a crash beside it. */
class UplcConformanceAllowlistTest {

    private static final String CONSTR_07 = "term_constr_constr_07_v3 V3/11.0";

    private static final String ACCEPTED = UplcEvaluation.flatKindProblem(UplcEvaluation.Kind.FAILURE,
            UplcEvaluation.Kind.PARSE_ERROR);

    @Test
    void acceptsTheExactConstr07Mismatch() {
        Map<String, List<String>> problems = Map.of(CONSTR_07, List.of(ACCEPTED));

        assertTrue(UplcConformanceNdcScenario.unexpectedProblems(problems,
                UplcConformanceNdcScenario.ALLOWED_PROBLEMS).isEmpty());
        assertTrue(UplcConformanceNdcScenario.staleProblems(problems,
                UplcConformanceNdcScenario.ALLOWED_PROBLEMS).isEmpty());
    }

    @Test
    void aCrashOnConstr07StillFailsTheGate() {
        // If the Flat program's evaluation crashed, the Flat check still sees a failure (so the accepted mismatch is
        // there too) and the text fallback still gives the expected outcome: only the crash problem can catch it.
        String crash = UplcEvaluation.crashProblem("Flat", "java.lang.NullPointerException: boom");
        Map<String, List<String>> problems = Map.of(CONSTR_07, List.of(crash, ACCEPTED));

        assertEquals(List.of(CONSTR_07 + ": " + crash), UplcConformanceNdcScenario.unexpectedProblems(problems,
                UplcConformanceNdcScenario.ALLOWED_PROBLEMS));
    }

    @Test
    void anotherOutcomeOnConstr07FailsTheGateAndMakesTheEntryStale() {
        String other = UplcEvaluation.flatKindProblem(UplcEvaluation.Kind.SUCCESS, UplcEvaluation.Kind.PARSE_ERROR);
        Map<String, List<String>> problems = Map.of(CONSTR_07, List.of(other));

        assertEquals(List.of(CONSTR_07 + ": " + other), UplcConformanceNdcScenario.unexpectedProblems(problems,
                UplcConformanceNdcScenario.ALLOWED_PROBLEMS));
        assertEquals(List.of(CONSTR_07), UplcConformanceNdcScenario.staleProblems(problems,
                UplcConformanceNdcScenario.ALLOWED_PROBLEMS));
    }

    @Test
    void theAcceptedProblemOnAnotherVectorFails() {
        Map<String, List<String>> problems = Map.of("term_constr_constr_08_v3 V3/11.0", List.of(ACCEPTED));

        assertEquals(1, UplcConformanceNdcScenario.unexpectedProblems(problems,
                UplcConformanceNdcScenario.ALLOWED_PROBLEMS).size());
    }
}
