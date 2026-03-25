package com.bloxbean.cardano.julc.testkit.jqwik;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.testkit.ScriptContextTestBuilder;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Builder for generating internally consistent random ScriptContexts via jqwik.
 * <p>
 * A fully random ScriptContext would be rejected by most validators because fields
 * would be inconsistent (wrong signers, missing inputs, etc.). This builder generates
 * contexts where:
 * <ul>
 *   <li>Spending: the spent TxOutRef appears in the inputs list</li>
 *   <li>Minting: the PolicyId appears in the mint value and ScriptInfo</li>
 *   <li>Outputs use addresses drawn from signatories</li>
 *   <li>Fee is a realistic range</li>
 * </ul>
 * <p>
 * Usage:
 * <pre>{@code
 * Arbitrary<ScriptContext> ctxArb = ArbitraryScriptContext.spending()
 *     .signers(1, 3)
 *     .inputs(1, 5)
 *     .outputs(1, 5)
 *     .fee(150_000, 2_000_000)
 *     .build();
 * }</pre>
 */
public final class ArbitraryScriptContext {

    private enum Mode { SPENDING, MINTING, REWARDING }

    private final Mode mode;
    private int minSigners = 1;
    private int maxSigners = 3;
    private int minInputs = 1;
    private int maxInputs = 3;
    private int minOutputs = 1;
    private int maxOutputs = 3;
    private long minFee = 150_000;
    private long maxFee = 2_000_000;
    private Arbitrary<PlutusData> datumArbitrary = Arbitraries.just(PlutusData.UNIT);
    private Arbitrary<PlutusData> redeemerArbitrary = Arbitraries.just(PlutusData.UNIT);
    private Arbitrary<Interval> validRangeArbitrary = Arbitraries.just(Interval.always());

    private ArbitraryScriptContext(Mode mode) {
        this.mode = mode;
    }

    /** Create a builder for a spending script context. */
    public static ArbitraryScriptContext spending() {
        return new ArbitraryScriptContext(Mode.SPENDING);
    }

    /** Create a builder for a minting script context. */
    public static ArbitraryScriptContext minting() {
        return new ArbitraryScriptContext(Mode.MINTING);
    }

    /** Create a builder for a rewarding script context. */
    public static ArbitraryScriptContext rewarding() {
        return new ArbitraryScriptContext(Mode.REWARDING);
    }

    /**
     * Set the number of signatories (inclusive range).
     * For spending contexts, at least 1 signer is required (the spent UTxO owner).
     */
    public ArbitraryScriptContext signers(int min, int max) {
        if (mode == Mode.SPENDING && min < 1) {
            throw new IllegalArgumentException(
                    "Spending contexts require at least 1 signer (spent UTxO owner), got min=" + min);
        }
        this.minSigners = min;
        this.maxSigners = max;
        return this;
    }

    /**
     * Set the number of transaction inputs (inclusive range).
     * For spending contexts, at least 1 input is required (the spent UTxO),
     * so the effective extra inputs generated will be [min-1, max-1].
     */
    public ArbitraryScriptContext inputs(int min, int max) {
        this.minInputs = min;
        this.maxInputs = max;
        return this;
    }

    /** Set the number of transaction outputs (inclusive range). */
    public ArbitraryScriptContext outputs(int min, int max) {
        this.minOutputs = min;
        this.maxOutputs = max;
        return this;
    }

    /** Set the fee range in lovelace. */
    public ArbitraryScriptContext fee(long min, long max) {
        this.minFee = min;
        this.maxFee = max;
        return this;
    }

    /** Set a custom datum generator for spending contexts. */
    public ArbitraryScriptContext withDatum(Arbitrary<PlutusData> datumArb) {
        this.datumArbitrary = datumArb;
        return this;
    }

    /** Set a custom redeemer generator. */
    public ArbitraryScriptContext withRedeemer(Arbitrary<PlutusData> redeemerArb) {
        this.redeemerArbitrary = redeemerArb;
        return this;
    }

    /** Set a custom valid range generator. */
    public ArbitraryScriptContext withValidRange(Arbitrary<Interval> rangeArb) {
        this.validRangeArbitrary = rangeArb;
        return this;
    }

    /** Build an {@code Arbitrary<ScriptContext>}. */
    public Arbitrary<ScriptContext> build() {
        return switch (mode) {
            case SPENDING -> buildSpending();
            case MINTING -> buildMinting();
            case REWARDING -> buildRewarding();
        };
    }

    /** Build an {@code Arbitrary<PlutusData>} (for direct eval). */
    public Arbitrary<PlutusData> buildAsPlutusData() {
        return build().map(ScriptContext::toPlutusData);
    }

