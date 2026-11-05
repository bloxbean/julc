package org.julclang.difftest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Coverage accounting for a campaign (ADR-063 D9, H5). Each case is recorded with the dimensions
 * its program has and the verdict of every configuration. A dimension counts towards its quota only
 * from conclusive cases, those that agree at every configuration: a case that is inconclusive
 * anywhere, or passes only as an allowed divergence, adds to its own tally and to no quota. The
 * dimensions are syntactic: they say what a program contains, not that a loop iterated or a branch
 * was taken; the exhaustive corpus checks that (D7).
 */
public final class Coverage {

    /** How a case ended, over all its configurations. */
    public enum Outcome {
        /** Every configuration agreed. */
        CONCLUSIVE,
        /** No configuration was inconclusive, and every mismatch was an allowed divergence. */
        ALLOWED_DIVERGENCE,
        /** Some configuration was inconclusive; explained or not, it is no evidence (H5). */
        INCONCLUSIVE
    }

    private int cases;
    private final Map<Outcome, Integer> outcomes = new EnumMap<>(Outcome.class);
    private final Map<String, Integer> conclusive = new TreeMap<>();
    private final Map<String, Map<Outcome, Integer>> byConfiguration = new TreeMap<>();

    /**
     * Records one passing case: its dimensions and, per configuration, whether it agreed, was
     * inconclusive or was an allowed divergence. A case with an unexplained mismatch fails the run
     * before it is recorded.
     */
    public synchronized void record(Set<String> dimensions, Map<DiffConfig, Verdict.Kind> verdicts) {
        cases++;
        Outcome outcome = Outcome.CONCLUSIVE;
        for (var entry : verdicts.entrySet()) {
            Outcome configOutcome = switch (entry.getValue()) {
                case AGREE -> Outcome.CONCLUSIVE;
                case INCONCLUSIVE -> Outcome.INCONCLUSIVE;
                default -> Outcome.ALLOWED_DIVERGENCE;
            };
            byConfiguration.computeIfAbsent(entry.getKey().toString(), k -> new EnumMap<>(Outcome.class))
                    .merge(configOutcome, 1, Integer::sum);
            if (configOutcome.compareTo(outcome) > 0) outcome = configOutcome;
        }
        outcomes.merge(outcome, 1, Integer::sum);
        if (outcome == Outcome.CONCLUSIVE) dimensions.forEach(d -> conclusive.merge(d, 1, Integer::sum));
    }

    public synchronized int cases() {
        return cases;
    }

    public synchronized int count(Outcome outcome) {
        return outcomes.getOrDefault(outcome, 0);
    }

    /** The conclusive cases that have this dimension. */
    public synchronized int conclusive(String dimension) {
        return conclusive.getOrDefault(dimension, 0);
    }

    /** One message per required dimension whose conclusive cases are below {@code minPercent} of all cases. */
    public synchronized List<String> quotaFailures(Collection<String> required, double minPercent) {
        var failures = new ArrayList<String>();
        for (String dimension : required) {
            double percent = cases == 0 ? 0 : 100.0 * conclusive(dimension) / cases;
            if (percent < minPercent) {
                failures.add(String.format("%s: %d conclusive of %d cases (%.1f%%), quota %.1f%%",
                        dimension, conclusive(dimension), cases, percent, minPercent));
            }
        }
        return failures;
    }

    public synchronized String report() {
        var text = new StringBuilder().append(cases).append(" cases: ").append(outcomes);
        text.append("\nconclusive cases per syntactic dimension: ").append(conclusive);
        byConfiguration.forEach((config, counts) -> text.append("\n").append(config).append(": ").append(counts));
        return text.toString();
    }
}
