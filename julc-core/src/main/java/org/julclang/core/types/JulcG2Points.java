package org.julclang.core.types;

/**
 * Opaque native UPLC value: a native list of G2 points (`list bls12_381_G2_element`) (ADR-047).
 *
 * <p>Built by `Builtins.g2Points(...)` or `Builtins.g2PointsFromCompressed(list)`.
 * This type is deliberately not {@code byte[]} and not {@code PlutusData}: it cannot be
 * Data-encoded, stored in a datum, redeemer, record or list of Data, or compared with
 * {@code ==}; the compiler rejects such uses. There is no JVM constructor because the
 * semantics belong to the ledger evaluator.
 */
public final class JulcG2Points {

    private JulcG2Points() {
    }
}
