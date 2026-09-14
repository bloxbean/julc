# ADR-045 evidence: typed native Value literals and literal folding (O14)

Everything below was produced on `feat/119-value-literals` with Java 25, the repository
Gradle wrapper, and the pinned `cardano-node-11.0.1-plutus-v3-pv11` cost profile
(`40ea9e0b7df77a7bd2cb7d4e4d9da040f8bee7ff0324a7cdb7e51702330e43a8`). Java and Truffle budgets
are asserted equal; Scalus agrees on results, traces, failure text and budgets.

## Probes before code

- A program embedding `(con value [(#aa, [(#aa, 100000)])])`, the empty Value constant, a
  `lookupCoin`/`valueData`/`unionValue` of Value constants, and an `insertCoin` with a runtime
  quantity: FLAT encode/decode round-trips; Java, Truffle and Scalus return the same results
  and budgets at `BASELINE` and `PV11_SAFE`. Scalus therefore decodes Value constants from
  FLAT, an independent implementation agreeing with `julc-core`'s encoder.
- The conformance suite spells Value constants in program text
  (`builtin/semantics/unionValue/combine/combine.uplc`), so the constant's evaluation is
  reference-pinned; its FLAT tag (13) follows plutus-core.
- Every `NativeValueLib` wrapper is one builtin spine over its parameters in order
  (`fromData`, `toData`, `insertCoin`, `lookupCoin`, `union`, `contains`, `scale`); after the
  first draft `empty` was a constant binding and `singleton`/`lovelace` spines with the empty
  constant in their body; as library methods they added 28 bytes of dead bindings to the
  `NONE` and source-map artifacts of every ADR-042 fixture (52 of 78 golden rows; the 26
  deployable rows unchanged), so the producers became `Builtins` intrinsics.
- `new byte[]{}` lowers to an empty byte-string constant; `new byte[0]` is rejected by the
  subset ("arrays are not supported on-chain"). `BigInteger.valueOf(-5)` lowers to a runtime
  subtraction; `new BigInteger("-5")` is a constant.

## Fixture matrix (`safeProfileFoldsLiteralCallsAndStaysObservationallyEquivalentOnEveryBackend`)

`PV11_SAFE`, source maps off, rule off → on. Bytes and hashes; CPU/memory per input. Every
row's failure text is identical with the rule off and on on all three backends. Call sites
are Value builtin applications in user code (bare or through a wrapper).

| Fixture | Calls | Bytes | Hash off → on |
|---|---:|---:|---|
| EMPTY_LOOKUP (runtime key, empty literal) | 1 → 1 | 21 → 21 | unchanged |
| SINGLETON_LOOKUP | 2 → 0 | 40 → 7 | `42ae31e8…` → `cc0efc03…` |
| LOVELACE_UNION | 4 → 0 | 43 → 6 | `215d83dd…` → `0f005611…` |
| INSERT_CHAIN | 3 → 0 | 44 → 21 | `abfbec2d…` → `a061ea5f…` |
| UNION_CANCEL (`toData(empty)` stays: objective) | 4 → 1 | 48 → 9 | `0a336143…` → `6d0ce5c2…` |
| SCALE | 3 → 0 | 44 → 6 | `336bff3e…` → `0f005611…` (= LOVELACE_UNION) |
| SCALE_ZERO (`toData(empty)` stays) | 3 → 1 | 31 → 9 | `7969c4bc…` → `6d0ce5c2…` (= UNION_CANCEL) |
| CONTAINS_TRUE | 3 → 0 | 46 → 5 | `72aa8c0c…` → `28c3b2fb…` |
| CONTAINS_FALSE | 3 → 0 | 46 → 5 | `ff21070d…` → `05ff76fb…` |
| ROUND_TRIP | 4 → 0 | 43 → 6 | `a57fcbaa…` → `cb6a637c…` |
| RUNTIME_QUANTITY | 2 → 2 | 46 → 46 | unchanged |
| RUNTIME_VALUE | 3 → 2 | 33 → 27 | `6f41474d…` → `9e8ce926…` |
| LOCAL_LITERAL | 3 → 0 | 46 → 6 | `664036df…` → `16d9e796…` |
| ALIAS_LOCAL | 2 → 0 | 39 → 6 | `179936cd…` → `32d6215e…` |
| KEY_TOO_LONG (33-byte key, fails) | 2 → 2 | 99 → 99 | unchanged |
| KEY_MAX (32-byte key) | 2 → 0 | 97 → 6 | `f3958465…` → `32d6215e…` (= ALIAS_LOCAL) |
| LONG_KEY_ZERO (`toData(empty)` stays) | 2 → 1 | 57 → 9 | `9fca6895…` → `6d0ce5c2…` (= UNION_CANCEL) |
| QUANTITY_MAX | 2 → 0 | 57 → 24 | `b3c0b5be…` → `b03684ef…` |
| QUANTITY_OVERFLOW (fails) | 2 → 2 | 57 → 57 | unchanged |
| QUANTITY_MIN | 2 → 0 | 57 → 24 | `cd2baaee…` → `2d41ba54…` |
| QUANTITY_UNDERFLOW (fails) | 2 → 2 | 57 → 57 | unchanged |
| UNION_OVERFLOW (singletons fold, union fails) | 4 → 2 | 79 → 65 | `fe92b106…` → `c43c7ce7…` |
| SCALE_OVERFLOW (singleton folds, scale fails) | 3 → 2 | 62 → 56 | `cca59de6…` → `e4b59aed…` |
| CONTAINS_NEGATIVE (singleton folds, containment fails) | 2 → 1 | 29 → 22 | `bd3a3e55…` → `5b2ae500…` |
| TRACE_AROUND | 2 → 0 | 55 → 24 | `4b973955…` → `7f696c38…` |
| ERROR_ARM | 2 → 0 | 62 → 31 | `bef5360a…` → `ae064515…` |
| MIXED_KEY (runtime key, literal union) | 4 → 1 | 57 → 40 | `d1a905a6…` → `e39e3ae1…` |
| UNUSED_PARAM (helper with an unused parameter fed a runtime decode) | 2 → 2 | 51 → 51 | unchanged |
| USER_WRAPPER (user wrapper over a bare builtin, every parameter used) | 4 → 1 | 61 → 6 | `94533d4b…` → `c64aea12…` |

