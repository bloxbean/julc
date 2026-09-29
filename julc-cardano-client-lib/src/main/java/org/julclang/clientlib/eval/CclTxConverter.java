package org.julclang.clientlib.eval;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.NegativeInteger;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.Special;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.transaction.spec.cert.*;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionId;
import org.julclang.core.PlutusData;
import org.julclang.core.cbor.PlutusDataCborDecoder;
import org.julclang.core.types.JulcArrayList;
import org.julclang.core.types.JulcAssocMap;
import org.julclang.core.types.JulcList;
import org.julclang.core.types.JulcMap;
import org.julclang.ledger.*;

import java.math.BigInteger;
import java.util.*;

/**
 * Converts a CCL {@link Transaction} and resolved UTxOs into a JuLC {@link TxInfo}.
 */
final class CclTxConverter {

    private static final int BODY_TTL = 3;
    private static final int BODY_VALIDITY_START = 8;
    private static final int BODY_PROPOSAL_PROCEDURES = 20;

    private static final System.Logger LOG = System.getLogger(CclTxConverter.class.getName());
    private static volatile boolean slotConfigWarningLogged = false;

    private final Transaction tx;
    private final OriginalTxBytes original;
    private final Set<Utxo> inputUtxos;
    private final UtxoSupplier utxoSupplier;
    private final SlotConfig slotConfig;
    private final int protocolMajorVersion;

    // Cached sorted inputs for redeemer index mapping
    private List<TransactionInput> sortedInputs;
    // Derived once from the transaction (the context and the redeemer indexes read them)
    private LinkedHashMap<Credential, BigInteger> withdrawals;
    private TreeMap<Voter, TreeMap<GovernanceActionId, Vote>> votes;
    private List<ProposalProcedure> proposals;
    private java.util.Map<Long, byte[]> redeemerData;

