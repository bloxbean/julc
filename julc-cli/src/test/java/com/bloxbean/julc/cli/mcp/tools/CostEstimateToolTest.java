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

class CostEstimateToolTest {

    private static McpJsonMapper jsonMapper;

    @BeforeAll
    static void loadMapper() {
        jsonMapper = ServiceLoader.load(McpJsonMapperSupplier.class).stream()
                .map(ServiceLoader.Provider::get)
                .map(McpJsonMapperSupplier::get)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void estimatesValidatorScriptSizeWithoutCpuMemoryClaim() {
        String src = """
                import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
                import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;

                @SpendingValidator
                public class V {
                    record R() {}
                    @Entrypoint
                    public static boolean validate(R r, ScriptContext ctx) { return true; }
                }
                """;

        var res = CostEstimateTool.handle(new McpSchema.CallToolRequest(
                "julc_estimate_costs", Map.of("source", src)), jsonMapper);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        assertEquals(Boolean.TRUE, body.get("ok"));
        assertTrue(((Number) body.get("scriptSizeBytes")).intValue() > 0);
        assertEquals(Boolean.FALSE, body.get("cpuMemoryAvailable"));
        assertTrue(((String) body.get("cpuMemoryNote")).contains("method+args"));
    }

    @Test
    void delegatesMethodBudgetToEvaluateTool() {
        String src = """
                import java.math.BigInteger;

                public class Pure {
                    public static BigInteger add(BigInteger a, BigInteger b) {
                        return a.add(b);
                    }
                }
                """;

        var res = CostEstimateTool.handle(new McpSchema.CallToolRequest(
                "julc_estimate_costs", Map.of(
                        "source", src,
                        "method", "add",
                        "args", List.of(Map.of("int", 2), Map.of("int", 3))
                )), jsonMapper);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.structuredContent();
        assertEquals(Boolean.TRUE, body.get("ok"), "estimate should succeed: " + body);
        assertEquals("single-method VM evaluation", body.get("basis"));
        assertTrue(((Number) body.get("cpu")).longValue() > 0);
        assertTrue(((Number) body.get("memory")).longValue() > 0);
        assertTrue(((String) body.get("note")).contains("not a full transaction-context benchmark"));
    }

    @Test
    void rejectsOversizedSource() {
        String huge = "/*".repeat(McpLimits.MAX_SOURCE_BYTES / 2 + 1);

        var res = CostEstimateTool.handle(new McpSchema.CallToolRequest(
                "julc_estimate_costs", Map.of("source", huge)), jsonMapper);

        assertEquals(Boolean.TRUE, res.isError(),
                "oversized source must be rejected before cost estimation");
    }
}
