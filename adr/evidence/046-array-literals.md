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
builtin is `IndexArray` either way (asserted by prefix on the Java VM for every failing array
input), over a constant either way. Call sites are `ListToArray`, `LengthOfArray` and
`IndexArray` applications in user code. No emitted program, folded or not, mentions
`MultiIndexArray` (asserted per fixture and level). CPU and memory are asserted never higher
on every path, failing ones included.

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
| LOCAL_LIST (list local shared with `size()`: the conversion would copy it, nothing folds) | 2 → 2 | 91 → 91 | unchanged |
| LOCAL_LIST_ONCE (list local used only by the conversion) | 3 → 0 | 48 → 6 | `c08b55f8…` → `c64aea12…` |
| SHARED_ELEMENT (256-byte local as both elements and in a runtime use: nothing folds) | 2 → 2 | 306 → 306 | unchanged |
| SHARED_ELEMENT_ONCE (256-byte local as both elements only: the array would hold two copies) | 2 → 2 | 296 → 296 | unchanged |
| ELEMENT_ONCE (32-byte local as the single element, used once) | 2 → 0 | 62 → 40 | `3f253f1c…` → `608154b5…` |
| WIDE_ELEMENT_ONCE (256-byte local as the single element: the Data element outgrows the constant) | 2 → 2 | 287 → 287 | unchanged |
| SHARED_LIST_ELEMENT (256-byte local as both elements of a list local converted to an array, and in a runtime use) | 2 → 2 | 309 → 309 | unchanged |
| LIST_ELEMENT_ONCE (32-byte local as the single element of a list local converted once) | 2 → 0 | 64 → 40 | `5f1b5345…` → `608154b5…` (= ELEMENT_ONCE) |
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
| LOCAL_LIST | run (3) | unchanged | unchanged |
| LOCAL_LIST_ONCE | run (3) | 1,592,057 → 16,100 | 5,099 → 200 |
| SHARED_ELEMENT | first, second (512 bytes) | unchanged (1,326,077) | unchanged (5,529) |
| SHARED_ELEMENT | past-the-end (fail) | unchanged | unchanged |
| SHARED_ELEMENT_ONCE | run (256 bytes) | unchanged | unchanged |
| ELEMENT_ONCE | run (32 bytes) | 784,878 → 16,100 | 2,868 → 200 |
| WIDE_ELEMENT_ONCE | run (256 bytes) | unchanged (784,878) | unchanged (2,868) |
| SHARED_LIST_ELEMENT | first, second (512 bytes) | unchanged (1,374,077) | unchanged (5,829) |
| SHARED_LIST_ELEMENT | past-the-end (fail) | unchanged | unchanged |
| LIST_ELEMENT_ONCE | run (32 bytes) | 832,878 → 16,100 | 3,168 → 200 |
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
(809,740 CPU). `LOCAL_LIST` is left as written: `xs.size()` still walks the list, so the
conversion would copy the list into an array constant beside the chain (ADR-045's call-site
objective, inherited through `LiteralFoldPass`); `LOCAL_LIST_ONCE`, where the conversion is
the list's only use, folds to one constant. `HELPER_GET` passes the array constant to a helper whose
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

- `ListToArray` of a bare list literal and of a once-bound local holding one (its only use)
  fold; a local still used elsewhere is measured as a reference and is not copied; a runtime
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

## The base-class extraction is neutral for ADR-045

`O14ValueLiteralFoldTest` ran in the same Gradle invocation as the new suite; its 79
`VALUE_LITERAL_*` measurement lines (bytes, hashes, CPU, memory, objective decisions) are
byte-identical to the lines recorded for ADR-045 at `4e2961f0`, so moving the machinery into
`LiteralFoldPass` changed nothing for the Value domain.

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

## Rebase onto ADR-045's corrected objective, and the review of the merged head

