package org.julclang.playground.uplc;

import com.bloxbean.cardano.client.address.Address;
import org.julclang.clientlib.eval.V1V2ScriptContextBuilder;
import org.julclang.core.PlutusData;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.core.types.JulcArrayList;
import org.julclang.core.types.JulcAssocMap;
import org.julclang.core.types.JulcList;
import org.julclang.core.types.JulcMap;
import org.julclang.ledger.Credential;
import org.julclang.ledger.DatumHash;
import org.julclang.ledger.Delegatee;
import org.julclang.ledger.GovernanceAction;
import org.julclang.ledger.GovernanceActionId;
import org.julclang.ledger.Interval;
import org.julclang.ledger.IntervalBound;
import org.julclang.ledger.IntervalBoundType;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.PolicyId;
import org.julclang.ledger.ProposalProcedure;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.ScriptContext;
import org.julclang.ledger.ScriptHash;
import org.julclang.ledger.ScriptInfo;
import org.julclang.ledger.ScriptPurpose;
import org.julclang.ledger.StakingCredential;
import org.julclang.ledger.TokenName;
import org.julclang.ledger.TxCert;
import org.julclang.ledger.TxId;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;
import org.julclang.ledger.Vote;
import org.julclang.ledger.Voter;
import org.julclang.playground.model.MockTransaction;
import org.julclang.vm.PlutusLanguage;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Builds the script arguments for a {@link MockTransaction}: the V3 {@code ScriptContext}, or for V1/V2 the
 * datum (spending only), redeemer and older script context.
 */
final class MockContextBuilder {

    static final String SELF = "$self";
    private static final byte[] ZERO_TX_ID = new byte[32];

    /**
     * @param args          script arguments in application order
     * @param scriptContext the script context argument
     */
    record Built(List<PlutusData> args, PlutusData scriptContext) {}

    private final byte[] selfHash;

    private MockContextBuilder(byte[] selfHash) {
        this.selfHash = selfHash;
    }

    static Built build(MockTransaction tx, byte[] selfHash, PlutusLanguage language) {
        return new MockContextBuilder(selfHash).build(tx == null ? empty() : tx, language);
    }

