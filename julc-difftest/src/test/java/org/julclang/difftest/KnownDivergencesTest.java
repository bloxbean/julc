package org.julclang.difftest;

import org.julclang.compiler.OptimizationLevel;
import org.julclang.core.PlutusData;
import org.julclang.difftest.gen.G0;
import org.julclang.difftest.gen.G0Enumeration;
import org.julclang.difftest.gen.KnownDivergences;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The allowed-divergence registry (ADR-063 D6): a conclusive mismatch passes only as an exact pin, and
 * a rule explains only an inconclusive UPLC side, by its exact reason.
 */
class KnownDivergencesTest {

    private static final KnownDivergences KNOWN = G0PropertyTest.KNOWN;

    /** The T23 shape of the exhaustive corpus. */
    private static final G0Enumeration.Program T23 = new G0Enumeration.Program(G0Enumeration.Shape.FOR_EACH,
            List.of(G0Enumeration.Statement.IF_ELSE_UPDATE, G0Enumeration.Statement.BREAK), List.of());

    private static final DiffConfig CONFIG = new DiffConfig(OptimizationLevel.BASELINE, "Java");

    private static DiffCase t23(String className, long a, long b, long... xs) {
        var items = new ArrayList<PlutusData>();
        for (long x : xs) items.add(new PlutusData.IntData(BigInteger.valueOf(x)));
        return new DiffCase("t23", T23.source(className), className, "m", List.of(new PlutusData.IntData(BigInteger.valueOf(a)),
                new PlutusData.IntData(BigInteger.valueOf(b)), new PlutusData.ListData(items)));
    }

    private static Observation value(long v, String... traces) {
        return new Observation.Value(new PlutusData.IntData(BigInteger.valueOf(v)), List.of(traces));
    }

    private static Optional<String> explain(DiffCase diffCase, Set<G0.Feature> features, Observation jvm, Observation uplc) {
        return KNOWN.explain(diffCase, CONFIG, features, new DiffRunner.Result(jvm, uplc, Verdict.compare(jvm, uplc)));
    }

    @Test
    void theSavedT23CasesArePins() {
        var pinned = KNOWN.pins().stream().filter(p -> p.expectation().detail().equals("T23")).count();
        assertEquals(4, pinned, "two hand-reduced reproducers and the two corpus runs");
    }

    @Test
    void aPinnedReproducerIsExplainedUnderAnyClassName() {
        // The corpus compiles program 135 as class E135; its saved case declares Probe.
        assertEquals(Optional.of("T23"), explain(t23("E135", -2, 1, -1, 2, 4), T23.features(), value(8), value(38)));
        assertEquals(Optional.of("T23"), explain(t23("Probe", -2, -3, -4, 3, 1, 2), T23.features(), value(-1), value(26)));
        assertEquals(Optional.of("T23"), explain(t23("Probe", 1, -3, -4, -5), T23.features(), value(-6), value(-5)));
    }

    @Test
    void anotherWrongValueInTheT23ShapeIsNotExplained() {
        // Review round r2, F7: the loop body never runs on an empty list, so both sides return 9.
        var empty = t23("Probe", 0, 3);
        var jvm = new JvmExecutor(5_000).run(empty);
        assertEquals(value(9), jvm);
        assertEquals(Optional.empty(), explain(empty, T23.features(), jvm, value(999)), "an unrelated wrong value");
        assertEquals(Optional.empty(), explain(empty, T23.features(), jvm, value(9, "unexpected trace")), "a trace-only change");
        // A pinned program and input, but a value other than the pinned one.
        var pinned = t23("Probe", -2, 1, -1, 2, 4);
        assertEquals(Optional.empty(), explain(pinned, T23.features(), value(8), value(39)));
        assertEquals(Optional.empty(), explain(pinned, T23.features(), value(8), value(38, "t")));
        assertEquals(Optional.empty(), explain(pinned, T23.features(), value(7), value(38)), "another JVM value");
        // The pinned observations on another input, or on another program.
        assertEquals(Optional.empty(), explain(t23("Probe", -2, 1, -1, 2, 5), T23.features(), value(8), value(38)));
        var other = new DiffCase("other", pinned.source().replace("BigInteger.valueOf(3)", "BigInteger.valueOf(4)"),
                "Probe", "m", pinned.args());
        assertEquals(Optional.empty(), explain(other, T23.features(), value(8), value(38)));
    }

    @Test
    void aRuleExplainsOnlyItsExactInconclusiveReason() {
        var features = Set.of(G0.Feature.WHILE_BREAK_WITH_IF_UPDATE);
        var any = t23("Probe", 0, 3);
        assertEquals(Optional.of("T21"), explain(any, features, value(3), new Observation.Inconclusive("budget exhausted")));
        assertEquals(Optional.empty(), explain(any, features, value(3), new Observation.Inconclusive("stack overflow")));
        assertEquals(Optional.empty(), explain(any, Set.of(), value(3), new Observation.Inconclusive("budget exhausted")));
        assertEquals(Optional.empty(), explain(any, features, value(3), value(4)), "a rule never explains a conclusive side");
        assertThrows(IllegalArgumentException.class, () -> new KnownDivergences.Rule("T0", "no reason",
                features, Observation.Value.class, null));
    }

    /** ADR-063 D13 (r6): the T13 rule, for the #146 growth that Jazzer found, follows the same contract. */
    @Test
    void theT13RuleExplainsOnlyAnExhaustedBudget() {
        var deep = Set.of(G0.Feature.DEEP_COMPARISON);
        var any = t23("Probe", 0, 3);
        var budget = new Observation.Inconclusive("budget exhausted");
        assertEquals(Optional.of("T13"), explain(any, deep, value(-5), budget));
        assertEquals(Optional.empty(), explain(any, deep, value(-5), value(4)), "a wrong value is not T13");
        assertEquals(Optional.empty(), explain(any, deep, value(-5),
                new Observation.Crash("compiler: java.lang.OutOfMemoryError: Java heap space")), "a crash is never allowed");
        assertEquals(Optional.empty(), explain(any, deep, new Observation.Failure("x", List.of()), budget),
                "the JVM side must return a value");
        // A program with both shapes matches both rules, so neither explains it.
        assertEquals(Optional.empty(), explain(any, Set.of(G0.Feature.DEEP_COMPARISON, G0.Feature.WHILE_BREAK_WITH_IF_UPDATE),
                value(-5), budget));
    }
}
