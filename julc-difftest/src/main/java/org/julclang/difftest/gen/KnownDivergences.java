package org.julclang.difftest.gen;

import org.julclang.difftest.Observation;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The allowed-divergence registry for generated programs (ADR-063 D6). A generated mismatch
 * passes only when exactly one entry matches it. Each entry names its ADR-062 catalogue ID, a
 * structural condition on the program, and the observation each side must produce, so it cannot
 * hide a mismatch with another cause. Remove an entry when its catalogue entry is resolved; runs
 * report how often each entry matched, so an entry that stops matching is visible.
 */
public final class KnownDivergences {

    private KnownDivergences() {
    }

    /** One known, unresolved divergence. */
    public record Entry(String id, String description, Set<G0.Feature> requires, Class<? extends Observation> jvm,
                        Class<? extends Observation> uplc, String uplcDetail) {

        boolean matches(Set<G0.Feature> features, Observation jvmObservation, Observation uplcObservation) {
            if (!features.containsAll(requires) || !jvm.isInstance(jvmObservation) || !uplc.isInstance(uplcObservation)) {
                return false;
            }
            return uplcDetail == null || String.valueOf(uplcObservation).contains(uplcDetail);
        }
    }

    public static final List<Entry> ENTRIES = List.of(
            new Entry("T21", "a while loop with break and an if that updates a variable never terminates on UPLC",
                    Set.of(G0.Feature.WHILE_BREAK_WITH_IF_UPDATE), Observation.Value.class,
                    Observation.Inconclusive.class, "budget exhausted"),
            new Entry("T13", "nested compareTo operands double the script per level (#146) and exhaust the budget",
                    Set.of(G0.Feature.DEEP_COMPARISON), Observation.Value.class,
                    Observation.Inconclusive.class, "budget exhausted"),
            new Entry("T13", "nested compareTo operands double the term per level (#146) until compilation runs out of memory",
                    Set.of(G0.Feature.DEEP_COMPARISON), Observation.Value.class,
                    Observation.Crash.class, "OutOfMemoryError"));

    /** The single entry that explains this mismatch, if exactly one does. */
    public static Optional<Entry> explain(Set<G0.Feature> features, Observation jvm, Observation uplc) {
        var matching = ENTRIES.stream().filter(e -> e.matches(features, jvm, uplc)).toList();
        return matching.size() == 1 ? Optional.of(matching.getFirst()) : Optional.empty();
    }
}
