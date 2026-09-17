# ADR-047 evidence: typed BLS12-381 values, native lists and explicit MSM (O11)

**Issue:** [#117](https://github.com/bloxbean/julc/issues/117) · **ADR:** [047](../047-bls-types.md) · **Branch:** `feat/117-bls-types` (stacked on ADR-046)

Every number below is from the tests named, run on this branch with the pinned
`cardano-node-11.0.1` PV11 cost model; Truffle and Scalus budgets are asserted equal to
Java in the tests that print them.

## Probes before code

A throwaway probe (deleted before the commit) established on Java, Truffle and Scalus:
`g1MultiScalarMul` over two hashed points equals the `g1ScalarMul`/`g1Add` chain; the
converters build the lists from Data lists; the empty product is the identity and the longer
list's extra entries are ignored; a scalar of 2^4095−1 and of −2^4095 succeeds and one beyond
each fails on all three VMs (`multiScalarMul: scalar too large (513 bytes, max 512)` on Java
and Truffle, a `Builtin error: Bls12_381_G1_multiScalarMul` on Scalus); G2 and pairing
agree; the eight misuse shapes probed (a byte string into `g1Add`, G2 into G1, a G1 held as
`byte[]`, `==`, a Data list of points, the boundary, a Data list as scalars, G2 in
`g1Points`) were rejected with the codes intended, the other shapes below were first
exercised in the test. Census: no program in
either `julc-examples` tree, the golden suites or the Blaster fixtures uses `BlsLib` or a
BLS builtin.

## Fixture matrix (`O11BlsTypesTest.typedBlsProgramsAgreeWithTheirManualChainsOnEveryBackend`)

18 fixtures × 4 levels × 3 VMs, 34 inputs. Every successful path returns the pinned boolean
(`true` where the typed program agrees with its manual chain, `false` for the disagreeing
pair of `NOT_EQUAL`); every failing path fails on all three VMs with the pinned text on Java
and Truffle. Budgets at `PV11_SAFE`, Java VM:

| Fixture | Input | CPU | Memory | Outcome |
|---|---|---:|---:|---|
| MSM_VS_CHAIN | run | 632,637,461 | 7,105 | true |
| FROM_LISTS | two | 750,655,331 | 36,150 | true |
| FROM_LISTS | extra-scalar-ignored | 776,555,352 | 39,310 | true |
| FROM_LISTS | negative | 750,655,331 | 36,150 | true |
| FROM_LISTS | one-scalar | 646,872,349 | 32,821 | fails: HeadList: empty list |
| FROM_LISTS | not-an-integer | 113,463,803 | 14,096 | fails: UnIData: not IntData |
| POINTS_FROM_DATA | generator-twice | 589,928,033 | 22,058 | true |
| POINTS_FROM_DATA | invalid-encoding | 53,999,880 | 5,546 | fails: Bls12_381_G1_uncompress: G1 uncompress failed: BLST_ERROR: bad point encoding |
| POINTS_FROM_DATA | not-bytes | 1,051,758 | 5,528 | fails: UnBData: not BytesData |
| POINTS_FROM_DATA | empty | 373,672,731 | 9,115 | fails: HeadList: empty list |
| EMPTY_AND_UNEVEN | run | 1,325,870,124 | 10,757 | true |
| SCALAR_BOUND | zero | 477,560,516 | 6,683 | true |
| SCALAR_BOUND | negative | 477,560,516 | 6,683 | true |
| SCALAR_BOUND | max | 478,119,200 | 6,683 | true |
| SCALAR_BOUND | max-plus-one | 400,596,634 | 6,164 | fails: Bls12_381_G1_multiScalarMul: multiScalarMul: scalar too large (513 bytes, max 512) |
| SCALAR_BOUND | min | 478,119,200 | 6,683 | true |
| SCALAR_BOUND | min-minus-one | 400,596,634 | 6,164 | fails: Bls12_381_G1_multiScalarMul: multiScalarMul: scalar too large (513 bytes, max 512) |
| SCALAR_BEYOND_ZIP | in-range | 426,294,673 | 6,797 | true |
| SCALAR_BEYOND_ZIP | beyond-bound | 425,772,665 | 6,296 | fails: Bls12_381_G1_multiScalarMul: multiScalarMul: scalar too large (513 bytes, max 512) |
| G2_MSM | run | 1,778,044,533 | 14,246 | true |
| PAIRING | run | 1,887,314,225 | 8,330 | true |
| HELPERS | run | 401,731,871 | 5,019 | true |
| ROUND_TRIP | run | 355,330,424 | 5,560 | true |
| TRACE_ORDER | pass | 478,141,222 | 8,848 | true |
| TRACE_ORDER | fail | 401,129,340 | 8,029 | fails: Error term encountered |
| VAR_LOCALS | run | 400,657,536 | 4,301 | true |
| NOT_EQUAL | four | 477,360,154 | 5,851 | false |
| NOT_EQUAL | three | 477,360,154 | 5,851 | true |
| EMPTY_CONVERTERS | both-empty | 801,083,594 | 13,866 | true |
| NEGATIVE_LITERAL | run | 477,463,339 | 4,937 | true |
| NESTED_PRODUCERS | three | 1,253,270,040 | 21,603 | true |
| BRANCHES | first | 1,008,865,828 | 56,176 | true |
| BRANCHES | second | 1,036,640,861 | 67,793 | true |
| NAME_CAPTURE | three | 1,627,590,416 | 30,733 | true |

At `PV11_COSTED` the two chain-side failures `FROM_LISTS/one-scalar` and
`POINTS_FROM_DATA/empty` fail as `IndexArray: index 1 out of bounds for array of size 1` and
`IndexArray: index 0 out of bounds for array of size 0`: ADR-043's promotion of the
repeatedly indexed boundary list, the documented O9 failure-contract change, not an O11
effect. `TRACE_ORDER` records `before`, `after` on every path at every level.

Artifacts at `PV11_SAFE` (bytes, script hash prefix):

| Fixture | Bytes | Hash |
|---|---:|---|
| MSM_VS_CHAIN | 76 | `4abd25cfa03c3bc0…` |
| FROM_LISTS | 287 | `4990aa7afcbfc219…` |
| POINTS_FROM_DATA | 192 | `9e05dbd628e91cff…` |
| EMPTY_AND_UNEVEN | 116 | `be12ab2626f9e739…` |
| SCALAR_BOUND | 62 | `97a22b611176fec2…` |
| SCALAR_BEYOND_ZIP | 63 | `9ac2c9abc92dcd40…` |
| G2_MSM | 135 | `5ec465197e8ebafb…` |
| PAIRING | 84 | `93cbaa387b450864…` |
| HELPERS | 52 | `7c3baf78717df138…` |
| ROUND_TRIP | 59 | `baeb454ca380944b…` |
| TRACE_ORDER | 101 | `961db7bacf1dba33…` |
| VAR_LOCALS | 42 | `24bffdb3f2696e80…` |
| NOT_EQUAL | 56 | `fca6f0020e970d4c…` |
| EMPTY_CONVERTERS | 182 | `3a5a446d62766a8c…` |
| NEGATIVE_LITERAL | 52 | `a38724ab836cc109…` |
| NESTED_PRODUCERS | 199 | `62402cd2884bc5f6…` |
| BRANCHES | 516 | `feb4bb460b2befef…` |
| NAME_CAPTURE | 296 | `d89f15933208c256…` |

The PR #150 review fix to the converters (the caller's list applied outside the decoding
loop's binding) moved the hashes of the five fixtures that use a converter (`FROM_LISTS`,
`POINTS_FROM_DATA`, `G2_MSM`, `EMPTY_CONVERTERS`, `NESTED_PRODUCERS`): the argument now sits
under one lambda fewer, so its de Bruijn indices differ. Their sizes and every budget above
are unchanged from the `5de83694` run.

