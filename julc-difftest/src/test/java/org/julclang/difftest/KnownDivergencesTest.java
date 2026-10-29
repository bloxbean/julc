package org.julclang.difftest;

import org.julclang.compiler.OptimizationLevel;
import org.julclang.core.PlutusData;
import org.julclang.difftest.gen.G0;
import org.julclang.difftest.gen.G0Enumeration;
import org.julclang.difftest.gen.KnownDivergences;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Path;
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
        var hangs = fixed(BUDGET, new ArrayList<>());
        assertEquals(Optional.of("T21"), explain(features, value(3), BUDGET, hangs));
        assertEquals(Optional.empty(), explain(features, value(3), new Observation.Inconclusive("stack overflow"), hangs));
        assertEquals(Optional.empty(), explain(Set.of(), value(3), BUDGET, hangs));
        assertEquals(Optional.empty(), explain(features, value(3), value(4), hangs), "a rule never explains a conclusive side");
        assertThrows(IllegalArgumentException.class, () -> new KnownDivergences.Rule("T0", "no reason",
                features, Observation.Value.class, null));
    }

    /**
     * ADR-063 D6 (r6, round r5 of #253): T21 explains an exhausted budget only if its rerun with
     * {@link KnownDivergences#CONFIRMATION_FACTOR} times the budget is still exhausted, the opposite of T13.
     */
    @Test
    void theT21RuleExplainsOnlyARunThatStillDoesNotFinish() {
        var t21 = Set.of(G0.Feature.WHILE_BREAK_WITH_IF_UPDATE);
        var factors = new ArrayList<Long>();
        assertEquals(Optional.of("T21"), explain(t21, value(3), BUDGET, fixed(BUDGET, factors)));
        assertEquals(Optional.empty(), explain(t21, value(3), BUDGET, fixed(value(3), factors)), "it finishes: not T21");
        assertEquals(Optional.empty(), explain(t21, value(3), BUDGET, fixed(value(4), factors)), "it finishes wrong");
        assertEquals(Optional.empty(), explain(t21, value(3), BUDGET,
                fixed(new Observation.Inconclusive("stack overflow"), factors)), "another reason");
        assertEquals(Optional.empty(), explain(t21, value(3), BUDGET,
                fixed(new Observation.Crash("vm: java.lang.OutOfMemoryError"), factors)), "a crash");
        assertEquals(List.of(100L, 100L, 100L, 100L, 100L), factors);
        assertEquals(Optional.empty(), KNOWN.explain(t23("Probe", 0, 3), CONFIG, t21,
                new DiffRunner.Result(value(3), BUDGET, Verdict.compare(value(3), BUDGET))), "no rerun, no T21");
    }

    private static final Path REGRESSIONS = Path.of("src/test/resources/regressions");
    private static final DiffRunner RUNNER = DiffRunner.standard();
    private static final Set<G0.Feature> DEEP = Set.of(G0.Feature.DEEP_COMPARISON);
    private static final Observation BUDGET = new Observation.Inconclusive("budget exhausted");

    /** A rerun that returns a fixed observation, and remembers the factors it was asked for. */
    private static KnownDivergences.Rerun fixed(Observation observation, List<Long> factors) {
        return factor -> {
            factors.add(factor);
            return new UplcExecutor.Execution(observation, null);
        };
    }

    private static Optional<String> explain(Set<G0.Feature> features, Observation jvm, Observation uplc,
                                            KnownDivergences.Rerun rerun) {
        return KNOWN.explain(t23("Probe", 0, 3), CONFIG, features, new DiffRunner.Result(jvm, uplc, Verdict.compare(jvm, uplc)),
                rerun);
    }

    /**
     * ADR-063 D6 (r6, round r4 of #253): T13 explains an exhausted budget only when a rerun with
     * {@link KnownDivergences#CONFIRMATION_FACTOR} times the budget finishes and agrees with the JVM. The saved
     * reproducer is such a case at every level.
     */
    @Test
    void theSavedT13ReproducerIsExplainedByItsConfirmationRerun() {
        var saved = RegressionStore.read(REGRESSIONS.resolve("diverge-t13-nested-compare-to-exhausts-budget.diffcase"));
        var diffCase = saved.diffCase();
        var report = RUNNER.run(diffCase);
        for (var entry : report.results().entrySet()) {
            var config = entry.getKey();
            var result = entry.getValue();
            assertEquals(BUDGET, result.uplc(), config.toString());
            var reruns = new ArrayList<UplcExecutor.Execution>();
            KnownDivergences.Rerun rerun = factor -> {
                assertEquals(KnownDivergences.CONFIRMATION_FACTOR, factor);
                var execution = RUNNER.rerun(diffCase, config, factor);
                reruns.add(execution);
                return execution;
            };
            assertEquals(Optional.of("T13"), KNOWN.explain(diffCase, config, DEEP, result, rerun), config.toString());
            assertEquals(List.of(value(2)), reruns.stream().map(UplcExecutor.Execution::observation).toList());
            // The verdict itself stays inconclusive (D3, H5), and without a rerun nothing confirms it.
            assertEquals(Verdict.Kind.INCONCLUSIVE, result.verdict().kind());
            assertEquals(Optional.empty(), KNOWN.explain(diffCase, config, DEEP, result), "no rerun, no T13");
        }
    }

    @Test
    void aRerunThatDisagreesWithTheJvmIsNotT13() {
        var factors = new ArrayList<Long>();
        assertEquals(Optional.empty(), explain(DEEP, value(-5), BUDGET, fixed(value(4), factors)), "another value");
        assertEquals(Optional.empty(), explain(DEEP, value(-5), BUDGET, fixed(value(-5, "t"), factors)), "another trace");
        assertEquals(Optional.empty(), explain(DEEP, value(-5), BUDGET, fixed(new Observation.Failure(null, List.of()), factors)),
                "a failure");
        assertEquals(Optional.empty(), explain(DEEP, value(-5), BUDGET,
                fixed(new Observation.Crash("vm: java.lang.OutOfMemoryError"), factors)), "a crash");
        assertEquals(Optional.of("T13"), explain(DEEP, value(-5), BUDGET, fixed(value(-5), factors)), "the JVM's value");
        assertEquals(List.of(100L, 100L, 100L, 100L, 100L), factors);
    }

    @Test
    void aRerunThatIsStillExhaustedIsNotT13() {
        var factors = new ArrayList<Long>();
        assertEquals(Optional.empty(), explain(DEEP, value(-5), BUDGET, fixed(BUDGET, factors)));
        assertEquals(List.of(100L, 1000L), factors, "both rungs, and nothing more");
    }

    /** A rerun that is still exhausted below {@code finishesAt} times the budget and returns {@code then} from there. */
    private static KnownDivergences.Rerun ladder(long finishesAt, Observation then, List<Long> factors) {
        return factor -> {
            factors.add(factor);
            return new UplcExecutor.Execution(factor >= finishesAt ? then : BUDGET, null);
        };
    }

    /**
     * Review round r5 of #253, F15: T13 climbs a ladder. A run still exhausted at 100 times the budget is rerun at
     * 1000 times, and T13 explains it if that agrees with the JVM. T21 needs the last rung run to be exhausted.
     */
    @Test
    void t13ClimbsToItsSecondRungAndT21NeedsTheLastRungExhausted() {
        var both = Set.of(G0.Feature.DEEP_COMPARISON, G0.Feature.WHILE_BREAK_WITH_IF_UPDATE);
        var t21 = Set.of(G0.Feature.WHILE_BREAK_WITH_IF_UPDATE);
        var factors = new ArrayList<Long>();
        assertEquals(Optional.of("T13"), explain(DEEP, value(-5), BUDGET, ladder(1000, value(-5), factors)));
        assertEquals(List.of(100L, 1000L), factors);
        factors.clear();
        assertEquals(Optional.empty(), explain(DEEP, value(-5), BUDGET, ladder(1000, value(4), factors)), "wrong at 1000x");
        assertEquals(Optional.of("T13"), explain(both, value(-5), BUDGET, ladder(1000, value(-5), factors)),
                "both shapes, finishing at 1000x: T13 only");
        assertEquals(Optional.of("T21"), explain(both, value(-5), BUDGET, ladder(Long.MAX_VALUE, value(-5), factors)),
                "both shapes, exhausted at both rungs: T21 only");
        assertEquals(Optional.empty(), explain(both, value(-5), BUDGET, ladder(1000, value(4), factors)),
                "both shapes, wrong at 1000x: neither");
        factors.clear();
        // Without T13's marker there is one rung: a run that would finish at 1000x is still T21 within 100x.
        assertEquals(Optional.of("T21"), explain(t21, value(-5), BUDGET, ladder(1000, value(-5), factors)));
        assertEquals(List.of(100L), factors);
    }

    /**
     * The input of F15, saved as a reproducer: two nested {@code while} loops around a deep comparison. At
     * {@code NONE} it needs about 107 times the budget, so only T13's second rung confirms it.
     */
    @Test
    void theNestedLoopsReproducerNeedsTheSecondRungAtNone() {
        var diffCase = RegressionStore.read(REGRESSIONS.resolve("diverge-t13-nested-loops-needs-second-rung.diffcase")).diffCase();
        var report = RUNNER.run(diffCase);
        assertEquals(value(-5), report.jvm());
        for (var entry : report.results().entrySet()) {
            var config = entry.getKey();
            assertEquals(BUDGET, entry.getValue().uplc(), config.toString());
            var factors = new ArrayList<Long>();
            var observations = new ArrayList<Observation>();
            KnownDivergences.Rerun rerun = factor -> {
                var execution = RUNNER.rerun(diffCase, config, factor);
                factors.add(factor);
                observations.add(execution.observation());
                return execution;
            };
            assertEquals(Optional.of("T13"), KNOWN.explain(diffCase, config, DEEP, entry.getValue(), rerun), config.toString());
            if (config.level() == OptimizationLevel.NONE) {
                assertEquals(List.of(100L, 1000L), factors);
                assertEquals(List.of(BUDGET, value(-5)), observations);
            } else {
                assertEquals(List.of(100L), factors, config.toString());
                assertEquals(List.of(value(-5)), observations, config.toString());
            }
        }
    }

    @Test
    void theT13RuleAsksForNoRerunOutsideItsShape() {
        var factors = new ArrayList<Long>();
        var rerun = fixed(value(-5), factors);
        assertEquals(Optional.empty(), explain(Set.of(), value(-5), BUDGET, rerun), "no marker");
        assertEquals(Optional.empty(), explain(DEEP, value(-5), value(4), rerun), "a conclusive side");
        assertEquals(Optional.empty(), explain(DEEP, new Observation.Failure(null, List.of()), BUDGET, rerun), "a JVM failure");
        assertEquals(Optional.empty(), explain(DEEP, value(-5), new Observation.Inconclusive("JVM timeout after 10000 ms"), rerun),
                "another reason");
        assertEquals(List.of(), factors);
    }

    /**
     * A T21 non-termination is still exhausted at a hundred and a thousand times the budget, so T13 does not
     * explain it, even when the program also carries T13's marker; T21 alone does, from the same reruns.
     */
    @Test
    void aT21ExhaustionIsNotExplainedByT13() {
        var saved = RegressionStore.read(REGRESSIONS.resolve("diverge-t21-while-break-first-hangs.diffcase"));
        var diffCase = saved.diffCase();
        var config = new DiffConfig(OptimizationLevel.PV11_SAFE, "Java");
        var result = RUNNER.run(diffCase).results().get(config);
        assertEquals(BUDGET, result.uplc());
        var reruns = new ArrayList<Observation>();
        KnownDivergences.Rerun rerun = factor -> {
            var execution = RUNNER.rerun(diffCase, config, factor);
            reruns.add(execution.observation());
            return execution;
        };
        assertEquals(Optional.empty(), KNOWN.explain(diffCase, config, DEEP, result, rerun), "T13's shape only");
        assertEquals(List.of(BUDGET, BUDGET), reruns, "both of T13's rungs");
        assertEquals(Optional.of("T21"), KNOWN.explain(diffCase, config,
                Set.of(G0.Feature.DEEP_COMPARISON, G0.Feature.WHILE_BREAK_WITH_IF_UPDATE), result, rerun), "both shapes");
        assertEquals(List.of(BUDGET, BUDGET, BUDGET, BUDGET), reruns, "the same two rungs, shared by both rules");
    }

    /**
     * Review round r4 of #253, F12: a program with both markers whose T21 {@code if} is never taken, so it does
     * not hang, and whose result includes the saved T13 expression. It exhausts the budget only by cost: the rerun
     * returns the JVM's value, so it is T13 alone at every level.
     */
    @Test
    void aProgramWithBothShapesThatFinishesIsT13Only() {
        var t13 = RegressionStore.read(REGRESSIONS.resolve("diverge-t13-nested-compare-to-exhausts-budget.diffcase")).diffCase();
        String expression = t13.source().substring(t13.source().indexOf("return ") + 7, t13.source().lastIndexOf(';'));
        String source = """
                import java.math.BigInteger;
                import org.julclang.core.types.JulcList;

                public class Probe {
                    public static BigInteger m(BigInteger a, BigInteger b, JulcList<BigInteger> xs) {
                        BigInteger v0 = a;
                        long i = 0;
                        while (i < 1) {
                            if (b.compareTo(BigInteger.ZERO) < 0) { break; }
                            if (a.compareTo(BigInteger.TEN) > 0) { v0 = b; }
                            i = i + 1;
                        }
                        return v0.subtract(a).add(%s);
                    }
                }
                """.formatted(expression);
        var diffCase = new DiffCase("both shapes, finishing", source, "Probe", "m", t13.args());
        var both = Set.of(G0.Feature.DEEP_COMPARISON, G0.Feature.WHILE_BREAK_WITH_IF_UPDATE);
        var report = RUNNER.run(diffCase);
        assertEquals(value(2), report.jvm());
        for (var entry : report.results().entrySet()) {
            var config = entry.getKey();
            assertEquals(BUDGET, entry.getValue().uplc(), config.toString());
            var reruns = new ArrayList<Observation>();
            KnownDivergences.Rerun rerun = factor -> {
                var execution = RUNNER.rerun(diffCase, config, factor);
                reruns.add(execution.observation());
                return execution;
            };
            assertEquals(Optional.of("T13"), KNOWN.explain(diffCase, config, both, entry.getValue(), rerun), config.toString());
            assertEquals(List.of(value(2)), reruns, config.toString());
        }
    }

    @Test
    void aConfirmationHasAPositiveFactor() {
        for (var invalid : List.<Object[]>of(
                new Object[]{KnownDivergences.Confirmation.AGREES_WITH_JVM, List.of(-1)},
                new Object[]{KnownDivergences.Confirmation.STILL_INCONCLUSIVE, List.of()},
                new Object[]{KnownDivergences.Confirmation.AGREES_WITH_JVM, List.of(1000, 100)},
                new Object[]{KnownDivergences.Confirmation.NONE, List.of(100)})) {
            @SuppressWarnings("unchecked")
            var rungs = (List<Integer>) invalid[1];
            assertThrows(IllegalArgumentException.class, () -> new KnownDivergences.Rule("T0", "invalid", Set.of(),
                    Observation.Value.class, "budget exhausted", (KnownDivergences.Confirmation) invalid[0], rungs));
        }
        assertThrows(IllegalArgumentException.class, () -> UplcExecutor.budgetTimes(0));
    }
}
