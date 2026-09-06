# Issue #130 / ADR-039 evidence

## Scope and stack

Base: PR #129 branch `feat/125-switch-pair-case` at `397abf51`.
Implementation branch: `fix/130-pair-case-decompiler`.
The new PR targets that branch, not main. Compiler, core, VM and ledger sources
are unchanged relative to the parent; there is no new script-byte/hash migration.

## Recognition and preservation

The new strict entry point recognizes either a directly saturated UnConstrData
native pair Case with exactly one branch and two leading lambdas, or the legacy
pair/tag/fields Let chain. Legacy FstPair must reference index 1 and SndPair
index 2 with exactly two forces each. It never trusts debug names.

Dispatch recognition accepts EqualsInteger with the tag at index 2 and a literal
BigInteger in either argument position. Native Case Bool order is false/true;
the legacy chooser must have one force, three applications, delayed branches
and the final force. Tag constants are never narrowed to int. Repeated tags
retain order. The entire residual term remains a fallback, including errors,
traces and further computation. A conditional with no proven tag comparison
is not promoted. Singleton/unconditional decomposition has zero tag branches,
not a fictitious tag-zero case.

HIR DataMatch explicitly binds native pair, tag and raw fields. Its scrutinee
is outside their scope. Fields remain decoded only in their selected branch;
unused field decoding is not removed. Native matches receive a fresh pair name
for readable projection rendering; legacy matches retain their visible pair
binding. The names-only preprocessing pass follows original de Bruijn indices
and assigns unique binder names, preserving indices and serialized FLAT bytes.
This avoids capture and name loss after FLAT decoding. Other programs keep the
previous naming path.

The legacy public `DataMatchRecognizer.match` API is retained and documented as
a heuristic; the production lifter now uses `matchConstructorDispatch` before
generic SOP recovery. Existing HIR Switch still represents generic constructor
cases. The new sealed HIR variant deliberately requires external exhaustive
visitors to add a DataMatch case.

## Tests and review

`ConstructorDispatchTest` checks native and legacy shapes, wrong producers,
branch arity/count, projection indices/force counts, malformed chooser forces,
wrong tag bindings, reversed/huge tags, residual identity, duplicate-tag order,
singletons, nested matches, match-in-scrutinee, field/tag/pair capture and
closures. Invalid and malformed selected fields fail with the same trace order;
an unselected partial decoder stays unobserved.

The semantic oracle reconstructs supported structured and typed/named HIR into
PIR/UPLC, then compares original/reconstructed result, failure text and traces
on Java at explicit PV11 and Scalus's language-only API. It runs both before and
after FLAT round-trip; unsupported HIR fails the test instead of approximating
it. This is bounded differential evidence, not an interpreter/certification for
all existing decompiler heuristics. Scalus is not a ledger-certification claim.

Fresh source fixtures cover ordinary Pay/Cancel dispatch, nested capture and a
fieldless singleton at NONE, BASELINE and PV11_SAFE. Tests require actual
DataMatch recovery, exercise malformed and unknown-tag inputs, and check
rendering retains decomposition and fallback without invented Case0 records.
Names-only rewriting must reproduce identical FLAT bytes.

The analyzer walker visits match scrutinee, branch bodies and fallback. A
regression distinguishes a non-empty tag-dispatch guard from unconditional
singleton decomposition in the existing recursion heuristic. Neither is a
termination proof. Type/naming passes restore match scope across branches and
fallback.

Self-review also replaced the general renderer's arithmetic equality path with
explicit BigInteger value equality for recovered tag dispatch. Output is
readable reconstruction, not promised recompilable Java or original schema
recovery; existing generic decompiler limitations remain.

## Validation commands

```sh
./gradlew :julc-decompiler:test :julc-analysis:test \
  :julc-analyzer-cli:test :julc-cli:test -PskipSigning=true
./gradlew build -PskipSigning=true --rerun-tasks
npm run build --prefix docs
```

Validation completed:

- Fresh full build: all 218 tasks executed, **10,832 cases: 10,301 passed,
  531 existing/profile skips, zero failures/errors**. Counts include only tasks
  executed by that build, excluding stale optional E2E reports.
- Decompiler: **105 passed**; analysis: **82 passed, 2 existing skips**;
  analyzer CLI: **96 passed, 4 existing skips**; compiler CLI: **442 passed**.
- Compiler: 1,483 regular tests and 21 pair tests passed, including parent PR
  historical-byte fixtures. Java and Truffle each passed all **999 PV11
  conformance vectors**, without PV11 skips or failures.
- Documentation: 32 pages built successfully.
- Final diff review: production changes are limited to julc-decompiler and
  its julc-analysis HIR consumers. No compiler/core/VM/ledger implementation
  changes relative to PR #129; no parent files or commits were rewritten.

No devnet mutation or Maven-local publication is needed for this decompiler-only
change. Independent review of this new PR remains pending; Claude's approval of
PR #129 does not cover it.
