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
import com.bloxbean.cardano.client.transaction.spec.cert.*;
import com.bloxbean.cardano.client.transaction.spec.governance.VotingProcedures;
import com.bloxbean.cardano.client.transaction.spec.governance.VotingProcedure;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionId;
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

    private static final System.Logger LOG = System.getLogger(CclTxConverter.class.getName());
    private static volatile boolean slotConfigWarningLogged = false;

    private final Transaction tx;
    private final Set<Utxo> inputUtxos;
    private final UtxoSupplier utxoSupplier;
    private final SlotConfig slotConfig;
    private final int protocolMajorVersion;

    // Cached sorted inputs for redeemer index mapping
    private List<TransactionInput> sortedInputs;

    CclTxConverter(Transaction tx, Set<Utxo> inputUtxos,
                   UtxoSupplier utxoSupplier, SlotConfig slotConfig) {
        this(tx, inputUtxos, utxoSupplier, slotConfig, 10);
    }

    CclTxConverter(Transaction tx, Set<Utxo> inputUtxos,
                   UtxoSupplier utxoSupplier, SlotConfig slotConfig,
                   int protocolMajorVersion) {
        this.tx = Objects.requireNonNull(tx);
        this.inputUtxos = inputUtxos != null ? inputUtxos : Set.of();
        this.utxoSupplier = utxoSupplier;
        this.slotConfig = slotConfig;
        this.protocolMajorVersion = protocolMajorVersion;
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

        // 6. Certificates
        JulcList<TxCert> certificates = convertCertificatesList(body.getCerts());

        // 7. Withdrawals
        JulcMap<Credential, BigInteger> withdrawals = convertWithdrawals(body.getWithdrawals());

        // 8. Valid range
        Interval validRange = convertValidRange(body.getValidityStartInterval(), body.getTtl());

        // 9. Signatories
        JulcList<PubKeyHash> signatories = convertSignatories(body.getRequiredSigners());

        // 10. Redeemers
        JulcMap<ScriptPurpose, com.bloxbean.cardano.julc.core.PlutusData> redeemers =
                convertRedeemers(tx.getWitnessSet().getRedeemers());

        // 11. Datums: witness set datums + inline datums from all inputs/refInputs/outputs
        JulcMap<DatumHash, com.bloxbean.cardano.julc.core.PlutusData> datums = convertAllDatums(
                tx.getWitnessSet().getPlutusDataList(), inputs, referenceInputs, outputs);

        // 12. TxId from body hash
        TxId txId = computeTxId(body);

        // 13. Votes
        JulcMap<Voter, JulcMap<GovernanceActionId, Vote>> votes = convertVotingProcedures(
                body.getVotingProcedures());

        // 14. Proposal procedures
        JulcList<ProposalProcedure> proposalProcedures = convertProposalProceduresList(
                body.getProposalProcedures());

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
            try {
                byte[] datumBytes = HexFormat.of().parseHex(utxo.getInlineDatum());
                PlutusData cclDatum = PlutusData.deserialize(datumBytes);
                var julcDatum = PlutusDataAdapter.fromClientLib(cclDatum);
                datum = new OutputDatum.OutputDatumInline(julcDatum);
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
        if (slotConfig == null && (validityStart != 0 || ttl != 0) && !slotConfigWarningLogged) {
            slotConfigWarningLogged = true;
            LOG.log(System.Logger.Level.WARNING,
                    "SlotConfig is null — validity range will use raw slot numbers instead of POSIX time. "
                    + "Time-sensitive validators will likely fail. Pass a SlotConfig to JulcTransactionEvaluator.");
        }

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
            // Scalus/Cardano ledger: when both bounds are set, upper is always exclusive.
            // When only TTL is set (no lower), closure depends on protocol version:
            //   PV <= 8 (V1/V2 Babbage): inclusive (true)
            //   PV >= 9 (V3 Conway+): exclusive (false)
            boolean upperInclusive;
            if (validityStart != 0) {
                upperInclusive = false; // both bounds set → always exclusive
            } else {
                upperInclusive = protocolMajorVersion <= 8; // only TTL → PV-dependent
            }
            to = new IntervalBound(new IntervalBoundType.Finite(toTime), upperInclusive);
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
                var sortedWithdrawals = getSortedWithdrawalCredentials();
                if (index >= sortedWithdrawals.size()) {
                    throw new IllegalArgumentException(
                            "Reward redeemer index " + index + " out of range (withdrawals: "
                            + sortedWithdrawals.size() + ")");
                }
                yield new ScriptPurpose.Rewarding(sortedWithdrawals.get(index));
            }
            case Cert -> {
                var certs = convertCertificates(tx.getBody().getCerts());
                if (index >= certs.size()) {
                    throw new IllegalArgumentException(
                            "Cert redeemer index " + index + " out of range (certs: " + certs.size() + ")");
                }
                yield new ScriptPurpose.Certifying(BigInteger.valueOf(index), certs.get(index));
            }
            case Voting -> {
                var sortedVoters = getSortedVoters();
                if (index >= sortedVoters.size()) {
                    throw new IllegalArgumentException(
                            "Voting redeemer index " + index + " out of range (voters: "
                            + sortedVoters.size() + ")");
                }
                yield new ScriptPurpose.Voting(sortedVoters.get(index));
            }
            case Proposing -> {
                var proposals = convertProposalProcedures(tx.getBody().getProposalProcedures());
                if (index >= proposals.size()) {
                    throw new IllegalArgumentException(
                            "Proposing redeemer index " + index + " out of range (proposals: "
                            + proposals.size() + ")");
                }
                yield new ScriptPurpose.Proposing(BigInteger.valueOf(index), proposals.get(index));
            }
        };
    }

    // --- Governance conversion helpers ---

    /**
     * Get sorted withdrawal credentials list (for Reward redeemer index mapping).
     */
    List<Credential> getSortedWithdrawalCredentials() {
        List<Withdrawal> withdrawals = tx.getBody().getWithdrawals();
        if (withdrawals == null || withdrawals.isEmpty()) {
            return List.of();
        }
        // Sort by reward address (lexicographic) to match ledger ordering
        var sorted = new ArrayList<>(withdrawals);
        sorted.sort(Comparator.comparing(Withdrawal::getRewardAddress));
        var result = new ArrayList<Credential>(sorted.size());
        for (Withdrawal w : sorted) {
            var rewardAddr = new com.bloxbean.cardano.client.address.Address(w.getRewardAddress());
            byte[] credHash = rewardAddr.getDelegationCredentialHash()
                    .or(rewardAddr::getPaymentCredentialHash)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Cannot extract credential from reward address: " + w.getRewardAddress()));
            if (rewardAddr.isScriptHashInDelegationPart() || rewardAddr.isScriptHashInPaymentPart()) {
                result.add(new Credential.ScriptCredential(ScriptHash.of(credHash)));
            } else {
                result.add(new Credential.PubKeyCredential(PubKeyHash.of(credHash)));
            }
        }
        return result;
    }

    /**
     * Get sorted voters from voting procedures (for Voting redeemer index mapping).
     */
    List<Voter> getSortedVoters() {
        var votingProcs = tx.getBody().getVotingProcedures();
        if (votingProcs == null || votingProcs.getVoting() == null || votingProcs.getVoting().isEmpty()) {
            return List.of();
        }
        // Convert and sort voters
        var voters = new ArrayList<Voter>();
        for (var cclVoter : votingProcs.getVoting().keySet()) {
            voters.add(convertVoter(cclVoter));
        }
        // Sort by PlutusData encoding for deterministic ordering
        voters.sort(Comparator.comparing(v -> v.toPlutusData().toString()));
        return voters;
    }

    /**
     * Convert a list of CCL certificates to julc TxCert list.
     */
    List<TxCert> convertCertificates(List<Certificate> certs) {
        if (certs == null || certs.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<TxCert>(certs.size());
        for (Certificate cert : certs) {
            result.add(convertCertificate(cert));
        }
        return result;
    }

    private JulcList<TxCert> convertCertificatesList(List<Certificate> certs) {
        if (certs == null || certs.isEmpty()) {
            return JulcList.empty();
        }
        return new JulcArrayList<>(convertCertificates(certs));
    }

    private TxCert convertCertificate(Certificate cert) {
        return switch (cert) {
            case StakeRegistration sr -> new TxCert.RegStaking(
                    convertStakeCredential(sr.getStakeCredential()), Optional.empty());
            case StakeDeregistration sd -> new TxCert.UnRegStaking(
                    convertStakeCredential(sd.getStakeCredential()), Optional.empty());
            case StakeDelegation sd -> new TxCert.DelegStaking(
                    convertStakeCredential(sd.getStakeCredential()),
                    new Delegatee.Stake(PubKeyHash.of(sd.getStakePoolId().getPoolKeyHash())));
            case PoolRegistration pr -> new TxCert.PoolRegister(
                    PubKeyHash.of(pr.getOperator()),
                    PubKeyHash.of(pr.getVrfKeyHash()));
            case PoolRetirement pr -> new TxCert.PoolRetire(
                    PubKeyHash.of(pr.getPoolKeyHash()),
                    BigInteger.valueOf(pr.getEpoch()));
            case RegCert rc -> new TxCert.RegStaking(
                    convertStakeCredential(rc.getStakeCredential()),
                    Optional.ofNullable(rc.getCoin()));
            case UnregCert uc -> new TxCert.UnRegStaking(
                    convertStakeCredential(uc.getStakeCredential()),
                    Optional.ofNullable(uc.getCoin()));
            case VoteDelegCert vdc -> new TxCert.DelegStaking(
                    convertStakeCredential(vdc.getStakeCredential()),
                    new Delegatee.Vote(convertCclDRep(vdc.getDrep())));
            case StakeVoteDelegCert svdc -> new TxCert.DelegStaking(
                    convertStakeCredential(svdc.getStakeCredential()),
                    new Delegatee.StakeVote(
                            PubKeyHash.of(HexFormat.of().parseHex(svdc.getPoolKeyHash())),
                            convertCclDRep(svdc.getDrep())));
            case StakeRegDelegCert srdc -> new TxCert.RegDeleg(
                    convertStakeCredential(srdc.getStakeCredential()),
                    new Delegatee.Stake(PubKeyHash.of(HexFormat.of().parseHex(srdc.getPoolKeyHash()))),
                    srdc.getCoin());
            case VoteRegDelegCert vrdc -> new TxCert.RegDeleg(
                    convertStakeCredential(vrdc.getStakeCredential()),
                    new Delegatee.Vote(convertCclDRep(vrdc.getDrep())),
                    vrdc.getCoin());
            case StakeVoteRegDelegCert svrdc -> new TxCert.RegDeleg(
                    convertStakeCredential(svrdc.getStakeCredential()),
                    new Delegatee.StakeVote(
                            PubKeyHash.of(HexFormat.of().parseHex(svrdc.getPoolKeyHash())),
                            convertCclDRep(svrdc.getDrep())),
                    svrdc.getCoin());
            case AuthCommitteeHotCert ahc -> new TxCert.AuthHotCommittee(
                    convertCclCredential(ahc.getCommitteeColdCredential()),
                    convertCclCredential(ahc.getCommitteeHotCredential()));
            case ResignCommitteeColdCert rcc -> new TxCert.ResignColdCommittee(
                    convertCclCredential(rcc.getCommitteeColdCredential()));
            case RegDRepCert rdc -> new TxCert.RegDRep(
                    convertCclCredential(rdc.getDrepCredential()),
                    rdc.getCoin());
            case UnregDRepCert udc -> new TxCert.UnRegDRep(
                    convertCclCredential(udc.getDrepCredential()),
                    udc.getCoin());
            case UpdateDRepCert udc -> new TxCert.UpdateDRep(
                    convertCclCredential(udc.getDrepCredential()));
            default -> throw new UnsupportedOperationException(
                    "Unsupported certificate type: " + cert.getClass().getSimpleName());
        };
    }

    private Credential convertStakeCredential(StakeCredential sc) {
        byte[] hash = sc.getHash();
        return switch (sc.getType()) {
            case ADDR_KEYHASH -> new Credential.PubKeyCredential(PubKeyHash.of(hash));
            case SCRIPTHASH -> new Credential.ScriptCredential(ScriptHash.of(hash));
        };
    }

    private Credential convertCclCredential(
            com.bloxbean.cardano.client.address.Credential cclCred) {
        if (cclCred == null) {
            throw new IllegalArgumentException("Credential is null");
        }
        byte[] hash = cclCred.getBytes();
        return switch (cclCred.getType()) {
            case Key -> new Credential.PubKeyCredential(PubKeyHash.of(hash));
            case Script -> new Credential.ScriptCredential(ScriptHash.of(hash));
        };
    }

    private DRep convertCclDRep(com.bloxbean.cardano.client.transaction.spec.governance.DRep cclDRep) {
        return switch (cclDRep.getType()) {
            case ADDR_KEYHASH -> new DRep.DRepCredential(
                    new Credential.PubKeyCredential(PubKeyHash.of(HexFormat.of().parseHex(cclDRep.getHash()))));
            case SCRIPTHASH -> new DRep.DRepCredential(
                    new Credential.ScriptCredential(ScriptHash.of(HexFormat.of().parseHex(cclDRep.getHash()))));
            case ABSTAIN -> new DRep.AlwaysAbstain();
            case NO_CONFIDENCE -> new DRep.AlwaysNoConfidence();
        };
    }

    private JulcMap<Voter, JulcMap<GovernanceActionId, Vote>> convertVotingProcedures(
            VotingProcedures votingProcs) {
        if (votingProcs == null || votingProcs.getVoting() == null || votingProcs.getVoting().isEmpty()) {
            return JulcAssocMap.empty();
        }
        JulcMap<Voter, JulcMap<GovernanceActionId, Vote>> result = JulcAssocMap.empty();
        for (var entry : votingProcs.getVoting().entrySet()) {
            Voter voter = convertVoter(entry.getKey());
            JulcMap<GovernanceActionId, Vote> innerMap = JulcAssocMap.empty();
            for (var voteEntry : entry.getValue().entrySet()) {
                GovernanceActionId actionId = convertGovActionId(voteEntry.getKey());
                Vote vote = convertVote(voteEntry.getValue().getVote());
                innerMap = innerMap.insert(actionId, vote);
            }
            result = result.insert(voter, innerMap);
        }
        return result;
    }

    private Voter convertVoter(com.bloxbean.cardano.client.transaction.spec.governance.Voter cclVoter) {
        var cred = cclVoter.getCredential();
        byte[] credBytes = cred.getBytes();
        return switch (cclVoter.getType()) {
            case CONSTITUTIONAL_COMMITTEE_HOT_KEY_HASH ->
                    new Voter.CommitteeVoter(new Credential.PubKeyCredential(PubKeyHash.of(credBytes)));
            case CONSTITUTIONAL_COMMITTEE_HOT_SCRIPT_HASH ->
                    new Voter.CommitteeVoter(new Credential.ScriptCredential(ScriptHash.of(credBytes)));
            case DREP_KEY_HASH ->
                    new Voter.DRepVoter(new Credential.PubKeyCredential(PubKeyHash.of(credBytes)));
            case DREP_SCRIPT_HASH ->
                    new Voter.DRepVoter(new Credential.ScriptCredential(ScriptHash.of(credBytes)));
            case STAKING_POOL_KEY_HASH ->
                    new Voter.StakePoolVoter(PubKeyHash.of(credBytes));
        };
    }

    private GovernanceActionId convertGovActionId(GovActionId cclId) {
        return new GovernanceActionId(
                TxId.of(HexFormat.of().parseHex(cclId.getTransactionId())),
                BigInteger.valueOf(cclId.getGovActionIndex()));
    }

    private Vote convertVote(com.bloxbean.cardano.client.transaction.spec.governance.Vote cclVote) {
        return switch (cclVote) {
            case YES -> new Vote.VoteYes();
            case NO -> new Vote.VoteNo();
            case ABSTAIN -> new Vote.Abstain();
        };
    }

    List<ProposalProcedure> convertProposalProcedures(
            List<com.bloxbean.cardano.client.transaction.spec.governance.ProposalProcedure> cclProposals) {
        if (cclProposals == null || cclProposals.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<ProposalProcedure>(cclProposals.size());
        for (var cclProposal : cclProposals) {
            var rewardAddr = new com.bloxbean.cardano.client.address.Address(cclProposal.getRewardAccount());
            byte[] credHash = rewardAddr.getDelegationCredentialHash()
                    .or(rewardAddr::getPaymentCredentialHash)
                    .orElseThrow();
            Credential returnAddr;
            if (rewardAddr.isScriptHashInDelegationPart() || rewardAddr.isScriptHashInPaymentPart()) {
                returnAddr = new Credential.ScriptCredential(ScriptHash.of(credHash));
            } else {
                returnAddr = new Credential.PubKeyCredential(PubKeyHash.of(credHash));
            }
            // Governance action is encoded as raw PlutusData since the CCL GovAction
            // subtypes don't map 1:1 to julc GovernanceAction without significant conversion.
            // Use InfoAction as a placeholder for now since it's the simplest variant.
            GovernanceAction govAction = new GovernanceAction.InfoAction();

            result.add(new ProposalProcedure(
                    cclProposal.getDeposit(),
                    returnAddr,
                    govAction));
        }
        return result;
    }

    private JulcList<ProposalProcedure> convertProposalProceduresList(
            List<com.bloxbean.cardano.client.transaction.spec.governance.ProposalProcedure> cclProposals) {
        if (cclProposals == null || cclProposals.isEmpty()) {
            return JulcList.empty();
        }
        return new JulcArrayList<>(convertProposalProcedures(cclProposals));
    }

    /**
     * Collect datums from the transaction witness set.
     * <p>
     * Per the Cardano ledger specification, the txInfoData map in V1/V2/V3 ScriptContext
     * contains ONLY witness set datums (from {@code datsTxWitsL}). Inline datums are
     * NOT included — scripts access them through the resolved TxOut's OutputDatum field.
     */
    private JulcMap<DatumHash, com.bloxbean.cardano.julc.core.PlutusData> convertAllDatums(
            List<PlutusData> witnessDatums,
            JulcList<TxInInfo> inputs, JulcList<TxInInfo> referenceInputs,
            JulcList<TxOut> outputs) {

        JulcMap<DatumHash, com.bloxbean.cardano.julc.core.PlutusData> result = JulcAssocMap.empty();

        // Witness set datums only
        if (witnessDatums != null) {
            for (PlutusData cclDatum : witnessDatums) {
                try {
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