| Fixture | Input | CPU off → on | Memory off → on |
|---|---|---:|---:|
| EMPTY_LOOKUP | key | unchanged | unchanged |
| SINGLETON_LOOKUP | run (100) | 883,863 → 16,100 | 1,946 → 200 |
| LOVELACE_UNION | run (12) | 1,789,059 → 16,100 | 3,257 → 200 |
| INSERT_CHAIN | run | 1,228,092 → 16,100 | 2,778 → 200 |
| UNION_CANCEL | run (`Map []`) | 1,487,220 → 97,100 | 2,858 → 702 |
| SCALE | run (12) | 1,226,440 → 16,100 | 2,379 → 200 |
| SCALE_ZERO | run (`Map []`) | 924,601 → 97,100 | 1,980 → 702 |
| CONTAINS_TRUE | run (true) | 1,746,605 → 16,100 | 2,591 → 200 |
| CONTAINS_FALSE | run (false) | 1,746,605 → 16,100 | 2,591 → 200 |
| ROUND_TRIP | run (9) | 1,467,712 → 16,100 | 2,426 → 200 |
| RUNTIME_QUANTITY | four | unchanged | unchanged |
| RUNTIME_QUANTITY | zero | unchanged | unchanged |
| RUNTIME_QUANTITY | overflow (fail) | unchanged | unchanged |
| RUNTIME_VALUE | holds | 1,870,371 → 1,385,447 | 2,602 → 1,757 |
| RUNTIME_VALUE | empty | 1,051,241 → 566,317 | 2,558 → 1,713 |
| RUNTIME_VALUE | not-a-map (fail) | unchanged | unchanged |
| RUNTIME_VALUE | zero-quantity (fail) | unchanged | unchanged |
| LOCAL_LITERAL | run (2) | 1,352,135 → 16,100 | 2,712 → 200 |
| ALIAS_LOCAL | run (1) | 883,863 → 16,100 | 1,946 → 200 |
| KEY_TOO_LONG | run (fail) | unchanged | unchanged |
| KEY_MAX | run (1) | 883,863 → 16,100 | 1,946 → 200 |
| LONG_KEY_ZERO | run (`Map []`) | 582,024 → 97,100 | 1,547 → 702 |
| QUANTITY_MAX | run | 883,863 → 16,100 | 1,946 → 200 |
| QUANTITY_OVERFLOW | run (fail) | unchanged | unchanged |
| QUANTITY_MIN | run | 883,863 → 16,100 | 1,946 → 200 |
| QUANTITY_UNDERFLOW | run (fail) | unchanged | unchanged |
| UNION_OVERFLOW | run (fail) | 1,534,220 → 564,372 | 3,156 → 1,466 |
| SCALE_OVERFLOW | run (fail) | 971,601 → 486,677 | 2,278 → 1,433 |
| CONTAINS_NEGATIVE | run (fail) | 1,217,423 → 732,499 | 1,646 → 801 |
| TRACE_AROUND | run (1, trace "literal") | 1,071,361 → 251,598 | 2,778 → 1,332 |
| ERROR_ARM | pass (1) | 1,486,679 → 666,916 | 4,411 → 2,965 |
| ERROR_ARM | guard (fail) | unchanged | unchanged |
| MIXED_KEY | present (1) | 1,994,645 → 604,525 | 4,389 → 2,233 |
| MIXED_KEY | absent (0) | 1,994,645 → 604,525 | 4,389 → 2,233 |
| UNUSED_PARAM | integer (1) | unchanged | unchanged |
| UNUSED_PARAM | bytes (fail in the unused argument's decode) | unchanged | unchanged |
| USER_WRAPPER | run (3) | 1,789,059 → 16,100 | 3,257 → 200 |

Notes. A folded method whose whole body is a literal costs the one constant step
(16,100 CPU / 200 memory). The three `toData(empty)` fixtures keep one call because the
empty map's Data literal (9 bytes as a program) is larger than the conversion call
(7 bytes). Failing fixtures keep their failing call and fail with the same text; when the
literal operands of that call folded, the failing path is cheaper by exactly those folds.

## Objective probe (`wrappersLiteralLocalsPositionsAndObjective`)

Measured in bits of the bare term (no program header, no padding). `ValueData` of a
one-policy literal folds for every entry count probed (1 to 12 tokens); `UnValueData` of the
corresponding Data literal folds up to 4 tokens and stays from 5 on: a Value literal
byte-aligns every byte string in FLAT, the CBOR Data does not. `ValueData` of the empty Value
stays (40-bit literal against a 26-bit call). The pass's decision matches the encoder in
every case. A two-policy, six-token canonical literal is decoded fine by the semantics and
stays for size. A wrapper call is measured as the wrapper variable applied to the call-site
literals: `mk 5` for `mk = λq. insertCoin(P, T, q, empty)` is shorter than the singleton
literal, so it stays; the `NativeValueLib` shape (`un a b = unionValue(b, a)`) folds.

## Structural probes (same test)

- Canonical Data literal (one entry, negative quantity) folds to exactly the VM's decoding;
  fourteen non-canonical literals (not a map, non-bytes policy, 33-byte policy, unsorted or
  duplicate policies, non-map token map, empty token map, non-bytes token, 33-byte token,
  unsorted or duplicate tokens, zero quantity, out-of-range quantity, non-integer quantity)
  stay and fail identically on three backends.
- Bare `lookupCoin` on literals folds; `BASELINE` and the rule switch leave it.
- A once-bound wrapper whose body is the builtin over its parameters (`un a b =
  unionValue(b, a)`, substituted by position) applied to literals folds; the same name bound
  twice does not; an unsaturated call does not; a used parameter fed a runtime variable does
  not.
- A parameter the body never uses (`mk x q = insertCoin(P, T, q, empty)`) is not a wrapper:
  `mk error 5`, `mk (trace "m" 0) 5`, `mk y 5` and `mk 0 5` all stay, and the error and the
  trace are observed on three backends (the first review found this argument being dropped).
- A pre-PV11 target fails closed with `JULC0031` before any lowering
  (`nonPv11TargetFailsClosedBeforeLowering`); `NativeValueTypingTest` rejects a literal
  assigned to `PlutusData` and a literal inside `equalsData` with `JULC0041`.
- A literal local and a local aliasing it feed the calls below; a rebound name does not.
- A `Trace`, an `Error` or a runtime variable in argument position blocks the fold.
- Nested literal calls fold to a fixed point in one pass (`17`).
- Failing literal calls stay: union overflow, negative containment, a 33-byte key.
- The call's source position moves to the literal.

## Semantics (`NativeValueSemanticsTest`)

Ordering, replacement, zero removal (entries and policies), key and range checks only for
non-zero quantities (`long-key-zero`), total lookup, union add/cancel/overflow with the
exact text, containment (true, false, absent policy, empty operands) and negative rejection
on either side, scale (positive, negative, zero short-circuit before the range check,
overflow), canonical `ValueData` and round trip, fourteen `UnValueData` rejections with
their exact text, and a negative quantity decoding as is.

## Benchmark (`O14ValueLiteralBenchmarkTest`, PV11_SAFE with O14 off → on, Java = Truffle)

Reproduce with `./gradlew :julc-benchmark:optimizationEvidence`. `requires(minted)` checks
runtime Data against a literal requirement of one NFT plus two ADA (sufficient,
insufficient, unsorted and non-map inputs); `total()` is all literal. The requirement folds
to one Value constant and every path, including the two failing decodes, saves exactly the
two inserts and the union (1,438,120 CPU / 2,456 memory); the all-literal method is one
integer constant.

#### o14-value-literal-requirement

Target: `plutus-v3-pv11-uplc-1.1.0`; cost profile: `cardano-node-11.0.1-plutus-v3-pv11` (`40ea9e0b7df77a7bd2cb7d4e4d9da040f8bee7ff0324a7cdb7e51702330e43a8`).

Baseline script hash: `58d428180ad73cd7bc504930a2df7e579c27262a676bbbd5deceba57`; candidate script hash: `f62d0539fc6fed9259afb294fd756d835089ba624961a976b0c74635`.

| Metric | Baseline | Candidate | Delta |
|---|---:|---:|---:|
| FLAT bytes | 81 | 62 | -19 |
| UPLC term nodes | 37 | 14 | -23 |

| Backend | Case | Outcome | CPU baseline | CPU candidate | CPU delta | Memory baseline | Memory candidate | Memory delta |
|---|---|---|---:|---:|---:|---:|---:|---:|
| java | sufficient | SUCCESS | 3237611 | 1799491 | -1438120 | 4257 | 1801 | -2456 |
| java | insufficient | SUCCESS | 2388193 | 950073 | -1438120 | 4213 | 1757 | -2456 |
| java | unsorted | FAILURE | 2542698 | 1104578 | -1438120 | 4156 | 1700 | -2456 |
| java | not-a-map | FAILURE | 1775154 | 337034 | -1438120 | 4068 | 1612 | -2456 |
| truffle | sufficient | SUCCESS | 3237611 | 1799491 | -1438120 | 4257 | 1801 | -2456 |
| truffle | insufficient | SUCCESS | 2388193 | 950073 | -1438120 | 4213 | 1757 | -2456 |
| truffle | unsorted | FAILURE | 2542698 | 1104578 | -1438120 | 4156 | 1700 | -2456 |
| truffle | not-a-map | FAILURE | 1775154 | 337034 | -1438120 | 4068 | 1612 | -2456 |

Applied candidate rules: `pv11.o14.value-literal-fold`, `dead-code-elimination`, `beta-reduce`, `eta-reduce`.

#### o14-value-literal-only

Target: `plutus-v3-pv11-uplc-1.1.0`; cost profile: `cardano-node-11.0.1-plutus-v3-pv11` (`40ea9e0b7df77a7bd2cb7d4e4d9da040f8bee7ff0324a7cdb7e51702330e43a8`).

Baseline script hash: `215d83dd92f6ded4346a2c4d323e5d634db3dcea091b6af7c392ca3f`; candidate script hash: `0f005611a61713a69c8d11a2fda99afe7aeb4910c363d8e2a9767bcc`.

| Metric | Baseline | Candidate | Delta |
|---|---:|---:|---:|
| FLAT bytes | 43 | 6 | -37 |
| UPLC term nodes | 30 | 1 | -29 |

| Backend | Case | Outcome | CPU baseline | CPU candidate | CPU delta | Memory baseline | Memory candidate | Memory delta |
|---|---|---|---:|---:|---:|---:|---:|---:|
| java | run | SUCCESS | 1789059 | 16100 | -1772959 | 3257 | 200 | -3057 |
| truffle | run | SUCCESS | 1789059 | 16100 | -1772959 | 3257 | 200 | -3057 |

Applied candidate rules: `pv11.o14.value-literal-fold`, `dead-code-elimination`, `beta-reduce`.

## Repository validation

Final commit `4e2961f0` (review fixes).

- Full build (`./gradlew build --continue`): 10,924 tests, 0 failures, 0 errors, 530 skipped
  (the pre-existing on-chain and DevKit-gated suites). The Java VM suite includes the
  999-case `PlutusConformanceTest`, so the `ValueBuiltins` delegation to
  `NativeValueSemantics` is conformance-checked in this run.
- Blaster: `verification/blaster/scripts/prepare-artifacts.sh` completed and the regenerated
  `verification/blaster/generated/artifact-lock.json` is identical to the committed one.
- Published `0.1.0-pre17-4e2961f-SNAPSHOT` to Maven local.
- External `julc-examples` (`../julc-examples`, resolved through
  `adr/evidence/041-local-examples.init.gradle`, the examples' `build.gradle` untouched) at the
  default level and at `pv11-costed`: 418 tests, 55 failures, 11 skipped in each run. Every
  failure is an `InsufficientBalanceException` against the local Yaci DevKit, whose funded
  accounts are exhausted and were not reset for this run: the same 52 `*IntegrationTest`
  steps and three `EscrowBudgetComparisonTest` steps as the ADR-043 and ADR-044 runs.
- **Additivity census.** 41 validators compiled at the default level and 41 at `pv11-costed`;
  every one has the same size and hash as in the ADR-044 run at the same level (0 of 41 differ
  at either level). The only default-versus-costed difference is ADR-043's
  `LinkedListValidator` promotion, as before. No program in the corpus, in the in-repo example
  module, in the Blaster fixtures or in any earlier golden suite contains a Value literal, and
  the O8, O9 and O15 golden suites are byte-identical with the rule enabled.
