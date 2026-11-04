# ADR-068 M5: native, Wasm and external verification

This records the hosts M5's exit criteria name, for [ADR-068](../068-javac-frontend-migration.md): the native CLI, the Wasm image,
the external `julc-examples` on DevKit, `julc-helloworld` and Elci's release smoke. Each ran on the default frontend, javac, at the
M5b head before the range-aware narrowing of r18. `<M0>` is `/Users/satya/work/bloxbean/julc-068-m0/build`. The raw notes are in
`<M0>/work/m5v/` (native, Wasm, examples) and `<M0>/work/m5x/` (Elci, helloworld, cost).

r18 changes only where a cast's wrap stands, so these runs are not repeated at M5d. What it moves is in the I1 table, and on the
external corpus that is the 11 `MpfRegistryValidator` rows, which return to the bytes of the runs below on JavaParser
([the cost report](068-m5-cost-size.md)). Nothing else in the external corpus moves.

| Host | Result |
|---|---|
| Native CLI against the JVM CLI | `julc build` on three projects, the 43 external validators (44 files), a project with a library and a project with the stdlib: every `.uplc` and `plutus.json` is byte-identical. Exit 0 on all. `julc check` on a scaffolded project passes on both |
| Wasm image | `wasmSmokeFull` passes (`BUILD SUCCESSFUL`, the VM parity rows `ok`). A separate run of 15 requests, the external validators among them, against `ParityFixtures`' JVM answers: 15 of 15 equal |
| External `julc-examples` (`d415b1d`, the migrated sources) | 43 validators: every hash equal between the M5 integration head, this default-flip head and this cost report's build. `clean test` on the plugin's default frontend: 446 tests, 0 failures, 0 errors, 11 skipped. DevKit: 53 transactions accepted (as the verification run reported; its log does not print one line per transaction) |
| `julc-helloworld` | Migrated mechanically (`<M0>/work/m5x/helloworld-migration.diff`): 4 of 4 tests pass, and the script is equal on both frontends |
| Elci's release smoke against a local snapshot | Identical to M0: 2, 12 and 6 tests passing, and 42 |

## Compile time and memory (median of 3, `julc build`)

| Project | Native (javac) | Native (`--frontend javaparser`) | JVM CLI | `0.1.0-pre20` native |
|---|---|---|---|---|
| 43 external validators | 1.30 s, 184 MB | 2.05 s, 154 MB | 2.63 s, 934 MB | 1.73 s, 238 MB |
| A project with a library | 0.03 s, 80 MB | 0.05 s, 98 MB | 0.70 s, 262 MB | 0.03 s, 80 MB |
| A project with the stdlib | 0.06 s, 98 MB | 0.08 s, 103 MB | 0.80 s, 285 MB | 0.06 s, 90 MB |
| UVerify | 0.15 s, 111 MB | 0.17 s, 119 MB | 1.01 s, 348 MB | 0.16 s, 111 MB |

The commands are `<M0>/work/m5v/perf.sh` and `errs.sh`, with paths in `env.sh`.
