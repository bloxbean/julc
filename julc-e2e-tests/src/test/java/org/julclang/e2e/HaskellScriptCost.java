package org.julclang.e2e;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Direct Haskell evaluation of transaction witnesses on the developer's running node. */
final class HaskellScriptCost {
    private HaskellScriptCost() {}
    record Result(int exitCode, String output) {}

    static Result evaluate(byte[] transaction) throws Exception {
        String container = System.getenv("JULC_E2E_CARDANO_CONTAINER");
        assertNotNull(container, "Set JULC_E2E_CARDANO_CONTAINER to the running developer DevKit container");
        var outputFile = Files.createTempFile("julc-haskell-cost-", ".log");
        Process process = null;
        try {
            process = new ProcessBuilder("docker", "exec", "-i", container,
                    "/app/cardano-bin/cardano-cli", "conway", "transaction", "calculate-plutus-script-cost", "online",
                    "--testnet-magic", "42", "--socket-path", "/clusters/nodes/default/node/node.sock",
                    "--tx-file", "/dev/stdin").redirectErrorStream(true).redirectOutput(outputFile.toFile()).start();
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
