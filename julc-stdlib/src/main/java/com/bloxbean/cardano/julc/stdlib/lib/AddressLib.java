package com.bloxbean.cardano.julc.stdlib.lib;

import com.bloxbean.cardano.julc.onchain.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.Credential;

/**
 * Address operations compiled from Java source to UPLC.
 * <p>
 * Uses ledger types (Address, Credential) for readability.
 * Works both on-chain (compiled to UPLC) and off-chain (as plain Java).
 */
@OnchainLibrary
public class AddressLib {

    /** Extract the hash from the payment credential of an address.
     *  Works for both PubKeyCredential and ScriptCredential.
     *  Uses chained field access: Credential variant -> hash wrapper -> byte[]. */
    public static byte[] credentialHash(Address address) {
        return switch (address.credential()) {
            case Credential.PubKeyCredential pk -> (byte[])(Object) pk.hash();
            case Credential.ScriptCredential sc -> (byte[])(Object) sc.hash();
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
