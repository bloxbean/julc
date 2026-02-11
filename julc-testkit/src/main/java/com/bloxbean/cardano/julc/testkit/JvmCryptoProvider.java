package com.bloxbean.cardano.julc.testkit;

import com.bloxbean.cardano.julc.onchain.stdlib.CryptoProvider;
import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * JVM-based implementation of {@link CryptoProvider} for off-chain testing.
 * Uses java.security.MessageDigest for SHA-2/SHA-3/Keccak and BouncyCastle for Blake2b/Ed25519.
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

    @Override
    public boolean verifyEd25519Signature(byte[] pubKey, byte[] msg, byte[] sig) {
        try {
            var publicKeyParams = new Ed25519PublicKeyParameters(pubKey, 0);
            var signer = new Ed25519Signer();
            signer.init(false, publicKeyParams);
            signer.update(msg, 0, msg.length);
            return signer.verifySignature(sig);
        } catch (Exception e) {
            return false;
        }
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
