package com.bloxbean.cardano.julc.clientlib.eval;

import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.exception.ApiException;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.util.CostModelUtil;
import com.bloxbean.cardano.client.plutus.spec.*;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.ExBudget;
import com.bloxbean.cardano.julc.vm.JulcVm;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;

import java.math.BigInteger;
import java.util.*;

/**
 * Local Plutus script cost evaluator using the JuLC CEK machine.
 * <p>
 * Implements CCL's {@link TransactionEvaluator} interface for seamless integration
 * with {@code QuickTxBuilder}:
 * <pre>{@code
 * var evaluator = new JulcTransactionEvaluator(
 *     utxoSupplier, protocolParamsSupplier, scriptSupplier);
 *
 * quickTxBuilder.compose(scriptTx)
 *     .withTxEvaluator(evaluator)
 *     .withSigner(signer)
 *     .complete();
 * }</pre>
 */
public class JulcTransactionEvaluator implements TransactionEvaluator {

    private final UtxoSupplier utxoSupplier;
    private final ProtocolParamsSupplier protocolParamsSupplier;
    private final ScriptSupplier scriptSupplier;
    private final SlotConfig slotConfig;

    private volatile JulcVm vm;
    private volatile com.bloxbean.cardano.julc.vm.JulcVmProvider provider;

    /**
     * Create an evaluator without slot-to-POSIX conversion.
     * Slot numbers will be passed directly in the validity range.
     */
    public JulcTransactionEvaluator(UtxoSupplier utxoSupplier,
                                    ProtocolParamsSupplier protocolParamsSupplier,
                                    ScriptSupplier scriptSupplier) {
        this(utxoSupplier, protocolParamsSupplier, scriptSupplier, null);
    }

    /**
     * Create an evaluator with slot-to-POSIX conversion for time-sensitive scripts.
     */
    public JulcTransactionEvaluator(UtxoSupplier utxoSupplier,
                                    ProtocolParamsSupplier protocolParamsSupplier,
                                    ScriptSupplier scriptSupplier,
                                    SlotConfig slotConfig) {
        this.utxoSupplier = Objects.requireNonNull(utxoSupplier, "utxoSupplier");
        this.protocolParamsSupplier = Objects.requireNonNull(protocolParamsSupplier, "protocolParamsSupplier");
        this.scriptSupplier = scriptSupplier;
        this.slotConfig = slotConfig;
    }

