package com.bloxbean.cardano.plutus.e2e;

import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.plutus.compiler.PlutusCompiler;
import com.bloxbean.cardano.plutus.compiler.pir.StdlibLookup;
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
 * Base class for E2E integration tests against Yaci Devkit.
 * <p>
 * Provides:
 * - BackendService connected to local Yaci Devkit
 * - QuickTxBuilder for transaction construction
 * - Test account with funded ADA
 * - Compiler with stdlib support
 * <p>
 * Tests are skipped if Yaci Devkit is not reachable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class E2ETestBase {

    static final String YACI_BASE_URL = "http://localhost:8080/api/v1/";
    static final String YACI_ADMIN_URL = "http://localhost:10000";

    BackendService backendService;
    QuickTxBuilder quickTxBuilder;
    Account testAccount;
    StdlibRegistry stdlib;

    @BeforeAll
    void setUp() {
        // Skip if Yaci Devkit is not running
        assumeTrue(isYaciReachable(), "Yaci Devkit not reachable at " + YACI_BASE_URL);

        backendService = new BFBackendService(YACI_BASE_URL, "dummy-key");
        quickTxBuilder = new QuickTxBuilder(backendService);

        // Create a test account on the preview/devkit network
        testAccount = new Account(Networks.testnet());

        stdlib = StdlibRegistry.defaultRegistry();

        // Top up test account
        topUp(testAccount.baseAddress(), 1000_000_000L); // 1000 ADA
    }

    /**
     * Compile Java source to a UPLC Program with stdlib support.
     */
    protected Program compile(String javaSource) {
        var compiler = new PlutusCompiler(stdlib::lookup);
        var result = compiler.compile(javaSource);
        if (result.hasErrors()) {
            throw new AssertionError("Compilation errors: " + result.diagnostics());
        }
        return result.program();
    }

    /**
     * Top up an address using the Yaci Devkit faucet API.
     */
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
            // Wait for the transaction to be confirmed
            Thread.sleep(3000);
        } catch (Exception e) {
            throw new RuntimeException("Failed to topup test account", e);
        }
    }

    /**
     * Wait for a transaction to be confirmed.
     */
    protected void waitForConfirmation(String txHash) throws Exception {
        for (int i = 0; i < 30; i++) {
            try {
                var txResult = backendService.getTransactionService().getTransaction(txHash);
                if (txResult.isSuccessful() && txResult.getValue() != null) {
                    return;
                }
            } catch (Exception ignored) {
                // Not yet confirmed
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
