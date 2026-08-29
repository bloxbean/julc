package com.bloxbean.julc.cli.cmd;

import com.bloxbean.julc.cli.project.ProjectLayout;
import com.bloxbean.julc.cli.scaffold.ProjectScaffolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BuildCommandTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void defaultsToPv11SafeOptimization() {
        var commandLine = new CommandLine(new BuildCommand());
        commandLine.parseArgs(".");

        assertEquals("pv11-safe", commandLine.getCommandSpec()
                .findOption("--optimization").getValue());
    }

    @Test
    void acceptsExactPv11TargetAndRejectsUnknownFutureTarget(@TempDir Path tempDir)
            throws Exception {
        Path project = tempDir.resolve("target-build");
        ProjectScaffolder.scaffold(project, "target-build");

        assertEquals(0, new CommandLine(new BuildCommand()).execute(
                project.toString(), "--target", "plutus-v3-pv11-uplc-1.1.0"));
        assertEquals(1, new CommandLine(new BuildCommand()).execute(
                project.toString(), "--target", "plutus-v3-pv12-uplc-1.1.0"));
    }

    @Test
    void optimizerSelectionIsExactAndCostedRequiresPinnedProfile(@TempDir Path tempDir)
            throws Exception {
        Path project = tempDir.resolve("optimization-build");
        ProjectScaffolder.scaffold(project, "optimization-build");

        assertEquals(0, new CommandLine(new BuildCommand()).execute(
                project.toString(), "--optimization", "pv11-safe"));
        assertEquals(1, new CommandLine(new BuildCommand()).execute(
                project.toString(), "--optimization", "PV11_SAFE"));
        assertEquals(1, new CommandLine(new BuildCommand()).execute(
                project.toString(), "--optimization", "pv11-costed"));
        assertEquals(0, new CommandLine(new BuildCommand()).execute(
                project.toString(),
                "--optimization", "pv11-costed",
                "--cost-profile", "cardano-node-11.0.1-plutus-v3-pv11"));
    }

    @Test
    void noBlueprintEmitsRawArtifactAndCannotLeaveStaleBlueprint(@TempDir Path tempDir)
            throws Exception {
        Path project = tempDir.resolve("raw-build");
        ProjectScaffolder.scaffold(project, "raw-build");
        Path plutusDir = ProjectLayout.plutusDir(project);
        Files.createDirectories(plutusDir);
        Files.writeString(plutusDir.resolve("plutus.json"), "stale");

        int exit = new CommandLine(new BuildCommand()).execute(
                project.toString(), "--no-blueprint");
        assertEquals(0, exit);
        assertFalse(Files.exists(plutusDir.resolve("plutus.json")));
        assertTrue(Files.isRegularFile(plutusDir.resolve("AlwaysSucceeds.uplc")));
        assertTrue(Files.isRegularFile(plutusDir.resolve("AlwaysSucceeds.compiledCode.hex")));
        assertEquals(56, Files.readString(plutusDir.resolve("AlwaysSucceeds.script-hash"))
                .trim().length());

        String rawCompiledCode = Files.readString(
                plutusDir.resolve("AlwaysSucceeds.compiledCode.hex")).trim();
        int strictExit = new CommandLine(new BuildCommand()).execute(project.toString());
        assertEquals(0, strictExit);
        var blueprint = JSON.readTree(plutusDir.resolve("plutus.json").toFile());
        assertEquals(rawCompiledCode,
                blueprint.path("validators").get(0).path("compiledCode").asText());
        assertEquals(rawCompiledCode,
                Files.readString(plutusDir.resolve("AlwaysSucceeds.compiledCode.hex")).trim());
    }

    @Test
    void skipBlueprintAliasIsAccepted(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("raw-alias");
        ProjectScaffolder.scaffold(project, "raw-alias");

        int exit = new CommandLine(new BuildCommand()).execute(
                project.toString(), "--skip-blueprint");
        assertEquals(0, exit);
        assertFalse(Files.exists(ProjectLayout.plutusDir(project).resolve("plutus.json")));
    }

    @Test
    void strictBuildEmitsCompleteNestedCip57Schemas(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("typed-build");
        ProjectScaffolder.scaffold(project, "typed-build");
        Files.writeString(ProjectLayout.srcDir(project).resolve("AlwaysSucceeds.java"), """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                import java.util.List;
                import java.util.Map;
                import java.util.Optional;

                @SpendingValidator
                class AlwaysSucceeds {
                    record Datum(List<BigInteger> values,
                                 Map<byte[], BigInteger> balances,
                                 Optional<byte[]> owner,
                                 boolean active) {}
                    record Redeemer(BigInteger value) {}

                    @Entrypoint
                    static boolean validate(Datum datum, Redeemer redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """);

        int exit = new CommandLine(new BuildCommand()).execute(project.toString());
        assertEquals(0, exit);

        var blueprint = JSON.readTree(
                ProjectLayout.plutusDir(project).resolve("plutus.json").toFile());
        assertEquals("https://cips.cardano.org/cips/cip57/schemas/plutus-blueprint.json",
                blueprint.path("$schema").asText());
        var fields = blueprint.path("definitions").path("Datum")
                .path("anyOf").get(0).path("fields");
        assertEquals("list", fields.get(0).path("dataType").asText());
        assertEquals("integer", fields.get(0).path("items").path("dataType").asText());
        assertEquals("map", fields.get(1).path("dataType").asText());
        assertEquals("bytes", fields.get(1).path("keys").path("dataType").asText());
        assertEquals("integer", fields.get(1).path("values").path("dataType").asText());
        assertEquals(0, fields.get(2).path("anyOf").get(0).path("index").asInt());
        assertEquals(1, fields.get(2).path("anyOf").get(1).path("index").asInt());
        assertEquals("False", fields.get(3).path("anyOf").get(0).path("title").asText());
        assertEquals("True", fields.get(3).path("anyOf").get(1).path("title").asText());
    }

    @Test
    void strictBuildPublishesExplicitMultiPurposeInterfaces(@TempDir Path tempDir)
            throws Exception {
        Path project = tempDir.resolve("multi-build");
        ProjectScaffolder.scaffold(project, "multi-build");
        Files.writeString(ProjectLayout.srcDir(project).resolve("AlwaysSucceeds.java"), """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                @MultiValidator class AlwaysSucceeds {
                    record Datum(BigInteger state) {}
                    record Spend(BigInteger next) {}
                    record Mint(byte[] tokenName) {}
                    @Entrypoint(purpose = Purpose.SPEND)
                    static boolean spend(Datum datum, Spend redeemer, ScriptContext ctx) {
                        return true;
                    }
                    @Entrypoint(purpose = Purpose.MINT)
                    static boolean mint(Mint redeemer, ScriptContext ctx) { return true; }
                }
                """);

        assertEquals(0, new CommandLine(new BuildCommand()).execute(project.toString()));
        var validators = JSON.readTree(
                ProjectLayout.plutusDir(project).resolve("plutus.json").toFile())
                .path("validators");
        assertEquals(2, validators.size());
        assertEquals("AlwaysSucceeds.mint", validators.get(0).path("title").asText());
        assertEquals("mint", validators.get(0).path("redeemer").path("purpose").asText());
        assertEquals("AlwaysSucceeds.spend", validators.get(1).path("title").asText());
        assertEquals("spend", validators.get(1).path("datum").path("purpose").asText());
        assertEquals(validators.get(0).path("compiledCode").asText(),
                validators.get(1).path("compiledCode").asText());
        assertEquals(validators.get(0).path("hash").asText(),
                validators.get(1).path("hash").asText());
    }

    @Test
    void singleCertifyingPurposePublishesAsCip57Publish(@TempDir Path tempDir)
            throws Exception {
        Path project = tempDir.resolve("certifying-build");
        ProjectScaffolder.scaffold(project, "certifying-build");
        Files.writeString(ProjectLayout.srcDir(project).resolve("AlwaysSucceeds.java"), """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;

                @CertifyingValidator class AlwaysSucceeds {
                    record Redeemer(BigInteger value) {}
                    @Entrypoint
                    static boolean validate(Redeemer redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """);

        assertEquals(0, new CommandLine(new BuildCommand()).execute(project.toString()));
        Path plutusDir = ProjectLayout.plutusDir(project);
        var validator = JSON.readTree(plutusDir.resolve("plutus.json").toFile())
                .path("validators").get(0);
        assertEquals("AlwaysSucceeds", validator.path("title").asText());
        assertEquals("publish", validator.path("redeemer").path("purpose").asText());
    }

    @Test
    void unsupportedSingleGovernancePurposeFailsButOptOutStillCompiles(
            @TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("voting-build");
        ProjectScaffolder.scaffold(project, "voting-build");
        Files.writeString(ProjectLayout.srcDir(project).resolve("AlwaysSucceeds.java"), """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;

                @VotingValidator class AlwaysSucceeds {
                    record Redeemer(BigInteger value) {}
                    @Entrypoint
                    static boolean validate(Redeemer redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """);

        assertEquals(1, new CommandLine(new BuildCommand()).execute(project.toString()));
        Path plutusDir = ProjectLayout.plutusDir(project);
        assertFalse(Files.exists(plutusDir.resolve("plutus.json")));

        assertEquals(0, new CommandLine(new BuildCommand()).execute(
                project.toString(), "--no-blueprint"));
        assertTrue(Files.isRegularFile(plutusDir.resolve("AlwaysSucceeds.uplc")));
        assertTrue(Files.isRegularFile(
                plutusDir.resolve("AlwaysSucceeds.compiledCode.hex")));
        assertTrue(Files.isRegularFile(plutusDir.resolve("AlwaysSucceeds.script-hash")));
        assertFalse(Files.exists(plutusDir.resolve("plutus.json")));
    }

    @Test
    void strictSchemaFailureReturnsOneAndPublishesNoBlueprint(@TempDir Path tempDir)
            throws Exception {
        Path project = tempDir.resolve("strict-failure");
        ProjectScaffolder.scaffold(project, "strict-failure");
        writeUnsupportedBoundary(ProjectLayout.srcDir(project).resolve("AlwaysSucceeds.java"));

        int exit = new CommandLine(new BuildCommand()).execute(project.toString());

        assertEquals(1, exit);
        Path plutusDir = ProjectLayout.plutusDir(project);
        assertFalse(Files.exists(plutusDir.resolve("plutus.json")));
        assertFalse(Files.exists(plutusDir.resolve("AlwaysSucceeds.uplc")));
        assertFalse(Files.exists(plutusDir.resolve("AlwaysSucceeds.compiledCode.hex")));
        assertFalse(Files.exists(plutusDir.resolve("AlwaysSucceeds.script-hash")));
    }

    @Test
    void failedStrictBuildPreservesTheCompleteLastGoodBuild(@TempDir Path tempDir)
            throws Exception {
        Path project = tempDir.resolve("last-good");
        ProjectScaffolder.scaffold(project, "last-good");
        assertEquals(0, new CommandLine(new BuildCommand()).execute(project.toString()));
        Path plutusDir = ProjectLayout.plutusDir(project);
        String oldBlueprint = Files.readString(plutusDir.resolve("plutus.json"));
        String oldUplc = Files.readString(plutusDir.resolve("AlwaysSucceeds.uplc"));
        String oldHex = Files.readString(plutusDir.resolve("AlwaysSucceeds.compiledCode.hex"));
        String oldHash = Files.readString(plutusDir.resolve("AlwaysSucceeds.script-hash"));

        writeUnsupportedBoundary(ProjectLayout.srcDir(project).resolve("AlwaysSucceeds.java"));
        assertEquals(1, new CommandLine(new BuildCommand()).execute(project.toString()));

        assertEquals(oldBlueprint, Files.readString(plutusDir.resolve("plutus.json")));
        assertEquals(oldUplc, Files.readString(plutusDir.resolve("AlwaysSucceeds.uplc")));
        assertEquals(oldHex, Files.readString(plutusDir.resolve("AlwaysSucceeds.compiledCode.hex")));
        assertEquals(oldHash, Files.readString(plutusDir.resolve("AlwaysSucceeds.script-hash")));
    }

    @Test
    void successfulBuildWithNoValidatorsRemovesGeneratedArtifacts(@TempDir Path tempDir)
            throws Exception {
        Path project = tempDir.resolve("no-validators");
        ProjectScaffolder.scaffold(project, "no-validators");
        assertEquals(0, new CommandLine(new BuildCommand()).execute(project.toString()));
        Path plutusDir = ProjectLayout.plutusDir(project);

        Files.delete(ProjectLayout.srcDir(project).resolve("AlwaysSucceeds.java"));
        assertEquals(0, new CommandLine(new BuildCommand()).execute(project.toString()));

        assertFalse(Files.exists(plutusDir.resolve("plutus.json")));
        assertFalse(Files.exists(plutusDir.resolve("AlwaysSucceeds.uplc")));
        assertFalse(Files.exists(plutusDir.resolve("AlwaysSucceeds.compiledCode.hex")));
        assertFalse(Files.exists(plutusDir.resolve("AlwaysSucceeds.script-hash")));
    }

    private static void writeUnsupportedBoundary(Path source) throws Exception {
        Files.writeString(source, """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import com.bloxbean.cardano.julc.core.types.JulcArray;
                import java.math.BigInteger;

                @SpendingValidator class AlwaysSucceeds {
                    record Datum(JulcArray<BigInteger> values) {}
                    record Redeemer(BigInteger value) {}
                    @Entrypoint static boolean validate(Datum d, Redeemer r, ScriptContext c) {
                        return true;
                    }
                }
                """);
    }
}
