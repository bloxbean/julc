package com.bloxbean.cardano.plutus.plugintest;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E test for TokenGateValidator.
 * <p>
 * Demonstrates a spending validator with a sealed interface redeemer.
 * The on-chain code defines sealed Action { Spend(owner), Delegate(delegate) },
 * but the current validation just checks signedBy(txInfo, redeemer).
 * <p>
 * Key concepts:
 * <ul>
 *   <li>Sealed interface variants map to ConstrPlutusData with sequential tags</li>
 *   <li>Action.Spend(owner) -> Constr(0, [BytesData(ownerPkh)])</li>
 *   <li>Action.Delegate(delegate) -> Constr(1, [BytesData(delegPkh)])</li>
 *   <li>Record fields become constructor data list items in declaration order</li>
 * </ul>
 * <p>
 * Run: ./gradlew :plutus-plugin-test:test -Pe2e
 */
class TokenGateValidatorE2ETest extends PluginTestBase {

    static final String TOKEN_GATE_VALIDATOR = """
            import com.bloxbean.cardano.plutus.core.PlutusData;

            @Validator
            class TokenGateValidator {
                sealed interface Action {
                    record Spend(PlutusData owner) implements Action {}
                    record Delegate(PlutusData delegate) implements Action {}
                }

                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    PlutusData txInfo = ContextsLib.getTxInfo(ctx);
                    return ContextsLib.signedBy(txInfo, redeemer);
                }
            }
            """;

    @Test
    void lockAndUnlockWithOwnerSignature() throws Exception {
        // 1. Compile the spending validator
        PlutusV3Script tokenGateScript = compileScript(TOKEN_GATE_VALIDATOR);
        String scriptAddr = AddressProvider.getEntAddress(tokenGateScript, Networks.testnet()).toBech32();
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

        // 4. Unlock with owner's PubKeyHash as redeemer
        //    The on-chain code does signedBy(txInfo, redeemer), so redeemer = owner PKH
        byte[] ownerPkh = testAccount.hdKeyPair().getPublicKey().getKeyHash();
        BytesPlutusData redeemer = new BytesPlutusData(ownerPkh);

        ScriptTx unlockTx = new ScriptTx()
                .collectFrom(scriptUtxo, redeemer)
                .payToAddress(testAccount.baseAddress(), Amount.ada(9))
                .attachSpendingValidator(tokenGateScript);

        var unlockResult = quickTxBuilder.compose(unlockTx)
                .withSigner(SignerProviders.signerFrom(testAccount))
                .withRequiredSigners(ownerPkh)
                .feePayer(testAccount.baseAddress())
                .collateralPayer(testAccount.baseAddress())
                .complete();

        assertTrue(unlockResult.isSuccessful(), "Unlock tx should succeed: " + unlockResult);
        System.out.println("Unlock tx: " + unlockResult.getValue());
        waitForConfirmation(unlockResult.getValue());

        // 5. Verify the script UTXO is spent
        var afterUtxos = backendService.getUtxoService().getUtxos(scriptAddr, 100, 1);
        boolean originalSpent = afterUtxos.getValue() == null
                || afterUtxos.getValue().stream()
                .noneMatch(u -> u.getTxHash().equals(lockTxHash)
                        && u.getOutputIndex() == scriptUtxo.getOutputIndex());
        assertTrue(originalSpent, "Original script UTXO should be spent");
    }

    /**
     * Demonstrates how to construct sealed interface variant redeemers.
     * <p>
     * While the current on-chain code uses the raw redeemer as a PubKeyHash,
     * this shows how you would construct the Action variants if the validator
     * pattern-matched on them.
     */
    @Test
    void sealedInterfaceRedeemerEncoding() {
        byte[] ownerPkh = new byte[28];
        byte[] delegatePkh = new byte[28];
        System.arraycopy(new byte[]{1, 2, 3}, 0, ownerPkh, 0, 3);
        System.arraycopy(new byte[]{4, 5, 6}, 0, delegatePkh, 0, 3);

        // Action.Spend(owner) -> Constr(0, [BytesData(ownerPkh)])
        ConstrPlutusData spendAction = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(new BytesPlutusData(ownerPkh)))
                .build();

        // Action.Delegate(delegate) -> Constr(1, [BytesData(delegPkh)])
        ConstrPlutusData delegateAction = ConstrPlutusData.builder()
                .alternative(1)
                .data(ListPlutusData.of(new BytesPlutusData(delegatePkh)))
                .build();

        assertEquals(0, spendAction.getAlternative());
        assertEquals(1, spendAction.getData().getPlutusDataList().size());
        assertEquals(1, delegateAction.getAlternative());
        assertEquals(1, delegateAction.getData().getPlutusDataList().size());

        System.out.println("Spend action: Constr(0, [" + ownerPkh.length + " bytes])");
        System.out.println("Delegate action: Constr(1, [" + delegatePkh.length + " bytes])");
    }
}
