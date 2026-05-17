---
name: julc add-test
description: Add a JuLC test method or JVM testkit case for a validator/helper
---

# /julc add-test

Add a test for a JuLC validator or helper. There are two valid styles:

- **JuLC CLI / MCP tests**: `com.bloxbean.cardano.julc.stdlib.test.Test` on `public static boolean` methods. These are compiled and evaluated by `julc_test` / `julc check`.
- **JVM testkit tests**: `org.junit.jupiter.api.Test` methods using `ContractTest`, `ValidatorTest`, and `ScriptContextTestBuilder`. These run under Gradle/Maven/JUnit.

## Steps

1. Identify the **target**:
   - A validator class (look for `@SpendingValidator`, `@MintingValidator`, etc.).
   - Or a helper / `@OnchainLibrary` method (any pure `static` method with deterministic semantics).

2. Identify the **scenario** the user wants to assert. Examples:
   - Vesting before deadline → must fail (or return `false`).
   - Vesting after deadline with beneficiary signature → must succeed.
   - Helper `signedBy(ctx, pkh)` returns `true` when `pkh` is in `signatories`.

3. Pick the right pattern:
   - **Fast MCP loop**: use a JuLC `@Test public static boolean` method. Build on-chain values with ledger constructors and `TestContextLib`; then call `julc_test`.
   - **JVM regression test**: use JUnit + testkit. Build `PlutusData` contexts with `ScriptContextTestBuilder`; then run the project test task.

4. For the MCP / `julc check` path, generate this style:

   ```java
   package myorg;

   import com.bloxbean.cardano.julc.core.PlutusData;
   import com.bloxbean.cardano.julc.core.types.JulcList;
   import com.bloxbean.cardano.julc.ledger.PubKeyHash;
   import com.bloxbean.cardano.julc.ledger.TxId;
   import com.bloxbean.cardano.julc.ledger.TxOut;
   import com.bloxbean.cardano.julc.ledger.TxOutRef;
   import com.bloxbean.cardano.julc.stdlib.Builtins;
   import com.bloxbean.cardano.julc.stdlib.lib.ListsLib;
   import com.bloxbean.cardano.julc.stdlib.test.Test;
   import com.bloxbean.cardano.julc.stdlib.test.TestContextLib;
   import java.math.BigInteger;

   public final class SignedValidatorTest {

       @Test
       public static boolean required_signature_succeeds() {
           var beneficiary = PubKeyHash.of(Builtins.replicateByte(28, 1));
           var datum = new SignedValidator.Datum(beneficiary);
           var redeemer = new SignedValidator.Redeemer();
           var ref = new TxOutRef(TxId.of(Builtins.replicateByte(32, 0)), BigInteger.ZERO);
           var ownInput = TestContextLib.txOut(
                   TestContextLib.pubKeyAddress(beneficiary.hash()),
                   BigInteger.valueOf(5_000_000),
                   (PlutusData)(Object) datum);
           var signers = (JulcList<PubKeyHash>)(Object) ListsLib.prepend(
                   ListsLib.empty(),
                   (PlutusData)(Object) beneficiary);

           var ctx = TestContextLib.spending(
                   ref,
                   ownInput,
                   (PlutusData)(Object) datum,
                   (PlutusData)(Object) redeemer,
                   (JulcList<TxOut>)(Object) ListsLib.empty(),
                   signers);

           return SignedValidator.validate(datum, redeemer, ctx);
       }
   }
   ```

   Key MCP-test touchpoints:

   | Helper | What it does |
   |---|---|
   | `com.bloxbean.cardano.julc.stdlib.test.Test` | marks a JuLC static boolean test |
   | `TestContextLib.spending(...)` | builds an on-chain `ScriptContext` for spending validators |
   | `TestContextLib.txOut(...)` / `pubKeyAddress(...)` | builds typed ledger values that compile to UPLC |
   | `ListsLib.empty()` / `ListsLib.prepend(...)` | builds `JulcList` values for signers/outputs |
   | `Interval.after(...)`, `before(...)`, `between(...)`, `always()` | valid range values; no `Intervals.from(...)` helper exists |

5. For a JVM/JUnit testkit test, generate this style instead:

   ```java
   package myorg;

   import com.bloxbean.cardano.julc.core.PlutusData;
   import com.bloxbean.cardano.julc.ledger.Interval;
   import com.bloxbean.cardano.julc.testkit.ContractTest;
   import com.bloxbean.cardano.julc.testkit.ScriptContextTestBuilder;
   import com.bloxbean.cardano.julc.testkit.TestDataBuilder;
   import org.junit.jupiter.api.Test;
   import java.math.BigInteger;

   import static org.junit.jupiter.api.Assertions.assertTrue;

   final class VestingValidatorJvmTest extends ContractTest {

       @Test
       void vesting_after_deadline_with_signature_succeeds() {
           var beneficiary = TestDataBuilder.randomPubKeyHash_typed();
           var datum = PlutusData.constr(0,
                   PlutusData.bytes(beneficiary.hash()),
                   PlutusData.integer(100));
           var ref = TestDataBuilder.randomTxOutRef_typed();

           var ctx = ScriptContextTestBuilder.spending(ref, datum)
                   .signer(beneficiary)
                   .validRange(Interval.after(BigInteger.valueOf(150)))
                   .buildPlutusData();

           var program = compileValidatorWithSourceMap(VestingValidator.class);
           assertTrue(evaluateWithTrace(program, ctx).isSuccess());
       }
   }
   ```

6. **Run before reporting completion**:
   - For JuLC static tests: `julc_test` against the test file.
   - For JVM/JUnit tests: the repo's Gradle/Maven test task.

7. Persist under `test/` for JuLC static tests, or `src/test/java/` for Gradle/Maven JUnit projects. Multiple tests may live in one class.

## Conventions

- Use `com.bloxbean.cardano.julc.stdlib.test.Test` only for `public static boolean` JuLC tests.
- Use `org.junit.jupiter.api.Test` only for JVM/JUnit tests.
- Test method names describe the scenario in plain English.
- Each test is independent — no shared mutable state.
- Use `JulcList.of(...)` / `JulcMap.of(...)` for inline collections; never `new ListData(...)`.
- For PubKeyHash/ScriptHash etc., use the `Type.of(byte[])` factory rather than raw casts.

## Output

```
✔ test/myorg/SignedValidatorTest.java
  Added: required_signature_succeeds
  Run:   1 passed, 0 failed (CPU 2.1M, mem 7.8K)
```
