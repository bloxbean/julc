package com.bloxbean.cardano.julc.benchmark.optimization;

import com.bloxbean.cardano.julc.core.DefaultFun;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class O1DropListBenchmarkTest {

    @Test
    void nativeDropIsEquivalentAcrossBoundariesFailuresAndBackends() {
        var comparison = OptimizationEvidenceMain.o1DropListComparison();

        comparison.verifyEquivalent();
        assertTrue(comparison.candidateArtifact().appliedRules()
                .contains("pv11.o1.drop-list"));
        assertTrue(comparison.candidateArtifact().flatBytes()
                < comparison.baselineArtifact().flatBytes());
        assertTrue(comparison.toMarkdown().contains("| truffle | count-failure | FAILURE |"));

        var javaNegative = comparison.baselineEvaluations().stream()
                .filter(result -> result.backend().equals("java")
                        && result.caseId().equals("negative"))
                .findFirst().orElseThrow();
        var javaReceiverFailure = comparison.baselineEvaluations().stream()
                .filter(result -> result.backend().equals("java")
                        && result.caseId().equals("receiver-failure"))
                .findFirst().orElseThrow();
        var javaCountFailure = comparison.baselineEvaluations().stream()
                .filter(result -> result.backend().equals("java")
                        && result.caseId().equals("count-failure"))
                .findFirst().orElseThrow();
        assertEquals(List.of("receiver", "count"), javaNegative.traces());
        assertEquals(List.of(), javaReceiverFailure.traces());
        assertEquals(List.of("receiver"), javaCountFailure.traces());
    }

    @Test
    void composedDropsRemainEquivalentAndUseTwoNativeApplications() {
        var comparison = OptimizationEvidenceMain.o1DropListComposedComparison();

        comparison.verifyEquivalent();
        assertEquals(2, comparison.candidateArtifact().termMetrics().builtins()
                .getOrDefault(DefaultFun.DropList, 0));
        assertTrue(comparison.candidateArtifact().flatBytes()
                < comparison.baselineArtifact().flatBytes());
    }

    @Test
    void boundedLengthAndCountMatrixIsEquivalentOnBothBackends() {
        var comparison = OptimizationEvidenceMain.o1DropListBoundaryMatrix();

        comparison.verifyEquivalent();
        assertEquals(9 * 16 * 2, comparison.candidateEvaluations().size());
    }
}
