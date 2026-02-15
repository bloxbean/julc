package com.bloxbean.cardano.julc.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcArrayList;
import com.bloxbean.cardano.julc.core.types.JulcAssocMap;
import com.bloxbean.cardano.julc.core.types.JulcMap;

import java.math.BigInteger;
import java.util.*;

/**
 * Fluent builder for constructing test ScriptContext instances.
 */
public class ScriptContextBuilder {

    private final List<TxInInfo> inputs = new ArrayList<>();
    private final List<TxInInfo> referenceInputs = new ArrayList<>();
    private final List<TxOut> outputs = new ArrayList<>();
    private BigInteger fee = BigInteger.ZERO;
    private Value mint = Value.zero();
    private final List<TxCert> certificates = new ArrayList<>();
    private final Map<Credential, BigInteger> withdrawals = new LinkedHashMap<>();
    private Interval validRange = Interval.always();
    private final List<PubKeyHash> signatories = new ArrayList<>();
    private final Map<ScriptPurpose, PlutusData> redeemers = new LinkedHashMap<>();
    private final Map<DatumHash, PlutusData> datums = new LinkedHashMap<>();
    private TxId txId = new TxId(new byte[32]);
    private final Map<Voter, Map<GovernanceActionId, Vote>> votes = new LinkedHashMap<>();
    private final List<ProposalProcedure> proposalProcedures = new ArrayList<>();
    private Optional<BigInteger> currentTreasuryAmount = Optional.empty();
    private Optional<BigInteger> treasuryDonation = Optional.empty();
    private PlutusData redeemer = PlutusData.UNIT;
    private ScriptInfo scriptInfo;

    private ScriptContextBuilder(ScriptInfo scriptInfo) {
        this.scriptInfo = scriptInfo;
    }

    public static ScriptContextBuilder spending(TxOutRef ref) {
        return new ScriptContextBuilder(new ScriptInfo.SpendingScript(ref, Optional.empty()));
    }

    public static ScriptContextBuilder spending(TxOutRef ref, PlutusData datum) {
        return new ScriptContextBuilder(new ScriptInfo.SpendingScript(ref, Optional.of(datum)));
    }

    public static ScriptContextBuilder minting(PolicyId policyId) {
        return new ScriptContextBuilder(new ScriptInfo.MintingScript(policyId));
    }

    public ScriptContextBuilder input(TxInInfo input) {
        this.inputs.add(input);
        return this;
    }

    public ScriptContextBuilder referenceInput(TxInInfo input) {
        this.referenceInputs.add(input);
        return this;
    }

    public ScriptContextBuilder output(TxOut output) {
        this.outputs.add(output);
        return this;
    }

    public ScriptContextBuilder fee(BigInteger fee) {
        this.fee = fee;
        return this;
    }

    public ScriptContextBuilder mint(Value mint) {
        this.mint = mint;
        return this;
    }

    public ScriptContextBuilder certificate(TxCert cert) {
        this.certificates.add(cert);
        return this;
    }

    public ScriptContextBuilder withdrawal(Credential cred, BigInteger amount) {
        this.withdrawals.put(cred, amount);
        return this;
    }

    public ScriptContextBuilder validRange(Interval range) {
        this.validRange = range;
        return this;
    }

    public ScriptContextBuilder signer(PubKeyHash pkh) {
        this.signatories.add(pkh);
        return this;
    }

    public ScriptContextBuilder redeemerEntry(ScriptPurpose purpose, PlutusData redeemer) {
        this.redeemers.put(purpose, redeemer);
        return this;
    }

    public ScriptContextBuilder datum(DatumHash hash, PlutusData datum) {
        this.datums.put(hash, datum);
        return this;
    }

    public ScriptContextBuilder txId(TxId txId) {
        this.txId = txId;
        return this;
    }

    public ScriptContextBuilder redeemer(PlutusData redeemer) {
        this.redeemer = redeemer;
        return this;
    }

    public ScriptContextBuilder currentTreasuryAmount(BigInteger amount) {
        this.currentTreasuryAmount = Optional.of(amount);
        return this;
    }

    public ScriptContextBuilder treasuryDonation(BigInteger amount) {
        this.treasuryDonation = Optional.of(amount);
        return this;
    }

    public ScriptContext build() {
        var txInfo = new TxInfo(
                new JulcArrayList<>(List.copyOf(inputs)),
                new JulcArrayList<>(List.copyOf(referenceInputs)),
                new JulcArrayList<>(List.copyOf(outputs)),
                fee,
                mint,
                new JulcArrayList<>(List.copyOf(certificates)),
                buildJulcMap(withdrawals),
                validRange,
                new JulcArrayList<>(List.copyOf(signatories)),
                buildJulcMap(redeemers),
                buildJulcMap(datums),
                txId,
                buildVotesJulcMap(votes),
                new JulcArrayList<>(List.copyOf(proposalProcedures)),
                currentTreasuryAmount,
                treasuryDonation);
        return new ScriptContext(txInfo, redeemer, scriptInfo);
    }

    private static <K, V> JulcMap<K, V> buildJulcMap(Map<K, V> source) {
        JulcMap<K, V> result = JulcAssocMap.empty();
        // Insert in reverse order to preserve iteration order
        var entries = new ArrayList<>(source.entrySet());
        for (int i = entries.size() - 1; i >= 0; i--) {
            var e = entries.get(i);
            result = result.insert(e.getKey(), e.getValue());
        }
        return result;
    }

    private static JulcMap<Voter, JulcMap<GovernanceActionId, Vote>> buildVotesJulcMap(
            Map<Voter, Map<GovernanceActionId, Vote>> source) {
        JulcMap<Voter, JulcMap<GovernanceActionId, Vote>> result = JulcAssocMap.empty();
        var entries = new ArrayList<>(source.entrySet());
        for (int i = entries.size() - 1; i >= 0; i--) {
            var e = entries.get(i);
            result = result.insert(e.getKey(), buildJulcMap(e.getValue()));
        }
        return result;
    }
}
