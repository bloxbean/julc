package com.bloxbean.cardano.julc.testkit;

import com.bloxbean.cardano.julc.core.PlutusData;

import java.math.BigInteger;
import java.util.*;

/**
 * Converts ledger-api (rich wrapper) types to onchain-api (raw stub) types.
 * <p>
 * The ledger-api types use wrappers like {@code PubKeyHash}, {@code PolicyId}, {@code TokenName},
 * while onchain-api types use raw {@code byte[]} and {@code PlutusData}.
 * This adapter bridges the two type systems, enabling test builders that produce
 * ledger-api types to be used with validators that expect onchain-api types.
 * <p>
 * All methods are static. The naming convention is {@code toOnchain(LedgerType)}.
 */
public final class LedgerTypeAdapter {

    private LedgerTypeAdapter() {
        // utility class
    }

    // --- ScriptContext ---

    /**
     * Convert a ledger-api ScriptContext to an onchain-api ScriptContext.
     */
    public static com.bloxbean.cardano.julc.onchain.ledger.ScriptContext toOnchain(
            com.bloxbean.cardano.julc.ledger.ScriptContext ctx) {
        return new com.bloxbean.cardano.julc.onchain.ledger.ScriptContext(
                toOnchain(ctx.txInfo()),
                ctx.redeemer(),
                toOnchain(ctx.scriptInfo()));
    }

    // --- TxInfo ---

    /**
     * Convert a ledger-api TxInfo to an onchain-api TxInfo.
     */
    public static com.bloxbean.cardano.julc.onchain.ledger.TxInfo toOnchain(
            com.bloxbean.cardano.julc.ledger.TxInfo txInfo) {
        return new com.bloxbean.cardano.julc.onchain.ledger.TxInfo(
                txInfo.inputs().stream().map(LedgerTypeAdapter::toOnchain).toList(),
                txInfo.referenceInputs().stream().map(LedgerTypeAdapter::toOnchain).toList(),
                txInfo.outputs().stream().map(LedgerTypeAdapter::toOnchain).toList(),
                txInfo.fee(),
                toOnchain(txInfo.mint()),
                txInfo.certificates().stream()
                        .<PlutusData>map(com.bloxbean.cardano.julc.ledger.TxCert::toPlutusData).toList(),
                convertWithdrawals(txInfo.withdrawals()),
                toOnchain(txInfo.validRange()),
                txInfo.signatories().stream()
                        .map(pkh -> pkh.hash()).toList(),
                convertRedeemers(txInfo.redeemers()),
                convertDatums(txInfo.datums()),
                txInfo.id().hash(),
                convertVotes(txInfo.votes()),
                txInfo.proposalProcedures().stream()
                        .<PlutusData>map(com.bloxbean.cardano.julc.ledger.ProposalProcedure::toPlutusData).toList(),
                txInfo.currentTreasuryAmount(),
                txInfo.treasuryDonation());
    }

    private static Map<PlutusData, BigInteger> convertWithdrawals(
            Map<com.bloxbean.cardano.julc.ledger.Credential, BigInteger> withdrawals) {
        var result = new LinkedHashMap<PlutusData, BigInteger>();
        for (var entry : withdrawals.entrySet()) {
            result.put(entry.getKey().toPlutusData(), entry.getValue());
        }
        return result;
    }

    private static Map<PlutusData, PlutusData> convertRedeemers(
            Map<com.bloxbean.cardano.julc.ledger.ScriptPurpose, PlutusData> redeemers) {
        var result = new LinkedHashMap<PlutusData, PlutusData>();
        for (var entry : redeemers.entrySet()) {
            result.put(entry.getKey().toPlutusData(), entry.getValue());
        }
        return result;
    }

    private static Map<PlutusData, PlutusData> convertDatums(
            Map<com.bloxbean.cardano.julc.ledger.DatumHash, PlutusData> datums) {
        var result = new LinkedHashMap<PlutusData, PlutusData>();
        for (var entry : datums.entrySet()) {
            result.put(entry.getKey().toPlutusData(), entry.getValue());
        }
        return result;
    }

