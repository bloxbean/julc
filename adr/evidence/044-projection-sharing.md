# ADR-044 evidence: strict-prefix sharing of record field projections (O15)

Everything below was produced on `feat/120-projection-sharing` with Java 25, the repository
Gradle wrapper, and the pinned `cardano-node-11.0.1-plutus-v3-pv11` cost profile
(`40ea9e0b7df77a7bd2cb7d4e4d9da040f8bee7ff0324a7cdb7e51702330e43a8`). Java and Truffle budgets
are asserted equal, Scalus agrees on results, traces, failure text and budgets.

## Provenance

- Base commit `1fd98c93` (ADR-043 head, `feat/115-list-to-array-promotion`). The goldens in
  `julc-compiler/src/test/resources/optimization/o15-pre-change-bytes.txt` (18 fixtures × 4
  levels × source maps off/on = 144 rows) were captured from a detached worktree at that
  commit with the fixture file final, before any pass change; the same capture on the branch
  after the per-rule switch (milestone 1) was byte-identical, which is the switch's inertness
  proof.
- Census before writing the pass: the only earlier golden O15 moves is O9's `FIELD` (a cast
  local with two leading `h.items()` chains; the O9 suite now compiles with O15 off). O5's
  pattern-variable fields reuse the match binders, O8's fixtures have no projections,
  `GoldenUplcTest` and the seven Blaster fixtures compile at `BASELINE`, the benchmark
  aggregate source has no records, and every 56-hex literal in the test trees is an authority
  string or an on-chain reference hash, not a compiled script.
- `ContextsLib`'s uncalled helpers were the first thing the pass shared (O8's `SHARED` fixture
  changed by 5 bytes with source maps on); the transitive dead-binding exclusion followed.
  The `FIELD_THEN_INDEX` handoff fixture did not share at first because each `get` site sits
  inside the recursive `go_get` binding; the recursive-binding-of-lambdas leading rule
  followed.

## Cost model (`sharingCostDerivesFromThePinnedProfile`)

Machine step: 16,000 CPU / 100 memory (lambda, application, variable lookup measured as
`[(λv. v) 1]` minus `1` = 48,000 / 300). Unit costs including the lookup of the root variable
(Java VM, source maps on so the UPLC optimiser does not interfere):

| Unit | CPU | Memory |
|---|---:|---:|
| `sndPair(unConstrData(x))` (fields prefix) | 278,580 | 764 |
| `unIData(headList(P))` (integer, depth 0) | 462,474 | 1,328 |
| `unListData(headList(tailList(P)))` (list, depth 1) | 597,326 | 1,660 |
| `unBData(headList(tailList²(P)))` (bytes, depth 2) | 721,198 | 1,992 |
| `headList(tailList⁵(P))` (raw, depth 5) | 1,058,045 | 2,756 |
| `equalsInteger(fstPair(unConstrData(headList(tailList³(P)))), 1)` (Bool, depth 3) | 1,177,535 | 3,157 |

Asserted exactly for every shape at 2 sites (and 3 for the integer field and the prefix):
`after = before + (2 + k)·16,000 − (k − 1)·unit` for CPU, and with 100 and the unit's memory
for memory. Loss bound on a path that reaches only the leading site: 48,000 / 300 per binding.

## Fixture matrix (`safeProfileSharesLeadingProjectionsAndStaysObservationallyEquivalentOnEveryBackend`)

Safe profile, source maps off. Bytes and hashes before → after; CPU/memory per input. Every
row's failure text is identical before and after on Java, Truffle and Scalus, except the
costed-only `FIELD_THEN_INDEX` out-of-range rows, which carry ADR-043's `IndexArray` text at
`PV11_COSTED` (the O15 binding makes the list a proven variable for O9).

| Fixture | Bytes | Hash before → after |
|---|---:|---|
| REPEATED | 88 → 63 | `f331bc3a…` → `c357d6f4…` |
| MANUAL | 63 → 63 | `c357d6f4…` (= REPEATED after) |
| TWO_FIELDS | 104 → 97 | `bd466d59…` → `7849460e…` |
| MIXED | 100 → 73 | `51eeee26…` → `68677812…` |
| BOTH_BRANCHES | 57 → 57 | unchanged |
| TRACE_FIRST | 48 → 41 | `011c627e…` → `8d738dc4…` |
| CALL_FIRST | 53 → 44 | `b21a9d47…` → `1da8cb2a…` |
| ALIAS | 32 → 32 | unchanged |
| SHADOW | 66 → 53 | `a4a078e9…` → `063ea4b7…` |
| LOOP_HOIST | 96 → 87 | `556cedad…` → `768af50a…` |
| LOOP_BODY | 90 → 81 | `6f15e833…` → `da0ae331…` |
| NESTED | 75 → 57 | `b08fdaa9…` → `65168d93…` |
| NESTED_LET | 54 → 47 | `d86c56c4…` → `bcb11153…` |
| CAST | 90 → 63 | `f675f505…` → `c357d6f4…` (= REPEATED after) |
| BOOL_STRING | 142 → 100 | `522b9348…` → `d6c15849…` |
| FIELD_THEN_INDEX | 139 → 130 | `7355343a…` → `b2164abc…` |
| SINGLE | 20 → 20 | unchanged |
| LEDGER | 132 → 114 | `5c277897…` → `b7fc07c2…` |

