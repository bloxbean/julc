# ADR-078: Script bytes that do not depend on names: library ties by first use

**Status:** Proposed (2026-11-18), revision r3. Design only; nothing is implemented.
**Date:** 2026-11-18
**Risk class:** correctness-sensitive, **low** (M1a, the scope of this ADR).
- M1a changes only the comparator that orders library `let`s whose content keys are equal.
- Every such binding is a closed function value or a total, effect-free value (ADR-075, `DelayedMethodPass`). So
  results, failures and traces are unchanged without a budget limit.
- The budget is expected to be unchanged but is **not** guaranteed: I6, L-C12.
- Bytes change only for programs that bind two or more library methods with equal content keys.

**Target release:** `0.1.0-pre22`, as a quick fix for JOUST.
**Reported by:** JOUST.
- Its ADR-0018 D12 promises that behaviour-neutral edits keep a contract's script hash.
- Its `JscV2HashStabilityTest.behaviourNeutralEdits_leaveEveryScriptHash` fails on `0.1.0-pre21` and passed on
  `0.1.0-pre21-rc1`.

**Relates to:**
- [ADR-075](075-canonical-library-binding-order.md): M1a **supersedes ADR-075 I2's first exception** (equal-key
  methods ordered by name). Its second exception (shape-check numbering) and its helper non-goal remain until the
  follow-up of § Future work.
- [ADR-060](060-generated-binder-namespace.md): source maps key on PIR node identity; on-chain methods are bound by name.
- [ADR-074](074-stack-safe-term-walks.md): the walks are iterative.
- [ADR-076](076-shared-bindings-script-size.md): handler sharing inherits the binding order, and its passes decide by
  size.
- ADR-053, Java source-level debugging. This is an untracked draft, not on `main`.

## Scope

- **r1** proposed four changes: library ties by first use, pruning of unreachable bindings, renumbering of the
  shape-check ids, and canonical helper order. It also proposed a property test over every renaming and method
  permutation.
- **r2** narrowed this to **M1a** after round 1 (Codex: 7 P1; Fable: 1 P1). M1a is D1 (first-use ranks) and D2's
  comparator.
- **r3** keeps that scope and answers round 2 (Codex: 4 P1, 3 P2, 1 P3; Fable: approve, with F11–F14):
  - D1 walks unread validator helpers before ranking unread library methods (C11);
  - budget and budget-limited outcomes are separated from unbounded semantics (C12);
  - new known limits: cross-class recursive type groups (C13) and overload families (C14);
  - the D1b recursion precondition (C15/F11);
  - census figures labelled by what they measured (C16);
  - wider D5 oracles (C17);
  - the complexity bound (C18);
  - F12–F14.
- Pruning, shape-check renumbering, helper order and the broad property test stay **moved to a later ADR** (§ Future
  work).

## Context

### What JOUST saw, and why (M0, `adr/evidence/078/`)

JOUST renames every declared name of `Crowdfund.jsc` so that their alphabetical order reverses. With `0.1.0-pre21`
the script hash moves from `28e115b5…` to `f338eb56…`.

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

Further name and order dependencies exist in pre21 (`probe-results.txt`, `r2-probes.txt`, `c1-probe-results.txt`). They
are listed under Known limits.

### Current behaviour (at `08b021ce`)

- **Library order.** `LibraryBindingOrder.innermostFirst` runs Kahn's algorithm over the library methods, dependencies
  outermost.
  - Each step takes the ready method that is smallest by `(content key, qualified name)`.
  - The content key is a SHA-256 Merkle digest of the body: bound variables as indices, library calls as the callee's
    key, types as structure.
- **Every static method of every offered library unit is lowered and bound** (`JavacLibraries.lower`,
  `JavacCompilation.java:433,478`). Its binder is `Owner.name`, so an interchangeable overload family (ADR-060,
  `OnchainOverloads.java:11-18`) shares one binder.
  - With the UPLC optimizer (`BASELINE` and above), unread bindings are dropped only when their value is provably pure
    (`UplcOptimizer.isPure`, `UplcOptimizer.java:537-561`).
  - At `NONE`, and in every source-map build (`PirBackend.java:111-123`), all of them stay.
- **The validator's helper methods** are bound inside the library bindings, in declaration order through Tarjan's SCCs
  (`MethodDependencyResolver`). Unread helpers are bound too, and they can read library methods.
- **Mutually recursive library methods are rejected**, reachable or not ("Unbound variable", `UplcGenerator.java:724`;
  `r2-probes.txt`, C15). Self-recursive ones are bound as a `LetRec` and compile.
