package org.julclang.difftest.gen;

import org.julclang.difftest.Observation;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The allowed-divergence registry for generated programs (ADR-063 D6). A generated mismatch
 * passes only when exactly one entry matches it. Each entry names its ADR-062 catalogue ID, a
 * structural condition on the program, the observation kind each side must produce, and the exact
 * reason of an inconclusive UPLC side. That is weaker than a saved case's pinned observations: a
 * generated mismatch with the same feature, kinds and reason but another cause passes as this
 * entry, which is why every match is counted and a reduced case is saved for each entry. Remove an
 * entry when its catalogue entry is resolved; runs report how often each entry matched, so an
 * entry that stops matching is visible.
 */
public final class KnownDivergences {

    private KnownDivergences() {
    }

    /**
     * One known, unresolved divergence. {@code uplcDetail} is the exact reason when the UPLC side is
     * {@link Observation.Inconclusive}, and text its detail must contain otherwise.
     */
    public record Entry(String id, String description, Set<G0.Feature> requires, Class<? extends Observation> jvm,
                        Class<? extends Observation> uplc, String uplcDetail) {

        boolean matches(Set<G0.Feature> features, Observation jvmObservation, Observation uplcObservation) {
            if (!features.containsAll(requires) || !jvm.isInstance(jvmObservation) || !uplc.isInstance(uplcObservation)) {
                return false;
            }
            if (uplcDetail == null) return true;
            if (uplcObservation instanceof Observation.Inconclusive i) return i.reason().equals(uplcDetail);
            return String.valueOf(uplcObservation).contains(uplcDetail);
        }
    }

    public static final List<Entry> ENTRIES = List.of(
            new Entry("T21", "a while loop with break and an if that updates a variable never terminates on UPLC",
                    Set.of(G0.Feature.WHILE_BREAK_WITH_IF_UPDATE), Observation.Value.class,
                    Observation.Inconclusive.class, "budget exhausted"),
            new Entry("T23", "a for-each with break and an if/else assigning different variables returns a wrong value",
                    Set.of(G0.Feature.FOR_EACH_BREAK_WITH_SPLIT_IF_ELSE), Observation.Value.class,
                    Observation.Value.class, null));

    /** The single entry that explains this mismatch, if exactly one does. */
    public static Optional<Entry> explain(Set<G0.Feature> features, Observation jvm, Observation uplc) {
        var matching = ENTRIES.stream().filter(e -> e.matches(features, jvm, uplc)).toList();
        return matching.size() == 1 ? Optional.of(matching.getFirst()) : Optional.empty();
    }
}
