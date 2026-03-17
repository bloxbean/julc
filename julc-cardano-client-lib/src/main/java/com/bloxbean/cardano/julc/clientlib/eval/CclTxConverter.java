package com.bloxbean.cardano.julc.clientlib.eval;

import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.core.types.JulcArrayList;
import com.bloxbean.cardano.julc.core.types.JulcAssocMap;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.core.types.JulcMap;
import com.bloxbean.cardano.julc.ledger.*;

import java.math.BigInteger;
import java.util.*;

/**
 * Converts a CCL {@link Transaction} and resolved UTxOs into a JuLC {@link TxInfo}.
 */
final class CclTxConverter {

    private final Transaction tx;
    private final Set<Utxo> inputUtxos;
    private final UtxoSupplier utxoSupplier;
    private final SlotConfig slotConfig;

    // Cached sorted inputs for redeemer index mapping
    private List<TransactionInput> sortedInputs;

    CclTxConverter(Transaction tx, Set<Utxo> inputUtxos,
                   UtxoSupplier utxoSupplier, SlotConfig slotConfig) {
        this.tx = Objects.requireNonNull(tx);
        this.inputUtxos = inputUtxos != null ? inputUtxos : Set.of();
        this.utxoSupplier = utxoSupplier;
        this.slotConfig = slotConfig;
    }

    /**
     * Build a complete JuLC {@link TxInfo} from the transaction.
     */
    TxInfo buildTxInfo() {
        var body = tx.getBody();

        // 1. Inputs (sorted by txHash, then index)
        sortedInputs = new ArrayList<>(body.getInputs());
        sortedInputs.sort(Comparator.comparing(TransactionInput::getTransactionId)
                .thenComparingInt(TransactionInput::getIndex));
        JulcList<TxInInfo> inputs = resolveInputs(sortedInputs);

        // 2. Reference inputs
        JulcList<TxInInfo> referenceInputs;
        if (body.getReferenceInputs() != null && !body.getReferenceInputs().isEmpty()) {
            var sortedRefInputs = new ArrayList<>(body.getReferenceInputs());
            sortedRefInputs.sort(Comparator.comparing(TransactionInput::getTransactionId)
                    .thenComparingInt(TransactionInput::getIndex));
            referenceInputs = resolveInputs(sortedRefInputs);
        } else {
            referenceInputs = JulcList.empty();
        }

        // 3. Outputs
        JulcList<TxOut> outputs = convertOutputs(body.getOutputs());

        // 4. Fee
        BigInteger fee = body.getFee() != null ? body.getFee() : BigInteger.ZERO;

        // 5. Mint
        Value mint = (body.getMint() != null && !body.getMint().isEmpty())
                ? CclValueConverter.fromMultiAssets(body.getMint())
                : Value.zero();

        // 6. Certificates (deferred)
        JulcList<TxCert> certificates = JulcList.empty();

        // 7. Withdrawals
        JulcMap<Credential, BigInteger> withdrawals = convertWithdrawals(body.getWithdrawals());

        // 8. Valid range
        Interval validRange = convertValidRange(body.getValidityStartInterval(), body.getTtl());

        // 9. Signatories
        JulcList<PubKeyHash> signatories = convertSignatories(body.getRequiredSigners());

        // 10. Redeemers
        JulcMap<ScriptPurpose, com.bloxbean.cardano.julc.core.PlutusData> redeemers =
                convertRedeemers(tx.getWitnessSet().getRedeemers());

        // 11. Datums (witness set datum map: hash -> data)
        JulcMap<DatumHash, com.bloxbean.cardano.julc.core.PlutusData> datums = convertDatums(
                tx.getWitnessSet().getPlutusDataList());

        // 12. TxId from body hash
        TxId txId = computeTxId(body);

        // 13-14. Governance (deferred)
        JulcMap<Voter, JulcMap<GovernanceActionId, Vote>> votes = JulcAssocMap.empty();
        JulcList<ProposalProcedure> proposalProcedures = JulcList.empty();

        // 15-16. Treasury
        Optional<BigInteger> currentTreasuryAmount =
                Optional.ofNullable(body.getCurrentTreasuryValue());
        Optional<BigInteger> treasuryDonation =
                Optional.ofNullable(body.getDonation());

        return new TxInfo(inputs, referenceInputs, outputs, fee, mint,
                certificates, withdrawals, validRange, signatories,
                redeemers, datums, txId, votes, proposalProcedures,
                currentTreasuryAmount, treasuryDonation);
    }

