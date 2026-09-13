package org.julclang.verification.dsl;

import org.julclang.verification.dsl.ir.DslType;
import org.julclang.verification.dsl.ir.FieldNode;
import org.julclang.verification.dsl.ir.PropertyNode;

import java.util.Objects;

public record DatumExpr(PropertyNode node) implements Expr {
    public DatumExpr { node = Objects.requireNonNull(node, "node"); }
    public ByteStringExpr bytesField(String name) {
        return new ByteStringExpr(new FieldNode(node, name, DslType.BYTE_STRING));
    }
    public IntegerExpr integerField(String name) {
        return new IntegerExpr(new FieldNode(node, name, DslType.INTEGER));
    }
}
