package com.bloxbean.cardano.julc.benchmark.optimization;

import com.bloxbean.cardano.julc.core.DefaultFun;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class O2CaseBoolBenchmarkTest {

    @Test
    void caseBoolPreservesLazyBranchesFailuresAndTracesOnBothBackends() {
        var comparison = OptimizationEvidenceMain.o2CaseBoolComparison();

        comparison.verifyEquivalent();
        assertTrue(comparison.candidateArtifact().appliedRules()
                .contains("pv11.o2.case-bool"));
        assertEquals(0, comparison.candidateArtifact().termMetrics().builtins()
                .getOrDefault(DefaultFun.IfThenElse, 0));
        assertTrue(comparison.candidateArtifact().flatBytes()
                < comparison.baselineArtifact().flatBytes());

        for (var result : comparison.candidateEvaluations()) {
            var expectedTrace = switch (result.caseId()) {
                case "true", "true-unselected-error" -> List.of("condition", "then");
                case "false", "false-unselected-error" -> List.of("condition", "else");
                default -> List.of("condition");
            };
            assertEquals(expectedTrace, result.traces(),
                    result.backend() + "/" + result.caseId());
        }
        assertEquals(4, comparison.candidateEvaluations().stream()
                .filter(result -> result.outcome()
                        == OptimizationBenchmarkRunner.Outcome.FAILURE)
                .count());
    }
}
