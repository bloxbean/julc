package com.bloxbean.julc.cli.mcp.prompts;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;

/**
 * MCP prompt templates advertised by {@code julc mcp}.
 *
 * <p>Prompts are lightweight workflow starters. They do not compile or inspect
 * code directly; they instruct the client-side agent to use the JuLC MCP tools
 * in the closed loop defined by ADR-020.
 */
public final class JulcPrompts {

    private JulcPrompts() {}

    public static final List<String> PROMPT_NAMES = List.of(
            "write-validator",
            "migrate-from-aiken",
            "migrate-from-plinth",
            "debug-compile-error"
    );

    public static List<McpServerFeatures.SyncPromptSpecification> all() {
        return List.of(
                writeValidator(),
                migrateFromAiken(),
                migrateFromPlinth(),
                debugCompileError()
        );
    }

    private static McpServerFeatures.SyncPromptSpecification writeValidator() {
        var prompt = new McpSchema.Prompt(
                "write-validator",
                "Write a JuLC validator",
                "Scaffold an idiomatic JuLC validator and verify it through lint, compile, and tests.",
                List.of(
                        new McpSchema.PromptArgument("kind",
                                "Validator kind: spending, minting, withdraw, certifying, voting, proposing, or multi.",
                                false),
                        new McpSchema.PromptArgument("requirements",
                                "Functional requirements for the validator.",
                                true)
                ));
        return new McpServerFeatures.SyncPromptSpecification(prompt,
                (exchange, req) -> result("Write JuLC validator", """
                        Create an idiomatic JuLC validator.

                        Kind: %s
                        Requirements: %s

                        Use this workflow:
                        1. Fetch or rely on the JuLC starter-pack rules.
                        2. Prefer typed records, sealed interfaces, JulcList, and JulcMap over raw PlutusData.
                        3. Use the correct @Entrypoint shape:
                           - spending: (Redeemer, ScriptContext) or (Datum, Redeemer, ScriptContext)
                           - non-spending: (Redeemer, ScriptContext)
                        4. Run julc_lint first, fix all findings, then run julc_compile.
                        5. For every JULC#### diagnostic, call julc_explain_diagnostic before changing code.
                        6. Add @Test public static boolean methods where practical and run julc_test.
                        """.formatted(
                        arg(req.arguments(), "kind", "spending"),
                        arg(req.arguments(), "requirements", "<requirements>"))));
    }

    private static McpServerFeatures.SyncPromptSpecification migrateFromAiken() {
        var prompt = new McpSchema.Prompt(
                "migrate-from-aiken",
                "Migrate Aiken to JuLC",
                "Translate an Aiken validator into idiomatic JuLC and verify the result.",
                List.of(
                        new McpSchema.PromptArgument("source",
                                "Aiken source or a concise description of the existing validator.",
                                true),
                        new McpSchema.PromptArgument("notes",
                                "Optional migration constraints or expected behavior.",
                                false)
                ));
        return new McpServerFeatures.SyncPromptSpecification(prompt,
                (exchange, req) -> result("Migrate Aiken to JuLC", """
                        Migrate this Aiken validator to JuLC:

                        %s

                        Notes: %s

                        Map algebraic data types to sealed interfaces and records. Map list/map logic to
                        JulcList and JulcMap helpers. Avoid raw PlutusData unless the value is intentionally opaque.
                        After translating, run julc_lint, julc_compile, and julc_test if tests are available.
                        """.formatted(
                        arg(req.arguments(), "source", "<aiken source>"),
                        arg(req.arguments(), "notes", "<none>"))));
    }

    private static McpServerFeatures.SyncPromptSpecification migrateFromPlinth() {
        var prompt = new McpSchema.Prompt(
                "migrate-from-plinth",
                "Migrate Plinth to JuLC",
                "Translate a Plinth validator into idiomatic JuLC and verify the result.",
                List.of(
                        new McpSchema.PromptArgument("source",
                                "Plinth/Haskell source or a concise description of the existing validator.",
                                true),
                        new McpSchema.PromptArgument("notes",
                                "Optional migration constraints or expected behavior.",
                                false)
                ));
        return new McpServerFeatures.SyncPromptSpecification(prompt,
                (exchange, req) -> result("Migrate Plinth to JuLC", """
                        Migrate this Plinth validator to JuLC:

                        %s

                        Notes: %s

                        Preserve datum/redeemer/context semantics, but express data as typed Java records and
                        sealed interfaces. Use JuLC stdlib discovery tools before inventing helper methods.
                        After translating, run julc_lint, julc_compile, and julc_test if tests are available.
                        """.formatted(
                        arg(req.arguments(), "source", "<plinth source>"),
                        arg(req.arguments(), "notes", "<none>"))));
    }

    private static McpServerFeatures.SyncPromptSpecification debugCompileError() {
        var prompt = new McpSchema.Prompt(
                "debug-compile-error",
                "Debug a JuLC compile error",
                "Diagnose a JuLC compiler failure using structured diagnostics and canonical fixes.",
                List.of(
                        new McpSchema.PromptArgument("diagnostic",
                                "Compiler diagnostic text or JULC#### code.",
                                true),
                        new McpSchema.PromptArgument("source",
                                "The JuLC source that failed to compile.",
                                false)
                ));
        return new McpServerFeatures.SyncPromptSpecification(prompt,
                (exchange, req) -> result("Debug JuLC compile error", """
                        Diagnose this JuLC compile failure:

                        Diagnostic: %s

                        Source:
                        %s

                        Use this workflow:
                        1. If a JULC#### code is present, call julc_explain_diagnostic.
                        2. If source is present, call julc_lint before changing code.
                        3. Make the smallest source change that addresses the diagnostic.
                        4. Re-run julc_compile and repeat until diagnostics contain no errors.
                        """.formatted(
                        arg(req.arguments(), "diagnostic", "<diagnostic>"),
                        arg(req.arguments(), "source", "<source not provided>"))));
    }

    private static McpSchema.GetPromptResult result(String description, String body) {
        return new McpSchema.GetPromptResult(description, List.of(
                new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(body))
        ));
    }

    private static String arg(Map<String, Object> args, String key, String fallback) {
        if (args == null) return fallback;
        Object value = args.get(key);
        if (value instanceof String s && !s.isBlank()) return s;
        return fallback;
    }
}
