package com.bloxbean.cardano.julc.benchmark.optimization;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.Term;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class O3ListCaseBenchmarkTest {
    @Test
    void compiledTraversalsPreserveResultsFailuresTracesAndImproveBudgets() {
        for (var c : ListCaseEvidence.comparisons()) {
            c.verifyEquivalent();
            assertTrue(c.candidateArtifact().appliedRules().contains("pv11.o3.case-list"));
            if (c.fixtureId().endsWith("-aggregate")) {
                assertTrue(c.candidateArtifact().appliedRules().containsAll(List.of(
                        "pv11.o1.drop-list", "pv11.o2.case-bool", "pv11.o13.exp-mod-literal-fold")));
            }
            assertTrue(c.candidateArtifact().flatBytes() < c.baselineArtifact().flatBytes(), c.fixtureId());
            for (int i = 0; i < c.candidateEvaluations().size(); i++) {
                var before = c.baselineEvaluations().get(i);
                var after = c.candidateEvaluations().get(i);
                String label = c.fixtureId() + "/" + after.backend() + "/" + after.caseId();
                assertTrue(after.budget().cpuSteps() <= before.budget().cpuSteps(), label);
                assertTrue(after.budget().memoryUnits() <= before.budget().memoryUnits(), label);
                if (c.fixtureId().endsWith("-traced")) {
                    var expected = new ArrayList<String>();
                    expected.add("input");
                    if (!after.caseId().equals("bad-outer")) expected.add("init");
                    int items = switch (after.caseId()) {
                        case "singleton", "bad-later", "break-before-bad" -> 1;
                        case "many", "break" -> 3;
                        default -> 0;
                    };
                    for (int j = 0; j < items; j++) expected.add("item");
                    assertEquals(expected, after.traces(), label);
                }
                if (after.caseId().equals("empty") && !c.fixtureId().endsWith("-unchecked")) {
                    assertEquals(Term.const_(Constant.integer(0)), after.resultTerm(), label);
                }
                if (c.fixtureId().endsWith("-sum") && after.caseId().equals("many")) {
                    assertEquals(Term.const_(Constant.integer(6)), after.resultTerm(), label);
                }
                if (c.fixtureId().endsWith("-multi") && after.caseId().equals("break")) {
                    assertEquals(Term.const_(Constant.integer(32)), after.resultTerm(), label);
                }
                if (c.fixtureId().endsWith("-nested") && after.caseId().equals("nested")) {
                    assertEquals(Term.const_(Constant.integer(6)), after.resultTerm(), label);
                }
                if ((c.fixtureId().endsWith("-recursive") || c.fixtureId().endsWith("-mutualA")) && after.caseId().equals("three")) {
                    assertEquals(Term.const_(Constant.integer(18)), after.resultTerm(), label);
                }
                if (c.fixtureId().endsWith("-aggregate") && !after.caseId().equals("visit-malformed")) {
                    long expected = switch (after.caseId()) {
                        case "accept" -> 11;
                        case "negative-drop" -> 12;
                        case "skip-malformed" -> 8;
                        default -> 0;
                    };
                    assertEquals(Term.const_(Constant.integer(expected)), after.resultTerm(), label);
                }
                if (c.fixtureId().endsWith("-records") && after.caseId().equals("record")) {
                    assertEquals(Term.const_(Constant.integer(7)), after.resultTerm(), label);
                }
                if (c.fixtureId().endsWith("-effects") && after.caseId().equals("many")) {
                    assertEquals(Term.const_(Constant.integer(0)), after.resultTerm(), label);
                    assertEquals(List.of("item", "item", "item"), after.traces(), label);
                }
                if (c.fixtureId().endsWith("-stop") && after.caseId().equals("break-before-bad")) {
                    assertEquals(Term.const_(Constant.integer(0)), after.resultTerm(), label);
                    assertEquals(List.of("item"), after.traces(), label);
                }
            }
            // Independent backend equality includes exact budgets, not only before/after semantics.
            int half = c.candidateEvaluations().size() / 2;
            for (int i = 0; i < half; i++) {
                var java = c.candidateEvaluations().get(i);
                var truffle = c.candidateEvaluations().get(i + half);
                assertEquals(java.resultTerm(), truffle.resultTerm());
                assertEquals(java.failure(), truffle.failure());
                assertEquals(java.traces(), truffle.traces());
                assertEquals(java.budget(), truffle.budget());
            }
        }
    }

    @Test
    void generatedListsAgreeWithHostSumAndBreakModel() {
        var random = new Random(110);
        var inputs = new ArrayList<OptimizationBenchmarkRunner.InputCase>();
        var sums = new ArrayList<Long>();
        var stops = new ArrayList<Long>();
        for (int n = 0; n < 80; n++) {
            long[] xs = new long[n % 25];
            long sum = 0, stop = 0;
            boolean stopped = false;
            for (int i = 0; i < xs.length; i++) {
                xs[i] = random.nextInt(21) - 10;
                sum += xs[i];
                if (!stopped) stop += xs[i];
                if (xs[i] == 0) stopped = true;
            }
            inputs.add(OptimizationBenchmarkRunner.InputCase.of("generated-" + n, ListCaseEvidence.integers(xs)));
            sums.add(sum); stops.add(stop);
        }
        for (var method : List.of("sum", "stop")) {
            var c = ListCaseEvidence.compare(method, inputs);
            var expected = method.equals("sum") ? sums : stops;
            for (int i = 0; i < c.candidateEvaluations().size(); i++) {
                assertEquals(Term.const_(Constant.integer(expected.get(i % inputs.size()))),
                        c.candidateEvaluations().get(i).resultTerm());
            }
        }
    }
}
