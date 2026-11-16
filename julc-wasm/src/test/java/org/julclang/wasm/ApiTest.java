package org.julclang.wasm;

import org.julclang.tools.model.ApplyParamsModels;
import org.julclang.tools.model.SourceDebugModels;
import org.julclang.tools.model.VmModels;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ApiTest {
    public record Numbers(long scalar, List<Long> list, long[] array, BigInteger big, Integer small) {}

    @Test
    void allIntegerPositionsAreEncodedBeforeJavascriptCanRoundThem() throws Exception {
        var value = new Numbers(9007199254740993L, List.of(Long.MIN_VALUE, Long.MAX_VALUE),
                new long[]{9007199254740993L}, BigInteger.ONE.shiftLeft(80), 42);
        var envelope = JsMarshalling.JSON.readTree(JsMarshalling.envelope(200, value));
        assertEquals("9007199254740993", envelope.at("/body/scalar").textValue());
        assertEquals(Long.toString(Long.MIN_VALUE), envelope.at("/body/list/0").textValue());
        assertEquals("9007199254740993", envelope.at("/body/array/0").textValue());
        assertEquals(5, envelope.get("integers").size());
        assertTrue(envelope.at("/body/small").isInt());
        var schema = (Map<?, ?>) JsMarshalling.schema(Numbers.class);
        assertEquals(List.of("long"), schema.get("list"));
        assertEquals(List.of("long"), schema.get("array"));
    }

    @Test
    void variantsExposeSameVmOperationsAndEvaluateExactly() throws Exception {
        var vm = JulcVmWasm.create();
        var full = JulcWasm.create();
        String request = "{\"script\":{\"script\":\"(program 1.1.0 (lam x x))\"},"
                + "\"args\":[{\"format\":\"json\",\"value\":\"{\\\"int\\\":18446744073709551617}\"}]}";
        assertEquals(vm.invoke("vm.evaluate", request), full.invoke("vm.evaluate", request));
        var result = JsMarshalling.JSON.readTree(vm.invoke("vm.evaluate", request));
        assertEquals(200, result.get("status").intValue());
        assertEquals("success", result.at("/body/status").asText());
        assertTrue(result.at("/body/result").asText().contains("18446744073709551617"));
        assertFalse(JsMarshalling.JSON.readTree(vm.schemas()).has("compiler.compile"));
        assertTrue(JsMarshalling.JSON.readTree(full.schemas()).has("compiler.compile"));
        var vmSchemas = JsMarshalling.JSON.readTree(vm.schemas());
        var fullSchemas = JsMarshalling.JSON.readTree(full.schemas());
        assertFalse(vmSchemas.has("sourceDebug.open"));
        assertFalse(vmSchemas.has("sourceDebug.locals"));
        assertTrue(fullSchemas.has("sourceDebug.open"));
        assertTrue(fullSchemas.has("sourceDebug.locals"));
        assertTrue(fullSchemas.has("sourceDebug.children"));
        assertNotNull(JsMarshalling.schema(VmModels.Request.class));
    }

    /** ADR-077 D6: vm.applyParams in both engines, read strictly, with the codes the client reports. */
    @Test
    void applyParamsIsAVmOperationOfBothEngines() throws Exception {
        var vm = JulcVmWasm.create();
        var full = JulcWasm.create();
        String request = "{\"language\":\"V3\",\"script\":{\"script\":\"46450101002499\"},"
                + "\"params\":[{\"format\":\"json\",\"value\":\"{\\\"int\\\":42}\"}]}";
        assertEquals(vm.invoke("vm.applyParams", request), full.invoke("vm.applyParams", request));
        var result = JsMarshalling.JSON.readTree(vm.invoke("vm.applyParams", request));
        assertEquals(200, result.get("status").intValue(), result.toString());
        // The ADR's fixed vector: Aiken v1.1.23's blueprint apply and Python's blake2b-224.
        assertEquals("4c0101003249930102182a0001", result.at("/body/cborHex").asText());
        assertEquals("4d4c0101003249930102182a0001", result.at("/body/compiledCode").asText());
        assertEquals("36f2b62169a767e3bcb49a0ff8dd3f473db91e15434ac69ace324ac6", result.at("/body/scriptHash").asText());
        assertEquals("V3", result.at("/body/language").asText());
        assertEquals(1, result.at("/body/paramsApplied").intValue());
        assertTrue(result.at("/body/remainingParams").isNull());
        assertFalse(result.at("/body/schemaChecked").booleanValue());
        assertTrue(JsMarshalling.JSON.readTree(vm.schemas()).has("vm.applyParams"));

        // I4's reference adapter: vm.decode with script.language := language and script.params := values.
        String decode = "{\"script\":{\"script\":\"46450101002499\",\"language\":\"V3\","
                + "\"params\":[{\"format\":\"json\",\"value\":\"{\\\"int\\\":42}\"}]}}";
        var decoded = JsMarshalling.JSON.readTree(vm.invoke("vm.decode", decode));
        assertEquals(decoded.at("/body/info/compiledCode").asText(), result.at("/body/compiledCode").asText());
        assertEquals(decoded.at("/body/info/scriptHash").asText(), result.at("/body/scriptHash").asText());

        // The request body is strict: a repeated member is refused whatever its order, and so are wrong scalars.
        for (String body : List.of(
                "{\"language\":\"V2\",\"language\":\"V3\",\"script\":{\"script\":\"0101002499\"}}",
                "{\"language\":\"V3\",\"language\":\"V2\",\"script\":{\"script\":\"0101002499\"}}",
                "{\"language\":\"V3\",\"script\":{\"script\":\"0101002499\",\"script\":\"0101002499\"}}",
                "{\"language\":\"V3\",\"script\":{\"script\":\"0101002499\"},\"partial\":\"true\"}",
                "{\"language\":\"V3\",\"script\":{\"script\":\"0101002499\"},\"bogus\":1}",
                "{\"language\":\"V3\",\"script\":{\"script\":\"0101002499\"}} {}")) {
            var refused = JsMarshalling.JSON.readTree(vm.invoke("vm.applyParams", body));
            assertEquals(400, refused.get("status").intValue(), body + " -> " + refused);
        }
        // The same body with duplicates is still accepted by the lenient operations (unchanged behaviour).
        assertEquals(200, JsMarshalling.JSON.readTree(vm.invoke("vm.decode",
                "{\"script\":{\"script\":\"0101002499\",\"script\":\"0101002499\"}}")).get("status").intValue());

        var missing = JsMarshalling.JSON.readTree(vm.invoke("vm.applyParams", "{\"script\":{\"script\":\"0101002499\"}}"));
        assertEquals(400, missing.get("status").intValue());
        assertEquals("invalid-request", missing.at("/body/code").asText());

        String blueprint = "{\"preamble\":{\"title\":\"t\",\"version\":\"0\",\"plutusVersion\":\"v3\"},"
                + "\"validators\":[{\"title\":\"v\",\"compiledCode\":\"450101002499\",\"parameters\":"
                + "[{\"title\":\"owner\",\"schema\":{\"dataType\":\"bytes\"}}]}]}";
        var mismatch = JsMarshalling.JSON.readTree(vm.invoke("vm.applyParams", JsMarshalling.JSON.writeValueAsString(
                Map.of("language", "V3", "script", Map.of("script", blueprint),
                        "params", List.of(Map.of("format", "json", "value", "{\"int\":1}"))))));
        assertEquals(422, mismatch.get("status").intValue());
        assertEquals("parameter-mismatch", mismatch.at("/body/code").asText());
        assertEquals("Parameter 1 (owner): expected bytes, found int (schema schema)", mismatch.at("/body/error").asText());
        var invalidSchema = JsMarshalling.JSON.readTree(vm.invoke("vm.applyParams", JsMarshalling.JSON.writeValueAsString(
                Map.of("language", "V3", "script", Map.of("script", blueprint.replace("\"bytes\"", "\"string\"")),
                        "params", List.of(Map.of("format", "json", "value", "{\"int\":1}"))))));
        assertEquals(422, invalidSchema.get("status").intValue());
        assertEquals("invalid-schema", invalidSchema.at("/body/code").asText());
    }

    /** M2-C1: the strict request reader turns no number or boolean into a string, and no string into a boolean. */
    @Test
    void applyParamsRequestsRefuseScalarsOfTheWrongJsonType() throws Exception {
        var vm = JulcVmWasm.create();
        String script = "\"script\":{\"script\":\"0101002499\"}";
        String good = "{\"language\":\"V3\"," + script + ",\"params\":[{\"format\":\"cbor\",\"value\":\"80\"}]}";
        assertEquals(200, JsMarshalling.JSON.readTree(vm.invoke("vm.applyParams", good)).get("status").intValue());
        for (String body : List.of(
                "{\"language\":\"V3\"," + script + ",\"params\":[{\"format\":\"cbor\",\"value\":80}]}",
                "{\"language\":\"V3\"," + script + ",\"params\":[{\"format\":\"json\",\"value\":true}]}",
                "{\"language\":\"V3\"," + script + ",\"params\":[{\"format\":1,\"value\":\"80\"}]}",
                "{\"language\":\"V3\"," + script + ",\"params\":[{\"format\":\"cbor\",\"value\":{}}]}",
                "{\"language\":3," + script + "}",
                "{\"language\":true," + script + "}",
                "{\"language\":[\"V3\"]," + script + "}",
                "{\"language\":\"V3\",\"script\":{\"script\":101002499}}",
                "{\"language\":\"V3\",\"script\":{\"script\":\"0101002499\",\"validator\":0}}",
                "{\"language\":\"V3\",\"script\":{\"script\":\"0101002499\",\"language\":3}}",
                "{\"language\":\"V3\"," + script + ",\"partial\":1}",
                "{\"language\":\"V3\"," + script + ",\"partial\":\"true\"}",
                "{\"language\":\"V3\"," + script + ",\"checkSchema\":\"false\"}",
                "{\"language\":\"V3\"," + script + ",\"checkSchema\":0}",
                "{\"language\":\"V3\"," + script + ",\"params\":{\"format\":\"cbor\",\"value\":\"80\"}}")) {
            var refused = JsMarshalling.JSON.readTree(vm.invoke("vm.applyParams", body));
            assertEquals(400, refused.get("status").intValue(), body + " -> " + refused);
            assertTrue(refused.at("/body/error").asText().startsWith("Invalid request body"), refused.toString());
        }
    }

    /** M2-C2: depth 10,000 in every format through the engine, on a thread with a 256 KiB stack. */
    @Test
    void applyParamsTakesDeepValuesInEveryFormat() throws Exception {
        var vm = JulcVmWasm.create();
        int depth = 10_000;
        var values = List.of(
                Map.of("format", "json", "value", "{\"list\":[".repeat(depth) + "{\"int\":0}" + "]}".repeat(depth)),
                Map.of("format", "cbor", "value", "9f".repeat(depth) + "00" + "ff".repeat(depth)),
                Map.of("format", "uplc", "value", "List [".repeat(depth) + "I 0" + "]".repeat(depth)));
        var hashes = new HashSet<String>();
        for (var value : values) {
            String body = JsMarshalling.JSON.writeValueAsString(Map.of("language", "V3",
                    "script", Map.of("script", "0101002499"), "params", List.of(value)));
            var result = new String[1];
            var thread = new Thread(null, () -> result[0] = vm.invoke("vm.applyParams", body), "small", 256 * 1024);
            thread.start();
            thread.join();
            var envelope = JsMarshalling.JSON.readTree(result[0]);
            assertEquals(200, envelope.get("status").intValue(), envelope.toString().substring(0, 300));
            hashes.add(envelope.at("/body/scriptHash").asText());
        }
        assertEquals(1, hashes.size());
        var empty = JsMarshalling.JSON.readTree(vm.invoke("vm.applyParams",
                "{\"language\":\"V3\",\"script\":{\"script\":\"40\"}}"));
        assertEquals(400, empty.get("status").intValue());
        assertEquals("invalid-request", empty.at("/body/code").asText());
    }

    @Test
    void sourceDebugRecordsAreRegisteredForNativeImageReflection() throws Exception {
        var resource = ApiTest.class.getResourceAsStream(
                "/META-INF/native-image/org.julclang/julc-wasm/reachability-metadata.json");
        assertNotNull(resource);
        try (resource) {
            var metadata = JsMarshalling.JSON.readTree(resource);
            var registered = new HashSet<String>();
            metadata.path("reflection").forEach(entry -> registered.add(entry.path("type").asText()));
            var missing = Stream.of(SourceDebugModels.class, ApplyParamsModels.class)
                    .flatMap(models -> Arrays.stream(models.getDeclaredClasses()))
                    .filter(Class::isRecord)
                    .map(Class::getName)
                    .filter(type -> !registered.contains(type))
                    .sorted()
                    .toList();
            assertEquals(List.of(), missing, "Records missing from native-image reflection metadata");
        }
    }
}