| Fixture | Input | CPU before → after | Δ CPU | Δ memory |
|---|---|---:|---:|---:|
| REPEATED | above | 2,292,387 → 1,447,439 | −844,948 | −2,156 |
| REPEATED | below | 1,697,580 → 1,299,106 | −398,474 | −928 |
| REPEATED | not-a-record / empty-record / bad-amount (fail) | | −112,000 | −700 |
| MANUAL | all | unchanged | 0 | 0 |
| TWO_FIELDS | open / closed | 5,325,409 → 4,848,249 | −477,160 | −1,028 |
| TWO_FIELDS | not-a-record (fail) | 456,688 → 312,688 | −144,000 | −900 |
| TWO_FIELDS | empty-record / bad-amount (fail after the first field) | | +48,000 | +300 |
| MIXED | above | 3,051,685 → 1,992,157 | −1,059,528 | −2,520 |
| MIXED | below | 1,532,372 → 1,181,898 | −350,474 | −628 |
| MIXED | fails | | −192,000 / −64,000 | |
| BOTH_BRANCHES | all | unchanged | 0 | 0 |
| TRACE_FIRST | open / closed | 1,405,754 → 1,007,280 | −398,474 | −928 |
| TRACE_FIRST | fails | | −16,000 | −100 |
| CALL_FIRST | above / below | 1,831,161 → 1,384,687 | −446,474 | −1,228 |
| CALL_FIRST | fails (before the shared scope) | unchanged | 0 | 0 |
| ALIAS | all | unchanged | 0 | 0 |
| SHADOW | both | 2,633,620 → 1,836,672 | −796,948 | −1,856 |
| SHADOW | other-bad-amount (fail in helper) | 1,405,048 → 1,437,048 | +32,000 | +200 |
| SHADOW | b-not-a-record / both-bad (fail) | | −64,000 | −400 |
| LOOP_HOIST | three | 5,499,141 → 4,159,719 | −1,339,422 | −3,684 |
| LOOP_HOIST | none / not-a-record / bad-element | unchanged | 0 | 0 |
| LOOP_BODY | three | 6,089,857 → 4,750,435 | −1,339,422 | −3,684 |
| LOOP_BODY | none | unchanged | 0 | 0 |
| LOOP_BODY | second-not-a-record (fail in iteration 2) | 3,198,851 → 2,592,377 | −606,474 | −2,228 |
| LOOP_BODY | bad-amount (fail) | 1,666,940 → 1,506,940 | −160,000 | −1,000 |
| NESTED | open / closed | 3,480,009 → 2,271,384 | −1,208,625 | −2,720 |
| NESTED | not-a-record / empty-record (fail) | | −176,000 | −1,100 |
| NESTED | bad-inner (fail) | 1,434,733 → 1,402,733 | −32,000 | −200 |
| NESTED_LET | open | 2,308,301 → 1,909,827 | −398,474 | −928 |
| NESTED_LET | not-a-record | unchanged | 0 | 0 |
| NESTED_LET | bad-inner | | −16,000 | −100 |
| CAST | above | 2,340,387 → 1,447,439 | −892,948 | −2,456 |
| CAST | below | 1,745,580 → 1,299,106 | −446,474 | −1,228 |
| CAST | fails | | −160,000 | −1,000 |
| BOOL_STRING | open-expected | 2,859,217 → 2,740,637 | −118,580 | +236 |
| BOOL_STRING | open-other | 5,314,602 → 2,946,005 | −2,368,597 | −5,583 |
| BOOL_STRING | closed (one site of each shared unit) | 1,673,735 → 1,769,735 | +96,000 | +600 |
| BOOL_STRING | not-a-record (fail) | 952,788 → 632,788 | −320,000 | −2,000 |
| BOOL_STRING | empty-record / bad-open (fail) | | +64,000 | +400 |
| BOOL_STRING | bad-label (fail) | 2,734,259 → 2,535,679 | −198,580 | −264 |
| FIELD_THEN_INDEX | three / one | 3,411,618 → 2,878,292 | −533,326 | −1,260 |
| FIELD_THEN_INDEX | none (fails at the first get) | 1,388,909 → 1,436,909 | +48,000 | +300 |
| FIELD_THEN_INDEX | not-a-record / bad-items (fail) | | −288,000 | −1,800 |
| SINGLE | all | unchanged | 0 | 0 |
| LEDGER | two-outputs | 4,964,523 → 4,086,954 | −877,569 | −1,956 |
| LEDGER | no-outputs | 1,860,985 → 1,694,405 | −166,580 | −64 |
| LEDGER | not-a-record (fail) | 520,688 → 312,688 | −208,000 | −1,300 |
| LEDGER | empty-record (fail) | 744,343 → 760,343 | +16,000 | +100 |

