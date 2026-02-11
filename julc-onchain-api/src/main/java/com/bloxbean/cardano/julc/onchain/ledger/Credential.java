package com.bloxbean.cardano.julc.onchain.ledger;

/**
 * A payment credential: either a public key hash or a script hash.
 * <p>
 * This is an IDE stub. The compiler uses the schema from LedgerTypeRegistry.
 */
public sealed interface Credential {
    record PubKeyCredential(byte[] hash) implements Credential {}
    record ScriptCredential(byte[] hash) implements Credential {}
}