    private Built build(MockTransaction tx, PlutusLanguage language) {
        var inputs = txIns(tx.inputs(), "Input");
        var referenceInputs = txIns(tx.referenceInputs(), "Reference input");
        var outputs = new ArrayList<TxOut>();
        for (var out : list(tx.outputs())) {
            outputs.add(txOut(out.address(), out.value(), out.datum(), out.referenceScript()));
        }

        var datums = new LinkedHashMap<DatumHash, PlutusData>();
        for (var datum : list(tx.datums())) {
            PlutusData data = DataInputs.parse(datum);
            if (data != null) datums.put(datumHash(data), data);
        }
        for (var input : list(tx.inputs())) {
            addHashedDatumWitness(input.datum(), datums);
        }
        for (var output : list(tx.outputs())) {
            addHashedDatumWitness(output.datum(), datums);
        }

        var certificates = new ArrayList<TxCert>();
        for (var cert : list(tx.certificates())) certificates.add(certificate(cert));
        var withdrawals = new LinkedHashMap<Credential, BigInteger>();
        for (var w : list(tx.withdrawals())) withdrawals.put(credential(w.credential(), "Withdrawal"), integer(w.amount(), "withdrawal amount"));
        var proposals = new ArrayList<ProposalProcedure>();
        for (var p : list(tx.proposals())) proposals.add(proposal(p));
        var votes = new LinkedHashMap<Voter, Map<GovernanceActionId, Vote>>();
        for (var v : list(tx.votes())) {
            var actionId = new GovernanceActionId(TxId.of(bytes(v.actionTxId(), ZERO_TX_ID, "governance action id")),
                    BigInteger.valueOf(v.actionIndex() == null ? 0 : v.actionIndex()));
            votes.computeIfAbsent(voter(v.voterType(), v.voter()), k -> new LinkedHashMap<>()).put(actionId, vote(v.vote()));
        }

        PlutusData redeemer = DataInputs.parseOr(tx.redeemer(), PlutusData.constr(0));
        var purposeInfo = purpose(tx, inputs, certificates, proposals, datums);

        var redeemers = new LinkedHashMap<ScriptPurpose, PlutusData>();
        redeemers.put(purposeInfo.purpose(), redeemer);

        var mint = Value.zero();
        for (var asset : list(tx.mint())) {
            mint = mint.merge(Value.singleton(PolicyId.of(hash(asset.policyId(), "mint policy")),
                    TokenName.of(bytes(asset.tokenName(), new byte[0], "token name")),
                    integer(asset.quantity(), "mint quantity")));
        }
        var signatories = new ArrayList<PubKeyHash>();
        for (var signer : list(tx.signatories())) signatories.add(PubKeyHash.of(unhex(signer, "Signer")));

        var txInfo = new TxInfo(
                new JulcArrayList<>(inputs),
                new JulcArrayList<>(referenceInputs),
                new JulcArrayList<>(outputs),
                integer(tx.fee(), "fee"),
                mint,
                new JulcArrayList<>(certificates),
                julcMap(withdrawals),
                interval(tx.validRange()),
                new JulcArrayList<>(signatories),
                julcMap(redeemers),
                julcMap(datums),
                TxId.of(bytes(tx.txId(), ZERO_TX_ID, "transaction id")),
                votesMap(votes),
                new JulcArrayList<>(proposals),
                optionalInteger(tx.currentTreasuryAmount(), "treasury amount"),
                optionalInteger(tx.treasuryDonation(), "treasury donation"));

        if (language == PlutusLanguage.PLUTUS_V3) {
            PlutusData context = new ScriptContext(txInfo, redeemer, purposeInfo.info()).toPlutusData();
            return new Built(List.of(context), context);
        }
        if (purposeInfo.purpose() instanceof ScriptPurpose.Voting || purposeInfo.purpose() instanceof ScriptPurpose.Proposing) {
            throw new IllegalArgumentException("Voting and proposing scripts require Plutus V3");
        }
        PlutusData context = V1V2ScriptContextBuilder.build(language, txInfo, purposeInfo.purpose());
        if (purposeInfo.info() instanceof ScriptInfo.SpendingScript spending) {
            PlutusData datum = spending.datum().orElseThrow(() -> new IllegalArgumentException(
                    "Plutus " + language.name().substring(7) + " spending scripts need a datum on the spent input"));
            return new Built(List.of(datum, redeemer, context), context);
        }
        return new Built(List.of(redeemer, context), context);
    }

    private record PurposeInfo(ScriptInfo info, ScriptPurpose purpose) {}

    private PurposeInfo purpose(MockTransaction tx, List<TxInInfo> inputs, List<TxCert> certificates,
                                List<ProposalProcedure> proposals, Map<DatumHash, PlutusData> datums) {
        var purpose = tx.purpose();
        String type = purpose == null || purpose.type() == null ? "spend" : purpose.type().toLowerCase(Locale.ROOT);
        int index = purpose == null || purpose.index() == null ? 0 : purpose.index();
        String target = purpose == null || purpose.target() == null || purpose.target().isBlank() ? SELF : purpose.target();
        return switch (type) {
            case "spend" -> {
                if (index < 0 || index >= inputs.size()) {
                    throw new IllegalArgumentException("Spend purpose: the transaction has no input #" + index);
                }
                var spent = inputs.get(index);
                Optional<PlutusData> datum = switch (spent.resolved().datum()) {
                    case OutputDatum.OutputDatumInline inline -> Optional.of(inline.datum());
                    case OutputDatum.OutputDatumHash h -> Optional.ofNullable(datums.get(h.hash()));
                    case OutputDatum.NoOutputDatum ignored -> Optional.empty();
                };
                yield new PurposeInfo(new ScriptInfo.SpendingScript(spent.outRef(), datum),
                        new ScriptPurpose.Spending(spent.outRef()));
            }
            case "mint" -> {
                var policy = PolicyId.of(hash(target, "mint policy"));
                yield new PurposeInfo(new ScriptInfo.MintingScript(policy), new ScriptPurpose.Minting(policy));
            }
            case "reward" -> {
                var credential = credential(target, "Reward purpose");
                yield new PurposeInfo(new ScriptInfo.RewardingScript(credential), new ScriptPurpose.Rewarding(credential));
            }
            case "certify" -> {
                if (index < 0 || index >= certificates.size()) {
                    throw new IllegalArgumentException("Certify purpose: the transaction has no certificate #" + index);
                }
                var i = BigInteger.valueOf(index);
                yield new PurposeInfo(new ScriptInfo.CertifyingScript(i, certificates.get(index)),
                        new ScriptPurpose.Certifying(i, certificates.get(index)));
            }
            case "vote" -> {
                var voter = voter(purpose == null ? null : purpose.voterType(), target);
                yield new PurposeInfo(new ScriptInfo.VotingScript(voter), new ScriptPurpose.Voting(voter));
            }
            case "propose" -> {
                if (index < 0 || index >= proposals.size()) {
                    throw new IllegalArgumentException("Propose purpose: the transaction has no proposal #" + index);
                }
                var i = BigInteger.valueOf(index);
                yield new PurposeInfo(new ScriptInfo.ProposingScript(i, proposals.get(index)),
                        new ScriptPurpose.Proposing(i, proposals.get(index)));
            }
            default -> throw new IllegalArgumentException("Unknown purpose: " + purpose.type());
        };
    }

