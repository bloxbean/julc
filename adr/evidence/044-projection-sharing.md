# ADR-044 evidence: strict-prefix sharing of record field projections (O15)

Everything below was produced on `feat/120-projection-sharing` with Java 25, the repository
Gradle wrapper, and the pinned `cardano-node-11.0.1-plutus-v3-pv11` cost profile
(`40ea9e0b7df77a7bd2cb7d4e4d9da040f8bee7ff0324a7cdb7e51702330e43a8`). Java and Truffle budgets
are asserted equal, Scalus agrees on results, traces, failure text and budgets.

## Provenance

- Base commit `1fd98c93` (ADR-043 head, `feat/115-list-to-array-promotion`). The goldens in
  `julc-compiler/src/test/resources/optimization/o15-pre-change-bytes.txt` (24 fixtures × 4
  levels × source maps off/on = 192 rows) were captured from a detached worktree at that
  commit with the fixture sources final, before any pass change (the chain-count expectations
  and three javadocs were corrected after capture and are not inputs to it); the same capture
  on the branch after the per-rule switch (milestone 1) was byte-identical, which is the
  switch's inertness proof. The six fixtures added after review (`SWITCH_BRANCH` to
  `TRACE_BETWEEN`) were captured the same way and the first 144 rows reproduced
  byte-for-byte; the matrix test now also reproduces every row at the final commit with
  `pv11.o15.projection-sharing` disabled, so the file is self-verifying.
- Census before writing the pass: the only earlier golden O15 moves is O9's `FIELD` (a cast
  local with two leading `h.items()` chains; the O9 suite now compiles with O15 off). O5's
  pattern-variable fields reuse the match binders, O8's fixtures have no projections,
  `GoldenUplcTest` and the seven Blaster fixtures compile at `BASELINE`, the benchmark
  aggregate source has no records, and every 56-hex literal in the test trees is an authority
  string or an on-chain reference hash, not a compiled script.
- `ContextsLib`'s uncalled helpers were the first thing the pass shared (O8's `SHARED` fixture
  changed by 5 bytes with source maps on); the transitive dead-binding exclusion followed.
  The `FIELD_THEN_INDEX` handoff fixture did not share at first because each `get` site sits
  inside the recursive `go_get` binding; the recursive-binding-of-lambdas leading rule
  followed.

## Cost model (`sharingCostDerivesFromThePinnedProfile`)

Machine step: 16,000 CPU / 100 memory (lambda, application, variable lookup measured as
`[(λv. v) 1]` minus `1` = 48,000 / 300). Unit costs including the lookup of the root variable
(Java VM, source maps on so the UPLC optimiser does not interfere):

| Unit | CPU | Memory |
|---|---:|---:|
| `sndPair(unConstrData(x))` (fields prefix) | 278,580 | 764 |
| `unIData(headList(P))` (integer, depth 0) | 462,474 | 1,328 |
| `unListData(headList(tailList(P)))` (list, depth 1) | 597,326 | 1,660 |
| `unMapData(headList(P))` (map, depth 0, two-entry map) | 466,353 | 1,328 |
| `unBData(headList(tailList²(P)))` (bytes, depth 2) | 721,198 | 1,992 |
| `headList(tailList⁵(P))` (raw, depth 5) | 1,058,045 | 2,756 |
| `equalsInteger(fstPair(unConstrData(headList(tailList³(P)))), 1)` (Bool, depth 3) | 1,177,535 | 3,157 |

Asserted exactly for every shape at 2 sites (and 3 for the integer field and the prefix):
`after = before + (2 + k)·16,000 − (k − 1)·unit` for CPU, and with 100 and the unit's memory
for memory. Loss bound on a path that reaches only the leading site: 48,000 / 300 per binding.

## Fixture matrix (`safeProfileSharesLeadingProjectionsAndStaysObservationallyEquivalentOnEveryBackend`)

