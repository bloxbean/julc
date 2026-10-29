package org.julclang.difftest.gen;

import org.julclang.difftest.DiffCase;
import org.julclang.difftest.DiffConfig;
import org.julclang.difftest.DiffRunner;
import org.julclang.difftest.Observation;
import org.julclang.difftest.RegressionStore;
import org.julclang.difftest.UplcExecutor;
import org.julclang.difftest.Verdict;

import java.util.HashMap;
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

    /** What a rule's confirmation rerun must show (ADR-063 D6, r6). */
    public enum Confirmation {
        /** No rerun. */
        NONE,
        /** The rerun finishes and agrees with the JVM, value and traces: the run is only expensive (T13). */
        AGREES_WITH_JVM,
        /** The rerun still ends with the rule's inconclusive reason: the run does not terminate (T21). */
        STILL_INCONCLUSIVE
    }

    /**
     * One known, unresolved divergence whose UPLC side never finishes: {@code uplcReason} is the exact
     * {@link Observation.Inconclusive} reason. A rule with a {@code confirmation} also reruns the configuration
     * with {@code confirmationFactor} times the budget and explains it only if the rerun shows what the
     * confirmation names (ADR-063 D6, r6). That separates a run that is only expensive from one that never
     * terminates, and both from one that computes a wrong value. The configuration's verdict stays inconclusive
     * (D3, H5).
     */
    public record Rule(String id, String description, Set<G0.Feature> requires, Class<? extends Observation> jvm,
                       String uplcReason, Confirmation confirmation, int confirmationFactor) {

        public Rule {
            if (uplcReason == null) throw new IllegalArgumentException(id + ": a rule names its exact inconclusive reason");
            if (confirmation == null) throw new IllegalArgumentException(id + ": a rule names its confirmation, or NONE");
            if ((confirmation == Confirmation.NONE) != (confirmationFactor == 0) || confirmationFactor < 0) {
                throw new IllegalArgumentException(id + ": a confirming rule needs a positive factor, and only it");
            }
        }

        /** A rule without a confirmation rerun. */
        public Rule(String id, String description, Set<G0.Feature> requires, Class<? extends Observation> jvm,
                    String uplcReason) {
            this(id, description, requires, jvm, uplcReason, Confirmation.NONE, 0);
        }

        boolean matches(Set<G0.Feature> features, DiffRunner.Result result, Rerun rerun) {
            if (!(features.containsAll(requires) && jvm.isInstance(result.jvm())
                    && result.uplc() instanceof Observation.Inconclusive i && i.reason().equals(uplcReason))) {
                return false;
            }
            if (confirmation == Confirmation.NONE) return true;
            if (rerun == null) return false;
            var observation = rerun.run(confirmationFactor).observation();
            return switch (confirmation) {
                case AGREES_WITH_JVM -> Verdict.compare(result.jvm(), observation).kind() == Verdict.Kind.AGREE;
                case STILL_INCONCLUSIVE -> observation instanceof Observation.Inconclusive again
                        && again.reason().equals(uplcReason);
                case NONE -> true;
            };
        }
    }

    /**
     * How many times the budget the confirmation reruns get. #146 doubles the script with each nested
     * {@code compareTo} operand, and evaluation can double with it, depending on the branches taken. A G0 r2
     * program at the nesting bound needed 53 times the memory budget; the saved T13 reproducer, 1.8 to 3.8 times.
     */
    public static final int CONFIRMATION_FACTOR = 100;

    public static final List<Rule> RULES = List.of(
            new Rule("T21", "a while loop with break and an if that updates a variable never terminates on UPLC",
                    Set.of(G0.Feature.WHILE_BREAK_WITH_IF_UPDATE), Observation.Value.class, "budget exhausted",
                    Confirmation.STILL_INCONCLUSIVE, CONFIRMATION_FACTOR),
            new Rule("T13", "nested compareTo operands double the script per level (#146) and exhaust the budget",
                    Set.of(G0.Feature.DEEP_COMPARISON), Observation.Value.class, "budget exhausted",
                    Confirmation.AGREES_WITH_JVM, CONFIRMATION_FACTOR));

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
            // Rules that confirm with the same factor share one rerun, so a program with both T13's and T21's
            // shapes is rerun once, and the rerun picks one of them.
            var reruns = new HashMap<Long, UplcExecutor.Execution>();
            Rerun once = rerun == null ? null : factor -> reruns.computeIfAbsent(factor, rerun::run);
            for (var rule : RULES) if (rule.matches(features, result, once)) ids.add(rule.id());
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
