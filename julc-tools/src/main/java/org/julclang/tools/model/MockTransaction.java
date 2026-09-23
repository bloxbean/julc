package org.julclang.tools.model;

import java.util.List;

/**
 * A mock transaction to evaluate a compiled script against. Every field is optional: missing lists are empty, the
 * fee is 0, the validity range is unbounded and ids are all-zero.
 * <p>
 * Hashes and byte strings are hex. Integers are decimal strings. Anywhere a script hash or script credential is
 * expected, {@code $self} stands for the hash of the script under evaluation.
 * <p>
 * Credentials are written {@code key:<hex>} or {@code script:<hex>} (or {@code $self}); an address is a payment
 * credential with an optional stake credential, or a bech32 address in {@code payment}.
 *
 * @param purpose               which script purpose is evaluated
 * @param redeemer              redeemer data (default {@code Constr 0 []})
 * @param inputs                spent outputs
 * @param referenceInputs       referenced outputs
 * @param outputs               created outputs
 * @param fee                   fee in lovelace
 * @param mint                  minted (positive) and burnt (negative) assets
 * @param certificates          certificates
 * @param withdrawals           reward withdrawals
 * @param validRange            validity interval in POSIX milliseconds
 * @param signatories           required signer key hashes
 * @param datums                datum witnesses; hashes are computed
 * @param txId                  transaction id
 * @param votes                 governance votes
 * @param proposals             governance proposals
 * @param currentTreasuryAmount current treasury amount in lovelace
 * @param treasuryDonation      treasury donation in lovelace
 */
public record MockTransaction(
        Purpose purpose,
        DataInput redeemer,
        List<TxIn> inputs,
        List<TxIn> referenceInputs,
        List<TxOut> outputs,
        String fee,
        List<Asset> mint,
        List<Certificate> certificates,
        List<Withdrawal> withdrawals,
        Interval validRange,
        List<String> signatories,
        List<DataInput> datums,
        String txId,
        List<Vote> votes,
        List<Proposal> proposals,
        String currentTreasuryAmount,
        String treasuryDonation
) {

    /**
     * Script purpose.
     *
     * @param type      {@code spend | mint | reward | certify | vote | propose}
     * @param index     spend: input index; certify: certificate index; propose: proposal index
     * @param target    mint: policy id; reward: credential; vote: voter credential or pool key hash
     * @param voterType vote: {@code drep | committee | pool}
     */
    public record Purpose(String type, Integer index, String target, String voterType) {}

    /** Plutus data given as text; {@code format} is {@code auto | json | cbor | uplc}. */
    public record DataInput(String format, String value) {}

    /** A spent or referenced output. */
    public record TxIn(String txId, Long index, Address address, Value value, Datum datum, String referenceScript) {}

    /** A created output. */
    public record TxOut(Address address, Value value, Datum datum, String referenceScript) {}

    /** Payment credential (or bech32 address) and optional stake credential. */
    public record Address(String payment, String stake) {}

    /** Lovelace and native assets. */
    public record Value(String lovelace, List<Asset> assets) {}

    /** A native asset; {@code tokenName} is hex. */
    public record Asset(String policyId, String tokenName, String quantity) {}

    /** Output datum: {@code kind} is {@code none | hash | inline}. */
    public record Datum(String kind, DataInput data, String hash) {}

    /** Validity interval; a {@code null} bound is unbounded, bounds are inclusive by default. */
    public record Interval(Long from, Boolean fromInclusive, Long to, Boolean toInclusive) {}

    /** Withdrawal of {@code amount} lovelace for a stake credential. */
    public record Withdrawal(String credential, String amount) {}

    /**
     * Certificate.
     *
     * @param type       {@code regStaking | unregStaking | delegStaking | regDRep | unregDRep | updateDRep}
     * @param credential the certified credential
     * @param deposit    deposit or refund in lovelace, where the certificate has one
     * @param poolId     delegStaking: pool key hash
     */
    public record Certificate(String type, String credential, String deposit, String poolId) {}

    /**
     * Governance vote.
     *
     * @param voterType   {@code drep | committee | pool}
     * @param voter       voter credential, or pool key hash
     * @param actionTxId  governance action transaction id
     * @param actionIndex governance action index
     * @param vote        {@code yes | no | abstain}
     */
    public record Vote(String voterType, String voter, String actionTxId, Long actionIndex, String vote) {}

    /**
     * Governance proposal.
     *
     * @param deposit          deposit in lovelace
     * @param returnCredential credential receiving the deposit back
     * @param actionType       {@code info | treasuryWithdrawals | parameterChange | noConfidence}
     * @param guardrail        guardrail script hash for treasuryWithdrawals and parameterChange
     * @param withdrawals      treasuryWithdrawals: credentials and amounts
     */
    public record Proposal(String deposit, String returnCredential, String actionType, String guardrail,
                           List<Withdrawal> withdrawals) {}
}
