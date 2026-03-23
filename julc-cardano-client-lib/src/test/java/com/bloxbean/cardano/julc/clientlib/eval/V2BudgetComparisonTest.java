package com.bloxbean.cardano.julc.clientlib.eval;

import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.ExBudget;
import com.bloxbean.cardano.julc.vm.JulcVm;
import com.bloxbean.cardano.julc.vm.JulcVmProvider;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import com.bloxbean.cardano.julc.vm.java.JavaVmProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-VM budget comparison test for V2 scripts.
 * <p>
 * Evaluates the same V2 script with the same PlutusData arguments through both
 * the Java VM and Scalus VM to determine whether budget discrepancies come from
 * the VM implementation or the ScriptContext construction.
 * <p>
 * If budgets differ → Java CEK machine has a cost computation bug.
 * If budgets match → difference is in ScriptContext PlutusData tree construction.
 */
class V2BudgetComparisonTest {

    // PlutusTx-compiled V2 sum validator from cardano-client-lib examples.
    // Validates that datum + redeemer == expected sum (datum=8, redeemer=36).
    static final String V2_SUM_SCRIPT_CBOR =
            "5907a65907a3010000323322323232323232323232323232323322323232323222232325335323232333573466e1ccc07000d200000201e01d3333573466e1cd55cea80224000466442466002006004646464646464646464646464646666ae68cdc39aab9d500c480008cccccccccccc88888888888848cccccccccccc00403403002c02802402001c01801401000c008cd405c060d5d0a80619a80b80c1aba1500b33501701935742a014666aa036eb94068d5d0a804999aa80dbae501a35742a01066a02e0446ae85401cccd5406c08dd69aba150063232323333573466e1cd55cea801240004664424660020060046464646666ae68cdc39aab9d5002480008cc8848cc00400c008cd40b5d69aba15002302e357426ae8940088c98c80c0cd5ce01881801709aab9e5001137540026ae854008c8c8c8cccd5cd19b8735573aa004900011991091980080180119a816bad35742a004605c6ae84d5d1280111931901819ab9c03103002e135573ca00226ea8004d5d09aba2500223263202c33573805a05805426aae7940044dd50009aba1500533501775c6ae854010ccd5406c07c8004d5d0a801999aa80dbae200135742a00460426ae84d5d1280111931901419ab9c029028026135744a00226ae8940044d5d1280089aba25001135744a00226ae8940044d5d1280089aba25001135744a00226ae8940044d55cf280089baa00135742a00860226ae84d5d1280211931900d19ab9c01b01a018375a00a6eb4014405c4c98c805ccd5ce24810350543500017135573ca00226ea800448c88c008dd6000990009aa80b911999aab9f0012500a233500930043574200460066ae880080508c8c8cccd5cd19b8735573aa004900011991091980080180118061aba150023005357426ae8940088c98c8050cd5ce00a80a00909aab9e5001137540024646464646666ae68cdc39aab9d5004480008cccc888848cccc00401401000c008c8c8c8cccd5cd19b8735573aa0049000119910919800801801180a9aba1500233500f014357426ae8940088c98c8064cd5ce00d00c80b89aab9e5001137540026ae854010ccd54021d728039aba150033232323333573466e1d4005200423212223002004357426aae79400c8cccd5cd19b875002480088c84888c004010dd71aba135573ca00846666ae68cdc3a801a400042444006464c6403666ae7007006c06406005c4d55cea80089baa00135742a00466a016eb8d5d09aba2500223263201533573802c02a02626ae8940044d5d1280089aab9e500113754002266aa002eb9d6889119118011bab00132001355014223233335573e0044a010466a00e66442466002006004600c6aae754008c014d55cf280118021aba200301213574200222440042442446600200800624464646666ae68cdc3a800a40004642446004006600a6ae84d55cf280191999ab9a3370ea0049001109100091931900819ab9c01101000e00d135573aa00226ea80048c8c8cccd5cd19b875001480188c848888c010014c01cd5d09aab9e500323333573466e1d400920042321222230020053009357426aae7940108cccd5cd19b875003480088c848888c004014c01cd5d09aab9e500523333573466e1d40112000232122223003005375c6ae84d55cf280311931900819ab9c01101000e00d00c00b135573aa00226ea80048c8c8cccd5cd19b8735573aa004900011991091980080180118029aba15002375a6ae84d5d1280111931900619ab9c00d00c00a135573ca00226ea80048c8cccd5cd19b8735573aa002900011bae357426aae7940088c98c8028cd5ce00580500409baa001232323232323333573466e1d4005200c21222222200323333573466e1d4009200a21222222200423333573466e1d400d2008233221222222233001009008375c6ae854014dd69aba135744a00a46666ae68cdc3a8022400c4664424444444660040120106eb8d5d0a8039bae357426ae89401c8cccd5cd19b875005480108cc8848888888cc018024020c030d5d0a8049bae357426ae8940248cccd5cd19b875006480088c848888888c01c020c034d5d09aab9e500b23333573466e1d401d2000232122222223005008300e357426aae7940308c98c804ccd5ce00a00980880800780700680600589aab9d5004135573ca00626aae7940084d55cf280089baa0012323232323333573466e1d400520022333222122333001005004003375a6ae854010dd69aba15003375a6ae84d5d1280191999ab9a3370ea0049000119091180100198041aba135573ca00c464c6401866ae700340300280244d55cea80189aba25001135573ca00226ea80048c8c8cccd5cd19b875001480088c8488c00400cdd71aba135573ca00646666ae68cdc3a8012400046424460040066eb8d5d09aab9e500423263200933573801401200e00c26aae7540044dd500089119191999ab9a3370ea00290021091100091999ab9a3370ea00490011190911180180218031aba135573ca00846666ae68cdc3a801a400042444004464c6401466ae7002c02802001c0184d55cea80089baa0012323333573466e1d40052002200923333573466e1d40092000200923263200633573800e00c00800626aae74dd5000a4c240029210350543100320013550032225335333573466e1c0092000005004100113300333702004900119b80002001122002122001112323001001223300330020020011";

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
     * Step 1: Cross-VM comparison — evaluate V2 sum script with identical PlutusData
     * through both Java and Scalus VMs.
     * <p>
     * This test isolates whether budget differences come from the VM or from
     * ScriptContext construction.
     */
    @Test
    void crossVmComparison_v2SumScript_samePlutusData() {
        org.junit.jupiter.api.Assumptions.assumeTrue(hasScalus(), "Scalus provider not available");

        // Build V2 arguments: datum=IntData(8), redeemer=IntData(36)
        var datum = new PlutusData.IntData(BigInteger.valueOf(8));
        var redeemer = new PlutusData.IntData(BigInteger.valueOf(36));
        var scriptContext = buildMinimalV2SpendingContext();

        List<PlutusData> args = List.of(datum, redeemer, scriptContext);

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

        System.out.println("=== V2 Sum Script Cross-VM Budget Comparison ===");
        System.out.println("Java VM:   mem=" + javaBudget.memoryUnits()
                + ", steps=" + javaBudget.cpuSteps());
        System.out.println("Scalus VM: mem=" + scalusBudget.memoryUnits()
                + ", steps=" + scalusBudget.cpuSteps());
        System.out.println("Delta:     mem=" + (javaBudget.memoryUnits() - scalusBudget.memoryUnits())
                + ", steps=" + (javaBudget.cpuSteps() - scalusBudget.cpuSteps()));

        assertEquals(scalusBudget.cpuSteps(), javaBudget.cpuSteps(),
                "CPU budget mismatch between Java and Scalus VMs (same PlutusData input)");
        assertEquals(scalusBudget.memoryUnits(), javaBudget.memoryUnits(),
                "Memory budget mismatch between Java and Scalus VMs (same PlutusData input)");
    }

