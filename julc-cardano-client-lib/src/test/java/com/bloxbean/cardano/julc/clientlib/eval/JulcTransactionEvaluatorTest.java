package com.bloxbean.cardano.julc.clientlib.eval;

import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.*;
import com.bloxbean.cardano.client.transaction.spec.*;
import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JulcTransactionEvaluator.
 */
class JulcTransactionEvaluatorTest {

    static PlutusV3Script alwaysTrueScript;
    static PlutusV3Script alwaysFalseScript;
    static String alwaysTrueHash;
    static String alwaysFalseHash;

    @BeforeAll
    static void setup() throws Exception {
        // Always-true: a script that ignores its argument and returns unit
        // (\ctx -> ())
        var alwaysTrueProgram = Program.plutusV3(
                Term.lam("ctx", Term.const_(Constant.unit())));
        alwaysTrueScript = JulcScriptAdapter.fromProgram(alwaysTrueProgram);
        alwaysTrueHash = JulcScriptAdapter.scriptHash(alwaysTrueProgram);

        // Always-false: a script that always errors
        // (\ctx -> ERROR)
        var alwaysFalseProgram = Program.plutusV3(
                Term.lam("ctx", Term.error()));
        alwaysFalseScript = JulcScriptAdapter.fromProgram(alwaysFalseProgram);
        alwaysFalseHash = JulcScriptAdapter.scriptHash(alwaysFalseProgram);
    }

    @Test
    void evaluateTx_noRedeemers_returnsEmptySuccess() throws Exception {
        var evaluator = createEvaluator(null);

        // Build a simple tx with no redeemers
        var tx = Transaction.builder()
                .body(TransactionBody.builder()
                        .inputs(List.of(new TransactionInput("ab".repeat(32), 0)))
                        .outputs(List.of())
                        .fee(BigInteger.valueOf(200_000))
                        .build())
                .build();

        byte[] cbor = tx.serialize();
        var result = evaluator.evaluateTx(cbor, Set.of());

        assertTrue(result.isSuccessful(), "Expected success but got: " + result.getResponse());
        assertNotNull(result.getValue());
        assertTrue(result.getValue().isEmpty());
    }

