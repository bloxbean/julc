package org.julclang.vm.crypto;

import java.util.HexFormat;

/**
 * Ed25519 verification with the outcomes of the node.
 * <p>
 * {@code verifyEd25519Signature} is {@code Ed25519DSIGN} from cardano-crypto-class, which calls libsodium's
 * {@code crypto_sign_ed25519_verify_detached}. That function makes four checks on its input (below) and then checks
 * the equation without the cofactor, comparing the bytes of {@code R} ({@link Ed25519Equation}). The same input
 * checks are in cardano-client-lib's {@code EdDSASigningProvider}.
 */
public final class Ed25519Verifier {

    public static final int PUBLIC_KEY_BYTES = 32;
    public static final int SIGNATURE_BYTES = 64;

    /** The group order {@code L = 2^252 + 27742317777372353535851937790883648493}, little-endian. */
    private static final byte[] L = bytes("edd3f55c1a631258d69cf7a2def9de1400000000000000000000000000000010");

    /** libsodium's {@code ge25519_has_small_order} list; the last byte is compared without its top bit. */
    private static final byte[][] SMALL_ORDER = {
            bytes("0000000000000000000000000000000000000000000000000000000000000000"),
            bytes("0100000000000000000000000000000000000000000000000000000000000000"),
            bytes("26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05"),
            bytes("c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a"),
            bytes("ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"),
            bytes("edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"),
            bytes("eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f")};

    private Ed25519Verifier() {
    }

    /**
     * Verifies {@code signature} over {@code message}.
     *
     * @param publicKey a key of {@value #PUBLIC_KEY_BYTES} bytes
     * @param message   the message
     * @param signature a signature of {@value #SIGNATURE_BYTES} bytes
     * @return whether the node would accept them; false for a key that is not a point on the curve
     * @throws IllegalArgumentException for another length. Plutus fails there, where a verifier would say false,
     *                                  so a caller checks lengths first and reports them in its own terms
     */
    public static boolean verify(byte[] publicKey, byte[] message, byte[] signature) {
        if (publicKey.length != PUBLIC_KEY_BYTES) {
            throw new IllegalArgumentException("Ed25519 public key must be 32 bytes, got " + publicKey.length);
        }
        if (signature.length != SIGNATURE_BYTES) {
            throw new IllegalArgumentException("Ed25519 signature must be 64 bytes, got " + signature.length);
        }
        if (!scalarIsCanonical(signature) || hasSmallOrder(signature)
                || !pointIsCanonical(publicKey) || hasSmallOrder(publicKey)) {
            return false;
        }
        return Ed25519Equation.holds(publicKey, message, signature);
    }

    /** libsodium {@code sc25519_is_canonical(sig + 32)}: {@code S < L}. */
    private static boolean scalarIsCanonical(byte[] signature) {
        for (int i = 31; i >= 0; i--) {
            int s = signature[32 + i] & 0xff;
            int l = L[i] & 0xff;
            if (s != l) {
                return s < l;
            }
        }
        return false;
    }

    /** libsodium {@code ge25519_has_small_order}: the first 32 bytes are on the list. */
    private static boolean hasSmallOrder(byte[] encoded) {
        for (byte[] point : SMALL_ORDER) {
            boolean match = true;
            for (int i = 0; i < 31 && match; i++) {
                match = encoded[i] == point[i];
            }
            if (match && (encoded[31] & 0x7f) == (point[31] & 0xff)) {
                return true;
            }
        }
        return false;
    }

    /** libsodium {@code ge25519_is_canonical}: the encoded {@code y}, sign bit aside, is below {@code p}. */
    private static boolean pointIsCanonical(byte[] point) {
        boolean allOnes = (point[31] & 0x7f) == 0x7f;
        for (int i = 30; i > 0 && allOnes; i--) {
            allOnes = (point[i] & 0xff) == 0xff;
        }
        return !(allOnes && (point[0] & 0xff) >= 0xed);
    }

    private static byte[] bytes(String hex) {
        return HexFormat.of().parseHex(hex);
    }
}
