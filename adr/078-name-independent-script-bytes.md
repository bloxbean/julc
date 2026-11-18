# ADR-078: Script bytes that do not depend on names: library ties by first use

**Status:** Proposed (2026-11-18), revision r4. Design only; nothing is implemented.
**Date:** 2026-11-18
**Risk class:** correctness-sensitive, **low** (M1a, the scope of this ADR).
- M1a changes only the comparator that orders library `let`s whose content keys are equal.
- Every such binding is a closed function value or a total, effect-free value (ADR-075, `DelayedMethodPass`). So
  results, failures and script traces are unchanged on every input when no budget limit applies (I6a).
- The budget, and so the outcome under a budget limit, can change (I6b; L-C12, reproduced).
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
- [ADR-045](045-value-literals.md) / [ADR-046](046-array-literals.md): the literal folds of L-C19.
- [ADR-074](074-stack-safe-term-walks.md): the walks are iterative.
- [ADR-076](076-shared-bindings-script-size.md): handler sharing inherits the binding order, and its passes decide by
  size.
- ADR-053, Java source-level debugging. This is an untracked draft, not on `main`.

## Scope

- **r1** proposed four changes: library ties by first use, pruning, shape-check renumbering and helper order.
- **r2** narrowed this to **M1a**: D1 (first-use ranks) and D2's comparator. Everything else moved to a later ADR.
- **r3** answered round 2: unread helpers are walked (D1a′), the semantics invariant was split (I6), C13 and C14 became
  known limits, and the recursion precondition was stated.
- **r4** answers round 3 (Codex: C19–C25; Fable: approve, with F15 and F16) and keeps M1a's scope:
  - **Lemma 3** is split into an assembly lemma and a final-bytes lemma. D1b name ties whose values differ as written
    leave I1's byte guarantee (C19).
  - **L-C19:** the literal folds count binders by spelling, so local names reach the bytes. Reproduced at the default
    profile.
  - **"For JOUST"** becomes a corpus acceptance criterion (C21).
  - **Compilability:** I1 needs both sides to compile (C20).
  - **Helpers and their library copies** have distinct identities, resolved lexically (C22).
  - **Census figures** are labelled as label-sequence comparisons (C23).
  - **Budget wording** is separated throughout (C24, F15).
  - **The complexity bound** is completed (C25); F16.
  - **C12 is reproduced exactly** and becomes a D5 fixture (`C12.java.txt`).

## Context

### What JOUST saw, and why (M0, `adr/evidence/078/`)

JOUST renames every declared name of `Crowdfund.jsc` so that their alphabetical order reverses. With `0.1.0-pre21`
the script hash moves from `28e115b5…` to `f338eb56…`.

M0 rebuilt the program with JOUST's toolchain (`joust-julc-pre21@5522cafc`) against the `0.1.0-pre21` artifacts, and
diffed the UPLC with binder names stripped (`crowdfund-diff.txt`).
- All 680 binders are in the same order but for one adjacent pair: `Crowdfund.contribute__orElse1` and
  `Crowdfund.refund__orElse2` swap.
- JOUST generates both methods for `?? 0`, and their bodies are identical.
- `refund` is a JOUST language word and is not renamed, while `contribute` becomes `zqaai`. So ADR-075's qualified-name
  tie-break (`LibraryBindingOrder.java:75`) flips.
