package org.julclang.vm.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ed25519VerifierTest {

    private static final byte[] KEY = bytes("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
    private static final byte[] SIGNATURE = bytes("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
            + "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b");

    /**
     * Edge cases with the outcomes of libsodium's {@code crypto_sign_open}, as PyNaCl 1.6.2 bundles it. The
     * {@code speccheck} rows are novifinancial/ed25519-speccheck {@code cases.json} at commit
     * 65519336fda78a3d016e947df6d82848aca0c9da (Apache-2.0); the others are signatures made for this test.
     */
    @ParameterizedTest(name = "{0} -> {4}")
    @CsvSource({
            "an ordinary signature, 6a756c632d70726f6265, 40147a199bce475942df78e84a62a5a5aa6abf46b1caddede017c3b1fc3ef9e5, c46eab6fe2b5bddff5712e725acf6f4cebfac397d9b68abdfd64074b492c86dc0a1e7a5d34fa4f1df9a839eef8cac469c10a40dc06b5a4f71c8600dd201ce305, true",
            "R of order 8, 6a756c632d70726f6265, 40147a199bce475942df78e84a62a5a5aa6abf46b1caddede017c3b1fc3ef9e5, c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a9d48dda7a96940fbaca991f040c6ee356996489a3d8fb39e4f5b2e95cf375c0b, false",
            "R the identity, 6a756c632d70726f6265, 40147a199bce475942df78e84a62a5a5aa6abf46b1caddede017c3b1fc3ef9e5, 01000000000000000000000000000000000000000000000000000000000000000d8ee98ad0d6f8f7ece9a6d946b1fe1a9c430ece529acb6b80b9f11c8a782f07, false",
            "R with a small-order part, 6a756c632d70726f6265, 40147a199bce475942df78e84a62a5a5aa6abf46b1caddede017c3b1fc3ef9e5, 1e8da9b152562713f9977c865cbb713fcaab10bf088a23cad3f52efe7b911939415815f7f5fb18c17571b3c875c1f47fd21e4a27389f3f4f06671ce0f44fea09, false",
            "a key with a small-order part, 6a756c632d70726f6265, e41e6689d7bfa3a2e42f7fdf63b8126834ad2246042ceae981f313a41bcee2f4, 7b5d50d371ef5c8a44a9aeeadab22055a561af63bd48b67086f9aba22458c668a9f2fd3959b71cd894c6ea525715ecf243c9bab9726f457dc4d182ee329bd60d, false",
            "speccheck 0, 8c93255d71dcab10e8f379c26200f3c7bd5f09d9bc3068d3ef4edeb4853022b6, c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac03fa, c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a0000000000000000000000000000000000000000000000000000000000000000, false",
            "speccheck 1, 9bd9f44f4dcc75bd531b56b2cd280b0bb38fc1cd6d1230e14861d861de092e79, c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac03fa, f7badec5b8abeaf699583992219b7b223f1df3fbbea919844e3f7c554a43dd43a5bb704786be79fc476f91d3f3f89b03984d8068dcf1bb7dfc6637b45450ac04, false",
            "speccheck 2, aebf3f2601a0c8c5d39cc7d8911642f740b78168218da8471772b35f9d35b9ab, f7badec5b8abeaf699583992219b7b223f1df3fbbea919844e3f7c554a43dd43, c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac03fa8c4bd45aecaca5b24fb97bc10ac27ac8751a7dfe1baff8b953ec9f5833ca260e, false",
            "speccheck 3, 9bd9f44f4dcc75bd531b56b2cd280b0bb38fc1cd6d1230e14861d861de092e79, cdb267ce40c5cd45306fa5d2f29731459387dbf9eb933b7bd5aed9a765b88d4d, 9046a64750444938de19f227bb80485e92b83fdb4b6506c160484c016cc1852f87909e14428a7a1d62e9f22f3d3ad7802db02eb2e688b6c52fcd6648a98bd009, true",
            "speccheck 4, e47d62c63f830dc7a6851a0b1f33ae4bb2f507fb6cffec4011eaccd55b53f56c, cdb267ce40c5cd45306fa5d2f29731459387dbf9eb933b7bd5aed9a765b88d4d, 160a1cb0dc9c0258cd0a7d23e94d8fa878bcb1925f2c64246b2dee1796bed5125ec6bc982a269b723e0668e540911a9a6a58921d6925e434ab10aa7940551a09, false",
            "speccheck 5, e47d62c63f830dc7a6851a0b1f33ae4bb2f507fb6cffec4011eaccd55b53f56c, cdb267ce40c5cd45306fa5d2f29731459387dbf9eb933b7bd5aed9a765b88d4d, 21122a84e0b5fca4052f5b1235c80a537878b38f3142356b2c2384ebad4668b7e40bc836dac0f71076f9abe3a53f9c03c1ceeeddb658d0030494ace586687405, false",
            "speccheck 6, 85e241a07d148b41e47d62c63f830dc7a6851a0b1f33ae4bb2f507fb6cffec40, 442aad9f089ad9e14647b1ef9099a1ff4798d78589e66f28eca69c11f582a623, e96f66be976d82e60150baecff9906684aebb1ef181f67a7189ac78ea23b6c0e547f7690a0e2ddcd04d87dbc3490dc19b3b3052f7ff0538cb68afb369ba3a514, false",
            "speccheck 7, 85e241a07d148b41e47d62c63f830dc7a6851a0b1f33ae4bb2f507fb6cffec40, 442aad9f089ad9e14647b1ef9099a1ff4798d78589e66f28eca69c11f582a623, 8ce5b96c8f26d0ab6c47958c9e68b937104cd36e13c33566acd2fe8d38aa19427e71f98a473474f2f13f06f97c20d58cc3f54b8bd0d272f42b695dd7e89a8c22, false",
            "speccheck 8, 9bedc267423725d473888631ebf45988bad3db83851ee85c85e241a07d148b41, f7badec5b8abeaf699583992219b7b223f1df3fbbea919844e3f7c554a43dd43, ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff03be9678ac102edcd92b0210bb34d7428d12ffc5df5f37e359941266a4e35f0f, false",
            "speccheck 9, 9bedc267423725d473888631ebf45988bad3db83851ee85c85e241a07d148b41, f7badec5b8abeaf699583992219b7b223f1df3fbbea919844e3f7c554a43dd43, ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffca8c5b64cd208982aa38d4936621a4775aa233aa0505711d8fdcfdaa943d4908, false",
            "speccheck 10, e96b7021eb39c1a163b6da4e3093dcd3f21387da4cc4572be588fafae23c155b, ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff, a9d55260f765261eb9b84e106f665e00b867287a761990d7135963ee0a7d59dca5bb704786be79fc476f91d3f3f89b03984d8068dcf1bb7dfc6637b45450ac04, false",
            "speccheck 11, 39a591f5321bbe07fd5a23dc2f39d025d74526615746727ceefd6e82ae65c06f, ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff, a9d55260f765261eb9b84e106f665e00b867287a761990d7135963ee0a7d59dca5bb704786be79fc476f91d3f3f89b03984d8068dcf1bb7dfc6637b45450ac04, false"
    })
    void hasTheOutcomesOfLibsodium(String name, String message, String key, String signature, boolean expected) {
        assertEquals(expected, Ed25519Verifier.verify(bytes(key), bytes(message), bytes(signature)), name);
    }

    @Test
    void acceptsTheRfc8032VectorOverTheEmptyMessage() {
        assertTrue(Ed25519Verifier.verify(KEY, new byte[0], SIGNATURE));
    }

    @Test
    void refusesAnotherMessage() {
        assertFalse(Ed25519Verifier.verify(KEY, new byte[]{1}, SIGNATURE));
    }

    @Test
    void refusesTheSameScalarWithLAdded() {
        byte[] overflowing = SIGNATURE.clone();
        byte[] order = bytes("edd3f55c1a631258d69cf7a2def9de1400000000000000000000000000000010");
        int carry = 0;
        for (int i = 0; i < 32; i++) {
            int sum = (overflowing[32 + i] & 0xff) + (order[i] & 0xff) + carry;
            overflowing[32 + i] = (byte) sum;
            carry = sum >> 8;
        }
        assertEquals(0, carry);
        assertFalse(Ed25519Verifier.verify(KEY, new byte[0], overflowing));
    }

    @Test
    void refusesAKeyThatIsNotAPoint() {
        byte[] notAPoint = new byte[32];
        notAPoint[0] = 2;
        assertFalse(Ed25519Verifier.verify(notAPoint, new byte[0], SIGNATURE));
    }

    @Test
    void failsOnAnotherLength() {
        assertThrows(IllegalArgumentException.class,
                () -> Ed25519Verifier.verify(Arrays.copyOf(KEY, 31), new byte[0], SIGNATURE));
        assertThrows(IllegalArgumentException.class,
                () -> Ed25519Verifier.verify(KEY, new byte[0], Arrays.copyOf(SIGNATURE, 65)));
    }

    private static byte[] bytes(String hex) {
        return HexFormat.of().parseHex(hex);
    }
}
