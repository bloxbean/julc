---
name: julc explain-uplc
description: Interpret a UPLC dump, CPU/mem budget, and script size for a compiled JuLC validator
---

# /julc explain-uplc

The user pastes a `julc compile` (or MCP `julc_compile`) result and asks "is this expected?" or "why is this so big / so expensive?". You explain the numbers and point to high-impact reductions.

## Inputs to expect

A compile result typically contains:

- `script_size`: bytes of CBOR-encoded script.
- `cpu_units` / `mem_units`: estimated execution budget at the eval input.
- `uplc`: the UPLC term tree as text.

Mainnet limits per script execution:
- CPU: **10 billion** units.
- Memory: **14 million** units.
- Script size: **8 KB** preferred (no hard-cap but block size limits aggregate).

JuLC validators typically come in 1–5K UPLC, 10–80M CPU, 30–150K mem per call. Outliers above 200M CPU usually have a fixable cause.

## How to read the UPLC

UPLC terms (use the catalog if you don't recognize one — `julc_builtins_list`):
- `force` / `delay` — strict / lazy evaluation markers; usually paired.
- `apply` — function application (`(force (lam x body) arg)`).
- `lam x body` — lambda abstraction.
- `con T value` — typed constant (integer, bytestring, unit).
- `MkCons` / `HeadList` / `TailList` — list builtins.
- `MkNilData` vs `MkNilPairData` — list-of-Data vs list-of-pairs nil seed.
- `IData` / `BData` / `MapData` / `ConstrData` — Plutus Data wrappers.
- `EqualsData` — full Data equality (slow on large structures); prefer `EqualsInteger` / `EqualsByteString` when types are known.

## Common explanations for high CPU

1. **`EqualsData` over large records** — replace with field-by-field typed comparison.
2. **`UnConstrData` repeated** — destructure once, bind locals, reuse.
3. **`map(...)` followed by re-iteration** — fuse traversals where possible.
4. **List operations on map data via `MapData`-wrapped lists** — `.insert()` / `.delete()` already return pair lists; don't re-wrap.
5. **Repeated `.signatories().contains(...)`** — bind to a local once.

## Output template

```
Script size: 412 B  (small — no concern)
CPU:         18.4M  (≈0.2% of mainnet limit)
Mem:         52K    (≈0.4% of mainnet limit)

Hot spots:
  - lam containing 3× UnConstrData on same `txInfo` — bind `var info = ctx.txInfo();` once
  - EqualsData over `Datum` record — switch to typed `==` on the integer field

Recommendation: reduce by ~25% with the two changes above. Re-run julc_compile after.
```
