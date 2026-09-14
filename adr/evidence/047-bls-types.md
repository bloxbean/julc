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
agree; every misuse shape below is rejected with the code intended. Census: no program in
either `julc-examples` tree, the golden suites or the Blaster fixtures uses `BlsLib` or a
BLS builtin.

## Fixture matrix (`O11BlsTypesTest.typedBlsProgramsAgreeWithTheirManualChainsOnEveryBackend`)

12 fixtures × 4 levels × 3 VMs, 25 inputs. Every successful path returns `true` (the typed
program agrees with its manual chain); every failing path fails on all three VMs with the
pinned text on Java and Truffle. Budgets at `PV11_SAFE`, Java VM:

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

At `PV11_COSTED` the two chain-side failures `FROM_LISTS/one-scalar` and
`POINTS_FROM_DATA/empty` fail as `IndexArray: index 1 out of bounds for array of size 1` and
`IndexArray: index 0 out of bounds for array of size 0`: ADR-043's promotion of the
repeatedly indexed boundary list, the documented O9 failure-contract change, not an O11
effect. `TRACE_ORDER` records `before`, `after` on every path at every level.

Artifacts at `PV11_SAFE` (bytes, script hash prefix):

| Fixture | Bytes | Hash |
|---|---:|---|
| MSM_VS_CHAIN | 76 | `4abd25cfa03c3bc0…` |
| FROM_LISTS | 287 | `b9e33218b5ef97eb…` |
| POINTS_FROM_DATA | 192 | `69edbff0a3ceada4…` |
| EMPTY_AND_UNEVEN | 116 | `be12ab2626f9e739…` |
| SCALAR_BOUND | 62 | `97a22b611176fec2…` |
| SCALAR_BEYOND_ZIP | 63 | `9ac2c9abc92dcd40…` |
| G2_MSM | 135 | `3d5efd0a100c8a8b…` |
| PAIRING | 84 | `93cbaa387b450864…` |
| HELPERS | 52 | `7c3baf78717df138…` |
| ROUND_TRIP | 59 | `baeb454ca380944b…` |
| TRACE_ORDER | 101 | `961db7bacf1dba33…` |
| VAR_LOCALS | 42 | `24bffdb3f2696e80…` |

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

MSM: about 325 million CPU plus 78 million per point; chain: 130 million per point. Bytes
and memory favour MSM from three points, CPU from seven. The test pins both crossovers.

## Neighbouring suites

`LoweringRequirementsTest`, `NativeValueTypingTest`, `OptimizationConfigurationTest`,
`LibraryDiscoveryCleanupTest`, `O14ValueLiteralFoldTest`, `O10ArrayLiteralFoldTest`,
`julc-stdlib` (411, `BlsLibTest` included, unchanged), `julc-blueprint` (26),
`julc-verification` (92), `julc-annotation-processor` (20): 579 tests, 0 failures.

## Repository validation

(recorded after the final commit)
