# ADR-076: Shared bindings for script size

**Status:** Proposed (2026-11-15). Implemented in the same change.
**Date:** 2026-11-15
**Risk class:** correctness-sensitive, **medium**.
- Two new rewrites run at `PV11_SAFE` and `PV11_COSTED`, the default levels, so script bytes and hashes change for most
  programs.
- Each moves only closed values, or functions every handler already evaluates, so what a script computes, where it
  fails and what it traces do not change.
- D1 keeps the budget of every run that reaches a handler. A run with no handler, which always fails at the dispatch,
  first evaluates the shared bindings.
- D2 lowers the budget on most paths, and can raise it by a few machine steps on a path that uses few shared values.

**Issue:** [#325](https://github.com/bloxbean/julc/issues/325)
**Relates to:** [#151](https://github.com/bloxbean/julc/issues/151) (script size research: ideas 1 and 2),
[ADR-074](074-stack-safe-term-walks.md) (stack-safe walks), [ADR-042](042-value-conversion-sharing.md) (O8),
[ADR-044](044-projection-sharing.md) (O15)

## Context

Issue #325 compares a counter state machine written twice with the same on-chain checks. JuLC's `PlainCounter` is
3,862 flat bytes; a hand-written Aiken counter is 1,769, so JuLC's is 2.18 times larger. The issue asks for three things:
1. attribute the bytes;
2. rank optimizations by measured savings;
3. keep the pair as a size benchmark, with its CPU and memory.

### Where the bytes went (measured on `fix/327` at `d212ed3c`, `PV11_SAFE`)

Each `let` of the compiled UPLC was listed with the FLAT size of its value, and every builtin site counted.

- **Duplicated handler bindings.** Each purpose handler bound its own copy of every helper and library method it used.
  - `PlainCounter` is a `@MultiValidator`, with mint and spend handlers of 1,514 and 1,798 B.
  - They shared about 780 B of methods, each written twice: `holder` (179 B), `_assetOf` (181), `findTokenAmount`
    (176), `signedBy` (89), `_tokensOf` (58), `getInlineDatum` (45) and `credentialHash` (38).
  - The cause is `ValidatorAssembly`, which wrapped the helpers and library methods around each handler separately.
- **Forced builtins.** `headList` appears at 140 sites, `tailList` at 108, `sndPair` at 56 (forced twice), `mkCons` at
  52, `nullList` at 26 and `fstPair` at 15 (forced twice). Each site costs 15 or 19 bits where a variable costs 12
  (#151 idea 2).
- **Fixpoint combinators.** Every recursive binding wrote its own copy of the 18-byte Z combinator (#151 idea 1).

## Goals

- Smaller scripts at the default levels, without changing what any script computes, where it fails or what it traces.
- A size and cost benchmark against Aiken that runs real transactions.
- Every change is switchable, so the effect of each rule can be tested by compiling with and without it.

## Non-goals

- **Closing the whole gap to Aiken.** The remaining duplication is listed under Open questions.
- **CSE of arbitrary closed terms** (#151 idea 4).
- **Field-walk sharing** (#151 idea 3).
- **Any change below `PV11_SAFE`.**

## Decision

### D1. Methods every handler needs are bound once, around the dispatch (`pv11.o16.shared-handler-bindings`)

Under auto-dispatch, `SharedBindings` shares a helper or library method when all three hold:
- **every handler needs it:** its entry point reaches it through the methods and static fields it names, read as free
  variables;
- **nothing it reaches is a validator static field**, since static fields stay bound inside each handler, where their
  initializers have always run;
- **it, and everything it reaches, is a function the optimizer keeps:** a lambda whose body is not just a builtin over
  its parameters.

`ValidatorAssembly` binds the shared methods once around the dispatch, inside the `@Param` lambdas. Everything else
stays inside each handler, as before.

**Why only kept functions.** The UPLC inliner replaces a binding used once by its value only when that value is a
constant, a variable or a (forced) builtin. A lambda over a builtin becomes a builtin by eta reduction. A handler that
called such a method once had it inlined, and could fold it further. Bound around the dispatch, it would be used twice
and kept, so the handler would do more work. A review measured that on a `five()` helper: +157,290 CPU per accepting
run, an extra `lessThanInteger` that no longer folded. A lambda is never inlined, so sharing one moves its binding
without changing what any handler evaluates.

A shared method's dependencies are shared too, so every name stays in scope. Each binding is a function, so evaluating
it before the dispatch instead of after it keeps every value, failure and trace. A run that reaches a handler takes the
same steps, since that handler bound the same methods. A run whose purpose has no handler fails at the dispatch.
Before, it evaluated no handler's bindings; now it evaluates the shared ones first. Its outcome, a failure, is the
same. Binding the shared methods inside each branch instead would bring back the copies D1 removes.

### D2. Repeated closed values are bound once, at the root (`pv11.o17.shared-closed-values`)

After the optimizer's fixpoint, `ClosedValueSharing` binds two kinds of value at the program root and replaces their
copies with variables:
- a builtin forced exactly as often as its type arity (one more force fails when it runs);
- the Z combinator, exactly as `UplcGenerator` writes it.

`[(\s. T[s/v]) v]` is a beta-expansion of `T` over a closed value `v`, so results, failures and traces are unchanged.
A value is shared where the bits it saves, counting each variable's real index width, exceed its binding's. The result
is then kept only if its whole FLAT encoding is smaller; FLAT pads before byte-aligned payloads, which the bit estimate
does not see.

D2 runs only on a closed term: the optimizer's public API also takes open terms, where a root binder would capture a
free variable. It counts a force chain before hashing it, so an arbitrarily long chain is rejected without recursion.
The binders are ordered by kind and builtin, so the output depends only on the term.

### D3. A size and cost benchmark

`CounterBenchmarkTest` compiles `PlainCounter` and runs it, and the Aiken counter's compiled script, on twelve real
script contexts: five accepting (Create, Burn, Increment, Reset, Close) and seven rejecting, each breaking one check.

It asserts:
- every JuLC variant (both rules, each alone, neither) and Aiken accept and reject the same transactions;
- JuLC's outcome and traces do not depend on the rules;
- D1 leaves every budget unchanged;
- `PlainCounter` is at most 2,750 bytes.

It prints the sizes and the CPU and memory of each accepting run.

### D4. The decompiler reads a script as it was before D2

`julc-decompiler` finds a validator's parameters from the outermost lambdas, and its recognizers match inline forced
builtins and the Z shape. `SharedRootBindings` substitutes a script's shared root bindings back before analysis.

## Affected stages and modules

- **PIR assembly:** `ValidatorAssembly` and the new `SharedBindings` (julc-compiler, codegen).
- **UPLC optimizer:** `UplcOptimizer` and the new `ClosedValueSharing`, which runs after the fixpoint (julc-compiler,
  uplc).
- **Rule registry:** `CompilationContext` registers both rules as switchable.
- **Decompiler:** the new `SharedRootBindings` (julc-decompiler).
- **Tests:** compiler goldens and frozen answers at the PV11 levels, the julc-benchmark budget tests, and the new tests
  named in Verification.

## Invariants

- **I1.** Neither rule changes a script's results, failures or traces. For D2 this is exact: substituting the root
  bindings back gives the program without the rule, term for term.
- **I2.** D2 changes the budget only by whole machine steps. Each executed use of a shared forced builtin saves one step
  per force: one for `headList`, two for `sndPair`. Each shared value adds its binding once, at most 5 steps: an
  application, a lambda and the value.
- **I3.** D1 leaves the budget of every run that reaches a handler unchanged: the same bindings are evaluated, all of
  them functions. A run with no handler pays for evaluating the shared bindings before it fails, which is a fixed
  number of whole steps per program.
- **I4.** Below `PV11_SAFE`, or with a rule disabled, the output is what it was.

## Results

`PlainCounter` at `PV11_SAFE`, in flat bytes:

| Build | Bytes | Against Aiken |
|---|---|---|
| Before (both rules off) | 3,862 | 2.18× |
| D1 only | 3,090 | 1.75× |
| D2 only | 3,402 | 1.92× |
| Both | **2,749** | **1.55×** |
| Aiken v1.1.23 | 1,769 | 1× |

CPU and memory per accepting transaction:

| Scenario | JuLC on | JuLC off | Aiken |
|---|---|---|---|
| mint Create | 65,663,291 / 192,020 | 68,623,291 / 210,520 | 46,720,866 / 113,908 |
| mint Burn | 21,727,311 / 74,381 | 22,783,311 / 80,981 | 10,457,021 / 36,257 |
| spend Increment | 69,605,587 / 191,221 | 72,853,587 / 211,521 | 53,519,371 / 132,898 |
| spend Reset | 72,612,468 / 202,735 | 76,084,468 / 224,435 | 56,429,656 / 141,883 |
| spend Close | 56,241,694 / 169,788 | 59,105,694 / 187,688 | 44,451,841 / 134,404 |

For the counter, every accepting run is cheaper with the rules. All of the change is D2, since D1 changes no budget. A
program that shares only the Z combinator, with no forced builtin to save on, pays D2's 3 binding steps on every run
(I2).

Across the 20,407-case corpus recorded for ADR-074, D2 makes 10,577 of 24,652 programs smaller at the two levels where
it runs. Their total falls from 8,387,601 to 7,521,804 bytes, −10.3 %. This was measured before the size guard of D2;
the guard only removes sharing that did not shrink a program.

## What the benchmark found besides size

- **The token name.** The two counters do not name the token the same way:
  - JuLC's `ValuesLib.uniqueTokenName` is `blake2b_256(txId ++ integerToByteString(BE, 2, index))`;
  - Aiken's counter uses `blake2b_256(serialiseData(seed))`.

  Each script is given contexts with its own name, and the test asserts each rejects the other's.
- **The datum.** Aiken's `datum: Option<State>` checks the spent datum's shape. JuLC's
  `(State) (Object) getInlineDatum(...)` does not. Every context in the benchmark has a well-formed datum.

## Alternatives rejected

- **Share a method used by at least two handlers, not all.** This saves more bytes in validators with three or more
  purposes, but a purpose that does not use a shared method would evaluate its binding. Sharing what every handler
  needs keeps each handler's work.
- **Share constants and builtin wrappers too, and teach the inliner to inline them anywhere.** This would save the last
  few bytes D1 gives up (2,749 against 2,741), but it changes the inliner's policy for every program, a separate
  decision.
- **Share every repeated closed term (CSE).** #151 idea 4 measured about 6.6 %, overlapping these two ideas heavily, and
  needs a cost model to keep from undoing the inliner.
- **Bind forced builtins in `UplcGenerator`.** The generator would have to keep the root binders in scope through every
  lowering, and the inliner could undo them. A pass after the fixpoint does neither.
- **Run D2 at `BASELINE`.** `BASELINE` keeps its bytes; the default level is where size matters.

## Compatibility

- Script bytes and hashes change at `PV11_SAFE` and `PV11_COSTED`, for any multi-purpose validator whose handlers
  share a function, and for any program with repeated forced builtins or recursion.
- Both rules can be disabled with `CompilerOptions.disableOptimizationRule`.
- The goldens and frozen answers at those levels were re-recorded (Verification).
- A program's root may now be an application, not a lambda. `Program.applyParams` only wraps applications, so it is
  unaffected; the decompiler inlines the bindings (D4).

## Risks

- **A value that is not closed or not a value.**
  - D2 checks closedness and force counts, and matches the Z combinator exactly.
  - D1 shares only functions; a change to how the javac frontend lowers methods must keep that test meaningful.
- **Budget.** D2 can raise a run's budget by up to 5 steps per shared value, on a run that uses few of them (I2). D1
  raises only the budget of a run that fails for having no handler (I3).

## Implementation milestones

1. D1 and D2, each behind its rule.
2. The counter benchmark against Aiken.
3. Reconciliation of goldens and other rules' cost tests, each held to its own guarantee with these rules off.
4. Review fixes: D1's kept-function test, D2's closed and size guards, D4.

## Verification

- **D2, exact.**
  - `ClosedValueSharingTest` substitutes the root bindings back and gets the original term on 3,000 random terms and
    on `PlainCounter` at both levels.
  - It also checks that over-forced builtins, long force chains and open terms are not shared, and that sharing never
    grows the encoding.
  - The same check over the whole corpus covers 24,652 programs, every one equal after substitution
    (`adr/evidence/076/SharingCorpusReplayTest.java.txt`).
- **D1, budget.**
  - `SharedBindingsTest` compiles a multi-validator with a `@Param`, a static, failing, tracing and constant zero-argument
    helpers, mutual recursion, and two-handler and three-handler variants.
  - It checks identical outcomes, traces, results and failure text with the rule on and off, on every context, at both
    levels, with and without source maps.
  - On every run that reaches a handler, the budget is identical too. The contexts with no handler cost exactly 30
    steps more in the two-handler variant, the shared bindings, and are pinned.
  - `CounterBenchmarkTest` asserts identical budgets on all twelve transactions.
- **Semantics.** `CounterBenchmarkTest`'s twelve transactions agree across every rule combination and with Aiken.
- **Goldens.** Every golden that moved is at `PV11_SAFE` or `PV11_COSTED`. With both rules forced off, every frozen
  answer equals the previous table.
- **Other rules' cost guarantees still hold.** The tests of other rules assert their own budget and size relations
  against the program compiled with both ADR-076 rules off. The rules-on program is checked by
  `Adr076Sharing.assertSharesOnlyClosedValues` and the I2 bound.
- **Suites.** The full build and the external `julc-examples` are recorded in the PR.

## Open questions

- **Strict-boundary checkers.** `PlainCounter` carries three, of 178, 72 and 150 B. Sharing their common machinery is
  the next measured candidate.
- **The stdlib's near-identical loops.** `ValuesLib.findTokenAmount` and `_assetOf` are 151 and 156 B of almost the
  same loop.
- **#151 idea 3.** Walk the field list of a record once for every field read.
