package com.bloxbean.cardano.julc.vm.java.builtins;

import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.bouncycastle.math.ec.ECCurve;
import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * BIP-340 Schnorr signature verification for secp256k1.
 */
final class Bip340Schnorr {

    private static final ECParameterSpec SPEC = ECNamedCurveTable.getParameterSpec("secp256k1");
    private static final ECCurve CURVE = SPEC.getCurve();
    private static final ECPoint G = SPEC.getG();
    private static final BigInteger N = SPEC.getN();
    private static final BigInteger P = CURVE.getField().getCharacteristic();

    private Bip340Schnorr() {}

    /**
     * Verify a BIP-340 Schnorr signature.
     *
     * @param pubKeyBytes 32-byte x-only public key
     * @param msg         the message
     * @param sigBytes    64-byte signature (r || s)
     * @return true if the signature is valid
     */
    public static boolean verify(byte[] pubKeyBytes, byte[] msg, byte[] sigBytes) {
        BigInteger px = new BigInteger(1, pubKeyBytes);
        if (px.compareTo(P) >= 0) {
            throw new IllegalArgumentException("public key x-coordinate exceeds field size");
        }

        BigInteger r = new BigInteger(1, java.util.Arrays.copyOfRange(sigBytes, 0, 32));
        BigInteger s = new BigInteger(1, java.util.Arrays.copyOfRange(sigBytes, 32, 64));

        if (r.compareTo(P) >= 0 || s.compareTo(N) >= 0) return false;

        // Lift x to point P — if this fails, the key is invalid (evaluation failure)
        ECPoint pubPoint = liftX(px);
        if (pubPoint == null) {
            throw new IllegalArgumentException("public key is not a valid point on the curve");
        }

        // e = int(tagged_hash("BIP0340/challenge", r || P || m)) mod n
        byte[] rBytes = padTo32(r.toByteArray());
        byte[] pBytes = padTo32(px.toByteArray());
        byte[] eHash = taggedHash("BIP0340/challenge", concat(rBytes, pBytes, msg));
        BigInteger e = new BigInteger(1, eHash).mod(N);

        // R = s*G - e*P
        ECPoint sG = G.multiply(s).normalize();
        ECPoint eP = pubPoint.multiply(e).normalize();
        ECPoint R = sG.add(eP.negate()).normalize();

        if (R.isInfinity()) return false;
        if (R.getAffineYCoord().toBigInteger().testBit(0)) return false; // y must be even
        if (!R.getAffineXCoord().toBigInteger().equals(r)) return false;

        return true;
    }

    private static ECPoint liftX(BigInteger x) {
        try {
            // y^2 = x^3 + 7 (mod p)
            BigInteger ySq = x.modPow(BigInteger.valueOf(3), P).add(BigInteger.valueOf(7)).mod(P);
            BigInteger y = ySq.modPow(P.add(BigInteger.ONE).divide(BigInteger.valueOf(4)), P);
            if (!y.modPow(BigInteger.TWO, P).equals(ySq)) return null;
            if (y.testBit(0)) y = P.subtract(y);
            return CURVE.createPoint(x, y);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] padTo32(byte[] b) {
        if (b.length == 32) return b;
        byte[] result = new byte[32];
        if (b.length > 32) {
            // Strip leading zeros
            System.arraycopy(b, b.length - 32, result, 0, 32);
        } else {
            System.arraycopy(b, 0, result, 32 - b.length, b.length);
        }
        return result;
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (var a : arrays) total += a.length;
        var result = new byte[total];
        int pos = 0;
        for (var a : arrays) {
            System.arraycopy(a, 0, result, pos, a.length);
            pos += a.length;
        }
        return result;
    }

    private static byte[] taggedHash(String tag, byte[] msg) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            byte[] tagHash = md.digest(tag.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            md.reset();
            md.update(tagHash);
            md.update(tagHash);
            md.update(msg);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
