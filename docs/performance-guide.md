# Performance and Budget Guide

This guide explains the Cardano execution budget model and how to measure, test,
and optimize the cost and size of JuLC validators.

---

## 1. Cardano Budget Model

Every Plutus script evaluation on Cardano consumes two resources:

| Resource        | Description                                | Per-Transaction Limit | Per-Script Limit |
|-----------------|--------------------------------------------|----------------------:|------------------:|
| **CPU steps**   | Computational work (number of CEK machine steps weighted by builtin costs) | 14,000,000,000 (14B) | Same as transaction limit |
| **Memory units**| Peak memory consumed during evaluation      | 10,000,000,000 (10B) | Same as transaction limit |

These limits are protocol parameters set on the Cardano mainnet. When a script
exceeds either limit, evaluation halts with a `BudgetExhausted` result and the
transaction is rejected.

In addition to execution budget, there is a **script size limit**:

| Constraint           | Limit     |
|----------------------|-----------|
| **FLAT-encoded script size** | 16,384 bytes (16 KB) |

The 16 KB limit applies to the FLAT-encoded UPLC program that is stored
on-chain. Scripts that exceed this limit cannot be submitted to the network.

JuLC tracks both dimensions through the `ExBudget` record in `julc-vm`:

```java
// ExBudget is a simple record with saturating arithmetic
public record ExBudget(long cpuSteps, long memoryUnits) {
    public static final ExBudget ZERO = new ExBudget(0, 0);

    public ExBudget add(ExBudget other) { /* saturating addition */ }
    public boolean isExhausted() { return cpuSteps < 0 || memoryUnits < 0; }
}
```

---

## 2. Measuring Script Size

### Using `CompileResult`

After compiling a validator, `CompileResult` provides two methods for
inspecting the FLAT-encoded on-chain size:

```java
JulcCompiler compiler = new JulcCompiler();
CompileResult result = compiler.compile(validatorSource);

// Exact byte count
int bytes = result.scriptSizeBytes();

// Human-readable string: "342 B", "4.7 KB", "15 KB"
String formatted = result.scriptSizeFormatted();
```

### Using `ScriptAnalysis`

`ScriptAnalysis` wraps `CompileResult` with size analysis, automatic warnings,
and a comparison against the 16 KB protocol limit:

```java
ScriptAnalysis analysis = ScriptAnalysis.of(result);

System.out.println("FLAT size: " + analysis.flatSizeBytes());       // e.g. 4812
System.out.println("Formatted: " + analysis.flatSizeFormatted());   // e.g. "4.7 KB"
System.out.println("Exceeds limit: " + analysis.exceedsMaxSize());  // false

// Warnings are generated automatically:
//   - Above 12 KB: "approaches the 16 KB on-chain limit"
//   - Above 16 KB: "exceeds the 16 KB on-chain limit"
analysis.warnings().forEach(System.out::println);
```

You can also attach a sample execution budget:

```java
EvalResult evalResult = ValidatorTest.evaluate(result.program(), sampleArgs);
ScriptAnalysis analysis = ScriptAnalysis.of(result, evalResult.budgetConsumed());
// analysis.sampleBudget() now returns the ExBudget from the evaluation
```

### Using `BudgetAssertions.assertScriptSizeUnder()`

In tests, assert that the script stays within a byte budget:

```java
@Test
void scriptSizeIsUnder8KB() {
    CompileResult result = compiler.compile(validatorSource);
    // Fails with a descriptive message if the script exceeds 8192 bytes
    BudgetAssertions.assertScriptSizeUnder(result, 8192);
}
```

The assertion error message includes both the actual size and the formatted
representation:

```
AssertionError: Script size 9400 bytes exceeds maximum 8192 bytes (formatted: 9.2 KB)
```

---

## 3. Measuring Execution Budget

### Evaluating a script

Use `ValidatorTest` to compile and evaluate a validator, then inspect the budget:

```java
// Compile and evaluate with sample arguments
EvalResult result = ValidatorTest.evaluate(validatorSource, sampleRedeemer, sampleCtx);

// Budget consumed is always available, regardless of success or failure
ExBudget consumed = result.budgetConsumed();
System.out.println("CPU steps:    " + consumed.cpuSteps());
System.out.println("Memory units: " + consumed.memoryUnits());
```

`EvalResult` is a sealed interface with three variants:

| Variant            | Meaning                                          |
|--------------------|--------------------------------------------------|
| `Success`          | Evaluation completed and produced a value         |
| `Failure`          | Evaluation hit an error term or type error        |
| `BudgetExhausted`  | Evaluation exceeded the provided budget ceiling   |

All three variants carry `budgetConsumed()` and `traces()`.

### Evaluating with a budget ceiling

To test that a validator fits within a specific budget:

```java
ExBudget ceiling = new ExBudget(5_000_000, 2_000_000);
EvalResult result = ValidatorTest.evaluate(program, ceiling, datum, redeemer, ctx);
// If the budget is exceeded, result will be a BudgetExhausted instance
```

### Budget assertions in tests

```java
@Test
void budgetIsReasonable() {
    EvalResult result = ValidatorTest.evaluate(validatorSource, redeemer, ctx);

    // Assert success first
    BudgetAssertions.assertSuccess(result);

    // Assert CPU and memory are both within limits
    BudgetAssertions.assertBudgetUnder(result, 1_000_000L, 500_000L);
}
```

The assertion error includes exactly which dimension was exceeded:

```
AssertionError: Budget exceeded: CPU steps 1200000 exceeds maximum 1000000
    (consumed: ExBudget{cpu=1200000, mem=340000})
```

### Trace assertions

Trace messages (emitted by `Builtins.trace()`) can be verified alongside budget:

```java
// Assert that specific trace messages appear (substring match, any order)
BudgetAssertions.assertTrace(result, "deadline check passed", "signature valid");

// Assert exact trace output (exact match, in order)
BudgetAssertions.assertTraceExact(result, "step 1", "step 2", "done");

// Assert no trace output (useful for production validators that should be silent)
BudgetAssertions.assertNoTraces(result);
```

---

## 4. Budget Cost Patterns

Different operations have very different costs in Plutus. The table below shows
approximate CPU step costs to help you reason about where budget is being spent.
Actual costs depend on the protocol cost model parameters.

| Operation                        | Approx. CPU Steps  | Notes                                         |
|----------------------------------|-------------------:|-----------------------------------------------|
| Field access (`unConstrData`, `sndPair`, etc.) | ~5,000       | Cheapest data operation                        |
| Integer arithmetic (`+`, `-`, `*`)            | ~10,000 per op  | Constant cost regardless of operand size (within typical ranges) |
| Integer comparison (`<`, `>`, `==`)           | ~10,000         | Same cost model as arithmetic                  |
| Boolean logic (`&&`, `\|\|`, `!`)             | ~5,000          | Compiled as if/else (force/delay), very cheap  |
| `equalsData` (deep data equality)            | ~50,000         | Single builtin; much cheaper than manual field-by-field comparison |
| ByteString comparison (`equalsByteString`)    | ~30,000         | Proportional to length for inequality          |
| List head/tail (`headList`, `tailList`)       | ~5,000 each     | Constant time; but traversal is O(n)           |
| List traversal (fold, map, filter)            | O(n) * body cost | Each element applies the body once             |
| Crypto: `verifyEd25519Signature`              | ~100,000+       | Most expensive common builtin                  |
| Crypto: `verifyEcdsaSecp256k1Signature`       | ~100,000+       | Similar cost to Ed25519                        |
| Crypto: `blake2b_256`, `sha2_256`             | ~50,000+        | Depends on input length                        |
| String/ByteString `appendByteString`          | ~20,000         | Proportional to total length                   |
| `serialiseData`                               | ~50,000+        | Depends on data structure size                 |