    @Override
    public Result<List<EvaluationResult>> evaluateTx(byte[] cbor, Set<Utxo> inputUtxos) throws ApiException {
        try {
            // 1. Deserialize
            Transaction tx = Transaction.deserialize(cbor);

            // 2. Extract redeemers
            var witnessSet = tx.getWitnessSet();
            List<Redeemer> redeemers = witnessSet != null ? witnessSet.getRedeemers() : null;
            if (redeemers == null || redeemers.isEmpty()) {
                return Result.<List<EvaluationResult>>success("OK")
                        .withValue(List.of());
            }

            // 3. Build TxInfo
            var converter = new CclTxConverter(tx, inputUtxos, utxoSupplier, slotConfig);
            TxInfo txInfo = converter.buildTxInfo();

            // 4. Get max script budget
            var params = protocolParamsSupplier.getProtocolParams();
            ExBudget maxBudget = new ExBudget(
                    Long.parseLong(params.getMaxTxExSteps()),
                    Long.parseLong(params.getMaxTxExMem()));

            // 5. Create VM (lazy, cached) and configure cost models for all language versions
            JulcVm julcVm = getOrCreateVm();
            int pvMajor = params.getProtocolMajorVer() != null ? params.getProtocolMajorVer() : 10;
            int pvMinor = params.getProtocolMinorVer() != null ? params.getProtocolMinorVer() : 0;
            for (var lang : List.of(Language.PLUTUS_V1, Language.PLUTUS_V2, Language.PLUTUS_V3)) {
                CostModelUtil.getCostModelFromProtocolParams(params, lang)
                        .ifPresent(cm -> julcVm.setCostModelParams(
                                cm.getCosts(), toPlutusLanguage(lang), pvMajor, pvMinor));
            }

            // 6. Evaluate each redeemer
            List<EvaluationResult> results = new ArrayList<>();

            for (Redeemer redeemer : redeemers) {
                try {
                    // a. Resolve script (with language version detection)
                    ScriptPurpose purpose = converter.redeemerToScriptPurpose(redeemer);
                    String scriptHash = resolveScriptHash(purpose, inputUtxos, converter);
                    ResolvedScript resolved = resolveScript(tx, scriptHash, inputUtxos);

                    // b. Build arguments based on script version
                    com.bloxbean.cardano.julc.core.PlutusData redeemerData =
                            PlutusDataAdapter.fromClientLib(redeemer.getData());

                    List<com.bloxbean.cardano.julc.core.PlutusData> args;
                    if (resolved.language() == PlutusLanguage.PLUTUS_V3) {
                        // V3: single ScriptContext argument
                        ScriptInfo scriptInfo = buildScriptInfo(purpose, redeemer, txInfo);
                        ScriptContext scriptContext = new ScriptContext(txInfo, redeemerData, scriptInfo);
                        args = List.of(scriptContext.toPlutusData());
                    } else {
                        // V1/V2: [datum, redeemer, scriptContext] or [redeemer, scriptContext]
                        com.bloxbean.cardano.julc.core.PlutusData scriptContextData =
                                V1V2ScriptContextBuilder.build(resolved.language(), txInfo,
                                        purpose, converter);
                        if (purpose instanceof ScriptPurpose.Spending(var txOutRef)) {
                            // Spending: datum is the first argument
                            com.bloxbean.cardano.julc.core.PlutusData datumData =
                                    resolveDatumForSpending(txOutRef, txInfo);
                            args = List.of(datumData, redeemerData, scriptContextData);
                        } else {
                            args = List.of(redeemerData, scriptContextData);
                        }
                    }

                    // c. Evaluate with the resolved language version
                    var langVm = JulcVm.withProvider(getProvider(), resolved.language());
                    // Apply cost model for this language if not already set
                    EvalResult evalResult = langVm.evaluateWithArgs(
                            resolved.program(), args, maxBudget);

                    // e. Handle result
                    switch (evalResult) {
                        case EvalResult.Success success -> {
                            ExBudget consumed = success.consumed();
                            results.add(EvaluationResult.builder()
                                    .redeemerTag(redeemer.getTag())
                                    .index(redeemer.getIndex().intValue())
                                    .exUnits(ExUnits.builder()
                                            .mem(BigInteger.valueOf(consumed.memoryUnits()))
                                            .steps(BigInteger.valueOf(consumed.cpuSteps()))
                                            .build())
                                    .build());
                        }
                        case EvalResult.Failure failure -> {
                            String traces = String.join("\n", failure.traces());
                            return Result.error("Script evaluation failed for "
                                    + redeemer.getTag() + "[" + redeemer.getIndex() + "]: "
                                    + failure.error()
                                    + (traces.isEmpty() ? "" : "\nTraces:\n" + traces));
                        }
                        case EvalResult.BudgetExhausted exhausted -> {
                            return Result.error("Budget exhausted for "
                                    + redeemer.getTag() + "[" + redeemer.getIndex() + "]: "
                                    + exhausted.consumed());
                        }
                    }
                } catch (Exception e) {
                    return Result.error("Error evaluating " + redeemer.getTag()
                            + "[" + redeemer.getIndex() + "]: " + e.getMessage());
                }
            }

            return Result.<List<EvaluationResult>>success("OK").withValue(results);

        } catch (Exception e) {
            return Result.error("Transaction evaluation failed: " + e.getMessage());
        }
    }

    private JulcVm getOrCreateVm() {
        if (vm == null) {
            synchronized (this) {
                if (vm == null) {
                    vm = JulcVm.create();
                }
            }
        }
        return vm;
    }

    private com.bloxbean.cardano.julc.vm.JulcVmProvider getProvider() {
        if (provider == null) {
            synchronized (this) {
                if (provider == null) {
                    provider = java.util.ServiceLoader.load(com.bloxbean.cardano.julc.vm.JulcVmProvider.class)
                            .stream()
                            .map(java.util.ServiceLoader.Provider::get)
                            .max(java.util.Comparator.comparingInt(com.bloxbean.cardano.julc.vm.JulcVmProvider::priority))
                            .orElseThrow(() -> new IllegalStateException("No JulcVmProvider found."));
                }
            }
        }
        return provider;
    }

    private com.bloxbean.cardano.julc.core.PlutusData resolveDatumForSpending(
            TxOutRef txOutRef, TxInfo txInfo) {
        for (int i = 0; i < txInfo.inputs().size(); i++) {
            TxInInfo input = txInfo.inputs().get(i);
            if (input.outRef().equals(txOutRef)) {
                OutputDatum od = input.resolved().datum();
                if (od instanceof OutputDatum.OutputDatumInline inlineDatum) {
                    return inlineDatum.datum();
                } else if (od instanceof OutputDatum.OutputDatumHash datumHashOd) {
                    var d = txInfo.datums().get(datumHashOd.hash());
                    if (d != null) {
                        return d;
                    }
                }
                break;
            }
        }
        // V1/V2 spending scripts require a datum; if not found, use unit
        return new com.bloxbean.cardano.julc.core.PlutusData.ConstrData(0, List.of());
    }

