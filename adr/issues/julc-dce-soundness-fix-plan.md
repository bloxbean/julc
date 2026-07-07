# Fix plan: UplcOptimizer soundness under strict (CBV) UPLC evaluation

**Companion to:** `julc-dce-soundness-issue.md`
**Component:** `julc-compiler` — `com.bloxbean.cardano.julc.compiler.uplc.UplcOptimizer`
**Status:** IMPLEMENTED (Phase 1 + Phase 2 item 1; items 2/3 resolved by design — see §5)
(analysis verified with live repros on 2026-07-07, jars `0.1.0-pre15-49ba691`)

## 1. Verified findings

All findings below were reproduced by evaluating original vs. optimized terms on `julc-vm-java`.
The scope is **wider than the original issue**: the DCE hole is one instance of a shared root
cause, and three sibling passes have the same class of bug.

### 1.1 The bug is live and reachable from Java source today (not just latent)

The issue speculated that `PirGenerator` "may never emit a discarded binding whose evaluation
enforces a check". That is not the case. `PirTerm.Let` lowers to `Apply(Lam(x, body), rhs)`
(`UplcGenerator.java:124`), so **any unused local variable** produces the DCE-targeted shape:

```java
public static boolean check(BigInteger a) {
    BigInteger assertNonZero = BigInteger.ONE.divide(a);  // guard by evaluation
    return true;
}
```

Compiled via `JulcCompiler.compileMethod` (standard pipeline, optimizer on), the emitted term is
`Lam[a, Const(true)]` — the division is **gone**. Evaluated with `a = 0`: returns `True` instead
of aborting. Verified end-to-end. The same applies to unused `map.get(key)` /
`list.head()` existence-asserts and to every switch-case field binding that a branch body does
not use (`UplcGenerator.buildBranchFieldExtraction` emits extraction Lets for *all* declared
fields; DCE currently deletes the unused ones, removing the shape check on attacker-supplied
data — fail-open).

The optimizer runs unconditionally in both `compile()` (`JulcCompiler.java:494`) and
`compileMethod()` (`JulcCompiler.java:800`); it is skipped only when source maps are enabled.

### 1.2 Confirmed behavior flips (original → optimized), one per pass

| # | Pass | Term | Before | After |
|---|------|------|--------|-------|
| 1 | 3 DCE | `(\_ -> True) error` | ERROR | `True` |
| 2 | 3 DCE | `(\_ -> True) (headList [])` | ERROR | `True` |
| 3 | 4 Beta | `(\x -> delay x) (force (con 1))` | ERROR | value |
| 4 | 4 Beta | `(\x -> delay x) (force (builtin addInteger))` | ERROR | value |
| 5 | 5 Eta | `\x -> (force (con 1)) x` | value (Lam) | ERROR |
| 6 | 2 ConstFold | `[(force (builtin addInteger)) 3 4]` | ERROR | `Const 7` |

Root cause common to all four passes: the optimizer has no correct notion of a **value** under
call-by-value semantics with `Force`-based type instantiation. Specifically:

- **Pass 3 (DCE)** gates on `!hasSideEffect(arg)`, a `Trace`-only blacklist. `Error`, partial
  builtins, and arbitrary applications fall through `default -> false` and get discarded.
- **Pass 4 (Beta)** `isSimple` recurses through `Force` unboundedly, so `Force(Const)`,
  `Force(Var)`, and over-forced builtins are treated as substitutable values. Substituting one
  under a `Delay`/`Lam` skips or reorders its (eager, possibly erroring) evaluation. The
  original issue's note that "Pass 4 is already safe" is **incorrect**.
- **Pass 5 (Eta)** `isValue` has the same unbounded `Force` recursion, and additionally admits
  targets (`Var`, `Delay`, `Constr`, under-instantiated builtins) for which `\x -> f x → f` is
  not observationally equivalent (they behave differently from a `Lam` under `Force`/`Case`
  elimination — e.g. `force (\x -> (force v) x)` errors while `force (delay t)` runs `t`).
- **Pass 2 (ConstFold)** `stripForces` ignores force count entirely, folding over-forced
  builtin applications that must error at runtime.

