package com.bloxbean.cardano.julc.benchmark.optimization;

import com.bloxbean.cardano.julc.compiler.OptimizationLevel;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.vm.OptimizationCostProfiles;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11,
                List.of(OptimizationBenchmarkRunner.Backend.javaVm()));

        comparison.verifyEquivalent();
        assertEquals(comparison.baselineArtifact().flatBytes(),
                comparison.candidateArtifact().flatBytes());
        assertEquals(comparison.baselineArtifact().scriptHash(),
                comparison.candidateArtifact().scriptHash());
        assertEquals(comparison.baselineEvaluations().getFirst().budget(),
                comparison.candidateEvaluations().getFirst().budget());
        assertEquals("cardano-node-11.0.1-plutus-v3-pv11",
                comparison.candidateArtifact().costProfileId());
        var markdown = comparison.toMarkdown();
        assertTrue(markdown.contains("| FLAT bytes |"));
        assertTrue(markdown.contains("| java | forty-one | SUCCESS |"));
        assertTrue(markdown.contains(
                OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11_PARAMETER_HASH));
    }
}