**Key takeaway:** Field access and integer arithmetic are cheap. List traversal
and crypto operations are expensive. Structure your validators to minimize
loops and avoid unnecessary cryptographic operations.

---

## 5. Optimization Patterns

### 5.1. Cache repeated field access

Each field access in Plutus compiles to a chain of `unConstrData`, `sndPair`,
`headList`, and `tailList` calls. If you access the same field multiple times,
the compiler re-derives it each time.

```java
// Bad: repeated field access walks the data structure twice
if (txInfo.fee() > 1000000 && txInfo.fee() < 5000000) { ... }

// Good: cache in a local var, accessed once
var fee = txInfo.fee();
if (fee > 1000000 && fee < 5000000) { ... }
```

### 5.2. Short-circuit validation (fail fast)

Plutus charges for every step executed. If a condition fails, stop immediately
rather than computing further checks.

```java
@Entrypoint
static boolean validate(PlutusData redeemer, ScriptContext ctx) {
    // Check cheapest condition first
    if (!checkSignature(ctx)) {
        Builtins.error(); // Immediately halt — no further budget consumed
    }

    // Only reach expensive checks if cheap ones pass
    if (!checkComplexBusinessLogic(ctx)) {
        Builtins.error();
    }

    return true;
}
```

Order your checks from cheapest to most expensive. Integer comparisons and
field access should come before list traversals and crypto operations.

### 5.3. Prefer builtins over stdlib loops

The stdlib methods (`ListsLib.map`, `ListsLib.filter`, etc.) are compiled as
recursive UPLC functions. When you only need to check a single property of a
list, a direct traversal with early exit can be cheaper.

```java
// Moderate: filters entire list, then checks length
var matching = ListsLib.filter(outputs, o -> o.address() == target);
if (matching.isEmpty()) { Builtins.error(); }

// Better if you only need to confirm existence:
// use a helper that returns on first match
static boolean anyOutputTo(List<TxOut> outputs, Address target) {
    for (var out : outputs) {
        if (out.address() == target) return true;
    }
    return false;
}
```

### 5.4. Use `equalsData` instead of field-by-field comparison

When comparing two `PlutusData` values, a single `equalsData` builtin is
usually cheaper than destructuring both values and comparing each field:

```java
// Expensive: destructure both and compare three fields
if (a.field0() == b.field0() && a.field1() == b.field1() && a.field2() == b.field2()) { ... }

// Cheaper: single builtin call
if (Builtins.equalsData(a, b)) { ... }
```

### 5.5. Minimize on-chain data size

Every byte of datum, redeemer, and script context contributes to transaction
fees and budget. Keep data structures lean:

- Use integer identifiers instead of long strings where possible.
- Avoid storing data on-chain that can be recomputed off-chain and passed as
  a redeemer.
- Prefer compact constructor indices over large enums.

---

## 6. Script Size Optimization

### 6.1. Keep validators focused

A validator that tries to do everything in one class will produce a large UPLC
program. Delegate reusable logic to library classes annotated with
`@OnchainLibrary` and keep the validator entry point minimal.

```java
@SpendingValidator
class MyValidator {
    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        // Thin validator — delegates to a library
        return ValidationLogic.check(redeemer, ctx);
    }
}

@OnchainLibrary
class ValidationLogic {
    static boolean check(PlutusData redeemer, ScriptContext ctx) {
        // ... business logic ...
    }
}
```

When a library method is used, the compiler includes only the methods that are
actually called (dead code elimination at the UPLC level). However, if a
library method calls other methods internally, those are included too.

### 6.2. Avoid importing unused library methods

Each stdlib method that is referenced in your code gets compiled into the UPLC
output. Only import and call the methods you need.

