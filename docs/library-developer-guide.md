# JuLC Library Developer Guide

This guide explains how to write, publish, and test on-chain libraries for JuLC, the Java-to-UPLC compiler for Cardano smart contracts.

---

## 1. Introduction

On-chain libraries in JuLC are reusable modules of logic that compile from Java source to UPLC (Untyped Plutus Lambda Calculus) and execute on the Cardano blockchain. When a validator calls a library method such as `MathLib.abs(x)`, the compiler:

1. Discovers the library's Java source file (from the classpath or the same project).
2. Compiles that source to PIR (Plutus Intermediate Representation) alongside the validator.
3. Inlines the library's compiled UPLC code into the final script.

Library methods do not execute on the JVM during compilation. They are compiled to UPLC terms that run on-chain inside the Plutus VM.

There are two approaches to writing library functions:

| Approach | When to use | Complexity |
|----------|-------------|------------|
| **Java Source (`@OnchainLibrary`)** | Most cases: arithmetic, data traversal, comparisons, builtin wrappers | Low |
| **PIR API (programmatic term building)** | Higher-order functions, complex recursion, lambda parameters | High |

The vast majority of library functions should use Approach 1. Approach 2 is only needed for patterns that the Java-subset compiler cannot express (primarily higher-order functions that accept function arguments).

---

## 2. Approach 1: Java Source Libraries (`@OnchainLibrary`)

This is the primary and recommended approach. You write normal-looking Java static methods, annotate the class with `@OnchainLibrary`, and the JuLC compiler handles the rest.

### 2.1 The `@OnchainLibrary` Annotation

The `@OnchainLibrary` annotation (defined in `julc-onchain-api`) marks a class whose static methods can be called from `@SpendingValidator` (or other validator annotation) classes and from other `@OnchainLibrary` classes.

```java
package com.bloxbean.cardano.julc.onchain.annotation;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface OnchainLibrary {
}
```

**Source:** `julc-onchain-api/src/main/java/com/bloxbean/cardano/julc/onchain/annotation/OnchainLibrary.java`

### 2.2 Basic Structure

A library class must:
- Be annotated with `@OnchainLibrary`.
- Contain only `public static` methods.
- Follow the supported Java subset (see Section 2.5 for details).

Here is `MathLib`, the simplest real library in the codebase:

```java
package com.bloxbean.cardano.julc.stdlib.lib;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.onchain.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.stdlib.Builtins;

@OnchainLibrary
public class MathLib {

    public static long abs(long x) {
        if (x < 0) {
            return 0 - x;
        } else {
            return x;
        }
    }

    public static long max(long a, long b) {
        if (a < b) {
            return b;
        } else {
            return a;
        }
    }

    public static long min(long a, long b) {
        if (a <= b) {
            return a;
        } else {
            return b;
        }
    }

    public static long pow(long base, long exp) {
        var result = 1L;
        var e = exp;
        while (e > 0) {
            result = result * base;
            e = e - 1;
        }
        return result;
    }

    public static long sign(long x) {
        if (x < 0) {
            return 0 - 1;
        } else {
            if (x == 0) {
                return 0;
            } else {
                return 1;
            }
        }
    }
}
```

**Source:** `julc-stdlib/src/main/java/com/bloxbean/cardano/julc/stdlib/lib/MathLib.java`

Key observations:
- Pure functions, no state.
- Only `if/else` and `while` control flow.
- Negation is expressed as `0 - x` (unary minus is not supported).
- The `var` keyword is used for local variables.

### 2.3 Using `Builtins.*` for UPLC Primitives

The `Builtins` class (in `julc-onchain-api`) provides Java method signatures that map directly to UPLC builtin operations. On-chain, calls to these methods are replaced by their corresponding UPLC builtins. Off-chain, the JVM implementations provide executable behavior for testing.

All data flowing through the Plutus VM is `PlutusData`. The `Builtins` class provides encode/decode functions to convert between Java types and `PlutusData`:

```java
// Encoding to Data
Builtins.iData(42)              // long -> IntData
Builtins.bData(bs)              // BytesData -> BytesData (identity wrapper)
Builtins.constrData(0, fields)  // tag + list-of-fields -> Constr
Builtins.listData(list)         // list -> ListData
Builtins.mapData(pairList)      // pair-list -> MapData

// Decoding from Data
Builtins.unIData(data)          // IntData -> long
Builtins.unBData(data)          // BytesData -> BytesData
Builtins.unConstrData(data)     // Constr -> (tag, fields) pair
Builtins.unListData(data)       // ListData -> list
Builtins.unMapData(data)        // MapData -> pair-list

// List primitives
Builtins.headList(list)         // first element
Builtins.tailList(list)         // all but first
Builtins.nullList(list)         // is empty?
Builtins.mkCons(elem, list)     // prepend element
Builtins.mkNilData()            // empty data list
Builtins.mkNilPairData()        // empty pair list

// Pair primitives
Builtins.fstPair(pair)          // first of pair
Builtins.sndPair(pair)          // second of pair
Builtins.mkPairData(a, b)       // create a pair

// Data decomposition
Builtins.constrTag(data)        // extract constructor tag (shortcut for FstPair(UnConstrData(data)))
Builtins.constrFields(data)     // extract constructor fields (shortcut for SndPair(UnConstrData(data)))

// Comparison
Builtins.equalsData(a, b)       // structural equality

// Error/Trace
Builtins.error()                // abort execution
Builtins.trace(msg, val)        // trace message, return val
```

