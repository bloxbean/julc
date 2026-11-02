package org.julclang.testkit;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The testkit's Ed25519 has the outcomes of the builtin. */
class JvmCryptoProviderEd25519Test {

    private static final byte[] KEY = bytes("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
    private static final byte[] SIGNATURE = bytes("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
            + "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b");

    private final JvmCryptoProvider provider = new JvmCryptoProvider();

    @Test
    void anOrdinarySignatureVerifies() {
        assertTrue(provider.verifyEd25519Signature(KEY, new byte[0], SIGNATURE));
    }

    /** ed25519-speccheck vector 4, which a cofactored check accepts and libsodium does not. */
    @Test
    void aSignatureOnlyTheCofactoredEquationAcceptsDoesNot() {
        assertFalse(provider.verifyEd25519Signature(
                bytes("cdb267ce40c5cd45306fa5d2f29731459387dbf9eb933b7bd5aed9a765b88d4d"),
                bytes("e47d62c63f830dc7a6851a0b1f33ae4bb2f507fb6cffec4011eaccd55b53f56c"),
                bytes("160a1cb0dc9c0258cd0a7d23e94d8fa878bcb1925f2c64246b2dee1796bed512"
                        + "5ec6bc982a269b723e0668e540911a9a6a58921d6925e434ab10aa7940551a09")));
    }

    @Test
    void anotherLengthFails() {
        assertThrows(IllegalArgumentException.class,
                () -> provider.verifyEd25519Signature(new byte[31], new byte[0], SIGNATURE));
        assertThrows(IllegalArgumentException.class,
                () -> provider.verifyEd25519Signature(KEY, new byte[0], new byte[63]));
    }

    private static byte[] bytes(String hex) {
        return HexFormat.of().parseHex(hex);
    }
}
