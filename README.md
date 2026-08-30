<p align="center">
  <img src="static/julc-logo.svg" height="200" alt="JuLC — Java UPLC Compiler for Cardano" width="100%"/>
</p>

<p align="center">
  <a href="https://github.com/bloxbean/julc/actions/workflows/build.yml"><img src="https://github.com/bloxbean/julc/actions/workflows/build.yml/badge.svg" alt="Build & Test"/></a>
  <a href="https://central.sonatype.com/artifact/com.bloxbean.cardano/julc-core"><img src="https://img.shields.io/maven-central/v/com.bloxbean.cardano/julc-core.svg?label=Maven%20Central" alt="Maven Central"/></a>
  <a href="https://github.com/bloxbean/julc/releases"><img src="https://img.shields.io/github/v/release/bloxbean/julc?include_prereleases&label=release" alt="GitHub Release"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/bloxbean/julc.svg" alt="License"/></a>
  <img src="https://img.shields.io/badge/Java-25-orange.svg" alt="Java 25"/>
  <img src="https://img.shields.io/badge/Plutus-V3-blue.svg" alt="Plutus V3"/>
</p>

> ## **Preview Status**
> **JuLC** is currently in preview and under active development. Core functionality is available and usable for experimentation and testnet development, but some language features and edge cases are still being implemented and hardened.
>
> APIs and compiler behavior may change between releases. **Production use is not yet recommended**.

# JuLC

**Java UPLC Compiler for Cardano**

*Pronounced “jool-see” (J-U-L-C), or simply “jules”*

Write Cardano smart contracts in Java and compile them to the pinned Plutus V3 / protocol 11 /
UPLC 1.1.0 target. julc provides a complete
toolchain: a Java-subset compiler, a pluggable VM for local evaluation, a standard library of on-chain
operations, and first-class integration with [cardano-client-lib](https://github.com/bloxbean/cardano-client-lib).

## Evaluation backends

JuLC supports two VM choices for local evaluation: its pure-Java VM and the
[Scalus](https://scalus.org/) backend. Both can evaluate JuLC-generated UPLC.
The Java VM additionally integrates with JuLC's complete compiler-target
provenance for explicit protocol-aware evaluation and cost selection. Scalus is
a supported alternative evaluator and provides a valuable independent
cross-check of generated programs. Users can choose the backend that best fits
their application. Huge thanks to the Scalus team for building and
open-sourcing a high-quality Plutus VM that made this project possible.

## Features

- **Java-to-UPLC compiler** — write validators in a familiar Java subset, compile to Plutus V3
- **Typed ledger access** — `ScriptContext`, `TxInfo`, `TxOut`, `Value` with typed field access and chaining
- **Records and sealed interfaces** — data modeling with pattern matching, switch expressions, and exhaustiveness checking
- **Strict typed boundaries** — canonical datum/redeemer tags, arities, fields, containers, and productive recursion are checked before validator code
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
- **Pluggable VM** — choose the pure-Java VM or Scalus backend for local UPLC evaluation
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
| `julc-verification` | Typed Java security-property annotations and processors |

## Known Limitations

JuLC compiles a safe subset of Java to UPLC. Key limitations to be aware of:

- **`default` branches in switch expressions** work as catch-alls for uncovered variants, but prefer explicit cases for all variants of sealed interfaces for clarity
- **`@Param` fields**: always use `PlutusData` as the type for `@Param` fields. Other supported types are `byte[]`, `BigInteger`, `String`, records, sealed interfaces, and `@NewType`. **Never** use `PlutusData.BytesData`, `PlutusData.MapData`, `PlutusData.ListData`, or `PlutusData.IntData` — these cause double-wrapping and cross-library type mismatches at runtime
- **No `Function.apply()`** — lambdas work with HOFs (`list.map(x -> ...)`, `list.filter(...)`) but cannot be stored in `Function<T,R>` variables and called via `.apply()`
- **Immutable variables** — variables cannot be reassigned except as loop accumulators in `while`/`for-each`

For the full list of compiler limitations and workarounds, see the [Compiler Limitations](docs/src/content/docs/getting-started.md#16-compiler-limitations) section in the Getting Started guide.

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
    testRuntimeOnly "com.bloxbean.cardano:julc-vm-java:${julcVersion}"
}
```

For detailed dependencies, check the [getting started](docs/src/content/docs/getting-started.md) guide or the `julc-helloworld` example at https://github.com/bloxbean/julc-helloworld.

### Compiler target

JuLC currently compiles exactly one profile:
`plutus-v3-pv11-uplc-1.1.0`. Existing APIs default to that named profile;
“latest” is intentionally not a target, and unknown future protocol versions
fail closed.

```java
var options = new CompilerOptions()
        .setTarget(CompilerTarget.PLUTUS_V3_PV11);
var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), options);
```

The Gradle plugin accepts the same stable profile ID:

```groovy
julc {
    target = 'plutus-v3-pv11-uplc-1.1.0'
    optimization = 'pv11-safe'
}
```

Optimizer rollout is deliberately separate from the compiler target. The
stable IDs are `none`, `baseline`, `pv11-safe`, and `pv11-costed`; `pv11-safe`
is the default. Select `baseline` explicitly to reproduce the pre-ADR-032
lowering and script bytes. Cost-directed rules also require an exact pinned
profile, for example:

```groovy
julc {
    optimization = 'pv11-costed'
    costProfile = 'cardano-node-11.0.1-plutus-v3-pv11'
}
```

The same values are available through `CompilerOptions`, the CLI
(`--optimization`, `--cost-profile`), annotation-processor options
(`-Ajulc.optimization`, `-Ajulc.costProfile`), and MCP compile/evaluate tools.
Unknown identifiers never fall back.

Default compiler output may contain PV11-only `Case Bool` or `DropList` terms.
`JulcVm` language-only overloads intentionally retain ADR-030's PV10
compatibility behavior, so raw VM evaluation must use the compiled target:

```java
var result = compiler.compile(source);
var evaluation = vm.evaluateWithArgs(
        result.program(), result.target().ledgerTarget(), args, null,
        EvalOptions.DEFAULT);