**Source:** `julc-onchain-api/src/main/java/com/bloxbean/cardano/julc/onchain/stdlib/Builtins.java`

Here is a real example from `CryptoLib` -- the simplest pattern, where library methods are thin wrappers around builtins:

```java
@OnchainLibrary
public class CryptoLib {

    public static PlutusData sha2_256(PlutusData bs) {
        return Builtins.sha2_256(bs);
    }

    public static PlutusData blake2b_256(PlutusData bs) {
        return Builtins.blake2b_256(bs);
    }

    public static boolean verifyEd25519Signature(PlutusData key, PlutusData msg, PlutusData sig) {
        return Builtins.verifyEd25519Signature(key, msg, sig);
    }
}
```

**Source:** `julc-stdlib/src/main/java/com/bloxbean/cardano/julc/stdlib/lib/CryptoLib.java`

### 2.4 Complete Example: A Custom `TokenUtils` Library

Here is an example of a custom library that checks whether a `Value` contains a specific token and retrieves its amount. This demonstrates real patterns found in `ValuesLib`:

```java
package com.example.myproject;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.onchain.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.stdlib.Builtins;

@OnchainLibrary
public class TokenUtils {

    /**
     * Returns the amount of a specific token in a Value.
     * Returns 0 if the policy/token is not found.
     *
     * A Value is Map<ByteString, Map<ByteString, Integer>>
     * (currency symbol -> token name -> amount).
     */
    public static long tokenAmount(PlutusData value, PlutusData policyId, PlutusData tokenName) {
        var outerPairs = Builtins.unMapData(value);
        var result = 0L;
        var current = outerPairs;
        while (!Builtins.nullList(current)) {
            var outerPair = Builtins.headList(current);
            if (Builtins.equalsData(Builtins.fstPair(outerPair), policyId)) {
                // Found the policy -- search inner map for the token name
                var innerPairs = Builtins.unMapData(Builtins.sndPair(outerPair));
                result = findToken(innerPairs, tokenName);
                current = Builtins.mkNilPairData(); // break out of while loop
            } else {
                current = Builtins.tailList(current);
            }
        }
        return result;
    }

    /** Search an inner token map for a token name. Returns amount or 0. */
    public static long findToken(PlutusData innerPairs, PlutusData tokenName) {
        var result = 0L;
        var current = innerPairs;
        while (!Builtins.nullList(current)) {
            var pair = Builtins.headList(current);
            if (Builtins.equalsData(Builtins.fstPair(pair), tokenName)) {
                result = Builtins.unIData(Builtins.sndPair(pair));
                current = Builtins.mkNilPairData(); // break
            } else {
                current = Builtins.tailList(current);
            }
        }
        return result;
    }

    /**
     * Returns true if the Value contains at least `minAmount` of the given token.
     */
    public static boolean hasToken(PlutusData value, PlutusData policyId,
                                   PlutusData tokenName, long minAmount) {
        var amount = tokenAmount(value, policyId, tokenName);
        return minAmount <= amount;
    }
}
```

A validator using this library:

```java
package com.example.myproject;

import java.math.BigInteger;
import com.bloxbean.cardano.julc.stdlib.Builtins;

@SpendingValidator
class TokenGateValidator {
    @Entrypoint
    static boolean validate(BigInteger redeemer, PlutusData ctx) {
        var txInfo = Builtins.headList(Builtins.constrFields(ctx));
        var mint = /* extract mint field from txInfo */;
        var myPolicy = Builtins.bData(/* policy id bytes */);
        var myToken = Builtins.bData(/* token name bytes */);
        return TokenUtils.hasToken(mint, myPolicy, myToken, 1);
    }
}
```

### 2.5 Supported Java Patterns

The JuLC compiler supports a restricted subset of Java. Within `@OnchainLibrary` classes, you can use:

**Control Flow:**
- `if` / `else` (must always have both branches when returning a value)
- `while` loops with accumulator variables
- Early exit from `while` by setting the list cursor to an empty list (`Builtins.mkNilData()` or `Builtins.mkNilPairData()`)

**Variables:**
- `var` declarations with initializers (e.g., `var count = 0L;`)
- Variable reassignment only inside `while` and `for-each` loop bodies
- No uninitialized variables

**Expressions:**
- Arithmetic: `+`, `-`, `*`, `/`, `%`
- Comparison: `<`, `<=`, `>`, `>=`, `==`, `!=`
- Boolean: `&&`, `||`, `!`
- Static method calls: `Builtins.headList(x)`, `MyLib.method(a, b)`
- Chained calls: `Builtins.unIData(Builtins.sndPair(pair))`

**Types:**
- `long` for integers
- `boolean` for booleans
- `PlutusData` for all Plutus data types (lists, maps, constructors, etc.)

**The "break" pattern:** Since `break` is not directly supported in UPLC compilation, `while` loops simulate early exit by replacing the loop cursor with an empty list:

```java
// Instead of: while (...) { if (found) break; ... }
// Use:
var current = list;
while (!Builtins.nullList(current)) {
    if (someCondition) {
        // "break" -- set cursor to empty list to exit the loop
        current = Builtins.mkNilData();
    } else {
        current = Builtins.tailList(current);
    }
}
```

