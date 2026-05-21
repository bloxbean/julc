---
name: julc
description: Author and review JuLC (Java → Cardano UPLC) smart contracts. TRIGGER when working in a project that contains a `julc.toml` or any `.java` file with `@SpendingValidator`, `@MintingValidator`, `@WithdrawValidator`, `@CertifyingValidator`, `@VotingValidator`, `@ProposingValidator`, `@MultiValidator`, or `@OnchainLibrary`; or when the user explicitly mentions JuLC, Plutus V3, or Cardano smart contracts.
version: 0.1.0
---

# JuLC Skill

Helps authors of Cardano smart contracts written in JuLC (Java → UPLC). The skill bundles the canonical AI starter pack, wires up the JuLC MCP server, and provides slash commands for common workflows.

## What is JuLC

JuLC compiles a safe subset of Java to Plutus V3 UPLC (Untyped Plutus Core), the on-chain bytecode for Cardano. Validators are written as `@SpendingValidator`-annotated Java classes with an `@Entrypoint static boolean validate(...)` method. The compiler is invoked via Gradle/Maven plugin or the `julc` CLI.

JuLC is a **subset** of Java. The compiler rejects code that looks valid Java but cannot be lowered to UPLC. Common AI-generated mistakes:

- mutable variables (`x = ...` after declaration) — except inside `while` accumulators
- uninitialized locals (`int x;` then `x = 1;`)
- `return` inside a loop
- C-style `for (int i = 0; i < n; i++)` — use `while` accumulator instead
- `Optional.mkSome(x)` / `Optional.mkNone()` — use `Optional.of(x)` / `Optional.empty()`
- `lambdaVar.apply(...)` — lambdas only as inline HOF arguments
- `try`/`catch` — not supported
- raw `PlutusData.ConstrData(...)` — use typed records and let the compiler auto-encode
- `switch` over `Tuple2`/`Tuple3` — they are records, not sum types
- raw `PlutusData` subtypes in `@Param` — use `byte[]`, `BigInteger`, typed records, or redeemers
- `@Entrypoint` with bare `BigInteger` parameter — wrap in a record

## Authoritative resources

The skill defers to live, hosted artifacts so it always reflects the current language:

- **Starter pack**: <https://julc.dev/ai/starter-pack.md> — full reference, idiomatic examples, anti-patterns
- **Stdlib catalog**: <https://julc.dev/ai/catalog.json> — every stdlib method, ledger type, and builtin
- **Diagnostic codes**: <https://julc.dev/ai/diagnostics.json> — `JULC####` error code → root cause + fix
- **Examples corpus**: <https://julc.dev/ai/examples.json> — tagged canonical validators

Fetch these on first use of the skill in a session and cache for the conversation.

**Offline fallback**: a frozen copy of the starter pack ships with the skill at `knowledge/starter-pack.md`. Use it when the live URL is unreachable or when the user explicitly works offline. See `knowledge/MANIFEST.md` for refresh instructions.

## MCP server (preferred path)

If the user has the JuLC MCP server installed (see `mcp.json` in this skill bundle, or `claude mcp add julc -- julc mcp`), prefer MCP tools over copy/paste compile cycles. The server exposes:

| Tool | When to use |
|---|---|
| `julc_lint` | **First-line defense.** Run before `julc_compile` on any source the agent generated — catches the 15 most common AI-generated anti-patterns instantly. |
| `julc_compile` | Compile a JuLC source. Returns UPLC, script size, and structured diagnostics with `JULC####` codes. |
| `julc_evaluate` | Compile + run a method on supplied PlutusData arguments. Use for sanity-checking pure helpers. |
| `julc_estimate_costs` | Estimate script size; with method+args, report VM CPU/memory for that method invocation. Not a full validator transaction benchmark. |
| `julc_test` | Run `@Test public static boolean` methods in a test file. |
| `julc_stdlib_list` / `julc_stdlib_method` | Look up stdlib API surface. |
| `julc_ledger_type` | Field/variant info for ledger types (TxOut, Value, Address, ...). |
| `julc_builtins_list` | Available Plutus builtins. |
| `julc_examples_search` / `julc_example_get` | Find canonical examples by concept/tag. |
| `julc_explain_diagnostic` | Look up `JULC####` codes for canonical fixes. |

**Closed-loop authoring**: when generating new validator code, the recommended sequence is

1. `julc_lint(source)` — fix any error/warning findings before going further.
2. `julc_compile(source)` — read diagnostics; for any `JULC####`, call `julc_explain_diagnostic`.
3. `julc_evaluate(...)` or `julc_test(...)` to verify behavior.

If MCP is not available, fall back to the user copying the same artifacts from `https://julc.dev/ai/`.

## Slash commands

Available in `commands/`:

- `/julc new-validator` — scaffold a typed-record validator using idiomatic JuLC
- `/julc add-test` — add a `@Test public static boolean` method using julc-testkit
- `/julc debug-failure` — guided flow for diagnosing a compile/runtime failure
- `/julc explain-uplc` — interpret a UPLC dump (CPU/mem budget, script size)

## Hard rules (apply in every JuLC conversation)

1. **Always prefer typed records / sealed interfaces / `JulcList<T>` / `JulcMap<K,V>` over raw `PlutusData`.** Raw `ConstrData`/`IntData`/`BytesData`/`MapData`/`ListData` construction is the project's #1 anti-pattern.
2. **Single-assignment.** Locals are immutable except inside `while`-loop accumulators.
3. **No `return` inside a loop.** Use a result variable + post-loop `return`.
4. **Initialize at declaration.** No `int x; x = 1;`.
5. **Lambdas only as inline HOF arguments** — never stored and `.apply(...)`-ed.
6. **`Optional.of(x)` / `Optional.empty()`** — never `mkSome`/`mkNone`.
7. **Don't use `Tuple2`/`Tuple3` in `switch`** — call `.first()` / `.second()`.
8. **`@Entrypoint` parameters depend on validator kind.** Spending validators take `(Redeemer, ScriptContext)` or `(Datum, Redeemer, ScriptContext)`; non-spending validators take `(Redeemer, ScriptContext)`. Don't pass bare `BigInteger`.
9. **`@Param` types must not be `PlutusData.BytesData`, `PlutusData.MapData`, `PlutusData.ListData`, or `PlutusData.IntData`** — use `byte[]`, `BigInteger`, typed records, or redeemers. `@Param BigInteger` is supported.
10. **`ByteStringLib.zeros()` / `empty()` / `integerToByteString()` / `serialiseData()`** are on-chain only; in JVM tests call `Builtins.*` directly.
