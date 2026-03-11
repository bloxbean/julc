<p align="center">
  <img src="static/logo-light.svg" alt="JuLC Logo" width="300"/>
</p>

> ## **Warning**
> **Experimental / Research Project**
>
> Most of the code in this project is generated using AI, with human-assisted design, testing, and verification.
> This is an experimental project created mainly for research and exploration purposes.
>
> **Please do not use this in production.** Expect rough edges, incomplete features, and potential bugs.

# JuLC

**Java UPLC Compiler for Cardano**

*Pronounced “jool-see” (J-U-L-C), or simply “jules”*

Write Cardano smart contracts in Java and compile them to Plutus V3 UPLC. julc provides a complete
toolchain: a Java-subset compiler, a pluggable VM for local evaluation, a standard library of on-chain
operations, and first-class integration with [cardano-client-lib](https://github.com/bloxbean/cardano-client-lib).

## Powered by Scalus

JuLC's end-to-end experience — from local testing to script evaluation — is powered by
[Scalus](https://scalus.org/), a Scala implementation of the Plutus VM (CEK machine).
JuLC uses the Scalus VM as its evaluation backend for local validator testing, cost estimation,
and the testkit. Huge thanks to the Scalus team for building and open-sourcing a high-quality
Plutus VM that made this project possible.

## Features

- **Java-to-UPLC compiler** — write validators in a familiar Java subset, compile to Plutus V3
- **Typed ledger access** — `ScriptContext`, `TxInfo`, `TxOut`, `Value` with typed field access and chaining
- **Records and sealed interfaces** — data modeling with pattern matching, switch expressions, and exhaustiveness checking
- **Instance methods** — `list.contains()`, `value.lovelaceOf()`, `map.get()`, `optional.isPresent()` and more
- **Lambda expressions and HOFs** — `ListsLib.map()`, `filter()`, `foldl()`, `any()`, `all()`, `find()`, `zip()`
- **Nested loops** — for-each and while loops with nesting, multi-accumulator, and break support
- **Standard library** — 11 libraries: math, lists, maps, values, intervals, crypto, bitwise, output, address, contexts, byte strings
- **@NewType** — zero-cost type aliases for single-field records
- **Tuple2/Tuple3** — generic tuples with auto-unwrapping field access
- **Type.of() factories** — `PubKeyHash.of(bytes)`, `PolicyId.of(bytes)`, etc. for ledger hash types
- **JulcList/JulcMap** — typed collection interfaces with IDE autocomplete for on-chain methods
- **Multi-validator** — `@MultiValidator` for handling multiple script purposes (mint + spend + withdraw, etc.) in a single compiled script
- **Annotation processor** — `@SpendingValidator`, `@MintingValidator`, `@MultiValidator`, `@Entrypoint` for compile-time code generation
- **Pluggable VM** — evaluate UPLC programs locally via SPI (Scalus backend included)
- **Testkit** — test validators locally without a running node
- **Gradle plugin** — compile validators and bundle on-chain sources as part of your build
- **cardano-client-lib integration** — deploy and submit transactions with compiled scripts

## Modules

| Module | Description |
|--------|-------------|
| `julc-core` | UPLC AST, CBOR/FLAT serialization |
| `julc-vm` | VM SPI interface |
| `julc-vm-scalus` | Scalus-based VM backend |
| `julc-ledger-api` | ScriptContext, TxInfo, and ledger types |
| `julc-compiler` | Java source to UPLC compiler |
| `julc-stdlib` | On-chain standard library |
| `julc-testkit` | Testing utilities for validators |
| `julc-cardano-client-lib` | cardano-client-lib integration |
| `julc-gradle-plugin` | Gradle build plugin |
| `julc-annotation-processor` | Compile-time annotation processor |

## Known Limitations

JuLC compiles a safe subset of Java to UPLC. Key limitations to be aware of:

- **`default` branches in switch expressions** work as catch-alls for uncovered variants, but prefer explicit cases for all variants of sealed interfaces for clarity
- **`@Param` fields**: always use `PlutusData` as the type for `@Param` fields. Other supported types are `byte[]`, `BigInteger`, `String`, records, sealed interfaces, and `@NewType`. **Never** use `PlutusData.BytesData`, `PlutusData.MapData`, `PlutusData.ListData`, or `PlutusData.IntData` — these cause double-wrapping and cross-library type mismatches at runtime
- **No `Function.apply()`** — lambdas work with HOFs (`list.map(x -> ...)`, `list.filter(...)`) but cannot be stored in `Function<T,R>` variables and called via `.apply()`
- **Immutable variables** — variables cannot be reassigned except as loop accumulators in `while`/`for-each`

For the full list of compiler limitations and workarounds, see the [Compiler Limitations](docs/getting-started.md#16-compiler-limitations) section in the Getting Started guide.

## Examples Repositories
- [julc-helloworld](https://github.com/bloxbean/julc-helloworld) - A simple vesting contract with on-chain and off-chain code, plus tests
- [julc-examples](https://github.com/bloxbean/julc-examples) - A collection of more complex validators demonstrating various features and patterns

## Quick Start

### Dependencies

```groovy
dependencies {
    implementation "com.bloxbean.cardano:julc-stdlib:${julcVersion}"
    implementation "com.bloxbean.cardano:julc-ledger-api:${julcVersion}"

    // Annotation processor -- compiles validators during javac
    annotationProcessor "com.bloxbean.cardano:julc-annotation-processor:${julcVersion}"

    // Test: VM for local evaluation
    testImplementation "com.bloxbean.cardano:julc-testkit:${julcVersion}"
    testImplementation "com.bloxbean.cardano:julc-vm:${julcVersion}"
    testRuntimeOnly "com.bloxbean.cardano:julc-vm-scalus:${julcVersion}"
}
```

For detailed dependencies, check the [getting started](docs/getting-started.md) guide or the `julc-helloworld` example at https://github.com/bloxbean/julc-helloworld.

### Current Preview Version

**`0.1.0-pre3`**

```groovy
ext.julcVersion = '0.1.0-pre3'
```

### Using Snapshot Builds

Snapshot versions include the Git commit hash for traceability, e.g. `0.1.0-055d17f-SNAPSHOT`.

**Current snapshot version**: `0.1.0-055d17f-SNAPSHOT`. Check here for the latest snapshot commit ID: https://github.com/bloxbean/julc/actions/workflows/snapshot.yml

To use snapshots, add the Sonatype snapshot repository:

**Gradle**

```groovy
repositories {
    mavenCentral()
    maven {
        url "https://central.sonatype.com/repository/maven-snapshots"
    }
}
```

**Maven**

```xml
<repositories>
    <repository>
        <id>snapshots-repo</id>
        <url>https://central.sonatype.com/repository/maven-snapshots</url>
        <releases>
            <enabled>false</enabled>
        </releases>
        <snapshots>
            <enabled>true</enabled>
        </snapshots>
    </repository>
</repositories>
```

Then use the snapshot version in your dependencies:

```groovy
implementation "com.bloxbean.cardano:julc-stdlib:${julcVersion}"
```

### Write a Spending Validator

```java
@SpendingValidator
public class VestingValidator {
    record VestingDatum(PubKeyHash beneficiary, BigInteger deadline) {}

    @Entrypoint
    static boolean validate(VestingDatum datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();

        // Check that the beneficiary signed the transaction
        boolean signed = txInfo.signatories().contains(datum.beneficiary());

        // Check that the deadline has passed (lower bound of valid range > deadline)
        // Just a dummy check to demonstrate using the datum's deadline field.
        boolean pastDeadline = datum.deadline().compareTo(BigInteger.ZERO) > 0;

        return signed && pastDeadline;
    }
}
```

### Write a Minting Validator with Sealed Interface Redeemer

```java
@MintingValidator
public class TokenPolicy {
    sealed interface Action permits Mint, Burn {}
    record Mint(BigInteger amount) implements Action {}
    record Burn() implements Action {}

    @Entrypoint
    static boolean validate(Action action, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        return switch (action) {
            case Mint m -> m.amount().compareTo(BigInteger.ZERO) > 0 && !txInfo.signatories().isEmpty();
            case Burn b -> true;
        };
    }
}
```

### Write a Multi-Validator (Mint + Spend)

```java
@MultiValidator
public class TokenManager {

    @Entrypoint(purpose = Purpose.MINT)
    static boolean mint(PlutusData redeemer, ScriptContext ctx) {
        return !ctx.txInfo().signatories().isEmpty();
    }

    @Entrypoint(purpose = Purpose.SPEND)
    static boolean spend(PlutusData redeemer, ScriptContext ctx) {
        return true;
    }
}
```

### Load Compiled Script at Runtime

During a Gradle build, the `@SpendingValidator` and `@MintingValidator` annotated classes are compiled to UPLC and saved as JSON files in `META-INF/plutus/` inside the JAR. You can load these compiled scripts at runtime using `JulcScriptLoader`:

```java
PlutusV3Script script = JulcScriptLoader.load(VestingValidator.class);
// Use `script` for transaction building with cardano-client-lib
```

### Programmatically Compile and Evaluate

```java
var stdlib = StdlibRegistry.defaultRegistry();
var compiler = new JulcCompiler(stdlib::lookup);

var result = compiler.compile(javaSource);
if (!result.hasErrors()) {
    Program program = result.program();
    // Ready for serialization and on-chain deployment
}
```

### Test Locally

```java
var vm = JulcVm.create();
var evalResult = vm.evaluateWithArgs(program, datum, redeemer, scriptContext);
assertTrue(evalResult.isSuccess());
```

`julc-testkit` provides utilities for unit testing your validators locally.

## Requirements

- **Java 24+**
- **Gradle 9+**

## Documentation

| Guide | Description |
|-------|-------------|
| [Getting Started](docs/getting-started.md) | Comprehensive guide: validators, data modeling, collections, control flow, stdlib, testing, deployment |
| [API Reference](docs/api-reference.md) | All supported types, operators, methods, and ledger access |
| [Standard Library Guide](docs/stdlib-guide.md) | All 11 stdlib libraries with usage examples |
| [Advanced Guide](docs/advanced-guide.md) | Low-level PlutusData patterns, type casting, raw list/map manipulation, debugging |
| [For-Loop Patterns](docs/for-loop-patterns.md) | For-each, while, nested loops, multi-accumulator, break |
| [Library Developer Guide](docs/library-developer-guide.md) | Writing `@OnchainLibrary` modules and PIR API |
| [Troubleshooting](docs/troubleshooting.md) | Every compiler error, common mistakes, and FAQ |
| [Compiler Developer Guide](docs/compiler-developer-guide.md) | Internal architecture for compiler contributors |
| [Type Method Comparison](docs/type-method-compilation-comparison.md) | How JuLC compares to Opshin and Scalus |

## License

MIT
