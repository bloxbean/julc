# ADR-043 evidence: cost-directed list-to-array promotion (O9)

Companion to `adr/043-list-to-array-promotion.md`. Every number below was produced by the
tests named, on the Java VM under `cardano-node-11.0.1-plutus-v3-pv11` costs; Truffle budgets
are asserted equal to Java, Scalus agrees on result, traces, failure class and budgets.

## Provenance

- Base commit `940dc65b` (ADR-042 head, `feat/114-value-conversion-motion`).
- Goldens `julc-compiler/src/test/resources/optimization/o9-pre-change-bytes.txt`: 23
  fixtures × 4 levels × source maps off/on = 184 rows, captured from a detached worktree at
  the base commit with the final fixture sources (`O9ListIndexFixtures`) and a throwaway
  printing test (the cast, alias and loop-state fixtures were captured the same way after the
  reviews, and the `CALLBACK_HELPER`, `HELPER_CALLERS` and `HELPER` fixtures after the PR #144
  review; on each later capture every earlier row was reproduced byte-identically). At the
  base, `PV11_COSTED` rows equal `PV11_SAFE` rows for every fixture (no costed-only rule
  existed), so the costed "before" is the safe output.
- Tests: `O9ListIndexPromotionTest` (`:julc-compiler:pairCaseTest`, Java/Truffle/Scalus),
  `O9DropListAlternativeTest` (`:julc-compiler:test`, Java),
  `O9ListIndexPromotionBenchmarkTest` (`:julc-benchmark:test`, Java/Truffle),
  `ListIndexPromotionDecompileTest` (`:julc-decompiler:test`).

## Cost model (measured, `breakEvenDerivesFromThePinnedCostProfile`)

Program differences at the safe profile so the shared list expression cancels:

| Quantity | CPU | Memory |
|---|---:|---:|
| recursive `get` site at index `i`, with its `unIData` decode | 620,227 + 683,204·i | |
| `ListToArray` of `n` elements | 49,000 + 24,838·n | 307 + n |
| one `IndexArray` site with the same decode (312,010 without it: the decode is 52,744) | 364,754 | |
| array binding (lambda, application, lookup) | 48,000 | 300 |
| in-program per-site delta (`364,754 − 620,227`) | −255,473 | |

Whole-program deltas (costed minus safe) of `let xs = list(n) in get(xs, i₁) + …`, all
reproduced exactly by `97,000 + 24,838·n − 255,473·k − 683,204·ΣI`:

| n | (0,0) | (0,1) | (1,2) | (3,7) | (0,1,2) | (2,5,7) | (0,1,2,3) |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 2 | −364,270 | −1,047,474 | | | | | |
| 8 | −215,242 | −898,446 | −2,264,854 | −7,047,282 | −2,520,327 | −10,035,571 | −4,825,412 |
| 32 | +380,870 | −302,334 | −1,668,742 | −6,451,170 | −1,924,215 | −9,439,459 | −4,229,300 |
| 64 | +1,175,686 | +492,482 | −873,926 | −5,656,354 | −1,129,399 | −8,644,643 | −3,434,484 |
| 128 | +2,765,318 | +2,082,114 | +715,706 | −4,066,722 | +460,233 | −7,055,011 | −1,844,852 |

Largest list length that still saves CPU: (0,0) 16; (0,1) 44; (1,2) 99; (0,7) 209; (3,7) 291;
(0,1,2) 109; (1,2,3) 191; (0,1,2,3) 202.

## Fixture matrix (`costedProfilePromotesRepeatedIndexingAndEveryOtherLevelKeepsItsBytes`)

Before = golden (safe = costed at the base), after = `PV11_COSTED`; CPU/memory. Failure texts
are the Java/Truffle wording; Scalus wording is matched by pattern with the same index and
length.