    /** Resolved script: program + language version. */
    private record ResolvedScript(Program program, PlutusLanguage language) {}

    private String resolveScriptHash(ScriptPurpose purpose, Set<Utxo> inputUtxos,
                                     CclTxConverter converter) {
        return switch (purpose) {
            case ScriptPurpose.Spending(var txOutRef) -> {
                // Find the UTxO being spent, get its address script hash
                String txHash = HexFormat.of().formatHex(txOutRef.txId().hash());
                int idx = txOutRef.index().intValue();
                Utxo resolved = null;
                for (Utxo utxo : inputUtxos) {
                    if (txHash.equals(utxo.getTxHash()) && idx == utxo.getOutputIndex()) {
                        resolved = utxo;
                        break;
                    }
                }
                if (resolved == null && utxoSupplier != null) {
                    resolved = utxoSupplier.getTxOutput(txHash, idx).orElse(null);
                }
                if (resolved == null) {
                    throw new IllegalStateException("UTxO not found for spending: " + txHash + "#" + idx);
                }
                final Utxo spentUtxo = resolved;
                var addr = new com.bloxbean.cardano.client.address.Address(spentUtxo.getAddress());
                yield addr.getPaymentCredentialHash()
                        .map(h -> HexFormat.of().formatHex(h))
                        .orElseThrow(() -> new IllegalStateException(
                                "Cannot extract script hash from address: " + spentUtxo.getAddress()));
            }
            case ScriptPurpose.Minting(var policyId) ->
                    HexFormat.of().formatHex(policyId.hash());
            case ScriptPurpose.Rewarding(var credential) ->
                    extractScriptHashFromCredential(credential);
            case ScriptPurpose.Certifying(var index, var cert) ->
                    extractScriptHashFromCert(cert);
            case ScriptPurpose.Voting(var voter) ->
                    extractScriptHashFromVoter(voter);
            case ScriptPurpose.Proposing(var index, var procedure) ->
                    extractScriptHashFromCredential(procedure.returnAddress());
        };
    }

    private String extractScriptHashFromCredential(Credential credential) {
        if (credential instanceof Credential.ScriptCredential sc) {
            return HexFormat.of().formatHex(sc.hash().hash());
        }
        throw new IllegalStateException("Credential is not a script credential: " + credential);
    }

    private String extractScriptHashFromCert(TxCert cert) {
        return switch (cert) {
            case TxCert.RegStaking(var cred, var _) -> extractScriptHashFromCredential(cred);
            case TxCert.UnRegStaking(var cred, var _) -> extractScriptHashFromCredential(cred);
            case TxCert.DelegStaking(var cred, var _) -> extractScriptHashFromCredential(cred);
            case TxCert.RegDeleg(var cred, var _, var _) -> extractScriptHashFromCredential(cred);
            case TxCert.RegDRep(var cred, var _) -> extractScriptHashFromCredential(cred);
            case TxCert.UpdateDRep(var cred) -> extractScriptHashFromCredential(cred);
            case TxCert.UnRegDRep(var cred, var _) -> extractScriptHashFromCredential(cred);
            case TxCert.AuthHotCommittee(var cold, var _) -> extractScriptHashFromCredential(cold);
            case TxCert.ResignColdCommittee(var cold) -> extractScriptHashFromCredential(cold);
            case TxCert.PoolRegister _, TxCert.PoolRetire _ ->
                    throw new IllegalStateException("Pool certificates don't have script credentials");
        };
    }

    private String extractScriptHashFromVoter(Voter voter) {
        return switch (voter) {
            case Voter.CommitteeVoter(var cred) -> extractScriptHashFromCredential(cred);
            case Voter.DRepVoter(var cred) -> extractScriptHashFromCredential(cred);
            case Voter.StakePoolVoter _ ->
                    throw new IllegalStateException("StakePoolVoter does not have a script credential");
        };
    }