- **Library units and their types are registered in qualified-name order** (`JavacProgram.java:94`).
  - A recursive type group's registration order decides which fields become `NamedTypeRef`s.
  - A `NamedTypeRef` is part of the content key, written as `Ref<kind>`.

## Definitions

- **Library class:** a class of an offered library unit that contributes static methods, such as an `@OnchainLibrary`
  class or JOUST's contract class. A data type is not one, even when it is nested in such a class: a record or sealed
  interface used as a value or at a datum or redeemer boundary (L-C1, L-C2). (F12)
- **Registered binder:** the `Owner.name` binder that lowering gives a library method. An interchangeable overload
  family is one registered binder (ADR-060).
- **Content-key twins:** library methods with equal content keys. Their bodies are equal up to bound names and up to
  which content-key twins they call.
- **α-equal:** two values equal up to bound names, with the same free variables.

## Goals (M1a)

- **G1.** Renaming library methods (whole overload families), or renaming library classes or moving them across
  packages, leaves the library binding order unchanged. With ADR-075 I1 (import and library-source order), this fixes
  JOUST's case.
  - **Exceptions:** what the coverage table excludes, namely L-SC, L-C1, L-C2 and C14.
- **G2.** Debugging is unchanged: no source position, provenance association, breakpoint, step or visible local moves.
- **G3.** Results, failures and traces are unchanged on every input without a budget limit. Bytes change only for
  programs with content-key twins.
- **G4.** A quick, low-risk change for `0.1.0-pre22`.

## Non-goals (M1a)

- Everything under Known limits and Future work.
- A guarantee that budgets, or outcomes under a budget limit, are unchanged (I6, L-C12).
- Merging identical bodies (rejected; see Alternatives).
- Bytes across compiler versions: a release may change them, and its notes say so.

## What M1a covers and what it does not (I1 in plain words)

| Behaviour-neutral edit | Bytes kept by M1a? | Why, or where it is tracked |
|---|---|---|
| Rename a library method, or a whole overload family together | **yes**, in every mode, except D1b's residual (measured 0, simulated) | D1, D2 |
| Rename one member of an overload family | **no**: one registered binder becomes two (88 → 96 B, `r2-probes.txt`) | C14 |
| Rename or move a library class (as defined above) | **yes**, unless a recursive type group spans classes (C13) or the program uses shared shape checks (L-SC) | D1, D2, L-C2, L-SC |
| Rename or reorder a data type, or reorder type declarations in a recursive group | **no** | L-C1, L-C2 |
| Permute imports or library sources | **yes** | ADR-075 I1 |
| Comments, whitespace, line endings | **yes** | no position reaches the bytes |
| JOUST's reversed-alphabet rename of a `.jsc` contract | **yes** in simulation, 67/67 (`m1a-census-joust.txt`); measured on actual bytes in M1a's exit | D1, D2 |
| Reorder or rename the validator's own helper methods | reorder **no**; rename **yes** | L-H |
| Reorder methods inside a library class | binding order yes; compilability may change (L-C4); shape-check ids may change (L-SC) | ADR-075, L-C4, L-SC |
| Statement, field, variant, `@Param` or static field order, or any logic change | **may** change bytes (non-goal) | I2 |

### For JOUST (F13)

With `0.1.0-pre22`, ADR-0018 D12 holds for renames and for `fn` reordering in contracts that avoid four things:
- shared shape checks (L-SC);
- two boundary types with the same simple name and fields (L-C1);
- recursive type groups, including groups spanning classes that a rename reorders (L-C2, C13);
- renaming one member of an overload family alone (C14).

No JOUST example uses shared shape checks today. M1a's exit (M3) measures D12 on the corpus with actual bytes, per
artifact and mode.

## Invariants

- **I1. Library ties are name-independent** (r3: rewrites restricted to those that keep registered binders; constrains
  D1 and D2).
  - **For** a fixed compiler version and configuration (target, level, profile, options, source maps on or off) and a
    fixed set of offered library units.
  - **The rewrites** preserve the partition of source declarations into registered binders (C14). They are:
    - a bijective, capture-free renaming of library methods, with a whole overload family renamed together;
    - a renaming of library classes, and moves of them across packages;
    - permutations of imports and library sources.
  - **The result:** identical FLAT bytes within each mode, for every program that has:
    - no shared shape check (L-SC);
    - no recursive type group spanning library units (L-C2, C13);
    - no D1b residual tie between unread library methods that are not α-equal (measured: none in either corpus, by
      simulation).
- **I2. Meaningful order still counts** (kept). Statement, field, variant, `@Param` and static field order, and logic,
  may change the bytes.
