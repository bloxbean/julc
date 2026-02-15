package com.bloxbean.cardano.julc.onchain.stdlib;

import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.Credential;

/**
 * On-chain address operations.
 * <p>
 * These methods are executable both on-chain (compiled to UPLC via the onchain source)
 * and off-chain (as plain Java for debugging and testing).
 */
public final class AddressLib {

    private AddressLib() {}

    /** Extract the hash from the payment credential of an address.
     *  Works for both PubKeyCredential and ScriptCredential. */
    public static byte[] credentialHash(Address address) {
        return switch (address.credential()) {
            case Credential.PubKeyCredential pk -> pk.hash().hash();
            case Credential.ScriptCredential sc -> sc.hash().hash();
        };
    }

    /** Check if an address has a ScriptCredential. */
    public static boolean isScriptAddress(Address address) {
        return address.credential() instanceof Credential.ScriptCredential;
    }

    /** Check if an address has a PubKeyCredential. */
    public static boolean isPubKeyAddress(Address address) {
        return address.credential() instanceof Credential.PubKeyCredential;
    }

    /** Extract the payment credential from an address. */
    public static Credential paymentCredential(Address address) {
        return address.credential();
    }
}
