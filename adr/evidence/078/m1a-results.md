# ADR-078 M1a: results

Implementation: `f7db99bc`. Tests: `3e7957f6`. Measured 2026-11-18/19 with JDK Liberica 25.0.4.

## In-repo byte oracles

- **`./gradlew :julc-compiler:test`** (the whole module, 7 min 9 s) passed on `f7db99bc` with no expectation changed.
  That includes `GoldenUplcTest`, the `*-pre-change-bytes.txt` goldens, `Adr060ByteSnapshotTest`,
  `LibraryOrderEquivalence`, the ADR-068 frozen reference, the `O11BlsTypesTest` pins, `ImportOrderInvarianceTest`,
  `LibraryBindingOrderTest`, `MethodOrderingTest` and `JavacLibrariesTest`.
  - Either their programs bind no library methods with equal content keys, or the first-use order equals the old name
    order, or the oracle compares up to library-let order.
  - No golden needed re-recording, so there is no explanation file of changed goldens.
- **Blaster** (`verification/blaster/scripts/prepare-artifacts.sh`, without `--update-lock`, offline, no daemon): the
  generated `artifact-lock.json` is identical to the committed `verification/blaster/artifacts/artifact-lock.json`.
  No fixture's bytes changed, so no refresh is needed.
- The full `./gradlew build --offline --no-daemon` on the final head is recorded in the PR.

## D5

- **`FirstUseRanksTest`** (10 tests).
  - First use rather than names.
  - Static initialisers before handlers, and handlers in order.
  - C22: a helper and its library copy are two bindings; references resolve lexically; a static initialiser reads the
    library copy.
  - C11, D1a′: unread helpers.
  - Shadowing by a local.
  - Cycle fallback, self-recursion, and a planted D1b residual decided by name.
  - Lemma 1 on 500 random libraries with twin groups and roots: a name decides only in D1b's fallback, and where it
    never does, a name-reversing rename keeps the order.
  - Lemma 2.
- **`LibraryTieOrderFixturesTest`** (8 tests, against `pre21-fixtures.txt`).
  - Every variant compiles exactly where it did on pre21.
  - The `SAME` fixtures give the base's bytes in all 8 modes: twins reached, C11, C12, C14 renamed together,
    self-recursion, static-initialiser first use.
  - **C12:** both spellings give `c8489246…`, 1,010 B, and succeed under `ExBudget(31_150_000, 110_000)`. pre21's
    `zeta` gave `4ab65773…`, 1,012 B, and exhausted.
  - **C20:** `[B, A]` compiles and `[A, B]` is rejected, in every mode.
  - **The known limits and negative controls differ:**

    | Fixture | Modes where it differs |
    |---|---|
    | C13 | all |
    | C14 member alone | all |
    | C19 | pv11-safe+maps, pv11-costed+maps |
    | C19c | pv11-safe, pv11-costed, both with and without maps |
    | C27 | all |
    | negative constant | all |
    | C26 | pv11-costed+maps |
    | negative unread body | none, none+maps, baseline+maps, pv11-safe+maps, pv11-costed+maps |

  - **C26:** each spelling's bytes equal pre21's in every mode, and the two spellings still differ at
    pv11-costed+maps. It remains a known limit, now asserted as expected to differ.
  - **I6a:** 512 evaluations compared with pre21 (result term, failure reason, traces); 0 inconclusive; no mismatch.
  - **I6b:** the only budgets that changed are C12's `zeta` at pv11-safe and pv11-costed. They went from 31,201,556 CPU
    / 102,366 mem to 31,073,556 / 101,566, which is the `alpha` program's budget.
  - **I4:** every source-map build maps the same DFS indices to the same lines across a fixture's variants.
  - **Which fixtures changed bytes from pre21:** only fixtures that bind library methods with equal content keys —
    twins reached, C11, C12, static-initialiser first use. The rejected cycle's rejection text differs only through the
    test JVM's I13 check (`m1a-pre21-recording.md`).
- **`LibraryTieOrderPropertyTest`** (seed 78, 3 programs, 40 rewrites each, 8 modes; 984 compilations).
  - Every rewrite compiles, as on pre21, and gives the base's bytes and source-map lines in every mode: 41/41 per
    program and mode.
  - On pre21 the same rewrites gave the base's bytes for only 2–23 of 41 (`pre21-programs.txt`).

## The gate, julc-examples half (ADR-078 § The gate)

