package com.bloxbean.cardano.plutus.plugintest;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E test for TimelockValidator.
 * <p>
 * Demonstrates a spending validator that checks whether the redeemer (a deadline)
 * falls within the transaction's validity interval (TxInfo.validRange).
 * <p>
 * Key concepts:
 * <ul>
 *   <li>{@code .validFrom(slot)} — sets tx validity lower bound (populates TxInfo.validRange)</li>
 *   <li>{@code .validTo(slot)} — sets tx validity upper bound</li>
 *   <li>Slot values are absolute slot numbers (use node API to convert POSIX time ↔ slots)</li>
 *   <li>On-chain: IntervalLib.contains(validRange, redeemer) checks if redeemer falls in range</li>
 * </ul>
 * <p>
 * Run: ./gradlew :plutus-plugin-test:test -Pe2e
 */
class TimelockValidatorE2ETest extends PluginTestBase {

    static final String TIMELOCK_VALIDATOR = """
            import com.bloxbean.cardano.plutus.core.PlutusData;

            @Validator
            class TimelockValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    PlutusData txInfo = ContextsLib.getTxInfo(ctx);
                    PlutusData validRange = ContextsLib.txInfoValidRange(txInfo);
                    return IntervalLib.contains(validRange, redeemer);
                }
            }
            """;

    @Test
    void lockAndUnlockWithTimeRange() throws Exception {
        // 1. Compile the spending validator
        PlutusV3Script timelockScript = compileScript(TIMELOCK_VALIDATOR);
        String scriptAddr = AddressProvider.getEntAddress(timelockScript, Networks.testnet()).toBech32();
        System.out.println("Script address: " + scriptAddr);

        // 2. Lock 10 ADA to the script address
        BigIntPlutusData datum = BigIntPlutusData.of(0);

        Tx lockTx = new Tx()
                .payToContract(scriptAddr, Amount.ada(10), datum)
                .from(testAccount.baseAddress());

        var lockResult = quickTxBuilder.compose(lockTx)
                .withSigner(SignerProviders.signerFrom(testAccount))
                .complete();

        assertTrue(lockResult.isSuccessful(), "Lock tx should succeed: " + lockResult);
        String lockTxHash = lockResult.getValue();
        System.out.println("Lock tx: " + lockTxHash);
        waitForConfirmation(lockTxHash);

        // 3. Find the script UTXO
        var scriptUtxo = findUtxo(scriptAddr, lockTxHash);

        // 4. Get current slot to construct a validity range
        var latestBlock = backendService.getBlockService().getLatestBlock();
        assertTrue(latestBlock.isSuccessful(), "Should get latest block");
        long currentSlot = latestBlock.getValue().getSlot();

        // 5. Set redeemer = a time point within the validity range
        //    The validity interval [currentSlot - 100, currentSlot + 200] will be
        //    translated to TxInfo.validRange on-chain.
        //    The redeemer is the "deadline" that must fall within this range.
        long deadline = currentSlot;
        BigIntPlutusData redeemer = BigIntPlutusData.of(deadline);

        // 6. Unlock with validity interval
        ScriptTx unlockTx = new ScriptTx()
                .collectFrom(scriptUtxo, redeemer)
                .payToAddress(testAccount.baseAddress(), Amount.ada(9))
                .attachSpendingValidator(timelockScript);

        var unlockResult = quickTxBuilder.compose(unlockTx)
                .withSigner(SignerProviders.signerFrom(testAccount))
                .validFrom(currentSlot - 100)      // tx validity lower bound
                .validTo(currentSlot + 200)        // tx validity upper bound
                .feePayer(testAccount.baseAddress())
                .collateralPayer(testAccount.baseAddress())
                .complete();

        assertTrue(unlockResult.isSuccessful(), "Unlock tx should succeed: " + unlockResult);
        System.out.println("Unlock tx: " + unlockResult.getValue());
        waitForConfirmation(unlockResult.getValue());
    }
}
