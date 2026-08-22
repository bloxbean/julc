package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import java.util.Objects;

/** Symbolic pinned V3 protocol version. */
public record ProtocolVersionExpr(PropertyNode node) implements Expr {
    public ProtocolVersionExpr { node = Objects.requireNonNull(node, "node"); }
    public IntegerExpr major() { return integer("major"); }
    public IntegerExpr minor() { return integer("minor"); }
    public TypedValueExpr typed() { return new TypedValueExpr(node, LedgerTypeAuthority.PROTOCOL_VERSION); }
    private IntegerExpr integer(String name) {
        return new IntegerExpr(new LedgerFieldNode(node, LedgerTypeAuthority.PROTOCOL_VERSION,
                name, LedgerTypeAuthority.INTEGER));
    }
}
