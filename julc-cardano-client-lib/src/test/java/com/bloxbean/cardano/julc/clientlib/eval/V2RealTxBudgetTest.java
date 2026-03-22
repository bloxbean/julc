package com.bloxbean.cardano.julc.clientlib.eval;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.ExBudget;
import com.bloxbean.cardano.julc.vm.JulcVmProvider;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import com.bloxbean.cardano.julc.vm.java.JavaVmProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reproduction test for V2 budget delta investigation.
 * <p>
 * Uses actual on-chain transaction data to build the exact ScriptContext and evaluate
 * through both Java and Scalus VMs, comparing budgets with the Cardano node's result.
 * <p>
 * Node budget:  mem=800,662, cpu=202,209,884
 * JuLC budget:  mem=800,756, cpu=202,200,515
 * Delta:        mem +94 (over), cpu -9,369 (under)
 */
class V2RealTxBudgetTest {

    // Actual transaction CBOR from on-chain V2 spending tx
    static final String TX_CBOR =
            "84a800d90102828258203b2352f450793b11e5d03c5b764c32903a9b3e91dc0dee578cf1ae4b5d59173700825820f6ab80d6ed4a2935539c104a16c0d0c136a73c85f3374de83c64e9c37cc94773010182825839001c1ffaf141ebbb8e3a7072bb15f50f938b994c82de2d175f358fc942441f00edfe1b8d6a84f0d19c25a9c8829442160c0b5c758094c423441a003d0900825839000d30c6d716fd6c48ab546f0b66fd5faaa3a2f0ccecf0a72ea8c04a30a91cf775fb8e1fdfe882b26014f11d56bd47681270a55fd15e6d064c1ac2813401021a00042c8d0b58201eb80ca71ffc7131b61d1a4e1ba6e3053d5e9a713d80db58d97052461ec3df4e0dd9010281825820f6ab80d6ed4a2935539c104a16c0d0c136a73c85f3374de83c64e9c37cc947730110825839000d30c6d716fd6c48ab546f0b66fd5faaa3a2f0ccecf0a72ea8c04a30a91cf775fb8e1fdfe882b26014f11d56bd47681270a55fd15e6d064c1ac27f1dba111a000642d412d901028182582073518d464dbf639e5a98330dc198efb981c579a10101e21538c64518cbc60c4700a200d9010281825820fa83136ba3706ea8d337d80bc94817e057ab51bd43d471fd386951c0a16c42fc5840369e6e4dcddeaf732cbf1d3a8b571d03315a117b934d863f046887a2d40f1da5006843fea95ea287fb229ac083938e492f31281c30e24ad35fd5f2f183312f0805a1820000821824821a000c37f41a0c0d55c3f5f6";

    // PlutusTx-compiled V2 sum validator (datum + redeemer == expected sum)
    static final String V2_SUM_SCRIPT_CBOR =
            "5907a65907a3010000323322323232323232323232323232323322323232323222232325335323232333573466e1ccc07000d200000201e01d3333573466e1cd55cea80224000466442466002006004646464646464646464646464646666ae68cdc39aab9d500c480008cccccccccccc88888888888848cccccccccccc00403403002c02802402001c01801401000c008cd405c060d5d0a80619a80b80c1aba1500b33501701935742a014666aa036eb94068d5d0a804999aa80dbae501a35742a01066a02e0446ae85401cccd5406c08dd69aba150063232323333573466e1cd55cea801240004664424660020060046464646666ae68cdc39aab9d5002480008cc8848cc00400c008cd40b5d69aba15002302e357426ae8940088c98c80c0cd5ce01881801709aab9e5001137540026ae854008c8c8c8cccd5cd19b8735573aa004900011991091980080180119a816bad35742a004605c6ae84d5d1280111931901819ab9c03103002e135573ca00226ea8004d5d09aba2500223263202c33573805a05805426aae7940044dd50009aba1500533501775c6ae854010ccd5406c07c8004d5d0a801999aa80dbae200135742a00460426ae84d5d1280111931901419ab9c029028026135744a00226ae8940044d5d1280089aba25001135744a00226ae8940044d5d1280089aba25001135744a00226ae8940044d55cf280089baa00135742a00860226ae84d5d1280211931900d19ab9c01b01a018375a00a6eb4014405c4c98c805ccd5ce24810350543500017135573ca00226ea800448c88c008dd6000990009aa80b911999aab9f0012500a233500930043574200460066ae880080508c8c8cccd5cd19b8735573aa004900011991091980080180118061aba150023005357426ae8940088c98c8050cd5ce00a80a00909aab9e5001137540024646464646666ae68cdc39aab9d5004480008cccc888848cccc00401401000c008c8c8c8cccd5cd19b8735573aa0049000119910919800801801180a9aba1500233500f014357426ae8940088c98c8064cd5ce00d00c80b89aab9e5001137540026ae854010ccd54021d728039aba150033232323333573466e1d4005200423212223002004357426aae79400c8cccd5cd19b875002480088c84888c004010dd71aba135573ca00846666ae68cdc3a801a400042444006464c6403666ae7007006c06406005c4d55cea80089baa00135742a00466a016eb8d5d09aba2500223263201533573802c02a02626ae8940044d5d1280089aab9e500113754002266aa002eb9d6889119118011bab00132001355014223233335573e0044a010466a00e66442466002006004600c6aae754008c014d55cf280118021aba200301213574200222440042442446600200800624464646666ae68cdc3a800a40004642446004006600a6ae84d55cf280191999ab9a3370ea0049001109100091931900819ab9c01101000e00d135573aa00226ea80048c8c8cccd5cd19b875001480188c848888c010014c01cd5d09aab9e500323333573466e1d400920042321222230020053009357426aae7940108cccd5cd19b875003480088c848888c004014c01cd5d09aab9e500523333573466e1d40112000232122223003005375c6ae84d55cf280311931900819ab9c01101000e00d00c00b135573aa00226ea80048c8c8cccd5cd19b8735573aa004900011991091980080180118029aba15002375a6ae84d5d1280111931900619ab9c00d00c00a135573ca00226ea80048c8cccd5cd19b8735573aa002900011bae357426aae7940088c98c8028cd5ce00580500409baa001232323232323333573466e1d4005200c21222222200323333573466e1d4009200a21222222200423333573466e1d400d2008233221222222233001009008375c6ae854014dd69aba135744a00a46666ae68cdc3a8022400c4664424444444660040120106eb8d5d0a8039bae357426ae89401c8cccd5cd19b875005480108cc8848888888cc018024020c030d5d0a8049bae357426ae8940248cccd5cd19b875006480088c848888888c01c020c034d5d09aab9e500b23333573466e1d401d2000232122222223005008300e357426aae7940308c98c804ccd5ce00a00980880800780700680600589aab9d5004135573ca00626aae7940084d55cf280089baa0012323232323333573466e1d400520022333222122333001005004003375a6ae854010dd69aba15003375a6ae84d5d1280191999ab9a3370ea0049000119091180100198041aba135573ca00c464c6401866ae700340300280244d55cea80189aba25001135573ca00226ea80048c8c8cccd5cd19b875001480088c8488c00400cdd71aba135573ca00646666ae68cdc3a8012400046424460040066eb8d5d09aab9e500423263200933573801401200e00c26aae7540044dd500089119191999ab9a3370ea00290021091100091999ab9a3370ea00490011190911180180218031aba135573ca00846666ae68cdc3a801a400042444004464c6401466ae7002c02802001c0184d55cea80089baa0012323333573466e1d40052002200923333573466e1d40092000200923263200633573800e00c00800626aae74dd5000a4c240029210350543100320013550032225335333573466e1c0092000005004100113300333702004900119b80002001122002122001112323001001223300330020020011";

