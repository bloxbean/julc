package com.bloxbean.cardano.julc.testkit;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.*;

import java.math.BigInteger;

/**
 * Fluent builder for constructing test ScriptContext instances.
 * <p>
 * Wraps the ledger-api {@link ScriptContextBuilder} and provides three output modes:
 * <ul>
 *   <li>{@link #build()} - returns a ledger-api {@link ScriptContext}</li>
 *   <li>{@link #buildOnchain()} - returns an onchain-api ScriptContext (via {@link LedgerTypeAdapter})</li>
 *   <li>{@link #buildPlutusData()} - returns a {@link PlutusData} (via {@link ScriptContext#toPlutusData()})</li>
 * </ul>
 * <p>
 * Usage:
 * <pre>{@code
 * var ctx = ScriptContextTestBuilder.spending(txOutRef)
 *     .signer(pkh)
 *     .input(txIn)
 *     .output(txOut)
 *     .fee(BigInteger.valueOf(200_000))
 *     .buildPlutusData();
 * }</pre>
 */
public class ScriptContextTestBuilder {

    private final ScriptContextBuilder inner;

    private ScriptContextTestBuilder(ScriptContextBuilder inner) {
        this.inner = inner;
    }

    // --- Factory methods ---

    /**
     * Create a builder for a spending script context.
     *
     * @param ref the transaction output reference being spent
     * @return a new builder
     */
    public static ScriptContextTestBuilder spending(TxOutRef ref) {
        return new ScriptContextTestBuilder(ScriptContextBuilder.spending(ref));
    }

    /**
     * Create a builder for a spending script context with an inline datum.
     *
     * @param ref   the transaction output reference being spent
     * @param datum the datum attached to the spent output
     * @return a new builder
     */
    public static ScriptContextTestBuilder spending(TxOutRef ref, PlutusData datum) {
        return new ScriptContextTestBuilder(ScriptContextBuilder.spending(ref, datum));
    }

    /**
     * Create a builder for a minting script context.
     *
     * @param policyId the minting policy ID
     * @return a new builder
     */
    public static ScriptContextTestBuilder minting(PolicyId policyId) {
        return new ScriptContextTestBuilder(ScriptContextBuilder.minting(policyId));
    }

    /**
     * Create a builder for a rewarding (withdraw) script context.
     *
     * @param credential the staking credential
     * @return a new builder
     */
    public static ScriptContextTestBuilder rewarding(Credential credential) {
        return new ScriptContextTestBuilder(ScriptContextBuilder.rewarding(credential));
    }

    /**
     * Create a builder for a certifying script context.
     *
     * @param index the certificate index
     * @param cert  the transaction certificate
     * @return a new builder
     */
    public static ScriptContextTestBuilder certifying(BigInteger index, TxCert cert) {
        return new ScriptContextTestBuilder(ScriptContextBuilder.certifying(index, cert));
    }

    /**
     * Create a builder for a voting script context.
     *
     * @param voter the governance voter
     * @return a new builder
     */
    public static ScriptContextTestBuilder voting(Voter voter) {
        return new ScriptContextTestBuilder(ScriptContextBuilder.voting(voter));
    }

    /**
     * Create a builder for a proposing script context.
     *
     * @param index     the proposal index
     * @param procedure the proposal procedure
     * @return a new builder
     */
    public static ScriptContextTestBuilder proposing(BigInteger index, ProposalProcedure procedure) {
        return new ScriptContextTestBuilder(ScriptContextBuilder.proposing(index, procedure));
    }

    // --- Delegate methods ---

    /**
     * Add a signatory (public key hash).
     *
     * @param pkh the public key hash
     * @return this builder
     */
    public ScriptContextTestBuilder signer(PubKeyHash pkh) {
        inner.signer(pkh);
        return this;
    }

    /**
     * Add a signatory from raw bytes.
     *
     * @param pkhBytes the 28-byte public key hash
     * @return this builder
     */
    public ScriptContextTestBuilder signer(byte[] pkhBytes) {
        inner.signer(new PubKeyHash(pkhBytes));
        return this;
    }

