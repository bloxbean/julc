package com.bloxbean.cardano.julc.jrl.ast;

/**
 * A fact pattern in a JRL rule's 'when' clause.
 */
public sealed interface FactPattern {

    SourceRange sourceRange();

    record RedeemerPattern(VariantMatch match, SourceRange sourceRange) implements FactPattern {}

    record DatumPattern(VariantMatch match, SourceRange sourceRange) implements FactPattern {}

    record TransactionPattern(TxField field, Expression value,
                              SourceRange sourceRange) implements FactPattern {}

    record ConditionPattern(Expression condition, SourceRange sourceRange) implements FactPattern {}

    record OutputPattern(Expression to, ValueConstraint value, DatumConstraint datum,
                         SourceRange sourceRange) implements FactPattern {}

    enum TxField { SIGNED_BY, VALID_AFTER, VALID_BEFORE }
}