    private static Map<PlutusData, PlutusData> convertVotes(
            Map<com.bloxbean.cardano.julc.ledger.Voter,
                    Map<com.bloxbean.cardano.julc.ledger.GovernanceActionId,
                            com.bloxbean.cardano.julc.ledger.Vote>> votes) {
        var result = new LinkedHashMap<PlutusData, PlutusData>();
        for (var entry : votes.entrySet()) {
            var innerMap = new LinkedHashMap<PlutusData, PlutusData>();
            for (var inner : entry.getValue().entrySet()) {
                innerMap.put(inner.getKey().toPlutusData(), inner.getValue().toPlutusData());
            }
            // Encode the inner map as PlutusData.MapData
            var pairs = new ArrayList<PlutusData.Pair>();
            for (var ie : innerMap.entrySet()) {
                pairs.add(new PlutusData.Pair(ie.getKey(), ie.getValue()));
            }
            result.put(entry.getKey().toPlutusData(), new PlutusData.MapData(pairs));
        }
        return result;
    }

    // --- TxInInfo ---

    /**
     * Convert a ledger-api TxInInfo to an onchain-api TxInInfo.
     */
    public static com.bloxbean.cardano.julc.onchain.ledger.TxInInfo toOnchain(
            com.bloxbean.cardano.julc.ledger.TxInInfo txInInfo) {
        return new com.bloxbean.cardano.julc.onchain.ledger.TxInInfo(
                toOnchain(txInInfo.outRef()),
                toOnchain(txInInfo.resolved()));
    }

    // --- TxOut ---

    /**
     * Convert a ledger-api TxOut to an onchain-api TxOut.
     */
    public static com.bloxbean.cardano.julc.onchain.ledger.TxOut toOnchain(
            com.bloxbean.cardano.julc.ledger.TxOut txOut) {
        return new com.bloxbean.cardano.julc.onchain.ledger.TxOut(
                toOnchain(txOut.address()),
                toOnchain(txOut.value()),
                toOnchain(txOut.datum()),
                txOut.referenceScript()
                        .map(sh -> (PlutusData) sh.toPlutusData())
                        .orElse(null));
    }

    // --- TxOutRef ---

    /**
     * Convert a ledger-api TxOutRef to an onchain-api TxOutRef.
     */
    public static com.bloxbean.cardano.julc.onchain.ledger.TxOutRef toOnchain(
            com.bloxbean.cardano.julc.ledger.TxOutRef txOutRef) {
        return new com.bloxbean.cardano.julc.onchain.ledger.TxOutRef(
                txOutRef.txId().hash(),
                txOutRef.index());
    }

    // --- Address ---

    /**
     * Convert a ledger-api Address to an onchain-api Address.
     */
    public static com.bloxbean.cardano.julc.onchain.ledger.Address toOnchain(
            com.bloxbean.cardano.julc.ledger.Address address) {
        return new com.bloxbean.cardano.julc.onchain.ledger.Address(
                toOnchain(address.credential()),
                address.stakingCredential()
                        .map(sc -> (PlutusData) sc.toPlutusData())
                        .orElse(null));
    }

    // --- Credential ---

    /**
     * Convert a ledger-api Credential to an onchain-api Credential.
     */
    public static com.bloxbean.cardano.julc.onchain.ledger.Credential toOnchain(
            com.bloxbean.cardano.julc.ledger.Credential credential) {
        return switch (credential) {
            case com.bloxbean.cardano.julc.ledger.Credential.PubKeyCredential pkc ->
                    new com.bloxbean.cardano.julc.onchain.ledger.Credential.PubKeyCredential(
                            pkc.hash().hash());
            case com.bloxbean.cardano.julc.ledger.Credential.ScriptCredential sc ->
                    new com.bloxbean.cardano.julc.onchain.ledger.Credential.ScriptCredential(
                            sc.hash().hash());
        };
    }

    // --- Value ---

    /**
     * Convert a ledger-api Value to an onchain-api Value.
     * PolicyId and TokenName wrappers are unwrapped to raw byte[].
     */
    public static com.bloxbean.cardano.julc.onchain.ledger.Value toOnchain(
            com.bloxbean.cardano.julc.ledger.Value value) {
        var result = new LinkedHashMap<byte[], Map<byte[], BigInteger>>();
        for (var entry : value.inner().entrySet()) {
            var tokenMap = new LinkedHashMap<byte[], BigInteger>();
            for (var tokenEntry : entry.getValue().entrySet()) {
                tokenMap.put(tokenEntry.getKey().name(), tokenEntry.getValue());
            }
            result.put(entry.getKey().hash(), tokenMap);
        }
        return new com.bloxbean.cardano.julc.onchain.ledger.Value(result);
    }

    // --- Interval ---

    /**
     * Convert a ledger-api Interval to an onchain-api Interval.
     */
    public static com.bloxbean.cardano.julc.onchain.ledger.Interval toOnchain(
            com.bloxbean.cardano.julc.ledger.Interval interval) {
        return new com.bloxbean.cardano.julc.onchain.ledger.Interval(
                toOnchain(interval.from()),
                toOnchain(interval.to()));
    }

