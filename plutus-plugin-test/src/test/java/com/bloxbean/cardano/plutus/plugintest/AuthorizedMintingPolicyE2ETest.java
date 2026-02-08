package com.bloxbean.cardano.plutus.plugintest;

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
 * E2E test for AuthorizedMintingPolicy.
 * <p>
 * Demonstrates offchain minting with QuickTx where the redeemer is a PubKeyHash
 * and the on-chain policy checks ContextsLib.signedBy(txInfo, redeemer).
 * <p>
 * Key concepts:
 * <ul>
 *   <li>{@code mintAsset(script, asset, redeemer, receiverAddr)} — mints tokens to the receiver</li>
 *   <li>{@code .withRequiredSigners(pkh)} — adds PKH to tx signatories for on-chain signedBy</li>
 *   <li>{@code .withSigner()} — provides the private key for actual signing</li>
 * </ul>
 * <p>
 * Run: ./gradlew :plutus-plugin-test:test -Pe2e
 */
class AuthorizedMintingPolicyE2ETest extends PluginTestBase {

    static final String AUTHORIZED_MINTING_POLICY = """
            import com.bloxbean.cardano.plutus.core.PlutusData;

            @MintingPolicy
            class AuthorizedMintingPolicy {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    PlutusData txInfo = ContextsLib.getTxInfo(ctx);
                    return ContextsLib.signedBy(txInfo, redeemer);
                }
            }
            """;

    @Test
    void mintWithAuthorizedSigner() throws Exception {
        // 1. Compile the minting policy
        PlutusV3Script mintPolicy = compileScript(AUTHORIZED_MINTING_POLICY);
        String policyId = mintPolicy.getPolicyId();
        assertNotNull(policyId);
        System.out.println("Policy ID: " + policyId);

        // 2. Redeemer = signer's PubKeyHash (28 bytes)
        byte[] signerPkh = testAccount.hdKeyPair().getPublicKey().getKeyHash();
        BytesPlutusData redeemer = new BytesPlutusData(signerPkh);

        // 3. Asset to mint
        String assetName = "AuthToken";
        Asset myToken = new Asset(assetName, BigInteger.valueOf(100));
        String assetUnit = policyId + HexFormat.of().formatHex(assetName.getBytes());

        // 4. Build minting transaction
        //    mintAsset(script, asset, redeemer, receiverAddress) sends minted tokens to the address
        ScriptTx mintTx = new ScriptTx()
                .mintAsset(mintPolicy, myToken, redeemer, testAccount.baseAddress());

        var result = quickTxBuilder.compose(mintTx)
                .withSigner(SignerProviders.signerFrom(testAccount))
                .withRequiredSigners(signerPkh)    // PKH must appear in tx signatories
                .feePayer(testAccount.baseAddress())
                .collateralPayer(testAccount.baseAddress())
                .complete();

        assertTrue(result.isSuccessful(), "Mint tx should succeed: " + result);
        String mintTxHash = result.getValue();
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
        assertTrue(hasMintedAsset, "Should have minted AuthToken in UTXOs");
    }

    @Test
    void burnWithAuthorizedSigner() throws Exception {
        PlutusV3Script mintPolicy = compileScript(AUTHORIZED_MINTING_POLICY);
        byte[] signerPkh = testAccount.hdKeyPair().getPublicKey().getKeyHash();
        BytesPlutusData redeemer = new BytesPlutusData(signerPkh);

        String assetName = "BurnableToken";
        Asset mintAsset = new Asset(assetName, BigInteger.valueOf(50));

        // Mint first
        var mintTx = new ScriptTx()
                .mintAsset(mintPolicy, mintAsset, redeemer, testAccount.baseAddress());

        var mintResult = quickTxBuilder.compose(mintTx)
                .withSigner(SignerProviders.signerFrom(testAccount))
                .withRequiredSigners(signerPkh)
                .feePayer(testAccount.baseAddress())
                .collateralPayer(testAccount.baseAddress())
                .complete();

        assertTrue(mintResult.isSuccessful(), "Mint tx should succeed: " + mintResult);
        waitForConfirmation(mintResult.getValue());

        // Burn: mint with negative quantity
        Asset burnAsset = new Asset(assetName, BigInteger.valueOf(-50));
        var burnTx = new ScriptTx()
                .mintAsset(mintPolicy, burnAsset, redeemer);

        var burnResult = quickTxBuilder.compose(burnTx)
                .withSigner(SignerProviders.signerFrom(testAccount))
                .withRequiredSigners(signerPkh)
                .feePayer(testAccount.baseAddress())
                .collateralPayer(testAccount.baseAddress())
                .complete();

        assertTrue(burnResult.isSuccessful(), "Burn tx should succeed: " + burnResult);
        waitForConfirmation(burnResult.getValue());
        System.out.println("Burn tx: " + burnResult.getValue());
    }
}
