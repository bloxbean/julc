# ADR-003: Milestone 3 — Compiler MVP (`plutus-compiler`)

**Status**: Proposed
**Date**: 2026-02-07
**Authors**: BloxBean Team
**Depends on**: ADR-001, ADR-002

---

## Context

With Milestone 1 (UPLC Core + VM) and Milestone 2 (Ledger Types) complete, we can build the compiler that transforms Java source code into working Plutus V3 scripts. This is the heart of the developer experience.

## Decision

Create a `plutus-compiler` module implementing the compiler pipeline from ADR-001 Section 4.4:

```
Java Source -> JavaParser Parse -> Subset Validation -> Type Resolution -> PIR Generation -> PIR → UPLC Lowering -> FLAT Serialization
```

The MVP compiles simple validators with arithmetic, boolean logic, if/else, record construction, and field access. Pattern matching, loops, lambdas, and optimizations are deferred to Milestone 4.

---

## Module Setup

```groovy
// plutus-compiler/build.gradle
plugins { id 'java-library' }
description = 'Java source to UPLC compiler for Cardano smart contracts'
dependencies {
    api project(':plutus-core')
    api project(':plutus-ledger-api')
    implementation 'com.github.javaparser:javaparser-core:3.26.3'
    testRuntimeOnly project(':plutus-vm-scalus')
}
```

---

## Package Structure (from ADR-001 Section 4.5)

```
com.bloxbean.cardano.plutus.compiler
├── annotation/
│   ├── Validator.java            // @Validator annotation
│   ├── MintingPolicy.java        // @MintingPolicy annotation
│   └── Entrypoint.java           // @Entrypoint annotation
├── parse/
│   └── SourceParser.java         // JavaParser integration
├── validate/
│   └── SubsetValidator.java      // Reject unsupported Java features
├── resolve/
│   ├── TypeResolver.java         // Java types → PIR types
│   └── SymbolTable.java          // Variable/method scope tracking
├── pir/
│   ├── PirType.java              // PIR type system
│   ├── PirTerm.java              // PIR term AST (from ADR-001 Section 4.6)
│   └── PirGenerator.java         // Java AST → PIR
├── uplc/
│   └── UplcGenerator.java        // PIR → UPLC (type erasure, De Bruijn)
├── codegen/
│   ├── DataCodecGenerator.java   // Record → PlutusData encode/decode
│   └── ValidatorWrapper.java     // Wrap entrypoint for on-chain
├── error/
│   └── CompilerDiagnostic.java   // Errors with source locations
├── CompilerException.java
├── PlutusCompiler.java           // Main facade
└── CompileResult.java            // Compilation output
```

---

## Annotations

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Validator {}

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface MintingPolicy {}

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Entrypoint {}
```

---

## PIR (Plutus Intermediate Representation) — from ADR-001 Section 4.6

### PIR Type System

```java
public sealed interface PirType {
    // Primitive types
    record IntegerType() implements PirType {}
    record ByteStringType() implements PirType {}
    record StringType() implements PirType {}
    record BoolType() implements PirType {}
    record UnitType() implements PirType {}
    record DataType() implements PirType {}

    // Container types
    record ListType(PirType elemType) implements PirType {}
    record PairType(PirType first, PirType second) implements PirType {}
    record MapType(PirType keyType, PirType valueType) implements PirType {}
    record OptionalType(PirType elemType) implements PirType {}

    // Function type
    record FunType(PirType paramType, PirType returnType) implements PirType {}

    // Algebraic data types
    record RecordType(String name, List<Field> fields) implements PirType {}
    record SumType(String name, List<Constructor> constructors) implements PirType {}