- **Revision:** `../julc-examples@49614db8` (a scratch clone), `src/main/java`; 46 validator-annotated files.
- **Runner:** `Adr078ExamplesGate.java.txt`, compiled and run once on the pre21 class path (Maven artifacts) and once on
  the M1a class path (this worktree's `julc-compiler` test class path).
- **Compilation:** 43 compile and 3 are skipped on both. The skipped ones are `WingRidersPoolValidator`,
  `WingRidersRequestValidator` (a `return` inside a `while` loop) and the off-chain uverify file.
- **Result:** **pass.** For all 43 programs, the release FLAT (pv11-safe, source maps off), the source-map-on FLAT,
  and the complete `DFS index → SourceLocation` maps (`SourceMapSerializer` JSON) are identical
  (`m1a-gate-julc-examples-pre21.txt`, `m1a-gate-julc-examples-m1a.txt`).
  - The headers differ only in the class-path library count. The pre21 class path is JOUST's, which carries JOUST's
    own on-chain libraries; julc-examples resolves none of them.

## Compile time

Measured on the same julc-examples validators in release mode, 12 rounds in one JVM, median of rounds 3–12, in two
runs each:
- pre21: 1,977 ms and 1,962 ms;
- M1a: 1,970 ms and 1,973 ms.

The difference is within run-to-run noise (< 1%).

## Debug oracle (I4, D5; added after the first M1a review)

`julc-tools` `LibraryTieDebugOracleTest` drives JuLC's source debugger (`SourceDebugService`), the service the
playground and the Wasm engine use. The recorded pre21 side is `julc-tools/src/test/resources/adr078/pre21-debug.txt`.

**Fixtures** (`Adr078DebugFixtures`): 6, each with a base and a renamed variant, all compiled validators.

| Fixture | Rename |
|---|---|
| Twin bodies | `alpha` → `zeta` |
| Crowdfund-like pair of identical `?? 0` helpers used from two branches | `contribute` → `zqaai` |
| C11 (an unread helper reads a twin) | `alpha` → `zeta` |
| C12 (121 chained lets and 12 head reads) | `alpha` → `zeta` |
| Static-initialiser-only use | `zeta` → `aaaa` |
| Multi-purpose validator whose handlers first use opposite twins | `alpha` → `zeta` |

C22 (`compileMethod(root, …, List.of(root))`) cannot be opened in the source debugger, which takes a validator, and a
validator cannot be a library. It is covered at the compiler level.

**What is recorded per program:**
- the executable Java lines;
- with a Java breakpoint on every executable line and `continue` to the end, the line and column of every stop;
- the visible Java locals (names and values) at every stop;
- every change of Java location while stepping through the whole run (`goto` each step);
- the final status.

On pre21 that is 12 programs, 408 breakpoint stops, 408 locals snapshots (314 local values in all) and 82 source-step location changes.

**The three tests:**
1. **Same source.** Every program shows exactly what pre21 showed for it. This includes the renamed variants, whose
   bytes M1a changes, because the twins are now bound in first-use order.
2. **Through the rename.** Every renamed variant shows the base's pre21 trace, with each location carried token by
   token through the rename mapping. At least one fixture has locations that the rename moves.
3. **Negative controls.**
   - A line-shifted mapping never matches.
   - The identity mapping fails wherever the rename moves columns. The Crowdfund-like fixture's stops after the
     renamed identifier do.
   - The traces of different fixtures differ.

**Result:** all three pass.

## ADR-075's corpus replay (I6a; required for M1a's exit, ADR-078 r7)

The runner and comparator were corrected after Codex's second review (C12, C13), and again after its third (C20, C21,
C23, C24, C27). The results, commands, counts and the class-file fingerprints of both runs are in
`m1a-corpus-replay.txt`.

| | |
|---|---|
| Corpus | The ADR-074 corpus that ADR-075 used: `/Users/satya/work/bloxbean/julc-327-base/build/corpus327`, 20,407 manifests |
| Runner | `Adr078Replay.java.txt`. It decodes sources with `CorpusFiles.text`, restores the recorded options (target, level, cost profile, source maps, disabled rules), dispatches the recorded entry point, and writes one JSON record per line. |
| Comparator | `compare_replay.py.txt`. It validates every record against its kind and outcome, requires one higher-limit retry per exhausted evaluation and no other, fails on any harness error, pins the 55 excluded ids, exits 1 on any unaccepted difference, and reports budget-only differences, bounded comparisons and inconclusive cases apart. |

**Results.**
- **Coverage.** 20,352 manifests were replayed; 55 were skipped (JavaLibraryProvider entries and custom lookups).
  That gives 54,691 case × level compilations: 47,593 compiled and 7,098 rejected, with no harness errors. There are
  266,441 evaluations. 112 of them exhaust the transaction limit on both sides; they are bounded comparisons, and
  their higher-limit retries are identical in outcome, traces and budget.
- **Comparison.** The pre21 and head outputs are byte-identical: 0 unaccepted differences, 0 budget-only differences,
  0 inconclusive.
- **Comparator controls.** The comparator's negative controls behave as they should
  (`m1a-replay-comparator-controls.txt`).

