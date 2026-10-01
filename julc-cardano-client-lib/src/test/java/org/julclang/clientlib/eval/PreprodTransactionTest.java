package org.julclang.clientlib.eval;

import co.nstant.in.cbor.CborDecoder;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real preprod transactions, evaluated as recorded on chain. The fixtures in {@code /preprod} are Yano shadow-sync
 * bundles (bloxbean/yano, ADR-056): the transaction's original CBOR, the protocol parameters, the slot config and
 * every resolved UTxO (output CBOR, and the inline datum's chain bytes). The chain applied each transaction with
 * {@code is_valid = true}.
 *
 * <p>The expected ExUnits are what Yano's Haskell-faithful context translator (cardano-ledger {@code f649f975}) gives
 * on julc's CEK machine; for {@code 0037f55f} and {@code 3f1e07e7} every redeemer and for {@code 050f838f} the spend
 * they equal the ExUnits the transaction declares (bloxbean/julc#220).</p>
 */
class PreprodTransactionTest {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * Tx {@code 0037f55f…} (slot 73652469, PV 9): two PlutusV2 spends of outputs whose inline datums are maps the
     * script inspects.
     */
    @Test
    void chainedInlineDatumSpendsMatchTheLedgerBudget() throws Exception {
        var results = evaluate("preprod-0037f55f-chained-inline-datum.json");
        assertBudget(results, 0, 1_362_711, 403_547_293);
        assertBudget(results, 1, 1_409_897, 414_660_378);
    }

    /**
     * Tx {@code 050f838f…} (slot 69224264, PV 9): a PlutusV2 spend and a mint over outputs holding Word64 token
     * quantities. Before #220 the spend failed: the script compares Values, and Julc's came out in the wrong order.
     */
    @Test
    void word64QuantitySpendAndMintMatchTheLedgerBudget() throws Exception {
        var results = evaluate("preprod-050f838f-word64-quantities.json");
        assertBudget(results, 0, 1_784_012, 852_999_362);
        assertEquals(BigInteger.valueOf(321_017), find(results, "Mint", 0).getExUnits().getMem());
        assertEquals(BigInteger.valueOf(132_042_980), find(results, "Mint", 0).getExUnits().getSteps());
    }

    /**
     * Tx {@code 3f1e07e7…} (slot 92286697, PV 10): three PlutusV2 spends; Spend[1] verifies a secp256k1 ECDSA
     * signature whose r and s are zero, which must return False rather than fail (bloxbean/julc#219).
     */
    @Test
    void secp256k1ZeroSignatureSpendsMatchTheLedgerBudget() throws Exception {
        var results = evaluate("preprod-3f1e07e7-secp256k1-zero-signature.json");
        assertBudget(results, 1, 5_644_299, 1_276_760_095);
        assertBudget(results, 2, 1_536_184, 282_236_505);
        assertBudget(results, 3, 1_536_184, 282_236_505);
    }

    /** The TxId the scripts see is the chain's transaction id: the hash of the original body bytes. */
    @Test
    void txIdIsTheChainTransactionId() throws Exception {
        for (String name : List.of("preprod-0037f55f-chained-inline-datum.json",
                "preprod-050f838f-word64-quantities.json", "preprod-3f1e07e7-secp256k1-zero-signature.json")) {
            Bundle bundle = Bundle.read(name);
            var converter = new CclTxConverter(Transaction.deserialize(bundle.cbor), bundle.cbor, bundle.utxos,
                    null, bundle.slotConfig, bundle.params.getProtocolMajorVer());
            assertEquals(bundle.txHash, HexFormat.of().formatHex(converter.buildTxInfo().id().hash()), name);
        }
    }

    // --- helpers ---

    private static List<EvaluationResult> evaluate(String name) throws Exception {
        Bundle bundle = Bundle.read(name);
        UtxoSupplier none = new UtxoSupplier() {
            @Override
            public List<Utxo> getPage(String address, Integer nrOfItems, Integer page, OrderEnum order) {
                return List.of();
            }

            @Override
            public Optional<Utxo> getTxOutput(String txHash, int outputIndex) {
                return Optional.empty();
            }
        };
        var evaluator = new JulcTransactionEvaluator(none, () -> bundle.params,
                hash -> Optional.ofNullable(bundle.referenceScripts.get(hash)), bundle.slotConfig);
        var result = evaluator.evaluateTx(bundle.cbor, bundle.utxos);
        assertTrue(result.isSuccessful(), result.getResponse());
        return result.getValue();
    }

    private static void assertBudget(List<EvaluationResult> results, int spendIndex, long mem, long steps) {
        EvaluationResult r = find(results, "Spend", spendIndex);
        assertEquals(BigInteger.valueOf(mem), r.getExUnits().getMem(), "mem of Spend[" + spendIndex + "]");
        assertEquals(BigInteger.valueOf(steps), r.getExUnits().getSteps(), "steps of Spend[" + spendIndex + "]");
    }

    private static EvaluationResult find(List<EvaluationResult> results, String tag, int index) {
        return results.stream()
                .filter(r -> r.getRedeemerTag().name().equals(tag) && r.getIndex() == index)
                .findFirst().orElseThrow();
    }

    /** A bundle's transaction, protocol parameters, slot config and resolved UTxOs, as CCL types. */
    private record Bundle(String txHash, byte[] cbor, ProtocolParams params, SlotConfig slotConfig, Set<Utxo> utxos,
                          Map<String, PlutusScript> referenceScripts) {

        static Bundle read(String name) throws Exception {
            JsonNode root;
            try (InputStream in = PreprodTransactionTest.class.getResourceAsStream("/preprod/" + name)) {
                root = JSON.readTree(in);
            }
            JsonNode env = root.get("env");
            ProtocolParams params = null;
            Set<Utxo> utxos = new HashSet<>();
            Map<String, PlutusScript> scripts = new HashMap<>();
            for (JsonNode read : root.get("reads")) {
                switch (read.get("method").asText()) {
                    case "protocolParams" -> params = JSON.treeToValue(read.get("value"), ProtocolParams.class);
                    case "utxo" -> utxos.add(utxo(read.get("value"), scripts));
                    default -> { }
                }
            }
            return new Bundle(root.get("txHash").asText(), HexFormat.of().parseHex(root.get("txCbor").asText()),
                    params, new SlotConfig(env.get("slotZero").asLong(), env.get("slotZeroTime").asLong(),
                    env.get("slotLength").asLong()), utxos, scripts);
        }

        private static Utxo utxo(JsonNode value, Map<String, PlutusScript> scripts) throws Exception {
            String[] outpoint = value.get("outpoint").asText().split("#");
            TransactionOutput out = TransactionOutput.deserialize(
                    CborDecoder.decode(HexFormat.of().parseHex(value.get("output").asText())).get(0));
            var amounts = new ArrayList<Amount>();
            amounts.add(Amount.lovelace(out.getValue().getCoin()));
            if (out.getValue().getMultiAssets() != null) {
                for (var ma : out.getValue().getMultiAssets()) {
                    for (var asset : ma.getAssets()) {
                        String assetName = asset.getName().startsWith("0x") ? asset.getName().substring(2) : asset.getName();
                        amounts.add(new Amount(ma.getPolicyId() + assetName, asset.getValue()));
                    }
                }
            }
            String referenceScriptHash = null;
            if (out.getScriptRef() != null) {
                PlutusScript script = PlutusScript.deserializeScriptRef(out.getScriptRef());
                referenceScriptHash = HexFormat.of().formatHex(script.getScriptHash());
                scripts.put(referenceScriptHash, script);
            }
            return Utxo.builder()
                    .txHash(outpoint[0]).outputIndex(Integer.parseInt(outpoint[1]))
                    .address(out.getAddress()).amount(amounts)
                    .dataHash(out.getDatumHash() != null ? HexFormat.of().formatHex(out.getDatumHash()) : null)
                    .inlineDatum(value.has("inlineDatum") ? value.get("inlineDatum").asText() : null)
                    .referenceScriptHash(referenceScriptHash)
                    .build();
        }
    }
}
