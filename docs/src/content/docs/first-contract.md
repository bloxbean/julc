---
title: "Write Your First JuLC Contract"
description: "Step-by-step guide to scaffolding a project, writing a validator, compiling, and testing with the julc CLI"
---

This tutorial walks you through writing your first Cardano smart contract with JuLC.
You will install the CLI, scaffold a project, write a spending validator, compile it to UPLC,
and test it locally — all in under 10 minutes.

## Prerequisites

- **Java 25+** (GraalVM recommended)
- **julc CLI** installed (see below)

## 1. Install the julc CLI

### Homebrew (macOS / Linux)

```bash
brew install bloxbean/tap/julc
```

### Direct download

Download from [GitHub Releases](https://github.com/bloxbean/julc/releases).

On macOS, remove the quarantine attribute after download:

```bash
xattr -d com.apple.quarantine ./julc
chmod +x ./julc
```

Verify the installation:

```bash
julc --version
```

---

## 2. Scaffold a Project

The `julc new` command creates a ready-to-use project. JuLC supports three project types:

### Option A: Basic project (julc CLI only)

The simplest option — no build tool required. The julc CLI handles compilation and testing directly.

```bash
julc new my-first-contract
```

This creates:

```
my-first-contract/
  julc.toml                   # Project config
  src/
    AlwaysSucceeds.java       # Starter validator
  test/
    AlwaysSucceedsTest.java   # Starter test
  .julc/stdlib/               # Stdlib sources (auto-installed)
  .idea/                      # IntelliJ project files
```

Build and test with:

```bash
cd my-first-contract
julc build     # Compile validators to UPLC
julc check     # Run on-chain tests
```

### Option B: Gradle project

For teams using Gradle. Includes Gradle wrapper, annotation processor, JUnit 5, and cardano-client-lib integration.

```bash
julc new my-contract -t gradle
```

You'll be prompted for:
- **Group ID** (default: `com.example`)
- **Artifact ID** (default: project name)
- **Package name** (default: `<group>.<name>`)

This creates a standard Gradle project:

```
my-contract/
  build.gradle             # Pre-configured with JuLC deps
  settings.gradle
  gradlew / gradlew.bat    # Gradle wrapper
  src/main/java/com/example/my_contract/
    AlwaysSucceeds.java     # Starter validator
  src/test/java/com/example/my_contract/
    AlwaysSucceedsTest.java # JUnit 5 test with ContractTest
```

Build and test with:

```bash
cd my-contract
./gradlew build
```

### Option C: Maven project

For teams using Maven. Includes Maven wrapper, annotation processor config, and full pom.xml.

```bash
julc new my-contract -t maven
```

Same prompts as Gradle (Group ID, Artifact ID, Package name).

Build and test with:

```bash
cd my-contract
./mvnw compile test
```

---

## 3. Write a Spending Validator

Open the generated `AlwaysSucceeds.java` and replace it with a real vesting validator:

```java
package com.example.my_contract;

import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.PubKeyHash;
import java.math.BigInteger;

@SpendingValidator
public class VestingValidator {

    // Datum: who can claim and when
    record VestingDatum(PubKeyHash beneficiary, BigInteger deadline) {}

    @Entrypoint
    static boolean validate(VestingDatum datum, PlutusData redeemer,
                            ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();

        // The beneficiary must have signed the transaction
        boolean signed = txInfo.signatories()
                               .contains(datum.beneficiary());

        // The deadline must have passed
        boolean pastDeadline = datum.deadline()
                                    .compareTo(BigInteger.ZERO) > 0;

        return signed && pastDeadline;
    }
}
```

Key concepts:
- **`@SpendingValidator`** — marks this class as a spending validator
- **`record VestingDatum`** — defines the on-chain datum structure
- **`@Entrypoint`** — the method the Cardano node calls to validate a transaction
- **`ScriptContext`** — provides typed access to the transaction being validated

---

## 4. Compile to UPLC

### With julc CLI (basic project)

```bash
julc build
```

Output:

```
Building my-first-contract ...
  Compiling VestingValidator ... OK [245 bytes, a3f8b2c1...]

Build successful: 1 validator(s) compiled to build/plutus/
```

The compiled artifacts are in `build/plutus/`:
- `VestingValidator.uplc` — the UPLC program (human-readable)
- `plutus.json` — CIP-57 blueprint (for deployment tools)

### With Gradle

```bash
./gradlew build
```

The annotation processor compiles during `javac`. The compiled script is bundled into the JAR at
`META-INF/plutus/` and can be loaded at runtime:

```java
PlutusV3Script script = JulcScriptLoader.load(VestingValidator.class);
```

### With Maven

```bash
./mvnw compile
```

Same annotation processor behavior as Gradle.

---

## 5. Test Your Validator

### With julc CLI (basic project)

Create `test/VestingValidatorTest.java`:

```java
import com.bloxbean.cardano.julc.stdlib.test.Test;

public class VestingValidatorTest {

    @Test
    public static boolean test_beneficiary_can_claim() {
        // On-chain test — evaluated as UPLC
        return true;
    }
}
```

Run:

```bash
julc check
```

### With Gradle / Maven (JUnit 5)

The scaffolded test extends `ContractTest` which provides helpers:

```java
package com.example.my_contract;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.testkit.ContractTest;
import com.bloxbean.cardano.julc.testkit.TestDataBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VestingValidatorTest extends ContractTest {

    @Test
    void testBeneficiaryCanClaim() {
        // Compile with source maps for better error messages
        var program = compileValidatorWithSourceMap(VestingValidator.class);

        // Build a mock ScriptContext
        var ref = TestDataBuilder.randomTxOutRef_typed();
        var ctx = spendingContext(ref, PlutusData.UNIT)
                .redeemer(PlutusData.UNIT)
                .buildPlutusData();

        // Evaluate the validator
        var result = evaluateWithTrace(program, ctx);

        // Check result
        assertTrue(result.isSuccess());

        // Print execution details
        System.out.print(formatExecutionTrace());
        System.out.print(formatBudgetSummary());
    }
}
```

Run:

```bash
./gradlew test        # Gradle
./mvnw test           # Maven
```

---

## 6. Explore with the REPL

The julc REPL lets you experiment with on-chain expressions interactively:

```bash
julc repl
```

```
JuLC REPL v0.1.0 — type :help for commands

julc> 1 + 2
=> 3
   CPU: 230,100  Mem: 602

julc> ListsLib.length(List.of(1, 2, 3))
=> 3
   CPU: 1,082,720  Mem: 3,410

julc> MathLib.pow(2, 10)
=> 1024
   CPU: 4,510,306  Mem: 12,818

julc> :doc ListsLib.filter
ListsLib.filter(list, predicate)
  Keep only elements matching predicate.
  Returns: JulcList<PlutusData>
```

Every expression is compiled to UPLC and evaluated — CPU and memory budgets are shown in real time.

---

## 7. Inspect Blueprints

After building, inspect the compiled blueprint:

```bash
julc blueprint inspect build/plutus/plutus.json
```

This shows the validator hash, script size, and parameter info — useful for computing script addresses
and preparing transactions.

---

## CLI Command Reference

| Command | Description |
|---------|-------------|
| `julc new <name>` | Create a new project (`-t basic`, `-t gradle`, `-t maven`) |
| `julc build` | Compile validators to UPLC + generate CIP-57 blueprint |
| `julc check` | Discover and run on-chain tests |
| `julc repl` | Interactive REPL with live budgets |
| `julc eval <expr>` | Evaluate a single expression |
| `julc blueprint inspect <file>` | Inspect a compiled blueprint |
| `julc blueprint address <file>` | Compute script address from blueprint |
| `julc uplc decode <hex>` | Decode UPLC from hex/CBOR |
| `julc uplc fmt <file>` | Pretty-print UPLC |
| `julc install` | Install/update stdlib sources |
| `julc --version` | Show version |

---

## Next Steps

- [Getting Started](/getting-started/) — comprehensive guide covering all language features
- [Standard Library Reference](/stdlib/stdlib-guide/) — all 13 stdlib libraries
- [Testing Guide](/guides/testing-guide/) — unit tests, property-based testing, budget analysis
- [Examples](/reference/examples/) — 12 example validators