## Diagnostics (`misuseIsRejectedAtCompileTimeWithTheNativeIsolationCodes`)

| Shape | Code | Message fragment |
|---|---|---|
| `byte[]` into `g1Add` | JULC0041 | `requires G1` |
| G2 point into `bls12_381_G1_add` | JULC0041 | `requires G1` |
| Miller result into `g1Add` | JULC0041 | `requires G1` |
| `byte[] p = hashToGroup(...)` | JULC0041 | `G1` |
| `p == p` | JULC0041 | `G1` |
| `JulcList.of(point)` | JULC0041 | `G1` |
| `static boolean m(JulcG1 p)` | JULC0042 | `G1` |
| `static boolean m(JulcScalars s)` | JULC0042 | `NativeList[Integer]` |
| `JulcList<BigInteger>` as the scalars of MSM | JULC0041 | `received List[Integer], but requires NativeList[Integer]` |
| G2 point in `g1Points(...)` | JULC0041 | `requires G1` |
| a point list where scalars are required | JULC0041 | `requires NativeList[Integer]` |
| G1 points into `bls12_381_G2_multiScalarMul` | JULC0041 | `requires NativeList[G2]` |
| a point where Data is required (`equalsData`) | JULC0041 | `G1` |
| a point as a record field | JULC0041 | `requires Data` |
| `byte[]` into `g2Add` | JULC0041 | `requires G2` |
| G1 point into `bls12_381_G2_add` | JULC0041 | `requires G2` |
| `byte[] q = g2HashToGroup(...)` | JULC0041 | `G2` |
| `static boolean m(JulcMlResult r)` | JULC0042 | `MlResult` |
| `static boolean m(JulcG2Points ps)` | JULC0042 | `NativeList[G2]` |
| `scalars(new byte[]{1})` (constant path) | JULC0041 | `requires Integer` |
| `scalars(BigInteger.ONE, dst)` with `byte[] dst` | JULC0041 | `requires Integer` |
| `scalars(d)` with `PlutusData d` | JULC0041 | `requires Integer` |
| `scalarsFromList(JulcList<byte[]>)` | JULC0041 | `received List[ByteString], but requires List[Integer]` |
| `g1PointsFromCompressed(JulcList<BigInteger>)` | JULC0041 | `received List[Integer], but requires List[ByteString]` |
| a point into a same-class helper's `byte[]` parameter | JULC0041 | `h argument 1` |
| a `JulcList<BigInteger>` into a helper's `JulcScalars` parameter | JULC0041 | `received List[Integer], but requires NativeList[Integer]` |
| `static byte[] m(...) { return hashToGroup(...); }` | JULC0041 | `Return value` |
| `static JulcG1 h(byte[] b) { return b; }` | JULC0041 | `requires G1` |
| `b ? p : q` into `g1Compress` (G2 in the else branch; PR #150 review) | JULC0041 | `Conditional else branch received G2, but requires G1` |
| `b ? q : p` into `g1Compress` | JULC0041 | `Conditional else branch received G1, but requires G2` |
| `b ? p : d` with `PlutusData d` | JULC0041 | `Conditional else branch received Data, but requires G1` |
| `b ? d : p` | JULC0041 | `Conditional else branch received G1, but requires Data` |
| `b ? p : dst` with `byte[] dst` | JULC0041 | `Conditional else branch received ByteString, but requires G1` |
| `b ? s : ps` as the scalars of MSM | JULC0041 | `Conditional else branch received NativeList[G1], but requires NativeList[Integer]` |
| `b ? ps : s` as the points of MSM | JULC0041 | `Conditional else branch received NativeList[Integer], but requires NativeList[G1]` |
| `b ? s : xs` with `JulcList<BigInteger> xs` | JULC0041 | `Conditional else branch received List[Integer], but requires NativeList[Integer]` |
| `JulcG1 r = b ? p : q` | JULC0041 | `Conditional else branch received G2, but requires G1` |
| `JulcG1 p = g2HashToGroup(...)` in a loop body (PR #150 review) | JULC0041 | `Variable 'p' initializer received G2, but requires G1` |
| the same in a nested loop | JULC0041 | `Variable 'p' initializer received G2, but requires G1` |
| the same in a break-aware loop | JULC0041 | `Variable 'p' initializer received G2, but requires G1` |
| `JulcG1 p = dst` in a loop body | JULC0041 | `Variable 'p' initializer received ByteString, but requires G1` |
| `acc = g2HashToGroup(...)` for a `JulcG1` accumulator (PR #150 review) | JULC0041 | `Assignment to 'acc' received G2, but requires G1` |
| the same with a second accumulator (rejected at the Data pack of the multi-accumulator loop) | JULC0041 | `Data encoding received G1, but requires Data` |
| the same before a `break` | JULC0041 | `Assignment to 'acc' received G2, but requires G1` |
| the same with a second accumulator, before a `break` (the pack again) | JULC0041 | `Data encoding received G1, but requires Data` |
| `p = g2HashToGroup(...)` for a loop-body `JulcG1 p` | JULC0041 | `Assignment to 'p' received G2, but requires G1` |
| `acc = dst` for a `JulcG1` accumulator | JULC0041 | `Assignment to 'acc' received ByteString, but requires G1` |

A `@SpendingValidator` entrypoint with a `JulcG1` datum and a `JulcScalars` redeemer is
`JULC0042`. A native-typed method whose body uses a block lambda with its own `return`
compiles (the lambda's return is not the method's).

A method returning `JulcG1` compiles and evaluates to a `bls12_381_G1_element` constant (as a
`JulcValue` result does); `var` locals infer the typed values (`VAR_LOCALS`).

## Producer shapes (`producersLowerToNativeListConstantsOrConsChainsWithoutDataWrappers`)

`scalars(1, 2)` is the constant `list integer [1, 2]` (no `MkCons`); `g1Points()` is the
empty `list bls12_381_G1_element` constant; `scalars(x, 1)` and `g1Points(hashToGroup(...))`
are `MkCons` chains ending in the empty native constant; `scalarsFromList` mentions
`UnIData`, `MkCons`, `NullList`; the `FromCompressed` converters mention `UnBData` and the
group's `uncompress`. None of the seven producer programs mentions `IData`, `BData`,
`ListData`, `MapData` or `ConstrData` in user code.

## Requirements (`requirementsNameThePv11BuiltinsAndBlsConstantsAndAPrePv11TargetFailsClosed`)

`scalars`/`scalarsFromList`: none; `g1Points`/`g2Points`: `BLS_CONSTANTS`;
`g1PointsFromCompressed`/`g2PointsFromCompressed`: the group's `uncompress` plus
`BLS_CONSTANTS`; `bls12_381_G1_multiScalarMul`: the builtin. The MSM fixture at a V3/PV10
target fails closed with `JULC0031` before lowering.

## Crossover benchmark (`O11BlsMsmBenchmarkTest`, Java = Truffle)

`Σ (3+i)·P_i` over `n` hashed G1 points, compressed, written as the explicit MSM (candidate)
and as the manual chain (baseline); both compiled at `BASELINE` and equivalent on both VMs.

| n | Chain CPU | Chain mem | Chain bytes | MSM CPU | MSM mem | MSM bytes |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 132,232,605 | 2,974 | 31 | 402,916,206 | 3,806 | 41 |
| 2 | 262,466,625 | 4,828 | 52 | 480,810,048 | 5,056 | 56 |
| 3 | 392,700,645 | 6,682 | 74 | 558,703,890 | 6,306 | 72 |
| 4 | 522,886,665 | 8,236 | 92 | 636,597,732 | 7,556 | 87 |
| 5 | 653,072,685 | 9,790 | 111 | 714,491,574 | 8,806 | 103 |
| 6 | 783,258,705 | 11,344 | 130 | 792,385,416 | 10,056 | 118 |
| 7 | 913,444,725 | 12,898 | 149 | 870,279,258 | 11,306 | 134 |
| 8 | 1,043,630,745 | 14,452 | 167 | 948,173,100 | 12,556 | 149 |

Per-point increments from the rows: MSM +77,893,842 at every n; chain +130,234,020 up to
n=3 and +130,186,020 from n=4. Both programs hash every point (`hashToGroup` intercept
52,538,055 on this profile), so net of hashing the builtin's own increment is 25.1 million
(its pinned slope 25,087,669; intercept 321,837,444) and the chain's about 77.4 million
(`scalarMul` 76,433,006 plus `add` 962,335 plus machine steps). Bytes and memory favour MSM
from three points, CPU from seven; the crossover does not depend on the hashing, which is
the same on both sides. The test pins both crossovers.

## Neighbouring suites

Before the review round: `LoweringRequirementsTest`, `NativeValueTypingTest`,
`OptimizationConfigurationTest`, `LibraryDiscoveryCleanupTest` (30), `julc-stdlib` (411,
`BlsLibTest` included, unchanged), `julc-blueprint` (26), `julc-verification` (92),
`julc-annotation-processor` (20): 579 tests, 0 failures; the `pair-case-backends` suites
(`O10ArrayLiteralFoldTest`, `O14ValueLiteralFoldTest` and the rest) ran in the full build.
After the review round (the helper-argument and return checks touch every method):
`:julc-compiler:test` and `pairCaseTest` 1,624, `julc-stdlib` 411, the benchmark test: 0
failures.

## Review round

Two independent agent reviews (types and lowering; tests, evidence and docs), no blocking
finding. Folded in: `Builtins.scalars` elements must be integers and the converters' Data
lists must carry the element they decode (`JULC0041`, with a universe check on the
all-constant path so no ill-formed list constant can be built); a native value can no
longer be passed into a same-class helper's differently typed parameter or returned through
a differently typed method (`JULC0041`; a lambda's own `return` is exempt); a `false`-valued
fixture, the empty converters, a negative literal scalar on the constant path, nested
producers, the G2 and Miller-result misuse shapes, the validator boundary and the G2 MSM
requirement added; the per-point cost prose now separates the hashing both programs share;
the tautological rule assertion dropped from the benchmark test; wording (26 became 31 inputs
with the new fixtures, the old MSM signatures compiled but could not evaluate, the
conformance ids). Recorded residuals: casts, `switch` expressions typed as Data, instance
calls on native receivers, the `requires Data` wording for non-native slots (all inherited
from O7).

## Review fixes (PR #150)

Three findings, each reproduced on Java at every level before the fix and pinned after it:

1. The converters bound the caller's list inside the `LetRec` of their decoding loop, so a
   user variable named like the loop (`go__scalars`) was captured: `scalarsFromList(go__scalars)`
   with a parameter of that name failed with `NullList: expected list, got VLam`. The list
   is now applied to the loop outside the binding; `NAME_CAPTURE` pins all three converters
   with same-named parameters, locals and free variables in the argument expression.
2. A conditional was typed by its `then` branch alone, so `g1Compress(b ? p : q)` with a G2
   `q` compiled and failed in the CEK on the `else` path. The generator now checks the `else`
   branch against the `then` type wherever a native type is involved (nine shapes in the
   diagnostics table: both orders, native/Data, native/bytes, scalar list/point list, native
   list/Data list, and a conditional as an initializer); `BRANCHES` evaluates agreeing
   branches on both paths.
3. A declaration inside a loop body bypassed the initializer check, and a loop assignment had
   none: `JulcG1 p = g2HashToGroup(...)` in a `for` body and `acc = g2HashToGroup(...)` for a
   `JulcG1` accumulator compiled. Both now go through the one boundary check the other sites
   use (`PirGenerator.checkNativeBoundary`); plain, nested, break-aware and no-accumulator
   loops and single accumulators, before a `break` and loop-body locals are pinned. A native
   accumulator among several was already rejected at the multi-accumulator loop's Data pack
   and stays so.

The temporary probes (deleted before the commit) also confirmed the retained residual that a
`switch` expression is typed Data (`g1Compress(switch ...)` is rejected as `received Data,
but requires G1` whether the arms agree or not) and that a nested conditional and a
conditional whose branches are both `byte[]` behave as intended (rejected where a G2 hides
in the inner `else`; accepted otherwise).

## Repository validation

At the review-round-two commit `f39ee64c` (on the branch merged with `main` at `291dc62d`,
after ADR-046's four review rounds): `julc-core` 693, `julc-compiler` 1,620 plus the gated
`pairCaseTest` 67 (`O11BlsTypesTest` 4 with the 18 fixtures and 50 misuse shapes),
`julc-stdlib` 411, `julc-blueprint` 26 and `O11BlsMsmBenchmarkTest` 1: 0 failures, 0 errors.
The three reviewer reproducers (`converterMustNotCaptureSourceName`,
`conditionalMustNotHideWrongGroup`, `loopLocalMustNotHideWrongGroup`) failed at `291dc62d`
and pass at `f39ee64c`; the MSM crossover is unchanged (bytes from three points, CPU from
seven). The full build, the additivity probe and the external `julc-examples` run below were
not repeated for this round: no lowering outside the three converters changed, and the
converter fixtures keep their sizes and budgets.

At the final commit `5de83694` (implementation `3a45a967`, review fixes `2583e42b`, documentation
`840589fa`/`5de83694`); the same gate at `840589fa`, before the review fixes, gave identical
results:

- Full build (`./gradlew build --continue`): 10,941 tests, 0 failures, 0 errors, 530 skipped
  (the pre-existing on-chain/DevKit-gated skips); the count is the ADR-046 build plus the
  four `O11BlsTypesTest` cases and `O11BlsMsmBenchmarkTest`.
- Additivity probe: two `var`-style BLS programs (the `BlsLibTest` style: `Builtins.bls12_381_*`
  add/equal over hashed points; `BlsLib` pairing with `millerLoop`/`mulMlResult`/`finalVerify`,
  `g1Neg`, compress/uncompress round trips) compiled in a worktree at the base commit
  `f4d9cbed` and at the final commit at every level with source maps off and on: 16 of 16
  FLAT encodings byte-identical.
- Blaster `prepare-artifacts.sh`: regenerated `artifact-lock.json` identical to the committed
  one.
- Published `0.1.0-pre17-5de8369-SNAPSHOT`; external `julc-examples` at the default level and at
  `pv11-costed` (init-script override, examples `build.gradle` untouched): 418 tests / 55
  failures / 11 skipped in each run. All 55 failures are `InsufficientBalanceException`
  against the local Yaci DevKit (exhausted funded accounts, not reset for this run): the same
  52 `*IntegrationTest` steps plus the three `EscrowBudgetComparisonTest` steps as the
  ADR-043 to ADR-046 runs. No compile-time or off-chain evaluation test fails.
- Additivity census: 41 of 41 validators unchanged (same size and hash as the ADR-046 run) at
  the default level and at `pv11-costed`.
- Docs site (`npm run build`): 32 pages, no warnings.