This shows identical outputs. It does not show that the binding orders inside these programs are identical, and it is
not used to infer them (C19).

## Changes after the first M1a review (Fable M1A-F1–F6)

- **F1.** The `@Param` decoders are not walked: they hold no method reference. Recorded in ADR D1a (r7) and at
  `FirstUseRanks.Roots`.
- **F2.** The compiled-validator fixtures added to `LibraryTieOrderFixturesTest` (single entry, static initialiser,
  multi-purpose with opposite first uses) give identical bytes for both spellings in all 8 modes; on pre21 most of them
  differed. The debug oracle's fixtures are all compiled validators. JOUST's Crowdfund and Tips Java need JOUST's
  on-chain runtime, so the JOUST fixture is M3's gate (ADR r7).
- **F3.** The corpus replay above; ADR r7 makes it a required M1a exit item.
- **F4.** The property suite now has 10 programs (seed 78, 40 rewrites each, 8 modes, 3,280 compilations): 41/41 give
  the base's bytes in every mode. pre21 recording: `pre21-programs.txt`.
- **F5.** `LibraryBindingOrder.describe` now takes its child order from `children`, so there is one child order, which
  `FirstUseRanks` walks too. The ADR now names `LibraryBindingOrder.children`.
- **F6.** `Rule.UNREAD_BY_NAME` is documented: the last member of a tied group is labelled `UNREAD_BY_KEY`. The pre21
  Maven coordinates are in `m1a-pre21-recording.md`.

## Changes after Codex's M1a review (C1–C9)

- **C1 (code).** D1b took its call graph from `PirHelpers.containsVarRef`, a spelling search, so a local spelled like
  a library method counted as a call.
  - Both of Codex's hand-built PIR reproductions now give the correct result (`FirstUseRanksTest.aLocalSpelledLikeAMethodIsNotACall`):
    - unread equal-key `q.M.a = λx.x` and `q.M.b = λq.M.a.q.M.a`: `a` is chosen by name, where `b` was chosen
      `UNREAD_BY_KEY` before;
    - both locals spelled like the other method: no spurious `CYCLE`.
  - D1b now uses each method's free names (`PirSubstitution.collectFreeVarNames`), itself excluded.
  - Kahn's dependency graph and the content key's computation order still use `containsVarRef`, which is pre21's
    behaviour. The key's encoding already resolves variables lexically. No Java source can produce the difference,
    because library binders are qualified (dotted) and Java locals cannot contain dots. Bytes are unchanged: the corpus
    replay below is identical.
- **C2.** Two new assembly fixtures:
  - a multi-purpose validator whose handlers are declared against tag order and first use opposite twins;
  - an unread helper declared before a `compileMethod` target.

  Each has an emitted-order assertion: the first-used twin's `let` comes first in the PIR. Each of Codex's three
  mutations, applied one at a time, now fails `theAssemblysRootsDecideTheEmittedOrder`; removing the roots also fails
  `sameFixturesGiveTheBasesBytesInEveryMode`:
  - no purpose-tag sort;
  - `Roots.NONE` in `validator()`;
  - no target root in `method()`.
- **I3.** The sorted multiset of method binders in each variant's PIR equals pre21's in every mode. C22 binds both the
  helper and the library copy of `q.H.g`.
- **C3, the I6a oracle.**
  - **What is compared:** results as FLAT-encoded terms, the whole failure reason, and traces as a list.
  - **Returned functions:** the first argument vector is empty, so the result is the function itself, which is then
    applied to the next vector and compared.
  - **Traces:** a fixture traces `["two", "t", "t", "one"]` in order.
  - **Exhaustion:** a one-sided exhaustion is replayed at a higher limit. pre21 terminating where this build does not
    is a failure; both exhausting is inconclusive.
  - **C12's tight limit:** the outcomes are recorded for pre21 (`alpha` Success, `zeta` Exhausted) and asserted for M1a
    (both Success).
  - **Result:** 1,152 evaluations compared, 296 of them applied functions and 32 with traces; 0 inconclusive; no
    mismatch.
  - **Budgets that changed (I6b):** only C12's `zeta` at pv11-safe and pv11-costed, now the `alpha` program's.
- **C4.** The complete pre21 source maps (every DFS index, with file, line, column and fragment) are equal for every
  fixture variant and source-map mode. Renamed variants equal their base's map carried through the rename mapping. The
  mapping is token by token; fragments are renamed all at once, and a fragment the compiler truncated is compared on
  the prefix both sides have. Without the mapping, the maps differ (negative control).
- **C5.** A stalled recursive group is emitted in pre21's `(content key, name)` order. The cycle fixture's diagnostic
  equals pre21's with the I13 check off ("Unbound variable: q.M.odd") and on (the I13 message).
