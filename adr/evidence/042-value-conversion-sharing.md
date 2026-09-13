# ADR-042 / issue #114 validation evidence

## Reference and fixture provenance

- Base: `feat/112-integer-case-dispatch` at `5e32bbcd` (ADR-041 head, PR #141). Branch
  `feat/114-value-conversion-motion`, stacked on it.
- Target `plutus-v3-pv11-uplc-1.1.0`; cost profile `cardano-node-11.0.1-plutus-v3-pv11`.
- Golden bytes: `julc-compiler/src/test/resources/optimization/o8-pre-change-bytes.txt`,
  78 rows (13 fixtures × NONE/BASELINE/PV11_SAFE × source maps off/on), captured from a
  detached worktree at `5e32bbcd` by compiling `O8ValueSharingFixtures.FIXTURES` through
  `JulcCompiler.compileMethod` before any pass change. The `PV11_SAFE` rows are the pre-O8
  safe-profile output, so every delta below is O8 alone.
- Fixtures (`O8ValueSharingFixtures`, all `compileMethod` sources over `PlutusData`
  arguments): REPEATED, SHARED (manual control), MIXED (raw builtin + wrapper), TRACE_FIRST,
  BRANCH_ONLY, BOTH_BRANCHES, TWO_VARS, SEQUENTIAL, INTERLEAVED, SHADOW, LOOP_HOIST,
  LOOP_BODY, SINGLE (control). Inputs: a canonical one-policy/one-token Value (`VALID`), a
  second policy (`DIFFERENT`), `NOT_A_MAP` (`integer 1`, Java text
  `UnValueData: expected Map data, got IntegerData`), `ZERO_QUANTITY` (Java text
  `UnValueData: zero quantity`), token lists of length 3 and 0, and a list with a non-bytes
  element.

## Measurements

Java VM, `PV11_SAFE`, no source maps, printed by `O8ValueSharingTest` as
`VALUE_SHARING_COST` / `VALUE_SHARING_ARTIFACT`. Truffle budgets are asserted equal to Java for
every input; Scalus agrees on results, traces, success/failure and failure text.

| Fixture | Input | CPU before → after | Δ CPU | Memory before → after |
|---|---|---:|---:|---:|
| REPEATED | valid | 2,508,650 → 1,995,960 | −512,690 | 6,080 → 5,824 |
| REPEATED | absent-token | 2,508,650 → 1,995,960 | −512,690 | 6,080 → 5,824 |
| REPEATED | not-a-map (fails) | 921,318 → 761,318 | −160,000 | 5,076 → 4,076 |
| REPEATED | zero-quantity (fails) | 1,305,074 → 1,145,074 | −160,000 | 5,120 → 4,120 |
| SHARED | all | unchanged | 0 | unchanged |
| MIXED | valid | 2,460,650 → 1,995,960 | −464,690 | 5,780 → 5,824 |
| MIXED | not-a-map (fails) | 873,318 → 761,318 | −112,000 | 4,776 → 4,076 |
| TRACE_FIRST | valid | 2,696,148 → 2,183,458 | −512,690 | 6,912 → 6,656 |
| TRACE_FIRST | not-a-map (fails, trace `before` kept) | 1,108,816 → 948,816 | −160,000 | 5,908 → 4,908 |
| BRANCH_ONLY | all five | unchanged | 0 | unchanged |
| BOTH_BRANCHES | all four | unchanged | 0 | unchanged |
| TWO_VARS | same | 3,860,174 → 2,930,794 | −929,380 | 4,326 → 4,414 |
| TWO_VARS | different (`&&` short-circuits) | 2,122,137 → 2,218,137 | +96,000 | 3,413 → 4,013 |
| TWO_VARS | a-not-a-map (fails) | 545,034 → 513,034 | −32,000 | 2,912 → 2,712 |
| TWO_VARS | b-zero-quantity (fails) | 1,457,480 → 1,457,480 | 0 | unchanged |
| TWO_VARS | a-not-a-map-b-zero-quantity (fails on a) | 545,034 → 513,034 | −32,000 | 2,912 → 2,712 |
| TWO_VARS | a-zero-quantity-b-not-a-map (fails on a) | 928,790 → 896,790 | −32,000 | 2,956 → 2,756 |
| SEQUENTIAL | valid | 4,726,124 → 3,796,744 | −929,380 | 9,798 → 9,886 |
| SEQUENTIAL | a-not-a-map (fails) | 1,001,318 → 905,318 | −96,000 | 5,576 → 4,976 |
| SEQUENTIAL | b-zero-quantity (fails) | 2,280,603 → 2,232,603 | −48,000 | 6,777 → 6,477 |
| INTERLEAVED | valid | 4,630,124 → 4,165,434 | −464,690 | 9,198 → 9,242 |
| INTERLEAVED | a-not-a-map (fails) | 1,113,318 → 905,318 | −208,000 | 6,276 → 4,976 |
| INTERLEAVED | b-zero-quantity (fails) | 2,360,603 → 2,408,603 | +48,000 | 7,277 → 7,577 |
| SHADOW | valid | 3,809,387 → 3,344,697 | −464,690 | 9,139 → 9,183 |
| SHADOW | data-not-a-map (fails) | 1,113,318 → 953,318 | −160,000 | 6,276 → 5,276 |
| SHADOW | other-zero-quantity (fails in helper) | 2,504,603 → 2,552,603 | +48,000 | 8,177 → 8,477 |
| SHADOW | both-bad (fails on data) | 1,497,074 → 1,337,074 | −160,000 | 6,320 → 5,320 |
| LOOP_HOIST | three-tokens | 6,950,215 → 5,412,145 | −1,538,070 | 21,554 → 20,786 |
| LOOP_HOIST | no-tokens | 2,236,279 → 2,236,279 | 0 | unchanged |
| LOOP_HOIST | not-a-map (fails) | 1,107,251 → 963,251 | −144,000 | 6,108 → 5,208 |
| LOOP_HOIST | bad-token-element (fails in loop) | 4,307,733 → 3,795,043 | −512,690 | 16,140 → 15,884 |
| LOOP_BODY | three-tokens | 10,368,912 → 8,926,842 | −1,442,070 | 26,837 → 26,669 |
| LOOP_BODY | no-tokens | 1,144,608 → 1,096,608 | −48,000 | 6,596 → 6,296 |
| LOOP_BODY | not-a-map (fails) | 1,689,542 → 1,529,542 | −160,000 | 9,408 → 8,408 |
| LOOP_BODY | bad-token-element (fails in loop) | 5,718,785 → 5,254,095 | −464,690 | 17,964 → 18,008 |
| SINGLE | all | unchanged | 0 | unchanged |

One conversion of the one-policy/one-token Value, measured as `[(builtin unValueData) VALID]`
minus a bare constant, costs 512,690 CPU and 256 memory (builtin execution about 480,690 plus
three machine steps). The fixture deltas are net of the binding: −464,690 where the binding is
new (MIXED, INTERLEAVED, SHADOW), −512,690 where a wrapper let that became single-use is
inlined away by the UPLC optimiser (REPEATED, TRACE_FIRST). A binding that is never used a
second time costs at most 48,000 CPU and 300 memory (three machine steps), measured in the
test as `[(λv. v) 1]` minus `1` under the pinned profile.

| Fixture | FLAT bytes | Script hash before → after |
|---|---:|---|
| REPEATED | 50 → 48 | `cc4248596a2c7bafb7a66ab0a7e7c7f3643ed8059087a56305a941d1` → `d4fdd21954619f5f2530ef6c84499e6e4a54699fa679a91ff70c9ac7` (= SHARED) |
| SHARED | 48 → 48 | `d4fdd21954619f5f2530ef6c84499e6e4a54699fa679a91ff70c9ac7` unchanged |
| MIXED | 47 → 48 | `126d167ed884bf1b496f62f1865b835b279a2cd40abd799b23eb9238` → `d4fdd21954619f5f2530ef6c84499e6e4a54699fa679a91ff70c9ac7` (= SHARED) |
| TRACE_FIRST | 65 → 63 | `a2cb173e60ad3ba65e5d6407d2216ebab60e86afda2c3196b8eb4cba` → `9f49394d20d9faf3c780fac30d776503b2f54d344df3cf1aea750557` |
| BRANCH_ONLY | 83 → 83 | `d2d0a6d308c053a14ec7f6ec55125a7404d365ddf95802e0ce7fc3ae` unchanged |
| BOTH_BRANCHES | 72 → 72 | `ced1063dc2ae791581cbeb920f20f6c78c4ca80486844609e65e211d` unchanged |
| TWO_VARS | 38 → 39 | `be1501fe55a313a86e4a82216e39499e6d050b37457482ded07c75c1` → `4082e0978be9f03507958216c736caa159103cb9ad74655f568d4d6b` |
| SEQUENTIAL | 82 → 83 | `9c5cd7ac026168732efbcdae7ab2d14c7a5c8f93a32d7ebe3a5aacff` → `bbe3f50d1487a71a920631d8e9cb354cf384dde916d6a39aaed19a2c` |
| INTERLEAVED | 77 → 77 | `7a061a170390759fd2cb6f4e99d97674af77ae41fdf87411c65cef4b` → `1fb0e03f0a393824c9820b7e6e810fe9241c1ff1eb00db981e7a3c2c` |
| SHADOW | 75 → 75 | `2c1952eaa17bff4e1a66e3c5dcf8dbc540cf493a9d8eac3d1ff7ff79` → `c1b20d5ea0ec80ab3e86064e8dfa2894499d769d783a6b15d05a9f4c` |
| LOOP_HOIST | 107 → 105 | `903d82e4366914f3978b62921c30c192336ce3f5c84a7f904c312044` → `7d7d47dea8b3677056d899f5e14edf73ab401fb6ead133a379218d12` |
| LOOP_BODY | 114 → 112 | `7e55cd1628911a780d0acd9e30f083bd92ad17243b2d6410057039af` → `3c56f2c2a853a12b8b94e1c5ae04e481f02f035b549b7fbcdc55b877` |
| SINGLE | 33 → 33 | `198d1f2db5e99afb6c522fdf5af5e561e9e6b222e4b58f2b83fc7118` unchanged |

Reproduce: `./gradlew :julc-compiler:pairCaseTest --tests '*O8ValueSharingTest*' --rerun -i`.

## Structural probes

- Conversion sites counted on the emitted PIR (outside the wrapper's own body): REPEATED
  2 → 1, MIXED 2 → 1, TRACE_FIRST 2 → 1, BRANCH_ONLY 2 → 2, BOTH_BRANCHES 2 → 2, TWO_VARS
  4 → 2, SEQUENTIAL 4 → 2, INTERLEAVED 4 → 3, SHADOW 3 → 2, LOOP_HOIST 2 → 1, LOOP_BODY
  2 → 1, SINGLE 1 → 1.
- Every `#value-N` binding's value is a conversion of a variable and its body is never a
  lambda (the binding lands inside loop bodies, never around them).
- REPEATED at PV11_SAFE and PV11_COSTED, source maps off and on, is byte-identical to SHARED.
- Guard probes with observable outcomes (direct PIR, BASELINE vs PV11_SAFE on all three VMs):
  `Let`/`Lam`/`DataMatch`-field rebinding of `x` (outer 42+42 plus inner 0 = 84, and the
  malformation order `(NOT_A_MAP, ZERO_QUANTITY)` keeps the inner failure text), a saturated
  partial builtin (`unBData y`) and a saturated total builtin (`addInteger 1 2`) in front (the
  binding stays below them), a variable bound outside the wrapper let by a `Let` or a lambda
  (binding lands directly under the wrapper; no free variable), and the `@Param` source
  shapes through `compileMethod` and `compileWithDetails`.
- Non-canonical input (`UNSORTED`, policies out of order) fails with
  `UnValueData: currencies not strictly ordered or duplicate` before and after on every
  fixture that takes it: e.g. REPEATED 1,688,862 → 1,528,862 CPU, INTERLEAVED
  a-valid-b-unsorted 2,744,391 → 2,792,391 (+48,000, one binding before the failure).

## Corpus check

`rg -n "NativeValueLib|unValueData" ../julc-examples --glob '*.java' --glob '!build/**'`
finds no validator or library. `ValuesLib` (`julc-stdlib`) contains no native Value builtin.
O8 therefore changes no shipped example artifact; the Maven-local run below confirms it.

## Repository validation

- `./gradlew build --rerun --continue -PskipSigning=true` at `40a28f83` (after the review
  round): exit 0, 0 failures, 0 errors. Gradle re-executed every test task whose inputs
  changed, 3,554 tests fresh (`:julc-compiler:test` 1,617; `:julc-compiler:pairCaseTest` 37,
  of which `O8ValueSharingTest` 8; `:julc-benchmark:test` 127; `:julc-stdlib:test` 403;
  `:julc-testkit:test` 193; `:julc-decompiler:test` 107; `:julc-cli:test` 442;
  `:julc-cardano-client-lib:test` 204; `:julc-examples:test` 81; verification, blueprint,
  playground, plugin, processor, jqwik, analysis), and reported the VM, core, ledger-api and
  analyzer modules up-to-date (7,328 tests, 530 profile-inapplicable skips, unchanged inputs).
- Two independent reviewer agents: reviewer A (pass legality) found the wrapper-scope
  unbound-variable defect described in ADR-042 §Invariants, fixed and pinned; its sixteen
  other probes were equivalent. Reviewer B reproduced all 78 golden rows byte-identically from
  a worktree at `5e32bbcd`, matched every number and hash in this document against fresh
  runs, and asked for the guard probes, the non-canonical input and the wording corrections
  now in the branch.
- Blaster `verification/blaster/scripts/prepare-artifacts.sh` (no `--update-lock`): exit 0,
  lock and hex artifacts unchanged (the suite compiles at `baseline`).
- Docs build (`npm run build` in `docs/`): exit 0.
- Maven local: published the tree as `0.1.0-pre17-40a28f8-SNAPSHOT`; external `julc-examples`
  run with `adr/evidence/041-local-examples.init.gradle` (`org.julclang` plugin and artifact
  override, build file untouched, every `julc-*` artifact resolved from the local snapshot):
  420 tests, 409 passed, 11 pre-existing skips, 0 failures, including the 54 transaction
  integration tests against the available DevKit. Every shipped validator hash is unchanged,
  as the corpus check predicts.
