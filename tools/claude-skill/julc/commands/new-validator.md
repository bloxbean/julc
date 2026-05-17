---
name: julc new-validator
description: Scaffold an idiomatic JuLC validator using typed records (no raw PlutusData)
---

# /julc new-validator

Generate a JuLC `@SpendingValidator` (or other validator type) using the canonical idiom: typed `record` for datum and redeemer, sealed interfaces for variants, no raw `PlutusData` construction.

## Steps

1. Ask the user (only what's missing from their prompt):
   - **Validator name** (e.g. `VestingValidator`).
   - **Kind** (`spending`, `minting`, `withdraw`, `certifying`, `voting`, `proposing`).
   - **Datum shape** in plain English — only for spending validators that need a datum. Spending validators may also use the 2-parameter no-datum form.
   - **Redeemer shape** — same as datum. Non-spending validators use the redeemer as the first entrypoint parameter. For minting policies, redeemer is often a sealed interface (`Mint` / `Burn`).
   - **Validation predicate** — what makes this transaction valid?

2. Confirm by sketching the records before writing code:

   ```
   record VestingDatum(PubKeyHash beneficiary, BigInteger deadline) {}
   record VestingRedeemer() {}
   ```

3. Generate the validator. **Required idioms:**
   - Annotation matches the kind (`@SpendingValidator`, `@MintingValidator`, etc.).
   - Datum and redeemer are nested `record`s declared on the validator class.
   - Entrypoint shape matches the validator kind:
     - Spending with datum: `@Entrypoint static boolean validate(Datum d, Redeemer r, ScriptContext ctx)`.
     - Spending without datum: `@Entrypoint static boolean validate(Redeemer r, ScriptContext ctx)`.
     - Minting/withdraw/certifying/voting/proposing: `@Entrypoint static boolean validate(Redeemer r, ScriptContext ctx)`.
   - Use `ctx.txInfo()`, then high-level accessors (`txInfo.signatories()`, `txInfo.validRange()`, `txInfo.outputs()`).
   - `signatories().contains(d.beneficiary())` rather than iterating raw byte arrays.
   - For range checks: `IntervalLib.contains(...)`, `IntervalLib.finiteLowerBound(...)`,
     `IntervalLib.finiteUpperBound(...)`, or record accessors `txInfo.validRange().from()` / `.to()`.

4. **Pre-flight via MCP** before showing the file to the user:
   - `julc_lint` — must produce zero error-level findings.
   - `julc_compile` — must succeed.
   - If anything fails, fix and retry; don't surface a broken validator.

5. Write the file under `src/<package-path>/<Name>.java`.

## Anti-patterns to refuse

- `new PlutusData.ConstrData(...)` — use the record.
- `BigInteger total = BigInteger.ZERO; total = total.add(x);` — single-assignment violation.
- `for (int i = 0; i < n; i++)` — use `while` accumulator.
- `return` inside a loop.
- `Optional.mkSome(x)` / `Optional.mkNone()`.
- Bare `BigInteger` as `@Entrypoint` parameter.

## Output

After writing, print a short summary:

```
✔ src/myorg/VestingValidator.java
  Datum:    VestingDatum(beneficiary, deadline)
  Redeemer: VestingRedeemer
  Lint:     clean
  Compile:  ok (UPLC 312 B)
```