Safe profile, source maps off. Bytes and hashes before → after; CPU/memory per input. Every
row's failure text is identical before and after on Java, Truffle and Scalus, except the
costed-only `FIELD_THEN_INDEX` out-of-range rows, which carry ADR-043's `IndexArray` text at
`PV11_COSTED` (the O15 binding makes the list a proven variable for O9).

| Fixture | Bytes | Hash before → after |
|---|---:|---|
| REPEATED | 88 → 63 | `f331bc3a…` → `c357d6f4…` |
| MANUAL | 63 → 63 | `c357d6f4…` (= REPEATED after) |
| TWO_FIELDS | 104 → 97 | `bd466d59…` → `7849460e…` |
| MIXED | 100 → 73 | `51eeee26…` → `68677812…` |
| BOTH_BRANCHES | 57 → 57 | unchanged |
| TRACE_FIRST | 48 → 41 | `011c627e…` → `8d738dc4…` |
| CALL_FIRST | 53 → 44 | `b21a9d47…` → `1da8cb2a…` |
| ALIAS | 32 → 32 | unchanged |
| SHADOW | 66 → 53 | `a4a078e9…` → `063ea4b7…` |
| LOOP_HOIST | 96 → 87 | `556cedad…` → `768af50a…` |
| LOOP_BODY | 90 → 81 | `6f15e833…` → `da0ae331…` |
| NESTED | 75 → 57 | `b08fdaa9…` → `65168d93…` |
| NESTED_LET | 54 → 47 | `d86c56c4…` → `bcb11153…` |
| CAST | 90 → 63 | `f675f505…` → `c357d6f4…` (= REPEATED after) |
| BOOL_STRING | 142 → 100 | `522b9348…` → `d6c15849…` |
| FIELD_THEN_INDEX | 139 → 130 | `7355343a…` → `b2164abc…` |
| SINGLE | 20 → 20 | unchanged |
| LEDGER | 132 → 114 | `5c277897…` → `b7fc07c2…` |
| SWITCH_BRANCH | 84 → 70 | `cde9340a…` → `ea5d2687…` |
| MAP_FIELD | 111 → 97 | `a43b0346…` → `12276f1f…` |
| ERROR_GUARD | 55 → 48 | `f8e8bdb1…` → `83a03d3d…` |
| ESCAPE | 55 → 48 | `ba7cc7cf…` → `ce5ca9bb…` |
| CALL_BETWEEN | 55 → 55 | unchanged |
| TRACE_BETWEEN | 51 → 42 | `68155763…` → `86552cc3…` |

