# Testing Guide

Write tests for your Cardano smart contracts at every level — from direct Java
debugging to property-based fuzzing to on-chain integration.

Smart contracts handle real value and, once deployed, cannot be patched. JuLC
provides a layered testing approach so you can catch bugs early:

1. **Direct Java tests** — call validator logic as plain Java, set breakpoints
2. **UPLC evaluation tests** — compile to Plutus and evaluate in the CEK machine
3. **Property-based tests** — run hundreds of random scenarios with jqwik
4. **Budget tests** — verify CPU/memory costs stay within bounds
5. **Integration tests** — submit real transactions to a local devnet

| Layer | Speed | What it catches |
|-------|-------|-----------------|
| Direct Java | Instant | Logic bugs, off-by-one errors |
| UPLC evaluation | Fast (~ms) | Compilation issues, on-chain behavior differences |
| Property-based | Moderate (~s) | Edge cases you didn't think of |
| Budget | Fast (~ms) | Cost regressions, script size bloat |
| Integration | Slow (~s) | Transaction building, serialization, ledger rules |

## Table of Contents

1. [Project Setup](#1-project-setup) — Gradle, Maven, JUnit 5 dependencies
2. [Unit Testing with ContractTest](#2-unit-testing-with-contracttest) — base class, compile-from-class, two-tier pattern
3. [Building Test ScriptContexts](#3-building-test-scriptcontexts) — fluent builder, TestDataBuilder helpers
4. [Testing Helper Methods with JulcEval](#4-testing-helper-methods-with-julceval) — proxy mode, call() API
5. [ValidatorTest — Static Utility API](#5-validatortest--static-utility-api) — compile, evaluate, method eval
6. [Budget and Trace Assertions](#6-budget-and-trace-assertions) — budget limits, traces, script size
7. [Property-Based Testing with jqwik](#7-property-based-testing-with-jqwik) — CardanoArbitraries, BudgetCollector, ArbitraryScriptContext
8. [Source Map Debugging](#8-source-map-debugging) — error locations, execution tracing
9. [Integration Testing with Yaci DevKit](#9-integration-testing-with-yaci-devkit) — devnet, admin API
10. [Testing Patterns and Best Practices](#10-testing-patterns-and-best-practices) — compile-once, sealed interfaces, @Param
11. [Quick Reference](#11-quick-reference) — which tool for which scenario

---

## 1. Project Setup

### Gradle

Add these test dependencies to your `build.gradle`:

```groovy
dependencies {
    // Core compilation + ledger types
    implementation 'com.bloxbean.cardano:julc-core:0.1.0-pre7'
    implementation 'com.bloxbean.cardano:julc-compiler:0.1.0-pre7'
    implementation 'com.bloxbean.cardano:julc-ledger-api:0.1.0-pre7'
    implementation 'com.bloxbean.cardano:julc-stdlib:0.1.0-pre7'

    // Test framework
    testImplementation 'com.bloxbean.cardano:julc-testkit:0.1.0-pre7'
    testRuntimeOnly    'com.bloxbean.cardano:julc-vm-scalus:0.1.0-pre7'
}
```

To add property-based testing with jqwik:

```groovy
dependencies {
    testImplementation 'com.bloxbean.cardano:julc-testkit-jqwik:0.1.0-pre7'
    testImplementation 'net.jqwik:jqwik:1.9.2'
}
```

> If you use the JuLC BOM (`julc-bom`), you can omit version numbers for JuLC
> modules. The BOM manages all versions centrally.

### JUnit 5 Platform

Both JUnit 5 and jqwik run on the JUnit Platform. Ensure your `build.gradle`
includes:

```groovy
test {
    useJUnitPlatform()
}
```

### Maven

```xml
<dependency>
    <groupId>com.bloxbean.cardano</groupId>
    <artifactId>julc-testkit</artifactId>
    <version>0.1.0-pre7</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>com.bloxbean.cardano</groupId>
    <artifactId>julc-vm-scalus</artifactId>
    <version>0.1.0-pre7</version>
    <scope>test</scope>
</dependency>

<!-- Optional: property-based testing -->
<dependency>
    <groupId>com.bloxbean.cardano</groupId>
    <artifactId>julc-testkit-jqwik</artifactId>
    <version>0.1.0-pre7</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>net.jqwik</groupId>
    <artifactId>jqwik</artifactId>
    <version>1.9.2</version>
    <scope>test</scope>
</dependency>
```

---

## 2. Unit Testing with ContractTest

`ContractTest` is the recommended base class for validator tests. It provides
a pre-configured VM, compilation helpers, context builders, and assertion methods.

In a real project, your validators live in their own `.java` files (e.g.
`src/main/java/com/example/VestingValidator.java`). The test compiles the
validator class directly — no need to copy the source into a string.

Given a validator like this:

```java
// src/main/java/com/example/VestingValidator.java
package com.example;

@SpendingValidator
class VestingValidator {
    record VestingDatum(byte[] beneficiary, BigInteger deadline) {}

    @Entrypoint
    static boolean validate(VestingDatum datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        var sigs = txInfo.signatories();
        return sigs.contains(datum.beneficiary()) && datum.deadline() > 0;
    }
}
```

The test compiles it by class reference:

```java
import com.bloxbean.cardano.julc.testkit.ContractTest;
import com.bloxbean.cardano.julc.testkit.BudgetAssertions;
import org.junit.jupiter.api.*;

class VestingValidatorTest extends ContractTest {

    // Compile once — auto-discovers source file + @OnchainLibrary dependencies
    static CompileResult compiled;

    @BeforeAll
    static void setup() {
        initCrypto();
        compiled = compileValidator(VestingValidator.class);
    }

    @Test
    void beneficiaryCanUnlock() {
        var ctx = buildSpendingCtx(beneficiaryPkh);
        assertValidates(compiled.program(), ctx);
    }

    @Test
    void attackerIsRejected() {
        var ctx = buildSpendingCtx(attackerPkh);
        assertFailure(evaluate(compiled.program(), ctx));
    }
}
```

`compileValidator(Class<?>)` locates the `.java` source file on the classpath,
discovers any `@OnchainLibrary` dependencies, and compiles everything together.
This is the recommended approach for real projects.

### ContractTest API Reference

| Method | Description |
|--------|-------------|
| `compile(String source)` | Compile Java source to a `Program` |
| `compile(String source, String... libs)` | Multi-file compilation |
| `compile(Path sourceFile)` | Compile from a file path |
| `compileValidator(Class<?>)` | Auto-discover source + dependencies |
| `compileValidatorWithSourceMap(Class<?>)` | Compile with source map for error locations |
| `evaluate(Program, PlutusData...)` | Evaluate a compiled program |
| `evaluateWithTrace(CompileResult, PlutusData...)` | Evaluate with execution tracing |
| `assertValidates(Program, PlutusData...)` | Assert the validator accepts |
| `assertSuccess(EvalResult)` | Assert evaluation succeeded |
| `assertFailure(EvalResult)` | Assert evaluation failed |
| `assertSuccess(EvalResult, SourceMap)` | Assert success with source location on failure |
| `assertBudgetUnder(EvalResult, long, long)` | Assert CPU/memory within bounds |
| `spendingContext(TxOutRef)` | Create spending ScriptContext builder |
| `spendingContext(TxOutRef, PlutusData)` | Spending builder with inline datum |
| `mintingContext(PolicyId)` | Create minting ScriptContext builder |
| `rewardingContext(Credential)` | Create rewarding ScriptContext builder |
| `certifyingContext(BigInteger, TxCert)` | Create certifying context builder |
| `votingContext(Voter)` | Create voting context builder |
| `proposingContext(BigInteger, ProposalProcedure)` | Create proposing context builder |
| `initCrypto()` | Initialize JVM crypto provider (call in `@BeforeAll`) |
| `vm()` | Access the underlying `JulcVm` instance |

### The Two-Tier Test Pattern

A common pattern is to organize tests into two `@Nested` classes:

1. **DirectJavaTests** — call validator logic as plain Java for debugging
2. **UplcTests** — compile to UPLC and evaluate in the CEK machine

```java
class VestingTest extends ContractTest {

    @BeforeAll
    static void setup() { initCrypto(); }

    // The EXACT validator logic, written as a regular method for debugging
    static boolean vestingValidate(byte[] beneficiary, BigInteger deadline,
                                   ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        boolean isSigned = ContextsLib.signedBy(txInfo, beneficiary);
        boolean isPast = IntervalLib.contains(txInfo.validRange(), deadline);
        return isSigned && isPast;
    }

    @Nested
    class DirectJavaTests {
        @Test
        void beneficiaryCanUnlock() {
            var ctx = buildCtx(new byte[][]{pkh}, Interval.after(BigInteger.valueOf(500)));

            // SET BREAKPOINT HERE — step into vestingValidate with IntelliJ debugger!
            boolean result = vestingValidate(pkh, BigInteger.valueOf(1000), ctx);
            assertTrue(result);
        }

        @Test
        void wrongSignerRejected() {
            var ctx = buildCtx(new byte[][]{attacker}, Interval.after(BigInteger.valueOf(500)));
            assertFalse(vestingValidate(beneficiary, BigInteger.valueOf(1000), ctx));
        }
    }

    @Nested
    class UplcTests {
        // Compile from the actual validator class
        static final CompileResult compiled = ValidatorTest.compileValidator(VestingValidator.class);

        @Test
        void beneficiaryCanUnlock() {
            var ctx = ScriptContextTestBuilder.spending(spentRef, datum)
                    .signer(beneficiaryPkh)
                    .input(txIn)
                    .buildPlutusData();
            BudgetAssertions.assertSuccess(ValidatorTest.evaluate(compiled.program(), ctx));
        }
    }
}
```

Direct Java tests let you set breakpoints and step through logic in your IDE.
UPLC tests verify the same logic compiles correctly and behaves identically
on-chain.

---

## 3. Building Test ScriptContexts

The `ScriptContextTestBuilder` provides a fluent API for constructing Plutus V3
ScriptContexts with the correct structure.

### Spending Context

```java
import com.bloxbean.cardano.julc.testkit.ScriptContextTestBuilder;
import com.bloxbean.cardano.julc.testkit.TestDataBuilder;
import com.bloxbean.cardano.julc.ledger.*;

var spentRef = TestDataBuilder.randomTxOutRef_typed();
var beneficiary = TestDataBuilder.randomPubKeyHash_typed();
var datum = PlutusData.constr(0, PlutusData.bytes(beneficiary.hash()), PlutusData.integer(1000));

var ctx = ScriptContextTestBuilder.spending(spentRef, datum)
        .signer(beneficiary)
        .input(TestDataBuilder.txIn(spentRef,
                TestDataBuilder.txOut(
                        TestDataBuilder.pubKeyAddress(beneficiary),
                        Value.lovelace(BigInteger.valueOf(5_000_000)))))
        .output(new TxOut(destAddress, destValue,
                new OutputDatum.NoOutputDatum(), Optional.empty()))
        .fee(BigInteger.valueOf(200_000))
        .validRange(Interval.after(BigInteger.valueOf(1000)))
        .buildPlutusData();
```

### Minting Context

```java
var policyId = PolicyId.of(policyBytes);
var ctx = ScriptContextTestBuilder.minting(policyId)
        .redeemer(PlutusData.bytes(signerPkh.hash()))
        .signer(signerPkh)
        .mint(Value.singleton(policyId, tokenName, BigInteger.ONE))
        .input(TestDataBuilder.txIn(
                TestDataBuilder.randomTxOutRef_typed(),
                TestDataBuilder.txOut(
                        TestDataBuilder.pubKeyAddress(signerPkh),
                        Value.lovelace(BigInteger.valueOf(10_000_000)))))
        .buildPlutusData();
```

### Other Script Purposes

```java
// Rewarding (staking)
ScriptContextTestBuilder.rewarding(credential)

// Certifying (certificate operations)
ScriptContextTestBuilder.certifying(BigInteger.ZERO, txCert)

// Voting (Conway governance)
ScriptContextTestBuilder.voting(voter)

// Proposing (governance proposals)
ScriptContextTestBuilder.proposing(BigInteger.ZERO, proposalProcedure)
```

### Builder Methods

| Method | Description |
|--------|-------------|
| `.signer(PubKeyHash)` | Add a signatory |
| `.signer(byte[])` | Add a signatory from raw bytes |
| `.input(TxInInfo)` | Add a transaction input |
| `.referenceInput(TxInInfo)` | Add a reference input |
| `.output(TxOut)` | Add a transaction output |
| `.fee(BigInteger)` | Set the fee (lovelace) |
| `.mint(Value)` | Set the mint value |
| `.validRange(Interval)` | Set the validity interval |
| `.redeemer(PlutusData)` | Set the redeemer |
| `.txId(TxId)` | Set the transaction ID |
| `.certificate(TxCert)` | Add a certificate |
| `.withdrawal(Credential, BigInteger)` | Add a withdrawal |
| `.datum(DatumHash, PlutusData)` | Add a datum entry |
| `.redeemerEntry(ScriptPurpose, PlutusData)` | Add a redeemer map entry |
| `.currentTreasuryAmount(BigInteger)` | Set current treasury amount (Conway) |
| `.treasuryDonation(BigInteger)` | Set treasury donation (Conway) |

### Build Modes

| Method | Returns | Use when |
|--------|---------|----------|
| `.build()` | `ScriptContext` | Direct Java tests with ledger types |
| `.buildOnchain()` | `ScriptContext` | On-chain API variant |
| `.buildPlutusData()` | `PlutusData` | UPLC evaluation via `ValidatorTest.evaluate()` |

### TestDataBuilder Helpers

`TestDataBuilder` provides random and typed test data generators:

```java
import com.bloxbean.cardano.julc.testkit.TestDataBuilder;

// Random typed ledger values
PubKeyHash pkh       = TestDataBuilder.randomPubKeyHash_typed();
TxOutRef   ref       = TestDataBuilder.randomTxOutRef_typed();
Address    addr      = TestDataBuilder.pubKeyAddress(pkh);
TxOut      out       = TestDataBuilder.txOut(addr, Value.lovelace(BigInteger.valueOf(5_000_000)));
TxInInfo   input     = TestDataBuilder.txIn(ref, out);

// Raw PlutusData values
PlutusData pkhData   = TestDataBuilder.randomPubKeyHash();
PlutusData txIdData  = TestDataBuilder.randomTxId();
PlutusData refData   = TestDataBuilder.randomTxOutRef();
PlutusData unit      = TestDataBuilder.unitData();
PlutusData boolTrue  = TestDataBuilder.boolData(true);
PlutusData someBytes = TestDataBuilder.randomBytes(32);

// PlutusData constructors
PlutusData anInt     = TestDataBuilder.intData(42);
PlutusData bytes     = TestDataBuilder.bytesData(new byte[]{1, 2, 3});
PlutusData list      = TestDataBuilder.listData(TestDataBuilder.intData(1), TestDataBuilder.intData(2));
PlutusData constr    = TestDataBuilder.constrData(0, TestDataBuilder.intData(100));
PlutusData map       = TestDataBuilder.mapData(TestDataBuilder.intData(1), TestDataBuilder.intData(2));
```

---

## 4. Testing Helper Methods with JulcEval

`JulcEval` tests individual methods in isolation — no ScriptContext needed.
It compiles a single Java method to UPLC, evaluates it, and extracts the result
with natural Java types.

### Interface Proxy Mode

Define a Java interface matching the on-chain methods and call them directly:

```java
import com.bloxbean.cardano.julc.testkit.JulcEval;

// Given an on-chain class:
//   class MathHelper {
//       static BigInteger doubleIt(BigInteger x) { return x * 2; }
//       static boolean isPositive(BigInteger x) { return x > 0; }
//   }

interface MathProxy {
    BigInteger doubleIt(long x);
    boolean isPositive(long x);
}

var proxy = JulcEval.forClass(MathHelper.class).create(MathProxy.class);

assertEquals(BigInteger.valueOf(42), proxy.doubleIt(21));
assertTrue(proxy.isPositive(1));
assertFalse(proxy.isPositive(-5));
```

The proxy automatically converts Java arguments (`long`, `int`, `byte[]`, etc.)
to `PlutusData`, compiles and evaluates the method in the CEK machine, and
converts the UPLC result back to the declared Java return type.

### Fluent call() API

For one-off calls without defining an interface:

```java
var eval = JulcEval.forClass(MathHelper.class);

BigInteger result = eval.call("doubleIt", 21).asInteger();
boolean positive  = eval.call("isPositive", 1).asBoolean();
byte[] hash       = eval.call("hashData", someBytes).asByteString();
```

### Inline Source

You can also test inline Java source strings:

```java
var eval = JulcEval.forSource("""
        class Utils {
            static BigInteger add(BigInteger a, BigInteger b) {
                return a + b;
            }
        }
        """);

assertEquals(BigInteger.valueOf(30), eval.call("add", 10, 20).asInteger());
```

### CallResult Extraction

| Method | Return type | Description |
|--------|-------------|-------------|
| `.asInteger()` | `BigInteger` | Integer result |
| `.asLong()` | `long` | Integer as long |
| `.asInt()` | `int` | Integer as int |
| `.asByteString()` | `byte[]` | Byte string result |
| `.asBoolean()` | `boolean` | Boolean result |
| `.asString()` | `String` | UTF-8 string result |
| `.asData()` | `PlutusData` | Raw PlutusData |
| `.asOptional()` | `Optional<PlutusData>` | Optional (Some/None) |
| `.asList()` | `List<PlutusData>` | List of data items |
| `.as(Class<T>)` | `T` | Any ledger type or primitive |
| `.auto()` | `Object` | Auto-detect type |
| `.rawTerm()` | `Term` | Raw UPLC term |

### Source Maps with JulcEval

Enable source maps to get Java line numbers in error messages:

```java
var eval = JulcEval.forClass(SwapOrder.class).sourceMap();
// On failure: "Evaluation failed at SwapOrder.java:42 (Builtins.error())"
```

### Execution Tracing

Enable tracing for step-by-step execution analysis:

```java
var eval = JulcEval.forClass(MyValidator.class).trace();
eval.call("validate", args);

// Print execution trace
System.out.println(eval.formatLastTrace());
System.out.println(eval.formatLastBudgetSummary());
```

---

## 5. ValidatorTest — Static Utility API

`ValidatorTest` provides static methods for compile-and-evaluate workflows
without extending a base class. Use it when you prefer composition over
inheritance.

### Compile

```java
import com.bloxbean.cardano.julc.testkit.ValidatorTest;

// From class — recommended for real projects
// Auto-discovers source file + @OnchainLibrary dependencies
CompileResult result = ValidatorTest.compileValidator(MyValidator.class);
Program program = result.program();

// From fully-qualified class name (when .class is unavailable, e.g. -proc:only builds)
CompileResult result = ValidatorTest.compileValidatorByName("com.example.MyValidator");

// From a .java file path
Program program = ValidatorTest.compile(Path.of("src/main/java/MyValidator.java"));

// From inline source string (useful for quick experiments and internal compiler tests)
Program program = ValidatorTest.compile(javaSource);

// Multi-file inline sources (validator + library)
Program program = ValidatorTest.compile(validatorSource, librarySource);
```

### Evaluate

```java
// Evaluate with PlutusData arguments
EvalResult result = ValidatorTest.evaluate(program, datum, redeemer, scriptContext);

// Evaluate with an explicit budget cap
EvalResult result = ValidatorTest.evaluate(program, new ExBudget(10_000_000_000L, 10_000_000L),
        datum, redeemer, scriptContext);

// Assert success/failure
ValidatorTest.assertValidates(program, datum, redeemer, ctx);
ValidatorTest.assertRejects(program, datum, redeemer, ctx);
```

### Evaluate Individual Methods

```java
// Evaluate a single static method
BigInteger result = ValidatorTest.evaluateInteger(javaSource, "myMethod", arg1, arg2);
boolean    check  = ValidatorTest.evaluateBoolean(javaSource, "isValid", arg1);
PlutusData data   = ValidatorTest.evaluateData(javaSource, "transform", input);

// File-based method evaluation
BigInteger result = ValidatorTest.evaluateInteger(MathHelper.class, "doubleIt", arg);
```

---

## 6. Budget and Trace Assertions

Budget testing catches cost regressions before they become on-chain failures.
Script size testing ensures your validators fit within the 16 KB Plutus limit.

### BudgetAssertions API

```java
import com.bloxbean.cardano.julc.testkit.BudgetAssertions;

var result = ValidatorTest.evaluate(program, ctx);

// Success / failure
BudgetAssertions.assertSuccess(result);
BudgetAssertions.assertFailure(result);

// CPU and memory limits
BudgetAssertions.assertBudgetUnder(result, 50_000_000L, 200_000L);

// Trace messages (from Builtins.trace())
BudgetAssertions.assertTrace(result, "checking signature");
BudgetAssertions.assertTraceExact(result, "step1", "step2", "step3");
BudgetAssertions.assertNoTraces(result);

// Script size (compile-time check)
var compiled = ValidatorTest.compileWithDetails(source);
BudgetAssertions.assertScriptSizeUnder(compiled, 16_384);  // 16 KB max
```

### Source Map Assertions

When a test fails, source-map-aware assertions include the Java source location:

```java
var compiled = ValidatorTest.compileValidatorWithSourceMap(MyValidator.class);
var result = ValidatorTest.evaluate(compiled.program(), ctx);

// Throws with "Expected success, but failed at MyValidator.java:42"
BudgetAssertions.assertSuccess(result, compiled.sourceMap());
```

### Budget Regression Test Pattern

Record budget values as constants and assert they don't regress:

```java
// Compile once from class
static final CompileResult compiled = ValidatorTest.compileValidator(MyValidator.class);

// Known-good budget for this validator (from initial measurement)
static final long MAX_CPU = 25_000_000L;
static final long MAX_MEM = 100_000L;

@Test
void budgetDoesNotRegress() {
    var ctx = buildTypicalContext();
    var result = ValidatorTest.evaluate(compiled.program(), ctx);

    BudgetAssertions.assertSuccess(result);
    BudgetAssertions.assertBudgetUnder(result, MAX_CPU, MAX_MEM);
}
```

### Script Size Analysis

```java
import com.bloxbean.cardano.julc.testkit.ScriptAnalysis;

var compiled = ValidatorTest.compileValidator(MyValidator.class);
var analysis = ScriptAnalysis.of(compiled);

System.out.println("FLAT size: " + analysis.flatSizeFormatted());  // e.g. "3.2 KB"

if (analysis.exceedsMaxSize()) {
    fail("Script exceeds 16 KB limit: " + analysis.flatSizeFormatted());
}
```

### Budget Logging Helper

A simple pattern for printing budgets during development:

```java
static void logBudget(String name, EvalResult result) {
    var budget = result.budgetConsumed();
    System.out.printf("%s: CPU=%,d  Mem=%,d%n", name, budget.cpuSteps(), budget.memoryUnits());
}
```

---

## 7. Property-Based Testing with jqwik

Property-based testing generates hundreds of random inputs to verify that
invariants always hold. For smart contracts, this is invaluable — it finds
edge cases that hand-written tests miss.

### What is a Property?

Instead of "given this specific input, expect this specific output", a property
says "for ALL valid inputs, this invariant holds":

- "The beneficiary can always unlock their vesting contract"
- "An unauthorized signer can never mint tokens"
- "The evaluation budget never exceeds X CPU steps"

### Setup

Add `julc-testkit-jqwik` and `jqwik` to your test dependencies:

```groovy
testImplementation 'com.bloxbean.cardano:julc-testkit-jqwik:0.1.0-pre7'
testImplementation 'net.jqwik:jqwik:1.9.2'
```

### Complete Example: Vesting Validator Properties

Assume `VestingValidator.java` is your validator class in `src/main/java`:

```java
// src/main/java/com/example/VestingValidator.java
@SpendingValidator
class VestingValidator {
    record VestingDatum(PlutusData beneficiary, PlutusData deadline) {}

    @Entrypoint
    static boolean validate(VestingDatum datum, PlutusData redeemer, PlutusData ctx) {
        PlutusData txInfo = ContextsLib.getTxInfo(ctx);
        PlutusData pkh = datum.beneficiary();
        return ContextsLib.signedBy(txInfo, pkh);
    }
}
```

The property test compiles it once and runs hundreds of random scenarios:

```java
import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.testkit.BudgetAssertions;
import com.bloxbean.cardano.julc.testkit.ScriptContextTestBuilder;
import com.bloxbean.cardano.julc.testkit.TestDataBuilder;
import com.bloxbean.cardano.julc.testkit.ValidatorTest;
import com.bloxbean.cardano.julc.testkit.jqwik.BudgetCollector;
import com.bloxbean.cardano.julc.testkit.jqwik.CardanoArbitraries;
import net.jqwik.api.*;
import net.jqwik.api.lifecycle.AfterProperty;

import java.math.BigInteger;
import java.util.Arrays;

class VestingPropertyTest {

    // Compile once from class — reuse across all property trials
    static final Program VESTING = ValidatorTest.compileValidator(VestingValidator.class).program();

    final BudgetCollector budgetCollector = new BudgetCollector();

    /**
     * Property: the beneficiary can ALWAYS unlock the vesting contract.
     */
    @Property(tries = 200)
    void beneficiaryAlwaysSucceeds(@ForAll("pkh") PubKeyHash beneficiary) {
        var ctx = buildVestingCtx(beneficiary, beneficiary);

        var result = ValidatorTest.evaluate(VESTING, ctx);
        BudgetAssertions.assertSuccess(result);
        budgetCollector.record(result);
    }

    /**
     * Property: a non-beneficiary can NEVER unlock the vesting contract.
     */
    @Property(tries = 200)
    void nonBeneficiaryAlwaysFails(
            @ForAll("pkh") PubKeyHash beneficiary,
            @ForAll("pkh") PubKeyHash attacker) {

        Assume.that(!Arrays.equals(beneficiary.hash(), attacker.hash()));

        var ctx = buildVestingCtx(beneficiary, attacker);

        var result = ValidatorTest.evaluate(VESTING, ctx);
        BudgetAssertions.assertFailure(result);
    }

    /**
     * Property: evaluation budget is always bounded.
     */
    @Property(tries = 200)
    void budgetIsBounded(@ForAll("pkh") PubKeyHash beneficiary) {
        var ctx = buildVestingCtx(beneficiary, beneficiary);

        var result = ValidatorTest.evaluate(VESTING, ctx);
        BudgetAssertions.assertSuccess(result);
        BudgetAssertions.assertBudgetUnder(result, 50_000_000, 200_000);
    }

    @AfterProperty
    void reportBudget() {
        if (budgetCollector.count() > 0) {
            System.out.println(budgetCollector.summary());
        }
    }

    @Provide
    Arbitrary<PubKeyHash> pkh() {
        return CardanoArbitraries.pubKeyHash();
    }

    private static PlutusData buildVestingCtx(PubKeyHash beneficiary, PubKeyHash signer) {
        var datum = PlutusData.constr(0,
                PlutusData.bytes(beneficiary.hash()),
                PlutusData.integer(1000));
        var spentRef = TestDataBuilder.randomTxOutRef_typed();
        return ScriptContextTestBuilder.spending(spentRef, datum)
                .signer(signer)
                .input(TestDataBuilder.txIn(spentRef,
                        TestDataBuilder.txOut(
                                TestDataBuilder.pubKeyAddress(beneficiary),
                                Value.lovelace(BigInteger.valueOf(5_000_000)))))
                .buildPlutusData();
    }
}
```

Key points:
- **Compile once from class**: `compileValidator(VestingValidator.class).program()` avoids recompiling every trial
- **`@Property(tries = 200)`**: each property runs 200 random trials
- **`@ForAll("pkh")`**: injects random `PubKeyHash` via `@Provide` method
- **`Assume.that(...)`**: skip trials where beneficiary == attacker (distinct keys)
- **`BudgetCollector`**: accumulates budget stats across trials

### Complete Example: Minting Policy Properties

Assume `SignedMintPolicy.java` is your minting policy in `src/main/java`:

```java
// src/main/java/com/example/SignedMintPolicy.java
@MintingPolicy
class SignedMintPolicy {
    @Entrypoint
    static boolean validate(PlutusData redeemer, PlutusData ctx) {
        PlutusData txInfo = ContextsLib.getTxInfo(ctx);
        return ContextsLib.signedBy(txInfo, redeemer);
    }
}
```

The property test:

```java
class MintingPropertyTest {

    // Compile once from class
    static final Program MINTING_POLICY = ValidatorTest.compileValidator(SignedMintPolicy.class).program();

    final BudgetCollector budgetCollector = new BudgetCollector();

    @Property(tries = 200)
    void authorizedSignerCanMint(
            @ForAll("policyId") PolicyId policy,
            @ForAll("pkh") PubKeyHash signer,
            @ForAll("tokenName") TokenName tokenName) {

        var ctx = ScriptContextTestBuilder.minting(policy)
                .redeemer(PlutusData.bytes(signer.hash()))
                .signer(signer)
                .mint(Value.singleton(policy, tokenName, BigInteger.ONE))
                .input(TestDataBuilder.txIn(
                        TestDataBuilder.randomTxOutRef_typed(),
                        TestDataBuilder.txOut(
                                TestDataBuilder.pubKeyAddress(signer),
                                Value.lovelace(BigInteger.valueOf(10_000_000)))))
                .buildPlutusData();

        var result = ValidatorTest.evaluate(MINTING_POLICY, ctx);
        BudgetAssertions.assertSuccess(result);
        budgetCollector.record(result);
    }

    @Property(tries = 200)
    void unauthorizedSignerCannotMint(
            @ForAll("policyId") PolicyId policy,
            @ForAll("pkh") PubKeyHash requiredSigner,
            @ForAll("pkh") PubKeyHash actualSigner) {

        Assume.that(!Arrays.equals(requiredSigner.hash(), actualSigner.hash()));

        var ctx = ScriptContextTestBuilder.minting(policy)
                .redeemer(PlutusData.bytes(requiredSigner.hash()))
                .signer(actualSigner)
                .mint(Value.singleton(policy, TokenName.EMPTY, BigInteger.ONE))
                .input(TestDataBuilder.txIn(
                        TestDataBuilder.randomTxOutRef_typed(),
                        TestDataBuilder.txOut(
                                TestDataBuilder.pubKeyAddress(actualSigner),
                                Value.lovelace(BigInteger.valueOf(10_000_000)))))
                .buildPlutusData();

        var result = ValidatorTest.evaluate(MINTING_POLICY, ctx);
        BudgetAssertions.assertFailure(result);
    }

    @AfterProperty
    void reportBudget() {
        if (budgetCollector.count() > 0) {
            System.out.println(budgetCollector.summary());
        }
    }

    @Provide Arbitrary<PubKeyHash> pkh()       { return CardanoArbitraries.pubKeyHash(); }
    @Provide Arbitrary<PolicyId> policyId()     { return CardanoArbitraries.policyId(); }
    @Provide Arbitrary<TokenName> tokenName()   { return CardanoArbitraries.tokenName(); }
}
```

### CardanoArbitraries — Available Generators

The `CardanoArbitraries` class provides jqwik `Arbitrary` generators for all
Cardano/Plutus types.

**Hash types:**

| Generator | Type | Size |
|-----------|------|------|
| `pubKeyHash()` | `PubKeyHash` | 28 bytes |
| `scriptHash()` | `ScriptHash` | 28 bytes |
| `validatorHash()` | `ValidatorHash` | 28 bytes |
| `policyId()` | `PolicyId` | 28 bytes |
| `tokenName()` | `TokenName` | 0-32 bytes |
| `datumHash()` | `DatumHash` | 32 bytes |
| `txId()` | `TxId` | 32 bytes |

**Composite types:**

| Generator | Type | Notes |
|-----------|------|-------|
| `credential()` | `Credential` | PubKeyCredential or ScriptCredential |
| `address()` | `Address` | Enterprise address with credential |
| `txOutRef()` | `TxOutRef` | TxId + index (0-9) |
| `lovelaceValue()` | `Value` | 1-100 ADA |
| `lovelaceValue(min, max)` | `Value` | Bounded lovelace |
| `multiAssetValue()` | `Value` | ADA + 1-3 native tokens |
| `value()` | `Value` | Either lovelace-only or multi-asset |
| `outputDatum()` | `OutputDatum` | NoOutputDatum, Hash, or InlineDatum |
| `txOut()` | `TxOut` | Address + value + datum |
| `txInInfo()` | `TxInInfo` | TxOutRef + TxOut |
| `interval()` | `Interval` | always, never, after, before, or between |

**PlutusData types:**

| Generator | Type | Notes |
|-----------|------|-------|
| `intData()` | `PlutusData` | Integer [-1B, 1B] |
| `intData(min, max)` | `PlutusData` | Bounded integer |
| `bytesData()` | `PlutusData` | 0-64 byte array |
| `bytesData(length)` | `PlutusData` | Fixed-length bytes |
| `constrData(maxDepth)` | `PlutusData` | Random ConstrData |
| `listData(maxDepth)` | `PlutusData` | Random ListData |
| `mapData(maxDepth)` | `PlutusData` | Random MapData |
| `plutusData()` | `PlutusData` | Any PlutusData (depth 3) |
| `plutusData(maxDepth)` | `PlutusData` | Depth-bounded |

### SPI Auto-Injection

The `julc-testkit-jqwik` module registers a `CardanoArbitraryProvider` via
Java SPI. This means jqwik can auto-inject Cardano types without `@Provide`
methods:

```java
// No @Provide needed — jqwik discovers the provider automatically
@Property
void test(@ForAll PubKeyHash pkh, @ForAll TxId txId) {
    // pkh and txId are auto-generated
}
```

Supported auto-injected types: `PlutusData`, `PubKeyHash`, `ScriptHash`,
`ValidatorHash`, `PolicyId`, `TokenName`, `DatumHash`, `TxId`, `Credential`,
`Address`, `TxOutRef`, `Value`, `TxOut`, `TxInInfo`, `Interval`, `OutputDatum`.

For custom constraints (e.g., bounded values), use `@Provide` with explicit
generator methods.

### BudgetCollector — Statistical Budget Analysis

`BudgetCollector` aggregates budget data across many property test trials:

```java
final BudgetCollector budgetCollector = new BudgetCollector();

@Property(tries = 500)
void myProperty(@ForAll PubKeyHash pkh) {
    var result = ValidatorTest.evaluate(program, buildCtx(pkh));
    BudgetAssertions.assertSuccess(result);
    budgetCollector.record(result);
}

@AfterProperty
void reportBudget() {
    if (budgetCollector.count() > 0) {
        System.out.println(budgetCollector.summary());
        // Output:
        //   Budget (500 trials): CPU avg=12,345,678 min=11,000,000 max=14,000,000 p99=13,900,000
        //                        Mem avg=45,678 min=40,000 max=52,000 p99=51,000
    }
}
```

| Method | Description |
|--------|-------------|
| `record(EvalResult)` | Record one trial's budget |
| `count()` | Number of recorded trials |
| `avgCpu()` / `avgMem()` | Average CPU/memory |
| `maxCpu()` / `maxMem()` | Maximum CPU/memory |
| `minCpu()` / `minMem()` | Minimum CPU/memory |
| `p99Cpu()` / `p99Mem()` | 99th percentile |
| `summary()` | Human-readable summary string |

### ArbitraryScriptContext — Random Consistent Contexts

`ArbitraryScriptContext` generates internally consistent ScriptContexts where
the spent reference appears in inputs, signers appear in address credentials, etc.

```java
import com.bloxbean.cardano.julc.testkit.jqwik.ArbitraryScriptContext;

// Spending context with 1-3 signers, 2-5 inputs, 1-3 outputs
Arbitrary<PlutusData> ctxArb = ArbitraryScriptContext.spending()
        .signers(1, 3)
        .inputs(2, 5)
        .outputs(1, 3)
        .fee(150_000, 300_000)
        .withDatum(CardanoArbitraries.intData())
        .withRedeemer(CardanoArbitraries.intData())
        .withValidRange(CardanoArbitraries.interval())
        .buildAsPlutusData();

// Minting context
Arbitrary<ScriptContext> mintCtx = ArbitraryScriptContext.minting()
        .signers(1, 2)
        .inputs(1, 3)
        .build();
```

### Custom Generators with @Provide

For domain-specific constraints, write custom `@Provide` methods that compose
`CardanoArbitraries` generators:

```java
@Provide
Arbitrary<PlutusData> vestingDatum() {
    return Combinators.combine(
            CardanoArbitraries.pubKeyHash(),
            Arbitraries.bigIntegers().between(BigInteger.ZERO, BigInteger.valueOf(100_000)))
        .as((pkh, deadline) -> PlutusData.constr(0,
                PlutusData.bytes(pkh.hash()),
                PlutusData.integer(deadline)));
}
```

### Property Design Tips

**Positive properties** verify that authorized actions always succeed:
- "The beneficiary can always unlock"
- "The authorized minter can always mint"
- "A valid bid always updates the auction state"

**Negative properties** verify that unauthorized actions always fail:
- "An attacker can never unlock"
- "An unauthorized signer can never mint"
- "A bid below the minimum always fails"

**Budget properties** verify cost bounds:
- "CPU never exceeds 50M steps"
- "Memory never exceeds 200K units"

**Structural properties** verify data integrity:
- "Output value always equals input value minus fee"
- "The continuing output always carries the updated datum"

---

## 8. Source Map Debugging

Source maps map UPLC runtime errors back to Java source lines. Without them, a
failure looks like `"Error term encountered"`. With them, you see
`"Error at MyValidator.java:42 (Builtins.error())"`.

### With JulcEval

```java
// Enable source maps with one call
var eval = JulcEval.forClass(MyValidator.class).sourceMap();
eval.call("validate", args);
// Failures now include Java source location
```

### With ValidatorTest

```java
// Compile with source maps
CompileResult compiled = ValidatorTest.compileValidatorWithSourceMap(MyValidator.class);

// Evaluate
EvalResult result = ValidatorTest.evaluate(compiled.program(), ctx);

// Resolve error location
SourceLocation location = ValidatorTest.resolveErrorLocation(result, compiled.sourceMap());
System.out.println("Error at: " + location);
// → "MyValidator.java:58 (Builtins.error())"

// Or use assertion shorthand — throws with location in the message
ValidatorTest.assertValidatesWithSourceMap(compiled, ctx);
```

### With ContractTest

```java
class MyTest extends ContractTest {
    @Test
    void testWithSourceMap() {
        var compiled = compileValidatorWithSourceMap(MyValidator.class);
        var result = evaluate(compiled.program(), ctx);

        // Assert with source-mapped error messages
        assertSuccess(result, compiled.sourceMap());

        // Or resolve the location manually
        var location = resolveErrorLocation(result, compiled.sourceMap());

        // Log with budget and location
        logResult("myTest", result, compiled.sourceMap());
    }
}
```

### Execution Tracing

Tracing provides a step-by-step view of which Java source lines executed and
their CPU/memory cost:

```java
var compiled = ValidatorTest.compileValidatorWithSourceMap(MyValidator.class);
var evalTrace = ValidatorTest.evaluateWithTrace(compiled, ctx);

System.out.println(evalTrace.formatTrace());
// MyValidator.java:30  var txInfo = ContextsLib.getTxInfo(ctx);   CPU: 1,234
// MyValidator.java:31  var pkh = datum.beneficiary();             CPU: 567
// MyValidator.java:32  return ContextsLib.signedBy(txInfo, pkh);  CPU: 8,901

System.out.println(evalTrace.formatBudgetSummary());
// Per-location budget breakdown
```

### Enabling Source Maps in Gradle

For the annotation processor build pipeline:

```groovy
julc {
    sourceMap = true
}
```

Or via compiler options:

```groovy
compileJava {
    options.compilerArgs += ['-Ajulc.sourceMap=true']
}
```

---

## 9. Integration Testing with Yaci DevKit

Integration tests submit real transactions to a local Cardano devnet. Use them
for end-to-end verification: transaction building, script serialization, ledger
rule validation, and fee calculation.

### Prerequisites

Yaci DevKit must be started externally before running integration tests:

```bash
# Start Yaci DevKit (separate terminal)
yaci-devkit start
```

The DevKit exposes:
- **Node API** on the default Cardano port
- **Admin API** on port 10000

### Admin API — Reset and Fund

```bash
# Reset the devnet to genesis state
curl -X POST http://localhost:10000/local-cluster/api/admin/devnet/reset

# Top up an address with test ADA
curl -X POST http://localhost:10000/local-cluster/api/admin/topup \
  -H 'Content-Type: application/json' \
  -d '{"address": "addr_test1...", "adaAmount": 1000}'
```

Full OpenAPI documentation: `http://localhost:10000/v3/api-docs`

### Integration Test Pattern

```java
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.backend.api.BackendService;

class VestingIntegrationTest {

    static BackendService backendService;  // from cardano-client-lib

    @BeforeAll
    static void setup() {
        // Connect to local Yaci DevKit
        backendService = new BFBackendService("http://localhost:10000/api/v1", "");
    }

    @Test
    void deployAndUnlockVesting() {
        // 1. Compile validator
        var compiled = ValidatorTest.compileValidator(VestingValidator.class);
        var scriptHash = compiled.program().scriptHash();

        // 2. Build lock transaction (send ADA to script address)
        // ... use cardano-client-lib transaction builder ...

        // 3. Submit lock tx
        Result<String> lockResult = transactionService.submitTransaction(signedLockTx);
        assertTrue(lockResult.isSuccessful());

        // 4. Build unlock transaction (claim from script)
        // ... attach compiled script, datum, redeemer ...

        // 5. Submit unlock tx
        Result<String> unlockResult = transactionService.submitTransaction(signedUnlockTx);
        assertTrue(unlockResult.isSuccessful());
    }
}
```

> Integration tests are slower and require external infrastructure. Use UPLC
> evaluation tests for the bulk of your testing, and integration tests for
> final verification before deployment.

---

## 10. Testing Patterns and Best Practices

### Compile-Once, Evaluate-Many

Compilation is the expensive step. Always compile once and reuse the `Program`:

```java
// Good — compile once as a static field
static final Program VALIDATOR = ValidatorTest.compileValidator(MyValidator.class).program();

@Test void test1() { ValidatorTest.evaluate(VALIDATOR, ctx1); }
@Test void test2() { ValidatorTest.evaluate(VALIDATOR, ctx2); }

// Bad — recompiles on every test
@Test void test1() {
    ValidatorTest.evaluate(ValidatorTest.compileValidator(MyValidator.class).program(), ctx1);
}
```

### Testing Sealed Interface Redeemers

When your validator uses a sealed interface for the redeemer, test every variant:

```java
// Validator with action redeemer:
//   sealed interface Action { record Mint() implements Action {} record Burn() implements Action {} }

@Test
void mintActionAccepted() {
    var redeemer = PlutusData.constr(0);  // Mint = Constr(0, [])
    var ctx = buildMintCtx(redeemer);
    BudgetAssertions.assertSuccess(ValidatorTest.evaluate(program, ctx));
}

@Test
void burnActionAccepted() {
    var redeemer = PlutusData.constr(1);  // Burn = Constr(1, [])
    var ctx = buildBurnCtx(redeemer);
    BudgetAssertions.assertSuccess(ValidatorTest.evaluate(program, ctx));
}

@Test
void invalidActionRejected() {
    var redeemer = PlutusData.constr(99);  // Unknown variant
    var ctx = buildMintCtx(redeemer);
    BudgetAssertions.assertFailure(ValidatorTest.evaluate(program, ctx));
}
```

### Testing @Param Validators

Parameterized validators take compile-time parameters applied before deployment.
Use `program.applyParams()` to bind them in tests:

```java
static final Program BASE = ValidatorTest.compileValidator(MyParamValidator.class).program();

@Test
void parameterizedValidator() {
    // Apply parameters: owner PKH and minimum ADA
    var parameterized = BASE.applyParams(
            PlutusData.bytes(ownerPkh),
            PlutusData.integer(2_000_000));

    var ctx = buildCtx(ownerPkh);
    BudgetAssertions.assertSuccess(ValidatorTest.evaluate(parameterized, ctx));
}
```

### Testing Trace Output

Use `Builtins.trace()` in your validator for debugging, then verify traces in
tests:

```java
@Test
void validatorTracesExpectedMessages() {
    var result = ValidatorTest.evaluate(program, ctx);
    BudgetAssertions.assertSuccess(result);
    BudgetAssertions.assertTrace(result, "checking signature", "signature valid");
}

@Test
void failureTraceExplainsReason() {
    var result = ValidatorTest.evaluate(program, badCtx);
    BudgetAssertions.assertFailure(result);
    BudgetAssertions.assertTrace(result, "signature check failed");
}
```

### Multi-File Compilation

When your validator depends on helper classes, `compileValidator(Class<?>)`
auto-discovers `@OnchainLibrary` dependencies automatically. For helper classes
without the `@OnchainLibrary` annotation, use the inline `compile()` with
multiple sources:

```java
static final String MATH_LIB = """
        class MathUtils {
            static BigInteger max(BigInteger a, BigInteger b) {
                if (a > b) { return a; } else { return b; }
            }
        }
        """;

static final String VALIDATOR = """
        @SpendingValidator
        class AuctionValidator {
            @Entrypoint
            static boolean validate(BigInteger redeemer, BigInteger ctx) {
                return MathUtils.max(100, 50) == 100;
            }
        }
        """;

@Test
void multiFileCompilation() {
    // ContractTest.compile() accepts additional library sources
    var program = compile(VALIDATOR, MATH_LIB);
    assertValidates(program, PlutusData.integer(0), PlutusData.integer(0));
}
```

> In production code, prefer annotating shared helper classes with
> `@OnchainLibrary` so `compileValidator()` picks them up automatically.

### Direct Java Tests for Stdlib

Test standard library calls directly as Java — useful for verifying business
logic without compiling to UPLC:

```java
@Nested
class StdlibDirectTests {
    @Test
    void valueOperationsWork() {
        var value = Value.lovelace(BigInteger.valueOf(10_000_000));
        assertEquals(BigInteger.valueOf(10_000_000), value.lovelaceOf());
    }

    @Test
    void intervalContainmentWorks() {
        var interval = Interval.between(BigInteger.valueOf(1000), BigInteger.valueOf(2000));
        assertTrue(IntervalLib.contains(interval, BigInteger.valueOf(1500)));
        assertFalse(IntervalLib.contains(interval, BigInteger.valueOf(500)));
    }
}
```

---

## 11. Quick Reference

### Which Tool for Which Scenario

| Scenario | Tool | Base class |
|----------|------|------------|
| Test a helper method (math, logic, string) | `JulcEval` | None needed |
| Test validator with datum + redeemer + ctx | `ValidatorTest` or `ContractTest` | Optional |
| Debug validator logic with IDE breakpoints | Direct Java calls | `ContractTest` |
| Budget regression testing | `BudgetAssertions` | None needed |
| Script size verification | `ScriptAnalysis` | None needed |
| Fuzz with random inputs (100+ trials) | jqwik + `CardanoArbitraries` | None needed |
| Budget statistics across many trials | `BudgetCollector` | None needed |
| Source-mapped error messages | `.sourceMap()` / `compileWithSourceMap()` | Either |
| End-to-end with real transactions | Yaci DevKit + cardano-client-lib | None needed |

### Module Dependencies

| Module | Provides |
|--------|----------|
| `julc-testkit` | `ContractTest`, `ValidatorTest`, `ScriptContextTestBuilder`, `BudgetAssertions`, `TestDataBuilder`, `JulcEval`, `ScriptAnalysis` |
| `julc-testkit-jqwik` | `CardanoArbitraries`, `ArbitraryScriptContext`, `BudgetCollector`, `CardanoArbitraryProvider` (SPI) |
| `julc-vm-scalus` | Scalus-based CEK machine (runtime dependency) |

### Minimal Test Setup (Gradle)

```groovy
dependencies {
    testImplementation 'com.bloxbean.cardano:julc-testkit:0.1.0-pre7'
    testRuntimeOnly    'com.bloxbean.cardano:julc-vm-scalus:0.1.0-pre7'
}

test { useJUnitPlatform() }
```

### Minimal Test with Property Testing (Gradle)

```groovy
dependencies {
    testImplementation 'com.bloxbean.cardano:julc-testkit:0.1.0-pre7'
    testImplementation 'com.bloxbean.cardano:julc-testkit-jqwik:0.1.0-pre7'
    testImplementation 'net.jqwik:jqwik:1.9.2'
    testRuntimeOnly    'com.bloxbean.cardano:julc-vm-scalus:0.1.0-pre7'
}

test { useJUnitPlatform() }
```