    /**
     * Cross-VM comparison with a simple V2 always-true script.
     * Verifies budget parity for the simplest possible V2 program.
     */
    @Test
    void crossVmComparison_v2AlwaysTrue_samePlutusData() {
        org.junit.jupiter.api.Assumptions.assumeTrue(hasScalus(), "Scalus provider not available");

        // Build a trivial V2 always-true script: \d r ctx -> ()
        Program alwaysTrueProgram = com.bloxbean.cardano.julc.core.Program.plutusV2(
                com.bloxbean.cardano.julc.core.Term.lam("d",
                        com.bloxbean.cardano.julc.core.Term.lam("r",
                                com.bloxbean.cardano.julc.core.Term.lam("ctx",
                                        com.bloxbean.cardano.julc.core.Term.const_(
                                                com.bloxbean.cardano.julc.core.Constant.unit())))));

        var datum = new PlutusData.IntData(BigInteger.valueOf(8));
        var redeemer = new PlutusData.IntData(BigInteger.valueOf(36));
        var scriptContext = buildMinimalV2SpendingContext();

        List<PlutusData> args = List.of(datum, redeemer, scriptContext);

        EvalResult javaResult = javaProvider.evaluateWithArgs(
                alwaysTrueProgram, PlutusLanguage.PLUTUS_V2, args, null);
        assertTrue(javaResult.isSuccess(), "Java VM should succeed");

        EvalResult scalusResult = scalusProvider.evaluateWithArgs(
                alwaysTrueProgram, PlutusLanguage.PLUTUS_V2, args, null);
        assertTrue(scalusResult.isSuccess(), "Scalus VM should succeed");

        ExBudget javaBudget = javaResult.budgetConsumed();
        ExBudget scalusBudget = scalusResult.budgetConsumed();

        System.out.println("=== V2 Always-True Cross-VM Budget Comparison ===");
        System.out.println("Java VM:   mem=" + javaBudget.memoryUnits()
                + ", steps=" + javaBudget.cpuSteps());
        System.out.println("Scalus VM: mem=" + scalusBudget.memoryUnits()
                + ", steps=" + scalusBudget.cpuSteps());

        assertEquals(scalusBudget.cpuSteps(), javaBudget.cpuSteps(),
                "CPU budget mismatch (V2 always-true)");
        assertEquals(scalusBudget.memoryUnits(), javaBudget.memoryUnits(),
                "Memory budget mismatch (V2 always-true)");
    }

