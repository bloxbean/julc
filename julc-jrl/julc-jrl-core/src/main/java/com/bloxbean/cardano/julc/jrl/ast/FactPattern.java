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

    /**
     * Input fact: check transaction inputs.
     * {@code Input( from: expr, value: $var, token: contains(...) )}
     */
    record InputPattern(Expression from, String valueBinding, ValueConstraint token,
                        SourceRange sourceRange) implements FactPattern {}

    /**
     * Mint fact: check minting/burning.
     * {@code Mint( policy: expr, token: expr, amount: $var, burned )}
     */
    record MintPattern(Expression policy, Expression token, String amountBinding,
                       boolean burned, SourceRange sourceRange) implements FactPattern {}

    /**
     * ContinuingOutput fact: check output back to own script address.
     * Same fields as Output but implicitly targets ownAddress.
     */
    record ContinuingOutputPattern(ValueConstraint value, DatumConstraint datum,
                                   SourceRange sourceRange) implements FactPattern {}

    enum TxField { SIGNED_BY, VALID_AFTER, VALID_BEFORE, FEE }
}
