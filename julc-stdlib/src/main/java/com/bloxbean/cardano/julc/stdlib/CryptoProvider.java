package com.bloxbean.cardano.julc.stdlib;

/**
 * Interface for cryptographic operations used by {@link Builtins}.
 * <p>
 * Implementations provide real hash functions for off-chain testing/debugging.
 * The on-chain versions are compiled to UPLC builtins and don't use this interface.
 * <p>
 * Set the provider via {@link Builtins#setCryptoProvider(CryptoProvider)}.
 */
public interface CryptoProvider {
    byte[] sha2_256(byte[] data);
    byte[] blake2b_256(byte[] data);
    byte[] blake2b_224(byte[] data);
    byte[] sha3_256(byte[] data);
    byte[] keccak_256(byte[] data);
    boolean verifyEd25519Signature(byte[] pubKey, byte[] msg, byte[] sig);

    /** Verify ECDSA secp256k1 signature. Default: unsupported. */
    default boolean verifyEcdsaSecp256k1Signature(byte[] pubKey, byte[] msg, byte[] sig) {
        throw new UnsupportedOperationException("verifyEcdsaSecp256k1Signature not implemented by this CryptoProvider");
    }

    /** Verify Schnorr secp256k1 signature. Default: unsupported. */
    default boolean verifySchnorrSecp256k1Signature(byte[] pubKey, byte[] msg, byte[] sig) {
        throw new UnsupportedOperationException("verifySchnorrSecp256k1Signature not implemented by this CryptoProvider");
    }

    /** RIPEMD-160 hash. Default: unsupported. */
    default byte[] ripemd_160(byte[] data) {
        throw new UnsupportedOperationException("ripemd_160 not implemented by this CryptoProvider");
    }
}
