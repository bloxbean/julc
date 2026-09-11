package org.julclang.verification.dsl;

import org.julclang.verification.dsl.ir.DslType;
import org.julclang.verification.dsl.ir.FieldNode;
import org.julclang.verification.dsl.ir.PropertyNode;

import java.util.Objects;

public record AddressExpr(PropertyNode node) implements Expr {
    public AddressExpr { node = Objects.requireNonNull(node, "node"); }
    public CredentialExpr credential() {
        return new CredentialExpr(new FieldNode(node, "credential", DslType.CREDENTIAL));
    }
}