- **C6.** An `Observer.chose` hook reports every equal-key choice of Kahn's algorithm. On 500 random DAGs:
  - the winner always has the lowest rank;
  - unread identical twins lie in one equal-key run (Lemma 3's block; a non-identical member of the run can sit between
    them);
  - a dependent is bound between reached twins in some cases (Lemma 4).
- **C7.** A recursive group's names are bound once for all of its parts, in both the key and the walk. A
  4,096-binding group is walked in linear time.
- **C8.**
  - Rewrites alternate between `REVERSED` (names in exactly the reverse of the base order) and `RANDOM`.
  - Coverage is asserted per kind and mode (200 of each per mode).
  - The recording carries a digest of every generated source, so a stale `pre21-programs.txt` fails the test.
  - On M1a: 41/41 per program and mode. On pre21: 1–24 of 41.
- **C9.** The Javadoc states the ready-method order and where a name decides; `FIRST_USE` covers D1b's walked callees.

## Changes after Codex's second M1a review (C10–C19) and Fable's (F7, F8)

- **C10 (code).** One lexical dependency graph (free references, self excluded) now schedules the content keys, D1b
  and Kahn's algorithm (`LibraryBindingOrder.dependencies`).
  - **Tests:** Codex's custom-registry shape (a closed inner `let q.M.b` inside `q.M.a`, with `b` calling `a`) now
    orders `a` outside `b` with no cycle (`aClosedInnerBindingSpelledLikeAMethodIsNotACall`). Codex's Lemma 3 case is
    a test (`anUnreadAlphaEqualPairIsAdjacentWhenALocalSpellsAnotherMethod`). The genuine-cycle fixture stays rejected,
    with its diagnostics pinned.
  - **Bytes:** C10 changed no bytes. The corrected corpus replay is byte-identical to pre21's, and the julc-examples
    gate (release FLAT, maps-on FLAT, complete source maps) is identical for all 43 programs.
  - **Docs:** ADR r8 states the compatibility note for custom registries and other frontends, and restates Lemma 3.
- **C11.** `aStaticInitialiserFirstUseIsBoundOutermost` asserts the emitted outermost twin for the static fixtures,
  through both entry points. Replacing `staticValues(...)` with `List.of()` in `validator()`, or in `method()`, now
  fails it.
- **C12, C13.** The corrected replay and comparator, above.
- **C14.** Traces are written as a JSON string array. `theTraceEncodingIsUnambiguous` is the negative control:
  `["a","b"]` against the single trace `a", "b`.
- **C15.** Returned functions are also replayed at the higher limit. The I6a report separates demonstrated mismatches
  (0) from inconclusive cases (0).
- **C16.** A `REVERSED` rewrite now reverses the base's qualified-name order exactly: one package, class and method
  names descending against the base's comparator order. The generator checks it for every pair of methods.
- **C17.** `ValidatorAssembly.observing(...)` is a test hook. It wraps the frontend's `methodBound` callback and
  reports every binding with its handler. `everyBindingReachesTheMethodBoundCallback` asserts that the callback
  receives exactly the method binders of the emitted PIR, in every fixture and mode, both handlers of the
  multi-purpose fixtures included. Suppressing the callback for one library method now fails it.
- **C18.**
  - The random-DAG Lemma 3/4 assertions use the actual content keys (`LibraryBindingOrder.contentKeys`), and Lemma 4
    uses the actual dependency paths.
  - `aCompiledArtifactsEqualKeyChoicesTakeTheLowestRank` collects the equal-key choices of compiled artifacts through
    the hook.
- **C19.** The `LibraryBindingOrder` Javadoc says the order depends on the program's roots, and
  `m1a-pre21-recording.md` is updated. The content key's variable lookup is now constant-time per reference (a name
  → positions map), so the linear-time claim covers the key as well as the walk.
- **F7.** Superseded by C10: there is now a single graph, documented at `LibraryBindingOrder.dependencies`.
- **F8.** The debugger suites that are green on the head:

  | Module | Suites |
  |---|---|
  | `julc-tools` (ran on the head) | `SourceDebugServiceTest` (16), `LibraryTieDebugOracleTest` (3), `JavacDebugMetadataTest` |
  | `julc-playground` | `SourceDebugControllerTest` |
  | `julc-compiler` | `SourceMapCompilerTest`, `SourceMapEvalTest`, `DebugCompileResultTest`, `DeepDebugMetadataWalkTest`, `JavacContractAndDebugTest` |
  | `julc-vm-java` | `SteppingEvaluationTest` (6), `SourceMapVmTest` (14), `JavaVmTracingTest` (10), `BuiltinTraceIntegrationTest` (9) |
  | `julc-vm-truffle` | `JulcDebuggerTest` (14), `TruffleDebuggerTest` (15), `TruffleBuiltinTraceTest` (7) |

  The two VM modules do not depend on `julc-compiler`. Gradle kept their test results up to date (green, from the
  first full build at `21805157`), so they did not re-run.

