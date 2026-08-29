package com.bloxbean.cardano.julc.benchmark.optimization;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.Term;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CostUseRewriteExperimentTest {

    @Test
    void explicitValueSharingIsEquivalentButRequiresDominanceAnalysis() {
        var comparison = OptimizationEvidenceMain.o8ValueSharingExperiment();

        comparison.verifyEquivalent();
        assertTrue(comparison.candidateArtifact().flatBytes()
                < comparison.baselineArtifact().flatBytes());
        assertTrue(comparison.candidateEvaluations().stream()
                .filter(result -> result.caseId().equals("valid"))
                .allMatch(result -> result.budget().cpuSteps()
                        < comparison.baselineEvaluations().stream()
                                .filter(before -> before.backend().equals(result.backend())
                                        && before.caseId().equals(result.caseId()))
                                .findFirst().orElseThrow().budget().cpuSteps()));
    }

    @Test
    void listToArrayValidResultsMatchButFailureTextDoesNot() {
        var comparison = OptimizationEvidenceMain.o9ArrayPromotionExperiment();

        for (int i = 0; i < comparison.baselineEvaluations().size(); i++) {
            var before = comparison.baselineEvaluations().get(i);
            var after = comparison.candidateEvaluations().get(i);
            assertEquals(before.outcome(), after.outcome());
            if (before.outcome() == OptimizationBenchmarkRunner.Outcome.SUCCESS) {
                assertEquals(before.resultTerm(), after.resultTerm());
            } else {
                assertNotEquals(before.failure(), after.failure());
            }
        }
    }

    @Test
    void powModAndExpModDifferOnDocumentedBoundaryDomain() {
        var comparison = OptimizationEvidenceMain.o12ExpModIdiomExperiment();

        var differences = 0;
        for (int i = 0; i < comparison.baselineEvaluations().size(); i++) {
            var before = comparison.baselineEvaluations().get(i);
            var after = comparison.candidateEvaluations().get(i);
            if (before.outcome() != after.outcome()
                    || !java.util.Objects.equals(before.resultTerm(), after.resultTerm())
                    || !java.util.Objects.equals(before.failure(), after.failure())) {
                differences++;
            }
        }
        assertTrue(differences > 0);
        assertEquals(1, integerResult(comparison, true, "negative-exponent"));
        assertEquals(3, integerResult(comparison, false, "negative-exponent"));
    }

    private static long integerResult(
            OptimizationBenchmarkRunner.Comparison comparison,
            boolean baseline,
            String caseId) {
        var evaluations = baseline
                ? comparison.baselineEvaluations()
                : comparison.candidateEvaluations();
        var result = evaluations.stream()
                .filter(evaluation -> evaluation.backend().equals("java")
                        && evaluation.caseId().equals(caseId))
                .findFirst().orElseThrow();
        var term = (Term.Const) result.resultTerm();
        return ((Constant.IntegerConst) term.value()).value().longValueExact();
    }
}
