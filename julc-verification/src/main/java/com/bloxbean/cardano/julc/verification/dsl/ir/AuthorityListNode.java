package com.bloxbean.cardano.julc.verification.dsl.ir;

import java.util.List;

/** Bounded nonempty authority list; authorization relations deduplicate identities. */
public record AuthorityListNode(List<PropertyNode> authorities) implements PropertyNode {
    public AuthorityListNode {
        authorities = List.copyOf(authorities == null ? List.of() : authorities);
    }

    @Override
    public DslType resultType() {
        return DslType.TYPED_VALUE;
    }
}
