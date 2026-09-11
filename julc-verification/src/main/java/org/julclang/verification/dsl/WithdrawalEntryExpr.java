package org.julclang.verification.dsl;

import org.julclang.verification.dsl.ir.DslType;
import org.julclang.verification.dsl.ir.FieldNode;
import org.julclang.verification.dsl.ir.PropertyNode;

import java.util.Objects;

/** One raw credential/amount pair from the V3 withdrawal association list. */
public record WithdrawalEntryExpr(PropertyNode node) implements Expr {
    public WithdrawalEntryExpr { node = Objects.requireNonNull(node, "node"); }

    public CredentialExpr credential() {
        return new CredentialExpr(new FieldNode(node, "credential", DslType.CREDENTIAL));
    }

    public IntegerExpr amount() {
        return new IntegerExpr(new FieldNode(node, "amount", DslType.INTEGER));
    }
}