    /**
     * Get the sorted inputs list (for redeemer index mapping).
     */
    List<TransactionInput> getSortedInputs() {
        if (sortedInputs == null) {
            sortedInputs = new ArrayList<>(tx.getBody().getInputs());
            sortedInputs.sort(Comparator.comparing(TransactionInput::getTransactionId)
                    .thenComparingInt(TransactionInput::getIndex));
        }
        return sortedInputs;
    }

    /**
     * Get sorted unique policy IDs from the mint field (for Mint redeemer mapping).
     */
    List<PolicyId> getSortedMintPolicyIds() {
        List<MultiAsset> mint = tx.getBody().getMint();
        if (mint == null || mint.isEmpty()) {
            return List.of();
        }

        var policyIds = new TreeSet<String>();
        for (MultiAsset ma : mint) {
            policyIds.add(ma.getPolicyId());
        }

        return policyIds.stream()
                .map(hex -> PolicyId.of(HexFormat.of().parseHex(hex)))
                .toList();
    }

    // --- Private helpers ---

    private JulcList<TxInInfo> resolveInputs(List<TransactionInput> txInputs) {
        var result = new ArrayList<TxInInfo>(txInputs.size());
        for (TransactionInput input : txInputs) {
            TxOutRef outRef = new TxOutRef(
                    TxId.of(HexFormat.of().parseHex(input.getTransactionId())),
                    BigInteger.valueOf(input.getIndex()));

            TxOut resolved = resolveUtxo(input.getTransactionId(), input.getIndex());
            result.add(new TxInInfo(outRef, resolved));
        }
        return new JulcArrayList<>(result);
    }

    private TxOut resolveUtxo(String txHash, int index) {
        // Try the provided input UTxOs first
        for (Utxo utxo : inputUtxos) {
            if (txHash.equals(utxo.getTxHash()) && index == utxo.getOutputIndex()) {
                return convertUtxoToTxOut(utxo);
            }
        }

        // Fallback to UtxoSupplier if available
        if (utxoSupplier != null) {
            var result = utxoSupplier.getTxOutput(txHash, index);
            if (result.isPresent()) {
                return convertUtxoToTxOut(result.get());
            }
        }

        throw new IllegalStateException("UTxO not found: " + txHash + "#" + index
                + ". Ensure all input and reference UTxOs are provided.");
    }

    private TxOut convertUtxoToTxOut(Utxo utxo) {
        Address address = CclAddressConverter.fromBech32(utxo.getAddress());
        Value value = CclValueConverter.fromAmounts(utxo.getAmount());

        OutputDatum datum;
        if (utxo.getInlineDatum() != null && !utxo.getInlineDatum().isEmpty()) {
            // Inline datum: deserialize the CBOR hex to CCL PlutusData, then convert
            try {
                byte[] datumBytes = HexFormat.of().parseHex(utxo.getInlineDatum());
                PlutusData cclDatum = PlutusData.deserialize(datumBytes);
                datum = new OutputDatum.OutputDatumInline(PlutusDataAdapter.fromClientLib(cclDatum));
            } catch (Exception e) {
                throw new IllegalStateException("Failed to deserialize inline datum for UTxO: "
                        + utxo.getTxHash() + "#" + utxo.getOutputIndex(), e);
            }
        } else if (utxo.getDataHash() != null && !utxo.getDataHash().isEmpty()) {
            datum = new OutputDatum.OutputDatumHash(
                    DatumHash.of(HexFormat.of().parseHex(utxo.getDataHash())));
        } else {
            datum = new OutputDatum.NoOutputDatum();
        }

        Optional<ScriptHash> referenceScript;
        if (utxo.getReferenceScriptHash() != null && !utxo.getReferenceScriptHash().isEmpty()) {
            referenceScript = Optional.of(
                    ScriptHash.of(HexFormat.of().parseHex(utxo.getReferenceScriptHash())));
        } else {
            referenceScript = Optional.empty();
        }

        return new TxOut(address, value, datum, referenceScript);
    }