    // ---- ledger values ----

    private List<TxInInfo> txIns(List<MockTransaction.TxIn> ins, String what) {
        var result = new ArrayList<TxInInfo>();
        for (int i = 0; i < list(ins).size(); i++) {
            var in = ins.get(i);
            var ref = new TxOutRef(TxId.of(bytes(in.txId(), ZERO_TX_ID, what + " transaction id")),
                    BigInteger.valueOf(in.index() == null ? i : in.index()));
            result.add(new TxInInfo(ref, txOut(in.address(), in.value(), in.datum(), in.referenceScript())));
        }
        return result;
    }

    private TxOut txOut(MockTransaction.Address address, MockTransaction.Value value, MockTransaction.Datum datum,
                        String referenceScript) {
        Optional<ScriptHash> refScript = referenceScript == null || referenceScript.isBlank()
                ? Optional.empty() : Optional.of(ScriptHash.of(hash(referenceScript, "reference script")));
        return new TxOut(address(address), value(value), outputDatum(datum), refScript);
    }

    private org.julclang.ledger.Address address(MockTransaction.Address address) {
        if (address == null || address.payment() == null || address.payment().isBlank()) {
            throw new IllegalArgumentException("Every input and output needs an address");
        }
        String payment = address.payment().strip();
        if (payment.startsWith("addr")) {
            var parsed = bech32(payment);
            byte[] paymentHash = parsed.getPaymentCredentialHash()
                    .orElseThrow(() -> new IllegalArgumentException("Address has no payment credential: " + payment));
            Credential paymentCredential = parsed.isScriptHashInPaymentPart()
                    ? new Credential.ScriptCredential(ScriptHash.of(paymentHash))
                    : new Credential.PubKeyCredential(PubKeyHash.of(paymentHash));
            Optional<StakingCredential> stake = parsed.getDelegationCredentialHash().map(h ->
                    new StakingCredential.StakingHash(parsed.isScriptHashInDelegationPart()
                            ? new Credential.ScriptCredential(ScriptHash.of(h))
                            : new Credential.PubKeyCredential(PubKeyHash.of(h))));
            return new org.julclang.ledger.Address(paymentCredential, stake);
        }
        Optional<StakingCredential> stake = address.stake() == null || address.stake().isBlank()
                ? Optional.empty()
                : Optional.of(new StakingCredential.StakingHash(credential(address.stake(), "Stake credential")));
        return new org.julclang.ledger.Address(credential(payment, "Address"), stake);
    }

