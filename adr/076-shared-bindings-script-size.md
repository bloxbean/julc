# ADR-076: Shared bindings for script size

**Status:** Proposed (2026-11-15). Implemented in the same change.
**Date:** 2026-11-15
**Risk class:** correctness-sensitive, **medium**.
- Two new rewrites run at `PV11_SAFE` and `PV11_COSTED`, the default levels, so script bytes and hashes change for most
  programs.
- Each rewrite moves only closed values or values the compiler already proves total, so what a script computes, where
  it fails and what it traces do not change.
- The budget moves: it falls on most paths and can rise by a few machine steps on some.

**Issue:** [#325](https://github.com/bloxbean/julc/issues/325)
**Relates to:** [#151](https://github.com/bloxbean/julc/issues/151) (script size research: ideas 1 and 2),
[ADR-074](074-stack-safe-term-walks.md) (stack-safe walks), [ADR-042](042-value-conversion-sharing.md) (O8),
[ADR-044](044-projection-sharing.md) (O15)

## Context

Issue #325 compares a counter state machine written twice with the same on-chain checks:
- JOUST-style Java, compiled by JuLC to `PlainCounter`: **3,862 flat bytes**;
- hand-written Aiken: **1,769**.

That is 2.18 times larger. The issue asks for three things:
1. attribute the bytes;
2. rank optimizations by measured savings;
3. keep the pair as a size benchmark, with its CPU and memory.

### Where the bytes went (measured on `fix/327` at `d212ed3c`, `PV11_SAFE`)

Each `let` of the compiled UPLC was listed with the FLAT size of its value, and every builtin site counted.

- **Each purpose handler bound its own copy of every helper and library method it used.** `PlainCounter` is a
  `@MultiValidator` with mint and spend handlers of 1,514 and 1,798 B. They shared about 780 B of methods, each written
  twice:
  - `holder`, 179 B;
  - `_assetOf`, 181 B;
  - `findTokenAmount`, 176 B;
  - `signedBy`, 89 B;
  - `_tokensOf`, 58 B;
  - `getInlineDatum`, 45 B;
  - `credentialHash`, 38 B.

  `ValidatorAssembly` wrapped the helpers and library methods around each handler separately.
- **Forced builtins:**
  - `headList` at 140 sites;
  - `tailList` at 108;
  - `sndPair` at 56, each forced twice;
  - `mkCons` at 52;
  - `nullList` at 26;
  - `fstPair` at 15, forced twice.

  Each costs 15 or 19 bits where a variable costs 12 (#151 idea 2).
- **Fixpoint combinators:** every recursive binding wrote its own copy of the 18-byte Z combinator (#151 idea 1).

## Decision

### D1. Methods every handler needs are bound once, around the dispatch (`pv11.o16.shared-handler-bindings`)

Under auto-dispatch, `SharedBindings` finds the helpers and library methods that every handler reaches through the
methods and static fields it names, read as free variables. It excludes any method that reaches a validator static
field. `ValidatorAssembly` binds those once around the dispatch, inside the `@Param` lambdas. Every other method, and
every static field, stays inside each handler as before.

A shared method's dependencies are shared too, so every name stays in scope. Each binding is a function, or one of the
total, cheap values the delayed-method pass leaves bound. So evaluating it before the dispatch, not after, keeps what
every purpose computes.

### D2. Repeated closed values are bound once, at the root (`pv11.o17.shared-closed-values`)

After the optimizer's fixpoint, `ClosedValueSharing` binds two kinds of value at the program root and replaces their
copies with variables:
- **a forced builtin**, but only one forced exactly as often as its type arity, since one more force fails when it
  runs;
- **the Z combinator** exactly as `UplcGenerator` writes it.

The program `[(\s. T[s/v]) v]` is a beta-expansion of `T` over a closed value `v`, so results, failures and traces are
unchanged. A value is shared only where the bits it saves, counting each variable's real index width, exceed its
binding's. The binders are ordered by kind and builtin, so the output depends only on the term.

### D3. A size and cost benchmark

`CounterBenchmarkTest` compiles `PlainCounter` and runs it and the Aiken counter's compiled script on twelve real
script contexts:
- **five accepting:** Create, Burn, Increment, Reset, Close;
- **seven rejecting**, each breaking one check.

It asserts:
- every JuLC variant (both rules, each alone, neither) and Aiken accept and reject the same transactions;
- JuLC's outcome and traces do not depend on the rules;
- `PlainCounter` is at most 2,750 bytes.

It prints the sizes and the CPU and memory of each accepting run.

## Invariants

- **I1.** Neither rule changes a script's results, failures or traces. For D2 this is exact: substituting the root
  bindings back gives the program without the rule, term for term.
- **I2.** D2 changes the budget only by whole machine steps, and raises it by at most 5 steps per shared value (the
  root bindings run before any failure):
  - each executed use of a forced builtin saves one;
  - each shared value adds its binding (an application, a lambda and the value) once.
- **I3.** D1 keeps each handler's work. Its budget can still move a little:
  - a helper a handler used once was inlined into it, and once shared it is a binding, a few steps more;
  - other paths can come out cheaper, because the optimizer sees the method outside the handler.
  - The benchmark pins the measured deltas.
- **I4.** Below `PV11_SAFE`, or with a rule disabled, the output is what it was.

## Results

`PlainCounter` at `PV11_SAFE`, in flat bytes:

| Build | Bytes | Against Aiken |
|---|---|---|
| Before (both rules off) | 3,862 | 2.18× |
| D1 only | 3,082 | 1.74× |
| D2 only | 3,402 | 1.92× |
| Both | **2,741** | **1.55×** |
| Aiken v1.1.23 | 1,769 | 1× |

CPU and memory per accepting transaction (JuLC both rules on, JuLC both off, Aiken):

| Scenario | JuLC on | JuLC off | Aiken |
|---|---|---|---|
| mint Create | 65,711,291 / 192,320 | 68,623,291 / 210,520 | 46,720,866 / 113,908 |
| mint Burn | 21,775,311 / 74,681 | 22,783,311 / 80,981 | 10,457,021 / 36,257 |
| spend Increment | 69,605,587 / 191,221 | 72,853,587 / 211,521 | 53,519,371 / 132,898 |
| spend Reset | 72,148,933 / 201,875 | 76,084,468 / 224,435 | 56,429,656 / 141,883 |
| spend Close | 56,241,694 / 169,788 | 59,105,694 / 187,688 | 44,451,841 / 134,404 |

Both rules make every accepting run cheaper. D2 alone saves 56 to 217 steps per run.

D1 alone moves these budgets:
- mint paths and Reset by a non-owner: +3 steps. `ByteStringLib.equals` was inlined into the mint handler, and is
  now bound;
- the accepted Reset: −463,535 CPU;
- the other paths: unchanged.

Across the 20,407-case corpus recorded for ADR-074, D2 makes 10,577 of 24,652 programs smaller at the two levels where
it runs. Their total falls from 8,387,601 to 7,521,804 bytes, −10.3 %.

## What the benchmark found besides size

- **The token name.** The two counters do not name the token the same way:
  - JuLC's `ValuesLib.uniqueTokenName` is `blake2b_256(txId ++ integerToByteString(BE, 2, index))`;
  - Aiken's counter uses `blake2b_256(serialiseData(seed))`.

  Each script is given contexts with its own name, and the test asserts each rejects the other's.
- **The datum.** Aiken's `datum: Option<State>` checks the spent datum's shape. JuLC's
  `(State) (Object) getInlineDatum(...)` does not. Every context in the benchmark has a well-formed datum.

## Alternatives rejected

- **Share a method used by at least two handlers, not all.** This saves more bytes in validators with three or more
  purposes, but each purpose that does not use a shared method would evaluate its binding. Sharing what every handler
  needs keeps each handler's work.
- **Share every repeated closed term (CSE).** #151 idea 4 measured about 6.6 % from the largest repeated closed
  subterms, overlapping these two ideas heavily. It needs a size and cost model to keep from undoing the inliner, and
  was left for later.
- **Bind forced builtins in `UplcGenerator`.** The generator would have to keep the root binders in scope through
  every lowering, and the optimizer's inliner could undo them. A pass after the fixpoint does neither.
- **Run D2 at `BASELINE`.** `BASELINE` keeps its bytes; the default level is where size matters.

## Compatibility

- Script bytes and hashes change at `PV11_SAFE` and `PV11_COSTED` for most programs: any multi-purpose validator
  whose handlers share a method, and any program with repeated forced builtins or recursion.
- Both rules can be disabled with `CompilerOptions.disableOptimizationRule`.
- The goldens and frozen answers at those levels were re-recorded (Verification).

## Risks

- **A value that is not closed or not a value.**
  - D2 checks force counts against `BuiltinSemantics` and matches the Z combinator exactly.
  - D1 relies on the delayed-method pass to make every binding a function or a total value; a change to that pass
    must keep it.
- **Budget on multi-purpose validators.** D1 can add a few steps where it stops an inlining (I3).

## Verification

- **D2, exact:**
  - `ClosedValueSharingTest` checks that substituting the root bindings back gives the original term on 3,000 random
    terms and on `PlainCounter` at both levels.
  - It also checks that over-forced builtins are not shared, that a use under 20,000 lambdas is not worth a variable,
    and that a 50,000-deep term works on a 256 KB stack.
  - The same check over the whole corpus: 24,652 programs, every one equal after substitution
    (`adr/evidence/076/SharingCorpusReplayTest.java.txt`).
- **D1 and D2, semantics:** `CounterBenchmarkTest`'s twelve transactions agree across every rule combination and with
  Aiken.
- **Goldens.** Every golden that moved is at `PV11_SAFE` or `PV11_COSTED`. With both rules forced off, all 8,679
  frozen answers equal the previous table.
  - **ADR-068 frozen reference:** 218 answers moved. 212 are lowering fixtures (106 per level, without source maps),
    4 are contract bytes and 2 are size vectors.
  - **ADR-060 snapshot:** 97 rows moved, all by D2. The 52 not already explained are listed by exact id.
  - **BLS pins:** seven `O11BlsTypesTest` pins shrank. With the rules off, each fixture keeps its old size and hash.
- **Other rules' cost guarantees still hold.**
  - The tests of other rules (pair case, switch pair case, integer case, list case, O9, O15) assert their own budget
    and size relations against the program compiled with both ADR-076 rules off.
  - They check each rules-on program with `Adr076Sharing.assertSharesOnlyClosedValues`: D1 did not apply,
    substitution gives the rules-off term, and the bytes are no larger.
  - They also bound its budget at the rules-off run plus 5 machine steps per shared value.
  - 224 runs cost more with the rules on. Every increase is whole steps; the largest is 6 steps with 2 shared values,
    on a rejecting run.
- **Suites:** `:julc-compiler:test` (5,749), `:julc-compiler:pairCaseTest` (72) and `:julc-core:test` (913) pass. The
  full build and the external `julc-examples` are recorded in the PR.

## Open questions

- **Strict-boundary checkers.** `PlainCounter` carries three, of 178, 72 and 150 B. Sharing their common machinery is
  the next measured candidate.
- **The stdlib's near-identical loops.** `ValuesLib.findTokenAmount` and `_assetOf` are 151 and 156 B of almost the
  same loop.
- **#151 idea 3.** Walk the field list of a record once for every field read.
- **The Reset saving under D1.** Which pass makes the accepted Reset cheaper by 463,535 CPU is not yet attributed.
