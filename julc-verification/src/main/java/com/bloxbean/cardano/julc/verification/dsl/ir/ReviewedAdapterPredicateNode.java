package com.bloxbean.cardano.julc.verification.dsl.ir;

import java.util.List;
import java.util.Objects;

/** Closed Boolean operations over raw fields reviewed by ADR-027. */
public record ReviewedAdapterPredicateNode(
        ReviewedAdapterPredicate predicate,
        List<PropertyNode> arguments) implements PropertyNode {
    public ReviewedAdapterPredicateNode {
        predicate = Objects.requireNonNull(predicate, "predicate");
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
    }

    @Override
    public DslType resultType() {
        return DslType.BOOL;
    }

    public enum ReviewedAdapterPredicate {
        VALIDITY_DECODER_VALID,
        VALIDITY_CANONICAL_ENCODING,
        VALIDITY_EMPTY,
        VALIDITY_CONTAINS,
        VALIDITY_INCLUDES,
        VALIDITY_ENTIRELY_BEFORE,
        VALIDITY_ENTIRELY_AFTER,
        CURRENT_TREASURY_WELL_FORMED,
        CURRENT_TREASURY_ABSENT,
        CURRENT_TREASURY_MALFORMED,
        TREASURY_DONATION_WELL_FORMED,
        TREASURY_DONATION_ABSENT,
        TREASURY_DONATION_MALFORMED,
        CHANGED_PARAMETERS_WELL_FORMED,
        CHANGED_PARAMETERS_NON_EMPTY,
        CHANGED_PARAMETERS_STRICTLY_ASCENDING_UNIQUE,
        CHANGED_PARAMETERS_CONTAINS_ID,
        CHANGED_PARAMETERS_COUNT_ID_EQUALS,
        QUORUM_DECODER_VALID,
        QUORUM_CANONICAL_ENCODING,
        QUORUM_UNIT_INTERVAL
    }
}