    /**
     * @param tx      the transaction
     * @param txCbor  the transaction's original bytes, which the ledger hashes (TxId, witness datums)
     */
    CclTxConverter(Transaction tx, byte[] txCbor, Set<Utxo> inputUtxos,
                   UtxoSupplier utxoSupplier, SlotConfig slotConfig,
                   int protocolMajorVersion) {
        this.tx = Objects.requireNonNull(tx);
        this.original = new OriginalTxBytes(Objects.requireNonNull(txCbor));
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
        JulcMap<Credential, BigInteger> withdrawals = convertWithdrawals();

        // 8. Valid range (from the body's keys: CCL keeps an absent bound as slot 0)
        Interval validRange = convertValidRange(
                original.bodyUnsigned(BODY_VALIDITY_START), original.bodyUnsigned(BODY_TTL));

        // 9. Signatories
        JulcList<PubKeyHash> signatories = convertSignatories(body.getRequiredSigners());

        // 10. Redeemers
        JulcMap<ScriptPurpose, PlutusData> redeemers =
                convertRedeemers(tx.getWitnessSet().getRedeemers());

        // 11. Datums: the witness set's, keyed by the hash of their original bytes
        JulcMap<DatumHash, PlutusData> datums = convertWitnessDatums();

        // 12. TxId: the hash of the original body bytes
        TxId txId = TxId.of(Blake2bUtil.blake2bHash256(original.body()));

        // 13. Votes
        JulcMap<Voter, JulcMap<GovernanceActionId, Vote>> votes = convertVotingProcedures();

        // 14. Proposal procedures
        List<ProposalProcedure> proposalList = proposals();
        JulcList<ProposalProcedure> proposalProcedures = proposalList.isEmpty()
                ? JulcList.empty() : new JulcArrayList<>(proposalList);

        // 15-16. Treasury
        Optional<BigInteger> currentTreasuryAmount =
                Optional.ofNullable(body.getCurrentTreasuryValue());
        Optional<BigInteger> treasuryDonation =
                Optional.ofNullable(body.getDonation()).filter(d -> d.signum() != 0);

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
                datum = new OutputDatum.OutputDatumInline(
                        PlutusDataCborDecoder.decode(HexFormat.of().parseHex(utxo.getInlineDatum())));
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

        // Inline datums from the original bytes, like every datum the context holds
        List<byte[]> inlineDatums = original.outputInlineDatums();
        var result = new ArrayList<TxOut>(outputs.size());
        for (int i = 0; i < outputs.size(); i++) {
            TransactionOutput txOut = outputs.get(i);
            Address address = CclAddressConverter.fromBech32(txOut.getAddress());
            Value value = CclValueConverter.fromTransactionOutputValue(txOut.getValue());

            OutputDatum datum;
            if (inlineDatums.get(i) != null) {
                datum = new OutputDatum.OutputDatumInline(PlutusDataCborDecoder.decode(inlineDatums.get(i)));
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

    private JulcMap<Credential, BigInteger> convertWithdrawals() {
        return LedgerOrder.assocMap(withdrawals());
    }

    /** The withdrawals in the ledger's {@code Map AccountAddress} order (redeemer indexes and the V3 map). */
    private LinkedHashMap<Credential, BigInteger> withdrawals() {
        if (withdrawals == null) {
            var byAccount = new TreeMap<byte[], BigInteger>(LedgerOrder.ACCOUNT);
            List<Withdrawal> list = tx.getBody().getWithdrawals();
            if (list != null) {
                for (Withdrawal w : list) {
                    byAccount.put(new com.bloxbean.cardano.client.address.Address(w.getRewardAddress()).getBytes(),
                            w.getCoin());
                }
            }
            withdrawals = accountMap(byAccount);
        }
        return withdrawals;
    }

    /** Reward accounts in ledger order, keyed by their credentials. */
    private static LinkedHashMap<Credential, BigInteger> accountMap(TreeMap<byte[], BigInteger> byAccount) {
        var result = new LinkedHashMap<Credential, BigInteger>();
        byAccount.forEach((account, coin) -> result.put(accountCredential(account), coin));
        return result;
    }

    /** The credential of a reward account (header byte, then the 28-byte hash). */
    private static Credential accountCredential(byte[] account) {
        byte[] hash = Arrays.copyOfRange(account, 1, 29);
        return (account[0] & 0x10) != 0
                ? new Credential.ScriptCredential(ScriptHash.of(hash))
                : new Credential.PubKeyCredential(PubKeyHash.of(hash));
    }

    /** Conway's {@code transValidityInterval}: a present bound (even slot 0) is finite. Null means absent. */
    private Interval convertValidRange(BigInteger validityStart, BigInteger ttl) {
        if (slotConfig == null && (validityStart != null || ttl != null) && !slotConfigWarningLogged) {
            slotConfigWarningLogged = true;
            LOG.log(System.Logger.Level.WARNING,
                    "SlotConfig is null — validity range will use raw slot numbers instead of POSIX time. "
                    + "Time-sensitive validators will likely fail. Pass a SlotConfig to JulcTransactionEvaluator.");
        }

        IntervalBound from = validityStart == null
                ? new IntervalBound(new IntervalBoundType.NegInf(), true)
                : new IntervalBound(new IntervalBoundType.Finite(toTime(validityStart)), true);

        IntervalBound to;
        if (ttl == null) {
            to = new IntervalBound(new IntervalBoundType.PosInf(), true);
        } else {
            // Scalus/Cardano ledger: when both bounds are set, upper is always exclusive.
            // When only TTL is set (no lower), closure depends on protocol version:
            //   PV <= 8 (V1/V2 Babbage): inclusive (true)
            //   PV >= 9 (V3 Conway+): exclusive (false)
            boolean upperInclusive = validityStart == null && protocolMajorVersion <= 8;
            to = new IntervalBound(new IntervalBoundType.Finite(toTime(ttl)), upperInclusive);
        }

        return new Interval(from, to);
    }

    private BigInteger toTime(BigInteger slot) {
        return slotConfig != null ? BigInteger.valueOf(slotConfig.slotToPosixMs(slot.longValueExact())) : slot;
    }

    private JulcList<PubKeyHash> convertSignatories(List<byte[]> requiredSigners) {
        if (requiredSigners == null || requiredSigners.isEmpty()) {
            return JulcList.empty();
        }

        // A set in the ledger: hash order, no duplicates
        var sorted = new TreeSet<byte[]>(LedgerOrder.BYTES);
        sorted.addAll(requiredSigners);
        var result = new ArrayList<PubKeyHash>(sorted.size());
        for (byte[] signer : sorted) {
            result.add(PubKeyHash.of(signer));
        }
        return new JulcArrayList<>(result);
    }

    private JulcMap<ScriptPurpose, PlutusData> convertRedeemers(
            List<Redeemer> redeemers) {
        if (redeemers == null || redeemers.isEmpty()) {
            return JulcAssocMap.empty();
        }

        // The ledger's Redeemers map, in (tag, index) order; a later duplicate replaces an earlier one
        var byPointer = new TreeMap<Long, Redeemer>();
        for (Redeemer redeemer : redeemers) {
            byPointer.put(OriginalTxBytes.pointer(redeemer.getTag().value, redeemer.getIndex().longValueExact()),
                    redeemer);
        }
        var result = new LinkedHashMap<ScriptPurpose, PlutusData>();
        for (Redeemer redeemer : byPointer.values()) {
            result.put(redeemerToScriptPurpose(redeemer), redeemerData(redeemer));
        }
        return LedgerOrder.assocMap(result);
    }

    /** A redeemer's data, decoded from its original bytes. */
    PlutusData redeemerData(Redeemer redeemer) {
        if (redeemerData == null) {
            redeemerData = original.redeemerData();
        }
        byte[] data = redeemerData.get(
                OriginalTxBytes.pointer(redeemer.getTag().value, redeemer.getIndex().longValueExact()));
        if (data == null) {
            throw new IllegalStateException("No redeemer " + redeemer.getTag() + "[" + redeemer.getIndex() + "]");
        }
        return PlutusDataCborDecoder.decode(data);
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
                var proposals = proposals();
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
     * Withdrawal credentials in ledger order (for Reward redeemer index mapping).
     */
    List<Credential> getSortedWithdrawalCredentials() {
        return new ArrayList<>(withdrawals().keySet());
    }

    /**
     * Voters in ledger order (for Voting redeemer index mapping).
     */
    List<Voter> getSortedVoters() {
        return new ArrayList<>(votes().keySet());
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
            // PV9 (Conway bootstrap) omits these deposits: transTxCert, Conway/TxInfo.hs
            case RegCert rc -> new TxCert.RegStaking(
                    convertStakeCredential(rc.getStakeCredential()),
                    bootstrapDeposit(rc.getCoin()));
            case UnregCert uc -> new TxCert.UnRegStaking(
                    convertStakeCredential(uc.getStakeCredential()),
                    bootstrapDeposit(uc.getCoin()));
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

    private Optional<BigInteger> bootstrapDeposit(BigInteger deposit) {
        return protocolMajorVersion == 9 ? Optional.empty() : Optional.ofNullable(deposit);
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

    private JulcMap<Voter, JulcMap<GovernanceActionId, Vote>> convertVotingProcedures() {
        var result = new LinkedHashMap<Voter, JulcMap<GovernanceActionId, Vote>>();
        votes().forEach((voter, actions) -> result.put(voter, LedgerOrder.assocMap(actions)));
        return LedgerOrder.assocMap(result);
    }

    /** The voting procedures: voters and action ids in ledger order. */
    private TreeMap<Voter, TreeMap<GovernanceActionId, Vote>> votes() {
        if (votes == null) {
            votes = new TreeMap<>(LedgerOrder.VOTER);
            var votingProcs = tx.getBody().getVotingProcedures();
            if (votingProcs != null && votingProcs.getVoting() != null) {
                for (var entry : votingProcs.getVoting().entrySet()) {
                    var actions = votes.computeIfAbsent(convertVoter(entry.getKey()),
                            v -> new TreeMap<>(LedgerOrder.GOV_ACTION_ID));
                    for (var voteEntry : entry.getValue().entrySet()) {
                        actions.put(convertGovActionId(voteEntry.getKey()),
                                convertVote(voteEntry.getValue().getVote()));
                    }
                }
            }
        }
        return votes;
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


    // --- Proposal procedures, from the body's original CBOR (key 20) ---

    /** The proposal procedures in body order, each translated as Conway's {@code transProposal}. */
    List<ProposalProcedure> proposals() {
        if (proposals == null) {
            var result = new ArrayList<ProposalProcedure>();
            DataItem field = original.bodyField(BODY_PROPOSAL_PROCEDURES);
            if (field != null) {
                for (DataItem item : items(field)) {
                    List<DataItem> p = items(item);
                    result.add(new ProposalProcedure(unsigned(p.get(0)), accountCredential(bytes(p.get(1))),
                            govAction(items(p.get(2)))));
                }
            }
            proposals = List.copyOf(result);
        }
        return proposals;
    }

    /** Conway's {@code transGovAction}. */
    private static GovernanceAction govAction(List<DataItem> a) {
        int tag = unsigned(a.get(0)).intValueExact();
        return switch (tag) {
            case 0 -> new GovernanceAction.ParameterChange(prevActionId(a.get(1)),
                    changedParameter(a.get(2)), scriptHash(a.get(3)));
            case 1 -> {
                List<DataItem> version = items(a.get(2));
                yield new GovernanceAction.HardForkInitiation(prevActionId(a.get(1)),
                        new ProtocolVersion(unsigned(version.get(0)), unsigned(version.get(1))));
            }
            case 2 -> {
                var byAccount = new TreeMap<byte[], BigInteger>(LedgerOrder.ACCOUNT);
                var withdrawals = (co.nstant.in.cbor.model.Map) a.get(1);
                for (DataItem account : withdrawals.getKeys()) {
                    byAccount.put(bytes(account), unsigned(withdrawals.get(account)));
                }
                yield new GovernanceAction.TreasuryWithdrawals(LedgerOrder.assocMap(accountMap(byAccount)),
                        scriptHash(a.get(2)));
            }
            case 3 -> new GovernanceAction.NoConfidence(prevActionId(a.get(1)));
            case 4 -> {
                var removed = new TreeSet<Credential>(LedgerOrder.LEDGER_CREDENTIAL);
                for (DataItem member : items(a.get(2))) {
                    removed.add(credential(member));
                }
                var added = new TreeMap<Credential, BigInteger>(LedgerOrder.LEDGER_CREDENTIAL);
                var terms = (co.nstant.in.cbor.model.Map) a.get(3);
                for (DataItem member : terms.getKeys()) {
                    added.put(credential(member), unsigned(terms.get(member)));
                }
                BigInteger[] quorum = rational(a.get(4));
                yield new GovernanceAction.UpdateCommittee(prevActionId(a.get(1)),
                        new JulcArrayList<>(new ArrayList<>(removed)), LedgerOrder.assocMap(added),
                        new Rational(quorum[0], quorum[1]));
            }
            case 5 -> new GovernanceAction.NewConstitution(prevActionId(a.get(1)),
                    scriptHash(items(a.get(2)).get(1)));
            case 6 -> new GovernanceAction.InfoAction();
            default -> throw new IllegalArgumentException("Unknown governance action tag: " + tag);
        };
    }

    /**
     * {@code ChangedParameters}: the ledger's {@code ToPlutusData PParamsUpdate} (Conway/PParams.hs), a map from each
     * present parameter's key to its value in ascending key order. Integers stay integers, rationals become
     * {@code [n, d]} in lowest terms, arrays (ExUnits, prices, voting thresholds) become lists, and cost models a map
     * in key order.
     */
    private static PlutusData changedParameter(DataItem item) {
        if (isRational(item)) {
            BigInteger[] r = rational(item);
            return new PlutusData.ListData(List.of(
                    new PlutusData.IntData(r[0]), new PlutusData.IntData(r[1])));
        }
        return switch (item) {
            case UnsignedInteger u -> new PlutusData.IntData(u.getValue());
            case NegativeInteger n -> new PlutusData.IntData(n.getValue());
            case Array array -> {
                var list = new ArrayList<PlutusData>();
                for (DataItem element : items(array)) {
                    list.add(changedParameter(element));
                }
                yield new PlutusData.ListData(list);
            }
            case co.nstant.in.cbor.model.Map map -> {
                var byKey = new TreeMap<BigInteger, PlutusData>();
                for (DataItem key : map.getKeys()) {
                    byKey.put(integer(key), changedParameter(map.get(key)));
                }
                var entries = new ArrayList<PlutusData.Pair>();
                byKey.forEach((key, value) -> entries.add(new PlutusData.Pair(
                        new PlutusData.IntData(key), value)));
                yield new PlutusData.MapData(entries);
            }
            default -> throw new IllegalArgumentException("Unexpected CBOR in a parameter update: " + item);
        };
    }

    private static Optional<GovernanceActionId> prevActionId(DataItem item) {
        if (SimpleValue.NULL.equals(item)) {
            return Optional.empty();
        }
        List<DataItem> id = items(item);
        return Optional.of(new GovernanceActionId(TxId.of(bytes(id.get(0))), unsigned(id.get(1))));
    }

    private static Optional<ScriptHash> scriptHash(DataItem item) {
        return SimpleValue.NULL.equals(item)
                ? Optional.empty() : Optional.of(ScriptHash.of(bytes(item)));
    }

    private static Credential credential(DataItem item) {
        List<DataItem> c = items(item);
        byte[] hash = bytes(c.get(1));
        return unsigned(c.get(0)).signum() == 0
                ? new Credential.PubKeyCredential(PubKeyHash.of(hash))
                : new Credential.ScriptCredential(ScriptHash.of(hash));
    }

    private static boolean isRational(DataItem item) {
        return item instanceof Array && item.getTag() != null && item.getTag().getValue() == 30;
    }

    /** A tag-30 rational in lowest terms (Haskell {@code Rational} is always normalised). */
    private static BigInteger[] rational(DataItem item) {
        List<DataItem> r = items(item);
        BigInteger n = integer(r.get(0));
        BigInteger d = integer(r.get(1));
        BigInteger gcd = n.gcd(d);
        return gcd.signum() == 0 ? new BigInteger[]{n, d} : new BigInteger[]{n.divide(gcd), d.divide(gcd)};
    }

    private static List<DataItem> items(DataItem item) {
        var result = new ArrayList<DataItem>();
        for (DataItem element : ((Array) item).getDataItems()) {
            if (!Special.BREAK.equals(element)) {
                result.add(element);
            }
        }
        return result;
    }

    private static BigInteger unsigned(DataItem item) {
        return ((UnsignedInteger) item).getValue();
    }

    private static BigInteger integer(DataItem item) {
        return item instanceof NegativeInteger n ? n.getValue() : unsigned(item);
    }

    private static byte[] bytes(DataItem item) {
        return ((ByteString) item).getBytes();
    }

    // --- Witness datums, from the original bytes ---

    /**
     * The witness datums keyed by the hash of their original bytes ({@code TxDats}, {@code hashData}), in hash
     * order. Only witness datums: scripts read inline datums through the resolved outputs.
     */
    private JulcMap<DatumHash, PlutusData> convertWitnessDatums() {
        var byHash = new TreeMap<byte[], PlutusData>(LedgerOrder.BYTES);
        for (byte[] datum : original.witnessDatums()) {
            byHash.putIfAbsent(Blake2bUtil.blake2bHash256(datum), PlutusDataCborDecoder.decode(datum));
        }
        var result = new LinkedHashMap<DatumHash, PlutusData>();
        byHash.forEach((hash, datum) -> result.put(DatumHash.of(hash), datum));
        return LedgerOrder.assocMap(result);
    }
}
