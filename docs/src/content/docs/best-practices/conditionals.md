---
title: "Conditionals and Script Size"
description: "How to write clear and compact if/else and switch expressions in JuLC contracts"
---

JuLC supports familiar Java conditionals: `if`, `else if`, `else`, the ternary
operator, and exhaustive `switch` expressions. You do not need to rewrite normal,
readable Java just because the contract eventually runs as UPLC.

There is one on-chain detail worth knowing: code after a conditional may become part
of every generated branch that can reach it. Usually this is small and harmless. If
the shared code is large, however, the generated script can become larger than the
Java source suggests.

This page shows how to keep validation code readable while avoiding unnecessary
script size and execution cost.

## Short version

- An `if` does **not** need an `else`.
- Nested `if` statements work, including several levels of nesting.
- Code after a nested `if` runs only when execution reaches it, just as it does in Java.
- Prefer a sequence of guard clauses for independent validation rules.
- Put cheap checks before expensive checks so failures stop early.
- Keep large common work outside branch bodies.
- If several branches reach the same large block, extract that block into a helper method.
- Prefer exhaustive `switch` expressions for sealed types such as redeemers.
- Measure script size and execution budget; do not optimize based only on appearance.

## `else` is optional

This is valid JuLC:

```java
static boolean validate(BigInteger amount) {
    if (amount.compareTo(BigInteger.ZERO) <= 0) {
        return false;
    }

    return true;
}
```

When the condition is false, execution continues with `return true`. JuLC preserves
that Java behavior when it creates the UPLC program.

The same applies to nested conditionals:

```java
static boolean validate(BigInteger a, BigInteger b) {
    if (a.compareTo(BigInteger.ZERO) > 0) {
        if (b.compareTo(BigInteger.ZERO) <= 0) {
            return false;
        }
    } else {
        return false;
    }

    return true;
}
```

This returns `true` only when both `a > 0` and `b > 0`. The inner `if` does not need
an `else`: when `b > 0`, execution continues after the outer `if`.

Four or more nesting levels are also supported. Deep nesting is mainly a readability
concern; it is not necessary to add artificial `else` branches for the compiler.

## Prefer guard clauses for validation rules

A validator often checks several independent rules. A linear sequence is usually the
clearest way to express them:

```java
record PaymentDatum(byte[] owner, BigInteger amount) {}

static boolean validate(PaymentDatum datum, ScriptContext ctx) {
    TxInfo txInfo = ctx.txInfo();

    if (datum.amount().compareTo(BigInteger.ZERO) <= 0) {
        return false;
    }

    if (!ContextsLib.signedBy(txInfo, datum.owner())) {
        return false;
    }

    if (!hasRequiredPayment(txInfo, datum)) {
        return false;
    }

    return true;
}
```

This style has three useful properties:

1. Each rule is visible on its own.
2. A failed rule stops evaluation before later work.
3. There is no deeply nested success path to read.

The equivalent nested form is harder to scan:

```java
if (amountIsValid(datum)) {
    if (ownerSigned(txInfo, datum)) {
        if (hasRequiredPayment(txInfo, datum)) {
            return true;
        }
    }
}
return false;
```

Both forms can be correct, but guard clauses communicate validator intent more directly.

## Put cheap checks first

Java's `if`, `&&`, and `||` retain their short-circuit behavior in JuLC. Work that is
not reached is not evaluated.

Use that to reject invalid transactions before running expensive operations:

```java
if (amount.compareTo(BigInteger.ZERO) <= 0) {
    return false;                       // cheap integer comparison
}

if (!ContextsLib.signedBy(txInfo, owner)) {
    return false;                       // list search
}

return verifyProof(proof, expectedRoot); // expensive work last
```

The order must still preserve the contract's meaning. Do not reorder checks when one
depends on data validated by an earlier check.

## Keep common work in one place

Suppose mint and burn actions have different rules, followed by several common checks:

```java
if (isMint) {
    if (!validMint(action, txInfo)) {
        return false;
    }
} else {
    if (!validBurn(action, txInfo)) {
        return false;
    }
}

TxInfo info = ctx.txInfo();
var outputs = info.outputs();
var signatories = info.signatories();
// ...many more common calculations...
return validateCommonRules(outputs, signatories);
```

At the UPLC level there is no Java-style instruction pointer that simply moves to the
next statement. JuLC represents the remaining work as an expression—often called the
**continuation**—and connects it to every branch that can continue.

If several branches can continue into a large block, some of that generated structure
may be repeated. The result remains correct, but its serialized script can be larger.

A helper keeps the shared body in one generated binding:

```java
static boolean validate(Action action, ScriptContext ctx) {
    TxInfo txInfo = ctx.txInfo();

    if (isMint(action)) {
        if (!validMint(action, txInfo)) {
            return false;
        }
    } else {
        if (!validBurn(action, txInfo)) {
            return false;
        }
    }

    return validateCommonRules(txInfo);
}

static boolean validateCommonRules(TxInfo txInfo) {
    var outputs = txInfo.outputs();
    var signatories = txInfo.signatories();
    // ...the large common calculation exists here once...
    return outputsAreValid(outputs) && signersAreValid(signatories);
}
```

The helper call may appear on more than one generated path, but the helper's body is
defined once. This matters most when the common block is large; extracting every
two-line continuation usually makes code less readable without a meaningful size win.

## Prefer a switch expression for variants

For a sealed redeemer or another sum type, use a Java `switch` expression that produces
a value:

```java
boolean actionIsValid = switch (action) {
    case Mint mint -> validMint(mint, txInfo);
    case Burn burn -> validBurn(burn, txInfo);
};

if (!actionIsValid) {
    return false;
}

return validateCommonRules(txInfo);
```

JuLC switch expressions must be exhaustive: handle every permitted variant or provide
a `default` branch. Statement-style switches with `return` inside cases are not the
supported pattern; produce a value with a switch expression instead.

For a multi-statement case, use `yield`:

```java
boolean actionIsValid = switch (action) {
    case Mint mint -> {
        boolean positive = mint.amount().compareTo(BigInteger.ZERO) > 0;
        yield positive && validMint(mint, txInfo);
    }
    case Burn burn -> validBurn(burn, txInfo);
};
```

## Do not move unsafe work earlier

Do not move an operation before a guard merely to make the generated program look
smaller:

```java
// Risky: head() is evaluated before the empty-list check.
TxOut first = outputs.head();
if (outputs.isEmpty()) {
    return false;
}
```

Keep the guard first:

```java
if (outputs.isEmpty()) {
    return false;
}
TxOut first = outputs.head();
```

UPLC evaluation is strict. Moving `head()` earlier makes it run even for an empty list,
where it can fail. The same warning applies to division, decoding data, indexing lists,
and other operations that require validated input.

## Measure instead of guessing

Control-flow shape is only one part of script size. Compile the validator and inspect
the actual result:

```java
CompileResult compiled = ValidatorTest.compileValidator(MyValidator.class);

System.out.println(compiled.scriptSizeFormatted());
System.out.println(compiled.scriptSizeBytes());
```

For important contracts, add a regression assertion:

```java
BudgetAssertions.assertScriptSizeUnder(compiled, 16_384);
```

Measure execution budget with representative successful and failing transactions too.
A smaller script is not automatically cheaper on every execution path. See the
[Testing Guide](/guides/testing-guide/#budget-regression-test-pattern) for budget tests
and script analysis.

## Review checklist

Before optimizing a validator, ask:

- Can independent validation rules be written as guard clauses?
- Are cheap and safe rejection checks performed before expensive work?
- Does a large block follow several branches that can all continue?
- Would one well-named helper keep that common block in one place?
- Am I preserving guards before operations that can fail?
- Have I measured both serialized script size and execution budget?