| Fixture | Input | CPU before → after | Δ CPU | Δ memory |
|---|---|---:|---:|---:|
| REPEATED | above | 2,292,387 → 1,447,439 | −844,948 | −2,156 |
| REPEATED | below | 1,697,580 → 1,299,106 | −398,474 | −928 |
| REPEATED | not-a-record / empty-record / bad-amount (fail) | | −112,000 | −700 |
| MANUAL | all | unchanged | 0 | 0 |
| TWO_FIELDS | open / closed | 5,325,409 → 4,848,249 | −477,160 | −1,028 |
| TWO_FIELDS | not-a-record (fail) | 456,688 → 312,688 | −144,000 | −900 |
| TWO_FIELDS | empty-record / bad-amount (fail after the first field) | | +48,000 | +300 |
| MIXED | above | 3,051,685 → 1,992,157 | −1,059,528 | −2,520 |
| MIXED | below | 1,532,372 → 1,181,898 | −350,474 | −628 |
| MIXED | fails | | −192,000 / −64,000 | |
| BOTH_BRANCHES | all | unchanged | 0 | 0 |
| TRACE_FIRST | open / closed | 1,405,754 → 1,007,280 | −398,474 | −928 |
| TRACE_FIRST | fails | | −16,000 | −100 |
| CALL_FIRST | above / below | 1,831,161 → 1,384,687 | −446,474 | −1,228 |
| CALL_FIRST | fails (before the shared scope) | unchanged | 0 | 0 |
| ALIAS | all | unchanged | 0 | 0 |
| SHADOW | both | 2,633,620 → 1,836,672 | −796,948 | −1,856 |
| SHADOW | other-bad-amount (fail in helper) | 1,405,048 → 1,437,048 | +32,000 | +200 |
| SHADOW | b-not-a-record / both-bad (fail) | | −64,000 | −400 |
| LOOP_HOIST | three | 5,499,141 → 4,159,719 | −1,339,422 | −3,684 |
| LOOP_HOIST | none / not-a-record / bad-element | unchanged | 0 | 0 |
| LOOP_BODY | three | 6,089,857 → 4,750,435 | −1,339,422 | −3,684 |
| LOOP_BODY | none | unchanged | 0 | 0 |
| LOOP_BODY | second-not-a-record (fail in iteration 2) | 3,198,851 → 2,592,377 | −606,474 | −2,228 |
| LOOP_BODY | bad-amount (fail) | 1,666,940 → 1,506,940 | −160,000 | −1,000 |
| NESTED | open / closed | 3,480,009 → 2,271,384 | −1,208,625 | −2,720 |
| NESTED | not-a-record / empty-record (fail) | | −176,000 | −1,100 |
| NESTED | bad-inner (fail) | 1,434,733 → 1,402,733 | −32,000 | −200 |
| NESTED_LET | open | 2,308,301 → 1,909,827 | −398,474 | −928 |
| NESTED_LET | not-a-record | unchanged | 0 | 0 |
| NESTED_LET | bad-inner | | −16,000 | −100 |
| CAST | above | 2,340,387 → 1,447,439 | −892,948 | −2,456 |
| CAST | below | 1,745,580 → 1,299,106 | −446,474 | −1,228 |
| CAST | fails | | −160,000 | −1,000 |
| BOOL_STRING | open-expected | 2,859,217 → 2,740,637 | −118,580 | +236 |
| BOOL_STRING | open-other | 5,314,602 → 2,946,005 | −2,368,597 | −5,583 |
| BOOL_STRING | closed (one site of each shared unit) | 1,673,735 → 1,769,735 | +96,000 | +600 |
| BOOL_STRING | not-a-record (fail) | 952,788 → 632,788 | −320,000 | −2,000 |
| BOOL_STRING | empty-record / bad-open (fail) | | +64,000 | +400 |
| BOOL_STRING | bad-label (fail) | 2,734,259 → 2,535,679 | −198,580 | −264 |
| FIELD_THEN_INDEX | three / one | 3,411,618 → 2,878,292 | −533,326 | −1,260 |
| FIELD_THEN_INDEX | none (fails at the first get) | 1,388,909 → 1,436,909 | +48,000 | +300 |
| FIELD_THEN_INDEX | not-a-record / bad-items (fail) | | −288,000 | −1,800 |
| SINGLE | all | unchanged | 0 | 0 |
| LEDGER | two-outputs | 4,964,523 → 4,086,954 | −877,569 | −1,956 |
| LEDGER | no-outputs | 1,860,985 → 1,694,405 | −166,580 | −64 |
| LEDGER | not-a-record (fail) | 520,688 → 312,688 | −208,000 | −1,300 |
| LEDGER | empty-record (fail) | 744,343 → 760,343 | +16,000 | +100 |
| SWITCH_BRANCH | spend (shared pair in the case body) | 2,339,339 → 1,726,285 | −613,054 | −1,292 |
| SWITCH_BRANCH | mint (prefix only) | 1,727,657 → 1,513,077 | −214,580 | −364 |
| SWITCH_BRANCH | not-a-record (fail) | 408,688 → 312,688 | −96,000 | −600 |
| SWITCH_BRANCH | empty-record / bad-kind / unknown-kind (fail after the prefix) | | +48,000 | +300 |
| SWITCH_BRANCH | bad-amount-spend (fail) | 1,658,449 → 1,379,869 | −278,580 | −764 |
| MAP_FIELD | two | 4,135,925 → 3,518,992 | −616,933 | −1,292 |
| MAP_FIELD | empty | 1,341,023 → 1,174,443 | −166,580 | −64 |
| MAP_FIELD | not-a-record (fail) | 424,688 → 312,688 | −112,000 | −700 |
| MAP_FIELD | empty-record / bad-balances (fail) | | +16,000 | +100 |
| ERROR_GUARD | pass | 1,821,072 → 1,422,598 | −398,474 | −928 |
| ERROR_GUARD | guard / guard-not-a-record (error before the projections) | unchanged | 0 | 0 |
| ERROR_GUARD | not-a-record / bad-amount (fail) | | −16,000 | −100 |
| ESCAPE | open / closed | 2,091,146 → 1,692,672 | −398,474 | −928 |
| ESCAPE | fails | | −64,000 | −400 |
| CALL_BETWEEN | all (not shared) | unchanged | 0 | 0 |
| TRACE_BETWEEN | open / closed | 1,453,754 → 1,007,280 | −446,474 | −1,228 |
| TRACE_BETWEEN | fails (before the trace) | unchanged | 0 | 0 |

