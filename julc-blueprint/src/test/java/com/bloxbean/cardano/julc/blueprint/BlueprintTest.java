package com.bloxbean.cardano.julc.blueprint;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BlueprintTest {

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
    void schemaGeneratorExtractsSpendingValidator() {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.Validator;
                import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import com.bloxbean.cardano.julc.core.PlutusData;
                import java.math.BigInteger;

                @Validator
                public class MyValidator {
                    public sealed interface MyDatum permits DatumA, DatumB {}
                    public record DatumA(byte[] owner, BigInteger amount) implements MyDatum {}
                    public record DatumB(byte[] addr) implements MyDatum {}

                    public sealed interface MyRedeemer permits Claim, Cancel {}
                    public record Claim() implements MyRedeemer {}
                    public record Cancel() implements MyRedeemer {}

                    @Entrypoint
                    public static boolean validate(MyDatum datum, MyRedeemer redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;

        var schema = SchemaGenerator.extract(source);
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
        assertTrue(defs.containsKey("ByteArray"));
        assertTrue(defs.containsKey("Int"));

        // MyDatum should have 2 variants
        var myDatum = defs.get("MyDatum");
        assertNotNull(myDatum.anyOf());
        assertEquals(2, myDatum.anyOf().size());
        assertEquals("DatumA", myDatum.anyOf().get(0).title());
        assertEquals("DatumB", myDatum.anyOf().get(1).title());

        // DatumA should have 2 fields
        assertEquals(2, myDatum.anyOf().get(0).fields().size());
        assertEquals("owner", myDatum.anyOf().get(0).fields().get(0).title());
        assertEquals("amount", myDatum.anyOf().get(0).fields().get(1).title());

        // MyRedeemer: 2 variants, no fields
        var myRedeemer = defs.get("MyRedeemer");
        assertEquals(2, myRedeemer.anyOf().size());
        assertEquals("Claim", myRedeemer.anyOf().get(0).title());
        assertTrue(myRedeemer.anyOf().get(0).fields().isEmpty());
    }

    @Test
    void schemaGeneratorExtractsMintingValidator() {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.MintingPolicy;
                import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import com.bloxbean.cardano.julc.core.PlutusData;

                @MintingPolicy
                public class MyMinter {
                    @Entrypoint
                    public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;

        var schema = SchemaGenerator.extract(source);
        assertNotNull(schema);
        assertNull(schema.datum()); // Minting has no datum
        assertNotNull(schema.redeemer());
        assertEquals("redeemer", schema.redeemer().title());
        assertTrue(schema.redeemer().ref().contains("Data"));
    }
}