This pattern is used throughout the standard library, as seen in `ListsLib.contains`:

```java
public static boolean contains(PlutusData list, PlutusData target) {
    var found = false;
    var current = list;
    while (!Builtins.nullList(current)) {
        if (Builtins.equalsData(Builtins.headList(current), target)) {
            found = true;
            current = Builtins.mkNilData(); // break
        } else {
            current = Builtins.tailList(current);
        }
    }
    return found;
}
```

**Source:** `julc-stdlib/src/main/java/com/bloxbean/cardano/julc/stdlib/lib/ListsLib.java`

### 2.6 Limitations

The following Java features are NOT supported in `@OnchainLibrary` classes:

- **No lambdas or `.apply()` on functions** -- you cannot pass functions as arguments. Use the PIR API (Approach 2) for higher-order functions.
- **No assignment expressions** -- all variables are immutable outside of `while`/`for-each` loop bodies.
- **No `try`/`catch`** -- errors abort execution via `Builtins.error()`.
- **No `null`** -- Plutus has no null concept.
- **No object creation** (`new`) -- all data is constructed via `Builtins.*` or `PlutusData` factories.
- **No arrays or collections** -- lists are Plutus builtin lists manipulated through `Builtins.headList`, `Builtins.tailList`, etc.
- **No `return` inside `while` body** -- accumulate into a variable and return after the loop.
- **No `for` loops with ranges** -- use `while` with a counter variable.
- **No string operations in library source** -- UPLC Text type cannot be compiled from Java source; use PIR API for `trace`.
- **No unary minus** -- write `0 - x` instead of `-x`.

### 2.7 Cross-Library Calls

Libraries can call methods from other `@OnchainLibrary` classes. The compiler automatically resolves dependencies transitively.

From `ValuesLib.flatten`:

```java
public static PlutusData flatten(PlutusData value) {
    var result = Builtins.mkNilData();
    var outerPairs = Builtins.unMapData(value);
    var current = outerPairs;
    while (!Builtins.nullList(current)) {
        var outerPair = Builtins.headList(current);
        var policyData = Builtins.fstPair(outerPair);
        var innerPairs = Builtins.unMapData(Builtins.sndPair(outerPair));
        result = flattenPolicy(policyData, innerPairs, result);
        current = Builtins.tailList(current);
    }
    return ListsLib.reverse(result);  // <-- cross-library call to ListsLib
}
```

**Source:** `julc-stdlib/src/main/java/com/bloxbean/cardano/julc/stdlib/lib/ValuesLib.java` (line 213)

Cross-library calls work as long as:
1. The called library is also annotated with `@OnchainLibrary`.
2. The called library's source is discoverable (either in the same project, or bundled via `META-INF/plutus-sources/` in a JAR dependency).
3. The import statement (or same-package reference) is present so the resolver can find the dependency.

> **Cross-Library BytesData Param Bug**: When calling a stdlib library method that takes `BytesData`/`MapData` typed parameters from user code, the compiler may skip Data encoding at the call boundary if the caller has a variable of the same type. **Workaround**: Pass `PlutusData` typed variables (not `BytesData`/`MapData`) when calling across library boundaries. See [Troubleshooting](troubleshooting.md) for details.

> **@NewType records in library parameters**: `@NewType` records resolve to their underlying primitive type at compile time. When accepting `@NewType` parameters in library methods, the parameter will be the underlying type (e.g., `byte[]` for a `@NewType` wrapping `byte[]`).

The `LibrarySourceResolver` handles transitive resolution: if `ValuesLib` calls `ListsLib.reverse`, and `ListsLib` calls `Builtins.headList`, all three are automatically included.

---

## 3. Publishing and Distribution

When you build a library project as a JAR, the library's Java source files must be bundled into the JAR so that consuming projects can discover and compile them to UPLC.

### 3.1 The Gradle Plugin `bundleJulcSources` Task

The JuLC Gradle plugin registers a `bundleJulcSources` task that:

1. Scans `src/main/java/` for classes containing `@OnchainLibrary`.
2. Copies each matching `.java` source file into `META-INF/plutus-sources/` under `build/resources/main/`, preserving the package directory structure.
3. Generates an `index.txt` manifest listing all bundled source paths.
4. The `jar` task depends on `bundleJulcSources`, so sources are automatically included in the published JAR.

**Source:** `julc-gradle-plugin/src/main/java/com/bloxbean/cardano/julc/gradle/BundleJulcSourcesTask.java`

Example directory layout in a published JAR:

```
my-library.jar
  META-INF/
    plutus-sources/
      index.txt
      com/
        example/
          mylib/
            TokenUtils.java
            HelperLib.java
```

### 3.2 The `index.txt` Manifest Format

The `index.txt` file lists one source file path per line (relative to `META-INF/plutus-sources/`):

```
com/example/mylib/TokenUtils.java
com/example/mylib/HelperLib.java
```

This manifest enables reliable source discovery from both file-system directories and JAR archives.

**Example from the standard library (`julc-stdlib`):**