- **I3. One body per registered binder and assembly copy** (r3: restated per C14). Every registered binder keeps its own
  `let` in every assembly copy (one per handler under auto-dispatch), with its own PIR body. There is no merging.
  - Test: the multiset of `(handler, registered binder)` `methodBound` callbacks, with their resolved declarations, is
    unchanged from pre21.
- **I4. Debugging unchanged** (kept). No `SourceLocation`, `PirDebugProvenance` association or I13 record changes. Java
  breakpoint hits, source-step locations and visible locals are the same as pre21's on the same source (§ Debugging,
  D5).
- **I5. Shape dispatch** (moved to a later ADR, with r1 D3).
- **I6. Semantics** (r3: split per C12).
  - **I6a. Unbounded:** on every input, with no budget limit, results, failures and traces are identical to
    `0.1.0-pre21`'s. Only `let`s of closed or total, effect-free values move, and each is evaluated exactly once
    wherever it sits.
  - **I6b. Budget:** not guaranteed.
    - The CEK machine charges a variable lookup the same whatever its index. But an order change changes index widths,
      and so FLAT sizes.
    - The UPLC optimizer's size-driven passes then decide on different sizes: `ClosedValueSharing` binds a value when
      it pays, and ADR-076's handler sharing keeps a result only if it is smaller. They can produce different terms,
      and so a different budget. A budget-limited evaluation can then succeed on one side and exhaust on the other
      (L-C12).
    - ADR-075 measured no budget difference over 266,441 evaluations of the same kind of change. M1a's exit measures
      it again and reports every difference.
  - **I6c. Size:** script size can change by index widths and by those passes' decisions.
- **I7. Determinism** (kept). The library binding order is a function of the content keys, D1's ranks, reference
  resolution, and the helpers' declaration order. Name text decides only in D1b's residual.

## Decisions

### D1. First-use ranks

**D1a. The root walk `T`.**
- **What it walks:** the program the assembly builds, minus the library and helper `let`s:
  1. the `@Param` decoders in declaration order;
  2. the static initialisers in textual order;
  3. the entry wrapper with its handlers in purpose tag order; for `compileMethod`, the call of the target.
- **How:** pre-order, left to right, over `PirHelpers.mapChildren`'s child order, iteratively (ADR-074).
- **Methods:** at the first `Var` that resolves to an unranked helper or library method, the method takes the next rank
  and its body is walked there. It takes the rank before its body is walked, so recursion ends.

**D1a′. Unread validator helpers** (new in r3, C11).
- After `T`, each helper `T` did not reach is walked, in the helpers' **declaration order**, as `T` walks a method. Its
  unranked library callees are ranked there.
- Helper order is outside M1a (L-H), and I1's rewrites do not reorder helpers, so declaration order is consistent with
  I1.
- A helper rename does not change it: declaration order is not name order.

**D1b. Unread library methods.** M1a binds every offered method, as today, so what D1a and D1a′ leave is ranked too.
- **The graph:** the library call graph without self-edges. A self-recursive method is its own `LetRec` and does not
  block itself.
- **The step:** repeatedly, among the unranked library methods that no unranked library method calls:
  - take the one with the smallest content key;
  - rank it, and walk it as `T` does, which ranks its unranked callees.
- **The residual:** if several methods share that smallest key, the qualified name picks the first. This is the only
  place a name decides. Lemma 3 shows it is invisible when the tied values are α-equal.
- **Rejected inputs** (C15/F11).
  - If every unranked method is called by another unranked one, they contain a cycle of two or more methods. pre21
    rejects such a program ("Unbound variable").
  - D1b then takes the smallest-key unranked method, by key and then qualified name, and continues. So it always
    terminates and changes nothing about the rejection, which happens later, at UPLC generation.
  - The tie-break inside such a group never reaches bytes, because no bytes are produced.
- **The data structure** (C18):
  - each method keeps a count of its unranked library callers;
  - the methods with count 0 are kept in a priority queue by `(content key, qualified name)`;
  - ranking a method decrements its callees' counts.
  - This is O((n + e) log n) for n library methods and e call edges, plus one walk of each body. JOUST's largest
    program offers 283 library methods (Safes).

**Lemmas** (verified against `LibraryBindingOrder` and the census):
1. **First use is total and name-independent.**
   - D1a, D1a′ and D1b give every method exactly one rank, at a distinct step. So `(content key, rank)` is a strict
     total order, and Kahn's output is unique.
   - The walks read only term shape, reference resolution and helper declaration order. A capture-free rename that
     keeps registered binders commutes with resolution, so it maps the output onto itself.
   - Two callers with identical bodies are walked at different steps, so their callees are ranked in that order.
