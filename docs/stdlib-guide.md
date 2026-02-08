# Writing Custom Standard Library Functions

This guide explains how to add new functions to the Plutus-Java standard library. Stdlib functions are PIR term *builders* that construct UPLC code at compile time -- they don't execute at compile time.

## Architecture Overview

The standard library has three layers:

1. **Implementation** (`plutus-stdlib`): PIR term builders that produce UPLC code
2. **Registry** (`plutus-stdlib/StdlibRegistry`): Maps `(className, methodName)` to term builders
3. **Stubs** (`plutus-onchain-api`): Java method signatures for IDE autocomplete

When a user writes `CryptoLib.sha2_256(data)` in their validator, the compiler:
1. Recognizes the method call as a stdlib invocation
2. Looks up `("CryptoLib", "sha2_256")` in the StdlibRegistry
3. Calls the registered builder with the compiled argument terms
4. Inserts the resulting PIR term into the program

The key insight: **stdlib methods return PirTerm** -- they build UPLC code that will run on-chain. They don't execute the operation themselves.

## Three-File Pattern

Every stdlib function requires changes in three places:

### 1. Implementation (`plutus-stdlib/src/main/java/.../YourLib.java`)

The actual term builder. This is where the UPLC logic lives.

### 2. Registration (`plutus-stdlib/src/main/java/.../StdlibRegistry.java`)

Register the builder so the compiler can find it:

```java
private static void registerYourLib(StdlibRegistry reg) {
    reg.register("YourLib", "myMethod", args -> {
        requireArgs("YourLib.myMethod", args, 2);
        return YourLib.myMethod(args.get(0), args.get(1));
    });
}
```

And add `registerYourLib(reg)` to `defaultRegistry()`.

### 3. Stub (`plutus-onchain-api/src/main/java/.../YourLib.java`)

A do-nothing Java class that provides IDE autocomplete:

```java
package com.bloxbean.cardano.plutus.onchain.stdlib;

public final class YourLib {
    private YourLib() {}

    public static int myMethod(int a, int b) {
        throw new UnsupportedOperationException("On-chain only");
    }
}
```

The stub's parameter/return types determine what the user sees in their IDE. The body is never executed on-chain -- it's just for the Java compiler.

## PirTerm Building Blocks

All UPLC code is constructed from these PIR term types:

| PirTerm | Description | Example |
|---------|-------------|---------|
| `Var(name, type)` | Variable reference | `new PirTerm.Var("x", new PirType.IntegerType())` |
| `Const(constant)` | Literal value | `new PirTerm.Const(Constant.integer(BigInteger.ZERO))` |
| `Builtin(fun)` | UPLC builtin function | `new PirTerm.Builtin(DefaultFun.AddInteger)` |
| `App(function, arg)` | Function application | `new PirTerm.App(fun, arg)` |
| `Lam(param, type, body)` | Lambda abstraction | `new PirTerm.Lam("x", type, body)` |
| `Let(name, value, body)` | Let binding | `new PirTerm.Let("x", expr, body)` |
| `LetRec(bindings, body)` | Recursive let (for loops) | See examples below |
| `IfThenElse(cond, then, else)` | Conditional | `new PirTerm.IfThenElse(cond, t, f)` |
| `DataConstr(tag, type, fields)` | Data constructor | `new PirTerm.DataConstr(0, type, List.of(f1))` |
| `Error(type)` | Runtime error | `new PirTerm.Error(new PirType.DataType())` |

### Applying Builtins

UPLC builtins are applied one argument at a time (curried):

```java
// AddInteger(a, b) -- two arguments
new PirTerm.App(
    new PirTerm.App(new PirTerm.Builtin(DefaultFun.AddInteger), a),
    b);
```

The UplcGenerator handles force counts automatically -- you don't need to add Force wrappers in PIR.

### Common Constants

```java
Constant.bool(true)                        // Bool
Constant.bool(false)
Constant.integer(BigInteger.valueOf(42))   // Integer
Constant.integer(BigInteger.ZERO)
Constant.byteString(new byte[]{})          // ByteString (empty)
Constant.unit()                            // Unit ()
```

## Available UPLC Builtins

### Integer Arithmetic
`AddInteger`, `SubtractInteger`, `MultiplyInteger`, `DivideInteger`, `QuotientInteger`, `RemainderInteger`, `ModInteger`