```java
// Avoid: calling a broad utility that internally pulls in many helpers
var result = ValidationUtils.fullCheck(ctx);

// Prefer: call only the specific checks you need
var sigOk = checkSignature(ctx);
var timeOk = checkDeadline(ctx);
```

### 6.3. Use inline expressions where clarity allows

Intermediate variables can sometimes increase UPLC size because the compiler
emits a `let` binding for each one. When the expression is simple and used
only once, inline it:

```java
// Slightly larger UPLC: introduces a let-binding
var deadline = txInfo.validRange().lowerBound();
if (deadline > 1000) { ... }

// Slightly smaller UPLC: no intermediate binding
if (txInfo.validRange().lowerBound() > 1000) { ... }
```

Use your judgment here. Readability matters, and the size difference per
variable is small (typically a few bytes). This optimization is most relevant
when you are close to the 16 KB limit.

---

## Quick Reference: Test Template

A complete test that validates both budget and script size:

```java
import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.testkit.BudgetAssertions;
import com.bloxbean.cardano.julc.testkit.ScriptAnalysis;
import com.bloxbean.cardano.julc.testkit.ValidatorTest;
import com.bloxbean.cardano.julc.vm.EvalResult;
import org.junit.jupiter.api.Test;

class MyValidatorBudgetTest {

    @Test
    void scriptSizeAndBudgetAreWithinLimits() {
        // Compile
        JulcCompiler compiler = new JulcCompiler();
        CompileResult compileResult = compiler.compile(VALIDATOR_SOURCE);

        // Check script size
        ScriptAnalysis analysis = ScriptAnalysis.of(compileResult);
        System.out.println("Script size: " + analysis.flatSizeFormatted());
        analysis.warnings().forEach(System.out::println);
        BudgetAssertions.assertScriptSizeUnder(compileResult, 8192); // 8 KB budget

        // Evaluate with sample data
        EvalResult evalResult = ValidatorTest.evaluate(
                compileResult.program(), sampleRedeemer, sampleCtx);

        // Check execution
        BudgetAssertions.assertSuccess(evalResult);
        BudgetAssertions.assertBudgetUnder(evalResult, 5_000_000L, 2_000_000L);

        // Print budget for visibility
        var consumed = evalResult.budgetConsumed();
        System.out.println("CPU steps:    " + consumed.cpuSteps());
        System.out.println("Memory units: " + consumed.memoryUnits());
    }
}
```

---

## 7. Benchmark Data

//TODO

### Instance Methods vs Stdlib Calls

Instance methods (e.g., `list.contains(x)`) and stdlib calls (e.g., `ListsLib.contains(list, x)`) generate the **same UPLC** — there is no performance difference. Both are dispatched through the `TypeMethodRegistry` and produce identical PIR terms. Choose based on readability.

### Dead Code Elimination

`@OnchainLibrary` methods are only included in the final UPLC if they are actually called. The compiler's library resolution is transitive but only includes reachable methods. Unused library methods do not increase script size.

---

## Summary

| What to Measure       | API                                             | Assertion                                    |
|-----------------------|-------------------------------------------------|----------------------------------------------|
| Script size (bytes)   | `CompileResult.scriptSizeBytes()`               | `BudgetAssertions.assertScriptSizeUnder()`   |
| Script size (display) | `CompileResult.scriptSizeFormatted()`           | --                                           |
| Size analysis         | `ScriptAnalysis.of(result)`                     | Check `exceedsMaxSize()` and `warnings()`    |
| CPU steps consumed    | `EvalResult.budgetConsumed().cpuSteps()`        | `BudgetAssertions.assertBudgetUnder()`       |
| Memory consumed       | `EvalResult.budgetConsumed().memoryUnits()`     | `BudgetAssertions.assertBudgetUnder()`       |
| Evaluation success    | `EvalResult.isSuccess()`                        | `BudgetAssertions.assertSuccess()`           |
| Trace messages        | `EvalResult.traces()`                           | `BudgetAssertions.assertTrace()`             |