## Changes after Codex's third M1a review (C20–C28) and Fable's (F9)

- **F9 = C26 (code).** The assembly observer is no longer a public static hook with thread-locals.
  - `ValidatorAssembly.Frontend` carries it as a component, beside `methodBound`. `ValidatorAssembly.observing()`,
    `observed()` and both thread-locals are gone.
  - `methodBound` is now a `MethodBound(handler, binder, binding)`. `wrap()` passes the handler tag explicitly to
    `wrapMethodBindings` and `bindLibraries` (`-1` for a single-purpose validator or a method).
  - `JavacCompilation` keeps the observer in a field and calls it from its own `methodBound` callback, so the test
    records what the frontend receives.
  - The test entry point is `Adr078AssemblyBridge`, in the test sources. Production code gains no observer entry
    point.
  - **API note.** `ValidatorAssembly.Frontend` is public because `JavacCompilation` lives in another package. Its
    `methodBound` type and arity changed from pre21's, and `MethodBound` is a new public nested interface. Only
    `JavacCompilation` and a test construct a `Frontend`. No other module or sibling repository uses it.
  - **Bytes.** Unchanged. The corrected corpus replay and the julc-examples gate (release FLAT, maps-on FLAT and the
    complete source maps) are identical to pre21's on the round-3 classes.
- **C20.** The comparator validates each record against its kind and outcome: required fields, no unknown kinds,
  statuses, outcomes or fields. It requires exactly one higher-limit retry per exhausted evaluation and rejects
  orphans. It compares matched higher-limit retries: semantics, and budgets reported as budget-only. All of these
  controls are in `m1a-replay-comparator-controls.txt`:
  - a missing higher-limit record;
  - an orphan retry;
  - `compile/ok` without `flat`;
  - an unknown kind;
  - a higher-limit CPU change, which is reported and passes.
- **C21.** In the runner, preparation (options, sources, staging files) and result adaptation are outside the compiler
  call's exception boundary. A failure there is a `harness-error` record, and any harness error fails the comparison,
  even one that both sides share. The control runs the runner with `-Dadr078.tmp` set to a regular file. The two
  `compilePath` manifests give harness errors, not rejections. Spliced into both outputs, they make the comparator exit 1.
- **C22.** The exception for custom registries and other frontends is in D1b, D2 and the release note (ADR r9). Two
  tests pin it end to end, both recorded on pre21 (`c22-probes.txt`, `C22Probes.java.txt`):
  - `aCustomRegistrysMaterialisedBindingIsNoCall`: pre21 binds z, a, b; the head binds a, b, z.
  - `aGenuineCycleBesideACustomRegistrysBindingIsTheOneReported`: pre21 rejects with `Unbound variable: q.M.a`, the
    head with `Unbound variable: q.M.d`.
- **C23.** The runner always passes the recorded library list, even an empty one, so it never falls into the
  class-path-scanning overload. It checks the recorded cost profile's parameter hash.
- **C24.** Bounded comparisons (112, both sides exhausted) are counted apart from inconclusive ones. A one-sided
  exhaustion that persists at the higher limit is inconclusive, reported, and passes (ADR I6a). One that the higher limit
  resolves is compared. Both are controls. The fixture oracle checks that the evaluation ids made equal the recorded
  ones, both ways.
- **C25.** `everyBindingReachesTheMethodBoundCallbackOfItsHandler` compares, per handler, the multiset of binders the
  frontend's callback received with that handler's emitted PIR. Each received binding must also enclose the next one
  in the handler's chain. These mutations fail it:
  - giving one library binding the other handler's tag (both a symmetric and an asymmetric case);
  - suppressing the callback for one library method.

  Deleting the higher-limit applied evaluation, or the C12 tight limit, fails `evaluationsMatchPre21`.
- **C27.** The 55 excluded case ids are pinned (SHA-256 `b95d72fa…`); an extra exclusion fails. The ADR's exit
  criterion states the 20,352-manifest scope (r9).
- **C28.**
  - The README's FLAT count is now 47,593.
  - `m1a-pre21-recording.md` gives the 1,152 evaluations, the exact diagnostics and the two-way id check.
  - The `LibraryBindingOrder` class Javadoc states the order: content key, then first-use rank. A qualified name
    decides only in D1b and in a stalled group.
  - The runner imports `NoSuchAlgorithmException`.
  - Each run log starts with the SHA-256 of the compiler class files it loaded.

## Changes after Codex's fourth M1a review (C29–C35) and Fable's (F10)

