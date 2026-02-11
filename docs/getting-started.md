# Getting Started with julc

Write Cardano smart contracts in Java and compile them to Plutus V3 UPLC.

## Prerequisites

- **Java 25** (GraalVM recommended)
- **Gradle 9+**
- Familiarity with Cardano's eUTxO model

## Project Setup

Add the julc modules to your `build.gradle`:

```groovy
repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    // Core compiler + stdlib
    implementation 'com.bloxbean.cardano:julc-compiler:0.1.0-SNAPSHOT'
    implementation 'com.bloxbean.cardano:julc-stdlib:0.1.0-SNAPSHOT'

    // VM for local evaluation (test only)
    testImplementation 'com.bloxbean.cardano:julc-testkit:0.1.0-SNAPSHOT'
    testImplementation 'com.bloxbean.cardano:julc-vm:0.1.0-SNAPSHOT'
    testRuntimeOnly 'com.bloxbean.cardano:julc-vm-scalus:0.1.0-SNAPSHOT'

    // cardano-client-lib integration (for on-chain deployment)
    implementation 'com.bloxbean.cardano:julc-cardano-client-lib:0.1.0-SNAPSHOT'
    implementation 'com.bloxbean.cardano:cardano-client-lib:0.7.1'
}
```

Enable preview features (required for Java 25):

```groovy
tasks.withType(JavaCompile).configureEach {
    options.compilerArgs.addAll(['--enable-preview'])
}

tasks.withType(Test).configureEach {
    jvmArgs '--enable-preview'
}
```

## Writing a Spending Validator

A spending validator locks ADA at a script address and controls who can spend it.

```java
@Validator
class VestingValidator {
    record VestingDatum(PlutusData beneficiary, BigInteger deadline) {}

    @Entrypoint
    static boolean validate(VestingDatum datum, PlutusData redeemer, PlutusData ctx) {
        PlutusData txInfo = ContextsLib.getTxInfo(ctx);
        return ContextsLib.signedBy(txInfo, datum.beneficiary());
    }
}
```

Key annotations:
- **`@Validator`** marks the class as a spending validator
- **`@Entrypoint`** marks the validation function (must be `static`, return `boolean`)
- Parameters: `(datum, redeemer, scriptContext)` for spending, `(redeemer, scriptContext)` for 2-param

### Record Types as Datum

Java records compile to Plutus Data constructor types. Field access (`datum.beneficiary()`) compiles to Data navigation:

```
HeadList(TailList^n(SndPair(UnConstrData(data))))
```

Where `n` is the zero-based field index. Fields decode automatically based on type:
- `BigInteger` fields → `UnIData`
- `byte[]` fields → `UnBData`
- `PlutusData` fields → pass-through (no unwrap)

## Writing a Minting Policy

```java
@MintingPolicy
class AuthorizedMinting {
    @Entrypoint
    static boolean validate(PlutusData redeemer, PlutusData ctx) {
        PlutusData txInfo = ContextsLib.getTxInfo(ctx);
        return ContextsLib.signedBy(txInfo, redeemer);
    }
}
```

Minting policies always take 2 parameters: `(redeemer, scriptContext)`.

## Compiling

```java
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;

// Create compiler with stdlib support
var stdlib = StdlibRegistry.defaultRegistry();
var compiler = new JulcCompiler(stdlib::lookup);

// Compile Java source to UPLC Program
var result = compiler.compile(javaSource);
if (result.hasErrors()) {
    System.err.println("Errors: " + result.diagnostics());
} else {
    Program program = result.program();
    // program is a Plutus V3 UPLC program ready for serialization
}
```

