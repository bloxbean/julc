package com.bloxbean.cardano.julc.verification.dsl.ir;

import java.util.List;
import java.util.Objects;

/** Guarded elimination of a successfully decoded reviewed raw-data adapter. */
public record ReviewedAdapterWhenNode(
        ReviewedAdapterEliminator eliminator,
        PropertyNode source,
        List<String> variables,
        PropertyNode predicate) implements PropertyNode {
    public ReviewedAdapterWhenNode {
        eliminator = Objects.requireNonNull(eliminator, "eliminator");
        source = Objects.requireNonNull(source, "source");
        variables = List.copyOf(Objects.requireNonNull(variables, "variables"));
        predicate = Objects.requireNonNull(predicate, "predicate");
    }

    @Override
    public DslType resultType() {
        return DslType.BOOL;
    }

    public enum ReviewedAdapterEliminator {
        CURRENT_TREASURY_PRESENT,
        TREASURY_DONATION_PRESENT,
        QUORUM_DECODED
    }
}
