# ADR-050: Preserve loop accumulator updates across enclosing if statements

**Status:** Implemented with shared join lambdas; human review pending
**Issue:** [#161](https://github.com/bloxbean/julc/issues/161)

## Context and current behavior

Outside the specialized loop-body generators, `generateIfStmt` uses a discarded
`Let("_if", branch, rest)` unless a branch contains an early return or yield.
A loop rebinds its accumulators inside the branch, so `rest` reads their old
values. This occurs in methods and in switch-expression arms alike.
ADR-048 explicitly leaves this issue separate from its mutation restrictions.

## Goals, non-goals and invariants

- Preserve loop accumulator values on every falling-through branch.
- Evaluate the condition once, run only the selected branch, and run following
  statements once, after that branch. Return/yield bypass their continuation.
- Preserve existing loop accumulator representation, iteration and break semantics.
- Resolve following statements in their enclosing lexical scope, before branch
  locals are introduced. Branch-local declarations must not escape.
- Preserve ADR-048 restrictions, native accumulator restrictions and typed boundaries.
- No new PIR node, general mutable locals, liveness analysis or optimizer rule.

## Decision

Reuse `generateIfStmt`'s existing continuation-based early-exit lowering when
either branch contains a statement-level for-each or while loop. Search nested
blocks and if statements, but not expressions: a switch or lambda owns its own
loops and value boundary. Generate the continuation once in the enclosing scope
and bind it once as a typed lambda before the conditional. Its parameters are
the deterministic, source-order union of enclosing accumulators assigned by the
branches' loops. Lexical analysis excludes branch/loop-local declarations,
iteration and pattern bindings, and loops inside switch/lambda expressions.
Each falling-through branch calls this join with its updated accumulator values;
the untaken/unchanged path passes the incoming values. Existing loop lowering
places this call under its final bindings, including after a break. With no
escaping accumulators, a unit argument delays the continuation until fall-through.
Native values are passed directly; no Data tuple or encoding is added.

Generating the continuation in its source scope is necessary but insufficient:
The join closes over free references before branch locals exist. PIR still uses
names for the join's arguments, however: a branch-local declaration may shadow
a class field whose updated value the other branch needs to pass to the join.
Reuse `LoopBodyGenerator`'s existing deterministic block-local renaming before
lowering continuation-bearing branches, and its name-reference traversal for
instanceof pattern bindings. Rename source references, not method names and not
the join body; preserve parent links on temporary AST clones. This also protects
the existing inline return/yield continuation path. No duplicate renaming implementation.
Source-facing diagnostics use the existing source-name helper, never fresh PIR names.

Branches without early exits or statement-level loops retain the existing
sequencing shape. Branches with loops use the continuation path even when their
accumulators are branch-local: avoiding that requires extra escape analysis and
does not improve correctness.

## Alternatives

- Reject these updates: permitted by the issue, but unnecessary because the
  existing continuation lowering can preserve supported source behavior.
- Pack branch outputs into Data: duplicates accumulator logic, introduces
  encoding and native-value restrictions, and needs branch join analysis.
- Use continuations for every if: simpler globally, but changes unrelated scripts.
- Inline the continuation in both branches: rejected after review measured
  exponential growth for sequential guarded loops (PV11_SAFE, 1–6 loops:
  107, 273, 607, 1275, 2611, 5283 FLAT bytes). Shared join lambdas keep each
  subsequent guarded-loop tail serialized once. The existing optimizer does not
  inline a lambda used more than once; size tests cover every optimization level.

## Affected stages and modules

`julc-compiler` Java-to-PIR statement lowering, semantic evaluation tests, and
documentation. Existing PIR-to-UPLC delayed conditionals and loop desugaring
are reused without changes. No public API or ledger encoding changes.

## Compatibility and risks

Recompiled loop-containing branches (and previously capturing early-exit branches)
may change script bytes, hashes, size and
execution cost at every optimization level, including NONE and BASELINE. Already
deployed scripts are unchanged. Corrected source must be recompiled and its hash
and budget reassessed. Each join adds a function binding and a call on the selected
fall-through path. Sequential guarded-loop source grows linearly rather than
duplicating its entire tail in each branch. This is a size bound for that shape,
not a general optimizer size or execution-cost guarantee. Earlier return/yield-only
conditionals retain their existing strategy.

## Implementation milestone and verification

1. Reproduce method-level and switch-arm stale results before implementation.
2. Extend the existing continuation eligibility check; retain loop/body guards.
3. Evaluate taken/untaken, empty/nonempty, nested/else, both loop kinds,
   single/multiple accumulators and break cases at all levels on Java, Truffle
   and Scalus (Scalus through its compatibility API).
4. Check explicit branch exits, failure/trace order, lexical scopes, native values,
   determinism and unsupported constructs; run compiler and repository builds.
5. Update user guidance and hash migration notes; review against these invariants.

## Open questions

General loop-body local mutation remains governed by ADR-048. No broader source
control-flow redesign or liveness optimization is included.

The follow-up to PR #164 resolves ADR-048's validation overlap: its outer-loop
guard visits switch selectors but leaves arms to the dedicated ownership guard
and each nested loop's own validation. Arm-local accumulators updated by guarded
loops now work inside an outer loop too. At `53034e4c` this shape was conservatively
rejected, not miscompiled. No join lowering changes are needed. Enclosing-variable
updates, case-pattern reassignments and conditional updates to actual loop-body
locals remain rejected. ADR-048 records the refined traversal boundary.

## Historical analysis

This is a missed case, not a regression introduced by the recent conditional fixes.
The parent of `682acff6` already uses `Let("_if", ifExpr, rest)` for this source
shape. `682acff6` (#79 / PR #80) introduced continuations only for branches with
method returns. `c9ecc341` (#137 / PR #138) extended that predicate to owned yields.
Neither includes a branch containing only a loop. The discarded-result binding
itself traces back to the initial repository history (`4d0e66fa`). ADR-048 / PR #157
explicitly records #161 as separate; its validation did not introduce the lowering.
The current-head regression test returned 0 instead of 6 before this implementation.
This conclusion combines source-history inspection with current-head reproduction;
it does not claim every historical release was executed.

Review of the initial continuation reuse found an additional scope regression:
`if (c) { BigInteger helper = ZERO; for (...) { helper = ...; } }
return helper(ONE);` tried to apply an integer as a function. The shared local
renaming above is required by the lexical-scope invariant and tested for both
ordinary locals and instanceof pattern bindings.

## Validation evidence for the initial inline implementation

- The original method reproducer failed before the fix; method and switch-arm
  versions now evaluate to 6 for `[1, 2, 3]`.
- `IfLoopContinuationTest` performs 5,162 VM evaluations: 4,608 across the loop/
  branch matrix, 480 composed/scoping/native cases, 72 trace/failure checks and
  two direct reproductions. All four optimization levels and Java/Truffle/Scalus
  are covered (Scalus uses its language-only compatibility route). The matrix
  also recompiles each source to verify deterministic FLAT bytes. Eight rejection
  checks preserve invalid-scope and unsupported-assignment diagnostics.
- Focused regression tests include #79 (`EarlyReturnLoweringTest`), #137
  (`ConditionalYieldLoweringTest`), #155/#162 (`LoopConditionalLocalTest`), and
  the earlier bare-block fixes (`LoopBlockAssignmentTest`); all passed.
- The complete compiler suite passed: 1,644 tests, no failures/errors/skips.
  The complete cross-backend suite passed: 69 tests, no failures/errors/skips.
- `./gradlew build -PskipSigning=true` passed (218 actionable tasks), including
  affected downstream modules and in-repository examples.
- `npm run build` in `docs` passed (33 pages); `git diff --check` passed.
- Sibling validator examples were inspected for documentation/API updates;
  no API migration is needed. Existing examples are covered by the repository
  build. External DevKit/on-chain and native-image tests are not claimed.

## Review revision: sharing, diagnostics and ownership

The initial strategy's exponential size is not retained. The size regression
evaluates 1–12 sequential guarded loops at all four optimization levels and
bounds the incremental FLAT size. Measurements from that fixture (bytes):

| Profile | 1 loop | 2 loops | 6 loops | 12 loops |
|---|---:|---:|---:|---:|
| NONE | 2406 | 2512 | 2934 | 3567 |
| BASELINE | 131 | 237 | 659 | 1292 |
| PV11_SAFE | 114 | 203 | 557 | 1089 |
| PV11_COSTED | 114 | 203 | 557 | 1089 |

NONE retains unused fixture/library definitions, so its fixed overhead is larger.
These numbers describe this fixture, not a universal script-size or budget limit.

The revised semantic suite performs 5,402 VM evaluations, including no-accumulator
trace order, a side-effect loop followed by an accumulator loop, and a join carrying
native G1 plus Integer results from separate loops without Data packing.
Structural tests pin the exact parameter union, lexical ownership, and unit thunk.
Uninitialized-local and native-boundary diagnostics are checked at every level;
they display `t`, never `t'1`. Existing bare-block diagnostics share the same helper.

Renaming is retained deliberately: with an enclosing `@Param total`, a then-branch
local `total` and an else-branch loop updating the field, the common join has a
`total` parameter. Merely closing the join outside the if does not protect the
then-branch call's argument from that local. The field-shadow regression evaluates
to the untouched field value on the then path and updated field value on the else
path, on Java, Truffle and Scalus. Reusing the existing renamer also protects the
unchanged inline early-exit path; no second renaming implementation is introduced.

The reviewer confirmed all 58 external validators byte-identical between the base
compiler and the shared-join revision at `53034e4c`, with only aggregate-blueprint
compiler version metadata differing. This comparison used an isolated copy of the
examples built sequentially with each compiler; earlier in-place comparisons were
discarded because concurrent Gradle builds contaminated their outputs. This is
reviewer-supplied compile-only evidence for the shared-join revision, not a fresh
DevKit run. Their source scan found no affected guarded-loop shape in the external
validators or the seven Blaster fixtures. The earlier external test run's DevKit
setup HTTP 500 and successful rerun remain evidence for that earlier run only.

ADR-050 avoids the ADR-055 allocation in the open playground debugger PR #154.

Fresh shared-join validation: `./gradlew build -PskipSigning=true` passed (218
actionable tasks), with 1,649 compiler tests and 69 cross-backend tests, zero
failures/errors/skips in those suites. Stdlib, testkit, annotation processor,
in-repository examples and other build checks passed. `npm run build` built all
33 documentation pages; `git diff --check` passed. External DevKit and native-image
checks were not run for this revision.

## Switch-arm validation follow-up to PR #164

The new outer-loop/switch-arm reproducer fails on the parent with the loop-local
diagnostic before the selector-only validation change. Added coverage performs
936 VM evaluations across all four optimization levels and Java/Truffle/Scalus:
both loop forms, nested switches, taken/untaken guards, both variants, empty and
mixed input, inner/outer breaks, trace order and deterministic compilation.
Negative cases preserve selector restrictions, enclosing-variable ownership and
checks on actual inner-loop locals, including with source maps enabled.

`./gradlew build -PskipSigning=true` passed (218 actionable tasks): 1,651 compiler
tests, 70 cross-backend tests, 411 stdlib tests, 193 testkit tests and 81 in-repository
example tests, with no failures/errors/skips in those suites. Documentation built
all 33 pages and diff checks passed. No fresh external corpus, DevKit or native-image
validation is claimed. The production diff changes validation only, not lowering.
