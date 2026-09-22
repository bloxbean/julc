package org.julclang.e2e;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Direct Haskell evaluation of transaction witnesses on the developer's running node. */
final class HaskellScriptCost {
    private HaskellScriptCost() {}
    record Result(int exitCode, String output) {}

    /** Configure access to an existing node; never download or start one. No shell expansion. */
    static List<String> command(Map<String, String> env) {
        String container = env.getOrDefault("JULC_E2E_CARDANO_CONTAINER", "").trim();
        String cli = env.getOrDefault("JULC_E2E_CARDANO_CLI", "").trim();
        String socket = env.getOrDefault("JULC_E2E_CARDANO_SOCKET", "").trim();
        var command = new ArrayList<String>();
        if (!container.isEmpty()) {
            if (!cli.isEmpty() || !socket.isEmpty()) {
                throw new IllegalArgumentException("Choose Docker container OR native CLI/socket, not both");
            }
            if (container.startsWith("-")) throw new IllegalArgumentException("Invalid container name");
            command.addAll(List.of("docker", "exec", "-i", container));
            cli = "/app/cardano-bin/cardano-cli";
            socket = "/clusters/nodes/default/node/node.sock";
        } else if (cli.isEmpty() || socket.isEmpty()) {
            throw new IllegalArgumentException("Set JULC_E2E_CARDANO_CONTAINER, or both "
                    + "JULC_E2E_CARDANO_CLI and JULC_E2E_CARDANO_SOCKET for a running DevKit");
        }
        command.addAll(List.of(cli, "conway", "transaction", "calculate-plutus-script-cost", "online",
                "--testnet-magic", "42", "--socket-path", socket, "--tx-file", "/dev/stdin"));
        return List.copyOf(command);
    }

    static void requireConfigured() {
        command(System.getenv());
    }

    /** Keep the failure category, accepting either the adapter's name or Haskell's cause. */
    static void assertBackendFailure(String response, String adapterFailure, String haskellCause) {
        assertTrue(response.contains("EvaluationFailure"), response);
        assertTrue(response.contains(adapterFailure) || response.contains(haskellCause), response);
    }

    static Result evaluate(byte[] transaction) throws Exception {
        var command = command(System.getenv());
        var outputFile = Files.createTempFile("julc-haskell-cost-", ".log");
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true).redirectOutput(outputFile.toFile()).start();
            String envelope = "{\"type\":\"Tx ConwayEra\",\"description\":\"JuLC test\",\"cborHex\":\""
                    + HexFormat.of().formatHex(transaction) + "\"}";
            try (var input = process.getOutputStream()) {
                input.write(envelope.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Haskell evaluation timed out");
            return new Result(process.exitValue(), Files.readString(outputFile));
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(outputFile);
        }
    }

}
