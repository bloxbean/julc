package org.julclang.verification.dsl.ir;

/** Explicitly requires the complete modeled transaction signatory list to be empty. */
public record NoSignersNode() implements PropertyNode {
    @Override
    public DslType resultType() {
        return DslType.BOOL;
    }
}
