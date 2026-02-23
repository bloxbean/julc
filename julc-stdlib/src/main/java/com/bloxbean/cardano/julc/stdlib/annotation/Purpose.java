package com.bloxbean.cardano.julc.stdlib.annotation;

/**
 * The script purpose for a multi-validator entrypoint.
 * <p>
 * Maps to ScriptInfo constructor tags in Plutus V3:
 * <ul>
 *   <li>{@link #MINT} - tag 0 (MintingScript)</li>
 *   <li>{@link #SPEND} - tag 1 (SpendingScript)</li>
 *   <li>{@link #WITHDRAW} - tag 2 (RewardingScript)</li>
 *   <li>{@link #CERTIFY} - tag 3 (CertifyingScript)</li>
 *   <li>{@link #VOTE} - tag 4 (VotingScript)</li>
 *   <li>{@link #PROPOSE} - tag 5 (ProposingScript)</li>
 * </ul>
 * <p>
 * {@link #DEFAULT} means no auto-dispatch; the user handles ScriptInfo switching manually.
 */
public enum Purpose {
    DEFAULT,
    MINT,
    SPEND,
    WITHDRAW,
    CERTIFY,
    VOTE,
    PROPOSE
}
