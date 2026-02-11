package com.bloxbean.cardano.julc.e2e;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.core.Program;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end spending test: lock ADA to a script address and unlock it.
 * <p>
 * Uses a simple "always true" validator that accepts any redeemer.
 * Requires Yaci Devkit running locally.
 * <p>
 * Run with: ./gradlew :plutus-e2e-tests:test -Pe2e
 */
class SpendingE2ETest extends E2ETestBase {

    static final String ALWAYS_TRUE_VALIDATOR = """
            @Validator
            class AlwaysTrue {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    return true;
                }
            }
            """;

    @Test
    void lockAndUnlockAda() throws Exception {
        // 1. Compile the validator
        Program program = compile(ALWAYS_TRUE_VALIDATOR);
        PlutusV3Script script = JulcScriptAdapter.fromProgram(program);

        // 2. Derive the script address
        String scriptAddress = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();
        assertNotNull(scriptAddress);
        System.out.println("Script address: " + scriptAddress);

        // 3. Lock: send 5 ADA to the script address with an inline datum
        var datum = BigIntPlutusData.of(42);
        var lockTx = new Tx()
                .payToContract(scriptAddress, Amount.ada(5), datum)
                .from(testAccount.baseAddress());

        var lockResult = quickTxBuilder.compose(lockTx)
                .withSigner(SignerProviders.signerFrom(testAccount))
                .complete();

        assertTrue(lockResult.isSuccessful(), "Lock tx should succeed: " + lockResult);
        String lockTxHash = lockResult.getValue();
        assertNotNull(lockTxHash);
        System.out.println("Lock tx: " + lockTxHash);

        // 4. Wait for lock tx to be confirmed
        waitForConfirmation(lockTxHash);

        // 5. Find the script UTXO
        var utxoResult = backendService.getUtxoService().getUtxos(scriptAddress, 100, 1);
        assertTrue(utxoResult.isSuccessful(), "Should find UTXOs at script address");
        assertFalse(utxoResult.getValue().isEmpty(), "Should have at least one UTXO");

        Utxo scriptUtxo = utxoResult.getValue().stream()
                .filter(u -> u.getTxHash().equals(lockTxHash))
                .findFirst()
                .orElseGet(() -> utxoResult.getValue().get(0));

        System.out.println("Script UTXO: " + scriptUtxo.getTxHash() + "#" + scriptUtxo.getOutputIndex());

        // 6. Unlock: collect from the script UTXO
        var redeemer = BigIntPlutusData.of(0);
        var unlockTx = new ScriptTx()
                .collectFrom(scriptUtxo, redeemer)
                .payToAddress(testAccount.baseAddress(), Amount.ada(4))
                .attachSpendingValidator(script);

        var unlockResult = quickTxBuilder.compose(unlockTx)
                .withSigner(SignerProviders.signerFrom(testAccount))
                .feePayer(testAccount.baseAddress())
                .collateralPayer(testAccount.baseAddress())
                .complete();

        assertTrue(unlockResult.isSuccessful(), "Unlock tx should succeed: " + unlockResult);
        String unlockTxHash = unlockResult.getValue();
        assertNotNull(unlockTxHash);
        System.out.println("Unlock tx: " + unlockTxHash);

        // 7. Wait for unlock tx to be confirmed
        waitForConfirmation(unlockTxHash);

        // 8. Verify the script UTXO is spent
        var afterUtxos = backendService.getUtxoService().getUtxos(scriptAddress, 100, 1);
        boolean originalSpent = afterUtxos.getValue() == null
                || afterUtxos.getValue().stream()
                .noneMatch(u -> u.getTxHash().equals(lockTxHash)
                        && u.getOutputIndex() == scriptUtxo.getOutputIndex());
        assertTrue(originalSpent, "Original script UTXO should be spent");
    }
}
