package org.julclang.vm.java.builtins;

import org.julclang.core.Constant;
import org.julclang.vm.java.CekValue;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@code verifyEd25519Signature} has the node's outcomes: false for a verifier's no, failure for a bad length. */
class Ed25519BuiltinTest {

    private static final String KEY = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
    private static final String SIGNATURE = "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
            + "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b";

    @Test
    void anOrdinarySignatureIsTrue() {
        assertEquals(BuiltinHelper.mkBool(true), CryptoBuiltins.verifyEd25519Signature(args(KEY, "", SIGNATURE)));
    }

    /** ed25519-speccheck vector 4, which a cofactored check accepts and libsodium does not. */
    @Test
    void aSignatureOnlyTheCofactoredEquationAcceptsIsFalse() {
        var result = CryptoBuiltins.verifyEd25519Signature(args(
                "cdb267ce40c5cd45306fa5d2f29731459387dbf9eb933b7bd5aed9a765b88d4d",
                "e47d62c63f830dc7a6851a0b1f33ae4bb2f507fb6cffec4011eaccd55b53f56c",
                "160a1cb0dc9c0258cd0a7d23e94d8fa878bcb1925f2c64246b2dee1796bed512"
                        + "5ec6bc982a269b723e0668e540911a9a6a58921d6925e434ab10aa7940551a09"));
        assertEquals(BuiltinHelper.mkBool(false), result);
    }

    @Test
    void anotherLengthFails() {
        assertThrows(BuiltinException.class,
                () -> CryptoBuiltins.verifyEd25519Signature(args(KEY.substring(2), "", SIGNATURE)));
        assertThrows(BuiltinException.class,
                () -> CryptoBuiltins.verifyEd25519Signature(args(KEY, "", SIGNATURE + "00")));
    }

    private static List<CekValue> args(String... hex) {
        var hexFormat = HexFormat.of();
        return List.of(
                new CekValue.VCon(Constant.byteString(hexFormat.parseHex(hex[0]))),
                new CekValue.VCon(Constant.byteString(hexFormat.parseHex(hex[1]))),
                new CekValue.VCon(Constant.byteString(hexFormat.parseHex(hex[2]))));
    }
}
