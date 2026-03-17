package com.bloxbean.cardano.julc.clientlib.eval;

import com.bloxbean.cardano.client.address.AddressType;
import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.PubKeyHash;
import com.bloxbean.cardano.julc.ledger.ScriptHash;
import com.bloxbean.cardano.julc.ledger.StakingCredential;

import java.util.Optional;

/**
 * Converts CCL bech32 address strings to JuLC {@link Address}.
 */
final class CclAddressConverter {

    private CclAddressConverter() {}

    /**
     * Parse a bech32 address string into a JuLC {@link Address}.
     */
    static Address fromBech32(String bech32) {
        var cclAddr = new com.bloxbean.cardano.client.address.Address(bech32);
        return fromCclAddress(cclAddr);
    }

    /**
     * Convert a CCL {@link com.bloxbean.cardano.client.address.Address} to JuLC {@link Address}.
     */
    static Address fromCclAddress(com.bloxbean.cardano.client.address.Address cclAddr) {
        // Payment credential
        Credential paymentCred = extractPaymentCredential(cclAddr);

        // Staking credential (optional)
        Optional<StakingCredential> stakingCred = extractStakingCredential(cclAddr);

        return new Address(paymentCred, stakingCred);
    }

    private static Credential extractPaymentCredential(com.bloxbean.cardano.client.address.Address cclAddr) {
        byte[] paymentHash = cclAddr.getPaymentCredentialHash()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Address has no payment credential: " + cclAddr.toBech32()));

        if (cclAddr.isScriptHashInPaymentPart()) {
            return new Credential.ScriptCredential(ScriptHash.of(paymentHash));
        } else {
            return new Credential.PubKeyCredential(PubKeyHash.of(paymentHash));
        }
    }

    private static Optional<StakingCredential> extractStakingCredential(
            com.bloxbean.cardano.client.address.Address cclAddr) {
        AddressType addrType = cclAddr.getAddressType();

        // Enterprise and Reward addresses have no delegation part
        if (addrType == AddressType.Enterprise || addrType == AddressType.Reward) {
            return Optional.empty();
        }

        return cclAddr.getDelegationCredentialHash().map(delegHash -> {
            Credential delegCred;
            if (cclAddr.isScriptHashInDelegationPart()) {
                delegCred = new Credential.ScriptCredential(ScriptHash.of(delegHash));
            } else {
                delegCred = new Credential.PubKeyCredential(PubKeyHash.of(delegHash));
            }
            return new StakingCredential.StakingHash(delegCred);
        });
    }
}
