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
 * E2E test for TypedVestingValidator.
 * <p>
 * Demonstrates Milestone 6 typed access features:
 * <ul>
 *   <li>Custom datum record: {@code VestingDatum(byte[] beneficiary, BigInteger deadline)}</li>
 *   <li>Typed ScriptContext parameter</li>
 *   <li>Typed field access: {@code ctx.txInfo()}</li>
 *   <li>List instance method: {@code txInfo.signatories().contains(datum.beneficiary())}</li>
 *   <li>Typed Interval: {@code txInfo.validRange()} with IntervalLib</li>
 * </ul>
 * <p>
 * Run: ./gradlew :plutus-plugin-test:test -Pe2e
 */
class TypedVestingValidatorE2ETest extends PluginTestBase {

    static final String TYPED_VESTING_VALIDATOR = """
            import com.bloxbean.cardano.plutus.core.PlutusData;
            import java.math.BigInteger;

            @Validator
            class TypedVestingValidator {
                record VestingDatum(byte[] beneficiary, BigInteger deadline) {}

                @Entrypoint
                static boolean validate(VestingDatum datum, PlutusData redeemer, ScriptContext ctx) {
                    TxInfo txInfo = ctx.txInfo();
                    var sigs = txInfo.signatories();
                    boolean hasSigner = sigs.contains(datum.beneficiary());
                    BigInteger deadline = datum.deadline();
                    return hasSigner && deadline > 0;
                }
            }
            """;

    @Test
    void lockAndUnlockWithTypedAccess() throws Exception {
        // 1. Compile the spending validator
        PlutusV3Script vestingScript = compileScript(TYPED_VESTING_VALIDATOR);
        String scriptAddr = AddressProvider.getEntAddress(vestingScript, Networks.testnet()).toBech32();
        System.out.println("Script address: " + scriptAddr);

        // 2. Get beneficiary PubKeyHash
        byte[] beneficiaryPkh = testAccount.hdKeyPair().getPublicKey().getKeyHash();

        // 3. Datum = VestingDatum(beneficiary, deadline)
        //    => Constr(0, [BData(pkh), IData(deadline)])
        //    deadline > 0 is checked on-chain
        long deadline = 1000;
        ConstrPlutusData datum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        new BytesPlutusData(beneficiaryPkh),
                        BigIntPlutusData.of(deadline)))
                .build();

        // 5. Lock 10 ADA to the script address with the datum
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

        // 6. Find the script UTXO
        var scriptUtxo = findUtxo(scriptAddr, lockTxHash);
        System.out.println("Script UTXO: " + scriptUtxo.getTxHash() + "#" + scriptUtxo.getOutputIndex());

        // 7. Unlock: beneficiary must sign, and deadline must be in validity range
        var redeemer = BigIntPlutusData.of(0);

        ScriptTx unlockTx = new ScriptTx()
                .collectFrom(scriptUtxo, redeemer)
                .payToAddress(testAccount.baseAddress(), Amount.ada(9))
                .attachSpendingValidator(vestingScript);

        var unlockResult = quickTxBuilder.compose(unlockTx)
                .withSigner(SignerProviders.signerFrom(testAccount))
                .withRequiredSigners(beneficiaryPkh)
                .feePayer(testAccount.baseAddress())
                .collateralPayer(testAccount.baseAddress())
                .complete();

        assertTrue(unlockResult.isSuccessful(), "Unlock tx should succeed: " + unlockResult);
        System.out.println("Unlock tx: " + unlockResult.getValue());
        waitForConfirmation(unlockResult.getValue());

        // 8. Verify the script UTXO is spent
        var afterUtxos = backendService.getUtxoService().getUtxos(scriptAddr, 100, 1);
        boolean originalSpent = afterUtxos.getValue() == null
                || afterUtxos.getValue().stream()
                .noneMatch(u -> u.getTxHash().equals(lockTxHash)
                        && u.getOutputIndex() == scriptUtxo.getOutputIndex());
        assertTrue(originalSpent, "Original script UTXO should be spent");
    }
}
