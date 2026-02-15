package com.bloxbean.cardano.julc.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.core.types.JulcMap;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/**
 * V3 (Conway) transaction info with 16 fields.
 */
public record TxInfo(
        JulcList<TxInInfo> inputs,
        JulcList<TxInInfo> referenceInputs,
        JulcList<TxOut> outputs,
        BigInteger fee,
        Value mint,
        JulcList<TxCert> certificates,
        JulcMap<Credential, BigInteger> withdrawals,
        Interval validRange,
        JulcList<PubKeyHash> signatories,
        JulcMap<ScriptPurpose, PlutusData> redeemers,
        JulcMap<DatumHash, PlutusData> datums,
        TxId id,
        JulcMap<Voter, JulcMap<GovernanceActionId, Vote>> votes,
        JulcList<ProposalProcedure> proposalProcedures,
        Optional<BigInteger> currentTreasuryAmount,
        Optional<BigInteger> treasuryDonation
) implements PlutusDataConvertible {

    @Override
    public PlutusData.ConstrData toPlutusData() {
        return new PlutusData.ConstrData(0, List.of(
                PlutusDataHelper.encodeList(inputs, TxInInfo::toPlutusData),
                PlutusDataHelper.encodeList(referenceInputs, TxInInfo::toPlutusData),
                PlutusDataHelper.encodeList(outputs, TxOut::toPlutusData),
                new PlutusData.IntData(fee),
                mint.toPlutusData(),
                PlutusDataHelper.encodeList(certificates, TxCert::toPlutusData),
                PlutusDataHelper.encodeJulcMap(withdrawals,
                        Credential::toPlutusData, PlutusDataHelper::encodeInteger),
                validRange.toPlutusData(),
                PlutusDataHelper.encodeList(signatories, PubKeyHash::toPlutusData),
                PlutusDataHelper.encodeJulcMap(redeemers,
                        ScriptPurpose::toPlutusData, d -> d),
                PlutusDataHelper.encodeJulcMap(datums,
                        DatumHash::toPlutusData, d -> d),
                id.toPlutusData(),
                encodeVotes(votes),
                PlutusDataHelper.encodeList(proposalProcedures, ProposalProcedure::toPlutusData),
                PlutusDataHelper.encodeOptional(currentTreasuryAmount, PlutusDataHelper::encodeInteger),
                PlutusDataHelper.encodeOptional(treasuryDonation, PlutusDataHelper::encodeInteger)));
    }

    private static PlutusData.MapData encodeVotes(JulcMap<Voter, JulcMap<GovernanceActionId, Vote>> votes) {
        return PlutusDataHelper.encodeJulcMap(votes,
                Voter::toPlutusData,
                innerMap -> PlutusDataHelper.encodeJulcMap(innerMap,
                        GovernanceActionId::toPlutusData, Vote::toPlutusData));
    }

    public static TxInfo fromPlutusData(PlutusData data) {
        var f = PlutusDataHelper.expectConstr(data, 0);
        return new TxInfo(
                PlutusDataHelper.decodeJulcList(f.get(0), TxInInfo::fromPlutusData),
                PlutusDataHelper.decodeJulcList(f.get(1), TxInInfo::fromPlutusData),
                PlutusDataHelper.decodeJulcList(f.get(2), TxOut::fromPlutusData),
                PlutusDataHelper.decodeInteger(f.get(3)),
                Value.fromPlutusData(f.get(4)),
                PlutusDataHelper.decodeJulcList(f.get(5), TxCert::fromPlutusData),
                PlutusDataHelper.decodeJulcMap(f.get(6),
                        Credential::fromPlutusData, PlutusDataHelper::decodeInteger),
                Interval.fromPlutusData(f.get(7)),
                PlutusDataHelper.decodeJulcList(f.get(8), PubKeyHash::fromPlutusData),
                PlutusDataHelper.decodeJulcMap(f.get(9),
                        ScriptPurpose::fromPlutusData, d -> d),
                PlutusDataHelper.decodeJulcMap(f.get(10),
                        DatumHash::fromPlutusData, d -> d),
                TxId.fromPlutusData(f.get(11)),
                decodeVotes(f.get(12)),
                PlutusDataHelper.decodeJulcList(f.get(13), ProposalProcedure::fromPlutusData),
                PlutusDataHelper.decodeOptional(f.get(14), PlutusDataHelper::decodeInteger),
                PlutusDataHelper.decodeOptional(f.get(15), PlutusDataHelper::decodeInteger));
    }

    private static JulcMap<Voter, JulcMap<GovernanceActionId, Vote>> decodeVotes(PlutusData data) {
        return PlutusDataHelper.decodeJulcMap(data,
                Voter::fromPlutusData,
                innerData -> PlutusDataHelper.decodeJulcMap(innerData,
                        GovernanceActionId::fromPlutusData, Vote::fromPlutusData));
    }
}
