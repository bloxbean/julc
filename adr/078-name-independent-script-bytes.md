# ADR-078: Script bytes that do not depend on names: library ties by first use

**Status:** Proposed (2026-11-18), revision r2. Design only; nothing is implemented.
**Date:** 2026-11-18
**Risk class:** correctness-sensitive, **low** (M1a, the scope of this revision).
- M1a changes only the comparator that orders library `let`s whose content keys are equal.
- Every such binding is a closed function value or a total, effect-free value (ADR-075 I4, `DelayedMethodPass`). So
  results, failures, traces and budgets are unchanged; only de Bruijn indices, and so the script size, can move.
- Bytes change only for programs that bind two or more library methods with equal content keys.

**Target release:** `0.1.0-pre22`, as a quick fix for JOUST.
**Reported by:** JOUST.
- Its ADR-0018 D12 promises that behaviour-neutral edits keep a contract's script hash.
- Its `JscV2HashStabilityTest.behaviourNeutralEdits_leaveEveryScriptHash` fails on `0.1.0-pre21` and passed on
  `0.1.0-pre21-rc1`.

**Relates to:**
- [ADR-075](075-canonical-library-binding-order.md): M1a **supersedes ADR-075 I2's first exception** (identical
  bodies ordered by name). Its second exception (shape-check numbering) and its helper-method non-goal remain until the
  follow-up of § Future work.
- [ADR-060](060-generated-binder-namespace.md): source maps key on PIR node identity.
- [ADR-074](074-stack-safe-term-walks.md): the walks are iterative.
- [ADR-076](076-shared-bindings-script-size.md): handler sharing inherits the binding order.
- ADR-053, Java source-level debugging. This is an untracked draft, not on `main`.

## Scope of this revision

- r1 proposed four changes: library ties by first use, pruning of unreachable bindings, renumbering of the
  shape-check ids, and canonical helper order. It also proposed a property test over every renaming and method
  permutation.
- Round-1 review (Codex: 7 P1; Fable: 1 P1) confirmed the diagnosis and that first use is sound for JOUST's case. The
  P1s were all in the broad scope.
- r2 narrows the decision to **M1a**: D1 (first-use ranking) and D2's comparator, no more.
- Pruning, the shape-check renumbering (r1 D3), the helper order (r1 D4) and the broad property test are **moved to a
  later ADR**. They are kept below, under Known limits and Future work, with the review's findings and their
  reproductions.

## Context

### What JOUST saw, and why (M0, `adr/evidence/078/`)

JOUST renames every declared name of `Crowdfund.jsc` so that their alphabetical order reverses. With
`0.1.0-pre21` the script hash moves from `28e115b5…` to `f338eb56…`.

M0 rebuilt the program with JOUST's toolchain (`joust-julc-pre21@5522cafc`) against the `0.1.0-pre21` artifacts, and
diffed the UPLC with binder names stripped. FLAT keeps no names, so this compares exactly what reaches the bytes
(`crowdfund-diff.txt`).
- All 680 binders are in the same order but for one adjacent pair: `Crowdfund.contribute__orElse1` and
  `Crowdfund.refund__orElse2` swap.
- JOUST generates both methods for `?? 0`, and their bodies are identical.
- `refund` is a JOUST language word and is not renamed, while `contribute` becomes `zqaai`. So ADR-075's qualified-name
  tie-break (`LibraryBindingOrder.java:75`) flips.
