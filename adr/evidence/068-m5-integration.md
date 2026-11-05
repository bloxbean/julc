# ADR-068 M5's entry gate: the integration of M5a and M6a, the freeze with `m5-corpus-head-3`, and the gates

Branch `wip/068-m5-integ`, from `638d23ad` (M5a's confirmed head, [`068-m5a.md`](068-m5a.md)), with `wip/068-m6a` at
`dbc506bd` ([`068-m6a-tools.md`](068-m6a-tools.md)) merged in. PR #267's head `e7603aaa` is an ancestor of both. This file
records the merge, the freeze of every committed table with `m5-corpus-head-3` as the head recording, the I1 tool's classes
for `DIFFERENT-pir-unaccepted`, the gates, and r17's ADR text. `JAVAPARSER` stays the default.

`<M0>` = `/Users/satya/work/bloxbean/julc-068-m0/build`. Gradle runs through `<M0>/heavy.sh` with
`JAVA_HOME=~/.sdkman/candidates/java/25.0.2-librca`, `--offline --no-watch-fs`, each command under a time limit that kills
its process tree. Scripts: `build/m5i/freeze.sh`, `g.sh` and `gates.sh` in the worktree, copies of G4's with this worktree's
paths and `-Padr068.corpus.head=<M0>/m5-corpus-head-3` in place of `m2-corpus-head`, and one more step, `reference-shrink`
(below). Scratch: `<M0>/work/m5i/`.

## Commits

| Commit | What |
|---|---|
| `707270a7` | `git merge --no-ff wip/068-m6a`: clean, no conflict |
| `6e8587c4` | The I1 tool classes a `DIFFERENT-pir-unaccepted` row by the side that rejects (G1); the control check reads the committed table (G2) |
| `a148f06e` | The M5a tables folded into `lowering-accepted-differences.tsv`; the three var-lambda rows relabelled; M5a's registry row added to `m2-accepted-differences.tsv` |
| `07382de6` | The I1 reference and row tables frozen |
| `9a9a9322` | The baseline tables frozen |
| `75a8d388` | The encodings gate lists an encoder conflict by case key |
| `839e1919` | The types tables frozen, and the head recording's types rows listed |
| `c9ffdb47` | r17 in the ADR; the follow-ups |
| `1c2afab6` | Two frontend-option tests read `JULC0124` (the full test gate's two failures) |
| this file | The evidence |

## 1. The merge

`git merge --no-ff wip/068-m6a` merged cleanly (84 files from M6a). After it, at `707270a7`:

```sh
./gradlew :julc-compiler:test :julc-cli:test :julc-tools:test :julc-verification:test --rerun --continue
./gradlew :julc-compiler:adr068Tools :julc-cli:adr068Tools :julc-tools:adr068Tools :julc-verification:adr068Tools --continue --rerun \
    -Padr068.tools.sources=<M0>/julc-corpus/sources:<M0>/ext-examples:<M0>/work/m5i/repo-e7603aaa -Padr068.tools.out=<M0>/work/m5i/adr068-tools
```

(`repo-e7603aaa` is `git archive e7603aaa`, 1,946 `.java` files, as M6a's gate reads.)

| Run | Result |
|---|---|
| Module tests | `julc-compiler` 6,754, `julc-cli` 534, `julc-tools` 50, `julc-verification` 97; 0 failures, 0 skipped |
| The four tool gates, 12,339 files | Pass. Lint 12,308 same (30 javac rejects, 1 reader rejects); discovery 12,313 none and 22 same; arity 33,546; project scan 12,220 (33 javac rejects, 17 reader rejects, 63 later Java, 6 fail closed); library references 12,303, top-level names 12,339; annotations 12,280 (9 text of unread annotations) |
| Metadata extractor | 4,293 same, 7,577 `javac-attribution`, 298 `javac-codes`, 7 `same-first-error`, no `not-ported` |

The metadata counts differ from M6a's corpus-only line (4,294 same, 279 `javac-codes`, 18 `not-ported`) by M5a's rules
alone: the 18 programs that stopped at `JULC0084` now meet M5a's codes, and one file M6a read alike now gets one of them
(18 + 1 = 19 more `javac-codes`). The other counts equal M6a's.

## 2. The I1 tool and the tables (G1, G2)

**G1 (the M5a re-confirmation).** A `DIFFERENT-pir-unaccepted` row is a method PIR that differs where a frontend rejects
the program. The row now carries which side rejects (`Row.rejects`: `reference`, `javac` or `both`; not written to the
table, so no row moves), and `Adr068LoweringTest.assertListed` checks each entry's class against it
(`Adr068Lowering.explainsUnaccepted`):

- "Accepted by decision" explains it only where the reference rejects and the javac frontend accepts;
- "Policy rejection" only where the javac frontend rejects and the reference accepts;
- "Semantic fix" explains neither: the tool's class list no longer lets it, since the side that rejects has no semantic
  observation (the review asked for this or for r17 to say why Semantic fix is used; the stricter form was taken, as
  `DIFFERENT-javac-accepts` already takes only Accepted by decision);
- a row both frontends reject has no class without a decision of its own.

Tests (`Adr068LoweringToolTest`, 73 tests): `aPirRowOnlyJavacRejectsIsAPolicyRejection` (a Semantic fix and an Accepted by
decision refused where the javac frontend rejects), `aPirRowOnlyTheReferenceRejectsIsAcceptedByDecision` (on the real
program, `(var x) -> x.signum() > 0`: Semantic fix and Policy rejection refused), `aPirRowBothRejectHasNoClass`, and
`aMethodLoweredToAnotherTermWhereJavacRejectsIsUnaccepted` pins the side. The rows `eb1358aa2a`, `6163833394` and
`b9af5af07e` are Accepted by decision, in the committed table and in the M5a tables (r13's widening, M5a section 12,
`reject-068-var-lambda-parameter.diffcase`).

**G2.** `theM5aTablesCiteControlsThatExist` reads `lowering-accepted-differences.tsv` too, requires a run-time policy
rejection there, and requires each of the M5a tables' run-time entries to be in it. It passes: the five entries are there.

**The fold.** `m5a-accepted-differences.tsv` and `m5a-head2-accepted-differences.tsv` are folded into
`lowering-accepted-differences.tsv` by the procedure of M5a's section 16 (later tables replace earlier entries of the same
key), and the 162 entries of `m2-corpus-head` cases that `m5-corpus-head-3` does not hold are removed. The result is 2,264
entries (463 kept from the committed table, 34 from `m5a-accepted-differences.tsv`, 1,767 from the head table), byte for
byte the table M5a's scratch view passed with (`<M0>/work/m5a-fix/i1/view`), before the three relabels. The M5a tables
stay as evidence, with a header saying they are folded; `m5a-head2-unresolved.tsv` holds no row. The registry row M5a left
commented for the types table (`aa77e73fd6`, a float component, Diagnostic change) is in `m2-accepted-differences.tsv`.

## 3. The freeze

**The head corpus is replaced, not added.** M5a's sections 16 and 17 freeze with `-Padr068.corpus.head=<M0>/m5-corpus-head-3`
and `-Padr068.allowShrink=true` "for the reference and the table": the head recording at `8875e6a9` replaces
`m2-corpus-head`, whose 564 cases it no longer records. The committed corpora are unchanged.

**The reference step.** `-Padr068.freezeReference=true` writes `lowering-reference.tsv` into the repository (the code copies
the run's table over the committed one), not into the out directory alone, and refuses to lose a case without
`-Padr068.allowShrink=true`. So `freeze.sh` gained `reference-shrink` (`reference` with `allowShrink`), and the check by
diff was made on the written file against `HEAD` before it was committed:

| `lowering-reference.tsv` | Rows |
|---|---|
| Before (`a148f06e`) | 17,098 |
| After | 24,167 |
| Added (the head recording's new cases) | 7,633 |
| Removed (the `m2-corpus-head` cases no longer recorded) | 564 |
| **Changed** | **0** |

No existing reference row changed (D4's cap holds; no B-exception is needed). The table equals byte for byte the reference
M5a froze in its view.

**The steps**, at `a148f06e`'s code and tables:

```sh
build/m5i/freeze.sh reference-shrink   # exit 1 by design: it writes the reference, then fails on 8,292 rows that differ from the committed lowering-i1.tsv
build/m5i/freeze.sh table-shrink       # exit 0; -Padr068.freeze=true -Padr068.allowShrink=true
build/m5i/freeze.sh baseline           # exit 0; writes the baseline tables
build/m5i/freeze.sh types              # writes to the out dir; copied in by hand after the diffs below
build/m5i/freeze.sh types-gate         # exit 0, against the copied tables
```

The listing check (`assertListed`, with G1's side check) passed in both I1 steps: every `DIFFERENT` row is listed with a class
its row and side allow, and no entry is stale.

**`table-shrink`.** 8,292 moved rows, all accounted for:

| Moved | Rows |
|---|---|
| New cases (`(new)`): 5,331 `i1a-identical`, 949 `DIFFERENT-javac-rejects`, 460 `both-reject`, 322 `DIFFERENT-rejection`, 308 `DIFFERENT-bytes`, 73 `-diagnostics`, 68 `-javac-accepts`, 45 `-provenance`, 40 `javac-rejects`, 33 `both-reject-javac-first`, 3 `DIFFERENT-pir-unaccepted`, 1 `DIFFERENT-pir` | 7,633 |
| Cases no longer recorded: 362 `i1a-identical`, 151 `DIFFERENT-javac-rejects`, 33 `javac-rejects`, 11 `DIFFERENT-rejection`, 5 `both-reject-javac-first`, 2 `both-reject` | 564 |
| `not-ported` to `DIFFERENT-javac-rejects` 23, to `i1a-identical` 12; `reference-rejects-not-ported` to `DIFFERENT-rejection` 4, to `both-reject` 1 | 40 |
| `DIFFERENT-javac-rejects` in the same class with another construct or digest (M5a's codes reach the method attempt) | 51 |
| `both-reject` to `DIFFERENT-rejection` (`036f5ec3`, `fec535c6`); `DIFFERENT-rejection` with another digest (`1a9eb48f`); `DIFFERENT-pir` to `DIFFERENT-bytes` (`5468f431`) | 4 |

**571 shrunk rows**, as M5a's section 17 expects: the 564 cases no longer recorded and 7 moves: `132e0afc`, `33ee1cb2` (their
one covered method now meets `JULC0012`), `5468f431` (`DIFFERENT-pir` to `DIFFERENT-bytes`), `036f5ec3`, `fec535c6`
(`both-reject` to `DIFFERENT-rejection`, `JULC0012` for an unspecialized type parameter), `1a9eb48f` (the javac frontend's
array rejection gains `JULC0014`, `055af874`) and `8470b042` (`Optional.equals` of unrelated arguments from `JULC0078` to
`JULC0087`, M5a's section 15 F3). Section 17 names the first five; the last two are M5a's code changes of the same rounds,
and each is listed in the folded table with that class.

**Baseline** (`adr068Baseline`, `-Padr068.freeze=true`; committed corpora and external examples, no head): the four `.diffcase`
files M5a added gain their rows in `corpus-b4.tsv`, `ledger-b4.tsv`, `ledger-b4-semantic.tsv`, `ledger-b4-budget.tsv` (4 lines
each) and `rejection-inventory-b4.tsv` (1, `reject-068-var-lambda-parameter`, permanent, `JULC0055`). No line is removed or
changed. `totals-b4.tsv`: cases 11,460 to 11,464, `.diffcase` 236 to 240, ledger accepted 9,705 to 9,708 and rejected 1,745 to
1,746 at each level, runs 1,908 to 1,912 (`MISMATCH` 224 to 236, the three new `diverge-` pins at four levels; `REJECTED` 252
to 256), and the rejection-site catalogue's line (193 to 200 sites, M5a's codes; the gate ignores that line).

**Types.** M5a never ran `adr068Types` with the new head, so the first `types` run here failed on 71 unlisted rows of
`m5-corpus-head-3`, all of cases new to the head; no entry was stale, and no committed corpus row moved but the three M5a
names (`036f5ec3` and `aa77e73f` to `DIFFERENT-rejection`, listed; `e7af4314` to `both-reject-javac-first`, which needs no
entry). Each was listed in `m2-accepted-differences.tsv` with the class of its case's I1 row
(`<M0>/work/m5i/types-unlisted.txt` has each row, its origin test and its I1 entry):

| Kind | Rows | Class |
|---|---|---|
| `registry`, `DIFFERENT-provider` | 12 | Policy rejection: `JULC0099`, `JULC0098`, `JULC0080`, `JULC0101`, `JULC0095` on the javac provider (`SubsetRulesAtEveryEntryTest`, `JavacRecordMembersTest`), as their I1 rows |
| `registry`, `DIFFERENT-javac-type-rejects` | 8 | Policy rejection: 5 `JULC0092` (D7), 2 `JULC0012` of a type parameter named like a type (D15, M5a section 16), 1 `JULC0014`, the varargs component (O6) |
| `registry`, `DIFFERENT` | 12 | 8 Semantic fix, T33/#286's family: a user record named like a ledger type (`TxId`, `TokenName`, `PolicyId`, `DatumHash`, `ScriptHash`, `ValidatorHash`, `PubKeyHash`) or read by its spelling (`Foo`) that the reference does not register; 2 Diagnostic change, a local record both compilations reject; 2 a local record in code nothing lowers (I1 identical, no script holds it) |
| `registry`, `DIFFERENT-rejection` | 1 | Diagnostic change, `JULC0012` (`036f5ec3`) |
| `reference-rejects` | 14 | 3 permanent (an unpaired surrogate, `JULC0093` on javac), 3 Accepted by decision (a lone surrogate in a comment, r11), 2 Accepted by decision (a guard JavaParser does not parse), 6 scheduled for acceptance (ADR-065 I2c escape shapes) |
| `unreadable` | 2 | A library compiled as the root and given in the pool: javac's one model holds the class twice (`duplicate.class`); the compilations agree (I1 identical) |
| `encoding` | 16 | 14 Semantic fix, the same T33/#286 records (the reference's Data is the ledger type's bytes, javac's `Constr 0 [a]`); 2 Policy rejection (`C.V`, `C.W`, below) |
| `conflict` | 3 | Policy rejection: `eacc8ffb`, `68c98027`, `7e2c39d0` (below) |

**The conflicts.** Three `JavacStopsTest#theDefaultFrontendsAnswerIsKnown` cases declare a `@NewType` record as a sealed
interface's variant (`sealed interface W permits V { } @NewType record V(BigInteger value) implements W { }`). Both registries
are equal (`types-m2.tsv` row `equal`) and hold the variant as a constructor with no fields, so the encodings gate's encoder
fails on an integer sample of it: the gate calls that a conflict, and before this integration it could not list one. The
javac frontend rejects each program with `JULC0127` (r17, a variant that is not a plain record), so no script on it holds the
type; the reference's reading is the one `JULC0127` closes. `75a8d388` lets `m2-accepted-differences.tsv` list a conflict,
kind `conflict`, by its case key with a reason, as an encoding is listed by its layout; nothing else of the gate changes.
This is a change to what the gate accepts, so it is a review focus.

**The types tables copied in**, after these diffs against `HEAD`: `types-m2.tsv` 17,080 to 24,151 rows (7,635 added, 564 removed,
3 changed: the three above); `encodings-b1.tsv` 902 to 2,054 lines, only additions; `blueprints-b1.tsv` 1,175 lines added and
508 removed, every removed line a case no longer recorded, none changed; the two totals files. `types-gate` then passes.

## 4. M6a moved no compiled program

The I1 row table of the merged tree (`reference-shrink`'s run at `a148f06e`, 24,167 rows, and then `table-shrink`'s frozen
`lowering-i1.tsv`) is **byte for byte** the table frozen from M5a alone in M5a's scratch view (`<M0>/work/m5a-fix/i1/view`,
section 17's `out-c`, at `c51f3e59`'s code, whose code `638d23ad` equals: `git diff --stat c51f3e59 638d23ad` touches only
docs and tables), and so are `lowering-i1-totals.tsv` and `lowering-reference.tsv`. **0 moved rows.** The tool change of G1
does not touch a written row (`Row.rejects` is not in the table). A copy of the M5a tables is in `<M0>/work/m5i/m5a-frozen/`.

## 5. The gates

`build/m5i/gates.sh` from a clean, committed tree at `c9ffdb47` (the freeze, the tool changes and r17; `dirty-after 0`):

| Gate | Command | Result |
|---|---|---|
| baseline | `freeze.sh baseline-gate` | 0: equal to the frozen tables |
| I1 | `freeze.sh lowering` (I1 over the committed corpora and `m5-corpus-head-3`) | 0: 24,167 cases, **moved 0, shrunk 0**; `i1a-identical` 14,405; `not-ported` and `reference-rejects-not-ported` 0; no `JULC0084`; `DIFFERENT-javac-rejects` 1,075, `-rejection` 570, `-bytes` 396, `-javac-accepts` 89, `-diagnostics` 78, `-provenance` 52, `-pir` 1, `-pir-unaccepted` 3, every one listed |
| types | `freeze.sh types-gate` | 0: 24,151 cases |
| full test | `g.sh test --rerun --continue` | 19,099 tests: 18,567 passed, 530 skipped, **2 failed**: `JulcAnnotationProcessorFrontendTest.theOptionReachesTheCompilation` and `JulcPluginFrontendTest.theOptionReachesTheCompilation` pinned `JULC0084` for `(short) x`, which M5a made `JULC0124` (M5a ran neither module). `1c2afab6` reads `JULC0124`, as the testkit's sibling already did; `:julc-annotation-processor:test` (30) and `:julc-gradle-plugin:test` (51) then pass, 0 failures |
| pair | `:julc-compiler:pairCaseTest` | 0: 71 tests |
| on-chain harness | `:julc-e2e-tests:onChainHarnessTest` | 0: 16 tests, offline (no node or subprocess; tag `onchain-harness`) |
| javac 21 | `:julc-difftest:test --tests '*Javac21ParityTest*' -Pjulc.javac21=<21.0.3-tem>/bin/javac -Pjulc.javac21.required=true` | 0: 51 tests |
| rediscovery | `:julc-difftest:rediscoveryDiffTest -Pdifftest.profile=<p>` | `REDISCOVER_209`, `_280`, `_146`, `_291`, `_291_LOOPS`: 0. `REDISCOVER_162`: 1, by design (below) |
| Blaster | `verification/blaster/scripts/prepare-artifacts.sh` | 0; the artifacts and lock unchanged (`dirty-after 0`) |

**`REDISCOVER_162`** fails as ADR-066 designs it: the profile steers G1 to #162's shape, and the campaign stops at try 2
(plan `[1,0,…,0,2]`, seed `20261030`) on the reference's rejection, `Reassignment of switch case-pattern variable 'v1' is not
supported`, at `Probe.java:24:21` on all four levels (`julc-difftest/build/difftest/last-failure.diffcase`). Its log equals
G4's but for the worktree's paths. Nothing else failed.

**No live DevKit was used.** A DevKit was running on this machine; nothing here touched it. The full `test` does not run
`julc-e2e-tests:test` or `julc-plugin-test:test` (each needs `-Pe2e`), and `onChainHarnessTest` is offline.

**D10's set.** As at M3's and M4's gates, `adr068Baseline` runs every `.diffcase`, G0's 376 programs on 4 inputs and G1's 150
fixed-seed tries (seed `20261030`, profile `DEFAULT`, `G1 r9`) through the harness, and must equal the frozen observations:
1,912 runs, `AGREE` 7,144, `MISMATCH` 236 (the saved `DIVERGE` pins), `REJECTED` 256 (the `reject-` pins), `INCONCLUSIVE` 8,
`CRASH` 4, unchanged but the four new cases. The full test runs them on `frontend=JAVAC` and the default:
`RegressionCasesTest` (254 passed), `Adr068SavedCasesOnJavacTest` (42), `G0ExhaustiveTest` (3), `G1PropertyTest` (the fixed
seed), `Adr068LoopCasesOnJavacTest` (59), all passing.

## 6. r17

The ADR's r17 takes M5a's section 10 as section 17 corrects it, with G1's classes:

- D6: `.equals` between two types no class can share is `JULC0078` (was `JULC0084`); a class that could be both, an
  intersection or a mixed choice is `JULC0087`; two containers of unrelated arguments are `JULC0087`; a choice is held to one
  representation (`JULC0012`); a narrowing cast of an integer literal is its Java value.
- D13: the "Policy rejection at run time" row and its four bounds; "Representation only" for a both-accept `DIFFERENT-pir`
  row; the `DIFFERENT-pir-unaccepted` row class and its classes by side; script size in the budgets.
- D15: the table's new rows (`JULC0078`'s plain `equals`, `JULC0012`, `JULC0123` to `JULC0129`, `JULC0014`), and the varargs
  record component's `JULC0014` (O6).
- The milestone table marks M5's entry gate met, with this file and `068-m5a.md`; the revision history has r17's entry, and
  the header's dates include it. IDs are unchanged.

Stale wording: the ADR's other `JULC0084` mentions are history (M2's, M3a's and M3's results, r12's and r13's entries) or the
rule for constructs not ported, and stay. M6a's proposed D12 text (`068-m6a-tools.md`, "the maintainer's decision") is not
applied: it is not part of r17.

## 7. What is left

- **O1 and O2** (the choice rule and M5a's policy codes): r17 states the rules as M5a implements them; the follow-ups keep the
  rows for the maintainer's confirmation.
- **O8** (17 Cost change rows grow) for M5's cost and size report; **O10** (a compile-time warning for an unread `find`);
  **G3** (tie a run-time entry to a `FAILS-CLOSED` recheck), added as a follow-up.
- **Review focus:** the types rows of section 3 (71 entries classified here, each from its case's I1 entry, none by a new
  decision), and the gate change for `conflict`; G1's side check.
- **M5's exit criteria** (the default flip, full build, external examples and `julc-helloworld`, Elci's smoke, native and Wasm
  smokes, the cost and size report) are not started here.
- Codex's read of M5a's, M6a's and this head (`068-followups.md`).
