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
     * {@link Observation.Inconclusive} reason. A rule with a positive {@code minScriptBytes} also needs the
     * configuration's script to be at least that large, as evidence of its mechanism (ADR-063 D6, r6): the
     * comparison itself never uses the size (D2).
     */
    public record Rule(String id, String description, Set<G0.Feature> requires, Class<? extends Observation> jvm,
                       String uplcReason, int minScriptBytes) {

        public Rule {
            if (uplcReason == null) throw new IllegalArgumentException(id + ": a rule names its exact inconclusive reason");
            if (minScriptBytes < 0) throw new IllegalArgumentException(id + ": a negative script size");
        }

        /** A rule with no size condition. */
        public Rule(String id, String description, Set<G0.Feature> requires, Class<? extends Observation> jvm,
                    String uplcReason) {
            this(id, description, requires, jvm, uplcReason, 0);
        }

        boolean matches(Set<G0.Feature> features, DiffRunner.Result result) {
            return features.containsAll(requires) && jvm.isInstance(result.jvm())
                    && result.uplc() instanceof Observation.Inconclusive i && i.reason().equals(uplcReason)
                    && (minScriptBytes == 0
                        || result.measurement() != null && result.measurement().scriptBytes() >= minScriptBytes);
        }
    }

    /**
     * The smallest script the T13 rule explains. #146 doubles the script with each nested {@code compareTo}
     * operand, and evaluation can double with it, depending on the branches taken. In about 6,500 sampled
     * G0 r1 programs without {@link G0.Feature#DEEP_COMPARISON} the largest script was 26,193 bytes, and a T21
     * program with a deep comparison that never terminates was 44,039 bytes. The smallest T13 exhaustion seen, a program that
     * finishes with ten times the budget, was 90,756 bytes.
     */
    public static final int T13_MIN_SCRIPT_BYTES = 64 * 1024;

    public static final List<Rule> RULES = List.of(
            new Rule("T21", "a while loop with break and an if that updates a variable never terminates on UPLC",
                    Set.of(G0.Feature.WHILE_BREAK_WITH_IF_UPDATE), Observation.Value.class, "budget exhausted"),
            new Rule("T13", "nested compareTo operands double the script per level (#146) and exhaust the budget",
                    Set.of(G0.Feature.DEEP_COMPARISON), Observation.Value.class, "budget exhausted",
                    T13_MIN_SCRIPT_BYTES));

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
        var ids = new TreeSet<String>();
        if (result.uplc() instanceof Observation.Inconclusive) {
            for (var rule : RULES) if (rule.matches(features, result)) ids.add(rule.id());
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