    private Credential credential(String text, String what) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException(what + " is missing a credential");
        String value = text.strip();
        if (value.equals(SELF)) return new Credential.ScriptCredential(ScriptHash.of(selfHash));
        if (value.startsWith("key:")) return new Credential.PubKeyCredential(PubKeyHash.of(unhex(value.substring(4), what)));
        if (value.startsWith("script:")) return new Credential.ScriptCredential(ScriptHash.of(hash(value.substring(7), what)));
        if (value.startsWith("stake")) {
            var parsed = bech32(value);
            byte[] h = parsed.getDelegationCredentialHash()
                    .orElseThrow(() -> new IllegalArgumentException("Not a stake address: " + value));
            return parsed.isScriptHashInDelegationPart()
                    ? new Credential.ScriptCredential(ScriptHash.of(h))
                    : new Credential.PubKeyCredential(PubKeyHash.of(h));
        }
        return new Credential.PubKeyCredential(PubKeyHash.of(unhex(value, what)));
    }

    private Value value(MockTransaction.Value value) {
        var result = Value.lovelace(value == null ? BigInteger.ZERO : integer(value.lovelace(), "lovelace"));
        if (value != null) {
            for (var asset : list(value.assets())) {
                result = result.merge(Value.singleton(PolicyId.of(hash(asset.policyId(), "asset policy")),
                        TokenName.of(bytes(asset.tokenName(), new byte[0], "token name")),
                        integer(asset.quantity(), "asset quantity")));
            }
        }
        return result;
    }

    private OutputDatum outputDatum(MockTransaction.Datum datum) {
        String kind = datum == null || datum.kind() == null ? "none" : datum.kind().toLowerCase(Locale.ROOT);
        return switch (kind) {
            case "none" -> new OutputDatum.NoOutputDatum();
            case "inline" -> {
                PlutusData data = DataInputs.parse(datum.data());
                if (data == null) throw new IllegalArgumentException("An inline datum needs data");
                yield new OutputDatum.OutputDatumInline(data);
            }
            case "hash" -> {
                if (datum.hash() != null && !datum.hash().isBlank()) {
                    yield new OutputDatum.OutputDatumHash(DatumHash.of(unhex(datum.hash(), "Datum hash")));
                }
                PlutusData data = DataInputs.parse(datum.data());
                if (data == null) throw new IllegalArgumentException("A datum hash needs a hash or the datum");
                yield new OutputDatum.OutputDatumHash(datumHash(data));
            }
            default -> throw new IllegalArgumentException("Unknown datum kind: " + datum.kind());
        };
    }

    private static void addHashedDatumWitness(MockTransaction.Datum datum, Map<DatumHash, PlutusData> datums) {
        if (datum != null && "hash".equalsIgnoreCase(datum.kind()) && (datum.hash() == null || datum.hash().isBlank())) {
            PlutusData data = DataInputs.parse(datum.data());
            if (data != null) datums.putIfAbsent(datumHash(data), data);
        }
    }

    private static DatumHash datumHash(PlutusData data) {
        return DatumHash.of(ScriptDecoder.blake2b256(PlutusDataCborEncoder.encode(data)));
    }

    private TxCert certificate(MockTransaction.Certificate cert) {
        String type = cert.type() == null ? "" : cert.type();
        Credential credential = credential(cert.credential(), "Certificate");
        return switch (type) {
            case "regStaking" -> new TxCert.RegStaking(credential, optionalInteger(cert.deposit(), "deposit"));
            case "unregStaking" -> new TxCert.UnRegStaking(credential, optionalInteger(cert.deposit(), "refund"));
            case "delegStaking" -> new TxCert.DelegStaking(credential,
                    new Delegatee.Stake(PubKeyHash.of(unhex(cert.poolId(), "Pool id"))));
            case "regDRep" -> new TxCert.RegDRep(credential, integer(cert.deposit(), "deposit"));
            case "unregDRep" -> new TxCert.UnRegDRep(credential, integer(cert.deposit(), "refund"));
            case "updateDRep" -> new TxCert.UpdateDRep(credential);
            default -> throw new IllegalArgumentException("Unsupported certificate type: " + type);
        };
    }

    private Voter voter(String voterType, String voter) {
        String type = voterType == null ? "drep" : voterType.toLowerCase(Locale.ROOT);
        return switch (type) {
            case "drep" -> new Voter.DRepVoter(credential(voter, "Voter"));
            case "committee" -> new Voter.CommitteeVoter(credential(voter, "Voter"));
            case "pool" -> new Voter.StakePoolVoter(PubKeyHash.of(unhex(voter, "Pool voter")));
            default -> throw new IllegalArgumentException("Unknown voter type: " + voterType);
        };
    }

    private static Vote vote(String vote) {
        return switch (vote == null ? "yes" : vote.toLowerCase(Locale.ROOT)) {
            case "yes" -> new Vote.VoteYes();
            case "no" -> new Vote.VoteNo();
            case "abstain" -> new Vote.Abstain();
            default -> throw new IllegalArgumentException("Unknown vote: " + vote);
        };
    }

    private ProposalProcedure proposal(MockTransaction.Proposal proposal) {
        Optional<ScriptHash> guardrail = proposal.guardrail() == null || proposal.guardrail().isBlank()
                ? Optional.empty() : Optional.of(ScriptHash.of(hash(proposal.guardrail(), "guardrail")));
        String type = proposal.actionType() == null ? "info" : proposal.actionType();
        GovernanceAction action = switch (type) {
            case "info" -> new GovernanceAction.InfoAction();
            case "noConfidence" -> new GovernanceAction.NoConfidence(Optional.empty());
            case "parameterChange" -> new GovernanceAction.ParameterChange(Optional.empty(),
                    new PlutusData.MapData(List.of()), guardrail);
            case "treasuryWithdrawals" -> {
                var withdrawals = new LinkedHashMap<Credential, BigInteger>();
                for (var w : list(proposal.withdrawals())) {
                    withdrawals.put(credential(w.credential(), "Treasury withdrawal"), integer(w.amount(), "amount"));
                }
                yield new GovernanceAction.TreasuryWithdrawals(julcMap(withdrawals), guardrail);
            }
            default -> throw new IllegalArgumentException("Unsupported governance action: " + type);
        };
        return new ProposalProcedure(integer(proposal.deposit(), "proposal deposit"),
                credential(proposal.returnCredential(), "Proposal return"), action);
    }

    private static Interval interval(MockTransaction.Interval range) {
        if (range == null) return Interval.always();
        var from = new IntervalBound(range.from() == null ? new IntervalBoundType.NegInf()
                : new IntervalBoundType.Finite(BigInteger.valueOf(range.from())), range.fromInclusive() == null || range.fromInclusive());
        var to = new IntervalBound(range.to() == null ? new IntervalBoundType.PosInf()
                : new IntervalBoundType.Finite(BigInteger.valueOf(range.to())), range.toInclusive() == null || range.toInclusive());
        return new Interval(from, to);
    }

    // ---- primitives ----

    private byte[] hash(String text, String what) {
        if (text == null || text.isBlank() || text.strip().equals(SELF)) return selfHash;
        String value = text.strip();
        if (value.startsWith("script:")) value = value.substring(7);
        return value.equals(SELF) ? selfHash : unhex(value, what);
    }

    private static byte[] bytes(String text, byte[] fallback, String what) {
        return text == null || text.isBlank() ? fallback : unhex(text.strip(), what);
    }

    private static byte[] unhex(String text, String what) {
        return ScriptDecoder.unhex(text == null ? null : text.strip(), what);
    }

    private static BigInteger integer(String text, String what) {
        if (text == null || text.isBlank()) return BigInteger.ZERO;
        try {
            return new BigInteger(text.strip().replace("_", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + what + ": " + text);
        }
    }

    private static Optional<BigInteger> optionalInteger(String text, String what) {
        return text == null || text.isBlank() ? Optional.empty() : Optional.of(integer(text, what));
    }

    private static Address bech32(String text) {
        try {
            return new Address(text);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid bech32 address: " + text);
        }
    }

    private static <K, V> JulcMap<K, V> julcMap(Map<K, V> source) {
        JulcMap<K, V> result = JulcAssocMap.empty();
        var entries = new ArrayList<>(source.entrySet());
        for (int i = entries.size() - 1; i >= 0; i--) {
            result = result.insert(entries.get(i).getKey(), entries.get(i).getValue());
        }
        return result;
    }

    private static JulcMap<Voter, JulcMap<GovernanceActionId, Vote>> votesMap(Map<Voter, Map<GovernanceActionId, Vote>> votes) {
        var converted = new LinkedHashMap<Voter, JulcMap<GovernanceActionId, Vote>>();
        votes.forEach((voter, actions) -> converted.put(voter, julcMap(actions)));
        return julcMap(converted);
    }

    private static <T> List<T> list(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static MockTransaction empty() {
        return new MockTransaction(null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }
}
