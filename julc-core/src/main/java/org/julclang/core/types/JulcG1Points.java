package org.julclang.core.types;

/**
 * Opaque native UPLC value: a native list of G1 points (`list bls12_381_G1_element`) (ADR-047).
 *
 * <p>Built by `Builtins.g1Points(...)` or `Builtins.g1PointsFromCompressed(list)`.
 * This type is deliberately not {@code byte[]} and not {@code PlutusData}: it cannot be
 * Data-encoded, stored in a datum, redeemer, record or list of Data, or compared with
 * {@code ==}; the compiler rejects such uses. There is no JVM constructor because the
 * semantics belong to the ledger evaluator.
 */
public final class JulcG1Points {

    private JulcG1Points() {
    }
}
