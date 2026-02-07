package com.bloxbean.cardano.plutus.onchain.stdlib;

import com.bloxbean.cardano.plutus.core.PlutusData;

/**
 * On-chain cryptographic hash and signature verification functions.
 * <p>
 * These are compile-time stubs for IDE support. The actual on-chain implementation
 * is provided by the PlutusCompiler via {@code StdlibRegistry}.
 */
public final class CryptoLib {

    private CryptoLib() {}

    /** SHA2-256 hash. */
    public static PlutusData sha2_256(PlutusData data) {
        throw new UnsupportedOperationException("On-chain only");
    }

    /** Blake2b-256 hash. */
    public static PlutusData blake2b_256(PlutusData data) {
        throw new UnsupportedOperationException("On-chain only");
    }

    /** Blake2b-224 hash (commonly used for key hashes). */
    public static PlutusData blake2b_224(PlutusData data) {
        throw new UnsupportedOperationException("On-chain only");
    }

    /** SHA3-256 hash. */
    public static PlutusData sha3_256(PlutusData data) {
        throw new UnsupportedOperationException("On-chain only");
    }

    /** Keccak-256 hash. */
    public static PlutusData keccak_256(PlutusData data) {
        throw new UnsupportedOperationException("On-chain only");
    }

    /** Verify an Ed25519 signature. */
    public static boolean verifyEd25519Signature(PlutusData pubKey, PlutusData msg, PlutusData sig) {
        throw new UnsupportedOperationException("On-chain only");
    }
}
