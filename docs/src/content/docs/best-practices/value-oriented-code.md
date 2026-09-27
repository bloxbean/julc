---
title: "Value-Oriented Contract Code"
description: "Compute values where they are declared, and test the compiled validator"
---

Value-oriented code computes each value where it is declared: in an initializer, as
the result of a `switch` expression, as the return value of a helper method, or with
a list operation. It avoids declaring a variable and then updating it from inside an
`if`, a switch arm, or another method.

Java itself is not the problem. JuLC must either translate a supported update
correctly or reject it at compile time. Several update shapes that are valid Java are
rejected today, and this page shows the rewrite for each one. The rewrites also make a
contract easier to review, because each value's meaning sits in one place.

Style does not make a contract correct. JuLC is an experimental compiler; test the
compiled validator, as described at the end of this page, whatever style you use.

The recommended examples on this page are real Java classes: javac compiles them, JuLC
compiles them to UPLC, and the tests in
[`julc-examples/src/test/java/org/julclang/examples/valueoriented`](https://github.com/bloxbean/julc/tree/main/julc-examples/src/test/java/org/julclang/examples/valueoriented)
evaluate them on the VM. The examples of code to avoid are compiled from this page's
text, and the tests check that JuLC rejects them or that the script fails. A test also
fails if this page stops matching the tested code.

## Short version

- Compute a conditional value with a conditional expression (`c ? a : b`) in the
  declaration, instead of assigning it inside an `if`.
- Let a `switch` expression yield its result. An arm cannot update a variable declared
  outside it.
- Copy a case-pattern binding into a fresh local before accumulating into it.
- Pass values to helper methods and return results from them. A loop may update a static
  field or `@Param` only in the entrypoint, and only when no other method reads it.
- Use `any`, `all`, `filter` and `map` where they read better, and measure their cost.
- Keep ordinary accumulator loops for sums, counts, several results in one pass, and
  early exit.
- Test the compiled validator with real ScriptContexts, not only the Java method.

The examples use these types:

```java
sealed interface Action permits Only, Deposit {}
record Only() implements Action {}
record Deposit(BigInteger base) implements Action {}
```

## Compute a conditional value in its initializer

Inside a loop, do not declare a local and then update it from an `if`:

```java
static BigInteger countPositive(JulcList<BigInteger> xs) {
    BigInteger acc = BigInteger.ZERO;
    for (var x : xs) {
        BigInteger step = BigInteger.ZERO;
        if (x.compareTo(BigInteger.ZERO) > 0) {
            step = BigInteger.ONE;
        }
        acc = acc.add(step);
    }
    return acc;
}
```

JuLC rejects this with `Conditional update to loop-body local 'step' is not supported`.
Before the fix for [#155](https://github.com/bloxbean/julc/issues/155) (closed by
[PR #157](https://github.com/bloxbean/julc/pull/157)), the update was silently lost. The
rewrite is therefore required, not a matter of taste. Compute the value in the
declaration instead:

```java
static BigInteger countPositive(JulcList<BigInteger> xs) {
    BigInteger acc = BigInteger.ZERO;
    for (var x : xs) {
        BigInteger step = x.compareTo(BigInteger.ZERO) > 0 ? BigInteger.ONE : BigInteger.ZERO;
        acc = acc.add(step);
    }
    return acc;
}
```

For `[1, 2, 3]` this returns 3, for `[-1, 0, 2]` it returns 1, and for an empty list it
returns 0.

The same applies outside loops. Outside the supported loop paths JuLC variables are
immutable, so this is rejected with `Unsupported expression: AssignExpr`:

```java
static BigInteger limitFor(boolean vip) {
    BigInteger limit = BigInteger.TEN;
    if (vip) {
        limit = BigInteger.valueOf(100);
    }
    return limit;
}
```

Write the choice as one expression:

```java
static BigInteger limitFor(boolean vip) {
    BigInteger limit = vip ? BigInteger.valueOf(100) : BigInteger.TEN;
    return limit;
}
```

`limitFor(true)` returns 100 and `limitFor(false)` returns 10.

When a conditional initializer does not fit, a loop has two other supported forms:
declare the variable before the loop so it becomes an accumulator, or declare it inside
the branch when only that branch needs it. The
[loop-local assignment rules](/best-practices/conditionals/#loop-local-assignment-rules)
list every supported shape.

## Yield the result of a switch arm

A `switch` expression exports exactly one thing: the value its arm yields. Put the work
in the arm and yield the result:

```java
static BigInteger armTotal(Action action, JulcList<BigInteger> xs) {
    BigInteger total = switch (action) {
        case Only o -> {
            BigInteger local = BigInteger.ZERO;
            for (var x : xs) {
                local = local.add(x);
            }
            yield local;
        }
        case Deposit d -> d.base();
    };
    return total;
}
```

For `Only` and `[1, 2, 3]`, `armTotal` returns 6; for an empty list it returns 0; for
`Deposit(9)` it returns 9.

An arm cannot update a variable declared outside it, even through a loop:

```java
static BigInteger armTotal(Action action, JulcList<BigInteger> xs) {
    BigInteger total = BigInteger.ZERO;
    BigInteger ignored = switch (action) {
        case Only o -> {
            for (var x : xs) {
                total = total.add(x);
            }
            yield BigInteger.ZERO;
        }
        case Deposit d -> BigInteger.ZERO;
    };
    return total;
}
```

JuLC rejects this with `Switch-expression arm cannot update enclosing variable 'total'`.

A loop inside an `if` at method level, or inside a switch arm, does keep its
accumulator updates for the statements after the branch. That was fixed in
[#161](https://github.com/bloxbean/julc/issues/161) by
[PR #164](https://github.com/bloxbean/julc/pull/164); see
[loops inside an if](/best-practices/conditionals/#loops-inside-an-if-outside-a-loop-body).

## Copy a case-pattern binding before accumulating

Do not reassign the variable a case pattern binds:

```java
static BigInteger depositTotal(Action action, JulcList<BigInteger> xs) {
    return switch (action) {
        case Deposit d -> {
            for (var x : xs) {
                d = new Deposit(d.base().add(x));
            }
            yield d.base();
        }
        case Only o -> BigInteger.ZERO;
    };
}
```

JuLC rejects this with `Reassignment of switch case-pattern variable 'd' is not
supported` ([#162](https://github.com/bloxbean/julc/issues/162), closed by
[PR #157](https://github.com/bloxbean/julc/pull/157)). The compiler caches reads of the
fields of `d`, so after a reassignment they could still see the original record. Copy
the binding into a fresh local and update the copy:

```java
static BigInteger depositTotal(Action action, JulcList<BigInteger> xs) {
    return switch (action) {
        case Deposit d -> {
            Deposit current = d;
            for (var x : xs) {
                current = new Deposit(current.base().add(x));
            }
            yield current.base();
        }
        case Only o -> BigInteger.ZERO;
    };
}
```

For `Deposit(10)` and `[1, 2, 3]` this returns 16; with an empty list it returns 10.
When only one field accumulates, copying that field into a local is simpler still.

## Return values from helper methods

A static field or `@Param` is not shared state on-chain. A loop that updates one
rebinds the name only in the rest of that method's current run, so other methods, and
later calls of the same method, still see the original value. Java would see the update,
so JuLC accepts such an update only in the entrypoint, when nothing calls it and no other
method reads the field, and rejects anything else (`JULC0056`). Here `feeCovered` reads
the field that `paysFees` updates:

```java
static BigInteger fee = BigInteger.ZERO;

static boolean feeCovered(BigInteger paid) {
    return paid.compareTo(fee) >= 0;
}

static boolean paysFees(JulcList<BigInteger> fees, BigInteger paid) {
    for (var f : fees) {
        fee = fee.add(f);
    }
    return feeCovered(paid);
}
```

Compute the value in one helper and pass it to the next:

```java
static BigInteger totalFee(JulcList<BigInteger> fees) {
    BigInteger total = BigInteger.ZERO;
    for (var fee : fees) {
        total = total.add(fee);
    }
    return total;
}

static boolean feeCovered(BigInteger paid, BigInteger fee) {
    return paid.compareTo(fee) >= 0;
}

static boolean paysFees(JulcList<BigInteger> fees, BigInteger paid) {
    return feeCovered(paid, totalFee(fees));
}
```

`paysFees([1, 2, 3], 6)` returns true and `paysFees([1, 2, 3], 5)` returns false.
Helpers that return values also keep a large shared calculation in one place; see
[keep common work in one place](/best-practices/conditionals/#keep-common-work-in-one-place).

## Use list operations where they read better

`JulcList<T>` has these operations that take an inline lambda:

| Operation | Returns | Notes |
|-----------|---------|-------|
| `list.any(x -> ...)` | `boolean` | evaluates the predicate for every element |
| `list.all(x -> ...)` | `boolean` | evaluates the predicate for every element |
| `list.filter(x -> ...)` | `JulcList<T>` | builds a new list |
| `list.map(x -> ...)` | `JulcList<R>` | builds a new list; assign it to a typed local before using its elements |

A lambda must be passed inline. It cannot be stored in a variable and called with
`.apply()`.

Two related methods do not work in a javac-compiled validator today:

- `list.find(x -> ...)` is declared in Java to return the element, but the compiled code
  returns an optional value. Using the result as the element fails when the script runs.
  Use `any` for a yes-or-no question, or a loop that records what it finds.
- `ListsLib.any`, `ListsLib.all`, `ListsLib.filter`, `ListsLib.map`, `ListsLib.find`,
  `ListsLib.foldl` and `ListsLib.zip` compile only from source text, such as testkit
  strings or the playground. `ListsLib` does not declare them in Java, so javac rejects
  them in a Gradle project. Write a fold as an accumulator loop.

A predicate often states the rule more directly than a loop, and lambdas may nest:

```java
static boolean anyAbove(JulcList<BigInteger> xs, BigInteger limit) {
    return xs.any(x -> x.compareTo(limit) > 0);
}

static boolean allPresent(JulcList<BigInteger> required, JulcList<BigInteger> present) {
    return required.all(r -> present.any(p -> p.equals(r)));
}
```

`anyAbove([1, 5, 3], 4)` is true and `anyAbove([1, 2, 4], 4)` is false.
`allPresent([1, 3], [3, 2, 1])` is true and `allPresent([1, 4], [3, 2, 1])` is false.
Before 0.1.0-pre17, a nested lambda whose parameter shared a name with a variable of the
list operation's own implementation, such as `x`, was compiled wrongly
([PR #186](https://github.com/bloxbean/julc/pull/186)). List operations are compiler
lowerings like any other code: test them through the compiled script.

On the JVM, `JulcList.any` returns at the first match. The compiled `any` and `all`
evaluate the predicate for the whole list instead. A predicate that fails on a later
element therefore fails the script, even where the Java method would already have
returned. For `[5, 0]` this returns true on the JVM and fails on-chain:

```java
static boolean anyLargeShare(JulcList<BigInteger> parts) {
    return parts.any(p -> BigInteger.valueOf(100).divide(p).compareTo(BigInteger.TEN) > 0);
}
```

Guard the predicate with `&&`, which does stop early, so every element is safe to test:

```java
static boolean anyLargeShare(JulcList<BigInteger> parts) {
    return parts.any(p -> p.signum() > 0
            && BigInteger.valueOf(100).divide(p).compareTo(BigInteger.TEN) > 0);
}
```

This also means `any` costs a full pass over the list. When an early exit matters for
cost, use a loop with `break`.

The result of `map` needs a typed local before a lambda uses its elements:

```java
static boolean anyDoubledAbove(JulcList<BigInteger> xs, BigInteger limit) {
    JulcList<BigInteger> doubled = xs.map(x -> x.multiply(BigInteger.TWO));
    return doubled.any(y -> y.compareTo(limit) > 0);
}
```

Chaining the next operation directly onto `map` is rejected with `JULC0055`, because the
chained lambda does not know the element type:

```java
static boolean anyDoubledAbove(JulcList<BigInteger> xs, BigInteger limit) {
    return xs.map(x -> x.multiply(BigInteger.TWO)).any(y -> y.compareTo(limit) > 0);
}
```

List operations are not automatically cheaper. These two methods return the same count:

```java
static long countAbove(JulcList<BigInteger> xs, BigInteger limit) {
    return xs.filter(x -> x.compareTo(limit) > 0).size();
}

static long countAboveLoop(JulcList<BigInteger> xs, BigInteger limit) {
    long count = 0;
    for (var x : xs) {
        if (x.compareTo(limit) > 0) {
            count += 1;
        }
    }
    return count;
}
```

For ten elements, `filter(...).size()` used more than twice the CPU budget of the loop
(25.5 million against 11.7 million units with JuLC 0.1.0-pre17 and default options),
because it builds a new list and then walks it again. The tests assert that ordering.
Measure your own case with `BudgetAssertions` before choosing one form for cost.

## Keep accumulator loops where they fit

A loop is still the clearest form for a sum, a count, several results in one pass, or an
early exit with `break`:

```java
static boolean withinCap(JulcList<BigInteger> amounts, BigInteger cap) {
    BigInteger total = BigInteger.ZERO;
    long count = 0;
    for (var amount : amounts) {
        total = total.add(amount);
        count += 1;
    }
    return count > 0 && total.compareTo(cap) <= 0;
}
```

`withinCap([1, 2, 3], 6)` is true, `withinCap([1, 2, 3], 5)` is false, and an empty list
gives false. `count += 1` means `count = count + 1`; `&=`, `|=`, `^=` and the shift
compound operators are rejected (`JULC0052`).

This page does not repeat the loop rules. The canonical references are:

- [loop-local assignment rules](/best-practices/conditionals/#loop-local-assignment-rules),
  for which variables a loop, a branch or a switch arm may update;
- [for-loop patterns](/guides/for-loop-patterns/) and its
  [limitations table](/guides/for-loop-patterns/#limitations), for every supported loop
  shape.

## Test the compiled validator

Evaluate the compiled UPLC, not only the Java method: a Java test shows what the source
means, while a compiled test shows what the script does. This complete validator uses the
patterns above:

```java
import org.julclang.core.types.JulcList;
import org.julclang.ledger.ScriptContext;
import org.julclang.ledger.TxInfo;
import org.julclang.ledger.TxOut;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;
import org.julclang.stdlib.lib.AddressLib;
import org.julclang.stdlib.lib.ContextsLib;
import org.julclang.stdlib.lib.ValuesLib;

import java.math.BigInteger;

@SpendingValidator
class PaymentGateValidator {
    record GateDatum(byte[] owner, byte[] payee, BigInteger minimum) {}

    sealed interface GateAction permits Claim, Refund {}
    record Claim() implements GateAction {}
    record Refund() implements GateAction {}

    @Entrypoint
    static boolean validate(GateDatum datum, GateAction action, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        return switch (action) {
            case Claim c -> paidTo(txInfo.outputs(), datum.payee()).compareTo(datum.minimum()) >= 0;
            case Refund r -> ContextsLib.signedBy(txInfo, datum.owner());
        };
    }

    static BigInteger paidTo(JulcList<TxOut> outputs, byte[] payee) {
        BigInteger total = BigInteger.ZERO;
        for (var output : outputs) {
            boolean toPayee = Builtins.equalsByteString(AddressLib.credentialHash(output.address()), payee);
            BigInteger amount = toPayee ? ValuesLib.lovelaceOf(output.value()) : BigInteger.ZERO;
            total = total.add(amount);
        }
        return total;
    }
}
```

Its test compiles the real class and builds ScriptContexts with the testkit:

```java
static final CompileResult compiled =
        ValidatorTest.compileValidator(PaymentGateValidator.class, Path.of("src/test/java"));

static final PubKeyHash OWNER = TestDataBuilder.randomPubKeyHash_typed();
static final PubKeyHash PAYEE = TestDataBuilder.randomPubKeyHash_typed();
static final PubKeyHash OTHER = TestDataBuilder.randomPubKeyHash_typed();
static final BigInteger MINIMUM = BigInteger.valueOf(5_000_000);

static final PlutusData CLAIM = PlutusData.constr(0);
static final PlutusData REFUND = PlutusData.constr(1);

static TxOut pay(PubKeyHash to, long lovelace) {
    return TestDataBuilder.txOut(TestDataBuilder.pubKeyAddress(to), Value.lovelace(BigInteger.valueOf(lovelace)));
}

static PlutusData context(PlutusData redeemer, List<TxOut> outputs, PubKeyHash... signers) {
    PlutusData datum = PlutusData.constr(0,
            PlutusData.bytes(OWNER.hash()), PlutusData.bytes(PAYEE.hash()), PlutusData.integer(MINIMUM));
    TxOutRef spent = TestDataBuilder.randomTxOutRef_typed();
    var builder = ScriptContextTestBuilder.spending(spent, datum)
            .redeemer(redeemer)
            .input(TestDataBuilder.txIn(spent, pay(OWNER, 10_000_000)));
    outputs.forEach(builder::output);
    for (PubKeyHash signer : signers) builder.signer(signer);
    return builder.buildPlutusData();
}
```

The cases cover the boundary value, a malformed redeemer, an independent model of the
rule, and the budget:

```java
@Test
void claimAcceptsTheMinimumExactly() {
    ValidatorTest.assertValidates(compiled, context(CLAIM, List.of(pay(PAYEE, 5_000_000))));
}

@Test
void claimRejectsOneLovelaceShort() {
    ValidatorTest.assertRejects(compiled, context(CLAIM, List.of(pay(PAYEE, 4_999_999))));
}

@Test
void malformedRedeemerFails() {
    ValidatorTest.assertRejects(compiled, context(PlutusData.constr(2), List.of(pay(PAYEE, 5_000_000))));
    ValidatorTest.assertRejects(compiled, context(PlutusData.integer(0), List.of(pay(PAYEE, 5_000_000))));
}

@Test
void agreesWithAnIndependentModel() {
    Random random = new Random(163);
    for (int i = 0; i < 40; i++) {
        List<TxOut> outputs = new ArrayList<>();
        long toPayee = 0;
        for (int n = random.nextInt(5); n > 0; n--) {
            long lovelace = random.nextLong(3_000_001);
            boolean payee = random.nextBoolean();
            outputs.add(pay(payee ? PAYEE : OTHER, lovelace));
            if (payee) toPayee += lovelace;
        }
        boolean expected = toPayee >= MINIMUM.longValueExact();
        EvalResult result = ValidatorTest.evaluate(compiled, context(CLAIM, outputs));
        assertEquals(expected, result.isSuccess(), "case " + i + ": " + toPayee + " lovelace to the payee");
    }
}

@Test
void staysWithinBudget() {
    var outputs = List.of(pay(PAYEE, 2_000_000), pay(OTHER, 9_000_000), pay(PAYEE, 3_000_000));
    EvalResult result = ValidatorTest.evaluate(compiled, context(CLAIM, outputs));
    BudgetAssertions.assertSuccess(result);
    BudgetAssertions.assertBudgetUnder(result, 35_000_000, 140_000);
    BudgetAssertions.assertScriptSizeUnder(compiled, 1_000);
}
```

The full test also covers several payments that add up, a payment to someone else, a
transaction with no outputs, and the refund signature with the right, wrong and missing
signer.

When you test your own validator:

- Compile the real validator class and evaluate its UPLC with representative
  ScriptContexts ([building test ScriptContexts](/guides/testing-guide/#3-building-test-scriptcontexts)).
- Assert acceptance, rejection and failure, and keep a budget and size ceiling
  ([budget assertions](/guides/testing-guide/#6-budget-and-trace-assertions)).
- Cover empty collections, both sides of every branch, boundary values, malformed
  datums and redeemers, and adversarial transactions such as a payment to a different
  key or a missing signature.
- Compare against an independent model of the rule, and use generated inputs where you
  can ([property-based testing](/guides/testing-guide/#7-property-based-testing-with-jqwik)).
  The model above adds up the payee's payments without reading any `TxOut` the way the
  validator does.
- Agreement between several evaluators shows that they run the script the same way. It
  does not show that the script matches your source: they all run the same compiled
  artifact, including any compiler mistake.
- Test the script you deploy, with the compiler options, protocol version and cost
  model it will run under; see [cost model profiles](/reference/cost-model-profiles/)
  and [hash and cost stability](/reference/hash-stability/).

## Limits of this advice

- Value-oriented code reduces what a reader must keep in mind. It does not guarantee
  compiler correctness or production safety, and JuLC remains an experimental project.
- List operations need correct lowering and compiled tests just like loops, and their
  cost can differ from an explicit loop.
- Developers should not have to avoid compiler bugs through style. JuLC's rule is that a
  supported construct compiles with its Java meaning and an unsupported one is rejected.
  If you find a construct that compiles to a different meaning, report it as a bug.
- The restrictions on this page are current compiler limitations, not style rules. As of
  0.1.0-pre17:
  - [#155](https://github.com/bloxbean/julc/issues/155), a lost update to a loop-body
    local inside an `if`: closed; the shape is rejected
    ([PR #157](https://github.com/bloxbean/julc/pull/157)).
  - [#161](https://github.com/bloxbean/julc/issues/161), lost accumulator updates from a
    loop inside an `if` outside a loop body: fixed
    ([PR #164](https://github.com/bloxbean/julc/pull/164)).
  - [#162](https://github.com/bloxbean/julc/issues/162), stale field reads after
    reassigning a case-pattern variable: closed; the shape is rejected
    ([PR #157](https://github.com/bloxbean/julc/pull/157)).
  - Compound assignment, overloads, several variables in one declaration and loop
    updates of shared fields: see the
    [release notes](/reference/release-notes/).