### 1.3 Minor (no success/failure flip): Pass 6 evaluation reorder

`Case(Constr(tag, fields), bs) → Apply(bs[tag], fields...)` moves branch evaluation *before*
field evaluation (original CEK order: fields, then branch). Both sides still agree on
success-vs-error, but `Trace` emission order / which-error-wins can differ. Cheap to harden
(§2.6); not a validator-security issue.

## 2. The fix (Phase 1 — one PR, soundness only)

Replace the blacklist with a whitelist of CBV values, exactly as Plutus IR (`Purity.hs`) and
Scalus (`TermAnalysis.isPure`) do, but **force-arity-aware** from day one — the conservative
reject-all-`Force` variant would regress real codegen, because `UplcGenerator` emits
`Force(Builtin headList)` etc. pervasively and Pass 4/5 legitimately traffic in those.

### 2.1 Builtin type-arity table (julc-core)

New small class `com.bloxbean.cardano.julc.core.BuiltinTypeArity` (or a `typeArity()` accessor
on `DefaultFun`): `static int of(DefaultFun)` returning the number of `Force` applications the
builtin requires (0 for most; 1 for `IfThenElse`, `ChooseUnit`, `Trace`, `MkCons`, `HeadList`,
`TailList`, `NullList`, `ChooseData`, …; 2 for `FstPair`, `SndPair`, `ChooseList`).

- **Populate from** `julc-vm-java` `BuiltinTable.reg(fun, forces, arity, ...)` — the
  authoritative in-repo source (matches the Plutus spec and is exercised by the CEK tests).
- **Guard against drift** with a cross-check test in `julc-vm-java` (which sees both modules):
  for every registered `DefaultFun`, `BuiltinTable.getSignature(f).forceCount() ==
  BuiltinTypeArity.of(f)`. Unknown/unregistered builtins: `BuiltinTypeArity` should throw or
  return `-1` so callers treat them as non-values (fail-safe).
- `julc-compiler` already depends on `julc-core`; no new dependency edges.

### 2.2 Shared predicates in UplcOptimizer

```java
/** Force-chain over a Builtin: force(force(...(builtin f))). Returns count, or -1 if not that shape. */
static int forceChainOverBuiltin(Term t) { ... }

/**
 * A term is a CBV value: evaluating it cannot error, diverge, emit logs,
 * or consume meaningful budget, and re-evaluating it is free.
 *   Var, Const, Builtin, Lam, Delay          -> value
 *   Force^k(Builtin f), k <= typeArity(f)    -> value (partial/full type instantiation)
 *   Constr(tag, fields)                      -> value iff all fields are values
 *   everything else (Apply, Case, Error, other Force) -> not a value
 */
static boolean isValue(Term t) { ... }

/** Discarding without evaluation preserves behavior iff the term is a value. */
static boolean isPure(Term t) { return isValue(t); }
```

Notes:
- `Force^k(Builtin)` with `k <= typeArity` covers both partial (`k < arity`, still awaiting
  forces) and exact (`k == arity`, awaiting term args) instantiation — both are CEK values.
  `k > typeArity` errors at runtime → not a value.
- Saturated applications of *total* builtins (e.g. `addInteger 1 2`) are deliberately **not**
  pure in v1 (they cost budget and need a totality table) — Phase 2.