    // --- IntervalBound ---

    /**
     * Convert a ledger-api IntervalBound to an onchain-api IntervalBound.
     */
    public static com.bloxbean.cardano.julc.onchain.ledger.IntervalBound toOnchain(
            com.bloxbean.cardano.julc.ledger.IntervalBound bound) {
        return new com.bloxbean.cardano.julc.onchain.ledger.IntervalBound(
                toOnchain(bound.boundType()),
                bound.isInclusive());
    }

    // --- IntervalBoundType ---

    /**
     * Convert a ledger-api IntervalBoundType to an onchain-api IntervalBoundType.
     */
    public static com.bloxbean.cardano.julc.onchain.ledger.IntervalBoundType toOnchain(
            com.bloxbean.cardano.julc.ledger.IntervalBoundType boundType) {
        return switch (boundType) {
            case com.bloxbean.cardano.julc.ledger.IntervalBoundType.NegInf() ->
                    new com.bloxbean.cardano.julc.onchain.ledger.IntervalBoundType.NegInf();
            case com.bloxbean.cardano.julc.ledger.IntervalBoundType.Finite f ->
                    new com.bloxbean.cardano.julc.onchain.ledger.IntervalBoundType.Finite(f.time());
            case com.bloxbean.cardano.julc.ledger.IntervalBoundType.PosInf() ->
                    new com.bloxbean.cardano.julc.onchain.ledger.IntervalBoundType.PosInf();
        };
    }

    // --- ScriptInfo ---

    /**
     * Convert a ledger-api ScriptInfo to an onchain-api ScriptInfo.
     */
    public static com.bloxbean.cardano.julc.onchain.ledger.ScriptInfo toOnchain(
            com.bloxbean.cardano.julc.ledger.ScriptInfo scriptInfo) {
        return switch (scriptInfo) {
            case com.bloxbean.cardano.julc.ledger.ScriptInfo.MintingScript m ->
                    new com.bloxbean.cardano.julc.onchain.ledger.ScriptInfo.MintingScript(
                            m.policyId().hash());
            case com.bloxbean.cardano.julc.ledger.ScriptInfo.SpendingScript s ->
                    new com.bloxbean.cardano.julc.onchain.ledger.ScriptInfo.SpendingScript(
                            s.txOutRef().toPlutusData(),
                            s.datum().map(d -> d).orElse(null));
            case com.bloxbean.cardano.julc.ledger.ScriptInfo.RewardingScript r ->
                    new com.bloxbean.cardano.julc.onchain.ledger.ScriptInfo.RewardingScript(
                            r.credential().toPlutusData());
            case com.bloxbean.cardano.julc.ledger.ScriptInfo.CertifyingScript c ->
                    new com.bloxbean.cardano.julc.onchain.ledger.ScriptInfo.CertifyingScript(
                            c.index(), c.cert().toPlutusData());
            case com.bloxbean.cardano.julc.ledger.ScriptInfo.VotingScript v ->
                    new com.bloxbean.cardano.julc.onchain.ledger.ScriptInfo.VotingScript(
                            v.voter().toPlutusData());
            case com.bloxbean.cardano.julc.ledger.ScriptInfo.ProposingScript p ->
                    new com.bloxbean.cardano.julc.onchain.ledger.ScriptInfo.ProposingScript(
                            p.index(), p.procedure().toPlutusData());
        };
    }

    // --- OutputDatum ---

    /**
     * Convert a ledger-api OutputDatum to an onchain-api OutputDatum.
     */
    public static com.bloxbean.cardano.julc.onchain.ledger.OutputDatum toOnchain(
            com.bloxbean.cardano.julc.ledger.OutputDatum outputDatum) {
        return switch (outputDatum) {
            case com.bloxbean.cardano.julc.ledger.OutputDatum.NoOutputDatum() ->
                    new com.bloxbean.cardano.julc.onchain.ledger.OutputDatum.NoOutputDatum();
            case com.bloxbean.cardano.julc.ledger.OutputDatum.OutputDatumHash h ->
                    new com.bloxbean.cardano.julc.onchain.ledger.OutputDatum.OutputDatumHash(
                            h.datumHash().hash());
            case com.bloxbean.cardano.julc.ledger.OutputDatum.OutputDatumInline i ->
                    new com.bloxbean.cardano.julc.onchain.ledger.OutputDatum.OutputDatumInline(
                            i.datum());
        };
    }
}