    // Nested helper types
    record Field(String name, PirType type) {}
    record Constructor(String name, int tag, List<Field> fields) {}
}
```

### PIR Term AST

Matches ADR-001 Section 4.6 exactly:

```java
public sealed interface PirTerm {
    record Var(String name, PirType type) implements PirTerm {}
    record Let(String name, PirTerm value, PirTerm body) implements PirTerm {}
    record LetRec(List<Binding> bindings, PirTerm body) implements PirTerm {}
    record Lam(String param, PirType paramType, PirTerm body) implements PirTerm {}
    record App(PirTerm function, PirTerm argument) implements PirTerm {}
    record Const(Constant value) implements PirTerm {}
    record Builtin(DefaultFun fun) implements PirTerm {}
    record IfThenElse(PirTerm cond, PirTerm thenBranch, PirTerm elseBranch) implements PirTerm {}
    record DataConstr(int tag, PirType dataType, List<PirTerm> fields) implements PirTerm {}
    record DataMatch(PirTerm scrutinee, List<MatchBranch> branches) implements PirTerm {}
    record Error(PirType type) implements PirTerm {}
    record Trace(PirTerm message, PirTerm body) implements PirTerm {}

    record Binding(String name, PirTerm value) {}
    record MatchBranch(String constructorName, List<String> bindings, PirTerm body) {}
}
```

---

## SubsetValidator — Supported Java Subset (from ADR-001 Section 4.1)

### Supported (MVP)

| Java Feature | PIR Mapping |
|---|---|
| `BigInteger` arithmetic | `App(App(Builtin(AddInteger/...), a), b)` |
| Comparison operators | EqualsInteger, LessThanInteger, etc. |
| `boolean` operators (`&&`, `\|\|`, `!`) | Short-circuit IfThenElse |
| `if/else` | `IfThenElse(cond, then, else)` |
| Records | `DataConstr(tag, type, fields)` |
| Record field access | UnConstrData + HeadList/TailList |
| Local `var`/`final` | `Let(name, value, body)` |
| Static methods | `Lam(params, body)` |
| Method invocation | `App(function, argument)` |
| Integer/String/Boolean literals | `Const(value)` |

### Rejected (compile-time error)

- `try/catch/finally`, `throw`
- `null` literal
- `synchronized`
- Mutable fields (non-static, non-final)
- `float`, `double`
- Arrays (use `List`)
- `for`, `while`, `do` loops (Milestone 4: desugar to recursion)
- Lambda expressions (Milestone 4)
- `new` on non-record classes
- `this`, `super`
- Class inheritance (use sealed interfaces)
- Threads, reflection, IO, System calls

---

## Type Resolution

| Java Type | PIR Type |
|---|---|
| `BigInteger` | `IntegerType` |
| `byte[]` | `ByteStringType` |
| `String` | `StringType` |
| `boolean` | `BoolType` |
| `void` | `UnitType` |
| `PlutusData` | `DataType` |
| `List<T>` | `ListType(resolve(T))` |
| `Map<K,V>` | `MapType(resolve(K), resolve(V))` |
| `Optional<T>` | `OptionalType(resolve(T))` |
| User records | `RecordType(name, fields)` |
| Sealed interfaces | `SumType(name, constructors)` |
| Ledger hash types | `ByteStringType` (thin wrappers) |
| `ScriptContext`, `TxInfo`, etc. | `DataType` (pass through as raw Data) |

---

## Expression Compilation (PirGenerator)

### Arithmetic

```java
// Java: a + b
// PIR:  App(App(Builtin(AddInteger), Var("a")), Var("b"))

// Java: a * b + c
// PIR:  App(App(Builtin(AddInteger),
//            App(App(Builtin(MultiplyInteger), Var("a")), Var("b"))),
//          Var("c"))
```

### Comparisons

```java
// Java: a == b  (integers)
// PIR:  App(App(Builtin(EqualsInteger), Var("a")), Var("b"))

// Java: a > b
// PIR:  App(App(Builtin(LessThanInteger), Var("b")), Var("a"))  // swap operands

// Java: a != b
// PIR:  IfThenElse(App(App(Builtin(EqualsInteger), a, b)), Const(false), Const(true))
```

### Boolean Short-Circuit

```java
// Java: a && b
// PIR:  IfThenElse(Var("a"), Var("b"), Const(BoolConst(false)))

// Java: a || b
// PIR:  IfThenElse(Var("a"), Const(BoolConst(true)), Var("b"))