- Delete `hasSideEffect` entirely so it cannot be misused again. A value cannot contain a
  *saturated* `Trace` (that's an `Apply`), so the Trace concern is subsumed.

### 2.3 Per-pass gate changes

| Pass | Current gate | New gate |
|------|--------------|----------|
| 3 DCE `Apply(Lam(x,b), a) → b`, x∉fv(b) | `!hasSideEffect(a)` | `isPure(a)` |
| 4 Beta (single use) | `isSimple(a)`: Const/Var/Builtin/`Force(*)` | Const/Var/Builtin/`Force^k(Builtin), k ≤ typeArity` (i.e. `isValue` minus Lam/Delay/Constr, which stay excluded for size reasons) |
| 5 Eta `\x -> f x → f`, x∉fv(f) | `isValue(f)` (buggy) | `f` is `Lam` **or** `Force^k(Builtin)` with `k == typeArity` (bare `Builtin` with arity 0 included). Explicitly **exclude** `Var`, `Delay`, `Constr`, `Const`, under-instantiated builtins — they are not observationally `Lam`-like under `Force`/`Case`. |
| 2 ConstFold | `stripForces(fn)` then fold | Match bare `Term.Builtin` only (all currently-folded builtins have typeArity 0); delete `stripForces`. Keep the existing rule: never fold partial builtins (division etc.) — documented guard for future additions. |
| 6 Constr/Case | tag-bounds check only | additionally require `isValue(branches[tag])` (branches are `Lam`s in practice, so no real optimization loss; makes the branch-before-fields reorder unobservable) |

Substituting a value at a single use site (Pass 4) is sound regardless of where the use sits
(under `Lam`/`Delay`): evaluating a value is effect-free, and single-use means no work
duplication.

### 2.4 Expected impact on existing behavior

- All existing `UplcOptimizerTest` cases keep passing (checked by inspection):
  `removeUnusedLet` (Const arg — pure), `dcePreservesSemantics` (`5+3` constant-folds to a
  Const in the fixpoint loop *before* DCE sees it), `etaReduceSimple` (`AddInteger`, typeArity
  0), beta tests (Const/Var args).
- **Retained dead bindings** where the arg is not a value: unused locals with call/builtin
  initializers, and — the main real-world case — **unused switch-case field extractions**
  (§1.1). Scripts get slightly larger and costlier, and this is the *correct* direction:
  strict Java semantics evaluate those initializers, and for validators the change is
  fail-closed on malformed data.
- Script hashes of affected validators change. Needs a release-note entry.

### 2.5 Tests

1. **Flip regression tests** (unit, `UplcOptimizerTest`): the 6 confirmed repros from §1.2 plus
   eta negative cases (`f = Var`, `f = Delay`, `f = Constr`, `f = force(fstPair)` i.e.
   under-instantiated) — assert the term is *not* rewritten, and/or that optimized and original
   agree on `isSuccess` under `julc-vm-java`.
2. **Positive tests** (no over-conservatism): DCE still drops Const/Var/Lam/Delay/
   `force(builtin headList)` args; beta still inlines `force(builtin ifThenElse)`; eta still
   fires on `addInteger` and on `force(force(builtin fstPair))`.
3. **Java-source regression test** (compiler-level): the §1.1 unused-local validator must
   abort with `a = 0` after the fix, and the switch-with-unused-field case must retain its
   extraction (fail on malformed constr fields).
4. **Differential property test** (jqwik, in `julc-compiler` test scope, which already has a VM
   on the classpath): a closed-term generator (bounded depth; `Var` indices ≤ enclosing binder
   count; constants int/bool/bytes/unit; builtins from a representative set incl. partial ones;
   `Error`; `Lam/Apply/Force/Delay/Constr/Case`) and the property
   `eval(optimize(t)) ≡ eval(t)` on **(success/failure, result term if success)**, with a
   budget cap per evaluation. This mechanically guards this fix *and* the roadmap passes
   (CSE, force-hoisting, constant propagation). Optionally also cross-check against
   `julc-vm-scalus` where available.
5. **Arity-table drift test** in `julc-vm-java` (§2.1).

### 2.6 Validation & rollout

1. Full `julc` suite + `julc-examples` (`/julc-test-full`).
2. `BenchmarkJulcTask` before/after on the example validators (incl. WingRiders and CIP-113
   E2E) to quantify the size/budget regression from retained bindings. Expectation: small
   (a few `headList`/`unIData` per unused case field, per executed branch). If a validator
   regresses materially, that's Phase-2 motivation, not a reason to weaken the gate.
3. Release note: soundness fix; script hashes may change; dead-but-failing code is now
   evaluated (fail-closed).

## 2.7 As implemented (2026-07-07)

The fix landed as designed in §2.1–§2.3, plus three certification mechanisms found
necessary to keep real-world script sizes at parity (all whitelist-style, each with a
shape-level soundness argument in the code):

1. **`BuiltinSemantics` (julc-core)** carries type arity, value arity, totality, declared
   argument types, and a **result type** per builtin. The vm-java cross-check test
   verifies arities against `BuiltinTable` for all 102 builtins and **executes every
   claimed-total builtin** on typical + edge samples of its declared arg types. That
   execution check immediately caught a misclassification: `constrData` errors on tags
   outside int range, so it is total only at `CONSTR_TAG` (constant in [0, MAX_INT]).
   `MkCons` is declared total at the monomorphic `(DATA, LIST_DATA)` signature.
2. **Recursive result-type certification**: a saturated total builtin application is pure
   when each typed argument is a matching constant or a certified-pure application whose
   result type satisfies the expectation. This certifies constant Data literals
   (`constrData 0 (mkCons (iData 5) (mkNilData ()))` — e.g. `IntervalLib.always`).
3. **Lazy conditional**: `force ((force ifThenElse) cond (delay a) (delay b))` is pure
   when `cond` certifies BOOL and both branch bodies are pure (exactly one pure branch
   runs). This is how the frontend encodes boolean record fields inside Data literals.
4. **Fixpoint recognition**: `Z (\f -> <value>)` — the exact Z-combinator shape emitted
   by `UplcGenerator.generateLetRec`, matched alpha-structurally — evaluates in a fixed
   number of beta steps over lambda values to a closure; unused recursive bindings
   (stdlib helpers) are therefore discardable.

**Size impact** (script bytes, old → new, compiled with stdlib sources):
GuardedMinting 135 = 135, MultiSigTreasury 292 = 292, AuctionValidator 451 = 451,
MultiSigMinting 500 = 500, EscrowValidator 966 = 966, VestingValidator 582 → 567
(smaller: the value analysis legitimately optimizes more), OutputCheckValidator +5,
WhitelistTreasury +12, TokenDistribution +13, OneShotMintPolicy +15. The small growths
are retained script-context shape checks — required by strict Java semantics and
fail-closed for validators. Loop-heavy golden files changed (+5B..+79B) because unused
inner loops / accumulator extractions are now correctly retained.

**Tests added**: `UplcOptimizerSoundnessTest` (54: per-pass flip regressions + purity
predicate checks + anti-over-conservatism positives), `OptimizerSourceSoundnessTest`
(3: unused failing local aborts, unIData shape assert aborts, switch field extraction
fail-closed on malformed data), `UplcOptimizerPropertyTest` (jqwik differential:
eval(optimize(t)) ≡ eval(t) incl. traces, 1500 random closed terms per run),
`BuiltinSemanticsCrossCheckTest` (104: arity drift + totality execution).

## 3. Phase 2 — resolution

1. **Totality + value-arity table** — DONE (see §2.7): `BuiltinSemantics` records totality,
   value arity, and result types; under-saturated builtin applications are values; saturated
   total applications with certified argument types are pure. Saturated total applications
   over non-constant arguments (`addInteger a b` with unknown-typed vars) remain impure by
   design: argument types are unprovable at the untyped UPLC level, and discarding them
   would be exactly the class of unsoundness this fix removes.
2. **Frontend-level dead-binding avoidance for unused case fields** — RESOLVED: WON'T DO,
   by design. Java record pattern semantics (JLS 14.30.2) invoke component accessors during
   matching, so skipping unused field extractions would deviate from Java semantics AND be
   fail-open on malformed constr payloads. The extraction stays; measured cost is a few
   bytes per switch (see §2.7 size table).
3. **PIR-level DCE with type information** — NOT NEEDED for the original motivation (the
   §2.7 certifications recovered size parity). Remains a possible future optimization,
   tracked independently of this soundness issue.

## 4. Explicitly out of scope (tracked separately)

- `DivideInteger` vs `QuotientInteger` Java semantics — `julc-division-semantics-issue.md`
  (fixed in #44 per recent history; listed here only for cross-reference).
- Never fold integer division with constant zero divisor if division folding is ever added to
  Pass 2 (guard documented at the fold site).