Without stdlib (for simple validators that don't call library functions):

```java
var compiler = new JulcCompiler();
var result = compiler.compile(javaSource);
```

## Testing Validators

The `julc-testkit` module provides test utilities for evaluating validators locally without a blockchain.

### Basic Evaluation

```java
import com.bloxbean.cardano.julc.testkit.ValidatorTest;
import com.bloxbean.cardano.julc.core.PlutusData;

// Compile and evaluate in one step
String source = """
    @Validator
    class AlwaysTrue {
        @Entrypoint
        static boolean validate(PlutusData redeemer, PlutusData ctx) {
            return true;
        }
    }
    """;

// Build a minimal ScriptContext
PlutusData ctx = PlutusData.constr(0,
    PlutusData.integer(0),  // txInfo (simplified)
    PlutusData.integer(0),  // redeemer
    PlutusData.integer(0)   // scriptInfo
);

EvalResult result = ValidatorTest.evaluate(source, ctx);
assertTrue(result.isSuccess());
```

### With Stdlib

```java
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;

var stdlib = StdlibRegistry.defaultRegistry();
var program = ValidatorTest.compile(source, stdlib::lookup);
var result = ValidatorTest.evaluate(program, ctx);
```

### Assertion Helpers

```java
// Assert validator accepts
ValidatorTest.assertValidates(program, ctx);

// Assert validator rejects
ValidatorTest.assertRejects(program, ctx);
```

### Budget Assertions

```java
import com.bloxbean.cardano.julc.testkit.BudgetAssertions;

var result = ValidatorTest.evaluate(program, ctx);

// Check that execution budget is within limits
BudgetAssertions.assertBudgetUnder(result, 1_000_000L, 500_000L);

// Check trace messages
BudgetAssertions.assertTrace(result, "expected message");
```

### Building ScriptContext for Tests

V3 ScriptContext structure (as Plutus Data):

```
ScriptContext = Constr(0, [txInfo, redeemer, scriptInfo])

TxInfo = Constr(0, [
    inputs,           // field 0
    referenceInputs,  // field 1
    outputs,          // field 2
    fee,              // field 3
    mint,             // field 4
    certs,            // field 5
    withdrawals,      // field 6
    validRange,       // field 7
    signatories,      // field 8  <-- list of PubKeyHash
    redeemers,        // field 9
    datums,           // field 10
    id,               // field 11
    votes,            // field 12
    proposalProcedures, // field 13
    currentTreasury,  // field 14
    treasuryDonation  // field 15
])

// Spending ScriptInfo
ScriptInfo = Constr(1, [txOutRef, optionalDatum])

// Minting ScriptInfo
ScriptInfo = Constr(0, [policyId])
```

Example building a minting context with signatories:

```java
var pkh = new byte[]{1, 2, 3, ...};  // 28-byte PubKeyHash
var sigsList = PlutusData.list(PlutusData.bytes(pkh));
var zero = PlutusData.integer(0);

var txInfo = PlutusData.constr(0,
    zero, zero, zero, zero, zero, zero, zero, zero,
    sigsList,  // signatories at index 8
    zero, zero, zero, zero, zero, zero, zero);

var scriptInfo = PlutusData.constr(0, PlutusData.bytes(new byte[28]));
var ctx = PlutusData.constr(0, txInfo, PlutusData.bytes(pkh), scriptInfo);
```

## Standard Library Reference

The standard library provides pre-compiled UPLC functions callable from validator source code.

### ContextsLib

| Method | Description |
|--------|-------------|
| `ContextsLib.getTxInfo(ctx)` | Extract TxInfo from ScriptContext (field 0) |
| `ContextsLib.getRedeemer(ctx)` | Extract redeemer from ScriptContext (field 1) |
| `ContextsLib.signedBy(txInfo, pkh)` | Check if txInfo's signatories contain the given PubKeyHash |
| `ContextsLib.getSpendingDatum(ctx)` | Extract Optional datum from spending ScriptInfo |

### ListsLib

| Method | Description |
|--------|-------------|
| `ListsLib.find(list, elem)` | Check if element exists in a Data list (equality via SerialiseData) |
| `ListsLib.length(list)` | Count elements in a Data list |
| `ListsLib.isEmpty(list)` | Check if a Data list is empty |

### ValuesLib

| Method | Description |
|--------|-------------|
| `ValuesLib.valueOf(value, cs, tn)` | Get quantity of a specific token from a Value |
| `ValuesLib.geq(a, b)` | Check if Value `a` >= Value `b` (ADA only) |
| `ValuesLib.assetOf(value, cs, tn)` | Get asset amount from a nested Value structure |

### CryptoLib

| Method | Description |
|--------|-------------|
| `CryptoLib.sha2_256(data)` | SHA2-256 hash |
| `CryptoLib.sha3_256(data)` | SHA3-256 hash |
| `CryptoLib.blake2b_256(data)` | Blake2b-256 hash |

### IntervalLib

| Method | Description |
|--------|-------------|
| `IntervalLib.contains(interval, point)` | Check if a time interval contains a given point |

## Deploying to a Network

### Converting to PlutusV3Script

Use `JulcScriptAdapter` to convert a compiled Program to a cardano-client-lib `PlutusV3Script`:

```java
import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;

Program program = compiler.compile(source).program();
PlutusV3Script script = JulcScriptAdapter.fromProgram(program);

// Get the script hash (policy ID for minting policies)
String scriptHash = JulcScriptAdapter.scriptHash(program);
```

### Locking ADA to a Script Address

```java
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;

// Derive the script address
String scriptAddress = AddressProvider
    .getEntAddress(script, Networks.testnet())
    .toBech32();

// Build and submit the lock transaction
var datum = BigIntPlutusData.of(42);
var lockTx = new Tx()
    .payToContract(scriptAddress, Amount.ada(5), datum)
    .from(account.baseAddress());

var result = quickTxBuilder.compose(lockTx)
    .withSigner(SignerProviders.signerFrom(account))
    .completeAndWait();
```

### Unlocking from a Script Address

```java
import com.bloxbean.cardano.client.quicktx.ScriptTx;

// Find the script UTXO
var utxos = backendService.getUtxoService()
    .getUtxos(scriptAddress, 100, 1);
var scriptUtxo = utxos.getValue().get(0);

// Build and submit the unlock transaction
var redeemer = BigIntPlutusData.of(0);
var unlockTx = new ScriptTx()
    .collectFrom(scriptUtxo, redeemer)
    .payToAddress(account.baseAddress(), Amount.ada(4))
    .attachSpendingValidator(script);

var result = quickTxBuilder.compose(unlockTx)
    .withSigner(SignerProviders.signerFrom(account))
    .feePayer(account.baseAddress())
    .collateralPayer(account.baseAddress())
    .completeAndWait();
```

### Minting Tokens

```java
import com.bloxbean.cardano.client.transaction.spec.Asset;

var asset = new Asset("MyToken", BigInteger.valueOf(100));
var redeemer = BigIntPlutusData.of(0);

var mintTx = new ScriptTx()
    .mintAsset(script, asset, redeemer);

var result = quickTxBuilder.compose(mintTx)
    .withSigner(SignerProviders.signerFrom(account))
    .feePayer(account.baseAddress())
    .collateralPayer(account.baseAddress())
    .completeAndWait();
```

### PlutusData Conversion

Convert between julc's `PlutusData` and cardano-client-lib's `PlutusData`:

```java
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;

// julc → cardano-client-lib
var ourData = PlutusData.constr(0, PlutusData.integer(42));
var clientData = PlutusDataAdapter.toClientLib(ourData);

// cardano-client-lib → julc
var backToOurs = PlutusDataAdapter.fromClientLib(clientData);
```

## File-Based Validator Development (Gradle Plugin)

For the best development experience, write validators as **real Java files** with full IDE support (autocomplete, type checking, syntax highlighting).

### Setup

Apply the Plutus Gradle plugin in your `build.gradle`:

```groovy
plugins {
    id 'java'
    id 'com.bloxbean.cardano.julc' version '0.1.0-SNAPSHOT'
}

dependencies {
    // On-chain API for IDE support (annotations + stdlib stubs)
    compileOnly 'com.bloxbean.cardano:julc-onchain-api:0.1.0-SNAPSHOT'
}
```

### Writing Validators as Java Files

Create validator source files in `src/main/plutus/`:

```java
// src/main/plutus/VestingValidator.java
package com.example.contracts;

import com.bloxbean.cardano.julc.onchain.annotation.Validator;
import com.bloxbean.cardano.julc.onchain.annotation.Entrypoint;
import com.bloxbean.cardano.julc.onchain.stdlib.ContextsLib;
import com.bloxbean.cardano.julc.core.PlutusData;

@Validator
class VestingValidator {
    @Entrypoint
    static boolean validate(PlutusData redeemer, PlutusData ctx) {
        PlutusData txInfo = ContextsLib.getTxInfo(ctx);
        return ContextsLib.signedBy(txInfo, redeemer);
    }
}
```

Your IDE provides autocomplete on `ContextsLib.`, type checking on `PlutusData`, and annotation recognition.

### Building

```
$ gradle build
> Task :compilePlutus
Compiled VestingValidator.java → VestingValidator.json (hash: 19b1de27...)

BUILD SUCCESSFUL
```

### Build Output

Each compiled validator produces a JSON file in `build/plutus/`:

```json
{
  "type": "PlutusScriptV3",
  "description": "VestingValidator",
  "cborHex": "4d4c010100253335...",
  "hash": "19b1de272327e28670d69f6820c0298d19b05497e04d78571f7e6bc3"
}
```

### Loading Compiled Scripts in Off-Chain Code

```java
String json = Files.readString(Path.of("build/plutus/VestingValidator.json"));
// Parse JSON and extract cborHex
PlutusV3Script script = PlutusV3Script.builder()
    .cborHex(cborHex)
    .build();
```

### Configuration

The plugin supports optional configuration:

```groovy
plutus {
    sourceDir = file('src/main/plutus')     // default
    outputDir = file("${buildDir}/plutus")   // default
}
```

## Annotation Processor (Recommended)

The annotation processor lets you write validators as **normal Java classes** in `src/main/java/` with full IDE support. Validators are compiled to UPLC during `javac` and loaded from the classpath at runtime. Works with both Gradle and Maven.

### Gradle Setup

```groovy
plugins {
    id 'java'
}

repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    // On-chain API — annotations, ledger types, and stdlib stubs for IDE support
    implementation 'com.bloxbean.cardano:julc-onchain-api:0.1.0-SNAPSHOT'

    // Annotation processor — compiles validators during javac
    annotationProcessor 'com.bloxbean.cardano:julc-annotation-processor:0.1.0-SNAPSHOT'

    // Runtime — load pre-compiled scripts from classpath
    implementation 'com.bloxbean.cardano:julc-annotation-processor:0.1.0-SNAPSHOT'

    // cardano-client-lib — for off-chain transaction building
    implementation 'com.bloxbean.cardano:cardano-client-lib:0.7.1'
}

tasks.withType(JavaCompile).configureEach {
    options.compilerArgs.addAll(['--enable-preview'])
}
```

### Maven Setup

```xml
<dependencies>
    <dependency>
        <groupId>com.bloxbean.cardano</groupId>
        <artifactId>julc-onchain-api</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </dependency>
    <dependency>
        <groupId>com.bloxbean.cardano</groupId>
        <artifactId>julc-annotation-processor</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </dependency>
    <dependency>
        <groupId>com.bloxbean.cardano</groupId>
        <artifactId>cardano-client-lib</artifactId>
        <version>0.7.1</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <version>3.13.0</version>
            <configuration>
                <release>24</release>
                <compilerArgs>
                    <arg>--enable-preview</arg>
                </compilerArgs>
                <annotationProcessorPaths>
                    <path>
                        <groupId>com.bloxbean.cardano</groupId>
                        <artifactId>julc-annotation-processor</artifactId>
                        <version>0.1.0-SNAPSHOT</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

### Writing a Validator

Write your validator as a normal Java class in `src/main/java/`. You get full IDE support — autocomplete, type checking, refactoring, and navigation all work.

```java
// src/main/java/com/example/VestingValidator.java
package com.example;

import com.bloxbean.cardano.julc.onchain.annotation.Validator;
import com.bloxbean.cardano.julc.onchain.annotation.Entrypoint;
import com.bloxbean.cardano.julc.onchain.ledger.ScriptContext;
import com.bloxbean.cardano.julc.onchain.ledger.TxInfo;
import com.bloxbean.cardano.julc.core.PlutusData;
import java.math.BigInteger;

@Validator
public class VestingValidator {
    record VestingDatum(byte[] beneficiary, BigInteger deadline) {}

    @Entrypoint
    static boolean validate(VestingDatum datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        boolean hasSigner = txInfo.signatories().contains(datum.beneficiary());
        return hasSigner && datum.deadline() > 0;
    }
}
```

For a minting policy, use `@MintingPolicy` instead of `@Validator`:

```java
package com.example;

import com.bloxbean.cardano.julc.onchain.annotation.MintingPolicy;
import com.bloxbean.cardano.julc.onchain.annotation.Entrypoint;
import com.bloxbean.cardano.julc.onchain.ledger.ScriptContext;
import com.bloxbean.cardano.julc.onchain.ledger.TxInfo;
import com.bloxbean.cardano.julc.core.PlutusData;

@MintingPolicy
public class AuthorizedMinting {
    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        return txInfo.signatories().contains(redeemer);
    }
}
```

### What Happens at Compile Time

When you run `javac` (via `gradle build` or `mvn compile`), the annotation processor:

1. Finds all classes annotated with `@Validator` or `@MintingPolicy`
2. Reads the source file via the compiler's `Trees` API
3. Compiles the validator to a UPLC program using `JulcCompiler`
4. FLAT-encodes and double-CBOR-wraps the program
5. Writes the result to `META-INF/plutus/<ClassName>.plutus.json` in the class output directory

The JSON file ends up on the classpath alongside the compiled `.class` files:

```
build/classes/java/main/
    com/example/VestingValidator.class
    META-INF/plutus/VestingValidator.plutus.json
