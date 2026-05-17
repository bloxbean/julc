---
name: julc debug-failure
description: Diagnose a JuLC compile or evaluation failure end-to-end via MCP
---

# /julc debug-failure

Walk a compile or evaluation failure to a fix using the MCP server. The user gives you a stack trace, error message, or "this script returns false but should return true" — you produce a root cause + fix.

## Decision tree

### 1. Compile error with `JULC####` code

The error message contains a code like `[JULC0021]`. Steps:

1. Call `julc_explain_diagnostic(code)` to get the canonical fix.
2. Re-read the failing code in light of the explanation.
3. Apply the fix. Re-run `julc_lint` and `julc_compile` to confirm.

### 2. Compile error without a code

Codes are still being rolled out. Match the message text against known patterns:

| Message contains | Likely root cause |
|---|---|
| "return inside while loop" | `JULC-LINT-RETURN-IN-LOOP` — refactor to accumulator + post-loop return |
| "single-assignment" / "re-assignment" | `JULC-LINT-MUTABLE-VAR` / `JULC-LINT-INCREMENT` |
| "unknown method" on `Optional.mkSome` / `mkNone` | `JULC-LINT-OPTIONAL-API` |
| "not a sum type" / "switch requires sealed" | `JULC-LINT-TUPLE-SWITCH` (Tuple2/3) |
| "not allowed for @Param" | `JULC-LINT-BANNED-PARAM-TYPE` |

If still unclear: run `julc_lint` against the source — the rule IDs above map directly to lint findings with explicit fix suggestions.

### 3. Compiles but evaluates incorrectly

Common shapes:

- **Off-by-one in `compareTo`**: switch case binding shadows the parameter (`JULC0021` / `JULC-LINT-SWITCH-SHADOW`). Different param names than constructor field names.
- **`map(...)` result wrong type**: `map` returns `ListType(DataType)` — wrap the accessor in `Builtins.unIData` / `unBData` (`JULC-LINT-MAP-RETURN-TYPE`).
- **Pair list crashes in VM**: pair lists must be seeded with `mkNilPairData()`, not `mkNilData()` (`JULC-LINT-MKCONS-PAIR-LIST`).
- **`.hash()` returns wrong bytes**: double-hash (`JULC-LINT-DOUBLE-HASH`). `pkh.hash()` already returns bytes.

Use `julc_evaluate(...)` with explicit datum/redeemer/context arguments to reproduce.

### 4. Tests pass on JVM but fail on-chain (or vice versa)

`ByteStringLib.zeros()`, `empty()`, `integerToByteString()`, `serialiseData()` use casts that fail off-chain. In tests call `Builtins.replicateByte(...)`, `new byte[0]`, `Builtins.integerToByteString(...)`, `Builtins.serialiseData(...)` directly (`JULC-LINT-BYTESTRINGLIB-OFFCHAIN`).

## Output template

```
Symptom:    <one-line restatement>
Root cause: <which limitation / rule fired>
Fix:        <minimal diff>
Verified:   julc_compile ✓ / julc_evaluate ✓ / julc_test ✓
```
