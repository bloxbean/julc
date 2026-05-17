# Bundled knowledge

This directory contains a frozen copy of the canonical AI artifacts so the skill works offline / when `julc.dev` is unreachable. The skill should still prefer the live URLs (see `SKILL.md`) when they are available — they pick up post-release fixes — but fall back to these bundled copies otherwise.

| File | Source | Purpose |
|---|---|---|
| `starter-pack.md` | `docs/src/content/docs/ai/starter-pack.md` | Full AI starter pack (Java subset rules, stdlib surface, ledger types, anti-patterns, canonical examples) |

## How to refresh

Run from the repo root:

```bash
cp docs/src/content/docs/ai/starter-pack.md tools/claude-skill/julc/knowledge/starter-pack.md
```

The file is plain markdown and identical to the deployed `https://julc.dev/ai/starter-pack.md`.

## Versioning

The bundled artifacts above are sourced from this repo. Match by commit SHA — see `git log -- tools/claude-skill/julc/knowledge/` for the last refresh.
