package org.julclang.benchmark.optimization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #240 (ADR-047 amendment): incremental native point lists. Building the list of a G1
 * multi-scalar multiplication by a source recursion that uncompresses and conses each point
 * agrees with {@code g1PointsFromCompressed} on Java and Truffle; validating each point once
 * and consing it agrees with validating and then converting, and saves one decompression per
 * point; nested conses over a held point build the same list as the literal {@code g1Points}.
 * The numbers are printed for the evidence file.
 */
class O11BlsListConsBenchmarkTest {

    private static final int[] SIZES = {1, 2, 4, 8, 16, 24};

    @Test
    void consBuiltListsAgreeWithTheConverterAndValidatingOnceSavesADecompressionPerPoint() {
        for (int n : SIZES) {
            var build = OptimizationEvidenceMain.o11ConsBuildComparison(n);
            build.verifyEquivalent();
            var once = OptimizationEvidenceMain.o11ValidateOnceComparison(n);
            once.verifyEquivalent();
            var held = OptimizationEvidenceMain.o11ConsHeldPointsComparison(n);
            held.verifyEquivalent();
            for (var comparison : new OptimizationBenchmarkRunner.Comparison[]{build, once, held}) {
                for (var after : comparison.candidateEvaluations()) {
                    var before = comparison.baselineEvaluations().stream()
                            .filter(b -> b.backend().equals(after.backend()) && b.caseId().equals(after.caseId()))
                            .findFirst().orElseThrow();
                    assertEquals(OptimizationBenchmarkRunner.Outcome.SUCCESS, before.outcome(), comparison.fixtureId() + " " + before.failure());
                    assertEquals(OptimizationBenchmarkRunner.Outcome.SUCCESS, after.outcome(), comparison.fixtureId() + " " + after.failure());
                    if (comparison == once) {
                        // One G1 uncompress (about 52.9 million CPU on this profile) per point saved.
                        assertTrue(before.budget().cpuSteps() - after.budget().cpuSteps() > n * 50_000_000L,
                                "n=" + n + " " + after.backend() + ": " + after.budget() + " vs " + before.budget());
                    }
                    if (after.backend().equals(OptimizationBenchmarkRunner.Backend.javaVm().id())) {
                        System.out.println("BLS_LIST_CONS " + comparison.fixtureId() + " baseline=" + before.budget() + "/"
                                + comparison.baselineArtifact().flatBytes() + "B candidate=" + after.budget() + "/"
                                + comparison.candidateArtifact().flatBytes() + "B");
                    }
                }
            }
        }
    }
}
