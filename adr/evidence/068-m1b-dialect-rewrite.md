# ADR-068 M1b: the dialect rewrite

The M1b evidence of [ADR-068](../068-javac-frontend-migration.md): every in-repo source is Java javac accepts (D6), or
is listed below with the reason it is not, and the javac gate's default is `enforce`. The reference is **B1**
([`068-m1c-reference-b1.md`](068-m1c-reference-b1.md)); M1c was merged at `f58bb831`.

## Result

| Item | Value |
|---|---|
| Base | `42e7bd51` (M0's head), then M1a, M0 rounds 2–4 and M1c merged in |
| Evidence measured at | `e0b3922e` (code); later commits add evidence only |
| Gate default | `enforce`; `off` and `report` stay available (`CompilerOptions.setJavacGate`, `-Djulc.javacGate`) |
| Full suite | 12,711 tests, 0 failures, 0 errors, 530 skipped; outer `BUILD SUCCESSFUL in 14m 19s` |
| Frozen corpus, gate off | 11,410 of 11,417 cases byte-identical to B1; the other 7 are the Blaster fixtures M1b rewrote; semantic, budget, specialization and rejection-inventory files have no differing row |
| Frozen corpus, gate on | every case javac accepts is byte-identical to the gate-off row; nothing else changes |
| In-repo cases javac rejects that B1 accepts | 5,241: 5,135 rewritten and byte-identical, 27 rewritten and different, 76 with no Java form (table U), 3 expected (2 reference fixtures, 1 parity probe) |
| Open for review | Table U (95 recorded cases have no Java form); 13 rewrites grow (11 cases, 2 Blaster fixtures) |

## Commands

```sh
# The worklist. With enforce the default, report mode is explicit; tests that pin enforce fail in this run, by design.
./gradlew test --offline --continue --rerun -Pjulc.javacGate=report -Pjulc.javacGate.report=build/javac-gate
./gradlew :julc-compiler:javacGateSummary -Pjulc.javacGate.report=build/javac-gate

# The full suite, with the default gate
./gradlew test --offline --continue --rerun
./gradlew :julc-compiler:pairCaseTest :julc-e2e-tests:onChainHarnessTest --offline --continue --rerun

# Byte identity, over M0's recordings (never a re-recording): gate off, then gate on
./gradlew :julc-difftest:adr068Baseline -Padr068.corpus=<M0 recording> -Padr068.corpus.external=<M0 external> \
    -Padr068.corpus.elci=<M0 Elci> -Padr068.external=<julc-examples at 22b4e00f> -Padr068.out=<off> --offline
./gradlew :julc-difftest:adr068Baseline <the same> -Padr068.out=<enforce> -Padr068.gate=enforce --offline
./gradlew :julc-compiler:javacGateCorpus -Pjulc.javacGate.corpus=<M0 recording> -Pjulc.javacGate.out=<gate-m0>

# The rewrites: a recording of this head, used only to read the new sources' rows
./gradlew :julc-compiler:test :julc-compiler:pairCaseTest :julc-stdlib:test :julc-testkit:test \
    :julc-difftest:test :julc-examples:test --offline --continue --rerun -Pjulc.corpus.record=build/julc-corpus-m1b
./gradlew :julc-difftest:adr068Baseline -Padr068.corpus=build/julc-corpus-m1b -Padr068.out=<rewritten> --offline
./gradlew :julc-compiler:javacGateCorpus -Pjulc.javacGate.corpus=build/julc-corpus-m1b -Pjulc.javacGate.out=<gate-m1b>

python3 adr/evidence/068/compare-m1b.py <off> <enforce> <rewritten> <gate-m1b>/gate-corpus.tsv <M0 recording> \
    build/julc-corpus-m1b adr/evidence/068 <gate-m0>/gate-corpus.tsv
```

`adr068Baseline` runs with the gate off unless `-Padr068.gate=enforce` is given, in its ledger, its harness runs and
its evaluation replays, so it stays the JavaParser frontend's. Generated here:
[`m1b-gate-corpus-summary.txt`](068/m1b-gate-corpus-summary.txt), [`m1b-rewrites.tsv`](068/m1b-rewrites.tsv) and
[`m1b-diagnostic-changes.tsv`](068/m1b-diagnostic-changes.tsv).

## The worklist

`javacGateSummary` before this round's changes (`fa8f5b7c`): **1,102 rejected inputs**, against 6,007 at M1a's
`689c40b4`, 0 internal gate failures. All 1,102 are expected rejections; none was a migration case. The largest keys
are `pattern.dominated` 717, `not.exhaustive` 144, `duplicate.default.label` 143, `prob.found.req` 52 and
`guard.has.constant.expression.false` 35; 952 inputs come from `SwitchParityCorpusTest` and 47 from
`Javac21ParityTest`.

A report run does not see two kinds of input: a test that compiles with an explicit `ENFORCE` or `OFF`, and
`pairCaseTest`. So the classification below uses the gate over a recording of the head, which sees every source:
11,857 cases, **1,405 rejected** (1,359 distinct roots), 0 differing from the whole exported `java.base`.

| Category | Cases | Action |
|---|---:|---|
| Differential corpora, where javac is the oracle (`SwitchParityCorpusTest`, `Javac21ParityTest`, saved cases) | 1,000 | None; they expect `JULC0085` where javac rejects |
| Negative fixture, source unchanged since B0, javac first | 177 | D13 diagnostic change: `JULC0085` with javac's key |
| Negative fixture made Java so its rule still fires, javac first | 25 | Expectation is `JULC0085` with the rule's own key; the original is kept (below) |
| Original negative fixture (D13), kept | 79 | `Adr068NegativeOriginalsTest` |
| Reference-site fixture, gate off (M0) | 15 | `Adr068RejectionFixturesTest` compiles at the reference's sites |
| The gate's own tests and host fixtures | 14 | Deliberate dialect or broken sources |
| **Table U**: no Java form today, compiled with the gate off | 95 | Open, for review |

Inputs scheduled for acceptance stay JuLC-rejected: all 21 keep their B1 cell with the gate on, and no scheduled input
is rejected by javac.

### Negative fixtures (D13)

Earlier M1b commits changed the sources of 83 negative cases. Most are JuLC subset rules on sources that only lacked
imports (a permitted fix, code kept); the rest were invalid Java rewritten so that javac's own rule fires
(`missing.ret.stmt`, `not.exhaustive`) rather than a missing import. D13 never rewrites negative fixtures, and the loss
was visible: the I2a coverage gate failed on `U-newtype-of-arity`.

The rewritten tests stay, because they test the rule. `Adr068NegativeOriginalsTest` restores the originals as frozen
resources (`src/test/resources/adr068/negative-originals`, from M0's recording), 83 rows, each compiled through its B0
entry point and options. With the gate off each gets its B1 rejection; with the default gate each gets javac's keys
(79) or, where javac accepts it, the same JuLC rejection (4). In the frozen-corpus run they are ordinary rows again, and
the coverage gate passes on the frozen corpus and on the head's recording: 146 sites, 117 covered, 29 not source
rejections, 0 uncovered, 0 uncatalogued, 0 contradicted.

### Changed expectations

[`m1b-diagnostic-changes.tsv`](068/m1b-diagnostic-changes.tsv) gives the frozen corpus's 1,266 diagnostic changes by B1's
code and javac's first key. The largest:

| B1 | javac | Cases |
|---|---|---:|
| `JULC0071` | `pattern.dominated` | 601 |
| `JULC0005` | `not.exhaustive` | 133 |
| `JULC0082` | `duplicate.default.label` | 83 |
| `JULC0071` | `duplicate.default.label` | 60 |
| `JULC0072` | `prob.found.req` | 49 |
| `JULC0041` | `prob.found.req` | 44 |
| `JULC0072` | `guard.has.constant.expression.false` | 42 |
| `JULC0070` | `int.number.too.large` | 27 |
| uncoded `CompilerException` | `cant.resolve` | 27 |

Each is I2b's javac-first rejection. A test of a JuLC rule on otherwise valid Java keeps its JuLC code; `JULC0013`,
`JULC0008`, `JULC0054`, `JULC0056`, `JULC0059` and `JULC0003` are the common cases.

### Table U: no Java form today

These are JuLC features javac rejects and D6 does not list, so there was no rewrite to apply. Their tests compile
with the gate off, through `DialectGate` or a `compileDialect*` helper, each with a comment naming the reason. Counts
are recorded cases at the head; 76 frozen-corpus cases are among them.

| ID | Construct | Cases | javac | Java alternative |
|---|---|---:|---|---|
| U1 | A for-each over a `JulcMap`, or `head()` entries (`JulcMap` is not `Iterable`, `head()` returns `Object`) | 24 | `foreach.not.applicable.to.type`, `cant.resolve.location.args` | `keys()` and `values()` (tested); no entry type |
| U2 | `ListsLib.any/all/filter/map/find/foldl/zip`, registry intrinsics with no declaration | 42 | `cant.resolve.location.args` | Instance methods, except `foldl` and `zip`: an accumulator loop |
| U3 | A lambda bound to `var` | 6 | `cant.infer.local.var.type` | None |
| U4 | Implicit `Data`/bytes coercion at a call | 6 | `prob.found.req` | `Builtins.unBData` and friends, at some cost (below) |
| U5 | Dialect methods on `byte[]` and wrappers (`.hash()` and `.length()` on bytes, `.value()` on a hash) | 8 | `cant.resolve.location.args` | The test's subject is the dialect |
| U6 | `fromPlutusData` on a user record or sealed interface, a JuLC-registered factory | 4 | `cant.resolve.location.args` | None |
| — | Names only a custom registry defines (`LibraryDiscoveryCleanupTest`) | 5 | `cant.resolve.location` | A Java declaration |

Rejecting these is what enforcing means, but D6 and the Compatibility section name four dialect forms, not these. Under
`enforce` a user gets `JULC0085` for each. **Decision needed:** per construct, an API (for example a `JulcMap.entries()`
with a Java entry type) or a D6 amendment naming the removal. Listed in
[`068-followups.md`](068-followups.md).

## Codemods (test-side only)

`DialectCodemod` in julc-compiler's test sources is the only rewriting tool, and nothing in it ships. It reads a gate
report and applies diagnostics-driven rules: imports, `BigInteger` operators, `compareTo(..) OP 0`, `equals`,
`BigInteger.valueOf`, `new X(v)` for `@NewType`, `static @Param`, `PlutusData.cast`, `java.util` to `JulcList`/`JulcMap`.
Gradle tasks: `dialectCodemod`, `dialectCodemodDebug`, `dialectCodemodMarkdown`, `dialectCompare`. The last compares the
JavaParser frontend's FLAT bytes before and after a rewrite.

## Generators fixed

| Generator | Change |
|---|---|
| `BinderNameIndependenceTest`, `Adr060Corpus` | Its renamings are Java; shapes that call U2's intrinsics compile with the gate off |
| `IfLoopContinuationTest` | `xs.size()` is a `long`, and `xs.get` takes a `long` |
| JRL transpiler (`JavaTranspiler`, `FactTranslator`) | Emits Java: record casts through `Object`, a qualified `@Param`, `PubKeyHash.of(signer)` |
| `OptimizationEvidenceMain`, the playground's bundled examples, `editor.ts` | Written as Java; a test compiles every bundled example |
| `julc-plugin-test` validators | Written as Java |

## The REPL decision (D9)

**The wrappers emit valid Java; there is no carve-out.** `ReplExpressionWrapper` (julc-compiler) attributes the input
in a probe method returning `Object`, then generates the method with the returned type, wrapping an `int` or `long`
result in `BigInteger.valueOf`. A javac rejection is the REPL's error, its line and column counted from the input.
`ExpressionEvaluator` and the CLI's `ExpressionWrapper`/`ReplEngine` share it, and no main source sets the gate. D9's
probe is `var v = (expr);`; this one returns the expression as `Object`, which also types a statement block.

## Docs and examples

41 files changed since the base. `README`, the docs, the AI starter pack and the verification guides are Java. The block scan (`dialectCodemodMarkdown`, every whole-unit fenced block alone against the bundled stdlib)
gives **110 valid, 16 unchanged, 69 off-chain, 408 fragments**. The 16 are illustrative on purpose (WRONG/CORRECT pairs in
one block, compact source files, cross-file examples, sketches). Fragments were checked by a `BigInteger`-operator
heuristic, which found `for-loop-patterns.md` still in dialect. Also fixed: missing stdlib imports in 45 blocks, the
`ListsLib` HOF sections, the map for-each sections (now `keys()` and `values()`, with tests in `JulcMapTest`), and
placeholder expressions that were not Java. Release notes and troubleshooting cover `JULC0085`, the rewrites and the
modes.

## Byte identity

Over M0's recordings, 11,417 cases:

| Row | Cases |
|---|---:|
| Gate off: shared with B1, every column equal | 11,410 |
| Gate off: differ, the rewritten Blaster fixtures (five FLAT-identical, two below) | 7 |
| Gate on: accepted by javac, FLAT identical to gate off | 4,427 |
| Gate on: JuLC rejects, same cell | 471 |
| Gate on: javac rejects a case JuLC accepts (javac rejection; 5,241 in-repo, 2 external) | 5,243 |
| Gate on: javac rejects first (diagnostic change) | 1,266 |
| Unreplayable | 10 |

`totals-b1.tsv` is reproduced byte for byte; `ledger-b1-semantic.tsv`, `-budget`, `-instantiations` and
`rejection-inventory-b1.tsv` have 0 differing rows.

The 5,241 in-repo javac rejections, against the Java in the head's recording, compared per test at all four levels:

| Outcome | Cases |
|---|---:|
| Rewritten, javac accepts, FLAT identical at every level | 5,135 |
| Rewritten, javac accepts, FLAT differs | 27 |
| Made Java as far as it can be, javac still rejects it, gate off, FLAT identical | 74 |
| Source unchanged, gate off (2 reference fixtures, 2 `O3ListCaseLoweringTest`, 1 `Javac21ParityTest`) | 5 |

The 27, in [`m1b-rewrites.tsv`](068/m1b-rewrites.tsv), old → new summed over the group; budgets are the recorded
evaluations of both programs on the same arguments:

| Group | Cases | `NONE` | `BASELINE` | `PV11_SAFE`, `PV11_COSTED` | Evaluations | CPU | Memory |
|---|---:|---:|---:|---:|---:|---:|---:|
| Generated loops, `xs.size()` as the `long` it is | 16 | 42,416 → 41,087 | 6,264 → 4,974 | 5,376 → 4,278 | 288 | 1,499,314,304 → 1,188,040,880 | 6,584,248 → 5,277,128 |
| Explicit decode at a typed call | 7 | 2,682 → 2,712 | 1,093 → 1,147 | 985 → 1,039 | 22 | 122,438,679 → 126,453,129 | 341,286 → 362,950 |
| `equals` for `==` on `Data` | 3 | 871 → 876 | 713 → 719 | 641 → 646 | 0 | not comparable | not comparable |
| `ContextsLib.trace` for `Builtins.trace` | 1 | 130 → 2,319 | 117 → 119 | 75 → 77 | 4 | 3,740,054 → 5,084,054 | 16,664 → 25,064 |

The Blaster fixtures: `typed-multisig` 432 → 437 bytes at `BASELINE`, `typed-multisig-broken` 360 → 363, with the same
kind of explicit decode; their artifacts are refreshed and the suite reports `SMT-VALID: all-properties-established`.

**The 11 rewrites that grow, plus those two fixtures, are D13 cost changes and need the review's decision.** M1's exit
says a rewrite costs no more than the order-correct lowering of its original. Each one spells out in Java a conversion
the dialect made implicitly, so there is no zero-cost spelling. Every `compareTo(..) OP 0` rewrite is byte-identical,
as M1c promised. Over 13,063 evaluations whose program changed between M0's recording and the head's, on the same
arguments, no value, trace or failure differs (153 further evaluations could not be paired).

## Blob

M0's review added a `throw` fixture after M1a derived the blob, so `IllegalStateException` was missing, the gate's only
difference from `java.base` on the corpus. Derived again on the head's recording: **70 classes**, 136,450 bytes, sha256
`c45dd2df24a5f1bd71cded55ab36bc425d13e4edc284b2f983c88acd5075cf0e`; 0 differences on the M0 and M1b recordings. `JavacGateProbeTest` and `JavacGateReviewTest` pin the new digest. The Web Image probe and
the native CLI were not rebuilt with it.

## Tests

`./gradlew test --offline --continue --rerun` at `e0b3922e`, default gate: **12,711 tests, 0 failures, 0 errors, 530
skipped** (XML totals of the `test` tasks), outer `BUILD SUCCESSFUL in 14m 19s`. `pairCaseTest` 71/0/0/0 and
`onChainHarnessTest` 16/0/0/0, also under the default gate, `BUILD SUCCESSFUL in 1m 18s`. M1a's head had 12,500. The
javac gate's report mode is not a green run any more: tests that pin `enforce` fail there, by design.

`Adr068NegativeOriginalsTest` adds 83, `JulcMapTest` 2. The one class that shrank is `ExpressionWrapperTest` (21 → 19): D9
removed the name heuristics and the `PlutusData` cast fallback those tests described, and `ReplEngineTest` gained the
evaluation, `:type` and `:pir` coverage. M1b's weakened assertion in `FailureReportIntegrationTest` is back to its original
with M1c's rule, and M1b's two golden regenerations are reverted: `hof-filter` and `nested-while` match B0's goldens
again. M1c's new tests needed two changes under `enforce`: the dialect
comparison test compiles with the gate off (it tests the JavaParser frontend's rule, D6), and a `@Param` array is
`static`, keeping `JULC0091`.

## Deviations and open items

| # | Item | Status |
|---|---|---|
| 1 | Table U is outside D6's list; 76 frozen cases have no accepted rewrite, which M1's exit asks for | Needs a decision; recorded in the follow-ups |
| 2 | 13 cost changes (11 rewrites, 2 fixtures) | Needs a review decision |
| 3 | 2 external `julc-examples` cases javac rejects (`ByteStringLibEvalTest`), and Elci | External: not rewritten here; check before release |
| 4 | `julc-e2e-tests` and `julc-plugin-test` integration runs need a DevKit | Not run; their sources are Java and `onChainHarnessTest` runs |
| 5 | Native CLI and Web Image with the new blob | Not rebuilt; M1a's digests are superseded |
| 6 | F401's per-compilation metadata and the cold-compile threshold | M1 exit items, not M1b's |
