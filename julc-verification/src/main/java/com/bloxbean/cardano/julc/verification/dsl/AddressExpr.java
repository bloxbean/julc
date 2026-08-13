package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.DslType;
import com.bloxbean.cardano.julc.verification.dsl.ir.FieldNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;

import java.util.Objects;

public record AddressExpr(PropertyNode node) implements Expr {
    public AddressExpr { node = Objects.requireNonNull(node, "node"); }
    public CredentialExpr credential() {
        return new CredentialExpr(new FieldNode(node, "credential", DslType.CREDENTIAL));
    }
}
