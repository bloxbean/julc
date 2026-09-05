# ADR-036: Typed local Pair Case lowering

Status: Implemented on `feat/111-typed-pair-case`; validation in progress; independent review pending.
Parent: ADR-032 O4, issue #111. Base: `991cf66b`.
Target: `plutus-v3-pv11-uplc-1.1.0`.

## Context and current behavior

Compiler-generated strict boundary checks bind `UnConstrData(data)` once and
project its tag and field list separately. These are native UPLC pairs, unlike
Data-encoded tuples/records. Some existing internal pair Vars are annotated Data;
correct their internal types without changing NONE/BASELINE generated code.
Source types alone do not prove runtime representation: unchecked casts already
necessitated a runtime guard for ADR-034. O4 must use a stronger producer proof.

## Goals and non-goals

Implement the bounded constructor-decomposition family: a strict Let of a direct
UnConstrData application whose correctly typed local pair is used only by direct
FstPair/SndPair projections, with at least one of each. This covers compiler-owned
record, sum, Optional and Boolean boundary checks. Establish typed local analysis,
explicit destructuring, deterministic scope, and measurable complete-program gains.
Do not optimize arbitrary pairs, aliases/escaping pair values, map traversal,
Data-encoded JulcPair/Tuple2/records, or unrelated projections. No generic CSE,
dominance framework, decoder motion, new Java construct or new ledger encoding.

## Decision and invariants

Add typed PIR `PairMatch(scrutinee, pairType, firstName, secondName, body)`.
Its scrutinee must be a proven native pair; raw fields bind first then second.
The result type is the body's type. It emits one branch:

```
Case scrutinee [(lam first (lam second body))]
```

A narrowly scoped PIR pass immediately before UPLC generation recognizes:

```
let p = UnConstrData(d) in body[FstPair(p), SndPair(p)]
```

and replaces only direct projections of that lexical binding with raw field
variables in PairMatch. The runtime-producing operation remains exactly once at
the same strict evaluation position. UnConstrData failure behavior is unchanged.
After it succeeds, projecting either raw component is total and effect-free.
Branch selection, field decoding, Trace/Error, subsequent list operations and
recursive work remain in their original body positions. Pair creation itself
is not moved or repeated. Branch-local projections may be removed because their
raw extraction is total, not because their later consumers are total.

Require native `PairType(IntegerType, ListType(DataType))` annotations on all
matched references. Bare uses, aliases, differently typed references, and any
unproven producer reject the candidate. Respect Lam/Let/LetRec, ListMatch,
PairMatch and DataMatch binding scopes. Fresh binders must avoid all names in the
term, including shadowed ones; no capture through nested scopes is permitted.
No one-sided projection rule is enabled. NONE/BASELINE return the original PIR.

Maintain source positions through identity-preserving rewrites, mapping changed
nodes to their original locations. Update every PIR consumer. Gate pass and final
PairMatch lowering with exact PV11 target, safe optimization level and
CASE_ON_BUILTIN_CONSTANTS capability. Record `pv11.o4.case-pair`. Direct PairMatch
lowering outside that profile fails closed.

## Affected stages/modules and compatibility

julc-compiler: boundary producer typing, typed PIR pass, PIR consumers, final UPLC
lowering and pipeline integration. julc-stdlib: exhaustive PIR consumers if any.
julc-compiler tests: pinned cost/size/hash and cross-backend evidence, reusing
the historical complete-source fixtures without a duplicate representation.
The dedicated `:julc-compiler:pairCaseTest` task is part of check/build and adds
Truffle only to its own classpath, preserving the existing suite’s VM selection.
julc-benchmark: existing aggregate regression suite. julc-decompiler:
review new generated shape and record classification limits. VM implementations
are unchanged; test explicit Pair Case semantics across Java/Truffle/Scalus.

NONE/BASELINE must preserve historical bytes. Safe-profile recompilation may
change script hashes; deployed scripts and ledger Data representation do not
change. The sealed PIR variant requires exhaustive downstream consumers to adapt.
Independent correctness review remains required before merge/enablement release;
local self-review is not that independent approval.

## Alternatives rejected

A raw UPLC Fst/Snd peephole loses typed ownership and producer proof. Trusting
PairType alone accepts unsafe casts. Optimizing every map/native pair family
would assume unrelated list-element representation invariants. A generic sharing
framework is unnecessary for this local binding pattern. Keeping FstPair as a
runtime guard adds redundant work when UnConstrData already proves native pair
representation. Single-projection shapes are excluded pending their own evidence.

## Risks and verification strategy

Test both projections in either order, repeated and branch-local uses, one-sided
uses, aliases/escapes, nested same-name bindings, recursive scopes, unused/malformed
fields, invalid constructor tags/arity, wrong outer Data, traces and failure order.
Pin raw Pair Case first/second binding and strict scrutinee behavior on VMs.
Compare expected outcomes independently of differential tests. Test source maps,
free variables, substitution, output determinism and historical bytes.

Measure representative complete source fixtures with pinned
`cardano-node-11.0.1-plutus-v3-pv11` costs, including failure/early-exit paths and
aggregate O1/O2/O3/O4/O13 where applicable. Do not substitute the old raw-pair
microbenchmark for whole-program evidence. If size/cost regressions occur, narrow
eligibility or revise this ADR before proceeding.

## Milestones and completion criteria

1. Inspect producer/runtime representations and review this design against code.
2. Capture historical bytes; create positive and negative semantic scenarios.
3. Implement the typed node, local pass, visitors and exact profile gates.
4. Run focused, affected-module, cross-backend/conformance and aggregate tests;
   self-review, fix failures and record any design refinements.
