package com.bloxbean.cardano.plutus.onchain.stdlib;

import com.bloxbean.cardano.plutus.core.PlutusData;

/**
 * On-chain cryptographic hash and signature verification functions.
 * <p>
 * These methods are executable both on-chain (compiled to UPLC via StdlibRegistry)
 * and off-chain (delegating to a {@link CryptoProvider} for real crypto operations).
 * <p>
 * For off-chain testing, call {@link #setProvider(CryptoProvider)} before using these
 * methods (e.g., in a {@code @BeforeAll} test setup or via {@code ContractTest}).
 */
public final class CryptoLib {

    private static volatile CryptoProvider provider;

    private CryptoLib() {}

    /** Set the crypto provider for off-chain execution. */
    public static void setProvider(CryptoProvider provider) {
        CryptoLib.provider = provider;
    }

    /** Get the current crypto provider, or throw if not set. */
    private static CryptoProvider requireProvider() {
        CryptoProvider p = provider;
        if (p == null) {
            throw new UnsupportedOperationException(
                    "CryptoLib requires a CryptoProvider for off-chain use. "
                    + "Call CryptoLib.setProvider(new JvmCryptoProvider()) in test setup, "
                    + "or extend ContractTest which sets it up automatically.");
        }
        return p;
    }

    private static byte[] toBytes(PlutusData data) {
        if (data instanceof PlutusData.BytesData bd) {
            return bd.value();
        }
        throw new IllegalArgumentException("Expected BytesData, got: " + data.getClass().getSimpleName());
    }

    /** SHA2-256 hash. */
    public static PlutusData sha2_256(PlutusData data) {
        return new PlutusData.BytesData(requireProvider().sha2_256(toBytes(data)));
    }

    /** Blake2b-256 hash. */
    public static PlutusData blake2b_256(PlutusData data) {
        return new PlutusData.BytesData(requireProvider().blake2b_256(toBytes(data)));
    }

    /** Blake2b-224 hash (commonly used for key hashes). */
    public static PlutusData blake2b_224(PlutusData data) {
        return new PlutusData.BytesData(requireProvider().blake2b_224(toBytes(data)));
    }

    /** SHA3-256 hash. */
    public static PlutusData sha3_256(PlutusData data) {
        return new PlutusData.BytesData(requireProvider().sha3_256(toBytes(data)));
    }

    /** Keccak-256 hash. */
    public static PlutusData keccak_256(PlutusData data) {
        return new PlutusData.BytesData(requireProvider().keccak_256(toBytes(data)));
    }

    /** Verify an Ed25519 signature. */
    public static boolean verifyEd25519Signature(PlutusData pubKey, PlutusData msg, PlutusData sig) {
        return requireProvider().verifyEd25519Signature(toBytes(pubKey), toBytes(msg), toBytes(sig));
    }

    // --- byte[] convenience overloads (used by @OnchainLibrary code) ---

    /** Blake2b-256 hash (byte[] overload). */
    public static byte[] blake2b_256(byte[] data) {
        return requireProvider().blake2b_256(data);
    }

    /** SHA2-256 hash (byte[] overload). */
    public static byte[] sha2_256(byte[] data) {
        return requireProvider().sha2_256(data);
    }

    /** Blake2b-224 hash (byte[] overload). */
    public static byte[] blake2b_224(byte[] data) {
        return requireProvider().blake2b_224(data);
    }

    /** SHA3-256 hash (byte[] overload). */
    public static byte[] sha3_256(byte[] data) {
        return requireProvider().sha3_256(data);
    }

    /** Keccak-256 hash (byte[] overload). */
    public static byte[] keccak_256(byte[] data) {
        return requireProvider().keccak_256(data);
    }

    /** Verify ECDSA secp256k1 signature. Off-chain: not supported. */
    public static boolean verifyEcdsaSecp256k1(byte[] vk, byte[] msg, byte[] sig) {
        throw new UnsupportedOperationException("CryptoLib.verifyEcdsaSecp256k1() not supported off-chain.");
    }

    /** Verify Schnorr secp256k1 signature. Off-chain: not supported. */
    public static boolean verifySchnorrSecp256k1(byte[] vk, byte[] msg, byte[] sig) {
        throw new UnsupportedOperationException("CryptoLib.verifySchnorrSecp256k1() not supported off-chain.");
    }

    /** RIPEMD-160 hash. Off-chain: not supported. */
    public static byte[] ripemd_160(byte[] bs) {
        throw new UnsupportedOperationException("CryptoLib.ripemd_160() not supported off-chain.");
    }
}