| Fixture | Bytes | Hash before → after | Input | Before | After |
|---|---:|---|---|---:|---:|
| TWO | 134 → 51 | 16dfd7a2… → 500725e4… | 0-1 | 2,748,387 / 13,063 | 1,849,941 / 6,441 |
| | | | 7-7 | 11,630,039 / 49,918 | 1,849,941 / 6,441 |
| | | | 64-0-63 | 45,107,035 / 188,833 | 3,240,869 / 6,497 |
| | | | 7-8 (fails) | 12,191,291 `HeadList: empty list` | 1,727,989 `IndexArray: index 8 out of bounds for array of size 8` |
| | | | neg-first (fails) | 6,819,149 `TailList: empty list` | 1,347,235 `IndexArray: index -1 out of bounds for array of size 8` |
| | | | empty (fails) | 1,307,004 `HeadList: empty list` | 1,148,531 `IndexArray: index 0 out of bounds for array of size 0` |
| | | | huge 2^40 (fails) | 7,455,376 `TailList: empty list` | 1,727,989 `IndexArray: index out of range: 1099511627776` |
| MANUAL | 51 → 51 | 500725e4… (= TWO after) | all | unchanged | unchanged |
| LOOP | 307 → 229 | 29825563… → 2a964398… | three-requests | 26,396,255 / 110,706 | 15,765,779 / 59,035 |
| | | | no-requests | 3,061,156 / 13,977 | 3,453,860 / 15,199 |
| | | | one-request-64 | 8,334,441 / 35,825 | 8,948,761 / 29,867 |
| | | | one-request-0 | 7,651,237 / 32,990 | 7,557,833 / 29,811 |
| | | | index-9 (fails) | 10,821,465 `TailList` | 5,215,916 `IndexArray: index 9 … size 8` |
| | | | index-neg (fails) | 16,777,954 `TailList` | 9,319,889 `IndexArray: index -1 … size 8` |
| EACH | 132 → 93 | 53e24b0a… → 3aec2cd1… | three | 5,631,859 / 26,495 | 5,161,144 / 20,507 |
| | | | none | 958,399 / 5,396 | 1,254,103 / 6,011 |
| | | | empty-list (fails) | 2,074,626 `HeadList` | 1,916,153 `IndexArray: index 0 … size 0` |
| FIELD | 192 → 109 | b6dbadbd… → be479372… | four | 5,352,135 / 22,060 | 4,354,337 / 15,434 |
| | | | two-accessor-fails | 5,230,183 `HeadList: empty list` | 4,182,709 `HeadList: empty list` (unpromoted site, same text) |
| | | | one (fails) | 2,674,677 `HeadList` | 1,602,365 `IndexArray: index 1 … size 1` |
| | | | empty (fails) | 1,355,246 `HeadList` | 1,196,773 `IndexArray: index 0 … size 0` |
| BRANCH | 205 → 122 | c5b5d90d… → 73fa7720… | then | 2,909,715 / 12,964 | 2,011,269 / 6,342 |
| | | | else (trace) | 8,398,412 / 34,145 | 8,398,412 / 34,145 |
| | | | then-one (fails) | 2,787,763 `HeadList` | 1,715,451 `IndexArray: index 1 … size 1` |
| | | | else-empty | 1,450,780 / 6,961 | 1,450,780 / 6,961 |
| TRACE_BETWEEN | 135 → 52 | f206ad75… → 73393d23… | eight | 2,590,397 / 11,931 | 1,691,951 / 5,309 |
| | | | one (fails after trace) | 2,468,445 `HeadList` | 1,396,133 `IndexArray: index 1 … size 1` |
| | | | empty (fails before trace) | 897,516 `HeadList` | 739,043 `IndexArray: index 0 … size 0` |
| GUARDED | 190 → 107 | 888de553… → 9cfb3b78… | eight-match / eight-mismatch | 10,199,331 / 42,449 | 9,300,885 / 35,827 |
| | | | short | 1,914,954 / 9,095 | 1,914,954 / 9,095 |
| | | | empty | 1,046,500 / 5,697 | 1,046,500 / 5,697 |
| ESCAPE | 187 → 104 | 13c2b96d… → e1da31d8… | three | 5,471,695 / 25,731 | 4,449,059 / 19,104 |
| | | | one (fails) | 3,804,973 `HeadList` | 2,732,661 `IndexArray: index 1 … size 1` |
| | | | empty (fails) | 1,611,949 `HeadList` | 1,453,476 `IndexArray: index 0 … size 0` |
| SHADOW | 188 → 105 | 3ee091d7… → 36998218… | both | 3,438,267 / 15,898 | 2,390,793 / 9,270 |
| | | | helper-empty | 1,895,676 `HeadList: empty list` | 1,786,879 `HeadList: empty list` (helper's own site) |
| | | | outer-one (fails) | 3,316,315 `HeadList` | 2,244,003 `IndexArray: index 1 … size 1` |
| NESTED | 116 → 33 | c6f679ae… → a078965d… | three | 2,189,691 / 10,397 | 1,167,055 / 3,770 |
| | | | self | 1,506,487 / 7,562 | 1,117,379 / 3,768 |
| | | | one (fails) | 2,215,460 `TailList` | 1,096,635 `IndexArray: index 3 … size 1` |
| | | | empty (fails) | 1,169,516 `HeadList` | 819,043 `IndexArray: index 0 … size 0` |
| EXCLUSIVE | 136 → 53 | a5f51a84… → e6f2f981… | then (index 0) | 1,441,076 / 6,762 | 1,481,307 / 5,176 |
| | | | else (index 1) | 2,124,280 / 9,597 | 1,481,307 / 5,176 |
| | | | then-64 | 1,441,076 / 6,762 | 2,872,235 / 5,232 |
| | | | else-one (fails) | 2,103,536 `HeadList` | 1,286,697 `IndexArray: index 1 … size 1` |
| CALLBACK | 237 → 198 | e6540feb… → 22457d0d… | match | 10,488,249 / 47,322 | 9,868,506 / 41,328 |
| | | | no-ys | 1,873,751 / 10,224 | 1,995,589 / 10,832 |
| | | | empty-xs (fails) | 2,588,262 `HeadList` | 2,429,789 `IndexArray: index 0 … size 0` |
| SINGLE | 73 → 73 | e4216b39… (unchanged) | all | unchanged | unchanged |
| CAST_LET | 140 → 140 | f40226bf… (unchanged) | int-data-no-index / list-data-no-index | 701,510 / 3,734 | 701,510 / 3,734 |
| | | | list-data-index (fails) | 1,152,660 `HeadList: expected list, got VCon(Data[])` | same text |
| CAST_HELPER | 145 → 145 | 24c835ca… (unchanged: the call with the cast local refutes the helper's parameter) | int-data-no-index / list-data-no-index | 797,510 / 4,334 | 797,510 / 4,334 |
| | | | list-data-index (fails) | 1,248,660 `HeadList: expected list, got VCon(Data[])` | same text |
| CAST_ALIAS | 140 → 140 | f40226bf… (unchanged; the optimiser inlines the alias, so the artifact is CAST_LET's) | int-data-no-index / list-data-no-index | 701,510 / 3,734 | 701,510 / 3,734 |
| | | | list-data-index (fails) | 1,152,660 `HeadList: expected list, got VCon(Data[])` | same text |
| CAST_LOOP_ALIAS | 212 → 212 | 04ce36bb… (unchanged) | int-data-no-index / list-data-no-index | 2,986,646 / 15,130 | 2,986,646 / 15,130 |
| | | | int-data-empty-loop | 1,441,876 / 7,798 | 1,441,876 / 7,798 |
| | | | list-data-index (fails) | 3,501,796 `HeadList: expected list, got VCon(Data[])` | same text |
| ALIAS | 137 → 51 | 49ead651… → 500725e4… | 0-1 | 2,796,387 / 13,363 | 1,849,941 / 6,441 |
| | | | 7-7 | 11,678,039 / 50,218 | 1,849,941 / 6,441 |
| | | | 64-0-63 | 45,155,035 / 189,133 | 3,240,869 / 6,497 |
| | | | 7-8 (fails) | 12,239,291 `HeadList` | 1,727,989 `IndexArray: index 8 … size 8` |
| | | | neg-first (fails) | 6,867,149 `TailList` | 1,347,235 `IndexArray: index -1 … size 8` |
| | | | empty (fails) | 1,355,004 `HeadList` | 1,148,531 `IndexArray: index 0 … size 0` |
| | | | huge (fails) | 7,503,376 `TailList` | 1,727,989 `IndexArray: index 1099511627776 … size 8` |
| CAST_LOOP_STATE | 204 → 204 | 767cef35… (unchanged: the initial call refutes the loop's parameter, and its result with it) | int-data-empty-loop-no-index | 1,393,876 / 7,498 | 1,393,876 / 7,498 |
| | | | list-data-empty-loop-index (fails) | 1,845,026 `HeadList: expected list, got VCon(Data[])` | same text |
| | | | int-data-loop (fails in the loop body) | 1,653,615 `MkCons: expected list, got VCon(Data[])` | same text |
| CALLBACK_HELPER (the PR #144 review shape) | 206 → 206 | 3eaa1e66… (unchanged: the call from the callback refutes the parameter) | no-index (`[[10, 20]]`, k = 0) | 2,081,122 / 10,394 | 2,081,122 / 10,394 |
| | | | no-groups | 953,210 / 5,396 | 953,210 / 5,396 |
| | | | index-data-element (fails: the callback element is Data) | 2,088,176 `HeadList: expected list, got VCon(Data[])` | same text |
| HELPER_CALLERS | 328 → 328 | f922b505… (unchanged: one refuting caller is enough) | direct-1 / direct-2 / direct-0-no-groups | 6,364,932 / 10,365,138 / 2,724,369 | identical |
| | | | direct-out-of-range (fails at the helper's own site) | 2,420,063 `HeadList: empty list` | same text |
| HELPER (positive control: every caller passes a decoded list) | 150 → 67 | 8cf34374… → 00ea9e7e… | eight-1 | 5,247,650 / 24,132 | 3,450,758 / 10,888 |
| | | | eight-7 | 13,446,098 / 58,152 | 3,450,758 / 10,888 |
| | | | sixty-four-0 (two conversions of 64, two index-0 sites each) | 3,881,242 / 18,462 | 6,232,614 / 11,000 (+2,351,372 = 2 × (97,000 + 24,838·64 − 2·255,473), exactly the model) |
| | | | eight-8 (fails) | 6,969,825 `HeadList` | 1,544,424 `IndexArray: index 8 … size 8` |
| | | | second-list-short (fails at the second call) | 9,217,204 `TailList` | 2,697,816 `IndexArray: index 7 … size 2` |
| | | | empty (fails) | 1,504,193 `HeadList` | 1,345,720 `IndexArray: index 0 … size 0` |

Per-input budget expectations asserted: `SAVES` paths strictly cheaper in CPU and no higher in
memory; `PAYS` paths within `Σ (49,000 + 24,838·n + 48,000)` CPU and `Σ (307 + n + 300)`
memory over the lists converted on that path; `UNTOUCHED` paths byte-identical budgets;
failing paths compared on text only. Truffle and Scalus budgets are asserted equal to Java
on every input. No input diverges any more: the earlier `DIVERGES_AT_CONVERSION` expectation
(the typing-trust exposure through a helper or a loop's state) was removed with the PR #144
fix, and the two fixtures that carried it are now untouched at every level.

## Benchmark (`O9ListIndexPromotionBenchmarkTest`, safe → costed, Java = Truffle)

Request loop (sixteen inputs, seventeen outputs, `r` requests with descending indexes; three
lists promoted):

| Requests | CPU safe | CPU costed | Δ |
|---:|---:|---:|---:|
| 0 | 3,263,089 | 4,373,743 | +1,110,654 |
| 1 | 9,535,017 | 9,220,886 | −314,131 |
| 2 | 17,856,557 | 14,068,029 | −3,788,528 |
| 4 | 40,648,473 | 23,762,315 | −16,886,158 |
| 8 | 110,827,649 | 43,150,887 | −67,676,762 |
| 16 | 349,567,377 | 81,928,031 | −267,639,346 |

Two sites: the costed program's hash and FLAT size equal the manual `toArray()` control's,
and the control is identical between the safe and costed profiles.

## Structural and guard probes (`directPirPromotionRespectsScopesShadowingLoopsAndPlacement`)

- `Let`-bound list, two sites: `let xs = … in let #array-0 = [listToArray xs] in …`; NONE,
  BASELINE and PV11_SAFE return the identical term object.
- Parameter chain `λxs. λflag. if flag then sites else −1`: the binding lands in the `then`
  branch, inside both lambdas.
- Exclusive branches with one site each: promoted above the conditional (static count).
- One site inside a callback lambda passed to an unknown function: untouched. Two sites: the
  binding wraps the lambda (built once, not per call).
- One site inside a `LetRec` binding: promoted, binding above the `LetRec`.
- Inner `let xs = ys`: outer pair promoted, inner site kept, inner site reads the inner list
  (result 17 = 0 + 10 + 7).
- Match field bound to a list: promoted inside the branch body (result 10).
- Index containing a site of another list: both lists promoted (result 85).
- Trace before the sites: binding inside the trace body; traces identical on all VMs.
- An `Error` in one branch: the binding sinks into the indexing branch; both paths equivalent.
- Post-loop self-alias (`let xs = xs`, as the loop lowering emits): one conversion serves the
  sites inside the loop and after it (result 140 = 60 + 70 + 10).
- A `Let` bound to a bare Data term (the lowering of a cast to `JulcList`) is not promoted
  (`assertSame`), and its non-indexing path keeps succeeding; the same sites behind a decode
  promote.
- List-typed aliases (`listTypedAliasesInheritTheProofOfTheirBinding`): an unproven cast
  local self-aliased as the loop lowering does, copied into a list-typed alias, and copied
  twice: the pass returns the identical term object and the non-indexing path returns −1 at
  both levels on all three VMs. The same aliases of a parameter (array bound below the alias,
  result 10), of a decode through two copies (10) and of a list-match tail (16) promote. A
  copy of a list-match head, a pattern variable, a non-list match field, a pair-match
  component or a recursive binding that shadows a proven name is untouched. A callback
  lambda's list-typed parameter (the lambda in argument position) is untouched, while the
  identical lambda as the root term or as a recursive binding promotes (result 10). The
  `CAST_LOOP_ALIAS` fixture's safe PIR holds exactly one list-typed `let xs = xs` after the
  loop and two recursive sites, so its byte identity at the costed level rests on the rule.
- Call-site provenance (`helperParametersAndReturnsCarryCallSiteProvenance`, direct PIR, the
  PR #144 fix): the review shape (a callback's list-typed parameter passed into a helper), one
  proven caller plus one callback caller, and a helper passed as a value are untouched
  (`assertSame`) and return −1 on the never-indexing path at both levels on all three VMs
  (−2 for the mixed pair); a helper whose two callers both pass decoded lists promotes once
  inside the helper (20); a list-typed result carries its argument's proof (`id d` untouched,
  `id list` promoted, 10); under-application refutes the parameters it leaves unbound
  (`pick false` then `g d`: untouched, −1) and keeps the supplied one (`pick list` then
  `g true`: promoted, 10); a recursive helper fed its own tail keeps its proof (promoted, 30)
  and is refuted by a Data entry call (untouched, −1); mutual recursion through one `LetRec`
  proves both (two arrays, 30) or refutes both (−1), and a callee fed only `tailList` keeps its
  proof while its caller's own list is refuted (one array, −1); a chain read through a let
  between its lambdas counts both parameters (promoted, 10); two binders under one name and
  parameter list share one record (untouched, 10) while distinct parameter names do not (one
  array, 10).
- The review's spending validator (`callbackToHelperCompositionKeepsValidatorAcceptanceAtTheCostedLevel`):
  `Input(groups, mode)` redeemer, `input.groups().any(xs -> helper(xs, mode))` with a helper
  that indexes twice behind conditionals. Rule inert at the costed level, costed bytes equal
  safe bytes, and with `[[10, 20]]` at mode 0 both levels accept with the same budget on
  Java, Truffle and Scalus. The well-typed counterpart (`JulcList<BigInteger> xs` redeemer
  passed to the same helper) promotes one array with no recursive site left, accepts `[0, 0]`
  at both levels, and fails `[0]` with `HeadList: empty list` at the safe level and
  `IndexArray: index 1 out of bounds for array of size 1` at the costed level.
- Every rebinding binder kind inside a promoted scope (`everyRebindingBinderKindIsOpaqueToTheOuterBinding`):
  lambda parameter (17), match field (17), match pattern variable (both levels fail at the
  inner site with `HeadList: expected list, got VCon(Data[])`), list-match tail (19),
  pair-match field (17), recursive binding (both levels fail with `HeadList: expected list,
  got VLam`); in every case the outer pair promotes, the inner site stays recursive, and with a
  single outer site nothing is promoted. Removing any rebinding guard changes one of these
  results or promotes a once-indexed list.
- Strict-boundary validator (`validatorObservesTheIndexFailureContractThroughTheStrictBoundary`):
  `@Param JulcList<BigInteger> allowed`, `BigInteger index` redeemer; index 7 succeeds at
  both levels; 8, 99 and −1 fail with `HeadList`/`TailList: empty list` at the safe level and
  `IndexArray: index I out of bounds for array of size 8` at the costed level on Java, Truffle
  and Scalus.
- Validator shapes: `@Param` list indexed in a helper and in the entry point, datum record
  field bound to a local: two array bindings, four sites rewritten, no free variables at
  every level; `@Param` list via `compileMethod` equivalent on all three VMs including the
  empty and one-element failures.

## The recognised shape

`arrayBindingsConvertVariablesAndRewrittenSitesAreIndexArrayOnly` pins that the registry's
`get` lowering is exactly the shape the pass recognises (`PirHelpers.RECURSIVE_LIST_GET`),
that every `#array-` binding converts a variable, and that every rewritten site is a
two-argument `IndexArray` on such a binding.

## `DropList` alternative (measured, not adopted; `O9DropListAlternativeTest`; decision filed as [#143](https://github.com/bloxbean/julc/issues/143))

`compileMethod` programs with `BigInteger` indexes on eight elements, Java VM, safe profile
(the harness asserts the affine shapes, the element identity for i = 0 … 7 and every failure
text below):

| Form | CPU | FLAT bytes | i = 8 | i = 9 | i = −1 |
|---|---:|---:|---|---|---|
| `xs.drop(i).head()` | 843,382 + 1,957·i | 31 | `HeadList: empty list` | `HeadList: empty list` | **succeeds with element 0** (`DropList` ignores a negative count) |
| with `i.compareTo(ZERO) < 0` guard | 1,274,295 + 1,957·i | 60 | `HeadList: empty list` | `HeadList: empty list` | `Error term encountered` |
| `xs.get(i)` (recursive) | 1,083,004 + 683,204·i | 73 | `HeadList: empty list` | `TailList: empty list` | `TailList: empty list` |

An earlier one-off probe with `long` indexes and a bare `lessThanInteger` guard measured
790,638 + 1,957·i, 931,928 + 1,957·i and 1,030,260 + 683,204·i (29/37/71 bytes); the guard's
cost depends on how the comparison is lowered.

## Repository validation

Three rounds. Round three (2026-09-15, uncommitted at the time of writing) follows the PR #144
review fix (call-site provenance): `:julc-compiler:test` 1,618 tests, `:julc-compiler:pairCaseTest`
49 tests (`O9ListIndexPromotionTest` 12, all 23 fixtures at 4 levels × source maps off/on on
Java, Truffle and Scalus), `:julc-decompiler:test` 108, `:julc-benchmark:test` 128, all with
`--rerun`, 0 failures; every fixture that promoted before the fix reproduces the hash in the
table above, and the four fixtures that carry an unproven list into a helper or a loop
(`CAST_HELPER`, `CAST_LOOP_STATE`, `CALLBACK_HELPER`, `HELPER_CALLERS`) are byte-identical to
their goldens at every level. The pass is gated off below the costed level before the analysis
runs, so the default-level corpus and the Blaster lock (compiled at `baseline`) are unaffected
by construction and were not re-run in this round. The two earlier rounds:

 Round one at commit `1301298` (the pass with the proven-name environment, the
alias fixtures, goldens and docs). Round two at `d6d44b6`, after the callback-parameter rule,
the applied-lambda rule and the `CAST_LOOP_STATE` fixture: Blaster, full build, publish and
the costed examples run again; the default-level examples run was not repeated because the
pass is inert below `pv11-costed` and the goldens pin that for every fixture. The final commit
adds only this section's text.

| Step | Round one (`1301298`) | Round two (`d6d44b6`) |
|---|---|---|
| `./gradlew build --rerun --continue -PskipSigning=true` | exit 0 (every module's tests, `pairCaseTest` included) | exit 0 |
| `:julc-compiler:test`, `:julc-compiler:pairCaseTest`, `:julc-decompiler:test`, `:julc-benchmark:test` (`--rerun`) | 1618, 47, 108, 128 tests; 0 failures | 1618, 47, 108, 128 tests; 0 failures |
| Blaster lock (`verification/blaster/scripts/prepare-artifacts.sh`) | lock and every `compiledCode.hex` identical (the fixtures compile at `baseline`, where O9 is inert) | identical |
| Docs site (`astro build`) | 32 pages, exit 0 | 32 pages, exit 0 |
| `publishToMavenLocal` | `0.1.0-pre17-1301298-SNAPSHOT` | `0.1.0-pre17-d6d44b6-SNAPSHOT` |
| External `julc-examples` at the default level (`adr/evidence/041-local-examples.init.gradle`) | 418 tests: 352 passed, 11 skipped (pre-existing), 55 failed. Every failure is a step of a Yaci DevKit `*IntegrationTest` (`InsufficientBalanceException`, `No UTXOs found`): the local devnet had stopped producing blocks (tip 34198, last block about seventy minutes old), so the tests' own top-ups never landed. The examples' `CLAUDE.md` prescribes a devnet reset before the integration run; the devnet was not reset in this session. | not repeated (inert level) |
| External `julc-examples` at `pv11-costed` (`adr/evidence/043-costed-examples.init.gradle` layered on the local script) | the same 418 / 352 / 11 / 55 with the identical failure set; all 41 annotation-processor-compiled validators have the same hash and size as at the default level | the same 418 / 352 / 11 / 55 with the identical failure set (every failure a Yaci DevKit step, `EscrowBudgetComparisonTest` included); all 41 compiled validators identical in hash and size to the default level and to round one |

The costed corpus is byte-identical because no compiled example validator binds a list to a
variable and indexes it twice: the repeated-index shapes in the corpus are accessor chains
(`txInfo.inputs().get(i)` in `LinkedListValidator`, O15 territory) and the
`com.example.benchmark.wingriders` package, which the examples build excludes from both source
sets upstream (its `WingRidersUtils.containsDuplicate` returns from inside a `while` body,
which the compiler rejects). Compiling that package with the CLI (`julc build`) after replacing
that early `return` by an accumulator flag:

| Validator | `pv11-safe` | `pv11-costed` |
|---|---|---|
| `WingRidersPoolValidator` | 1,943 bytes, `afd30d4f…` | 1,699 bytes, `cead36bb…`: four `listToArray` bindings (`requestIndices`, `inputs`, `outputs`, and `indices` in the duplicate check), six `indexArray` sites |
| `WingRidersRequestValidator` (one accessor-chain site) | 1,132 bytes, `6e81d331…` | unchanged |

The budget of that loop shape is measured by `O9ListIndexPromotionBenchmarkTest` (above); the
examples' own `WingRidersBenchmarkTest` compiles from source through the testkit at the default
level and cannot exercise the costed profile.
