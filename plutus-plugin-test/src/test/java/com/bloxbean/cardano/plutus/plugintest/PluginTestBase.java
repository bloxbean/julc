package com.bloxbean.cardano.plutus.plugintest;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.plutus.clientlib.PlutusScriptAdapter;
import com.bloxbean.cardano.plutus.compiler.PlutusCompiler;
import com.bloxbean.cardano.plutus.core.Program;
import com.bloxbean.cardano.plutus.stdlib.StdlibRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Base class for plugin-test E2E tests against Yaci Devkit.
 * <p>
 * Provides:
 * - BackendService connected to local Yaci Devkit
 * - QuickTxBuilder for transaction construction
 * - Two test accounts with funded ADA (for multi-sig scenarios)
 * - Compiler with stdlib for compiling validators at test time
 * <p>
 * Tests are skipped if Yaci Devkit is not reachable.
 * <p>
 * Run: ./gradlew :plutus-plugin-test:test -Pe2e
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class PluginTestBase {

    static final String YACI_BASE_URL = "http://localhost:8080/api/v1/";
    static final String YACI_ADMIN_URL = "http://localhost:10000";

    BackendService backendService;
    QuickTxBuilder quickTxBuilder;
    Account testAccount;
    Account testAccount2;
    StdlibRegistry stdlib;

    @BeforeAll
    void setUp() {
        assumeTrue(isYaciReachable(), "Yaci Devkit not reachable at " + YACI_BASE_URL);

        backendService = new BFBackendService(YACI_BASE_URL, "dummy-key");
        quickTxBuilder = new QuickTxBuilder(backendService);

        testAccount = new Account(Networks.testnet());
        testAccount2 = new Account(Networks.testnet());
        stdlib = StdlibRegistry.defaultRegistry();

        topUp(testAccount.baseAddress(), 1000_000_000L);
        topUp(testAccount2.baseAddress(), 1000_000_000L);
    }

    /**
     * Compile a Java validator source to a PlutusV3Script using the Plutus compiler.
     * This is equivalent to what the Gradle plugin does at build time.
     */
    protected PlutusV3Script compileScript(String javaSource) {
        var compiler = new PlutusCompiler(stdlib::lookup);
        var result = compiler.compile(javaSource);
        if (result.hasErrors()) {
            throw new AssertionError("Compilation errors: " + result.diagnostics());
        }
        return PlutusScriptAdapter.fromProgram(result.program());
    }

    /**
     * Find a UTXO at the given address matching a specific tx hash.
     */
    protected Utxo findUtxo(String address, String txHash) throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            var utxoResult = backendService.getUtxoService().getUtxos(address, 100, 1);
            if (utxoResult.isSuccessful() && utxoResult.getValue() != null) {
                var match = utxoResult.getValue().stream()
                        .filter(u -> u.getTxHash().equals(txHash))
                        .findFirst();
                if (match.isPresent()) return match.get();
            }
            Thread.sleep(2000);
        }
        throw new RuntimeException("UTXO not found at " + address + " for tx " + txHash);
    }

    protected void topUp(String address, long lovelace) {
        try {
            var client = HttpClient.newHttpClient();
            var body = "{\"address\":\"" + address + "\",\"adaAmount\":" + (lovelace / 1_000_000) + "}";
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(YACI_ADMIN_URL + "/local-cluster/api/addresses/topup"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Failed to topup: " + response.statusCode() + " " + response.body());
            }
            Thread.sleep(3000);
        } catch (Exception e) {
            throw new RuntimeException("Failed to topup test account", e);
        }
    }

    protected void waitForConfirmation(String txHash) throws Exception {
        for (int i = 0; i < 30; i++) {
            try {
                var txResult = backendService.getTransactionService().getTransaction(txHash);
                if (txResult.isSuccessful() && txResult.getValue() != null) {
                    return;
                }
            } catch (Exception ignored) {
            }
            Thread.sleep(2000);
        }
        throw new RuntimeException("Transaction not confirmed within timeout: " + txHash);
    }

    private boolean isYaciReachable() {
        try {
            var client = HttpClient.newHttpClient();
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(YACI_BASE_URL))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .GET()
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }
}