```

The testkit and CLI evaluation paths propagate this target automatically.

For direct annotation-processor configuration, pass
`-Ajulc.target=plutus-v3-pv11-uplc-1.1.0`. Supporting a later protocol version
will add a separately pinned compiler target and feature matrix; it will not
silently change this default.

### Current Preview Version

**`0.1.0-pre16`**

```groovy
ext.julcVersion = '0.1.0-pre16'
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
var compiler = new JulcCompiler(stdlib);

var result = compiler.compile(javaSource);
if (!result.hasErrors()) {
    Program program = result.program();
    CompilerTarget target = result.target();
    // Ready for serialization and on-chain deployment with explicit provenance
}
```

### Test Locally

```java
var evalResult = ValidatorTest.evaluate(result, datum, redeemer, scriptContext);
assertTrue(evalResult.isSuccess());
```

Passing the `CompileResult` lets `julc-testkit` hand the exact compiler target
to the VM. Raw `Program` evaluation overloads remain available for compatibility
when target provenance is not available.

## Requirements

- **Java 25+**
- **Gradle 9+**

## Documentation

| Guide | Description |
|-------|-------------|
| [Getting Started](docs/src/content/docs/getting-started.md) | Comprehensive guide: validators, data modeling, collections, control flow, stdlib, testing, deployment |
| [API Reference](docs/src/content/docs/reference/api-reference.md) | All supported types, operators, methods, and ledger access |
| [Standard Library Guide](docs/src/content/docs/stdlib/stdlib-guide.md) | All 13 stdlib libraries with usage examples |
| [Advanced Guide](docs/src/content/docs/guides/advanced-guide.md) | Low-level PlutusData patterns, type casting, raw list/map manipulation, debugging |
| [For-Loop Patterns](docs/src/content/docs/guides/for-loop-patterns.md) | For-each, while, nested loops, multi-accumulator, break |
| [Library Developer Guide](docs/src/content/docs/reference/library-developer-guide.md) | Writing `@OnchainLibrary` modules and PIR API |
| [Troubleshooting](docs/src/content/docs/reference/troubleshooting.md) | Every compiler error, common mistakes, and FAQ |
| [Compiler Developer Guide](docs/src/content/docs/internals/compiler-developer-guide.md) | Internal architecture for compiler contributors |

## License

MIT
