# ADR-078: First use breaks ties among equal-content-key library methods

**Status:** Proposed (2026-11-18), revision r5. Design only; nothing is implemented.
**Date:** 2026-11-18
**Risk class:** correctness-sensitive, **low**.
- M1a changes only the comparator that orders library `let`s whose content keys are equal.
- Every such binding is a closed function value or a total, effect-free value (ADR-075, `DelayedMethodPass`). So
  results, failures and script traces are unchanged on every input when no budget limit applies (I6a).
- The budget, and so a budget-limited outcome, can change (I6b; L-C12).

**Target release:** `0.1.0-pre22`, as a quick fix for JOUST.
**Reported by:** JOUST.
- Its ADR-0018 D12 promises that behaviour-neutral edits keep a contract's script hash.
- Its `JscV2HashStabilityTest.behaviourNeutralEdits_leaveEveryScriptHash` fails on `0.1.0-pre21`, and passed on
  `0.1.0-pre21-rc1`.

**Relates to:**
- [ADR-075](075-canonical-library-binding-order.md): M1a **supersedes ADR-075 I2's first exception** (equal-key
  methods ordered by name). Its second exception and its helper non-goal remain.
- [ADR-060](060-generated-binder-namespace.md): source maps key on node identity; on-chain methods are bound by name.
- [ADR-045](045-value-literals.md), [ADR-046](046-array-literals.md), [ADR-043](043-list-to-array-promotion.md): passes
  in the known-limits list.

## Scope

r5 keeps the r2–r4 decision. M1a ranks library methods by first use, and the comparator becomes
`(content key, rank)`.

It **reframes the guarantee**. Rounds 3 and 4 showed that backend passes key on binder spellings (L-C19, C26, the
Codex audit), so a general "renames keep bytes" theorem does not hold. r5 drops it.
- The pre22 guarantee is a **measured acceptance gate** (§ The gate).
- The name and order dependences found so far are a **non-exhaustive** known-limits list.
- Making backend passes key on binder identity is **one future ADR**.

Pruning, shape-check renumbering, helper order and the broad property test stay moved to a later ADR.

## Context

### The failure (M0, `adr/evidence/078/`)

JOUST renames every declared name of `Crowdfund.jsc` so that their alphabetical order reverses. With `0.1.0-pre21`
the hash moves from `28e115b5…` to `f338eb56…` (`crowdfund-diff.txt`).
- All 680 binders keep their order except one adjacent pair, `Crowdfund.contribute__orElse1` and
  `Crowdfund.refund__orElse2`.
- JOUST generates both for `?? 0`, and their bodies are identical.
- `refund` is not renamed (it is a JOUST word), while `contribute` becomes `zqaai`. So ADR-075's tie-break by qualified
  name (`LibraryBindingOrder.java:75`) flips.
