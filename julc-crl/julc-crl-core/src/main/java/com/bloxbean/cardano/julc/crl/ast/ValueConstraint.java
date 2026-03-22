package com.bloxbean.cardano.julc.crl.ast;

/**
 * Value constraint in an Output fact pattern.
 */
public sealed interface ValueConstraint {

    /** {@code minADA( amount )} */
    record MinADA(Expression amount) implements ValueConstraint {}

    /** {@code contains( policyId, tokenName, amount )} */
    record Contains(Expression policy, Expression token,
                    Expression amount) implements ValueConstraint {}
}
