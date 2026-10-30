package org.julclang.difftest.gen;

import org.julclang.difftest.DiffCase;
import org.julclang.difftest.DiffConfig;
import org.julclang.difftest.DiffRunner;
import org.julclang.difftest.Observation;
import org.julclang.difftest.RegressionStore;
import org.julclang.difftest.UplcExecutor;
import org.julclang.difftest.Verdict;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The allowed-divergence registry for generated programs (ADR-063 D6). It explains a generated
 * mismatch in one of two ways, by what its UPLC side produced:
 * <ul>
 * <li>An inconclusive UPLC side is explained by a {@link Rule}: a structural condition on the
 *     program, the JVM kind and the exact inconclusive reason, and a confirmation rerun with a larger budget:
 *     for T13 it must agree with the JVM, for T21 it must still be inconclusive. A rule is weaker than a pin, since
 *     another cause with the same shape and reason passes as it, but an inconclusive case counts
 *     towards no quota (D9).</li>
 * <li>A conclusive UPLC side, a value or a failure, is explained only by a <em>pin</em>: a saved
 *     {@code DIVERGE} case with the same program, method and arguments, whose pinned observations
 *     the result matches exactly, traces included. Any other wrong value fails the run and is
 *     written out for triage.</li>
 * </ul>
 * Remove a rule, or change a pin's expectation, when its catalogue entry is resolved; runs report
 * how often each entry matched, so one that stops matching is visible.
 */
public final class KnownDivergences {

    /**
     * Runs one configuration of a case again with {@code factor} times the budget (ADR-063 D6, r6), as
     * {@link DiffRunner#rerun} does. The caller records each rerun as the evidence of the explanation (H2).
     */
    @FunctionalInterface
    public interface Rerun {
        UplcExecutor.Execution run(long factor);
    }

    /** What a rule's confirmation reruns must show (ADR-063 D6, r6). */
    public enum Confirmation {
        /** No rerun. */
        NONE,
        /**
         * A rerun finishes and agrees with the JVM, value and traces: the run is only expensive (T13). Any rung of
         * the shared ladder counts, since agreement with less budget than the rule's own rungs shows the same.
         */
        AGREES_WITH_JVM,
        /**
         * The last rerun run still ends with the rule's inconclusive reason: the run does not terminate within
         * that many times the budget (T21).
         */
        STILL_INCONCLUSIVE
    }

    /**
     * One known, unresolved divergence whose UPLC side never finishes: {@code uplcReason} is the exact
     * {@link Observation.Inconclusive} reason. A rule with a {@code confirmation} also needs reruns of the
     * configuration with more budget, on the rungs {@code confirmationFactors} (ADR-063 D6, r6). That separates a
     * run that is only expensive from one that does not terminate, and both from one that computes a wrong value.
     * The configuration's verdict stays inconclusive (D3, H5).
     */
    public record Rule(String id, String description, Set<G0.Feature> requires, Class<? extends Observation> jvm,
                       String uplcReason, Confirmation confirmation, List<Integer> confirmationFactors) {

        public Rule {
            if (uplcReason == null) throw new IllegalArgumentException(id + ": a rule names its exact inconclusive reason");
            if (confirmation == null) throw new IllegalArgumentException(id + ": a rule names its confirmation, or NONE");
            confirmationFactors = List.copyOf(confirmationFactors);
            if ((confirmation == Confirmation.NONE) != confirmationFactors.isEmpty()) {
                throw new IllegalArgumentException(id + ": a confirming rule needs rungs, and only it");
            }
            for (int k = 0; k < confirmationFactors.size(); k++) {
                if (confirmationFactors.get(k) < 1 || k > 0 && confirmationFactors.get(k) <= confirmationFactors.get(k - 1)) {
                    throw new IllegalArgumentException(id + ": rungs are positive and ascending: " + confirmationFactors);
                }
            }
        }

        /** A rule without a confirmation rerun. */
        public Rule(String id, String description, Set<G0.Feature> requires, Class<? extends Observation> jvm,
                    String uplcReason) {
            this(id, description, requires, jvm, uplcReason, Confirmation.NONE, List.of());
        }

        /** The shape, the JVM kind and the exact reason: everything but the confirmation. */
        boolean fits(Set<G0.Feature> features, DiffRunner.Result result) {
            return features.containsAll(requires) && jvm.isInstance(result.jvm())
                    && result.uplc() instanceof Observation.Inconclusive i && i.reason().equals(uplcReason);
        }

        /** Whether the reruns made, in ascending order of factor, confirm this rule. */
        boolean confirmedBy(DiffRunner.Result result, List<Rung> rungs) {
            return switch (confirmation) {
                case NONE -> true;
                case AGREES_WITH_JVM -> rungs.stream().anyMatch(rung ->
                        Verdict.compare(result.jvm(), rung.observation()).kind() == Verdict.Kind.AGREE);
                case STILL_INCONCLUSIVE -> !rungs.isEmpty()
                        && rungs.getLast().observation() instanceof Observation.Inconclusive again
                        && again.reason().equals(uplcReason);
            };
        }
    }

    /** One confirmation rerun: its factor and what it produced. */
    record Rung(int factor, Observation observation) {
    }

