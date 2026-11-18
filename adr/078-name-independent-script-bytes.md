# ADR-078: First use breaks ties among equal-content-key library methods

**Status:** Accepted (2026-11-19), revision r6, with r7's implementation notes. Approved by Claude (Fable) and Codex in
review round 5 (`dcf36096`) and by the maintainer's external reviewer (reviewer1) at r6 (`d68b2c50`). Implementation:
M1a, then M3, in the same PR.
**Date:** 2026-11-18
**Risk class:** correctness-sensitive, **low**.
- The change touches only the comparator that orders library `let`s whose content keys are equal.
- Those bindings are closed or total, effect-free values, so unbounded semantics are unchanged (I6a).
- The budget can change (I6b).

**Target release:** `0.1.0-pre22`, as a fix for a JOUST regression.
**Reported by:** JOUST.
- Its ADR-0018 D12 promises that behaviour-neutral edits keep a contract's script hash.
- `JscV2HashStabilityTest` fails on `0.1.0-pre21` and passed on `0.1.0-pre21-rc1`.

**Relates to:**
- [ADR-075](075-canonical-library-binding-order.md): this ADR **supersedes ADR-075 I2's first exception**, under which
  methods with equal keys are ordered by name. Its second exception (shape-check ids) and its helper non-goal remain.
- [ADR-060](060-generated-binder-namespace.md): generated names; source maps key on node identity.
- [ADR-045](045-value-literals.md), [ADR-046](046-array-literals.md), [ADR-043](043-list-to-array-promotion.md): passes
  that appear in the known limits.

**Finding IDs.** Review findings are cited by ID:
- `C…` are the Codex reviewer's;
- `F…` are the Fable reviewer's;
- `L-…` are known limits.

Each is described in a line where it is first used. The full review history is on PR #348.

## Context

### The failure (M0, `adr/evidence/078/`)

JOUST renames every declared name of `Crowdfund.jsc` so that their alphabetical order reverses. On `0.1.0-pre21` the
hash moves from `28e115b5…` to `f338eb56…` (`crowdfund-diff.txt`).
- All 680 binders keep their order except one adjacent pair: `Crowdfund.contribute__orElse1` and
  `Crowdfund.refund__orElse2`.
- JOUST generates both for `?? 0`, and their bodies are identical, so their content keys are equal.
- The rename reverses the order of their qualified names, which ADR-075 uses to break ties
  (`LibraryBindingOrder.java:75`). So their order flips.
