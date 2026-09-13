package org.julclang.benchmark.optimization;

import org.julclang.core.Constant;
import org.julclang.core.Term;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CostUseRewriteExperimentTest {

    // O8 value sharing graduated from research to a compiler rule in ADR-042; see
    // O8ValueSharingBenchmarkTest for the BASELINE vs PV11_SAFE comparison.

    // O9 list-to-array promotion graduated from research to a costed compiler rule in ADR-043;
    // see O9ListIndexPromotionBenchmarkTest for the PV11_SAFE vs PV11_COSTED comparison. The
    // research finding (valid results match, out-of-range failure text differs) is now the
    // ADR-043 failure contract, pinned by the compiler's O9ListIndexPromotionTest.

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
