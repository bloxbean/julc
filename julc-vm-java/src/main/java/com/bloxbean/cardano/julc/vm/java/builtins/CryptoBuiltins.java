package com.bloxbean.cardano.julc.vm.java.builtins;

import com.bloxbean.cardano.julc.vm.java.CekValue;
import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.bouncycastle.crypto.digests.KeccakDigest;
import org.bouncycastle.crypto.digests.RIPEMD160Digest;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.bouncycastle.math.ec.ECPoint;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import static com.bloxbean.cardano.julc.vm.java.builtins.BuiltinHelper.*;

/**
 * Cryptographic hash and signature verification builtins.
 */
public final class CryptoBuiltins {

    private CryptoBuiltins() {}

    public static CekValue sha2_256(List<CekValue> args) {
        var bs = asByteString(args.get(0), "Sha2_256");
        try {
            var md = MessageDigest.getInstance("SHA-256");
            return mkByteString(md.digest(bs));
        } catch (NoSuchAlgorithmException e) {
            throw new BuiltinException("Sha2_256: algorithm not available", e);
        }
    }

    public static CekValue sha3_256(List<CekValue> args) {
        var bs = asByteString(args.get(0), "Sha3_256");
        try {
            var md = MessageDigest.getInstance("SHA3-256");
            return mkByteString(md.digest(bs));
        } catch (NoSuchAlgorithmException e) {
            throw new BuiltinException("Sha3_256: algorithm not available", e);
        }
    }

    public static CekValue blake2b_256(List<CekValue> args) {
        var bs = asByteString(args.get(0), "Blake2b_256");
        var digest = new Blake2bDigest(256);
        digest.update(bs, 0, bs.length);
        byte[] result = new byte[32];
        digest.doFinal(result, 0);
        return mkByteString(result);
    }

    public static CekValue blake2b_224(List<CekValue> args) {
        var bs = asByteString(args.get(0), "Blake2b_224");
        var digest = new Blake2bDigest(224);
        digest.update(bs, 0, bs.length);
        byte[] result = new byte[28];
        digest.doFinal(result, 0);
        return mkByteString(result);
    }

    public static CekValue keccak_256(List<CekValue> args) {
        var bs = asByteString(args.get(0), "Keccak_256");
        var digest = new KeccakDigest(256);
        digest.update(bs, 0, bs.length);
        byte[] result = new byte[32];
        digest.doFinal(result, 0);
        return mkByteString(result);
    }

    public static CekValue ripemd_160(List<CekValue> args) {
        var bs = asByteString(args.get(0), "Ripemd_160");
        var digest = new RIPEMD160Digest();
        digest.update(bs, 0, bs.length);
        byte[] result = new byte[20];
        digest.doFinal(result, 0);
        return mkByteString(result);
    }

    public static CekValue verifyEd25519Signature(List<CekValue> args) {
        var pubKey = asByteString(args.get(0), "VerifyEd25519Signature");
        var msg = asByteString(args.get(1), "VerifyEd25519Signature");
        var sig = asByteString(args.get(2), "VerifyEd25519Signature");

        if (pubKey.length != 32) {
            throw new BuiltinException("VerifyEd25519Signature: public key must be 32 bytes, got " + pubKey.length);
        }
        if (sig.length != 64) {
            throw new BuiltinException("VerifyEd25519Signature: signature must be 64 bytes, got " + sig.length);
        }

        try {
            var keyParams = new Ed25519PublicKeyParameters(pubKey, 0);
            var signer = new Ed25519Signer();
            signer.init(false, keyParams);
            signer.update(msg, 0, msg.length);
            return mkBool(signer.verifySignature(sig));
        } catch (Exception e) {
            // BouncyCastle may throw for invalid keys/signatures — return false per Plutus spec
            return mkBool(false);
        }
    }

    public static CekValue verifyEcdsaSecp256k1Signature(List<CekValue> args) {
        var pubKey = asByteString(args.get(0), "VerifyEcdsaSecp256k1Signature");
        var msgHash = asByteString(args.get(1), "VerifyEcdsaSecp256k1Signature");
        var sig = asByteString(args.get(2), "VerifyEcdsaSecp256k1Signature");

        if (pubKey.length != 33) {
            throw new BuiltinException("VerifyEcdsaSecp256k1Signature: public key must be 33 bytes (compressed), got " + pubKey.length);
        }
        if (msgHash.length != 32) {
            throw new BuiltinException("VerifyEcdsaSecp256k1Signature: message hash must be 32 bytes, got " + msgHash.length);
        }
        if (sig.length != 64) {
            throw new BuiltinException("VerifyEcdsaSecp256k1Signature: signature must be 64 bytes, got " + sig.length);
        }

        try {
            ECParameterSpec spec = ECNamedCurveTable.getParameterSpec("secp256k1");
            ECDomainParameters domain = new ECDomainParameters(spec.getCurve(), spec.getG(), spec.getN(), spec.getH());

            // Decode r and s from 64-byte signature
            java.math.BigInteger r = new java.math.BigInteger(1, java.util.Arrays.copyOfRange(sig, 0, 32));
            java.math.BigInteger s = new java.math.BigInteger(1, java.util.Arrays.copyOfRange(sig, 32, 64));

            // Validate r and s are in valid range [1, n-1]
            java.math.BigInteger n = spec.getN();
            if (r.signum() <= 0 || r.compareTo(n) >= 0 ||
                s.signum() <= 0 || s.compareTo(n) >= 0) {
                throw new BuiltinException("VerifyEcdsaSecp256k1Signature: r or s out of range");
            }

            // Plutus requires low-s (BIP-146): s must be <= n/2
            java.math.BigInteger halfN = n.shiftRight(1);
            if (s.compareTo(halfN) > 0) {
                return mkBool(false);
            }

            ECPoint point = spec.getCurve().decodePoint(pubKey);
            var keyParams = new ECPublicKeyParameters(point, domain);

            var signer = new org.bouncycastle.crypto.signers.ECDSASigner();
            signer.init(false, keyParams);

            return mkBool(signer.verifySignature(msgHash, r, s));
        } catch (BuiltinException e) {
            throw e;
        } catch (Exception e) {
            throw new BuiltinException("VerifyEcdsaSecp256k1Signature: " + e.getMessage(), e);
        }
    }

    public static CekValue verifySchnorrSecp256k1Signature(List<CekValue> args) {
        var pubKey = asByteString(args.get(0), "VerifySchnorrSecp256k1Signature");
        var msg = asByteString(args.get(1), "VerifySchnorrSecp256k1Signature");
        var sig = asByteString(args.get(2), "VerifySchnorrSecp256k1Signature");

        if (pubKey.length != 32) {
            throw new BuiltinException("VerifySchnorrSecp256k1Signature: public key must be 32 bytes, got " + pubKey.length);
        }
        if (sig.length != 64) {
            throw new BuiltinException("VerifySchnorrSecp256k1Signature: signature must be 64 bytes, got " + sig.length);
        }

        // BIP-340 Schnorr signature verification
        // If public key is not a valid point, this is an evaluation failure
        try {
            return mkBool(Bip340Schnorr.verify(pubKey, msg, sig));
        } catch (IllegalArgumentException e) {
            // Invalid public key point — evaluation failure per Plutus spec
            throw new BuiltinException("VerifySchnorrSecp256k1Signature: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new BuiltinException("VerifySchnorrSecp256k1Signature: " + e.getMessage(), e);
        }
    }
}
