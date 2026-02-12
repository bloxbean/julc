package com.bloxbean.cardano.julc.stdlib.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.onchain.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.onchain.stdlib.Builtins;

/**
 * Address operations compiled from Java source to UPLC.
 * <p>
 * Address encoding in Plutus Data:
 * <pre>
 * Address = Constr(0, [credential: Credential, stakingCredential: Data])
 * Credential:
 *   PubKeyCredential  = Constr(0, [hash: ByteString])
 *   ScriptCredential  = Constr(1, [hash: ByteString])
 * </pre>
 */
@OnchainLibrary
public class AddressLib {

    /** Extract the hash from the payment credential of an address.
     *  Works for both PubKeyCredential and ScriptCredential. */
    public static PlutusData.BytesData credentialHash(PlutusData address) {
        var addrFields = Builtins.constrFields(address);
        var credential = Builtins.headList(addrFields);
        var credFields = Builtins.constrFields(credential);
        return Builtins.unBData(Builtins.headList(credFields));
    }

    /** Check if an address has a ScriptCredential (tag == 1). */
    public static boolean isScriptAddress(PlutusData address) {
        var addrFields = Builtins.constrFields(address);
        var credential = Builtins.headList(addrFields);
        return Builtins.constrTag(credential) == 1;
    }

    /** Check if an address has a PubKeyCredential (tag == 0). */
    public static boolean isPubKeyAddress(PlutusData address) {
        var addrFields = Builtins.constrFields(address);
        var credential = Builtins.headList(addrFields);
        return Builtins.constrTag(credential) == 0;
    }

    /** Extract the payment credential from an address (field 0). */
    public static PlutusData paymentCredential(PlutusData address) {
        var addrFields = Builtins.constrFields(address);
        return Builtins.headList(addrFields);
    }
}
