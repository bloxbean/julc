JuLC is designed to be **AI-native**: a Java developer working with any AI agent can be productive in JuLC within minutes, with no JuLC training data required. This page is the entry point for using JuLC with AI tools.

## TL;DR

The single best thing to do is point your AI agent at the **[AI Starter Pack](/ai/starter-pack/)** or the full docs dump:

| File | When to use it |
|---|---|
| **[`/ai/starter-pack/`](/ai/starter-pack/)** | The single highest-leverage artifact. Distills what AI needs to write correct JuLC on first try (limitations, anti-patterns, idioms, error→fix table, canonical examples). ~15–40K tokens. |
| **[`/llms.txt`](/llms.txt)** | Curated index of the JuLC docsite, following [llmstxt.org](https://llmstxt.org/). Small, agent-friendly. |
| **[`/llms-full.txt`](/llms-full.txt)** | Full JuLC docsite concatenated as a single markdown file. Ingest this for full coverage (~50–150K tokens). |

## Per-tool setup

### Claude Code (CLI)

Drop a `CLAUDE.md` into the root of your JuLC project:

```bash
curl -o CLAUDE.md https://julc.dev/ai/starter-pack.md
```

Claude Code automatically reads `CLAUDE.md` at the start of every session, so the agent always has JuLC context.

For multi-project setups, you can also reference the hosted version from your global `~/.claude/CLAUDE.md`:

```markdown
When working in a JuLC project, follow the rules at https://julc.dev/ai/starter-pack/
```

### Cursor

Add a project-level rule at `.cursor/rules/julc.mdc`:

```bash
mkdir -p .cursor/rules
curl -o .cursor/rules/julc.mdc https://julc.dev/ai/starter-pack.md
```

Cursor automatically applies rules in `.cursor/rules/` when working in the project.

### Continue (VS Code / JetBrains)

Add a context provider in `.continue/config.json`:

```json
{
  "contextProviders": [
    {
      "name": "url",
      "params": {
        "url": "https://julc.dev/llms-full.txt"
      }
    }
  ]
}
```

### ChatGPT / Claude.ai (web)

For one-off conversations, paste this at the start of your chat:

```
I'm writing Cardano smart contracts in JuLC (Java → Plutus V3 UPLC compiler).
Read the JuLC AI Starter Pack at https://julc.dev/ai/starter-pack and follow
its rules strictly. In particular:
- Always prefer high-level type classes (TxOut, Value, Address, sealed
  interfaces, JulcList<T>, JulcMap<K,V>) over raw PlutusData.ConstrData/
  IntData/BytesData/MapData/ListData.
- The Java subset is restricted: no mutable variables, no lambda .apply(),
  no return inside while loops, no reflection.
```

For long-lived projects, attach the full docs dump (`https://julc.dev/llms-full.txt`) to your project files / custom GPT.

### Codex / Aider / other AI-coding tools

Most tools support project-level context files. Drop `https://julc.dev/ai/starter-pack.md` into your tool's equivalent of "system prompt" or "always include" file list.

## JuLC MCP server

The `julc mcp` subcommand runs an MCP server over stdio that exposes the JuLC compiler, linter, and evaluator as tools. Once configured, any MCP-compatible agent (Claude Code, Claude Desktop, Cursor, Continue, …) can compile, lint, and evaluate JuLC code directly — closing the loop so the AI recovers from its own errors without copy-pasting between you and the compiler.

### Install `julc` first

```bash
# macOS / Linux
brew install bloxbean/tap/julc
julc --version
julc mcp --help
```

If `julc --version` works, you're good. The `mcp` subcommand uses stdio — no port to configure.

### Tools exposed

The MCP server registers 13 tools (closed-loop compiler/evaluator tools, discovery tools, testkit, and conservative cost estimates), 6 read-only resources, 4 resource templates, and 4 prompt templates. The full list is also live at the `julc://server-info.md` MCP resource.

**Closed compile-feedback loop (Phase C):**

| Tool | Purpose |
|---|---|
| `julc_ping` | Round-trip check — returns the JuLC version. |
| `julc_compile` | Compiles JuLC source. Returns structured diagnostics with stable `JULC####` codes, UPLC, and script size. Accepts optional `librarySources` for `@OnchainLibrary` helpers. |
| `julc_lint` | Pre-compile static analysis. 15 rules covering switch-field shadow, raw `PlutusData` construction, `Optional.mkSome`/`mkNone` (which doesn't exist!), double `.hash()`, mutation, return-in-loop, lambda `.apply()`, banned `@Param` types, `Tuple2` switches, and more. |
| `julc_evaluate` | Compile + run a single static method on the JuLC VM. Accepts recursive PlutusData args (`{int}`, `{bytes}`, `{string}`, `{bool}`, `{unit}`, `{constr:{tag,fields}}`, `{list:[…]}`, `{map:[{key,value}]}`). Returns the extracted result + CPU/memory budget. |
| `julc_estimate_costs` | Conservative estimate: compile source for script size; if `method` + `args` are supplied, delegates to VM method evaluation for CPU/memory. Not a full transaction-context benchmark. |

**Discovery and testkit (Phase D):**

| Tool | Purpose |
|---|---|
| `julc_stdlib_list` | Enumerate every stdlib library + its method count. |
| `julc_stdlib_method` | Look up the signature, params, and return type of a single stdlib method. |
| `julc_builtins_list` | List Plutus builtins exposed by `Builtins.*`. |
| `julc_ledger_type` | Field/variant info for a ledger type (TxOut, Value, Address, sealed interfaces …). |
| `julc_examples_search` | Tagged search across the canonical examples corpus. |
| `julc_example_get` | Full source + commentary for a single example. |
| `julc_explain_diagnostic` | `JULC####` code → root cause + canonical fix. |
| `julc_test` | Run `@Test public static boolean` methods in a test source file; reports per-test pass/fail + CPU/mem budget. |

**Read-only resources** (URIs accessible via `resources/read`):

`julc://limitations.md`, `julc://diagnostics.json`, `julc://stdlib.json`, `julc://ledger.json`, `julc://builtins.json`, `julc://server-info.md`.

**Resource templates**:

`julc://stdlib/{lib}`, `julc://stdlib/{lib}/{method}`, `julc://ledger/{type}`, `julc://examples/{id}`.

**Prompt templates**:

`write-validator`, `migrate-from-aiken`, `migrate-from-plinth`, `debug-compile-error`.

### Claude Code

The recommended path is the `claude mcp add` command, which writes the server into `~/.claude.json`:

```bash
# Local scope (current project only — default)
claude mcp add --transport stdio julc -- julc mcp

# Or user scope (all your projects)
claude mcp add --transport stdio --scope user julc -- julc mcp
```

Then verify with `/mcp` inside Claude Code — `julc` should appear with all 13 tools.

For project-wide team sharing, use a checked-in `.mcp.json` at your repo root:

```json
{
  "mcpServers": {
    "julc": {
      "type": "stdio",
      "command": "julc",
      "args": ["mcp"]
    }
  }
}
```

### Claude Desktop

Edit `claude_desktop_config.json` (Settings → Developer → Edit Config):

```json
{
  "mcpServers": {
    "julc": {
      "command": "julc",
      "args": ["mcp"]
    }
  }
}
```

Restart Claude Desktop. The 13 tools should appear in the tool picker.

### Cursor

`.cursor/mcp.json` (project-local) or `~/.cursor/mcp.json` (global):

```json
{
  "mcpServers": {
    "julc": {
      "command": "julc",
      "args": ["mcp"]
    }
  }
}
```

### Continue

In `~/.continue/config.yaml` (or the equivalent `config.json`):

```yaml
mcpServers:
  - name: julc
    command: julc
    args: [mcp]
```

### Verifying the install

After configuring, ask the agent:

> Run the `julc_ping` MCP tool and tell me what it returns.

You should see `julc-mcp v<version> — alive`. If the tool isn't visible, check that `julc` is on the PATH the agent sees (use `which julc` from the same shell that launched the agent).

### Diagnostic codes vs. lint rule IDs

Two identifier schemes flow through the MCP tools:

- **`JULC####`** (e.g. `JULC0015`) — emitted by `julc_compile` and registered in [`/ai/diagnostics.json`](/ai/diagnostics.json). Look up canonical fixes there.
- **`JULC-LINT-*`** (e.g. `JULC-LINT-OPTIONAL-API`) — emitted by `julc_lint`. The `suggestion` field on each lint finding is authoritative; lint rule IDs are intentionally NOT in `/ai/diagnostics.json` (they are static-analysis hints, not runtime diagnostics). Some lint findings ALSO carry a `JULC####` code in their `diagnostic` field when the rule overlaps with a runtime diagnostic (e.g. `JULC-LINT-SWITCH-SHADOW` ↔ `JULC0021`); use that code for the catalog lookup if present.

## Additional Integrations

- **JuLC Claude Skill**: available in the repo at [`tools/claude-skill/julc`](https://github.com/bloxbean/julc/tree/main/tools/claude-skill/julc). It bundles the starter pack, JuLC slash-command workflows, and local MCP configuration.
- **Hosted SSE MCP endpoint** at `mcp.julc.dev` is still planned for users who don't want a local install.

Track progress in [ADR-020: AI-Ready Developer Experience](https://github.com/bloxbean/julc/blob/main/adr/020-ai-ready-developer-experience.md).

## Why this works

JuLC is a young language: it has been renamed and released after most LLM training cutoffs, so models have no training data for it. Without context, an AI will hallucinate — generating code that looks like Plutus or plain Java but does not compile.

The artifacts on this page solve that by **giving the agent everything it needs to know in one ingest**:

- **What the Java subset actually allows** (and what it doesn't — no mutation, no lambda `.apply()`, no `return` in `while` loops, etc.).
- **Stdlib API surface** with exact signatures, so the agent stops inventing methods that don't exist.
- **Ledger types catalog** so the agent uses `TxOut`, `Value`, `Address`, sealed interfaces — not raw `PlutusData`.
- **Known compiler limitations and gotchas** distilled from thousands of real test cases — the precise failure modes JuLC contributors have already debugged.
- **Canonical examples** drawn from [`julc-examples`](https://github.com/bloxbean/julc-examples) (cftemplates, nft, uverify, mpf, linkedlist, swap, lending, validators).

The result: a Java developer who has never seen JuLC can produce a working validator on first try, with the AI handling the JuLC-specific idioms.

## Feedback

The starter pack is a living document. If your agent fails on a specific JuLC pattern, [open an issue](https://github.com/bloxbean/julc/issues) — the failure mode probably belongs in the starter pack so the next agent doesn't repeat it.
