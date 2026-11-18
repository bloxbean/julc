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

| | |
|---|---|
| Corpus | The ADR-074 corpus that ADR-075 used: `/Users/satya/work/bloxbean/julc-327-base/build/corpus327`, 20,407 recorded cases |
| Harness | ADR-075's `EvalDiffReplayTest`, as a standalone runner (`Adr078Replay.java.txt`) so that the same code runs on the pre21 artifacts and on this build. It also compiles validator cases (`compile`, `compileWithDetails`) and records the FLAT digest of every compiled program. |
| pre21 command | `java -cp "rep21:<pre21 class path>" replay.Adr078Replay <corpus> replay-pre21.txt` |
| M1a command | `java -cp "repm1a:<julc-compiler test class path>" replay.Adr078Replay <corpus> replay-m1a.txt` |
| Comparison | `compare_replay.py.txt` |

**Result** (`m1a-corpus-replay.txt`): the two outputs are byte-identical, 317,286 lines each with the same SHA-256.
- All 44,475 compiled programs, across 18,452 cases and their recorded levels, have identical FLAT.
- All 266,441 evaluations have identical outcomes, traces and budgets: 98,422 ok, 167,907 fail, 112 exhausted. These
  are ADR-075's own counts.
- No evaluation exhausts on one side only.

So the recorded corpus holds no program whose equal-key tie order changes under M1a. This replay ran after the
refactor that made `describe` share `children` (M1A-F5). The identical FLAT shows that the refactor left every content
key, and so every binding order, unchanged.

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

## Not done in M1a

- **The JOUST half of the gate** is M3.
