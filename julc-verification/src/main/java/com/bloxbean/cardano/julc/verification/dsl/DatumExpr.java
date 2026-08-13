package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.DslType;
import com.bloxbean.cardano.julc.verification.dsl.ir.FieldNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;

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