- ADR-075 (#333) is not in rc1.
- Tips fails the same way. The other 65 examples pass that edit (`joust-corpus-renameall.txt`).

### Current behaviour (at `08b021ce`)

- **Library order.** `LibraryBindingOrder` runs Kahn's algorithm, dependencies outermost. Each step takes the ready
  method that is smallest by `(content key, qualified name)`. The content key is a SHA-256 Merkle digest of the body,
  without names.
- **Every static method of every offered library unit is bound**, as `Owner.name`, used or not
  (`JavacCompilation.java:433,478`).
  - At `NONE` and in source-map builds all of them stay (`PirBackend.java:111-123`).
  - At higher levels the optimizer drops only unread bindings it proves pure (`UplcOptimizer.java:537-561`).
- **The validator's helpers** are bound inside the library bindings, in declaration order.
  - `compileMethod(root, …, List.of(root))` binds a root's methods twice under one name: as helpers, and as library
    copies (`JavacLibrariesTest.java:465-472`).
- **Mutually recursive library methods are rejected** ("Unbound variable", `UplcGenerator.java:724`), reached or not.
  Self-recursive ones compile.

## Decision

### D1. First-use ranks

There is one rank table per validator artifact. It is keyed by **binding identity**: a library copy of a registered
binder, or a validator helper. A helper and a library copy with the same name are distinct (C22).
- References resolve **lexically**, to the innermost binding of that name in scope in the walked body.
- A method is marked before its body is walked, and calls from one helper to another are followed.

- **D1a. The root walk.** It walks, in this order:
  1. the `@Param` decoders in declaration order;
  2. the static initialisers in textual order;
  3. the handlers in purpose tag order, into the one table; for `compileMethod`, the call of the target.

  The walk is pre-order and left to right over `PirHelpers.mapChildren`, and iterative. The first `Var` that resolves to
  an unranked helper or library method gives it the next rank, and its body is walked there.
- **D1a′. Unread helpers.** Helper declarations not yet visited are walked next, once each, in declaration order.
- **D1b. Unread library methods.** On the library call graph without self-edges:
  - repeatedly, among the unranked methods that no unranked library method calls, rank the one with the smallest
    content key, and walk it;
  - **if several share that key, the qualified name decides.** This is the residual fallback, and the only place a name
    decides;
  - if every unranked method is called by another unranked one (a mutual-recursion cycle, which pre21 rejects), rank
    the smallest by `(content key, qualified name)` and continue. The rejection is unchanged, and the walk terminates.
    This relies on mutual library recursion staying rejected (F16).

  **Cost (C32).** The cost is incremental:
  - each method keeps a count of unranked callers;
  - methods with a count of 0 go in a priority queue;
  - the cycle fallback reads a presorted list with a monotone cursor;
  - so the total is O((n + e) log n) plus one walk per body. JOUST's largest program offers n = 283.

### D2. The comparator

- `LibraryBindingOrder` orders ready methods by `(content key, first-use rank)` instead of
  `(content key, qualified name)`.
- **Programs without equal-key methods are byte-identical to pre21's.** The comparator never compares two equal keys
  there.
- **In programs with them,** the tied methods move, and Kahn's interleaving can move others too.
- There is no merging and no pruning.

### Lemmas (assembly level; accepted programs, whose library call graph is acyclic apart from self-edges)

These state facts about the **assembled PIR**. They are not a claim about final bytes; the gate is.
1. **The ranks are a strict total order.** Every binding identity gets one rank, so Kahn's output is unique. The ranks
   depend on term shape, lexical resolution and helper declaration order, and on names only through D1b's fallback.
2. **What D1a and D1a′ reach is ordered independently of what they do not reach.** A reached method calls only reached
   methods, so the reached subsequence is Kahn run on the reached subgraph.
3. **Transposition** (C31).
   - Take two **adjacent** bindings `u`, `v` whose values are α-equal, which neither value nor the rest of the term
     reads. Swapping them gives an α-equal assembled program.
   - A block of such bindings in any order follows by composing adjacent transpositions.
   - D1b's fallback produces exactly such blocks when the tied values are α-equal and no dependent intervenes.
4. **Equal keys are not identical, and not always adjacent.** Twins can call different twins, and a dependent of the
   first twin can be bound between them.

## The gate (the pre22 guarantee, C28)

pre22 ships if, and only if, **the actual M1a build** passes both checks:

1. **JOUST.** `JscV2HashStabilityTest` passes with **zero failures** at JOUST's release mode (default profile, source
   maps off). That covers:
   - every edit of `edits()`, including `renameAll`;
   - the per-example renamed-file assertion, for all 67 examples;
   - the Pledges `fn` swap.

   Source-map mode is run and reported, not gated.
2. **julc-examples.** Script bytes and indexed debug source locations are unchanged from pre21, except in programs
   with equal-key ties. The census expects none to change.

A single failure blocks pre22. Attributing a failure to a known limit diagnoses it; it does not excuse it.

**Evidence so far.** These are not the gate.
- **Codex's in-memory model of D1 and D2, at `PV11_SAFE`:**
  - `renameAll` changes 0 of the 67 JOUST artifacts (pre21: 2 with source maps off; 3 with them on, Steward included);
  - julc-examples' FLAT and indexed debug locations are unchanged;
  - C12's two spellings both give `c8489246…`, 1,010 B, which succeeds under the limit.
