# ADR-078: Script bytes that do not depend on names or declaration order

**Status:** Proposed (2026-11-18). Design only; nothing is implemented.
**Date:** 2026-11-18
**Risk class:** correctness-sensitive, **medium**.
- D1 and D4 only reorder `let`s of closed function values. That is ADR-075's argument, and it keeps results, failures and
  traces.
- D3 renumbers the type ids of the shared strict-boundary shape checker, which guards typed `fromPlutusData`. A wrong
  renumbering would accept Data of the wrong shape, so D3 carries its own invariant (I5) and its own differential test.
- D3 also changes the execution budget of programs that use the shared checker, as the Semantics section explains.

**Reported by:** JOUST. Its ADR-0018 D12 promises that behaviour-neutral edits keep a contract's script hash, and its
`JscV2HashStabilityTest.behaviourNeutralEdits_leaveEveryScriptHash` fails on JuLC `0.1.0-pre21`. It passed on
`0.1.0-pre21-rc1`.
**Relates to:**
- [ADR-075](075-canonical-library-binding-order.md): closes its two open questions and removes the two exceptions to its I2.
- [ADR-060](060-generated-binder-namespace.md): generated names; source maps key on PIR node identity.
- [ADR-074](074-stack-safe-term-walks.md): the walks stay iterative; its corpus is used for replay.
- [ADR-076](076-shared-bindings-script-size.md): handler sharing inherits the binding order.
- ADR-053, Java source-level debugging. This is an untracked draft, not on `main`.

## Context

### What JOUST saw

JOUST renames every declared name of `Crowdfund.jsc` so that their alphabetical order reverses. With JuLC
`0.1.0-pre21` the script hash moves from `28e115b5…` to `f338eb56…`. With `0.1.0-pre21-rc1` it did not move.