- ADR-075 (#333) is not in rc1.
- Tips fails the same way. The other 65 JOUST examples keep their hashes under this edit (`joust-corpus-renameall.txt`).

### Current behaviour (at `08b021ce`)

- **The library binding order.** `LibraryBindingOrder` runs Kahn's algorithm, dependencies outermost. Each step takes the
  ready method that is smallest by `(content key, qualified name)`. The content key is a SHA-256 Merkle digest of the
  body, without names.
- **Every registered library binding is supplied to assembly and bound, used or not** (`JavacCompilation.java:433,478`).
  - A generic template produces no binding; an overload family shares one.
  - At `NONE` and in source-map builds, all of them stay (`PirBackend.java:111-123`).
  - At higher levels, the optimizer drops only the unread bindings it proves pure (`UplcOptimizer.java:537-561`).
- **The validator's helpers** are handed to the assembly in declaration order. They are bound in
  `MethodDependencyResolver`'s dependency and SCC order, inside the library bindings.
  - `compileMethod(root, …, List.of(root))` binds a root's methods twice under one name: as helpers, and as library
    copies (`JavacLibrariesTest.java:465-472`).
- **Mutually recursive library methods are rejected**, reached or not ("Unbound variable", `UplcGenerator.java:724`).
  Self-recursive ones compile.

## Goals and non-goals (F19: restore this section)

**Goals:**
- Fix JOUST's regression: equal-key library methods no longer follow their names.
- Debugging unchanged: no source position, breakpoint, step or visible local moves (I4).
- Unbounded semantics unchanged: results, failure reasons and traces (I6a).

**Non-goals:**
- **General name independence.** Backend passes key on spellings (Known limits; F-ID).
- **Pruning unread bindings, shape-check ids, helper order.** These are future work.
- **Budget equality** (I6b).

## Decision

### D1. First-use ranks

- **The rank table.** There is one per validator artifact. It holds **binding identities**: each registered library
  binding supplied to assembly, and each validator helper. A helper and a library copy that share a name are distinct
  (C22: such a pair exists under `compileMethod` with the root as its own library).
- **Resolution.** References resolve **lexically**, to the innermost binding of that name in scope.
- **Visiting.** A method is marked before its body is walked. Calls from one helper to another are followed.

**D1a. The root walk.** It visits:
1. the static initialisers in textual order;
2. the handlers in purpose tag order, into the one table; for `compileMethod`, the call of the target.

The `@Param` decoders are not walked (r7). Each is `PirHelpers.wrapDecode` over the raw argument's variable, so it
holds no reference to a method.

The walk is pre-order, left to right, in `LibraryBindingOrder.children`'s order. That is the one child order the
content key also walks (r7). The walk is iterative. The first `Var` that resolves to an
unranked method gives it the next rank, and its body is walked there.

**D1a′. Unread helpers** (C11: an unread helper can read a library method). The helpers the root walk did not reach
are walked next, once each, in the order the assembly receives them, which is declaration order. Their binding order is
unchanged.

**D1b. Unread library methods.** On the library call graph without self-edges, where a method calls another when
the other's name is free in its body, resolved lexically (r7; a local or a closed inner binding spelled like a method
is not a call). Since r8 this one lexical graph also schedules the content keys and orders Kahn's algorithm:
- **The step:** repeatedly, among the unranked methods that no unranked library method calls, rank the one with the
  smallest content key and walk it.
- **The fallback:** if several share that key, **the qualified name decides**.
- **Cycles:** if every unranked method is called by another unranked one (a mutual-recursion cycle, which pre21
  rejects), rank the smallest by `(content key, qualified name)` and continue. The rejection is unchanged and the walk
  terminates. This relies on mutual library recursion staying rejected (F16: reviewer note on that dependency).
  Kahn's algorithm emits such a stalled group in pre21's `(content key, qualified name)` order, so the rejection
  names the same method (r7). That holds for programs the javac frontend builds; for custom registries and other
  frontends, see D2's exception (r9).
- **Cost** (incremental):
  - counts of unranked callers;
  - a priority queue of the methods whose count is 0;
  - a presorted list read by a monotone cursor for the cycle fallback.

  That is O((n + e) log n) plus one walk per body. JOUST's largest program has n = 283.

### D2. The comparator

- Among ready library methods with equal content keys, JuLC chooses the lower first-use rank. For otherwise unreached
  methods a qualified-name fallback remains (D1b).
- Programs without equal-key methods are byte-identical to pre21's. This is claimed for programs the javac frontend
  builds from Java sources: their PIR has no closed binding spelled like a library method. The corpus replay and the
  julc-examples gate found no difference.
- **Exception (r9, Codex C22): custom registries and other frontends.** A library body built through a custom registry
  or another frontend can hold a closed binding spelled like another library method, for example a materialised
  `let q.M.b = λy.y in q.M.b`. pre21's spelling search read that as a call, and could key the method with group tokens
  or stall on a false cycle. With the one lexical graph, such a program's order, acceptance and diagnostics can change
  even without equal keys.
  - Codex's case: a registry's `Math.abs` builder applies a materialised `q.M.b`. pre21 binds z, a, b; this ADR binds
    a, b, z. It is pinned by `LibraryTieOrderFixturesTest.aCustomRegistrysMaterialisedBindingIsNoCall`.
  - A genuine cycle beside it: with c and d calling each other, pre21 reports `Unbound variable: q.M.a` (its stalled
    group holds the false cycle too) and this ADR reports `Unbound variable: q.M.d`. The program stays rejected. It is
    pinned by `aGenuineCycleBesideACustomRegistrysBindingIsTheOneReported`.