    /**
     * Add a transaction input.
     *
     * @param input the transaction input
     * @return this builder
     */
    public ScriptContextTestBuilder input(TxInInfo input) {
        inner.input(input);
        return this;
    }

    /**
     * Add a reference input.
     *
     * @param input the reference input
     * @return this builder
     */
    public ScriptContextTestBuilder referenceInput(TxInInfo input) {
        inner.referenceInput(input);
        return this;
    }

    /**
     * Add a transaction output.
     *
     * @param output the transaction output
     * @return this builder
     */
    public ScriptContextTestBuilder output(TxOut output) {
        inner.output(output);
        return this;
    }

    /**
     * Set the transaction fee.
     *
     * @param fee the fee in lovelace
     * @return this builder
     */
    public ScriptContextTestBuilder fee(BigInteger fee) {
        inner.fee(fee);
        return this;
    }

    /**
     * Set the minting value.
     *
     * @param mint the minting value
     * @return this builder
     */
    public ScriptContextTestBuilder mint(Value mint) {
        inner.mint(mint);
        return this;
    }

    /**
     * Set the valid time range.
     *
     * @param range the validity interval
     * @return this builder
     */
    public ScriptContextTestBuilder validRange(Interval range) {
        inner.validRange(range);
        return this;
    }

    /**
     * Set the redeemer.
     *
     * @param redeemer the redeemer PlutusData
     * @return this builder
     */
    public ScriptContextTestBuilder redeemer(PlutusData redeemer) {
        inner.redeemer(redeemer);
        return this;
    }

    /**
     * Set the transaction ID.
     *
     * @param txId the transaction ID
     * @return this builder
     */
    public ScriptContextTestBuilder txId(TxId txId) {
        inner.txId(txId);
        return this;
    }

    /**
     * Add a certificate.
     *
     * @param cert the certificate
     * @return this builder
     */
    public ScriptContextTestBuilder certificate(TxCert cert) {
        inner.certificate(cert);
        return this;
    }

    /**
     * Add a withdrawal.
     *
     * @param cred   the credential
     * @param amount the withdrawal amount
     * @return this builder
     */
    public ScriptContextTestBuilder withdrawal(Credential cred, BigInteger amount) {
        inner.withdrawal(cred, amount);
        return this;
    }

    /**
     * Add a redeemer entry.
     *
     * @param purpose  the script purpose
     * @param redeemer the redeemer data
     * @return this builder
     */
    public ScriptContextTestBuilder redeemerEntry(ScriptPurpose purpose, PlutusData redeemer) {
        inner.redeemerEntry(purpose, redeemer);
        return this;
    }

    /**
     * Add a datum entry.
     *
     * @param hash  the datum hash
     * @param datum the datum data
     * @return this builder
     */
    public ScriptContextTestBuilder datum(DatumHash hash, PlutusData datum) {
        inner.datum(hash, datum);
        return this;
    }

    /**
     * Set the current treasury amount.
     *
     * @param amount the treasury amount
     * @return this builder
     */
    public ScriptContextTestBuilder currentTreasuryAmount(BigInteger amount) {
        inner.currentTreasuryAmount(amount);
        return this;
    }

    /**
     * Set the treasury donation amount.
     *
     * @param amount the treasury donation
     * @return this builder
     */
    public ScriptContextTestBuilder treasuryDonation(BigInteger amount) {
        inner.treasuryDonation(amount);
        return this;
    }

    // --- Build methods ---

    /**
     * Build the ledger-api ScriptContext.
     *
     * @return a ledger-api ScriptContext
     */
    public ScriptContext build() {
        return inner.build();
    }

    /**
     * Build an onchain-api ScriptContext by converting from the ledger-api representation.
     *
     * @return an onchain-api ScriptContext
     */
    public com.bloxbean.cardano.julc.ledger.ScriptContext buildOnchain() {
        return LedgerTypeAdapter.toOnchain(inner.build());
    }

    /**
     * Build the ScriptContext as PlutusData.
     *
     * @return the ScriptContext encoded as PlutusData
     */
    public PlutusData buildPlutusData() {
        return inner.build().toPlutusData();
    }
}