    private JulcList<TxOut> convertOutputs(List<TransactionOutput> outputs) {
        if (outputs == null || outputs.isEmpty()) {
            return JulcList.empty();
        }

        var result = new ArrayList<TxOut>(outputs.size());
        for (TransactionOutput txOut : outputs) {
            Address address = CclAddressConverter.fromBech32(txOut.getAddress());
            Value value = CclValueConverter.fromTransactionOutputValue(txOut.getValue());

            OutputDatum datum;
            if (txOut.getInlineDatum() != null) {
                datum = new OutputDatum.OutputDatumInline(
                        PlutusDataAdapter.fromClientLib(txOut.getInlineDatum()));
            } else if (txOut.getDatumHash() != null) {
                datum = new OutputDatum.OutputDatumHash(
                        DatumHash.of(txOut.getDatumHash()));
            } else {
                datum = new OutputDatum.NoOutputDatum();
            }

            Optional<ScriptHash> refScript = Optional.empty();
            if (txOut.getScriptRef() != null) {
                try {
                    var script = com.bloxbean.cardano.client.plutus.spec.PlutusScript
                            .deserializeScriptRef(txOut.getScriptRef());
                    refScript = Optional.of(ScriptHash.of(script.getScriptHash()));
                } catch (Exception e) {
                    // If we can't extract hash, skip reference script
                }
            }

            result.add(new TxOut(address, value, datum, refScript));
        }
        return new JulcArrayList<>(result);
    }

    private JulcMap<Credential, BigInteger> convertWithdrawals(List<Withdrawal> withdrawals) {
        if (withdrawals == null || withdrawals.isEmpty()) {
            return JulcAssocMap.empty();
        }

        JulcMap<Credential, BigInteger> result = JulcAssocMap.empty();
        for (Withdrawal w : withdrawals) {
            // Parse the reward address to extract the credential
            var rewardAddr = new com.bloxbean.cardano.client.address.Address(w.getRewardAddress());
            byte[] credHash = rewardAddr.getDelegationCredentialHash()
                    .or(rewardAddr::getPaymentCredentialHash)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Cannot extract credential from reward address: " + w.getRewardAddress()));

            Credential cred;
            if (rewardAddr.isScriptHashInDelegationPart() || rewardAddr.isScriptHashInPaymentPart()) {
                cred = new Credential.ScriptCredential(ScriptHash.of(credHash));
            } else {
                cred = new Credential.PubKeyCredential(PubKeyHash.of(credHash));
            }

            result = result.insert(cred, w.getCoin());
        }
        return result;
    }

    private Interval convertValidRange(long validityStart, long ttl) {
        IntervalBound from;
        if (validityStart == 0) {
            from = new IntervalBound(new IntervalBoundType.NegInf(), true);
        } else {
            BigInteger fromTime = slotConfig != null
                    ? BigInteger.valueOf(slotConfig.slotToPosixMs(validityStart))
                    : BigInteger.valueOf(validityStart);
            from = new IntervalBound(new IntervalBoundType.Finite(fromTime), true);
        }

        IntervalBound to;
        if (ttl == 0) {
            to = new IntervalBound(new IntervalBoundType.PosInf(), true);
        } else {
            BigInteger toTime = slotConfig != null
                    ? BigInteger.valueOf(slotConfig.slotToPosixMs(ttl))
                    : BigInteger.valueOf(ttl);
            to = new IntervalBound(new IntervalBoundType.Finite(toTime), true);
        }

        return new Interval(from, to);
    }