- In programs with them, the tied methods move, and Kahn's interleaving can move others too.
- There is no merging and no pruning.

### Lemmas

These hold at assembly level, for accepted programs, whose library call graph is acyclic apart from self-edges. They
describe the assembled PIR; the gate covers the final bytes.
1. **A strict total order.** Every binding identity gets one rank, so Kahn's output is unique.
2. **Independence of what is not reached.** What D1a and D1a′ reach is ordered independently of what they do not
   reach. A reached method calls only reached methods.
3. **Transposition.** Swapping two adjacent bindings with α-equal values, which nothing reads, gives an α-equal
   assembled program. Blocks follow by composition. Two identical values that nothing reads swap without effect wherever
   they are.
   - With the one lexical graph (r8), an unread α-equal pair with the same callees becomes ready together. Kahn binds
     them with nothing between them but methods of the same key.
   - r7 claimed this under the spelling graph, and that was false. Codex's case was a = λq.M.c.q.M.c, b = λx.x,
     c = λx.0 with no roots: the parameter spelled `q.M.c` made a wait for c. Withdrawn in r8.
4. **Equal keys are not identical, and not always adjacent.** Twins can call different twins, and a dependent of one
   twin can be bound between them.

## Affected stages and modules (F19)

- **`julc-compiler`, `codegen/LibraryBindingOrder`:** the comparator takes the ranks.
- **`julc-compiler`, `codegen/ValidatorAssembly`:** computes the rank table (D1a, D1a′, D1b) in `validator()` and
  `method()`, from the entries, statics, helpers and libraries it already receives.
- **Tests:** D5.
- Nothing else. Helper binding order (`MethodDependencyResolver`), the frontends, the PIR passes, the backend, the VM,
  the ledger API, the annotation processor and the Gradle plugin are unchanged.

## The gate: a release condition for pre22 (C28, C35, C36, F20)

pre22 requires the gate **and** M1a's other exit items (D5, the oracles, the build).

The order of work is: implement M1a, run M3 (the JOUST half below), then close M1a.

**JOUST.**
- **Checkout:** the JOUST worktree at a recorded commit; today `joust-julc-pre21@5522cafc`.
- **The local M1a build:**
  1. in JuLC, run `./gradlew publishToMavenLocal`, and record the published version;
  2. in a scratch JOUST worktree, set `julc` in `gradle/libs.versions.toml` to that version (`mavenLocal()` is already
     a repository);
  3. run `./gradlew --offline --no-daemon :julc-state-toolchain:test --tests 'org.julclang.state.toolchain.JscV2HashStabilityTest' --rerun-tasks`.

  JOUST's `-PuseLocalJulc=/path/to/julc` (an `includeBuild`) is an alternative; JOUST marks it untested.
- **Record:**
  - the resolved compiler, from `./gradlew :julc-state-toolchain:dependencyInsight --dependency org.julclang:julc-compiler --configuration testRuntimeClasspath`;
  - `JulcVersion.VERSION`.
- **Pass:** zero failures. All 67 contracts must be built: the test's `[hash]` table has 67 rows. That covers:
  - every edit of `edits()`, including `renameAll`;
  - the renamed-file assertion;
  - the Pledges `fn` swap.
- **Maps-on run:** reported, not gated. It uses `Adr078Diag.java.txt` with `setSourceMapEnabled(true)` in its compiler
  options.

**julc-examples.**
- **Revision:** `../julc-examples@49614db8` (`src/main/java`).
- **Inputs:** the 46 files that carry a validator annotation.
  - The baseline compiles 43 of them on pre21.
  - Three are excluded, as they were in the baseline:
    - `WingRidersPoolValidator` and `WingRidersRequestValidator`, which do not compile standalone (`return` inside a
      `while`);
    - one off-chain `uverify` file, whose text mentions the annotation.
  - Every baseline success must also compile on M1a.