`main` (`bc7c8199`, ADR-045 merged with its review fixes `af4bdd8d`) was merged into the
branch after ADR-044 and ADR-045 landed. Two conflicts: the ADR-032 catalog rows (main's O9
row and this branch's O10 row are both kept) and `ValueLiteralFoldPass`, whose machinery this
branch had moved into `LiteralFoldPass`; the domain class keeps its thin form and the fix is
ported into the base (`remainingUses` counted per fixpoint iteration and reduced by the uses a
fold consumes; `creditableBindings` = once-bound locals whose value is a literal term, not an
alias; a `Var` argument measured as a reference unless this call consumes every remaining
occurrence of a creditable local, then as the constant the binding denotes, once).

- Element type under `var` (the maintainer's review of the merged head, P1): `var a =
  JulcArray.of(BigInteger.valueOf(7)); increment(a.get(0))` compiled without a diagnostic
  and failed at runtime with `AddInteger: expected integer, got Data` at every level, since
  the local was typed `JulcArray<PlutusData>`. The generator now records the arguments' source
  types on the lowered `JulcArray.of(...)` term and `TypeInferenceHelper` types the local and a
  chained access from that record (`JulcArray.of(...)` also resolves to `JulcArray<T>` at the
  expression level when every element resolves to the same type). A first version read the
  types back from the encodings; the review's second round showed `var a =
  JulcArray.of(Builtins.iData(BigInteger.valueOf(7))); extract(a.get(0))` failing with
  `UnIData: expected data, got Integer`, since `IData(x)` is also a user's `Builtins.iData(x)`.
  Elements of different types under `var` raise `JULC0012` naming the types and the fix
  (`varLocalOfAnArrayLiteralInfersTheElementType`: integer, string, boolean, nested-list and
  Data elements, the explicit declaration, a chained access, the empty literal, the mixed
  rejection, on every level).
- Structural measure (the maintainer's review of the merged head): a list literal is measured
  as it stands, each nested variable as a reference or, once, as the constant of a creditable
  local whose every remaining occurrence the call consumes. The reviewer's reproducer (a
  256-byte `b` as both elements of `JulcArray.of(b, b)` and in `appendByteString(a.get(i), b)`)
  grew from 306 to 825 bytes under the expanded-constant measure and now stays at 306
  (`SHARED_ELEMENT`); `SHARED_ELEMENT_ONCE` (296 → 296), `ELEMENT_ONCE` (62 → 40) and
  `WIDE_ELEMENT_ONCE` (287 → 287) bound the rule; direct-PIR probes cover the same three
  shapes with a 64-byte local. The review's second round found a dying *list local* still
  credited as its expanded constant: `xs = JulcList.of(b, b); a = xs.toArray()` beside a
  runtime `b` grew from 309 to 845 bytes. The credit is now dependency-aware: a dying local is
  measured as the term its binding holds, its own variables as references or dying locals in
  turn (a fixed point over the bindings the fold removes), so `SHARED_LIST_ELEMENT` stays at
  309 while `LIST_ELEMENT_ONCE` (`b` and `xs` both die) folds 64 → 40; the direct-PIR probes
  `throughList` (stays) and `throughListOnce` (folds to `1`) pin the same shapes. Element-width probe (single byte-string element, credited
  local): array/chain bits 57/113 at 1 byte, 313/361 at 32, 569/617 at 64, 1,113/1,129 at
  128 (fold), 1,713/1,705 at 200, 2,169/2,145 at 255, 2,177/2,161 at 256, 4,297/4,217 at 512
  (stay); the pass's decision matches the encoder at every width, bare or through the local.
- Mutation checks for the review's findings: with the list literal measured as its expanded
  constant again, `SHARED_ELEMENT` folds (the rule fires where none is expected) and the direct
  probe over the 64-byte local folds; with the element types not recorded on the literal, the
  `var` test fails on its first shape (the access is not even dispatched on the untyped
  scope); with a dying local credited as its expanded constant again, `SHARED_LIST_ELEMENT`
  folds and the `throughList` probe folds. All restored, all suites pass.
- Consequence for this domain: `LOCAL_LIST` (its list local is still walked by `size()`) no
  longer folds — the conversion would copy the list into an array constant beside the chain —
  and stays at 91 bytes with an unchanged hash; `LOCAL_LIST_ONCE` (the conversion is the
  list's only use) folds completely, 48 → 6 bytes, 1,592,057 → 16,100 CPU. The direct-PIR
  probe `let xs = ⟨list literal⟩ in lengthOfArray(listToArray(xs))` still folds to `2`, since
  `xs` is credited when its only occurrence is consumed. Every other fixture keeps its bytes,
  hash and budgets.
- Rerun on the merged tree with both review rounds' fixes: `:julc-compiler:check` 1,620 tests + 63
  `pairCaseTest` (the O10 and O14 suites among them), 0 failures, `verifyDiagnosticCodes`
  passed; `O10ArrayLiteralBenchmarkTest` and `O14ValueLiteralBenchmarkTest` 4/4;
  `NativeValueLibTest` 22/22; the in-repo `julc-examples` module 81/81.

## Repository validation

Final commit `a654df55` (review fixes); the same census was run at `2da29f5e` before the
fixes with identical results.

- Full build (`./gradlew build --continue`): 10,936 tests, 0 failures, 0 errors, 530 skipped
  (the pre-existing on-chain and DevKit-gated suites). The Java VM suite includes the
  999-case `PlutusConformanceTest`, so the `ArrayBuiltins` delegation to `ArraySemantics` is
  conformance-checked in this run.
- Blaster: `verification/blaster/scripts/prepare-artifacts.sh` completed and the regenerated
  `verification/blaster/generated/artifact-lock.json` is identical to the committed one.
- Published `0.1.0-pre17-a654df5-SNAPSHOT` to Maven local.
- External `julc-examples` (`../julc-examples`, resolved through
  `adr/evidence/041-local-examples.init.gradle`, the examples' `build.gradle` untouched) at the
  default level and at `pv11-costed`: 418 tests, 55 failures, 11 skipped in each run. Every
  failure is an `InsufficientBalanceException` against the local Yaci DevKit, whose funded
  accounts are exhausted and were not reset for this run: the same 52 `*IntegrationTest`
  steps and three `EscrowBudgetComparisonTest` steps as the ADR-043, ADR-044 and ADR-045 runs.
- **Additivity census.** 41 validators compiled at the default level and 41 at `pv11-costed`;
  every one has the same size and hash as in the ADR-045 run at the same level (0 of 41 differ
  at either level). The only default-versus-costed difference is ADR-043's
  `LinkedListValidator` promotion, as before. The O8, O9, O14 and O15 golden suites are
  byte-identical with the rule enabled.
