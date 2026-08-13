package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.DslProperty;

public final class VerificationDsl {
    private VerificationDsl() { }
    public static DslProperty property(String id, BoolExpr expression) {
        return new DslProperty(id, expression.node());
    }
}
