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
 * E2E test for TypedMultiSigValidator.
 * <p>
 * Demonstrates Milestone 6 typed access features:
 * <ul>
 *   <li>Custom datum record with PlutusData fields</li>
 *   <li>Typed ScriptContext parameter</li>
 *   <li>List.contains() instead of ContextsLib.signedBy()</li>
 *   <li>Multiple list method calls on the same list variable</li>
 * </ul>
 * <p>
 * Run: ./gradlew :plutus-plugin-test:test -Pe2e
 */
class TypedMultiSigValidatorE2ETest extends PluginTestBase {

    static final String TYPED_MULTI_SIG_VALIDATOR = """
            import com.bloxbean.cardano.plutus.core.PlutusData;

            @Validator
            class TypedMultiSigValidator {
                record Keys(PlutusData key1, PlutusData key2) {}

                @Entrypoint
                static boolean validate(Keys datum, PlutusData redeemer, ScriptContext ctx) {
                    TxInfo txInfo = ctx.txInfo();
                    var sigs = txInfo.signatories();
                    boolean sig1 = sigs.contains(datum.key1());
                    boolean sig2 = sigs.contains(datum.key2());
                    if (sig1) {
                        return sig2;
                    } else {
                        return false;
                    }
                }
            }
            """;

    @Test
    void lockAndUnlockWithTypedAccess() throws Exception {
        // 1. Compile the spending validator
        PlutusV3Script multiSigScript = compileScript(TYPED_MULTI_SIG_VALIDATOR);
        String scriptAddr = AddressProvider.getEntAddress(multiSigScript, Networks.testnet()).toBech32();
        System.out.println("Script address: " + scriptAddr);

        // 2. Get PubKeyHashes for both signers
        byte[] pkh1 = testAccount.hdKeyPair().getPublicKey().getKeyHash();
        byte[] pkh2 = testAccount2.hdKeyPair().getPublicKey().getKeyHash();

        // 3. Datum = Keys(key1, key2) => Constr(0, [BData(pkh1), BData(pkh2)])
        ConstrPlutusData datum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        new BytesPlutusData(pkh1),
                        new BytesPlutusData(pkh2)))
                .build();

        // 4. Lock 10 ADA to the script address with the datum
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

        // 5. Find the script UTXO
        var scriptUtxo = findUtxo(scriptAddr, lockTxHash);
        System.out.println("Script UTXO: " + scriptUtxo.getTxHash() + "#" + scriptUtxo.getOutputIndex());

        // 6. Unlock: both signers must sign (using typed list.contains() instead of ContextsLib.signedBy)
        var redeemer = BigIntPlutusData.of(0);

        ScriptTx unlockTx = new ScriptTx()
                .collectFrom(scriptUtxo, redeemer)
                .payToAddress(testAccount.baseAddress(), Amount.ada(9))
                .attachSpendingValidator(multiSigScript);

        var unlockResult = quickTxBuilder.compose(unlockTx)
                .withSigner(SignerProviders.signerFrom(testAccount))
                .withSigner(SignerProviders.signerFrom(testAccount2))
                .withRequiredSigners(pkh1, pkh2)
                .feePayer(testAccount.baseAddress())
                .collateralPayer(testAccount.baseAddress())
                .complete();

        assertTrue(unlockResult.isSuccessful(), "Unlock tx should succeed: " + unlockResult);
        System.out.println("Unlock tx: " + unlockResult.getValue());
        waitForConfirmation(unlockResult.getValue());

        // 7. Verify the script UTXO is spent
        var afterUtxos = backendService.getUtxoService().getUtxos(scriptAddr, 100, 1);
        boolean originalSpent = afterUtxos.getValue() == null
                || afterUtxos.getValue().stream()
                .noneMatch(u -> u.getTxHash().equals(lockTxHash)
                        && u.getOutputIndex() == scriptUtxo.getOutputIndex());
        assertTrue(originalSpent, "Original script UTXO should be spent");
    }
}
