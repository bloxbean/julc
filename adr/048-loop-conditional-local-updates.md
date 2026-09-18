# ADR-048: Fail closed on conditional updates to loop-body locals

**Status:** Implemented and locally validated; independent agent review applied; maintainer review pending
**Issue:** [#155](https://github.com/bloxbean/julc/issues/155)

## Context and current behavior

Loop body generators return only accumulator values from an `if`. An update
to a local declared inside the loop, outside the branch, does not survive the
branch: `step = ZERO; if (positive) { step = ONE; } acc = acc.add(step);`
silently sums zero. ADR-047 records this separately from its bare-block fix.
It affects for-each/while loops and every optimization level.

## Goals, non-goals and invariants

- Never silently accept this lost-update pattern.
- Preserve PIR, UPLC bytes, evaluation order, failure behavior and data
  representations of programs that remain accepted.
- Cover all accumulator-count and break-aware paths before PIR construction.
- No new join-point lowering, liveness analysis, optimization or Data packing.
- No broader claim that every existing loop/control-flow pattern is correct.

## Decision

Before lowering a loop, perform lexical statement traversal. Track locals of the
current loop and names whose bindings would cross an enclosing conditional join.
Entering an `if` adds the current loop's locals to the latter set. Assigning to
one of those names is rejected at its source position, with conditional-initializer
and pre-loop-accumulator workarounds. This is conservative even for constant
conditions, unused updates, or an update consumed entirely inside the branch.

Bare blocks copy lexical scope without adding a join. Branch-local declarations
remain local to their branch. Nested loops start a fresh set of loop locals:
enclosing locals are accumulators of the inner loop, not its body locals. However,
an enclosing conditional's restriction remains active across the nested loop.
Each loop's iteration variable is included as a body-local binding. Expression
children are traversed too: a switch-expression arm may contain a nested loop
which binds its own assignments. Conditions, initializers and iterable expressions
must not hide an enclosing conditional restriction. Lambda bodies have their own
scope; their loops are checked when lowered.

## Alternatives

- **Join points passing updated locals:** the eventual general solution, but it
  requires a separate lowering design, native-value handling, break/continuation
  reasoning and script-hash impact assessment. Not needed to close this hole.
- **Add all body locals to accumulators:** invalid lifetime/initialization semantics;
  multi-accumulator Data packing also cannot carry native BLS values.
- **Reject every assignment under an if:** rejects supported accumulator
  updates and branch-local straight-line assignments unnecessarily.
- **Accept apparently dead updates:** requires liveness/control-flow analysis;
  a conservative explicit subset is easier to audit for this fix.

## Affected stages and modules

Only `julc-compiler` source-to-PIR validation (`PirGenerator` loop entry points and
`LoopBodyGenerator` lexical check), compiler tests, and documentation. No optimizer,
VM, ledger encoding, cost model or public Java API changes.

## Compatibility and risks

Previously accepted source containing this pattern now fails compilation. Existing
deployed scripts are not changed; affected source must be rewritten and recompiled.
Use `BigInteger step = condition ? value : otherValue;`, or declare `step` before
the loop and reset it each iteration when the original local lifetime requires it.
The latter remains subject to existing accumulator type restrictions (native
values cannot be Data-packed with other accumulators).

The main risks are over-rejection across scopes and missing a conditional enclosing
a nested loop. Tests pin both. Validation does not mutate AST or compiler state,
so an accepted program takes exactly its previous lowering path.

## Milestone and verification strategy

One milestone: reproduce the wrong result, add the guard and actionable diagnostic,
test rejected shapes at all levels, test accepted workarounds/scopes across Java,
Truffle and Scalus, run full compiler/cross-backend and repository builds,
then independent review before opening the stacked PR.

`LoopConditionalLocalTest` covers both loops, one/several accumulators, break/no-break,
then/else/nested branches, bare blocks, nested loops, native locals, iteration
variables and no-accumulator loops. Positive cases check empty, positive and mixed
inputs at every level and compilation determinism. `LoopBlockAssignmentTest`
continues to pin bare-block/braceless byte equality. Scalus uses its existing
non-protocol-aware evaluation route, not a claim of PV11 certification.

Final validation: `./gradlew build -PskipSigning=true` passed (218 actionable
tasks), including 1,639 compiler tests and 68 cross-backend tests, with no failures.
The new suite performs 416 rejection checks (52 shapes, four levels, source maps
off/on) and 360 supported-case evaluations (10 fixtures, four levels, three inputs,
three backends). `npm run build` built all 33 docs pages; the new release-note link
and target anchor were verified. Native CLI and external DevKit tests were not run.

Independent review found a switch-expression initializer containing a nested loop
could bypass the initial statement-only guard (0 instead of 18 on the review probe).
Expression traversal and regressions for initializers, conditions and iterables
close that route; the second review found no remaining blockers. This evidence
does not establish general mutable-capture or unrelated loop-lowering correctness.

## Open questions

A future ADR may replace rejection with explicit join points. It must account for
all live bindings, native values, nested loops and break continuations, and provide
semantic and byte/hash evidence rather than merely removing this guard.
