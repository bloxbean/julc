package com.bloxbean.cardano.plutus.stdlib;

import com.bloxbean.cardano.plutus.compiler.pir.PirTerm;
import com.bloxbean.cardano.plutus.core.DefaultFun;

/**
 * Cryptographic operations as PIR term builders.
 * <p>
 * These are thin wrappers around the corresponding UPLC builtins.
 * The UplcGenerator handles force counts automatically.
 */
public final class CryptoLib {

    private CryptoLib() {}

    /**
     * SHA2-256 hash of a bytestring.
     *
     * @param bs PIR term representing a ByteString
     * @return PIR term that evaluates to ByteString (32-byte hash)
     */
    public static PirTerm sha2_256(PirTerm bs) {
        return new PirTerm.App(new PirTerm.Builtin(DefaultFun.Sha2_256), bs);
    }

    /**
     * Blake2b-256 hash of a bytestring.
     *
     * @param bs PIR term representing a ByteString
     * @return PIR term that evaluates to ByteString (32-byte hash)
     */
    public static PirTerm blake2b_256(PirTerm bs) {
        return new PirTerm.App(new PirTerm.Builtin(DefaultFun.Blake2b_256), bs);
    }

    /**
     * Verify an Ed25519 signature.
     *
     * @param publicKey PIR term representing the public key (ByteString, 32 bytes)
     * @param message   PIR term representing the message (ByteString)
     * @param signature PIR term representing the signature (ByteString, 64 bytes)
     * @return PIR term that evaluates to Bool (true if signature is valid)
     */
    public static PirTerm verifyEd25519Signature(PirTerm publicKey, PirTerm message, PirTerm signature) {
        return new PirTerm.App(
                new PirTerm.App(
                        new PirTerm.App(
                                new PirTerm.Builtin(DefaultFun.VerifyEd25519Signature),
                                publicKey),
                        message),
                signature);
    }

    /**
     * SHA3-256 hash of a bytestring.
     *
     * @param bs PIR term representing a ByteString
     * @return PIR term that evaluates to ByteString (32-byte hash)
     */
    public static PirTerm sha3_256(PirTerm bs) {
        return new PirTerm.App(new PirTerm.Builtin(DefaultFun.Sha3_256), bs);
    }

    /**
     * Blake2b-224 hash of a bytestring (commonly used for key hashes).
     *
     * @param bs PIR term representing a ByteString
     * @return PIR term that evaluates to ByteString (28-byte hash)
     */
    public static PirTerm blake2b_224(PirTerm bs) {
        return new PirTerm.App(new PirTerm.Builtin(DefaultFun.Blake2b_224), bs);
    }

    /**
     * Keccak-256 hash of a bytestring.
     *
     * @param bs PIR term representing a ByteString
     * @return PIR term that evaluates to ByteString (32-byte hash)
     */
    public static PirTerm keccak_256(PirTerm bs) {
        return new PirTerm.App(new PirTerm.Builtin(DefaultFun.Keccak_256), bs);
    }
}
