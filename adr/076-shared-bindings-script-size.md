# ADR-076: Shared bindings for script size

**Status:** Proposed (2026-11-15). Implemented in the same change.
**Date:** 2026-11-15
**Risk class:** correctness-sensitive, **medium**.
- Two new rewrites run at `PV11_SAFE`, the default level, and at the opt-in `PV11_COSTED`, so script bytes and hashes
  change for most programs at those levels.
- Each moves only closed values, or functions every handler already evaluates, so what a script computes, where it
  fails and what it traces do not change.
- D1 runs on the optimizer's output and only merges bindings every handler compiled to the same function, so a run that
  reaches its handler's bindings keeps its budget exactly (I3). A run that fails before them, for want of a handler, at
  its boundary check or in a static initializer, first evaluates the shared bindings.
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

- Smaller scripts at `PV11_SAFE`, the default level, and at `PV11_COSTED`, without changing what any script computes,
  where it fails or what it traces.
- A size and cost benchmark against Aiken that runs real transactions.
- Every change is switchable, so the effect of each rule can be tested by compiling with and without it.

## Non-goals

- **Closing the whole gap to Aiken.** The remaining duplication is listed under Open questions.
- **CSE of arbitrary closed terms** (#151 idea 4).
- **Field-walk sharing** (#151 idea 3).
- **Any change below `PV11_SAFE`.**

## Decision

### D1. Methods every handler binds the same way are bound once, where the handlers branch (`pv11.o16.shared-handler-bindings`)

D1 is a UPLC pass (`HandlerBindingSharing`), the last one the optimizer runs: after the fixpoint, and after D2. Its
input is the program as it is compiled without D1, so every handler's code is what it would be without D1. D1 only
replaces the duplicate bindings of a method with one.

**Candidates, from the PIR (`SharedBindings`).** Under auto-dispatch, `ValidatorAssembly` hands the backend the
helpers and library methods that:
- **every handler needs:** its entry point reaches the method through the methods and static fields it names, read as
  free variables;
- **reach no validator static field**, since static fields stay bound inside each handler, where their initializers have
  always run;
- **are functions, as is everything they reach:** a lambda with parameters, not a builtin wrapper the optimizer would
  inline. A function is total and has no effect, so evaluating its binding earlier changes no result, failure or trace.

**What is shared, from the optimizer's output.** One walk finds each candidate's bindings, `[(\m. body) value]`, by
name. A method's binder is `Owner.name`, which no local and no generated `#` binder can be. A candidate is shared when
all of these hold:
- **One per handler.** It is bound once in each of as many *let spines* as there are handlers. A let spine is a run of
  nested `let`s. Every shared method is bound in the same spines.
- **Run once.** Between the point where the handlers branch and the binding there is no lambda but `let`s'. Once the path
  below that point enters a `let`'s value, it passes no `Delay`, `Case` branch or lambda. Any of those could run the
  binding zero or many times. Another method's value is such a value, so a binding inside it moves with it and is not
  shared out of it.
- **The same function.** Each binding is the same term, where a variable bound inside it has the same index and one bound
  outside it is the same declaration: the very binder, for one bound above the handlers, or in each handler the binder
  the same method's binding declares. A binder is known by where it is declared, never by its name or depth, which two
  binders can share (Codex rounds 7 and 8).
- **Total, no effect.** The value is pure (`UplcOptimizer.isPure`: a value, a pure term or a fixpoint of a lambda), or
  the fixpoint of a lambda over a value read from a binder of the Z combinator, as D2 leaves it. It is not a value
  `UplcOptimizer.inlinesWhenUsedOnce` accepts.
- **In scope.** Every variable it reads is bound above that point, or is another shared method.

A term that reads a variable nothing binds is left as it is.

A method that reads one that is not shared is not shared either. The shared bindings wrap the smallest term that holds
every binding of every shared method, in dependency order, and are removed from each handler. Their variables are
rebuilt for the new binders. The result is kept only if its FLAT encoding is smaller.

**Why the program means the same.** Each value is the same function the handlers bound, reading the same binders, and
it is total and has no effect. Evaluating it once, before the handlers branch, instead of in the handler that runs,
gives the same results, failures and traces.

**Why each handled run costs the same.** No handler's code is re-optimized. `ValidatorAssembly` binds a handler's
methods at the start of the handler, after its dispatch branch and boundary check, so each handled run passes through
exactly one handler's spine, once. That is the one fact about the program D1 takes from the assembly and does not check
itself. The checks above rule out every other way a binding could run more or less often. A run evaluated each of its
handler's bindings once, and now evaluates the same bindings once on the way to it.

A run that fails before its handler's bindings now evaluates the shared ones first and pays for them. That is a run
where no handler takes the purpose, where the purpose's boundary check fails, or where a static initializer fails.

**Why on the optimizer's output.** Three reviews found ways a decision made earlier, in the PIR, could raise a handled
run's budget, because the optimizer then works on one copy where it had worked on one per handler:
- **A helper that becomes a builtin once compiled.** Examples are `PlutusData e = d; return Builtins.unIData(e);`,
  `BigInteger unused = BigInteger.ZERO; return a.add(b);`, and `(int) Builtins.indexByteString(bytes, index)`, whose
  cast `NarrowingElisionPass` removes. Each handler inlined it, and folded `add(ONE, ONE)`; shared, it stayed bound,
  at 377,541 CPU more per run.
- **A fold that depends on how often a name is bound.** `JulcArray.of` bound in each copy of a helper is not folded,
  while one shared copy would fold to a partial application that costs two steps more to bind.
- **D2's profitability.** It counts the uses of a forced builtin, and sharing a method's copies before D2 lowers that
  count.

Optimizing each handler first and merging only identical bindings removes all three by construction. A trial lowering
of the program without D1, tried in review, settles the first two but not the third, and it lowered every
multi-validator twice.

With source maps the optimizer does not run, and neither does D1.

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

- **PIR assembly:** `ValidatorAssembly` and the new `SharedBindings`, which finds D1's candidates (julc-compiler,
  codegen); `PirBackend.Bindings` carries them to the optimizer.
- **UPLC optimizer:** `UplcOptimizer` and the new `ClosedValueSharing` (D2) and `HandlerBindingSharing` (D1), which run
  after the fixpoint, in that order (julc-compiler, uplc).
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
- **I3.** With D1, every run that reaches its handler's bindings takes exactly the steps it takes without D1, with D2
  on or off. A run that fails before them pays for the shared bindings first. Each costs an application, a lambda and
  its value's own steps: one for a lambda, three for a partial application of a builtin, more for a recursive method's
  fixpoint. Such a run is one with no handler, a failed boundary check or a failed static initializer. Inlining every
  method binding gives the same program with or without D1, as FLAT.

  As for D2, results and traces are the same whenever the budget suffices. Under a budget cut short between the
  shared bindings and a trace, a run can stop before the trace.
- **I4.** Below `PV11_SAFE`, or with a rule disabled, the output is what it was.

## Results

`PlainCounter` at `PV11_SAFE`, in flat bytes:

| Build | Bytes | Against Aiken |
|---|---|---|
| Before (both rules off) | 3,862 | 2.18× |
| D1 only | 3,091 | 1.75× |
| D2 only | 3,403 | 1.92× |
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

- **Decide D1 before the optimizer.** Binding the shared methods once in the PIR assembly, and then optimizing,
  was the first design. Review showed the optimizer then treats one copy differently from one per handler (D1, "Why on
  the optimizer's output"). Each patch fell short:
  - a PIR shape check;
  - a check of each method optimized alone, which missed the PIR passes and cost a lowering per method and dependency;
  - a trial lowering of the whole program without D1, which missed D2's counts and lowered every multi-validator twice.
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
  - D1 shares only what the optimizer's output binds the same way in every handler, reading only what is bound above
    the handlers, and only candidates the PIR shows to be functions. It finds the bindings by the method's binder name,
    so a change that renames a method's binder in UPLC, or binds methods some other way, makes D1 share less, not more.
- **Budget.** D2 can raise a run's budget by up to 5 steps per shared value, on a run that uses few of them (I2). D1
  raises only the budget of a run that fails before its handler's bindings (I3). On Cardano a failing script forfeits
  its collateral, whatever budget it used.
- **The assembly's shape.** D1 checks what it can from the term: one binding per spine and handler, no lambda,
  delay or branch that could run it other than once. It trusts the assembly that each handled run passes through its
  own handler's spine once. `HandlerBindingSharingFuzzTest` and `SharedBindingsTest` check the budgets on every
  fixture, and `HandlerBindingSharingTest` the shapes that must not be shared.
- **A mutually recursive group.** Bekić's lowering nests copies of the methods in each other, so a group is shared only
  where its bindings line up as one function per handler. The two-handler fixture shares `odd`, with the copy of `even`
  nested in it, and keeps `even`'s own bindings.

## Implementation milestones

1. D1 and D2, each behind its rule.
2. The counter benchmark against Aiken.
3. Reconciliation of goldens and other rules' cost tests, each held to its own guarantee with these rules off.
4. Review fixes: D1 inside the context lambda, D2's closed and size guards, D4.
5. Review fixes (Codex rounds 3 to 5): D1 moved from the PIR assembly to a UPLC pass after D2, sharing only the
   bindings every handler compiled to the same function.

## Verification

- **D2, exact.**
  - `ClosedValueSharingTest` substitutes the root bindings back and gets the original term on 3,000 random terms and
    on `PlainCounter` at both levels.
  - It also checks that over-forced builtins, long force chains and open terms are not shared, and that sharing never
    grows the encoding.
  - The same check over the whole corpus covers 24,652 programs, every one equal after substitution
    (`adr/evidence/076/SharingCorpusReplayTest.java.txt`).
- **D1, exact.**
  - `HandlerBindingSharingFuzzTest` builds 3,000 random programs shaped as multi-validators. They have:
    - two to four handlers and a failing last branch;
    - methods that are lambdas or partial builtin applications;
    - methods that differ in some handlers or read a handler's own binder;
    - calls between methods;
    - in some programs, part of one handler's chain under a lambda that is not a `let`'s, or behind a `force (delay ...)`.

    On each it checks four things:
    - the shared methods are exactly those the generator knows to qualify;
    - with every method binding inlined, the programs are equal as FLAT;
    - the script never grows;
    - every input gives the same result and traces, the same budget when a handler takes it, and exactly the shared
      bindings' steps more when none does.
  - `HandlerBindingSharingTest` covers the shapes the generator does not:
    - a value that traces;
    - a fixpoint over D2's `#z`, and the same shape over a binder that is not Z;
    - a binding inside another method's delayed value;
    - a binding under a lambda passed as an argument;
    - an open term;
    - a chain of 4,000 methods per handler, decided in under a second.
  - Each guard was disabled in turn, and a test fails each time: totality, same function, scope, the lambda and deferred
    step checks, one binding per spine, and the open term.
  - `SharedBindingsTest` compiles a multi-validator with a `@Param`, a static, failing, tracing and constant
    zero-argument helpers, mutual recursion, and two-handler and three-handler variants. It checks:
    - identical outcomes, traces, results and failure text with the rule on and off, on every context, at both levels,
      with and without source maps and with D2 on and off;
    - the inlined-program oracle;
    - the same budget on every run that reaches a handler;
    - in the two-handler variant, exactly 15 steps more on the contexts with no handler: 3 for `underLimit`, a lambda,
      and 12 for `odd`, a fixpoint.
  - The review's scenarios are pinned with identical budgets on every run: the helpers that become builtins, the
    `JulcArray.of` helper and six `nullList` checks under D2. So is a run that fails at its boundary check, at exactly
    3 steps more.
  - A chain of 300 methods every handler reaches is shared whole.
  - `CounterBenchmarkTest` asserts identical budgets on all twelve transactions.
- **Semantics.** `CounterBenchmarkTest`'s twelve transactions agree across every rule combination and with Aiken.
- **Goldens.** Every golden that moved is at `PV11_SAFE` or `PV11_COSTED`. With both rules forced off, every frozen
  answer equals the previous table.
- **Other rules' cost guarantees still hold.** The tests of other rules assert their own budget and size relations
  against the program compiled with both ADR-076 rules off. The rules-on program is checked by
  `Adr076Sharing.assertSharesOnlyClosedValues` and the I2 bound.
- **Suites.** The full build and the external `julc-examples` are recorded in the PR.

## Open questions

- **One Z combinator.** It is written in `UplcGenerator`, `ClosedValueSharing`, the decompiler and a test helper. One
  constant in julc-core would keep them from drifting.

- **Strict-boundary checkers.** `PlainCounter` carries three, of 178, 72 and 150 B. Sharing their common machinery is
  the next measured candidate.
- **The stdlib's near-identical loops.** `ValuesLib.findTokenAmount` and `_assetOf` are 151 and 156 B of almost the
  same loop.
- **#151 idea 3.** Walk the field list of a record once for every field read.

## Revision history

- **r1** (2026-11-15): the design as implemented in PR #336. It was reviewed in nine Codex rounds, which moved D1 from
  the PIR assembly to a UPLC pass after D2 (Alternatives rejected), and by independent Claude reviewers.
- **r2** (2026-11-16; responds to reviewer1's review of `2fabf0b2`): F1 [P3]. The risk summary now states D1's exact I3
  budget and its failure-path surcharge, not the earlier design's "or lowers it". `PV11_SAFE` is named as the default
  level and `PV11_COSTED` as opt-in. The long-chain test's Javadoc no longer describes the trial lowering.
