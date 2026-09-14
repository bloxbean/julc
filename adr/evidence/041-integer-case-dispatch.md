# ADR-041 / issues #112, #113 validation evidence

## Reference and fixture provenance

- Base: `main` at `c2142e92` (merge of PR #140). Branch `feat/112-integer-case-dispatch`.
- Target `plutus-v3-pv11-uplc-1.1.0`; cost profile `cardano-node-11.0.1-plutus-v3-pv11`
  (`OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11`).
- Golden bytes: `julc-compiler/src/test/resources/optimization/o5-pre-change-bytes.txt`,
  30 rows (5 fixtures × NONE/BASELINE/PV11_SAFE × source maps off/on), captured from a
  detached worktree at `c2142e92` by compiling `O5IntegerCaseFixtures.SOURCES` through
  `PairCaseLoweringTest.compile` before any generator change. The `PV11_SAFE` rows are the
  O4-era output with the O2 `Case Bool` equality chain, so every delta below is O5 alone.
- Fixtures (`O5IntegerCaseFixtures`): TWO (`Pay(amount) | Cancel`), THREE
  (`Mint | Burn | Pause` with a `default` arm), FIVE (five zero-field constructors),
  NESTED (two-way outer with a three-way inner dispatch on a field), SINGLE (control).

## Measurements

Java VM, `PV11_SAFE`, no source maps, printed by `O5IntegerCaseLoweringTest` as
`INTEGER_CASE_COST` / `INTEGER_CASE_ARTIFACT`. Truffle budgets are asserted equal to Java for
every scenario; Scalus agrees on results, traces and success/failure.

| Fixture | Path | CPU before → after | Δ CPU | Memory before → after |
|---|---|---:|---:|---:|
| TWO | `Pay` (tag 0) | 3,697,464 → 3,581,131 | −116,333 | 16,093 → 15,692 |
| TWO | `Cancel` (tag 1) | 2,252,767 → 1,988,101 | −264,666 | 9,929 → 8,927 |
| THREE | `Mint` (tag 0) | 3,697,464 → 3,581,131 | −116,333 | 16,093 → 15,692 |
| THREE | `Burn` (tag 1) | 3,877,797 → 3,613,131 | −264,666 | 16,894 → 15,892 |
| THREE | `Pause` via `default` (tag 2) | 2,565,433 → 2,152,434 | −412,999 | 11,231 → 9,628 |
| FIVE | `A` (tag 0) | 1,924,101 → 1,807,768 | −116,333 | 8,527 → 8,126 |
| FIVE | `B` (tag 1) | 2,236,767 → 1,972,101 | −264,666 | 9,829 → 8,827 |
| FIVE | `C` (tag 2) | 2,581,433 → 2,168,434 | −412,999 | 11,331 → 9,728 |
| FIVE | `D` (tag 3) | 2,894,099 → 2,332,767 | −561,332 | 12,633 → 10,429 |
| FIVE | `E` (tag 4) | 3,238,765 → 2,529,100 | −709,665 | 14,135 → 11,330 |
| NESTED | `Pay`/`Fast` | 5,672,031 → 5,439,365 | −232,666 | 24,521 → 23,719 |
| NESTED | `Pay`/`Slow` | 5,750,117 → 5,369,118 | −380,999 | 24,921 → 23,518 |
| NESTED | `Pay`/`Manual` | 7,850,693 → 7,321,361 | −529,332 | 33,889 → 31,885 |
| NESTED | `Cancel` | 2,252,767 → 1,988,101 | −264,666 | 9,929 → 8,927 |
| SINGLE | `Only` | 3,549,131 → 3,549,131 | 0 | unchanged |

Selecting tag `k` saves `116,333 + k × 148,333` CPU. Every invalid input (non-constructor,
tag 99, tag 2^40, tag n, wrong field type, wrong arity) has an identical budget before and
after on every fixture: the strict boundary rejects it before user dispatch.

| Fixture | FLAT bytes | Script hash before → after |
|---|---:|---|
| TWO | 184 → 172 | `00511d8f9575445538f69612617349b546c77fb35d5a35c1f22741be` → `e38cfb864fe7f36cda94ac6eb7988bcee1b4b8cbf076bca5c89b384b` |
| THREE | 256 → 237 | `1bd81420a6990207c2d04fb40e9e4aa62ba87826a9ab557788acd9c0` → `dc2627a740cef531c77c991c116ef739dd20572aa1f017217c779e4d` |
| FIVE | 187 → 154 | `cf88321267439d57040cd381ba2fa532d102edc847d82741f5de4e32` → `01927d699cd539afc0be71c6e9d9c742bda3f32de6d06365c48d114c` |
| NESTED | 346 → 315 | `121969a199e7489a1db701dd9c8c748364fe75ba98532017dd909bbe` → `5ee44baf8240be594d96f3455686cd8ff6fe5b2e07942193f4dd9ebb` |
| SINGLE | 154 → 154 | `c59229b7819fd0a8ca837f797f9e46c01d6e19543b12e41b72d07144` unchanged |

Reproduce: `./gradlew :julc-compiler:pairCaseTest --tests '*O5IntegerCaseLoweringTest*' --rerun -i`.

## Failure-text contract probes

Raw `case (con integer T) [10, 20, 30]` and a raw `(error)` term, PV11 target:

| Input | Java / Truffle | Scalus |
|---|---|---|
| `error` | `Error term encountered` | `Error evaluated` |
| `T = 99` | `Case: tag 99 out of range for 3 branches` | `Case index 99 out of bounds for 3 branches` |
| `T = 2^40` | `Case: tag 1099511627776 out of range for 3 branches` (was `ArithmeticException` before the VM hardening) | `Case index 1099511627776 out of bounds for 3 branches` |
| `T = -1`, `2^64`, `-2^64` | out-of-range machine error naming `T` | out-of-bounds naming `T` |

`CaseFailureEquivalence` accepts exactly the legacy-to-Case substitution in either direction,
within one VM family's wording. Constructor-tag inputs top out at `2^40` on the compiled
matrix because Scalus cannot deserialize a Data constructor tag beyond the unsigned 64-bit
domain (`Expected Long but got Tag: PositiveBigNum`, budget 0, pre-existing and unrelated to
O5); raw-term `case` scrutinees cover `±2^64` on all three VMs.

## O6 census (rejection basis)

Shipped external `julc-examples` blueprint (58 validators,
`../julc-examples/build/classes/java/main/META-INF/plutus/plutus.json`, SHA-256
`25bc727922481ed45461abce3f3e81724464850ed5c77e8d608f5eb90f74147a`), counting every
`[(λx. body) e]` whose binder is unused in `body`. Statement sequencing always produces that
shape, but so do compiler-generated bindings whose value is discarded (boundary checks,
`_if` conditionals), so the count is an over-approximation of sequencing sites:

| Class of `e` | Sites | Note |
|---|---:|---|
| `error` term | 80 | never returns; a Unit Case would fail at the scrutinee exactly as the application does |
| `trace` application | 47 | unit-returning only when the traced continuation is unit |
| other (compiler bindings of non-unit values: boundary checks, conditionals) | 654 | not unit-typed; a Case on them would select by value |
| total | 781 | across 46,363 application nodes |

At most 127 sites in 58 validators are even candidates, each worth one lambda plus one
application (a few bytes, about two machine steps). No typed Unit statement surface exists to
prove any of them. O6 is rejected; see ADR-041. The census is reproducible with
`./gradlew :julc-benchmark:test --tests '*O6SequencingCensusTest*' --rerun -i` (it skips when
the blueprint is absent) and prints one `O6CENSUS` line per validator plus a total.

## Blaster lock check

`verification/blaster/scripts/prepare-artifacts.sh` (no `--update-lock`) on the branch:
all seven fixtures compiled, generated lock and hex artifacts byte-identical to the committed
lock, exit 0, `git status` clean under `verification/blaster/artifacts`. The suite compiles
at `baseline`, which O5 does not touch.

## Direct Haskell-node gate

`JULC_E2E_CARDANO_CONTAINER=<devkit> ./gradlew :julc-e2e-tests:switchPairCaseOnChainTest -Pe2e --rerun`
against the running developer DevKit (cardano-node 11.0.1, no reset or restart), 2026-09-13.
The ADR-038 switch validator (`Pay | Cancel`) was compiled at BASELINE and PV11_SAFE, locked
and spent on chain, and every spend's Java budget was compared with direct
`cardano-cli conway transaction calculate-plutus-script-cost` output. All four spends were
confirmed with exact Java/backend/Haskell agreement; all eight invalid redeemers were rejected
by Java, the backend and Haskell with the expected causes. Transaction ids are in
`041-integer-case-transactions.csv`.

| Profile | Scenario | ADR-038 CPU / memory | ADR-041 CPU / memory | Δ CPU |
|---|---|---:|---:|---:|
| BASELINE | pay | 10,398,076 / 39,712 | 10,398,076 / 39,712 | 0 (byte-identical script) |
| BASELINE | cancel | 7,235,935 / 26,453 | 7,235,935 / 26,453 | 0 |
| PV11_SAFE | pay (tag 0) | 6,859,673 / 28,039 | 6,743,340 / 27,638 | −116,333 |
| PV11_SAFE | cancel (tag 1) | 4,261,776 / 16,883 | 3,997,110 / 15,881 | −264,666 |

PV11_SAFE script: 247 → 239 bytes, hash
`352b54cac43d47713810019c12123c26f09137f67ae2024973c04ff1`. The on-chain deltas equal the
isolated tag-0 and tag-1 savings measured above, node-exact.

## Maven-local external examples

Published the working tree as `0.1.0-pre17-c2142e9-SNAPSHOT` to Maven local and ran the
sibling `julc-examples` checkout with `adr/evidence/041-local-examples.init.gradle`
(`org.julclang` plugin and artifact override, examples build file untouched):
`./gradlew clean test verifyJulcLocalArtifacts --refresh-dependencies --no-daemon -I … -Djulc.localVersion=…`.
Plugin and every `julc-*` artifact resolved from the local snapshot (`LOCAL_JULC_PLUGIN` /
`LOCAL_JULC_ARTIFACT` lines). Result: 420 tests, 409 passed, 11 pre-existing skips, 0
failures, including the 54 transaction integration tests against the available DevKit.

## Repository validation

- `./gradlew build --rerun --continue -PskipSigning=true` after the review round: exit 0;
  11,150 tests, 530 existing or profile-inapplicable skips, 0 failures, 0 errors (all modules,
  including `pairCaseTest`). Per module: `:julc-compiler:test` 1,617/0;
  `:julc-compiler:pairCaseTest` 29/0 (`O5IntegerCaseLoweringTest` 6/0, `SwitchPairCasePirTest`
  7/0); `:julc-vm-java:test` 2,440/0 (262 PV10-unavailable skips, both 999-case conformance
  profiles); `:julc-vm-truffle:test` 3,487/0; `:julc-decompiler:test` 106/0;
  `:julc-testkit:test` 193/0; `:julc-benchmark:test` 127/0.
- Two independent reviewer agents (lowering semantics and VM hardening; tests, evidence and
  ADR claims) found no correctness defect; both regenerated the 30 golden rows from the base
  commit byte-identically. Their required fixes (the `compileMethod`/testkit reachability
  statement and its tests, exact tag/count pinning, decompiler recognizer unit tests, guard
  tests, census reproducibility) are in this branch.
- Docs build: 32 pages.
- Blaster `prepare-artifacts.sh`: lock unchanged (see above).
