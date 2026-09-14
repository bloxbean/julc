# ADR-046 evidence: typed array literals and literal folding (O10)

Everything below was produced on `feat/116-array-literals` with Java 25, the repository
Gradle wrapper, and the pinned `cardano-node-11.0.1-plutus-v3-pv11` cost profile
(`40ea9e0b7df77a7bd2cb7d4e4d9da040f8bee7ff0324a7cdb7e51702330e43a8`). Java and Truffle budgets
are asserted equal; Scalus agrees on results, traces, failure text and budgets.

## Probes before code

- `JulcArray.of(...)` over integer, byte string, string, boolean, nested list and runtime
  elements, `JulcArray.of()` empty, `xs.toArray()` on a live list local and
  `JulcArray.fromList(JulcList.of(...))` all compile to `ListToArray` over the `MkCons` chain
  `JulcList.of` emits (the elements wrapped exactly as `PirHelpers.wrapEncode` wraps them). At
  `PV11_SAFE` the fold turns each into a `(con (array data) [...])` constant and, at a literal
  index, into the decoded element; Java, Truffle and Scalus return the same results and
  budgets, Scalus decoding the array constant from FLAT
  (`Const(Array(Data,Vector(Data(1), Data(2), Data(3))))` in its failure text).
- `JulcArray.of(Builtins.emptyValue())` is rejected with `JULC0041` (Data encoding of a native
  Value), the existing container isolation.
- The conformance suite spells array constants in program text
  (`builtin/semantics/indexArray/indexArray-01/indexArray-01.uplc`: `(con (array integer) [1, 2, 3, 4, 5])`).
- Census: no `JulcArray` or `toArray()` in either `julc-examples` tree or in the Blaster
  fixtures; the in-repo uses are runtime lists (`O9ListIndexFixtures`, the benchmark's manual
  array control, `JulcArrayTest` on decoded Data) and `JulcArray<BigInteger>` fields at
  boundaries (`StrictDataBoundaryTest`, `BlueprintTest`, `BuildCommandTest`), none of them a
  list literal converted to an array.

## Fixture matrix (`safeProfileFoldsLiteralArraysAndStaysObservationallyEquivalentOnEveryBackend`)

`PV11_SAFE`, source maps off, rule off → on. Bytes and hashes; CPU/memory per input. Every
row's failure text is identical with the rule off and on on all three backends: the failing
builtin is `IndexArray` either way, over a constant either way. Call sites are `ListToArray`,
`LengthOfArray` and `IndexArray` applications in user code.

| Fixture | Calls | Bytes | Hash off → on |
|---|---:|---:|---|
| LENGTH | 2 → 0 | 35 → 6 | `881ecbcb…` → `c64aea12…` |
| GET_LITERAL | 2 → 0 | 42 → 6 | `9dc84957…` → `16d9e796…` |
| GET_RUNTIME (runtime index, constant embedded) | 2 → 1 | 49 → 34 | `dc12279f…` → `3f1e94fc…` |
| OUT_OF_RANGE_LITERAL (literal index 5, fails) | 2 → 1 | 42 → 27 | `eb7d2e26…` → `2797a10d…` |
| NEGATIVE_LITERAL (literal index −1, fails) | 2 → 1 | 42 → 27 | `c38ee2ba…` → `18d01e6a…` |
| HUGE_INDEX (literal index 2^63, fails) | 2 → 1 | 51 → 36 | `23e19209…` → `cddffa0b…` |
| BYTES | 2 → 0 | 44 → 14 | `b067efac…` → `9deb2261…` |
| STRING | 2 → 0 | 58 → 23 | `32a70179…` → `54270687…` |
| BOOL | 2 → 0 | 75 → 13 | `cf0055ea…` → `1f33385f…` |
| NESTED_LIST (list decode folded to a list constant) | 2 → 0 | 107 → 61 | `01db4a31…` → `a9d7798e…` |
| EMPTY | 2 → 0 | 14 → 6 | `28d9fe5c…` → `8cf91b7d…` |
| EMPTY_GET (fails) | 2 → 1 | 21 → 16 | `e51933ad…` → `ebc1e702…` |
| LOCAL_LIST (list local stays live) | 2 → 0 | 91 → 80 | `559b4b90…` → `99da1997…` |
| FROM_LIST | 2 → 0 | 28 → 6 | `d09f7cf0…` → `16d9e796…` (= GET_LITERAL) |
| RUNTIME_ELEMENT (runtime element, nothing literal) | 3 → 3 | 53 → 53 | unchanged |
| TRACE_AROUND | 2 → 0 | 56 → 22 | `1a46c088…` → `cd7b2304…` |
| ERROR_ARM | 2 → 0 | 64 → 31 | `21085413…` → `ae064515…` |
| TWO_ARRAYS | 4 → 0 | 66 → 6 | `de981a62…` → `0f005611…` |
| HELPER_GET (decoded access inside a helper: not a wrapper) | 2 → 1 | 40 → 31 | `d324b24f…` → `0376614c…` |

