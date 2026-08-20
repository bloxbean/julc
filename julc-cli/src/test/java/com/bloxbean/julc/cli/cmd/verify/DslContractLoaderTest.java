package com.bloxbean.julc.cli.cmd.verify;

import com.bloxbean.cardano.julc.compiler.schema.ContractSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DslContractLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void mixedValidatorRequiresPurposeAndBindsBothInterfacesToSharedArtifact()
            throws Exception {
        Path project = tempDir.resolve("mixed-protocol");
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("julc.toml"), """
                [project]
                name = "mixed-protocol"
                version = "0.1.0"
                compiler = "local"
                """);
        Files.writeString(project.resolve("src/Protocol.java"), """
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import java.math.BigInteger;

                @MultiValidator
                class Protocol {
                    record Datum(BigInteger state) {}
                    record Spend(BigInteger next) {}
                    record Mint(byte[] token) {}
                    record Reward() {}

                    @Entrypoint(purpose = Purpose.SPEND)
                    static boolean spend(Datum datum, Spend redeemer, ScriptContext ctx) {
                        return true;
                    }

                    @Entrypoint(purpose = Purpose.MINT)
                    static boolean mint(Mint redeemer, ScriptContext ctx) {
                        return true;
                    }

                    @Entrypoint(purpose = Purpose.WITHDRAW)
                    static boolean reward(Reward redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """);

        var missing = assertThrows(IllegalArgumentException.class,
                () -> DslContractLoader.load(project, "Protocol"));
        assertTrue(missing.getMessage().contains("multiple interfaces"));
        assertTrue(missing.getMessage().contains("--purpose"));

        var spending = DslContractLoader.load(
                project, "Protocol", VerificationPurpose.SPENDING);
        var minting = DslContractLoader.load(
                project, "Protocol", VerificationPurpose.MINTING);
        var rewarding = DslContractLoader.load(
                project, "Protocol", VerificationPurpose.REWARDING);

        assertEquals(ContractSchema.Purpose.SPEND, spending.schema().purpose());
        assertEquals(ContractSchema.Purpose.MINT, minting.schema().purpose());
        assertEquals("Protocol.spend", spending.blueprintEntryTitle());
        assertEquals("Protocol.mint", minting.blueprintEntryTitle());
        assertEquals(ContractSchema.Purpose.WITHDRAW, rewarding.schema().purpose());
        assertEquals("Protocol.withdraw", rewarding.blueprintEntryTitle());
        var blueprint = VerificationFiles.JSON.readTree(minting.blueprint().toFile());
        var entries = new java.util.HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        blueprint.path("validators").forEach(entry -> entries.put(
                entry.path("redeemer").path("purpose").asText(), entry));
        String spendCode = entries.get("spend").path("compiledCode").asText();
        String mintCode = entries.get("mint").path("compiledCode").asText();
        String rewardCode = entries.get("withdraw").path("compiledCode").asText();
        assertEquals(spendCode, mintCode,
                "purpose-indexed interfaces must share exact compiled code");
        assertEquals(spendCode, rewardCode,
                "rewarding interface must share exact compiled code");
        assertEquals(entries.get("spend").path("hash").asText(),
                entries.get("mint").path("hash").asText(),
                "purpose-indexed interfaces must share the Cardano script hash");
    }

    @Test
    void singleMintingInterfaceInfersPurposeAndRejectsMismatch() throws Exception {
        Path project = tempDir.resolve("single-policy");
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("julc.toml"), """
                [project]
                name = "single-policy"
                version = "0.1.0"
                compiler = "local"
                """);
        Files.writeString(project.resolve("src/TokenPolicy.java"), """
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                @MintingValidator class TokenPolicy {
                    record Redeemer() {}
                    @Entrypoint static boolean validate(Redeemer redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """);

        var inferred = DslContractLoader.load(project, "TokenPolicy");
        assertEquals(VerificationPurpose.MINTING, inferred.purpose());
        assertEquals(ContractSchema.Purpose.MINT, inferred.schema().purpose());
        var mismatch = assertThrows(IllegalArgumentException.class,
                () -> DslContractLoader.load(
                        project, "TokenPolicy", VerificationPurpose.SPENDING));
        assertTrue(mismatch.getMessage().contains("has purpose minting"));
    }

    @Test
    void singleRewardingInterfaceUsesWithdrawBlueprintIdentity() throws Exception {
        Path project = tempDir.resolve("single-rewards");
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("julc.toml"), """
                [project]
                name = "single-rewards"
                version = "0.1.0"
                compiler = "local"
                """);
        Files.writeString(project.resolve("src/Rewards.java"), """
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                @WithdrawValidator class Rewards {
                    record Redeemer() {}
                    @Entrypoint static boolean validate(Redeemer redeemer,
                            ScriptContext ctx) { return true; }
                }
                """);

        var inferred = DslContractLoader.load(project, "Rewards");

        assertEquals(VerificationPurpose.REWARDING, inferred.purpose());
        assertEquals(ContractSchema.Purpose.WITHDRAW, inferred.schema().purpose());
        assertEquals("Rewards", inferred.blueprintEntryTitle());
        var blueprint = VerificationFiles.JSON.readTree(inferred.blueprint().toFile());
        assertEquals("withdraw", blueprint.path("validators").get(0)
                .path("redeemer").path("purpose").asText());
    }
}
