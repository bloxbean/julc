# [Compiler/PIR] `/` lowers to `DivideInteger` (floored) — diverges from Java's truncating semantics and is inconsistent with `%`

**Component:** `julc-compiler` — `com.bloxbean.cardano.julc.compiler.pir.PirGenerator`, `TypeMethodRegistry`
**Severity:** Medium (silent wrong results for negative operands; breaks the "Java intuition carries over" contract)
**Type:** Bug / Semantics

## Summary

JuLC lowers the Java `/` operator to the UPLC `DivideInteger` builtin and `%` to `RemainderInteger`. These two builtins implement **different** division conventions, and `/` in particular does not match Java:

| Operator | Lowered to | Convention | Matches Java? |
|---|---|---|---|
| `/` | `DivideInteger` | floored (rounds toward −∞) | **No** — Java truncates toward zero |
| `%` | `RemainderInteger` | truncated (sign follows dividend) | Yes |

Java `/` truncates toward zero (`-7 / 2 == -3`). UPLC `DivideInteger` floors (`-7 / 2 == -4`). The matching UPLC builtin for Java's `/` is `QuotientInteger`, not `DivideInteger`.

Because `%` is already correctly mapped to `RemainderInteger` (the truncating remainder), the current pairing is internally inconsistent: the fundamental identity **`(a / b) * b + (a % b) == a` does not hold** for mixed-sign operands.

## Affected code

`PirGenerator.java` (binary operators):

```java
case DIVIDE    -> builtinApp2(DefaultFun.DivideInteger, left, right);     // line 713 — floored
case REMAINDER -> builtinApp2(DefaultFun.RemainderInteger, left, right);  // line 714 — truncated
```

`TypeMethodRegistry.java` (BigInteger methods):

```java
reg.register("IntegerType", "divide",    ... DivideInteger ...);    // line ~152  (floored)
reg.register("IntegerType", "remainder", ... RemainderInteger ...); // line ~158  (truncated)
reg.register("IntegerType", "mod",       ... ModInteger ...);       // line ~163  (floored modulo)
```

There is **no** `quotient` method registered on `IntegerType`, so there is currently no way to express Java/`BigInteger`-style truncating division at all.

## Reproduction

```java
// On the JVM (and per java.math.BigInteger.divide / .remainder):
-7 / 2   == -3        // truncates toward zero
-7 % 2   == -1
(-7 / 2) * 2 + (-7 % 2) == -7   // identity holds

// Compiled by JuLC to UPLC and evaluated:
DivideInteger(-7, 2)    == -4   // floors toward -infinity
RemainderInteger(-7, 2) == -1
(-4) * 2 + (-1)         == -9   // != -7  -> identity broken
```

Any validator that does signed integer division — fee splits, proportional payouts, tick/price math with signed deltas, index arithmetic that can go negative — silently computes a different value on-chain than the same code does when unit-tested on the JVM.

## Why this matters

JuLC's core value proposition is that Java developers' intuition (and their ability to unit-test pure logic on the JVM with plain `javac`) carries over to on-chain code. Division is the most common operation where Plutus's defaults differ from mainstream languages, so this is exactly the kind of deviation that will bite users who test off-chain and deploy on-chain. `BigInteger.divide()` in the JDK truncates toward zero; mapping JuLC's `.divide()` to floored `DivideInteger` contradicts the very type it mirrors.

## Proposed fix

Map the Java truncating operators to the truncating builtins, and expose the floored pair explicitly:

1. `/` → `QuotientInteger` (was `DivideInteger`) in `PirGenerator` line 713.
2. `%` → `RemainderInteger` — **unchanged** (already correct).
3. `IntegerType.divide()` → `QuotientInteger` in `TypeMethodRegistry` (to match `BigInteger.divide()` semantics), and `IntegerType.remainder()` → `RemainderInteger` (unchanged).
4. Keep floored division available but **named for what it is**: expose `floorDiv` → `DivideInteger` and `floorMod` → `ModInteger` (mirrors `Math.floorDiv`/`Math.floorMod` in the JDK, so the names are already familiar and unambiguous).

After the fix, both conventions are reachable and each is paired with its matching remainder:
- truncating: `QuotientInteger` / `RemainderInteger`  (Java `/`, `%`, `BigInteger.divide`, `Math.floorDiv` ← no; `divide`/`remainder`)
- floored: `DivideInteger` / `ModInteger`  (`Math.floorDiv` / `Math.floorMod`)

## Suggested tests

- Golden/eval tests for all four sign combinations of `a / b` and `a % b` (`+/+`, `+/-`, `-/+`, `-/-`), asserting the UPLC result equals `BigInteger.divide`/`.remainder` for the same inputs.
- A property test (jqwik): for random non-zero `b`, assert `(a / b) * b + (a % b) == a` after compilation, evaluated on `julc-vm-java`. This identity is the cleanest invariant and currently fails for mixed signs.
- Cross-check `floorDiv`/`floorMod` against `Math.floorDiv`/`Math.floorMod`.

## Notes

- Division-by-zero behavior is unaffected (all four builtins error on a zero divisor); just don't constant-fold the zero-divisor case if folding is ever added.
- This is the issue referenced as "out of scope" in the DCE soundness issue.