2. **The order of what `T` reaches does not depend on what it does not reach.**
   - A reached method calls only reached methods, so binding an unreached one never makes a reached one ready.
   - When Kahn picks a reached method, it is the least ready one, and so the least ready reached one.
   - So the subsequence of reached methods is Kahn run on the reached subgraph alone.
   - Reached ranks are below all others, so a reached method always precedes a twin that is ready at the same time.
3. **α-equal residual ties are invisible** (r3: condition restated per C11).
   - Suppose D1b's tie holds library methods `u1` and `u2`. Then nothing in the program reads them:
     - no unranked library method calls them, by the step's condition;
     - no ranked method calls them, because it would have ranked them;
     - no helper reads them, after D1a′;
     - the root does not read them.
   - If their values are α-equal, they have the same dependencies and become ready together. Binding the first makes
     nothing ready, and everything ranked between them is a callee of the first, already bound. So Kahn binds them
     adjacently.
   - `let u1 = V in let u2 = V in R`, where the whole remaining term `R` (helpers included) reads neither binder, is
     α-equal to the program with the two swapped. So the bytes are the same either way.
   - In r2 this argument ignored helpers. C11's fixture (an unread helper reading `alpha`) breaks it at `NONE` and in
     source-map builds (`r2-probes.txt`). D1a′ ranks such methods before D1b, so they never reach the residual.
4. **Twins need not be identical or adjacent.**
   - Two methods that call different twins have equal keys but different de Bruijn bodies.
   - A dependent of the first twin with a smaller key is bound between the twins.
   - Lemmas 2 and 3 cover what remains; the residual is stated in I1.

### D2. The comparator

- **The change:** `LibraryBindingOrder` orders ready methods by `(content key, first-use rank)` instead of
  `(content key, qualified name)`. The qualified name is used only in D1b's residual.
- **Programs without twins** are byte-identical to pre21's: the comparator never compares two equal keys there.
- **Programs with twins:** the twins' order changes, and so can the relative order of other methods (r3, C12). Kahn's
  interleaving depends on when each twin's dependents become ready, so methods with different keys can move too.
- **No merging** (I3). **No pruning** (withdrawn in r2).

### D3. Shape-check ids by first use (moved to a later ADR)

See Future work F-SC.

### D4. Helper methods in canonical order (moved to a later ADR)

See Future work F-H.

### D5. Tests for M1a (r3: oracles widened, C14, C17)

**`LibraryTieOrderPropertyTest`** (julc-compiler):
- **The corpus.** Programs with no shared shape check and no recursive type group spanning units:
  - the ADR-075 `ImportOrderInvarianceTest` programs;
  - generated programs with two to four library classes that hold twin groups:
    - α-equal twins;
    - twins calling distinct twins;
    - reached and unreached twins;
    - self-recursive twins;
    - an overload family;
  - JOUST fixtures: the generated Java of Crowdfund and Tips, as committed and after `renameAll`, exported with
    `Adr078Diag.java.txt`.
- **The rewrites:**
  - a capture-free, resolution-preserving rename of library methods and classes. Fresh names occur nowhere in the
    program, references are rewritten by symbol, javac re-attributes the result to the same declarations, and an
    overload family is renamed as a whole;
  - moves of library classes across packages, including packages that sort among `org.julclang.stdlib`;
  - permutations of library sources and imports.
- **Seeds.** Base seed 78. N = 40 rewrites per program in the build; a system property raises N for a soak run. A
  failure prints the seed and the rewrite.
- **The assertions:**
  - identical FLAT bytes at `NONE`, `BASELINE`, `PV11_SAFE`, `PV11_COSTED` and the default profile, with source maps
    on and off, each mode compared with itself;
  - with source maps, the same set of mapped DFS indices.

**Fixed fixtures:**
- **JOUST:** Crowdfund and Tips, base and renamed, give identical bytes in every mode.
- **Twin bodies:** the JuLC-only two-identical-bodies probe (`m1a-case1-probe.txt`).
- **C11:** `L.alpha(long x){return x+1;}`, `L.beta(long y){return y+1;}`, an unread helper
  `unused(x){return L.alpha(x);}`, and an entry point that returns `true`. Renaming `alpha` to `zeta` gives identical
  bytes in every mode.
- **Multi-purpose:** a validator whose handlers first use opposite twins. Ranks follow purpose tag order.
- **Static initialiser:** a library call reachable only through a static initialiser.
- **`compileMethod`:** a target whose helpers reach twins.
- **Recursion (C15):**
  - an unread mutually recursive library pair: the rejection is preserved, and D1b terminates;
  - a supported self-recursive library method;
  - a mutually recursive pair of validator helpers.
