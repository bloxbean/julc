package org.julclang.benchmark.optimization;

import org.julclang.compiler.OptimizationLevel;
import org.julclang.core.PlutusData;
import org.julclang.vm.OptimizationCostProfiles;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OptimizationBenchmarkRunnerTest {

    @Test
    void identityComparisonRecordsExactArtifactAndBudgetProvenance() {
        var fixture = new OptimizationBenchmarkRunner.Fixture(
                "infrastructure-identity",
                """
                        class BenchmarkSample {
                            static long increment(long value) {
                                return value + 1;
                            }
                        }
                        """,
                "increment",
                List.of(OptimizationBenchmarkRunner.InputCase.of(
                        "forty-one", PlutusData.integer(41))));

        var comparison = OptimizationBenchmarkRunner.compare(
                fixture,
                OptimizationLevel.PV11_SAFE,
                OptimizationCostProfiles.PLUTUS_V3_PV11_COSTS_V1,
                List.of(OptimizationBenchmarkRunner.Backend.javaVm()));

        comparison.verifyEquivalent();
        assertEquals(comparison.baselineArtifact().flatBytes(),
                comparison.candidateArtifact().flatBytes());
        assertEquals(comparison.baselineArtifact().scriptHash(),
                comparison.candidateArtifact().scriptHash());
        assertEquals(comparison.baselineEvaluations().getFirst().budget(),
                comparison.candidateEvaluations().getFirst().budget());
        assertEquals("plutus-v3-pv11-costs-v1",
                comparison.candidateArtifact().costProfileId());
        assertThrows(NullPointerException.class, () -> OptimizationBenchmarkRunner.compare(
                fixture, OptimizationLevel.PV11_COSTED, null,
                List.of(OptimizationBenchmarkRunner.Backend.javaVm())));
        var markdown = comparison.toMarkdown();
        assertTrue(markdown.contains("| FLAT bytes |"));
        assertTrue(markdown.contains("| java | forty-one | SUCCESS |"));
        assertTrue(markdown.contains(
                OptimizationCostProfiles.PLUTUS_V3_PV11_COSTS_V1_PARAMETER_HASH));
    }
}
