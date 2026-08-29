package com.bloxbean.julc.cli.mcp.tools;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

class EvaluateToolTest {

    private static McpJsonMapper jsonMapper;

    @BeforeAll
    static void loadMapper() {
        jsonMapper = ServiceLoader.load(McpJsonMapperSupplier.class).stream()
                .map(ServiceLoader.Provider::get)
                .map(McpJsonMapperSupplier::get)
                .findFirst().orElseThrow();
    }

    @Test
    void evaluates_pure_integer_method() {
        String src = """
                import java.math.BigInteger;
                public class M {
                    public static BigInteger doubleIt(BigInteger x) {
                        return x.add(x);
                    }
                }
                """;
        var req = new McpSchema.CallToolRequest("julc_evaluate", Map.of(
                "source", src,
                "method", "doubleIt",
                "args", List.of(Map.of("int", 21))
        ));
        var res = EvaluateTool.handle(req, jsonMapper);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        assertEquals(Boolean.TRUE, body.get("ok"), "evaluation failed: " + body);
        assertEquals("42", body.get("result"));
        assertEquals("integer", body.get("resultType"));
        assertEquals("plutus-v3-pv11-uplc-1.1.0", body.get("compilerTarget"));
        assertTrue(((Number) body.get("cpu")).longValue() > 0, "cpu must be > 0");
    }

    @Test
    void evaluates_boolean_method() {
        String src = """
                import java.math.BigInteger;
                public class M {
                    public static boolean isEven(BigInteger x) {
                        return x.remainder(BigInteger.TWO).equals(BigInteger.ZERO);
                    }
                }
                """;
        var req = new McpSchema.CallToolRequest("julc_evaluate", Map.of(
                "source", src,
                "method", "isEven",
                "args", List.of(Map.of("int", 4))
        ));
        var res = EvaluateTool.handle(req, jsonMapper);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        assertEquals(Boolean.TRUE, body.get("ok"), "evaluation failed: " + body);
        assertEquals(Boolean.TRUE, body.get("result"));
        assertEquals("boolean", body.get("resultType"));
    }

    @Test
    void evaluates_bytes_method() {
        String src = """
                public class M {
                    public static byte[] passthrough(byte[] b) { return b; }
                }
                """;
        var req = new McpSchema.CallToolRequest("julc_evaluate", Map.of(
                "source", src,
                "method", "passthrough",
                "args", List.of(Map.of("bytes", "0xdeadbeef"))
        ));
        var res = EvaluateTool.handle(req, jsonMapper);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        assertEquals(Boolean.TRUE, body.get("ok"), "evaluation failed: " + body);
        assertEquals("0xdeadbeef", body.get("result"));
        assertEquals("bytes", body.get("resultType"));
    }

    @Test
    void surfaces_compile_diagnostics_when_method_does_not_compile() {
        String src = """
                public class M {
                    public static boolean bad() {
                        try { return true; } catch (Exception e) { return false; }
                    }
                }
                """;
        var req = new McpSchema.CallToolRequest("julc_evaluate", Map.of(
                "source", src,
                "method", "bad"
        ));
        var res = EvaluateTool.handle(req, jsonMapper);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        assertEquals(Boolean.FALSE, body.get("ok"));
        assertNotNull(body.get("diagnostics"),
                "compile-failed evaluation must surface diagnostics");
    }

    @Test
    void rejects_missing_method_name() {
        var req = new McpSchema.CallToolRequest("julc_evaluate", Map.of(
                "source", "class X {}"
        ));
        var res = EvaluateTool.handle(req, jsonMapper);
        assertEquals(Boolean.TRUE, res.isError());
    }