    // Expected node budgets
    static final long NODE_MEM = 800_662;
    static final long NODE_CPU = 202_209_884;

    static Program sumProgram;
    static JavaVmProvider javaProvider;
    static JulcVmProvider scalusProvider;

    @BeforeAll
    static void setup() {
        sumProgram = JulcScriptAdapter.toProgram(V2_SUM_SCRIPT_CBOR);
        javaProvider = new JavaVmProvider();

        JulcVmProvider found = null;
        for (var provider : ServiceLoader.load(JulcVmProvider.class)) {
            if ("Scalus".equals(provider.name())) {
                found = provider;
                break;
            }
        }
        scalusProvider = found;
    }

    private boolean hasScalus() {
        return scalusProvider != null;
    }

    /**
     * Build the exact UTxO set matching the actual transaction.
     */
    private Set<Utxo> buildInputUtxos() {
        // Script UTxO: spending input at index 0
        Utxo scriptUtxo = Utxo.builder()
                .txHash("3b2352f450793b11e5d03c5b764c32903a9b3e91dc0dee578cf1ae4b5d591737")
                .outputIndex(0)
                .address("addr_test1wzcppsyg36f65jydjsd6fqu3xm7whxu6nmp3pftn9xfgd4ckah4da")
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(4_000_000))))
                .dataHash("fadd2180bd6b1cfa73a67e7892d878521ef69918995040fb8661647d321e0c55")
                .inlineDatum("08") // IntData(8)
                .build();

        // Payer UTxO: non-script input at index 1
        Utxo payerUtxo = Utxo.builder()
                .txHash("f6ab80d6ed4a2935539c104a16c0d0c136a73c85f3374de83c64e9c37cc94773")
                .outputIndex(1)
                .address("addr_test1qqxnp3khzm7kcj9t23hskehat7428ghsenk0pfew4rqy5v9frnmht7uwrl073q4jvq20z82kh4rksyns540azhndqexqpvhgqr")
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(3_263_520_910L))))
                .build();

        return Set.of(scriptUtxo, payerUtxo);
    }

    /**
     * Build the reference input UTxOs (contains the reference script).
     */
    private Set<Utxo> buildRefUtxos() {
        Utxo refScriptUtxo = Utxo.builder()
                .txHash("73518d464dbf639e5a98330dc198efb981c579a10101e21538c64518cbc60c47")
                .outputIndex(0)
                .address("addr_test1qqwpl7h3g84mhr36wpetk904p7fchx2vst0z696lxk8ujsjyruqwmlsm344gfux3nsj6njyzj3ppvrqtt36cp9xyydzqzumz82")
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(9_464_760))))
                .referenceScriptHash("b010c0888e93aa488d941ba4839136fceb9b9a9ec310a573299286d7")
                .build();

        return Set.of(refScriptUtxo);
    }

    /**
     * Build all UTxOs (inputs + reference inputs).
     */
    private Set<Utxo> buildAllUtxos() {
        var all = new HashSet<>(buildInputUtxos());
        all.addAll(buildRefUtxos());
        return all;
    }

    /**
     * Step 1: Cross-VM comparison — evaluate the exact transaction through both VMs.
     * <p>
     * If VMs agree → delta is in ScriptContext construction.
     * If VMs disagree → delta is in VM cost model.
     */
    @Test
    void crossVmComparison_realV2Transaction() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(hasScalus(), "Scalus provider not available");

        // Deserialize the actual transaction
        Transaction tx = Transaction.deserialize(HexFormat.of().parseHex(TX_CBOR));
        assertNotNull(tx, "Transaction should deserialize");

        // Build TxInfo with PV 8 (Babbage V2)
        var allUtxos = buildAllUtxos();
        var converter = new CclTxConverter(tx, allUtxos, null, null, 8);
        TxInfo txInfo = converter.buildTxInfo();

        // Get the redeemer: Spend[0] → IntData(36)
        var redeemer = tx.getWitnessSet().getRedeemers().getFirst();
        ScriptPurpose purpose = converter.redeemerToScriptPurpose(redeemer);
        PlutusData redeemerData = PlutusDataAdapter.fromClientLib(redeemer.getData());

        // Build V2 ScriptContext
        PlutusData scriptContextData = V1V2ScriptContextBuilder.build(
                PlutusLanguage.PLUTUS_V2, txInfo, purpose, converter);

        // Resolve datum for spending (should be IntData(8) from inline datum)
        PlutusData datumData = resolveDatum(purpose, txInfo);

        List<PlutusData> args = List.of(datumData, redeemerData, scriptContextData);

        // Evaluate with Java VM
        EvalResult javaResult = javaProvider.evaluateWithArgs(
                sumProgram, PlutusLanguage.PLUTUS_V2, args, null);
        assertTrue(javaResult.isSuccess(),
                "Java VM should succeed: " + javaResult);

        // Evaluate with Scalus VM
        EvalResult scalusResult = scalusProvider.evaluateWithArgs(
                sumProgram, PlutusLanguage.PLUTUS_V2, args, null);
        assertTrue(scalusResult.isSuccess(),
                "Scalus VM should succeed: " + scalusResult);

        ExBudget javaBudget = javaResult.budgetConsumed();
        ExBudget scalusBudget = scalusResult.budgetConsumed();

        System.out.println("=== V2 Real Tx Cross-VM Budget Comparison ===");
        System.out.println("Java VM:   mem=" + javaBudget.memoryUnits()
                + ", cpu=" + javaBudget.cpuSteps());
        System.out.println("Scalus VM: mem=" + scalusBudget.memoryUnits()
                + ", cpu=" + scalusBudget.cpuSteps());
        System.out.println("VM Delta:  mem=" + (javaBudget.memoryUnits() - scalusBudget.memoryUnits())
                + ", cpu=" + (javaBudget.cpuSteps() - scalusBudget.cpuSteps()));
        System.out.println();
        System.out.println("Node:      mem=" + NODE_MEM + ", cpu=" + NODE_CPU);
        System.out.println("Java-Node: mem=" + (javaBudget.memoryUnits() - NODE_MEM)
                + ", cpu=" + (javaBudget.cpuSteps() - NODE_CPU));
        System.out.println("Scalus-Node: mem=" + (scalusBudget.memoryUnits() - NODE_MEM)
                + ", cpu=" + (scalusBudget.cpuSteps() - NODE_CPU));

        // VMs should agree with each other
        assertEquals(scalusBudget.cpuSteps(), javaBudget.cpuSteps(),
                "CPU budget mismatch between Java and Scalus VMs");
        assertEquals(scalusBudget.memoryUnits(), javaBudget.memoryUnits(),
                "Memory budget mismatch between Java and Scalus VMs");
    }

    /**
     * Step 2: Dump and inspect the ScriptContext PlutusData tree.
     */
    @Test
    void dumpScriptContext_realV2Transaction() throws Exception {
        Transaction tx = Transaction.deserialize(HexFormat.of().parseHex(TX_CBOR));
        var allUtxos = buildAllUtxos();
        var converter = new CclTxConverter(tx, allUtxos, null, null, 8);
        TxInfo txInfo = converter.buildTxInfo();

        var redeemer = tx.getWitnessSet().getRedeemers().getFirst();
        ScriptPurpose purpose = converter.redeemerToScriptPurpose(redeemer);

        PlutusData scriptContextData = V1V2ScriptContextBuilder.build(
                PlutusLanguage.PLUTUS_V2, txInfo, purpose, converter);

        System.out.println("=== V2 Real Tx ScriptContext PlutusData Tree ===");
        System.out.println(scriptContextData.prettyPrint());
        System.out.println("Node count: " + scriptContextData.countNodes());

        // Basic structural assertions
        assertInstanceOf(PlutusData.ConstrData.class, scriptContextData);
        var ctx = (PlutusData.ConstrData) scriptContextData;
        assertEquals(0, ctx.tag(), "ScriptContext should be Constr 0");
        assertEquals(2, ctx.fields().size(), "ScriptContext should have 2 fields: TxInfo + ScriptPurpose");

        // TxInfo should have 12 fields (V2)
        var txInfoData = (PlutusData.ConstrData) ctx.fields().get(0);
        assertEquals(12, txInfoData.fields().size(),
                "V2 TxInfo should have 12 fields");
    }

    /**
     * Step 3a: Verify datums map content.
     * The tx has no witness datums. Inline datums are NOT included in the datums map
     * (per Cardano ledger spec, txInfoData contains only witness set datums).
     */
    @Test
    void verifyDatumsMap_realV2Transaction() throws Exception {
        Transaction tx = Transaction.deserialize(HexFormat.of().parseHex(TX_CBOR));
        var allUtxos = buildAllUtxos();
        var converter = new CclTxConverter(tx, allUtxos, null, null, 8);
        TxInfo txInfo = converter.buildTxInfo();

        var datums = txInfo.datums();
        System.out.println("=== Datums Map ===");
        System.out.println("Size: " + datums.size());

        // No witness datums in this transaction → empty map
        assertEquals(0, datums.size(),
                "Datums map should be empty (no witness datums, inline datums not included)");

        // Verify that the inline datum is still accessible through the TxOut's OutputDatum
        TxInInfo scriptInput = txInfo.inputs().get(0);
        assertInstanceOf(OutputDatum.OutputDatumInline.class, scriptInput.resolved().datum(),
                "Script input should have inline datum");
        var inlineDatum = (OutputDatum.OutputDatumInline) scriptInput.resolved().datum();
        assertInstanceOf(PlutusData.IntData.class, inlineDatum.datum());
        assertEquals(BigInteger.valueOf(8), ((PlutusData.IntData) inlineDatum.datum()).value());
    }

    /**
     * Step 3b: Verify fee encoding as Value.
     * V2 fee is a Value (map), not plain integer.
     * Should be: Map { B"" → Map { B"" → I 273549 } }
     */
    @Test
    void verifyFeeEncoding_realV2Transaction() throws Exception {
        Transaction tx = Transaction.deserialize(HexFormat.of().parseHex(TX_CBOR));
        var allUtxos = buildAllUtxos();
        var converter = new CclTxConverter(tx, allUtxos, null, null, 8);
        TxInfo txInfo = converter.buildTxInfo();

        BigInteger fee = txInfo.fee();
        assertEquals(BigInteger.valueOf(273_549), fee, "Fee should be 273549");

        // Verify the V2 fee encoding (Value = map of maps)
        PlutusData feeData = Value.lovelace(fee).toPlutusData();
        System.out.println("=== Fee Value Encoding ===");
        System.out.println(feeData.prettyPrint());

        // Should be: Map { B"" → Map { B"" → I 273549 } }
        assertInstanceOf(PlutusData.MapData.class, feeData);
        var feeMap = (PlutusData.MapData) feeData;
        assertEquals(1, feeMap.entries().size(), "Fee value should have 1 policy entry");
        var innerPair = feeMap.entries().getFirst();
        assertInstanceOf(PlutusData.BytesData.class, innerPair.key());
        assertEquals(0, ((PlutusData.BytesData) innerPair.key()).value().length,
                "Policy ID should be empty bytes (ADA)");
    }

    /**
     * Step 3c: Verify redeemers map encoding.
     * V2 redeemers: Map { ScriptPurpose → Data }
     * Key: Constr 1 [Constr 0 [Constr 0 [B txHash], I 0]] (Spending TxOutRef)
     * Value: IntData(36)
     */
    @Test
    void verifyRedeemersEncoding_realV2Transaction() throws Exception {
        Transaction tx = Transaction.deserialize(HexFormat.of().parseHex(TX_CBOR));
        var allUtxos = buildAllUtxos();
        var converter = new CclTxConverter(tx, allUtxos, null, null, 8);
        TxInfo txInfo = converter.buildTxInfo();

        var redeemers = txInfo.redeemers();
        System.out.println("=== Redeemers Map ===");
        System.out.println("Size: " + redeemers.size());

        assertEquals(1, redeemers.size(), "Should have exactly 1 redeemer");

        // The redeemer data should be IntData(36)
        var keys = redeemers.keys();
        PlutusData redeemerValue = redeemers.get(keys.get(0));
        assertInstanceOf(PlutusData.IntData.class, redeemerValue);
        assertEquals(BigInteger.valueOf(36), ((PlutusData.IntData) redeemerValue).value());

        // The key should be a Spending purpose
        ScriptPurpose purpose = keys.get(0);
        assertInstanceOf(ScriptPurpose.Spending.class, purpose);

        System.out.println("Purpose: " + purpose);
        System.out.println("Redeemer: " + redeemerValue.prettyPrint());
    }

    /**
     * Step 3d: Verify reference input TxOut encoding (referenceScript field).
     * The reference UTxO has a script hash → encoded as Just(B scriptHash).
     */
    @Test
    void verifyReferenceInputEncoding_realV2Transaction() throws Exception {
        Transaction tx = Transaction.deserialize(HexFormat.of().parseHex(TX_CBOR));
        var allUtxos = buildAllUtxos();
        var converter = new CclTxConverter(tx, allUtxos, null, null, 8);
        TxInfo txInfo = converter.buildTxInfo();

        var refInputs = txInfo.referenceInputs();
        System.out.println("=== Reference Inputs ===");
        System.out.println("Count: " + refInputs.size());

        assertEquals(1, refInputs.size(), "Should have 1 reference input");

        TxInInfo refInput = refInputs.get(0);
        TxOut refTxOut = refInput.resolved();

        // Should have a reference script
        assertTrue(refTxOut.referenceScript().isPresent(),
                "Reference input should have a reference script");

        String expectedScriptHash = "b010c0888e93aa488d941ba4839136fceb9b9a9ec310a573299286d7";
        String actualScriptHash = HexFormat.of().formatHex(
                refTxOut.referenceScript().get().hash());
        assertEquals(expectedScriptHash, actualScriptHash,
                "Reference script hash should match");

        System.out.println("Reference script hash: " + actualScriptHash);

        // Verify V2 TxOut encoding has 4 fields
        PlutusData refTxOutData = V1V2ScriptContextBuilder.build(
                PlutusLanguage.PLUTUS_V2, txInfo,
                new ScriptPurpose.Spending(refInput.outRef()), converter);
        System.out.println("Reference input TxOut in context:");
        System.out.println(refTxOutData.prettyPrint());
    }

    /**
     * Step 3e: Verify inputs ordering.
     * Inputs must be sorted by (txHash, index) lexicographically.
     * 3b2352f4... < f6ab80d6... → script input first.
     */
    @Test
    void verifyInputOrdering_realV2Transaction() throws Exception {
        Transaction tx = Transaction.deserialize(HexFormat.of().parseHex(TX_CBOR));
        var allUtxos = buildAllUtxos();
        var converter = new CclTxConverter(tx, allUtxos, null, null, 8);
        TxInfo txInfo = converter.buildTxInfo();

        var inputs = txInfo.inputs();
        assertEquals(2, inputs.size(), "Should have 2 inputs");

        // First input should be 3b2352f4...#0 (script UTxO)
        String firstTxHash = HexFormat.of().formatHex(inputs.get(0).outRef().txId().hash());
        assertTrue(firstTxHash.startsWith("3b2352f4"),
                "First input should be 3b2352f4... but was " + firstTxHash);

        // Second input should be f6ab80d6...#1 (payer UTxO)
        String secondTxHash = HexFormat.of().formatHex(inputs.get(1).outRef().txId().hash());
        assertTrue(secondTxHash.startsWith("f6ab80d6"),
                "Second input should be f6ab80d6... but was " + secondTxHash);

        System.out.println("Input 0: " + firstTxHash + "#" + inputs.get(0).outRef().index());
        System.out.println("Input 1: " + secondTxHash + "#" + inputs.get(1).outRef().index());
    }

    /**
     * Step 3f: Verify the valid range interval encoding.
     * Both TTL and validity start are absent → (NegInf, PosInf).
     */
    @Test
    void verifyValidRange_realV2Transaction() throws Exception {
        Transaction tx = Transaction.deserialize(HexFormat.of().parseHex(TX_CBOR));
        var allUtxos = buildAllUtxos();
        var converter = new CclTxConverter(tx, allUtxos, null, null, 8);
        TxInfo txInfo = converter.buildTxInfo();

        Interval validRange = txInfo.validRange();
        System.out.println("=== Valid Range ===");
        System.out.println("From: " + validRange.from().boundType() + " (inclusive=" + validRange.from().isInclusive() + ")");
        System.out.println("To:   " + validRange.to().boundType() + " (inclusive=" + validRange.to().isInclusive() + ")");

        // Both absent → (NegInf, PosInf) with True closures
        assertInstanceOf(IntervalBoundType.NegInf.class, validRange.from().boundType());
        assertInstanceOf(IntervalBoundType.PosInf.class, validRange.to().boundType());
        assertTrue(validRange.from().isInclusive(), "Lower bound should be inclusive");
        assertTrue(validRange.to().isInclusive(), "Upper bound should be inclusive (PosInf)");

        // Verify PlutusData encoding
        PlutusData intervalData = validRange.toPlutusData();
        System.out.println("Interval PlutusData:");
        System.out.println(intervalData.prettyPrint());
    }

    /**
     * Evaluate with Java VM only (doesn't require Scalus).
     * Prints budgets for quick comparison.
     */
    @Test
    void javaVmOnly_realV2Transaction() throws Exception {
        Transaction tx = Transaction.deserialize(HexFormat.of().parseHex(TX_CBOR));
        var allUtxos = buildAllUtxos();
        var converter = new CclTxConverter(tx, allUtxos, null, null, 8);
        TxInfo txInfo = converter.buildTxInfo();

        var redeemer = tx.getWitnessSet().getRedeemers().getFirst();
        ScriptPurpose purpose = converter.redeemerToScriptPurpose(redeemer);
        PlutusData redeemerData = PlutusDataAdapter.fromClientLib(redeemer.getData());

        PlutusData scriptContextData = V1V2ScriptContextBuilder.build(
                PlutusLanguage.PLUTUS_V2, txInfo, purpose, converter);

        PlutusData datumData = resolveDatum(purpose, txInfo);

        List<PlutusData> args = List.of(datumData, redeemerData, scriptContextData);

        EvalResult result = javaProvider.evaluateWithArgs(
                sumProgram, PlutusLanguage.PLUTUS_V2, args, null);
        assertTrue(result.isSuccess(), "Java VM should succeed: " + result);

        ExBudget budget = result.budgetConsumed();
        System.out.println("=== Java VM Budget ===");
        System.out.println("Java VM:   mem=" + budget.memoryUnits() + ", cpu=" + budget.cpuSteps());
        System.out.println("Node:      mem=" + NODE_MEM + ", cpu=" + NODE_CPU);
        System.out.println("Delta:     mem=" + (budget.memoryUnits() - NODE_MEM)
                + ", cpu=" + (budget.cpuSteps() - NODE_CPU));
    }

    /**
     * Step 4: Compare JuLC's ScriptContext with Scalus's ScriptContext.
     * <p>
     * Uses Scalus's Interop to build the V2 ScriptContext from the same transaction,
     * then compares the PlutusData trees byte-for-byte via CBOR serialization.
     */
    @Test
    void compareWithScalusScriptContext() throws Exception {
        Transaction tx = Transaction.deserialize(HexFormat.of().parseHex(TX_CBOR));
        var allUtxos = buildAllUtxos();

        // --- Build JuLC ScriptContext ---
        var converter = new CclTxConverter(tx, allUtxos, null, null, 8);
        TxInfo txInfo = converter.buildTxInfo();
        var redeemer = tx.getWitnessSet().getRedeemers().getFirst();
        ScriptPurpose purpose = converter.redeemerToScriptPurpose(redeemer);
        PlutusData julcCtx = V1V2ScriptContextBuilder.build(
                PlutusLanguage.PLUTUS_V2, txInfo, purpose, converter);

        // Convert JuLC PlutusData → CCL PlutusData → CBOR bytes
        var julcCclData = PlutusDataAdapter.toClientLib(julcCtx);
        byte[] julcCborBytes = com.bloxbean.cardano.client.common.cbor.CborSerializationUtil
                .serialize(julcCclData.serialize());

        // --- Build Scalus ScriptContext ---
        // Build the UTxO map (TransactionInput → TransactionOutput)
        var utxoMap = new java.util.HashMap<
                com.bloxbean.cardano.client.transaction.spec.TransactionInput,
                com.bloxbean.cardano.client.transaction.spec.TransactionOutput>();

        // Script UTxO: inline datum = 0x08 (IntData 8)
        var scriptInput = new com.bloxbean.cardano.client.transaction.spec.TransactionInput(
                "3b2352f450793b11e5d03c5b764c32903a9b3e91dc0dee578cf1ae4b5d591737", 0);
        var scriptDatum = com.bloxbean.cardano.client.plutus.spec.PlutusData.deserialize(
                HexFormat.of().parseHex("08"));
        var scriptOutput = com.bloxbean.cardano.client.transaction.spec.TransactionOutput.builder()
                .address("addr_test1wzcppsyg36f65jydjsd6fqu3xm7whxu6nmp3pftn9xfgd4ckah4da")
                .value(com.bloxbean.cardano.client.transaction.spec.Value.builder()
                        .coin(BigInteger.valueOf(4_000_000)).build())
                .inlineDatum(scriptDatum)
                .build();
        utxoMap.put(scriptInput, scriptOutput);

        // Payer UTxO
        var payerInput = new com.bloxbean.cardano.client.transaction.spec.TransactionInput(
                "f6ab80d6ed4a2935539c104a16c0d0c136a73c85f3374de83c64e9c37cc94773", 1);
        var payerOutput = com.bloxbean.cardano.client.transaction.spec.TransactionOutput.builder()
                .address("addr_test1qqxnp3khzm7kcj9t23hskehat7428ghsenk0pfew4rqy5v9frnmht7uwrl073q4jvq20z82kh4rksyns540azhndqexqpvhgqr")
                .value(com.bloxbean.cardano.client.transaction.spec.Value.builder()
                        .coin(BigInteger.valueOf(3_263_520_910L)).build())
                .build();
        utxoMap.put(payerInput, payerOutput);

        // Reference script UTxO — include scriptRef (the V2 sum script CBOR)
        var refInput = new com.bloxbean.cardano.client.transaction.spec.TransactionInput(
                "73518d464dbf639e5a98330dc198efb981c579a10101e21538c64518cbc60c47", 0);
        // Build script ref bytes: the CBOR encoding of [2, script_bytes] for PlutusV2
        byte[] scriptCborBytes = HexFormat.of().parseHex(V2_SUM_SCRIPT_CBOR);
        // CCL uses PlutusV2Script for this
        var v2Script = com.bloxbean.cardano.client.plutus.spec.PlutusV2Script.builder()
                .cborHex(V2_SUM_SCRIPT_CBOR).build();
        byte[] scriptRefBytes = v2Script.scriptRefBytes();
        var refOutput = com.bloxbean.cardano.client.transaction.spec.TransactionOutput.builder()
                .address("addr_test1qqwpl7h3g84mhr36wpetk904p7fchx2vst0z696lxk8ujsjyruqwmlsm344gfux3nsj6njyzj3ppvrqtt36cp9xyydzqzumz82")
                .value(com.bloxbean.cardano.client.transaction.spec.Value.builder()
                        .coin(BigInteger.valueOf(9_464_760)).build())
                .scriptRef(scriptRefBytes)
                .build();
        utxoMap.put(refInput, refOutput);

        // Convert Java Map to Scala immutable Map
        var scalaMutableMap = scala.jdk.javaapi.CollectionConverters.asScala(utxoMap);
        @SuppressWarnings("unchecked")
        var scalaMap = (scala.collection.immutable.Map<
                com.bloxbean.cardano.client.transaction.spec.TransactionInput,
                com.bloxbean.cardano.client.transaction.spec.TransactionOutput>)
                scala.collection.immutable.Map$.MODULE$.from(scalaMutableMap);

        // No slot config (no TTL/validity start) — use identity slot config
        var slotConfig = new scalus.cardano.ledger.SlotConfig(0, 0, 1);

        // Ensure witness set has non-null PlutusData list (Scalus expects it)
        if (tx.getWitnessSet().getPlutusDataList() == null) {
            tx.getWitnessSet().setPlutusDataList(List.of());
        }

        // Build Scalus V2 ScriptContext
        var scalusCtx = scalus.bloxbean.Interop$.MODULE$.getScriptContextV2(
                redeemer, tx, scalaMap, slotConfig, 8);

        // Convert Scalus ScriptContext to Data
        var toData = scalus.cardano.onchain.plutus.v2.ScriptContext$.MODULE$.given_ToData_ScriptContext();
        scalus.uplc.builtin.Data scalusData = toData.apply(scalusCtx);

        // Convert to CCL PlutusData
        com.bloxbean.cardano.client.plutus.spec.PlutusData scalusCclData =
                scalus.bloxbean.Interop$.MODULE$.toPlutusData(scalusData);
        byte[] scalusCborBytes = com.bloxbean.cardano.client.common.cbor.CborSerializationUtil
                .serialize(scalusCclData.serialize());

        // --- Compare CBOR bytes ---
        String julcHex = HexFormat.of().formatHex(julcCborBytes);
        String scalusHex = HexFormat.of().formatHex(scalusCborBytes);

        System.out.println("=== ScriptContext CBOR Comparison ===");
        System.out.println("JuLC  CBOR length: " + julcCborBytes.length);
        System.out.println("Scalus CBOR length: " + scalusCborBytes.length);
        System.out.println("Match: " + julcHex.equals(scalusHex));

        if (!julcHex.equals(scalusHex)) {
            // Find first difference
            int minLen = Math.min(julcHex.length(), scalusHex.length());
            for (int i = 0; i < minLen; i++) {
                if (julcHex.charAt(i) != scalusHex.charAt(i)) {
                    int start = Math.max(0, i - 20);
                    int end = Math.min(minLen, i + 20);
                    System.out.println("First difference at byte offset " + (i / 2));
                    System.out.println("JuLC:   ..." + julcHex.substring(start, end) + "...");
                    System.out.println("Scalus: ..." + scalusHex.substring(start, end) + "...");
                    break;
                }
            }

            // Also compare the pretty-printed PlutusData
            PlutusData scalusJulcData = PlutusDataAdapter.fromClientLib(scalusCclData);
            System.out.println("\n=== JuLC ScriptContext ===");
            System.out.println(julcCtx.prettyPrint());
            System.out.println("\n=== Scalus ScriptContext ===");
            System.out.println(scalusJulcData.prettyPrint());
        }

        // --- Evaluate with Scalus's ScriptContext to get node-matching budget ---
        PlutusData datumData = resolveDatum(purpose, txInfo);
        PlutusData redeemerData = PlutusDataAdapter.fromClientLib(redeemer.getData());

        // Convert Scalus ScriptContext to JuLC PlutusData for VM evaluation
        PlutusData scalusJulcCtx = PlutusDataAdapter.fromClientLib(scalusCclData);
        List<PlutusData> scalusArgs = List.of(datumData, redeemerData, scalusJulcCtx);

        EvalResult scalusCtxResult = javaProvider.evaluateWithArgs(
                sumProgram, PlutusLanguage.PLUTUS_V2, scalusArgs, null);
        assertTrue(scalusCtxResult.isSuccess(),
                "Evaluation with Scalus context should succeed: " + scalusCtxResult);

        ExBudget scalusCtxBudget = scalusCtxResult.budgetConsumed();
        System.out.println("\n=== Budget with Scalus ScriptContext ===");
        System.out.println("Scalus ctx: mem=" + scalusCtxBudget.memoryUnits()
                + ", cpu=" + scalusCtxBudget.cpuSteps());
        System.out.println("Node:       mem=" + NODE_MEM + ", cpu=" + NODE_CPU);
        System.out.println("Delta:      mem=" + (scalusCtxBudget.memoryUnits() - NODE_MEM)
                + ", cpu=" + (scalusCtxBudget.cpuSteps() - NODE_CPU));

        // --- Also evaluate with JuLC ScriptContext for comparison ---
        List<PlutusData> julcArgs = List.of(datumData, redeemerData, julcCtx);
        EvalResult julcResult = javaProvider.evaluateWithArgs(
                sumProgram, PlutusLanguage.PLUTUS_V2, julcArgs, null);
        ExBudget julcBudget = julcResult.budgetConsumed();
        System.out.println("\nJuLC ctx:   mem=" + julcBudget.memoryUnits()
                + ", cpu=" + julcBudget.cpuSteps());
        System.out.println("Difference: mem=" + (julcBudget.memoryUnits() - scalusCtxBudget.memoryUnits())
                + ", cpu=" + (julcBudget.cpuSteps() - scalusCtxBudget.cpuSteps()));

        assertEquals(julcHex, scalusHex,
                "JuLC and Scalus ScriptContext CBOR should match");
    }

    /**
     * Compare JuLC and Scalus ScriptContext for a synthetic V2 minting transaction.
     * <p>
     * The existing {@link #compareWithScalusScriptContext()} only exercises the empty mint case.
     * This test verifies that the {@code encodeMintValue()} zero-ADA-prepend logic produces
     * CBOR output identical to Scalus when actual minting tokens are present.
     */
    @Test
    void compareWithScalusScriptContext_withMinting() throws Exception {
        // --- Build a synthetic V2 minting transaction ---
        String policyIdHex = "aa".repeat(28);
        String tokenNameHex = "0x746f6b656e"; // "token" in ASCII hex, 0x prefix for CCL
        BigInteger mintQuantity = BigInteger.valueOf(100);

        String inputTxHash = "bb".repeat(32);
        String scriptAddr = "addr_test1wzcppsyg36f65jydjsd6fqu3xm7whxu6nmp3pftn9xfgd4ckah4da";

        // Build mint field: 1 policy + 1 token
        var asset = new Asset(tokenNameHex, mintQuantity);
        var multiAsset = new MultiAsset();
        multiAsset.setPolicyId(policyIdHex);
        multiAsset.setAssets(List.of(asset));

        // Build Mint redeemer at index 0
        var redeemer = Redeemer.builder()
                .tag(RedeemerTag.Mint)
                .index(BigInteger.ZERO)
                .data(new BigIntPlutusData(BigInteger.ZERO))
                .exUnits(ExUnits.builder()
                        .mem(BigInteger.valueOf(14_000_000))
                        .steps(BigInteger.valueOf(10_000_000_000L))
                        .build())
                .build();

        var tx = Transaction.builder()
                .body(TransactionBody.builder()
                        .inputs(List.of(new TransactionInput(inputTxHash, 0)))
                        .outputs(List.of(
                                TransactionOutput.builder()
                                        .address(scriptAddr)
                                        .value(com.bloxbean.cardano.client.transaction.spec.Value.builder()
                                                .coin(BigInteger.valueOf(2_000_000)).build())
                                        .build()))
                        .fee(BigInteger.valueOf(200_000))
                        .mint(List.of(multiAsset))
                        .build())
                .witnessSet(TransactionWitnessSet.builder()
                        .redeemers(List.of(redeemer))
                        .build())
                .build();

        // Build UTxO for the input
        Utxo inputUtxo = Utxo.builder()
                .txHash(inputTxHash)
                .outputIndex(0)
                .address(scriptAddr)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(5_000_000))))
                .build();
        var allUtxos = Set.of(inputUtxo);

        // --- Build JuLC ScriptContext ---
        var converter = new CclTxConverter(tx, allUtxos, null, null, 8);
        TxInfo txInfo = converter.buildTxInfo();
        ScriptPurpose purpose = converter.redeemerToScriptPurpose(redeemer);
        PlutusData julcCtx = V1V2ScriptContextBuilder.build(
                PlutusLanguage.PLUTUS_V2, txInfo, purpose, converter);

        // Convert JuLC PlutusData → CCL PlutusData → CBOR bytes
        var julcCclData = PlutusDataAdapter.toClientLib(julcCtx);
        byte[] julcCborBytes = com.bloxbean.cardano.client.common.cbor.CborSerializationUtil
                .serialize(julcCclData.serialize());

        // --- Build Scalus ScriptContext ---
        var utxoMap = new java.util.HashMap<TransactionInput, TransactionOutput>();
        var sInput = new TransactionInput(inputTxHash, 0);
        var sOutput = TransactionOutput.builder()
                .address(scriptAddr)
                .value(com.bloxbean.cardano.client.transaction.spec.Value.builder()
                        .coin(BigInteger.valueOf(5_000_000)).build())
                .build();
        utxoMap.put(sInput, sOutput);

        // Convert Java Map to Scala immutable Map
        var scalaMutableMap = scala.jdk.javaapi.CollectionConverters.asScala(utxoMap);
        @SuppressWarnings("unchecked")
        var scalaMap = (scala.collection.immutable.Map<TransactionInput, TransactionOutput>)
                scala.collection.immutable.Map$.MODULE$.from(scalaMutableMap);

        var slotConfig = new scalus.cardano.ledger.SlotConfig(0, 0, 1);

        // Ensure witness set has non-null PlutusData list (Scalus expects it)
        if (tx.getWitnessSet().getPlutusDataList() == null) {
            tx.getWitnessSet().setPlutusDataList(List.of());
        }

        // Build Scalus V2 ScriptContext
        var scalusCtx = scalus.bloxbean.Interop$.MODULE$.getScriptContextV2(
                redeemer, tx, scalaMap, slotConfig, 8);

        // Convert Scalus ScriptContext to Data → CCL PlutusData → CBOR bytes
        var toData = scalus.cardano.onchain.plutus.v2.ScriptContext$.MODULE$.given_ToData_ScriptContext();
        scalus.uplc.builtin.Data scalusData = toData.apply(scalusCtx);
        com.bloxbean.cardano.client.plutus.spec.PlutusData scalusCclData =
                scalus.bloxbean.Interop$.MODULE$.toPlutusData(scalusData);
        byte[] scalusCborBytes = com.bloxbean.cardano.client.common.cbor.CborSerializationUtil
                .serialize(scalusCclData.serialize());

        // --- Compare CBOR bytes ---
        String julcHex = HexFormat.of().formatHex(julcCborBytes);
        String scalusHex = HexFormat.of().formatHex(scalusCborBytes);

        System.out.println("=== Minting ScriptContext CBOR Comparison ===");
        System.out.println("JuLC  CBOR length: " + julcCborBytes.length);
        System.out.println("Scalus CBOR length: " + scalusCborBytes.length);
        System.out.println("Match: " + julcHex.equals(scalusHex));

        if (!julcHex.equals(scalusHex)) {
            int minLen = Math.min(julcHex.length(), scalusHex.length());
            for (int i = 0; i < minLen; i++) {
                if (julcHex.charAt(i) != scalusHex.charAt(i)) {
                    int start = Math.max(0, i - 20);
                    int end = Math.min(minLen, i + 20);
                    System.out.println("First difference at byte offset " + (i / 2));
                    System.out.println("JuLC:   ..." + julcHex.substring(start, end) + "...");
                    System.out.println("Scalus: ..." + scalusHex.substring(start, end) + "...");
                    break;
                }
            }

            PlutusData scalusJulcData = PlutusDataAdapter.fromClientLib(scalusCclData);
            System.out.println("\n=== JuLC ScriptContext ===");
            System.out.println(julcCtx.prettyPrint());
            System.out.println("\n=== Scalus ScriptContext ===");
            System.out.println(scalusJulcData.prettyPrint());
        }

        // --- Verify the mint field structure ---
        // V2 TxInfo: field index 4 = mint
        var txInfoData = (PlutusData.ConstrData) ((PlutusData.ConstrData) julcCtx).fields().get(0);
        var mintData = (PlutusData.MapData) txInfoData.fields().get(4);

        System.out.println("\n=== Mint Field Structure ===");
        System.out.println(mintData.prettyPrint());

        // Should have 2 entries: zero ADA entry + the minted token policy
        assertEquals(2, mintData.entries().size(),
                "Mint should have 2 entries: zero ADA prepend + minted token policy");

        // Entry 0: B"" → Map { B"" → I 0 } (zero ADA)
        var adaEntry = mintData.entries().get(0);
        assertInstanceOf(PlutusData.BytesData.class, adaEntry.key());
        assertEquals(0, ((PlutusData.BytesData) adaEntry.key()).value().length,
                "First entry key should be empty bytes (ADA policy)");
        var adaInner = (PlutusData.MapData) adaEntry.value();
        assertEquals(1, adaInner.entries().size());
        var adaTokenEntry = adaInner.entries().getFirst();
        assertEquals(0, ((PlutusData.BytesData) adaTokenEntry.key()).value().length,
                "ADA token name should be empty bytes");
        assertEquals(BigInteger.ZERO, ((PlutusData.IntData) adaTokenEntry.value()).value(),
                "ADA amount should be 0");

        // Entry 1: B policyId → Map { B tokenName → I quantity }
        var mintEntry = mintData.entries().get(1);
        assertInstanceOf(PlutusData.BytesData.class, mintEntry.key());
        assertArrayEquals(HexFormat.of().parseHex(policyIdHex),
                ((PlutusData.BytesData) mintEntry.key()).value(),
                "Second entry key should be the minted policy ID");
        var mintInner = (PlutusData.MapData) mintEntry.value();
        assertEquals(1, mintInner.entries().size());
        var tokenEntry = mintInner.entries().getFirst();
        assertArrayEquals(HexFormat.of().parseHex(tokenNameHex.substring(2)),
                ((PlutusData.BytesData) tokenEntry.key()).value(),
                "Token name should match");
        assertEquals(mintQuantity, ((PlutusData.IntData) tokenEntry.value()).value(),
                "Mint quantity should match");

        // Final byte-for-byte assertion
        assertEquals(julcHex, scalusHex,
                "JuLC and Scalus ScriptContext CBOR should match for minting tx");
    }

    /**
     * Resolve the datum for a Spending purpose from TxInfo.
     */
    private PlutusData resolveDatum(ScriptPurpose purpose, TxInfo txInfo) {
        if (purpose instanceof ScriptPurpose.Spending(var txOutRef)) {
            for (int i = 0; i < txInfo.inputs().size(); i++) {
                TxInInfo input = txInfo.inputs().get(i);
                if (input.outRef().equals(txOutRef)) {
                    OutputDatum od = input.resolved().datum();
                    if (od instanceof OutputDatum.OutputDatumInline inlineDatum) {
                        return inlineDatum.datum();
                    } else if (od instanceof OutputDatum.OutputDatumHash datumHash) {
                        PlutusData d = txInfo.datums().get(datumHash.hash());
                        if (d != null) return d;
                    }
                    break;
                }
            }
        }
        return new PlutusData.ConstrData(0, List.of()); // fallback: unit
    }
}