Notes. `REPEATED`'s three source projections are four chains in PIR because `compareTo`
evaluates its receiver twice; all four become one. `CAST` after sharing is the same program as
`REPEATED` after sharing (same hash, same budgets). `CALL_FIRST` and the loops save 48,000 more
than the model because the UPLC optimiser inlines a local that became a single-use alias of
the shared binding. Failing paths move by a few machine steps because the unit now runs before
the trivial prefix instead of after it.

## Validator shape (`validatorSharesNestedContextProjectionsAndKeepsItsOutcome`)

`@MintingValidator` with a record redeemer and `ctx.txInfo().outputs()` twice plus
`ctx.txInfo().fee()`; O15 on versus off at the safe profile, same script context:

| Input | CPU before → after | Δ CPU | Memory before → after |
|---|---:|---:|---:|
| two-outputs | 11,627,269 → 8,781,047 | −2,846,222 | 41,838 → 34,970 |
| no-outputs | 5,414,406 → 5,510,406 | +96,000 (two bindings) | 21,279 → 21,879 |
| no-outputs-nonzero | 5,398,406 → 5,494,406 | +96,000 | 21,179 → 21,779 |
| malformed-tx-info (fail) | 4,455,616 → 4,247,616 | −208,000 | 19,084 → 17,784 |

FLAT 429 → 380 bytes. No shared binding roots at the redeemer (the boundary already binds its
fields once).

## Benchmark (`O15ProjectionSharingBenchmarkTest`, PV11_SAFE with O15 off → on, Java = Truffle)

| Comparison | Bytes | Case | CPU | Memory |
|---|---:|---|---:|---:|
| o15-projection-sharing (REPEATED) | 88 → 63 | above | 2,292,387 → 1,447,439 | 8,121 → 5,965 |
| | | below | 1,697,580 → 1,299,106 | 6,292 → 5,364 |
| | | not-a-record / bad-amount (fail) | −112,000 | −700 |
| o15-projection-manual-control | 63 → 63 (hash = REPEATED candidate) | all | unchanged | unchanged |
| o15-projection-ledger | 132 → 114 | two-outputs | 4,964,523 → 4,086,954 | 17,470 → 15,514 |
| | | no-outputs | 1,860,985 → 1,694,405 | 5,748 → 5,684 |
| | | not-a-record (fail) | 520,688 → 312,688 | 3,232 → 1,932 |

Candidate script hashes: REPEATED `c357d6f4f2bcfdf19c23029001dcb7dd87b37a8cfed9e48680d9db16`
(= manual), LEDGER `b7fc07c256030c0d90719a748869077439e7bd0eed530a99c2206a83`.

## Structural probes (`directPirSharingMatchesUnitsRespectsRebindingAndSkipsDeadBindings`)

- Bool unit: the shared value is the whole `equalsInteger(fstPair(unConstrData(H)), 1)` form;
  one chain unit remains, no prefix binding; evaluated on `BOX_OPEN`, `BOX_CLOSED`,
  `NOT_A_RECORD`, `EMPTY_RECORD` against BASELINE on three VMs.
- Distinct arms over one raw field: no `#field-` binding (a raw-chain unit would have been
  bound), the prefix is shared and both decodes survive.
- Two distinct fields: `#fields-0` bound to `sndPair(unConstrData(x))`, no prefix occurrence
  left in the body, both chains re-rooted.
- Chain on chain: `#field-0` = inner raw projection, then `#fields-0` = prefix of `#field-0`,
  three units after.
- Rebinding under `Let`, `Lam` and a `DataMatch` binder named `x`: unchanged; a leading pair
  followed by a rebinding shares the pair only.
- Dead lambda binding, transitively dead pair of bindings, dead `LetRec`: unchanged, no
  provenance; the same helper once called shares inside its body and records provenance.
- Round order: `unValueData(raw(x,1))` twice becomes `#field-0` then `#value-0`, both rules
  recorded; with `pv11.o15.projection-sharing` off nothing changes and nothing is recorded;
  with `pv11.o8.value-sharing` off the projection is shared and both `unValueData`
  applications remain.
- NONE and BASELINE return the same instance.

## Decompiler (`ProjectionSharingDecompileTest`)

BASELINE: four `unConstrData` applications inline; PV11_SAFE: one, rendered as
`var n = Builtins.unIData(Builtins.headList(Builtins.sndPair(Builtins.unConstrData(i2))));`
and used at every site.

## Repository validation

Recorded in the pull request after the final commit: full build, Blaster lock check, Maven-local
publish, external `julc-examples` at the default and costed levels with the per-validator
size/hash diff against the ADR-043 run.
