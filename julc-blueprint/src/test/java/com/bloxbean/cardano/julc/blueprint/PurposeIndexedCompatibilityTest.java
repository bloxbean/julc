package com.bloxbean.cardano.julc.blueprint;

import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintLoader;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PurposeIndexedCompatibilityTest {

    @Test
    void repeatedPurposeEntriesPassPinnedCip57AndCardanoClientLib() throws Exception {
        try (var input = getClass().getResourceAsStream(
                "/cip57/purpose-indexed-multi-validator.json")) {
            if (input == null) throw new IllegalStateException("Missing compatibility fixture");
            byte[] json = input.readAllBytes();

            BlueprintValidator.validate(new String(json, StandardCharsets.UTF_8));

            var blueprint = PlutusBlueprintLoader.loadBlueprint(
                    new java.io.ByteArrayInputStream(json));
            assertEquals(2, blueprint.getValidators().size());
            var spend = blueprint.getValidators().get(0);
            var mint = blueprint.getValidators().get(1);
            assertEquals("ProtocolValidator.spend", spend.getTitle());
            assertEquals("spend", spend.getDatum().getPurpose());
            assertEquals("spend", spend.getRedeemer().getPurpose());
            assertEquals("ProtocolValidator.mint", mint.getTitle());
            assertNull(mint.getDatum());
            assertEquals("mint", mint.getRedeemer().getPurpose());
            assertEquals(spend.getCompiledCode(), mint.getCompiledCode());
            assertEquals(spend.getHash(), mint.getHash());
        }
    }

    @Test
    void cardanoClientLibLoadsRealGeneratorOutputWithColonNamespacedReferences()
            throws Exception {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;

                @MultiValidator
                class ProtocolValidator {
                    record State(BigInteger counter) {}
                    record Spend(BigInteger next) {}
                    record Mint(byte[] tokenName) {}
                    record Certify(byte[] credential) {}

                    @Entrypoint(purpose = Purpose.CERTIFY)
                    static boolean certify(Certify redeemer, ScriptContext ctx) {
                        return true;
                    }

                    @Entrypoint(purpose = Purpose.SPEND)
                    static boolean spend(State datum, Spend redeemer, ScriptContext ctx) {
                        return true;
                    }

                    @Entrypoint(purpose = Purpose.MINT)
                    static boolean mint(Mint redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var compiled = new JulcCompiler().compileContract(source);
        String json = BlueprintGenerator.generate(
                new BlueprintConfig("consumer-gate", "1.0.0"),
                List.of(new BlueprintGenerator.CompiledValidator(
                        "ProtocolValidator", compiled.compileResult(),
                        compiled.contractSchema())))
                .toJson();

        assertTrue(json.contains("#/definitions/ProtocolValidator:State"), json);
        assertTrue(json.contains("#/definitions/ProtocolValidator:Mint"), json);
        assertTrue(json.contains("#/definitions/ProtocolValidator:Certify"), json);
        var loaded = PlutusBlueprintLoader.loadBlueprint(new ByteArrayInputStream(
                json.getBytes(StandardCharsets.UTF_8)));

        assertEquals(3, loaded.getValidators().size());
        var mint = loaded.getValidators().stream()
                .filter(entry -> "ProtocolValidator.mint".equals(entry.getTitle()))
                .findFirst().orElseThrow();
        var spend = loaded.getValidators().stream()
                .filter(entry -> "ProtocolValidator.spend".equals(entry.getTitle()))
                .findFirst().orElseThrow();
        var publish = loaded.getValidators().stream()
                .filter(entry -> "ProtocolValidator.publish".equals(entry.getTitle()))
                .findFirst().orElseThrow();
        assertEquals("mint", mint.getRedeemer().getPurpose());
        assertEquals("spend", spend.getDatum().getPurpose());
        assertEquals("publish", publish.getRedeemer().getPurpose());
        assertEquals(spend.getCompiledCode(), mint.getCompiledCode());
        assertEquals(spend.getCompiledCode(), publish.getCompiledCode());
        assertEquals(spend.getHash(), mint.getHash());
        assertEquals(spend.getHash(), publish.getHash());
    }
}