```
com/bloxbean/cardano/julc/stdlib/lib/MapLib.java
com/bloxbean/cardano/julc/stdlib/lib/MathLib.java
com/bloxbean/cardano/julc/stdlib/lib/IntervalLib.java
com/bloxbean/cardano/julc/stdlib/lib/CryptoLib.java
com/bloxbean/cardano/julc/stdlib/lib/ByteStringLib.java
com/bloxbean/cardano/julc/stdlib/lib/BitwiseLib.java
com/bloxbean/cardano/julc/stdlib/lib/ContextsLib.java
com/bloxbean/cardano/julc/stdlib/lib/ValuesLib.java
com/bloxbean/cardano/julc/stdlib/lib/ListsLib.java
```

### 3.3 Auto-Discovery from Classpath

When the compiler encounters a call to a library method (e.g., `TokenUtils.hasToken(...)`), the `LibrarySourceResolver` discovers the library source using a three-tier strategy:

1. **Tier 1 -- Same-project sources:** Looks for a `.java` file matching the import path under the project's source root directory.
2. **Tier 2 -- Classpath JAR sources:** Scans `META-INF/plutus-sources/index.txt` from all classpath JARs.
3. **Tier 3 -- Transitive resolution:** For each discovered library, recursively resolves its imports until no new libraries are found.

**Source:** `julc-compiler/src/main/java/com/bloxbean/cardano/julc/compiler/LibrarySourceResolver.java`

### 3.4 Setting Up `build.gradle` for a Library Project

If you are building a standalone library project (not using the JuLC Gradle plugin), you can replicate the bundling with a custom task. Here is the pattern used by `julc-stdlib`:

```groovy
plugins {
    id 'java-library'
}

dependencies {
    api 'com.bloxbean.cardano:julc-core:<version>'
    implementation 'com.bloxbean.cardano:julc-onchain-api:<version>'
}

// Bundle @OnchainLibrary Java sources into META-INF/plutus-sources/
def generatedResDir = file("${buildDir}/generated/plutus-resources")
tasks.register('bundlePlutusSources') {
    def srcDir = file('src/main/java')
    def outDir = file("${generatedResDir}/META-INF/plutus-sources")
    inputs.dir(srcDir)
    outputs.dir(generatedResDir)
    doLast {
        def entries = []
        fileTree(srcDir).matching { include '**/*.java' }.each { File f ->
            if (f.text =~ /(?m)^@OnchainLibrary/) {
                def relative = srcDir.toPath().relativize(f.toPath()).toString()
                def target = outDir.toPath().resolve(relative)
                target.parent.toFile().mkdirs()
                target.toFile().text = f.text
                entries << relative
            }
        }
        // Write index file so classpath scanning works from jar URLs too
        new File(outDir, 'index.txt').text = entries.join('\n') + '\n'
    }
}
sourceSets.main.resources.srcDir generatedResDir
processResources.dependsOn bundlePlutusSources
```

**Source:** `julc-stdlib/build.gradle`

---

## 4. Testing Libraries

Libraries can be tested in two ways:

### 4.1 Compile-and-Evaluate Pattern

Write a minimal validator that calls the library method, compile it with the library source, and evaluate the resulting UPLC program. This is the primary integration testing approach.

```java
class TokenUtilsTest {

    private final JulcCompiler compiler = new JulcCompiler();
    private final JulcVm vm = JulcVm.create();

    @Test
    void hasTokenReturnsTrueWhenPresent() {
        var libSource = """
            import com.bloxbean.cardano.julc.core.PlutusData;
            import com.bloxbean.cardano.julc.onchain.annotation.OnchainLibrary;
            import com.bloxbean.cardano.julc.stdlib.Builtins;

            @OnchainLibrary
            public class TokenUtils {
                public static long tokenAmount(PlutusData value, PlutusData policy, PlutusData token) {
                    // ... implementation ...
                }
                public static boolean hasToken(PlutusData value, PlutusData policy,
                                               PlutusData token, long minAmount) {
                    var amount = tokenAmount(value, policy, token);
                    return minAmount <= amount;
                }
            }
            """;

        var validatorSource = """
            import java.math.BigInteger;

            @SpendingValidator
            class TestValidator {
                @Entrypoint
                static boolean validate(BigInteger redeemer, BigInteger ctx) {
                    // Test logic calling TokenUtils
                    return true;
                }
            }
            """;

        var result = compiler.compile(validatorSource, List.of(libSource));
        assertFalse(result.hasErrors(), "Compilation failed: " + result.diagnostics());

        var program = result.program();
        var evalResult = vm.evaluateWithArgs(program, List.of(mockCtx));
        assertTrue(evalResult.isSuccess());
    }
}
```

### 4.2 Using `SourceDiscovery` from `julc-testkit`

For library projects that use the `@OnchainLibrary` annotation on real source files, the `SourceDiscovery` utility automates discovery and compilation:

```java
import com.bloxbean.cardano.julc.testkit.SourceDiscovery;

@Test
void testMyValidator() {
    // Automatically finds MyValidator.java under src/main/java,
    // resolves its library dependencies, and compiles everything
    var result = SourceDiscovery.compile(MyValidator.class);
    // result.program() is ready for VM evaluation
}
```

**Source:** `julc-testkit/src/main/java/com/bloxbean/cardano/julc/testkit/SourceDiscovery.java`

`SourceDiscovery` performs the three-tier library resolution (same-project, classpath JARs, transitive) automatically.