- **Residual:** a planted D1b residual that is not α-equal. It documents the exception: its bytes are asserted equal
  at levels that drop it, and the test records that they may differ at `NONE` and in source-map builds.
- **Budget (C12):** Codex's size-threshold fixture, kept with its explicit budgets, documents L-C12. The test asserts
  equal unbounded results; budgets are recorded, not asserted equal.
- **Negative controls:**
  - changing a constant in one twin changes the bytes (I2);
  - changing the body of an unread binding that survives at `NONE` changes the `NONE` bytes.
- **Known limits, as expected-to-differ tests:** C13 (cross-class recursive type group), C14 (one overload-family member
  renamed) and C1.

**Debug oracles (I3, I4):**
- On the same source, pre21 and M1a give the same:
  - Java breakpoint hits;
  - source-step locations;
  - visible locals;
  - multiset of `(handler, registered binder)` `methodBound` callbacks, with resolved declarations.
- For renamed sources, the same holds through the rewrite mapping.

**Unit tests (`LibraryBindingOrderTest`):**
- Lemmas 1–3 on 500 random DAGs with planted twin groups, self-loops, and unread helpers that read twins.
- An instrumented comparator asserts that the qualified name is reached only in D1b's residual.

## Alternatives rejected

- **Merging identical bodies.** One compiled body would stand for two source methods, which breaks I3.
  - Library bodies carry no positions today (`Terms.java:312-325`), but ADR-053's library debugging would need them.
  - It is also a size optimisation with its own cost analysis.
  - The maintainer rejected it.
