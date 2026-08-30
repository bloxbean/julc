package com.bloxbean.cardano.julc.benchmark.optimization;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.Term;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class O13ExpModLiteralBenchmarkTest {

    @Test
    void literalFoldIsEquivalentAndDominatesOnBothBackends() {
        var comparison = OptimizationEvidenceMain.o13ExpModLiteralComparison();

        comparison.verifyEquivalent();
        assertTrue(comparison.candidateArtifact().appliedRules()
                .contains("pv11.o13.exp-mod-literal-fold"));
        assertTrue(comparison.candidateArtifact().flatBytes()
                < comparison.baselineArtifact().flatBytes());
        for (var result : comparison.candidateEvaluations()) {
            var term = (Term.Const) result.resultTerm();
            var value = (Constant.IntegerConst) term.value();
            assertEquals(BigInteger.TEN, value.value());
        }
        for (int i = 0; i < comparison.baselineEvaluations().size(); i++) {
            assertTrue(comparison.candidateEvaluations().get(i).budget().cpuSteps()
                    < comparison.baselineEvaluations().get(i).budget().cpuSteps());
            assertTrue(comparison.candidateEvaluations().get(i).budget().memoryUnits()
                    < comparison.baselineEvaluations().get(i).budget().memoryUnits());
        }
    }

    @Test
    void invalidLiteralRetainsExactRuntimeFailureOnBothBackends() {
        var comparison = OptimizationEvidenceMain.o13ExpModInvalidLiteralComparison();

        comparison.verifyEquivalent();
        assertEquals(comparison.baselineArtifact().scriptHash(),
                comparison.candidateArtifact().scriptHash());
        assertEquals(comparison.baselineArtifact().flatBytes(),
                comparison.candidateArtifact().flatBytes());
        assertFalse(comparison.candidateArtifact().appliedRules()
                .contains("pv11.o13.exp-mod-literal-fold"));

        var failureMatrix = OptimizationEvidenceMain.o13ExpModFailureMatrix();
        failureMatrix.verifyEquivalent();
        assertFalse(failureMatrix.candidateArtifact().appliedRules()
                .contains("pv11.o13.exp-mod-literal-fold"));
    }
}
