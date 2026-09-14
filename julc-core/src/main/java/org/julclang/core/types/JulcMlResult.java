package org.julclang.core.types;

/**
 * Opaque native UPLC value: a BLS12-381 Miller-loop result (`bls12_381_mlresult`) (ADR-047).
 *
 * <p>Miller results come from `Builtins.bls12_381_millerLoop` and `bls12_381_mulMlResult` and are consumed by `bls12_381_finalVerify`; they have no encoding at all.
 * This type is deliberately not {@code byte[]} and not {@code PlutusData}: it cannot be
 * Data-encoded, stored in a datum, redeemer, record or list of Data, or compared with
 * {@code ==}; the compiler rejects such uses. There is no JVM constructor because the
 * semantics belong to the ledger evaluator.
 */
public final class JulcMlResult {

    private JulcMlResult() {
    }
}