// Java: !a
// PIR:  IfThenElse(Var("a"), Const(BoolConst(false)), Const(BoolConst(true)))
```

### Let Bindings

```java
// Java: var x = 42; return x + 1;
// PIR:  Let("x", Const(IntegerConst(42)),
//            App(App(Builtin(AddInteger), Var("x")), Const(IntegerConst(1))))
```

---

## UPLC Generation (UplcGenerator)

Type-erasing translation from PIR to UPLC Term:

| PIR | UPLC |
|-----|------|
| `Var(name)` | `Term.Var(deBruijnIndex)` — computed from scope stack |
| `Const(value)` | `Term.Const(value)` |
| `Lam(param, body)` | `Term.Lam(param, body')` — push scope |
| `App(f, arg)` | `Term.Apply(f', arg')` |
| `Let(name, val, body)` | `Term.Apply(Term.Lam(name, body'), val')` |
| `IfThenElse(c, t, e)` | `Force(Apply(Apply(Apply(Force(Builtin(IfThenElse)), c'), Delay(t')), Delay(e')))` |
| `Builtin(fun)` | `Term.Builtin(fun)` with Force wrapping |
| `DataConstr(tag, fields)` | `Term.Constr(tag, [fields'])` (V3 SOPs) |
| `DataMatch(scrutinee, cases)` | `Term.Case(scrutinee', [branches'])` (V3 SOPs) |
| `Error` | `Term.Error()` |

### De Bruijn Index Computation

Maintain a stack of bound variable names. When encountering `Var(name)`:
1. Search stack from top (innermost) to bottom (outermost)
2. Index = distance from top of stack to the binding

```
Lam("x",                     -- stack: [x]
  Lam("y",                   -- stack: [y, x]
    App(Var("x"), Var("y"))   -- x = index 2 (distance from y,x), y = index 1
  )
)
```

### Force Count for Polymorphic Builtins

| Builtin | Force Count |
|---------|-------------|
| `IfThenElse`, `ChooseUnit`, `Trace`, `ChooseData` | 1 |
| `FstPair`, `SndPair`, `ChooseList`, `MkCons`, `HeadList`, `TailList`, `NullList` | 2 |
| All others | 0 |

---

## Data Codec Generation (DataCodecGenerator)

For a record `VestingDatum(byte[] beneficiary, BigInteger deadline)`:

### toData (encode to PlutusData at UPLC level)

```
\datum ->
  let pair = UnConstrData(datum)
  let fields = SndPair(pair)
  in ConstrData(0,
       MkCons(HeadList(fields),           -- beneficiary: already ByteString Data
         MkCons(HeadList(TailList(fields)),  -- deadline: already Integer Data
           MkNilData())))
```

Actually for encoding a structured record → Data, the pattern is:
```
\beneficiary \deadline ->
  ConstrData(0, MkCons(BData(beneficiary), MkCons(IData(deadline), MkNilData())))
```

### fromData (decode from PlutusData)

```
\data ->
  let pair = UnConstrData(data)
  let fields = SndPair(pair)
  let beneficiary = UnBData(HeadList(fields))
  let deadline = UnIData(HeadList(TailList(fields)))
  in (beneficiary, deadline)   -- represented as Constr in UPLC
```

### Type-to-Builtin Mapping

| PIR Type | Encode (value → Data) | Decode (Data → value) |
|----------|----------------------|----------------------|
| `IntegerType` | `IData` | `UnIData` |
| `ByteStringType` | `BData` | `UnBData` |
| `DataType` | identity | identity |
| `BoolType` | Constr(0/1, []) | UnConstrData + check tag |
| `ListType` | `ListData` | `UnListData` |
| `MapType` | `MapData` | `UnMapData` |

---

## Validator Wrapping (ValidatorWrapper)

For V3, a validator receives one argument: `ScriptContext` as `Data`.

### Spending Validator Wrapper

```
\scriptContextData ->
  let ctxPair = UnConstrData(scriptContextData)
  let ctxFields = SndPair(ctxPair)
  let txInfoData = HeadList(ctxFields)
  let redeemerData = HeadList(TailList(ctxFields))
  let scriptInfoData = HeadList(TailList(TailList(ctxFields)))
  // Extract datum from SpendingScript info
  let infoPair = UnConstrData(scriptInfoData)
  let infoFields = SndPair(infoPair)
  let datumOptData = HeadList(TailList(infoFields))
  // Decode datum using generated codec
  let datum = fromData_VestingDatum(datumOptData)
  // Call user validation function
  let result = validate(datum, redeemerData, scriptContextData)
  in IfThenElse(result, Unit, Error)
```

### Minting Policy Wrapper

```
\scriptContextData ->
  let ctxPair = UnConstrData(scriptContextData)
  let ctxFields = SndPair(ctxPair)
  let redeemerData = HeadList(TailList(ctxFields))
  let result = validate(redeemerData, scriptContextData)
  in IfThenElse(result, Unit, Error)
```

---

## PlutusCompiler Facade

```java
public class PlutusCompiler {
    public CompileResult compile(String javaSource) {
        // 1. Parse with JavaParser
        CompilationUnit cu = StaticJavaParser.parse(javaSource);

        // 2. Validate subset
        List<CompilerDiagnostic> diagnostics = new SubsetValidator().validate(cu);
        if (hasErrors(diagnostics)) throw new CompilerException(diagnostics);

        // 3. Find @Validator class and @Entrypoint method
        // 4. Resolve types (TypeResolver)
        // 5. Generate PIR (PirGenerator)
        // 6. Generate Data codecs (DataCodecGenerator)
        // 7. Wrap entrypoint (ValidatorWrapper)
        // 8. Lower to UPLC (UplcGenerator)
        // 9. Create Program (version 1.1.0 for V3)

        return new CompileResult(program, diagnostics);
    }
}

public record CompileResult(Program program, List<CompilerDiagnostic> diagnostics) {
    public byte[] toFlatBytes() { return UplcFlatEncoder.encodeProgram(program); }
}
```

---

## MVP Scope Boundaries

### Included in Milestone 3 (MVP)
- Arithmetic, comparison, boolean operators
- If/else expressions
- Record construction and field access
- Local variable bindings
- Static method calls
- Record → PlutusData codec generation
- Validator wrapping (spending + minting)
- End-to-end: Java → compile → evaluate via PlutusVm

### Deferred to Milestone 4 (Full Compiler)
- Sealed interface → sum type compilation
- Pattern matching (instanceof, switch) → Case (using `desugar/PatternMatchDesugarer`)
- Loop desugaring (for-each, while → recursion) (using `desugar/LoopDesugarer`)
- Lambda expressions
- Complex let bindings and recursive definitions
- UPLC optimizations (dead code elimination, constant folding, eta reduction, inlining) (`uplc/UplcOptimizer`)
- PIR-level optimizations (`pir/PirOptimizer`)
- Error diagnostics with precise source locations
- Separate desugarer classes (`desugar/OperatorDesugarer`, `desugar/BooleanDesugarer`, `desugar/LetBindingDesugarer`)

---

## Testing Strategy

1. **SubsetValidator tests**: One test per rejected construct
2. **TypeResolver tests**: One per type mapping
3. **PirGenerator tests**: One per expression pattern
4. **UplcGenerator tests**: De Bruijn indexing, Force counts, let-as-application
5. **DataCodecGenerator tests**: Simple/nested records, various field types
6. **ValidatorWrapper tests**: Spending/minting wrapper structure
7. **PlutusCompiler tests**: Full pipeline invocations
8. **End-to-end integration**: Compile → evaluate via PlutusVm → verify results

**Estimated total: ~205 tests across 11 tasks**

---

## Reference Implementations

- **Scalus compiler**: `/Users/satya/work/cardano-comm-projects/scalus/scalus-plugin/src/main/scala/scalus/compiler/plugin/`
  - SIRCompiler.scala — main compilation logic
  - Plugin.scala — Dotty compiler plugin phases
  - PatternMatchingCompiler.scala — pattern matching → decision trees
- **Scalus lowering**: `/Users/satya/work/cardano-comm-projects/scalus/scalus-core/shared/src/main/scala/scalus/compiler/sir/lowering/SirToUplcV3Lowering.scala`
- **Scalus builtins**: `/Users/satya/work/cardano-comm-projects/scalus/scalus-core/shared/src/main/scala/scalus/compiler/sir/SIRBuiltins.scala`