### 4.3 Off-Chain Testing with `Builtins`

Because `Builtins` methods have JVM implementations, you can also unit-test library logic directly off-chain without the compiler:

```java
@Test
void testContainsOffChain() {
    var list = Builtins.mkCons(
        Builtins.iData(10),
        Builtins.mkCons(Builtins.iData(20), Builtins.mkNilData()));
    assertTrue(ListsLib.contains(list, Builtins.iData(20)));
    assertFalse(ListsLib.contains(list, Builtins.iData(99)));
}
```

This works because the `@OnchainLibrary` classes call `Builtins.*` methods whose JVM implementations mirror on-chain behavior.

---

## 5. Approach 2: PIR API (Advanced)

Some patterns cannot be expressed in the Java subset -- primarily higher-order functions (functions that accept other functions as arguments). For these cases, you build PIR terms programmatically.

### 5.1 When to Use the PIR API

Use Approach 2 when your library function needs:
- **Lambda parameters** -- accepting a function and applying it to elements (e.g., `map`, `filter`, `foldl`, `any`, `all`).
- **Complex recursion** -- `LetRec` bindings for recursive definitions.
- **UPLC Text type** -- `trace` requires the UPLC Text type, which cannot be compiled from Java source.
- **Performance-critical hand-tuned UPLC** -- manual control over the exact UPLC output.

In the standard library, only these methods use the PIR API:
- `ListsLib.any`, `ListsLib.all`, `ListsLib.find`, `ListsLib.foldl`, `ListsLib.map`, `ListsLib.filter`, `ListsLib.zip` (all HOF -- require lambda parameters)
- `ContextsLib.trace` (uses UPLC Text type)
- `Math.abs`, `Math.max`, `Math.min` (inline PIR delegates for `java.lang.Math`)

Everything else is compiled from `@OnchainLibrary` Java source.

### 5.2 PirTerm Building Blocks

All UPLC code is constructed from these PIR term types:

| PirTerm | Description | Example |
|---------|-------------|---------|
| `Var(name, type)` | Variable reference | `new PirTerm.Var("x", new PirType.IntegerType())` |
| `Const(constant)` | Literal value | `new PirTerm.Const(Constant.integer(BigInteger.ZERO))` |
| `Builtin(fun)` | UPLC builtin function | `new PirTerm.Builtin(DefaultFun.AddInteger)` |
| `App(function, arg)` | Function application | `new PirTerm.App(fun, arg)` |
| `Lam(param, type, body)` | Lambda abstraction | `new PirTerm.Lam("x", type, body)` |
| `Let(name, value, body)` | Let binding | `new PirTerm.Let("x", expr, body)` |
| `LetRec(bindings, body)` | Recursive let (for loops) | See `foldl` example below |
| `IfThenElse(cond, then, else)` | Conditional | `new PirTerm.IfThenElse(cond, t, f)` |
| `DataConstr(tag, type, fields)` | Data constructor | `new PirTerm.DataConstr(0, type, List.of(f1))` |
| `Error(type)` | Runtime error | `new PirTerm.Error(new PirType.DataType())` |
| `Trace(msg, value)` | Trace message | `new PirTerm.Trace(msg, val)` |
| `Binding(name, body)` | Named binding (for `LetRec`) | `new PirTerm.Binding("go", goBody)` |

UPLC builtins are applied one argument at a time (curried):

```java
// AddInteger(a, b) -- two arguments applied sequentially
new PirTerm.App(
    new PirTerm.App(new PirTerm.Builtin(DefaultFun.AddInteger), a),
    b);
```

Common constants:

```java
Constant.bool(true)                        // Bool
Constant.integer(BigInteger.valueOf(42))   // Integer
Constant.integer(BigInteger.ZERO)          // Integer 0
Constant.byteString(new byte[]{})          // ByteString (empty)
Constant.unit()                            // Unit ()
```

The UplcGenerator handles force counts automatically -- you do not need to add Force wrappers in PIR.

### 5.3 Pattern: Simple Builtin Wrapper

The simplest PIR method wraps a single UPLC builtin:

```java
// From StdlibRegistry.registerBuiltins:
reg.register("Builtins", "sha2_256", args -> {
    requireArgs("Builtins.sha2_256", args, 1);
    return new PirTerm.App(new PirTerm.Builtin(DefaultFun.Sha2_256), args.get(0));
});
```

### 5.4 Pattern: Data Field Extraction

Extract a field from a `Constr`-encoded Data value by index:

```java
// From StdlibRegistry: Builtins.constrTag extracts FstPair(UnConstrData(data))
reg.register("Builtins", "constrTag", args -> {
    requireArgs("Builtins.constrTag", args, 1);
    var unconstr = new PirTerm.App(
        new PirTerm.Builtin(DefaultFun.UnConstrData), args.get(0));
    return new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), unconstr);
});
```

**Source:** `julc-stdlib/src/main/java/com/bloxbean/cardano/julc/stdlib/StdlibRegistry.java` (lines 382-386)

### 5.5 Pattern: Recursive List Operation with `LetRec`

For operations that traverse a list, use `LetRec` for recursion. Here is `ListsLibHof.foldl` -- a left fold:

