package org.julclang.difftest;

import org.julclang.compiler.OptimizationLevel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Quotas count conclusive cases only (ADR-063 D9, H5). */
class CoverageTest {

    private static Map<DiffConfig, Verdict.Kind> verdicts(Verdict.Kind... kinds) {
        var verdicts = new LinkedHashMap<DiffConfig, Verdict.Kind>();
        var levels = OptimizationLevel.values();
        for (int i = 0; i < kinds.length; i++) verdicts.put(new DiffConfig(levels[i], "Java"), kinds[i]);
        return verdicts;
    }

    @Test
    void aDimensionSeenOnlyInInconclusiveCasesFailsItsQuota() {
        var coverage = new Coverage();
        for (int i = 0; i < 10; i++) coverage.record(Set.of("WHILE"), verdicts(Verdict.Kind.AGREE, Verdict.Kind.AGREE));
        // A budget exhaustion explained by the registry: the case passes, but its BREAK is no evidence.
        for (int i = 0; i < 10; i++) {
            coverage.record(Set.of("WHILE", "BREAK"), verdicts(Verdict.Kind.INCONCLUSIVE, Verdict.Kind.INCONCLUSIVE));
        }
        assertEquals(20, coverage.cases());
        assertEquals(10, coverage.conclusive("WHILE"));
        assertEquals(0, coverage.conclusive("BREAK"));
        assertEquals(10, coverage.count(Coverage.Outcome.INCONCLUSIVE));
        var failures = coverage.quotaFailures(List.of("WHILE", "BREAK"), 3.0);
        assertEquals(1, failures.size(), failures.toString());
        assertTrue(failures.getFirst().startsWith("BREAK: 0 conclusive of 20"), failures.getFirst());
    }

    @Test
    void oneInconclusiveOrDivergentConfigurationMakesTheCaseInconclusiveOrAllowed() {
        var coverage = new Coverage();
        coverage.record(Set.of("A"), verdicts(Verdict.Kind.AGREE, Verdict.Kind.INCONCLUSIVE));
        coverage.record(Set.of("A"), verdicts(Verdict.Kind.AGREE, Verdict.Kind.MISMATCH));
        coverage.record(Set.of("A"), verdicts(Verdict.Kind.MISMATCH, Verdict.Kind.INCONCLUSIVE));
        assertEquals(0, coverage.conclusive("A"));
        assertEquals(2, coverage.count(Coverage.Outcome.INCONCLUSIVE));
        assertEquals(1, coverage.count(Coverage.Outcome.ALLOWED_DIVERGENCE));
        assertEquals(1, coverage.quotaFailures(List.of("A"), 1.0).size());
        String report = coverage.report();
        assertTrue(report.contains("Java/NONE: {CONCLUSIVE=2, ALLOWED_DIVERGENCE=1}"), report);
        assertTrue(report.contains("Java/BASELINE: {ALLOWED_DIVERGENCE=1, INCONCLUSIVE=2}"), report);
    }

    @Test
    void conclusiveCasesMeetTheQuota() {
        var coverage = new Coverage();
        coverage.record(Set.of("A", "B"), verdicts(Verdict.Kind.AGREE));
        coverage.record(Set.of("A"), verdicts(Verdict.Kind.AGREE));
        assertEquals(List.of(), coverage.quotaFailures(List.of("A", "B"), 50.0));
        assertEquals(1, coverage.quotaFailures(List.of("B"), 51.0).size());
    }
}
