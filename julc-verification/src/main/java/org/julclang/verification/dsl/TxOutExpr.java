package org.julclang.verification.dsl;

import org.julclang.verification.dsl.ir.DslType;
import org.julclang.verification.dsl.ir.FieldNode;
import org.julclang.verification.dsl.ir.PropertyNode;

import java.util.Objects;

public record TxOutExpr(PropertyNode node) implements Expr {
    public TxOutExpr { node = Objects.requireNonNull(node, "node"); }
    public AddressExpr address() {
        return new AddressExpr(new FieldNode(node, "address", DslType.ADDRESS));
    }
    public ValueExpr value() {
        return new ValueExpr(new FieldNode(node, "value", DslType.VALUE));
    }
}