    private Arbitrary<ScriptContext> buildSpending() {
        return Combinators.combine(
                CardanoArbitraries.txOutRef(),
                CardanoArbitraries.pubKeyHash().list().ofMinSize(minSigners).ofMaxSize(maxSigners),
                datumArbitrary,
                redeemerArbitrary,
                Arbitraries.longs().between(minFee, maxFee),
                validRangeArbitrary,
                CardanoArbitraries.txId()
        ).flatAs((spentRef, signers, datum, redeemer, fee, range, txId) -> {
            // Build consistent spending context
            var builder = ScriptContextTestBuilder.spending(spentRef, datum);
            builder.redeemer(redeemer);
            builder.fee(BigInteger.valueOf(fee));
            builder.validRange(range);
            builder.txId(txId);

            // Add signers
            for (var signer : signers) {
                builder.signer(signer);
            }

            // The spent input MUST be in the inputs list
            var spentAddress = new Address(
                    new Credential.PubKeyCredential(signers.getFirst()),
                    Optional.empty());
            var spentTxOut = new TxOut(spentAddress,
                    Value.lovelace(BigInteger.valueOf(5_000_000)),
                    new OutputDatum.NoOutputDatum(), Optional.empty());
            builder.input(new TxInInfo(spentRef, spentTxOut));

            // Generate additional random inputs and outputs
            int extraInputs = Math.max(0, minInputs - 1);
            int maxExtraInputs = Math.max(0, maxInputs - 1);
            return Combinators.combine(
                    CardanoArbitraries.txInInfo().list().ofMinSize(extraInputs).ofMaxSize(maxExtraInputs),
                    generateOutputs(signers, minOutputs, maxOutputs)
            ).as((inputs, outputs) -> {
                for (var input : inputs) {
                    builder.input(input);
                }
                for (var output : outputs) {
                    builder.output(output);
                }
                return builder.build();
            });
        });
    }

    private Arbitrary<ScriptContext> buildMinting() {
        return Combinators.combine(
                CardanoArbitraries.policyId(),
                CardanoArbitraries.pubKeyHash().list().ofMinSize(minSigners).ofMaxSize(maxSigners),
                redeemerArbitrary,
                Arbitraries.longs().between(minFee, maxFee),
                validRangeArbitrary,
                CardanoArbitraries.txId(),
                CardanoArbitraries.tokenName()
        ).flatAs((policy, signers, redeemer, fee, range, txId, tn) ->
                Combinators.combine(
                        CardanoArbitraries.txInInfo().list().ofMinSize(minInputs).ofMaxSize(maxInputs),
                        generateOutputs(signers, minOutputs, maxOutputs),
                        Arbitraries.longs().between(1, 1_000_000)
                ).as((inputs, outputs, qty) -> {
                    var builder = ScriptContextTestBuilder.minting(policy);
                    builder.redeemer(redeemer);
                    builder.fee(BigInteger.valueOf(fee));
                    builder.validRange(range);
                    builder.txId(txId);
                    builder.mint(Value.singleton(policy, tn, BigInteger.valueOf(qty)));
                    for (var signer : signers) {
                        builder.signer(signer);
                    }
                    for (var input : inputs) {
                        builder.input(input);
                    }
                    for (var output : outputs) {
                        builder.output(output);
                    }
                    return builder.build();
                })
        );
    }

    private Arbitrary<ScriptContext> buildRewarding() {
        return Combinators.combine(
                CardanoArbitraries.credential(),
                CardanoArbitraries.pubKeyHash().list().ofMinSize(minSigners).ofMaxSize(maxSigners),
                redeemerArbitrary,
                Arbitraries.longs().between(minFee, maxFee),
                validRangeArbitrary,
                CardanoArbitraries.txId()
        ).flatAs((cred, signers, redeemer, fee, range, txId) ->
                Combinators.combine(
                        CardanoArbitraries.txInInfo().list().ofMinSize(minInputs).ofMaxSize(maxInputs),
                        generateOutputs(signers, minOutputs, maxOutputs)
                ).as((inputs, outputs) -> {
                    var builder = ScriptContextTestBuilder.rewarding(cred);
                    builder.redeemer(redeemer);
                    builder.fee(BigInteger.valueOf(fee));
                    builder.validRange(range);
                    builder.txId(txId);
                    for (var signer : signers) {
                        builder.signer(signer);
                    }
                    for (var input : inputs) {
                        builder.input(input);
                    }
                    for (var output : outputs) {
                        builder.output(output);
                    }
                    return builder.build();
                })
        );
    }

    /**
     * Generate outputs whose addresses use the given signers' PubKeyCredentials.
     */
    private static Arbitrary<java.util.List<TxOut>> generateOutputs(
            java.util.List<PubKeyHash> signers, int minCount, int maxCount) {
        return Combinators.combine(
                Arbitraries.of(signers),
                CardanoArbitraries.lovelaceValue()
        ).as((signer, val) -> new TxOut(
                new Address(new Credential.PubKeyCredential(signer), Optional.empty()),
                val,
                new OutputDatum.NoOutputDatum(),
                Optional.empty()
        )).list().ofMinSize(minCount).ofMaxSize(maxCount);
    }
}
