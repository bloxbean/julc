# ADR-048: Fail closed on updates lost across conditional and switch boundaries

**Status:** Implemented; PR #157 targets `main` after #156 merged; reviewer-requested revisions applied, final maintainer approval pending
**Issues:** [#155](https://github.com/bloxbean/julc/issues/155), [#162](https://github.com/bloxbean/julc/issues/162)

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

Review clarification: that traversal only preserves an **enclosing if's**
restriction. A switch arm without an if can independently lose updates to a
loop-body local **or a pre-loop accumulator**. Therefore every `generateSwitchExpr`
entry also validates each arm before generating PIR: assignment targets must be
declared inside that arm. Bare blocks and branches preserve lexical scope,
for-each and pattern bindings belong to their proper scopes, nested loops may
update arm-local accumulators, and a nested switch starts another value boundary.
This deliberately rejects even an enclosing-variable update consumed only within
the arm. Use an arm-local accumulator and explicitly yield the result instead.

For example, inside an outer loop over `[1, 2, 3]`:

```java
BigInteger step = BigInteger.ZERO;
BigInteger ignored = switch (action) {
    case Only o -> {
        for (var y : xs) { step = step.add(y); }
        yield BigInteger.ZERO;
    }
};
acc = acc.add(step); // previously produced total 0 rather than 18
```

The nested loop assignment is now rejected even without an enclosing `if`.
Replacing `step` with outer accumulator `acc` in the arm is rejected too.
The supported rewrite is `BigInteger step = switch (...) { ... }`, declaring a
fresh accumulator inside the arm and yielding it after the loop.

An `if` outside a loop body (at method level or inside a switch arm) around a loop remains separate: [#161](https://github.com/bloxbean/julc/issues/161)
tracks an enclosing accumulator update lost when read after the branch. This ADR
does not fix it; ADR-050 now addresses that separate lowering path.

Review regression testing also found [#162](https://github.com/bloxbean/julc/issues/162):
reassigning a switch case-pattern variable can leave its field projections pointing
at the original record. It reproduces on the previous jar and is not a boundary
escape. Round two resolves it by rejecting reassignment of switch case-pattern
bindings, rather than rewriting cached projections. The pattern names are tracked
separately from ordinary arm-owned locals. Rebinding is rejected even if only the
record itself is yielded (a previously correct shape). Copy the case binding to a
fresh arm-local accumulator instead; the positive suite evaluates that workaround.
An `instanceof` binding introduced inside the arm is recognized in its then-branch
only; a positive regression prevents incorrectly rejecting that supported pattern,
and negative tests prevent the binding leaking into its else or following statements.
This is deliberately **not** a general immutable-pattern-binding policy: working
`instanceof` mutation is preserved. Rejecting all pattern bindings would expand the
source-subset contract beyond the demonstrated case-projection correctness defect.

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

Only `julc-compiler` source-to-PIR validation (`PirGenerator` loop and switch entry points and
`LoopBodyGenerator` lexical checks), compiler tests, and documentation. No optimizer,
VM, ledger encoding, cost model or public Java API changes.

## Compatibility and risks

Previously accepted source containing this pattern now fails compilation. Existing
deployed scripts are not changed; affected source must be rewritten and recompiled.
Use `BigInteger step = condition ? value : otherValue;`, or declare `step` before
the loop and reset it each iteration when the original local lifetime requires it.
The latter remains subject to existing accumulator type restrictions (native
values cannot be Data-packed with other accumulators).
When a value is needed only in an if branch, declaring it in that branch is a
third remedy. General body-local reassignment in the single-accumulator break-aware
path remains limited. An if whose branches contain no break uses normal lowering,
so declaration and reassignment within that branch can work even when another
statement makes the enclosing loop break-aware. A positive regression pins this
exception; the docs no longer claim moving a declaration into a branch never helps.

The main risks are over-rejection across scopes and missing a conditional enclosing
a nested loop. Tests pin both. Validation does not mutate AST or compiler state,
so an accepted program takes exactly its previous lowering path.

## Milestone and verification strategy

One milestone: reproduce the wrong result, add the guard and actionable diagnostic,
test rejected shapes at all levels, test accepted workarounds/scopes across Java,
Truffle and Scalus, run full compiler/cross-backend and repository builds,
then independent review. PR #157 originally stacked on #156 and now targets main.

`LoopConditionalLocalTest` covers both loops, one/several accumulators, break/no-break,
then/else/nested branches, bare blocks, nested loops, native locals, iteration
variables and no-accumulator loops. Positive cases check empty, positive and mixed
inputs at every level and compilation determinism. `LoopBlockAssignmentTest`
continues to pin bare-block/braceless byte equality. Scalus uses its existing
non-protocol-aware evaluation route, not a claim of PV11 certification.

Initial PR validation (before the switch-boundary review revision): `./gradlew build -PskipSigning=true` passed (218 actionable
tasks), including 1,639 compiler tests and 68 cross-backend tests, with no failures.
The new suite performs 416 rejection checks (52 shapes, four levels, source maps
off/on) and 360 supported-case evaluations (10 fixtures, four levels, three inputs,
three backends). `npm run build` built all 33 docs pages; the new release-note link
and target anchor were verified. Native CLI and external DevKit tests were not run.

Independent review found a switch-expression initializer containing a nested loop
could bypass the initial statement-only guard (0 instead of 18 on the review probe).
Expression traversal and regressions for initializers, conditions and iterables
closed that route **under an enclosing if only**; the second review found no remaining blockers in that scope.
Subsequent review reproduced the no-if switch boundary escape, prompting the
dedicated arm-ownership check above. This evidence
does not establish general mutable-capture or unrelated loop-lowering correctness.

Review-revision validation: `./gradlew build -PskipSigning=true` passed again
(218 actionable tasks), including 1,640 compiler tests and 68 cross-backend tests
with zero failures/errors/skips. The expanded regression suite performs 536
rejection checks (67 shapes, four levels, source maps off/on) and 468 supported
evaluations (13 fixtures, four levels, three inputs, three backends). Docs built
all 33 pages and the new rules anchor/troubleshooting link resolved. Independent
review's then-branch pattern-scope finding was fixed; no remaining blockers were
reported for this scoped change. Native-image and external DevKit tests were not run.

Round-two validation: the full build passed again (218 actionable tasks), with
1,641 compiler tests and 68 cross-backend tests, zero failures/errors/skips.
The focused suite now contains 632 rejection checks (79 shapes, four levels,
source maps off/on) and 504 supported evaluations (14 fixtures, four levels,
three inputs and three backends). This includes case-pattern field-read and
record-yield rejection, the copy-to-local workaround, unchanged instanceof
reassignment, and a non-breaking branch-local update in a break-aware loop.
The docs build passed for all 33 pages. Independent review found no blocker in
the narrow case-binding guard. The #161 warning remains explicit; no native-image
or external DevKit validation was performed.

## Open questions

A future ADR may replace rejection with explicit join points. It must account for
all live bindings, native values, nested loops and break continuations, and provide
semantic and byte/hash evidence rather than merely removing this guard.