- ADR-075 (#333) is not in `v0.1.0-pre21-rc1`, which is why rc1 passed.
- Of the 67 JOUST examples, only Crowdfund and Tips move under the rename (`joust-corpus-renameall.txt`).

Further name and order dependencies exist in pre21. They are listed under Known limits, each with its reproduction
(`probe-results.txt`, `r2-probes.txt`, `r3-probes.txt`, `c1-probe-results.txt`).

### Current behaviour (at `08b021ce`)

- **Library order.** `LibraryBindingOrder.innermostFirst` runs Kahn's algorithm, dependencies outermost. Each step
  takes the ready method that is smallest by `(content key, qualified name)`. The content key is a SHA-256 Merkle digest
  of the body: bound variables as indices, library calls as the callee's key, types as structure.
- **Every static method of every offered library unit is lowered and bound** (`JavacCompilation.java:433,478`), as
  `Owner.name`. An interchangeable overload family (`OnchainOverloads.java:11-18`) shares one binder.
  - The library units are registered in an order that may have to be retried (`LibraryRegistration.order`,
    `LibraryRegistration.java:45-47`), and which can fail (C20).
  - With the UPLC optimizer (`BASELINE` and above), unread bindings are dropped only when their value is provably pure
    (`UplcOptimizer.java:537-561`).
  - At `NONE`, and in every source-map build (`PirBackend.java:111-123`), they all stay.
- **The validator's helper methods** are bound inside the library bindings, in declaration order. Unread helpers are
  bound too.
  - With `compileMethod(root, …, List.of(root))`, a root class is both the root and a library. Its methods are then
    bound twice under the same name: as helpers, and as library copies (`JavacLibrariesTest.java:465-472`).
- **Mutually recursive library methods are rejected**, reachable or not ("Unbound variable", `UplcGenerator.java:724`;
  `r2-probes.txt`). Self-recursive ones compile.
- **Library units and their types are registered in qualified-name order** (`JavacProgram.java:94`). A recursive type
  group's registration order decides which fields become `NamedTypeRef`s, which the content key writes as `Ref<kind>`.
- **After assembly**, PIR passes run on the whole program (`PirBackend.java:90-110`). Some of them key on binder
  spellings:
  - The literal folds (ADR-045 O14, ADR-046 O10) treat a variable as a literal only if its name is "bound exactly once
    in the program" (`LiteralFoldPass.java:137-150`).

## Definitions

- **Library class:** a class of an offered library unit that contributes static methods, such as an `@OnchainLibrary`
  class or JOUST's contract class. A data type is not one, even when nested in such a class (L-C1, L-C2).
- **Registered binder:** the `Owner.name` binder that lowering gives a library method; an interchangeable overload
  family is one registered binder.
- **Binding identity:** a binding as the assembly creates it: a library copy of a registered binder, or a validator
  helper. A helper and a library copy can share a name; they are still distinct identities (C22).
- **Content-key twins:** library methods with equal content keys.
- **α-equal:** values equal up to bound names, with the same free variables.
- **Identical as written:** values equal as PIR terms, bound names included.

## Goals (M1a)

- **G1.** Renaming library methods (whole overload families), or renaming library classes or moving them across
  packages, leaves the library binding order unchanged, and so the bytes.
  - **Exceptions:** what the coverage table excludes: L-SC, L-C1, L-C2, C14, C20, L-C19, and D1b name ties whose values
    differ as written.
  - With ADR-075 I1, this fixes JOUST's case.
- **G2.** Debugging is unchanged: no source position, provenance association, breakpoint, step or visible local moves.
- **G3.** On every input, without a budget limit, results, failures and script traces are unchanged (I6a).
  - Budgets, and outcomes under a budget limit, may change (I6b). (F15)
  - Bytes change only for programs with content-key twins.
- **G4.** A quick, low-risk change for `0.1.0-pre22`.

## Non-goals (M1a)

- Everything under Known limits and Future work.
- A guarantee that budgets, or budget-limited outcomes, are unchanged.
- Merging identical bodies (rejected).
- Bytes across compiler versions.

## What M1a covers and what it does not (I1 in plain words)

| Behaviour-neutral edit | Bytes kept by M1a? | Why, or where it is tracked |
|---|---|---|
| Rename a library method, or a whole overload family together | **yes**, in every mode, except where a D1b name tie's values differ as written | D1, D2, Lemma 3b |
| Rename one member of an overload family | **no** (88 → 96 B) | C14 |
| Rename or move a library class | **yes**, under the same exception, unless a recursive type group spans classes (C13) or the program uses shared shape checks (L-SC) | D1, D2, L-C2, L-SC |
| Rename a local variable or parameter, in a method or in a library | **no** where a literal fold applies: 33 → 73 B at the default profile (`r3-probes.txt`, C19c) | L-C19 |
| Rename or reorder a data type, or reorder type declarations in a recursive group | **no** | L-C1, L-C2 |
| Permute imports or library sources | **yes** when both orders compile; one order can be rejected (C20) | ADR-075 I1, L-C4 |
| Comments, whitespace, line endings | **yes** | no position reaches the bytes |
| JOUST's D12 edits on JOUST's corpus | the M3 acceptance criterion below | § For JOUST |
| Reorder or rename the validator's own helper methods | reorder **no**; rename **yes** | L-H |
| Reorder methods inside a library class | binding order yes; compilability (L-C4) and shape-check ids (L-SC) may change | L-C4, L-SC |
| Statement, field, variant, `@Param` or static field order, or any logic change | **may** change bytes (non-goal) | I2 |

### For JOUST: the M3 acceptance criterion (C21, F13)

M1a promises JOUST no more than I1. Whether JOUST's D12 holds with `0.1.0-pre22` is **measured at M3**, on actual
bytes:
- **The transformations:** every edit of `JscV2HashStabilityTest.edits`:
  - layout;
  - comments;
  - CRLF;
  - header;
  - locals, lambda and loop variables, `fn`s, parameters and methods renamed;
  - `renameAll`;
  - `fn` reordering in every example.
- **The artifacts:** each example's own validator script, as the test selects it, unapplied where it has
  parameters.
- **The modes:** JOUST's build (default profile, source maps off). Source-map builds are reported but not gated, since
  JOUST does not ship them.
- **The result:** pass or fail per example. A failure must be attributed to a named known limit. The limits most
  likely to apply are L-C19 (renamed locals reaching a literal fold), L-SC, L-C1, L-C2, C14 and D1b ties whose values
  differ as written.
- M1a ships if JOUST's current corpus passes. A failure that cannot be attributed to a named limit blocks it.

## Invariants

- **I1. Library ties are name-independent** (r4: compile precondition and spelling exception added; constrains D1 and
  D2).
  - **For** a fixed compiler version and configuration (target, level, profile, options, source maps on or off) and a
    fixed set of offered library units.
  - **Over** pairs of programs that **both compile**, with corresponding resolved registered binders (C20).
  - **The rewrites** preserve the partition of declarations into registered binders:
    - a bijective, capture-free renaming of library methods, with a whole overload family renamed together;
    - a renaming of library classes, and moves of them across packages;
    - permutations of imports and library sources.
  - **The result:** identical FLAT bytes within each mode, for every program that has:
    - no shared shape check (L-SC);
    - no recursive type group spanning library units (L-C2);
    - no D1b name tie whose values are not identical as written (Lemma 3b; C19). Simulated: 19/67 JOUST programs
      have one, all between `AddressAdapter.payment` and `AddressLib.paymentCredential`, which D12's edits do not
      rename; julc-examples has none.
- **I2. Meaningful order still counts** (kept). Statement, field, variant, `@Param` and static field order, and logic,
  may change the bytes.
- **I3. One body per binding identity and assembly copy** (kept). Every registered binder keeps its own `let` in every
  assembly copy, and every helper keeps its own. There is no merging.
  - Test: the multiset of `(handler, binding identity)` `methodBound` callbacks, with their resolved declarations.
- **I4. Debugging unchanged** (kept). No `SourceLocation`, `PirDebugProvenance` association or I13 record changes. Java
  breakpoint hits, source-step locations and visible locals are the same as pre21's on the same source.
- **I5. Shape dispatch** (moved to a later ADR).
- **I6. Semantics** (r4: wording per C24).
  - **I6a. Unbounded:** on every input with no budget limit, results, failures and script traces are identical to
    `0.1.0-pre21`'s.
    - Only `let`s of closed values, or of total and effect-free values, move.
    - Each is evaluated at most once, at its pre-optimisation binding site, when evaluation enters that site.
  - **I6b. Budget:** not guaranteed. An order change changes index widths, and so FLAT sizes, and the optimizer's
    size-driven passes can then decide differently.
    - L-C12 reproduces this exactly: 31,073,556 against 31,201,556 CPU, and Success against `BudgetExhausted` under a
      31,150,000 limit.
    - So recompiling can turn a success into budget exhaustion under the same limit.
  - **I6c. Size:** script size can change by index widths and by those passes' decisions.
- **I7. Determinism** (kept). The order is a function of the content keys, D1's ranks, lexical reference resolution and
  the helpers' declaration order. Name text decides only in D1b's name ties.

## Decisions

### D1. First-use ranks

The rank table is computed per validator artifact. It is keyed by **binding identity** and filled by one visit, with
one visited set (C22).
- References are resolved **lexically**: a `Var` names the innermost binding of that name in scope at that point of the
  walked body. A helper's read of `q.H.g` is the helper, and a library body's read of it is the library copy.
- A method is marked visited **before** its body is walked, so recursion ends.
- Calls from one helper to another are followed transitively.

**D1a. The root walk `T`.**
- **What it walks:** the program the assembly builds, minus the library and helper `let`s:
  1. the `@Param` decoders in declaration order;
  2. the static initialisers in textual order;
  3. the handlers in purpose tag order, all into one rank table. For `compileMethod`, the call of the target.
- **How:** pre-order, left to right, over `PirHelpers.mapChildren`'s child order, iteratively (ADR-074).
- **Methods:** at the first `Var` that resolves to an unvisited helper or library method, the method takes the next
  rank and its body is walked there.

**D1a′. Unread helpers.**
- After `T`, the helper declarations `T` did not visit are walked **once each, in declaration order**, as `T` walks a
  method. Their unranked library callees are ranked there.
- Under auto-dispatch this happens once for the artifact, not once per handler.
- I1's rewrites do not reorder helpers, so declaration order is consistent with I1, and a helper rename does not change
  it.

**D1b. Unread library methods.** M1a binds every offered method, as today, so what D1a and D1a′ leave is ranked too.
- **The graph:** the library call graph without self-edges.
- **The step:** repeatedly, among the unranked library methods that no unranked library method calls, take the one with
  the smallest content key, rank it, and walk it as `T` does.
- **Name ties:** if several share the smallest key, the qualified name picks the first. That is the only place a name
  decides (Lemma 3).
- **Rejected inputs** (F11, F16):
  - If every unranked method is called by another unranked one, they contain a mutual-recursion cycle, which pre21
    rejects ("Unbound variable").
  - D1b then takes the smallest unranked method by `(content key, qualified name)` and continues. So it terminates and
    leaves the rejection, at UPLC generation, unchanged.
  - **This relies on mutual library recursion staying rejected.** If a later change accepts it, the fallback becomes a
    name dependence, and that change must define an order for library SCCs. D5 keeps a fixture that asserts the
    rejection.
- **Data structure and bound** (C18, C25):
  - each method keeps a count of its unranked library callers;
  - the methods whose count is 0 are kept in a priority queue by `(content key, qualified name)`;
  - for the cycle fallback, all methods are kept in one list presorted by the same key, read with a monotone cursor
    that skips ranked entries.
  - Total: O((n + e) log n) for n library methods and e call edges, cycle fallbacks included, plus one walk of each
    body. JOUST's largest program offers 283 library methods (Safes).

**Lemmas:**
1. **First use is total, and name-independent up to D1b's name ties.**
   - Every binding identity gets exactly one rank, so `(content key, rank)` is a strict total order, and Kahn's output
     is unique.
   - The walks read only term shape, lexical resolution and helper declaration order. A capture-free rename that keeps
     registered binders therefore leaves every rank unchanged, **except** where a D1b name tie flips (C19).
   - A flipped tie can reverse the two methods' order even when the bytes agree (Lemma 3b).
2. **The order of what D1a and D1a′ reach does not depend on what they do not reach.**
   - A reached method calls only reached methods, so binding an unreached one never makes a reached one ready.
   - So the subsequence of reached methods is Kahn run on the reached subgraph alone.
   - Reached ranks are below all others, so a reached method precedes a twin that is ready at the same time.
3. **D1b name ties** (r4: split per C19).
   - **3a (assembly).** Take a tie between `u1` and `u2`, which nothing reads: no unranked or ranked library method,
     no helper after D1a′, and not the root. If their values are α-equal, Kahn binds them adjacently, and
     `let u1 = V in let u2 = V′ in R` is α-equal to the program with the two swapped (V and V′ α-equal; `R` reads
     neither). This is a statement about the **assembled PIR**.
   - **3b (final bytes).** If the two values are **identical as written**, the two assembled programs differ only by
     swapping two binder names that nothing reads. Every pass after assembly sees the same term, up to that swap. In
     the passes read for r4 (`LiteralFoldPass`, `DelayedMethodPass`, `HandlerBindingSharing`), names are map keys and
     are compared only for equality, and the method-binder test treats `u1` and `u2` alike. So a pass that is
     equivariant under renaming unread binders gives equal final bytes.
     - This is an argument from reading three passes, not a proof over all of them. D5's identical-as-written fixtures
       test it in every mode, and a pass that orders by name text would break it (Risks).
     - If the values are only α-equal, passes that count binders by spelling can treat them differently (L-C19).
     - C19's fixture: `alpha` binds `x` and `beta` binds `y`. Renaming `alpha` to `zeta` gives different FLAT with
       source maps (`471fb25d…` against `18ed8feb…`, 45 B), and identical FLAT with the O10 fold disabled or at
       `NONE` (`r3-probes.txt`).
     - Such ties are therefore outside I1's byte guarantee.
4. **Twins need not be identical or adjacent.** Twins can call different twins. A dependent of the first twin with a
   smaller key is bound between the twins.

### D2. The comparator

- **The change:** `LibraryBindingOrder` orders ready methods by `(content key, first-use rank)` instead of
  `(content key, qualified name)`.
- **Programs without twins** are byte-identical to pre21's: the comparator never compares two equal keys there.
- **Programs with twins:** the twins' order changes, and Kahn's interleaving can move methods with other keys too.
- **No merging** (I3). **No pruning.**

### D3. Shape-check ids by first use (moved to a later ADR)

See Future work F-SC.

### D4. Helper methods in canonical order (moved to a later ADR)

See Future work F-H.

### D5. Tests for M1a

**`LibraryTieOrderPropertyTest`** (julc-compiler):
- **The corpus:** programs with no shared shape check, no recursive type group spanning units, and no literal fold over
  a local whose spelling is shared (L-C19):
  - the ADR-075 `ImportOrderInvarianceTest` programs;
  - generated twin groups: α-equal twins; twins identical as written; twins calling distinct twins; reached and
    unreached twins; self-recursive twins; an overload family;
  - JOUST fixtures: the generated Java of Crowdfund and Tips, as committed and after `renameAll`.
- **The rewrites:** capture-free, resolution-preserving renames of library methods (whole overload families) and
  classes; moves across packages, including among `org.julclang.stdlib`; permutations of library sources and imports.
  A pair where one side does not compile is reported and skipped (C20).
- **Seeds:** base seed 78, N = 40 per program in the build; a system property raises N for a soak run. A failure prints
  the seed and the rewrite.
- **The assertions:**
  - identical FLAT bytes at `NONE`, `BASELINE`, `PV11_SAFE`, `PV11_COSTED` and the default profile, with source maps
    on and off, within each mode;
  - with source maps, the same set of mapped DFS indices.

**Fixed fixtures:**
- **JOUST:** Crowdfund and Tips, base and renamed.
- **The two-identical-bodies probe** (`m1a-case1-probe.txt`).
- **C11:** an unread helper reads one twin.
- **C12** (`C12.java.txt`):
  - pin pre21's two outcomes: `alpha` 1,010 B and Success under the limit; `zeta` 1,012 B and `BudgetExhausted`;
  - assert that under M1a both spellings give one FLAT program, which is expected to be the `alpha` one.
- **C19:**
  - α-equal twins with different local spellings are recorded as the documented exception to I1 (expected to differ
    with source maps);
  - the same twins written identically assert equal bytes in every mode (Lemma 3b);
  - C19c (renaming a local in a reached method) is an expected-to-differ known-limit test (L-C19).
- **C20:** pool `[B, A]` compiles and `[A, B]` is rejected. Recorded as L-C4, and I1 skips the pair.
- **C22:** `compileMethod(root, "f", List.of(root))`, with the `JavacLibrariesTest.java:465` fixture. Both the helper and
  its library copy are ranked and counted, and the handler identity is observed at assembly.
- **Multi-purpose:** a validator whose handlers first use opposite twins.
- **Static initialiser:** a library call reachable only through one.
- **`compileMethod`:** a target whose helpers reach twins.
- **Recursion:**
  - an unread mutually recursive library pair: the rejection is preserved and D1b terminates (F16);
  - a self-recursive library method;
  - mutually recursive validator helpers.
- **Residual:** a planted D1b tie that is not α-equal; documented exception.
- **Negative controls:**
  - a constant changed in one twin changes the bytes;
  - a body changed in an unread binding that survives at `NONE` changes the `NONE` bytes.
- **Known limits, as expected-to-differ tests:** C1, C13, C14, C19c.

**Debug oracles (I3, I4):** on the same source, pre21 and M1a give the same Java breakpoint hits, source-step
locations, visible locals and `(handler, binding identity)` callback multiset. For renamed sources, the same holds
through the rewrite mapping.

**Semantic oracle (I6):**
- ADR-075's replay always passes a budget limit. M1a's comparison therefore splits the cases:
  - **I6a:** the cases that terminate within the limit on both sides compare outcome, result and traces;
  - cases that exhaust the budget on either side are reported separately;
  - **I6b:** budgets are reported per case.

**Unit tests (`LibraryBindingOrderTest`):**
- Lemmas 1–3 on 500 random DAGs with planted twin groups, self-loops and unread helpers.
- An instrumented comparator asserts that the qualified name is reached only in D1b's name ties and in the cycle
  fallback.

## Alternatives rejected

- **Merging identical bodies.** This breaks I3; the maintainer rejected it.
- **Tie-break by declaration order** (rc1) or **by qualified name** (pre21). The first moves the hash under method
  reordering; the second is JOUST's failure.
- **First use as the primary key.** This would change most programs' bytes.
- **Pruning in M1a.** It changes every `NONE` and source-map build, and conflicts with L-C3 and L-C5.
- **D1a only.** It leaves C11's rename-visible difference.
- **Excluding every D1b name tie from I1** (Codex's conservative option, applied in full).
  - On the census, every JOUST program and 31 of 43 julc-examples programs have one.
  - r4 excludes only ties whose values differ as written (Lemma 3b): 19/67 JOUST programs, and 0 julc-examples.
- **Fixing L-C19 in M1a** (counting binders by identity in the literal folds).
  - It changes a semantics-critical pass (ADR-045/046) program-wide.
  - It changes bytes wherever a spelling collision blocks a fold today.
  - It is not small. Deferred (OQ9).

## Affected stages and modules (M1a)

- `julc-compiler`:
  - `codegen/LibraryBindingOrder`: the comparator;
  - `codegen/ValidatorAssembly`: the rank table (D1a, D1a′, D1b) over the parts it already holds;
  - the new tests.
- Nothing else changes.

## Debugging and source maps (verified in code)

- **Library terms are unlocated.** They are built under `Terms.unlocated` (`Terms.java:312-325`;
  `JavacLibraries.java:135,179`), and never associated in `PirDebugProvenance` (`Terms.java:273-276`).
- **Reordering library `let`s moves no positioned node.**
  - Source maps key on node identity (`Terms.java:327`; `UplcGenerator.java:49-50`; `PirBackend.java:91-113`).
  - Debug-metadata slots are recomputed from the final term (`DebugMetadataCollector.java:259-266`).
- **Not affected:** stepping and call stacks (CEK depth and frames, `UplcToolsService.java:381-405,480-508`), and Java
  breakpoints (`sourceMap.lookup`, `UplcToolsService.java:201-209`). D5's debug oracles check this end to end.
- **UPLC-line breakpoints** in the playground (`julc-playground/frontend/src/lib/uplc/store.ts:42-43`) shift only for
  programs whose bytes change.
- **Traces:** none is built from names, and eager bindings are total.

## Compatibility (M1a)

- **Deployed scripts and compiled artifacts are unaffected.**
- **Recompiling with `0.1.0-pre22`** changes bytes, hash and address only for programs that bind content-key twins.
  Their budget can change, and an evaluation near a budget limit can change from success to exhaustion (I6b).
- **Simulated estimate.** This is not a measurement of M1a's bytes.
  - The census reads pre21's PIR after the PIR passes and simulates both comparators. It compares the orders as
    sequences of `key-prefix (8 hex)@rank` labels.
  - Equal label sequences do not prove that the same methods correspond: a byte-visible swap can keep them equal (C23).
  - It omits D1a′.

  | Corpus | Simulated order change, filtered to binder names present in the default-profile UPLC | Simulated full-order change (relevant to `NONE` and source-map builds) |
  |---|---|---|
  | JOUST examples, as committed | 5/67 | 17/67 |
  | julc-examples (43 validators) | 0 | 0 |

  M1a's exit replaces this with measured FLAT comparisons per artifact and mode. The release notes quote only those.
- **In-repo oracles** are re-run and explained in M1a's exit:
  - `GoldenUplcTest`;
  - the `*-pre-change-bytes.txt` goldens;
  - `Adr060ByteSnapshotTest` and `LibraryOrderEquivalence`;
  - the ADR-068 frozen reference;
  - the `O11BlsTypesTest` pins;
  - the Blaster fixtures (`verification/blaster/fixtures`).
- **Release notes** (draft; the counts come from M1a's measured exit):

  > Library methods whose compiled bodies are the same up to names are now bound in the order the program first uses
  > them; they used to follow their qualified names, so renaming such a method or its class could change a script's
  > hash. Scripts already compiled or deployed are unaffected. Recompiling changes the bytes, hash and address only of
  > a script that binds such methods (measured: …). Without a budget limit it computes the same results, failures and
  > traces; its execution budget can differ, so a transaction close to its limit can succeed with one version and
  > exhaust its budget with the other. Some names and orders still reach the bytes — renamed local variables where a
  > literal fold applies, shared `fromPlutusData` shape checks, boundary types with the same simple name and fields,
  > recursive type groups, a single overload renamed apart from its family, library sources whose order decides
  > whether a program compiles, and the order of a validator's own helper methods; see ADR-078's coverage table.

## Risks (M1a)

- **The ranks miss a reference.** Lexical resolution uses the same names the dependencies use, and D1b ranks whatever
  D1a and D1a′ miss. Every binding identity is ranked, and the unit tests assert it.
- **The budget changes** (I6b, L-C12). This is mitigated by measuring in M1a's exit and by the release note.
- **A post-assembly pass treats unread binder names asymmetrically.** That would break Lemma 3b. The passes were
  reviewed for this, and D5's identical-as-written fixtures test it in every mode.
- **D1b's cycle fallback** depends on mutual library recursion staying rejected (F16). A fixture asserts the rejection.
- **A future eager binding that may fail** (ADR-075's risk). M1a adds a test that every library binding value is a
  lambda, a delay, or a term `mayFail` proves total.
- **Cost:** O((n + e) log n) plus one walk per body. M1a's exit measures the compile time.

## Implementation milestones

- **M0. Diagnosis.** Done.
- **M1a (`0.1.0-pre22`). D1 (D1a, D1a′, D1b), D2, D5.**
  - **Entry:** this revision accepted.
  - **Exit:**
    - the D5 tests are green;
    - the semantic oracle (I6a on terminating cases, exhausted cases separately, I6b reported) is run against
      `08b021ce`;
    - the in-repo oracles and Blaster fixtures are checked, and every byte change is explained;
    - measured FLAT comparisons (pre21 vs M1a, and base vs renamed) are recorded per artifact and mode for the JOUST
      corpus and julc-examples;
    - the debug oracles pass;
    - the compile time is measured;
    - `./gradlew build` is green.
- **M3. JOUST acceptance with a local build** (§ For JOUST).
  - **Entry:** M1a in `mavenLocal`.
  - **Exit:** the acceptance criterion is measured, and every failure is attributed to a named limit. JOUST pins and
    docs follow.
- **M1b (future).** Shape-check ids (F-SC), pruning (F-P), L-C1, L-C2, and L-C19 (or its own ADR, OQ9).
  - **Entry:** the later ADR is accepted and resolves L-C1, L-C2, L-C3, L-C6, L-C12, L-C19, OQ1–OQ3, OQ7 and OQ9.
- **M2 (future).** Helper order (F-H), overload families (C14), and registration-order compilability (L-C4).
  - **Entry:** the later ADR is accepted and resolves L-C4, L-C5, L-C7, C14 and OQ6.
  - **M1b and M2 ship together.**

## Verification (M1a)

- **I1:** D5's property test and fixtures, and `ImportOrderInvarianceTest` extended with twins and class renames.
- **I3:** the `(handler, binding identity)` callback multiset.
- **I4:** D5's debug oracles, and the existing suites:
  - core: `SourceMapTest`, `SourceMapSerializerTest`, `DeepSourceMapSerializerTest`, `DebugMetadataTest`;
  - compiler: `SourceMapCompilerTest`, `SourceMapEvalTest`, `DebugCompileResultTest`, `DeepDebugMetadataWalkTest`,
    `JavacContractAndDebugTest`, `ExecutionTraceTest`, `BuiltinTraceIntegrationTest`;
  - `julc-vm-java`: `SourceMapVmTest`, `SteppingEvaluationTest`;
  - `julc-vm-truffle`: `JulcDebuggerTest`, `TruffleDebuggerTest`;
  - `julc-tools`: `SourceDebugServiceTest`, `JavacDebugMetadataTest`, `Adr068MetadataCorpusTest`;
  - `julc-playground`: `SourceDebugControllerTest`;
  - `julc-examples`: `DebugVestingTest`.
- **I6a and I6b:** D5's semantic oracle and C12's fixture.
- **I7:** the permuted runs and the instrumented comparator.
- **Name guards stay green:** `BinderNameIndependenceTest`, `GeneratedBinderNameLintTest`, `julc.verifyBinderNamespace`.

## Known limits (not fixed by M1a)

- **L-SC. Shared shape-check ids** follow library class name, method declaration order and helper declaration order
  (`probe-results.txt`).
- **L-C1. Per-root boundary checkers share nodes by simple name and fields** (Codex C1). Reproduced: 276 B → 318 B
  (`c1-probe-results.txt`).
- **L-C2. Type registration order reaches the content key** (Codex C2, C13).
  - Recursive groups are registered in declaration order, and units in qualified-name order (`JavacProgram.java:94`).
  - So reordering type declarations, or renaming a library class in a cross-class recursive group, can change keys and
    order with no twins. C13 is reproduced in every mode (`r2-probes.txt`).
- **C14. Renaming one overload-family member splits a registered binder** (88 B → 96 B, `r2-probes.txt`).
- **L-C19. The literal folds count binders by spelling** (Codex C19; new in r4).
  - ADR-045's and ADR-046's folds treat a variable as a literal only if its name is bound exactly once **in the whole
    program** (`LiteralFoldPass.java:137-150`). So a local's spelling decides whether a fold applies.
  - Reproduced (`r3-probes.txt`):
    - **C19c:** renaming a local in a reached library method so that it collides with another method's local gives 33 B
      → 73 B at the default profile, source maps off.
    - Renaming a local so that it stops colliding with a parameter of the target gives 45 B → 29 B with source maps.
    - With the O10 rule disabled, or at `NONE`, all variants are equal.
  - This is a local-name dependence that has nothing to do with binding order, and JOUST's D12 renames locals.
    JOUST's corpus passed this edit at the default profile on pre21 (`joust-corpus-renameall.txt`). M3 measures it
    again.
  - The fix, counting binders by identity, is not small (OQ9).
- **L-H. The validator's helper methods follow declaration order** (`probe-results.txt`).
- **L-C3. Pruning interacts with the shape-check registry** (Codex C3).
- **L-C4. Library registration order can decide compilability** (Codex C4, C20).
  - Methods register in source order, with a retry loop (`LibraryRegistration.java:45-47`).
  - C20 is reproduced: `A.a1→B.b0`, `A.a2→B.b1`, `B.b1→A.a1`, target `A.a2 + B.b1`. Pool `[B, A]` compiles (45 B);
    pool `[A, B]` is rejected with "Undefined variable: B" (`r3-probes.txt`).
  - So ADR-075 I1 and this ADR's I1 hold only over pairs that both compile.
- **L-C5. Pruning would remove unread eager helpers' breakpoints** (Codex C5).
- **L-C6. Pruning and renumbering change budgets in proportion to data** (Codex C6).
- **L-C7. Swapping a mutually recursive helper pair changes size** (Bekić duplication; Codex C7).
- **L-C12. Order changes can change budget and budget-limited outcomes** (Codex C12; **reproduced exactly** in r4,
  `C12.java.txt`, `r3-probes.txt`).
  - `alpha` gives 1,010 B, FLAT `c8489246…`, 31,073,556 CPU and 101,566 mem, and succeeds under the limit.
  - `zeta` gives 1,012 B, FLAT `4ab65773…`, 31,201,556 CPU and 102,366 mem, and exhausts the budget under
    `ExBudget(31_150_000, 110_000)`.
  - My r3 reconstruction differed from Codex's program (`v0` and the return expression), which is why it did not
    reproduce.
- **L-SM. Source-map builds differ from release builds** (`PirBackend.java:111-123`).

## Future work (moved to a later ADR)

- **F-SC (r1 D3, I5).**
  - Renumber the shared checker's ids by first use, as a position-preserving rebuild with a consistent bijection `σ`.
  - Test it differentially against `exactShapeCheck`.
  - Consider ADR-041's integer `case` dispatch: not applied today, but the dense ids would allow it (F5, OQ7, L-C6).
- **F-H (r1 D4).** Helpers in `(content key, first use)` order over Tarjan's SCCs, answering L-C4, L-C5, L-C7 and C14.
- **F-P (r1 D2's pruning).** Bind only what is reachable, answering L-C3 and L-C5.
- **F-LF (new, L-C19).** Count binders by identity in the literal folds, with its own byte and semantics review.
- **F-T (r1 D5).** The broad property test, after L-C1, L-C2, L-C4, L-C19 and C14 are fixed.

## Open questions

- **OQ1, OQ2, OQ3, OQ6, OQ7.** Moved to a later ADR.
- **OQ4.** Answered: yes (L-C2, C13).
- **OQ5.** Answered for M1a: within each mode.
- **OQ8.** Answered in r3: ship D1b with D1a′.
- **OQ9 (new in r4). Fix L-C19 before or with M1b, in its own ADR?**
  - It affects JOUST's local renames directly, wherever a literal fold applies.
  - Lean: its own small ADR soon after pre22, with the ADR-045/046 differential harnesses. Not in M1a.

## Revision history

- **r1 (2026-11-18, `b7383306`).** Initial version.
- **r2 (2026-11-18, `79bf86df`).** Narrowed to M1a after review round 1.
- **r3 (2026-11-18, `2479a06c`).** After review round 2 on `79bf86df`: D1a′ (C11), I6 split (C12), C13 and C14 as known
  limits, the D1b recursion precondition (C15/F11), census labels (C16), D5 oracles (C17), the bound (C18), and F12–F14.
- **r4 (2026-11-18).** After review round 3 on `2479a06c` (Fable: approve, with F15 and F16; Codex: changes
  requested, C19 and C21 P1, C20 and C22–C24 P2, C25 P3).
  - **C19:** Lemma 3 split into 3a (assembly) and 3b (final bytes, identical as written). I1 now excludes D1b ties
    whose values differ as written: 19/67 JOUST programs (`AddressAdapter.payment` / `AddressLib.paymentCredential`)
    and 0 julc-examples. The fixtures are reproduced. New L-C19 covers local spelling in the literal folds (reproduced
    at the default profile, 33 → 73 B), with F-LF and OQ9. Lemma 1 is qualified.
  - **C21:** "For JOUST" is now an M3 acceptance criterion, with named transformations, artifacts and modes. The
    exceptions are carried into G1, the coverage table and the release notes.
  - **C20:** reproduced. I1 holds over pairs that both compile, and L-C4 and the coverage row are extended.
  - **C22:** binding identities, lexical resolution, one rank table per artifact, and the helper-and-library-copy
    fixture.
  - **C23:** census figures are labelled as label-sequence comparisons, which omit D1a′ and take keys after the PIR
    passes.
  - **C24:** I6a now says "at most once, at the pre-optimisation site, when entered". The release note says success can
    become exhaustion. D5 pins C12's bounded outcomes and asserts that both spellings converge. The semantic oracle
    splits terminating and exhausted cases.
  - **C25:** a presorted fallback sequence with a monotone cursor, and the bound covers cycle fallbacks.
  - **F15:** G3 now matches I6.
  - **F16:** the cycle fallback's reliance on rejection is stated as a risk, with a fixture.
  - **C12:** reproduced exactly; `C12.java.txt` is added as a D5 fixture, and r3's dispute is withdrawn.
