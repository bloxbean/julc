package com.bloxbean.cardano.julc.plugintest;

import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E test for TypedMintingPolicy.
 * <p>
 * Demonstrates Milestone 6 typed access features:
 * <ul>
 *   <li>ScriptContext as typed parameter in a minting policy</li>
 *   <li>Chained method call: {@code ctx.txInfo().signatories().contains(redeemer)}</li>
 *   <li>No ContextsLib dependency — pure typed access</li>
 * </ul>
 * <p>
 * Run: ./gradlew :plutus-plugin-test:test -Pe2e
 */
class TypedMintingPolicyE2ETest extends PluginTestBase {

    static final String TYPED_MINTING_POLICY = """
            import com.bloxbean.cardano.julc.core.PlutusData;

            @MintingPolicy
            class TypedMintingPolicy {
                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    TxInfo txInfo = ctx.txInfo();
                    return txInfo.signatories().contains(redeemer);
                }
            }
            """;

    @Test
    void mintWithTypedAccess() throws Exception {
        // 1. Compile the minting policy
        PlutusV3Script mintingScript = compileScript(TYPED_MINTING_POLICY);
        String policyId = mintingScript.getPolicyId();
        assertNotNull(policyId);
        System.out.println("Policy ID: " + policyId);

        // 2. Get minter PubKeyHash
        byte[] minterPkh = testAccount.hdKeyPair().getPublicKey().getKeyHash();

        // 3. Redeemer = the minter's PubKeyHash (the policy checks it's in signatories)
        var redeemer = new BytesPlutusData(minterPkh);

        // 4. Mint 100 tokens using typed access (ctx.txInfo().signatories().contains(redeemer))
        String assetName = "TypedToken";
        Asset myToken = new Asset(assetName, BigInteger.valueOf(100));
        String assetUnit = policyId + HexFormat.of().formatHex(assetName.getBytes());

        ScriptTx mintTx = new ScriptTx()
                .mintAsset(mintingScript, myToken, redeemer, testAccount.baseAddress());

        var mintResult = quickTxBuilder.compose(mintTx)
                .withSigner(SignerProviders.signerFrom(testAccount))
                .withRequiredSigners(minterPkh)
                .feePayer(testAccount.baseAddress())
                .collateralPayer(testAccount.baseAddress())
                .complete();

        assertTrue(mintResult.isSuccessful(), "Mint tx should succeed: " + mintResult);
        String mintTxHash = mintResult.getValue();
        assertNotNull(mintTxHash);
        System.out.println("Mint tx: " + mintTxHash);

        // 5. Wait and verify
        waitForConfirmation(mintTxHash);

        boolean hasMintedAsset = false;
        for (int attempt = 0; attempt < 5; attempt++) {
            var utxoResult = backendService.getUtxoService()
                    .getUtxos(testAccount.baseAddress(), 100, 1);
            if (utxoResult.isSuccessful()) {
                hasMintedAsset = utxoResult.getValue().stream()
                        .flatMap(u -> u.getAmount().stream())
                        .anyMatch(a -> a.getUnit().equals(assetUnit)
                                && a.getQuantity().compareTo(BigInteger.ZERO) > 0);
                if (hasMintedAsset) break;
            }
            Thread.sleep(2000);
        }
        assertTrue(hasMintedAsset, "Should have minted TypedToken in UTXOs");
    }
}