### Integer Comparison
`EqualsInteger`, `LessThanInteger`, `LessThanEqualsInteger`

### ByteString Operations
`AppendByteString`, `ConsByteString`, `SliceByteString`, `LengthOfByteString`, `IndexByteString`, `EqualsByteString`, `LessThanByteString`, `LessThanEqualsByteString`

### Cryptography
`Sha2_256`, `Sha3_256`, `Blake2b_256`, `Blake2b_224`, `Keccak_256`, `Ripemd_160`, `VerifyEd25519Signature`, `VerifyEcdsaSecp256k1Signature`, `VerifySchnorrSecp256k1Signature`

### Data Construction
`ConstrData(tag, fields)`, `IData(int)`, `BData(bs)`, `ListData(list)`, `MapData(pairs)`, `MkPairData(a, b)`, `MkCons(elem, list)`, `MkNilData(unit)`, `MkNilPairData(unit)`

### Data Deconstruction
`UnConstrData(data) -> (tag, fields)`, `UnIData(data) -> int`, `UnBData(data) -> bs`, `UnListData(data) -> list`, `UnMapData(data) -> pairs`

### Data Inspection
`EqualsData`, `ChooseData`, `SerialiseData`

### Pair Operations
`FstPair(pair)`, `SndPair(pair)`

### List Operations
`HeadList(list)`, `TailList(list)`, `NullList(list)`, `MkCons(elem, list)`, `ChooseList(list, a, b)`

### Control
`IfThenElse(cond, then, else)`, `Trace(msg, val)`

### String
`AppendString`, `EqualsString`, `EncodeUtf8`, `DecodeUtf8`

## Patterns with Code Examples

### Pattern 1: Simple Builtin Wrapper (1-3 lines)

The simplest stdlib function wraps a single UPLC builtin.

**Example: `CryptoLib.sha2_256`**

```java
public static PirTerm sha2_256(PirTerm bs) {
    return new PirTerm.App(new PirTerm.Builtin(DefaultFun.Sha2_256), bs);
}
```

One line: apply the builtin to the argument.

**Example: `ListsLib.isEmpty`**

```java
public static PirTerm isEmpty(PirTerm list) {
    return new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), list);
}
```

### Pattern 2: Data Field Extraction (5-10 lines)

Extract a field from a Constr-encoded Data value by index.

**Key helper**: `SndPair(UnConstrData(data))` extracts the fields list from a Constr.

**Example: `ContextsLib.getTxInfo`** -- Extract field 0 from ScriptContext

```java
public static PirTerm getTxInfo(PirTerm ctx) {
    // ScriptContext = Constr(0, [txInfo, redeemer, scriptInfo])
    var fields = new PirTerm.App(
            new PirTerm.Builtin(DefaultFun.SndPair),
            new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnConstrData), ctx));
    return new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), fields);
}
```

For fields beyond index 0, chain `TailList` calls:

```java
// Field at index N: HeadList(TailList^N(SndPair(UnConstrData(data))))
private static PirTerm fieldAt(PirTerm data, int index) {
    PirTerm fields = new PirTerm.App(
            new PirTerm.Builtin(DefaultFun.SndPair),
            new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnConstrData), data));
    for (int i = 0; i < index; i++) {
        fields = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), fields);
    }
    return new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), fields);
}
```

### Pattern 3: Recursive List Operation (20-40 lines)

For operations that traverse a list, use `LetRec` for recursion.

**Example: `ListsLib.contains`** -- Search a list for an element