- **On pre21, the "locals, lambda and loop variables, `fn`s, parameters and methods renamed" edit** changes 0 of the 67
  artifacts (name-stripped UPLC, default profile) (`joust-corpus-locals.txt`, C34).
- **The census** gives two separate measurements (C34). Both are simulations on pre21's PIR, compared as label
  sequences (`m1a-census-*.txt`):
  - (a) the simulated order, filtered to binder names present in the default-profile UPLC, changes for 5/67;
  - (b) the full simulated order changes for 17/67.

## Invariants

- **I1** (withdrawn in r5; replaced by the gate). The universal byte claim of r2–r4, and Lemma 3b, are withdrawn
  (C26).
- **I2. Meaningful order still counts.** Statement, field, variant, `@Param` and static field order, and logic, may
  change the bytes.
- **I3. One body per binding identity and assembly copy.** No merging. Checked by the multiset of
  `(handler, binding identity)` `methodBound` callbacks.
- **I4. Debugging unchanged.** No `SourceLocation`, `PirDebugProvenance` association or I13 record changes. Library
  terms are unlocated (`Terms.java:312-325`), and source maps key on node identity (`Terms.java:327`;
  `UplcGenerator.java:49-50`).
- **I5** (moved to a later ADR).
- **I6. Semantics.**
  - **I6a:** on every input with no budget limit, first-order results, observable failure reasons and script traces
    are identical to pre21's. Each moved binding is evaluated at most once, at its pre-optimisation site, when entered.
  - **I6b:** the budget is not guaranteed. Index widths change sizes, and size-driven passes can then decide
    differently (L-C12).
  - **I6c:** the size can change.
- **I7. Determinism.** The order is a function of content keys, ranks, lexical resolution and helper declaration order.
  A name decides only in D1b's fallback.

## Tests (D5): regression and discovery, not a universal gate

- **The property suite** (`LibraryTieOrderPropertyTest`).
  - The rewrites: capture-free renames of library methods (whole overload families) and classes, package moves, and
    permutations of library sources and imports.
  - Seed 78, N = 40 per program.
  - Run at every level, with source maps on and off.
  - It reports coverage by rewrite and mode, and every program it finds whose bytes differ. Each must be either
    attributed to a known limit or fixed.
- **Fixed fixtures.** Which variants compile on pre21 is pinned, and M1a must compile the same ones (C29).
  - **JOUST:** Crowdfund and Tips, base and renamed; equal bytes.
  - **C11:** an unread helper reads one twin; equal bytes after the rename.
  - **C12** (`C12.java.txt`): both M1a spellings give `c8489246…`, 1,010 B, and succeed under
    `ExBudget(31_150_000, 110_000)`. pre21's two outcomes are pinned.
  - **C22:** `compileMethod(root, "f", List.of(root))`. The test asserts which binding identity each read ranks: the
    helper or the library copy.
  - **Cycles:** an unread mutual pair stays rejected; a self-recursive method compiles; mutually recursive helpers
    work.
  - **Expected to differ** (known limits): C13, C14, C19/C19c, C26, C27.
- **The debug oracle (I4, C29).** For every mapped DFS index, the source location is compared through the rewrite
  mapping. Breakpoint hits, step locations and visible locals are compared against pre21's.
- **The semantic oracle (I6a, C30).**
  - It compares first-order results structurally, and compares observable failure reasons and traces. Budgets are
    reported separately.
  - A case that exhausts the budget on one side only is replayed with a higher limit.
- **Unit tests.**
  - Lemmas 1–4 on random DAGs.
  - An instrumented rank selection asserts that the qualified name decides only in D1b's fallback (C32).

## Known limits (non-exhaustive)

These are the name and order dependences found so far, each with its reproduction. More may exist. JOUST's D12 cites
this list.