5. Run full repository and documentation builds. Commit implementation, publish
   its uniquely versioned artifacts to Maven local, and run the developer's
   julc-examples against those exact artifacts, including plugin and annotation
   processor. Preserve existing changes in that sibling repository.
6. Record results, measurements, compatibility, exclusions and review findings.
   Independent review is a pre-merge requirement, not claimed by self-review.

## Open questions

Broader pair/map families require separate producer/use proofs. No such extension
is assumed by this milestone. External DevKit execution needs an available
network; it is distinct from local Maven consumer regression tests.

## Implemented source pattern and self-review

For example, the full validator fixture `PairRecord` declares:

```java
record Redeemer(BigInteger amount, boolean approved, Optional<BigInteger> limit) {}
@Entrypoint static boolean validate(Redeemer r, ScriptContext ctx) {
    return r.approved();
}
```

Its strict entry boundary still validates `amount` and `limit`, including unused
malformed fields. The matched portion of its generated UPLC changes structurally
as follows (the unchanged check continuation is abbreviated as `checks`):

```
before: [(lam p [(lam fields checks[(force (force FstPair)) p, fields])
                  [(force (force SndPair)) p]]) [UnConstrData d]]
after:  (case [UnConstrData d] [(lam tag (lam fields checks[tag, fields]))])
```

The full pre-change scripts, not merely that abbreviated expression, are pinned
in `julc-compiler/src/test/resources/optimization/o4-pre-change-bytes.txt`. All 18
rows were generated at base `991cf66b` before implementation (three source
fixtures, three levels, source maps both off/on). Tests compare current
NONE/BASELINE bytes to these historical rows and compare current safe execution
to the historical safe artifact with the *same* source-map setting. Comparing a
mapped script with an unmapped baseline is invalid for cost attribution because
source-map mode already affects optimization.

Self-review covered every PIR consumer, all three final compiler generation
paths, binding scope and producer typing. No source decoder is moved by the pass.
The decompiler's existing generic Case recognizer preserves the single branch
and ordered fields; a regression test pins this. No new loop recognition is
required because this milestone changes boundary decomposition, not map loops.
The decompiler still displays generic Case structure, without promising recovery
of the original Java record declaration.

A raw negative test found that Scalus's language-only path accepts Data Case
shapes rejected by Java/Truffle's PV11 profile. Therefore it is used only as an
independent semantic cross-check of supported emitted forms, never as a legality
oracle. The production pass rejects Data pair-like producers and unproven native
pair Vars. Raw Pair Case order, strict scrutinee evaluation, recursive captured
fields and partial decoder/trace ordering agree on all three backends.

The first broad build found that adding Truffle to the ordinary compiler test
runtime changes default provider selection (priority 200 vs Java's 100), causing
two existing diagnostic tests to fail. The dedicated tagged `pairCaseTest` task
fixes this test-isolation issue without changing those tests or production code.

## Pinned isolated O4 measurements

Profile: `cardano-node-11.0.1-plutus-v3-pv11`. Source maps off. Before is the
historical **PV11_SAFE** artifact, so these gains isolate O4 from existing O2/O3.
Java and Truffle budgets agree exactly; all measured malformed/early-exit paths
also have non-increasing CPU and memory, with source maps on and off.

| Complete validator/input | FLAT bytes before → after | CPU before → after | Memory before → after |
| --- | --- | --- | --- |
| Record, amount=9 / true / None | 360 → 323 | 7,671,745 → 6,024,294 | 28,312 → 24,856 |
| Record, malformed unused amount | 360 → 323 | 3,200,150 → 2,756,263 | 12,351 → 11,287 |
| Nested, empty list | 270 → 247 | 3,162,512 → 2,718,625 | 13,019 → 11,955 |
| Nested, singleton Item(9) | 270 → 247 | 6,919,212 → 5,873,446 | 27,980 → 25,720 |
| Sum, invalid tag 99 | 144 → 130 | 2,012,870 → 1,617,080 | 7,927 → 7,163 |

New script hashes (source maps off, deterministic recompilation):

- Record: `3f073c9faf8ff14323aad26d7e4e049425924e32766b5cfeffb4e154`
- Sum: `91561ed23a1eae9f14372f0a544d066b0ed9b98e6515892ec130f9d0`
- Nested: `930c0300c9749b235790c97c43e33175b49f34c23c54e9e793cd58a5`

The aggregate validator combines O1/O2/O3/O4/O13, uses 50 seeded host-model
scenarios plus a malformed element that drop would skip, and checks both safe
levels against BASELINE on all three VMs. This is separate from the isolated
historical-safe O4 measurement above. The strict boundary must reject that
malformed element even when the validator body would not visit it.

## Repository validation

- `./gradlew build -PskipSigning=true`: passed.
- `./gradlew test --rerun :julc-compiler:pairCaseTest --rerun -PskipSigning=true`:
  fresh run passed, 10,173 passed / 531 existing skips / zero failures across
  10,704 reported cases (including nested julc-jrl-core).
- Compiler: 1,483 regular tests + 13 dedicated pair tests passed. Stdlib:
  402 passed / one existing skip. Testkit: 191 passed. Decompiler: 97 passed.
  Benchmark: 22 passed. Annotation processor: 20 passed. Gradle plugin: 30 passed.
- Java and Truffle each passed all 999 PV11 conformance cases. Their 262 skipped
  cases each are in the separate PV10 corpus, not the PV11 run. Scalus tests also
  passed; its target-aware conformance test checks fail-closed target support,
  not certification as a PV11 ledger backend.
- `cd docs && npm run build`: passed, 32 pages.
- Opt-in `julc-e2e-tests` and `julc-plugin-test` are disabled in the default build.
  The separately requested Maven-local consumer/Yaci validation is recorded below
  after publishing the implementation commit.
