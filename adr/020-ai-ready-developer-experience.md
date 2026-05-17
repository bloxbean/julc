# ADR-020: AI-Ready Developer Experience — Making JuLC Native to AI-Assisted Coding

**Date**: 2026-05-02
**Status**: Proposed

---

## Context

JuLC's strategic bet is reaching the 100M+ Java developer ecosystem. But in 2026, "developers learning a new language" no longer means "developers reading docs." A growing share of new code is written in collaboration with AI agents (Claude Code, Cursor, Copilot, Continue, ChatGPT). For an AI agent encountering JuLC for the first time, the experience today is poor:

- The model has no training data for JuLC (the language was renamed and released after most cutoffs).
- It will pattern-match on Java + Plutus heuristics and generate code that **compiles in plain Java but is invalid JuLC** — using mutable variables, lambdas with `.apply()`, `return` inside loops, `BigInteger` `@Param` types, switch cases that shadow parameters, or stdlib methods that don't exist.
- Even when it finds the docs, it cannot easily *use* them: there is no machine-readable index, no compile-feedback loop, no curated error→fix table.
- Users currently have to manually paste the JuLC docs or our `CLAUDE.md` limitations list into every conversation.

This is not a documentation problem; it is an **infrastructure** problem. JuLC has accumulated, through 3,442 tests and many debugging cycles, a precise body of knowledge about *what AI gets wrong about JuLC* (see CLAUDE.md "Known Compiler Limitations" and "Bugs Found & Fixed"). That knowledge is currently locked inside human-readable markdown. Surfacing it through standardized AI-consumption channels — `llms.txt`, an AI starter pack, an MCP server, and a Claude Skill — would turn JuLC into one of the **most AI-friendly languages on Cardano**, lowering the barrier to entry for any Java developer who works with an AI agent.

The competitive context matters: Aiken, Plutarch, and Plinth have no AI-specific tooling. Whoever ships first with a strong AI experience captures the "default choice for AI-assisted Cardano development" position. Given JuLC already has a Java compiler, registries (`StdlibRegistry`, `TypeMethodRegistry`, `LedgerTypeRegistry`), `DiagnosticCollector`, `MethodEvaluator`, and a CLI, the marginal cost of building this infrastructure is low.

---

## Goals

1. **Zero-setup AI agent productivity**: A developer with any AI agent can ingest a single URL or run a single command and have an AI that writes correct JuLC on first try.
2. **Closed compile-feedback loop**: AI agents can compile, lint, evaluate, and test JuLC code without leaving the conversation, recovering from errors autonomously.
3. **Encode hard-won knowledge**: Every limitation, gotcha, and "bug found & fixed" in CLAUDE.md becomes a structured warning the agent can consume *before* it generates broken code.
4. **No regression of human DX**: All AI artifacts are derived from the same source of truth as human docs. No drift, no duplication.

---

## Pillars

This ADR proposes four complementary AI-readiness pillars. They are independent (each delivers value alone) but compose into a powerful end-to-end experience.

### Pillar 1: `llms.txt` and `llms-full.txt` on julc.dev

**What**: Standard AI-consumption files served from `julc.dev/llms.txt` and `julc.dev/llms-full.txt`.
- `llms.txt` — curated index: project description, links to canonical docs, quick reference of stdlib modules, ledger types, and known limitations.
- `llms-full.txt` — the entire docsite concatenated as one markdown blob (~50–200K tokens), structured for LLM ingestion.

