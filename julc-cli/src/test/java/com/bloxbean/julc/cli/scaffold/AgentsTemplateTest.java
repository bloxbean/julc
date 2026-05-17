package com.bloxbean.julc.cli.scaffold;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AgentsTemplateTest {

    @Test
    void render_includesProjectName() {
        String body = AgentsTemplate.render("my-validator");
        assertTrue(body.contains("my-validator"), "should embed project name");
    }

    @Test
    void render_pointsToHostedStarterPack() {
        String body = AgentsTemplate.render("any");
        assertTrue(body.contains("https://julc.dev/ai/starter-pack/"),
                "should point to hosted starter pack");
    }

    @Test
    void render_includesCriticalRules() {
        String body = AgentsTemplate.render("any");
        // The 8 critical rules section is the load-bearing content; verify it's present.
        assertTrue(body.contains("high-level type classes"),
                "missing the type-class-over-PlutusData rule");
        assertTrue(body.contains("No mutation"),
                "missing the no-mutation rule");
        assertTrue(body.contains("return") && body.contains("while"),
                "missing the no-return-in-while rule");
        assertTrue(body.contains("PubKeyHash.of("),
                "missing the .of() factory guidance");
        assertTrue(body.contains("sealed interfaces") && body.contains("exhaustive"),
                "missing the switch exhaustiveness rule");
    }

    @Test
    void render_listsAnnotations() {
        String body = AgentsTemplate.render("any");
        assertTrue(body.contains("@SpendingValidator"));
        assertTrue(body.contains("| `@SpendingValidator` | `static boolean validate(<Redeemer> r, ScriptContext ctx)` or `static boolean validate(<Datum> d, <Redeemer> r, ScriptContext ctx)` |"),
                "spending validators must document both the no-datum and datum forms");
        assertTrue(body.contains("@MintingValidator"));
        assertTrue(body.contains("@OnchainLibrary"));
        assertTrue(body.contains("@Param"));
        assertTrue(body.contains("@Entrypoint"));
    }

    @Test
    void render_pointsToCanonicalReferences() {
        // After Phase B trim, AGENTS.md points users to the canonical hosted
        // catalog + diagnostics + examples endpoints (not the old prose URLs).
        String body = AgentsTemplate.render("any");
        assertTrue(body.contains("julc.dev/ai/starter-pack"),
                "must link to hosted starter pack");
        assertTrue(body.contains("/ai/catalog.json"),
                "must link to machine-readable catalog");
        assertTrue(body.contains("/ai/diagnostics.json"),
                "must link to diagnostics catalog");
        assertTrue(body.contains("/ai/examples.json"),
                "must link to tagged examples index");
    }

    @Test
    void write_createsBothAgentsAndClaudeFiles(@TempDir Path tmp) throws IOException {
        AgentsTemplate.write(tmp, "demo");
        Path agents = tmp.resolve("AGENTS.md");
        Path claude = tmp.resolve("CLAUDE.md");
        assertTrue(Files.exists(agents), "AGENTS.md must be written");
        assertTrue(Files.exists(claude), "CLAUDE.md must be written (Claude Code default)");
        // Both should have identical content so the user does not have to choose a tool.
        assertEquals(Files.readString(agents), Files.readString(claude));
    }

    @Test
    void write_isReasonablySizedForInContextLoading(@TempDir Path tmp) throws IOException {
        // The whole point of AGENTS.md is to be loaded into every conversation.
        // Cap it at ~10 KB so it fits easily in any agent's context budget.
        AgentsTemplate.write(tmp, "demo");
        long size = Files.size(tmp.resolve("AGENTS.md"));
        assertTrue(size < 10_000, "AGENTS.md should stay under ~10 KB, got " + size);
        assertTrue(size > 1_500, "AGENTS.md should be substantive, got " + size);
    }
}
