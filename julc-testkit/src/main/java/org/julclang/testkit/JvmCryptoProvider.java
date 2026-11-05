package org.julclang.testkit;

import org.julclang.stdlib.CryptoProvider;
import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.bouncycastle.crypto.digests.RIPEMD160Digest;

import org.julclang.vm.crypto.Ed25519Verifier;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * JVM-based implementation of {@link CryptoProvider} for off-chain testing.
 * Uses java.security.MessageDigest for SHA-2/SHA-3/Keccak and BouncyCastle for Blake2b; Ed25519 is {@link Ed25519Verifier}.
 */
public class JvmCryptoProvider implements CryptoProvider {

    @Override
    public byte[] sha2_256(byte[] data) {
        return digest("SHA-256", data);
    }

    @Override
    public byte[] blake2b_256(byte[] data) {
        return blake2b(data, 32);
    }

    @Override
    public byte[] blake2b_224(byte[] data) {
        return blake2b(data, 28);
    }

    @Override
    public byte[] sha3_256(byte[] data) {
        return digest("SHA3-256", data);
    }

    @Override
    public byte[] keccak_256(byte[] data) {
        var keccak = new org.bouncycastle.crypto.digests.KeccakDigest(256);
        keccak.update(data, 0, data.length);
        byte[] result = new byte[32];
        keccak.doFinal(result, 0);
        return result;
    }

    /**
     * As {@code PlutusCore.Crypto.Ed25519} at Plutus 1.63.0.0: a key of another length than 32 bytes or a signature of another
     * length than 64 is a failure, and false is for what the two of them, as they stand, do not verify.
     */
    @Override
    public boolean verifyEd25519Signature(byte[] pubKey, byte[] msg, byte[] sig) {
        return Ed25519Verifier.verify(pubKey, msg, sig);
    }

    @Override
    public byte[] ripemd_160(byte[] data) {
        var digest = new RIPEMD160Digest();
        digest.update(data, 0, data.length);
        byte[] result = new byte[digest.getDigestSize()];
        digest.doFinal(result, 0);
        return result;
    }

    private static byte[] digest(String algorithm, byte[] data) {
        try {
            return MessageDigest.getInstance(algorithm).digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Algorithm not available: " + algorithm, e);
        }
    }

    private static byte[] blake2b(byte[] data, int outputLengthBytes) {
        var digest = new Blake2bDigest(outputLengthBytes * 8);
        digest.update(data, 0, data.length);
        byte[] result = new byte[outputLengthBytes];
        digest.doFinal(result, 0);
        return result;
    }
}