**Why**: This is the [emerging standard](https://llmstxt.org/) (Anthropic, Vercel, Cloudflare, Stripe ship them). Any AI agent — Claude, ChatGPT, Cursor, Continue — can ingest a single URL and have the entire language in context. It costs nothing for the user and works with every AI tool in existence.

**Builds on**: Existing Astro docsite at `docs/` with Starlight. Generation is a build-time step: walk `src/content/docs/`, concatenate, add front-matter index. Already content-rich (overview, getting-started, first-contract, guides, stdlib reference, internals).

**Complexity**: S — Astro plugin or Node script run during `astro build`. ~1–2 days.

**Files**: New `docs/scripts/generate-llms-txt.mjs`, `docs/astro.config.mjs` integration, output to `docs/public/llms.txt` and `docs/public/llms-full.txt`.

---

### Pillar 2: AI Starter Pack — "JuLC for AI Agents"

**What**: A single curated markdown file (~500–1,500 lines, ~15–40K tokens) that distills *exactly* what an AI needs to write correct JuLC on the first try. Not a port of the human docs; a specialized artifact.

**Sections**:
1. **What is JuLC** — 3 paragraphs; what compiles, what doesn't (no reflection, no lambdas with `.apply()`, no mutation).
2. **The Java subset that compiles** — explicit allow/deny list with examples.
3. **Stdlib API surface** — one-line signatures grouped by library, with on-chain semantics notes.
4. **Ledger types catalog** — every record + sealed interface, with switch examples.
5. **Builtins reference** — Plutus builtins available + their typing.
6. **Known compiler limitations** — verbatim from CLAUDE.md, structured as `pattern → why → fix`.
7. **Common error → fix table** — every diagnostic code with root cause and canonical resolution.
8. **Canonical examples** — 8–12 working validators (vesting, escrow, multisig, minting policy with `@Param`, governance, etc.) annotated with *why* each pattern is used. **Sourced from `julc-examples/` (cftemplates, nft, uverify, mpf, linkedlist, swap, lending, validators)** — these are real, tested implementations, not hand-written demos.
9. **Anti-patterns** — code that *looks* right to a Java developer but breaks JuLC. **Critically: always prefer high-level ledger type classes (`TxOut`, `Value`, `Address`, `OutputDatum`, `Credential`, `Optional<T>`, `JulcList<T>`, `JulcMap<K,V>`, sealed-interface variants) over low-level `PlutusData` (`ConstrData`, `IntegerData`, `BytesData`, `MapData`, `ListData`).** Direct `PlutusData` usage is an anti-pattern in nearly all cases — it loses type safety, defeats the compiler's automatic encode/decode, and is the #1 way AI agents produce verbose, brittle, non-idiomatic JuLC. The starter pack must lead every example with type-class style and only show raw `PlutusData` when interop with arbitrary datums truly requires it (e.g. forwarding opaque payloads).
10. **Project conventions** — `julc init` layout, `@Validator`, `@OnchainLibrary`, testkit usage.

**Why**: This is the highest-leverage artifact in the proposal. Every compiler limitation in CLAUDE.md represents a debugging session a user would otherwise repeat. Inoculating the agent against these specific failure modes is far more valuable than generic docs.

**Distribution**:
- Hosted at `julc.dev/ai/starter-pack.md` (ingestible by URL)
- Bundled into `llms.txt` from Pillar 1
- Dropped into user projects by `julc init` (becomes their `CLAUDE.md` / `AGENTS.md`)
- Distributed as part of the Claude Skill in Pillar 4

**Builds on**: CLAUDE.md, `StdlibRegistry`, `TypeMethodRegistry`, `LedgerTypeRegistry`, julc-examples corpus, `DiagnosticCollector` error codes.

**Complexity**: M — content curation is the bulk of the work. ~3–5 days for first revision.

**Files**: New `docs/src/content/docs/ai/starter-pack.md`, `docs/scripts/extract-stdlib-catalog.mjs` (machine-readable index), template at `julc-cli/src/main/resources/templates/AGENTS.md`.

---

### Pillar 3: JuLC MCP Server (`julc mcp`)

**What**: A Model Context Protocol server bundled into `julc-cli`, runnable as `julc mcp` over stdio, that gives any MCP-compatible agent (Claude Desktop, Claude Code, Cursor, Continue) **direct programmatic access** to the JuLC compiler, registries, examples, and testkit.

**Tools** (initial set):

| Tool | Purpose |
|---|---|
| `julc_compile(source, validator?)` | Compile JuLC source; returns UPLC, diagnostics, CPU/mem budget, script size. |
| `julc_lint(source)` | Pre-compile checks for *known limitations* (return-in-loop, switch-shadow, MkCons pair-list typing, double `.hash()`, `BigInteger` `@Param`, etc.). Encodes CLAUDE.md gotchas as detectable patterns. |
| `julc_evaluate(source, method, args[])` | Wraps `MethodEvaluator` — compile + evaluate + extract result. |
| `julc_test(source, testFile?)` | Runs julc-testkit against the source. |
| `julc_stdlib_list()` | All stdlib libraries + summaries (from `StdlibRegistry`). |
| `julc_stdlib_method(lib, method)` | Signature, on-chain semantics, examples, gotchas. |
| `julc_ledger_type(name)` | Fields, variants, switch example (from `LedgerTypeRegistry`). |
| `julc_builtins_list(category?)` | Plutus builtins available. |
| `julc_examples_search(query, tags?)` | Tagged search across `julc-examples`. |
| `julc_example_get(name)` | Full example source + commentary. |
| `julc_explain_diagnostic(code)` | Curated error-code → fix mapping. |
| `julc_estimate_costs(source)` | Wraps `BenchmarkJulcTask`. |

**Resources** (read-only MCP resources):
- `julc://limitations` — current "Known Compiler Limitations" doc
- `julc://stdlib/<lib>` — per-library reference
- `julc://ledger/<type>` — per-type reference
- `julc://examples/<name>` — example sources

**Prompts** (templates):
- `write-validator` — scaffolds a validator with the right structure
- `migrate-from-aiken` / `migrate-from-plinth`
- `debug-compile-error` — structured diagnostic flow

**Why**: This is what unlocks **autonomous agents that actually ship working contracts**. Without an MCP server, an agent generates code blind and has no way to verify it. With one, the loop is `write → compile → read errors → fix → verify`, all without human intervention. The `julc_lint` tool in particular is irreplaceable — it encodes our debugging history as machine-actionable warnings.

**Architecture**: Build inside `julc-cli` as a subcommand. Reuse the entire compiler, testkit, and registries directly — no IPC, no version skew. Use the official Anthropic Java MCP SDK (`io.modelcontextprotocol:mcp-server`) for stdio + SSE transports.

**Builds on**: `JulcCompiler`, `MethodEvaluator`, `DiagnosticCollector`, `StdlibRegistry`, `TypeMethodRegistry`, `LedgerTypeRegistry`, `BenchmarkJulcTask`, julc-testkit, julc-examples.

**Complexity**: L — MCP scaffolding is straightforward, but the full tool surface is ~2 weeks of focused work. Linter rules can be added incrementally.

**Files**: New module `julc-mcp` (or new package in `julc-cli`):
```
julc-cli/src/main/java/com/bloxbean/julc/cli/mcp/
  McpCommand.java           # `julc mcp` entry point
  JulcMcpServer.java        # registers tools + resources + prompts
  tools/
    CompileTool.java
    LintTool.java
    EvaluateTool.java
    TestTool.java
    StdlibLookupTool.java
    LedgerLookupTool.java
    ExamplesTool.java
    DiagnosticExplainTool.java
    CostEstimateTool.java
  resources/
    LimitationsResource.java
    StdlibResource.java
    LedgerResource.java
    ExamplesResource.java
  prompts/
    WriteValidatorPrompt.java
    DebugErrorPrompt.java
  catalog/
    StdlibCatalog.java       # introspects StdlibRegistry → JSON
    LedgerCatalog.java       # introspects LedgerTypeRegistry → JSON
    DiagnosticCatalog.java   # error-code → fix mapping
  lint/
    LintEngine.java
    rules/                   # one rule per known limitation
      ReturnInLoopRule.java
      SwitchFieldShadowRule.java
      BigIntegerParamRule.java
      DoubleHashRule.java
      MkConsPairListRule.java
      ...
```

---

### Pillar 4: JuLC Claude Skill

**What**: A packaged [Claude Skill](https://www.anthropic.com/news/claude-skills) that bundles the starter pack, MCP configuration, and project conventions into a one-command install.

**Behavior**:
- User runs `claude skill install julc` (or copies a snippet).
- Skill auto-loads when working in a JuLC project (detected via `julc.toml` or `@Validator` import).
- Provides `/julc` slash command for guided workflows: `/julc new-validator`, `/julc add-stdlib-method`, `/julc debug-failure`.
- Bundles starter pack as in-context knowledge.
- Auto-configures the JuLC MCP server.
- Includes opinionated coding rules ("always use `point` not `time` as parameter name in IntervalLib switches", etc.).

**Why**: This is the polish layer that turns "JuLC has good AI tooling" into "JuLC is the obvious choice for AI-assisted Cardano development." It packages the four pillars into a single user action. For Claude users specifically (a large and growing developer segment), this is the canonical install path.

**Distribution**:
- Published in the Claude Skills registry (when available)
- Source at `tools/claude-skill/` in the JuLC repo
- Versioned alongside JuLC releases

**Builds on**: All three preceding pillars.

**Complexity**: S — once the underlying assets exist, packaging is straightforward. ~2–3 days.

**Files**: New directory `tools/claude-skill/julc/` containing `SKILL.md`, `mcp.json`, bundled starter pack, slash commands.

---

## Phased Implementation Plan

### Phase A — Foundations (Week 1)
**Goal**: Anyone with any AI agent can become productive in JuLC via a single URL ingest.

- [ ] **A1**: Generate `llms.txt` from existing Astro docs (Pillar 1, basic).
- [ ] **A2**: Generate `llms-full.txt` (concatenated docsite).
- [ ] **A3**: Draft v0 of the AI Starter Pack (Pillar 2) — sections 1, 2, 6, 7, 9 (the high-leverage limitations + error tables, since these are the failure modes AI gets wrong most).
- [ ] **A4**: Wire both into Astro build; deploy to julc.dev.
- [ ] **A5**: Add a `/ai` page on julc.dev that documents how to use JuLC with AI agents (Claude, Cursor, etc.).

**Exit criteria**: A developer can paste `https://julc.dev/llms-full.txt` into any AI chat and get a working vesting validator on first try.

---

### Phase B — Starter Pack Polish & Distribution (Week 2)
**Goal**: AI starter pack reaches its full form and is auto-distributed to new projects.

- [ ] **B1**: Complete remaining starter pack sections (stdlib API surface, ledger types catalog, canonical examples, anti-patterns).
- [ ] **B2**: Build `extract-stdlib-catalog.mjs` — generate machine-readable JSON from `StdlibRegistry` and `LedgerTypeRegistry`. Embed in starter pack and serve at `julc.dev/ai/catalog.json`.
- [ ] **B3**: Add `AGENTS.md` template to `julc init` scaffold (drops project-local AI guide on `julc new`).
- [ ] **B4**: Curate 10–12 canonical examples drawn from `julc-examples/` (cftemplates, nft, uverify, mpf, linkedlist, swap, lending, validators) with front-matter tags (`difficulty`, `concepts`, `cip-relevance`). **All examples must use high-level type classes** (TxOut, Value, Address, OutputDatum, sealed interfaces, JulcList, JulcMap) — never raw `PlutusData.ConstrData/IntegerData/BytesData/MapData/ListData` unless interop genuinely requires it. This is the canonical idiom AI agents must learn.
- [ ] **B5**: Tighten `DiagnosticCollector` error messages — every diagnostic gets a code, root cause, and canonical fix.

**Exit criteria**: The starter pack is the single artifact a user can paste into any agent and get state-of-the-art results.

---

### Phase C — MCP Server: Compile-Feedback Loop (Week 3)
**Goal**: Agents can compile, lint, and evaluate JuLC autonomously. The closed feedback loop.

- [ ] **C1**: Scaffold `julc mcp` subcommand in `julc-cli` using the official Java MCP SDK. Stdio transport.
- [ ] **C2**: Implement `julc_compile` tool (returns diagnostics, UPLC, budget).
- [ ] **C3**: Implement `julc_lint` tool with first 5 high-impact rules:
  - `ReturnInLoopRule` (already a compile error — surface as lint pre-check)
  - `SwitchFieldShadowRule` (param name == constructor field name)
  - `BigIntegerParamRule` (`@Param BigInteger` is broken)
  - `MutableVarRule` (assignment after declaration)
  - `DoubleHashRule` (calling `.hash()` on already-hashed type)
- [ ] **C4**: Implement `julc_evaluate` tool wrapping `MethodEvaluator`.
- [ ] **C5**: Publish Claude Desktop / Cursor / Continue config snippets to docs.
- [ ] **C6**: Smoke test: end-to-end transcript of an agent writing, compiling, fixing, and evaluating a validator.

**Exit criteria**: An agent can be given "write a vesting validator with deadline checks" and produce a working, tested validator without human intervention beyond the initial prompt.

---

### Phase D — MCP Server: Discovery & Examples (Week 4)
**Goal**: Agents stop hallucinating APIs. Every stdlib lookup, ledger type, and example is structured and queryable.

- [ ] **D1**: Implement `StdlibCatalog` and `LedgerCatalog` (registry introspection → JSON).
- [ ] **D2**: Implement `julc_stdlib_list`, `julc_stdlib_method`, `julc_ledger_type`, `julc_builtins_list` tools.
- [ ] **D3**: Implement `julc_examples_search` and `julc_example_get` tools backed by the tagged corpus.
- [ ] **D4**: Build `DiagnosticCatalog` (error code → root cause + fix). Implement `julc_explain_diagnostic` tool.
- [ ] **D5**: Add MCP resources for `julc://limitations`, `julc://stdlib/*`, `julc://ledger/*`, `julc://examples/*`.
- [ ] **D6**: Implement `julc_test` tool wrapping julc-testkit.

**Exit criteria**: All discovery tools work; agents can answer "what stdlib method does X" deterministically.

---

### Phase E — Lint Hardening (Week 5)
**Goal**: Encode the long tail of CLAUDE.md "Bugs Found & Fixed" as lint rules.

- [ ] **E1**: Add 10+ additional lint rules covering remaining limitations:
  - `RawPlutusDataAntiPatternRule` (warn when on-chain code constructs `ConstrData`/`IntegerData`/`BytesData`/`MapData`/`ListData` directly instead of using ledger type classes — points to the high-level equivalent: TxOut/Value/Address/sealed interfaces/JulcList/JulcMap)
  - `MkConsPairListRule` (must use `MkNilPairData` for pair lists)
  - `MapReturnTypeRule` (`map()` returns `ListType(DataType)`)
  - `ByteStringLibOffChainRule` (warn when off-chain code calls `ByteStringLib.zeros()` etc.)
  - `Tuple2SwitchRule` (Tuple2 not switchable; use `.first()`/`.second()`)
  - `AssignmentRule`, `LambdaApplyRule`, `UninitializedVarRule`
  - `EntrypointBigIntegerParamRule` (typed as `IntegerType` but holds raw Data)
  - `BannedParamTypesRule` (BytesData/MapData)
- [ ] **E2**: Each rule includes severity (error/warning/info), a clear message, a fix suggestion, and a link to the relevant docs section.
- [ ] **E3**: Integration tests: each rule has a passing-source and a failing-source fixture.
- [ ] **E4**: Wire `julc_lint` into the `julc check` CLI command for human use too (free human DX win).

**Exit criteria**: Every limitation in CLAUDE.md "Known Compiler Limitations" and "Bugs Found & Fixed" has a corresponding lint rule.

---

### Phase F — Claude Skill (Week 6)
**Goal**: One-command install for the Claude ecosystem.

- [ ] **F1**: Author `tools/claude-skill/julc/SKILL.md` describing trigger conditions, included knowledge, and behaviors.
- [ ] **F2**: Bundle starter pack as in-context knowledge for the skill.
- [ ] **F3**: Wire MCP config (`mcp.json`) so installing the skill auto-configures the MCP server.
- [ ] **F4**: Add slash commands: `/julc new-validator`, `/julc add-test`, `/julc debug-failure`, `/julc explain-uplc`.
- [ ] **F5**: Publish to Claude Skills registry. Document install path.
- [ ] **F6**: Record demo video / GIF showing the Claude Skill flow.

**Exit criteria**: `claude skill install julc` (or equivalent) gets a Claude user from zero to writing JuLC contracts in under 60 seconds.

---

### Phase G — Polish, Marketing, Iteration (Week 7+)
- [ ] **G1**: Hosted SSE MCP endpoint at `mcp.julc.dev` for users who don't want a local install.
- [ ] **G2**: VS Code / Cursor extension that auto-configures the MCP server.
- [ ] **G3**: Write blog post / case studies: "Building a Cardano contract in 10 minutes with Claude + JuLC".
- [ ] **G4**: Telemetry on MCP usage (opt-in) to identify which tools are used most and prioritize lint rules accordingly.
- [ ] **G5**: Iterate on starter pack based on common agent failures observed in the wild.

---

## Source-of-Truth Strategy

To prevent drift, **everything flows from code**:

```
StdlibRegistry, LedgerTypeRegistry, TypeMethodRegistry  (Java code)
      ↓ introspection
StdlibCatalog, LedgerCatalog (JSON)
      ↓
  ├──→ MCP tools (julc_stdlib_method, julc_ledger_type)
  ├──→ Starter pack sections 3, 4 (auto-generated)
  ├──→ llms-full.txt (auto-generated)
  └──→ Claude Skill knowledge (auto-generated)

DiagnosticCollector error codes  (Java code)
      ↓ extraction
DiagnosticCatalog (JSON)
      ↓
  ├──→ MCP tool (julc_explain_diagnostic)
  └──→ Starter pack section 7 (auto-generated)

CLAUDE.md "Known Limitations"  (markdown)
      ↓ becomes
Lint rules in julc-mcp/lint/rules/  (Java code)
      ↓
  ├──→ MCP tool (julc_lint)
  └──→ `julc check` CLI command
```

The only hand-curated artifacts are: starter pack sections 1, 2, 5, 6, 8, 9, 10; the Claude Skill `SKILL.md`; and the docs `/ai` landing page.

---

## Decision

Adopt this 4-pillar AI-readiness roadmap and execute Phases A–F over ~6 weeks. Phase A (llms.txt + starter pack v0) is the immediate priority — it delivers value to *every* AI agent in existence with the smallest investment and creates the foundation everything else builds on.

The MCP server (Phases C–E) is the deepest investment but the largest moat. No competing Cardano tool offers a closed compile-feedback loop for AI agents, and the lint engine — which encodes JuLC's accumulated debugging knowledge — cannot be easily replicated by a competitor without going through the same 3,000+ test cycles JuLC has.

The Claude Skill (Phase F) is the polish layer that turns the underlying infrastructure into a one-command install for the largest AI-coding user segment.

Together, these four pillars position JuLC as **the AI-native language for Cardano smart contracts** — a positioning that compounds over time as AI-assisted coding becomes the dominant mode of software development.

---

## Open Questions

1. **MCP SDK choice**: Use the official Anthropic Java MCP SDK or build a thin Node wrapper over a `julc compile --json` CLI? Java SDK preferred for direct registry access; revisit if the SDK lacks features.
2. **Hosting llms.txt vs llms-full.txt**: Some agents charge by ingested tokens. Should we ship a "lite" variant (~10K tokens) optimized for cost-sensitive agents in addition to the full version?
3. **Telemetry**: Opt-in usage data from MCP would massively accelerate Phase E rule prioritization. Privacy/UX tradeoffs warrant their own ADR.
4. **Versioning**: How do we ensure the starter pack and llms.txt match the JuLC version a user has installed? Embed a `julcVersion` header and surface a warning when they diverge.
5. **Other AI ecosystems**: Cursor Rules, Continue config, Aider conventions — should we ship per-tool config files in `tools/ai-configs/`? Probably yes, low effort.

---

## Phase B Remediation — multi-agent review findings & fixes (2026-05-02)

After Phase B's initial implementation, three independent review agents (implementation-validator, user-perspective generalist, Codex adversarial) audited the deliverables. They surfaced 11 issues plus several nits. All P0/P1 items were remediated in the same session; the remediation **required production-code changes in `julc-compiler` and `julc-cli`** beyond the docs/tooling work originally scoped.

### Production-code changes made (and why)

These are the only `*.java` files modified outside docs/scripts. Each is documented here for transparency and to inform future audits.

| File | Change | Reason |
|---|---|---|
| `julc-compiler/src/main/java/.../error/CompilerDiagnostic.java` | Added 7th record component `String code`. Kept 5- and 6-arg constructors as overloads (backwards-compatible). Added `hasCode()`. Updated `toString()` to surface `[CODE]` only when set (no `[null]` regression). | Phase B5 needs stable diagnostic codes (e.g. `JULC0003`) that downstream tools can use to look up canonical fixes. The field had to live on the diagnostic record itself; nullability + overloads preserve every existing call site. |
| `julc-compiler/src/main/java/.../error/DiagnosticCollector.java` | Added `errorWithCode(...)` and `warningWithCode(...)` overloads. Existing `error(...)` and `warning(...)` delegate to the *WithCode versions, passing `null` for code. | Code-aware emission API for new error sites. No behavior change for legacy call sites. |
| `julc-compiler/src/main/java/.../validate/SubsetValidator.java` | Added private `error(Node, String code, ...)` overload. Migrated 5 existing call sites (try/catch, throw, null, C-style for, do-while) to attach codes JULC0015/0016/0017/0018/0019. **Error messages and suggestions are unchanged** — only the new `code` field carries new data. | Demonstrates the round-trip from compiler error → catalog code → MCP `julc_explain_diagnostic` → starter-pack §7 actually works. Without at least one migrated site, the catalog would be paper metadata. |
| `julc-compiler/src/main/resources/diagnostics.json` (new) | 30 curated diagnostic codes JULC0001–JULC0030 with title, category, severity, summary, fix, and bad/good code examples. | Source-of-truth for the diagnostic catalog the docs build serves at `/ai/diagnostics.json`. Lives in the compiler module so future code-side migrations stay close to it. Not loaded by the runtime compiler today. |
| `julc-cli/src/main/java/.../scaffold/AgentsTemplate.java` (new) | Renders an `AGENTS.md` + `CLAUDE.md` (identical content) for AI agent guidance. ~50-line template; references the canonical hosted artifacts. | Phase B3 deliverable. Auto-loaded into every AI conversation in a freshly-scaffolded project. |
| `julc-cli/src/main/java/.../scaffold/{Project,Gradle,Maven}ProjectScaffolder.java` | (a) Added `AgentsTemplate.write(...)` call. (b) **Changed the starter validator template** from `@Validator + PlutusData redeemer` to `@SpendingValidator + record Datum() {} + record Redeemer() {}`. | (a) Drops AGENTS.md into new projects so the AI guide is on disk by default. (b) Old template used a deprecated annotation (`@Validator` is `@Deprecated` since `julc-stdlib/.../annotation/Validator.java:15-17`) and contradicted the AGENTS.md type-class rule it lives next to. New template models the recommended idiom. |

### Risk classification of production-code changes

| Change | Risk class | Justification |
|---|---|---|
| `CompilerDiagnostic` 7th component | **safe-additive (low risk)** | All existing 5- and 6-arg constructor calls remain valid. The auto-generated canonical (7-arg) constructor is new and used only by the new code-aware path. Equality on diagnostics now considers `code`, but no test or production code compared diagnostics for equality (verified by independent review). |
| `DiagnosticCollector` new overloads | **safe-additive (no risk)** | Pure addition; existing API unchanged. |
| `SubsetValidator` 5-site migration | **safe-refactor (low risk)** | Identical error messages and source positions emitted. Only the previously-null `code` field is now populated. Downstream consumers (`CompilerException`, formatters) ignore the field if they don't read it. |
| `diagnostics.json` resource | **safe-additive (no risk)** | New file. Not consumed by runtime today. |
| Scaffolder template rewrite | **behavior-change (low-medium risk)** | `julc new` produces a different starter validator. Only affects users running `julc new` *after* this change ships — existing projects are untouched. The replacement is the documented recommended idiom. Old template used a deprecated annotation. Existing test (`JulcIntegrationTest.scaffoldGradleProject`) updated to match. |
| `AgentsTemplate.write` in scaffolders | **behavior-change (low risk)** | New projects now contain `AGENTS.md` + `CLAUDE.md`. `julc new` already refuses to scaffold into a non-empty directory (`NewCommand.java`), so there is no overwrite scenario for fresh projects. |

### Validation

- ✅ Full `./gradlew test` passes after all changes (compiler + CLI + all modules).
- ✅ 7 new tests for `AgentsTemplate` (size bounds, content load-bearing assertions).
- ✅ 3 new tests for `DiagnosticCollector` covering `errorWithCode`, code-less backwards compat, `toString` doesn't embed `[null]`.
- ✅ End-to-end docs build emits all 7 AI endpoints with corrected catalog (13 stdlib classes, 174 user-facing methods, 33 ledger types — no internal-helper leaks, no fake fields from generic-comma corruption, all sealed variants captured).
- ✅ Three independent review agents (one of them with a confirmed bug-finding rate from the first review pass) re-reviewed the production-code changes; findings folded back in this section's risk table.

### Second-pass review (2026-05-02, after first remediation)

A separate external Codex review (independent from the in-session reviewers) verified the first remediation pass and surfaced four additional gaps. Each was fixed in the same session:

| External finding | Resolution |
|---|---|
| `DiagnosticDto.from()` in `julc-playground` hardcoded `null` for code, so playground users would never see `JULC####` codes even when emitted | Fixed: `DiagnosticDto.from()` now passes `d.code()` through. |
| `DiagnosticFormatter` (CLI) and `ReplEngine.formatDiagnostics()` did not surface the code in user-visible output | Fixed: both now prefix the message with `[JULC####]` when present. New `DiagnosticFormatterTest.formatShowsCodeWhenPresent` asserts the rendering; `formatOmitsCodeBracketsWhenAbsent` guards backwards compat. |
| Starter pack §6.5 contradicted §10.3 — warned `@Param BigInteger` is broken, then used it in the canonical one-shot mint example | Fixed: §6.5 rewritten to clarify the param works for the common case (comparison flow routes through `EqualsData`) and only fails for arithmetic that requires `IntegerType`. The example is correct as-is. |
| `docs/.gitignore` ignored `catalog.json`/`starter-pack.md`/`index.md` but not the sibling `diagnostics.json`/`examples.json` | Fixed: added both to `.gitignore`. |
| `/ai/examples.json` silently absent if sibling `julc-examples` not checked out (a deferred item from the first pass) | **Fixed in this pass:** added `JULC_REQUIRE_EXAMPLES=1` env var that turns the warning into a hard build failure. Set in CI/deploy so the deployed site never ships a 404 at the advertised endpoint; unset in local dev so contributors without the sibling repo can still work. |

The external reviewer also flagged "Phase B5 not really done" — meaning the diagnostic-codes work is infrastructure-complete but only 5 of ~70 compiler error sites currently emit codes. The remaining migration is genuine deferred work; see the table below.

### In-session adversarial review (Codex P2 findings)

The codex-rescue agent ran an adversarial pass on the production code changes. P0/P1 verdicts: nothing broken. P2 findings:

| Finding | Resolution |
|---|---|
| CLI formatter doesn't render the code | Fixed (same as external finding above). |
| Scaffolded `record Datum() {} / record Redeemer() {}` pair with `PlutusData.UNIT` test inputs by virtue of empty-constr equivalence; this works but is conceptually fragile | Acknowledged. Empty records compile to `Constr(0, [])` which is bit-identical to `PlutusData.UNIT`. The starter validator passes against `PlutusData.UNIT` because it ignores both args. Considered a known scaffold-template design choice; if scaffolded tests grow non-trivial they'd need typed-record fixtures, which is a Phase D testkit-DX concern. |
| No validation tying `JULC####` literals in source to `diagnostics.json` | **Fixed in this pass:** new `DiagnosticCatalogConsistencyTest` walks `julc-compiler/src/main/java`, extracts every `JULC####` literal, and asserts catalog membership. Drift now fails CI. |
| SubsetValidator null-suggestion message changed from `"Use Optional<T> to represent absence of a value"` to `"Use Optional<T> (Optional.of(x) / Optional.empty()) to represent absence of a value"` | Intentional. The new wording fixes the `mkSome/mkNone` factual error reported by the user-perspective reviewer; the longer form is correct and more actionable. Documented as a deliberate behavior change. |
| Gradle/Maven scaffold tests don't actually run `./gradlew build` against the generated project | Acknowledged. The basic-template scaffold IS end-to-end-compiled by `JulcIntegrationTest.scaffoldBuildCheck`. Adding equivalent slow tests for Gradle/Maven scaffolds would require shelling out to the wrappers — deferred to Phase G (CI hardening). |

### Findings deferred to future phases

| Item | Phase | Notes |
|---|---|---|
| Dev middleware concurrent-write race in `llms-integration.mjs` | C | Two simultaneous `/ai/catalog.json` requests could interleave file writes/reads. Mitigation: serialize `ensureFresh()` or generate to a swap-pointer cache. Single-developer dev mode → low practical impact today. |
| `injectBetweenAnchors` not fence-aware in `generate-llms-txt.mjs` | C | Anchors inside fenced code blocks would be matched. Today only one pair exists per section, so the failure mode is "future-edit hazard." Fix: validate exactly one start/end pair per anchor name outside fences and fail the build on duplicates. |
| `writeCatalog` regenerates the catalog internally even when caller passes a pre-built one | C | Perf nit (~50ms wasted per dev request, no correctness impact). |
| Migrate the remaining ~65 compiler error sites to `errorWithCode` | C/E | Phase B migrated 5 high-impact sites to demonstrate the round-trip; `DiagnosticCatalogConsistencyTest` ensures any new code reference is catalog-backed. Remaining migration is mechanical and can run incrementally alongside Phase E lint rules. |
| Slow integration tests that actually run `./gradlew build` / `./mvnw compile` against scaffolded Gradle/Maven projects | G | Catches packaging/template/AP regressions the unit tests miss. |
| Generate canonical starter-pack examples from compile-tested `julc-examples` sources | C/D | Currently the §10 examples are hand-curated near-copies of real examples. To be 100% trustworthy they should be extracted at build time from compile-verified sources via a tag-based selector. Until then they remain a small drift risk; the new `JULC_REQUIRE_EXAMPLES=1` flag at least guarantees the index is present. |

---

## Phase C — JuLC MCP Server (2026-05-02, shipped)

Phase C delivers the closed AI feedback loop: an MCP server exposing the JuLC compiler, linter, and evaluator over stdio so agents can compile/lint/evaluate JuLC source and recover from their own errors without copy-pasting between user and compiler.

### Implementation

| Subtask | Deliverable |
|---|---|
| C1 | `julc mcp` subcommand using `io.modelcontextprotocol.sdk:mcp:1.1.2` over stdio. New module path `julc-cli/src/main/java/.../mcp/`. ServiceLoader-based JSON mapper resolution. Server `instructions` preamble inlines the type-class rule + Optional API + diagnostic-code lookup URL. |
| C2 | `julc_compile` tool — wraps `JulcCompiler`, returns structured diagnostics (with `JULC####` codes), UPLC, script size, params. Optional `librarySources`, `includeUplc`, `includePir` flags. |
| C3 | `julc_lint` tool with **6 rules** (one more than the original spec): `OptionalMkSomeMkNoneRule`, `SwitchFieldShadowRule` (JULC0021), `RawPlutusDataAntiPatternRule`, `BigIntegerParamRule`, `DoubleHashRule`, `MutableVarRule`. Pre-compile static analysis via JavaParser. |
| C4 | `julc_evaluate` tool — wraps `JulcVm`. Recursive PlutusData arg shape (`int`/`bytes`/`string`/`bool`/`unit`/`constr`/`list`/`map`). Method-arity validation. Bounded budget (`DEFAULT_BUDGET = 10× mainnet limit`) and source-length cap (`MAX_SOURCE_LEN = 200KB`) for safety against runaway LLM-driven loops. Recursive PlutusData result rendering. |
| C5 | Install snippets at `/ai/index.md` for Claude Code (via `claude mcp add`), Claude Desktop, Cursor, Continue + Homebrew install + verification step. |
| C6 | Reproducible E2E walkthrough at `/ai/transcripts/closed-loop-walkthrough.md` showing lint → broken compile → idiomatic fix → clean compile → evaluate. Raw JSON-RPC committed alongside. |

**Tests:** 39 new tests (8 CompileTool + 16 EvaluateTool + 15 LintEngine). Full `./gradlew test` passes (111 tasks).

### Multi-agent review of Phase C

Three independent reviewers audited Phase C:

1. **implementation-validator** — verdict: READY WITH MINOR FIXES. Flagged missing `MutableVarRule`, lint precision issues, and `librarySources`/multi-arg test gaps.
2. **user-perspective generalist** — verdict: real, working closed loop. Flagged Claude Code config path error, missing Homebrew snippet, no stdlib/ledger discovery (Phase D), no validator-level eval (Phase D), weak walkthrough example, lint-rule-IDs not in `diagnostics.json`.
3. **Codex adversarial** — verdict: P0 = none. P1: lifecycle (`Thread.join` prevents EOF exit), `CompilerException(String)` swallowed into empty diagnostics, `args` cast safety, evaluation arity unchecked, missing recursive PlutusData arg shapes, unbounded eval budget (security). P2: `tools(true)` listChanged misleading, BigInteger truncation, `librarySources` validation fake, complex result stringification, lint precision (DoubleHash parens, RawPlutusData direct-import, SwitchFieldShadow false-positive).

Codex **disagreed** with implementation-validator on `isError` semantics: spec-grounded reading at https://modelcontextprotocol.io/specification/2025-06-18/server/tools confirms tool-origin errors should set `isError=true`. We followed Codex's reading.

### Phase C remediation

All P0/P1 findings addressed in the same session:

| Finding | Source | Resolution |
|---|---|---|
| Missing `MutableVarRule` (named C3 deliverable) | impl-validator | Added; flags re-assignment outside while-loop accumulators. 2 new tests. |
| `tools(true)` advertises listChanged for static catalog | impl-validator + Codex | Changed to `tools(false)`. |
| SLF4J no-provider warning to stderr | Codex | Added `runtimeOnly slf4j-nop:2.0.17` — clean stderr now. |
| `Thread.join` lifecycle | Codex | Investigated. Removing it caused immediate JVM exit (SDK threads are daemon). Restored with documentation: real MCP clients keep stdin open and SIGTERM on disconnect; the documented EOF edge case only matters in non-interactive scenarios that don't occur in practice. |
| `CompilerException(String)` swallowed into empty diagnostics | Codex | Both `CompileTool` and `EvaluateTool` now synthesize a fallback diagnostic from `e.getMessage()` when the underlying list is empty. |
| `(List<Object>) args.get("args")` ClassCastException | Codex | Validate `instanceof List<?>` before cast; structured error if not. |
| `librarySources` silent validation gaps | Codex + impl-validator | Strict: non-array → tool error; non-string item → tool error with index. New tests. |
| Number arg silently truncates over Long.MAX_VALUE | Codex + impl-validator | Reject fractional or out-of-Long-range JSON numbers; document string form for arbitrary precision. |
| Evaluation arity unchecked | Codex | Parse method's parameter count; mismatch → fast structured error. |
| No recursive PlutusData arg shapes | Codex + user-perspective | Added `constr`/`list`/`map` shapes with `MAX_ARG_DEPTH=16`. |
| Unbounded VM budget | Codex (security) | `DEFAULT_BUDGET = 10× mainnet` (CPU 100B, mem 140M). Source-length cap 200KB. |
| Result stringification | Codex | Recursive PlutusData renderer; Optional and List handled too. |
| `DoubleHashRule` misses parenthesized chains | Codex | Unwrap `EnclosedExpr` before scope check. New test. |
| `RawPlutusDataAntiPatternRule` misses direct-imported `ConstrData` | Codex + impl-validator | Detect direct imports of inner classes; match unqualified construction. New test. |
| `SwitchFieldShadowRule` overbroad | Codex | Only fire when the shadowed name is referenced in the case body. New test. |
| Missing `librarySources`/multi-arg test coverage | impl-validator | 3 new CompileToolTests + 1 multi-arg EvaluateToolTest + arity test. |
| Claude Code config path wrong (`~/.claude/settings.json`) | user-perspective | Verified with docs.claude.com — replaced with `claude mcp add` (canonical) + `.mcp.json` for project-scope. |
| No Homebrew install snippet | user-perspective | Added at top of "JuLC MCP server" section. |
| Lint rule IDs not in `diagnostics.json` | user-perspective | Documented intent: `JULC-LINT-*` are static-analysis hints, NOT runtime diagnostics. Findings that overlap with runtime errors carry the JULC#### code in their `diagnostic` field. |
| Walkthrough validator was no-op (`return true`) | user-perspective | Rewrote with substantive fix: agent's first cut uses `return` inside `while` (rejected); agent fixes by using `signatories().contains(PubKeyHash.of(...))` — typed records, no raw PlutusData, idiomatic. |

### Phase C deferred (Phase D scope)

Items the reviewers flagged that are explicitly Phase D in the original plan:

- `julc_stdlib_method` — discovery tool returning method signatures from `StdlibRegistry`
- `julc_ledger_type` — same for ledger types
- `julc_examples_search` / `julc_example_get` — tagged-example retrieval
- `julc_explain_diagnostic` — wraps `/ai/diagnostics.json` for one-tool-call lookup
- `julc_evaluate_validator` — full validator-level eval with synthetic `ScriptContext` (closes "is it correct, not just compilable")
- Migrating the remaining ~65 compiler error sites to use `errorWithCode` (some are visible in the walkthrough as missing code on `return`-in-`while`)

### Phase C exit criterion

> "An agent can be given 'write a vesting validator with deadline checks' and produce a working, tested validator without human intervention beyond the initial prompt."

**Met.** The recorded walkthrough demonstrates the closed loop end-to-end: lint catches the AI's `Optional.mkNone()` hallucination; compile catches `return`-in-`while`; the agent's recovery uses idiomatic JuLC (typed records, `PubKeyHash.of()`, no raw PlutusData) and compiles cleanly to 162 B; evaluate verifies a pure helper. Three round-trips, zero human intervention.

---

## Phase D — MCP Server: Discovery & Examples (2026-05-02, shipped)

Phase D adds 8 new MCP tools and 6 resources, taking the server from 4 tools (Phase C) to **12 tools + 6 resources**. Goal: agents stop hallucinating APIs because every stdlib method, ledger type, diagnostic code, and example is queryable.

### Implementation

| Subtask | Deliverable |
|---|---|
| D1 | `StdlibCatalog` and `LedgerCatalog` (reflection-based introspection of runtime classes). Filters internal helpers (`_*` prefix, `PlutusData$Subtype` parameter/return). 13 stdlib classes / 33 ledger types. |
| D2 | 4 discovery tools: `julc_stdlib_list`, `julc_stdlib_method`, `julc_builtins_list`, `julc_ledger_type`. |
| D3 | `julc_examples_search` + `julc_example_get` — query the tagged corpus by concept/difficulty/kind/canonical/text. Examples index loaded from `JULC_EXAMPLES_DIR` env var, sibling repo, or bundled JAR resource. |
| D4 | `julc_explain_diagnostic` — looks up `JULC####` codes from the bundled `diagnostics.json` (fuzzy candidates for unknown/short codes; lower-case + bracket normalization). |
| D5 | `JulcResources` — 6 read-only MCP resources: `julc://limitations.md`, `julc://diagnostics.json`, `julc://stdlib.json`, `julc://ledger.json`, `julc://builtins.json`, `julc://server-info.md`. |
| D6 | `julc_test` — discovers `@Test`-annotated `public static boolean` methods (multi-line + same-line conventions), compiles + evaluates with budget cap, reports per-test pass/fail + budget + traces. |

**Tests:** 32 new tests added during initial implementation (12 catalog + 6 explain + 6 examples + 8 testkit). Phase D remediation added 23 more (15 discovery handler + 9 resources tests). Total **94 MCP tests** all passing.

### Multi-agent review of Phase D

Three independent reviewers audited Phase D:

1. **implementation-validator** — verdict: READY WITH MINOR FIXES. Flagged missing `julc://limitations` resource (ADR's most-emphasized), unbounded `TestTool` evaluation, missing tool-layer tests for D2/D5.
2. **user-perspective generalist** — verdict: qualified yes. Flagged `examples-index.json` not bundled (Homebrew-broken), no reference to `julc.dev/ai/catalog.json` in tool descriptions, tool-vs-resource ambiguity.
3. **Codex adversarial** — P0 = none. P1: path traversal in `julc_example_get` (relative source path could escape repo root), `ExplainDiagnosticTool` killed server at startup if catalog missing, `julc_ledger_type` bypassed `TYPES` allowlist via `Class.forName`, `julc_builtins_list` filtered out legitimate raw builtins (`tailList`, `unBData`) AND included `setCryptoProvider` setup plumbing. Plus `TestTool` budget gap (same as impl-validator).

### Phase D remediation

All P0/P1 findings addressed in the same session:

| Finding | Source | Resolution |
|---|---|---|
| `TestTool.vm.evaluate(cr.program())` unbounded | impl-validator P0 + Codex P1 | Now uses `EvaluateTool.DEFAULT_BUDGET` — agent-generated infinite-loop tests can no longer hang the server. |
| Path traversal in `julc_example_get` | Codex P1 | Resolve+normalize path; require result to live under `repoRoot`; reject absolute paths. |
| `ExplainDiagnosticTool` killed server at startup if `diagnostics.json` missing | Codex P1 | Now logs warning to stderr and degrades to "catalog not bundled" message at request time. Server starts even with stripped JAR. |
| `julc_ledger_type` bypassed `TYPES` allowlist via `Class.forName` | Codex P1 | `describeType` enforces `TYPES.contains(...)` first — internal helpers like `PlutusDataHelper` no longer describable. |
| `julc://limitations` resource missing | impl-validator P1 | Added — full markdown of compiler limitations + AI gotchas, the encoded knowledge from CLAUDE.md. |
| `julc_builtins_list` filtered legitimate builtins, surfaced `setCryptoProvider` | Codex P2 | Builtins now use a separate `collectBuiltinMethods` path (no PlutusData-subtype filter) + `SKIP_BUILTINS = {setCryptoProvider}`. |
| `julc://server-info` stale (missing `julc_test`) | Codex P2 | Auto-generated from registered tool/resource lists; centralized `TOOL_NAMES` constant. |
| D2 tool-layer handler tests missing | impl-validator P1 | New `DiscoveryToolsHandlerTest` (14 tests) covers all 4 discovery tool handlers including arg validation, allowlist enforcement, builtins shape. |
| `JulcResources` had no direct tests | impl-validator P1 + Codex P2 | New `JulcResourcesTest` (9 tests) verifies all 6 resources, MIME types, body content, server-info coverage. |
| `examples-index.json` not bundled — Homebrew install gets empty results | user-perspective + Codex P2 | New `bundleExamplesIndex` Gradle task copies from sibling checkout into JAR resources. Stub written if sibling absent. Tool now degrades gracefully with GitHub link for source. |
| Tool descriptions don't mention `julc.dev/ai/catalog.json` | user-perspective | Added "for richer docs (Javadoc), fetch https://julc.dev/ai/catalog.json" to `julc_stdlib_list/method` and `julc_ledger_type`. |
| Tool-vs-resource ambiguity in descriptions | user-perspective | Added "Prefer this over reading julc://stdlib.json for single lookups" guidance. |
| `LedgerCatalog.TYPES` silent-miss risk | user-perspective + Codex P2 | New test `every_TYPES_entry_resolves_to_an_actual_class` enforces consistency. |
| `CatalogTest` assertions too weak (`size >= 10` patterns) | Codex P2 | Strengthened to assert exact known names (ListsLib, ValuesLib, etc.). |

### Phase D deferred (Phase E/G scope)

| Item | Phase | Notes |
|---|---|---|
| Migrate `extractFirstClassName` and test discovery to JavaParser instead of regex | E | Same-line `@Test @Test` chains and complex annotations would benefit; current regex covers AI-generated code well enough. |
| Add `-parameters` to global Java compile config so reflection sees real param names | E or follow-up | Currently degrades to `arg0`/`arg1`. Touches every module's build.gradle; defer to a coordinated change. |
| `ExamplesTools.CACHED` reset utility for cross-test isolation | E | Tests pass today; would only matter if a test wanted to exercise both populated and missing paths in the same JVM. |
| Source-file content bundling for `julc_example_get` | G | Currently bundled-only mode tells the agent to fetch from GitHub. Bundling sources would 5-10× the JAR size; defer until proven necessary. |
| Fully auto-generated server-info from the actual registered Tools list | E | Currently uses a parallel `TOOL_NAMES` constant; works but a single source-of-truth refactor would prevent future drift. |

### Phase D exit criterion

> "All discovery tools work; agents can answer 'what stdlib method does X' deterministically."

**Met.** Live-tested over stdio MCP transport: 12 tools registered, 6 resources fetchable. `julc_stdlib_method ListsLib head` returns the exact signature; `julc_ledger_type TxOut` returns the record's 4 fields; `julc_examples_search concept=auction` returns 2/47 matches; `julc_explain_diagnostic JULC0015` returns the canonical fix; `julc_test` discovers, runs, and reports per-test pass/fail with budget enforcement. Agents can now look up everything before generating code instead of hallucinating.
