package com.bloxbean.cardano.julc.clientlib.eval;

import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.StakingCredential;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CclAddressConverterTest {

    @Test
    void fromBech32_enterprisePubKey() {
        // Build an enterprise PubKey address using CCL
        byte[] pubKeyHash = new byte[28];
        java.util.Arrays.fill(pubKeyHash, (byte) 0x01);

        var cclAddr = com.bloxbean.cardano.client.address.AddressProvider
                .getEntAddress(
                        com.bloxbean.cardano.client.address.Credential.fromKey(pubKeyHash),
                        com.bloxbean.cardano.client.common.model.Networks.testnet());
        Address address = CclAddressConverter.fromCclAddress(
                new com.bloxbean.cardano.client.address.Address(cclAddr.toBech32()));

        assertNotNull(address);
        assertInstanceOf(Credential.PubKeyCredential.class, address.credential());
        assertTrue(address.stakingCredential().isEmpty());
    }

    @Test
    void fromBech32_scriptAddress() {
        // Build a script address using CCL's Address utilities
        byte[] scriptHash = new byte[28];
        java.util.Arrays.fill(scriptHash, (byte) 0xAB);

        var cclAddr = com.bloxbean.cardano.client.address.AddressProvider
                .getEntAddress(
                        com.bloxbean.cardano.client.address.Credential.fromScript(scriptHash),
                        com.bloxbean.cardano.client.common.model.Networks.testnet());

        Address address = CclAddressConverter.fromCclAddress(
                new com.bloxbean.cardano.client.address.Address(cclAddr.toBech32()));

        assertNotNull(address);
        assertInstanceOf(Credential.ScriptCredential.class, address.credential());
    }

    @Test
    void fromBech32_baseAddress_pubKeyWithStaking() {
        // Base testnet address with staking credential
        // Use AddressService to create one
        byte[] paymentHash = new byte[28];
        byte[] stakingHash = new byte[28];
        java.util.Arrays.fill(paymentHash, (byte) 0x01);
        java.util.Arrays.fill(stakingHash, (byte) 0x02);

        var cclAddr = com.bloxbean.cardano.client.address.AddressProvider
                .getBaseAddress(
                        com.bloxbean.cardano.client.address.Credential.fromKey(paymentHash),
                        com.bloxbean.cardano.client.address.Credential.fromKey(stakingHash),
                        com.bloxbean.cardano.client.common.model.Networks.testnet());

        Address address = CclAddressConverter.fromCclAddress(
                new com.bloxbean.cardano.client.address.Address(cclAddr.toBech32()));

        assertNotNull(address);
        assertInstanceOf(Credential.PubKeyCredential.class, address.credential());
        assertTrue(address.stakingCredential().isPresent());
        assertInstanceOf(StakingCredential.StakingHash.class, address.stakingCredential().get());

        var stakingCred = (StakingCredential.StakingHash) address.stakingCredential().get();
        assertInstanceOf(Credential.PubKeyCredential.class, stakingCred.credential());
    }
}
