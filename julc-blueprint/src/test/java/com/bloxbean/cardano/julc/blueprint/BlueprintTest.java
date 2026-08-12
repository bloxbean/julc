package com.bloxbean.cardano.julc.blueprint;

import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.compiler.schema.ContractSchema;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class BlueprintTest {

    @Test
    void libraryNativeImageMetadataOwnsPinnedCip57Resources() throws Exception {
        var loader = BlueprintValidator.class.getClassLoader();
        var metadata = Objects.requireNonNull(loader.getResource(
                "META-INF/native-image/com.bloxbean.cardano/julc-blueprint/"
                        + "reachability-metadata.json"));
        String json;
        try (var input = metadata.openStream()) {
            json = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertTrue(json.contains("\"glob\": \"cip57/**\""));
        assertNotNull(loader.getResource("cip57/plutus-blueprint.json"));
        assertNotNull(loader.getResource("cip57/plutus-data.json"));
        assertNotNull(loader.getResource("cip57/plutus-builtin.json"));
    }

    @Test
    void toJsonProducesValidStructure() {
        var blueprint = new Blueprint(
                new Blueprint.Preamble("test", "0.1.0", "v3",
                        new Blueprint.Compiler("julc", "0.1.0")),
                List.of(new Blueprint.ValidatorEntry("MyValidator", "abcdef", "123456", 42,
                        null, null, null)),
                null
        );

        String json = blueprint.toJson();
        assertTrue(json.contains("\"title\": \"test\""));
        assertTrue(json.contains("\"plutusVersion\": \"v3\""));
        assertTrue(json.contains("\"compiledCode\": \"abcdef\""));
        assertTrue(json.contains("\"hash\": \"123456\""));
        assertTrue(json.contains("\"name\": \"julc\""));
    }

    @Test
    void toJsonEscapesSpecialChars() {
        var blueprint = new Blueprint(
                new Blueprint.Preamble("test\"name", "0.1.0", "v3",
                        new Blueprint.Compiler("julc", "0.1.0")),
                List.of(),
                null
        );

        String json = blueprint.toJson();
        assertTrue(json.contains("test\\\"name"));
    }

    @Test
    void toJsonMultipleValidators() {
        var blueprint = new Blueprint(
                new Blueprint.Preamble("multi", "0.1.0", "v3",
                        new Blueprint.Compiler("julc", "0.1.0")),
                List.of(
                        new Blueprint.ValidatorEntry("V1", "aa", "bb", 10, null, null, null),
                        new Blueprint.ValidatorEntry("V2", "cc", "dd", 20, null, null, null)
                ),
                null
        );

        String json = blueprint.toJson();
        assertTrue(json.contains("\"title\": \"V1\""));
        assertTrue(json.contains("\"title\": \"V2\""));
    }

    @Test
    void toJsonWithDatumRedeemer() {
        var datum = SchemaGenerator.Schema.ref("datum", "#/definitions/EscrowDatum");
        var redeemer = SchemaGenerator.Schema.ref("redeemer", "#/definitions/EscrowAction");
        var defs = new java.util.LinkedHashMap<String, SchemaGenerator.Schema>();
        defs.put("Int", SchemaGenerator.Schema.primitive("integer"));
        defs.put("ByteArray", SchemaGenerator.Schema.primitive("bytes"));

        var blueprint = new Blueprint(
                new Blueprint.Preamble("test", "0.1.0", "v3",
                        new Blueprint.Compiler("julc", "0.1.0")),
                List.of(new Blueprint.ValidatorEntry("MyValidator", "abcdef", "123456", 42,
                        datum, redeemer, null)),
                defs
        );

        String json = blueprint.toJson();
        assertTrue(json.contains("\"datum\""));
        assertTrue(json.contains("\"redeemer\""));
        assertTrue(json.contains("\"$ref\": \"#/definitions/EscrowDatum\""));
        assertTrue(json.contains("\"$ref\": \"#/definitions/EscrowAction\""));
        assertTrue(json.contains("\"definitions\""));
        assertTrue(json.contains("\"dataType\": \"integer\""));
        assertTrue(json.contains("\"dataType\": \"bytes\""));
    }

    @Test
    void schemaGeneratorUsesResolvedCompilerTypesForNestedContainers() {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
                import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                import java.util.List;
                import java.util.Map;
                import java.util.Optional;

                @SpendingValidator
                public class MyValidator {
                    public record MyDatum(
                            List<BigInteger> values,
                            Map<byte[], BigInteger> balances,
                            Optional<List<Map<byte[], BigInteger>>> nested,
                            boolean active) {}

                    public sealed interface MyRedeemer permits Claim, Cancel {}
                    public record Claim(byte[] owner) implements MyRedeemer {}
                    public record Cancel() implements MyRedeemer {}

                    @Entrypoint
                    public static boolean validate(MyDatum datum, MyRedeemer redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;

        var compiled = new com.bloxbean.cardano.julc.compiler.JulcCompiler().compileContract(source);
        var schema = SchemaGenerator.from(compiled.contractSchema());
        assertNotNull(schema);
        assertNotNull(schema.datum());
        assertEquals("datum", schema.datum().title());
        assertTrue(schema.datum().ref().contains("MyDatum"));
        assertNotNull(schema.redeemer());
        assertEquals("redeemer", schema.redeemer().title());
        assertTrue(schema.redeemer().ref().contains("MyRedeemer"));

        // Check definitions
        var defs = schema.definitions();
        assertTrue(defs.containsKey("MyDatum"));
        assertTrue(defs.containsKey("MyRedeemer"));
        // MyDatum is one constructor with fully preserved nested field schemas.
        var myDatum = defs.get("MyDatum");
        assertNotNull(myDatum.anyOf());
        assertEquals(1, myDatum.anyOf().size());
        var fields = myDatum.anyOf().getFirst().fields();
        assertEquals("list", fields.get(0).dataType());
        assertEquals("integer", fields.get(0).items().dataType());
        assertEquals("map", fields.get(1).dataType());
        assertEquals("bytes", fields.get(1).keys().dataType());
        assertEquals("integer", fields.get(1).values().dataType());

        var optional = fields.get(2);
        assertEquals(2, optional.anyOf().size());
        assertEquals(0, optional.anyOf().get(0).index());
        assertEquals("list", optional.anyOf().get(0).fields().getFirst().dataType());
        assertEquals("map", optional.anyOf().get(0).fields().getFirst().items().dataType());
        assertEquals(1, optional.anyOf().get(1).index());
        assertTrue(optional.anyOf().get(1).fields().isEmpty());

        var bool = fields.get(3);
        assertEquals(List.of(0, 1), bool.anyOf().stream().map(SchemaGenerator.Schema::index).toList());
        assertEquals(List.of("False", "True"),
                bool.anyOf().stream().map(SchemaGenerator.Schema::title).toList());

        // MyRedeemer: 2 variants, no fields
        var myRedeemer = defs.get("MyRedeemer");
        assertEquals(2, myRedeemer.anyOf().size());
        assertEquals("Claim", myRedeemer.anyOf().get(0).title());
        assertEquals("bytes", myRedeemer.anyOf().get(0).fields().getFirst().dataType());
    }

    @Test
    void blueprintSerializesCip57ContainerAndConstructorShapes() {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.MintingValidator;
                import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                import java.util.List;

                @MintingValidator
                public class MyMinter {
                    public record Redeemer(List<BigInteger> values, boolean enabled) {}

                    @Entrypoint
                    public static boolean validate(Redeemer redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;

        var compiled = new com.bloxbean.cardano.julc.compiler.JulcCompiler().compileContract(source);
        var blueprint = BlueprintGenerator.generate(
                new BlueprintConfig("test", "1.0.0"),
                List.of(new BlueprintGenerator.CompiledValidator(
                        "MyMinter", compiled.compileResult(), compiled.contractSchema())));
        String json = blueprint.toJson();

        assertTrue(json.contains("\"$schema\""));
        assertTrue(json.contains("\"dataType\": \"list\""));
        assertTrue(json.contains("\"items\": {"));
        assertTrue(json.contains("\"title\": \"False\""));
        assertTrue(json.contains("\"title\": \"True\""));
        assertFalse(json.contains("\"dataType\": \"boolean\""));
    }

    @Test
    void advertisedContainerEncodingIsAcceptedByTheSameCompiledValidator() {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                import java.util.List;
                import java.util.Map;
                import java.util.Optional;

                @MintingValidator
                class EncodingGate {
                    record Redeemer(List<BigInteger> values,
                                    Map<byte[], BigInteger> balances,
                                    Optional<byte[]> owner,
                                    boolean active) {}

                    @Entrypoint
                    static boolean validate(Redeemer redeemer, ScriptContext ctx) {
                        byte[] owner = redeemer.owner().get();
                        return redeemer.values().get(0) == 42
                                && redeemer.balances().get(owner) == 7
                                && redeemer.active();
                    }
                }
                """;

        var compiled = new com.bloxbean.cardano.julc.compiler.JulcCompiler()
                .compileContract(source);
        var fields = SchemaGenerator.from(compiled.contractSchema()).definitions()
                .get("Redeemer").anyOf().getFirst().fields();
        assertEquals("list", fields.get(0).dataType());
        assertEquals("map", fields.get(1).dataType());
        assertEquals(List.of(0, 1), fields.get(2).anyOf().stream()
                .map(SchemaGenerator.Schema::index).toList());
        assertEquals(List.of(0, 1), fields.get(3).anyOf().stream()
                .map(SchemaGenerator.Schema::index).toList());

        // Redeemer = Constr 0 [ListData, MapData, Some = Constr 0, True = Constr 1].
        var redeemer = PlutusData.constr(0,
                PlutusData.list(PlutusData.integer(42)),
                PlutusData.map(new PlutusData.Pair(
                        PlutusData.bytes(new byte[0]), PlutusData.integer(7))),
                PlutusData.constr(0, PlutusData.bytes(new byte[0])),
                PlutusData.constr(1));
        var context = PlutusData.constr(0,
                PlutusData.integer(0), redeemer, PlutusData.integer(0));
        var result = JulcVm.create().evaluateWithArgs(
                compiled.compileResult().program(), List.of(context));
        assertTrue(result.isSuccess(),
                "compiled validator must consume the encoding advertised by its schema: " + result);
    }

    @Test
    void emitsRecursiveCip57ReferenceFromCompilerOwnedTypeGraph() {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;

                @SpendingValidator
                class RecursiveBlueprintGate {
                    sealed interface Node permits End, Cons {}
                    record End() implements Node {}
                    record Cons(BigInteger value, Node next) implements Node {}
                    record Datum(Node root) {}
                    record Redeemer(BigInteger expected) {}

                    @Entrypoint
                    static boolean validate(Datum datum, Redeemer redeemer,
                                            ScriptContext ctx) {
                        return true;
                    }
                }
                """;

        var compiled = new JulcCompiler().compileContract(source);
        var schema = SchemaGenerator.from(compiled.contractSchema());
        var node = schema.definitions().get("Node");
        assertNotNull(node);
        var cons = node.anyOf().stream()
                .filter(constructor -> constructor.title().equals("Cons"))
                .findFirst().orElseThrow();
        assertEquals("#/definitions/Node", cons.fields().get(1).ref());

        var blueprint = BlueprintGenerator.generate(
                new BlueprintConfig("recursive", "1"),
                List.of(new BlueprintGenerator.CompiledValidator(
                        "RecursiveBlueprintGate", compiled.compileResult(),
                        compiled.contractSchema())));
        assertDoesNotThrow(blueprint::toJson);
    }

    @Test
    void pinnedOfficialMetaSchemaRejectsMalformedBlueprint() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> BlueprintValidator.validate("{\"validators\": []}"));
        assertTrue(error.getMessage().contains("preamble"));
        assertEquals("0ed8837a02ed78b64847e5646f9572ee1830c7ba",
                BlueprintValidator.CIP_REVISION);
    }

    @Test
    void strictValidationRejectsMalformedDefinitionBodies() {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import java.math.BigInteger;
                @MintingValidator class BodyGate {
                    record Redeemer(BigInteger value) {}
                    @Entrypoint static boolean validate(Redeemer redeemer, BigInteger ctx) {
                        return true;
                    }
                }
                """;
        var compiled = new JulcCompiler().compileContract(source);
        String valid = BlueprintGenerator.generate(
                new BlueprintConfig("test", "1.0.0"),
                List.of(new BlueprintGenerator.CompiledValidator(
                        "BodyGate", compiled.compileResult(), compiled.contractSchema())))
                .toJson();

        String malformed = valid.replaceFirst(
                "\\\"dataType\\\"\\s*:\\s*\\\"integer\\\"",
                "\\\"dataType\\\": \\\"list\\\"");
        var error = assertThrows(IllegalArgumentException.class,
                () -> BlueprintValidator.validate(malformed));
        assertTrue(error.getMessage().contains("requires 'items'"), error.getMessage());
    }

    @Test
    void canonicalAndReservedDefinitionKeysDoNotRejectValidSchemas() {
        var datumType = new PirType.RecordType("Data", List.of(
                new PirType.Field("text", new PirType.StringType()),
                new PirType.Field("bytes", new PirType.ByteStringType())));
        var schema = new ContractSchema(
                "spending",
                new ContractSchema.Argument("datum", datumType, null),
                new ContractSchema.Argument("redeemer", new PirType.DataType(), null),
                List.of(
                        new ContractSchema.Argument("text", new PirType.StringType(), null),
                        new ContractSchema.Argument("bytes", new PirType.ByteStringType(), null)));

        var generated = SchemaGenerator.from(schema);
        assertTrue(generated.definitions().containsKey("Data"),
                "user record keeps its legal Java name");
        assertTrue(generated.definitions().containsKey("@julc:Data"),
                "opaque builtin uses an unreachable reserved name");
        assertTrue(generated.definitions().containsKey("@julc:ByteArray"));
    }

    @Test
    void sameSimpleNamesAreNamespacedAcrossValidators() {
        String v1 = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                @SpendingValidator class V1 {
                    record Datum(BigInteger count) {}
                    record Redeemer(BigInteger value) {}
                    @Entrypoint static boolean validate(Datum d, Redeemer r, ScriptContext c) {
                        return true;
                    }
                }
                """;
        String v2 = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                @SpendingValidator class V2 {
                    record Datum(byte[] owner) {}
                    record Redeemer(byte[] signature) {}
                    @Entrypoint static boolean validate(Datum d, Redeemer r, ScriptContext c) {
                        return true;
                    }
                }
                """;
        var compiler = new JulcCompiler();
        var first = compiler.compileContract(v1);
        var second = compiler.compileContract(v2);

        var blueprint = BlueprintGenerator.generate(
                new BlueprintConfig("multi", "1.0.0"),
                List.of(
                        new BlueprintGenerator.CompiledValidator(
                                "V1", first.compileResult(), first.contractSchema()),
                        new BlueprintGenerator.CompiledValidator(
                                "V2", second.compileResult(), second.contractSchema())));

        assertTrue(blueprint.definitions().containsKey("V1:Datum"));
        assertTrue(blueprint.definitions().containsKey("V2:Datum"));
        assertEquals("#/definitions/V1:Datum",
                blueprint.validators().get(0).datum().ref());
        assertEquals("#/definitions/V2:Datum",
                blueprint.validators().get(1).datum().ref());
    }

    @Test
    void atomicWriterPublishesNormallyReadableFile(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("plutus.json");
        BlueprintFileWriter.writeAtomically(target, "{}");
        assertEquals("{}", Files.readString(target));

        if (Files.getFileStore(target).supportsFileAttributeView("posix")) {
            Path control = tempDir.resolve("normal-write.json");
            Files.writeString(control, "{}");
            var permissions = Files.getPosixFilePermissions(target);
            assertEquals(Files.getPosixFilePermissions(control), permissions,
                    "new blueprint permissions must match a normal file under the active umask");

            var custom = java.util.Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ);
            Files.setPosixFilePermissions(target, custom);
            BlueprintFileWriter.writeAtomically(target, "{\"updated\":true}");
            assertEquals(custom, Files.getPosixFilePermissions(target),
                    "replacing an existing artifact preserves its permissions");
        }
    }

    @Test
    void unsupportedCompilerBoundaryDoesNotFallBackToOpaqueData() {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import com.bloxbean.cardano.julc.core.types.JulcArray;
                import java.math.BigInteger;

                @SpendingValidator
                class ArrayGate {
                    record Datum(JulcArray<BigInteger> values) {}
                    record Redeemer(BigInteger value) {}
                    @Entrypoint
                    static boolean validate(Datum datum, Redeemer redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;

        var compiled = new com.bloxbean.cardano.julc.compiler.JulcCompiler()
                .compileContract(source);
        var error = assertThrows(SchemaGenerator.SchemaGenerationException.class,
                () -> SchemaGenerator.from(compiled.contractSchema()));
        assertTrue(error.getMessage().contains("ArrayType"));
        assertTrue(error.getMessage().contains("ArrayGate.java"));
        assertFalse(error.getMessage().contains("Any Plutus data"));
    }

    @Test
    void sameSimpleNamedTypesWithDifferentShapesFailClosed() {
        String validator = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                @SpendingValidator
                class CollisionGate {
                    record Datum(a.Foo left, b.Foo right) {}
                    record Redeemer(long value) {}
                    @Entrypoint
                    static boolean validate(Datum datum, Redeemer redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        String left = """
                package a;
                import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary public record Foo(long value) {}
                """;
        String right = """
                package b;
                import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary public record Foo(byte[] value) {}
                """;

        var compiled = new com.bloxbean.cardano.julc.compiler.JulcCompiler()
                .compileContract(validator, List.of(left, right));
        var error = assertThrows(SchemaGenerator.SchemaGenerationException.class,
                () -> SchemaGenerator.from(compiled.contractSchema()));
        assertTrue(error.getMessage().contains("collision"));
        assertTrue(error.getMessage().contains("Foo"));
    }
}
