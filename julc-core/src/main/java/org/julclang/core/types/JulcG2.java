package org.julclang.core.types;

/**
 * Opaque native UPLC value: a BLS12-381 G2 point (`bls12_381_G2_element`) (ADR-047).
 *
 * <p>G2 points come from `Builtins.bls12_381_G2_uncompress`, `bls12_381_G2_hashToGroup` and the G2 operations, and leave through `bls12_381_G2_compress`.
 * This type is deliberately not {@code byte[]} and not {@code PlutusData}: it cannot be
 * Data-encoded, stored in a datum, redeemer, record or list of Data, or compared with
 * {@code ==}; the compiler rejects such uses. There is no JVM constructor because the
 * semantics belong to the ledger evaluator.
 */
public final class JulcG2 {

    private JulcG2() {
    }
}
