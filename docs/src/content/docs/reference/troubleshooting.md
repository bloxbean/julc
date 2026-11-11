---
title: "JuLC Compiler Troubleshooting Guide"
description: "JuLC Compiler Troubleshooting Guide - JuLC documentation"
---

This guide maps every compiler, validation, configuration, and runtime error to its
cause and solution. All error messages shown here are taken directly from the JuLC
source code.

---


## 1. Compilation Errors (lowering)

These errors are emitted during PIR (Plutus Intermediate Representation) generation,
when the compiler translates Java AST nodes into UPLC-compatible terms.

### `Conditional update to loop-body local '<name>' is not supported`

An `if`/`else` inside a loop updates a local declared in the loop body outside
that branch. The branch carries only loop accumulators out; the old lowering
silently lost this local's update. Even constant conditions and unused updates
are rejected.

```java
// Rejected inside a loop:
BigInteger step = BigInteger.ZERO;
if (positive) { step = BigInteger.ONE; }
acc = acc.add(step);

// Use a conditional initializer:
BigInteger step = positive ? BigInteger.ONE : BigInteger.ZERO;
acc = acc.add(step);
```

Alternatively, declare the variable before the loop as an accumulator (reset it
each iteration when needed), or declare it inside the branch if its value is
only used there. Existing break-aware and native-accumulator restrictions still
apply; see [the full rules](/best-practices/conditionals/#loop-local-assignment-rules).

### `Switch-expression arm cannot update enclosing variable '<name>'`

A switch exports only its yielded result, not assignments to enclosing variables.
A nested loop inside an arm does not change this rule. It applies to outer
accumulators as well as loop-body locals, with or without an enclosing `if`.

```java
// Correct: the accumulator belongs to the arm and its result is explicitly yielded.
BigInteger step = switch (action) {
    case Only o -> {
        BigInteger local = BigInteger.ZERO;
        for (var y : xs) { local = local.add(y); }
        yield local;
    }
};
```

Do not declare `local` outside the arm and expect the loop to update it there.
Loops inside an `if` at method level or in a switch arm now preserve their
accumulator updates after the branch (#161). If using an older compiler affected
by this bug, upgrade and recompile; script hashes and budgets may change.
This includes a switch expression inside an outer loop body: declare the
accumulator inside the arm, update it with the guarded loop, and yield its result.
Older versions may conservatively reject this composition as a conditional
loop-body local update. Updates to variables declared outside the arm, or to
actual loop-body locals across an `if`, remain rejected.

### `Reassignment of switch case-pattern variable '<name>' is not supported`

Updating a case-pattern binding could leave field projections pointing at its
original record (#162). Reassignment is now rejected, including when only the
record is yielded. Copy the binding to a fresh arm-local accumulator:

```java
case Only p -> {
    Only local = p;
    for (var y : xs) { local = new Only(y); }
    yield local.value();
}
```

For `[1, 2, 3]`, this yields `3`; assigning to `p` instead is a compile-time error.
Otherwise-supported `instanceof` binding reassignment is unaffected.

### 1.1 `JULC0001` `Method must have a body: <name>`

**Cause:** A method the program calls has no body, such as a `native` method. The compiler
lowers every method a validator calls, so each one needs a body.

**Fix:** Give the method a body. An `abstract` method that nothing calls is never lowered.

```java
import java.math.BigInteger;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;

// WRONG: a method with no body
@SpendingValidator
public class FeeValidator {
    static native BigInteger fee(BigInteger amount);

    @Entrypoint
    public static boolean validate(BigInteger redeemer, ScriptContext ctx) {
        return fee(redeemer).signum() > 0;
    }
}

// CORRECT: method with body
@SpendingValidator
public class FeeValidator {
    static BigInteger fee(BigInteger amount) {
        return amount.divide(BigInteger.valueOf(100));
    }

    @Entrypoint
    public static boolean validate(BigInteger redeemer, ScriptContext ctx) {
        return fee(redeemer).signum() > 0;
    }
}
```

---

### 1.2 `Variable must be initialized: <name>`

**Full message:** `Variable must be initialized: <name>. Hint: On-chain variables
need initial values, e.g. var <name> = BigInteger.ZERO;`

**Cause:** A local variable was declared without an initializer. On-chain code
requires all variables to be initialized at declaration because UPLC has no concept
of uninitialized memory.

**Fix:** Always assign an initial value.

```java
// WRONG
var total;

// CORRECT
var total = BigInteger.ZERO;
```

---

### 1.3 `break statement outside of a loop`

**Full message:** `break statement outside of a loop`
**Hint:** `break can only be used inside for-each or while loops.`

**Cause:** A `break` statement appeared outside of a for-each or while loop scope.
A `break` inside a loop that updates no variable declared before it reports
`JULC0063` instead ([1.25](#125-julc0063-break-is-not-supported-in-a-loop-that-updates-no-variable-declared-before-the-loop)).
A `break` that names a label reports `JULC0066` ([1.29](#129-julc0066-labels-are-not-supported-on-chain-construct)).

**Fix:** Move the `break` inside a loop body, or restructure your logic to use
`return` instead.

```java
// WRONG
if (someCondition) {
    break; // not inside a loop
}

// CORRECT
for (var item : items) {
    if (someCondition) {
        break; // inside a for-each loop
    }
}
```

---

### 1.4 `Unsupported statement: <StatementType>`

**Full message:** `Unsupported statement: <StatementType>`
**Hint:** `Only variable declarations, if/else, for-each, while, return, and
expression statements are supported on-chain.`

**Cause:** A Java statement type that has no on-chain equivalent was used.
Unsupported statements include `switch` statements (as opposed to `switch`
expressions), `try`/`catch`, `do`/`while`, labeled statements, and others.
`continue` reports `JULC0060` ([1.28](#128-julc0060-continue-is-not-supported-on-chain)),
and a labelled statement `JULC0066` ([1.29](#129-julc0066-labels-are-not-supported-on-chain-construct)).

**Fix:** Rewrite using the supported statement forms: variable declarations, if/else,
for-each, while, return, and expression statements.

```java
// WRONG: switch statement
switch (value) {
    case 1: doSomething(); break;
}

// CORRECT: switch expression (for sealed interface pattern matching)
var result = switch (action) {
    case Bid b -> b.amount();
    case Cancel c -> BigInteger.ZERO;
};
```

---

### 1.5 `JULC0064` `A nested while loop is not supported in a loop that contains break and updates one variable declared before the loop`

**Cause:** A loop that contains `break` and updates exactly one variable declared
before it may contain only variable declarations, assignments, if/else and break.
A nested `while` or for-each loop in its body is rejected with this code; the
message says `A nested while loop` or `A nested for-each loop`. Another unsupported
statement keeps its own message, `Unsupported statement in break-aware loop body:
<StatementType>`. A loop with `break` that updates two or more variables declared
before it supports nested loops.

**Fix:** Move the nested loop into a helper method that returns the value it
computes, and call the helper from the loop body.

```java
// WRONG: nested while inside a loop with break
BigInteger v = BigInteger.ZERO;
for (var x : xs) {
    if (x.signum() < 0) { break; }
    long i = 0;
    while (i < 3) { v = v.add(x); i = i + 1; }
}

// CORRECT: the nested loop is a helper
static BigInteger addTimes(BigInteger v, BigInteger x) {
    BigInteger r = v;
    long i = 0;
    while (i < 3) { r = r.add(x); i = i + 1; }
    return r;
}

BigInteger v = BigInteger.ZERO;
for (var x : xs) {
    if (x.signum() < 0) { break; }
    v = addTimes(v, x);
}
```

Before [#257](https://github.com/bloxbean/julc/issues/257) this was reported as
`Unsupported statement in break-aware loop body: WhileStmt`, without a code.

---

### 1.6 `Unsupported in multi-acc loop body: <StatementType>`

**Hint:** `Inside multi-accumulator loops, only variable declarations, assignments,
and if/else are supported.`

**Cause:** A for-each or while loop that updates multiple accumulators used an
unsupported statement. Multi-accumulator loops are translated by packing all
accumulators into a Data list tuple, which restricts the supported constructs.

**Fix:** Keep the loop body to variable declarations, accumulator assignments, and
if/else.

```java
// CORRECT: multi-accumulator loop
var count = BigInteger.ZERO;
var total = BigInteger.ZERO;
for (var item : items) {
    count = count + BigInteger.ONE;
    total = total + item.value();
}
```

---

### 1.7 `Unsupported in multi-acc break-aware body: <StatementType>`

**Hint:** `Inside multi-accumulator loops with break, only variable declarations,
assignments, if/else, and break are supported.`

**Cause:** A multi-accumulator loop that also contains `break` used an unsupported
statement. This is the most restrictive loop compilation mode.

**Fix:** Use only variable declarations, assignments, if/else, and break in the loop
body.

---

### 1.8 `Unsupported BigInteger field: <fieldName>`

**Full message:** `Unsupported BigInteger field: <fieldName>`
**Hint:** `Supported BigInteger fields: ZERO, ONE, TWO, TEN. Use new
BigInteger("value") for other constants.`

**Cause:** A `BigInteger` static field other than `ZERO`, `ONE`, `TWO`, or `TEN`
was referenced.

**Fix:** Use `new BigInteger("value")` for arbitrary constants.

```java
// WRONG
var x = BigInteger.valueOf(42);  // valueOf is fine for runtime, but...
var y = BigInteger.NEGATIVE_ONE; // this field is not supported

// CORRECT
var x = BigInteger.valueOf(42);  // valueOf IS supported
var y = new BigInteger("-1");    // use string constructor for other values
```

---

### 1.9 `new BigInteger() requires a string literal argument`

**Hint:** `Use new BigInteger("12345") or BigInteger.valueOf(n) for integer
constants.`

**Cause:** `new BigInteger(expr)` was called with a non-string-literal argument
(e.g., a variable).

**Fix:** Pass a string literal directly to the `BigInteger` constructor.

```java
// WRONG
var s = "100";
var amount = new BigInteger(s); // variable, not a string literal

// CORRECT
var amount = new BigInteger("100"); // string literal
var small = BigInteger.valueOf(42); // also works for int-range values
```

---

### 1.10 `Cannot construct non-record type: <typeName>`

**Hint:** `Only record types can be constructed on-chain. Define <typeName> as a
record.`

**Cause:** A `new ClassName(...)` expression was used with a type that is not a
Java `record` or a variant of a sealed interface.

**Fix:** Define your data types as records.

```java
// WRONG: regular class
public class Bid {
    public BigInteger amount;
    public Bid(BigInteger amount) { this.amount = amount; }
}

// CORRECT: record
public record Bid(BigInteger amount) {}
```

---

### 1.11 `switch expression requires a sealed interface type, got: <type>`

**Hint:** `Ensure the switch variable's type is a sealed interface with record
variants.`

**Cause:** A `switch` expression was used on a variable that is not typed as a
sealed interface with record variants.

**Fix:** Ensure the switched-on variable is a sealed interface with record
implementations.

```java
// Define the sum type
public sealed interface Action permits Bid, Cancel {}
public record Bid(BigInteger amount) implements Action {}
public record Cancel() implements Action {}

// CORRECT usage
var result = switch (action) {
    case Bid b -> b.amount();
    case Cancel c -> BigInteger.ZERO;
};
```

---

### 1.12 `Unknown variant in switch: <typeName>`

**Hint:** `Available variants: <list>`

**Cause:** A `case` label in a switch expression referenced a type name that is
not a known variant (constructor) of the sealed interface.

**Fix:** Check spelling and ensure the type is listed as a `permits` variant of
the sealed interface.

```java
// WRONG: "Offer" is not a variant of Action
var result = switch (action) {
    case Offer o -> o.price();   // Offer not in permits list
    case Cancel c -> BigInteger.ZERO;
};

// CORRECT
var result = switch (action) {
    case Bid b -> b.amount();    // Bid is a permitted variant
    case Cancel c -> BigInteger.ZERO;
};
```

---

### 1.13 `Unsupported instanceof pattern: <expression>`

**Cause:** `instanceof` was used with a type that is not a record variant of a sealed
interface (including the sealed interface itself, `x instanceof Action`), or with a record
pattern (`x instanceof R(var a, var b)`). A type test (`x instanceof Bid`) and a type pattern
(`x instanceof Bid b`) of a variant are supported.

**Fix:** Use `instanceof` only for sealed interface variant matching.

```java
// WRONG: instanceof with a non-variant type
if (obj instanceof String s) { ... }

// CORRECT: instanceof with sealed interface variant
if (action instanceof Bid b) {
    var amount = b.amount();
}
```

---

### 1.14 `Non-exhaustive switch on sealed interface: missing cases [X, Y]`

**Cause:** A `switch` expression on a sealed interface does not cover all variants. The compiler checks exhaustiveness before generating `DataMatch`.

**Fix:** Add explicit cases for all variants of the sealed interface, or use `default ->` as a catch-all for uncovered variants.

```java
sealed interface Action permits Bid, Cancel, Update {}
record Bid(BigInteger amount) implements Action {}
record Cancel() implements Action {}
record Update(BigInteger newPrice) implements Action {}

// WRONG: missing Update case
var result = switch (action) {
    case Bid b -> b.amount();
    case Cancel c -> BigInteger.ZERO;
};
// Error: Non-exhaustive switch on sealed interface: missing cases [Update]

// CORRECT: all cases covered
var result = switch (action) {
    case Bid b -> b.amount();
    case Cancel c -> BigInteger.ZERO;
    case Update u -> u.newPrice();
};
```

---

### 1.15 `Method does not return on all execution paths`

**Cause:** A method has code paths that do not end with a `return` statement. The compiler analyzes if/else completeness, fallthrough returns, and loop-as-return patterns.

**Fix:** Ensure every code path returns a value.

```java
// WRONG: missing return on else path
static boolean check(BigInteger x) {
    if (x.compareTo(BigInteger.ZERO) > 0) {
        return true;
    }
    // no return here
}

// CORRECT
static boolean check(BigInteger x) {
    if (x.compareTo(BigInteger.ZERO) > 0) {
        return true;
    } else {
        return false;
    }
}
```

---

### 1.16 `@NewType requires exactly one field`

**Cause:** A record annotated with `@NewType` has zero or more than one field.

**Fix:** `@NewType` records must have exactly one field of a supported primitive type (`byte[]`, `BigInteger`, `String`, `boolean`).

```java
import org.julclang.stdlib.annotation.NewType;
// WRONG: two fields
@NewType
record AssetId(byte[] policy, byte[] name) {}

// CORRECT: single field
@NewType
record PolicyHash(byte[] value) {}
```

---

### 1.17 `Unsupported lambda body: <BodyType>`

**Hint:** `Lambda bodies must be a single expression or a block statement.`

**Cause:** A lambda expression has an unexpected body form.

**Fix:** Use either a single expression or a block body.

```java
// CORRECT: expression body
var doubled = items.map(item -> item.multiply(BigInteger.TWO));

// CORRECT: block body
var filtered = items.filter(item -> {
    var threshold = new BigInteger("100");
    return item.compareTo(threshold) > 0;
});
```

---

### 1.18 `Unknown method: <methodName>`

**Hint (fuzzy match):** `Did you mean '<ClassName.methodName>()'?`
**Hint (no match):** `Library methods require class qualification (e.g.,
MathLib.<methodName>(...)).`

**Cause:** A method call could not be resolved. The method is not a local helper,
a stdlib method, a record accessor, or a known type method.

**Fix:** Qualify static library calls with their class name. Check spelling.

```java
// WRONG: unqualified stdlib call
var len = length(myList);

// CORRECT: qualified stdlib call
var len = ListsLib.length(myList);

// WRONG: typo
var total = MathLib.abss(value);

// CORRECT: compiler will suggest "Did you mean 'MathLib.abs()'?"
var total = MathLib.abs(value);
```

---

### 1.19 `Unsupported operator: <operator>`

**Hint:** `Supported operators: +, -, *, /, %, ==, !=, <, <=, >, >=, &&, ||`

**Cause:** A binary operator that has no UPLC equivalent was used (e.g., bitwise
operators `&`, `|`, `^`, `<<`, `>>`).

**Fix:** Use the supported operators. For bitwise operations, use `BitwiseLib`.

```java
// WRONG
var result = a & b;
var shifted = x << 2;

// CORRECT
var result = BitwiseLib.andByteString(false, a, b);
var shifted = BitwiseLib.shiftByteString(x, 2);
```

---

### 1.20 `Unsupported unary operator: <operator>`

**Hint:** `Supported unary operators: ! (logical NOT) and - (negation)`

**Cause:** A unary operator other than `!` or `-` was used (e.g., `~`, `++`, `--`).

**Fix:** Use `!` for boolean negation and `-` for arithmetic negation.

```java
// WRONG
var next = count++;
var flipped = ~bits;

// CORRECT
var negated = !condition;
var negative = -amount;
```

---

### 1.21 `JULC0061` `Reassigning '<name>' is not supported outside a loop`

**Cause:** On-chain variables are single-assignment. Only a loop can update a
variable declared before it (the loop's accumulator). Anywhere else, assigning to a
local variable, a parameter or a field is rejected, including in an `if`/`else`
branch and inside an expression. Before [#257](https://github.com/bloxbean/julc/issues/257)
this was reported without a code. An assignment inside an expression in a loop is
`JULC0114`.

**Fix:** Declare a new local for the new value, or choose the value in one
expression.

```java
// WRONG
long x = n;
x = x * n;

// CORRECT: a new local
long x = n;
long squared = x * n;

// WRONG
BigInteger limit = BigInteger.TEN;
if (vip) { limit = BigInteger.valueOf(100); }

// CORRECT: one expression
var limit = vip ? BigInteger.valueOf(100) : BigInteger.TEN;
```

---

### 1.22 `Unsupported expression: <ExpressionType>`

**Hint:** `This expression type is not supported in on-chain code.`

**Cause:** A Java expression type that has no on-chain compilation path was used.

**Fix:** Rewrite using the supported expression forms: literals, variables, binary
and unary expressions, method calls, field access, record construction, ternary
expressions, lambdas, switch expressions, instanceof, and casts.

---

### 1.23 `Undefined variable: <name>`

**Cause:** A variable name was used that is not in scope. This is reported by
lowering when javac's name has no value in the current scope.

**Fix:** Ensure the variable is declared before use, or check for typos.

```java
// WRONG: typo
var totl = BigInteger.ZERO;
return total; // "total" not defined, "totl" is

// CORRECT
var total = BigInteger.ZERO;
return total;
```

An `instanceof` pattern variable that JuLC does not bind is reported as `JULC0139`
([1.31](#131-julc0139-a-pattern-variable-is-used-where-julc-does-not-bind-it)), not as an
undefined variable.

`JulcMap.of(...)` used to be reported as `Undefined variable: JulcMap`; it now
reports `JULC0062` ([1.26](#126-julc0062-julcmapof-is-off-chain-only-and-has-no-on-chain-implementation)).

If the undefined name is an on-chain library class, such as `OutputLib`, look for a note earlier in the build output:
`JuLC skipped an on-chain library source on the processor path: ...`. It names a jar whose `META-INF/plutus-sources`
JuLC could not read, typically a jar rebuilt or deleted while a Gradle daemon kept it open. Rebuild that jar, or run
the build again; JuLC reads the jars from disk each time.

---

### 1.24 `JULC0003` `'return' is not supported inside while loop body`

**Cause:** A `while` loop body cannot `return` from the enclosing method. A `while`
loop is compiled to a recursive function whose result is the variables the loop
updates, so it has no way to leave the method early. The location is the first
such `return`.

**Fix:** Exit the loop with `break`, or keep the result in a variable declared
before the loop, and return after the loop.

```java
// WRONG
long i = 0;
while (i < n) {
    if (i > 3) { return i; }
    i = i + 1;
}
return n;

// CORRECT
long i = 0;
while (i < n) {
    if (i > 3) { break; }
    i = i + 1;
}
return i;
```

---

### 1.25 `JULC0063` `break is not supported in a loop that updates no variable declared before the loop`

**Cause:** A loop supports `break` only when it updates a variable declared before
the loop. A loop that updates none compiles its body as plain statements, which
cannot stop the loop. Before [#257](https://github.com/bloxbean/julc/issues/257) this was
reported as `break statement outside of a loop`, although the `break` is inside one.

The same applies to such a loop nested inside a loop that does update a variable.
That form used to be accepted, with its `break` ignored, so the inner loop ran to its
end ([#273](https://github.com/bloxbean/julc/issues/273)); it is now rejected with this
code too. A loop whose only update is inside a `switch` statement or an expression
reports `JULC0065` at the update instead ([1.27](#127-julc0065-updating-name-inside-a-switch-statement-is-not-supported-in-this-loop)).

**Fix:** Declare a variable before the loop and set it where the loop breaks.

```java
// WRONG
for (var x : xs) {
    if (x.equals(target)) { break; }
    if (x.signum() < 0) { Builtins.error(); }
}

// CORRECT
boolean stopped = false;
for (var x : xs) {
    if (x.equals(target)) { stopped = true; break; }
    if (x.signum() < 0) { Builtins.error(); }
}
```

---

### 1.26 `JULC0062` `JulcMap.of is off-chain only and has no on-chain implementation`

**Cause:** `JulcMap.of(...)` builds a `JulcAssocMap` in Java, for tests and
off-chain code. The compiler has no lowering for it. Before
[#257](https://github.com/bloxbean/julc/issues/257) this was reported as
`Undefined variable: JulcMap`.

**Fix:** Build the map from `JulcMap.empty()` with `insert`. `insert` adds the
entry at the front of the map, so insert the entries in reverse order to get the
order `JulcMap.of` gives.

```java
// WRONG
JulcMap<byte[], BigInteger> map = JulcMap.of(key, BigInteger.ONE);
JulcMap<byte[], BigInteger> two = JulcMap.of(a, BigInteger.ONE, b, BigInteger.TWO);

// CORRECT
JulcMap<byte[], BigInteger> map = JulcMap.<byte[], BigInteger>empty().insert(key, BigInteger.ONE);
JulcMap<byte[], BigInteger> two = JulcMap.<byte[], BigInteger>empty()
        .insert(b, BigInteger.TWO).insert(a, BigInteger.ONE); // keys: a, b
```

---

### 1.27 `JULC0065` `Updating '<name>' inside a switch statement is not supported in this loop`

**Cause:** A loop carries an update of a variable declared before it only when the
update is a statement of its own, directly in the loop body or in an `if`/`else`
branch. An update in the loop condition or iterable, inside a `switch` statement,
inside an expression or inside a lambda is not seen as one. A loop with `break`
whose only updates are in such a position reports this code at the update; the
message says where (`in the loop condition`, `inside a switch statement`, `inside an
expression`, `inside a lambda`). The code covers only such a loop with `break`: the
same update in a loop without `break` is reported as `JULC0110` (`a switch statement`) or
`JULC0114`.

**Fix:** Write the update as a statement of its own.

```java
// WRONG
for (var x : xs) {
    if (x.signum() < 0) { break; }
    switch (x.intValue()) { case 1 -> c = c + 1; default -> c = c + 2; }
}

// CORRECT
for (var x : xs) {
    if (x.signum() < 0) { break; }
    if (x.intValue() == 1) { c = c + 1; } else { c = c + 2; }
}

// WRONG
if ((c = c + 1) > 5) { Builtins.error(); }

// CORRECT
c = c + 1;
if (c > 5) { Builtins.error(); }

// WRONG
while ((x = x - 1) > 0) { if (x == 3) { break; } }

// CORRECT
while (x - 1 > 0) {
    x = x - 1;
    if (x == 3) { break; }
}
```

---

### 1.28 `JULC0060` `continue is not supported on-chain`

**Cause:** `continue` is outside the on-chain subset. Loops are compiled to folds
and recursive functions whose body runs to its end on every iteration; there is no
lowering that skips the rest of the body. The error is reported when the compiler
lowers the loop that contains the `continue`, so code JuLC never compiles, such as
a nested off-chain helper class, is not affected. Before
[#257](https://github.com/bloxbean/julc/issues/257) this was reported as
`Unsupported statement: ContinueStmt`, without a code.

**Fix:** Guard the rest of the loop body with an `if` instead.

```java
// WRONG
for (var x : xs) {
    if (x.signum() < 0) { continue; }
    total = total.add(x);
}

// CORRECT
for (var x : xs) {
    if (x.signum() >= 0) { total = total.add(x); }
}
```

---

### 1.29 `JULC0066` `Labels are not supported on-chain: '<construct>'`

**Cause:** Labelled statements are outside the on-chain subset, so no `break` or
`continue` can name a label. A labelled statement (`outer:`), a `break` that names a
label (`break outer`) and a `continue` that names one (`continue outer`) report this
code, at that statement, whether or not a matching label exists. `continue` is not
supported without a label either ([1.28](#128-julc0060-continue-is-not-supported-on-chain)).

Before [#201](https://github.com/bloxbean/julc/issues/201), a `break` that named a
label was accepted and compiled as a plain `break` of the innermost loop. javac
rejects `break outer;` when no statement carries the label `outer`, so a Gradle build
stopped there, but tools that compile source text without javac (the testkit's
`compileMethod`, the playground) compiled it with a different meaning: the example
below returned 3 instead of leaving both loops. A labelled statement used to be
reported as `Unsupported statement: LabeledStmt`, without a code. A label is reported
when the compiler lowers the code that contains it, so code JuLC never compiles, such
as a nested off-chain helper class, is not affected. Only a labelled `break` outside
any loop, which subset validation already rejected
([2.5](#25-break-is-only-supported-inside-for-each-or-while-loops-on-chain)), is
reported there.

**Fix:** Remove the label. To leave an outer loop from an inner one, declare a
variable before the loops, set it where the inner loop breaks, and skip the rest of
the outer loop while it is set.

```java
// WRONG
long count = 0;
for (var x : xs) {
    for (var y : xs) {
        if (y.equals(BigInteger.TWO)) { break outer; }
        count = count + 1;
    }
}

// CORRECT: returns 1 for [1, 2, 3], as `outer:` on the outer loop and `break outer` do in Java
long count = 0;
boolean stop = false;
for (var x : xs) {
    if (!stop) {
        for (var y : xs) {
            if (y.equals(BigInteger.TWO)) { stop = true; break; }
            count = count + 1;
        }
    }
}
```

---

### 1.30 `JULC0076` `Unsupported case pattern '<pattern>'`

**Cause:** The most common case is a record pattern, which is valid Java but which JuLC does not compile yet. A case pattern must
also name a permitted variant of the selector's sealed interface. `case S s` on a selector of type `S` is reported with this code too.

**Fix:** Bind the record and read its fields. The error shows this rewrite with your own names:

```java
// Rejected
case A(var v) -> v;

// Means the same thing
case A a -> a.v();
```

This holds when the pattern takes every value of the field, as `var` does. `case W(A x)` on `record W(S inner)` also tests that
`inner` is an `A`, so a rewrite has to keep that test: the error says so, and a value that does not match must go to what the
next case does.

An `instanceof` with a record pattern is `JULC0077`, and the same rewrite applies: `s instanceof A a`, then `a.v()`.

### 1.31 `JULC0139` A pattern variable is used where JuLC does not bind it

**Cause:** JuLC binds an `instanceof` pattern variable wherever Java puts it in scope within
one statement (`&&`, `||`, `!`, `?:`, the `if` and its branches, lambdas, loop bodies) and
after an `if` whose other branch leaves the block. It does not carry a variable out of a
`while` loop's condition, into the loop's body or past the loop.

**Fix:** test the value without the variable where the loop tests it, and test it again with
the variable where you use it:

```java
// WRONG: c is used after the loop that tests it
while (!(s instanceof Circle c)) { s = next(s); }
return c.radius();

// CORRECT
while (!(s instanceof Circle)) { s = next(s); }
if (s instanceof Circle c) { return c.radius(); }
```

If the tested value is a call, store it in a local first, so the second test does not call
it again.

### 1.32 `JULC0140` Reassigning pattern variable `<name>` is not supported

**Cause:** Java lets a pattern variable that is not `final` be reassigned; JuLC does not. In
a loop the update could be lost on the path that rejoins the rest of the body. The one form
that compiled before #204 still does: a loop inside an `if` whose whole condition is the test,
outside a loop with accumulators, updating that `if`'s variable.

**Fix:** declare a new local with the value you need.

```java
// WRONG
if (x instanceof Bid b) { if (late) { b = penalised(b); } total = total + b.amount(); }

// CORRECT
if (x instanceof Bid b) { Bid bid = late ? penalised(b) : b; total = total + bid.amount(); }
```

### 1.33 `JULC0072` `Invalid guard on '<case>': <reason>`

**Example message:** `Invalid guard on 'case Bid b when b.amount().intValue() > 100': 'b.amount().intValue()' narrows to 32 bits in Java, but JuLC lowers it to the identity; compare the BigInteger value instead`

**Cause:** A `when` guard uses something whose on-chain meaning differs from Java's: `intValue()` or
`longValue()`, `int` or `long` arithmetic that is not a constant, a cast to a reference type, or a
`char`, `byte` or `short` value. A guard that is not a `boolean`, or a constant `false`, is
rejected as well; javac usually reports those first, as `JULC0085`.

**Fix:** Compare the `BigInteger` value itself, or compute the condition in a helper that returns `boolean`.

```java
// WRONG
case Bid b when b.amount().intValue() > 100 -> true;

// CORRECT
case Bid b when b.amount().compareTo(BigInteger.valueOf(100)) > 0 -> true;
```

---

### 1.34 `JULC0074` `Guard on '<case>' declares pattern variable '<name>', which is not supported`

**Cause:** A `when` guard declares a pattern variable, as in `when p.target() instanceof ToKey k`.
Java puts `k` in scope in the arm, but JuLC does not bind a variable that a switch guard declares.
An `instanceof` without a binding is fine in a guard.

**Fix:** Move the pattern test into the arm, where `instanceof` patterns are bound, or test the
type in the guard and cast in the arm.

```java
// WRONG
case Pay p when p.target() instanceof ToKey k -> signers.contains(k.key());

// CORRECT: the pattern in the arm
case Pay p -> p.target() instanceof ToKey k && signers.contains(k.key());

// CORRECT: a type test in the guard, a cast in the arm
case Pay p when p.target() instanceof ToKey -> {
    ToKey k = (ToKey) p.target();
    yield signers.contains(k.key());
}
```

---

### 1.35 `JULC0075` `Unsupported switch group '<case>': <reason>`

**Example message:** `Unsupported switch group 'case Add a': the group can complete normally, so Java would fall through into the next group; JuLC does not implement fall-through. End the group with yield`

**Cause:** In a colon-form switch expression (`case X x:`), a group can fall through into the next
one, or one group has several labels. JuLC compiles a colon-form group only when it has one label
and yields on every path.

**Fix:** End every group with `yield`, give each label its own group, or use the arrow form.

```java
// WRONG: the first group falls through into default
BigInteger r = switch (step) {
    case Add a:
        a.v().negate();
    default:
        yield BigInteger.TWO;
};

// CORRECT
BigInteger r = switch (step) {
    case Add a:
        yield a.v().negate();
    default:
        yield BigInteger.TWO;
};
```

---

### 1.36 `JULC0083` `Pattern variable '<name>' is already defined <where>`

**Cause:** A pattern variable has the name of a parameter, a local or another pattern variable in
scope, or a pattern declares one name twice. javac rejects almost every such source first, as
`JULC0085` with `compiler.err.already.defined` or `compiler.err.match.binding.exists`; JuLC keeps
`JULC0083` for a guard that declares the same name twice. A pattern variable may have the name of a
field, as in Java.

**Fix:** Rename the pattern variable.

```java
// WRONG: javac reports "variable r is already defined in method size(...)"
static BigInteger size(BigInteger r, Shape s) {
    return switch (s) {
        case Circle r -> r.r();
        case Square q -> q.side();
    };
}

// CORRECT
static BigInteger size(BigInteger r, Shape s) {
    return switch (s) {
        case Circle c -> c.r();
        case Square q -> q.side();
    };
}
```

---

### 1.37 `JULC0132` `This switch case block does not yield a value on every path`

**Cause:** A `{ ... }` block in a switch expression can end without a `yield` on some path, by
JuLC's reading. JuLC does not read a loop as never finishing, so `while (true) { yield x; }`, which
javac accepts, has a path with no value.

**Fix:** End every path in the block with `yield`.

```java
// WRONG
case Add a -> {
    while (true) {
        yield a.v();
    }
}

// CORRECT
case Add a -> {
    BigInteger v = a.v();
    yield v.signum() > 0 ? v : BigInteger.ZERO;
}
```

---

## 2. Validation Errors (subset rules)

These errors are emitted during the subset validation pass, before PIR generation
begins. They reject Java constructs that have no on-chain equivalent. Each error
includes the file name, line number, and column.

### 2.1 `try/catch is not supported on-chain`

**Suggestion:** `Use if/else checks instead of exception handling`

**Cause:** A `try`/`catch`/`finally` block was used. UPLC has no exception handling
mechanism.

**Fix:** Replace with explicit if/else validation logic.

```java
// WRONG
try {
    riskyOperation();
} catch (Exception e) {
    return false;
}

// CORRECT
if (!isValid(input)) {
    return false;
}
```

---

### 2.2 `throw is not supported on-chain`

**Suggestion:** `Return false from the validator to reject a transaction`

**Cause:** A `throw` statement was used. On-chain validators reject transactions
by returning `false`, not by throwing exceptions.

**Fix:** Return `false` to reject a transaction.

```java
// WRONG
if (!authorized) {
    throw new RuntimeException("Unauthorized");
}

// CORRECT
if (!authorized) {
    return false;
}
```

---

### 2.3 `synchronized is not supported on-chain`

**Suggestion:** `On-chain code is single-threaded; remove synchronized blocks`

**Cause:** A `synchronized` block or method was used. On-chain execution is
single-threaded and deterministic.

**Fix:** Remove the `synchronized` keyword.

---

### 2.4 `C-style for loops are not supported on-chain`

**Suggestion:** `Use for-each over a list or while loops instead`

**Cause:** A traditional `for (init; cond; update)` loop was used.

**Fix:** Use `for-each` over a list or a `while` loop with an accumulator.

```java
// WRONG
for (int i = 0; i < 10; i++) {
    // ...
}

// CORRECT
for (var item : items) {
    // process item
}

// CORRECT: while loop with accumulator
var i = BigInteger.ZERO;
while (i < BigInteger.TEN) {
    // process
    i = i + BigInteger.ONE;
}
```

---

### 2.5 `break is only supported inside for-each or while loops on-chain`

**Suggestion:** `Use for-each or while with an accumulator and break to exit early`

**Cause:** A `break` statement appeared outside of a for-each or while loop.
A `break` there that names a label reports `JULC0066` instead ([1.29](#129-julc0066-labels-are-not-supported-on-chain-construct)).

**Fix:** Place `break` inside a for-each or while loop.

---

### 2.6 `do-while loops are not supported on-chain`

**Suggestion:** `Use while loops or for-each instead`

**Cause:** A `do { ... } while (cond)` loop was used.

**Fix:** Rewrite as a `while` loop.

```java
// WRONG
do {
    process();
} while (hasMore);

// CORRECT
while (hasMore) {
    process();
}
```

---

### 2.7 `null is not supported on-chain`

**Suggestion:** `Use Optional<T> to represent absence of a value`

**Cause:** The `null` literal was used. UPLC does not have a null concept.

**Fix:** Use `Optional<T>` or sentinel values.

```java
// WRONG
PlutusData result = null;

// CORRECT
var result = Optional.empty();
```

---

### 2.8 `'this' is not supported on-chain`

**Suggestion:** `On-chain validators are stateless; use static methods instead`

**Cause:** The `this` keyword was used. On-chain validators are purely functional
and stateless.

**Fix:** Use static methods and pass all data as parameters.

```java
// WRONG
public boolean validate() {
    return this.checkSignature();
}

// CORRECT
public static boolean validate(ScriptContext ctx) {
    return checkSignature(ctx);
}
```

---

### 2.9 `'super' is not supported on-chain`

**Suggestion:** `Use sealed interfaces and pattern matching instead of inheritance`

**Cause:** The `super` keyword was used. Inheritance is not supported on-chain.

**Fix:** Use sealed interfaces with record variants and pattern matching.

---

### 2.10 `arrays are not supported on-chain`

**Suggestion:** `Use List<T> instead of arrays`

**Cause:** An array creation expression (e.g., `new int[10]`) was used.

**Fix:** Use a `byte[]` literal or a builtin for bytes, and `JulcList<T>` for a list of elements.

```java
// WRONG
var data = new byte[32];

// CORRECT
var zeros = Builtins.replicateByte(32, 0);
byte[] literal = new byte[]{0x01, -1};  // the integer literals of a byte, so 0xFF is -1
```

> **PV11 Note:** From protocol version 11, `JulcArray<T>` provides O(1) random-access arrays via CIP-138 builtins. Use `list.toArray()` to convert a list.

---

### 2.11 `array access is not supported on-chain`

**Suggestion:** `Use List<T> with list operations instead`

**Cause:** Array indexing (e.g., `arr[i]`) was used.

**Fix:** Use list operations from `ListsLib`.

```java
// WRONG
var first = items[0];

// CORRECT
var first = Builtins.headList(items);
// or
var nth = ListsLib.nth(items, 2);
```

> **PV11 Note:** From protocol version 11, `JulcArray<T>` provides O(1) random-access arrays via CIP-138 builtins. Use `list.toArray()` to convert a list, then `arr.get(index)` for O(1) access.

---

### 2.12 `floating point types (float/double) are not supported on-chain`

**Suggestion:** `Use BigInteger for integer arithmetic or Rational for fractions`

**Cause:** A `float` or `double` type was used. UPLC only supports exact integer
arithmetic.

**Fix:** Use `BigInteger` for integer math. For fractions, use a rational number
pattern (numerator/denominator as `BigInteger`).

```java
// WRONG
double price = 1.5;
float rate = 0.03f;

// CORRECT
var priceNumerator = new BigInteger("3");
var priceDenominator = new BigInteger("2"); // represents 1.5
```

---

### 2.13 `class inheritance is not supported on-chain`

**Suggestion:** `Use sealed interfaces with record variants instead`

**Cause:** A class extends another class (other than `Object`).

**Fix:** Use sealed interfaces with record variants for polymorphism.

```java
// WRONG
public class SpecialBid extends Bid { ... }

// CORRECT
public sealed interface Action permits Bid, Cancel {}
public record Bid(BigInteger amount) implements Action {}
public record Cancel() implements Action {}
```

---

### 2.14 `JULC0052` `Compound assignment operator <op> is not supported on-chain`

**Cause:** A bitwise, shift or boolean compound assignment (`&=`, `|=`, `^=`, `<<=`, `>>=`,
`>>>=`) was used. Only `+=`, `-=`, `*=`, `/=` and `%=` on integers, and `+=` on strings, are
lowered (as `x = x op y`). Java evaluates the right-hand side of `&=` and `|=` even when the
result is already known; lowering them as `&&` or `||` would skip it, so they are rejected.

**Fix:** Evaluate the check first, then combine it.

```java
// WRONG
for (var x : xs) {
    ok &= check(x);
}

// CORRECT
for (var x : xs) {
    boolean c = check(x);
    ok = ok && c;
}
```

---

### 2.15 `JULC0053` `Declare one variable per statement: <declaration>`

**Cause:** A local declaration declared several variables (`BigInteger a = x, b = y;`). Only the
first one would be bound, so a later reference to another could read an outer declaration of the
same name. Fields may still declare several variables.

**Fix:** Split the declaration into one statement per variable.

---

### 2.16 `JULC0056` `Assignment to field '<field>' in a loop is not supported`

**Cause:** A loop updates a static field or `@Param`. On-chain fields are not shared state: the
update rebinds the name only in the rest of the method's current run. Every other method, and
every other run of the same method, sees the field's initial value. The update is accepted only
where that gives Java's result, and the message says which condition failed:

- `the field is final`
- `'<method>' is not an entry method`: only a validator entrypoint, a multi-validator handler or
  the `compileMethod` target may update a field. A helper or a library method can be called more
  than once.
- `'<method>' calls itself` or `'<caller>' calls '<method>'`: the entry method would run more
  than once.
- `the loop is in a lambda`: the lambda can run more than once.
- `a static field initializer cannot update a field`
- `'<method>' reads it`: another method would see the initial value. A method whose local,
  parameter or lambda variable has the field's name does not read the field.

**Fix:** Copy the field into a local accumulator before the loop, update the local, and pass it to
the methods that need it.

```java
// WRONG: current() still sees the original count
for (var x : xs) {
    count += 1;
}
return current();

// CORRECT
long seen = count;
for (var x : xs) {
    seen += 1;
}
return seen;
```

---

### 2.17 `JULC0058` `Reassigning loop variable '<name>' is not supported in a loop that updates no variable declared before the loop`

**Cause:** A for-each loop reassigns its loop variable, or a local declared in its body, and updates
no variable declared before the loop. Such a loop compiles its body as plain statements, which
cannot reassign a variable. A loop that also updates an earlier variable (an accumulator) can
reassign its loop variable and body locals. The message says `local variable` for a body local.

**Fix:** Declare a new local for the new value instead of reassigning.

```java
// WRONG
for (var amount : amounts) {
    amount = amount.subtract(fee);
    if (amount.signum() < 0) Builtins.error();
}

// CORRECT
for (var amount : amounts) {
    var net = amount.subtract(fee);
    if (net.signum() < 0) Builtins.error();
}
```

---

### 2.18 `JULC0059` `Pattern variable '<name>' has the same name as a field of <Class>`

**Cause:** An `instanceof` pattern variable has the same name as a field of the class,
including a `@Param` field. The rule dates from JuLC's first frontend, which resolved names
by spelling, so such a name could read or update the field instead of the variable. The name
is rejected in every position.

**Fix:** Rename the pattern variable. `switch` case patterns are not affected.

```java
static Bid best = new Bid(BigInteger.ZERO);

// WRONG
if (action instanceof Bid best) { return best.amount(); }

// CORRECT
if (action instanceof Bid bid) { return bid.amount(); }
```

---

### 2.19 `JULC0070` `Integer literal <literal> is invalid: <reason>`

**Cause:** javac rejects the literal, and so does JuLC
([#270](https://github.com/bloxbean/julc/issues/270)) (JLS 21 §3.10.1):

- An `int` literal has 32 bits and a `long` literal (suffix `L`) 64. A decimal literal must
  fit the signed range; `2147483648` and `9223372036854775808L` are allowed only directly
  after a unary minus, not inside parentheses. A hexadecimal, octal or binary literal must
  fit the width.
- An octal literal (a leading `0`) has only the digits 0 to 7, so `08` is invalid.
- A `byte[]` initializer element must be in -128 to 127.

A hexadecimal, octal or binary literal that fits denotes the two's-complement value of its
bits, as in Java: `0xFFFFFFFF` is `-1`, and `0xFFFFFFFFL` is `4294967295`.

**Fix:** Add the `L` suffix for a `long` literal, use `BigInteger` for a larger value, write a
negated most-negative literal without parentheses, and keep `byte[]` elements in range.

```java
// WRONG
long big = 4294967296;
byte[] b = new byte[]{0xFF};

// CORRECT
long big = 4294967296L;
byte[] b = new byte[]{-1};
BigInteger bigger = new BigInteger("18446744073709551616");
```

---

## 3. Configuration Errors (JulcCompiler)

These errors are raised during compiler setup and pipeline orchestration.

### 3.0 `JULC0085` `<javac's message> (compiler.err.<key>)`

**Cause:** javac rejects the source. Before JuLC compiles anything, javac analyzes the
sources of the compilation, and an error is reported with javac's message, key
and position. JuLC's dialect beyond Java is rejected this way, for example `a + b` or
`a > b` on `BigInteger` (`compiler.err.operator.cant.be.applied.1`), an `int` where a
`BigInteger` is expected (`compiler.err.prob.found.req`), or a ledger or stdlib type used
without an import (`compiler.err.cant.resolve.location`).

**Fix:** Fix the Java error. Where the hint names a JDK type, use JuLC's on-chain
equivalent (`JulcList`, `JulcMap`).

```java
// WRONG
BigInteger total = a + b;
if (total > limit) { ... }
BigInteger one = 1;

// CORRECT
BigInteger total = a.add(b);
if (total.compareTo(limit) > 0) { ... }
BigInteger one = BigInteger.ONE;
```

The [release notes](/reference/release-notes/) list the rewrites. javac reads every compilation and
the check cannot be turned off.

A runtime without a supported javac (JDK 25 with the `jdk.compiler` module) is `JULC0089`.

### `JULC0094` `Entrypoint <method> of <class> returns <type>; an entry point returns boolean or java.lang.Boolean`

**Cause:** An `@Entrypoint` method returns something other than `boolean` or `java.lang.Boolean`.
A type of your own named `Boolean` is not `java.lang.Boolean`.

**Fix:** Return a `boolean`. To fail, return `false` or call `Builtins.error()`.

### `JULC0095` `<field or block> is not lowered, so ...`

**Cause:** A `static` field of the class being compiled is lowered. Nothing lowers any other
field (a `@Param`, an instance field, a field of a nested type or a record), and nothing lowers
an initializer block. A library's `static` field binds at script start, whether or not Java
initializes the class, so it counts as one of these.

**Fix:** Such a field holds only a literal, a `new byte[]{...}` of literals,
`BigInteger.ZERO`, `ONE`, `TWO` or `TEN`, `PlutusData.UNIT`, a constant variable, or
`Boolean.valueOf(true)` or `Boolean.valueOf(false)`. Move anything else to a `static` field of
the class being compiled, or to a method. Keep an initializer block empty.

### `JULC0098` `A cast to <type> takes a boolean or a Boolean, not <type>`

**Cause:** A cast to `boolean` or `Boolean`, or a call that lowers to its argument with that result, such
as `PlutusData.cast(x, Boolean.class)`, of something else, through parentheses and other casts. Data is
something else: a cast does not decode it.

**Fix:** Compute the boolean with a comparison.

### `JULC0101` `<method or component> has a name JuLC reads in place of its body, so a program does not declare it`

**Cause:** JuLC compiles a record as its components, so a declared `toPlutusData` or an explicit accessor of a record is
never what a script's Data is. A generated accessor that implements `ToPlutusData.toPlutusData` is rejected too.

**Fix:** Give the method or component another name, and read the component through its generated accessor.

### `JULC0102` `These string constants are joined when the script runs`

A warning; the program still compiles. Java joins string constants when it compiles, and JuLC
keeps a `+` as written, so the script joins them each time it runs.

```java
// Warns:
return "hello " + "world";

// Write one literal:
return "hello world";
```

A constant is a string literal, a `String` variable javac gives a constant value, or a `+` of
constants, through parentheses. One warning covers a whole expression of constants. A `+` with a
parameter or a local that is not constant does not warn.

### `JULC0099` `The body of constructor <constructor> does not run on-chain, so a constructor holds no statement`

**Cause:** A constructor the source declares, on a record or a class, holds a statement. A value is
built from its components alone, so the statement would not run on-chain.

**Fix:** Move the statement to a static method that returns the value. An empty `R { }` is allowed.

### `JULC0096` `<source or class> declares more than one <validator class or @Entrypoint method>`

**Cause:** a source declares two validator classes, or a single-purpose
validator has two `@Entrypoint` methods.

**Fix:** Keep one. A `@MultiValidator` is how a script has several entry points.

### `JULC0097` `Entrypoint <method> of <class> is not static`

**Cause:** an entry point is not `static`.

**Fix:** Declare the entry point `static`.

### `JULC0103` `Entrypoint <method> of <class> takes <type> as its last parameter; the last parameter is the script context, ScriptContext or PlutusData`

**Cause:** An `@Entrypoint` method of a validator declares its last parameter with a type other than `org.julclang.ledger.ScriptContext` or `org.julclang.core.PlutusData`. The script receives the script context there, undecoded, so a parameter typed `BigInteger`, a record or a subtype of `PlutusData` reads the context as that type, and the method's JVM test does not test the script. A type of your own named `ScriptContext` is not the ledger's. An entry point with a parameter count its purpose does not take is `JULC0008` instead.

**Fix:** Declare the last parameter `ScriptContext`, or `PlutusData` to keep the context as Data. Take other values from the redeemer, or, for a spending validator, from the datum: `validate(BigInteger datum, BigInteger redeemer, ScriptContext ctx)`.

### `JULC0079` `Record <record> implements sealed interfaces <interface> and <interface>; a record may have at most one sealed parent`

**Cause:** a record implements two sealed interfaces. A Data value has one constructor tag, so one of the two families would read the record as another of its variants.

**Fix:** Give the record one sealed parent. For the second family, wrap it in a variant of its own: `record InT(A a) implements T { }`.

### `JULC0080` `Record <record> declares <member>, which JuLC does not compile; a record body may contain only nested type declarations`

**Cause:** a record declares a member other than a nested type: a method, `equals`, `hashCode`, `toString`, a compact or other constructor, a static field or an initializer block. JuLC compiles a record as its components only, so such a member is never what the script runs. A declared accessor or `toPlutusData` is `JULC0101`, and a constructor with a statement `JULC0099`.

**Fix:** Move the logic into a static method of the enclosing class, which takes the record as a parameter, and let the record hold its components alone.

### `JULC0087` `<operation> compares <type>, whose equality on the JVM compares an array by identity`

**Cause:** an `equals`, or a collection operation that compares
elements or keys (`contains`, `get`, `lookup`, `containsKey`, `delete`), at a type whose JVM equality compares a `byte[]`
by reference: `byte[].equals`, a record or sealed variant with a `byte[]` component, or an `Optional`, tuple or
`java.util` collection of one. On-chain the comparison is by content, so the script and the method's JVM test disagree.
A type the check cannot read (a raw collection, `Object`, a type variable) is rejected the same way, as is
`equals` between two containers of unrelated arguments, which Java answers both ways.

**Fix:** Compare the bytes with `Builtins.equalsByteString(a, b)`; `ByteStringLib.equals(a, b)` is equivalent, a few bytes larger. For a `@NewType` byte wrapper, compare its
components: `Builtins.equalsByteString(a.value(), b.value())`. The [release notes](/reference/release-notes/) have a
migration guide.

```java
// WRONG: identity on the JVM
return signer.equals(expected);
// CORRECT
return Builtins.equalsByteString(signer, expected);
```

### `JULC0086` `<expression> compares <type> by reference; use equals`

**Cause:** `==` or `!=` between references (`BigInteger`, `byte[]`,
`String`, boxed values, records) is reference equality in Java and value equality on-chain. Constant
strings built from literals and `+` alone keep their meaning.

**Fix:** `a.equals(b)`, or `Builtins.equalsByteString(a, b)` for bytes.

### `JULC0104` `synchronized is not supported on-chain`

**Cause:** a synchronized block has no on-chain meaning.

**Fix:** On-chain code is single-threaded; remove synchronized blocks.

### `JULC0105` `Method references (::) are not supported on-chain`

**Cause:** a method reference has no on-chain lowering.

**Fix:** Use a regular static method call instead.

### `JULC0106` `Functional interface .<method>() is not supported on-chain`

**Cause:** a function exists on-chain only as the argument of the list method that applies it, so a local of a functional interface type cannot be called.

**Fix:** Call the method directly instead of through a function reference.

### `JULC0107` `'this or super' is not supported on-chain`

**Cause:** On-chain code has no objects and no inheritance, so this and super name nothing.

**Fix:** On-chain validators are stateless; use static methods, and sealed interfaces with record variants instead of inheritance.

### `JULC0108` `array access is not supported on-chain`

**Cause:** an array element read or write has no on-chain lowering.

**Fix:** Use List<T> with list operations instead.

### `JULC0109` `class inheritance is not supported on-chain`

**Cause:** a class that extends another class, or an interface that extends another, has no on-chain meaning.

**Fix:** Use sealed interfaces with record variants instead.

### `JULC0110` `Unsupported statement: <statement>`

**Cause:** a bare block, an empty statement or a switch statement among a method's statements has no lowering.

**Fix:** Only variable declarations, if/else, for-each, while, return, and expression statements are supported on-chain.

### `JULC0111` `'return' is not supported inside for-each loop body`

**Cause:** a for-each loop is compiled to a fold whose result is the variables the loop updates, so its body cannot return from the method.

**Fix:** Use 'break' to exit the loop early, then return after the loop. Or use the accumulator pattern: result = value; (continue iterating) then return result; after the loop.

### `JULC0112` `'yield' is not supported inside for-each or while loop body`

**Cause:** a loop body cannot yield a value of an enclosing switch expression.

**Fix:** Use 'break' to exit the loop early, then yield after the loop. Or use the accumulator pattern: result = value; (continue iterating) then yield result; after the loop.

### `JULC0113` `Conditional update to loop-body local '<local>' is not supported`

**Cause:** an if/else branch in a loop carries only the loop's accumulators out of the branch, so an update of a local declared in the loop body would be lost.

**Fix:** An if/else branch carries only loop accumulators out of the branch. Use a conditional initializer (condition ? value : otherValue), or declare the variable before the loop so it is an accumulator (reset it each iteration if needed), or declare it inside the branch if its value is only needed there.

### `JULC0114` `Assignment to <variable> is not supported at this position`

**Cause:** Inside a loop, an assignment is lowered only as a statement directly in the loop body or in an if/else branch of it.

**Fix:** Inside a loop, assign an accumulator or a loop-body local as a statement directly in the loop body. In an if/else branch, assign an accumulator or a local declared within that branch; an assignment inside another expression or statement is not supported.

### `JULC0115` `Unsupported <loop kind> body: <statement>`

**Cause:** a loop that breaks, or that updates several variables declared before it, lowers only declarations, assignments, if/else and break in its body.

**Fix:** Inside loops with break or several accumulators, only variable declarations, assignments, if/else, and break are supported.

### `JULC0116` `Assignment to undeclared variable '<variable>'`

**Cause:** a loop assigns a name that is neither an accumulator of the loop nor a local of its body, such as a field.

**Fix:** Declare the variable before assigning it: before the loop for an accumulator, in the loop body for a local.

### `JULC0117` `Compound assignment <operator> to '<variable>' <what it needs>`

**Cause:** Compound assignment is lowered on integer variables, and += on String variables with a String operand.

**Fix:** Write the update explicitly, e.g. x = x.add(y), or convert the right-hand side to the variable's type first.

### `JULC0118` `Unsupported operator: <operator>`

**Cause:** a binary operator with no on-chain lowering, such as a shift or a bitwise operator.

**Fix:** Supported operators: +, -, *, /, %, ==, !=, <, <=, >, >=, &&, ||.

### `JULC0119` `Unsupported unary operator: <operator>`

**Cause:** a unary operator with no on-chain lowering, such as ~, + or an increment outside a loop update.

**Fix:** Supported unary operators: ! (logical NOT) and - (negation).

### `JULC0120` `Reassignment of switch case-pattern variable '<variable>' is not supported`

**Cause:** an arm of a switch reads a pattern variable through projections of the matched record, so an assignment to it would not be seen.

**Fix:** Copy the pattern variable to a fresh local accumulator inside the arm, update that local, and yield its result instead.

### `JULC0121` `Switch-expression arm cannot update enclosing variable '<variable>'`

**Cause:** a switch expression exports only its yielded value, so an update of a variable declared outside the arm would be lost.

**Fix:** A switch expression exports only its yielded value. Declare an accumulator inside the arm, yield its result, and use the switch value outside the arm instead of mutating an enclosing variable.

### `JULC0122` `Generic library method <class.method> cannot be called from Java yet: generic @OnchainLibrary methods are specialized only by language-neutral library providers. Provide a concrete overload-free method for Java callers.`

**Cause:** a generic @OnchainLibrary method is a template that only a language-neutral producer instantiates, so Java code cannot call it.

**Fix:** Provide a concrete overload-free method for Java callers.

### `JULC0123` `Static call <call> names its class by a qualified name; on-chain code calls a static method through its class's simple name`

**Cause:** JuLC resolves a static call through the simple name of its class, as an import or the enclosing class declares it, so a call through a package-qualified class name, such as `java.math.BigInteger.valueOf(1)`, is not resolved. A nested class's qualified name, `Outer.Inner.f(a)`, is `JULC0011`.

**Fix:** Import the class and call the method through its simple name, for example `import java.math.BigInteger;` and `BigInteger.valueOf(1)`.

### `JULC0124` `Unsupported primitive type: <type>. Supported types: boolean, int, long`

**Cause:** `byte`, `short`, `char`, `float` and `double` have no on-chain representation, as a variable's, a component's or a cast's type. The one exception is a cast of an integer literal to `byte`, `short` or `char`, perhaps negated, which is its Java value. A `char` literal elsewhere is `JULC0125`.

**Fix:** Use `int` or `long` for a number, `BigInteger` for an unbounded integer, `boolean` for a flag, and `String` or `byte[]` for text and bytes.

### `JULC0125` `Unsupported expression: <expression>`

**Cause:** an expression form JuLC does not compile on-chain, such as a class literal, a text block, a `char` literal or a function that is not a lambda written at the call.

**Fix:** Write the value with a supported form: a string literal for a text block, a lambda at the call for a function, and no class literal outside `PlutusData.cast`.

### `JULC0126` `<pattern> tests a value of type <type>, which is not <interface> or a variant of it; on-chain the test reads a constructor tag, which another type can share`

**Cause:** an `instanceof` pattern on a variant reads the value's constructor tag. That answers Java's question only when the value's static type is the variant's sealed interface or the variant itself: a value of another type, through a widening cast to `Object` for one, can carry the same tag.

**Fix:** Test a value of the sealed interface's type, or `switch` over it.

### `JULC0127` `<variant> is a variant of <interface> that is not a plain record; a variant is a record, not a class or a @NewType, so that it has one constructor tag and its fields`

**Cause:** JuLC encodes a sealed interface's value as a constructor whose tag is the variant's place among the permitted records, and whose fields are the record's components. A final class or a `@NewType` has neither. It is reported at a `new`, an `instanceof` or a `case` of such a variant, and otherwise at the variant's declaration wherever its sealed interface is declared in a unit the compilation reads, **whether or not the program uses the sum**: such a sum has no layout, so a value of it would be dropped wherever the sum is read.

**Fix:** Make the variant a record that is not a `@NewType`.

### `JULC0128` `<operation>: <reason>`

**Cause:** the value's on-chain representation is not the one this operation takes, and no conversion keeps Java's value: Data where a builtin takes bytes, a native list where a component holds Data, a ledger type that holds a map, whose Data repeats a key the JVM's map holds once.

**Fix:** Convert explicitly: `Builtins.unBData` for bytes, `Builtins.listData` or `Builtins.mapData` for a list or a map, or read the map through the stdlib's `ValuesLib` and `MapLib`.

### `JULC0129` `Cannot construct non-record type: <type>`

**Cause:** a `new` of a class that is not a record, a tuple, a sealed variant or a supported API type. On-chain a value is a record's components, and a class's constructor and fields have no on-chain form.

**Fix:** Only record types can be constructed on-chain. Declare the type as a record.

### `JULC0130` `Compound assignment <op> to the int '<name>' takes a long`

**Cause:** Java's `int x; x += someLong;` casts the result back to `int` without saying so. An on-chain integer has no width, so past 32 bits a JVM test and the script would give different values. A `Long` javac inferred counts too, as in `for (var z : JulcList.of(y)) { x += z; }`: Java unboxes it first.

**Fix:** Declare the variable as `long`, which is what most code means: `long total = 0; total += amount;`. Use `BigInteger` for values that can pass 64 bits. If you do want Java's 32-bit result, write the cast: `x = (int) (x + y);`.

### Codes for the subset's rejections

These rejections carry a code: `JULC0001` (a method
with no body), `JULC0002` (a local with no initializer), `JULC0004` (`break` outside a loop), `JULC0006` (a method that may not
return), `JULC0011` (`Undefined variable`), `JULC0014` (an array other than a `byte[]` literal), `JULC0020` (`float` or
`double`), `JULC0024` (`Unknown method`) and `JULC0077` (an `instanceof` with a record pattern). Their causes and fixes are those
of the words; [`/ai/diagnostics.json`](/ai/diagnostics.json) has each entry.

### 3.1 `Failed to parse <label> source: <details>`

**Cause:** The Java source code has syntax errors, and javac cannot parse it.
`<label>` is either `"validator"` or `"library[n]"`. javac reports most syntax errors
first, as `JULC0085`.

**Fix:** Ensure the source compiles with `javac` first. Common causes: missing
semicolons, unmatched braces, invalid Java 21 syntax.

---

### 3.2 `JULC0010` `No validator annotation found (e.g. @SpendingValidator, @MintingValidator)`

**Cause:** The validator source file does not contain any class annotated with a
validator annotation.

**Fix:** Annotate your validator class with one of the recognized annotations.

```java
import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;

// WRONG: no annotation
public class MyValidator {
    public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        return true;
    }
}

// CORRECT
@SpendingValidator
public class MyValidator {
    @Entrypoint
    public static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
        return true;
    }
}
```

**Recognized annotations:** `@SpendingValidator`, `@MintingValidator`, `@WithdrawValidator`, `@CertifyingValidator`,
`@VotingValidator`, `@ProposingValidator`

---

### 3.3 `JULC0009` `No @Entrypoint method found in <ClassName>`

**Cause:** The annotated validator class does not contain a method annotated with
`@Entrypoint`.

**Fix:** Add `@Entrypoint` to the validator's main method.

```java
import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;

@SpendingValidator
public class MyValidator {
    // WRONG: missing @Entrypoint
    public static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
        return true;
    }
}

@SpendingValidator
public class MyValidator {
    @Entrypoint  // CORRECT
    public static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
        return true;
    }
}
```

---

### 3.4 `@SpendingValidator entrypoint must have 2 or 3 parameters`

**Full message:** `@SpendingValidator entrypoint must have 2 or 3 parameters
(datum, redeemer, scriptContext), found <n> in <Class>.<method>()`

**Cause:** A spending validator entrypoint has the wrong number of parameters.
Spending validators accept 2 parameters (redeemer, scriptContext) or 3 parameters
(datum, redeemer, scriptContext).

**Fix:** Adjust the parameter count.

```java
// WRONG: 1 parameter
@Entrypoint
public static boolean validate(ScriptContext ctx) { ... }

// CORRECT: 2 parameters (datum in ScriptContext)
@Entrypoint
public static boolean validate(PlutusData redeemer, ScriptContext ctx) { ... }

// CORRECT: 3 parameters (explicit datum)
@Entrypoint
public static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) { ... }
```

---

### 3.5 `@MintingValidator entrypoint must have 2 parameters`

**Full message:** `<annotation> entrypoint must have 2 parameters (redeemer,
scriptContext), found <n> in <Class>.<method>(). Did you mean
@SpendingValidator?`

**Cause:** A non-spending validator entrypoint does not have exactly 2 parameters.
If you specified 3 parameters, the compiler asks if you meant to use
`@SpendingValidator`.

**Fix:** Use 2 parameters for non-spending validators, or switch to
`@SpendingValidator` if you need a datum parameter.

```java
import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.MintingValidator;

// WRONG: 3 params on a minting validator
@MintingValidator
public class MyPolicy {
    @Entrypoint
    public static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) { ... }
    // Error: "Did you mean @SpendingValidator?"
}

// CORRECT
@MintingValidator
public class MyPolicy {
    @Entrypoint
    public static boolean validate(PlutusData redeemer, ScriptContext ctx) { ... }
}
```

---

### 3.6 `Library source must not contain @<Annotation>: <ClassName>`

**Cause:** A source file passed as a library (not the validator source) contains a
validator annotation. Library files must not be validators.

**Fix:** Remove the validator annotation from library classes, or pass the file as
the validator source instead.

```java
import org.julclang.stdlib.annotation.MintingValidator;
import org.julclang.stdlib.annotation.OnchainLibrary;
// WRONG: library file with validator annotation
@OnchainLibrary
@MintingValidator  // this is not allowed on a library
public class HelperLib { ... }

// CORRECT
@OnchainLibrary
public class HelperLib { ... }
```

---

### 3.7 `JULC0054` `Method <name> is declared more than once in <class> with different on-chain behaviour`

**Cause:** On-chain methods are bound by name, so every call to an overloaded name would run one
of its declarations. Declarations that compile to identical code and types (such as the stdlib's
`integerToByteString(boolean, long, long)` and `(boolean, long, BigInteger)`) are accepted; any
other overload is rejected. A helper may not reuse an entrypoint's name.

**Fix:** Give each method a distinct name.

---

### 3.8 `JULC0057` `Static field '<field>' calls method <name>`

**Cause:** Static field initializers are evaluated outside the class's methods, so they cannot
call them.

**Fix:** Call the method where the value is needed, or initialise the field with an expression that
does not call a method of the same class.

---

### 3.9 `JULC0081` `<source> declares <type>, which JuLC's <owner> owns`

**Example message:** `AmountValidator.java declares org.julclang.ledger.AmountValidator, which JuLC's API package org.julclang.ledger owns`

**Cause:** A source declares a type in one of JuLC's API packages (`org.julclang.core`,
`org.julclang.ledger`, the stdlib's on-chain packages), or redeclares a bundled library. Those
types come only from JuLC, so no source can replace or extend them.

**Fix:** Move the type to a package of your own.

```java
// WRONG
package org.julclang.ledger;

// CORRECT
package com.example;

import org.julclang.ledger.ScriptContext;
```

---

### 3.10 `JULC0088` `@Param field <name> is a constant variable in Java, so javac would fold it; it is runtime input`

**Cause:** A `static final` `@Param` field of a primitive type or `String` has a constant
initializer. javac copies a constant into every place that reads it, so the JVM program would use
the initializer, while the script reads the parameter applied at deployment.

**Fix:** Drop `final`. A `@Param` value is supplied when the script is parameterized.

```java
// WRONG
@Param static final String TAG = "v1";

// CORRECT
@Param static String TAG;
```

---

### 3.11 `JULC0133` `No class found in source`

**Cause:** A source given to `compileMethod` (or to `JulcEval`) declares no class, for example
only an interface or a record.

**Fix:** Put the method in a class.

```java
// WRONG
interface Fees {
    BigInteger fee(BigInteger amount);
}

// CORRECT
class Fees {
    static BigInteger fee(BigInteger amount) {
        return amount.divide(BigInteger.valueOf(100));
    }
}
```

---

### 3.12 `JULC0134` `Class <name> must not combine @OnchainLibrary with validator annotation(s): <annotations>`

**Cause:** A class is annotated both as a library (`@OnchainLibrary`) and as a validator. A class is either a library that other code calls or a validator that the
ledger runs.

**Fix:** Keep one role on the class, and move the other into a class of its own.

```java
// WRONG
@OnchainLibrary
@SpendingValidator
public class FeeLib { ... }

// CORRECT
@OnchainLibrary
public class FeeLib { ... }

@SpendingValidator
public class FeeValidator { ... }   // calls FeeLib.fee(...)
```

---

### 3.13 `JULC0135` `Class <name> has multiple validator annotations: <annotations>`

**Example message:** `Class BadValidator has multiple validator annotations: @SpendingValidator, @MintingValidator`

**Cause:** Each class compiles to one script with one purpose. See also
[Multiple validator annotations on the same class](#q-multiple-validator-annotations-on-the-same-class).

**Fix:** Keep one validator annotation. For several purposes in one script, use `@MultiValidator`.

---

## 4. Type Resolution Errors

These errors are raised while the compiler registers and resolves types.

### 4.1 `Unknown type: <name>`

**Cause:** A type name was used that the compiler does not recognize. It is not a
primitive type, a known ledger type, a registered record, or a sealed interface.

**Fix:** Define the type as a record or sealed interface, or ensure it is imported.

```java
// WRONG: MyData is not defined
public static boolean validate(MyData data, ScriptContext ctx) { ... }

// CORRECT: define it as a record
public record MyData(BigInteger amount, byte[] hash) {}
```

---

### 4.2 `Unsupported primitive type: <type>`

**Cause:** A primitive type other than `boolean`, `int`, or `long` was used
(e.g., `char`, `short`, `byte` outside of `byte[]`).

**Fix:** Use `BigInteger` for integers and `boolean` for booleans.

---

### 4.3 `Arrays are not supported; use List. Got: <type>`

**Cause:** An array type other than `byte[]` was used. Only `byte[]` is supported
(mapped to ByteString).

**Fix:** Use `List<T>` instead of arrays.

> **PV11 Note:** From protocol version 11, `JulcArray<T>` provides O(1) random-access arrays via CIP-138 builtins. Use `list.toArray()` to convert a list.

---

### 4.4 `Cannot resolve type: <type>`

**Cause:** A type expression could not be resolved to any known PIR type.

**Fix:** Check the type name for typos and ensure it is defined or imported.

---

### 4.5 `Duplicate record type '<name>' found across compilation units`

**Cause:** Two or more compilation units define a record with the same name.

**Fix:** Rename one of the records to eliminate the conflict.

---

### 4.6 `Duplicate sealed interface '<name>' found across compilation units`

**Cause:** Two or more compilation units define a sealed interface with the same
name.

**Fix:** Rename one of the sealed interfaces.

---

### 4.7 `Circular type dependency detected among: <types>`

**Cause:** The type registrar found a circular dependency when topologically sorting
types. Type A depends on type B, which depends on type A.

**Fix:** Break the circular dependency by restructuring your types. Use `PlutusData` as
an opaque type to break cycles if needed.

---

### 4.8 `JULC0055` `Cannot resolve <member> on a value whose type is not known here`

**Cause:** `x.name()` or `x.name` was used on a value whose record, ledger or library type the
compiler does not know, for example a `PlutusData` or an untyped `var`. Earlier releases bound such a call
to whatever variable or helper method happened to be named `name`, which is not Java's
meaning.

**Fix:** Give the receiver its record or ledger type, or call a static helper as `helper(x)`.

```java
// WRONG: PlutusData has no amount()
static BigInteger amount(PlutusData d) { return Builtins.unIData(d); }
return d.amount();

// CORRECT
return amount(d);
```

---

### 4.9 `JULC0078` `<call> compares <type> with <type>, which Java keeps distinct: on-chain the two can compare equal`

**Example message:** `PubKeyHash.equals compares org.julclang.ledger.PubKeyHash with org.julclang.core.PlutusData, which Java keeps distinct: on-chain the two can compare equal`

**Cause:** An `equals` call, or a collection operation that compares with `equals`
(`contains`, `get`, `containsKey`, ...), between two unrelated types. On the JVM the answer is
always `false`; on-chain a comparison reads representations, so a `PubKeyHash` and a `PlutusData`
holding the same bytes, or two records with the same fields, could compare equal.

**Fix:** Compare two values of one type: decode the datum or redeemer to its record first, or
compare the components.

```java
// WRONG: sig is a PubKeyHash, redeemer a PlutusData
static boolean validate(PlutusData redeemer, ScriptContext ctx) {
    boolean found = false;
    for (var sig : ctx.txInfo().signatories()) {
        if (sig.equals(redeemer)) {
            found = true;
        }
    }
    return found;
}

// CORRECT: the redeemer is a record holding a PubKeyHash
record Claim(PubKeyHash signer) {}

static boolean validate(Claim redeemer, ScriptContext ctx) {
    boolean found = false;
    for (var sig : ctx.txInfo().signatories()) {
        if (sig.equals(redeemer.signer())) {
            found = true;
        }
    }
    return found;
}
```

---

### 4.10 `JULC0090` `String concatenation <expression> has a non-String operand of type <type>`

**Example message:** `String concatenation "redeemer: " + redeemer has a non-String operand of type java.math.BigInteger`

**Cause:** `"a" + 1` is valid Java, but JuLC lowers a string `+` to `appendString`, which fails
at run time on an integer, so the script would fail where the Java code returns.

**Fix:** Concatenate `String`s only.

```java
// WRONG
ContextsLib.trace("redeemer: " + redeemer);

// CORRECT
ContextsLib.trace("checking the redeemer");
```

---

### 4.11 `JULC0091` `<position> needs a Data encoding of the native array <type>, which has none`

**Example message:** `Component 'values' of record Prices needs a Data encoding of the native array Array[Integer], which has none`

**Cause:** A `JulcArray` is a native UPLC array, which has no Data encoding. A record or
sealed-variant component, a type argument of `Optional`, `Tuple2`, `Tuple3`, `JulcList`, `JulcMap`
or `JulcArray`, and a `@Param` are Data-encoded, so an array cannot be stored there.

**Fix:** Store a `JulcList`, and build the array where it is used.

```java
// WRONG
record Prices(JulcArray<BigInteger> values) {}
Prices p = new Prices(redeemer.toArray());
return p.values().length() > 0;

// CORRECT
record Prices(JulcList<BigInteger> values) {}
Prices p = new Prices(redeemer);
JulcArray<BigInteger> values = p.values().toArray();
return values.length() > 0;
```

---

### 4.12 `JULC0092` `Sealed interface '<name>' has no permits clause, and its permitted subclasses (<types>) are not exactly the records nested in it`

**Cause:** A variant's Data tag is its position in the `permits` clause. Without one, JuLC uses
the order of the records nested in the interface. Java also permits other classes of the same
file implicitly, such as top-level records, and those have no tag under that rule.

**Fix:** Add a `permits` clause listing every variant, in tag order.

```java
// WRONG
sealed interface Shape {}
record Circle(BigInteger r) implements Shape {}
record Square(BigInteger side) implements Shape {}

// CORRECT
sealed interface Shape permits Circle, Square {}
record Circle(BigInteger r) implements Shape {}
record Square(BigInteger side) implements Shape {}
```

---

### 4.13 `JULC0093` `A string literal holds the unpaired surrogate U+<hex>, which a UPLC string cannot hold`

**Cause:** javac accepts a lone UTF-16 surrogate in a string literal, but a UPLC string is
Unicode text, so the script would hold a different string from the Java program.

**Fix:** Write a supplementary character as its high surrogate followed by its low surrogate,
or remove it.

```java
// WRONG
return redeemer.equals("\uD83D");

// CORRECT
return redeemer.equals("😀");
```

---

### 4.14 `JULC0131` `PlutusData.cast() needs a class literal as its second argument`

**Cause:** The target type of `PlutusData.cast` must be known when the script is compiled, so the
second argument must be a class literal written directly, not wrapped in parentheses or computed.

**Fix:** Write the class literal directly.

```java
// WRONG
Bid bid = PlutusData.cast(redeemer, (Bid.class));

// CORRECT
Bid bid = PlutusData.cast(redeemer, Bid.class);
```

---

## 5. Runtime Errors

These errors occur when evaluating compiled UPLC programs on the Plutus VM.

### 5.1 `No JulcVmProvider found. Add julc-vm-scalus or julc-vm-java to your classpath.`

**Cause:** The `JulcVm.create()` method could not find any VM provider via
`ServiceLoader`. No VM backend JAR is on the classpath.

**Fix:** Add a VM backend dependency to your project.

```groovy
// build.gradle
dependencies {
    // the default VM; julc-vm-scalus is an alternative
    testRuntimeOnly 'org.julclang:julc-vm-java:<version>'
}
```

---

### 5.2 `EvalResult.Failure`

**Cause:** The UPLC program evaluated to an error term. This typically means the
validator returned `false` or hit a `Builtins.error()` call. The `error` field
contains a description of the failure.

**Fix:** Check the trace messages via `result.traces()` for debugging output.
Ensure your validator logic returns `true` for valid transactions. Add
`ContextsLib.trace("message")` calls to narrow down the failure point.

---

### 5.3 `EvalResult.BudgetExhausted`

**Cause:** The UPLC program exceeded the allowed execution budget (CPU steps
and/or memory units). This happens when the script is too expensive.

**Fix:**
- Simplify your validator logic
- Reduce the number of loop iterations
- Use `break` in for-each loops to exit early
- Avoid deeply nested data structure traversals
- Check `result.budgetConsumed()` to understand where the budget went
- Consider splitting logic across multiple transactions

---

### 5.4 Script too large for on-chain submission

**Cause:** The compiled UPLC program serialized to CBOR exceeds the protocol
parameter `maxTxSize` (currently ~16 KB for the script itself). This is not a
compiler error but a chain-level constraint.

**Fix:**
- Remove unused helper methods (they are included in the script)
- Use stdlib methods from `julc-stdlib` (compiled once, shared via reference)
- Simplify logic, reduce the number of record types and sealed interfaces
- Use `@Param` fields to move configuration data off-chain (applied at deployment)

---

## 6. Common Mistakes

### Q: Why does my validator always return false?

**A:** It is not a missing `return`: a method that can end without one does not compile. javac reports
`JULC0085` `missing return statement (compiler.err.missing.ret.stmt)`.

```java
// WRONG: javac rejects this, because the method can end without a return
@Entrypoint
public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
    TxInfo txInfo = ctx.txInfo();
    if (txInfo.signatories().isEmpty()) {
        return false;
    }
}

// CORRECT: every path returns
@Entrypoint
public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
    TxInfo txInfo = ctx.txInfo();
    return !txInfo.signatories().isEmpty();
}
```

A validator that compiles and rejects every transaction usually checks something the transaction does not
hold: a signer missing from `signatories()`, an output paid to another address, or a datum or redeemer whose
fields are in a different order from the record. Trace each check (see
[How do I debug a failing validator?](#q-how-do-i-debug-a-failing-validator)) to find the one that fails, and build
the test's script context with exactly the values that check reads.

---

### Q: Why do I get "Undefined variable" for a variable I just declared?

**A:** Variables are scoped to the block they are declared in. If you declare a
variable inside an `if` block, it is not visible outside.

```java
if (condition) {
    var value = BigInteger.ONE;
}
return value; // ERROR: "Undefined variable: value"

// CORRECT: declare before the if
var value = BigInteger.ZERO;
if (condition) {
    // use value, but cannot reassign outside of a loop accumulator
}
return value;
```

---

### Q: Why can I not reassign a variable?

**A:** On-chain variables are immutable. Reassignment is only supported inside
for-each and while loops as the accumulator pattern. Outside of loops, create a
new variable with a different name.

```java
// WRONG
var x = BigInteger.ONE;
x = BigInteger.TWO; // JULC0061: Reassigning 'x' is not supported outside a loop

// CORRECT: use a new variable
var x = BigInteger.ONE;
var y = BigInteger.TWO;
```

---

### Q: Why does `BigInteger.valueOf(42)` not work?

**A:** `BigInteger.valueOf(n)` is actually supported by the compiler. It translates
the argument directly to a UPLC integer. If you see an error, check that the
argument is a literal, not a variable.

```java
var x = BigInteger.valueOf(42);        // WORKS
var y = BigInteger.valueOf(someVar);   // WORKS (someVar must be in scope)
var z = new BigInteger("12345");       // WORKS (string literal required)
var w = new BigInteger(someString);    // ERROR: requires a string literal
```

---

### Q: How do I use `List.of(...)` on-chain?

**A:** `List.of()` is not directly supported. Use `Builtins.mkNilData()` to create
an empty list and `Builtins.mkCons()` to prepend elements.

```java
// Create an empty list
var empty = Builtins.mkNilData();

// Prepend an element
var list = Builtins.mkCons(Builtins.iData(BigInteger.ONE), empty);
```

---

### Q: Why does my library class get "Unknown method" errors?

**A:** Library methods must be called with their class name qualifier.

```java
// WRONG (inside a different class)
var result = myHelper(arg);

// CORRECT
var result = MyLib.myHelper(arg);
```

If the library is a separate file, ensure it is annotated with `@OnchainLibrary`
so it can be auto-discovered from the classpath via
`META-INF/plutus-sources/`.

---

### Q: How do I debug a failing validator?

**A:** Use `ContextsLib.trace()` to emit messages during evaluation. Trace messages
are collected in `EvalResult.traces()`.

```java
static boolean signedBy(TxInfo txInfo, PubKeyHash owner) {
    return txInfo.signatories().contains(owner);
}

@Entrypoint
public static boolean validate(OwnerDatum datum, PlutusData redeemer, ScriptContext ctx) {
    TxInfo txInfo = ctx.txInfo();
    ContextsLib.trace("got txInfo");

    if (!signedBy(txInfo, datum.owner())) {
        ContextsLib.trace("owner did not sign");
        return false;
    }

    ContextsLib.trace("all checks passed");
    return true;
}
```

`OwnerDatum` is the validator's datum record, for example `record OwnerDatum(PubKeyHash owner) {}`.

---

### Q: Why do I get "Circular type dependency detected"?

**A:** Records that reference each other cannot be topologically sorted. Break the
cycle by using `PlutusData` as an intermediate opaque type.

```java
// WRONG: circular
public record A(B other) {}
public record B(A other) {}

// CORRECT: break cycle with PlutusData
public record A(PlutusData other) {}  // other is opaque Data; decode it with PlutusData.cast(other, B.class)
public record B(A parent) {}
```

---

### Q: Why does my switch expression fail with a compiler error about missing cases?

**A:** The compiler enforces exhaustiveness for switch expressions on sealed interfaces. The `default ->` branch acts as a catch-all for any uncovered variants — its body is compiled and used for all variants not explicitly listed. However, for clarity and safety, prefer explicit cases for ALL variants rather than relying on `default`.

```java
// OK but not recommended: default catches unlisted variants
var result = switch (action) {
    case Bid b -> b.amount();
    default -> BigInteger.ZERO;  // compiled — covers Cancel and any other variants
};

// RECOMMENDED: explicit cases for all variants
var result = switch (action) {
    case Bid b -> b.amount();
    case Cancel c -> BigInteger.ZERO;
};
```

If you omit both a `default` branch and an explicit case, the compiler emits an error listing the missing variants.

---

### Q: Why does `pk.hash().hash()` crash with a DeserializationError?

**A:** Types like `PubKeyHash`, `TxId`, `ScriptHash`, and `DatumHash` map to `ByteStringType`. The field access `pk.hash()` already generates `UnBData(HeadList(...))` to extract the raw ByteString. Calling `.hash()` again applies a second `UnBData` on the already-unwrapped ByteString, causing `UnBData(ByteString)` to fail.

```java
// WRONG: double unwrap
byte[] h = pk.hash().hash();

// CORRECT: PlutusData.cast() (recommended)
byte[] h = PlutusData.cast(pk.hash(), byte[].class);

// CORRECT: double-cast (also works)
byte[] h2 = (byte[])(Object) pk.hash();

// CORRECT for TxId:
byte[] txHash = PlutusData.cast(ref.txId(), byte[].class);
```

---

### Q: Why can't I use `switch` on a `Tuple2` or `Tuple3`?

**A:** `Tuple2` and `Tuple3` are registered as `RecordType`, but `switch` requires `SumType` (sealed interface). Use field access instead:

```java
// WRONG: Tuple2 is not switchable
var result = switch (tuple) { ... };

// CORRECT: use field access
BigInteger first = tuple.first();
BigInteger second = tuple.second();
```

---

### Q: Why can't I access typed elements from a `list.map(...)` result?

**A:** The `map` HOF wraps each lambda result to `Data`, so the returned list is
`JulcList<PlutusData>` regardless of input element type. Use `Builtins.unIData()`
or `Builtins.unBData()` to extract the typed value:

```java
var mapped = amounts.map(x -> x.multiply(BigInteger.TWO));
// WRONG: mapped.head() returns PlutusData, not BigInteger
// CORRECT:
BigInteger first = Builtins.unIData(mapped.head());
```

---

### Q: Can I call `foldl` as an instance method on a list?

**A:** No. `foldl` is not registered as an instance method because it takes two lambda
parameters plus an initial value, which makes instance-call syntax ambiguous. The intrinsic
`ListsLib.foldl` has no Java declaration, so javac rejects it. Write a fold as an accumulator
loop.

---

### Q: Why does `Value.assetOf(policyId, tokenName)` fail?

**A:** `Value.assetOf()` uses `EqualsData` internally. If `policyId`/`tokenName` are `byte[]` (ByteStringType), you must wrap with `Builtins.bData()` before passing. Otherwise `EqualsData(BData(...), ByteString(...))` fails.

```java
// WRONG: passing raw byte[] to assetOf
byte[] policy = ...;
byte[] token = ...;
BigInteger amount = value.assetOf(policy, token);  // DeserializationError

// CORRECT: wrap with bData
BigInteger amount = value.assetOf(Builtins.bData(policy), Builtins.bData(token));
```

---

### Q: Why does my cross-library call with `BytesData` variables fail?

**A:** When calling a stdlib library method that takes `BytesData`/`MapData` typed parameters, if the caller also has a variable of the same type, the compiler sees matching types and skips conversion. But compiled libraries expect raw Data args at the UPLC boundary.

```java
// WRONG: BytesData variable matches library parameter type
PlutusData.BytesData myPolicy = ...;
long amount = ValuesLib.assetOf(value, myPolicy, tokenName);  // type mismatch at UPLC level

// CORRECT: use PlutusData typed variables
PlutusData myPolicy = ...;
long amount = ValuesLib.assetOf(value, myPolicy, tokenName);
```

---

### Q: Why does `@Param PlutusData.BytesData` cause issues?

**A:** `@Param` values are ALWAYS raw Data at runtime, regardless of the declared type. Using `PlutusData.BytesData` tells the compiler the value is already a ByteString, causing double-wrapping and incorrect cross-library calls.

```java
// WRONG
@Param PlutusData.BytesData myPolicyId;

// CORRECT — always use PlutusData for @Param
@Param PlutusData myPolicyId;
```

---

### Q: What annotations are available and when should I use each?

| Annotation | Purpose | Entrypoint params |
|---|---|---|
| `@SpendingValidator` | Validates spending from a script address | 2 (redeemer, ctx) or 3 (datum, redeemer, ctx) |
| `@MintingValidator` | Validates minting/burning tokens | 2 (redeemer, ctx) |
| `@WithdrawValidator` | Validates reward withdrawals | 2 (redeemer, ctx) |
| `@CertifyingValidator` | Validates certificate actions | 2 (redeemer, ctx) |
| `@VotingValidator` | Validates governance votes | 2 (redeemer, ctx) |
| `@ProposingValidator` | Validates governance proposals | 2 (redeemer, ctx) |
| `@MultiValidator` | Multi-purpose validator (multiple script purposes) | Multiple `@Entrypoint` methods with `Purpose`, or single DEFAULT |
| `@Entrypoint` | Marks the main validator method | - |
| `@Entrypoint(purpose = Purpose.MINT)` | Purpose-specific entrypoint in `@MultiValidator` | - |
| `@Param` | Marks a parameterized field (applied at deployment) | - |
| `@OnchainLibrary` | Marks a reusable library class (auto-discovered) | - |

---

### Q: Duplicate purpose in @MultiValidator

**A:** Two `@Entrypoint` methods in a `@MultiValidator` class have the same `Purpose` value. Each purpose can only have one handler.
The error is `JULC0138`: `Duplicate purpose MINT in @MultiValidator BadValidator`.

```java
import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.MultiValidator;
import org.julclang.stdlib.annotation.Purpose;
// WRONG: duplicate MINT
@MultiValidator
public class BadValidator {
    @Entrypoint(purpose = Purpose.MINT)
    static boolean mint1(PlutusData redeemer, ScriptContext ctx) { return true; }

    @Entrypoint(purpose = Purpose.MINT)  // ERROR: duplicate purpose MINT
    static boolean mint2(PlutusData redeemer, ScriptContext ctx) { return true; }
}

// CORRECT: one handler per purpose
@MultiValidator
public class GoodValidator {
    @Entrypoint(purpose = Purpose.MINT)
    static boolean mint(PlutusData redeemer, ScriptContext ctx) { return true; }

    @Entrypoint(purpose = Purpose.SPEND)
    static boolean spend(PlutusData redeemer, ScriptContext ctx) { return true; }
}
```

---

### Q: Cannot mix DEFAULT and explicit purposes in @MultiValidator

**A:** You are combining `Purpose.DEFAULT` (manual dispatch) with explicit purposes like `Purpose.MINT` (auto-dispatch). Choose one mode.
The error is `JULC0136`: `@MultiValidator BadValidator mixes DEFAULT and explicit purposes`. Two `@Entrypoint` methods
that both use `DEFAULT` are `JULC0137`: `@MultiValidator BadValidator has multiple @Entrypoint methods with DEFAULT purpose`;
keep one, or give each an explicit purpose.

```java
import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.MultiValidator;
import org.julclang.stdlib.annotation.Purpose;
// WRONG: mixing DEFAULT and MINT
@MultiValidator
public class BadValidator {
    @Entrypoint  // DEFAULT
    static boolean validate(PlutusData redeemer, ScriptContext ctx) { return true; }

    @Entrypoint(purpose = Purpose.MINT)  // ERROR: cannot mix
    static boolean mint(PlutusData redeemer, ScriptContext ctx) { return true; }
}

// CORRECT: all explicit
@MultiValidator
public class GoodValidator {
    @Entrypoint(purpose = Purpose.MINT)
    static boolean mint(PlutusData redeemer, ScriptContext ctx) { return true; }

    @Entrypoint(purpose = Purpose.SPEND)
    static boolean spend(PlutusData redeemer, ScriptContext ctx) { return true; }
}
```

---

### Q: Multiple validator annotations on the same class

**A:** A class cannot have both `@MultiValidator` and a single-purpose annotation (like `@SpendingValidator`). Use one or the other.
The error is `JULC0135`: `Class BadValidator has multiple validator annotations: @SpendingValidator, @MultiValidator`.

```java
import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.MultiValidator;
import org.julclang.stdlib.annotation.Purpose;
import org.julclang.stdlib.annotation.SpendingValidator;
// WRONG
@SpendingValidator
@MultiValidator
public class BadValidator { ... }

// CORRECT: use @MultiValidator for multi-purpose
@MultiValidator
public class GoodValidator {
    @Entrypoint(purpose = Purpose.SPEND)
    static boolean spend(PlutusData redeemer, ScriptContext ctx) { return true; }

    @Entrypoint(purpose = Purpose.MINT)
    static boolean mint(PlutusData redeemer, ScriptContext ctx) { return true; }
}
```

---

### Q: Invalid parameter count for multi-validator entrypoint

**A:** In a `@MultiValidator`, SPEND entrypoints accept 2 or 3 parameters. All other purposes accept exactly 2 parameters.

```java
// WRONG: MINT with 3 params
@Entrypoint(purpose = Purpose.MINT)
static boolean mint(PlutusData datum, PlutusData redeemer, ScriptContext ctx) { ... }
// ERROR: MINT entrypoint must have 2 parameters

// CORRECT: MINT with 2 params
@Entrypoint(purpose = Purpose.MINT)
static boolean mint(PlutusData redeemer, ScriptContext ctx) { ... }

// CORRECT: SPEND with 3 params (datum)
@Entrypoint(purpose = Purpose.SPEND)
static boolean spend(Optional<PlutusData> datum, PlutusData redeemer, ScriptContext ctx) { ... }
```

### Q: Why does my validator fail at a cast to a variant?

A cast from a sealed interface to one of its record variants, such as
`(ScriptInfo.MintingScript) ctx.scriptInfo()` or `(Withdraw) redeemer`, tests the variant on-chain
and fails the script on any other, as Java throws `ClassCastException`
([#288](https://github.com/bloxbean/julc/issues/288)). Before this check the cast read the other
variant's fields and carried on. If the value can legitimately be another variant, branch instead:

```java
if (redeemer instanceof Withdraw w) { ... }        // or: switch (redeemer) { case Withdraw w -> ...; }
```

A cast from a type JuLC cannot relate to the sealed interface remains an unchecked view, as before:
`(V)(Object) x`, `PlutusData.cast(x, V.class)`, casts to `PlutusData`'s own variants, and an operand of
an unbounded type variable or an open super-interface.

### Q: Why do my Java tests fail with `VerifyError: Inconsistent stackmap frames`?

This is a known javac bug, not a JuLC one: javac in JDK 21 to 25.0.2 can emit a class the JVM
refuses to load. JDK 25.0.3 and later compile it correctly. It is triggered when a method has:

- an `if` whose condition declares two pattern variables, through `||`, `&&` or `?:`, for
  example `if (p instanceof C c || p instanceof B b)`, and
- later, a pattern `switch` whose arm copies its case variable into a local and loops over an
  `if (p instanceof ...)`.

JuLC compiles such a method correctly for the chain, but your Java unit tests of it fail to
load. Use JDK 25.0.3 or later to run your tests, or avoid the shape:

- declare one pattern variable per condition, nesting a second `if` instead of `||`/`&&`, or
- move the `switch` into a helper method of its own.

## Retired codes

These codes are retired and never reused. No compilation reports them. `JULC0084` said the javac frontend had not ported a
construct, and `JULC0100` that a name read by its spelling bound to something else; both went with the JavaParser frontend,
which has been removed. `JULC0005`, `JULC0071`, `JULC0073` and `JULC0082` covered a switch that is not exhaustive, a dominated label, a guard's
variable that is reassigned and a second `default`: javac rejects each of those itself, and you see `JULC0085` with javac's key.