- Two de Bruijn indices change. Nothing else differs.
- ADR-075 (#333) is not in `v0.1.0-pre21-rc1`, which is why rc1 passed.
- Of the 67 JOUST examples, only Crowdfund and Tips move under the rename (`joust-corpus-renameall.txt`). Tips moves the
  same way.

JuLC-only probes (`probe-results.txt`) show that two further dependencies are real. Neither is in JOUST's failing case:
- the shared shape checker's ids follow library class names and method declaration order;
- the validator's helper methods follow declaration order.

Review round 1 found more. They are listed under Known limits.

### Current behaviour (at `08b021ce`)

- **Library order.** `LibraryBindingOrder.innermostFirst` runs Kahn's algorithm over the library methods, dependencies
  outermost.
  - Each step takes the ready method that is smallest by `(content key, qualified name)`.
  - The content key is a SHA-256 Merkle digest of the body: bound variables are written as indices, library calls as
    the callee's key, types as structure.
- **Every static method of every offered library unit is lowered and bound** (`JavacLibraries.lower`,
  `JavacCompilation.java:433,478`). What survives depends on the build:
  - With the UPLC optimizer (`BASELINE` and above), unread bindings are dropped by dead-code elimination, but only when
    their value is provably pure (`UplcOptimizer.isPure`, `UplcOptimizer.java:537-561`).
    - So unread bindings do survive in practice. In 12 of the 67 JOUST examples some do: seven `Mpf` methods in ten
      programs, and `mapEmpty__Int` in two (`m1a-census-joust.txt`, `DEAD-SURVIVING`).
  - At level `NONE`, and in every source-map build, all of them stay. Source-map builds skip the UPLC optimizer
    (`PirBackend.java:111-123`).

## Goals (M1a)

- **G1.** Renaming library methods or library classes, or moving library classes across packages, leaves the order of
  the library bindings unchanged.
  - With ADR-075 I1 (import and library-source order), this fixes JOUST's case.
- **G2.** Debugging is unchanged: no source position, provenance association, breakpoint or step moves.
- **G3.** Results, failures, traces and budgets are unchanged. Bytes change only for programs with equal-keyed library
  methods.
- **G4.** A quick, low-risk change for `0.1.0-pre22`.

## Non-goals (M1a)

- Everything under Known limits and Future work:
  - shape-check ids;
  - per-root boundary checkers;
  - type declaration order;
  - the validator's helper methods;
  - pruning unreachable bindings;
  - method permutation in general.
- Merging identical bodies (rejected; see Alternatives).
- Bytes across compiler versions: a release may change them, and its notes say so.

## What M1a covers and what it does not (I1 in plain words)

| Behaviour-neutral edit | Bytes kept by M1a? | Why, or where it is tracked |
|---|---|---|
| Rename a library method (any visibility) | **yes**, at every level and in source-map builds, except the residual of D1b (measured 0) | D1, D2 |
| Rename or move a library class or package | **yes**, same condition, for programs without shared shape checks | D1, D2. Shape checks are L-SC |
| Permute imports or library sources | **yes** | ADR-075 I1 |
| Comments, whitespace, line endings | **yes** | no position reaches the bytes |
| JOUST's reversed-alphabet rename of a `.jsc` contract | **yes** on the JOUST corpus: 67/67 (`m1a-census-joust.txt`) | D1, D2 |
| Rename a type (record, sealed interface) used at a datum or redeemer boundary | **no** | L-C1 |
| Reorder type declarations in a recursive group | **no** | L-C2 |
| Rename or reorder anything a shared `fromPlutusData` check sees | **no** | L-SC |
| Reorder or rename the validator's own helper methods | reorder **no**; rename yes (helpers are not compared by name) | L-H |
| Reorder methods inside a library class | yes for binding order; may change compilability (L-C4) and shape-check ids (L-SC) | ADR-075, L-C4, L-SC |
| Statement, field, variant, `@Param` or static field order; any logic change | must change bytes (non-goal) | I2 |

## Invariants

- **I1. Library ties are name-independent** (rewritten in r2, narrowed; constrains D1 and D2).
  - **For** a fixed compiler version and configuration (target, level, profile, options, source maps on or off) and a
    fixed set of offered library units.
  - **The rewrites:** a bijective, capture-free renaming of library methods and library classes, moves of library
    classes across packages, and permutations of imports and library sources.
  - **The result:** identical FLAT bytes within each mode, for every program that has:
    - no shared shape check (L-SC);
    - no D1b residual tie between unreachable methods whose bodies are not α-equal (measured: none in either corpus).
- **I2. Meaningful order still counts** (kept). Statement, field, variant, `@Param` and static field order, and logic,
  may change the bytes.
- **I3. One body per source method** (kept). Every bound library method keeps its own `let` and PIR body. No merging.
- **I4. Debugging unchanged** (kept, narrowed to M1a). No `SourceLocation`, `PirDebugProvenance` association or I13
  record changes. M1a moves no positioned term (§ Debugging).
- **I5. Shape dispatch** (moved to a later ADR, with r1 D3).
- **I6. Semantics** (narrowed). Results, failures, traces and budgets are identical to `0.1.0-pre21` on every input.
  - Only the de Bruijn indices of reordered library bindings change.
  - The CEK machine charges a variable lookup the same whatever its index, so the budget is unchanged. That is ADR-075
    I4, verified there on 266,441 evaluations.
  - Script size can move by index widths.
- **I7. Determinism** (kept, narrowed). The library binding order is a function of the content keys, the first-use
  ranks of D1, and reference resolution. Name text decides only in D1b's residual.

## Decisions

### D1. First-use ranks (narrowed in r2: library methods only)

**D1a. The root walk `T`.** `T` ranks the library methods the program reaches.
- **What it walks:** the program the assembly builds, minus the library and helper `let`s:
  1. the `@Param` decoders in declaration order;
  2. the static initialisers in textual order;
  3. the entry wrapper with its handlers in purpose tag order; for `compileMethod`, the call of the target.
- **How:** pre-order, left to right, over `PirHelpers.mapChildren`'s child order, iteratively (ADR-074).
- **Methods:** at the first `Var` that resolves to an unranked helper or library method, the method takes the next rank
  and its body is walked there. It takes the rank before its body is walked, so recursion ends.
- **What it is used for:** only the library ranks; helpers are walked only to reach the library methods they call.

**D1b. Unreachable library methods.** M1a binds every offered method, as today. So what `T` does not reach is ranked
too.
- **The step:** repeatedly, among the unranked library methods that no unranked library method calls:
  - take the one with the smallest content key;
  - rank it, and walk it as `T` does, which ranks its unranked callees.
- **The residual:** if several methods share that smallest key, the qualified name picks the first. That is the only
  place a name still decides. Lemma 3 shows this is invisible when their bodies are α-equal (equal up to bound names,
  calling the same methods).
- **Measured:** no non-α-equal residual tie in the JOUST corpus (67 programs) or in julc-examples (43 programs),
  in `m1a-census-*.txt`.

**Lemmas** (the argument the review asked for, verified against `LibraryBindingOrder` and the census):
1. **First use is total and name-independent.**
   - Every ranked method gets exactly one rank, at a distinct step. So `(content key, rank)` is a strict total order,
     and Kahn's output is unique.
   - The walks read term shape and reference resolution only. A capture-free rename commutes with resolution, so it
     maps the output onto itself.
   - Two callers with identical bodies are still walked at different steps, so their callees are ranked in that order.
2. **The reachable methods' order does not depend on the unreachable ones.**
   - A reachable method calls only reachable methods, so binding an unreachable one never makes a reachable one ready.
   - When Kahn picks a reachable method it is the least ready one, and so the least ready reachable one.
   - So the subsequence of reachable methods is exactly Kahn run on the reachable subgraph alone.
   - Every reachable rank is below every unreachable rank, so a reachable method always precedes an unreachable twin
     that is ready at the same time.
3. **α-equal ties are invisible.**
   - Suppose D1b's tie holds methods `u1`, `u2` with α-equal bodies and the same callees.
   - Nothing calls them: they are taken only when no unranked method calls them, and a ranked caller would have ranked
     them.
   - They have the same dependencies, so they become ready together. Binding the first makes nothing ready, and
     everything ranked between them is a callee of the first, already bound. So Kahn binds them adjacently.
   - `let u1 = V in let u2 = V in R`, where nothing reads `u1` or `u2`, is α-equal to the program with the two swapped.
     So the bytes are the same whichever is first.
4. **Equal keys do not imply identical bodies, and equal-key methods are not adjacent in general.** Both premises of
   the review's suggested argument fail:
   - Two methods calling *different* callees whose keys are equal have equal keys but different de Bruijn bodies.
   - A dependent of the first twin with a smaller key is bound between the twins.
   - Lemmas 2 and 3 cover what remains; the residual is stated in I1.

### D2. The comparator (narrowed in r2)

- **The change:** `LibraryBindingOrder` orders ready methods by `(content key, first-use rank)` instead of
  `(content key, qualified name)`. The qualified name is used only in D1b's residual.
- **What stays the same:**
  - Methods with different keys keep ADR-075's order, so their bytes are unchanged.
  - Identical bodies are not merged (I3).
- **Pruning of unreachable methods:** withdrawn in r2 (moved to a later ADR, L-C3 and L-C5).

### D3. Shape-check ids by first use (moved to a later ADR)

See Future work F-SC.

### D4. Helper methods in canonical order (moved to a later ADR)

See Future work F-H.

### D5. Tests for M1a (narrowed in r2)

**`LibraryTieOrderPropertyTest`** (julc-compiler):
- **The corpus.** Programs with no shared shape check:
  - the ADR-075 `ImportOrderInvarianceTest` programs;
  - generated programs with two to four library classes that hold groups of methods with equal keys: identical
    bodies, bodies calling distinct equal-key callees, and reachable and unreachable twins;
  - JOUST fixtures: the generated validator and library Java of Crowdfund and Tips, as committed and after JOUST's
    reversed-alphabet `renameAll`, exported with `Adr078Diag.java.txt`.
- **The rewrites:**
  - a capture-free, resolution-preserving rename of library methods and classes. Fresh names are not used anywhere in
    the program, references are rewritten by symbol, and javac re-attributes the result to the same declarations;
  - moves of library classes across packages, including packages that sort among `org.julclang.stdlib`;
  - permutations of library sources and imports.
- **Seeds.** Base seed 78. N = 40 rewrites per program in the build; a system property raises N for a soak run. A
  failure prints the seed and the rewrite.
- **The assertions:**
  - identical FLAT bytes at `NONE`, `BASELINE`, `PV11_SAFE`, `PV11_COSTED` and the default profile, with source maps
    on and with them off, each mode compared with itself;
  - with source maps, the same set of mapped DFS indices.
- **Fixed regressions:**
  - JOUST's Crowdfund and Tips, base and renamed, give identical bytes;
  - the JuLC-only two-identical-bodies probe (`m1a-case1-probe.txt`) gives identical bytes after the rename.
- **Negative case:** changing a constant in one of two twins changes the bytes (I2).

**Unit tests (`LibraryBindingOrderTest`):**
- Lemma 1: the order is invariant under random renames over 500 random DAGs with planted equal-key groups.
- Lemma 2: the reachable subsequence is unchanged when unreachable methods are added.
- Lemma 3: α-equal unreachable twins are adjacent.
- The D1b residual is the only place `naturalOrder` is reached. An instrumented comparator asserts it.

## Alternatives rejected

- **Merging identical bodies.** One compiled body would stand for two source methods, which breaks I3.
  - Library bodies carry no positions today (`Terms.java:312-325`), but ADR-053's library debugging would need them.
  - It is also a size optimisation with its own cost analysis.
  - The maintainer rejected it.
- **Tie-break by declaration order** (rc1's effective behaviour). Reordering library methods would move the hash again,
  and across classes it reintroduces ADR-075's problem.
- **Tie-break by qualified name** (pre21). This is the cause of JOUST's failure.
- **First use as the primary key.** Name-independent too, but it would change the bytes of most programs; M1a changes
  only programs with ties.
- **Pruning unreachable methods in M1a** (r1 D2). It makes first use total without D1b. But it changes the bytes of
  every `NONE` and source-map build, removes eager-helper breakpoints if extended to helpers (L-C5), and interacts with
  the shape-check registry (L-C3).
  - D1b removes the name decision without pruning, except the residual, which was measured 0.
- **Leaving it as a documented limit.** JOUST's generated `?? 0` helpers hit it in ordinary contracts.

## Affected stages and modules (M1a)

- `julc-compiler`:
  - `codegen/LibraryBindingOrder`: the comparator, and the ranks as an input;
  - `codegen/ValidatorAssembly`: computes `T` and D1b over the parts it already holds (`validator()`, `method()`);
  - the new `LibraryTieOrderPropertyTest` and the `LibraryBindingOrderTest` cases.
- Nothing else: frontends, PIR and UPLC types, backend passes, VM, ledger API, annotation processor and Gradle plugin
  are unchanged.

## Debugging and source maps (verified in code)

- **Library terms are unlocated.**
  - They are built under `Terms.unlocated` (`Terms.java:312-325`; `JavacLibraries.java:135,179`), so they are in no
    position map.
  - Their declarations are never associated in `PirDebugProvenance` (`Terms.java:273-276`).
- **What reordering library `let`s touches.** It moves no positioned node.
  - Source maps key on node identity (`Terms.java:327`; `UplcGenerator.java:49-50`), carried pass to pass
    (`PirBackend.java:91-113`).
  - The persisted map's DFS indices are recomputed in the same compile.
  - Debug-metadata environment slots are recomputed from the final term (`DebugMetadataCollector.java:259-266`).
- **Not affected:** stepping and call stacks, which use CEK depth and frames (`julc-tools` `UplcToolsService.java:381-405,480-508`),
  and Java breakpoints, which go through `sourceMap.lookup` (`UplcToolsService.java:201-209`).
- **UPLC-line breakpoints** in the playground (`julc-playground/frontend/src/lib/uplc/store.ts:42-43`) shift only for
  programs whose bytes change.
- **Traces:** none is built from names, and eager bindings are total.

## Compatibility (M1a)

- **Deployed scripts and compiled artifacts are unaffected.** Their bytes are fixed, on chain and in `plutus.json`.
- **Recompiling with `0.1.0-pre22`** changes bytes, hash and address only for programs that bind equal-keyed library
  methods whose first-use order differs from their name order. Measured with the census (`m1a-census-*.txt`; it
  simulates M1a's order on pre21's PIR):

  | Corpus | Default profile | `NONE` and source-map builds |
  |---|---|---|
  | JOUST examples, as committed | 5/67 change (Coins, Dex, Locker, Tipper, Tips) | 17/67 |
  | julc-examples (43 validators) | 0 | 0 |

  - Crowdfund as committed keeps `28e115b5…`, and after the rename it now gets the same hash.
  - These are estimates from a simulation. M1a's exit records the measured counts on the implementation.
- **In-repo oracles** are re-run and explained in M1a's exit:
  - `GoldenUplcTest`;
  - the `*-pre-change-bytes.txt` goldens;
  - `Adr060ByteSnapshotTest` and `LibraryOrderEquivalence`, which tolerate reordered `let` runs;
  - the ADR-068 frozen reference;
  - the `O11BlsTypesTest` pins;
  - the Blaster fixtures (`verification/blaster/fixtures`).
- **Preview policy:** byte changes are allowed at every level.
- **Release notes:**

  > When two library methods have identical bodies, JuLC now binds them in the order the program first uses them; it
  > used to follow their qualified names, so renaming a library method or class could change a script's hash. Scripts
  > already compiled or deployed are unaffected. Recompiling changes the bytes, hash and address only of a script that
  > uses such methods (in JOUST's examples, 5 of 67; in julc-examples, none); it computes the same results, failures,
  > traces and budget.

## Risks (M1a)

- **The ranks miss a reference.** `T` resolves references with the same test as the dependencies,
  `PirHelpers.containsVarRef`, and D1b ranks whatever `T` misses. Every method is ranked, and the unit tests assert it.
- **A future eager binding that may fail** would make any reordering visible. This is ADR-075's risk. M1a adds a test
  that, after `DelayedMethodPass`, every library binding value is a lambda, a delay, or a term `mayFail` proves total.
- **The cost of `T` and D1b.** It is linear in program size, plus a scan of the unranked set per D1b step; D1b is
  quadratic in the number of offered library methods at worst. JOUST's largest program offers 244. The compile time is
  to be measured in M1a's exit.

## Implementation milestones

- **M0. Diagnosis.** Done: `adr/evidence/078/`.
- **M1a (`0.1.0-pre22`). D1, D2, D5.**
  - **Entry:** r2 accepted.
  - **Exit:**
    - the D5 tests are green;
    - ADR-075's evaluation harness (`adr/evidence/075/EvalDiffReplayTest.java.txt`), run against `08b021ce`, shows
      identical outcomes, budgets and traces;
    - the in-repo oracles and Blaster fixtures are checked and every byte change is explained;
    - the measured counts for the compatibility table are recorded;
    - the debugger and source-map suites under Verification pass;
    - `./gradlew build` is green on the head.
- **M3. JOUST verification with a local build.**
  - **Entry:** M1a published to `mavenLocal`.
  - **Exit:**
    - JOUST's full `JscV2HashStabilityTest` passes;
    - `reorderingFns_leavesPledgesHash` is extended to every example. Its `fn`s are library methods, so it is covered
      by ADR-075 and M1a unless an example uses shared shape checks, which none does today;
    - JOUST pins and docs follow.
- **M1b (future; a later ADR revision).** The shape-check ids (F-SC), pruning, and L-C1 and L-C2.
  - **Entry:** the later ADR is accepted and resolves L-C1, L-C2, L-C3, L-C6, OQ1, OQ2, OQ3 and OQ7.
- **M2 (future; same later ADR).** The helper order (F-H).
  - **Entry:** the later ADR is accepted and resolves L-C4, L-C5, L-C7 and OQ6.
  - **M1b and M2 ship together**, so that users see one hash change, not two.

## Verification (M1a)

- **I1:** `LibraryTieOrderPropertyTest` (D5), and `ImportOrderInvarianceTest` extended with equal-key pairs and
  class renames.
- **I3:** every offered method still has its own binding. Count `methodBound` callbacks.
- **I4:** the source-map and debugger suites run unchanged:
  - core: `SourceMapTest`, `SourceMapSerializerTest`, `DeepSourceMapSerializerTest`, `DebugMetadataTest`;
  - compiler: `SourceMapCompilerTest`, `SourceMapEvalTest`, `DebugCompileResultTest`, `DeepDebugMetadataWalkTest`,
    `JavacContractAndDebugTest`, `ExecutionTraceTest`, `BuiltinTraceIntegrationTest`;
  - `julc-vm-java`: `SourceMapVmTest`, `SteppingEvaluationTest`;
  - `julc-vm-truffle`: `JulcDebuggerTest`, `TruffleDebuggerTest`;
  - `julc-tools`: `SourceDebugServiceTest`, `JavacDebugMetadataTest`, `Adr068MetadataCorpusTest`;
  - `julc-playground`: `SourceDebugControllerTest`;
  - `julc-examples`: `DebugVestingTest`.
- **I6:** ADR-075's evaluation harness against `08b021ce`, with identical outcomes, budgets and traces, and the ADR-076
  corpus replay.
- **I7:** the D5 permuted runs and the instrumented-comparator test.
- **Name guards stay green:** `BinderNameIndependenceTest`, `GeneratedBinderNameLintTest`, the
  `julc.verifyBinderNamespace` check.

## Known limits (not fixed by M1a)

Each limit has the review's finding ID and its evidence.

- **L-SC. Shared shape-check ids** (r1 case 2; reproduced in `probe-results.txt`).
  - The ids follow library class name, then method declaration order, then the validator's helper declaration order.
  - A rename or a reorder that changes that order changes the bytes of programs that use `T.fromPlutusData` shape
    checks on two or more types.
- **L-C1. Per-root boundary checkers share nodes by simple name and fields** (Codex C1).
  - `StrictBoundaryGenerator.typeIds` is keyed by the resolved `PirType`, and `RecordType` equality includes the simple
    name and field names (`PirType.java:43`).
  - So two records with the same simple name and fields share a dispatcher node, and renaming one splits them.
  - Reproduced: 276 B → 318 B (`C1Probe.java.txt`, `c1-probe-results.txt`); Codex measured 245 B → 287 B.
  - r1's claim that the per-root checkers are name-independent was **wrong** and is withdrawn.
- **L-C2. Type declaration order inside a recursive group reaches the content key** (Codex C2; answers r1 OQ4).
  - `TypeGroups` registers a group in declaration order, and that decides which fields become `NamedTypeRef`s.
  - `LibraryBindingOrder.type` writes a reference as `Ref<kind>` and a resolved type as its structure.
  - So reordering type declarations can change keys, and with them the library order. Reproduction: Codex's round-1
    review; not re-run by the author.
- **L-H. The validator's helper methods follow declaration order** (r1 case 3; `probe-results.txt`).
- **L-C3. Pruning interacts with the shape-check registry** (Codex C3).
  - Pruning after lowering, as r1 proposed for M1, would leave registry entries, and ids in surviving bodies, for
    pruned callers.
  - Pruning and renumbering must be designed together.
- **L-C4. Permuting methods can change whether a program compiles** (Codex C4). ADR-068's library registration is in
  source order (`LibraryRegistration.order`). So "method permutation keeps the bytes" needs a precondition that both
  orders compile.
- **L-C5. Pruning would remove the bindings of unreachable eager helpers** (Codex C5). A breakpoint set in such a
  helper would then never resolve. That conflicts with r1's I4.
- **L-C6. Pruning and renumbering change budgets in proportion to data** (Codex C6).
  - Pruning drops evaluated prologue bindings in builds that keep them.
  - A shared check of id `k` runs `k+1` comparisons per call, so renumbering costs scale with the number of checked
    values.
  - r1's "fixed per-check delta" understated this per run.
- **L-C7. Swapping the members of a mutually recursive helper pair changes size** (Codex C7). The Bekić encoding
  duplicates one member, so D4 is not size-neutral.
- **L-SM. Source-map builds differ from release builds** (`PirBackend.java:111-123`). ADR-053's draft invariant 3 does
  not hold today. M1a's I1 is stated within each mode.

## Future work (moved to a later ADR)

This keeps r1's analyses so the later ADR starts from them.

- **F-SC (r1 D3, I5).** Renumber the shared checker's ids by first use, in the assembly before ordering, as a
  position-preserving rebuild.
  - I5 is the correctness condition: the renumbering `σ` is a bijection, and each node's child ids are rewritten by the
    same `σ`.
  - The test is differential against `exactShapeCheck`.
  - Fable F5 asked about ADR-041. Its integer `case` dispatch is not applied to this dispatcher today: the probe's UPLC
    at the default profile has the `equalsInteger` chain.
  - First-use ids are dense, so a `case` dispatch could make each check's cost independent of its id. That would
    remove OQ7 and most of L-C6. The later ADR should decide whether to adopt it first.
- **F-H (r1 D4).** Helpers in `(content key, first use)` order over Tarjan's SCCs.
  - Free variables keyed name-free: statics by textual index, `@Param`s by declaration index.
  - It relies on `DelayedMethodPass` for eager bindings.
  - It must answer L-C4, L-C5 and L-C7.
- **F-P (r1 D2's pruning).** Bind only what is reachable. It needs L-C3 and L-C5 answered.
- **F-T (r1 D5).** The broad property test: rename every identifier, permute methods and types. It needs L-C1, L-C2 and
  L-C4 fixed first.

## Open questions

- **OQ1. Pruning?** Moved to a later ADR (F-P).
- **OQ2. How to rewrite the ids?** Moved to a later ADR (F-SC).
- **OQ3. First use or a structural key for the ids?** Moved to a later ADR (F-SC).
- **OQ4. Does type declaration order reach the bytes?** **Answered in r2:** yes, through the content key (L-C2, Codex
  C2).
- **OQ5. Do source-map builds fall under I1?** **Answered for M1a:** yes, within each mode (I1, L-SM).
- **OQ6. D4's fallback?** Moved to a later ADR (F-H).
- **OQ7. The budget effect of renumbering.** Moved to a later ADR (F-SC, L-C6).
- **OQ8 (new in r2). Should D1b ship in M1a?**
  - The alternative is to keep the qualified name for every unreachable tie. That is simpler, but then I1 fails at
    `NONE` and in source-map builds for 17 of the 67 JOUST programs.
  - Lean: ship D1b. It is about 40 lines of ordering code with no semantic effect.

## Revision history

- **r1 (2026-11-18, `b7383306`).** Initial version: M0 diagnosis, D1–D5, OQ1–OQ7.
- **r2 (2026-11-18).** After review round 1 on `b7383306` (Codex: changes requested, 7 P1; Fable: request changes,
  1 P1).
  - **Narrowed to M1a for `0.1.0-pre22`:** D1 (first-use ranks; new D1b for unreachable methods) and D2's
    comparator.
  - **Withdrawn:** D2's pruning.
  - **Moved to a later ADR:** D3, D4 and I5 (Future work), and the broad D5.
  - **New: Lemmas 1–4.** The adjacency argument holds only for α-equal unreferenced twins. Equal keys do not imply
    identical bodies.
  - **New: the "what M1a covers" table** (Fable F1).
  - **New: census evidence over the JOUST corpus and julc-examples** (`m1a-census-*.txt`).
  - **Compatibility:** deployed artifacts, counts and release notes (F6).
  - **M1a exit:** Blaster fixtures and in-repo oracles (F7).
  - **ADR-075:** the supersession is stated (F10).
  - **Future milestones:** entry gates (F3).
  - **Known limits:** L-C1 (reproduced), L-C2, L-C3–L-C7 from Codex C1–C7, with r1's per-root-checker claim
    withdrawn.
  - **Open questions:** OQ4 answered, OQ5 answered for M1a, OQ8 added.