- **Compare:** run the same runner on pre21's and on M1a's class path. The runner is built from
  `Adr078ExamplesCensus.java.txt`, and the two outputs are diffed. It compares:
  - the release FLAT bytes (default profile, source maps off), byte for byte;
  - the complete source-map-on `DFS index → SourceLocation` maps, in `SourceMapSerializer` form.
- **Pass:** no program's bytes change, as the census predicts.
  - A difference is exempt only if it comes from an equal-key selection, and the instrumented rank selection (D5) must
    record which one.

A single failure blocks pre22. Attributing a failure to a known limit diagnoses it; it does not excuse it.

**Evidence so far.** None of this is the gate.
- **The census** (`m1a-census-*.txt`) gives two separate measurements. Both are simulations on pre21's PIR, compared
  as same-source binder-name label sequences:
  - (a) filtered to binder names in the default-profile UPLC, the order changes for 5/67 JOUST programs;
  - (b) the full simulated order changes for 17/67;
  - julc-examples: 0 under both.
- **The locals-rename edit on pre21** ("locals, lambda and loop variables, `fn`s, parameters and methods renamed")
  changes 0/67 JOUST programs (`joust-corpus-locals.txt`).
- **Prior reviewer observations** (Codex, round 4, with an in-memory model of D1 and D2; the runner is not in this
  repo):
  - `renameAll` changes 0/67 at `PV11_SAFE`, against pre21's 2 with maps off and 3 with maps on (including Steward);
  - julc-examples' debug locations are unchanged;
  - C12's two spellings converge on `c8489246…`.

## Invariants

- **I1** (withdrawn in r5; replaced by the gate). Backend passes key on spellings, so a universal byte claim does not
  hold (C26).
- **I2. Meaningful order still counts.** Statement, field, variant, `@Param` and static field order, and logic, may
  change the bytes.
- **I3. One body per binding identity and assembly copy.** No merging. Checked by the multiset of
  `(handler, binding identity)` `methodBound` callbacks.
- **I4. Debugging unchanged.** No `SourceLocation`, `PirDebugProvenance` association or I13 record changes. Library
  terms are unlocated (`Terms.java:312-325`), and source maps key on node identity (`Terms.java:327`;
  `UplcGenerator.java:49-50`).
- **I5** (moved to a later ADR).
- **I6. Semantics.**
  - **I6a.** On every input with no budget limit, first-order results, observable failure reasons and script traces
    are identical to pre21's.
    - A moved binding is the same evaluation, at most once, at its pre-optimisation site when evaluation enters it.
    - This rules out an added, repeated or dropped evaluation. So no failure or trace can be added, removed or
      reordered.
  - **I6b.** The budget is not guaranteed. Index widths change sizes, and size-driven passes can decide differently
    (L-C12).
  - **I6c.** The size can change.
- **I7. Determinism.** Given the resolved call graph and the content keys, the ordering algorithm uses names only in
  D1b's fallback.

## Tests (D5): regression and discovery

**The property suite** (`LibraryTieOrderPropertyTest`):
- **The rewrites:**
  - capture-free renames of library methods (whole overload families) and classes;
  - package moves;
  - permutations of library sources and imports.
- **Seeds:** seed 78, N = 40 per program, at every level, with source maps on and off.
- **Results:**
  - it reports coverage by rewrite and by mode;
  - every generated variant that compiled on pre21 must compile on M1a (C38);
  - every difference found is attributed to a known limit or fixed.

**Fixed fixtures.** Which variants compile on pre21 is pinned (C29):
- **JOUST (r7).** JOUST's generated Crowdfund and Tips Java do not compile without JOUST's on-chain runtime
  (`org.julclang.state.api.*`), so they are not JuLC fixtures. **The JOUST fixture is M3's gate.** JuLC's D5 carries
  JuLC-only stand-ins:
  - the twin-body fixtures;
  - a Crowdfund-like pair of identical `?? 0` helpers used from two branches, in the debug oracle;
  - compiled validators (a single entry, a static initialiser, and a multi-purpose validator whose handlers first use
    opposite twins), so that the validator path of the assembly is covered too.
