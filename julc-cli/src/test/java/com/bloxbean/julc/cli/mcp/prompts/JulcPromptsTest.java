package com.bloxbean.julc.cli.mcp.prompts;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JulcPromptsTest {

    @Test
    void registers_adr_prompt_templates() {
        var prompts = JulcPrompts.all();
        var names = prompts.stream()
                .map(s -> s.prompt().name())
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(new HashSet<>(JulcPrompts.PROMPT_NAMES), names);
        for (var spec : prompts) {
            assertNotNull(spec.prompt().description(), "missing description: " + spec.prompt().name());
            assertFalse(spec.prompt().arguments().isEmpty(), "missing args: " + spec.prompt().name());
        }
    }

    @Test
    void write_validator_prompt_guides_closed_loop() {
        var spec = JulcPrompts.all().stream()
                .filter(s -> "write-validator".equals(s.prompt().name()))
                .findFirst()
                .orElseThrow();

        var result = spec.promptHandler().apply(null,
                new McpSchema.GetPromptRequest("write-validator", Map.of(
                        "kind", "spending",
                        "requirements", "deadline and signer check"
                )));

        String text = firstText(result);
        assertTrue(text.contains("julc_lint"));
        assertTrue(text.contains("julc_compile"));
        assertTrue(text.contains("Redeemer, ScriptContext"));
        assertTrue(text.contains("deadline and signer check"));
    }

    @Test
    void debug_prompt_routes_codes_to_explain_tool() {
        var spec = JulcPrompts.all().stream()
                .filter(s -> "debug-compile-error".equals(s.prompt().name()))
                .findFirst()
                .orElseThrow();

        var result = spec.promptHandler().apply(null,
                new McpSchema.GetPromptRequest("debug-compile-error", Map.of(
                        "diagnostic", "JULC0008"
                )));

        String text = firstText(result);
        assertTrue(text.contains("julc_explain_diagnostic"));
        assertTrue(text.contains("JULC0008"));
    }

    private String firstText(McpSchema.GetPromptResult result) {
        assertEquals(1, result.messages().size());
        var content = result.messages().get(0).content();
        assertInstanceOf(McpSchema.TextContent.class, content);
        return ((McpSchema.TextContent) content).text();
    }
}