```

The JSON file contains the compiled script in text envelope format:

```json
{
  "type": "PlutusScriptV3",
  "description": "VestingValidator",
  "cborHex": "590194590191010100...",
  "hash": "a1b2c3d4e5f6..."
}
```

### Loading Scripts at Runtime

Use `JulcScriptLoader` to load pre-compiled scripts from the classpath:

```java
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;

// Load the compiled script — ready for cardano-client-lib
PlutusV3Script script = JulcScriptLoader.load(VestingValidator.class);

// Get the script hash (also the policy ID for minting policies)
String hash = JulcScriptLoader.scriptHash(VestingValidator.class);

// Get the full output metadata
ValidatorOutput output = JulcScriptLoader.loadOutput(VestingValidator.class);
```

### Using the Loaded Script with cardano-client-lib

```java
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.quicktx.ScriptTx;

// Load the pre-compiled script
PlutusV3Script script = JulcScriptLoader.load(VestingValidator.class);

// Derive the script address
String scriptAddress = AddressProvider
    .getEntAddress(script, Networks.testnet())
    .toBech32();

// Lock ADA to the script
var lockTx = new Tx()
    .payToContract(scriptAddress, Amount.ada(5), datum)
    .from(account.baseAddress());

// Unlock from the script
var unlockTx = new ScriptTx()
    .collectFrom(scriptUtxo, redeemer)
    .payToAddress(account.baseAddress(), Amount.ada(4))
    .attachSpendingValidator(script);