- **C11:** an unread helper reads one twin; the bytes are equal after the rename.
- **C12** (`C12.java.txt`; an order change crosses FLAT's 128 index boundary): both M1a spellings give `c8489246…`,
  1,010 B, and succeed under `ExBudget(31_150_000, 110_000)`. pre21's two outcomes are pinned.
- **C20** (library-source order decides compilability): pool `[B, A]` compiles (45 B) and `[A, B]` is rejected. Both
  results are pinned.
- **C22:** `compileMethod(root, "f", List.of(root))`. The test asserts which binding identity each read ranks.
- **Direct first-use assertions** (C38):
  - a library call reachable only through a static initialiser;
  - a multi-purpose validator whose handlers first use opposite twins.
- **Cycles:** an unread mutual pair stays rejected; self-recursion compiles; mutually recursive helpers work.
- **Expected to differ** (known limits): C13, C14, C19/C19c, C26, C27.

**Oracles:**
- **Debug (I4).** For every mapped DFS index, the source location is compared through the rewrite mapping. Breakpoint
  hits, step locations and visible locals are compared against pre21's.
- **Semantic (I6a, C30, C39).**
  - Results are compared structurally. A returned function is applied, on both sides, to the same fixture arguments,
    and the results are compared.
  - Observable failure reasons and traces are compared.
  - Budgets are reported separately.
  - A case that exhausts the budget on one side only is replayed at a higher limit. If it still exhausts, it is
    reported as inconclusive.
  - **Refined in r11 (Codex M1a C30, C36, C37):**
    - Any case that exhausts the transaction limit, on either side or both, is decided by its higher-limit replays.
      A transaction-limit exhaustion's partial traces are superseded by the replay.
    - Where both replays terminate, results and complete traces must be equal.
    - Where an exhaustion persists, the case is inconclusive only if what it traced is a prefix of the other side's
      complete traces (or, both exhausted, one is a prefix of the other). Otherwise the traces already disagree, and
      it is a mismatch.
    - A returned function whose evaluation exhausted is recovered from the replay and applied.

**Unit tests:**
- Lemmas 1–4 on random DAGs.
- An instrumented rank selection asserts that the qualified name decides only in D1b's fallback, and records each
  equal-key selection for the gate.

## Known limits (non-exhaustive)

These are the name and order dependences found so far, each with its reproduction. More may exist. Citing this list in
JOUST's D12 is an M3 deliverable.

| ID | What | Reproduction |
|---|---|---|
| L-SC | Shared shape-check ids follow library class names, method order and helper order | `probe-results.txt` |
| L-C1 | Per-root boundary checkers share nodes by simple name and fields | 276 → 318 B, `c1-probe-results.txt` |
| L-C2 | Type registration order reaches content keys, including through class renames in cross-class recursive groups (C13) | `r2-probes.txt` |
| L-C4 | Library-source order can decide whether a program compiles (C20) | `r3-probes.txt` |
| C14 | Renaming one member of an overload family splits its binder | 88 → 96 B, `r2-probes.txt` |
| L-H | The validator's helpers bind in declaration and SCC order | `probe-results.txt` |
| L-C12 | Order changes can change the budget and budget-limited outcomes | `C12.java.txt`, `r3-probes.txt` |
| L-C19 | The literal folds (ADR-045, ADR-046) treat a name as a literal only if it is bound exactly once in the whole program (`LiteralFoldPass.java:137-150`) | 33 → 73 B at the default profile (C19c), `r3-probes.txt` |
| C26 | O9 list-index promotion keys on binder and parameter spellings (`ListIndexPromotionPass.java:219,378`) | `r4-probes.txt` |
| C27 | A user library method whose class and method simple names match a stdlib intrinsic is replaced by the intrinsic, because the stdlib lookup runs first (`Calls.java:180-188`). This is a correctness issue against ADR-060 and needs frontend dispatch work: **tracked as its own issue (issue to be filed)** | `Math.magnitude` → `abs`: 33 → 28 B at the default profile, 37 → 52 B at `NONE`, `r4-probes.txt` |
| Audit | Codex's backend audit, not individually reproduced: whole-program spelling counts in `ValueConversionSharingPass` (`:181-185`); lexical lookups in the narrowing and boolean proofs; fresh-name checks in promotion and pair destructuring; Z-combinator equality in `ClosedValueSharing` (`:145-147`); handler sharing grouped by method name | Codex round 4 |
| L-C3, L-C5–L-C7 | Interactions of pruning, renumbering and helper reordering (future work only) | Codex round 1 |
| L-SM | Source-map builds skip the UPLC optimizer | `PirBackend.java:111-123` |

**L-C19's trigger** (C33, F17).
- **When it fires:** a local or parameter bound to a literal is used in a foldable call (O10 `JulcArray.of`, O14 Value
  literals), and its spelling is also bound elsewhere in the program.
- **When a renaming is safe:** it keeps every fold decision if it maps the program's binder spellings bijectively, with
  no merges and no splits. That condition is sufficient, not necessary.
- **For JOUST:** it could avoid the trigger by generating program-unique local names.

## Future work

- **F-ID.** One ADR to make backend passes key on binder identity, not on spelling.
  - It covers L-C19, C26 and the audit list. C27's frontend dispatch is its separate issue.
  - **Trigger (OQ9; F18 asked for one):** M3 completes, or earlier if a supported JOUST artifact fails in release mode.
- **F-SC, F-H, F-P, F-T** (from r1): renumber shape-check ids, order helpers, prune unread bindings, and a broad
  property test. M1b and M2 ship together.

## Alternatives rejected

- **Merging identical bodies.** This breaks I3; rejected by the maintainer.
- **Tie-break by declaration order (rc1) or by qualified name (pre21).** The first moves the hash when methods are
  reordered; the second is the failure.
- **First use as the primary key.** It changes most programs' bytes.
- **Pruning in M1a.** It changes every `NONE` and source-map build.
- **A general byte theorem (r2–r4).** Backend passes key on spellings, so it needs F-ID first.

## Compatibility and release note

- **Deployed scripts and compiled artifacts are unaffected.**
- **Recompiling** a program compiled from Java sources changes bytes, hash and address only where it has
  equal-content-key library methods. Near a budget limit, the outcome can change (I6b).
- **Custom registries and other frontends** (D2's exception, C22): a program whose library bodies hold a closed
  binding spelled like another library method can also change its binding order, compile where pre21 rejected it,
  or name a different method in a rejection, without equal keys.
- **Internal API (accepted P2 deviation, Codex M1a C33).** `ValidatorAssembly.Frontend`, the internal codegen record
  through which a frontend receives assembled bindings, changed its public signature:
  - `methodBound` is now a `ValidatorAssembly.MethodBound` that also receives the handler's purpose tag, in place of
    pre21's `BiConsumer<String, PirTerm>`;
  - an `observer` component was added.

  A client compiled against pre21's constructor or accessor gets `NoSuchMethodError`. The only users are
  `JavacCompilation` and a test, and no other module or sibling repository constructs it. The change is accepted for a
  preview release rather than kept binary-compatible (Fable judged it acceptable).
- **In-repo oracles** are re-run and explained in M1a's exit: `GoldenUplcTest`, the pre-change goldens,
  `Adr060ByteSnapshotTest`, `LibraryOrderEquivalence`, the ADR-068 frozen reference, the `O11BlsTypesTest` pins and the
  Blaster fixtures.
- **Release note** (C40, F22):

  > Among ready library methods with equal content keys (methods whose compiled bodies are the same apart from names),
  > JuLC now chooses the one the program uses first. For otherwise unreached methods, a qualified-name fallback
  > remains. Recompiling can change affected scripts (measured: …); scripts already compiled or deployed are
  > unaffected. Programs compiled from Java sources change only where such methods exist. A program whose library
  > bodies come through a custom registry or another frontend may also change its binding order, compile where it was
  > rejected, or name a different method in a rejection. Without a budget limit the results, failures and traces are
  > the same; the budget can differ. Names and orders still reach the bytes in the cases listed in ADR-078's known
  > limits.
  >
  > API: `ValidatorAssembly.Frontend` (internal codegen API) changed: `methodBound` now receives the handler tag, and
  > an `observer` component was added. Out-of-tree frontends must adapt.

## Risks

- **The ranks miss a reference.** D1b ranks whatever D1a and D1a′ miss, and the unit tests assert that every binding
  identity is ranked.
- **The budget changes** (I6b). This is mitigated by reporting it and by the release note.
- **The cycle fallback depends on mutual library recursion staying rejected.** A fixture asserts the rejection.
- **The known limits are not exhaustive.** The gate protects pre22, and F-ID is the fix.

## Milestones

- **M0. Diagnosis.** Done.
- **M1a (`0.1.0-pre22`). D1, D2, D5.**
  - **Entry:** this ADR accepted.
  - **Exit:**
    - the gate passes;
    - D5 is green;
    - the oracles run against `08b021ce`;
    - ADR-075's corpus replay, run against `08b021ce`: the ADR-074 corpus, 20,407 manifests. **It is required (r7).** It
      is the broadest I6a evidence: unbounded outcomes and traces are compared, and budgets are reported.
      - **Scope:** the 20,352 manifests the runner replays. The 55 it excludes (JavaLibraryProvider entries and custom
        lookups) are pinned by their sorted ids (SHA-256 `b95d72fa…`), so an extra exclusion fails the comparison.
    - every in-repo byte change is explained;
    - the compile time is measured;
    - `./gradlew build` is green.
- **M3. JOUST with the local M1a build.**
  - It runs the gate's JOUST half.
  - **Status: complete.** The gate run passed on the final main code, `6c68f45a`
    (`adr/evidence/078/m3-joust-gate-6c68f45a.txt`).
    - 67 contracts × 10 edits, `renameAll` included; the renamed-file check; the Pledges swap; a package move.
    - Crowdfund keeps `28e115b5…` and Tips keeps `80648854…`.
    - Source-map mode is 67/67 identical, Steward included.
    - Every `[hash]` line is identical across the three runs: `4bac295d` (`m3-joust-gate.txt`), `2c0edb6e` (after
      r8's one lexical graph) and `6c68f45a` (after round 3's observer refactor). Main code is frozen at `6c68f45a`.
  - Its JOUST deliverables (JOUST's pins and docs, and D12 citing the known limits) follow when `0.1.0-pre22` is
    published.
  - F-ID opens as OQ9 says.
- **M1b and M2 (future).** Their own ADR, shipped together.

## Open questions

- **OQ9. When does F-ID start?** When M3 completes, or at the first release-mode failure on a supported JOUST
  artifact.
- All other open questions are answered or moved to the later ADR.

## Revision history

- **r1 (`b7383306`).** The broad design.
- **r2 (`79bf86df`).** Narrowed to M1a.
- **r3 (`2479a06c`).** D1a′; I6 split; C13 and C14; the recursion precondition.
- **r4 (`3ddaf89b`).** Lemma 3 split; L-C19; the M3 criterion; C20 and C22.
- **r5 (`dcf36096`).** The guarantee reframed as a measured gate; the known limits made one non-exhaustive list; F-ID.
  Both reviewers approved.
- **r6 (2026-11-19).** Text round after review round 5 on `dcf36096`. No decision changes.
  - Goals and non-goals, and affected stages and modules, restored (F19).
  - The gate made a runnable release condition (C35, C36, F20):
    - JOUST: checkout, `mavenLocal` invocation, the version record, all 67 contracts, and the maps-on runner;
    - julc-examples: the pinned revision, inputs and three exclusions, compilation required, the FLAT and source-map
      comparison, and recorded exemptions;
    - work order: implement, then M3, then close M1a.
  - Codex's model figures labelled as prior reviewer observations (C37).
  - Fixtures: pre21-compiling variants must compile on M1a; the C20 fixture is back; direct first-use assertions (C38).
  - The I6a oracle applies returned functions; an exhaustion that persists is inconclusive (C39).
  - The release note's and D2's wording, and I7's scope (C40).
  - L-C19's condition stated as sufficient; C27 tracked as its own issue; D12's citation is an M3 deliverable (C41,
    F21).
  - "Registered library binding supplied to assembly", and helper order versus binding order (C42).
  - A finding-ID legend; I6a's "at most once" explained; "(methods whose compiled bodies are the same apart from
    names)" in the release note (F22).
- **r7 (2026-11-19).** Implementation notes from the M1a reviews (Fable M1A-F1–F6; Codex M1a C1–C9). No decision
  changes.
  - **D1b:** the call graph is built from free (lexically resolved) references. Kahn's dependency graph and the content
    key keep their spelling search. No Java program can tell the two apart, since library binders are qualified and
    locals cannot contain dots.
  - **D1b:** a stalled group is emitted in pre21's order.
  - **Lemma 3:** the twins form one equal-key run.
  - **Recursive groups:** a group's names are bound once for all of its parts.
  - **D1a:** the `@Param` decoders are not walked, since they hold no method reference. The child order is
    `LibraryBindingOrder.children`, which the content key now shares.
  - **D5:** the JOUST fixture is M3's gate. JuLC carries JuLC-only stand-ins, including compiled validators.
  - **M1a exit:** ADR-075's corpus replay is required.
- **r8 (2026-11-19).** After Codex's second M1a review (C10–C19) and Fable's (F7, F8). No decision changes.
  - **One lexical dependency graph** (free references, self excluded) now schedules the content keys, D1b and Kahn's
    algorithm. r7's claim that no Java program could tell the spelling search apart was false for custom registries.
    `JavaLibraryProvider` can materialise a closed `let q.M.b = λy.y in q.M.b` inside another method, and the spelling
    search then made a false cycle (`Unbound variable: q.M.a`).
  - **Compatibility:** for the javac frontend's programs nothing changes. The corrected corpus replay and the
    julc-examples gate are identical to pre21's. A program built through a custom registry or another frontend whose
    library bodies hold such a binding may order differently, or now compile where it was rejected.
  - **Lemma 3** restated.
  - **M3:** status recorded.
- **r9 (2026-11-19).** After Codex's third M1a review (C20–C28) and Fable's F9.
  - **The exception for custom registries and other frontends** (C22) is stated in D1b, D2 and the release note, with
    both of Codex's cases (an order change and a changed cycle diagnostic) pinned by tests.
  - **The corpus replay's scope** and its pinned exclusions are stated in M1a's exit (C27).
  - The decision is unchanged.
- **r10 (2026-11-19).** After Codex's fourth M1a review (C29–C35) and Fable's F10.
  - **Compatibility:** the summary covers custom registries and other frontends (C35), and records the
    `ValidatorAssembly.Frontend` signature change as an accepted P2 deviation, with a release-note API line (C33).
  - **M3:** complete on `6c68f45a`.
  - The decision is unchanged.
- **r11 (2026-11-19).** After Codex's fifth M1a review (C36–C41).
  - **The I6a oracle's exhaustion rule** is refined: the higher-limit replays decide, and an exhaustion is inconclusive
    only when its traces are prefix-compatible with the other side's (C36). A returned function is recovered from the
    replay (C37).
  - Test and evidence changes only; the decision is unchanged.