- **Tie-break by declaration order** (rc1's effective behaviour). Reordering library methods would move the hash, and
  across classes it reintroduces ADR-075's problem.
- **Tie-break by qualified name** (pre21). This is the cause of JOUST's failure.
- **First use as the primary key.** Name-independent too, but it would change the bytes of most programs; M1a changes
  only programs with twins.
- **Pruning unreachable methods in M1a** (r1 D2).
  - It changes the bytes of every `NONE` and source-map build.
  - It removes eager-helper breakpoints if extended to helpers (L-C5).
  - It interacts with the shape-check registry (L-C3).
- **D1a only**, with unread twins ordered by name (OQ8).
  - Simulated on the JOUST corpus, its order is the same before and after `renameAll` (67/67). But that is because
    `renameAll` keeps imported and API names.
  - C11's JuLC-only fixture shows a rename-visible difference at `NONE` and in source-map builds.
  - D1a′ and D1b remove it for about the size of a priority queue.

## Affected stages and modules (M1a)

- `julc-compiler`:
  - `codegen/LibraryBindingOrder`: the comparator, and the ranks as an input;
  - `codegen/ValidatorAssembly`: computes D1a, D1a′ and D1b over the parts it already holds (`validator()`,
    `method()`);
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
  - The persisted DFS indices are recomputed in the same compile.
  - Debug-metadata environment slots are recomputed from the final term (`DebugMetadataCollector.java:259-266`).
- **Not affected:** stepping and call stacks, which use CEK depth and frames (`julc-tools` `UplcToolsService.java:381-405,480-508`),
  and Java breakpoints, which go through `sourceMap.lookup` (`UplcToolsService.java:201-209`). D5's debug oracles check
  this end to end.
- **UPLC-line breakpoints** in the playground (`julc-playground/frontend/src/lib/uplc/store.ts:42-43`) shift only for
  programs whose bytes change.
- **Traces:** none is built from names, and eager bindings are total.

## Compatibility (M1a)

- **Deployed scripts and compiled artifacts are unaffected.** Their bytes are fixed, on chain and in `plutus.json`.
- **Recompiling with `0.1.0-pre22`** changes bytes, hash and address only for programs that bind content-key twins. The
  budget of such a program may change (I6b).
- **Simulated estimate** (C16). This is not a measurement of M1a's bytes.
  - The census reads pre21's captured PIR after the PIR passes, recomputes the content keys, and simulates both
    comparators' Kahn order. It compares the orders as sequences of `key-prefix (8 hex)@rank`
    (`m1a-census-*.txt`).
  - It does **not** simulate D1a′.

  | Corpus | Simulated order change, filtered to binder names present in the default-profile UPLC | Simulated full-order change (relevant to `NONE` and source-map builds) |
  |---|---|---|
  | JOUST examples, as committed | 5/67 (Coins, Dex, Locker, Tipper, Tips) | 17/67 |
  | julc-examples (43 validators) | 0 | 0 |

  - M1a's exit replaces these with measured FLAT comparisons per artifact and mode. The release notes quote only the
    measured numbers (F14).
- **In-repo oracles** are re-run and explained in M1a's exit:
  - `GoldenUplcTest`;
  - the `*-pre-change-bytes.txt` goldens;
  - `Adr060ByteSnapshotTest` and `LibraryOrderEquivalence`;
  - the ADR-068 frozen reference;
  - the `O11BlsTypesTest` pins;
  - the Blaster fixtures (`verification/blaster/fixtures`).
- **Preview policy:** byte changes are allowed at every level.
- **Release notes** (draft; the counts are filled in from M1a's measured exit):

  > Library methods whose compiled bodies are the same up to names are now bound in the order the program first uses
  > them; they used to follow their qualified names, so renaming such a method or its class could change a script's
  > hash. Scripts already compiled or deployed are unaffected. Recompiling changes the bytes, hash and address only of
  > a script that binds such methods (measured: N of M programs in …); it computes the same results, failures and
  > traces, and its budget may differ slightly. Some names and orders still reach the bytes — shared `fromPlutusData`
  > shape checks, boundary types with the same simple name and fields, recursive type groups, a single overload
  > renamed apart from its family, and the order of a validator's own helper methods; see ADR-078's coverage table.

## Risks (M1a)

- **The ranks miss a reference.** The walks resolve references with the same test as the dependencies,
  `PirHelpers.containsVarRef`, and D1b ranks whatever D1a and D1a′ miss. Every method is ranked, and the unit tests
  assert it.
- **The budget changes** (I6b, L-C12). Budget-limited outcomes can differ for programs with twins. This is mitigated by
  measuring in M1a's exit and by the release note.
- **A future eager binding that may fail** would make any reordering visible. This is ADR-075's risk. M1a adds a test
  that, after `DelayedMethodPass`, every library binding value is a lambda, a delay, or a term `mayFail` proves total.
- **The cost of the walks** is O((n + e) log n) plus one walk per body (D1b), with n ≤ 283 in the JOUST corpus.
  M1a's exit measures the compile time.

## Implementation milestones

- **M0. Diagnosis.** Done: `adr/evidence/078/`.
- **M1a (`0.1.0-pre22`). D1 (D1a, D1a′, D1b), D2, D5.**
  - **Entry:** this revision accepted.
  - **Exit:**
    - the D5 tests are green;
    - ADR-075's evaluation harness (`adr/evidence/075/EvalDiffReplayTest.java.txt`) is run against `08b021ce`.
      Unbounded outcomes and traces are identical (I6a). Budget differences are reported per case (I6b), and so are
      budget-limited outcomes under the transaction limit;
    - the in-repo oracles and Blaster fixtures are checked, and every byte change is explained;
    - measured FLAT comparisons (pre21 vs M1a, and base vs renamed) are recorded per artifact and mode for the JOUST
      corpus and julc-examples (C16);
    - the debugger and source-map suites pass, and so do D5's debug oracles;
    - the compile time is measured;
    - `./gradlew build` is green on the head.
- **M3. JOUST verification with a local build.**
  - **Entry:** M1a published to `mavenLocal`.
  - **Exit:**
    - JOUST's full `JscV2HashStabilityTest` passes on actual bytes in every mode JOUST builds;
    - `reorderingFns_leavesPledgesHash` is extended to every example, with exceptions only where an example hits a known
      limit (named per example);
    - JOUST pins and docs follow.
- **M1b (future; a later ADR revision).** Shape-check ids (F-SC), pruning (F-P), and L-C1 and L-C2 (with C13).
  - **Entry:** the later ADR is accepted and resolves L-C1, L-C2, L-C3, L-C6, L-C12, OQ1, OQ2, OQ3 and OQ7.
- **M2 (future; same later ADR).** Helper order (F-H) and overload families (C14).
  - **Entry:** the later ADR is accepted and resolves L-C4, L-C5, L-C7, C14 and OQ6.
  - **M1b and M2 ship together**, so that users see one hash change, not two.

## Verification (M1a)

- **I1:** `LibraryTieOrderPropertyTest` and the fixed fixtures (D5), and `ImportOrderInvarianceTest` extended with
  twins and class renames.
- **I3:** the multiset of `(handler, registered binder)` `methodBound` callbacks.
- **I4:** D5's debug oracles, and the existing suites run unchanged:
  - core: `SourceMapTest`, `SourceMapSerializerTest`, `DeepSourceMapSerializerTest`, `DebugMetadataTest`;
  - compiler: `SourceMapCompilerTest`, `SourceMapEvalTest`, `DebugCompileResultTest`, `DeepDebugMetadataWalkTest`,
    `JavacContractAndDebugTest`, `ExecutionTraceTest`, `BuiltinTraceIntegrationTest`;
  - `julc-vm-java`: `SourceMapVmTest`, `SteppingEvaluationTest`;
  - `julc-vm-truffle`: `JulcDebuggerTest`, `TruffleDebuggerTest`;
  - `julc-tools`: `SourceDebugServiceTest`, `JavacDebugMetadataTest`, `Adr068MetadataCorpusTest`;
  - `julc-playground`: `SourceDebugControllerTest`;
  - `julc-examples`: `DebugVestingTest`.
- **I6a:** ADR-075's evaluation harness without a budget limit, and the ADR-076 corpus replay.
- **I6b:** the same harness with budgets reported, and C12's fixture.
- **I7:** the D5 permuted runs and the instrumented comparator.
- **Name guards stay green:** `BinderNameIndependenceTest`, `GeneratedBinderNameLintTest`, the
  `julc.verifyBinderNamespace` check.

## Known limits (not fixed by M1a)

Each limit has its review finding and its evidence.

- **L-SC. Shared shape-check ids** (r1 case 2; `probe-results.txt`).
  - The ids follow library class name, then method declaration order, then the validator's helper declaration order.
  - A rename or reorder that changes that order changes the bytes of programs using `T.fromPlutusData` shape checks on
    two or more types.
- **L-C1. Per-root boundary checkers share nodes by simple name and fields** (Codex C1).
  - `StrictBoundaryGenerator.typeIds` is keyed by the resolved `PirType`, and `RecordType` equality includes the simple
    name and field names (`PirType.java:43`).
  - Reproduced: 276 B → 318 B (`c1-probe-results.txt`); Codex measured 245 B → 287 B.
- **L-C2. Type registration order reaches the content key** (Codex C2, C13).
  - `TypeGroups` registers a recursive group in declaration order. Units are read in qualified-name order
    (`JavacProgram.java:94`). That decides which fields become `NamedTypeRef`s, and the content key writes a reference
    as `Ref<kind>`.
  - So reordering type declarations, **or renaming a library class** whose types form a recursive group with another
    class's, can change keys and the library order, even with no twins.
  - C13 reproduced: `A.R(Optional<B.S>)` and `B.S(Optional<A.R>)`, with A renamed to Z, give different hashes at the
    default profile, at `NONE` and with source maps (`r2-probes.txt`).
- **C14. Renaming one member of an overload family splits a registered binder.**
  - Interchangeable overloads share `Owner.name` (`OnchainOverloads.java:11-18,30-35`).
  - Renaming one gives two bindings: 88 B → 96 B (`r2-probes.txt`; Codex measured 57 B → 65 B).
  - I1 therefore admits only rewrites that keep the partition of declarations into registered binders.
- **L-H. The validator's helper methods follow declaration order** (r1 case 3; `probe-results.txt`).
- **L-C3. Pruning interacts with the shape-check registry** (Codex C3). Pruning and renumbering must be designed
  together.
- **L-C4. Permuting methods can change whether a program compiles** (Codex C4). ADR-068 registers library methods in
  source order (`LibraryRegistration.order`).
- **L-C5. Pruning would remove the bindings of unread eager helpers**, so their breakpoints would not resolve (Codex
  C5).
- **L-C6. Pruning and renumbering change budgets in proportion to data** (Codex C6).
- **L-C7. Swapping a mutually recursive helper pair changes size**, because the Bekić encoding duplicates one member
  (Codex C7).
- **L-C12. Order changes can change the budget** (Codex C12).
  - Index widths change FLAT sizes, and size-driven optimizer decisions (`ClosedValueSharing`, ADR-076 handler sharing)
    can flip.
  - Codex's fixture: twins `alpha`/`beta` (identity), and `c(n, xs)` with 121 chained `let`s and 12
    `xs.head().longValue()`, compiled with target `L.c(n, JulcList.of(ONE)) + L.beta(n)`. It gives 1,010 B and
    31,073,556 CPU against 1,012 B and 31,201,556 CPU. A 31,150,000 CPU limit succeeds on one side and exhausts the
    budget on the other.
  - **Not reproduced by the author.** My reconstruction from that description, renaming the twin in pre21
    (`r2-probes.txt`), gave identical 998 B and 30,764,348 CPU in both variants. The mechanism is accepted. M1a's D5
    adopts Codex's exact fixture.
- **L-SM. Source-map builds differ from release builds** (`PirBackend.java:111-123`). ADR-053's draft invariant 3 does
  not hold today. M1a's I1 is stated within each mode.

## Future work (moved to a later ADR)

This keeps r1's analyses so the later ADR starts from them.

- **F-SC (r1 D3, I5).** Renumber the shared checker's ids by first use, in the assembly before ordering, as a
  position-preserving rebuild.
  - The correctness condition is that the renumbering `σ` is a bijection and each node's child ids are rewritten by the
    same `σ`.
  - The test is differential against `exactShapeCheck`.
  - ADR-041's integer `case` dispatch is not applied to this dispatcher today: the default profile still emits the
    `equalsInteger` chain. First-use ids are dense, so a `case` dispatch could make each check's cost independent of
    its id (F5, OQ7, L-C6).
- **F-H (r1 D4).** Helpers in `(content key, first use)` order over Tarjan's SCCs.
  - Free variables keyed name-free: statics by textual index, `@Param`s by declaration index.
  - It must answer L-C4, L-C5, L-C7 and C14.
- **F-P (r1 D2's pruning).** Bind only what is reachable. It needs L-C3 and L-C5 answered.
- **F-T (r1 D5).** The broad property test: rename every identifier, permute methods and types. It needs L-C1, L-C2,
  L-C4 and C14 fixed first.

## Open questions

- **OQ1. Pruning?** Moved to a later ADR (F-P).
- **OQ2. How to rewrite the ids?** Moved to a later ADR (F-SC).
- **OQ3. First use or a structural key for the ids?** Moved to a later ADR (F-SC).
- **OQ4. Does type declaration order reach the bytes?** Answered in r2: yes (L-C2). r3 adds that class renames do too
  (C13).
- **OQ5. Do source-map builds fall under I1?** Answered for M1a: yes, within each mode.
- **OQ6. D4's fallback?** Moved to a later ADR (F-H).
- **OQ7. The budget effect of renumbering.** Moved to a later ADR (F-SC, L-C6).
- **OQ8. D1b in M1a?** **Answered in r3: yes, with D1a′.**
  - Simulated on JOUST, D1a alone and D1a+D1b both keep base and renamed orders equal (67/67), because `renameAll`
    keeps imported and API names.
  - C11's fixture shows that D1a alone, and D1b without D1a′, leave a rename-visible difference at `NONE` and in
    source-map builds.

## Revision history

- **r1 (2026-11-18, `b7383306`).** Initial version: M0 diagnosis, D1–D5, OQ1–OQ7.
- **r2 (2026-11-18, `79bf86df`).** After review round 1 on `b7383306` (Codex: 7 P1; Fable: 1 P1).
  - **Narrowed to M1a:** D1, with D1b new, and D2's comparator.
  - **Withdrawn:** pruning.
  - **Moved to a later ADR:** D3, D4, I5 and the broad D5.
  - **New:** Lemmas 1–4, the coverage table, the census, compatibility and release notes, M1a's exit gates, and Known
    limits L-C1 to L-C7.
- **r3 (2026-11-18).** After review round 2 on `79bf86df` (Fable: approve, with F11 P2 and F12–F14 P3; Codex: changes
  requested, C11–C14 P1, C15–C17 P2, C18 P3).
  - **C11:** D1a′ walks unread helpers in declaration order before D1b. Lemma 3's condition is restated, and the
    fixture is added (reproduced).
  - **C12:** I6 is split into unbounded semantics (I6a), budget (I6b) and size (I6c). D2 no longer claims that
    different-key order is kept. The fixture is adopted as L-C12; the author's reconstruction did not reproduce its
    numbers.
  - **C13:** L-C2 is extended to class renames (reproduced), and G1, I1 and the coverage table are qualified.
  - **C14:** I1 is restricted to rewrites that keep registered binders, I3 is restated per registered binder and
    assembly copy, and D5 renames overload families together (reproduced).
  - **C15/F11:** D1b ignores self-edges, defines the path for rejected cycles (verified: pre21 rejects them), and gets
    the recursion tests.
  - **C16:** census figures are labelled as simulations. OQ8 is answered with a D1a-only comparison and C11. JOUST's
    claim is gated on actual bytes in M1a's exit and M3. "Must change" became "may change", and the release-note wording
    changed.
  - **C17:** D5 gains the debug oracles, a multi-purpose fixture, a static-initialiser fixture, a `compileMethod`
    fixture, the `methodBound` multiset, a planted residual and negative controls.
  - **C18:** the D1b data structure and bound are given, and the maximum is corrected (283, Safes).
  - **F12:** a definitions section ("library class", "registered binder", "content-key twins", "α-equal").
  - **F13:** a "For JOUST" paragraph.
  - **F14:** the release notes name the remaining dependencies and quote only measured counts.
