package org.julclang.core.types;

/**
 * Opaque native UPLC value: a native list of integers (`list integer`) for multi-scalar multiplication (ADR-047).
 *
 * <p>Built by `Builtins.scalars(...)` or `Builtins.scalarsFromList(list)`; a `JulcList<BigInteger>` is a Data list and is not interchangeable with it.
 * This type is deliberately not {@code byte[]} and not {@code PlutusData}: it cannot be
 * Data-encoded, stored in a datum, redeemer, record or list of Data, or compared with
 * {@code ==}; the compiler rejects such uses. There is no JVM constructor because the
 * semantics belong to the ledger evaluator.
 */
public final class JulcScalars {

    private JulcScalars() {
    }
}
