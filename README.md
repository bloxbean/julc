<p align="center">
  <img src="static/accent-light.svg" alt="JuLC Logo" width="400"/>
</p>

# julc

**Java UPLC Compiler for Cardano**

*Pronounced like "bulk" — not "joolk"*

Write Cardano smart contracts in Java and compile them to Plutus V3 UPLC. julc provides a complete
toolchain: a Java-subset compiler, a pluggable VM for local evaluation, a standard library of on-chain
operations, and first-class integration with [cardano-client-lib](https://github.com/bloxbean/cardano-client-lib).

## Features

- **Java-to-UPLC compiler** — write validators in a familiar Java subset, compile to Plutus V3
- **Pluggable VM** — evaluate UPLC programs locally via SPI (Scalus backend included)
- **Standard library** — math, lists, maps, values, intervals, crypto, bitwise, and more
- **Annotation processor** — `@Validator`, `@MintingPolicy`, `@Entrypoint` for compile-time code generation
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
| `julc-onchain-api` | Annotations and builtin stubs for IDE support |
| `julc-testkit` | Testing utilities for validators |
| `julc-cardano-client-lib` | cardano-client-lib integration |
| `julc-gradle-plugin` | Gradle build plugin |
| `julc-annotation-processor` | Compile-time annotation processor |
| `julc-examples` | Example validators |

## Quick Start

### Dependencies

```groovy
dependencies {
    implementation 'com.bloxbean.cardano:julc-compiler:0.1.0-SNAPSHOT'
    implementation 'com.bloxbean.cardano:julc-stdlib:0.1.0-SNAPSHOT'

    testImplementation 'com.bloxbean.cardano:julc-testkit:0.1.0-SNAPSHOT'
    testImplementation 'com.bloxbean.cardano:julc-vm:0.1.0-SNAPSHOT'
    testRuntimeOnly 'com.bloxbean.cardano:julc-vm-scalus:0.1.0-SNAPSHOT'
}
```

### Write a Validator

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

### Compile and Evaluate

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

## Requirements

- **Java 25** (GraalVM recommended)
- **Gradle 9+**

## Documentation

See [docs/getting-started.md](docs/getting-started.md) for a comprehensive guide.

## License

MIT