```java
public static PirTerm contains(PirTerm list, PirTerm target, PirType elemType) {
    // 1. Declare variables for the recursive function
    var lstVar = new PirTerm.Var("lst", new PirType.ListType(new PirType.DataType()));
    var goVar = new PirTerm.Var("go", new PirType.FunType(
            new PirType.ListType(new PirType.DataType()),
            new PirType.BoolType()));
    var targetVar = new PirTerm.Var("target_c", new PirType.DataType());

    // 2. Build the recursive body:
    //    go(lst) = if NullList(lst) then False
    //             else let h = HeadList(lst) in
    //                  if eq(h, target) then True
    //                  else go(TailList(lst))
    var nullCheck = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), lstVar);
    var headExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), lstVar);
    var tailExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), lstVar);
    var hVar = new PirTerm.Var("h", new PirType.DataType());

    // Equality check depends on element type
    PirTerm equalCheck = /* build equality based on elemType */;

    var recurse = new PirTerm.App(goVar, tailExpr);
    var innerIf = new PirTerm.IfThenElse(equalCheck,
            new PirTerm.Const(Constant.bool(true)), recurse);
    var letHead = new PirTerm.Let("h", headExpr, innerIf);
    var outerIf = new PirTerm.IfThenElse(nullCheck,
            new PirTerm.Const(Constant.bool(false)), letHead);

    // 3. Wrap in LetRec
    var goBody = new PirTerm.Lam("lst", new PirType.ListType(new PirType.DataType()), outerIf);
    var binding = new PirTerm.Binding("go", goBody);

    return new PirTerm.Let("target_c", target,
            new PirTerm.LetRec(List.of(binding),
                    new PirTerm.App(goVar, list)));
}
```

The key pattern for recursion:
1. Declare a `goVar` that refers to the recursive function
2. Build the body using `goVar` for recursive calls
3. Wrap in `LetRec(binding, App(goVar, initialArg))`

### Pattern 4: Complex Nested Logic (50+ lines)

For complex operations that combine multiple patterns, use `Let` bindings to avoid recomputing expressions.

**Example: `IntervalLib.contains`** -- Check if a time is within an interval

This extracts the interval bounds, decomposes each bound type (NegInf/Finite/PosInf), checks inclusivity, and combines the results. See `IntervalLib.java` for the full implementation.

**Key principles:**
- Use `Let` bindings for any expression used more than once
- Use unique variable names to avoid shadowing (suffix with `_` or a unique tag)
- Decompose complex structures step by step (UnConstrData -> FstPair/SndPair -> further decoding)

## Testing Pattern

Stdlib functions are tested by compiling a validator that calls the function, then evaluating it with test data.

```java
@Test
void testMyStdlibFunction() {
    var source = """
        import com.bloxbean.cardano.plutus.onchain.stdlib.MyLib;

        @Validator
        class TestValidator {
            @Entrypoint
            static boolean validate(BigInteger redeemer, BigInteger ctx) {
                return MyLib.myMethod(42, 10);
            }
        }
        """;

    var stdlib = StdlibRegistry.defaultRegistry();
    var compiler = new PlutusCompiler(stdlib::lookup);
    var result = compiler.compile(source);
    assertFalse(result.hasErrors());

    // Evaluate with dummy arguments
    var vm = VmFactory.create();
    var program = result.program();
    // Apply dummy datum + redeemer + context
    var applied = applyArgs(program, dummyData(), dummyData());
    var evalResult = vm.evaluate(applied);
    // Assert expected result
}
```

Use `ValidatorTest` from `plutus-testkit` for a simpler testing experience:

```java
var test = new ValidatorTest(source);
test.evaluate(datum, redeemer, context);  // returns EvalResult
```

## Checklist for Adding a New Stdlib Function

1. Add the method to your `*Lib.java` in `plutus-stdlib` (the PIR term builder)
2. Register it in `StdlibRegistry.defaultRegistry()` with `requireArgs` validation
3. Add a stub method in `plutus-onchain-api` for IDE support
4. Write a test that compiles a validator using the function and evaluates it
5. Run `./gradlew :plutus-stdlib:test` to verify

## Tips

- **Variable naming**: Use suffixed names (`acc_`, `lst_`, `go_sb`) to avoid clashes when stdlib terms are inlined into user code
- **Don't forget Let**: Complex expressions should be Let-bound to avoid duplicate evaluation on-chain (UPLC has no sharing -- every reference re-evaluates)
- **Data encoding**: Booleans are `Constr(0,[])` (False) / `Constr(1,[])` (True). Optional is `Constr(0,[x])` (Some) / `Constr(1,[])` (None)
- **Force counts**: The UplcGenerator handles these. You only need to build `App(Builtin(fun), arg)` -- forces are added automatically based on the builtin's type signature
- **PirType accuracy**: Types in Var/Lam declarations guide the UplcGenerator's wrapping. Use `DataType` for general Data, `IntegerType`/`ByteStringType`/`BoolType` for decoded values, `ListType` for builtin lists