| ID | What | Reproduction |
|---|---|---|
| L-SC | Shared shape-check ids follow library class names, method order and helper order | `probe-results.txt` |
| L-C1 | Per-root boundary checkers share nodes by simple name and fields | 276 → 318 B, `c1-probe-results.txt` |
| L-C2 | Type registration order (declaration order; units in qualified-name order, `JavacProgram.java:94`) reaches content keys, including through class renames in cross-class recursive groups (C13) | `r2-probes.txt` |
| L-C4 | Library registration order can decide compilability (C20) | pool `[A, B]` rejected, `[B, A]` compiles, `r3-probes.txt` |
| C14 | Renaming one overload-family member splits a binder | 88 → 96 B, `r2-probes.txt` |
| L-H | The validator's helpers follow declaration order | `probe-results.txt` |
| L-C12 | Order changes can change budget and budget-limited outcomes | `C12.java.txt`, `r3-probes.txt` |
| L-C19 | The literal folds (ADR-045, ADR-046) treat a name as a literal only if it is bound exactly once in the whole program (`LiteralFoldPass.java:137-150`) | 33 → 73 B at the default profile (C19c), `r3-probes.txt` |
| C26 | O9 list-index promotion keys by binder and parameter spellings (`ListIndexPromotionPass.java:219,378`) | PV11_COSTED with source maps differs; equal with O9 off, at `NONE`, or with source maps off, `r4-probes.txt` |
| C27 | A library method renamed to a stdlib intrinsic's name is dispatched to the intrinsic (the stdlib lookup comes first, `Calls.java:180-188`) | `Math.magnitude` → `abs`: 33 → 28 B at the default profile, 37 → 52 B at `NONE`, `r4-probes.txt` |
| Audit | Codex's backend audit, not individually reproduced by me: whole-program spelling counts in `ValueConversionSharingPass` (`:181-185`); lexical lookups in the narrowing and boolean proofs; fresh-name checks in promotion and pair destructuring; Z-combinator equality in `ClosedValueSharing` (`:145-147`); handler sharing grouped by method name | Codex round 4 |
| L-C3, L-C5–L-C7 | The interactions of pruning, renumbering and helper reordering (future work only) | Codex round 1 |
| L-SM | Source-map builds skip the UPLC optimizer, so their bytes differ from release bytes | `PirBackend.java:111-123` |

**When L-C19 fires (C33, F17).**
- **The trigger:** a local or parameter is bound to a literal and used in a foldable call (O10 `JulcArray.of`, O14
  Value literals), and its spelling is also bound **anywhere else** in the program. The fold is then skipped.
- **When a renaming is safe:** it keeps every fold decision exactly when it maps the program's binder spellings
  bijectively. No two distinct spellings may become one, and no shared spelling may split.
- **JOUST:** `n → nRn` and `renameAll` are injective, but either can still create a collision with a name that is not
  renamed. JOUST could avoid the trigger by generating program-unique local names.

## Future work

- **F-ID: one ADR to make backend passes key on binder identity, not spelling.** It covers L-C19, C26, the audit list
  and C27's interception.
  - **Trigger (F18, OQ9):** M3 completes, or earlier if a supported JOUST artifact fails in release mode.
- **F-SC, F-H, F-P, F-T** (from r1): renumber the shape-check ids, order the helpers, prune unread bindings, and the
  broad property test. Each answers the limits listed for it. M1b and M2 ship together.

## Alternatives rejected

- **Merging identical bodies.** This breaks I3; rejected by the maintainer.
- **Tie-break by declaration order (rc1), or by qualified name (pre21).** The first moves the hash when methods are
  reordered; the second is the failure.