```

### Annotation Processor vs Gradle Plugin

| Feature | Annotation Processor | Gradle Plugin |
|---------|---------------------|---------------|
| IDE support | Full (validators in `src/main/java`) | Partial (files in `src/main/plutus`) |
| Build tool | Gradle + Maven + any javac | Gradle only |
| Validator loading | `JulcScriptLoader.load(MyValidator.class)` | Manual JSON file reading |
| Configuration | Zero-config (just add dependency) | `plutus { }` block |
| File location | `src/main/java/` (normal Java) | `src/main/plutus/` (separate) |

The annotation processor is recommended for new projects. The Gradle plugin is still available for projects that prefer to keep validator source separate from application code.

## Compiler Limitations

The current compiler supports a safe Java subset for on-chain code:

- **Immutable variables only**: No assignment (`x = x + 1` not supported)
- **No uninitialized variables**: All variables must be initialized at declaration
- **No lambda `.apply()`**: Lambda expressions compile but calling methods on them is not yet supported
- **Supported types**: `BigInteger`, `boolean`, `byte[]`, `PlutusData`, records, sealed interfaces
- **No standard Java library**: Only `BigInteger` arithmetic and the julc stdlib are available on-chain
- **Supported control flow**: `if/else`, `switch` expressions, `for`/`while` loops (desugared to recursion), pattern matching
- **Records**: Java records compile to Plutus Data constructors with field access support
- **Sealed interfaces**: Sealed interfaces with record implementations compile to tagged union types
- **Recursion**: Recursive methods use the Z-combinator for safe strict-language recursion