    /**
     * Verify that the manually-built ScriptContext has a reasonable node count,
     * using the new countNodes() diagnostic utility.
     */
    @Test
    void scriptContextNodeCount() {
        PlutusData ctx = buildMinimalV2SpendingContext();
        int nodeCount = ctx.countNodes();
        System.out.println("Minimal V2 ScriptContext node count: " + nodeCount);
        assertTrue(nodeCount > 10, "ScriptContext should have > 10 nodes, got: " + nodeCount);
    }

    /**
     * Verify prettyPrint() produces readable output for the V2 ScriptContext.
     */
    @Test
    void scriptContextPrettyPrint() {
        PlutusData ctx = buildMinimalV2SpendingContext();
        String pretty = ctx.prettyPrint();
        System.out.println("=== V2 ScriptContext Pretty Print ===");
        System.out.println(pretty);
        assertFalse(pretty.isEmpty());
        assertTrue(pretty.contains("Constr 0"));
    }

    /**
     * Verify that TTL closure matches Scalus convention for V2 (PV 8).
     * TTL only (no lower bound) → closure is inclusive (true) for PV <= 8.
     * Both bounds → closure is always exclusive (false).
     */
    @Test
    void ttlClosure_v2_matchesScalusConvention() {
        // TTL-only (no lower bound), PV 8 → inclusive
        var dummyUtxos = java.util.Set.of(com.bloxbean.cardano.client.api.model.Utxo.builder()
                .txHash("aa".repeat(32))
                .outputIndex(0)
                .address("addr_test1qz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3jcu5d8ps7zex2k2xt3uqxgjqnnj83ws8lhrn648jjxtwq2ytjqp")
                .amount(List.of(com.bloxbean.cardano.client.api.model.Amount
                        .lovelace(java.math.BigInteger.valueOf(5_000_000))))
                .build());

        var txTtlOnly = com.bloxbean.cardano.client.transaction.spec.Transaction.builder()
                .body(com.bloxbean.cardano.client.transaction.spec.TransactionBody.builder()
                        .inputs(List.of(new com.bloxbean.cardano.client.transaction.spec.TransactionInput(
                                "aa".repeat(32), 0)))
                        .outputs(List.of())
                        .fee(java.math.BigInteger.valueOf(200_000))
                        .ttl(1000)
                        .build())
                .build();
        var converterTtlOnly = new CclTxConverter(txTtlOnly, dummyUtxos, null, null, 8);
        var infoTtlOnly = converterTtlOnly.buildTxInfo();
        assertTrue(infoTtlOnly.validRange().to().isInclusive(),
                "PV 8, TTL-only → upper bound should be inclusive (matches Scalus)");

        // Both bounds, PV 8 → exclusive
        var txBoth = com.bloxbean.cardano.client.transaction.spec.Transaction.builder()
                .body(com.bloxbean.cardano.client.transaction.spec.TransactionBody.builder()
                        .inputs(List.of(new com.bloxbean.cardano.client.transaction.spec.TransactionInput(
                                "aa".repeat(32), 0)))
                        .outputs(List.of())
                        .fee(java.math.BigInteger.valueOf(200_000))
                        .ttl(1000)
                        .validityStartInterval(500)
                        .build())
                .build();
        var converterBoth = new CclTxConverter(txBoth, dummyUtxos, null, null, 8);
        var infoBoth = converterBoth.buildTxInfo();
        assertFalse(infoBoth.validRange().to().isInclusive(),
                "PV 8, both bounds → upper bound should be exclusive (matches Scalus)");
    }