- **First use as the primary key.** It changes most programs' bytes.
- **Pruning in M1a.** It changes every `NONE` and source-map build.
- **A general byte theorem (r2–r4's I1 and Lemma 3b).** Backend passes key on spellings (C26 and the audit), so it
  cannot be proved without F-ID. Replaced by the gate.

## Compatibility and release note

- **Deployed scripts and compiled artifacts are unaffected.**
- **Recompiling** changes bytes, hash and address only for programs with equal-content-key library methods. Near a
  budget limit, the outcome can change (I6b).
- **In-repo oracles** are re-run and explained in M1a's exit: `GoldenUplcTest`, the pre-change goldens,
  `Adr060ByteSnapshotTest`, `LibraryOrderEquivalence`, the ADR-068 frozen reference, the `O11BlsTypesTest` pins and the
  Blaster fixtures.
- **Release note (C31):**

  > First use now breaks ties among library methods with equal content keys: such methods are bound in the order the
  > program first uses them, not by their qualified names. Methods the program never reads are ordered by content
  > key, and only where those keys are equal does the qualified name still decide. Scripts already compiled or deployed
  > are unaffected; recompiling changes the bytes of a script that binds such methods (measured: …). Without a budget
  > limit it computes the same results, failures and traces; its budget can differ. Names and orders still reach the
  > bytes in the cases listed in ADR-078's known limits.

## Risks

- **The ranks miss a reference.** D1b ranks whatever D1a and D1a′ miss, and the unit tests assert that every binding
  identity is ranked.
- **The budget changes** (I6b). This is mitigated by reporting it in M1a's exit and by the release note.
- **The cycle fallback depends on mutual library recursion staying rejected** (F16). A fixture asserts the rejection.
- **The known-limits list is not exhaustive.** The gate is what protects pre22; F-ID is the fix.

## Milestones

- **M0. Diagnosis.** Done.
- **M1a (`0.1.0-pre22`). D1, D2, D5.**
  - **Entry:** r5 accepted.
  - **Exit:**
    - **the gate passes;**
    - D5 is green;
    - the semantic and debug oracles run against `08b021ce`;
    - every in-repo byte change is explained;
    - the compile time is measured;
    - `./gradlew build` is green.
- **M3. JOUST with a local M1a build.**
  - The gate's JOUST half is run and reported.
  - JOUST pins and docs follow, and D12 cites the known limits.
  - When M3 completes, it opens F-ID.
- **M1b and M2 (future).** Their own ADR revision, shipped together.

## Open questions

- **OQ9. When does F-ID start?** When M3 completes, or at the first release-mode failure on a supported JOUST
  artifact (C33, F18).
- All other open questions are answered or moved: OQ1–OQ3, OQ6 and OQ7 to the later ADR; OQ4 answered (L-C2); OQ5 and
  OQ8 answered.

## Revision history

- **r1 (`b7383306`).** The broad design (D1–D5).
- **r2 (`79bf86df`).** Narrowed to M1a.
- **r3 (`2479a06c`).** D1a′; I6 split; C13 and C14; the recursion precondition.
- **r4 (`3ddaf89b`).** Lemma 3 split; L-C19; the M3 acceptance criterion; C20 and C22.
- **r5 (2026-11-18).** After review round 4 on `3ddaf89b` (Fable: approve, with F17 and F18; Codex: changes
  requested, C26 and C27 P1, C28–C31 P2, C32–C34 P3). The guarantee is reframed as the maintainer directed. The
  decision is unchanged.
  - **C26:** Lemma 3b and I1's universal claim are withdrawn; C26 is reproduced and listed as a known limit.
  - **C27:** reproduced exactly and listed.
  - **The audit list** is recorded.
  - **C28:** the gate (zero failures; attribution only diagnoses).
  - **C29:** fixtures pin compilability and C12's alpha hash; C22 asserts which identity each read ranks; the debug
    oracle compares per DFS index.
  - **C30:** the semantic oracle.
  - **C31:** the title and release note; Lemma 3 restated as a transposition.
  - **C32:** instrumented rank selection; the cost is incremental.
  - **C33:** L-C19's conditions, and OQ9's trigger.
  - **C34:** the census measurements are labelled separately; the evidence README is corrected; the
    locals-rename evidence is added.
  - **F17:** L-C19's trigger, and that JOUST could generate unique local names.
  - **F18:** F-ID's trigger.
  - The text that argued the withdrawn theorem is cut.
