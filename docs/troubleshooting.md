# JuLC Compiler Troubleshooting Guide

This guide maps every compiler, validation, configuration, and runtime error to its
cause and solution. All error messages shown here are taken directly from the JuLC
source code.

---

## Table of Contents

1. [Compilation Errors (PirGenerator)](#1-compilation-errors-pirgenerator)
2. [Validation Errors (SubsetValidator)](#2-validation-errors-subsetvalidator)
3. [Configuration Errors (JulcCompiler)](#3-configuration-errors-julccompiler)
4. [Type Resolution Errors](#4-type-resolution-errors)
5. [Runtime Errors](#5-runtime-errors)
6. [Common Mistakes](#6-common-mistakes)

---

## 1. Compilation Errors (PirGenerator)

These errors are emitted during PIR (Plutus Intermediate Representation) generation,
when the compiler translates Java AST nodes into UPLC-compatible terms.

### 1.1 `Method must have a body: <name>`

**Cause:** An abstract or interface method was encountered where the compiler
expects a concrete method body.

**Fix:** Ensure every method in your validator class has a full body. On-chain code
cannot contain abstract methods.

```java
// WRONG: abstract method
@SpendingValidator
public class MyValidator {
    @Entrypoint
    public abstract boolean validate(Data redeemer, ScriptContext ctx);
}

// CORRECT: method with body
@SpendingValidator
public class MyValidator {
    @Entrypoint
    public static boolean validate(Data redeemer, ScriptContext ctx) {
        return true;
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

### 1.5 `Unsupported statement in break-aware loop body: <StatementType>`

**Hint:** `Inside loops with break, only variable declarations, assignments, if/else,
and break are supported.`

**Cause:** A loop body that contains `break` used an unsupported statement type.
When a loop body contains `break`, the compiler uses a specialized translation
path that supports a narrower set of statements.

**Fix:** Simplify the loop body to contain only variable declarations, accumulator
assignments, if/else, and break.

```java
// WRONG: nested for-each inside a break-aware loop body
for (var item : items) {
    for (var sub : item.children()) { // not supported inside break-aware body
        if (condition) break;
    }
}

// CORRECT: nested for-each without break is supported
for (var item : items) {
    for (var sub : item.children()) { // OK — no break in outer loop
        sum = sum + sub.amount();
    }
}

// CORRECT: use a helper method for the break pattern
var found = false;
for (var item : items) {
    if (checkCondition(item)) {
        found = true;
        break;
    }
}
```

Note: Nested loops (while-in-while, for-each-in-for-each, mixed) are fully supported. The restriction is specifically about nesting inside a **break-aware** loop body — when the outer loop uses `break`, the inner statements must be limited to declarations, assignments, if/else, and break.

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

**Hint:** `instanceof is supported only with sealed interface variants: if (x
instanceof Variant v) { ... }`

**Cause:** `instanceof` was used with a type that is not a variant of a sealed
interface, or the pattern form is not supported.

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
    if (x > 0) {
        return true;
    }
    // no return here
}

// CORRECT
static boolean check(BigInteger x) {
    if (x > 0) {
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
// WRONG: two fields
@NewType
record AssetId(byte[] policy, byte[] name) {}

// CORRECT: single field
@NewType
record PolicyId(byte[] hash) {}
```

---

### 1.17 `Unsupported lambda body: <BodyType>`

**Hint:** `Lambda bodies must be a single expression or a block statement.`

**Cause:** A lambda expression has an unexpected body form.

**Fix:** Use either a single expression or a block body.

```java
// CORRECT: expression body
var doubled = ListsLib.map(items, item -> item * 2);

// CORRECT: block body
var filtered = ListsLib.filter(items, item -> {
    var threshold = new BigInteger("100");
    return item > threshold;
});
```

---

### 1.15 `Unknown method: <methodName>`

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

### 1.16 `Unsupported operator: <operator>`

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

### 1.17 `Unsupported unary operator: <operator>`

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

### 1.18 `Unsupported expression: AssignExpr`

**Hint:** `Variables are immutable on-chain. Use the accumulator pattern in
for-each/while loops for mutable state.`

**Cause:** An assignment expression was used outside of the accumulator pattern
in a loop. On-chain UPLC does not support mutation.

**Fix:** Variables are immutable. Use the accumulator pattern in for-each or while
loops.

```java
// WRONG: assignment outside loop accumulator
var x = BigInteger.ZERO;
x = BigInteger.ONE; // not inside a loop accumulator context

// CORRECT: accumulator pattern in for-each
var total = BigInteger.ZERO;
for (var item : items) {
    total = total + item.value(); // accumulator assignment in loop
}
```

---

### 1.19 `Unsupported expression: <ExpressionType>`

**Hint:** `This expression type is not supported in on-chain code.`

**Cause:** A Java expression type that has no on-chain compilation path was used.

**Fix:** Rewrite using the supported expression forms: literals, variables, binary
and unary expressions, method calls, field access, record construction, ternary
expressions, lambdas, switch expressions, instanceof, and casts.

---

### 1.20 `Undefined variable: <name>`

**Cause:** A variable name was used that is not in scope. This is thrown by
`SymbolTable.require()` when no matching variable definition is found.

**Fix:** Ensure the variable is declared before use, or check for typos.

```java
// WRONG: typo
var totl = BigInteger.ZERO;
return total; // "total" not defined, "totl" is

// CORRECT
var total = BigInteger.ZERO;
return total;
```

---

## 2. Validation Errors (SubsetValidator)

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
Data result = null;

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

**Fix:** Use `List<T>` instead.

```java
// WRONG
var data = new byte[32];

// CORRECT
// Use ByteString for byte arrays, or List<T> for element lists
```

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

## 3. Configuration Errors (JulcCompiler)

These errors are raised during compiler setup and pipeline orchestration.

### 3.1 `Failed to parse <label> source: <details>`

**Cause:** The Java source code has syntax errors that JavaParser cannot parse.
`<label>` is either `"validator"` or `"library[n]"`.

**Fix:** Ensure the source compiles with `javac` first. Common causes: missing
semicolons, unmatched braces, invalid Java 21 syntax.

---

### 3.2 `No validator annotation found (e.g. @SpendingValidator, @MintingValidator)`

**Cause:** The validator source file does not contain any class annotated with a
validator annotation.

**Fix:** Annotate your validator class with one of the recognized annotations.

```java
// WRONG: no annotation
public class MyValidator {
    public static boolean validate(Data redeemer, ScriptContext ctx) {
        return true;
    }
}

// CORRECT
@SpendingValidator
public class MyValidator {
    @Entrypoint
    public static boolean validate(Data datum, Data redeemer, ScriptContext ctx) {
        return true;
    }
}
```

**Recognized annotations:** `@Validator`, `@SpendingValidator`, `@MintingPolicy`,
`@MintingValidator`, `@WithdrawValidator`, `@CertifyingValidator`,
`@VotingValidator`, `@ProposingValidator`

---

### 3.3 `No @Entrypoint method found in <ClassName>`

**Cause:** The annotated validator class does not contain a method annotated with
`@Entrypoint`.

**Fix:** Add `@Entrypoint` to the validator's main method.

```java
@SpendingValidator
public class MyValidator {
    // WRONG: missing @Entrypoint
    public static boolean validate(Data datum, Data redeemer, ScriptContext ctx) {
        return true;
    }
}

@SpendingValidator
public class MyValidator {
    @Entrypoint  // CORRECT
    public static boolean validate(Data datum, Data redeemer, ScriptContext ctx) {
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
public static boolean validate(Data redeemer, ScriptContext ctx) { ... }

// CORRECT: 3 parameters (explicit datum)
@Entrypoint
public static boolean validate(Data datum, Data redeemer, ScriptContext ctx) { ... }
```

---

### 3.5 `@MintingValidator/@MintingPolicy entrypoint must have 2 parameters`

**Full message:** `<annotation> entrypoint must have 2 parameters (redeemer,
scriptContext), found <n> in <Class>.<method>(). Did you mean
@SpendingValidator?`

**Cause:** A non-spending validator entrypoint does not have exactly 2 parameters.
If you specified 3 parameters, the compiler asks if you meant to use
`@SpendingValidator`.

**Fix:** Use 2 parameters for non-spending validators, or switch to
`@SpendingValidator` if you need a datum parameter.

```java
// WRONG: 3 params on a minting policy
@MintingPolicy
public class MyPolicy {
    @Entrypoint
    public static boolean validate(Data datum, Data redeemer, ScriptContext ctx) { ... }
    // Error: "Did you mean @SpendingValidator?"
}

// CORRECT
@MintingPolicy
public class MyPolicy {
    @Entrypoint
    public static boolean validate(Data redeemer, ScriptContext ctx) { ... }
}
```

---

### 3.6 `Library source must not contain @<Annotation>: <ClassName>`

**Cause:** A source file passed as a library (not the validator source) contains a
validator annotation. Library files must not be validators.

**Fix:** Remove the validator annotation from library classes, or pass the file as
the validator source instead.

```java
// WRONG: library file with validator annotation
@OnchainLibrary
@MintingPolicy  // this is not allowed on a library
public class HelperLib { ... }

// CORRECT
@OnchainLibrary
public class HelperLib { ... }
```

---

## 4. Type Resolution Errors

These errors are raised by the `TypeResolver` and `TypeRegistrar` during type
registration and resolution.

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

**Fix:** Break the circular dependency by restructuring your types. Use `Data` as
an opaque type to break cycles if needed.

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
    implementation 'com.bloxbean.cardano:julc-vm-scalus:<version>'
}
```

---

### 5.2 `EvalResult.Failure`

**Cause:** The UPLC program evaluated to an error term. This typically means the
validator returned `false` or hit a `Builtins.error()` call. The `error` field
contains a description of the failure.

**Fix:** Check the trace messages via `result.traces()` for debugging output.
Ensure your validator logic returns `true` for valid transactions. Add
`Builtins.trace("message", ...)` calls to narrow down the failure point.

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

**A:** The most common cause is a missing `return true` at the end of the
entrypoint method. In UPLC, if your code falls through without returning `true`,
the default result is unit/void, which is treated as failure.

```java
@Entrypoint
public static boolean validate(Data redeemer, ScriptContext ctx) {
    // Do some checks...
    var txInfo = ContextsLib.getTxInfo(ctx);
    // WRONG: forgot to return true
}

@Entrypoint
public static boolean validate(Data redeemer, ScriptContext ctx) {
    var txInfo = ContextsLib.getTxInfo(ctx);
    // checks...
    return true; // CORRECT: explicit success
}
```

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
x = BigInteger.TWO; // CompilerException: Unsupported expression: AssignExpr

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

**A:** Use `Builtins.trace()` to emit messages during evaluation. Trace messages
are collected in `EvalResult.traces()`.

```java
@Entrypoint
public static boolean validate(Data redeemer, ScriptContext ctx) {
    var txInfo = ContextsLib.getTxInfo(ctx);
    Builtins.trace("got txInfo");

    if (!checkSomething(txInfo)) {
        Builtins.trace("checkSomething failed");
        return false;
    }

    Builtins.trace("all checks passed");
    return true;
}
```

---

### Q: Why do I get "Circular type dependency detected"?

**A:** Records that reference each other cannot be topologically sorted. Break the
cycle by using `Data` as an intermediate opaque type.

```java
// WRONG: circular
public record A(B other) {}
public record B(A other) {}

// CORRECT: break cycle with Data
public record A(Data other) {}  // other is opaque Data, cast as needed
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

**A:** No. `foldl` is only available as a static call: `ListsLib.foldl(f, init, list)`.
It is not registered as an instance method because it takes two lambda parameters
plus an initial value, which makes instance-call syntax ambiguous.

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
| `@SpendingValidator` / `@Validator` | Validates spending from a script address | 2 (redeemer, ctx) or 3 (datum, redeemer, ctx) |
| `@MintingPolicy` / `@MintingValidator` | Validates minting/burning tokens | 2 (redeemer, ctx) |
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

```java
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

```java
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

```java
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