Notes. `REPEATED`'s three source projections are four chains in PIR because `compareTo`
evaluates its receiver twice; all four become one. `CAST` after sharing is the same program as
`REPEATED` after sharing (same hash, same budgets). `CALL_FIRST` and the loops save 48,000 more
than the model because the UPLC optimiser inlines a local that became a single-use alias of
the shared binding. Failing paths move by a few machine steps because the unit now runs before
the trivial prefix instead of after it.

## Validator shape (`validatorSharesNestedContextProjectionsAndKeepsItsOutcome`)

`@MintingValidator` with a record redeemer and `ctx.txInfo().outputs()` twice plus
`ctx.txInfo().fee()`; O15 on versus off at the safe profile, same script context:

| Input | CPU before → after | Δ CPU | Memory before → after |
|---|---:|---:|---:|
| two-outputs | 11,627,269 → 8,781,047 | −2,846,222 | 41,838 → 34,970 |
| no-outputs | 5,414,406 → 5,510,406 | +96,000 (two bindings) | 21,279 → 21,879 |
| no-outputs-nonzero | 5,398,406 → 5,494,406 | +96,000 | 21,179 → 21,779 |
| malformed-tx-info (fail) | 4,455,616 → 4,247,616 | −208,000 | 19,084 → 17,784 |

FLAT 429 → 380 bytes. No shared binding roots at the redeemer (the boundary already binds its
fields once).

## Benchmark (`O15ProjectionSharingBenchmarkTest`, PV11_SAFE with O15 off → on, Java = Truffle)

| Comparison | Bytes | Case | CPU | Memory |
|---|---:|---|---:|---:|
| o15-projection-sharing (REPEATED) | 88 → 63 | above | 2,292,387 → 1,447,439 | 8,121 → 5,965 |
| | | below | 1,697,580 → 1,299,106 | 6,292 → 5,364 |
| | | not-a-record / bad-amount (fail) | −112,000 | −700 |
| o15-projection-manual-control | 63 → 63 (hash = REPEATED candidate) | all | unchanged | unchanged |
| o15-projection-ledger | 132 → 114 | two-outputs | 4,964,523 → 4,086,954 | 17,470 → 15,514 |
| | | no-outputs | 1,860,985 → 1,694,405 | 5,748 → 5,684 |
| | | not-a-record (fail) | 520,688 → 312,688 | 3,232 → 1,932 |

Candidate script hashes: REPEATED `c357d6f4f2bcfdf19c23029001dcb7dd87b37a8cfed9e48680d9db16`
(= manual), LEDGER `b7fc07c256030c0d90719a748869077439e7bd0eed530a99c2206a83`.

## Structural probes (`directPirSharingMatchesUnitsRespectsRebindingAndSkipsDeadBindings`)