    /**
     * Cross-VM comparison with TTL set (finite upper bound).
     * Uses PV 8 semantics so TTL-only → inclusive closure.
     */
    @Test
    void crossVmComparison_v2SumScript_withTtl() {
        org.junit.jupiter.api.Assumptions.assumeTrue(hasScalus(), "Scalus provider not available");

        var datum = new PlutusData.IntData(BigInteger.valueOf(8));
        var redeemer = new PlutusData.IntData(BigInteger.valueOf(36));
        var scriptContext = buildV2SpendingContextWithTtl();

        List<PlutusData> args = List.of(datum, redeemer, scriptContext);

        EvalResult javaResult = javaProvider.evaluateWithArgs(
                sumProgram, PlutusLanguage.PLUTUS_V2, args, null);
        assertTrue(javaResult.isSuccess(), "Java VM should succeed with TTL context");

        EvalResult scalusResult = scalusProvider.evaluateWithArgs(
                sumProgram, PlutusLanguage.PLUTUS_V2, args, null);
        assertTrue(scalusResult.isSuccess(), "Scalus VM should succeed with TTL context");

        assertEquals(scalusResult.budgetConsumed().cpuSteps(), javaResult.budgetConsumed().cpuSteps(),
                "CPU budget mismatch with TTL context");
        assertEquals(scalusResult.budgetConsumed().memoryUnits(), javaResult.budgetConsumed().memoryUnits(),
                "Memory budget mismatch with TTL context");
    }

    /**
     * Build a V2 ScriptContext with a finite TTL (upper bound inclusive for PV 8).
     */
    private static PlutusData buildV2SpendingContextWithTtl() {
        byte[] txHashBytes = HexFormat.of().parseHex("aa".repeat(32));
        byte[] scriptHashBytes = HexFormat.of().parseHex("bb".repeat(28));
        byte[] signerHashBytes = HexFormat.of().parseHex("cc".repeat(28));

        var txId = PlutusData.constr(0, PlutusData.bytes(txHashBytes));
        var txOutRef = PlutusData.constr(0, txId, PlutusData.integer(0));
        var scriptCred = PlutusData.constr(1, PlutusData.bytes(scriptHashBytes));
        var noStaking = PlutusData.constr(1);
        var address = PlutusData.constr(0, scriptCred, noStaking);
        var value = PlutusData.map(
                new PlutusData.Pair(PlutusData.bytes(new byte[0]),
                        PlutusData.map(new PlutusData.Pair(
                                PlutusData.bytes(new byte[0]),
                                PlutusData.integer(5_000_000)))));
        var outputDatum = PlutusData.constr(2, PlutusData.integer(8));
        var noRefScript = PlutusData.constr(1);
        var txOut = PlutusData.constr(0, address, value, outputDatum, noRefScript);
        var txInInfo = PlutusData.constr(0, txOutRef, txOut);
        var fee = PlutusData.map(
                new PlutusData.Pair(PlutusData.bytes(new byte[0]),
                        PlutusData.map(new PlutusData.Pair(
                                PlutusData.bytes(new byte[0]),
                                PlutusData.integer(200_000)))));
        var emptyList = PlutusData.list();
        var emptyMap = PlutusData.map();

        // ValidRange with TTL=1000000 (PV 8, TTL-only → inclusive)
        var negInf = PlutusData.constr(0);  // NegInf
        var trueBool = PlutusData.constr(1); // True
        var lowerBound = PlutusData.constr(0, negInf, trueBool);
        var finite = PlutusData.constr(1, PlutusData.integer(1_000_000)); // Finite(1000000)
        // PV 8, TTL-only → closure is inclusive (True = Constr 1)
        var upperBound = PlutusData.constr(0, finite, trueBool);
        var validRange = PlutusData.constr(0, lowerBound, upperBound);

        var signerPkh = PlutusData.bytes(signerHashBytes);
        var purpose = PlutusData.constr(1, txOutRef);

        var txInfo = PlutusData.constr(0,
                PlutusData.list(txInInfo), emptyList, emptyList, fee, emptyMap,
                emptyList, emptyMap, validRange, PlutusData.list(signerPkh),
                emptyMap, emptyMap, txId);

        return PlutusData.constr(0, txInfo, purpose);
    }

