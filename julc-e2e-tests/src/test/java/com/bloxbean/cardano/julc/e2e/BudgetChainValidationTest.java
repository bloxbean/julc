package com.bloxbean.cardano.julc.e2e;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.util.CostModelUtil;
import com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultScriptSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.Language;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.clientlib.eval.JulcTransactionEvaluator;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.vm.java.cost.CostModelParser;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests validating JulcTransactionEvaluator budget accuracy
 * against real Cardano node (Yaci DevKit) protocol parameters.
 * <p>
 * Run with: ./gradlew :julc-e2e-tests:test -Pe2e
 */
class BudgetChainValidationTest extends E2ETestBase {

    static final String ALWAYS_TRUE_VALIDATOR = """
            @Validator
            class AlwaysTrue {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    return true;
                }
            }
            """;

    /**
     * Step 1: Verify the production path — JulcTransactionEvaluator with real chain
     * protocol parameters produces budgets accepted by the Cardano node.
     * <p>
     * If the unlock transaction confirms on-chain, it proves the JuLC-computed
     * ExUnits were correct (the node validated them against the Haskell CEK machine).
     */
    @Test
    void julcEvaluator_acceptedOnChain() throws Exception {
        // 1. Compile the validator
        Program program = compile(ALWAYS_TRUE_VALIDATOR);
        PlutusV3Script script = JulcScriptAdapter.fromProgram(program);
        String scriptAddress = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();
        System.out.println("Script address: " + scriptAddress);

        // 2. Lock: send 5 ADA to the script address with an inline datum
        var datum = BigIntPlutusData.of(42);
        var lockTx = new Tx()
                .payToContract(scriptAddress, Amount.ada(5), datum)
                .from(testAccount.baseAddress());

        var lockResult = quickTxBuilder.compose(lockTx)
                .withSigner(SignerProviders.signerFrom(testAccount))
                .complete();

        assertTrue(lockResult.isSuccessful(), "Lock tx should succeed: " + lockResult);
        String lockTxHash = lockResult.getValue();
        System.out.println("Lock tx: " + lockTxHash);
        waitForConfirmation(lockTxHash);

        // 3. Find the script UTXO
        var utxoResult = backendService.getUtxoService().getUtxos(scriptAddress, 100, 1);
        assertTrue(utxoResult.isSuccessful(), "Should find UTXOs at script address");
        assertFalse(utxoResult.getValue().isEmpty(), "Should have at least one UTXO");

        Utxo scriptUtxo = utxoResult.getValue().stream()
                .filter(u -> u.getTxHash().equals(lockTxHash))
                .findFirst()
                .orElseGet(() -> utxoResult.getValue().get(0));
        System.out.println("Script UTXO: " + scriptUtxo.getTxHash() + "#" + scriptUtxo.getOutputIndex());

        // 4. Create JulcTransactionEvaluator with real chain params
        var julcEvaluator = new JulcTransactionEvaluator(
                new DefaultUtxoSupplier(backendService.getUtxoService()),
                new DefaultProtocolParamsSupplier(backendService.getEpochService()),
                new DefaultScriptSupplier(backendService.getScriptService()));

        // 5. Unlock using JulcTransactionEvaluator for budget computation
        var redeemer = BigIntPlutusData.of(0);
        var unlockTx = new ScriptTx()
                .collectFrom(scriptUtxo, redeemer)
                .payToAddress(testAccount.baseAddress(), Amount.ada(4))
                .attachSpendingValidator(script);

        var unlockResult = quickTxBuilder.compose(unlockTx)
                .withTxEvaluator(julcEvaluator)
                .withSigner(SignerProviders.signerFrom(testAccount))
                .feePayer(testAccount.baseAddress())
                .collateralPayer(testAccount.baseAddress())
                .complete();

        assertTrue(unlockResult.isSuccessful(),
                "Unlock with JuLC evaluator should succeed: " + unlockResult);
        String unlockTxHash = unlockResult.getValue();
        System.out.println("Unlock tx (JuLC evaluated): " + unlockTxHash);

        // 6. Wait for confirmation — proves the Cardano node accepted JuLC's budget
        waitForConfirmation(unlockTxHash);
        System.out.println("Transaction confirmed! JuLC budget accepted by Cardano node.");
    }

    /**
     * Step 2: Diagnostic — compare DefaultCostModel hardcoded params against
     * the chain's actual protocol parameters.
     * <p>
     * Prints a diff of every parameter that differs, with its index and values.
     * This identifies whether DefaultCostModel.java needs updating.
     */
    @Test
    void chainCostModelDiagnostic() throws Exception {
        // Get chain params
        var params = new DefaultProtocolParamsSupplier(backendService.getEpochService())
                .getProtocolParams();

        // Extract V3 cost model from chain
        var chainModel = CostModelUtil.getCostModelFromProtocolParams(params, Language.PLUTUS_V3);
        assertTrue(chainModel.isPresent(), "V3 cost model should be present in chain params");

        long[] chainParams = chainModel.get().getCosts();
        long[] defaultParams = CostModelParser.defaultToFlatArray();

        System.out.println("=== Cost Model Parameter Comparison ===");
        System.out.println("Chain params count: " + chainParams.length);
        System.out.println("Default params count: " + defaultParams.length);

        // Compare element-by-element
        int minLen = Math.min(defaultParams.length, chainParams.length);
        int diffCount = 0;

        for (int i = 0; i < minLen; i++) {
            if (defaultParams[i] != chainParams[i]) {
                System.out.printf("  DIFF [%3d] default=%-15d chain=%-15d delta=%d%n",
                        i, defaultParams[i], chainParams[i], chainParams[i] - defaultParams[i]);
                diffCount++;
            }
        }

        // Report any extra params in chain that aren't in defaults
        if (chainParams.length > defaultParams.length) {
            System.out.println("Chain has " + (chainParams.length - defaultParams.length)
                    + " extra params beyond default array:");
            for (int i = defaultParams.length; i < chainParams.length; i++) {
                System.out.printf("  EXTRA [%3d] chain=%d%n", i, chainParams[i]);
            }
        }

        if (diffCount == 0 && chainParams.length == defaultParams.length) {
            System.out.println("DefaultCostModel matches chain params EXACTLY.");
        } else {
            System.out.println("Found " + diffCount + " differing parameters out of " + minLen + ".");
        }

        // Verify the params can be parsed without error
        assertDoesNotThrow(() -> CostModelParser.parse(chainParams),
                "Chain params should be parseable by CostModelParser");
    }
}