    private ResolvedScript resolveScript(Transaction tx, String scriptHash,
                                         Set<Utxo> inputUtxos) {
        var ws = tx.getWitnessSet();

        // 1. Check witness set PlutusV3Scripts
        List<PlutusV3Script> v3Scripts = ws.getPlutusV3Scripts();
        if (v3Scripts != null) {
            for (PlutusV3Script script : v3Scripts) {
                try {
                    String hash = HexFormat.of().formatHex(script.getScriptHash());
                    if (scriptHash.equals(hash)) {
                        return new ResolvedScript(
                                JulcScriptAdapter.toProgram(script.getCborHex()),
                                PlutusLanguage.PLUTUS_V3);
                    }
                } catch (Exception e) {
                    // Continue searching
                }
            }
        }

        // 2. Check witness set PlutusV2Scripts
        List<PlutusV2Script> v2Scripts = ws.getPlutusV2Scripts();
        if (v2Scripts != null) {
            for (PlutusV2Script script : v2Scripts) {
                try {
                    String hash = HexFormat.of().formatHex(script.getScriptHash());
                    if (scriptHash.equals(hash)) {
                        return new ResolvedScript(
                                JulcScriptAdapter.toProgram(script.getCborHex()),
                                PlutusLanguage.PLUTUS_V2);
                    }
                } catch (Exception e) {
                    // Continue searching
                }
            }
        }

        // 3. Check witness set PlutusV1Scripts
        List<PlutusV1Script> v1Scripts = ws.getPlutusV1Scripts();
        if (v1Scripts != null) {
            for (PlutusV1Script script : v1Scripts) {
                try {
                    String hash = HexFormat.of().formatHex(script.getScriptHash());
                    if (scriptHash.equals(hash)) {
                        return new ResolvedScript(
                                JulcScriptAdapter.toProgram(script.getCborHex()),
                                PlutusLanguage.PLUTUS_V1);
                    }
                } catch (Exception e) {
                    // Continue searching
                }
            }
        }

        // 4. Check reference input UTxOs with scriptRef
        if (inputUtxos != null) {
            for (Utxo utxo : inputUtxos) {
                if (utxo.getReferenceScriptHash() != null
                        && scriptHash.equals(utxo.getReferenceScriptHash())) {
                    // The UTxO has the script — but we need the actual script bytes.
                    // Reference scripts are resolved from the UTxO's scriptRef field.
                    // For now, fall through to ScriptSupplier.
                    break;
                }
            }
        }

        // 5. ScriptSupplier fallback
        if (scriptSupplier != null) {
            Optional<PlutusScript> script = scriptSupplier.getScript(scriptHash);
            if (script.isPresent()) {
                PlutusScript ps = script.get();
                if (ps instanceof PlutusV3Script v3) {
                    return new ResolvedScript(
                            JulcScriptAdapter.toProgram(v3.getCborHex()),
                            PlutusLanguage.PLUTUS_V3);
                } else if (ps instanceof PlutusV2Script v2) {
                    return new ResolvedScript(
                            JulcScriptAdapter.toProgram(v2.getCborHex()),
                            PlutusLanguage.PLUTUS_V2);
                } else if (ps instanceof PlutusV1Script v1) {
                    return new ResolvedScript(
                            JulcScriptAdapter.toProgram(v1.getCborHex()),
                            PlutusLanguage.PLUTUS_V1);
                }
            }
        }

        throw new IllegalStateException("Script not found: " + scriptHash
                + ". Provide it in the witness set, reference inputs, or via ScriptSupplier.");
    }

    private ScriptInfo buildScriptInfo(ScriptPurpose purpose, Redeemer redeemer,
                                       TxInfo txInfo) {
        return switch (purpose) {
            case ScriptPurpose.Spending(var txOutRef) -> {
                // Find the datum from the spent UTxO
                Optional<com.bloxbean.cardano.julc.core.PlutusData> datum = Optional.empty();

                // Look for inline datum in the resolved input
                for (int i = 0; i < txInfo.inputs().size(); i++) {
                    TxInInfo input = txInfo.inputs().get(i);
                    if (input.outRef().equals(txOutRef)) {
                        OutputDatum od = input.resolved().datum();
                        if (od instanceof OutputDatum.OutputDatumInline inlineDatum) {
                            datum = Optional.of(inlineDatum.datum());
                        } else if (od instanceof OutputDatum.OutputDatumHash datumHashOd) {
                            // Look up in witness datums
                            var d = txInfo.datums().get(datumHashOd.hash());
                            if (d != null) {
                                datum = Optional.of(d);
                            }
                        }
                        break;
                    }
                }

                yield new ScriptInfo.SpendingScript(txOutRef, datum);
            }
            case ScriptPurpose.Minting(var policyId) ->
                    new ScriptInfo.MintingScript(policyId);
            case ScriptPurpose.Rewarding(var credential) ->
                    new ScriptInfo.RewardingScript(credential);
            case ScriptPurpose.Certifying(var index, var cert) ->
                    new ScriptInfo.CertifyingScript(index, cert);
            case ScriptPurpose.Voting(var voter) ->
                    new ScriptInfo.VotingScript(voter);
            case ScriptPurpose.Proposing(var index, var procedure) ->
                    new ScriptInfo.ProposingScript(index, procedure);
        };
    }

    private static PlutusLanguage toPlutusLanguage(Language cclLanguage) {
        return switch (cclLanguage) {
            case PLUTUS_V1 -> PlutusLanguage.PLUTUS_V1;
            case PLUTUS_V2 -> PlutusLanguage.PLUTUS_V2;
            case PLUTUS_V3 -> PlutusLanguage.PLUTUS_V3;
        };
    }
}
