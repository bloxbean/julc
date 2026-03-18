# Source Maps — Runtime Error Location Reporting

When a UPLC program fails at runtime (Error term, budget exhaustion, builtin type error), the
default error message points to opaque UPLC internals:

```
Evaluation failed: Error term encountered
```

Source maps bridge this gap by mapping UPLC terms back to the Java source line that
generated them.

---

## Quick Start

### With `JulcEval` (recommended)

Add `.sourceMap()` to your evaluator:

```java
// Before (no source location)
var eval = JulcEval.forClass(SwapOrder.class);
PlutusData result = eval.call("makerAddress", MAKER).asData();
// On failure: "Evaluation failed: Error term encountered"

// After (with source location)
var eval = JulcEval.forClass(SwapOrder.class).sourceMap();
PlutusData result = eval.call("makerAddress", MAKER).asData();
// On failure: "Evaluation failed: Error term encountered
//   at SwapOrder.java:42 (Builtins.error())"
```

That's it. One method call. The error message now includes the file name, line number,
and a snippet of the Java expression that caused the failure.

### With `ValidatorTest`

For validator-level testing:

```java
// Compile with source maps
CompileResult compiled = ValidatorTest.compileValidatorWithSourceMap(EscrowValidator.class);

// Evaluate
EvalResult result = ValidatorTest.evaluate(compiled.program(), scriptContext);

// On failure, resolve the Java source location
SourceLocation location = ValidatorTest.resolveErrorLocation(result, compiled.sourceMap());
System.out.println("Error at: " + location);
// → "EscrowValidator.java:58 (Builtins.error())"
```

Or use the assertion shorthand:

```java
// Throws with source location in the error message
ValidatorTest.assertValidatesWithSourceMap(compiled, scriptContext);
// → AssertionError: Expected validator to succeed, but got:
//   Failure{error=Error term encountered, budget=ExBudget{cpu=..., mem=...}, traces=[]}
//     at EscrowValidator.java:58 (Builtins.error())
```

---

## Usage Patterns

### Pattern 1: JulcEval with `.sourceMap()` (simplest)

```java
@Test
void testSwapOrder() {
    var eval = JulcEval.forClass(SwapOrder.class).sourceMap();

    // Happy path — works as before
    PlutusData result = eval.call("makerAddress", MAKER).asData();
    assertNotNull(result);

    // Error path — now shows source location in the exception message
    assertThrows(ExtractionException.class, () ->
        eval.call("validate", invalidData).asBoolean()
    );
}
```

### Pattern 2: Validator compilation with source maps

```java
@Test
void testEscrowValidator() {
    // Compile once with source maps
    var compiled = ValidatorTest.compileValidatorWithSourceMap(EscrowValidator.class);

    // Evaluate multiple scenarios
    var ctx1 = buildScriptContext(/* valid */);
    ValidatorTest.assertValidatesWithSourceMap(compiled, ctx1);

    var ctx2 = buildScriptContext(/* invalid */);
    ValidatorTest.assertRejectsWithSourceMap(compiled, ctx2);
}
```

### With `ContractTest`

For real project tests (e.g., julc-examples) that extend `ContractTest`:

```java
class SwapOrderTest extends ContractTest {

    @Test
    void cancel_rejectsNonMaker() throws Exception {
        // Compile with source maps (drop-in replacement for compileValidator)
        var compiled = compileValidatorWithSourceMap(SwapOrder.class);

        var ref = TestDataBuilder.randomTxOutRef_typed();
        var ctx = spendingContext(ref, datum)
                .redeemer(redeemer)
                .signer(OTHER_PKH)
                .buildPlutusData();

        var result = evaluate(compiled.program(), ctx);

        // assertFailure with source map — error message includes Java source location
        assertFailure(result, compiled.sourceMap());

        // Optional: log budget + error location
        logResult("cancel_rejectsNonMaker", result, compiled.sourceMap());
        // → [cancel_rejectsNonMaker] CPU: 967522, Mem: 4536 | Error at: SwapOrder.java:42 (Builtins.error())

        // Optional: resolve location programmatically
        var location = resolveErrorLocation(result, compiled.sourceMap());
        System.out.println("Error at: " + location);
    }
}
```

Available `ContractTest` source map methods:
- `compileValidatorWithSourceMap(Class<?>)` — compile with source maps (default source root)
- `compileValidatorWithSourceMap(Class<?>, Path)` — compile with custom source root
- `assertSuccess(result, sourceMap)` / `assertFailure(result, sourceMap)` — assertions with source location in error messages
- `resolveErrorLocation(result, sourceMap)` — get `SourceLocation` programmatically
- `logResult(testName, result, sourceMap)` — print budget + error location

### Pattern 3: Programmatic (full control)

```java
@Test
void testWithFullControl() {
    var options = new CompilerOptions().setSourceMapEnabled(true);
    var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), options);

    CompileResult compiled = compiler.compile(source);
    SourceMap sourceMap = compiled.sourceMap();

    var vm = JulcVm.create();
    EvalResult result = vm.evaluateWithArgs(compiled.program(), List.of(scriptContext));

    if (!result.isSuccess()) {
        // Get the failed Term from the result
        Term failedTerm = switch (result) {
            case EvalResult.Failure f -> f.failedTerm();
            case EvalResult.BudgetExhausted b -> b.failedTerm();
            default -> null;
        };

        // Look up in source map
        SourceLocation location = sourceMap.lookup(failedTerm);
        if (location != null) {
            System.out.println("Error at: " + location);
            // → "MyValidator.java:42 (Builtins.error())"
        }
    }
}
```

