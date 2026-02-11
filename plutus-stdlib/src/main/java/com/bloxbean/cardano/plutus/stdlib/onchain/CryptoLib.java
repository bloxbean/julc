package com.bloxbean.cardano.plutus.stdlib.onchain;

import com.bloxbean.cardano.plutus.core.PlutusData;
import com.bloxbean.cardano.plutus.onchain.annotation.OnchainLibrary;
import com.bloxbean.cardano.plutus.onchain.stdlib.Builtins;

/**
 * Cryptographic hash and signature verification operations compiled from Java source to UPLC.
 */
@OnchainLibrary
public class CryptoLib {

    /** SHA2-256 hash. */
    public static PlutusData sha2_256(PlutusData bs) {
        return Builtins.sha2_256(bs);
    }

    /** Blake2b-256 hash. */
    public static PlutusData blake2b_256(PlutusData bs) {
        return Builtins.blake2b_256(bs);
    }

    /** Verify an Ed25519 signature. */
    public static boolean verifyEd25519Signature(PlutusData key, PlutusData msg, PlutusData sig) {
        return Builtins.verifyEd25519Signature(key, msg, sig);
    }

    /** SHA3-256 hash. */
    public static PlutusData sha3_256(PlutusData bs) {
        return Builtins.sha3_256(bs);
    }

    /** Blake2b-224 hash (commonly used for key hashes). */
    public static PlutusData blake2b_224(PlutusData bs) {
        return Builtins.blake2b_224(bs);
    }

    /** Keccak-256 hash. */
    public static PlutusData keccak_256(PlutusData bs) {
        return Builtins.keccak_256(bs);
    }

    /** Verify ECDSA secp256k1 signature. */
    public static boolean verifyEcdsaSecp256k1(PlutusData vk, PlutusData msg, PlutusData sig) {
        return Builtins.verifyEcdsaSecp256k1Signature(vk, msg, sig);
    }

    /** Verify Schnorr secp256k1 signature. */
    public static boolean verifySchnorrSecp256k1(PlutusData vk, PlutusData msg, PlutusData sig) {
        return Builtins.verifySchnorrSecp256k1Signature(vk, msg, sig);
    }

    /** RIPEMD-160 hash. */
    public static PlutusData ripemd_160(PlutusData bs) {
        return Builtins.ripemd_160(bs);
    }
}
