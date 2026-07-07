# [Compiler/Optimizer] Dead code elimination can drop erroring/diverging arguments — unsound under strict UPLC evaluation

**Component:** `julc-compiler` — `com.bloxbean.cardano.julc.compiler.uplc.UplcOptimizer`
**Severity:** High (latent miscompilation; can flip a should-fail validator to should-pass)
**Type:** Bug / Soundness

## Summary

The dead-code-elimination pass rewrites `Apply(Lam(x, body), arg) → body` whenever `x` is not free in `body` and `hasSideEffect(arg)` is false. However, `hasSideEffect` only models the `Trace` builtin. UPLC is strict (call-by-value), so discarding an unevaluated argument is only sound when the argument is a **value** — i.e., it cannot error, cannot diverge, and consumes no budget. The current blacklist-based gate allows DCE to delete:

- `Term.Error` (falls into the `default -> false` branch of `hasSideEffect`)
- Applications of partial builtins (`headList`, `tailList`, `unConstrData`, `divideInteger`, ...)
- Any fully-applied expression that may fail or burn budget

## Affected code

`UplcOptimizer.java`, Pass 3:

```java
// Apply(Lam(x, body), val) where x not free in body → body
// BUT: preserve side-effecting expressions (Trace)
if (optFn instanceof Term.Lam(var name, var body)) {
    if (!isFree(1, body) && !hasSideEffect(optArg)) {
        // x is not used and arg has no side effects — safe to drop
        yield shiftDown(body, 1);
    }
}
```

and the side-effect predicate:

```java
static boolean hasSideEffect(Term term) {
    return switch (term) {
        case Term.Builtin(var fun) -> fun == DefaultFun.Trace;
        // ... structural recursion ...
        default -> false; // Var, Const, Error   <-- Error treated as pure
    };
}
```

## Reproduction

Minimal term-level repro (no Java frontend involvement needed):

```java
// (\_ -> True) (error)
Term term = Term.apply(
        Term.lam("_", Term.const_(Constant.bool(true))),
        Term.error());

Term optimized = new UplcOptimizer().optimize(term);
// optimized == Const(True)
```

Under CEK semantics the original term **errors** (the argument is evaluated before the application); the optimized term **succeeds with True**. Same shape with a partial builtin:

```java
// (\_ -> True) (headList [])  — errors at runtime, optimized to True
```

## Why this matters

For a validator, the failure direction is the dangerous one: a check that was supposed to abort the script gets optimized away and the script succeeds. Today's `PirGenerator` may never emit a discarded binding whose evaluation enforces a check, but:

1. The optimizer is a generic UPLC-to-UPLC pass and must be sound on its own terms — it will be run on output of future codegen paths, future passes (constant propagation, CSE, force-hoisting per the optimizer roadmap), and potentially user/library-provided UPLC.
2. Pass interaction can manufacture the bad pattern. E.g., a future constant-propagation pass that replaces the single *use* of a bound variable can leave behind an unused binding of a failing expression, which this pass then deletes.
3. Let-style lowerings (`Apply(Lam(x, ...), rhs)`) are exactly the shape this pass targets, and statement expressions with discarded results (`a.divide(b);` for the zero-check) are a natural thing for a Java frontend to emit eventually.

## Proposed fix

Invert the gate: instead of blacklisting `Trace`, **whitelist work-free/error-free values** — same approach as Plutus IR's purity analysis. Discarding `arg` is sound iff `isPure(arg)`:

```java
/**
 * A term is pure (work-free and error-free) iff discarding it without
 * evaluation cannot change observable behavior: it is a value that
 * cannot error, diverge, emit logs, or consume meaningful budget.
 */
static boolean isPure(Term term) {
    return switch (term) {
        case Term.Var _, Term.Const _, Term.Builtin _ -> true;
        // Lam/Delay are values; their bodies are not evaluated here.
        case Term.Lam _, Term.Delay _ -> true;
        // Constr evaluates its fields eagerly under CBV.
        case Term.Constr(_, var fields) ->
                fields.stream().allMatch(UplcOptimizer::isPure);
        // Conservative: Apply, Case, Error, Force are not pure.
        // (Force(Builtin) for type instantiation could be admitted later
        // with builtin type-arity tracking; not worth it for v1.)
        default -> false;
    };
}
```

DCE site becomes:

```java
if (!isFree(1, body) && isPure(optArg)) {
    yield shiftDown(body, 1);
}
```

Notes:

- This subsumes the `Trace` check: a saturated `Trace` application is an `Apply`, hence not pure. `hasSideEffect` can be removed or kept for other diagnostics.
- `Force` is conservatively impure. `Force(Builtin)` (type instantiation) is value-like and could be whitelisted with arity tracking, but over-forcing a builtin errors at runtime, so admitting it blindly would reintroduce a (narrower) hole.
- Beta reduction (Pass 4) is already safe — it only substitutes when `isSimple(arg)` (Const/Var/Builtin), which are values.

## Prior art

Two reference implementations confirm the whitelist (`isPure`) approach is the standard fix:

- **Plutus IR** (`PlutusIR/Purity.hs`) — the canonical purity analysis; `isPure`/`isWorkFree` gate inlining and dead-binding removal on whether a term is work-free and error-free.
- **Scalus** (`scalus.uplc.transform.TermAnalysis.isPure`) — a sister JVM-targeting Cardano compiler with the same SIR→UPLC architecture as JuLC. It has no standalone DCE pass; dead-binding removal happens in its `Inliner`, gated by occurrence analysis **and** `isPure`: `if occurrences == 0 && inlinedArg.isPure then <drop>`. `Error` is explicitly impure, and any unrecognized `Apply` is impure by default. The same `isPure` is reused for eta reduction.

Scalus also adds a refinement JuLC could adopt as a follow-up: a **builtin arity + totality table**. It tracks each builtin's type arity and value arity and whether it is *total*, so it can prove more terms pure than the conservative version below:
- saturated `divideInteger 1 0` → impure (partial builtin, fully applied — may fail)
- saturated `addInteger 1 2` → pure (total builtin)
- *partial* application of any builtin → pure (it's a value)
- `Force(Builtin headList)` → pure (headList needs one type arg); `Force` of a non-delayed, non-builtin term → impure (over-forcing errors at runtime)

The minimal fix below is the conservative version (reject all `Apply`/`Force`); the arity-table version recovers the optimization opportunities that conservatism gives up and is worth a phase-2 issue.

## Suggested tests

1. Unit tests asserting the two repro terms above are **not** rewritten (or at minimum, that optimized and unoptimized terms agree on error behavior under the Java CEK).
2. Differential property test in `julc-testkit-jqwik`: generate random closed UPLC terms, evaluate optimized vs. unoptimized on `julc-vm-java` and the Scalus backend, and assert agreement on (result | error). This would have caught this mechanically and guards the upcoming constant-folding/force-hoisting/CSE passes.

## Out of scope (separate issues)

- `/` lowering to `DivideInteger` (floor) vs. Java's truncate-toward-zero semantics (`QuotientInteger`), and the resulting `(a/b)*b + a%b != a` inconsistency with `%` → `RemainderInteger`.
- If integer-division constant folding is ever added to Pass 2, the divisor-zero case must not be folded (the runtime error must be preserved).
