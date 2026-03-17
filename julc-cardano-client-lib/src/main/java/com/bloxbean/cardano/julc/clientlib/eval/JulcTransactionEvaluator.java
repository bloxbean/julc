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

            // 5. Create VM (lazy, cached) and configure cost model
            JulcVm julcVm = getOrCreateVm();
            CostModelUtil.getCostModelFromProtocolParams(params, Language.PLUTUS_V3)
                    .ifPresent(cm -> julcVm.setCostModelParams(
                            cm.getCosts(), params.getProtocolMajorVer()));

            // 6. Evaluate each redeemer
            List<EvaluationResult> results = new ArrayList<>();

            for (Redeemer redeemer : redeemers) {
                try {
                    // a. Resolve script
                    ScriptPurpose purpose = converter.redeemerToScriptPurpose(redeemer);
                    String scriptHash = resolveScriptHash(purpose, inputUtxos, converter);
                    Program program = resolveScript(tx, scriptHash, inputUtxos);

                    // b. Build ScriptInfo
                    ScriptInfo scriptInfo = buildScriptInfo(purpose, redeemer, txInfo);

                    // c. Build ScriptContext
                    com.bloxbean.cardano.julc.core.PlutusData redeemerData =
                            PlutusDataAdapter.fromClientLib(redeemer.getData());
                    ScriptContext scriptContext = new ScriptContext(txInfo, redeemerData, scriptInfo);
                    com.bloxbean.cardano.julc.core.PlutusData scriptContextData =
                            scriptContext.toPlutusData();

                    // d. Evaluate
                    EvalResult evalResult = julcVm.evaluateWithArgs(
                            program, List.of(scriptContextData), maxBudget);

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
            case ScriptPurpose.Rewarding(var credential) -> {
                if (credential instanceof Credential.ScriptCredential sc) {
                    yield HexFormat.of().formatHex(sc.hash().hash());
                }
                throw new IllegalStateException("Reward credential is not a script credential");
            }
            default ->
                    throw new UnsupportedOperationException("Script hash resolution not supported for: " + purpose);
        };
    }

    private Program resolveScript(Transaction tx, String scriptHash,
                                  Set<Utxo> inputUtxos) {
        // 1. Check witness set PlutusV3Scripts
        List<PlutusV3Script> v3Scripts = tx.getWitnessSet().getPlutusV3Scripts();
        if (v3Scripts != null) {
            for (PlutusV3Script script : v3Scripts) {
                try {
                    String hash = HexFormat.of().formatHex(script.getScriptHash());
                    if (scriptHash.equals(hash)) {
                        return JulcScriptAdapter.toProgram(script.getCborHex());
                    }
                } catch (Exception e) {
                    // Continue searching
                }
            }
        }

        // 2. Check V1/V2 scripts — not supported
        if (hasV1V2Script(tx, scriptHash)) {
            throw new UnsupportedOperationException(
                    "PlutusV1/V2 scripts not supported by JuLC evaluator. Script hash: " + scriptHash);
        }

        // 3. Check reference input UTxOs with scriptRef
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

        // 4. ScriptSupplier fallback
        if (scriptSupplier != null) {
            Optional<PlutusScript> script = scriptSupplier.getScript(scriptHash);
            if (script.isPresent()) {
                PlutusScript ps = script.get();
                if (ps instanceof PlutusV3Script v3) {
                    return JulcScriptAdapter.toProgram(v3.getCborHex());
                }
                throw new UnsupportedOperationException(
                        "PlutusV1/V2 scripts not supported by JuLC evaluator. Script hash: " + scriptHash);
            }
        }

        throw new IllegalStateException("Script not found: " + scriptHash
                + ". Provide it in the witness set, reference inputs, or via ScriptSupplier.");
    }

    private boolean hasV1V2Script(Transaction tx, String scriptHash) {
        try {
            var ws = tx.getWitnessSet();
            if (ws.getPlutusV1Scripts() != null) {
                for (var script : ws.getPlutusV1Scripts()) {
                    if (scriptHash.equals(HexFormat.of().formatHex(script.getScriptHash()))) {
                        return true;
                    }
                }
            }
            if (ws.getPlutusV2Scripts() != null) {
                for (var script : ws.getPlutusV2Scripts()) {
                    if (scriptHash.equals(HexFormat.of().formatHex(script.getScriptHash()))) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            // Ignore hash computation errors
        }
        return false;
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
            default ->
                    throw new UnsupportedOperationException("ScriptInfo not supported for: " + purpose);
        };
    }
}
