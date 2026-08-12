package com.bloxbean.julc.cli.cmd.verify;

import com.bloxbean.cardano.julc.blueprint.BlueprintConfig;
import com.bloxbean.cardano.julc.blueprint.BlueprintGenerator;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.julc.cli.JulcCommand;
import com.bloxbean.julc.cli.cmd.blueprint.ArtifactCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerificationProjectGeneratorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void generatesDeterministicStrictWorkspace() throws Exception {
        Path blueprint = writeBlueprint();
        Path output = tempDir.resolve("verification");

        var result = VerificationProjectGenerator.generate(
                blueprint, "StateGate", "spending", 12345, output, false);

        assertEquals("state-gate", result.artifactId());
        assertTrue(Files.isExecutable(output.resolve("scripts/verify.sh")));
        assertTrue(Files.readString(output.resolve("CheckedExecution.lean"))
                .contains("defaultFunSemanticsVariantE"));
        assertTrue(Files.readString(output.resolve("CheckedExecution.lean"))
                .contains("stepExhausted"));
        assertTrue(Files.readString(output.resolve("scripts/verify.sh"))
                .contains(manifestHash(output)));
        String schemas = Files.readString(output.resolve("GeneratedSchemas.lean"));
        assertTrue(schemas.contains("structure StateDatum where"));
        assertTrue(schemas.contains("owner : ByteString"));
        assertTrue(schemas.contains("state : Integer"));
        assertTrue(schemas.contains("Data.Constr 0 [r_owner, r_state]"));
        assertTrue(schemas.contains("| _, _ => none"));

        var manifest = JSON.readTree(output.resolve("verification-manifest.json").toFile());
        assertEquals("E", manifest.path("builtinSemanticsVariant").asText());
        assertEquals(11, manifest.path("protocolVersion").asInt());
        assertEquals(12345, manifest.path("fuel").asInt());
        assertEquals("COULD-NOT-EVALUATE",
                manifest.path("properties").get(0).path("result").asText());

        String firstManifest = Files.readString(output.resolve("verification-manifest.json"));
        VerificationProjectGenerator.generate(
                blueprint, "StateGate", "spending", 12345, output, true);
        assertEquals(firstManifest, Files.readString(output.resolve("verification-manifest.json")));
    }

    @Test
    void generatesStrictVariantEncoding() throws Exception {
        var document = JSON.readTree("""
                {
                  "validators": [{
                    "title": "Gate",
                    "redeemer": {"schema": {"$ref": "#/definitions/Action"}}
                  }],
                  "definitions": {
                    "Int": {"dataType": "integer"},
                    "Action": {"anyOf": [
                      {"title": "Stop", "dataType": "constructor", "index": 0,
                       "fields": []},
                      {"title": "Advance", "dataType": "constructor", "index": 1,
                       "fields": [{"title": "amount", "$ref": "#/definitions/Int"}]}
                    ]}
                  }
                }
                """);

        var result = VerificationProjectGenerator.generateSchemas(
                document.path("definitions"), document.path("validators").get(0));

        assertTrue(result.source().contains("inductive Action where"));
        assertTrue(result.source().contains("| Stop"));
        assertTrue(result.source().contains("| Advance (amount : Integer)"));
        assertTrue(result.source().contains("Data.Constr 0 []"));
        assertTrue(result.source().contains("Data.Constr 1 [r_amount]"));
    }

    @Test
    void rejectsUnsupportedSchemaWithoutWritingWorkspace() throws Exception {
        Path blueprint = writeBlueprint();
        var root = (com.fasterxml.jackson.databind.node.ObjectNode)
                JSON.readTree(blueprint.toFile());
        var unsupportedField = (com.fasterxml.jackson.databind.node.ObjectNode)
                root.path("definitions").path("StateDatum").path("anyOf")
                        .get(0).path("fields").get(1);
        unsupportedField.remove("$ref");
        unsupportedField.put("dataType", "future-container");
        JSON.writerWithDefaultPrettyPrinter().writeValue(blueprint.toFile(), root);
        Path output = tempDir.resolve("unsupported");

        var error = assertThrows(UnsupportedVerificationException.class,
                () -> VerificationProjectGenerator.generate(
                        blueprint, "StateGate", "spending", 100, output, false));

        assertTrue(error.getMessage().toLowerCase(java.util.Locale.ROOT).contains("unsupported"));
        assertFalse(Files.exists(output));
    }

    @Test
    void generatesStrictBooleanOptionalListMapAndNestedTypes() throws Exception {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                import java.util.List;
                import java.util.Map;
                import java.util.Optional;

                @MintingValidator
                class ContainerGate {
                    record Redeemer(List<BigInteger> values,
                                    Map<byte[], BigInteger> balances,
                                    Optional<List<Map<byte[], BigInteger>>> nested,
                                    boolean enabled) {}
                    @Entrypoint
                    static boolean validate(Redeemer redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry()).compileContract(source);
        var blueprint = BlueprintGenerator.generate(
                new BlueprintConfig("container-generator-test", "1"),
                List.of(new BlueprintGenerator.CompiledValidator(
                        "ContainerGate", compiled.compileResult(), compiled.contractSchema())));
        var document = JSON.readTree(blueprint.toJson());

        var result = VerificationProjectGenerator.generateSchemas(
                document.path("definitions"), document.path("validators").get(0));

        assertTrue(result.source().contains("structure JulcList (α : Type)"));
        assertTrue(result.source().contains("structure JulcMap (κ υ : Type)"));
        assertTrue(result.source().contains("values : JulcList (Integer)"));
        assertTrue(result.source().contains("balances : JulcMap (ByteString) (Integer)"));
        assertTrue(result.source().contains(
                "nested : Option (JulcList (JulcMap (ByteString) (Integer)))"));
        assertTrue(result.source().contains("enabled : Bool"));
        assertTrue(result.source().contains("Data.List (encodeDataList values.items)"));
        assertTrue(result.source().contains("Data.Map (encodeDataMap values.entries)"));
        assertFalse(result.source().contains("inductive JulcOptional"));
        assertEquals("Redeemer", result.leanTypes().get("Redeemer"));
    }

    @Test
    void rejectsMalformedContainerSchema() throws Exception {
        var document = JSON.readTree("""
                {
                  "validators": [{
                    "title": "Gate",
                    "redeemer": {"schema": {"$ref": "#/definitions/Values"}}
                  }],
                  "definitions": {
                    "Values": {"dataType": "list"}
                  }
                }
                """);

        var error = assertThrows(UnsupportedVerificationException.class,
                () -> VerificationProjectGenerator.generateSchemas(
                        document.path("definitions"), document.path("validators").get(0)));

        assertTrue(error.getMessage().contains("requires items"));
    }

    @Test
    void rejectsMapWithoutKeysOrValues() throws Exception {
        for (String mapSchema : List.of(
                "{\"dataType\": \"map\", \"values\": {\"dataType\": \"integer\"}}",
                "{\"dataType\": \"map\", \"keys\": {\"dataType\": \"bytes\"}}")) {
            var document = JSON.readTree("""
                    {
                      "validators": [{
                        "title": "Gate",
                        "redeemer": {"schema": {"$ref": "#/definitions/Balances"}}
                      }],
                      "definitions": {"Balances": %s}
                    }
                    """.formatted(mapSchema));

            var error = assertThrows(UnsupportedVerificationException.class,
                    () -> VerificationProjectGenerator.generateSchemas(
                            document.path("definitions"),
                            document.path("validators").get(0)));

            assertTrue(error.getMessage().contains("requires keys and values"));
        }
    }

    @Test
    void refusesNonEmptyOutputWithoutForceAndPreservesUnknownFiles() throws Exception {
        Path blueprint = writeBlueprint();
        Path output = tempDir.resolve("existing");
        Files.createDirectories(output);
        Path userFile = output.resolve("UserProperty.lean");
        Files.writeString(userFile, "-- user owned\n");

        assertThrows(IllegalArgumentException.class,
                () -> VerificationProjectGenerator.generate(
                        blueprint, "StateGate", "spending", 100, output, false));

        VerificationProjectGenerator.generate(
                blueprint, "StateGate", "spending", 100, output, true);
        assertEquals("-- user owned\n", Files.readString(userFile));

        Path securityProperty = output.resolve("SecurityProperty.lean");
        Files.writeString(securityProperty, "-- specialized property\n");
        VerificationProjectGenerator.generate(
                blueprint, "StateGate", "spending", 100, output, true);
        assertEquals("-- specialized property\n", Files.readString(securityProperty));
    }

    @Test
    void exposesVerifyCommandInRootCli() {
        var commandLine = new CommandLine(new JulcCommand());
        assertTrue(commandLine.getSubcommands().containsKey("verify"));
        assertTrue(commandLine.getSubcommands().get("verify")
                .getSubcommands().containsKey("init"));
    }

    @Test
    void rejectsNonPositiveFuel() throws Exception {
        Path blueprint = writeBlueprint();
        var error = assertThrows(IllegalArgumentException.class,
                () -> VerificationProjectGenerator.generate(
                        blueprint, "StateGate", "spending", 0,
                        tempDir.resolve("zero"), false));
        assertTrue(error.getMessage().contains("positive"));
    }

    @Test
    void rejectsPurposeThatContradictsBlueprintShape() throws Exception {
        Path blueprint = writeBlueprint();
        var error = assertThrows(UnsupportedVerificationException.class,
                () -> VerificationProjectGenerator.generate(
                        blueprint, "StateGate", "minting", 100,
                        tempDir.resolve("wrong-purpose"), false));
        assertTrue(error.getMessage().contains("Minting"));
    }

    @Test
    void rejectsBuiltinOutsidePinnedBlasterCoverage() {
        var error = assertThrows(UnsupportedVerificationException.class,
                () -> VerificationProjectGenerator.ensureSupportedBuiltins(List.of(
                        new ArtifactCommand.BuiltinUse("LengthOfArray", 89))));
        assertTrue(error.getMessage().contains("tag 89"));
    }

    @Test
    void rejectsRecursiveSchema() throws Exception {
        var document = JSON.readTree("""
                {
                  "validators": [{
                    "title": "Gate",
                    "redeemer": {"schema": {"$ref": "#/definitions/Node"}}
                  }],
                  "definitions": {
                    "Node": {"anyOf": [{
                      "title": "Node", "dataType": "constructor", "index": 0,
                      "fields": [{"title": "next", "$ref": "#/definitions/Node"}]
                    }]}
                  }
                }
                """);
        var error = assertThrows(UnsupportedVerificationException.class,
                () -> VerificationProjectGenerator.generateSchemas(
                        document.path("definitions"), document.path("validators").get(0)));
        assertTrue(error.getMessage().contains("Recursive"));
    }

    @Test
    void rejectsRecursionThroughContainerItems() throws Exception {
        var document = JSON.readTree("""
                {
                  "validators": [{
                    "title": "Gate",
                    "redeemer": {"schema": {"$ref": "#/definitions/Values"}}
                  }],
                  "definitions": {
                    "Values": {
                      "dataType": "list",
                      "items": {"$ref": "#/definitions/Values"}
                    }
                  }
                }
                """);

        var error = assertThrows(UnsupportedVerificationException.class,
                () -> VerificationProjectGenerator.generateSchemas(
                        document.path("definitions"), document.path("validators").get(0)));

        assertTrue(error.getMessage().contains("Recursive"));
    }

    @Test
    void rejectsSchemaNamesThatShadowGeneratedBoolOrOptionTypes() throws Exception {
        for (String schemaName : List.of("Bool", "bool", "Option", "option")) {
            var document = JSON.readTree("""
                    {
                      "validators": [{
                        "title": "Gate",
                        "redeemer": {"schema": {"$ref": "#/definitions/%s"}}
                      }],
                      "definitions": {
                        "%s": {"anyOf": [{
                          "title": "Wrapped", "dataType": "constructor", "index": 0,
                          "fields": [{"title": "value", "dataType": "integer"}]
                        }]}
                      }
                    }
                    """.formatted(schemaName, schemaName));

            var error = assertThrows(UnsupportedVerificationException.class,
                    () -> VerificationProjectGenerator.generateSchemas(
                            document.path("definitions"),
                            document.path("validators").get(0)));

            assertTrue(error.getMessage().contains("conflicts with generated Lean imports"));
            assertTrue(error.getMessage().contains(
                    Character.toUpperCase(schemaName.charAt(0)) + schemaName.substring(1)));
        }
    }

    @Test
    void rejectsSchemaNamesThatCollideAfterLeanNormalization() throws Exception {
        var document = JSON.readTree("""
                {
                  "validators": [{
                    "title": "Gate",
                    "datum": {"schema": {"$ref": "#/definitions/Foo-Bar"}},
                    "redeemer": {"schema": {"$ref": "#/definitions/Foo_Bar"}}
                  }],
                  "definitions": {
                    "Foo-Bar": {"anyOf": [{
                      "title": "First", "dataType": "constructor", "index": 0,
                      "fields": []
                    }]},
                    "Foo_Bar": {"anyOf": [{
                      "title": "Second", "dataType": "constructor", "index": 0,
                      "fields": []
                    }]}
                  }
                }
                """);
        var error = assertThrows(UnsupportedVerificationException.class,
                () -> VerificationProjectGenerator.generateSchemas(
                        document.path("definitions"), document.path("validators").get(0)));
        assertTrue(error.getMessage().contains("collide"));
    }

    @Test
    void rejectsSchemaNameWithoutLeanIdentifierCharacters() throws Exception {
        var document = JSON.readTree("""
                {
                  "validators": [{
                    "title": "Gate",
                    "redeemer": {"schema": {"$ref": "#/definitions/!!!"}}
                  }],
                  "definitions": {
                    "!!!": {"anyOf": [{
                      "title": "Only", "dataType": "constructor", "index": 0,
                      "fields": []
                    }]}
                  }
                }
                """);
        var error = assertThrows(UnsupportedVerificationException.class,
                () -> VerificationProjectGenerator.generateSchemas(
                        document.path("definitions"), document.path("validators").get(0)));
        assertTrue(error.getMessage().contains("cannot form"));
    }

    @Test
    void resolvesEscapedJsonPointerDefinitionNames() throws Exception {
        var document = JSON.readTree("""
                {
                  "validators": [{
                    "title": "Gate",
                    "redeemer": {"schema": {"$ref": "#/definitions/Foo~0Bar"}}
                  }],
                  "definitions": {
                    "Foo~Bar": {"anyOf": [{
                      "title": "Only", "dataType": "constructor", "index": 0,
                      "fields": []
                    }]}
                  }
                }
                """);

        var result = VerificationProjectGenerator.generateSchemas(
                document.path("definitions"), document.path("validators").get(0));

        assertTrue(result.source().contains("inductive FooBar where"));
    }

    @Test
    void rejectsAmbiguousValidatorTitleWithoutWritingWorkspace() throws Exception {
        Path blueprint = writeBlueprint();
        var root = (com.fasterxml.jackson.databind.node.ObjectNode)
                JSON.readTree(blueprint.toFile());
        var validators = (com.fasterxml.jackson.databind.node.ArrayNode)
                root.path("validators");
        validators.add(validators.get(0).deepCopy());
        JSON.writerWithDefaultPrettyPrinter().writeValue(blueprint.toFile(), root);
        Path output = tempDir.resolve("ambiguous");

        var error = assertThrows(IllegalArgumentException.class,
                () -> VerificationProjectGenerator.generate(
                        blueprint, "StateGate", "spending", 100, output, false));
        assertTrue(error.getMessage().contains("found 2"));
        assertFalse(Files.exists(output));
    }

    @Test
    void rejectsNonV3Blueprint() throws Exception {
        Path blueprint = writeBlueprint();
        var root = (com.fasterxml.jackson.databind.node.ObjectNode)
                JSON.readTree(blueprint.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("preamble"))
                .put("plutusVersion", "v2");
        JSON.writerWithDefaultPrettyPrinter().writeValue(blueprint.toFile(), root);

        var error = assertThrows(UnsupportedVerificationException.class,
                () -> VerificationProjectGenerator.generate(
                        blueprint, "StateGate", "spending", 100,
                        tempDir.resolve("v2"), false));
        assertTrue(error.getMessage().contains("Plutus V3"));
    }

    private Path writeBlueprint() throws Exception {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                @SpendingValidator
                class StateGate {
                    record StateDatum(byte[] owner, BigInteger state) {}
                    record Transition(BigInteger nextState) {}
                    @Entrypoint
                    static boolean validate(StateDatum datum, Transition redeemer,
                            ScriptContext ctx) {
                        return redeemer.nextState().compareTo(datum.state()) > 0;
                    }
                }
                """;
        var result = new JulcCompiler(StdlibRegistry.defaultRegistry()).compileContract(source);
        var generated = BlueprintGenerator.generate(
                new BlueprintConfig("verification-generator-test", "1"),
                List.of(new BlueprintGenerator.CompiledValidator(
                        "StateGate", result.compileResult(), result.contractSchema())));
        Path blueprint = tempDir.resolve("plutus-" + System.nanoTime() + ".json");
        Files.writeString(blueprint, generated.toJson());
        return blueprint;
    }

    private String manifestHash(Path output) throws Exception {
        return JSON.readTree(output.resolve("verification-manifest.json").toFile())
                .path("compiledCodeSha256").asText();
    }
}