```java
public static PirTerm foldl(PirTerm f, PirTerm init, PirTerm list) {
    var accVar = new PirTerm.Var("acc", new PirType.DataType());
    var lstVar = new PirTerm.Var("lst",
        new PirType.ListType(new PirType.DataType()));
    var goVar = new PirTerm.Var("go", new PirType.FunType(
        new PirType.DataType(),
        new PirType.FunType(
            new PirType.ListType(new PirType.DataType()),
            new PirType.DataType())));

    var nullCheck = new PirTerm.App(
        new PirTerm.Builtin(DefaultFun.NullList), lstVar);
    var headExpr = new PirTerm.App(
        new PirTerm.Builtin(DefaultFun.HeadList), lstVar);
    var tailExpr = new PirTerm.App(
        new PirTerm.Builtin(DefaultFun.TailList), lstVar);

    // f acc (HeadList lst)
    var fApp = new PirTerm.App(new PirTerm.App(f, accVar), headExpr);
    // go (f acc (HeadList lst)) (TailList lst)
    var recurse = new PirTerm.App(
        new PirTerm.App(goVar, fApp), tailExpr);

    var ifExpr = new PirTerm.IfThenElse(nullCheck, accVar, recurse);

    var goBody = new PirTerm.Lam("acc", new PirType.DataType(),
        new PirTerm.Lam("lst",
            new PirType.ListType(new PirType.DataType()), ifExpr));
    var binding = new PirTerm.Binding("go", goBody);

    return new PirTerm.LetRec(
        List.of(binding),
        new PirTerm.App(new PirTerm.App(goVar, init), list));
}
```

**Source:** `julc-stdlib/src/main/java/com/bloxbean/cardano/julc/stdlib/ListsLibHof.java` (lines 99-123)

The recursion pattern:
1. Declare a `goVar` that refers to the recursive function itself.
2. Build the body using `goVar` for recursive calls.
3. Wrap in `LetRec(List.of(binding), App(App(goVar, init), list))`.

### 5.6 Pattern: HOF with Lambda Parameters

Higher-order functions accept lambda (`Lam`) parameters. Here is `ListsLibHof.any`, which uses `foldl` internally:

```java
public static PirTerm any(PirTerm list, PirTerm predicate) {
    var accVar = new PirTerm.Var("acc", new PirType.BoolType());
    var xVar = new PirTerm.Var("x", new PirType.DataType());
    var predApp = new PirTerm.App(predicate, xVar);
    var body = new PirTerm.IfThenElse(
            predApp,
            new PirTerm.Const(Constant.bool(true)),
            accVar);
    var foldFn = new PirTerm.Lam("acc", new PirType.BoolType(),
            new PirTerm.Lam("x", new PirType.DataType(), body));
    return foldl(foldFn, new PirTerm.Const(Constant.bool(false)), list);
}
```

**Source:** `julc-stdlib/src/main/java/com/bloxbean/cardano/julc/stdlib/ListsLibHof.java` (lines 28-39)

### 5.7 Registering PIR Methods in `StdlibRegistry`

PIR-based methods must be registered in `StdlibRegistry` so the compiler can find them. Registration maps a `(className, methodName)` pair to a `PirTermBuilder`:

```java
private static void registerListsLibHof(StdlibRegistry reg) {
    reg.register("ListsLib", "any", args -> {
        requireArgs("ListsLib.any", args, 2);
        return ListsLibHof.any(args.get(0), args.get(1));
    });

    reg.register("ListsLib", "foldl", args -> {
        requireArgs("ListsLib.foldl", args, 3);
        return ListsLibHof.foldl(args.get(0), args.get(1), args.get(2));
    });

    // ... more registrations ...
}
```

Then add the registration call to `defaultRegistry()`:

```java
public static StdlibRegistry defaultRegistry() {
    var reg = new StdlibRegistry();
    registerBuiltins(reg);
    registerListsLibHof(reg);
    registerContextsTrace(reg);
    registerJavaMathDelegates(reg);
    return reg;
}
```

**Source:** `julc-stdlib/src/main/java/com/bloxbean/cardano/julc/stdlib/StdlibRegistry.java` (lines 113-120, 405-440)

**Important:** `@OnchainLibrary` Java source methods do NOT need registry entries. Only PIR-based methods need explicit registration. The compiler automatically discovers and compiles `@OnchainLibrary` source files.

### 5.8 Testing PIR-Based Methods

PIR methods are tested by building PIR terms, lowering them to UPLC with `UplcGenerator`, and evaluating via `JulcVm`:

```java
class StdlibTest {

    static JulcVm vm;

    @BeforeAll
    static void setUp() {
        vm = JulcVm.create();
    }

    private EvalResult evalPir(PirTerm pir) {
        var uplc = new UplcGenerator().generate(pir);
        return vm.evaluate(Program.plutusV3(uplc));
    }

    private boolean evalBool(PirTerm pir) {
        var result = evalPir(pir);
        assertTrue(result.isSuccess());
        var term = ((EvalResult.Success) result).resultTerm();
        var val = ((Term.Const) term).value();
        return ((Constant.BoolConst) val).value();
    }

    @Test
    void anyWithMatchReturnsTrue() {
        // Build predicate: \x -> LessThanInteger(7, UnIData(x))
        var pred = new PirTerm.Lam("x", new PirType.DataType(),
            new PirTerm.App(
                new PirTerm.App(
                    new PirTerm.Builtin(DefaultFun.LessThanInteger),
                    new PirTerm.Const(Constant.integer(BigInteger.valueOf(7)))),
                new PirTerm.App(
                    new PirTerm.Builtin(DefaultFun.UnIData),
                    new PirTerm.Var("x", new PirType.DataType()))));

        var list = intDataList(1, 5, 10);  // [1, 5, 10]
        var pir = ListsLibHof.any(list, pred);
        assertTrue(evalBool(pir));  // 10 > 7
    }
}
```