Main code is frozen at `6c68f45a`; this round changes test sources, evidence and docs only.

- **C29.** The comparator now reads the corpus. Each side's records must be exactly those the corpus calls for:
  - one manifest record per case directory, with the entry and lookup its `case.txt` records;
  - one compile record per eligible case and level directory;
  - seven transaction-limit evaluations for each `compileMethod` case with the default lookup that compiled.

  Every digest must be 64 hex digits, and a rejection must be a class name and a message digest. Each of these fails
  with the same edit made to both sides:
  - an evaluation and its retry deleted;
  - only the 55 exclusions left;
  - a null FLAT digest;
  - a reason that is not a digest;
  - a rejection without its digest;
  - an evaluation the corpus does not call for;
  - a manifest entry that is not the corpus's.
- **C30.** `evaluationsMatchPre21` decides every evaluation that exhausted the transaction limit, on either side or
  both, at the higher limit (`atTheHigherLimit`):
  - both replays terminate: their outcomes must be equal;
  - an exhaustion persists on either side: inconclusive.

  The control `theHigherLimitDecidesAnExhaustion` checks that a pre21 success against a head failure is a mismatch.
  The comparator's bounded case follows the same rule, with three controls.
- **C31.** While preparing, the runner rejects, as a harness error:
  - an entry point it does not dispatch;
  - a lookup other than `default:org.julclang.stdlib.StdlibRegistry` or `none`;
  - a `compileMethod` case without a method;
  - a cost profile without its parameter hash.

  The controls are `compileMethodV2`, a mistyped lookup and a profile missing its hash. The round-3 runner recorded
  them as a rejection, a compile without a lookup, and a successful compile. The round-4 runner records each as a
  harness error. No corpus manifest trips these checks, so the archived outputs stand.
- **C32.** `everyBindingReachesTheMethodBoundCallbackOfItsHandler` finds each handler's spine inside the dispatcher
  branch for its purpose tag (the value of `#result__tag<n>`). The binders each tag's callbacks received must equal
  that branch's. Where the emitted PIR keeps the assembled terms, the received chain must be that branch's spine
  itself; at least four multi-handler compilations are checked this way. These mutations of main code, each made
  temporarily and reverted, fail the test:
  - swapping tags 0 and 1 at every callback;
  - suppressing one library method's callback;
  - reassigning one method's tag.
- **C33.** The ADR's compatibility section records the `ValidatorAssembly.Frontend` signature change as an accepted
  P2 deviation: `methodBound` now receives the handler tag, and an `observer` component was added. The release note
  has an API line: out-of-tree frontends must adapt. Main code is unchanged.
- **C34.** Every one-sided exhaustion that the higher limit resolves to the same result is reported as a budget-only
  difference, with both completed evaluations' budgets. It still passes I6b.
- **C35.** The ADR's compatibility summary covers custom registries and other frontends, not only equal-key methods.
  `m1a-pre21-recording.md` already gives the 1,152 evaluations, the exact diagnostics and the two-way id check (r9).
- **F10.** `AssemblyObserver.bound` duplicates what `MethodBound` receives. This is by design: `JavacCompilation`
  forwards its own callback to the observer, so the test records exactly what the frontend received. It is to be
  simplified later; no code change now.
- **Re-runs.**
  - The comparator, with the corpus, on the archived outputs: 0 unaccepted, 0 budget-only, 0 inconclusive.
  - The controls: `m1a-replay-comparator-controls.txt`, from `compare_replay_controls.py.txt`.
  - The `adr078` fixture tests: 19 and 1, green.
  - `./gradlew build` on the exact head.

## Changes after Codex's fifth M1a review (C35–C41)

Main code stays frozen at `6c68f45a`; test sources, evidence and docs only.

- **C36 (P1).** Both oracles now use one rule, `afterAnExhaustion` in D5 and the same branch in the comparator, for
  any evaluation that exhausts the transaction limit on either side.
  - Each side's evidence is its transaction-limit outcome, or its higher-limit retry where the transaction limit
    exhausted.
  - Where both terminate, results and complete traces must be equal.
  - Where an exhaustion persists, the case is inconclusive only if what it traced is a prefix of the other side's
    complete traces (both exhausted: either way round). Otherwise it is a mismatch.

  Controls, in `theHigherLimitDecidesAnExhaustion` and in the comparator, for both directions:
  - a success tracing `["A"]` against an exhaustion having traced `["B"]` is a mismatch;
  - `["AB"]` against `["A"]` is a mismatch;
  - prefix-compatible traces are inconclusive;
  - transaction-limit traces `[]` and `["start"]` with agreeing retries (`["start","done"]`, the same term) pass.

  The comparator's bounded category is now "both exhaust at both limits with the same traces". On the archived
  outputs it is still 112, with 0 unaccepted, 0 budget-only and 0 inconclusive.
