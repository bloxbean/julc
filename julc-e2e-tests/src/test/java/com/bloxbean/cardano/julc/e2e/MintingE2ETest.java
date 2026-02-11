package com.bloxbean.cardano.julc.e2e;

import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.core.Program;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end minting test: mint and burn tokens using a Plutus V3 minting policy.
 * <p>
 * Uses a simple "always true" minting policy.
 * Requires Yaci Devkit running locally.
 * <p>
 * Run with: ./gradlew :plutus-e2e-tests:test -Pe2e
 */
class MintingE2ETest extends E2ETestBase {

    static final String ALWAYS_TRUE_MINT = """
            @MintingPolicy
            class AlwaysTrueMint {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    return true;
                }
            }
            """;

    private static String bytesToHex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    @Test
    void mintTokens() throws Exception {
        // 1. Compile the minting policy
        Program program = compile(ALWAYS_TRUE_MINT);
        PlutusV3Script script = JulcScriptAdapter.fromProgram(program);

        // getPolicyId() returns the hex-encoded script hash
        String policyId = script.getPolicyId();
        assertNotNull(policyId);
        System.out.println("Policy ID: " + policyId);

        // 2. Mint 100 tokens
        // Asset name is a raw string; cardano-client-lib handles hex encoding internally
        var redeemer = BigIntPlutusData.of(0);
        String assetName = "TestToken";
        var asset = new Asset(assetName, BigInteger.valueOf(100));
        // UTXO unit format: policyId + hex(assetNameBytes)
        String assetUnit = policyId + bytesToHex(assetName.getBytes());

        // mintAsset with receiver address sends minted tokens directly to the address
        var mintTx = new ScriptTx()
                .mintAsset(script, asset, redeemer, testAccount.baseAddress());

        var mintResult = quickTxBuilder.compose(mintTx)
                .withSigner(SignerProviders.signerFrom(testAccount))
                .feePayer(testAccount.baseAddress())
                .collateralPayer(testAccount.baseAddress())
                .complete();

        assertTrue(mintResult.isSuccessful(), "Mint tx should succeed: " + mintResult);
        String mintTxHash = mintResult.getValue();
        assertNotNull(mintTxHash);
        System.out.println("Mint tx: " + mintTxHash);

        // 3. Wait for mint tx to be confirmed
        waitForConfirmation(mintTxHash);

        // 4. Verify the minted tokens exist in our UTXOs (retry for indexer delay)
        boolean hasMintedAsset = false;
        for (int attempt = 0; attempt < 5; attempt++) {
            var utxoResult = backendService.getUtxoService().getUtxos(testAccount.baseAddress(), 100, 1);
            assertTrue(utxoResult.isSuccessful(), "Should find UTXOs");

            hasMintedAsset = utxoResult.getValue().stream()
                    .flatMap(u -> u.getAmount().stream())
                    .anyMatch(a -> a.getUnit().equals(assetUnit)
                            && a.getQuantity().compareTo(BigInteger.ZERO) > 0);
            if (hasMintedAsset) break;
            Thread.sleep(2000);
        }
        assertTrue(hasMintedAsset, "Should have minted TestToken in UTXOs");
    }

    @Test
    void mintAndBurnTokens() throws Exception {
        // 1. Compile the minting policy
        Program program = compile(ALWAYS_TRUE_MINT);
        PlutusV3Script script = JulcScriptAdapter.fromProgram(program);

        String policyId = script.getPolicyId();
        String assetName = "BurnToken";
        String assetUnit = policyId + bytesToHex(assetName.getBytes());

        // 2. Mint tokens
        var redeemer = BigIntPlutusData.of(0);
        var mintAsset = new Asset(assetName, BigInteger.valueOf(50));

        var mintTx = new ScriptTx()
                .mintAsset(script, mintAsset, redeemer, testAccount.baseAddress());

        var mintResult = quickTxBuilder.compose(mintTx)
                .withSigner(SignerProviders.signerFrom(testAccount))
                .feePayer(testAccount.baseAddress())
                .collateralPayer(testAccount.baseAddress())
                .complete();

        assertTrue(mintResult.isSuccessful(), "Mint tx should succeed: " + mintResult);
        waitForConfirmation(mintResult.getValue());
        System.out.println("Mint tx: " + mintResult.getValue());

        // 3. Burn tokens (mint with negative quantity)
        var burnAsset = new Asset(assetName, BigInteger.valueOf(-50));

        var burnTx = new ScriptTx()
                .mintAsset(script, burnAsset, redeemer);

        var burnResult = quickTxBuilder.compose(burnTx)
                .withSigner(SignerProviders.signerFrom(testAccount))
                .feePayer(testAccount.baseAddress())
                .collateralPayer(testAccount.baseAddress())
                .complete();

        assertTrue(burnResult.isSuccessful(), "Burn tx should succeed: " + burnResult);
        waitForConfirmation(burnResult.getValue());
        System.out.println("Burn tx: " + burnResult.getValue());

        // 4. Verify tokens are burned
        var utxoResult = backendService.getUtxoService().getUtxos(testAccount.baseAddress(), 100, 1);
        assertTrue(utxoResult.isSuccessful());

        boolean hasAsset = utxoResult.getValue().stream()
                .flatMap(u -> u.getAmount().stream())
                .anyMatch(a -> a.getUnit().equals(assetUnit)
                        && a.getQuantity().compareTo(BigInteger.ZERO) > 0);
        assertFalse(hasAsset, "BurnToken should be fully burned");
    }
}
