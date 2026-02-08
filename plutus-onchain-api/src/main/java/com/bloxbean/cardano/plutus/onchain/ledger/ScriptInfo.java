package com.bloxbean.cardano.plutus.onchain.ledger;

import com.bloxbean.cardano.plutus.core.PlutusData;

import java.math.BigInteger;

/**
 * Information about the script being executed (purpose and context).
 * <p>
 * This is an IDE stub. The compiler uses the schema from LedgerTypeRegistry.
 */
public sealed interface ScriptInfo {
    record MintingScript(byte[] policyId) implements ScriptInfo {}
    record SpendingScript(PlutusData txOutRef, PlutusData datum) implements ScriptInfo {}
    record RewardingScript(PlutusData credential) implements ScriptInfo {}
    record CertifyingScript(BigInteger index, PlutusData cert) implements ScriptInfo {}
    record VotingScript(PlutusData voter) implements ScriptInfo {}
    record ProposingScript(BigInteger index, PlutusData procedure) implements ScriptInfo {}
}