### Pattern 4: Gradle plugin DSL

For the `compileJulc` task (when using `src/main/plutus`):

```groovy
julc {
    sourceMap = true  // generates source maps alongside compiled scripts
}
```

---

## What Gets Mapped

Source maps capture positions for these Java constructs:

| Java Construct | Example | Mapped? |
|---------------|---------|---------|
| Method calls | `Builtins.error()`, `list.head()` | Yes |
| Binary expressions | `a.compareTo(b) < 0` | Yes |
| Unary expressions | `!isValid` | Yes |
| Field access | `txInfo.fee()` | Yes |
| Object creation | `new EscrowDatum(...)` | Yes |
| If statements | `if (amount < 0) { ... }` | Yes |
| Switch expressions | `switch (action) { ... }` | Yes |
| Return statements | `return result` | Yes |
| Method definitions | `public static boolean validate(...)` | Yes |
| Conditionals | `cond ? a : b` | Yes |
| Literals | `42`, `true`, `"hello"` | No (not useful) |
| Variable references | `amount` | No (not useful) |
| Compiler-generated code | ValidatorWrapper, wrapDecode | No (no Java source) |

---

## How It Works

### Architecture

```
Java Source → PirGenerator → PIR Terms → UplcGenerator → UPLC Terms → CekMachine
                 ↓                           ↓                          ↓
         pirPositions map            uplcPositions map           currentTerm
    (PirTerm → SourceLocation)    (Term → SourceLocation)    (on exception)
                                         ↓
                                    SourceMap
                                  (IdentityHashMap)
```

1. **PirGenerator** records `SourceLocation` for each PIR term it creates from a JavaParser AST node
2. **UplcGenerator** transfers positions from PIR terms to their outermost UPLC terms, propagating parent locations to children
3. The resulting `IdentityHashMap<Term, SourceLocation>` is wrapped in a `SourceMap`
4. When the **CekMachine** throws an exception, it attaches `currentTerm` (the UPLC term being evaluated)
5. **JavaVmProvider** passes `failedTerm` through to `EvalResult.Failure`/`BudgetExhausted`
6. The testkit resolves `failedTerm` against the `SourceMap` to get the `SourceLocation`

### Why optimization is skipped

Source maps use `IdentityHashMap` — lookups are by object identity (`==`), not structural equality.
The UPLC optimizer creates new Term objects, which breaks identity. Since source maps are a
debugging feature, skipping optimization is the right trade-off: unoptimized UPLC is functionally
identical, just uses more budget.

### Scalus VM backend

Source maps only work with the **Java VM backend** (`julc-vm-java`). The Scalus backend
serializes terms to FLAT format and re-parses them, which destroys object identity.
When using Scalus, `failedTerm` will be null.

---

## API Reference

### `CompilerOptions`

```java
new CompilerOptions()
    .setSourceMapEnabled(true)   // enable source map generation
    .setVerbose(true);           // optional: log source map stats
```

### `SourceLocation`

```java
record SourceLocation(String fileName, int line, int column, String fragment)
// toString() → "MyValidator.java:42 (Builtins.error())"
```

### `SourceMap`

```java
SourceMap.EMPTY              // always returns null
sourceMap.lookup(term)       // → SourceLocation or null
sourceMap.size()             // number of mapped terms
sourceMap.isEmpty()          // true if no entries
```

### `EvalResult.Failure` / `EvalResult.BudgetExhausted`

```java
failure.failedTerm()         // → Term (nullable) — the UPLC term that caused the error
exhausted.failedTerm()       // → Term (nullable) — the UPLC term when budget ran out
```

### `ValidatorTest`

```java
// Compile with source maps
ValidatorTest.compileValidatorWithSourceMap(MyValidator.class)
ValidatorTest.compileValidatorWithSourceMap(MyValidator.class, sourceRoot)
ValidatorTest.compileWithSourceMap(javaSource)

// Resolve error location
ValidatorTest.resolveErrorLocation(result, sourceMap) // → SourceLocation or null

// Assert with source location in error message
ValidatorTest.assertValidatesWithSourceMap(compileResult, args...)
ValidatorTest.assertRejectsWithSourceMap(compileResult, args...)
```

### `ContractTest`

```java
// Compile with source maps
compileValidatorWithSourceMap(MyValidator.class)
compileValidatorWithSourceMap(MyValidator.class, sourceRoot)

// Assertions with source location in error messages
assertSuccess(result, sourceMap)
assertFailure(result, sourceMap)

// Resolve error location
resolveErrorLocation(result, sourceMap)   // → SourceLocation or null

// Log budget + error location
logResult("testName", result, sourceMap)
```

### `JulcEval`

```java
JulcEval.forClass(MyClass.class).sourceMap()   // enable source maps
JulcEval.forSource(javaString).sourceMap()     // works with inline source too
```

---

## Limitations

- **Compiler-generated terms** (ValidatorWrapper lambdas, wrapDecode/wrapEncode, Z-combinator) have no source position — `lookup()` returns null.
- **Library method errors**: The source map points to the *call site* in user code, not the library implementation. This is intentional.
- **String sources**: When compiling from a string (not a file), `fileName` is null. The location shows `:42 (fragment)` instead of `File.java:42 (fragment)`.
- **Optimization disabled**: Source maps skip UPLC optimization. Budget numbers will be higher than production. Use source maps for debugging, not benchmarking.
- **Java VM only**: The Scalus VM backend does not support source maps.
