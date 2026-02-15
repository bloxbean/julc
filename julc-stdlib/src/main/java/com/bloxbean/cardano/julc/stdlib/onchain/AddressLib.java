package com.bloxbean.cardano.julc.stdlib.onchain;

import com.bloxbean.cardano.julc.onchain.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.onchain.ledger.Address;
import com.bloxbean.cardano.julc.onchain.ledger.Credential;

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
    public static byte[] credentialHash(Address address) {
        return switch (address.credential()) {
            case Credential.PubKeyCredential pk -> pk.hash();
            case Credential.ScriptCredential sc -> sc.hash();
        };
    }

    /** Check if an address has a ScriptCredential (tag == 1). */
    public static boolean isScriptAddress(Address address) {
        return switch (address.credential()) {
            case Credential.ScriptCredential sc -> true;
            default -> false;
        };
    }

    /** Check if an address has a PubKeyCredential (tag == 0). */
    public static boolean isPubKeyAddress(Address address) {
        return switch (address.credential()) {
            case Credential.PubKeyCredential pk -> true;
            default -> false;
        };
    }

    /** Extract the payment credential from an address. */
    public static Credential paymentCredential(Address address) {
        return address.credential();
    }
}
