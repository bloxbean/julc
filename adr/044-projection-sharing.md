# ADR-044: Strict-prefix sharing of record field projections (O15)

**Date:** 2026-09-13
**Status:** Implemented and locally validated on `feat/120-projection-sharing` (stacked on ADR-043's `feat/115-list-to-array-promotion`); independent agent reviews and advisor passes applied; maintainer review pending
**Issues:** [#120](https://github.com/bloxbean/julc/issues/120) (O15), research decision [#105](https://github.com/bloxbean/julc/issues/105), parent [#77](https://github.com/bloxbean/julc/issues/77)
**Governing decisions:** ADR-032 O15 (representation-aware let sharing, no general CSE), ADR-042 (the leading rule, its proof and the pass it lives in), ADR-036 (PIR-to-PIR pass placement before UPLC generation), ADR-015 (strict typed boundaries), ADR-043 (the costed promotion that consumes O15's bindings)

## Context and current behavior

A record field access on a nested value, `txInfo.outputs()` on a `TxInfo` local or parameter,
`b.amount()` on a cast local, `out.value()` on a for-each item, lowers to a projection chain
over the record's Data encoding:

```
unListData (headList (tailList (tailList (sndPair (unConstrData txInfo)))))
```

`PirGenerator.generateFieldExtraction` emits that chain at every access site;
`PirHelpers.wrapDecode` adds one decode arm per field type (`unIData`, `unBData`,
`unListData`, `unMapData`, the Bool form `equalsInteger(fstPair(unConstrData(·)), 1)`, the
String form `decodeUtf8(unBData(·))`, or nothing for `Data`, records and sum types). Only the
root-level projections of an entrypoint's own record parameters are bound once, by the strict
boundary (`__boundary-fields-*`); a switch pattern variable's fields reuse the match binders.
Everything nested is recomputed per site, and `compareTo` on a projected integer evaluates its
receiver twice on its own.

The example corpus repeats these chains constantly: `txInfo.outputs()` occurs 66 times in 29
files, `txInfo.validRange()`/`mint()`/`signatories()` several times per validator, and helper
methods such as an escrow's `handleCompleteTrade` project `txInfo.outputs()` three times in a
row. Each repeat re-runs `unConstrData`, `sndPair`, `k` `tailList`s, `headList` and the decode:
462,474 CPU for an integer field at depth 0, 1,058,045 CPU for a raw field at depth 5 under
`cardano-node-11.0.1` PV11 costs.

ADR-042 introduced `ValueConversionSharingPass` with a rule that shares a repeated
`unValueData(x)` exactly when it is already the first non-trivial evaluation of its scope, so
that no result, trace, failure point or failure text can change. Issue #120 asks for the
"smallest typed dominance/use/alias/escape analysis needed for deterministic let sharing of
proven expressions" once at least two consumer rules need it, and requires every consumer to be
separately legal and independently disableable. This ADR adds the two projection classes as
consumers of the ADR-042 rule; the rule itself, and its no-dominance-analysis proof, are
unchanged.

## Goals and non-goals

Goals:

- Share one record field projection chain, and one fields prefix, of a variable across a scope
  at the safe profile whenever doing so cannot change any result, trace, failure point or
  failure text, and pin exactly when the pass applies.
- Keep NONE and BASELINE byte-identical, keep every program without repeated projections
  byte-identical at every level, and keep every ADR-042 fixture byte-identical with every rule
  enabled.
- Produce, for the motivating shape, the same bytes as binding the field by hand.
- Make each PIR-to-PIR rule (O8, O9, O15) individually switchable so it can be reviewed,
  measured and, if necessary, excluded on its own, with a fail-closed diagnostic for unknown
  rule ids.
- State the cost model exactly and pin it against the pinned profile.

Non-goals (recorded so they are not reintroduced without their own proof):

- Sharing `ValuesLib` computations (`assetOf`, `lovelaceOf`). Those are library calls whose
  bodies traverse Data with their own failure behaviour; ADR-042's open question stands.
- Sharing a unit that does not lead its scope: no totality or anticipability rule, no
  hoisting out of exclusive branches, no hoisting a loop-invariant projection out of a loop
  whose body evaluates it first. The corpus count of such shapes is recorded under Open
  questions.
- Sharing `ListToArray`, `IndexArray`/`LengthOfArray` or `FstPair`/`SndPair` of a native pair
  (the other O15 examples in ADR-032). `ListToArray` is placed by ADR-043 itself; there is no
  producer of the others in the corpus.
- General common-subexpression elimination, or any untyped UPLC peephole.

## Invariants and proof

Definitions, for a scope term `S` and a variable `x` not rebound between `S` and a use. A
**unit** is one of:

- `V(x)`, ADR-042's native Value conversion: `[(builtin unValueData) x]` or `[w x]` with `w`
  the once-bound `NativeValueLib.fromData` wrapper.
- `F(x, k, D)`, a field chain: `D(headList(tailList^k(sndPair(unConstrData(x)))))` with `D`
  exactly one `wrapDecode` arm (none, `unIData`, `unBData`, `unListData`, `unMapData`, the Bool
  form, the String form). The chain is matched outermost arm first: the raw chain inside a
  decode is part of that unit and never a unit of its own, so a repeated Bool field is bound as
  the whole `equalsInteger(fstPair(unConstrData(headList(...))), 1)` and a `unIData(H)` beside a
  `lengthOfByteString(unBData(H))` are two different units that share nothing but the prefix.
- `P(x)`, the fields prefix: `sndPair(unConstrData(x))`.

Two occurrences are the same unit when their key, the root variable's *name*, the class and
for chains `(k, D)`, is equal; the `Var` node's type annotation is never compared (the loop
lowering annotates the same name differently at different sites). Trivial evaluations and
**leads** are as in ADR-042, with one addition: the body of a single-binding `LetRec` whose
value is a lambda can lead, provided the binding's name is neither the unit's variable nor a
wrapper alias. Such a binding lowers to `Z (λf. lambda)`; evaluating it performs a fixed number
of beta steps over lambda values and builds the recursive closure, and cannot fail, trace or
run user code (the UPLC optimiser drops an unused one on exactly this argument,
`isFixpointOfFunction`). The multi-binding Bekić lowering is not claimed and never leads. The
per-site `JulcList.get` lowering wraps each site in exactly such a single binding, so without
this addition `b.items().get(0)` beside `b.items().get(1)` could never lead.

**Dead bindings.** A `Let` (or single-binding `LetRec`) whose value is a lambda and whose name
is not referenced by the live part of its body, computed transitively with the dead bindings of
the body removed first, is dropped by the UPLC optimiser. The pass neither counts nor rewrites
units inside such a binding. Every library method a program does not call, directly or through
another live method, is bound this way; without this exclusion the pass fired inside
`ContextsLib` helpers in every program that imports `ContextsLib`, recording provenance for code
absent from the artifact and changing the bytes of source-map builds only.

**Rule.** For each unit class in turn, for every scope `S` in pre-order, if some unit `U(x)` has
at least two occurrences in `S` (outside any rebinding of `x`, outside dead bindings) and
`U(x)` leads `S`, emit `let v = U(x) in S[U(x) := v]` with a fresh `v` typed by the decode arm
(`Data` for the raw arm and the prefix, `NativeValue` for `V`), then continue below the new
binding. Rounds: field chains to a fixed point (a shared chain is a variable and may root
further chains, so `b.inner().x()` and `b.inner().y()` share the inner projection first and
then the prefix of the shared inner), then the fields prefix over the distinct chains that
remain including those inside the bindings just inserted, then Value conversions, which may now
apply to a shared raw projection. Each round re-reads the current term; fresh names
(`#field-N`, `#fields-N`, `#value-N`) stay unique across rounds.

**Soundness.** Identical to ADR-042: evaluating `S` performs a trivial prefix (now possibly
including the construction of recursive closures) and then `U(x)`; evaluating `S'` performs
`U(x)` first and then the same prefix with `v` in place of the leading unit. The prefix cannot
fail, trace or observe anything. If `U(x)` fails, both fail at the same builtin on the same
input with the same message and trace prefix; if it succeeds with `V`, every later `U(x)` in
`S` is the same deterministic sequence of builtins on the same immutable `x` and yields `V`.
No trust in PIR types is involved: a chain on a cast local whose Data is malformed fails in
the shared binding exactly where the leading site failed before (`CAST` pins identical failure
text at every level on `NOT_A_RECORD`, `EMPTY_RECORD` and `BAD_AMOUNT`). The prefix round is
sound for the same reason: `P(x)` leads wherever a chain of `x` leads, because
`unConstrData(x)` is the first evaluation inside every chain. Rebinding is respected as before;
a dead binding's value is left untouched, which is trivially sound.

**Failure-text neutrality.** No new failure point exists; the test asserts identical failure
text on Java, Truffle and Scalus for every malformed input at every level. The one exception is
deliberate and costed-only: because O15 binds a projected list once, ADR-043 now promotes
`b.items().get(i)` sites at `PV11_COSTED` that it could not reach before (the list was a chain,
not a variable), so an out-of-range index on such a site fails with ADR-043's `IndexArray` text
at the costed profile (`FIELD_THEN_INDEX`, pinned with `IndexFailureEquivalence`).

**Cost.** Under the pinned profile every CEK machine step (lambda, application, variable,
constant, builtin, force) costs 16,000 CPU and 100 memory. Sharing a unit at `k` sites of one
scope costs one lambda, one application and `k` variable lookups and saves `k − 1` evaluations
of the unit:

```
after = before + (2 + k) · 16,000 − (k − 1) · unit        (CPU; memory with 100 and the unit's memory)
```

The test asserts this exactly, without the UPLC optimiser, for six shapes at two and three
sites. Measured unit costs (Java VM, source maps on, including the lookup of the root
variable):

| Unit | CPU | Memory |
|---|---:|---:|
| fields prefix `sndPair(unConstrData(x))` | 278,580 | 764 |
| integer field, depth 0 | 462,474 | 1,328 |
| list field, depth 1 | 597,326 | 1,660 |
| byte-string field, depth 2 | 721,198 | 1,992 |
| raw field, depth 5 (`txInfo.validRange()`-like) | 1,058,045 | 2,756 |
| Bool field, depth 3 | 1,177,535 | 3,157 |

The loss bound on a path that reaches only the leading occurrence is therefore the ADR-042
bound, 48,000 CPU and 300 memory per shared binding (`k = 1`), and it is protocol-independent
in kind (three machine steps). The cheapest unit at two sites saves 462,474 − 64,000 = 398,474
CPU; a path pays only when it evaluates one site of a unit that another path evaluates twice
(`BOOL_STRING` `closed`: +96,000, two bindings; `TWO_FIELDS` malformed after the first field:
+48,000). FLAT size never grows for the shared fixtures (−7 to −42 bytes); the test pins
`bytes ≤ before + 1`.

## Decision

1. Generalise `ValueConversionSharingPass` in place around the unit classes above; the class
   and file keep their name and their ADR-042 behaviour. Rule provenance
   `pv11.o15.projection-sharing` when a projection class fires and `pv11.o8.value-sharing`
   only when the Value class fires. Gate for the projection classes: exact `PLUTUS_V3_PV11` and
   a level with `pv11SafeRulesEnabled()`; no capability, because every builtin involved
   predates PV11 and the gate is rollout policy, not legality. The Value class keeps its
   `VALUE_CONSTANTS` gate.
2. Rounds in the fixed order chains, prefix, Value, and the fixed point on chains. Sharing the
   prefix first would leave the chains rooted at a variable that the chain matcher does not
   recognise; sharing chains first and then the prefix over what remains is both simpler and
   never worse.
3. The single-recursive-binding leading rule and the dead-binding exclusion apply to all three
   classes. Both are neutral for the ADR-042 fixtures, whose suite runs with every rule enabled
   and stays byte-identical.
4. Add `CompilerOptions.disableOptimizationRule(ruleId)` and `CompilationContext.ruleEnabled`.
   The three PIR passes consult it inside their gates; `CompilationContext.resolve` validates
   each id against `switchableOptimizationRules()` and fails with `JULC0043`
   (`UNKNOWN_OPTIMIZATION_RULE`) otherwise, so a typo can never silently leave a rule on. Rules
   inside `UplcGenerator` and `UplcOptimizer` are not switchable, and the Gradle plugin and CLI
   do not expose the switch; the level remains the supported rollout control. The O9 golden
   suite compiles with O15 off (its `FIELD` fixture has two leading `h.items()` chains, the only
   earlier fixture whose safe bytes O15 moves), the O8 suite with everything on, and the
   benchmark compares `PV11_SAFE` with O15 off against `PV11_SAFE` with it on.
5. Keep the pass purely syntactic on typed PIR, with no cost model and no protocol constants
   beyond the gate. `NONE`/`BASELINE` are the identity by gate.

## Alternatives rejected

- **A separate `ProjectionSharingPass` with an extracted analysis helper.** Same rule, same
  traversal, same proof; two passes would duplicate the leading walk, the trivial-spine test
  and the rebinding-aware mapper, and would have to agree on round order across files. One
  pass with a unit matcher keeps the ADR-042 code path byte-for-byte and makes the O15 rounds
  a visible extension of it.
- **Keying units by the `Var` node** (name plus type). The loop lowering emits `let xs = xs`
  with the declared type while other sites carry the inferred one, so equal computations would
  fail to match. Names are keyed; structure is matched.
- **Counting the raw sub-chain inside a decode as its own unit.** It would be bound first,
  leaving the decode unshared, and it double-counts. A matched unit is a leaf for both the
  collector and the rewriter: a unit of another key of the same class is opaque to the leading
  walk, the counter and the mapper, so the output shape of one scope does not depend on which
  keys other scopes happen to contribute (the first review found the rewriter descending into
  decodes for a raw key; pinned by a direct-PIR probe).
- **Sharing inside dead library bindings** (ADR-042's behaviour, inherited by accident). Every
  program importing `ContextsLib` recorded O15 provenance for helpers it never called. Excluded
  by the transitive dead-binding test; the UPLC optimiser drops those bindings anyway.
- **Recording provenance from the final UPLC instead.** Would fix the same symptom for every
  rule but needs a UPLC-to-rule mapping that does not exist; left as an open question.
- **A totality/anticipability rule** (share a unit evaluated on every path even when it does not
  lead, or hoist a loop-invariant projection). Sound for total chains only if PIR types are
  trusted at runtime, which ADR-042 and ADR-043 decline to do at the safe profile; the corpus
  count is recorded below.
- **Both-branch and prefix-only-per-branch sharing.** Every path evaluates exactly one of the
  two chains; sharing above the conditional only adds a binding (ADR-042's measurement, and
  `BOTH_BRANCHES` stays byte-identical). The prefix *is* shared across branches when the
  chains differ (`LEDGER`, `BOOL_STRING`), because the leading chain's prefix already ran on
  every path.
- **Costed profile only.** The loss bound is three machine steps per binding, protocol
  independent; the rule needs no cost model. Enabled at the safe profile like O1–O5 and O8.
- **Refining the shared variable's type from the record definition.** No consumer reads the
  type of a `Let`-bound projection (`UplcGenerator` erases types; ADR-043 proves a `Let` by its
  value), so the decode arm's type is sufficient and needs no type resolver in the pass.

## Affected stages and modules

- `julc-compiler`: `pir/ValueConversionSharingPass` (unit classes, rounds, dead bindings,
  recursive-binding leading), `CompilerOptions.disableOptimizationRule`,
  `CompilationContext.ruleEnabled`/`switchableOptimizationRules`,
  `CompilerTargetDiagnostics.unknownOptimizationRule`, diagnostic `JULC0043` in
  `diagnostics.json` (generated `DiagnosticCodes`), `pir/ListIndexPromotionPass` gate. No new
  PIR node, no `UplcGenerator` change, no VM change, no generator change.
- `julc-benchmark`: `OptimizationBenchmarkRunner.compareRuleWithJavaAndTruffle` (one level,
  rule off versus on), `OptimizationEvidenceMain.o15*` comparisons,
  `O15ProjectionSharingBenchmarkTest`.
- Decompiler: unchanged. A shared projection is an ordinary strict `Let`; the safe-profile
  program lifts and renders the binding as a local (`ProjectionSharingDecompileTest`).
- Docs: ADR-032 audit row and deferral note, ADR-042 open-question pointer, release notes,
  compiler developer guide, stdlib guide.

## Compatibility and risks

- **Hash impact.** This is the first safe-profile rule whose shape is common in user code:
  default-level output of every program that projects the same field of the same variable
  twice in leading position, or two fields of one variable, changes bytes and hash. Most of
  the external `julc-examples` validators do (66 `txInfo.outputs()` sites in 29 files);
  the evidence document records the default-level diff against the ADR-043 run. Deployed
  scripts are unaffected; a script must be recompiled to change. The pending hash-stability
  policy document on the ADR-032 roadmap gets its first concrete precedent here.
- **Failure contract.** Unchanged at every level for O15 itself; the costed-only ADR-043
  contract now also reaches `x.items().get(i)` accessor chains (see Invariants).
- **Loss bound.** 48,000 CPU / 300 memory per shared binding on a path that reaches no later
  occurrence; asserted per input from the pinned profile, not hard-coded.
- **Budget on failing paths** moves both ways by a few machine steps (the unit now runs before
  the trivial prefix instead of after it); a failing script's budget is not observable
  on-chain.
- **Provenance.** Recorded only when a projection class fires in live code. A program whose
  only repeats sit in an uncalled helper records nothing.
- **Source maps.** Positions of the root scope map to the new `Let`; replaced sites map to
  the variable. Source-map builds skip the UPLC optimiser and are not the deployable artifact.
- **Determinism.** Fresh names are allocated in traversal order; every fixture is compiled
  twice and compared.
- **Direct PIR.** `compilePirToProgram` applies the same pass (ADR-036/038 promise).
- **Public API.** `CompilerOptions.disableOptimizationRule` and
  `getDisabledOptimizationRules`, `CompilationContext.ruleEnabled` and
  `switchableOptimizationRules`, `ValueConversionSharingPass.PROJECTION_RULE`; additive.

## Measurements

Java VM, `cardano-node-11.0.1` PV11 costs, source maps off, before = output at `1fd98c93`
(ADR-043 head) captured into `optimization/o15-pre-change-bytes.txt`, so every delta is O15
alone (at `PV11_COSTED` the goldens already contain O9). Truffle and Scalus budgets are
asserted equal to Java. Full table in `adr/evidence/044-projection-sharing.md`.

| Fixture | Bytes | Path | CPU before → after | Δ CPU |
|---|---:|---|---:|---:|
| REPEATED (three `b.amount()`, four chains with `compareTo`) | 88 → 63 | above | 2,292,387 → 1,447,439 | −844,948 |
| REPEATED | | below | 1,697,580 → 1,299,106 | −398,474 |
| MANUAL (hand-written binding) | 63 → 63 | all | unchanged | 0, same hash as REPEATED after |
| TWO_FIELDS (three distinct fields, prefix only) | 104 → 97 | open/closed | 5,325,409 → 4,848,249 | −477,160 |
| TWO_FIELDS | | fails after the first field | 681,830 → 729,830 | +48,000 (one binding) |
| MIXED (field then prefix) | 100 → 73 | above | 3,051,685 → 1,992,157 | −1,059,528 |
| BOTH_BRANCHES / ALIAS / SINGLE | unchanged | all | unchanged | 0 (not shared) |
| TRACE_FIRST | 48 → 41 | valid | 1,405,754 → 1,007,280 | −398,474, trace order kept |
| CALL_FIRST (shared one scope down) | 53 → 44 | valid | 1,831,161 → 1,384,687 | −446,474 |
| SHADOW (each scope its own) | 66 → 53 | both | 2,633,620 → 1,836,672 | −796,948 |
| LOOP_HOIST | 96 → 87 | three items | 5,499,141 → 4,159,719 | −1,339,422 |
| LOOP_HOIST | | no items | 1,346,940 → 1,346,940 | 0 |
| LOOP_BODY (per iteration) | 90 → 81 | three boxes | 6,089,857 → 4,750,435 | −1,339,422 |
| NESTED (chain on chain, fixed point) | 75 → 57 | valid | 3,480,009 → 2,271,384 | −1,208,625 |
| NESTED_LET | 54 → 47 | valid | 2,308,301 → 1,909,827 | −398,474 |
| CAST (cast root, same hash as REPEATED after) | 90 → 63 | above | 2,340,387 → 1,447,439 | −892,948 |
| BOOL_STRING (Bool and String units, prefix across branches) | 142 → 100 | open, other label | 5,314,602 → 2,946,005 | −2,368,597 |
| BOOL_STRING | | closed | 1,673,735 → 1,769,735 | +96,000 (two bindings) |
| FIELD_THEN_INDEX (safe) | 139 → 130 | three items | 3,411,618 → 2,878,292 | −533,326 |
| LEDGER (`outputs` twice, `fee` per branch) | 132 → 114 | two outputs | 4,964,523 → 4,086,954 | −877,569 |
| LEDGER | | no outputs | 1,860,985 → 1,694,405 | −166,580 |
| SWITCH_BRANCH (projection as scrutinee, pair in a case body, prefix above) | 84 → 70 | spend | 2,339,339 → 1,726,285 | −613,054 |
| MAP_FIELD (`unMapData` arm, prefix) | 111 → 97 | two entries | 4,135,925 → 3,518,992 | −616,933 |
| ERROR_GUARD (shared after the guard) | 55 → 48 | pass | 1,821,072 → 1,422,598 | −398,474 |
| ERROR_GUARD | | guard taken | 762,916 → 762,916 | 0 |
| ESCAPE (root passed to a helper between two sites) | 55 → 48 | valid | 2,091,146 → 1,692,672 | −398,474 |
| CALL_BETWEEN (call first, nothing binds it) | unchanged | all | unchanged | 0 (not shared) |
| TRACE_BETWEEN (trace between two sites) | 51 → 42 | valid | 1,453,754 → 1,007,280 | −446,474, trace order kept |

Validator shape (`@MintingValidator`, `ctx.txInfo().outputs()` twice and `ctx.txInfo().fee()`,
record redeemer root cached by the boundary): 429 → 380 bytes; two outputs 11,627,269 →
8,781,047 CPU (−24%); the empty-outputs path pays the two bindings (+96,000); a malformed
`txInfo` fails identically and cheaper (−208,000).

Every malformed input fails with the same text before and after on all three VMs.

## Implementation milestones

One milestone, delivered on `feat/120-projection-sharing` stacked on ADR-043:

1. Per-rule switch, `JULC0043`, O9 goldens pinned with O15 off; census of every earlier
   golden, hash pin and Blaster fixture (only O9's `FIELD` collides).
2. Fixture sources final, goldens captured at `1fd98c93` from a detached worktree before any
   pass change (the switch alone reproduced them byte-for-byte; the chain-count expectations
   and javadocs were corrected afterwards and are not inputs to the capture). The six
   fixtures added after review were captured the same way, with the first 144 rows
   reproduced byte-for-byte; the matrix test also reproduces every golden row at the final
   commit with the rule switched off.
3. Pass generalisation with the ADR-042 suite byte-identical; dead-binding exclusion and
   recursive-binding leading found by the O8 suite and the handoff fixture respectively.
4. Fixture matrix on Java, Truffle and Scalus; exact cost model; validator shape; direct-PIR
   probes; benchmark comparisons; decompiler check.
5. Independent agent reviews and advisor passes, fixes folded in; full build, Blaster lock
   check, Maven-local publish and external `julc-examples` runs at the default and costed
   levels; stacked PR; release-plan update.

## Verification

- `O15ProjectionSharingTest` (`pair-case-backends`, Java/Truffle/Scalus): 24 fixtures × 4
  levels × source maps off/on against the goldens, each golden reproduced with the rule off;
  provenance; `#field-`/`#fields-` binding counts and chain-unit counts on the emitted PIR;
  every shared binding is a unit and never wraps a lambda; strictly smaller bytes at the safe
  level; the O15-to-O9 handoff at the costed profile; byte identity with the manual binding;
  exact cost model on seven unit shapes (including the map arm); validator shape on a real
  script context; direct-PIR probes (Bool unit, distinct arms, prefix only, chain on chain,
  raw beside decoded, error arm, source positions, `Let`/`Lam`/pattern rebinding, dead and
  transitively dead bindings, dead `LetRec`, single and mutual recursive bindings, live
  helper, round order with O8, each switch, NONE/BASELINE identity). Fixtures cover the
  sealed-switch scrutinee and case body, the map arm, an `error` guard, an escaping root, a
  call between two projections and a trace between two projections.
- `OptimizationConfigurationTest`: switchable ids, `JULC0043`, blank id, O8 off reproduces the
  pre-O8 bytes, disabling an inactive rule is inert.
- `O8ValueSharingTest` with every rule enabled (byte-identical goldens); `O9ListIndexPromotionTest`
  with O15 off; `O5IntegerCaseLoweringTest` unchanged.
- `O15ProjectionSharingBenchmarkTest`: rule off versus on at `PV11_SAFE` with Java and
  Truffle, byte identity with the manual control, strict CPU and memory decrease on every
  successful path of the repeated shape, the binding bound on the ledger shape's empty branch.
- `ProjectionSharingDecompileTest`: the shared projection decompiles as a local.
- `DiagnosticCatalogConsistencyTest`, `verifyDiagnosticCodes`.
- Existing suites: compiler, `pairCaseTest`, benchmark, stdlib, testkit, decompiler, full
  build; Blaster lock (compiles at `baseline`); external `julc-examples` against Maven local
  at the default and costed levels.

## Open questions

- Sharing units that do not lead: exclusive branches that each project the same field
  (`BOTH_BRANCHES`), and loop bodies whose first evaluation is the per-item projection while a
  loop-invariant `txInfo.outputs()` follows. A totality rule would share both (every builtin
  in a chain is total on well-typed Data) at the price of trusting PIR types at runtime, which
  ADR-042/043 decline at the safe profile.
- `ValuesLib` computations (`assetOf` 51 sites, `lovelaceOf` 35 in the corpus) remain the
  largest unshared repeat; they are library calls with their own failure behaviour and need
  either inlining-aware sharing or a library-level rewrite.
- Provenance from the final artifact instead of from the pass. The dead-binding exclusion
  covers the common case (uncalled library methods); a unit shared in a live helper that the
  optimiser later inlines away still records provenance.
- `compareTo` on a projection evaluates its receiver twice by construction; O15 now shares
  it, but the generator could bind the receiver once at every level (issue #146).
- Leading through a multi-binding (Bekić) `LetRec`: sound if that lowering is also a fixed
  number of steps over values, which nobody has verified; single bindings cover every
  generated shape on the corpus.
- Exposing the per-rule switch in the Gradle plugin and CLI. Deliberately not done: the level
  is the supported rollout control and the switch is a review instrument.
- The self-alias `let x = x` the loop lowering emits for pre-loop variables is opaque to the
  pass (ADR-043's promotion sees through it), so projections before and after a for-each form
  two scopes. Sharing across it would be sound; left for a follow-up.
- A field used both raw and decoded in one scope (`b.inner()` passed on and `(BigInteger)
  b.inner()` cast, say) shares each form as its own unit and evaluates the chain once per
  form; re-rooting the decode at the raw binding would need a decode-of-variable unit class.
