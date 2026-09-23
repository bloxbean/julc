package org.julclang.e2e;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("onchain-harness")
class HaskellScriptCostTest {
    @Test
    void dockerUsesOnlyExecOnTheSuppliedContainer() {
        var command = HaskellScriptCost.command(Map.of("JULC_E2E_CARDANO_CONTAINER", "devkit"));
        assertEquals(List.of("docker", "exec", "-i", "devkit", "/app/cardano-bin/cardano-cli",
                "conway", "transaction", "calculate-plutus-script-cost", "online", "--testnet-magic",
                "42", "--socket-path", "/clusters/nodes/default/node/node.sock", "--tx-file", "/dev/stdin"), command);
    }

    @Test
    void nativePathsAreSingleArgumentsNotShellCommands() {
        var command = HaskellScriptCost.command(Map.of("JULC_E2E_CARDANO_CLI", "/tools with spaces/cardano-cli",
                "JULC_E2E_CARDANO_SOCKET", "/devnet with spaces/node.sock"));
        assertEquals(List.of("/tools with spaces/cardano-cli", "conway", "transaction",
                "calculate-plutus-script-cost", "online", "--testnet-magic", "42", "--socket-path",
                "/devnet with spaces/node.sock", "--tx-file", "/dev/stdin"), command);
    }

    @Test
    void missingPartialOrAmbiguousConfigurationFailsClosed() {
        for (var env : List.of(Map.<String, String>of(), Map.of("JULC_E2E_CARDANO_CONTAINER", " "),
                Map.of("JULC_E2E_CARDANO_CLI", "cardano-cli"), Map.of("JULC_E2E_CARDANO_SOCKET", "node.sock"),
                Map.of("JULC_E2E_CARDANO_CONTAINER", "devkit", "JULC_E2E_CARDANO_CLI", "cardano-cli"),
                Map.of("JULC_E2E_CARDANO_CONTAINER", "devkit", "JULC_E2E_CARDANO_SOCKET", "node.sock"),
                Map.of("JULC_E2E_CARDANO_CONTAINER", "--privileged"))) {
            assertThrows(IllegalArgumentException.class, () -> HaskellScriptCost.command(env));
        }
    }

    @Test
    void diagnosticCompatibilityStillRequiresTheExpectedFailureCategory() {
        HaskellScriptCost.assertBackendFailure("EvaluationFailure: Error evaluated", "Error evaluated", "Caused by: (error)");
        HaskellScriptCost.assertBackendFailure("EvaluationFailure: Caused by: (error)", "Error evaluated", "Caused by: (error)");
        HaskellScriptCost.assertBackendFailure("EvaluationFailure: UnIData", "UnIData", "Caused by: [ (builtin unIData)");
        HaskellScriptCost.assertBackendFailure("EvaluationFailure: Caused by: [ (builtin unIData)", "UnIData", "Caused by: [ (builtin unIData)");
        for (String wrong : List.of("HTTP 500", "Error evaluated", "EvaluationFailure: out of budget",
                "EvaluationFailure: Caused by: [ (builtin unBData)")) {
            assertThrows(AssertionError.class, () -> HaskellScriptCost.assertBackendFailure(
                    wrong, "UnIData", "Caused by: [ (builtin unIData)"));
        }
    }
}
