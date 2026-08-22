package com.bloxbean.cardano.julc.verification.dsl.ir;

import java.util.Objects;

/** One composable authorization relation over the complete modeled signatory list. */
public record AuthorizationNode(
        AuthorizationRelation relation,
        PropertyNode authorities,
        String threshold) implements PropertyNode {
    public AuthorizationNode {
        relation = Objects.requireNonNull(relation, "relation");
        authorities = Objects.requireNonNull(authorities, "authorities");
    }

    @Override
    public DslType resultType() {
        return DslType.BOOL;
    }
}