- **C37.** `Adr078Fixtures.evaluations` recovers a returned function from the higher-limit replay when obtaining it
  exhausted, so both sides make the `applied` records. Re-recorded on pre21, `pre21-fixtures.txt` is unchanged (no
  fixture exhausts the transaction limit).
- **C38.** After an exhaustion decided at the higher limit, the completed evaluations' budgets go into the I6b report.
- **C39.** The runner requires `sourceMap: true` or `false`, and anything else is a harness error. In the controls,
  "yes" and a missing line had been compiled as maps off and are now harness errors. Every corpus manifest records a
  literal value.
- **C40.** The comparator requires JSON integers (`type(v) is int`) for vectors and budgets. A `true` vector or budget
  fails.
- **C41.** The identity floor counts compilations, once each after all their branches match: 32 multi-handler
  compilations, floor 32.
- **C35.** `m1a-pre21-recording.md`'s notes now give the 1,152 evaluations, the higher-limit rule, the exact
  diagnostic checks and the two-way id check.
- **ADR r11** states the refined I6a exhaustion rule (C30, C36, C37).

## Changes after reviewer1's implementation review of `ceffc261` and Codex's follow-ups (r12)

**reviewer1's finding 1 (P2, main code): static field binders in the first-use walk.**

`FirstUseRanks` now walks each body in the scopes the assembly gives it. In `ValidatorAssembly.wrap` and `method` the
nesting is, outermost first: library methods (`bindLibraries`), static fields in declaration order (`bindStatics`,
:304), helpers (`wrapMethodBindings`), then the handler or target.
- `Roots` carries the `StaticField`s, names included. The `staticValues()` that dropped the names is gone.
- **Static initialisers.** Each one is walked with the fields declared before it in scope, and with no helpers
  (`FirstUseRanks.of`, :120–123).
- **Entry points and helper bodies.** These see every field (:125, :235, :281).
- **Shadowing.** A field in scope shadows a library method of the same name. It is checked after the locals and the
  helpers and before the library methods (:286).
- **Library bodies.** A library body starts in its own defining scope, with no fields and no helpers (:243, :292).