    /**
     * T21's only rung. A T21 run does not terminate, so a small rung confirms it as well as a large one, and a
     * still-exhausted rerun costs evaluation up to its whole budget (review round r7 of #253, F16).
     */
    public static final int T21_RUNG = 10;
    /**
     * T13's first rung. #146 doubles the script with each nested {@code compareTo} operand, and evaluation can
     * double with it, depending on the branches taken; the saved T13 reproducer needs 1.8 to 3.8 times the memory
     * budget, and most T13 runs in fuzzing fit within this.
     */
    public static final int CONFIRMATION_FACTOR = 100;
    /**
     * T13's second rung, used only when the first is still exhausted. Loops multiply the cost: a G0 r2 program with
     * two nested {@code while} loops around a deep comparison at the nesting bound needed 107 times the budget.
     */
    public static final int T13_SECOND_RUNG = 1000;

    public static final List<Rule> RULES = List.of(
            new Rule("T21", "a while loop with break and an if that updates a variable never terminates on UPLC",
                    Set.of(G0.Feature.WHILE_BREAK_WITH_IF_UPDATE), Observation.Value.class, "budget exhausted",
                    Confirmation.STILL_INCONCLUSIVE, List.of(T21_RUNG)),
            new Rule("T13", "nested compareTo operands double the script per level (#146) and exhaust the budget",
                    Set.of(G0.Feature.DEEP_COMPARISON), Observation.Value.class, "budget exhausted",
                    Confirmation.AGREES_WITH_JVM, List.of(CONFIRMATION_FACTOR, T13_SECOND_RUNG)));

    private static final Pattern CLASS_DECLARATION = Pattern.compile("\\bclass\\s+(\\w+)");

    private final List<RegressionStore.SavedCase> pins;

    private KnownDivergences(List<RegressionStore.SavedCase> pins) {
        this.pins = pins;
    }

    /** The rules, and the saved cases that pin a conclusive divergence at some optimization level. */
    public static KnownDivergences withPins(List<RegressionStore.SavedCase> saved) {
        return new KnownDivergences(saved.stream().filter(KnownDivergences::pinsAConclusiveDivergence).toList());
    }

    private static boolean pinsAConclusiveDivergence(RegressionStore.SavedCase saved) {
        return isConclusiveDivergence(saved.expectation())
                || saved.overrides().values().stream().anyMatch(KnownDivergences::isConclusiveDivergence);
    }

    private static boolean isConclusiveDivergence(RegressionStore.Expectation expectation) {
        return expectation.kind() == Verdict.Kind.MISMATCH && !(expectation.uplc() instanceof Observation.Inconclusive);
    }

    /** The saved cases this registry pins. */
    public List<RegressionStore.SavedCase> pins() {
        return pins;
    }

    /**
     * The catalogue ID that explains this configuration's mismatch, if exactly one does: a rule for an
     * inconclusive UPLC side, an exact pin otherwise.
     */
    public Optional<String> explain(DiffCase diffCase, DiffConfig config, Set<G0.Feature> features, DiffRunner.Result result) {
        return explain(diffCase, config, features, result, null);
    }

    /**
     * As {@link #explain(DiffCase, DiffConfig, Set, DiffRunner.Result)}, with the rerun a confirming rule needs.
     * Without one, such a rule explains nothing.
     */
    public Optional<String> explain(DiffCase diffCase, DiffConfig config, Set<G0.Feature> features, DiffRunner.Result result,
                                    Rerun rerun) {
        var ids = new TreeSet<String>();
        if (result.uplc() instanceof Observation.Inconclusive) {
            var fitting = RULES.stream().filter(rule -> rule.fits(features, result)).toList();
            // One ladder for all the fitting rules: each rung is run once, in ascending order, until one does not
            // end with the configuration's reason. So a program with both T13's and T21's shapes is decided by
            // the same reruns, and at most one of the two rules is confirmed.
            var rungs = new ArrayList<Rung>();
            if (rerun != null) {
                var factors = new TreeSet<Integer>();
                for (var rule : fitting) factors.addAll(rule.confirmationFactors());
                for (int factor : factors) {
                    var observation = rerun.run(factor).observation();
                    rungs.add(new Rung(factor, observation));
                    if (!observation.equals(result.uplc())) break;
                }
            }
            for (var rule : fitting) {
                if (rule.confirmation() == Confirmation.NONE || rerun != null && rule.confirmedBy(result, rungs)) {
                    ids.add(rule.id());
                }
            }
        } else {
            String program = program(diffCase);
            for (var pin : pins) {
                var saved = pin.diffCase();
                var expectation = pin.expectationFor(config.level());
                if (expectation.kind() == Verdict.Kind.MISMATCH && saved.method().equals(diffCase.method())
                        && saved.args().equals(diffCase.args()) && program(saved).equals(program)
                        && expectation.check(result) == null) {
                    ids.add(expectation.detail());
                }
            }
        }
        return ids.size() == 1 ? Optional.of(ids.getFirst()) : Optional.empty();
    }

    /** The source with its class renamed, so that a case compiled under another class name still matches. */
    static String program(DiffCase diffCase) {
        Matcher declaration = CLASS_DECLARATION.matcher(diffCase.source());
        while (declaration.find()) {
            if (declaration.group(1).equals(diffCase.className())) {
                return diffCase.source().substring(0, declaration.start(1)) + "_"
                        + diffCase.source().substring(declaration.end(1));
            }
        }
        return diffCase.source();
    }
}