| Fixture | Input | CPU off → on | Memory off → on |
|---|---|---:|---:|
| LENGTH | run (3) | 1,105,723 → 16,100 | 3,644 → 200 |
| GET_LITERAL | run (2) | 1,238,594 → 16,100 | 4,398 → 200 |
| GET_RUNTIME | first (1) | 1,435,338 → 625,598 | 5,530 → 2,396 |
| GET_RUNTIME | last (3) | 1,435,338 → 625,598 | 5,530 → 2,396 |
| GET_RUNTIME | past-the-end (fail) | 1,414,594 → 604,854 | 5,498 → 2,364 |
| GET_RUNTIME | negative (fail) | 1,414,594 → 604,854 | 5,498 → 2,364 |
| GET_RUNTIME | huge (fail, out of range) | 1,414,594 → 604,854 | 5,498 → 2,364 |
| OUT_OF_RANGE_LITERAL | run (fail) | 1,201,850 → 392,110 | 4,266 → 1,132 |
| NEGATIVE_LITERAL | run (fail) | 1,201,850 → 392,110 | 4,266 → 1,132 |
| HUGE_INDEX | run (fail) | 1,201,850 → 392,110 | 4,266 → 1,132 |
| BYTES | run (2) | 1,059,361 → 118,200 | 3,843 → 710 |
| STRING | run (true) | 1,260,219 → 129,100 | 4,648 → 901 |
| BOOL | run (0) | 1,592,357 → 96,100 | 5,930 → 700 |
| NESTED_LIST | run (1) | 3,044,746 → 1,374,987 | 12,119 → 6,230 |
| EMPTY | run (0) | 432,226 → 16,100 | 1,349 → 200 |
| EMPTY_GET | run (fail) | 528,353 → 392,110 | 1,971 → 1,132 |
| LOCAL_LIST | run (3) | 3,406,644 → 2,895,214 | 13,463 → 12,090 |
| FROM_LIST | run (2) | 881,224 → 16,100 | 2,879 → 200 |
| RUNTIME_ELEMENT | five (6) | unchanged | unchanged |
| TRACE_AROUND | run (3, trace "table") | 1,426,092 → 251,598 | 5,230 → 1,332 |
| ERROR_ARM | pass (1) | 1,841,410 → 666,916 | 6,863 → 2,965 |
| ERROR_ARM | guard (fail) | unchanged | unchanged |
| TWO_ARRAYS | run (12) | 2,129,298 → 16,100 | 7,168 → 200 |
| HELPER_GET | run (2) | 1,110,095 → 572,854 | 4,233 → 2,164 |

Notes. A method whose whole body folds costs the one constant step (16,100 CPU / 200 memory).
The runtime-index fixture embeds the array constant and keeps the access: every path,
including the three failing ones, saves the list construction and its conversion
(809,740 CPU). `LOCAL_LIST` keeps the `MkCons` chain because `xs.size()` still walks the
list; only the array is a constant. `HELPER_GET` passes the array constant to a helper whose
body is the decoded access, which is not a wrapper (the decode sits between the builtin and
the parameters), so the access stays. `ERROR_ARM` on the guard path never reaches the table
and is byte-for-byte the same cost.

## Objective probe (`directPirListLiteralsDecodesFailuresPositionsAndObjective`)

Measured in bits of the bare term. A list decode of a produced element
(`unListData(indexArray(A, 0))`, the element a Data list of `n` integers) folds to a
`list data` constant for `n` of 0 and 1 and stays from 2 on: the CBOR-in-FLAT Data literal
is shorter than one FLAT byte string per element. An array literal is always shorter than
the `MkCons` chain it replaces, and a decoded element is always shorter than the decode of
its Data constant, so every other fold passes the objective.

## Structural probes (same test)

- `ListToArray` of a bare list literal and of a once-bound local holding one fold; a runtime
  element blocks; every `wrapEncode` element form (bytes, UTF-8 string, boolean, nested list,
  Data constant) reads to the Data the runtime would build.
- `LengthOfArray` and an in-range `IndexArray` on an array literal fold; indexes 2 (past the
  end), −1 and 2^63 stay and fail identically on three backends.
