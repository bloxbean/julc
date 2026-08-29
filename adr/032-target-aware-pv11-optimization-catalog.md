# ADR-032: Target-Aware PV11 Optimization Catalog and Delivery Policy

**Date:** 2026-08-29

**Status:** Accepted; implementation in progress on a stacked integration branch

**Related issue:** [#77 — PV11 compiler optimizations umbrella](https://github.com/bloxbean/julc/issues/77)

**Prerequisite:** [ADR-031 — PV11-first compiler target and future protocol evolution](031-pv11-first-compiler-target-and-future-protocol-evolution.md) / [#76](https://github.com/bloxbean/julc/issues/76)

**Related roadmap:** [ADR-029 — PV11 ledger readiness and optimization roadmap](029-pv11-ledger-readiness-and-optimization-roadmap.md)

**Normative semantic and cost baseline:** cardano-node 11.0.1 / Plutus 1.63.0.0 at
[`f92b7d7d82622a26caf456a6be33859f697e2cfc`](https://github.com/IntersectMBO/plutus/tree/f92b7d7d82622a26caf456a6be33859f697e2cfc)

---

## Context

PV11 makes fourteen Batch 6 builtins available to Plutus V3 and enables
`Case` over selected builtin constant types. JuLC already exposes and evaluates
the builtins, but most high-level Java operations still lower to pre-PV11
recursive list traversal, nested Data-map scans, chooser builtins, or separate
BLS scalar multiplication and addition calls.

Availability alone does not justify a rewrite. JuLC is strict call-by-value
UPLC, and an optimization can change:

- evaluation order;
- whether an argument is evaluated;
- failure timing or failure class;
- traces;
- representation and canonicalization;
- CPU and memory budgets;
- script size and script hash.

ADR-031 supplies the missing legality boundary: every pass receives a resolved
compiler target and final output is revalidated. This ADR catalogs all PV11
operations with plausible compiler or stdlib optimization value, classifies
their readiness, and defines the evidence required before any rewrite ships.

## Goals

1. Inventory every optimization opportunity enabled directly by PV11 Batch 6
   or PV11 case-on-builtin support.
2. Distinguish direct lowerings from cost-directed and analysis-heavy rewrites.
3. Define semantic, strictness, failure, size, and budget evidence for each
   operation.
4. Prevent cost-model-dependent decisions from being inferred from protocol
   version alone.
5. Introduce typed native representations where Data/native ambiguity would
   otherwise make an optimization unsafe.
6. Deliver changes as focused, independently reviewable child issues.
7. Define how optimizations remain safe when a later protocol or cost model is
   supported.

## Non-goals

- Making Phase 5 a prerequisite for Java/Truffle PV11 evaluator parity.
- Enabling `MultiIndexArray` (tag 101), which is not released in PV11.
- Assuming every PV11 builtin is cheaper for every input.
- Rewriting ordinary exponentiation when no modulus is explicit.
- Introducing PV10 compiler fallbacks.
- Compiling V1/V2 source programs.
- Changing native Value, Array, BLS, or case semantics from the pinned Plutus
  reference.
- Combining all optimizations in one implementation PR.

## Optimization invariants

Every implemented rule must preserve:

1. **Target legality.** Every introduced builtin, constant universe, and term
   form is authorized by the resolved compiler target.
2. **Result semantics.** Successful results are observably equivalent under
   the source-language contract.
3. **Failure semantics.** Invalid inputs fail in the same cases and at the same
   semantic point unless a separately accepted language-semantic ADR changes
   the contract.
4. **Evaluation order.** Strict argument evaluation, branch laziness, traces,
   and divergence behavior are preserved.
5. **Representation.** Data/native Value, list/array, pair/Data-record, and BLS
   representations are not mixed implicitly.
6. **Determinism.** Identical source, target, compiler options, and cost profile
   produce identical bytes.
7. **Evidence before enablement.** A rewrite is not enabled because it looks
   smaller or because its builtin exists.
8. **Post-pass validation.** Final UPLC is checked against ADR-031 after all
   rewrites.
9. **No hidden cost assumptions.** Input-size-dependent profitability uses an
   explicit cost profile, not `protocol >= 11`.
10. **Script-hash transparency.** Every shipped rewrite documents whether it
    changes generated script bytes and hashes.

## Two independent inputs: legality and profitability

### Implementation resolution: no new compiler target

ADR-032 does not add or rename a compiler target. The compiler target remains
`plutus-v3-pv11-uplc-1.1.0` and continues to answer only whether generated UPLC
is legal. Optimizer rollout is selected independently through
`OptimizationLevel`; cost-directed rules additionally require a named,
immutable `OptimizationCostProfile`.

This separation is permanent. A future protocol target receives explicit rule
support and a separately pinned cost profile rather than inheriting whatever
was profitable for PV11.

### Compiler target controls legality

The resolved `CompilerTarget` and `ProtocolFeatureProfile` answer:

- whether the builtin is released;
- whether the UPLC version is legal;
- whether case-on-builtin is enabled;
- which term and constant forms may be emitted.

### Optimization cost profile controls profitability

Cost parameters may change through ledger governance without a new protocol
major. A PV11 target therefore does not uniquely determine whether
`ListToArray` promotion or another trade-off is profitable.

Introduce an immutable optimization cost profile with the conceptual shape:

```java
public record OptimizationCostProfile(
        String profileId,
        CompilerTarget target,
        String source,
        String parameterHash,
        long[] costModelParameters) {}
```

The initial profile is pinned to the node 11.0.1 V3/PV11 cost model used by the
conformance suite. Its exact source and parameter hash are recorded in test
fixtures and build provenance.

Rules are classified as:

- **legality-only:** semantic replacement expected to dominate independently
  of runtime input size, subject to measured confirmation;
- **static-cost:** profitability decided from compile-time literal sizes;
- **profile-cost:** profitability depends on a pinned cost model and static
  analysis;
- **research:** no rule is enabled until semantics and cost boundaries are
  established.

If no optimization cost profile is supplied, only approved legality-only rules
may run. The compiler never invents live network parameters.

## PV11 feature inventory

### Batch 6 builtins

| Tag | Builtin | Existing JuLC surface | Optimization opportunities |
|---:|---|---|---|
| 87 | `ExpModInteger` | `MathLib.expMod`, `Builtins.expModInteger` | explicit modular-exponentiation lowering, literal folding, repeated-base/modulus specialization only if proven |
| 88 | `DropList` | raw builtin; `JulcList.drop` is still recursive | direct `JulcList.drop` lowering, safe constant identities, guarded chained-drop fusion |
| 89 | `LengthOfArray` | `JulcArray.length` | literal-array folding, array-region analysis, eliminate repeated length calls when sharing is explicit |
| 90 | `ListToArray` | `JulcList.toArray`, `JulcArray.fromList` | repeated-index promotion, conversion hoisting, literal-array folding |
| 91 | `IndexArray` | `JulcArray.get` | repeated list-index replacement after promotion, literal index folding, checked common-subexpression reuse |
| 92 | `Bls12_381_G1_multiScalarMul` | `BlsLib.g1MultiScalarMul` | fuse G1 scalar-mul/add chains; typed scalar/point lists |
| 93 | `Bls12_381_G2_multiScalarMul` | `BlsLib.g2MultiScalarMul` | fuse G2 scalar-mul/add chains; typed scalar/point lists |
| 94 | `InsertCoin` | `NativeValueLib.insertCoin` | typed singleton/update construction, insert-chain simplification where key semantics are proven |
| 95 | `LookupCoin` | `NativeValueLib.lookupCoin` | replace nested Data-map asset/lovelace scans inside a native Value region |
| 96 | `UnionValue` | `NativeValueLib.union` | replace `ValuesLib.add`; combine with scale for subtraction |
| 97 | `ValueContains` | `NativeValueLib.contains` | replace multi-asset containment scans; equality only when bidirectional containment equivalence is proven |
| 98 | `ValueData` | `NativeValueLib.toData` | sink conversion to external Data boundaries; fold typed native literals |
| 99 | `UnValueData` | `NativeValueLib.fromData` | hoist one conversion before several native operations; avoid repeated conversions |
| 100 | `ScaleValue` | `NativeValueLib.scale` | replace value negation/scaling; combine with union for subtraction |

`MultiIndexArray` tag 101 is excluded. Repeated indexes use repeated
`IndexArray` calls for the PV11 target.

### PV11 case-on-builtin forms

PV11 permits `Case` over selected builtin constants. Candidate replacements
are:

| Scrutinee | Current common form | Candidate form |
|---|---|---|
| `Bool` | forced `IfThenElse` with delayed branches | `Case Bool` with equivalent branch laziness |
| `Unit` | `ChooseUnit` sequencing | `Case Unit` when it removes force/chooser overhead |
| `Integer` | equality chain for a bounded switch | `Case Integer` only after exact branch/default semantics are pinned |
| `List` | `NullList`, then `HeadList` and `TailList` | `Case List` binding head/tail once |
| `Pair` | separate `FstPair` and `SndPair` | `Case Pair` binding both components once |

The compiler must not extrapolate case support to ByteString, String, Data,
Array, Value, or BLS constants without a later pinned profile proving it.

## Complete optimization catalog

### O1. Direct `JulcList.drop` lowering

**Current form**

```text
LetRec go(list, n) =
    if n <= 0 || NullList(list)
    then list
    else go(TailList(list), n - 1)
```

**Candidate**

```text
DropList n list
```

The builtin argument order must follow the pinned UPLC signature even if the
Java method is `list.drop(n)`.

**Classification:** legality-only, first implementation candidate.

**Required evidence**

- `n < 0`, `n == 0`, `0 < n < length`, `n == length`, `n > length`;
- empty and non-empty lists;
- dynamically failing `n` and list expressions to confirm evaluation order;
- nested/composed calls;
- before/after FLAT size and exact PV11 budgets;
- final UPLC contains `DropList` and no recursive template.

**Additional safe peepholes to investigate**

- `drop(0, xs) -> xs` only when evaluation of the count and list remains
  observationally identical;
- `drop(n, []) -> []` only when `n` is still evaluated as required;
- `drop(a, drop(b, xs)) -> drop(a + b, xs)` only with proof that both counts
  are non-negative. It is not valid for arbitrary negative counts.

### O2. Case-on-`Bool`

Replace the current forced/delayed `IfThenElse` sequence with `Case Bool` when
the target enables it and the selected form preserves lazy branch evaluation.

**Candidate source operations**

- Java `if`/`else`;
- conditional expressions;
- boolean-returning stdlib helpers;
- compiler-generated guards in loops and pattern matches.

**Classification:** research, then legality-only if it dominates.

**Required evidence**

- true/false branches;
- unselected `Error`, divergence, and `Trace` remain unobserved;
- condition evaluated exactly once;
- selected branch evaluation order unchanged;
- script size and exact budget matrix for tiny and nested conditionals.

### O3. Case-on-`List` traversal

Replace a single traversal step:

```text
if NullList(xs)
then nilCase
else nonNilCase(HeadList(xs), TailList(xs))
```

with one `Case List` that binds the head and tail.

**Candidate operations now using repeated list builtins**

- `JulcList.get`;
- `contains`;
- `size`;
- `reverse`;
- `concat`;
- `take` and the legacy form of `drop`;
- HOFs `map`, `filter`, `foldl`, `any`, `all`, `find`, and `zip`;
- map traversal, because `Map` is represented as a list of native pairs;
- Data-map/value traversal helpers that remain after native Value migration;
- compiler-generated record/data field-list traversal.

**Classification:** research per traversal family. A single global syntactic
rewrite is not assumed sound.

**Required evidence**

- empty/non-empty and singleton lists;
- head/tail binding and decode timing;
- partial-operation failures;
- recursive strictness and trace order;
- no duplicated scrutinee evaluation;
- size/budget comparison per family.

### O4. Case-on-`Pair` destructuring

Where the same native pair is projected with both `FstPair` and `SndPair`, use
one `Case Pair` binding both fields.

**Candidate operations**

- `JulcPair.key()` plus `value()` in the same region;
- `JulcMap` searches and iterations;
- `UnConstrData` results when both tag and fields are used;
- Data-map and remaining Value-map traversal;
- BLS or other APIs returning native pairs.

This does not apply to `Tuple2`, which is Data-encoded and has a different
representation.

**Classification:** research, requiring local use analysis or a typed PIR
destructuring form.

### O5. Case-on-`Integer` switches

Evaluate replacing bounded integer equality chains with `Case Integer`.

**Candidate operations**

- dense Java integer switches;
- compiler-generated tag dispatch where the scrutinee is already a native
  integer rather than Data;
- small enum-like native integer dispatch.

**Classification:** research. Exact out-of-range/default behavior, negative
values, branch selection, and size growth must be pinned before a rule is
designed. Sparse switches may remain equality trees.

### O6. Case-on-`Unit`

Evaluate replacing `ChooseUnit` patterns used solely for sequencing with
`Case Unit`.

**Classification:** low-priority research. Only ship if it removes overhead
without changing strict evaluation or trace order.

### O7. Typed native Value region

Introduce a distinct Java/PIR type, tentatively `JulcValue`, for the native
UPLC `Value` constant. It must not be represented as `PlutusData`.

```text
Data-encoded ledger Value
    -> UnValueData once
    -> typed native Value region
         LookupCoin / InsertCoin / UnionValue / ValueContains / ScaleValue
    -> ValueData at an external Data boundary
```

The type boundary is required before automatic conversion elimination. The
current `NativeValueLib` accepts and returns `PlutusData`, which cannot prevent
Data/native mixing.

**Immediately plausible high-level replacements**

| Existing operation | Native candidate | Notes |
|---|---|---|
| `ValuesLib.lovelaceOf(v)` | `LookupCoin #"" #"" (UnValueData v)` | removes first-entry ordering assumption; prove empty-key semantics |
| `ValuesLib.assetOf(v,p,t)` | `LookupCoin p t (UnValueData v)` | native lookup returns zero when absent |
| `ValuesLib.add(a,b)` | `ValueData(UnionValue(UnValueData a, UnValueData b))` | compare representation/canonicalization at Data boundary |
| `ValuesLib.geqMultiAsset(a,b)` | `ValueContains(UnValueData a, UnValueData b)` | verify argument order and negative/zero quantities |
| `ValuesLib.negate(v)` | `ValueData(ScaleValue(-1, UnValueData v))` | compare zero-entry and ordering behavior |
| `ValuesLib.subtract(a,b)` | `ValueData(UnionValue(UnValueData a, ScaleValue(-1, UnValueData b)))` | preserve conversion and failure order |
| `ValuesLib.singleton(p,t,n)` | `InsertCoin p t n emptyNativeValue` then `ValueData` | define empty native Value construction from pinned semantics |

**Operations not automatically replaceable yet**

- `containsPolicy`, which asks about a policy without a token name;
- `flatten`, `countTokensWithQty`, and `findTokenName`, which require iteration
  not exposed by Batch 6 native Value builtins;
- `isZero` and equality until zero-entry, negative-quantity, and canonical
  representation behavior is proven;
- arbitrary `ValueData(UnValueData(d)) -> d`, because conversion may validate or
  normalize malformed/non-canonical Data.

**Classification:** typed API and explicit native operations first; automatic
region formation and conversion motion second.

### O8. Native Value conversion optimization

After O7 establishes typed invariants:

- hoist `UnValueData` once across several native operations;
- sink `ValueData` to the final external Data boundary;
- eliminate adjacent conversions only where the type system proves the value
  is already a valid native Value and canonicalization is unobservable;
- share one conversion through explicit `let` binding rather than duplicate
  evaluation;
- fold literal native operations when result size is acceptable.

**Classification:** static data-flow analysis. No untyped UPLC peephole may
guess whether a `PlutusData` term contains a valid Value.

### O9. Cost-directed list-to-array promotion

Detect repeated random access to the same immutable list:

```text
list.get(i1), list.get(i2), ...
    -> let a = ListToArray(list)
       in IndexArray(a, i1), IndexArray(a, i2), ...
```

**Eligible shapes**

- the list expression is evaluated once and immutable;
- all promoted uses are index operations compatible with Array element
  encoding;
- use/escape analysis proves the array can be shared;
- index failure behavior matches the current `JulcList.get` contract;
- the explicit cost profile shows a benefit for the static use count and any
  known list/index bounds.

**Ineligible by default**

- one indexed access;
- sequential head/tail traversal;
- unknown sharing that would duplicate `ListToArray`;
- lists escaping into code that expects list representation;
- transformations relying on unreleased `MultiIndexArray`.

**Classification:** profile-cost, requiring PIR use-count/escape analysis and
cost-derived break-even tests.

### O10. Array constant folding and simplification

For literal or statically constructed arrays, investigate:

- `LengthOfArray(ListToArray(literalList))`;
- `IndexArray(ListToArray(literalList), literalIndex)`;
- repeated `LengthOfArray` on one let-bound array;
- `ListToArray` of a statically known list.

Known out-of-bounds indexes must remain runtime failures at the correct point;
the compiler must not silently turn them into diagnostics or values. Folding a
large array literal is rejected if it increases script size beyond the rule's
declared objective.

**Classification:** static-cost.

### O11. BLS G1/G2 MSM fusion and typed API

Recognize typed expressions equivalent to:

```text
s1 * P1 + s2 * P2 + ... + sn * Pn
```

and lower them to the corresponding multi-scalar multiplication builtin.

**Required typed surfaces**

- distinct G1 and G2 element types rather than interchangeable `byte[]` at the
  compiler boundary;
- typed scalar lists;
- typed G1/G2 point lists;
- no fusion across compression/uncompression or group mismatch.

**Semantic gates**

- scalar bounds and unlifting;
- empty lists;
- unequal scalar/point list lengths and pinned zip/failure behavior;
- evaluation order of every scalar and point expression;
- group identity behavior;
- no loss of errors or traces from individual terms.

**Classification:** explicit typed MSM API is legality-only; automatic chain
fusion is research/static analysis and profile-cost.

### O12. `ExpModInteger` idiom lowering

`MathLib.expMod` and `Builtins.expModInteger` already map directly to the
builtin. Additional compiler work may recognize only explicit modular
exponentiation abstractions whose source contract matches:

```text
(base ^ exponent) mod modulus
```

Ordinary `MathLib.pow(base, exponent)` followed by an unrelated `%` is not
rewritten unless the compiler proves the exact explicit-modulus idiom and its
failure behavior.

**Boundary matrix**

- zero/one/negative modulus;
- negative exponent;
- zero base and exponent combinations;
- very large bounded integers under semantics E;
- failures and their evaluation order.

**Classification:** explicit API direct lowering is already present; idiom
recognition is research.

### O13. `ExpModInteger` constant folding

Fold all-literal calls only when:

- reference success/failure behavior is implemented exactly;
- every argument is already a value, so folding does not remove effects;
- the result satisfies PV11 integer bounds;
- embedding the result meets the rule's script-size objective;
- known failure remains `Error` at the equivalent evaluation point rather than
  being silently accepted or converted to a compile-time source error.

**Classification:** static-cost.

### O14. Native Value constant folding and algebraic rules

Candidate literal operations:

- `InsertCoin` into a literal native Value;
- `LookupCoin` in a literal native Value;
- `UnionValue` of literal Values;
- `ValueContains` of literal Values;
- `ScaleValue` of a literal Value;
- final `ValueData` of the literal result.

Potential algebraic identities such as scaling by one, union with empty, or
bidirectional containment for equality are not assumed. Each requires proof
against zero quantities, negative quantities, canonical ordering, duplicate
entries at the Data boundary, and strict argument evaluation.

**Classification:** static-cost after O7 and reference-semantic test helpers.

### O15. Conversion and projection common-subexpression reuse

PV11 operations make several repeated computations especially visible:

- multiple `LookupCoin` calls on the same `UnValueData(value)`;
- multiple `IndexArray`/`LengthOfArray` calls on one `ListToArray(list)`;
- `FstPair` and `SndPair` of one pair;
- repeated decode operations around case-bound list elements.

Introduce explicit `let` sharing at typed PIR only when the original expression
was already evaluated once or is proven pure and total. General common
subexpression elimination is out of scope; these are representation-aware,
targeted sharing rules.

**Classification:** static use analysis.

## Issue #77 traceability

ADR-029 and issue #77 grouped the work into seven roadmap IDs. This ADR expands
them into independently testable operations without changing their ownership:

| Roadmap task | Detailed operations in this ADR |
|---|---|
| PV11-050 — `DropList` lowering | O1 |
| PV11-051 — case-on-builtin lowering | O2–O6, plus case-based portions of O15 |
| PV11-052 — typed native Value API/lowering | O7, O8, native-Value portions of O14 and O15 |
| PV11-053 — list-to-array promotion | O9, Array portions of O10 and O15 |
| PV11-054 — BLS MSM fusion/typed API | O11 |
| PV11-055 — `ExpModInteger` lowering/folding | O12 and O13 |
| PV11-056 — Value/Array constant folding | O10 and O14 |

Child issues may split these rows further, but they must retain both the
original roadmap ID and the O-number so aggregate completion can be audited.

## Prioritization

| Priority | Workstream | Reason |
|---:|---|---|
| P0 | Optimization harness, target and cost-profile plumbing | prerequisite for trustworthy evidence |
| P1 | O1 direct `DropList` lowering | small, explicit, high expected size/budget gain |
| P1 | O7 typed native Value API and explicit native operations | removes unsafe Data/native ambiguity and unlocks major stdlib savings |
| P1 | O2/O3/O4 case-on-builtin experiments | broad potential across compiler-generated control flow and traversal |
| P2 | O9 list-to-array promotion | valuable for repeated indexing but requires cost/use analysis |
| P2 | O11 BLS MSM typed API/fusion | strong domain-specific gain; substantial semantic/type work |
| P2 | O12/O13 ExpMod idiom/folding | explicit builtin exists; automatic recognition is narrower |
| P2 | O8/O10/O14/O15 conversion, folding, and sharing | depends on typed regions and robust static analysis |
| P3 | O5/O6 integer/unit case lowerings | benchmark/research value is less certain |

Priority does not authorize implementation. Each row is split into focused
issues before code changes.

## Optimization stages

Use the highest-level stage that still has the required semantic information:

| Stage | Appropriate rules |
|---|---|
| Java AST / typed call recognition | explicit modular exponentiation idioms, typed BLS patterns |
| Typed PIR lowering | direct `DropList`, native Value region formation, list/pair case binding, array promotion, let sharing |
| Target-aware PIR optimization | use/escape analysis, conversion motion, algebraic rules with typed invariants |
| UPLC optimization | literal folds and local rules whose saturation, force count, strictness, and types are certified |
| Final validation | legality only; never repairs illegal output silently |

Do not implement representation-sensitive rules as untyped UPLC pattern
matching merely because the emitted term shape is recognizable.

## Rollout policy

Compiler optimization configuration uses these public levels:

```text
NONE          no optimizer rewrites; target validation still runs
BASELINE      existing sound optimizer passes
PV11_SAFE     reviewed target-legal rules that are not input-size dependent
PV11_COSTED   PV11_SAFE plus rules using an explicit OptimizationCostProfile
```

Initial rollout rules:

- existing behavior maps to `BASELINE`;
- `BASELINE` remains the default during the first ADR-032 implementation and
  review window, preserving ADR-031 script bytes;
- new direct lowerings may begin opt-in if they change widely deployed script
  hashes;
- profile-cost rules are never enabled without a named cost profile;
- source-map compilation may disable UPLC motion as today, but target
  validation remains active and typed lowerings must maintain source mapping;
- release notes list every newly default-enabled rule and representative hash
  changes.

## Measurement policy

There is no single scalar combining CPU, memory, and script size. Each child
issue declares its objective and acceptance envelope.

At minimum record:

```text
source fixture
compiler target ID
optimization cost profile ID and parameter hash
before/after UPLC text or structural summary
before/after FLAT byte count
before/after CPU
before/after memory
success/failure/trace equivalence
before/after script hash
```

An always-on rule should normally dominate the existing form for its declared
input domain. A rule with trade-offs is opt-in or guarded by a proven static
threshold. Representative benchmarks are evidence, not a substitute for
semantic tests or a profitability condition.

The reproducible comparison harness lives in `julc-benchmark` but is distinct
from VM-throughput JMH. It compiles the same fixture at `BASELINE` and the
candidate level, loads one pinned cost profile, evaluates through explicitly
selected Java and Truffle providers, and records the full minimum measurement
set above. `BASELINE` is retained as an executable before-image, and golden
tests ensure its bytes remain identical to ADR-031 while the migration window
is open.

## Child-issue requirements

Every optimization issue must include:

1. exact source pattern and ownership stage;
2. before/after PIR and UPLC;
3. required target capabilities;
4. semantic and representation assumptions;
5. evaluation-order, strictness, trace, and failure analysis;
6. cost classification and cost-profile dependency;
7. positive, boundary, negative, nested, and regression tests;
8. size and exact-budget fixtures;
9. determinism and script-hash impact;
10. rollout level and default/opt-in decision;
11. independent review before merge.

If evidence is inconclusive, the issue records an explicit deferral rather than
landing a speculative pass.

## Affected modules

| Module | Expected work |
|---|---|
| `julc-compiler` | target-aware PIR/UPLC passes, typed region/use analysis, optimization profiles, final validation integration |
| `julc-stdlib` | direct `DropList`, typed native Value and BLS APIs, source-level migration surfaces |
| `julc-core` | typed constants/semantics helpers only where an existing representation needs extension |
| `julc-vm` | target and cost-profile provenance contracts; no compiler-specific profitability decision |
| `julc-vm-java` | exact budget and differential evaluation fixtures |
| `julc-vm-truffle` | parity validation of optimized programs |
| `julc-testkit` | before/after semantic and budget assertion helpers |
| `julc-benchmark` | reproducible optimization fixture runner distinct from VM throughput JMH benchmarks |
| CLI/Gradle/AP | optimization level and cost-profile selection/reporting |
| docs/examples | public API migrations, target/cost profile, hash-change guidance |

## Compatibility and script hashes

All Phase 5 changes can alter generated program bytes. Public source behavior
must remain compatible unless a focused typed-API issue explicitly introduces a
documented migration.

For every default-enabled change:

- publish representative before/after hashes;
- state that recompilation changes deployment artifacts even when source is
  unchanged;
- retain a way to compile with the previous optimization level for an
  appropriate migration window;
- do not claim an existing deployed script has changed—only newly compiled
  artifacts change.

Typed `JulcValue` or BLS API changes are public API work and require migration
guidance. Existing raw `NativeValueLib`/`BlsLib` methods may remain deprecated
bridges until the chosen compatibility window ends.

## Future protocol and cost-model support

When ADR-031 adds a later compiler target:

1. every rule is disabled for that target by default;
2. legality is rechecked against the new `ProtocolFeatureProfile`;
3. semantic equivalence is rerun under the new semantics variant and bounds;
4. profile-cost rules receive a new pinned `OptimizationCostProfile` and
   thresholds;
5. rules are enabled individually after evidence passes;
6. an identical builtin set does not imply identical cost or failure behavior;
7. the old PV11 target and cost profile remain reproducible while supported.

Rules are keyed by named capabilities plus an explicit support entry, never by
an open-ended `protocol >= 11` condition.

## Alternatives considered

### Enable all direct PV11 builtins wherever a matching method name exists

Rejected. Method contracts, argument order, failure behavior, conversion cost,
and representation can differ even when names look equivalent.

### Put every rule in `UplcOptimizer`

Rejected. Untyped UPLC has already lost Java/PIR type and alias information
needed for native Value, array promotion, BLS fusion, and safe conversion
motion.

### Use protocol version as the cost profile

Rejected. Ledger cost parameters can change without a new protocol major.

### Use the current live mainnet cost model implicitly

Rejected. Builds would cease to be reproducible and offline compilation would
depend on external state.

### Optimize for one weighted CPU/memory/size score

Rejected. The weights are product- and workload-dependent and can hide a major
regression in one resource. Child issues declare explicit objectives.

### Automatically convert every ledger `Value` to native Value

Rejected. Conversion validation, canonicalization, Data boundaries, and
iteration-only operations make blanket conversion unsound or unprofitable.

### Use `MultiIndexArray` for repeated list indexes

Rejected for PV11. Tag 101 is future/unreleased; use one `ListToArray` and
repeated `IndexArray` calls.

### Ship Phase 5 as one undifferentiated PR

Rejected. The workstreams have different semantic risks, public APIs, cost
models, and review requirements.

A stacked integration PR is permitted when its base is the ADR-031 integration
branch and all of the following remain true: each shipped optimization has a
focused child issue; every delivery milestone is implemented and tested on a
separate branch; milestone commits remain independently reviewable; research
rules without complete evidence are explicitly deferred; and the aggregate PR
does not weaken any child issue acceptance gate. This is a delivery container,
not authorization to combine speculative transformations.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| A rewrite changes failure timing | explicit strictness/failure analysis and adversarial tests |
| Cost model changes make a rewrite worse | pinned cost profile; no implicit live/mainnet profile |
| Data/native Value mixing | dedicated typed native Value representation |
| List-to-array conversion is duplicated | use/escape analysis and explicit let sharing |
| BLS fusion changes zip/bounds behavior | typed lists and pinned mismatch/scalar tests |
| Case lowering evaluates an unselected branch | Error/divergence/Trace branch tests |
| Constant folding hides a runtime error | preserve `Error` and evaluation point; fold only certified arguments |
| New defaults unexpectedly change hashes | optimization levels, release notes, golden hashes |
| Future PV inherits PV11 rules accidentally | per-target enablement and final target validation |
| Benchmark-only evidence misses semantics | semantic equivalence is mandatory before cost evidence |

## Delivery milestones

### Milestone 0 — Infrastructure

**Implementation status:** Complete on the ADR-032 integration line (2026-08-29).

- Complete ADR-031/#76.
- Add optimization level and optional pinned cost profile.
- Add reproducible before/after size, budget, trace, and hash fixtures.
- Add per-rule identity to optimizer diagnostics/provenance.

Implementation decisions for Milestone 0:

- cost-profile contracts and the canonical pinned parameter resource live in
  `julc-vm`, which remains backend-neutral;
- VM backends own interpretation of the opaque ordered parameter array;
- the compiler snapshots the selected level/profile and retains deterministic
  rule IDs and cost-profile provenance in `CompileResult`;
- the initial pinned profile is
  `cardano-node-11.0.1-plutus-v3-pv11`, containing 350 V3 parameters with
  canonical parameter SHA-256
  `40ea9e0b7df77a7bd2cb7d4e4d9da040f8bee7ff0324a7cdb7e51702330e43a8`.
- public tooling uses exact IDs across Java APIs, CLI, Gradle, annotation
  processing, and MCP; unknown level/profile IDs fail closed with `JULC0039`
  and `JULC0040`;
- the non-JMH comparison harness checks success/failure, returned terms,
  failure text, trace order, FLAT bytes, script hashes, UPLC structure, CPU,
  and memory, and emits deterministic Markdown tables for release notes;
- default and explicit `baseline` compilation are byte-identical, and the
  full affected VM/compiler/tooling test set passed before the milestone merge.

### Milestone 1 — Direct PV11 lowering

**Implementation status:** Complete on the milestone branch; pending merge to
the ADR-032 integration line. See [#94](https://github.com/bloxbean/julc/issues/94)
and [O1 evidence](evidence/032-o1-drop-list.md).

- Create the O1 child issue.
- Implement and verify `JulcList.drop -> DropList`.
- Publish exact size/budget results and script-hash impact.

### Milestone 2 — Typed native operations

- Design and review `JulcValue` and typed BLS group/list APIs.
- Add explicit native Value and MSM operations without automatic fusion.
- Establish conversion and representation tests.

### Milestone 3 — Case-on-builtin experiments

- Split Bool, List, Pair, Integer, and Unit into independent issues.
- Pin semantics and benchmark each form.
- Enable only the forms with demonstrated benefit and complete equivalence.

### Milestone 4 — Cost/use-directed rewrites

- Implement list-to-array analysis.
- Implement typed conversion sharing/motion.
- Implement BLS fusion and explicit modular-exponentiation recognition where
  evidence supports them.

### Milestone 5 — Literal folding and aggregation

- Add target-aware Array, Value, and ExpMod folds.
- Run aggregate representative contracts.
- Publish combined size/budget/hash results and deferred work.

## Verification strategy

### Semantic differential tests

Evaluate original and optimized programs under the same explicit V3/PV11
target with Java and Truffle. Compare:

- success/failure class;
- returned value;
- traces and order;
- boundary behavior;
- deterministic bytes.

Use property tests where bounded generators can cover both source and builtin
domains, especially list counts/indexes, Values, and modular exponentiation.

### Structural tests

Assert the intended builtin/form appears and the superseded recursive template
or chooser sequence does not. Structural assertions supplement, not replace,
semantic evaluation.

### Budget and size tests

Use exact pinned PV11 cost parameters. Include small, boundary, representative,
and crossover inputs. Profile-cost rules assert their derived threshold on both
sides of the break-even point.

### Invalid and unsupported tests

- force every rule under an unsupported synthetic target and verify rejection;
- ensure tag 101 never appears for PV11;
- test malformed native Value Data, mismatched BLS lists, invalid scalars,
  negative/drop bounds, array out-of-range, and ExpMod failure cases;
- ensure optimizers cannot bypass final target validation.

### Integration tests

Run compiler, stdlib, Java VM, Truffle VM, testkit, CLI, Gradle plugin,
annotation processor, examples, and repository build suites in proportion to
each child issue's impact.

## Acceptance criteria for closing Phase 5

- [ ] Every O1–O15 workstream has a completed child issue or an explicit,
      evidence-backed deferral.
- [ ] Every shipped rule declares its target capabilities and cost
      classification.
- [ ] No shipped rule uses raw/open-ended protocol checks.
- [ ] Original/optimized semantic differential tests pass for Java and
      Truffle.
- [ ] Failure, strictness, trace, and boundary cases are covered.
- [ ] Profile-cost rules record the cost-profile ID and parameter hash.
- [ ] Aggregate size, CPU, memory, and script-hash results are published.
- [ ] Public typed API migrations and generated-hash changes are documented.
- [ ] Final target validation runs after all optimizations.
- [ ] Full affected-module and repository validation passes.

## Open questions

Resolved by Milestone 0:

1. `NONE`, `BASELINE`, `PV11_SAFE`, and `PV11_COSTED` are the public levels.
   `BASELINE` is initially default; promotion of a safe rule to the default is
   an evidence and release decision, not a target change.
2. The pinned optimization cost-profile contract and resource live in
   backend-neutral `julc-vm`; the compiler does not depend on a VM backend.
3. The aggregate suite begins with per-rule boundary/crossover fixtures and
   grows into representative validators. A fixture is admitted only with
   deterministic source and arguments, not because it is convenient for a
   favorable result.

Still open for their focused child issues:

1. Should `JulcValue` live in `julc-core`, `julc-ledger-api`, or a dedicated
   on-chain type package?
2. What typed G1/G2 representation best preserves existing byte-array API
   compatibility?
3. Should case-on-list be expressed as a new typed PIR match node or as
   target-aware lowering metadata on existing list traversal builders?
4. What objective should govern literal folding when CPU improves but embedded
   constant size grows?

These questions are resolved in focused child ADRs/issues; they do not weaken
the invariants or authorize speculative rewrites.