**Source:** `julc-stdlib/src/test/java/com/bloxbean/cardano/julc/stdlib/StdlibTest.java`

---

## 6. Builtins.java Reference

Complete listing of all `Builtins` methods, grouped by category. Each method maps to a UPLC builtin operation on-chain. Off-chain, the JVM implementation is used for testing.

### List Operations

| Method | Signature | UPLC Builtin |
|--------|-----------|-------------|
| `headList` | `(PlutusData list) -> PlutusData` | `HeadList` |
| `tailList` | `(PlutusData list) -> PlutusData` | `TailList` |
| `nullList` | `(PlutusData list) -> boolean` | `NullList` |
| `mkCons` | `(PlutusData elem, PlutusData list) -> PlutusData` | `MkCons` |
| `mkNilData` | `() -> PlutusData` | `MkNilData` |

### Pair Operations

| Method | Signature | UPLC Builtin |
|--------|-----------|-------------|
| `fstPair` | `(PlutusData pair) -> PlutusData` | `FstPair` |
| `sndPair` | `(PlutusData pair) -> PlutusData` | `SndPair` |
| `mkPairData` | `(PlutusData fst, PlutusData snd) -> PlutusData` | `MkPairData` |
| `mkNilPairData` | `() -> PlutusData` | `MkNilPairData` |

### Data Encoding

| Method | Signature | UPLC Builtin |
|--------|-----------|-------------|
| `constrData` | `(long tag, PlutusData fields) -> PlutusData` | `ConstrData` |
| `iData` | `(long value) -> PlutusData` | `IData` |
| `bData` | `(PlutusData bs) -> PlutusData` | `BData` |
| `listData` | `(PlutusData list) -> PlutusData` | `ListData` |
| `mapData` | `(PlutusData map) -> PlutusData` | `MapData` |

### Data Decoding

| Method | Signature | UPLC Builtin |
|--------|-----------|-------------|
| `unConstrData` | `(PlutusData data) -> PlutusData` | `UnConstrData` |
| `unIData` | `(PlutusData data) -> long` | `UnIData` |
| `unBData` | `(PlutusData data) -> PlutusData` | `UnBData` |
| `unListData` | `(PlutusData data) -> PlutusData` | `UnListData` |
| `unMapData` | `(PlutusData data) -> PlutusData` | `UnMapData` |

### Data Decomposition Helpers

| Method | Signature | UPLC Equivalent |
|--------|-----------|-----------------|
| `constrTag` | `(PlutusData data) -> long` | `FstPair(UnConstrData(data))` |
| `constrFields` | `(PlutusData data) -> PlutusData` | `SndPair(UnConstrData(data))` |

### Data Comparison

| Method | Signature | UPLC Builtin |
|--------|-----------|-------------|
| `equalsData` | `(PlutusData a, PlutusData b) -> boolean` | `EqualsData` |

### ByteString Operations

| Method | Signature | UPLC Builtin |
|--------|-----------|-------------|
| `indexByteString` | `(PlutusData bs, long index) -> long` | `IndexByteString` |
| `consByteString` | `(long byte_, PlutusData bs) -> PlutusData` | `ConsByteString` |
| `sliceByteString` | `(long start, long length, PlutusData bs) -> PlutusData` | `SliceByteString` |
| `lengthOfByteString` | `(PlutusData bs) -> long` | `LengthOfByteString` |
| `appendByteString` | `(PlutusData a, PlutusData b) -> PlutusData` | `AppendByteString` |
| `equalsByteString` | `(PlutusData a, PlutusData b) -> boolean` | `EqualsByteString` |
| `lessThanByteString` | `(PlutusData a, PlutusData b) -> boolean` | `LessThanByteString` |
| `lessThanEqualsByteString` | `(PlutusData a, PlutusData b) -> boolean` | `LessThanEqualsByteString` |
| `integerToByteString` | `(boolean bigEndian, long width, long i) -> PlutusData` | `IntegerToByteString` |
| `byteStringToInteger` | `(boolean bigEndian, PlutusData bs) -> long` | `ByteStringToInteger` |
| `encodeUtf8` | `(PlutusData s) -> PlutusData` | `EncodeUtf8` |
| `decodeUtf8` | `(PlutusData bs) -> PlutusData` | `DecodeUtf8` |
| `serialiseData` | `(PlutusData d) -> PlutusData` | `SerialiseData` |
| `replicateByte` | `(long n, long byte_) -> PlutusData` | `ReplicateByte` |
| `emptyByteString` | `() -> PlutusData` | Constant `#""` |

### Cryptographic Operations

