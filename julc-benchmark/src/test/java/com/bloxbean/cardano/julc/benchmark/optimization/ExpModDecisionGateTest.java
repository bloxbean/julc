package com.bloxbean.cardano.julc.benchmark.optimization;

import com.bloxbean.cardano.julc.vm.OptimizationCostProfiles;
import com.bloxbean.cardano.julc.compiler.OptimizationLevel;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Term;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** #118: preserve two distinct source contracts; do not introduce a pow/remainder rewrite. */
class ExpModDecisionGateTest {
    @Test
    void safeProfilesPreservePowRemainderAndExplicitExpModContracts() {
        var cases = List.of(input("ordinary", 2, 5, 13), input("inverse", 2, -1, 5),
                input("non-invertible", 2, -1, 4), input("zero-modulus", 2, 5, 0),
                input("negative-modulus", 2, 5, -7), input("one-modulus", 2, -1, 1),
                input("zero-base-exponent", 0, 0, 7));
        for (var expression : List.of("MathLib.pow(base, exponent) % modulus",
                "MathLib.expMod(base, exponent, modulus)")) {
            boolean explicit = expression.contains("expMod");
            String source = """
                    import java.math.BigInteger;
                    import com.bloxbean.cardano.julc.stdlib.lib.MathLib;
                    class Sample {
                        static BigInteger run(BigInteger base, BigInteger exponent, BigInteger modulus) {
                            return %s;
                        }
                    }
                    """.formatted(expression);
            for (var level : List.of(OptimizationLevel.PV11_SAFE, OptimizationLevel.PV11_COSTED)) {
                var comparison = OptimizationBenchmarkRunner.compareWithJavaAndTruffle(
                        new OptimizationBenchmarkRunner.Fixture("expmod-decision", source, "run", cases),
                        level, OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11);
                comparison.verifyEquivalent(); // Includes exact failure text and trace order for each backend.
                assertEquals(explicit, comparison.candidateArtifact().termMetrics().builtins()
                        .containsKey(DefaultFun.ExpModInteger));
                for (var result : comparison.candidateEvaluations()) {
                    if (result.caseId().equals("inverse")) {
                        assertEquals(Term.const_(Constant.integer(explicit ? 3 : 1)), result.resultTerm());
                    }
                    if (result.caseId().equals("non-invertible")) {
                        if (explicit) assertEquals(OptimizationBenchmarkRunner.Outcome.FAILURE, result.outcome());
                        else assertEquals(Term.const_(Constant.integer(1)), result.resultTerm());
                    }
                    if (result.caseId().equals("ordinary")) {
                        assertEquals(Term.const_(Constant.integer(6)), result.resultTerm());
                    }
                    if (result.caseId().equals("zero-modulus")) {
                        assertEquals(OptimizationBenchmarkRunner.Outcome.FAILURE, result.outcome());
                    }
                }
            }
        }
    }

    private static OptimizationBenchmarkRunner.InputCase input(String id, long base, long exponent, long modulus) {
        return OptimizationBenchmarkRunner.InputCase.of(id,
                PlutusData.integer(base), PlutusData.integer(exponent), PlutusData.integer(modulus));
    }
}