    private JulcList<PubKeyHash> convertSignatories(List<byte[]> requiredSigners) {
        if (requiredSigners == null || requiredSigners.isEmpty()) {
            return JulcList.empty();
        }

        var result = new ArrayList<PubKeyHash>(requiredSigners.size());
        for (byte[] signer : requiredSigners) {
            result.add(PubKeyHash.of(signer));
        }
        return new JulcArrayList<>(result);
    }

    private JulcMap<ScriptPurpose, com.bloxbean.cardano.julc.core.PlutusData> convertRedeemers(
            List<Redeemer> redeemers) {
        if (redeemers == null || redeemers.isEmpty()) {
            return JulcAssocMap.empty();
        }

        JulcMap<ScriptPurpose, com.bloxbean.cardano.julc.core.PlutusData> result = JulcAssocMap.empty();

        for (Redeemer redeemer : redeemers) {
            ScriptPurpose purpose = redeemerToScriptPurpose(redeemer);
            com.bloxbean.cardano.julc.core.PlutusData redeemerData =
                    PlutusDataAdapter.fromClientLib(redeemer.getData());
            result = result.insert(purpose, redeemerData);
        }

        return result;
    }

    ScriptPurpose redeemerToScriptPurpose(Redeemer redeemer) {
        int index = redeemer.getIndex().intValue();
        return switch (redeemer.getTag()) {
            case Spend -> {
                var sorted = getSortedInputs();
                if (index >= sorted.size()) {
                    throw new IllegalArgumentException(
                            "Spend redeemer index " + index + " out of range (inputs: " + sorted.size() + ")");
                }
                var input = sorted.get(index);
                yield new ScriptPurpose.Spending(new TxOutRef(
                        TxId.of(HexFormat.of().parseHex(input.getTransactionId())),
                        BigInteger.valueOf(input.getIndex())));
            }
            case Mint -> {
                var sortedPolicies = getSortedMintPolicyIds();
                if (index >= sortedPolicies.size()) {
                    throw new IllegalArgumentException(
                            "Mint redeemer index " + index + " out of range (policies: " + sortedPolicies.size() + ")");
                }
                yield new ScriptPurpose.Minting(sortedPolicies.get(index));
            }
            case Reward -> {
                throw new UnsupportedOperationException(
                        "Reward redeemer mapping not yet supported");
            }
            case Cert -> {
                throw new UnsupportedOperationException(
                        "Cert redeemer mapping not yet supported");
            }
            case Voting -> {
                throw new UnsupportedOperationException(
                        "Voting redeemer mapping not yet supported");
            }
            case Proposing -> {
                throw new UnsupportedOperationException(
                        "Proposing redeemer mapping not yet supported");
            }
        };
    }

    private JulcMap<DatumHash, com.bloxbean.cardano.julc.core.PlutusData> convertDatums(
            List<PlutusData> plutusDataList) {
        if (plutusDataList == null || plutusDataList.isEmpty()) {
            return JulcAssocMap.empty();
        }

        JulcMap<DatumHash, com.bloxbean.cardano.julc.core.PlutusData> result = JulcAssocMap.empty();

        for (PlutusData cclDatum : plutusDataList) {
            try {
                // Hash the original serialized bytes to preserve CBOR encoding
                byte[] serializedBytes = com.bloxbean.cardano.client.common.cbor.CborSerializationUtil
                        .serialize(cclDatum.serialize());
                byte[] datumHashBytes = Blake2bUtil.blake2bHash256(serializedBytes);

                DatumHash datumHash = DatumHash.of(datumHashBytes);
                com.bloxbean.cardano.julc.core.PlutusData julcDatum =
                        PlutusDataAdapter.fromClientLib(cclDatum);

                result = result.insert(datumHash, julcDatum);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to hash witness datum", e);
            }
        }

        return result;
    }

    private TxId computeTxId(TransactionBody body) {
        try {
            byte[] bodyBytes = com.bloxbean.cardano.client.common.cbor.CborSerializationUtil
                    .serialize(body.serialize());
            byte[] hash = Blake2bUtil.blake2bHash256(bodyBytes);
            return TxId.of(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute TxId", e);
        }
    }
}
