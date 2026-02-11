package com.bloxbean.cardano.julc.onchain.stdlib;

/**
 * Interface for cryptographic operations used by {@link CryptoLib}.
 * <p>
 * Implementations provide real hash functions for off-chain testing/debugging.
 * The on-chain versions are compiled to UPLC builtins and don't use this interface.
 */
public interface CryptoProvider {
    byte[] sha2_256(byte[] data);
    byte[] blake2b_256(byte[] data);
    byte[] blake2b_224(byte[] data);
    byte[] sha3_256(byte[] data);
    byte[] keccak_256(byte[] data);
    boolean verifyEd25519Signature(byte[] pubKey, byte[] msg, byte[] sig);
}
