package com.bloxbean.cardano.julc.benchmark.optimization;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.Term;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class O7NativeValueBenchmarkTest {

    @Test
    void typedBoundaryIsZeroCostAndEquivalentAcrossBackends() {
        var comparison = OptimizationEvidenceMain.o7NativeValueComparison();

        comparison.verifyEquivalent();
        assertEquals(comparison.baselineArtifact().flatBytes(),
                comparison.candidateArtifact().flatBytes());
        assertEquals(comparison.baselineArtifact().scriptHash(),
                comparison.candidateArtifact().scriptHash());
        assertEquals(comparison.baselineArtifact().termMetrics(),
                comparison.candidateArtifact().termMetrics());
        assertTrue(comparison.candidateArtifact().appliedRules().stream()
                .noneMatch(rule -> rule.startsWith("pv11.")));

        for (int i = 0; i < comparison.baselineEvaluations().size(); i++) {
            var before = comparison.baselineEvaluations().get(i);
            var after = comparison.candidateEvaluations().get(i);
            assertEquals(before.budget(), after.budget());
        }
        assertEquals(2, comparison.candidateEvaluations().stream()
                .filter(result -> result.caseId().equals("malformed-data"))
                .filter(result -> result.outcome()
                        == OptimizationBenchmarkRunner.Outcome.FAILURE)
                .count());
        assertBooleanResults(comparison, "present", true);
        assertBooleanResults(comparison, "absent", true);
    }

    private static void assertBooleanResults(
            OptimizationBenchmarkRunner.Comparison comparison,
            String caseId,
            boolean expected) {
        var results = comparison.candidateEvaluations().stream()
                .filter(result -> result.caseId().equals(caseId))
                .toList();
        assertEquals(2, results.size());
        for (var result : results) {
            var term = (Term.Const) result.resultTerm();
            var bool = (Constant.BoolConst) term.value();
            assertEquals(expected, bool.value(), result.backend());
        }
    }
}