    @Test
    void evaluateTx_alwaysTrue_returnsNonZeroBudget() throws Exception {
        // Build a spending tx with an always-true script
        String scriptAddr = buildScriptAddress(alwaysTrueHash);
        String txHash = "ab".repeat(32);

        // Create the UTxO being spent
        Utxo inputUtxo = Utxo.builder()
                .txHash(txHash)
                .outputIndex(0)
                .address(scriptAddr)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(5_000_000))))
                .inlineDatum(null)
                .dataHash(null)
                .build();

        // Build the transaction with a spend redeemer
        var redeemer = Redeemer.builder()
                .tag(RedeemerTag.Spend)
                .index(BigInteger.ZERO)
                .data(new BigIntPlutusData(BigInteger.ZERO))
                .exUnits(ExUnits.builder()
                        .mem(BigInteger.ZERO)
                        .steps(BigInteger.ZERO)
                        .build())
                .build();

        var tx = Transaction.builder()
                .body(TransactionBody.builder()
                        .inputs(List.of(new TransactionInput(txHash, 0)))
                        .outputs(List.of())
                        .fee(BigInteger.valueOf(200_000))
                        .build())
                .witnessSet(TransactionWitnessSet.builder()
                        .redeemers(List.of(redeemer))
                        .plutusV3Scripts(List.of(alwaysTrueScript))
                        .build())
                .build();

        var evaluator = createEvaluator(null);
        byte[] cbor = tx.serialize();
        var result = evaluator.evaluateTx(cbor, Set.of(inputUtxo));

        assertTrue(result.isSuccessful(), "Expected success but got: " + result.getResponse());
        assertNotNull(result.getValue());
        assertEquals(1, result.getValue().size());

        var evalResult = result.getValue().getFirst();
        assertEquals(RedeemerTag.Spend, evalResult.getRedeemerTag());
        assertEquals(0, evalResult.getIndex());
        assertNotNull(evalResult.getExUnits());
        assertTrue(evalResult.getExUnits().getMem().longValue() > 0
                || evalResult.getExUnits().getSteps().longValue() > 0,
                "Expected non-zero ExUnits");
    }

    @Test
    void evaluateTx_alwaysFalse_returnsError() throws Exception {
        String scriptAddr = buildScriptAddress(alwaysFalseHash);
        String txHash = "cd".repeat(32);

        Utxo inputUtxo = Utxo.builder()
                .txHash(txHash)
                .outputIndex(0)
                .address(scriptAddr)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(5_000_000))))
                .build();

        var redeemer = Redeemer.builder()
                .tag(RedeemerTag.Spend)
                .index(BigInteger.ZERO)
                .data(new BigIntPlutusData(BigInteger.ZERO))
                .exUnits(ExUnits.builder()
                        .mem(BigInteger.ZERO)
                        .steps(BigInteger.ZERO)
                        .build())
                .build();

        var tx = Transaction.builder()
                .body(TransactionBody.builder()
                        .inputs(List.of(new TransactionInput(txHash, 0)))
                        .outputs(List.of())
                        .fee(BigInteger.valueOf(200_000))
                        .build())
                .witnessSet(TransactionWitnessSet.builder()
                        .redeemers(List.of(redeemer))
                        .plutusV3Scripts(List.of(alwaysFalseScript))
                        .build())
                .build();

        var evaluator = createEvaluator(null);
        byte[] cbor = tx.serialize();
        var result = evaluator.evaluateTx(cbor, Set.of(inputUtxo));

        assertFalse(result.isSuccessful());
    }

    @Test
    void evaluateTx_scriptNotFound_returnsError() throws Exception {
        String unknownHash = "ff".repeat(28);
        String scriptAddr = buildScriptAddress(unknownHash);
        String txHash = "ee".repeat(32);

        Utxo inputUtxo = Utxo.builder()
                .txHash(txHash)
                .outputIndex(0)
                .address(scriptAddr)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(5_000_000))))
                .build();

        var redeemer = Redeemer.builder()
                .tag(RedeemerTag.Spend)
                .index(BigInteger.ZERO)
                .data(new BigIntPlutusData(BigInteger.ZERO))
                .exUnits(ExUnits.builder()
                        .mem(BigInteger.ZERO)
                        .steps(BigInteger.ZERO)
                        .build())
                .build();

        var tx = Transaction.builder()
                .body(TransactionBody.builder()
                        .inputs(List.of(new TransactionInput(txHash, 0)))
                        .outputs(List.of())
                        .fee(BigInteger.valueOf(200_000))
                        .build())
                .witnessSet(TransactionWitnessSet.builder()
                        .redeemers(List.of(redeemer))
                        .build())
                .build();

        var evaluator = createEvaluator(null);
        byte[] cbor = tx.serialize();
        var result = evaluator.evaluateTx(cbor, Set.of(inputUtxo));

        assertFalse(result.isSuccessful());
        assertTrue(result.getResponse().contains("Script not found"));
    }

    @Test
    void evaluateTx_mintRedeemer_alwaysTrue() throws Exception {
        // Build a minting tx with an always-true minting script
        String txHash = "ab".repeat(32);

        // Input UTxO (regular PubKey address, not a script)
        String payerAddr = "addr_test1qz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3jcu5d8ps7zex2k2xt3uqxgjqnnj83ws8lhrn648jjxtwq2ytjqp";
        Utxo inputUtxo = Utxo.builder()
                .txHash(txHash)
                .outputIndex(0)
                .address(payerAddr)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(10_000_000))))
                .build();

        // Mint redeemer at index 0 for the script's policy
        var redeemer = Redeemer.builder()
                .tag(RedeemerTag.Mint)
                .index(BigInteger.ZERO)
                .data(new BigIntPlutusData(BigInteger.ZERO))
                .exUnits(ExUnits.builder()
                        .mem(BigInteger.ZERO)
                        .steps(BigInteger.ZERO)
                        .build())
                .build();

        // Mint 1 token under the always-true script's policy
        var asset = new Asset("746f6b656e", BigInteger.ONE); // "token" in hex
        var multiAsset = new MultiAsset();
        multiAsset.setPolicyId(alwaysTrueHash);
        multiAsset.setAssets(List.of(asset));

        var tx = Transaction.builder()
                .body(TransactionBody.builder()
                        .inputs(List.of(new TransactionInput(txHash, 0)))
                        .outputs(List.of())
                        .fee(BigInteger.valueOf(200_000))
                        .mint(List.of(multiAsset))
                        .build())
                .witnessSet(TransactionWitnessSet.builder()
                        .redeemers(List.of(redeemer))
                        .plutusV3Scripts(List.of(alwaysTrueScript))
                        .build())
                .build();

        var evaluator = createEvaluator(null);
        byte[] cbor = tx.serialize();
        var result = evaluator.evaluateTx(cbor, Set.of(inputUtxo));

        assertTrue(result.isSuccessful(), "Expected success but got: " + result.getResponse());
        assertEquals(1, result.getValue().size());
        assertEquals(RedeemerTag.Mint, result.getValue().getFirst().getRedeemerTag());
    }

    @Test
    void evaluateTx_scriptViaSupplier() throws Exception {
        String scriptAddr = buildScriptAddress(alwaysTrueHash);
        String txHash = "ab".repeat(32);

        Utxo inputUtxo = Utxo.builder()
                .txHash(txHash)
                .outputIndex(0)
                .address(scriptAddr)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(5_000_000))))
                .build();

        var redeemer = Redeemer.builder()
                .tag(RedeemerTag.Spend)
                .index(BigInteger.ZERO)
                .data(new BigIntPlutusData(BigInteger.ZERO))
                .exUnits(ExUnits.builder()
                        .mem(BigInteger.ZERO)
                        .steps(BigInteger.ZERO)
                        .build())
                .build();

        // Tx with NO scripts in witness set — must come from ScriptSupplier
        var tx = Transaction.builder()
                .body(TransactionBody.builder()
                        .inputs(List.of(new TransactionInput(txHash, 0)))
                        .outputs(List.of())
                        .fee(BigInteger.valueOf(200_000))
                        .build())
                .witnessSet(TransactionWitnessSet.builder()
                        .redeemers(List.of(redeemer))
                        .build())
                .build();

        // Provide the script via ScriptSupplier
        ScriptSupplier scriptSupplier = hash ->
                alwaysTrueHash.equals(hash)
                        ? Optional.of(alwaysTrueScript)
                        : Optional.empty();

        var evaluator = createEvaluator(scriptSupplier);
        byte[] cbor = tx.serialize();
        var result = evaluator.evaluateTx(cbor, Set.of(inputUtxo));

        assertTrue(result.isSuccessful(), "Expected success but got: " + result.getResponse());
        assertEquals(1, result.getValue().size());
    }

    @Test
    void evaluateTx_utxoResolvedViaSupplier_whenInputUtxosEmpty() throws Exception {
        // Simulates the QuickTxBuilder / ScriptCostEvaluators flow where
        // evaluateTx(cbor) is called with empty inputUtxos — UTxOs must be
        // resolved via UtxoSupplier.getTxOutput() fallback.
        String scriptAddr = buildScriptAddress(alwaysTrueHash);
        String txHash = "ab".repeat(32);

        Utxo supplierUtxo = Utxo.builder()
                .txHash(txHash)
                .outputIndex(0)
                .address(scriptAddr)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(5_000_000))))
                .build();

        var redeemer = Redeemer.builder()
                .tag(RedeemerTag.Spend)
                .index(BigInteger.ZERO)
                .data(new BigIntPlutusData(BigInteger.ZERO))
                .exUnits(ExUnits.builder()
                        .mem(BigInteger.ZERO)
                        .steps(BigInteger.ZERO)
                        .build())
                .build();

        var tx = Transaction.builder()
                .body(TransactionBody.builder()
                        .inputs(List.of(new TransactionInput(txHash, 0)))
                        .outputs(List.of())
                        .fee(BigInteger.valueOf(200_000))
                        .build())
                .witnessSet(TransactionWitnessSet.builder()
                        .redeemers(List.of(redeemer))
                        .plutusV3Scripts(List.of(alwaysTrueScript))
                        .build())
                .build();

        // UtxoSupplier that returns the UTxO via getTxOutput
        UtxoSupplier utxoSupplier = new UtxoSupplier() {
            @Override
            public List<Utxo> getPage(String address, Integer nrOfItems, Integer page,
                    com.bloxbean.cardano.client.api.common.OrderEnum order) {
                return List.of();
            }

            @Override
            public Optional<Utxo> getTxOutput(String hash, int outputIndex) {
                if (txHash.equals(hash) && outputIndex == 0) {
                    return Optional.of(supplierUtxo);
                }
                return Optional.empty();
            }
        };

        ProtocolParamsSupplier protocolParamsSupplier = () -> {
            var params = new ProtocolParams();
            params.setMaxTxExMem("14000000");
            params.setMaxTxExSteps("10000000000");
            return params;
        };

        var evaluator = new JulcTransactionEvaluator(utxoSupplier, protocolParamsSupplier, null);
        byte[] cbor = tx.serialize();

        // Pass empty inputUtxos — should resolve via UtxoSupplier fallback
        var result = evaluator.evaluateTx(cbor, Set.of());

        assertTrue(result.isSuccessful(), "Expected success but got: " + result.getResponse());
        assertEquals(1, result.getValue().size());
        assertEquals(RedeemerTag.Spend, result.getValue().getFirst().getRedeemerTag());
    }

    // --- Helpers ---

    private JulcTransactionEvaluator createEvaluator(ScriptSupplier scriptSupplier) {
        UtxoSupplier utxoSupplier = new UtxoSupplier() {
            @Override
            public List<com.bloxbean.cardano.client.api.model.Utxo> getPage(
                    String address, Integer nrOfItems, Integer page,
                    com.bloxbean.cardano.client.api.common.OrderEnum order) {
                return List.of();
            }

            @Override
            public Optional<com.bloxbean.cardano.client.api.model.Utxo> getTxOutput(
                    String txHash, int outputIndex) {
                return Optional.empty();
            }
        };

        ProtocolParamsSupplier protocolParamsSupplier = () -> {
            var params = new ProtocolParams();
            params.setMaxTxExMem("14000000");
            params.setMaxTxExSteps("10000000000");
            return params;
        };

        return new JulcTransactionEvaluator(
                utxoSupplier, protocolParamsSupplier, scriptSupplier);
    }

    /**
     * Build a testnet script enterprise address from a script hash hex.
     */
    private static String buildScriptAddress(String scriptHashHex) {
        byte[] scriptHash = HexFormat.of().parseHex(scriptHashHex);
        var cclAddr = com.bloxbean.cardano.client.address.AddressProvider
                .getEntAddress(
                        com.bloxbean.cardano.client.address.Credential.fromScript(scriptHash),
                        com.bloxbean.cardano.client.common.model.Networks.testnet());
        return cclAddr.toBech32();
    }
}
