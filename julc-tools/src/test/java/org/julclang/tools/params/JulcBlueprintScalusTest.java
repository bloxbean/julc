package org.julclang.tools.params;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.julclang.blueprint.BlueprintConfig;
import org.julclang.blueprint.BlueprintGenerator;
import org.julclang.compiler.JulcCompiler;
import org.julclang.core.PlutusData;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.tools.model.ApplyParamsModels.Request;
import org.julclang.tools.model.ApplyParamsModels.Response;
import org.julclang.tools.model.MockTransaction.DataInput;
import org.julclang.tools.model.UplcModels.ScriptInput;
import org.julclang.tools.params.ApplyParamsException.Kind;
import org.julclang.tools.uplc.ScriptDecoder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import scalus.uplc.DeBruijnedProgram;
import scalus.uplc.DeBruijnedProgram$;
import scalus.uplc.builtin.Data$;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-077 I4 and I5 on scripts JuLC makes: the blueprint JuLC generates for a validator with {@code @Param} fields of
 * several types accepts conforming values and refuses others, and Scalus 1.1.0, an independent implementation, applies
 * the same values to the same script with the same bytes and hash.
 * <p>
 * Scalus' domain excludes constructor indices of 2^63 and above ("Expected Long but got OverLong"); generated values
 * stay below it, and {@link AikenOracleTest} covers the range above.
 */
class JulcBlueprintScalusTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final HexFormat HEX = HexFormat.of();

    static final String SOURCE = """
            import org.julclang.stdlib.annotation.*;
            import org.julclang.ledger.ScriptContext;
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.JulcList;
            import java.math.BigInteger;
            import java.util.Optional;

            @SpendingValidator
            public class ParamVault {
                public record Config(byte[] admin, BigInteger fee, JulcList<BigInteger> tiers) {}
                public sealed interface Mode permits Open, Closed {}
                public record Open() implements Mode {}
                public record Closed(BigInteger since) implements Mode {}

                @Param static byte[] owner;
                @Param static BigInteger deadline;
                @Param static Config config;
                @Param static Mode mode;
                @Param static JulcList<byte[]> admins;
                @Param static PlutusData extra;

                @Entrypoint
                static boolean validate(Optional<PlutusData> datum, PlutusData redeemer, ScriptContext ctx) {
                    return deadline.signum() > 0;
                }
            }
            """;

    static String blueprint;
    static JsonNode entry;

    @BeforeAll
    static void compile() throws Exception {
        var compiled = new JulcCompiler().compileContract(SOURCE);
        blueprint = BlueprintGenerator.generate(new BlueprintConfig("params", "1.0.0"),
                List.of(new BlueprintGenerator.CompiledValidator("ParamVault", compiled.compileResult(),
                        compiled.contractSchema()))).toJson();
        entry = JSON.readTree(blueprint).get("validators").get(0);
    }

    static Response apply(List<PlutusData> values, Boolean partial) {
        var params = new ArrayList<DataInput>();
        for (PlutusData value : values) params.add(new DataInput("cbor", HEX.formatHex(PlutusDataCborEncoder.encode(value))));
        return ApplyParams.apply(new Request("V3", new ScriptInput(blueprint, null, null, null), params, partial, null));
    }

    /** Scalus decodes the script, applies each value (decoded by Scalus, re-encoded by its own FLAT encoder). */
    static byte[] scalusApply(String doubleCborHex, List<PlutusData> values) {
        DeBruijnedProgram program = DeBruijnedProgram$.MODULE$.fromDoubleCborHex(doubleCborHex);
        for (PlutusData value : values) program = program.applyArg(Data$.MODULE$.fromCbor(PlutusDataCborEncoder.encode(value)));
        return program.cborEncoded();
    }

    static String blake2b224(int tag, byte[] script) {
        var digest = new Blake2bDigest(224);
        digest.update((byte) tag);
        digest.update(script, 0, script.length);
        byte[] out = new byte[28];
        digest.doFinal(out, 0);
        return HEX.formatHex(out);
    }

    static PlutusData bytes(int n, Random random) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return PlutusData.bytes(b);
    }

    /** Conforming values for the six parameters. */
    static List<PlutusData> conforming(Random random) {
        var tiers = new ArrayList<PlutusData>();
        for (int i = random.nextInt(4); i > 0; i--) tiers.add(PlutusData.integer(random.nextInt(1000)));
        var admins = new ArrayList<PlutusData>();
        for (int i = random.nextInt(3); i > 0; i--) admins.add(bytes(28, random));
        PlutusData mode = random.nextBoolean() ? new PlutusData.ConstrData(0, List.of())
                : new PlutusData.ConstrData(1, List.of(PlutusData.integer(random.nextLong())));
        return List.of(bytes(28, random), PlutusData.integer(new BigInteger(90, random)),
                new PlutusData.ConstrData(0, List.of(bytes(random.nextInt(70), random),
                        PlutusData.integer(new BigInteger(70, random).negate()), new PlutusData.ListData(tiers))),
                mode, new PlutusData.ListData(admins), randomData(random, 4));
    }

    /** Arbitrary Plutus data within Scalus' domain: constructor indices below 2^63. */
    static PlutusData randomData(Random random, int depth) {
        int kind = depth == 0 ? random.nextInt(2) : random.nextInt(5);
        return switch (kind) {
            case 0 -> PlutusData.integer(new BigInteger(random.nextInt(600), random).subtract(BigInteger.ONE.shiftLeft(64)));
            case 1 -> bytes(random.nextInt(140), random);
            case 2 -> {
                var items = new ArrayList<PlutusData>();
                for (int i = random.nextInt(4); i > 0; i--) items.add(randomData(random, depth - 1));
                yield new PlutusData.ListData(items);
            }
            case 3 -> {
                var pairs = new ArrayList<PlutusData.Pair>();
                PlutusData key = randomData(random, 0);
                for (int i = random.nextInt(4); i > 0; i--) {
                    // Repeated keys sometimes: Plutus maps keep them.
                    if (random.nextBoolean()) key = randomData(random, 0);
                    pairs.add(new PlutusData.Pair(key, randomData(random, depth - 1)));
                }
                yield new PlutusData.MapData(pairs);
            }
            default -> {
                var fields = new ArrayList<PlutusData>();
                for (int i = random.nextInt(3); i > 0; i--) fields.add(randomData(random, depth - 1));
                BigInteger index = switch (random.nextInt(4)) {
                    case 0 -> BigInteger.valueOf(random.nextInt(7));
                    case 1 -> BigInteger.valueOf(7 + random.nextInt(121));
                    case 2 -> BigInteger.valueOf(128 + random.nextInt(100_000));
                    default -> BigInteger.ONE.shiftLeft(63).subtract(BigInteger.ONE).subtract(BigInteger.valueOf(random.nextInt(1000)));
                };
                yield new PlutusData.ConstrData(index, fields);
            }
        };
    }

    @Test
    void theBlueprintDeclaresTheParametersAndTheScriptIsCanonical() {
        assertEquals(6, entry.get("parameters").size());
        var none = ApplyParams.apply(new Request("V3", new ScriptInput(blueprint, null, null, null), List.of(), true,
                null));
        assertEquals(6, none.remainingParams());
        // A JuLC blueprint holds the script wrapped twice; zero values give it back unchanged.
        assertEquals(entry.get("compiledCode").asText(), none.compiledCode());
        assertEquals(entry.get("hash").asText(), none.scriptHash());
    }

    @Test
    void generatedValuesMatchScalusAndScriptDecoder() {
        var random = new Random(77);
        String code = entry.get("compiledCode").asText();
        for (int round = 0; round < 40; round++) {
            List<PlutusData> values = conforming(random);
            int count = round == 0 ? 0 : 1 + random.nextInt(values.size());
            values = values.subList(0, count);
            var response = apply(values, true);
            assertEquals(6 - count, response.remainingParams());
            assertTrue(response.schemaChecked());
            byte[] scalus = scalusApply(code, values);
            assertEquals(HEX.formatHex(scalus), response.cborHex(), "round " + round);
            assertEquals(blake2b224(3, scalus), response.scriptHash(), "round " + round);
            assertEquals(DeBruijnedProgram$.MODULE$.fromCbor(scalus).doubleCborHex(), response.compiledCode());
            // I4's regression check: the same path as vm.decode with script.language and script.params.
            var legacy = ScriptDecoder.decode(new ScriptInput(code, values.stream().map(v -> new DataInput("cbor",
                    HEX.formatHex(PlutusDataCborEncoder.encode(v)))).toList(), "V3", null));
            assertEquals(HEX.formatHex(legacy.compiledCode()), response.compiledCode());
        }
    }

    @Test
    void arbitraryOpaqueDataMatchesScalus() {
        // A raw script (no declaration) with arbitrary values: bignums, long chunked bytes, repeated map keys,
        // every constructor-tag encoding below 2^63.
        String code = entry.get("compiledCode").asText();
        var random = new Random(2077);
        for (int round = 0; round < 200; round++) {
            var values = new ArrayList<PlutusData>();
            for (int i = random.nextInt(4); i > 0; i--) values.add(randomData(random, 5));
            var params = values.stream().map(v -> new DataInput("cbor", HEX.formatHex(PlutusDataCborEncoder.encode(v))))
                    .toList();
            var response = ApplyParams.apply(new Request("V2", new ScriptInput(code, null, null, null), params, null,
                    null));
            byte[] scalus = scalusApply(code, values);
            assertEquals(HEX.formatHex(scalus), response.cborHex(), "round " + round);
            assertEquals(blake2b224(2, scalus), response.scriptHash(), "round " + round);
        }
    }

    /** The documented edge of Scalus' domain: it cannot apply a constructor index of 2^63. */
    @Test
    void scalusRefusesConstructorIndicesFrom2To63() {
        var index = BigInteger.ONE.shiftLeft(63);
        var value = new PlutusData.ConstrData(index, List.of());
        String code = entry.get("compiledCode").asText();
        assertThrows(Exception.class, () -> scalusApply(code, List.of(value)));
        // vm.applyParams applies it; AikenOracleTest checks this range against Aiken.
        ApplyParams.apply(new Request("V3", new ScriptInput(code, null, null, null),
                List.of(new DataInput("json", "{\"constructor\":" + index + ",\"fields\":[]}")), null, null));
    }

    @Test
    void theGeneratedSchemasRefuseValuesThatDoNotConform() {
        var random = new Random(5);
        var good = conforming(random);
        record Case(int parameter, PlutusData value, String reason) {}
        var cases = List.of(
                new Case(0, PlutusData.integer(1), "Parameter 1 (owner): expected bytes, found int"),
                new Case(1, PlutusData.bytes(new byte[1]), "Parameter 2 (deadline): expected int, found bytes"),
                new Case(2, new PlutusData.ConstrData(1, ((PlutusData.ConstrData) good.get(2)).fields()),
                        "Parameter 3 (config): expected constructor index 0, found 1"),
                new Case(2, new PlutusData.ConstrData(0, List.of(PlutusData.bytes(new byte[0]), PlutusData.integer(1))),
                        "Parameter 3 (config): expected 3 fields for constructor 0, found 2"),
                new Case(2, new PlutusData.ConstrData(0, List.of(PlutusData.bytes(new byte[0]), PlutusData.integer(1),
                        new PlutusData.ListData(List.of(PlutusData.bytes(new byte[0]))))),
                        "Parameter 3 (config): at fields[2].list[0]: expected int, found bytes"),
                new Case(3, new PlutusData.ConstrData(2, List.of()), "Parameter 4 (mode): no branch of anyOf matched"),
                new Case(3, new PlutusData.ConstrData(1, List.of()), "Parameter 4 (mode): no branch of anyOf matched"),
                new Case(4, new PlutusData.ListData(List.of(PlutusData.integer(1))),
                        "Parameter 5 (admins): at list[0]: expected bytes, found int"));
        for (var c : cases) {
            var values = new ArrayList<>(good);
            values.set(c.parameter(), c.value());
            var e = assertThrows(ApplyParamsException.class, () -> apply(values, null), c::toString);
            assertEquals(Kind.PARAMETER_MISMATCH, e.kind());
            // The label names the parameter; JuLC writes a record as a one-branch anyOf, so the reason may follow
            // the applicator.
            String label = c.reason().substring(0, c.reason().indexOf("): ") + 3);
            assertTrue(e.getMessage().startsWith(label), () -> e.getMessage() + " / " + label);
            String reason = c.reason().substring(label.length());
            assertTrue(e.getMessage().contains(reason), () -> e.getMessage() + " / " + reason);
        }
        // Any data is accepted for the PlutusData parameter.
        var opaque = new ArrayList<>(good);
        opaque.set(5, new PlutusData.ConstrData(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE), List.of()));
        assertTrue(apply(opaque, null).schemaChecked());
    }
}
