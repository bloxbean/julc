package com.bloxbean.cardano.plutus.onchain.ledger;

import java.math.BigInteger;

/**
 * The type of an interval bound: negative infinity, finite, or positive infinity.
 * <p>
 * This is an IDE stub. The compiler uses the schema from LedgerTypeRegistry.
 */
public sealed interface IntervalBoundType {
    record NegInf() implements IntervalBoundType {}
    record Finite(BigInteger time) implements IntervalBoundType {}
    record PosInf() implements IntervalBoundType {}
}
