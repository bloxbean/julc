package org.julclang.vm.java.builtins;

import org.julclang.core.Constant;
import org.julclang.vm.java.CekValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code verifyEcdsaSecp256k1Signature} must match Plutus
 * ({@code PlutusCore.Crypto.Secp256k1}, libsecp256k1): only arguments that fail
 * to deserialise are evaluation errors (bad lengths, invalid public key,
 * {@code r} or {@code s} &ge; n). Every other verification failure, including
 * {@code r = 0}, {@code s = 0} and high {@code s}, is {@code False}.
 * The key is checked before the signature, so an invalid key is always an error.
 */
class EcdsaSecp256k1BuiltinTest {

    // Keypair, message hash sha2_256("") and signatures from the Plutus conformance
    // suite (verifyEcdsaSecp256k1Signature/test-vector-01, -03 and -16).
    private static final String KEY = "032e433589dce61863199171f4d1e3fa946a5832621fcd29559940a0950f96fb6f";
    private static final String MSG = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String R = "4941155e2303988a1be97a021fbaf9fe6064d05ea694bc5e89328f297154e5c6";
    private static final String S = "3a2f3e7b5f509294a4c2e22feb697a16b792fabfebe9d0f38403b1c929836b5a";
    private static final String HIGH_S_SIG = "603e6e7bf67152188204f76f6274d38c477bdbc3954cdaa44e4ef49691a517de"
            + "d5eaad30c2e69e3e9b124bcc48fa0e4d0aa0dfdb4ca9d537ea1dcd46c94b8f56";
    private static final String OFF_CURVE_KEY = "032e433589dce61863199171f4d1e3fa946a5832621fcd29559940a0950f96fb61";
    /** x = 2^256 - 1, which is not below the field prime p. */
    private static final String X_ABOVE_P_KEY = "02" + "ff".repeat(32);

    private static final String ZERO = "00".repeat(32);
    /** The secp256k1 group order n. */
    private static final String N = "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141";
    private static final String N_MINUS_1 = "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364140";

    @Test
    void validSignatureVerifies() {
        assertEquals(true, verify(KEY, MSG, R + S));
    }

    /** Preprod tx 97b31e354e1946203be90ae78cc1cba21a70a5cbd7b39b6b82d273a98e323531, script 9dd6dd04…. */
    @Test
    void zeroSignatureFromPreprodReturnsFalse() {
        assertEquals(false, verify(
                "03128dce1f96967bb339202f6649cc7b3132585428a1d6f825349d8c98c656f707",
                "d3444c2dad5f0229ca3e517eddddc8f61f53e804d8a0edb42c2d7bdb5dd05c00",
                ZERO + ZERO));
    }

    @ParameterizedTest(name = "r={0}, s={1}")
    @CsvSource({
            "zero, low",
            "low, zero",
            "zero, zero",
            "nMinus1, low",
            "low, nMinus1",
    })
    void inRangeInvalidSignatureReturnsFalse(String r, String s) {
        assertEquals(false, verify(KEY, MSG, scalar(r) + scalar(s)));
    }

    @Test
    void highSReturnsFalse() {
        assertEquals(false, verify(KEY, MSG, HIGH_S_SIG));
    }

    @ParameterizedTest(name = "r={0}, s={1}")
    @CsvSource({
            "n, low",
            "low, n",
            "max, low",
            "low, max",
    })
    void scalarNotBelowOrderIsError(String r, String s) {
        assertError(KEY, MSG, scalar(r) + scalar(s));
    }

    @ParameterizedTest(name = "key={0}")
    @CsvSource({"offCurve", "xAboveP", "uncompressedPrefix", "zeroPrefix"})
    void invalidKeyIsErrorEvenWhenSignatureWouldBeFalse(String key) {
        String keyHex = switch (key) {
            case "offCurve" -> OFF_CURVE_KEY;
            case "xAboveP" -> X_ABOVE_P_KEY;
            case "uncompressedPrefix" -> "04" + KEY.substring(2);
            case "zeroPrefix" -> "00" + KEY.substring(2);
            default -> throw new IllegalArgumentException(key);
        };
        assertError(keyHex, MSG, ZERO + ZERO);
        assertError(keyHex, MSG, R + ZERO);
        assertError(keyHex, MSG, HIGH_S_SIG);
        assertError(keyHex, MSG, R + S);
    }

    @Test
    void wrongLengthsAreErrors() {
        assertError(KEY.substring(2), MSG, R + S);
        assertError(KEY, MSG.substring(2), R + S);
        assertError(KEY, MSG, R + S.substring(2));
    }

    private static String scalar(String name) {
        return switch (name) {
            case "zero" -> ZERO;
            case "low" -> S;
            case "nMinus1" -> N_MINUS_1;
            case "n" -> N;
            case "max" -> "ff".repeat(32);
            default -> throw new IllegalArgumentException(name);
        };
    }

    private static boolean verify(String key, String msg, String sig) {
        var result = CryptoBuiltins.verifyEcdsaSecp256k1Signature(args(key, msg, sig));
        return ((Constant.BoolConst) ((CekValue.VCon) result).constant()).value();
    }

    private static void assertError(String key, String msg, String sig) {
        assertThrows(BuiltinException.class,
                () -> CryptoBuiltins.verifyEcdsaSecp256k1Signature(args(key, msg, sig)));
    }

    private static List<CekValue> args(String... hex) {
        var hexFormat = HexFormat.of();
        return List.of(
                new CekValue.VCon(Constant.byteString(hexFormat.parseHex(hex[0]))),
                new CekValue.VCon(Constant.byteString(hexFormat.parseHex(hex[1]))),
                new CekValue.VCon(Constant.byteString(hexFormat.parseHex(hex[2]))));
    }
}