- Bool unit: the shared value is the whole `equalsInteger(fstPair(unConstrData(H)), 1)` form;
  one chain unit remains, no prefix binding; evaluated on `BOX_OPEN`, `BOX_CLOSED`,
  `NOT_A_RECORD`, `EMPTY_RECORD` against BASELINE on three VMs.
- Distinct arms over one raw field: no `#field-` binding (a raw-chain unit would have been
  bound), the prefix is shared and both decodes survive.
- Two distinct fields: `#fields-0` bound to `sndPair(unConstrData(x))`, no prefix occurrence
  left in the body, both chains re-rooted.
- Chain on chain: `#field-0` = inner raw projection, then `#fields-0` = prefix of `#field-0`,
  three units after.
- Raw beside decoded (`let a = H in let b = H in unIData(H) + unIData(H)`): the raw binding
  takes the two bare sites, the decode is shared as its own unit (one `unIData` survives) and
  the prefix is shared above both; the rewriter never descends into a unit of another key.
- Error arm: `if flag then error else H' + H'` keeps the `error` arm and shares the pair inside
  the other arm only.
- Source positions: the scope's location moves to the inserted `Let`, each replaced site's
  location to its variable, the unit keeps its own.
- A single recursive binding of a lambda leads (the `x` pair is shared above it); a
  two-binding `LetRec` blocks (only the live lambda's own pair is shared inside it).
- Rebinding under `Let`, `Lam` and a `DataMatch` binder named `x`: unchanged; a leading pair
  followed by a rebinding shares the pair only.
- Dead lambda binding, transitively dead pair of bindings, dead `LetRec`: unchanged, no
  provenance for any of the three; the same helper once called shares inside its body and
  records provenance.
- Round order: `unValueData(raw(x,1))` twice becomes `#field-0` then `#value-0`, both rules
  recorded; with `pv11.o15.projection-sharing` off nothing changes and nothing is recorded;
  with `pv11.o8.value-sharing` off the projection is shared and both `unValueData`
  applications remain.
- NONE and BASELINE return the same instance.

## Decompiler (`ProjectionSharingDecompileTest`)

BASELINE: four `unConstrData` applications inline; PV11_SAFE: one, rendered as
`var n = Builtins.unIData(Builtins.headList(Builtins.sndPair(Builtins.unConstrData(i2))));`
and used at every site.

## Repository validation

Final code commit `6cbdd0a1` (review fixes). `64c51faa` adds one ADR line (issue #146) and is
the commit the publish and example runs below used; its compiled artifacts are those of
`6cbdd0a1`.

- Full build (`./gradlew build --continue`) on the `6cbdd0a1` tree: 10,904 tests, 0 failures,
  0 errors, 530 skipped (the pre-existing on-chain and DevKit-gated suites).
- Blaster: `verification/blaster/scripts/prepare-artifacts.sh` completed and the regenerated
  `verification/blaster/generated/artifact-lock.json` is identical to the committed one (the
  seven fixtures compile at `baseline`, which O15 does not touch).
- Published `0.1.0-pre17-64c51fa-SNAPSHOT` to Maven local.
- External `julc-examples` (`../julc-examples`, resolved through
  `adr/evidence/041-local-examples.init.gradle`, the examples' `build.gradle` untouched) at the
  default level and at `pv11-costed` (`043-costed-examples.init.gradle`): 418 tests,
  55 failures, 11 skipped in each run. Every failure is an `InsufficientBalanceException` from
  cardano-client-lib against the local Yaci DevKit, whose funded accounts are exhausted and
  were not reset for this run (external devnet, not mutated): 52 `*IntegrationTest` steps and
  the three `EscrowBudgetComparisonTest` steps, which are not named `IntegrationTest` but submit
  transactions the same way. The failure set is identical to the ADR-043 validation run and to
  the run at `8258bfa1` before the review fixes; no compile-time or off-chain evaluation test
  fails.
- Validators compiled at the default level: 41. 26 differ from the ADR-043 default run: 11 with
  a new hash and 15 parameterized validators (the plugin prints no hash for them) with a smaller
  rounded size; 9 parameterized validators keep their rounded size (a byte change cannot be
  excluded from the log); 6 are unchanged. At `pv11-costed` exactly one validator differs from
  the default-level run: `LinkedListValidator` 4.0KB → 3.8KB, the first corpus promotion
  ADR-043 could not reach before O15 (the O15 binding makes its projected list a proven
  variable for O9). The `64c51fa` run is identical, validator for validator at both levels, to
  the `8258bfa1` run: the rewriter leaf rule and the single-binding restriction change nothing
  in the corpus.

| Validator | ADR-043 default | ADR-044 default | Hash |
|---|---:|---:|---|
| AuctionValidator | 669B | 669B | unchanged |
| CfAnonymousDataValidator | 1.8KB | 1.8KB | new hash |
| CfAtomicTxValidator | 325B | 325B | unchanged |
| CfAuctionValidator | 3.7KB | 3.2KB | new hash |
| CfBetValidator | 2.4KB | 2.2KB | new hash |
| CfCrowdfundValidator | 1.9KB | 1.9KB | parameterized, size unchanged |
| CfEscrowValidator | 1.8KB | 1.6KB | new hash |
| CfFactoryValidator | 2.5KB | 2.4KB | parameterized, size changed |
| CfHtlcValidator | 618B | 618B | parameterized, size unchanged |
| CfIdentityValidator | 1.6KB | 1.5KB | new hash |
| CfLotteryValidator | 3.7KB | 3.5KB | parameterized, size changed |
| CfPaymentSplitterValidator | 805B | 736B | parameterized, size changed |
| CfPriceBetValidator | 2.3KB | 2.0KB | new hash |
| CfProductValidator | 1.2KB | 1.2KB | parameterized, size unchanged |
| CfProxyValidator | 4.4KB | 4.3KB | parameterized, size changed |
| CfScriptLogicV1 | 1.6KB | 1.6KB | parameterized, size unchanged |
| CfScriptLogicV2 | 1.5KB | 1.5KB | parameterized, size unchanged |
| CfSimpleTransferValidator | 257B | 257B | parameterized, size unchanged |
| CfSimpleWalletValidator | 1.3KB | 1.2KB | parameterized, size changed |
| CfStorageValidator | 1.4KB | 1.3KB | parameterized, size changed |
| CfTokenTransferValidator | 1.5KB | 1.5KB | parameterized, size unchanged |
| CfVaultValidator | 1.4KB | 1.4KB | parameterized, size unchanged |
| CfVestingValidator | 496B | 496B | unchanged |
| CfWalletFundsValidator | 1.3KB | 1.2KB | parameterized, size changed |
| Cip68Nft | 2.1KB | 2.0KB | parameterized, size changed |
| CollateralLoan | 1.8KB | 1.7KB | parameterized, size changed |
| EscrowValidator | 1.1KB | 1.1KB | new hash |
| GuardedMinting | 123B | 123B | unchanged |
| LinkedListValidator | 4.0KB | 4.0KB (costed: 3.8KB) | parameterized, size unchanged |
| MpfRegistryValidator | 2.5KB | 2.4KB | new hash |
| MultiSigMinting | 586B | 556B | new hash |
| MultiSigTreasury | 423B | 423B | unchanged |
| OneShotMintPolicy | 707B | 690B | parameterized, size changed |
| OutputCheckValidator | 821B | 816B | new hash |
| SwapOrder | 1.6KB | 1.6KB | new hash |
| TokenDistributionValidator | 1.0KB | 1012B | parameterized, size changed |
| UVerifyFeePot | 2.0KB | 1.9KB | parameterized, size changed |
| UVerifyProxy | 3.3KB | 3.1KB | parameterized, size changed |
| UVerifyV1 | 8.0KB | 7.5KB | parameterized, size changed |
| VestingValidator | 812B | 807B | parameterized, size changed |
| WhitelistTreasuryValidator | 904B | 904B | unchanged |
