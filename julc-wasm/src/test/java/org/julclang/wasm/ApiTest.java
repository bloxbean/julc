package org.julclang.wasm;

import org.julclang.tools.model.VmModels;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

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
}
