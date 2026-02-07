package com.bloxbean.cardano.plutus.ledger;

import com.bloxbean.cardano.plutus.core.PlutusData;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * V3 (Conway) transaction info with 16 fields.
 */
public record TxInfo(
        List<TxInInfo> inputs,
        List<TxInInfo> referenceInputs,
        List<TxOut> outputs,
        BigInteger fee,
        Value mint,
        List<TxCert> certificates,
        Map<Credential, BigInteger> withdrawals,
        Interval validRange,
        List<PubKeyHash> signatories,
        Map<ScriptPurpose, PlutusData> redeemers,
        Map<DatumHash, PlutusData> datums,
        TxId id,
        Map<Voter, Map<GovernanceActionId, Vote>> votes,
        List<ProposalProcedure> proposalProcedures,
        Optional<BigInteger> currentTreasuryAmount,
        Optional<BigInteger> treasuryDonation
) implements PlutusDataConvertible {

    @Override
    public PlutusData toPlutusData() {
        return new PlutusData.Constr(0, List.of(
                PlutusDataHelper.encodeList(inputs, TxInInfo::toPlutusData),
                PlutusDataHelper.encodeList(referenceInputs, TxInInfo::toPlutusData),
                PlutusDataHelper.encodeList(outputs, TxOut::toPlutusData),
                new PlutusData.IntData(fee),
                mint.toPlutusData(),
                PlutusDataHelper.encodeList(certificates, TxCert::toPlutusData),
                PlutusDataHelper.encodeMap(withdrawals,
                        Credential::toPlutusData, PlutusDataHelper::encodeInteger),
                validRange.toPlutusData(),
                PlutusDataHelper.encodeList(signatories, PubKeyHash::toPlutusData),
                PlutusDataHelper.encodeMap(redeemers,
                        ScriptPurpose::toPlutusData, d -> d),
                PlutusDataHelper.encodeMap(datums,
                        DatumHash::toPlutusData, d -> d),
                id.toPlutusData(),
                encodeVotes(votes),
                PlutusDataHelper.encodeList(proposalProcedures, ProposalProcedure::toPlutusData),
                PlutusDataHelper.encodeOptional(currentTreasuryAmount, PlutusDataHelper::encodeInteger),
                PlutusDataHelper.encodeOptional(treasuryDonation, PlutusDataHelper::encodeInteger)));
    }

    private static PlutusData encodeVotes(Map<Voter, Map<GovernanceActionId, Vote>> votes) {
        return PlutusDataHelper.encodeMap(votes,
                Voter::toPlutusData,
                innerMap -> PlutusDataHelper.encodeMap(innerMap,
                        GovernanceActionId::toPlutusData, Vote::toPlutusData));
    }

    public static TxInfo fromPlutusData(PlutusData data) {
        var f = PlutusDataHelper.expectConstr(data, 0);
        return new TxInfo(
                PlutusDataHelper.decodeList(f.get(0), TxInInfo::fromPlutusData),
                PlutusDataHelper.decodeList(f.get(1), TxInInfo::fromPlutusData),
                PlutusDataHelper.decodeList(f.get(2), TxOut::fromPlutusData),
                PlutusDataHelper.decodeInteger(f.get(3)),
                Value.fromPlutusData(f.get(4)),
                PlutusDataHelper.decodeList(f.get(5), TxCert::fromPlutusData),
                PlutusDataHelper.decodeMap(f.get(6),
                        Credential::fromPlutusData, PlutusDataHelper::decodeInteger),
                Interval.fromPlutusData(f.get(7)),
                PlutusDataHelper.decodeList(f.get(8), PubKeyHash::fromPlutusData),
                PlutusDataHelper.decodeMap(f.get(9),
                        ScriptPurpose::fromPlutusData, d -> d),
                PlutusDataHelper.decodeMap(f.get(10),
                        DatumHash::fromPlutusData, d -> d),
                TxId.fromPlutusData(f.get(11)),
                decodeVotes(f.get(12)),
                PlutusDataHelper.decodeList(f.get(13), ProposalProcedure::fromPlutusData),
                PlutusDataHelper.decodeOptional(f.get(14), PlutusDataHelper::decodeInteger),
                PlutusDataHelper.decodeOptional(f.get(15), PlutusDataHelper::decodeInteger));
    }

    private static Map<Voter, Map<GovernanceActionId, Vote>> decodeVotes(PlutusData data) {
        return PlutusDataHelper.decodeMap(data,
                Voter::fromPlutusData,
                innerData -> PlutusDataHelper.decodeMap(innerData,
                        GovernanceActionId::fromPlutusData, Vote::fromPlutusData));
    }
}