The regressions:
- **`ValidatorAssemblyFrontendTest.aStaticFieldSpelledLikeALibraryMethodIsTheField`**, through
  `ValidatorAssembly.method`, is reviewer1's program: `static q.L.alpha = 4; static use = q.L.alpha;
  T.f = q.L.beta(use)`. Beta wins under both field spellings (`q.L.alpha` and `field`), and the two FLAT programs at
  `NONE` are identical.
- **`FirstUseRanksTest.staticFieldsAreInScopeWhereTheAssemblyBindsThem`** covers shadowing in a later initialiser,
  in an entry point and in a helper. It also checks that an earlier initialiser and a library body do not see the
  field.
- **The mutation.** With the field check disabled, both tests fail (alpha wins).

There is no byte change for Java programs. The javac frontend's field binders are plain field names, and its method
binders are qualified. On the new classes (ValidatorAssembly `d9e76165…`, FirstUseRanks `6be5465f…`):
- the corpus replay is byte-identical to pre21's (sha `e04953cd…`, 0 unaccepted, 0 budget-only, 0 inconclusive,
  112 bounded);
- the julc-examples gate is GATE-SAME and MAPS-SAME for all 43 programs;
- `LibraryTieDebugOracleTest` passes (3/3).

**reviewer1's finding 2 (P2, test) and Codex's follow-ups C42–C46.**
- **Separate fields (finding 2, C43).** `Adr078Fixtures.Evaluation` keeps the outcome (result, failure reason or
  `EXHAUSTED`) and the trace list as separate fields. The pre21 recording has a separate `traces …` line, re-recorded
  on the pre21 class path: 7,880 lines. Every former line equals the new outcome plus its traces line (2,320 checked,
  0 differences).
  - The oracle compares `Run(outcome, traces)` records, and never reads traces out of a reason.
  - reviewer1's exact case is a control: a failure whose reason reads `example traces=["B"]`, with traces `["A"]`,
    against an exhaustion that traced `["B"]`. It is a mismatch, both ways round.
- **C42.** Where the parent `eval 0` still exhausts at both limits on either side, the parent is decided, and the
  `applied` ids are not required of both sides.
- **C44.** An exhausted transaction-limit run's traces must be a prefix of its own retry's, in D5 (`againstItsRetry`)
  and in the comparator. Controls: `["lost"]` against its retry's `["kept"]` is rejected, on either side.
- **C45.** A completed retry counts in the compared, applied and traced totals.
- **C46.**
  - Both oracles have one category, **bounded**, for "both exhaust at both limits with the same traces".
  - The ADR's older exhaustion sentence and r11's refinement are consolidated into one numbered rule (ADR r12).
- **Re-runs.**
  - The adr078 tests: 19 and 1 green. I6a: 1,152 compared, 296 applied, 32 with traces, 0 mismatches, 0 bounded,
    0 inconclusive.
  - The codegen tests are green.
  - The comparator controls: 38, as expected (`m1a-replay-comparator-controls.txt`).
  - The comparator on the archived outputs and on the r12 replay: 0 unaccepted.

## Changes after Codex's C47–C50 and Fable's PR348-F1 (r13)

- **C47 (main code, memory).**
  - `FirstUseRanks.of` passes one growing set of the fields in scope, as an unmodifiable view, to every static
    initialiser's walk. It no longer copies the set for each initialiser.
  - The sharing is safe. `walk()` returns only when every frame it pushed has been popped, so no frame holds the set
    when the next field joins it. Library bodies get an empty set, and helpers are not reachable from an initialiser.
  - The new test `manyStaticFieldsCostLinearMemory` takes 4,000 fields, each read by the next, with no library
    method, and requires under 32 MiB of thread allocation. Restoring the copy per initialiser fails it, at 417,951,560
    bytes.
  - There is no behaviour change: the replay, the gate and the debug oracle are identical (below).
- **C48.** When the parent `eval 0` still exhausts on either side, each side's own applications are first checked
  against their own retries. Only then do the applied ids leave the cross-side comparison. The control is
  `againstItsRetry` with `["lost"]` against `["kept"]`.
- **C49.** At load, a duplicate pre21 row is an error. Every recorded evaluation (higher and tight limits included)
  must have exactly one trace row, and no trace row may be an orphan. The test is
  `everyRecordedEvaluationHasExactlyOneTraceRow`, with a missing and an orphan control.
- **C50.** A bounded evaluation's retry budgets are compared and reported in I6b; the verdict stays bounded.
- **F1.** ADR D1's scope list and the `FirstUseRanks` Javadoc name the `@Param` binders as the outermost scope. They
  sit outside the library methods, so they shadow nothing the walk ranks.
- **Re-runs on the r13 classes** (FirstUseRanks `875c97f6…`):
  - The corpus replay is byte-identical to pre21's (sha `e04953cd…`), with 0 unaccepted, 0 budget-only and 0
    inconclusive.
  - The julc-examples gate is GATE-SAME and MAPS-SAME for all 43 programs.
  - `LibraryTieDebugOracleTest` passes 3/3.
  - The adr078 tests pass: 20 and 1. I6a compared 1,152 evaluations with 0 mismatches.
  - `FirstUseRanksTest` passes 17.

## M3 (JOUST's half of the gate)

M3 passed on `4bac295d` (`m3-joust-gate.txt`):
- 67 contracts × 10 edits, `renameAll` included; the renamed-file check; the Pledges swap; a package move;
- Crowdfund `28e115b5…` and Tips `80648854…` kept;
- source-map mode 67/67 identical.

The coordinator re-ran it on `2c0edb6e` after C10 (`m3-joust-gate-2c0edb6e.txt`): passed, every `[hash]` line
identical to the `4bac295d` run.

**Final gate run: `6c68f45a`** (`m3-joust-gate-6c68f45a.txt`), on the final main code, after round 3's observer
refactor. It passed:
- 5 tests, 0 failures;
- all 73 `[hash]` lines identical to the `4bac295d` and `2c0edb6e` runs;
- Crowdfund `28e115b5…`, Tips `80648854…`;
- maps-on 67/67.

**M3 is complete** on `6c68f45a`. r12 changed main code (reviewer1's finding 1). The coordinator re-ran M3 on the
r12 head `e5d61aa6` (`m3-joust-gate-e5d61aa6.txt`), with the local snapshot `0.1.0-pre22-adr078-e5d61aa6-SNAPSHOT`.
It passed:
- 5 tests, 0 failures;
- all 73 `[hash]` lines identical to the `4bac295d`, `2c0edb6e` and `6c68f45a` runs.

r13 changes main code again (C47, memory only). The coordinator re-ran M3 on the r13 head `31df46ba`
(`m3-joust-gate-31df46ba.txt`), with the local snapshot `0.1.0-pre22-adr078-31df46ba-SNAPSHOT`. It passed:
- 5 tests, 0 failures;
- all 73 `[hash]` lines identical to the four earlier runs; Crowdfund `28e115b5…`, Tips `80648854…`; maps-on 67/67.

 Its JOUST deliverables (JOUST's pins, and D12 citing the known limits) follow when
`0.1.0-pre22` is published. F-ID opens as the ADR's OQ9 says.

## Not done in M1a

- **The JOUST half of the gate** is M3, run separately by the coordinator (complete on `6c68f45a`, above).
