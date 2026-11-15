package org.julclang.benchmark.optimization;

import org.julclang.core.Constant;
import org.julclang.core.Term;
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
        // O13's size and budget against BASELINE are measured with ADR-076 off, and the real candidate held to
        // ADR-076's bound against that one.
        var unshared = OptimizationEvidenceMain.o13ExpModLiteralComparison(Adr076Isolation.WITHOUT_ADR076);
        Adr076Isolation.assertWithinAdr076Bound(comparison, unshared);
        assertTrue(unshared.candidateArtifact().flatBytes()
                < comparison.baselineArtifact().flatBytes());
        for (var result : comparison.candidateEvaluations()) {
            var term = (Term.Const) result.resultTerm();
            var value = (Constant.IntegerConst) term.value();
            assertEquals(BigInteger.TEN, value.value());
        }
        for (int i = 0; i < comparison.baselineEvaluations().size(); i++) {
            assertTrue(unshared.candidateEvaluations().get(i).budget().cpuSteps()
                    < comparison.baselineEvaluations().get(i).budget().cpuSteps());
            assertTrue(unshared.candidateEvaluations().get(i).budget().memoryUnits()
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
