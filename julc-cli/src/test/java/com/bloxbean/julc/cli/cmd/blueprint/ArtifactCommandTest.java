package com.bloxbean.julc.cli.cmd.blueprint;

import com.bloxbean.cardano.julc.blueprint.BlueprintConfig;
import com.bloxbean.cardano.julc.blueprint.BlueprintGenerator;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void inspectsExactValidatorAndRecomputesHash() throws Exception {
        Path blueprint = writeBlueprint();

        var metadata = ArtifactCommand.inspect(blueprint, "Gate");

        assertEquals("gate", metadata.artifactId());
        assertEquals("Gate", metadata.title());
        assertEquals(64, metadata.compiledCodeSha256().length());
        assertEquals(56, metadata.cardanoScriptHash().length());
        assertEquals("1.1.0", metadata.uplcVersion());
        assertTrue(metadata.builtins().stream()
                .anyMatch(builtin -> builtin.name().equals("EqualsInteger")));
    }

    @Test
    void rejectsMissingExactTitle() throws Exception {
        Path blueprint = writeBlueprint();
        var error = assertThrows(IllegalArgumentException.class,
                () -> ArtifactCommand.inspect(blueprint, "Missing"));
        assertTrue(error.getMessage().contains("found 0"));
    }

    @Test
    void rejectsBlueprintHashMismatch() throws Exception {
        Path blueprint = writeBlueprint();
        String json = Files.readString(blueprint)
                .replaceFirst("\\\"hash\\\": \\\"[0-9a-f]+\\\"",
                        "\\\"hash\\\": \\\"00000000000000000000000000000000000000000000000000000000\\\"");
        Files.writeString(blueprint, json);

        var error = assertThrows(IllegalArgumentException.class,
                () -> ArtifactCommand.inspect(blueprint, "Gate"));
        assertTrue(error.getMessage().contains("Script hash mismatch"));
    }

    private Path writeBlueprint() throws Exception {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                @SpendingValidator
                class Gate {
                    record Datum(BigInteger value) {}
                    record Redeemer(BigInteger value) {}
                    @Entrypoint
                    static boolean validate(Datum datum, Redeemer redeemer, ScriptContext ctx) {
                        return datum.value() == redeemer.value();
                    }
                }
                """;
        var result = new JulcCompiler(StdlibRegistry.defaultRegistry()).compileContract(source);
        var generated = BlueprintGenerator.generate(
                new BlueprintConfig("artifact-test", "1"),
                List.of(new BlueprintGenerator.CompiledValidator(
                        "Gate", result.compileResult(), result.contractSchema())));
        Path blueprint = tempDir.resolve("plutus.json");
        Files.writeString(blueprint, generated.toJson());
        return blueprint;
    }
}
