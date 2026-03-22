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
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.JulcVm;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
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

    // V2 scripts (3-arg calling convention: datum, redeemer, scriptContext)
    static PlutusV2Script v2AlwaysTrueScript;
    static String v2AlwaysTrueHash;

    // PlutusTx-compiled V2 sum validator from cardano-client-lib examples.
    // Validates that datum + redeemer == expected sum (datum=8, redeemer=36).
    static final String V2_SUM_SCRIPT_CBOR =
            "5907a65907a3010000323322323232323232323232323232323322323232323222232325335323232333573466e1ccc07000d200000201e01d3333573466e1cd55cea80224000466442466002006004646464646464646464646464646666ae68cdc39aab9d500c480008cccccccccccc88888888888848cccccccccccc00403403002c02802402001c01801401000c008cd405c060d5d0a80619a80b80c1aba1500b33501701935742a014666aa036eb94068d5d0a804999aa80dbae501a35742a01066a02e0446ae85401cccd5406c08dd69aba150063232323333573466e1cd55cea801240004664424660020060046464646666ae68cdc39aab9d5002480008cc8848cc00400c008cd40b5d69aba15002302e357426ae8940088c98c80c0cd5ce01881801709aab9e5001137540026ae854008c8c8c8cccd5cd19b8735573aa004900011991091980080180119a816bad35742a004605c6ae84d5d1280111931901819ab9c03103002e135573ca00226ea8004d5d09aba2500223263202c33573805a05805426aae7940044dd50009aba1500533501775c6ae854010ccd5406c07c8004d5d0a801999aa80dbae200135742a00460426ae84d5d1280111931901419ab9c029028026135744a00226ae8940044d5d1280089aba25001135744a00226ae8940044d5d1280089aba25001135744a00226ae8940044d55cf280089baa00135742a00860226ae84d5d1280211931900d19ab9c01b01a018375a00a6eb4014405c4c98c805ccd5ce24810350543500017135573ca00226ea800448c88c008dd6000990009aa80b911999aab9f0012500a233500930043574200460066ae880080508c8c8cccd5cd19b8735573aa004900011991091980080180118061aba150023005357426ae8940088c98c8050cd5ce00a80a00909aab9e5001137540024646464646666ae68cdc39aab9d5004480008cccc888848cccc00401401000c008c8c8c8cccd5cd19b8735573aa0049000119910919800801801180a9aba1500233500f014357426ae8940088c98c8064cd5ce00d00c80b89aab9e5001137540026ae854010ccd54021d728039aba150033232323333573466e1d4005200423212223002004357426aae79400c8cccd5cd19b875002480088c84888c004010dd71aba135573ca00846666ae68cdc3a801a400042444006464c6403666ae7007006c06406005c4d55cea80089baa00135742a00466a016eb8d5d09aba2500223263201533573802c02a02626ae8940044d5d1280089aab9e500113754002266aa002eb9d6889119118011bab00132001355014223233335573e0044a010466a00e66442466002006004600c6aae754008c014d55cf280118021aba200301213574200222440042442446600200800624464646666ae68cdc3a800a40004642446004006600a6ae84d55cf280191999ab9a3370ea0049001109100091931900819ab9c01101000e00d135573aa00226ea80048c8c8cccd5cd19b875001480188c848888c010014c01cd5d09aab9e500323333573466e1d400920042321222230020053009357426aae7940108cccd5cd19b875003480088c848888c004014c01cd5d09aab9e500523333573466e1d40112000232122223003005375c6ae84d55cf280311931900819ab9c01101000e00d00c00b135573aa00226ea80048c8c8cccd5cd19b8735573aa004900011991091980080180118029aba15002375a6ae84d5d1280111931900619ab9c00d00c00a135573ca00226ea80048c8cccd5cd19b8735573aa002900011bae357426aae7940088c98c8028cd5ce00580500409baa001232323232323333573466e1d4005200c21222222200323333573466e1d4009200a21222222200423333573466e1d400d2008233221222222233001009008375c6ae854014dd69aba135744a00a46666ae68cdc3a8022400c4664424444444660040120106eb8d5d0a8039bae357426ae89401c8cccd5cd19b875005480108cc8848888888cc018024020c030d5d0a8049bae357426ae8940248cccd5cd19b875006480088c848888888c01c020c034d5d09aab9e500b23333573466e1d401d2000232122222223005008300e357426aae7940308c98c804ccd5ce00a00980880800780700680600589aab9d5004135573ca00626aae7940084d55cf280089baa0012323232323333573466e1d400520022333222122333001005004003375a6ae854010dd69aba15003375a6ae84d5d1280191999ab9a3370ea0049000119091180100198041aba135573ca00c464c6401866ae700340300280244d55cea80189aba25001135573ca00226ea80048c8c8cccd5cd19b875001480088c8488c00400cdd71aba135573ca00646666ae68cdc3a8012400046424460040066eb8d5d09aab9e500423263200933573801401200e00c26aae7540044dd500089119191999ab9a3370ea00290021091100091999ab9a3370ea00490011190911180180218031aba135573ca00846666ae68cdc3a801a400042444004464c6401466ae7002c02802001c0184d55cea80089baa0012323333573466e1d40052002200923333573466e1d40092000200923263200633573800e00c00800626aae74dd5000a4c240029210350543100320013550032225335333573466e1c0092000005004100113300333702004900119b80002001122002122001112323001001223300330020020011";

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

        // V2 always-true (3-arg: \datum redeemer ctx -> ())
        var v2AlwaysTrueProgram = Program.plutusV2(
                Term.lam("d", Term.lam("r", Term.lam("ctx", Term.const_(Constant.unit())))));
        var v2Temp = JulcScriptAdapter.fromProgram(v2AlwaysTrueProgram);
        v2AlwaysTrueScript = PlutusV2Script.builder()
                .cborHex(v2Temp.getCborHex())
                .build();
        v2AlwaysTrueHash = HexFormat.of().formatHex(v2AlwaysTrueScript.getScriptHash());
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

    // --- V2 Tests ---

    @Test
    void evaluateTx_v2_alwaysTrue_spending() throws Exception {
        String scriptAddr = buildScriptAddress(v2AlwaysTrueHash);
        String txHash = "aa".repeat(32);

        Utxo inputUtxo = Utxo.builder()
                .txHash(txHash)
                .outputIndex(0)
                .address(scriptAddr)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(5_000_000))))
                .inlineDatum("08") // CBOR integer 8
                .build();

        var redeemer = Redeemer.builder()
                .tag(RedeemerTag.Spend)
                .index(BigInteger.ZERO)
                .data(new BigIntPlutusData(BigInteger.valueOf(36)))
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
                        .plutusV2Scripts(List.of(v2AlwaysTrueScript))
                        .build())
                .build();

        var evaluator = createEvaluator(null);
        byte[] cbor = tx.serialize();
        var result = evaluator.evaluateTx(cbor, Set.of(inputUtxo));

        assertTrue(result.isSuccessful(), "V2 always-true should succeed: " + result.getResponse());
        assertEquals(1, result.getValue().size());
        assertEquals(RedeemerTag.Spend, result.getValue().getFirst().getRedeemerTag());
    }

    @Test
    void evaluateV2_sumScript_directVmEvaluation() {
        // Decode the PlutusTx-compiled V2 sum validator
        Program program = JulcScriptAdapter.toProgram(V2_SUM_SCRIPT_CBOR);

        // Build V2 arguments: datum=IntData(8), redeemer=IntData(36)
        var datum = new com.bloxbean.cardano.julc.core.PlutusData.IntData(BigInteger.valueOf(8));
        var redeemer = new com.bloxbean.cardano.julc.core.PlutusData.IntData(BigInteger.valueOf(36));

        // Build a minimal V2 ScriptContext
        var scriptContext = buildMinimalV2SpendingContext();

        // Evaluate with V2
        JulcVm vm = JulcVm.create(PlutusLanguage.PLUTUS_V2);
        EvalResult result = vm.evaluateWithArgs(program, List.of(datum, redeemer, scriptContext));

        if (result instanceof EvalResult.Failure failure) {
            fail("V2 sum validator failed: " + failure.error() + "\nTraces: " + failure.traces());
        }
        assertTrue(result.isSuccess(), "V2 sum validator should succeed");
    }

    @Test
    void evaluateTx_v2_sumScript_spending() throws Exception {
        // Get the V2 sum script hash
        PlutusV2Script v2SumScript = PlutusV2Script.builder()
                .cborHex(V2_SUM_SCRIPT_CBOR)
                .build();
        String v2SumHash = HexFormat.of().formatHex(v2SumScript.getScriptHash());
        String scriptAddr = buildScriptAddress(v2SumHash);
        String txHash = "aa".repeat(32);

        // UTxO with inline datum IntData(8)
        Utxo inputUtxo = Utxo.builder()
                .txHash(txHash)
                .outputIndex(0)
                .address(scriptAddr)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(5_000_000))))
                .inlineDatum("08") // CBOR integer 8
                .build();

        var redeemer = Redeemer.builder()
                .tag(RedeemerTag.Spend)
                .index(BigInteger.ZERO)
                .data(new BigIntPlutusData(BigInteger.valueOf(36)))
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
                        .plutusV2Scripts(List.of(v2SumScript))
                        .build())
                .build();

        var evaluator = createEvaluator(null);
        byte[] cbor = tx.serialize();
        var result = evaluator.evaluateTx(cbor, Set.of(inputUtxo));

        assertTrue(result.isSuccessful(), "V2 sum script should succeed: " + result.getResponse());
        assertEquals(1, result.getValue().size());
        assertEquals(RedeemerTag.Spend, result.getValue().getFirst().getRedeemerTag());
    }

    // --- Helpers ---

    /**
     * Build a minimal V2 ScriptContext as raw PlutusData for testing.
     * V2 ScriptContext = Constr 0 [TxInfo, ScriptPurpose]
     */
    private static com.bloxbean.cardano.julc.core.PlutusData buildMinimalV2SpendingContext() {
        // Use short alias for readability
        var PD = com.bloxbean.cardano.julc.core.PlutusData.class;

        byte[] txHashBytes = HexFormat.of().parseHex("aa".repeat(32));
        byte[] scriptHashBytes = HexFormat.of().parseHex("bb".repeat(28));
        byte[] signerHashBytes = HexFormat.of().parseHex("cc".repeat(28));

        // TxId = Constr 0 [B hash]
        var txId = new com.bloxbean.cardano.julc.core.PlutusData.ConstrData(0,
                List.of(new com.bloxbean.cardano.julc.core.PlutusData.BytesData(txHashBytes)));
        // TxOutRef = Constr 0 [TxId, I index]
        var txOutRef = new com.bloxbean.cardano.julc.core.PlutusData.ConstrData(0,
                List.of(txId, new com.bloxbean.cardano.julc.core.PlutusData.IntData(BigInteger.ZERO)));
        // Address: Constr 0 [ScriptCredential(Constr 1 [B hash]), Nothing(Constr 1 [])]
        var scriptCred = new com.bloxbean.cardano.julc.core.PlutusData.ConstrData(1,
                List.of(new com.bloxbean.cardano.julc.core.PlutusData.BytesData(scriptHashBytes)));
        var noStaking = new com.bloxbean.cardano.julc.core.PlutusData.ConstrData(1, List.of());
        var address = new com.bloxbean.cardano.julc.core.PlutusData.ConstrData(0,
                List.of(scriptCred, noStaking));
        // Value: Map { B"" -> Map { B"" -> I 5000000 } }
        var value = new com.bloxbean.cardano.julc.core.PlutusData.MapData(List.of(
                new com.bloxbean.cardano.julc.core.PlutusData.Pair(
                        new com.bloxbean.cardano.julc.core.PlutusData.BytesData(new byte[0]),
                        new com.bloxbean.cardano.julc.core.PlutusData.MapData(List.of(
                                new com.bloxbean.cardano.julc.core.PlutusData.Pair(
                                        new com.bloxbean.cardano.julc.core.PlutusData.BytesData(new byte[0]),
                                        new com.bloxbean.cardano.julc.core.PlutusData.IntData(BigInteger.valueOf(5_000_000))))))));
        // OutputDatum.InlineDatum = Constr 2 [IntData(8)]
        var outputDatum = new com.bloxbean.cardano.julc.core.PlutusData.ConstrData(2,
                List.of(new com.bloxbean.cardano.julc.core.PlutusData.IntData(BigInteger.valueOf(8))));
        // Maybe ScriptHash = Nothing = Constr 1 []
        var noRefScript = new com.bloxbean.cardano.julc.core.PlutusData.ConstrData(1, List.of());
        // V2 TxOut = Constr 0 [address, value, outputDatum, maybeRefScript]
        var txOut = new com.bloxbean.cardano.julc.core.PlutusData.ConstrData(0,
                List.of(address, value, outputDatum, noRefScript));
        // TxInInfo = Constr 0 [TxOutRef, TxOut]
        var txInInfo = new com.bloxbean.cardano.julc.core.PlutusData.ConstrData(0,
                List.of(txOutRef, txOut));
        // Fee value
        var fee = new com.bloxbean.cardano.julc.core.PlutusData.MapData(List.of(
                new com.bloxbean.cardano.julc.core.PlutusData.Pair(
                        new com.bloxbean.cardano.julc.core.PlutusData.BytesData(new byte[0]),
                        new com.bloxbean.cardano.julc.core.PlutusData.MapData(List.of(
                                new com.bloxbean.cardano.julc.core.PlutusData.Pair(
                                        new com.bloxbean.cardano.julc.core.PlutusData.BytesData(new byte[0]),
                                        new com.bloxbean.cardano.julc.core.PlutusData.IntData(BigInteger.valueOf(200_000))))))));
        // Empty structures
        var emptyList = new com.bloxbean.cardano.julc.core.PlutusData.ListData(List.of());
        var emptyMap = new com.bloxbean.cardano.julc.core.PlutusData.MapData(List.of());
        // ValidRange = always: Interval(LowerBound(NegInf, True), UpperBound(PosInf, True))
        var negInf = new com.bloxbean.cardano.julc.core.PlutusData.ConstrData(0, List.of());
        var posInf = new com.bloxbean.cardano.julc.core.PlutusData.ConstrData(2, List.of());
        var trueBool = new com.bloxbean.cardano.julc.core.PlutusData.ConstrData(1, List.of());
        var lowerBound = new com.bloxbean.cardano.julc.core.PlutusData.ConstrData(0,
                List.of(negInf, trueBool));
        var upperBound = new com.bloxbean.cardano.julc.core.PlutusData.ConstrData(0,
                List.of(posInf, trueBool));
        var validRange = new com.bloxbean.cardano.julc.core.PlutusData.ConstrData(0,
                List.of(lowerBound, upperBound));
        // Signatories
        var signerPkh = new com.bloxbean.cardano.julc.core.PlutusData.BytesData(signerHashBytes);
        // V2 ScriptPurpose.Spending = Constr 1 [txOutRef]
        var purpose = new com.bloxbean.cardano.julc.core.PlutusData.ConstrData(1,
                List.of(txOutRef));

        // V2 TxInfo = Constr 0 [inputs, refInputs, outputs, fee, mint, dcert, wdrl,
        //   validRange, signatories, redeemers, data, id]
        var txInfo = new com.bloxbean.cardano.julc.core.PlutusData.ConstrData(0, List.of(
                new com.bloxbean.cardano.julc.core.PlutusData.ListData(List.of(txInInfo)),  // inputs
                emptyList,     // referenceInputs
                emptyList,     // outputs
                fee,           // fee (Value)
                emptyMap,      // mint (Value = empty map)
                emptyList,     // dcert
                emptyMap,      // wdrl
                validRange,    // validRange
                new com.bloxbean.cardano.julc.core.PlutusData.ListData(List.of(signerPkh)), // signatories
                emptyMap,      // redeemers
                emptyMap,      // data (witness datums)
                txId           // id
        ));

        return new com.bloxbean.cardano.julc.core.PlutusData.ConstrData(0,
                List.of(txInfo, purpose));
    }

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
