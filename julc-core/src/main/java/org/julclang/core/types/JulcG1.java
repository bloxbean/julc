package org.julclang.core.types;

/**
 * Opaque native UPLC value: a BLS12-381 G1 point (`bls12_381_G1_element`) (ADR-047).
 *
 * <p>G1 points come from `Builtins.bls12_381_G1_uncompress`, `bls12_381_G1_hashToGroup` and the G1 operations, and leave through `bls12_381_G1_compress`.
 * This type is deliberately not {@code byte[]} and not {@code PlutusData}: it cannot be
 * Data-encoded, stored in a datum, redeemer, record or list of Data, or compared with
 * {@code ==}; the compiler rejects such uses. There is no JVM constructor because the
 * semantics belong to the ledger evaluator.
 */
public final class JulcG1 {

    private JulcG1() {
    }
}
