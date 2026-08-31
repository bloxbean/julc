# ADR-033 Milestone 8 and live-cost compatibility validation

All commands ran sequentially and uncontended on 2026-08-31 with Java 25,
Gradle 9.2.0, and Scalus 1.1.0.

| Command | Result | Wall time |
|---|---|---:|
| Focused Scalus configuration, explicit-pipeline, and provider tests | Success | 11 s |
| `./gradlew :julc-vm-scalus:test --rerun-tasks --no-daemon` | Success; 306 tests, 0 failures, 0 errors, 0 skipped | 1 min 24 s |
| `./gradlew :julc-vm:test :julc-vm-java:test :julc-vm-truffle:test :julc-vm-scalus:test --rerun-tasks --no-daemon` | Success | 1 min 36 s |
| `./gradlew :julc-testkit:test :julc-cardano-client-lib:test --rerun-tasks --no-daemon` | Success | 13 s |
| `./gradlew build --no-daemon` | Success; 217 actionable tasks (16 executed, 201 up-to-date) | 1 min 17 s |

The compatibility regression asserts ready target-bound configurations for
V1/PV10/B, V1/PV11/D, V2/PV10/B, and V2/PV11/D. On each provider path, changing
the supplied `AddInteger` CPU intercept by 12,345 changes consumed CPU by
exactly 12,345 steps while memory is unchanged. Configuring V1, V2, and V3 on
one provider leaves all three language-only paths usable and keeps their
configuration snapshots independent.

The final build's JUnit XML totals are shown as tests / failures / errors /
skipped:

| Gradle module | XML totals |
|---|---:|
| `julc-core` | 621 / 0 / 0 / 0 |
| `julc-vm` | 91 / 0 / 0 / 0 |
| `julc-vm-scalus` | 306 / 0 / 0 / 0 |
| `julc-vm-java` | 2,439 / 0 / 0 / 262 |
| `julc-vm-truffle` | 3,486 / 0 / 0 / 262 |
| `julc-ledger-api` | 211 / 0 / 0 / 0 |
| `julc-compiler` | 1,465 / 0 / 0 / 0 |
| `julc-stdlib` | 403 / 0 / 0 / 1 |
| `julc-testkit` | 191 / 0 / 0 / 0 |
| `julc-testkit-jqwik` | 60 / 0 / 0 / 0 |
| `julc-cardano-client-lib` | 204 / 0 / 0 / 0 |
| `julc-examples` | 81 / 0 / 0 / 0 |
| `julc-gradle-plugin` | 30 / 0 / 0 / 0 |
| `julc-annotation-processor` | 20 / 0 / 0 / 0 |
| `julc-decompiler` | 94 / 0 / 0 / 0 |
| `julc-analysis` | 82 / 0 / 0 / 2 |
| `julc-analyzer-cli` | 100 / 0 / 0 / 4 |
| `julc-benchmark` | 16 / 0 / 0 / 0 |
| `julc-bls` | 2 / 0 / 0 / 0 |
| `julc-blueprint` | 26 / 0 / 0 / 0 |
| `julc-verification` | 92 / 0 / 0 / 0 |
| `julc-cli` | 442 / 0 / 0 / 0 |
| `julc-jrl:julc-jrl-core` | 165 / 0 / 0 / 0 |
| `julc-playground` | 31 / 0 / 0 / 0 |
| **Total XML** | **10,658 / 0 / 0 / 531** |

`julc-e2e-tests`, `julc-plugin-test`, and `julc-bom` emitted no JUnit XML in
the full build. They are included here explicitly so absence of an XML row is
not mistaken for an omitted module.