| Method | Signature | UPLC Builtin |
|--------|-----------|-------------|
| `sha2_256` | `(PlutusData bs) -> PlutusData` | `Sha2_256` |
| `sha3_256` | `(PlutusData bs) -> PlutusData` | `Sha3_256` |
| `blake2b_256` | `(PlutusData bs) -> PlutusData` | `Blake2b_256` |
| `blake2b_224` | `(PlutusData bs) -> PlutusData` | `Blake2b_224` |
| `keccak_256` | `(PlutusData bs) -> PlutusData` | `Keccak_256` |
| `ripemd_160` | `(PlutusData bs) -> PlutusData` | `Ripemd_160` |
| `verifyEd25519Signature` | `(PlutusData key, PlutusData msg, PlutusData sig) -> boolean` | `VerifyEd25519Signature` |
| `verifyEcdsaSecp256k1Signature` | `(PlutusData key, PlutusData msg, PlutusData sig) -> boolean` | `VerifyEcdsaSecp256k1Signature` |
| `verifySchnorrSecp256k1Signature` | `(PlutusData key, PlutusData msg, PlutusData sig) -> boolean` | `VerifySchnorrSecp256k1Signature` |

### Bitwise Operations

| Method | Signature | UPLC Builtin |
|--------|-----------|-------------|
| `andByteString` | `(boolean padding, PlutusData a, PlutusData b) -> PlutusData` | `AndByteString` |
| `orByteString` | `(boolean padding, PlutusData a, PlutusData b) -> PlutusData` | `OrByteString` |
| `xorByteString` | `(boolean padding, PlutusData a, PlutusData b) -> PlutusData` | `XorByteString` |
| `complementByteString` | `(PlutusData bs) -> PlutusData` | `ComplementByteString` |
| `readBit` | `(PlutusData bs, long index) -> boolean` | `ReadBit` |
| `writeBits` | `(PlutusData bs, PlutusData indices, boolean value) -> PlutusData` | `WriteBits` |
| `shiftByteString` | `(PlutusData bs, long n) -> PlutusData` | `ShiftByteString` |
| `rotateByteString` | `(PlutusData bs, long n) -> PlutusData` | `RotateByteString` |
| `countSetBits` | `(PlutusData bs) -> long` | `CountSetBits` |
| `findFirstSetBit` | `(PlutusData bs) -> long` | `FindFirstSetBit` |

### Math Operations

| Method | Signature | UPLC Builtin |
|--------|-----------|-------------|
| `expModInteger` | `(long base, long exp, long mod) -> long` | `ExpModInteger` |

### Error and Trace

| Method | Signature | UPLC Builtin |
|--------|-----------|-------------|
| `error` | `() -> PlutusData` | `Error` (aborts execution) |
| `trace` | `(String message, PlutusData value) -> PlutusData` | `Trace` |

---

## 7. Checklist for Adding a New Library Function

### For Java Source Libraries (`@OnchainLibrary`)

- [ ] **Write the method** in your `@OnchainLibrary` class under `src/main/java/`.
  - Use only `public static` methods.
  - Follow the supported Java subset (Section 2.5).
  - Use `Builtins.*` for all UPLC primitive operations.
- [ ] **Verify the annotation:** Ensure the class has `@OnchainLibrary` at the class level.
- [ ] **Check cross-library imports:** If calling methods from other libraries, ensure the import statement is present and the dependency is also an `@OnchainLibrary`.
- [ ] **Run the bundle task:** `./gradlew bundlePlutusSources` (or `bundleJulcSources` if using the plugin) to verify the source is picked up.
- [ ] **Write integration tests** using the compile-and-evaluate pattern (Section 4.1). Cover:
  - Normal operation with expected inputs.
  - Edge cases (empty lists, zero values, boundary conditions).
  - Error cases where applicable.
- [ ] **Test off-chain** with direct `Builtins` calls if appropriate (Section 4.3).
- [ ] **Run the full test suite:** `./gradlew test` to verify no regressions.

### For PIR API Methods (Approach 2)

- [ ] **Write the PIR term builder** in a class under `julc-stdlib` (e.g., `ListsLibHof.java`).
  - Use unique variable name suffixes (e.g., `acc_map`, `x_flt`) to avoid shadowing.
  - Use `Let` bindings for expressions used more than once.
  - PirType accuracy matters: use `DataType` for general Data, `IntegerType`/`BoolType` for decoded values, `ListType` for builtin lists.
- [ ] **Register in `StdlibRegistry`:** Add a `reg.register(...)` call with `requireArgs` validation.
- [ ] **Add the registration call** to `defaultRegistry()` if creating a new registration group.
- [ ] **Write PIR-level tests** using `UplcGenerator` and `JulcVm.evaluate` (Section 5.8).
- [ ] **Update the registry test** in `StdlibTest.RegistryTests` to verify the new entry is present and the count is correct.
- [ ] **Run the full test suite:** `./gradlew test`

### Data Encoding Reference

When constructing test data or library logic, remember these Plutus data encodings:
- **Boolean:** `Constr(0, [])` = False, `Constr(1, [])` = True
- **Optional:** `Constr(0, [x])` = Some(x), `Constr(1, [])` = None
- **Value:** `Map<ByteString, Map<ByteString, Integer>>` -- currency symbol to (token name to amount)
- **Lovelace:** Stored under empty bytestring policy and empty bytestring token name
- **Pairs:** Encoded as `Constr(0, [fst, snd])` by `MkPairData`
