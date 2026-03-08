package com.bloxbean.cardano.julc.stdlib.lib;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.testkit.JulcEval;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.bloxbean.cardano.julc.stdlib.lib.StdlibTestHelpers.*;
import static org.junit.jupiter.api.Assertions.*;

class AddressLibTest {

    static JulcEval eval;

    static final byte[] PKH = makeBytes(28, 0x01);
    static final byte[] SCRIPT_HASH = makeBytes(28, 0x10);

    @BeforeAll
    static void setUp() {
        eval = JulcEval.forClass(AddressLib.class);
    }

    @Nested
    class CredentialHash {

        @Test
        void extractsFromPubKeyAddress() {
            byte[] result = eval.call("credentialHash", pubKeyAddress(PKH)).asByteString();
            assertArrayEquals(PKH, result);
        }

        @Test
        void extractsFromScriptAddress() {
            byte[] result = eval.call("credentialHash", scriptAddress(SCRIPT_HASH)).asByteString();
            assertArrayEquals(SCRIPT_HASH, result);
        }
    }

    @Nested
    class IsScriptAddress {

        @Test
        void trueForScript() {
            assertTrue(eval.call("isScriptAddress", scriptAddress(SCRIPT_HASH)).asBoolean());
        }

        @Test
        void falseForPubKey() {
            assertFalse(eval.call("isScriptAddress", pubKeyAddress(PKH)).asBoolean());
        }
    }

    @Nested
    class IsPubKeyAddress {

        @Test
        void trueForPubKey() {
            assertTrue(eval.call("isPubKeyAddress", pubKeyAddress(PKH)).asBoolean());
        }

        @Test
        void falseForScript() {
            assertFalse(eval.call("isPubKeyAddress", scriptAddress(SCRIPT_HASH)).asBoolean());
        }
    }

    @Nested
    class PaymentCredential {

        @Test
        void returnsPubKeyCredential() {
            PlutusData result = eval.call("paymentCredential", pubKeyAddress(PKH)).asData();
            assertInstanceOf(PlutusData.ConstrData.class, result);
            // PubKeyCredential = Constr(0, [BData(pkh)])
            assertEquals(0, ((PlutusData.ConstrData) result).tag());
        }

        @Test
        void returnsScriptCredential() {
            PlutusData result = eval.call("paymentCredential", scriptAddress(SCRIPT_HASH)).asData();
            assertInstanceOf(PlutusData.ConstrData.class, result);
            // ScriptCredential = Constr(1, [BData(hash)])
            assertEquals(1, ((PlutusData.ConstrData) result).tag());
        }
    }
}