- A decode folds only over an element this pass produced: `unIData(indexArray(A, 0))` folds;
  `unIData` of a Data constant already in the program, bare or through a literal local, is
  never touched; a mismatched element (bytes under `unIData`) folds the access and keeps the
  decode, which fails at runtime; invalid UTF-8 keeps the `decodeUtf8`; the Bool form folds a
  constructor and keeps a non-constructor.
- BASELINE and the rule switch leave the term untouched; the call's source position moves to
  the literal.
- A pre-PV11 target fails closed with `JULC0031` before any lowering
  (`nonPv11TargetFailsClosedBeforeLowering`).

## Semantics (`ArraySemanticsTest`)

Length and conversion total and universe-preserving; `IndexArray` in range, past the end,
negative, on the empty array, and beyond the machine range in both directions, each with its
exact text.

## Benchmark (`O10ArrayLiteralBenchmarkTest`, PV11_SAFE with O10 off → on, Java = Truffle)

Reproduce with `./gradlew :julc-benchmark:optimizationEvidence`. `fee(tier)` indexes a
literal fee table by a runtime tier (two valid tiers, one past the end, one negative);
`middle()` is all literal.

#### o10-array-literal-table

Target: `plutus-v3-pv11-uplc-1.1.0`; cost profile: `cardano-node-11.0.1-plutus-v3-pv11` (`40ea9e0b7df77a7bd2cb7d4e4d9da040f8bee7ff0324a7cdb7e51702330e43a8`).

Baseline script hash: `0d5857cf0affe47b16abb8a2f8d13d9473fb8fcff82702fcf5e532c6`; candidate script hash: `47126dd586fff0c7a35e96bd5b0171bc294e1cc47053f6f54a2da5f8`.

| Metric | Baseline | Candidate | Delta |
|---|---:|---:|---:|
| FLAT bytes | 57 | 46 | -11 |
| UPLC term nodes | 49 | 20 | -29 |

| Backend | Case | Outcome | CPU baseline | CPU candidate | CPU delta | Memory baseline | Memory candidate | Memory delta |
|---|---|---|---:|---:|---:|---:|---:|---:|
| java | tier-0 | SUCCESS | 1435338 | 625598 | -809740 | 5530 | 2396 | -3134 |
| java | tier-2 | SUCCESS | 1435338 | 625598 | -809740 | 5530 | 2396 | -3134 |
| java | tier-3 | FAILURE | 1414594 | 604854 | -809740 | 5498 | 2364 | -3134 |
| java | negative | FAILURE | 1414594 | 604854 | -809740 | 5498 | 2364 | -3134 |
| truffle | tier-0 | SUCCESS | 1435338 | 625598 | -809740 | 5530 | 2396 | -3134 |
| truffle | tier-2 | SUCCESS | 1435338 | 625598 | -809740 | 5530 | 2396 | -3134 |
| truffle | tier-3 | FAILURE | 1414594 | 604854 | -809740 | 5498 | 2364 | -3134 |
| truffle | negative | FAILURE | 1414594 | 604854 | -809740 | 5498 | 2364 | -3134 |

Applied candidate rules: `pv11.o10.array-literal-fold`, `beta-reduce`.

#### o10-array-literal-only

Target: `plutus-v3-pv11-uplc-1.1.0`; cost profile: `cardano-node-11.0.1-plutus-v3-pv11` (`40ea9e0b7df77a7bd2cb7d4e4d9da040f8bee7ff0324a7cdb7e51702330e43a8`).

Baseline script hash: `7445b6190e15ad49743f3142eccd640914cfc729490655a06b177b3f`; candidate script hash: `a501b388f5504be47b66b7bbf60fd530736087adb2a95780f03a2598`.

| Metric | Baseline | Candidate | Delta |
|---|---:|---:|---:|
| FLAT bytes | 56 | 9 | -47 |
| UPLC term nodes | 47 | 1 | -46 |

| Backend | Case | Outcome | CPU baseline | CPU candidate | CPU delta | Memory baseline | Memory candidate | Memory delta |
|---|---|---|---:|---:|---:|---:|---:|---:|
| java | run | SUCCESS | 1683685 | 16100 | -1667585 | 5110 | 200 | -4910 |
| truffle | run | SUCCESS | 1683685 | 16100 | -1667585 | 5110 | 200 | -4910 |

Applied candidate rules: `pv11.o10.array-literal-fold`, `constant-fold`, `dead-code-elimination`, `beta-reduce`.

## Repository validation

Recorded after the final commit: full build, Blaster lock check, Maven-local publish,
external `julc-examples` at the default and costed levels with the per-validator diff
against the ADR-045 run (expected: 41 of 41 unchanged at both levels, the additivity
census).
