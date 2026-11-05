package org.julclang.testkit;

import org.julclang.stdlib.Builtins;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JVM's cryptography against the script's: Plutus 1.63.0.0's {@code PlutusCore.Crypto} is the truth, and the VM its copy here.
 */
class JvmCryptoProviderTest {

    private static final JvmCryptoProvider PROVIDER = new JvmCryptoProvider();

    /** RFC 8032, section 7.1, test 1: an empty message. */
    private static final byte[] KEY = HexFormat.of().parseHex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
    private static final byte[] SIGNATURE = HexFormat.of().parseHex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
            + "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b");

    @BeforeAll
    static void setup() {
        Builtins.setCryptoProvider(PROVIDER);
    }

    interface Hash {
        byte[] hash(byte[] bytes);
    }

    interface Verify {
        boolean verify(byte[] key, byte[] message, byte[] signature);
    }

    private static Hash onTheVm(String builtin) {
        return JulcEval.forSource("""
                import org.julclang.stdlib.Builtins;
                class Hashes { static byte[] hash(byte[] bytes) { return Builtins.%s(bytes); } }
                """.formatted(builtin)).create(Hash.class);
    }

    private static UnaryOperator<byte[]> onTheJvm(String builtin) {
        return switch (builtin) {
            case "sha2_256" -> PROVIDER::sha2_256;
            case "sha3_256" -> PROVIDER::sha3_256;
            case "blake2b_256" -> PROVIDER::blake2b_256;
            case "blake2b_224" -> PROVIDER::blake2b_224;
            case "keccak_256" -> PROVIDER::keccak_256;
            case "ripemd_160" -> PROVIDER::ripemd_160;
            default -> throw new IllegalArgumentException(builtin);
        };
    }

    @ParameterizedTest
    @ValueSource(strings = {"sha2_256", "sha3_256", "blake2b_256", "blake2b_224", "keccak_256", "ripemd_160"})
    void aHashIsTheScriptsHash(String builtin) {
        var script = onTheVm(builtin);
        var jvm = onTheJvm(builtin);
        for (int size : new int[]{0, 1, 31, 32, 33, 135, 136, 137, 1024}) {
            var bytes = new byte[size];
            for (int i = 0; i < size; i++) bytes[i] = (byte) (i * 7);
            assertArrayEquals(script.hash(bytes), jvm.apply(bytes), builtin + " of " + size + " bytes");
        }
    }

    private static Verify verifyOnTheVm() {
        return JulcEval.forSource("""
                import org.julclang.stdlib.Builtins;
                class Signatures {
                    static boolean verify(byte[] key, byte[] message, byte[] signature) {
                        return Builtins.verifyEd25519Signature(key, message, signature);
                    }
                }
                """).create(Verify.class);
    }

    @Test
    void aValidSignatureVerifies() {
        assertTrue(PROVIDER.verifyEd25519Signature(KEY, new byte[0], SIGNATURE));
        assertTrue(verifyOnTheVm().verify(KEY, new byte[0], SIGNATURE));
    }

    @Test
    void aWrongSignatureOfTheRightLengthIsFalse() {
        var wrong = SIGNATURE.clone();
        wrong[0] ^= 1;
        assertFalse(PROVIDER.verifyEd25519Signature(KEY, new byte[0], wrong));
        assertFalse(verifyOnTheVm().verify(KEY, new byte[0], wrong));
    }

    @Test
    void aKeyOfTheRightLengthThatIsNoPointIsFalse() {
        var notAPoint = new byte[32];
        Arrays.fill(notAPoint, (byte) 0xff);
        assertFalse(PROVIDER.verifyEd25519Signature(notAPoint, new byte[0], SIGNATURE));
        assertFalse(verifyOnTheVm().verify(notAPoint, new byte[0], SIGNATURE));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 31, 33})
    void aKeyOfAnotherLengthFails(int size) {
        var key = new byte[size];
        System.arraycopy(KEY, 0, key, 0, Math.min(size, 32));
        var failure = assertThrows(IllegalArgumentException.class, () -> PROVIDER.verifyEd25519Signature(key, new byte[0], SIGNATURE));
        assertEquals("Ed25519 public key must be 32 bytes, got " + size, failure.getMessage());
        assertThrows(RuntimeException.class, () -> verifyOnTheVm().verify(key, new byte[0], SIGNATURE));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 63, 65})
    void aSignatureOfAnotherLengthFails(int size) {
        var signature = new byte[size];
        System.arraycopy(SIGNATURE, 0, signature, 0, Math.min(size, 64));
        var failure = assertThrows(IllegalArgumentException.class, () -> PROVIDER.verifyEd25519Signature(KEY, new byte[0], signature));
        assertEquals("Ed25519 signature must be 64 bytes, got " + size, failure.getMessage());
        assertThrows(RuntimeException.class, () -> verifyOnTheVm().verify(KEY, new byte[0], signature));
    }
}