ADR-075 (#333, merged after rc1) introduced the difference. It orders library bindings by dependencies and then by a
name-free content key. It states two exceptions to its rename invariant I2, and a non-goal:
1. Two methods with identical bodies are ordered by qualified name.
2. The shared strict-boundary shape checker numbers the types it checks in lowering order. Its numbers are constants in
   library bodies.
3. Non-goal: the validator's own helper methods follow declaration order.

### Diagnosis (M0, `adr/evidence/078/`)

M0 reproduced JOUST's build at `joust-julc-pre21@5522cafc` against the `0.1.0-pre21` artifacts. It recompiled the
generated validator with `compileContractWithDetails`, which gave the same hashes, and diffed the UPLC with binder names
stripped. FLAT keeps no names, so this compares exactly what reaches the bytes.

- **Crowdfund.** All 680 binders are in the same order but one adjacent pair: `Crowdfund.contribute__orElse1` and
  `Crowdfund.refund__orElse2` swap.
  - JOUST generates both for `backers.get(caller) ?? 0`, and their bodies are identical.
  - `refund` is a JOUST language word and is not renamed. `contribute` becomes `zqaai`. So the qualified-name tie-break
    flips.
  - Two de Bruijn indices change (`i33`→`i32`, `i25`→`i26`), and the size stays 6,284 B. Nothing else differs.
  - This is **case 1**.
- **All 67 JOUST examples** (`joust-corpus-renameall.txt`). Only Crowdfund and Tips move. Tips moves the same way:
  `claim__orElse5` and `credit__orElse2` swap. Also case 1. None of the 67 uses the shared shape checker.
- **JuLC-only probes** (`probe-results.txt`) show that cases 2 and 3 are real too:
  - **Case 1.** Renaming one of two identical library methods changes the hash.
  - **Case 2.** Moving a library class across another in name order changes the shared checker's ids: Credential
    `0→3`, TxOutRef `2→0`. It also reorders the dispatcher's chain. So does swapping two methods *inside one library
    class*, with no rename, and so does swapping two validator helpers that check shapes.
    - ADR-075's description of the second exception is therefore incomplete. The ids follow lowering order: library
      classes by qualified name (`JavacLibraries.java:135`), then each class's methods in source order (`:208-221`),
      then the validator's statics and helpers in declaration order (`JavacCompilation.java:433-452`).
  - **Case 3.** Swapping two validator helper methods changes the hash. Renaming them in place does not.

### Current behaviour (at `08b021ce`)

- **Library bindings.** `LibraryBindingOrder.innermostFirst` runs Kahn's algorithm with the comparator
  `(content key, qualified name)` (`LibraryBindingOrder.java:75`).
  - A free variable that is not a library method is keyed by its name text (`:259`). The shared checker is the only
    such name in a library body today, and it is fixed.
  - **All** static methods of every offered library unit are lowered and bound (`JavacLibraries.lower`,
    `JavacCompilation.java:433,478`). Unused ones are dropped later by the UPLC optimizer. For example, Crowdfund's
    `contributionOf__orElse9` is lowered but absent from the script.
- **Shape checks.**
  - `Terms.checkedDecode` (`Terms.java:115-124`) calls `StrictBoundaryGenerator.checkShared` for `T.fromPlutusData` of a
    ledger type with a shape (`Calls.java:131-140`).
  - `register` gives each new resolved type the next id, `typeIds.size()`, and then registers its children
    (`StrictBoundaryGenerator.java:146-151`).
  - `withDispatcher` builds an if-chain over the ids in ascending order (`:127-139`). A check of id `k` therefore runs
    `k+1` `equalsInteger` tests.
  - The dispatcher is bound once around the program under `#__julc-shape-check`, through `Frontend.programBound`
    (`JavacFacts.java:794-796`, `Terms.java:136-138`).
  - The per-root checkers of the datum and redeemer boundary (`check`, `checkPrepared`, `checkRecordComponents`) clear
    their table and number from the root in field order. They do not depend on names.
- **Helpers.** `ValidatorAssembly.wrapMethodBindings` binds the helpers in the order of `MethodDependencyResolver`'s
  Tarjan SCCs, ties by input (declaration) order. A 2-member SCC becomes one `LetRec`, a Bekić tuple, with its members
  in that order; 3 or more members are rejected.
  - Static fields are a separate block, bound outside the helpers in textual order (`ValidatorAssembly.java:201-206,
    270-277`).
  - `compileMethod` binds the target and its helpers the same way (`:336`).

### Inventory: where names or declaration order can reach the bytes

| Where | Ordered by | Reaches bytes? | Neutral to change? |
|---|---|---|---|
| Library let order, ties (`LibraryBindingOrder:75`) | qualified name | yes (case 1, Crowdfund) | yes: closed values (ADR-075 I4) |
| Library content key, free names (`:259`) | name text | only through the fixed `#__julc-shape-check`, and through the ids of case 2 inside the keyed bodies | yes |
| Shared shape-check ids and dispatcher chain | lowering order: library class names, then library method declaration order, then validator helper declaration order | yes (case 2) | yes in results and failures; **no in budget** (dispatch position) |
| Helper let order (`MethodDependencyResolver`) | declaration order | yes (case 3) | yes for closed values (D4 argument). The order inside a Bekić pair can change budget |
| Library lowering order (`offeredByName`, `JavacFrontend.java:192`) | qualified name | only through case 2 | — |
| Static field order | textual | yes | **no**: Java evaluation order (non-goal) |
| `@Param` order, record component order, variant tags (`permits` or nesting, `JavacTypeRegistrar` I11) | declaration | yes | **no**: ABI and data layout (non-goal) |
| Per-root boundary checkers | field order from the root | yes | name-independent already |
| Entry handlers (`ValidatorWrapper.java:231`) | purpose tag | yes | name-independent already |
| ADR-076 handler sharing (`HandlerBindingSharing.dependencyOrder`), closed-value sharing (`ClosedValueSharing:70`) | term pre-order, ties by input order | inherit the let order | follow D1 and D4 |
| Type registration (`TypeGroups`, ledger `TreeMap` in `JavacTypeRegistrar:74`) | declaration order; ledger names fixed | not shown (OQ4) | — |
| Trace and string constants | — | no compiler-built text: only user `trace` and literal strings (julc-stdlib `StdlibRegistry.java:806,945`) | — |
| Binder names, hygienic `#` names (ADR-060) | — | no: FLAT keeps no names | — |
| `LiteralFoldPass.dyingLocals` (`HashMap` over names) | fixed point, a set | no | — |

A grep of `pir/`, `uplc/`, `codegen/` and `backend/` for sorting, `TreeMap`/`TreeSet` and hash-ordered iteration found no
other path. The grep is not proof. The property test of D5 is the guard.

## Goals

- **G1.** For a fixed compiler version and configuration, renaming any identifier, reordering methods, and moving or
  renaming library classes and packages leave the script bytes unchanged. Imports, library sources, comments and
  whitespace are already covered by ADR-075 I1.
- **G2.** Every source method keeps its own compiled body. Source maps, breakpoints, stepping, variable views and traces
  keep their meaning.
- **G3.** Typed boundary checks keep exactly their meaning.
- **G4.** Programs without ties, without shared shape checks and with helpers already in canonical order keep their
  bytes. This minimises churn.

## Non-goals

- **The order of fields, variants, `@Param`s, statements and static fields.** These have a meaning in Java or in the
  data layout, so changing them must be free to change the bytes.
- **Logic changes.** These must change the bytes. JOUST's `aRealChange_movesTheScriptHash` is the model.
- **Bytes across compiler versions.** A new version may change them; release notes say so.
- **Merging identical bodies, or any other size optimisation.**
- **Other frontends' names** (ADR-059 producers, a future Kotlin frontend). This ADR covers the javac frontend, but D1
  to D4 are stated on PIR so that another frontend gets them unchanged.

## Invariants

- **I1. Name and order independence.** Constrains D1-D5.
  - Under any composition of these rewrites, the FLAT bytes are identical at every optimisation level, with source maps
    on and with them off:
    - a bijective renaming of identifiers declared in the compiled sources (classes, records, interfaces, methods,
      parameters, locals, lambda parameters, pattern variables, fields; library classes and packages);
    - permuting methods within a class;
    - permuting classes across files and packages;
    - permuting imports and library sources;
    - comments and whitespace.
  - The configuration is fixed: target, level, profile, options, and the set of offered library units.
  - Text inside a string literal is not an identifier.
- **I2. Meaningful order still counts.** Constrains D1-D4. A change of field, variant, `@Param`, statement or static
  field order, or of logic, may change the bytes. The dependency structure and the textual order of statics are kept.
- **I3. One body per source method.** Constrains D1 and D4. Every method that is bound keeps its own `let`, with its own
  PIR body, and keeps its node identity. No two source methods share a binding.
- **I4. Debugging unchanged in meaning.** Constrains D1, D3 and D4.
  - Every source-mapped PIR node keeps its `SourceLocation`.
  - Every `PirDebugProvenance` association keeps its binding, scope and type.
  - Every `terms.assembled` record (ADR-068 I13) stays true of the final term.
  - The debugger suites listed under Verification pass unchanged.
  - Pretty-printed UPLC line numbers, which UPLC-level breakpoints use, may change as they do for any byte change.
- **I5. Shape dispatch.** Constrains D3. After renumbering by a bijection `σ` from old ids to new ids:
  - every `checkShared` call site of type `T` that read id `k` reads `σ(k)`;
  - dispatcher node `σ(k)` is node `k`'s check, with every child id `j` inside it rewritten to `σ(j)`;
  - the dispatcher holds exactly the renumbered nodes.
  - Hence a call accepts exactly the Data its type's own `exactShapeCheck` accepts.
- **I6. Semantics.** Constrains D1-D4.
  - Results, failures and traces are identical to `0.1.0-pre21` on every input.
  - Budgets are identical except in two places:
    - (a) D3: each shared check of a type whose id moves from `k` to `k'` runs `k'-k` more `equalsInteger` tests and
      branches. That is a fixed amount per call, possibly negative.
    - (b) D4: a mutually recursive pair whose members swap in their Bekić tuple.
  - De Bruijn indices change size only, not cost, as in ADR-075 I4.
- **I7. Determinism.** Constrains D1-D4. The order and the numbering are functions of the PIR term structure, the
  resolution of each reference to the declaration it names, and the textual order of statics and `@Param`s. Nothing
  else enters: no hash iteration order, no name text, no declaration order of methods or classes.

## Decisions

### D1. A canonical first-use traversal `T`

D2, D3 and D4 all rank by **first use**. `T` defines it once.

- **The root.** `T` walks the program as the assembly would build it with **no** helper or library `let`s:
  1. the `@Param` decoders in declaration order;
  2. the static field initialisers in textual order;
  3. the entry wrapper with its handlers in ascending purpose tag, as `ValidatorWrapper` orders them; for
     `compileMethod`, the call of the target.
- **The walk.** A pre-order, left-to-right walk over each node's children in `PirHelpers.mapChildren` order, the same
  order `LibraryBindingOrder.describe` uses. It is iterative (ADR-074).
- **Methods.** When the walk meets a `Var` that resolves to a helper or library method with no rank yet:
  - it gives the method the next rank;
  - it walks the method's body at that point, depth first;
  - then it continues.
  A method gets its rank *before* its body is walked, so recursion and mutual recursion end.
- **Shape checks.** When the walk meets a shared shape-check call `[[#__julc-shape-check (con integer k)] d]` whose
  type has no new id yet:
  - the type gets the next new id;
  - then its component types get ids depth first, in `register`'s field order, skipping those already numbered.
- **Totality and independence.**
  - The walk is a function of term shape and reference resolution only. A rename is a bijection that commutes with
    resolution, so it maps the rank sequence onto itself. Reordering declarations does not change any term the walk
    visits.
  - Every reachable method and every reachable check type gets exactly one rank, at a distinct step. So first use is a
    strict total order on what is reachable.
  - Two callers with identical bodies are still reached at different steps. Their callees are ranked in the order the
    callers are walked.
  - No tie is left among reachable items.

### D2. Library ties by first use; unreachable methods not bound

- **The comparator.** `LibraryBindingOrder` replaces `(content key, qualified name)` with
  `(content key, first-use rank)`.
  - Methods with different keys keep ADR-075's order (G4).
  - Methods with equal keys are bound in first-use order. Crowdfund's `__orElse` pair is then ordered by which action
    the wrapper reaches first, whatever the names.
- **Identical bodies are not merged** (I3).
- **The fallback for what `T` does not reach.** The assembly binds only library methods reachable from the root
  through references. It is pruned to the same closure `T` computes.
  - **Why this is safe:**
    - an unreachable method's value is never read;
    - every library binding is a closed function or a total, effect-free value (ADR-075 Risks; `DelayedMethodPass`);
    - a library class's static fields are bound inside each method's body (`JavacLibraries.java:208-264`), so pruning
      drops no initialiser that could run.
  - **What changes:**
    - At the default profile the UPLC optimizer already drops these bindings, as Crowdfund's absent
      `contributionOf__orElse9` shows. So optimised bytes are expected to be unchanged by the pruning; M1 measures
      this.
    - Builds that keep dead bindings get smaller. Source-map builds skip the UPLC optimizer
      (`PirBackend.java:111-123`).
  - **The alternative, if OQ1 rejects pruning:** order unreachable methods after all reachable ones, by
    `(content key, qualified name)`.
    - A remaining tie can then change bytes only if three things hold: the methods are unreachable; the build keeps
      dead bindings; and their lowered values differ. Equal keys with different lowered values happen when they call
      different but equal-keyed callees.
    - I1 would then be stated for optimised builds only.

### D3. Shape-check ids by first use

The shared checker's ids are renumbered by `T` (D1), so that `σ(k)` is the first-use id.

- **Where.** The renumbering runs in `ValidatorAssembly`, after lowering and **before** D2 and D4 order anything, so that
  the content keys see name-independent ids. It rewrites:
  - the id constant of every shared check call;
  - the child ids inside every dispatcher node;
  - the dispatcher, rebuilt with its nodes in new-id order.
- **Debugging.** It is a position-preserving rebuild, as every PIR pass in `PirBackend` already does
  (`PirBackend.java:91-110`). Each rebuilt node inherits its predecessor's entry in `positions`, `PirDebugProvenance`
  and the I13 provenance (I4).
  - Only the ids and their ancestors are rebuilt.
  - The dispatcher and the id constants carry no positions of their own (`StrictBoundaryGenerator` sets none).
- **Pruning.** Types not reached by `T` lose their nodes. With D2's pruning there are none. The dispatcher holds
  exactly the reachable types (I5).
- **What does not change.** The per-root checkers keep their own numbering, which does not depend on names.
- **Correctness argument (I5).**
  - `σ` is a bijection on the registered ids that `T` reaches.
  - Each node's check depends on its type alone, and on its children only through their ids, which `σ` rewrites
    consistently.
  - So the renumbered dispatcher computes, for every id `σ(k)`, what the old one computed for `k`.
  - Test: the differential oracle under Verification compares every shared check with `exactShapeCheck`.

### D4. Helper methods in canonical order (separate milestone, M2)

- **The order.** `wrapMethodBindings` keeps Tarjan's SCCs and their dependency order. Among ready SCCs it binds the
  smallest by `(content key, first-use rank)`, as D2 does.
  - A 2-member SCC orders its members by the same pair, with calls inside the group keyed by a fixed token
    (ADR-075's recursive-group rule).
  - Groups of 3 or more stay unsupported.
- **The content key** is ADR-075's, with free variables written name-free:
  - a library method → its key;
  - a helper → its key, or the group token;
  - a static field → `(static i)`, its textual index;
  - a `@Param` → `(param i)`, its declaration index;
  - a generated `#` name → its text. ADR-060 makes it independent of source names.
- **Barriers.**
  - Static initialisers stay a separate block, outside the helpers, in textual order. They are not reordered.
  - A zero-parameter helper that may fail or trace is delayed into a function by `DelayedMethodPass` after assembly, as
    library methods are. So every helper binding that is evaluated eagerly is total and has no effect, and its position
    changes nothing but indices.
  - D4 makes that reliance executable. A test asserts that, after `DelayedMethodPass`, every helper and library binding
    value is a lambda, a delay, or a term `mayFail` proves total.
- **Unreachable helpers** are pruned as in D2 (OQ1).
- **The fallback** if M2's gate fails: keep declaration order and document "reordering a validator's helper methods
  may change its hash". This needs a maintainer decision (OQ6).

### D5. A name-independence property test, kept in the build

`NameIndependencePropertyTest` (julc-compiler) is the executable form of I1.

- **The corpus:**
  - the in-repo `julc-examples` validators and their libraries;
  - the validators of JuLC's own test suites that compile standalone;
  - the ADR-074 `compileMethod` corpus, sampled;
  - a fixture of JOUST-generated validator and library Java: at least Crowdfund and Tips, exported with
    `Adr078Diag.java.txt`.
- **The rewrites.** For each program, N seeded random rewrites. The renaming is attribution-based (javac `Trees`), not
  regex. It renames every declared identifier to a random fresh name, with half of the draws reversing name order. The
  other rewrites:
  - permute methods within each class;
  - permute top-level classes and move library classes to random packages;
  - permute imports and library sources;
  - insert comments and whitespace.
- **The assertions:**
  - identical FLAT bytes at every level, with source maps on and off;
  - for source-map builds, the same set of mapped DFS indices;
  - the set of mapped indices is unchanged for any rewrite, including those that move lines;
  - for line-preserving rewrites, every mapped index also keeps its line.
- **The negative half (I2).** One rewrite per program that changes a statement order, a static order, a field order or
  a constant must change the bytes wherever the program reads that data.
- **Failure output.** A failure prints the seed and the minimised rewrite.

## Alternatives rejected

- **Merging identical bodies** into one binding.
  - One compiled body would stand for two source methods, which breaks I3.
  - For helpers it would merge two located bodies, and a breakpoint or a step could not tell them apart.
  - Library bodies carry no positions today (`Terms.unlocated`, `JavacLibraries.java:135,179`). But ADR-053's library
    debugging would need them.
  - It is also a size optimisation, a separate concern with its own cost analysis.
  - The maintainer rejected it.
- **Tie-break by declaration order** (rc1's behaviour for ties). Reordering methods would still move the hash, and
  across classes it reintroduces ADR-075's problem.
- **Sort by qualified name** (ADR-075's alternative and its current tie-break). This is the cause of case 1.
- **Number shape checks by a structural content key** instead of first use.
  - It needs a canonical key for cyclic type graphs.
  - Two structurally equal types are distinct nodes (`RecordType` equality includes names), so it still needs a
    first-use tie-break.
  - The chain order, and so the budget, would follow hash order. That is no better than first use.
  - First use is total by construction. Recorded as OQ3 because the maintainer offered either.
- **Ids from a hash of the type**, sparse constants with no renumbering. They make bigger constants, carry a collision
  risk in a correctness-critical dispatcher, and the chain order still needs a canonical order.
- **First use as the primary library key**, ignoring content keys. It is also name-independent, but it changes the
  bytes of most programs. Using it only for ties keeps G4.
- **Leaving the cases as documented limits.** This contradicts JOUST's promise (ADR-0018 D12) for a common, generated
  pattern, and case 2 also reacts to method reordering.

## Affected stages and modules

- `julc-compiler`:
  - `codegen/LibraryBindingOrder` (D2 comparator);
  - `codegen/ValidatorAssembly` (`T`, pruning, D3 renumbering, D4 order);
  - `util/MethodDependencyResolver` (an ordering hook);
  - `codegen/StrictBoundaryGenerator` (renumbering API, registry exposed to the assembly);
  - `frontend/javac/lower/Terms` and `JavacCompilation` (hand the shared registry to the assembly);
  - the new `NameIndependencePropertyTest` and differential tests.
- No change to the PIR or UPLC types, the backend passes, the VM, the ledger API, the annotation processor or the
  Gradle plugin.
- Test oracles that pin bytes:
  - `GoldenUplcTest` and the `*-pre-change-bytes.txt` goldens;
  - `Adr060ByteSnapshotTest`;
  - `LibraryOrderEquivalence`, which tolerates let reordering but not changed constants or chain order. D3 rows need
    re-recording with a reason;
  - `O11BlsTypesTest` pins;
  - the ADR-068 frozen reference.

## Debugging and source maps (verified in code)

- **Source maps are keyed on node identity, not on order.**
  - `Terms.positions()` is an `IdentityHashMap` (`Terms.java:327`), passed pass to pass (`PirBackend.java:91-113`).
  - `UplcGenerator` maps UPLC terms by identity (`UplcGenerator.java:49-50,128-141`).
  - The persisted form indexes DFS positions of the final tree (`SourceMapSerializer.java:14-18`).
  - Reordering `let`s changes DFS indices, but the map is built from the same compile, so it stays consistent.
- **Library terms are unlocated** (`Terms.java:312-325`). D2 cannot move a position. Helper bodies are located, and D4
  moves whole `let`s, whose own nodes carry no position (`ValidatorAssembly.java:389-414`).
- **Debug provenance** (`PirDebugProvenance`, an `IdentityHashMap`) associates parameters, locals and `@Param`s only,
  never method binders (`Terms.java:273-276`).
  - `DebugMetadataCollector` recomputes environment slots from the final term (`DebugMetadataCollector.java:259-266`),
    so they follow any order.
  - Its only sort is by source span (`:273-275`).
- **Stepping and call stacks** use CEK depth and frames (`julc-tools` `UplcToolsService.java:381-405,480-508`). They do
  not use binding order.
- **Java breakpoints** resolve through `sourceMap.lookup` (`UplcToolsService.java:201-209`; Truffle
  `TermToNodeConverter.java:135-142`). UPLC-line breakpoints in the playground are persisted by printed line
  (`julc-playground/frontend/src/lib/uplc/store.ts:42-43`) and shift with any byte change, as today.
- **Traces.** The compiler builds no trace text that carries names or ids. Reordering cannot reorder traces, because
  eager bindings are total (`DelayedMethodPass`).
- **The playground and Wasm** wrap the same `julc-tools` services, so they inherit the above.
- **D3's visible effect on debugging:** the CEK step count of a shared check changes, so step indices on a timeline
  move. Their meaning does not.

## Semantics, cost and size

- **D1, D2, D4** only reorder closed or total bindings, and prune unread ones. Results, failures and traces are kept, and
  budget is kept (ADR-075 I4), with two exceptions:
  - pruning lowers the budget of builds that keep dead bindings: one application and one lambda per pruned binding;
  - D4 can change the budget where a Bekić pair's member order flips.
- **D3** keeps results, failures and traces (I5). Each shared check costs a fixed amount more or less, set by its type's
  dispatch position.
- **Size** moves by de Bruijn index widths, and for D3 by the constant widths. No term is added.

## Compatibility

- **Preview policy.** Byte changes are allowed in preview, at every level (no opt-in).
- **What changes, estimated** (M1 and M2 measure on the replay corpus and record counts):
  - **D2:** programs with equal-keyed library methods whose first-use order differs from name order. JOUST's
    `__orElse` pairs are an example.
  - **D2 pruning:** source-map builds, and any build that keeps dead bindings.
  - **D3:** programs that use `fromPlutusData` shape checks on two or more types, where first use differs from lowering
    order.
  - **D4:** most validators with two or more independent helpers. This is an estimate; it is not measured.
- **JOUST hashes** recorded against pre21 move once with the release that carries M1. Its `JscV2HashStabilityTest`
  hashes are computed, not pinned, so only its docs and release notes change.
- **Release notes:**

  > Script bytes no longer depend on names or on the order of method declarations. Two library methods with identical
  > bodies are bound in the order the program first uses them (they used to follow their names); unused library and
  > helper methods are no longer bound; the ids of the shared `fromPlutusData` shape checker follow first use (they used
  > to follow class and method names and order), which changes the cost of each such check by a few comparison steps;
  > and a validator's helper methods are bound in a canonical order. Script hashes change on recompilation; the program
  > computes the same results, failures and traces.

## Risks

- **A wrong D3 renumbering accepts or rejects the wrong Data.** I5, the differential oracle, the existing
  `StrictDataBoundaryTest`, `StrictBoundaryCostTest` and `JavacLibraryShapeCheckTest`, and VM evaluation of mutated
  Data.
- **The rebuild loses a source position or a provenance association.** I4, the debugger suites, and a test that
  compares source-map entry counts and located DFS indices before and after renumbering.
- **The pruning removes something that is read.** The closure uses the same reference test as dependencies
  (`PirHelpers.containsVarRef`), and the final term is checked for free variables (ADR-060 G2 check).
- **A future eager binding that may fail.** This is ADR-075's risk, now guarded by D4's executable assertion.
- **The cost of `T`.** It is linear in program size, plus one body walk per method.

## Implementation milestones

- **M0. Diagnosis.** Done in this ADR: `adr/evidence/078/`.
- **M1. D1 (`T`), D2 (ties, pruning), D3 (shape ids), D5 (property test).**
  - **Entry:** ADR accepted. OQ1 and OQ2 decided.
  - **Exit:**
    - `NameIndependencePropertyTest` is green with rewrites restricted to library code and library classes. Helper
      reordering is excluded until M2.
    - A D3 differential test compares every shared check with `exactShapeCheck` on generated and mutated Data, for
      random type graphs including recursive and mutually recursive types.
    - ADR-075's evaluation harness (`adr/evidence/075/EvalDiffReplayTest.java.txt`) is run against `08b021ce`.
      Outcomes and traces are identical. Budget differences are confined to programs with shared checks, and with a
      test-only switch that restores the old numbering they vanish.
    - The ADR-076 sharing corpus replay is run (`adr/evidence/076/SharingCorpusReplayTest.java.txt`).
    - The debugger and source-map suites listed under Verification pass.
    - Byte oracles are re-recorded, with each class of change explained.
    - Counts of changed programs are recorded in `adr/evidence/078/`.
    - Full `./gradlew build` is green on the head.
- **M2. D4 (helpers).**
  - **Entry:** M1 merged.
  - **Exit:**
    - The property test is extended to method permutation inside validator classes.
    - `MethodOrderingTest` is extended with: reorder invariance for chains, diamonds, self-recursion, and both member
      orders of a mutual pair.
    - The `DelayedMethodPass` assertion of D4 passes.
    - The same harness and suites as M1 pass.
  - **Gate:** if the harness shows a semantic difference that cannot be explained, or the debug suites need changes
    that alter meaning, take D4's fallback (OQ6).
- **M3. JOUST verification with a local JuLC build.**
  - **Entry:** M1 (and M2 if taken) published to `mavenLocal`.
  - **Exit:**
    - JOUST's full `JscV2HashStabilityTest` passes against the local build.
    - It is extended to reorder every `fn` and method of each example, not only Pledges' two.
    - Follow-ups are filed:
      - Blaster fixtures: `verification/blaster/fixtures` are checked and refreshed where the bytes moved;
      - julc-examples hash notes;
      - Elci and JOUST pins.

## Verification

- **I1 and I2:** `NameIndependencePropertyTest` (D5), and `ImportOrderInvarianceTest` extended with method permutations
  and identical-body pairs.
- **I3:** a test that every source method has its own binding in the assembled PIR. Count `methodBound` callbacks
  against the declared methods that are reachable.
- **I4:** the source-map and debugger suites run unchanged:
  - core: `SourceMapTest`, `SourceMapSerializerTest`, `DeepSourceMapSerializerTest`, `DebugMetadataTest`;
  - compiler: `SourceMapCompilerTest`, `SourceMapEvalTest`, `DebugCompileResultTest`, `DeepDebugMetadataWalkTest`,
    `JavacContractAndDebugTest`, `ExecutionTraceTest`, `BuiltinTraceIntegrationTest`;
  - `julc-vm-java`: `SourceMapVmTest`, `SteppingEvaluationTest`;
  - `julc-vm-truffle`: `JulcDebuggerTest`, `TruffleDebuggerTest`;
  - `julc-tools`: `SourceDebugServiceTest`, `JavacDebugMetadataTest`, `Adr068MetadataCorpusTest`;
  - `julc-playground`: `SourceDebugControllerTest`;
  - `julc-examples`: `DebugVestingTest`.
  - New for I4: for programs with reordered helpers and shared checks, line breakpoints in each helper are hit in the
    same order on the same inputs before and after the change, with the same Java locals view.
- **I5:** the D3 differential oracle and a structural check that `σ` is a bijection with consistent child rewrites.
- **I6:** the ADR-075 evaluation harness and corpus replay as in M1 and M2. The budget deltas of D3 are explained by
  the old-numbering switch.
- **I7:** the property test's permuted runs, and a run under a different `HashMap` seed. Java's `String.hashCode` is
  fixed, so this is done with the rename rewrites rather than a seed.
- **Name guards stay green:** `BinderNameIndependenceTest`, `GeneratedBinderNameLintTest`, the
  `julc.verifyBinderNamespace` check.

## Related findings (out of scope)

- **JOUST could generate one `orElse` helper per type instead of one per use.** That would remove the tie at the source
  and shrink scripts.
- **Source-map builds skip the UPLC optimizer** (`PirBackend.java:111-123`). So their bytes are not the release bytes.
  ADR-053's draft invariant 3 ("bytes identical with debug info on or off") does not hold today.
- **Every shared shape check's cost grows with its id** (a linear chain). An integer `case` dispatch (ADR-041 style)
  would make it constant and remove D3's budget effect. That is a separate optimisation.

## Open questions

- **OQ1. Prune unreachable library and helper bindings at assembly?** (D2, D4)
  - Lean **yes**: it makes `T` total over everything bound, removes the only remaining name tie-break, and changes
    optimised bytes little or not at all (to be measured).
  - Without it, I1 holds for optimised builds only, and dead equal-keyed methods keep a qualified-name order.
- **OQ2. How D3 rewrites the ids.**
  - Lean: a position-preserving rebuild in `ValidatorAssembly`, before ordering.
  - The alternative: lower checks against a deferred id and fill it in at `bindShapeChecks`. This needs a new PIR
    placeholder node. That is a larger change, so it is not preferred.
- **OQ3. First use or structural key for shape ids?** Lean first use (see Alternatives). The maintainer offered either.
- **OQ4. Does type declaration order (`TypeGroups`) reach the bytes?** Not shown either way. M1 adds a "permute type
  declarations" rewrite as exploratory. If it fails, file a follow-up rather than widen this ADR.
- **OQ5. Do source-map builds fall under I1?** Lean yes, given OQ1. Otherwise I1 is stated for builds without source
  maps.
- **OQ6. D4's fallback.** If M2's gate fails, document the limit instead. This is a maintainer decision.
- **OQ7. Budget change in D3 (I6a).**
  - The maintainer's direction allowed budget changes only through de Bruijn indices. D3 cannot meet that, because the
    dispatch chain's length per check *is* the id.
  - Options:
    - (a) accept the per-check delta, as stated in I6;
    - (b) add an integer `case` dispatch first, which makes the cost id-independent but is a separate change;
    - (c) keep the old numbering and document case 2 as a limit.
  - Lean (a).

## Revision history

- **r1 (2026-11-18).** Initial version: M0 diagnosis, decisions D1-D5, open questions OQ1-OQ7.
