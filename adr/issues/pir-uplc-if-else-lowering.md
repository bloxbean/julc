# Understanding `if`/`else` Lowering in PIR and UPLC

- **Type:** Explanatory compiler note
- **Audience:** JuLC contributors and developers learning PIR/UPLC
- **Scope:** Java control flow → PIR → UPLC
- **Historical reference:** [PR #80](https://github.com/bloxbean/julc/pull/80) fixed the nested early-return lowering defect that motivated this guide.

## Purpose

Java, PIR, and UPLC express control flow differently:

- Java is statement-oriented and has control-transfer statements such as `return`.
- JuLC's PIR is expression-oriented: an `if` is an expression that produces a value.
- UPLC is a small, strict functional language. It has functions, application, constants,
  builtins, `delay`, and `force`, but no Java-style `return`, `break`, or mutable program counter.

Understanding those differences is essential when changing statement lowering. A Java branch
cannot merely *contain a value corresponding to a return*; the surrounding PIR structure must make
that value the result of the complete execution path.

This note introduces the relevant syntax and develops the lowering one step at a time.

## 1. The three levels

Consider a simple Java method:

```java
static boolean choose(boolean condition) {
    if (condition) {
        return true;
    } else {
        return false;
    }
}
```

Conceptually, JuLC translates it through three representations:

```text
Java statements
    ↓
PIR expressions with names and types
    ↓
UPLC functions, applications, constants, delays, and builtins
```

The important transition is from Java statements to a single expression representing the result
of each path.

## 2. PIR is expression-oriented

The PIR form of `choose` is approximately:

```text
lam condition : Bool
  if condition then
    true
  else
    false
```

Read the syntax as follows:

- `lam condition : Bool body` defines a function taking one Boolean argument.
- `if condition then A else B` evaluates only the selected branch and produces its value.
- `true` and `false` are the values returned by the function paths.

There is no separate PIR `return` instruction here. The Java returns disappear during lowering.
Their expressions become the final values of their respective branches.

## 3. What `let ... in ...` means

PIR uses `let` to bind a value and then evaluate another expression:

```text
let x = 10
in x + 5
```

Read it as:

1. Evaluate `10`.
2. Bind it to the name `x`.
3. Evaluate the expression after `in` using that binding.

The result is `15`.

In Java-like pseudocode:

```java
var x = 10;
return x + 5;
```

The expression after `in` is the body of the binding. It is also the result of the complete `let`
expression.

### An unused binding

This PIR is valid, although usually pointless:

```text
let ignored = expensiveCalculation
in true
```

It evaluates `expensiveCalculation`, binds its result to `ignored`, and then returns `true`.
The value of `expensiveCalculation` cannot affect the final result because the body does not use
`ignored`.

That distinction matters for control-flow lowering. A branch value may be discarded only when it
does not represent the final result of an early-returning execution path.

## 4. Ordinary `if` followed by more statements

Consider Java where neither branch returns:

```java
static boolean ordinary(boolean condition) {
    if (condition) {
        traceFirstPath();
    } else {
        traceSecondPath();
    }

    return true;
}
```

A sequencing-oriented PIR shape is:

```text
let ignoredIfResult =
  if condition then
    traceFirstPath()
  else
    traceSecondPath()
in
  true
```

This shape is reasonable because both Java branches fall through to `return true`. The branch
results are not method return values; the branches perform their work and execution continues.

## 5. Early returns require a different shape

Now consider:

```java
static boolean guard(boolean invalid) {
    if (invalid) {
        return false;
    }

    return true;
}
```

The correct PIR is:

```text
lam invalid : Bool
  if invalid then
    false
  else
    true
```

The trailing `return true` becomes the continuation of the path that falls through the `if`.
It must not be placed unconditionally after the `if`.

The following lowering is wrong:

```text
lam invalid : Bool
  let ignoredIfResult =
    if invalid then false else unit
  in
    true
```

When `invalid` is true, the conditional produces `false`, but that value is assigned to an unused
name. The expression after `in` still produces `true`.

Here `unit`, normally written `()`, is a placeholder value comparable to Java's `void`. It does not
mean “continue” or “return”; it is just an ordinary value.

## 6. Fall-through continuations

In compiler terminology, a continuation describes what should happen next.

For the previous method:

```java
if (invalid) {
    return false;
}
return true;
```

the continuation of the `if` is:

```text
true
```

Branch lowering then follows two rules:

1. If a path encounters a method `return`, that path produces the returned value and does not use
   the continuation.
2. If a path reaches the end of its block, it evaluates the continuation.

This produces:

```text
if invalid then
  false          -- returned: stop this path
else
  true           -- fell through: run the continuation
```

The continuation is not a runtime object in this example. It is a compiler technique for building
the correct expression tree.

## 7. A nested example

Consider a generic two-condition decision:

```java
static boolean decide(BigInteger x, BigInteger y) {
    if (x.compareTo(BigInteger.ZERO) > 0) {
        if (y.compareTo(BigInteger.ZERO) <= 0) {
            return false;
        }
    } else {
        return false;
    }

    return true;
}
```

For readability, define:

```text
xPositive    = x.compareTo(0) > 0
yNonPositive = y.compareTo(0) <= 0
```

The intended paths are:

```text
xPositive = false                         → false
xPositive = true, yNonPositive = true    → false
xPositive = true, yNonPositive = false   → true
```

### Correct PIR

The correct expression tree is:

```text
lam x : Integer
  lam y : Integer
    if xPositive then
      if yNonPositive then
        false
      else
        true
    else
      false
```

The final Java `return true` has been inserted only on the path that can reach it.

### Incorrect discarded-result shape

This structure does not preserve Java return semantics:

```text
lam x : Integer
  lam y : Integer
    let ignoredIfResult =
      if xPositive then
        if yNonPositive then false else unit
      else
        false
    in
      true
```

All paths eventually evaluate the body after `in`, so the final result is `true` even when the
conditional calculated `false`.

## 8. PIR `let` becomes UPLC function application

UPLC has no native `let` syntax. A PIR binding:

```text
let x = value
in body
```

is represented using a function application:

```text
[(lam x body) value]
```

UPLC notation used by JuLC:

- `(lam x body)` — a function accepting `x`.
- `[function argument]` — apply a function to one argument.
- `[[function a] b]` — apply a two-argument function one argument at a time.
- `(con bool True)` — a Boolean constant.
- `(builtin lessThanInteger)` — a Plutus builtin.

For example:

```text
let ignoredIfResult = conditionalValue
in true
```

becomes:

```text
[(lam ignoredIfResult (con bool True)) conditionalValue]
```

The function body never references `ignoredIfResult`. Therefore, after the argument has been
evaluated, this application always produces `True`.

## 9. UPLC is strict: why `delay` and `force` appear

UPLC uses strict, call-by-value evaluation. Function arguments are normally evaluated before the
function runs.

For an `if`, evaluating both branches would be incorrect. Only the selected branch should run;
the other branch may contain an error, a trace, or expensive work.

JuLC therefore passes delayed branches to the `ifThenElse` builtin:

```text
force
  [[[(force (builtin ifThenElse)) condition]
    (delay thenBranch)]
   (delay elseBranch)]
```

Read it in stages:

1. `(builtin ifThenElse)` identifies the builtin.
2. The first `force` instantiates the builtin's polymorphic result type.
3. `condition` is evaluated normally.
4. `thenBranch` and `elseBranch` are wrapped in `delay`, so neither runs immediately.
5. `ifThenElse` selects one delayed branch.
6. The outer `force` evaluates only the selected branch.

For beginners, it is sufficient to read the complete structure as:

```text
if condition then thenBranch else elseBranch
```

while remembering that `delay` and `force` preserve the normal lazy behavior of conditional
branches inside an otherwise strict language.

## 10. Correct nested UPLC shape

Using readable condition names, the nested decision becomes:

```text
lam x
  lam y
    force
      [[[(force (builtin ifThenElse)) xPositive]
        (delay
          (force
            [[[(force (builtin ifThenElse)) yNonPositive]
              (delay (con bool False))]
             (delay (con bool True))]))]
       (delay (con bool False))]
```

The inner structure means:

```text
if yNonPositive then false else true
```

The outer structure means:

```text
if xPositive then <inner result> else false
```

No unused lambda receives and discards the conditional result.

## 11. Names in PIR versus indices in UPLC

PIR is designed to be readable and retains variable names:

```text
lam x
  lam y
    addInteger x y
```

JuLC's printed UPLC commonly uses De Bruijn indices such as `i1` and `i2`:

```text
lam x
  lam y
    addInteger i2 i1
```

The indices count outward through enclosing binders:

- `i1` refers to the nearest binder, which is `y`.
- `i2` refers to the next binder, which is `x`.

The names printed beside `lam` are helpful labels, but variable references are encoded by position.
This avoids name-capture problems and makes alpha-equivalent programs identical.

## 12. Method argument wrappers

When a standalone method is compiled for evaluation, JuLC may generate a wrapper around the typed
method:

```text
let decide =
  lam x : Integer
    lam y : Integer
      <decision body>
in
  lam xRaw : Data
    let xDecoded = unIData(xRaw)
    in
      lam yRaw : Data
        let yDecoded = unIData(yRaw)
        in
          decide xDecoded yDecoded
```

The wrapper exists because Plutus entry boundaries use `Data`. It:

1. accepts raw `Data` arguments;
2. decodes them to integers;
3. calls the typed method.

This wrapper is separate from `if`/`else` lowering, but it explains why complete PIR and UPLC dumps
contain additional lambdas and `unIData` builtins around the method body.

## 13. `BigInteger.compareTo` makes dumps look larger

The Java expression:

```java
x.compareTo(BigInteger.ZERO)
```

produces `-1`, `0`, or `1`. JuLC may therefore lower it approximately as:

```text
if x < 0 then
  -1
else if x == 0 then
  0
else
  1
```

A source condition such as:

```java
x.compareTo(BigInteger.ZERO) > 0
```

then compares that result with zero. This is why a literal PIR or UPLC dump can contain several
nested integer comparisons before reaching the control-flow structure being studied.

When reviewing lowering, it is often useful to temporarily name such subexpressions (`xPositive`,
`yNonPositive`) and first verify the surrounding branch structure.

## 14. Compiler invariants for conditional lowering

The following rules are useful when reviewing or extending JuLC's statement compiler:

1. Every PIR and UPLC function path must ultimately produce a value.
2. A Java method `return` must become the final value of that path, not merely the value of an
   inner expression.
3. Statements after an `if` belong only to branches that can fall through.
4. A branch that returns must not evaluate the fall-through continuation.
5. Branch-local variables must remain scoped to their branch.
6. Returns inside nested lambdas belong to those lambdas, not the enclosing method.
7. UPLC conditional branches must remain delayed so only the selected branch evaluates.
8. Discarding an `if` result is appropriate only when no branch result represents method-level
   control transfer.

## 15. Compact mental model

When reading Java:

```java
if (condition) {
    return A;
}
return B;
```

do not imagine PIR as executing a `return` instruction. Instead, rewrite the method mentally as
one expression:

```text
if condition then A else B
```

For a nested example:

```java
if (first) {
    if (second) {
        return A;
    }
} else {
    return B;
}
return C;
```

the expression is:

```text
if first then
  if second then A else C
else
  B
```

That expression-tree view is the central idea behind correct Java-to-PIR and PIR-to-UPLC control
flow lowering.

## Glossary

| Term | Meaning |
|---|---|
| PIR | JuLC's typed, named intermediate representation before UPLC generation |
| UPLC | Untyped Plutus Core, the language executed by the Plutus VM |
| Expression-oriented | Constructs such as `if` produce values rather than merely directing statements |
| `lam` | Lambda: a function definition |
| Application | Calling a function with an argument, printed as `[function argument]` |
| `let ... in ...` | Bind a value and evaluate a body under that binding |
| Continuation | The computation to perform when the current block falls through |
| `unit` | The single `()` placeholder value, similar to a void result |
| `delay` | Package an expression without evaluating it yet |
| `force` | Evaluate a delayed expression, or instantiate a polymorphic builtin |
| De Bruijn index | A variable reference encoded by binder distance, such as `i1` or `i2` |

## Relevant JuLC implementation

- `julc-compiler/src/main/java/com/bloxbean/cardano/julc/compiler/pir/PirGenerator.java`
  lowers Java statements and expressions into PIR.
- `julc-compiler/src/main/java/com/bloxbean/cardano/julc/compiler/pir/PirTerm.java`
  defines PIR terms such as `Lam`, `Let`, and `IfThenElse`.
- `julc-compiler/src/main/java/com/bloxbean/cardano/julc/compiler/uplc/UplcGenerator.java`
  translates PIR into UPLC.
- `julc-core/src/main/java/com/bloxbean/cardano/julc/core/text/UplcPrinter.java`
  prints UPLC programs in textual form.
