package org.julclang.difftest.gen;

import org.julclang.difftest.DiffCase;
import org.julclang.difftest.DiffConfig;
import org.julclang.difftest.DiffRunner;
import org.julclang.difftest.Observation;
import org.julclang.difftest.RegressionStore;
import org.julclang.difftest.Verdict;

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
 *     program, the JVM kind and the exact inconclusive reason. A rule is weaker than a pin, since
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
     * One known, unresolved divergence whose UPLC side never finishes: {@code uplcReason} is the exact
     * {@link Observation.Inconclusive} reason.
     */
    public record Rule(String id, String description, Set<G0.Feature> requires, Class<? extends Observation> jvm,
                       String uplcReason) {

        public Rule {
            if (uplcReason == null) throw new IllegalArgumentException(id + ": a rule names its exact inconclusive reason");
        }

        boolean matches(Set<G0.Feature> features, Observation jvmObservation, Observation uplcObservation) {
            return features.containsAll(requires) && jvm.isInstance(jvmObservation)
                    && uplcObservation instanceof Observation.Inconclusive i && i.reason().equals(uplcReason);
        }
    }

    /** None at present: T21, the last, was fixed by #254. */
    public static final List<Rule> RULES = List.of();

    private static final Pattern CLASS_DECLARATION = Pattern.compile("\\bclass\\s+(\\w+)");

    private final List<Rule> rules;
    private final List<RegressionStore.SavedCase> pins;

    private KnownDivergences(List<Rule> rules, List<RegressionStore.SavedCase> pins) {
        this.rules = rules;
        this.pins = pins;
    }

    /** {@link #RULES}, and the saved cases that pin a conclusive divergence at some optimization level. */
    public static KnownDivergences withPins(List<RegressionStore.SavedCase> saved) {
        return of(RULES, saved);
    }

    /** These rules, and the saved cases that pin a conclusive divergence at some optimization level. */
    public static KnownDivergences of(List<Rule> rules, List<RegressionStore.SavedCase> saved) {
        return new KnownDivergences(List.copyOf(rules),
                saved.stream().filter(KnownDivergences::pinsAConclusiveDivergence).toList());
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
        var ids = new TreeSet<String>();
        if (result.uplc() instanceof Observation.Inconclusive) {
            for (var rule : rules) if (rule.matches(features, result.jvm(), result.uplc())) ids.add(rule.id());
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
