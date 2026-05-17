package com.bloxbean.julc.cli.mcp.tools;

import com.bloxbean.julc.cli.mcp.lint.LintEngine;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LintToolTest {

    private static McpJsonMapper jsonMapper;

    @BeforeAll
    static void loadMapper() {
        jsonMapper = ServiceLoader.load(McpJsonMapperSupplier.class).stream()
                .map(ServiceLoader.Provider::get)
                .map(McpJsonMapperSupplier::get)
                .findFirst().orElseThrow();
    }

    @Test
    void rejectsOversizedSource() {
        String huge = "/*".repeat(McpLimits.MAX_SOURCE_BYTES / 2 + 1);
        var req = new McpSchema.CallToolRequest("julc_lint", Map.of("source", huge));

        var res = LintTool.handle(req, new LintEngine(), jsonMapper);

        assertEquals(Boolean.TRUE, res.isError());
    }
}
