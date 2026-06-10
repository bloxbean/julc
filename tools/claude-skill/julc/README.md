# JuLC Claude Skill

A Claude Skill that turns Claude into a fluent JuLC (Java → Cardano UPLC) collaborator. Bundles the canonical AI starter pack, slash commands for common workflows, and configuration for the JuLC MCP server.

## Layout

```
tools/claude-skill/julc/
  SKILL.md              # skill entry point — trigger conditions + hard rules
  mcp.json              # MCP server registration (julc mcp over stdio)
  commands/
    new-validator.md    # /julc new-validator
    add-test.md         # /julc add-test
    debug-failure.md    # /julc debug-failure
    explain-uplc.md     # /julc explain-uplc
  README.md             # this file
```

> Note: this is the **end-user** skill — for developers writing JuLC
> validators. JuLC compiler-developer test-automation skills live separately
> at `.claude/commands/julc-*.md`; see `adr/025-julc-test-automation-skills.md`.

## Install

1. **Install the JuLC CLI** (provides the MCP server):

   ```bash
   brew install bloxbean/tap/julc
   # or download from https://github.com/bloxbean/julc/releases
   ```

2. **Register the MCP server with Claude Code**:

   ```bash
   claude mcp add julc -- julc mcp
   ```

   Or if you prefer project-scoped configuration, drop `mcp.json` (this directory) into a `.mcp.json` file at the root of your JuLC project.

3. **Install the skill**:

   For now, copy this directory into Claude's skill search path. Refer to <https://www.anthropic.com/news/claude-skills> for the current installation procedure.

## Verifying the install

In a JuLC project (anywhere with a `julc.toml` or a class annotated with `@SpendingValidator`), run:

```
/julc new-validator
```

The skill should respond with the scaffolding flow. If it doesn't, check that:

- Claude Code can see the skill (the trigger keywords are in `SKILL.md`).
- `julc mcp` runs successfully on the command line (returns no error and waits on stdin).

## Authoritative artifacts

The skill defers to live, hosted artifacts so it stays in sync with the language:

- <https://julc.dev/ai/starter-pack.md>
- <https://julc.dev/ai/catalog.json>
- <https://julc.dev/ai/diagnostics.json>
- <https://julc.dev/ai/examples.json>

## Contributing

The skill content lives in this repo (`tools/claude-skill/julc/`). Updates to lint rule names or stdlib surface area should be reflected in `SKILL.md` so the skill's hard-rules section stays accurate.