    /**
     * Build a minimal V2 ScriptContext as raw PlutusData for testing.
     * V2 ScriptContext = Constr 0 [TxInfo, ScriptPurpose]
     */
    private static PlutusData buildMinimalV2SpendingContext() {
        byte[] txHashBytes = HexFormat.of().parseHex("aa".repeat(32));
        byte[] scriptHashBytes = HexFormat.of().parseHex("bb".repeat(28));
        byte[] signerHashBytes = HexFormat.of().parseHex("cc".repeat(28));

        // TxId = Constr 0 [B hash]
        var txId = PlutusData.constr(0, PlutusData.bytes(txHashBytes));
        // TxOutRef = Constr 0 [TxId, I index]
        var txOutRef = PlutusData.constr(0, txId, PlutusData.integer(0));
        // Address: Constr 0 [ScriptCredential(Constr 1 [B hash]), Nothing(Constr 1 [])]
        var scriptCred = PlutusData.constr(1, PlutusData.bytes(scriptHashBytes));
        var noStaking = PlutusData.constr(1);
        var address = PlutusData.constr(0, scriptCred, noStaking);
        // Value: Map { B"" -> Map { B"" -> I 5000000 } }
        var value = PlutusData.map(
                new PlutusData.Pair(
                        PlutusData.bytes(new byte[0]),
                        PlutusData.map(
                                new PlutusData.Pair(
                                        PlutusData.bytes(new byte[0]),
                                        PlutusData.integer(5_000_000)))));
        // OutputDatum.InlineDatum = Constr 2 [IntData(8)]
        var outputDatum = PlutusData.constr(2, PlutusData.integer(8));
        // Maybe ScriptHash = Nothing = Constr 1 []
        var noRefScript = PlutusData.constr(1);
        // V2 TxOut = Constr 0 [address, value, outputDatum, maybeRefScript]
        var txOut = PlutusData.constr(0, address, value, outputDatum, noRefScript);
        // TxInInfo = Constr 0 [TxOutRef, TxOut]
        var txInInfo = PlutusData.constr(0, txOutRef, txOut);
        // Fee value
        var fee = PlutusData.map(
                new PlutusData.Pair(
                        PlutusData.bytes(new byte[0]),
                        PlutusData.map(
                                new PlutusData.Pair(
                                        PlutusData.bytes(new byte[0]),
                                        PlutusData.integer(200_000)))));
        // Empty structures
        var emptyList = PlutusData.list();
        var emptyMap = PlutusData.map();
        // ValidRange = always: Interval(LowerBound(NegInf, True), UpperBound(PosInf, True))
        var negInf = PlutusData.constr(0); // NegInf
        var posInf = PlutusData.constr(2); // PosInf
        var trueBool = PlutusData.constr(1); // True
        var lowerBound = PlutusData.constr(0, negInf, trueBool);
        var upperBound = PlutusData.constr(0, posInf, trueBool);
        var validRange = PlutusData.constr(0, lowerBound, upperBound);
        // Signatories
        var signerPkh = PlutusData.bytes(signerHashBytes);
        // V2 ScriptPurpose.Spending = Constr 1 [txOutRef]
        var purpose = PlutusData.constr(1, txOutRef);

        // V2 TxInfo = Constr 0 [inputs, refInputs, outputs, fee, mint, dcert, wdrl,
        //   validRange, signatories, redeemers, data, id]
        var txInfo = PlutusData.constr(0,
                PlutusData.list(txInInfo),    // inputs
                emptyList,                     // referenceInputs
                emptyList,                     // outputs
                fee,                           // fee (Value)
                emptyMap,                      // mint (Value = empty map)
                emptyList,                     // dcert
                emptyMap,                      // wdrl
                validRange,                    // validRange
                PlutusData.list(signerPkh),   // signatories
                emptyMap,                      // redeemers
                emptyMap,                      // data (witness datums)
                txId                           // id
        );

        return PlutusData.constr(0, txInfo, purpose);
    }
}
