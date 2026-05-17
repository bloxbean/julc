package com.bloxbean.julc.cli.scaffold;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes an AGENTS.md / CLAUDE.md guide into the project root so any AI
 * coding agent (Claude Code, Cursor, Continue, ChatGPT, Codex, Aider, etc.)
 * picks up JuLC-specific conventions without manual setup.
 *
 * <p>Two files are written:
 * <ul>
 *   <li>{@code AGENTS.md} — the canonical name (Cursor, Continue, Codex, Aider).</li>
 *   <li>{@code CLAUDE.md} — Claude Code's preferred name. Created as a copy
 *       so users don't have to choose.</li>
 * </ul>
 *
 * <p>The content is intentionally concise — it points to the canonical
 * starter pack at <a href="https://julc.dev/ai/starter-pack/">julc.dev/ai/starter-pack/</a>
 * for full coverage, and only restates the most critical project-scoped rules.
 */
public final class AgentsTemplate {

    private AgentsTemplate() {}

    /**
     * Writes AGENTS.md and CLAUDE.md into {@code projectRoot}.
     *
     * @param projectRoot project root directory; must already exist
     * @param projectName human-readable project name to embed in the guide
     */
    public static void write(Path projectRoot, String projectName) throws IOException {
        String content = render(projectName);
        Files.writeString(projectRoot.resolve("AGENTS.md"), content);
        // Claude Code reads CLAUDE.md by default; same content.
        Files.writeString(projectRoot.resolve("CLAUDE.md"), content);
    }

    static String render(String projectName) {
        // Kept deliberately tight — this file is auto-loaded into every AI
        // conversation, so every token costs latency. The 8 rules below are
        // the ones AI agents most commonly violate; everything else lives
        // in the canonical starter pack at the linked URL. (Phase B review
        // feedback: trim from ~120 to ~60 lines, drop content that overlaps
        // with the hosted starter pack.)
        return """
                # %s — AI Agent Guide

                **JuLC** project: Java → Plutus V3 UPLC for Cardano smart contracts.

                ## Canonical references (fetch and follow strictly)

                - Full rules + stdlib + ledger types + examples: <https://julc.dev/ai/starter-pack/>
                  (raw markdown: <https://julc.dev/ai/starter-pack.md>)
                - Machine-readable catalog: <https://julc.dev/ai/catalog.json>
                - Diagnostic codes → fixes: <https://julc.dev/ai/diagnostics.json>
                - Tagged examples: <https://julc.dev/ai/examples.json>

                ## Critical rules (do not violate)

                1. **Use high-level type classes, NOT raw `PlutusData`.** Prefer `record`s, sealed interfaces,
                   `TxOut`, `Value`, `Address`, `OutputDatum`, `Credential`, `JulcList<T>`, `JulcMap<K,V>`,
                   `Optional<T>`, `Tuple2`/`Tuple3`. Raw `PlutusData.ConstrData/IntData/BytesData/MapData/ListData`
                   constructions in on-chain code are an anti-pattern. (Subtype names: `ConstrData`, `IntData`,
                   `BytesData`, `MapData`, `ListData`.)
                2. **No mutation, no uninitialized variables.** Initialize at declaration: `var x = BigInteger.ZERO;`.
                3. **No `return` inside `while` loops.** Use a boolean accumulator and return after the loop.
                4. **Use `.compareTo()` / `.equals()` for `BigInteger` and `byte[]`.** Never `==`.
                5. **Lambdas only inline at HOFs.** `list.map(x -> ...)`, `list.filter(...)`, `list.any(...)`. Never store + `.apply()`.
                6. **Switches on sealed interfaces must be exhaustive** (or include `default ->`).
                7. **Switch case binding names must differ from method parameter names** (the field shadows the param).
                8. **Use `PubKeyHash.of(bytes)` and `Optional.of(x)` / `Optional.empty()`.** Not `(PubKeyHash)(Object) bytes` casts; not `mkSome` / `mkNone`.

                ## Annotations cheat sheet

                | Annotation | Entrypoint signature |
                |---|---|
                | `@SpendingValidator` | `static boolean validate(<Redeemer> r, ScriptContext ctx)` or `static boolean validate(<Datum> d, <Redeemer> r, ScriptContext ctx)` |
                | `@MintingValidator` | `static boolean validate(<Redeemer> r, ScriptContext ctx)` |
                | `@CertifyingValidator` / `@WithdrawValidator` / `@VotingValidator` / `@ProposingValidator` | `static boolean validate(<Redeemer> r, ScriptContext ctx)` |
                | `@MultiValidator` | combine multiple `@Entrypoint(purpose=...)` methods in one class |
                | `@OnchainLibrary` | reusable on-chain helper class |
                | `@Param` (on `static` field) | parameter baked in at deploy time |
                | `@Entrypoint` (on `static` method) | the validator entry method |

                Stdlib lives in `com.bloxbean.cardano.julc.stdlib.lib.*` and Plutus builtins in
                `com.bloxbean.cardano.julc.stdlib.Builtins`. The full one-line API surface is at
                `/ai/catalog.json`; do not invent methods not listed there.

                ---

                *Auto-generated by `julc new` for project `%s`. Edit freely — `julc` won't overwrite this file.*
                """.formatted(projectName, projectName);
    }
}