    @Test
    void rejects_unknown_future_compiler_target() {
        var req = new McpSchema.CallToolRequest("julc_evaluate", Map.of(
                "source", "class X { static BigInteger x() { return 1; } }",
                "method", "x",
                "target", "plutus-v3-pv12-uplc-1.1.0"));
        var res = EvaluateTool.handle(req, jsonMapper);
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) res.structuredContent();
        @SuppressWarnings("unchecked")
        var diagnostics = (List<Map<String, Object>>) body.get("diagnostics");
        assertEquals(Boolean.FALSE, body.get("ok"));
        assertTrue(diagnostics.stream().anyMatch(
                diagnostic -> "JULC0031".equals(diagnostic.get("code"))));
    }

    @Test
    void rejects_costed_optimization_without_profile() {
        var req = new McpSchema.CallToolRequest("julc_evaluate", Map.of(
                "source", "class X { static long x() { return 1; } }",
                "method", "x",
                "optimization", "pv11-costed"));
        var res = EvaluateTool.handle(req, jsonMapper);
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) res.structuredContent();
        @SuppressWarnings("unchecked")
        var diagnostics = (List<Map<String, Object>>) body.get("diagnostics");
        assertEquals(Boolean.FALSE, body.get("ok"));
        assertTrue(diagnostics.stream().anyMatch(
                diagnostic -> "JULC0037".equals(diagnostic.get("code"))));
    }

    @Test
    void parses_all_arg_shapes() {
        // No actual evaluation — just confirm the parser accepts each shape.
        var raw = List.<Object>of(
                Map.of("int", 5),
                Map.of("int", "1234567890123456789"),
                Map.of("bytes", "0xab"),
                Map.of("bytes", "ab"),               // no 0x prefix
                Map.of("string", "hi"),
                Map.of("bool", true),
                Map.of("unit", true)
        );
        var pds = EvaluateTool.parseArgs(raw);
        assertEquals(7, pds.size());
    }

    @Test
    void rejects_unknown_arg_shape() {
        var raw = List.<Object>of(Map.of("frobnicate", 7));
        var ex = assertThrows(IllegalArgumentException.class, () ->
                EvaluateTool.parseArgs(raw));
        assertTrue(ex.getMessage().contains("Unknown arg shape"));
    }

    @Test
    void evaluates_method_with_two_args() {
        // Phase C review: multi-arg evaluation was an uncovered path.
        String src = """
                import java.math.BigInteger;
                public class M {
                    public static BigInteger add(BigInteger a, BigInteger b) { return a.add(b); }
                }
                """;
        var req = new McpSchema.CallToolRequest("julc_evaluate", Map.of(
                "source", src,
                "method", "add",
                "args", List.of(Map.of("int", 17), Map.of("int", 25))
        ));
        var res = EvaluateTool.handle(req, jsonMapper);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        assertEquals(Boolean.TRUE, body.get("ok"), "two-arg add failed: " + body);
        assertEquals("42", body.get("result"));
    }

    @Test
    void rejects_arity_mismatch() {
        // Method takes one arg, agent passes none → fast structured error
        // instead of confusing VM-level failure.
        String src = """
                import java.math.BigInteger;
                public class M {
                    public static BigInteger needsArg(BigInteger x) { return x; }
                }
                """;
        var req = new McpSchema.CallToolRequest("julc_evaluate", Map.of(
                "source", src,
                "method", "needsArg",
                "args", List.<Object>of()
        ));
        var res = EvaluateTool.handle(req, jsonMapper);
        assertEquals(Boolean.TRUE, res.isError(),
                "arity mismatch must be flagged as tool-call error");
    }

    @Test
    void rejects_args_that_are_not_an_array() {
        // Codex P1.3: {"args": "nope"} previously caused ClassCastException.
        var req = new McpSchema.CallToolRequest("julc_evaluate", Map.of(
                "source", "class X {}",
                "method", "x",
                "args", "not-an-array"
        ));
        var res = EvaluateTool.handle(req, jsonMapper);
        assertEquals(Boolean.TRUE, res.isError());
    }

    @Test
    void rejects_int_value_exceeding_long_precision_when_passed_as_number() {
        // Codex P2 / impl-validator P1: silent truncation must become an error.
        var raw = List.<Object>of(Map.of("int", 1.5e19));
        var ex = assertThrows(IllegalArgumentException.class, () ->
                EvaluateTool.parseArgs(raw));
        assertTrue(ex.getMessage().contains("long precision"),
                "expected long-precision error: " + ex.getMessage());
    }

    @Test
    void accepts_arbitrary_precision_int_via_string() {
        // Companion to overflow rejection: string form works.
        var raw = List.<Object>of(Map.of("int", "12345678901234567890123456789"));
        var pds = EvaluateTool.parseArgs(raw);
        assertEquals(1, pds.size());
    }

    @Test
    void accepts_biginteger_directly_without_truncation() {
        // Codex review finding 5: when the JSON layer hands us a BigInteger
        // (e.g. integers larger than Long.MAX_VALUE), we must preserve full
        // precision instead of round-tripping through longValue().
        var huge = new java.math.BigInteger("99999999999999999999999999999");
        var raw = List.<Object>of(Map.of("int", huge));
        var pds = EvaluateTool.parseArgs(raw);
        assertEquals(1, pds.size());
        // The PlutusData#1 should preserve the full BigInteger.
        assertTrue(pds.get(0).toString().contains("99999999999999999999999999999"),
                "BigInteger arg must round-trip without truncation: " + pds.get(0));
    }

    @Test
    void rejects_fractional_bigdecimal() {
        // BigDecimal with a fractional part is not a valid int.
        var bd = new java.math.BigDecimal("1.5");
        var raw = List.<Object>of(Map.of("int", bd));
        var ex = assertThrows(IllegalArgumentException.class, () -> EvaluateTool.parseArgs(raw));
        assertTrue(ex.getMessage().toLowerCase().contains("integral"),
                "expected fractional-rejection error: " + ex.getMessage());
    }

    @Test
    void accepts_integral_bigdecimal() {
        // BigDecimal with no fractional part should be accepted.
        var bd = new java.math.BigDecimal("123");
        var raw = List.<Object>of(Map.of("int", bd));
        var pds = EvaluateTool.parseArgs(raw);
        assertEquals(1, pds.size());
    }

    @Test
    void rejects_constr_tag_too_large_for_int() {
        // Codex review finding 5: tag silently truncated for values > Integer.MAX_VALUE.
        var huge = new java.math.BigInteger("9999999999999999999");
        var raw = List.<Object>of(Map.of("constr", Map.of(
                "tag", huge,
                "fields", List.of()
        )));
        var ex = assertThrows(IllegalArgumentException.class, () -> EvaluateTool.parseArgs(raw));
        assertTrue(ex.getMessage().contains("32-bit"),
                "expected 32-bit-overflow error: " + ex.getMessage());
    }

    @Test
    void parses_recursive_constr_arg() {
        var raw = List.<Object>of(Map.of("constr", Map.of(
                "tag", 0,
                "fields", List.of(Map.of("int", 1), Map.of("bytes", "0xab"))
        )));
        var pds = EvaluateTool.parseArgs(raw);
        assertEquals(1, pds.size());
        var pd = pds.get(0);
        assertInstanceOf(com.bloxbean.cardano.julc.core.PlutusData.ConstrData.class, pd);
    }

    @Test
    void parses_recursive_list_arg() {
        var raw = List.<Object>of(Map.of("list",
                List.of(Map.of("int", 1), Map.of("int", 2), Map.of("int", 3))
        ));
        var pds = EvaluateTool.parseArgs(raw);
        assertEquals(1, pds.size());
        assertInstanceOf(com.bloxbean.cardano.julc.core.PlutusData.ListData.class, pds.get(0));
    }

    @Test
    void parses_recursive_map_arg() {
        var raw = List.<Object>of(Map.of("map", List.of(
                Map.of("key", Map.of("int", 1), "value", Map.of("string", "one")),
                Map.of("key", Map.of("int", 2), "value", Map.of("string", "two"))
        )));
        var pds = EvaluateTool.parseArgs(raw);
        assertEquals(1, pds.size());
        assertInstanceOf(com.bloxbean.cardano.julc.core.PlutusData.MapData.class, pds.get(0));
    }

    @Test
    void rejects_excessive_arg_recursion_depth() {
        // Build a deeply-nested constr beyond MAX_ARG_DEPTH.
        Object inner = Map.of("int", 1);
        for (int i = 0; i < EvaluateTool.MAX_ARG_DEPTH + 5; i++) {
            inner = Map.of("constr", Map.of("tag", 0, "fields", List.of(inner)));
        }
        var raw = List.<Object>of(inner);
        var ex = assertThrows(IllegalArgumentException.class, () ->
                EvaluateTool.parseArgs(raw));
        assertTrue(ex.getMessage().contains("depth"),
                "expected depth error: " + ex.getMessage());
    }

    @Test
    void rejects_oversized_source() {
        // Build a string of MAX_SOURCE_BYTES+1 bytes.
        String huge = "/*".repeat(McpLimits.MAX_SOURCE_BYTES / 2 + 1);
        var req = new McpSchema.CallToolRequest("julc_evaluate", Map.of(
                "source", huge,
                "method", "x"
        ));
        var res = EvaluateTool.handle(req, jsonMapper);
        assertEquals(Boolean.TRUE, res.isError(),
                "oversized source must be flagged as tool-call error");
    }
}
