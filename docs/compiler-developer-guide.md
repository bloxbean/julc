# JuLC Compiler Developer Guide

A comprehensive guide for developers who maintain, debug, or extend the JuLC compiler — the Java-to-UPLC compiler for Cardano smart contracts.

## Table of Contents

**Part I: Foundations**
1. [Introduction](#1-introduction)
2. [Conceptual Background: UPLC, PIR, and Data Encoding](#2-conceptual-background-uplc-pir-and-data-encoding)
3. [Module Architecture](#3-module-architecture)
4. [The Type System](#4-the-type-system)

**Part II: Compilation Pipeline**
5. [Pipeline Overview](#5-pipeline-overview)
6. [Phase 1: Parsing and Validation](#6-phase-1-parsing-and-validation)
7. [Phase 2: Type Registration](#7-phase-2-type-registration)
8. [Phase 3: PIR Generation](#8-phase-3-pir-generation)
9. [Phase 4: Loop Desugaring](#9-phase-4-loop-desugaring)
10. [Phase 5: Pattern Matching](#10-phase-5-pattern-matching)
11. [Phase 6: Validator Wrapping](#11-phase-6-validator-wrapping)
12. [Phase 7: UPLC Generation and Optimization](#12-phase-7-uplc-generation-and-optimization)
13. [Phase 8: Library Compilation](#13-phase-8-library-compilation)

**Part III: Subsystem Deep Dives**
14. [The Symbol Table](#14-the-symbol-table)
15. [The Type Method Registry](#15-the-type-method-registry)
16. [The Stdlib Registry](#16-the-stdlib-registry)
17. [Error Reporting](#17-error-reporting)

**Part IV: How-To Recipes**
18. [How to Add a New Instance Method](#18-how-to-add-a-new-instance-method)
19. [How to Add a New Builtin Function](#19-how-to-add-a-new-builtin-function)
20. [How to Add a New Java Type Mapping](#20-how-to-add-a-new-java-type-mapping)
21. [How to Add a New Stdlib Library](#21-how-to-add-a-new-stdlib-library)
22. [How to Add a New Ledger Type](#22-how-to-add-a-new-ledger-type)
23. [How to Add a New Optimization Pass](#23-how-to-add-a-new-optimization-pass)
24. [How to Trace Through a Compilation](#24-how-to-trace-through-a-compilation)

**Part V: Reference**
25. [Known Compiler Limitations](#25-known-compiler-limitations)
26. [Debugging Techniques](#26-debugging-techniques)
27. [Testing Patterns](#27-testing-patterns)
28. [Architecture Decision Records](#28-architecture-decision-records)
29. [Glossary](#29-glossary)
30. [Quick Reference: File Index](#30-quick-reference-file-index)

---

# Part I: Foundations

## 1. Introduction

### Purpose

This guide covers the internals of the JuLC compiler — the component that transforms a Java source subset into Untyped Plutus Lambda Calculus (UPLC) programs that run on the Cardano blockchain. It is intended for contributors who need to fix bugs, add language features, register new builtins, or understand compilation behavior.

### Prerequisites

- Java 21+ features: sealed interfaces, records, pattern matching
- Basic lambda calculus: application, abstraction, substitution, De Bruijn indices
- Familiarity with the Cardano transaction model (UTxOs, validators, datums, redeemers)

### Existing User-Facing Docs

| Guide | Audience |
|-------|----------|
| `docs/getting-started.md` | First-time users writing validators |
| `docs/api-reference.md` | On-chain API reference |
| `docs/stdlib-guide.md` | Standard library usage |
| `docs/library-developer-guide.md` | Writing `@OnchainLibrary` modules |
| `docs/for-loop-patterns.md` | Loop compilation patterns |
| `docs/performance-guide.md` | Script size and cost optimization |
| `docs/troubleshooting.md` | Common errors and solutions |
| `docs/advanced-guide.md` | Low-level programming patterns |
| `docs/type-method-compilation-comparison.md` | Type method compilation details |

---

## 2. Conceptual Background: UPLC, PIR, and Data Encoding

### 2.1 What is UPLC?

Untyped Plutus Lambda Calculus (UPLC) is the on-chain language that Cardano nodes execute. It is a minimal lambda calculus with 10 term variants, defined in `julc-core/.../Term.java`:

| # | Variant | Description |
|---|---------|-------------|
| 1 | `Var(NamedDeBruijn name)` | Variable reference (De Bruijn indexed) |
| 2 | `Lam(String paramName, Term body)` | Lambda abstraction |
| 3 | `Apply(Term function, Term argument)` | Function application |
| 4 | `Force(Term term)` | Force evaluation of a delayed (polymorphic) term |
| 5 | `Delay(Term term)` | Delay evaluation (create a thunk) |
| 6 | `Const(Constant value)` | Constant value (integer, bytestring, string, bool, unit, data) |
| 7 | `Builtin(DefaultFun fun)` | Reference to one of 102 built-in functions |
| 8 | `Error()` | Halt evaluation (transaction fails) |
| 9 | `Constr(long tag, List<Term> fields)` | Constructor application (Plutus V3, SOPs) |
| 10 | `Case(Term scrutinee, List<Term> branches)` | Case/pattern matching (Plutus V3, SOPs) |

**De Bruijn indexing.** Variables are referenced by how many lambda binders you must cross to reach the binding site. Index 1 means "the immediately enclosing lambda":

```
\x -> \y -> x   ≡   Lam("x", Lam("y", Var(2)))
                                          ^^^  cross y(1) then x(2)
```

**Force/Delay.** UPLC is strict (call-by-value). Polymorphic builtins like `IfThenElse` need `Force` to instantiate type variables, and branches need `Delay` to prevent premature evaluation:

```
-- if True then "yes" else "no"
Force(Apply(Apply(Apply(Force(Builtin(IfThenElse)), Const(True)),
            Delay(Const("yes"))),
      Delay(Const("no"))))
```

### 2.2 What is PIR?

Plutus Intermediate Representation (PIR) is the typed bridge between Java and UPLC. It has 12 term variants defined in `julc-compiler/.../pir/PirTerm.java`:

| # | Variant | Description |
|---|---------|-------------|
| 1 | `Var(String name, PirType type)` | Named, typed variable |
| 2 | `Let(String name, PirTerm value, PirTerm body)` | Let binding |
| 3 | `LetRec(List<Binding> bindings, PirTerm body)` | Recursive let (for loops, self-recursion) |
| 4 | `Lam(String param, PirType paramType, PirTerm body)` | Typed lambda |
| 5 | `App(PirTerm function, PirTerm argument)` | Application |
| 6 | `Const(Constant value)` | Constant |
| 7 | `Builtin(DefaultFun fun)` | Builtin function |
| 8 | `IfThenElse(PirTerm cond, PirTerm then, PirTerm else)` | Conditional |
| 9 | `DataConstr(int tag, PirType type, List<PirTerm> fields)` | Data constructor |
| 10 | `DataMatch(PirTerm scrutinee, List<MatchBranch> branches)` | Pattern match |
| 11 | `Error(PirType type)` | Typed error |
| 12 | `Trace(PirTerm message, PirTerm body)` | Trace debug output |

**Side-by-side compilation example:**

```java
// Java
int x = 5;
int y = x + 3;
return y;
```

```
// PIR
Let("x", Const(Integer(5)),
  Let("y", App(App(Builtin(AddInteger), Var("x", IntegerType)), Const(Integer(3))),
    Var("y", IntegerType)))
```

```
// UPLC (Let → Apply(Lam, val), names erased to De Bruijn)
Apply(Lam("x",
  Apply(Lam("y", Var(1)),
    Apply(Apply(Builtin(AddInteger), Var(1)), Const(Integer(3))))),
  Const(Integer(5)))
```

### 2.3 The Data Encoding

On the Cardano ledger, all values are encoded as **Data** — a universal representation with 5 constructors:

| Constructor | Encodes | Encode builtin | Decode builtin |
|-------------|---------|----------------|----------------|
| `Constr(tag, fields)` | Records, sealed interfaces, booleans, Optional | `ConstrData` | `UnConstrData` |
| `Map(pairs)` | `Map<K,V>` | `MapData` | `UnMapData` |
| `List(items)` | `List<T>` | `ListData` | `UnListData` |
| `I(integer)` | `int`, `long`, `BigInteger` | `IData` | `UnIData` |
| `B(bytestring)` | `byte[]`, hash types, `TokenName` | `BData` | `UnBData` |

The compiler inserts encode/decode wrappers at type boundaries. The two key helpers in `PirHelpers.java`:

**`wrapDecode(PirTerm data, PirType targetType)`** — Extract a typed value from raw Data:
- `IntegerType` → `UnIData(data)`
- `ByteStringType` → `UnBData(data)`
- `ListType` → `UnListData(data)`
- `MapType` → `UnMapData(data)`
- `BoolType` → `EqualsInteger(FstPair(UnConstrData(data)), 1)` (tag 1 = True, 0 = False)
- `StringType` → `DecodeUtf8(UnBData(data))`
- `DataType`, `RecordType`, `SumType` → pass through (already Data)

**`wrapEncode(PirTerm value, PirType type)`** — Wrap a typed value back into Data:
- `IntegerType` → `IData(value)`
- `ByteStringType` → `BData(value)`
- `BoolType` → `IfThenElse(value, ConstrData(1,[]), ConstrData(0,[]))`
- `StringType` → `BData(EncodeUtf8(value))`
- `ListType` → `ListData(value)`
- `MapType` → `MapData(value)`
- `DataType`, `RecordType`, `SumType` → pass through

**Boolean encoding:** Booleans map to `Constr(1, [])` (True) and `Constr(0, [])` (False) — matching the Haskell convention.

**Record encoding:** A record `Point(int x, int y)` with `x=10, y=20` encodes as:
```
Constr(0, [I(10), I(20)])
```

**Sealed interface encoding:** Variants get ascending tags:
```java
sealed interface Shape {
    record Circle(int radius) implements Shape {}    // tag 0
    record Rect(int w, int h) implements Shape {}    // tag 1
}
// Circle(5) → Constr(0, [I(5)])
// Rect(3,4) → Constr(1, [I(3), I(4)])
```

---

## 3. Module Architecture

### Module Dependency Diagram

```
                        ┌────────────────┐
                        │   julc-bom     │  (Bill of Materials)
                        └────────────────┘

  ┌─────────────┐     ┌────────────────┐     ┌──────────────────┐
  │  julc-core  │ ◄── │  julc-compiler │ ──► │   julc-stdlib    │
  │ (UPLC AST)  │     │ (Java→UPLC)    │     │ (StdlibRegistry) │
  └──────┬──────┘     └───────┬────────┘     └──────────────────┘
         │                    │
  ┌──────┴──────┐     ┌──────┴────────┐     ┌──────────────────┐
  │   julc-vm   │     │julc-ledger-api│     │ julc-onchain-api │
  │  (VM SPI)   │     │ (ScriptCtx)   │     │ (Annotations)    │
  └──────┬──────┘     └───────────────┘     └──────────────────┘
         │
  ┌──────┴──────────┐
  │ julc-vm-scalus  │
  │ (Scalus backend)│
  └─────────────────┘

  ┌─────────────────┐  ┌──────────────────────┐  ┌────────────────────┐
  │  julc-testkit   │  │ julc-annotation-proc  │  │ julc-gradle-plugin │
  │ (Test framework)│  │ (Build-time compile)  │  │ (Gradle integration│
  └─────────────────┘  └──────────────────────┘  └────────────────────┘

  ┌─────────────────┐  ┌──────────────────────┐  ┌────────────────────┐
  │  julc-examples  │  │ julc-cardano-client  │  │  julc-e2e-tests   │
  │ (Sample code)   │  │ (CCL integration)    │  │  (E2E tests)      │
  └─────────────────┘  └──────────────────────┘  └────────────────────┘

  ┌─────────────────┐
  │ julc-plugin-test│
  │ (Plugin tests)  │
  └─────────────────┘
```

### Module Roles

| Module | Role |
|--------|------|
| `julc-core` | UPLC AST (`Term.java`), constants, `DefaultFun` enum (102 builtins), FLAT/CBOR serialization, `Program` wrapper |
| `julc-compiler` | The compiler itself — parsing, validation, type resolution, PIR generation, loop desugaring, pattern matching, UPLC lowering, optimization |
| `julc-stdlib` | `StdlibRegistry` — PIR term builders for ~65 stdlib methods (builtins, HOFs, math) |
| `julc-vm` | VM SPI interface — pluggable execution backend |
| `julc-vm-scalus` | Scalus-based VM implementation (evaluates UPLC programs) |
| `julc-ledger-api` | Java records for Cardano ledger types (TxInfo, ScriptContext, TxOut, etc.) |
| `julc-onchain-api` | Annotations (`@Validator`, `@Entrypoint`, `@Param`, `@OnchainLibrary`) + off-chain stubs for stdlib classes |
| `julc-testkit` | `ValidatorTest` base class, `SourceDiscovery` for test-time compilation |
| `julc-annotation-processor` | Java annotation processor — compiles validators at build time |
| `julc-gradle-plugin` | Gradle plugin wrapping the annotation processor |
| `julc-cardano-client-lib` | Integration with cardano-client-lib v0.7.1 |
| `julc-examples` | Example validators and library code |
| `julc-e2e-tests` | End-to-end integration tests (CIP-113, etc.) |
| `julc-plugin-test` | Tests for the Gradle plugin |
| `julc-bom` | Bill of Materials for dependency management |

### Internal Package Structure of julc-compiler

```
com.bloxbean.cardano.julc.compiler/
├── JulcCompiler.java          # Main pipeline orchestrator 
├── LibraryCompiler.java       # Library compilation sub-pipeline 
├── CompileResult.java         # Compilation result record
├── CompilerException.java     # Fatal compiler error
├── CompilerOptions.java       # Compilation options
├── LibrarySourceResolver.java # Classpath scanning + transitive resolution
├── codegen/                   # ValidatorWrapper, DataCodecGenerator
├── desugar/                   # LoopDesugarer, PatternMatchDesugarer
├── error/                     # CompilerDiagnostic, DiagnosticCollector
├── pir/                       # PIR generation subsystem 
│   ├── PirGenerator.java      #   Core PIR generation 
│   ├── LoopBodyGenerator.java #   Loop body compilation 
│   ├── AccumulatorTypeAnalyzer.java # Accumulator type analysis 
│   ├── TypeInferenceHelper.java #   Read-only type inference 
│   ├── TypeMethodRegistry.java #   Instance method dispatch 
│   ├── PirHelpers.java        #   wrapDecode/wrapEncode + utilities 
│   ├── PirHofBuilders.java    #   HOF PIR builders 
│   ├── PirTerm.java, PirType.java # PIR AST + type system
│   └── StdlibLookup.java, CompositeStdlibLookup.java # Stdlib resolution
├── resolve/                   # TypeResolver, TypeRegistrar, SymbolTable,
│                              #   LedgerSourceLoader, LibraryMethodRegistry
├── uplc/                      # UplcGenerator, UplcOptimizer
└── validate/                  # SubsetValidator
```

---

## 4. The Type System

### 4.1 PirType Hierarchy

The PIR type system is defined as a sealed interface in `julc-compiler/.../pir/PirType.java` with 13 variants:

**Primitive types (6):**

| Variant | Java types | On-chain |
|---------|-----------|----------|
| `IntegerType` | `int`, `long`, `BigInteger` | Arbitrary-precision integer |
| `ByteStringType` | `byte[]`, `PubKeyHash`, `TxId`, `PolicyId`, `TokenName`, etc. | Raw bytes |
| `StringType` | `String` | UTF-8 text |
| `BoolType` | `boolean`, `Boolean` | Constr(0/1) |
| `UnitType` | `void` | Unit constant |
| `DataType` | `PlutusData`, `ConstrData`, `MapData`, `ListData`, `IntData`, `BytesData` | Raw untyped Data |

**Container types (4):**

| Variant | Fields | Java type |
|---------|--------|-----------|
| `ListType(PirType elemType)` | Element type | `List<T>` |
| `PairType(PirType first, PirType second)` | First, second | Internal (pair tuples) |
| `MapType(PirType keyType, PirType valueType)` | Key, value types | `Map<K,V>` |
| `OptionalType(PirType elemType)` | Element type | `Optional<T>` |

**Function type (1):**

| Variant | Fields | Use |
|---------|--------|-----|
| `FunType(PirType paramType, PirType returnType)` | Param, return | Method signatures (curried) |

**Algebraic data types (2):**

| Variant | Fields | Java type |
|---------|--------|-----------|
| `RecordType(String name, List<Field> fields)` | Name, fields | `record` declarations |
| `SumType(String name, List<Constructor> ctors)` | Name, constructors | `sealed interface` with record variants |

Helper records:
- `Field(String name, PirType type)` — record field
- `Constructor(String name, int tag, List<Field> fields)` — sum type variant

### 4.2 Java-to-PIR Type Mapping

Defined in `TypeResolver.resolve(Type)`:

| Java Type | PIR Type | Notes |
|-----------|----------|-------|
| `boolean` | `BoolType` | |
| `int`, `long` | `IntegerType` | |
| `void` | `UnitType` | |
| `var` | `DataType` | Placeholder; PirGenerator infers actual type from initializer |
| `byte[]` | `ByteStringType` | Special-cased array type |
| `BigInteger` | `IntegerType` | |
| `String` | `StringType` | |
| `Boolean` | `BoolType` | Boxed |
| `PlutusData`, `ConstrData`, `MapData`, `ListData`, `IntData`, `BytesData` | `DataType` | All raw data types |
| `List<T>` | `ListType(resolve(T))` | Generic |
| `Map<K,V>` | `MapType(resolve(K), resolve(V))` | Generic |
| `Optional<T>` | `OptionalType(resolve(T))` | Generic |
| `JulcArray<T>` | `ArrayType(resolve(T))` | PV11 only; O(1) random access (CIP-156) |
| `PubKeyHash`, `ScriptHash`, `ValidatorHash`, `PolicyId`, `TokenName`, `DatumHash`, `TxId` | `ByteStringType` | Ledger hash types |
| `StakingCredential`, `ScriptPurpose`, `Vote`, `Voter`, `DRep`, `Delegatee`, `GovernanceActionId`, `GovernanceAction`, `ProposalProcedure`, `TxCert`, `Rational`, `ProtocolVersion`, `Committee` | `DataType` | Opaque ledger types |
| Registered records | `RecordType(...)` | User-defined or ledger records |
| Registered sealed interfaces | `SumType(...)` | User-defined or ledger sum types |

### 4.3 Type Registration

Types must be registered before they can be resolved. Registration happens in two places:

**1. `LedgerTypeRegistry.registerAll(TypeResolver)`** — Pre-registers all Cardano ledger types in 4 tiers:

| Tier | Types | Examples |
|------|-------|---------|
| Tier 1: Simple leaf records | Records with only primitive fields | `TxOutRef`, `IntervalBound`, `Value` |
| Tier 2: Sealed interfaces | Sum types | `Credential`, `OutputDatum`, `ScriptInfo`, `IntervalBoundType` |
| Tier 3: Composite records | Records referencing Tier 1/2 types | `Address`, `TxOut`, `TxInInfo`, `Interval` |
| Tier 4: Top-level types | Types referencing Tier 3 | `TxInfo` (16 fields), `ScriptContext` (3 fields) |

**2. `TypeRegistrar.registerAll(List<CompilationUnit>, TypeResolver)`** — Registers user-defined types via topological sort:

1. **Collect** — Scan all compilation units for `record` and `sealed interface` declarations
2. **Validate** — Check for duplicate type names across compilation units
3. **Build dependency graph** — Record fields of type `OtherRecord` create a dependency edge
4. **Topological sort (Kahn's algorithm):**
   - Compute in-degree for each type
   - Emit types with in-degree 0 (no dependencies)
   - Decrement dependents, repeat
   - If remaining types exist after queue empties → cycle detected, throw `CompilerException`
5. **Register** — Call `typeResolver.registerRecord()` or `registerSealedInterface()` in dependency order

### 4.4 The DataType Escape Hatch

`DataType` is the "untyped" PIR type. The compiler uses it when:

- The Java type is `PlutusData` or any of its subtypes
- The Java type is an opaque ledger type (e.g., `StakingCredential`)
- The Java type is `var` and the initializer type cannot be inferred
- The type is unknown to the resolver

**Pitfalls:**
- `DataType` values pass through encode/decode unchanged — no type checking
- Cross-method calls may infer `DataType` where a specific type was expected (see Known Limitations, Section 25)
- `@Param` fields are always raw Data at runtime regardless of declared Java type

---

# Part II: The Compilation Pipeline

## 5. Pipeline Overview

The compilation pipeline is orchestrated by `JulcCompiler.compile()`. Here is the complete flow:

```
Java Source(s)
     │
     ▼
┌─────────────────────────────────────┐
│  1. Parse                           │  JavaParser → CompilationUnit ASTs
│  2. Validate                        │  SubsetValidator rejects unsupported constructs
│  3. Library Check                   │  Ensure libraries don't contain @Validator
│  4. Annotated Class Discovery       │  Find class with @Validator/@MintingPolicy/etc.
│  5. Script Purpose Detection        │  Map annotation → SPENDING/MINTING/WITHDRAW/...
│  6. Type Registration               │  LedgerTypeRegistry + TypeRegistrar (topo sort)
│  7. @Param Field Detection          │  Find @Param-annotated fields
│  8. Static Field Detection          │  Find static fields with initializers
│  9. Entrypoint Discovery            │  Find @Entrypoint method
│ 10. Parameter Validation            │  Check param count (2 or 3 for spending, 2 others)
│ 11. Library Compilation             │  Multi-pass compile library methods to PIR
│ 12. Compose Lookups                 │  CompositeStdlibLookup(stdlib, libraries)
│ 13. Symbol Table Setup              │  Define @Params, static fields, static methods
│ 14. Helper Method Generation        │  Generate PIR for all static helper methods
│ 15. Entrypoint Generation           │  Generate PIR for the @Entrypoint method
│ 16. Helper Wrapping                 │  Wrap helpers as Let bindings
│ 17. Static Field Wrapping           │  Wrap static field initializers as Let bindings
│ 18. Library Wrapping                │  Wrap library methods as Let/LetRec (topo sorted)
│ 19. Validator Wrapping              │  Add ScriptContext decoder + bool→unit/error
│ 20. @Param Wrapping                 │  Add outer lambda per @Param (Data → decoded)
│ 21. UPLC Generation                 │  UplcGenerator: PIR → UPLC
│ 22. Optimization                    │  UplcOptimizer: 6 passes, fixpoint
│ 23. Program Creation                │  Program.plutusV3(uplcTerm)
│ 24. ParamInfo Creation              │  Build param metadata list
└─────────────────────────────────────┘
     │
     ▼
  CompileResult(program, params, diagnostics)
```

---

## 6. Phase 1: Parsing and Validation

### Parsing

The compiler uses [JavaParser](https://javaparser.org/) configured for Java 21 language level:

```java
StaticJavaParser.getParserConfiguration()
    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
```

Each source string is parsed into a `CompilationUnit` AST. Parse errors are reported as `CompilerDiagnostic` entries.

### SubsetValidator

`SubsetValidator` (`validate/SubsetValidator.java`) extends JavaParser's `VoidVisitorAdapter` to walk the AST and reject unsupported Java constructs. It collects non-fatal diagnostics so multiple errors can be reported at once.

**Rejected constructs:**

| Category | Construct | Error Message | Suggestion |
|----------|-----------|---------------|------------|
| Statements | `try/catch` | "try/catch is not supported on-chain" | "Use if/else checks instead of exception handling" |
| | `throw` | "throw is not supported on-chain" | "Return false from the validator to reject a transaction" |
| | `synchronized` | "synchronized is not supported on-chain" | "On-chain code is single-threaded" |
| | `for(;;)` | "C-style for loops are not supported" | "Use for-each over a list or while loops" |
| | `do-while` | "do-while loops are not supported" | "Use while loops or for-each" |
| | `break` (outside loop) | "break is only supported inside for-each or while" | — |
| Expressions | `null` | "null is not supported on-chain" | "Use `Optional<T>` to represent absence" |
| | `this` | "'this' is not supported on-chain" | "Use static methods instead" |
| | `super` | "'super' is not supported on-chain" | "Use sealed interfaces and pattern matching" |
| | `new T[]` | "arrays are not supported on-chain" | "Use `List<T>` instead" |
| | `arr[i]` | "array access is not supported" | "Use `List<T>` with list operations" |
| Types | `float`, `double` | "floating point types not supported" | "Use `BigInteger` or `Rational`" |
| Classes | `extends` (non-Object) | "class inheritance is not supported" | "Use sealed interfaces with record variants" |

**Allowed constructs:**
- `for-each` loops (desugared to fold)
- `while` loops (desugared to recursion)
- Nested loops (while-in-while, for-each-in-for-each, mixed)
- `break` inside for-each/while
- `new RecordType(...)` (record construction)
- `switch` expressions on sealed interfaces (pattern matching)
- `instanceof` with pattern variables

---

## 7. Phase 2: Type Registration

### TypeRegistrar

`TypeRegistrar.registerAll()` processes all compilation units (validator + libraries) together:

```
1. Collect all record and sealed interface declarations
2. Validate no duplicate type names across CUs
3. Build dependency graph:
   - Record field types create edges (e.g., TxOut.address → Address)
   - Sealed interface → variant record edges
   - Generic type args extracted recursively (List<ProofStep> → ProofStep)
4. Topological sort (Kahn's algorithm):
   - Compute in-degree for each type
   - Queue types with in-degree 0
   - Dequeue → register → decrement dependents → repeat
   - Remaining types after queue empties → cycle error
5. Register in dependency order (skip already-registered ledger types)
```

### LedgerTypeRegistry

`LedgerTypeRegistry.registerAll()` is called before `TypeRegistrar` to pre-register all Cardano ledger types. The 4-tier ordering ensures types are available when referenced as field types.

**Key types registered:**

| Type | Fields | Tier |
|------|--------|------|
| `TxOutRef` | `txId: ByteString`, `index: Integer` | 1 |
| `Value` | `inner: Map<ByteString, Map<ByteString, Integer>>` | 1 |
| `Credential` | PubKeyCredential(0, hash) / ScriptCredential(1, hash) | 2 |
| `ScriptInfo` | MintingScript(0) / SpendingScript(1) / RewardingScript(2) / CertifyingScript(3) / VotingScript(4) / ProposingScript(5) | 2 |
| `Address` | `credential: Credential`, `stakingCredential: Data` | 3 |
| `TxOut` | `address: Address`, `value: Value`, `datum: OutputDatum`, `referenceScript: Data` | 3 |
| `TxInInfo` | `outRef: TxOutRef`, `resolved: TxOut` | 3 |
| `TxInfo` | 16 fields (inputs, outputs, fee, mint, signatories, ...) | 4 |
| `ScriptContext` | `txInfo: TxInfo`, `redeemer: Data`, `scriptInfo: ScriptInfo` | 4 |

---

## 8. Phase 3: PIR Generation

This is the heart of the compiler. `PirGenerator` transforms JavaParser AST nodes into PIR terms.

### 8.1 PirGenerator Architecture

After the ADR-018 refactoring, PirGenerator (2,147 lines) delegates to three extracted helper classes:

- **`AccumulatorTypeAnalyzer`** — Pure AST analysis: detects accumulator variables in loops, refines types (e.g., distinguishing `ListType` vs `MapType` accumulators based on pair operation evidence)
- **`TypeInferenceHelper`** — Read-only type inference: `resolveExpressionType()`, `inferPirType()`, `inferBuiltinReturnType()`, etc. Takes read-only dependencies (SymbolTable, TypeResolver, StdlibLookup, TypeMethodRegistry)
- **`LoopBodyGenerator`** — Loop body compilation: 9 body-processing methods across 5 loop paths (single/multi accumulator × break/no-break), plus pack/unpack helpers and nested loop handling

All three are package-private, final classes in the `pir/` package.

**Constructor dependencies:**

```java
PirGenerator(TypeResolver typeResolver,
             SymbolTable symbolTable,
             StdlibLookup stdlibLookup,
             TypeMethodRegistry typeMethodRegistry,
             String libraryClassName)  // non-null when compiling @OnchainLibrary
```

**Two entry points:**

1. **`generateMethod(MethodDeclaration)`** — Compiles an entrypoint or helper method:
   - Pushes a new scope
   - Registers parameters
   - Compiles the method body via `generateBlock()`
   - Wraps result in nested lambdas: `\p1 -> \p2 -> ... -> body`

2. **`generateExpression(Expression)`** (public) — Compiles any expression node to a PIR term. Dispatches by expression type to specialized handlers.

**Callback surface for LoopBodyGenerator:**

LoopBodyGenerator calls back to PirGenerator via 7 methods (1 already public, 6 widened to package-private):
- `generateExpression()` (public), `generateStatement()`, `generateWhileStmt()`, `generateForEachStmt()`, `inferType()`, `enrichedError()` (static), `detectForEachAccumulators()`

**Error collection:**
- Non-fatal: `collectError(msg, suggestion, node)` → adds `CompilerDiagnostic`, returns `PirTerm.Error`
- Fatal: `enrichedError(msg, suggestion, node)` → throws `CompilerException` immediately
- Fuzzy method name matching via Levenshtein distance for "did you mean?" suggestions

### 8.2 Expression Compilation

**Literals:**

| Java | PIR |
|------|-----|
| `42` / `42L` | `Const(Integer(42))` |
| `0xFF` | `Const(Integer(255))` |
| `true` | `Const(Bool(true))` |
| `"hello"` | `Const(String("hello"))` |

**Variables:**
- `name` → `Var(name, type)` where type is looked up from the SymbolTable

**Binary operations (type-aware dispatch):**

| Op | Default | String | ByteString |
|----|---------|--------|------------|
| `+` | `AddInteger` | `AppendString` | `AppendByteString` |
| `-` | `SubtractInteger` | — | — |
| `*` | `MultiplyInteger` | — | — |
| `/` | `DivideInteger` | — | — |
| `%` | `ModInteger` | — | — |
| `==` | `EqualsInteger` / `EqualsByteString` / `EqualsString` / `EqualsData` | | |
| `<` | `LessThanInteger` | — | — |
| `<=` | `LessThanEqualsInteger` | — | — |
| `>` | Swap operands + `LessThanInteger` | — | — |
| `>=` | Swap operands + `LessThanEqualsInteger` | — | — |
| `&&` | `IfThenElse(left, right, false)` | — | — |
| `\|\|` | `IfThenElse(left, true, right)` | — | — |

**Unary operations:**
- `!x` → `IfThenElse(x, false, true)`
- `-x` → `SubtractInteger(0, x)`

### 8.3 Method Call Dispatch

`generateMethodCall()` implements a 5-level cascading dispatch:

**Level 1: Special cases**
- `BigInteger.valueOf(n)` → identity (pass through)

**Level 2: Stdlib static methods**
- Check if scope is a class name (e.g., `ListsLib`, `Builtins`, `Math`)
- Look up in `StdlibLookup` → returns PIR term
- Example: `Builtins.headList(xs)` → `App(Builtin(HeadList), xs)`

**Level 3: Instance methods via TypeMethodRegistry**
- Resolve scope expression type
- Dispatch to registered handler
- Example: `list.size()` → `PirHelpers.generateListLength(list)`

**Level 4: Record field access (zero-arg methods)**
- Check if scope variable has `RecordType` or `OptionalType(RecordType)`
- Check if field name is in current scope (from pattern destructuring)
- Otherwise generate field extraction: `HeadList(TailList^n(SndPair(UnConstrData(data))))`

**Level 5: Helper method / library method calls**
- Look up method name in SymbolTable
- For library classes, try qualified name `ClassName.methodName`
- Apply scope + args
- If not found → error with fuzzy suggestion

### 8.4 Record Construction and Field Access

**Construction:**
```java
new Point(10, 20)
```
→
```
DataConstr(0, RecordType("Point", [...]), [Const(10), Const(20)])
```

For sum type variants, the tag comes from the constructor's position in the sealed interface.

**Field access:**
```java
point.x()  // field 0
point.y()  // field 1
```
→
```
// Field 0 (x): HeadList after UnConstrData
Let("__fields", SndPair(UnConstrData(point)),
  wrapDecode(HeadList(__fields), IntegerType))

// Field 1 (y): TailList then HeadList
Let("__fields", SndPair(UnConstrData(point)),
  wrapDecode(HeadList(TailList(__fields)), IntegerType))
```

### 8.5 Control Flow

**If/else:**
```java
if (cond) { thenBody } else { elseBody }
```
→ `IfThenElse(cond, thenBody, elseBody)`

**Fallthrough optimization:** If the then-branch returns and there's no explicit else but following statements exist, the following statements become the else-branch:
```java
if (cond) { return x; }
return y;          // ← becomes the else branch
```
→ `IfThenElse(cond, x, y)`

**instanceof pattern:**
```java
if (shape instanceof Circle c) { ... }
```
→ Tag equality check: `EqualsInteger(FstPair(UnConstrData(shape)), circleTag)`
The pattern variable `c` and its fields are bound in the then-scope.

**Switch expressions:**
```java
switch (shape) {
    case Circle c -> c.radius();
    case Rect r -> r.w() * r.h();
}
```
→ `DataMatch` with branches ordered by tag, delegated to `PatternMatchDesugarer`

**Ternary:** `cond ? a : b` → `IfThenElse(cond, a, b)`

### 8.6 Statement Compilation

**Variable declaration:**
```java
int x = 5;
// rest of block...
```
→ `Let("x", Const(5), rest)`

Type inference: explicit type if declared, otherwise inferred from initializer expression type, then PIR term type. Uninitialized variables are rejected.

**Return/yield:**
```java
return expr;   // or: yield expr;
```
→ `generateExpression(expr)` — becomes the final term in the Let chain

**Expression statement:**
```java
someCall();
// rest...
```
→ `Let("_", someCall, rest)` — evaluate and discard

---

## 9. Phase 4: Loop Desugaring

Loops are transformed into recursive `LetRec` patterns by `LoopDesugarer`. The desugarer assigns unique names to each loop function (`loop__forEach__0`, `loop__while__1`, etc.) to support nesting.

### 9.1 For-Each Loops

**5 compilation paths** based on accumulator count and break usage:

**Path A: Single accumulator, no break**
```java
long sum = 0;
for (var item : items) {
    sum = sum + item;
}
```
→
```
LetRec([loop__forEach__0 = \xs \acc ->
    IfThenElse(NullList(xs), acc,
      Let(item, wrapDecode(HeadList(xs), elemType),
        loop__forEach__0(TailList(xs), acc + item)))
], loop__forEach__0(items, 0))
```

**Path B: Single accumulator, with break**
```java
long result = 0;
for (var item : items) {
    if (item > 100) break;
    result = result + item;
}
```
→ Uses a `bodyBuilder(continueFn, accVar)` function:
- `break` → return `accVar` (no recursion)
- Continue → `continueFn.apply(newAcc)` (recurse with `loop(TailList(xs), newAcc)`)

**Path C: Multi-accumulator (2+)**
```java
long sum = 0, count = 0;
for (var item : items) {
    sum = sum + item;
    count = count + 1;
}
```
→ Accumulators packed into a Data list tuple:
```
pack:   MkCons(IData(sum), MkCons(IData(count), MkNilData))
unpack: Let(sum, UnIData(HeadList(tuple)),
          Let(count, UnIData(HeadList(TailList(tuple))), body))
```

**Path D: No accumulator**
→ Uses unit accumulator, discards result.

**Path E: Multi-accumulator with break**
→ Combines tuple packing (Path C) with break-aware body generation (Path B).

### 9.2 While Loops

While loops follow the same accumulator detection patterns but use a condition check instead of list exhaustion:

```java
long n = x;
while (n > 0) {
    n = n - 1;
}
```
→
```
LetRec([loop__while__0 = \n ->
    IfThenElse(n > 0,
      loop__while__0(n - 1),
      n)
], loop__while__0(x))
```

**Accumulator type refinement** (`AccumulatorTypeAnalyzer.refineAccumulatorTypes()`): The compiler distinguishes `ListType` vs `MapType` accumulators by looking for evidence (this logic was extracted from PirGenerator into `AccumulatorTypeAnalyzer` as part of ADR-018):

| Evidence | Inferred Type |
|----------|---------------|
| `mkNilPairData()` assignment | MapType |
| `tailList()` on `unMapData()` result | MapType |
| `mkCons()` with `mkPairData()` items | MapType |
| `fstPair()`/`sndPair()` on `headList(cursor)` | MapType |
| `mkNilData()`, `mkCons()`, `tailList()` (without pair evidence) | ListType |

**Nested loop unique naming:**
Each loop gets a unique counter-based name. A `loopCounter` field in `LoopDesugarer` increments per loop:
```
Outer: loop__forEach__0
Inner: loop__forEach__1
```

**`bodyUsesPairOpsOnCursor` fix:** The compiler now checks pair operations specifically on the cursor's `headList` result variable, not anywhere in the loop body. This prevents incorrect MapType inference when pair operations appear on unrelated variables.

---

## 10. Phase 5: Pattern Matching

`PatternMatchDesugarer` transforms Java pattern matching into PIR `DataMatch` terms.

### Switch on Sealed Interfaces

```java
switch (credential) {
    case PubKeyCredential pkc -> pkc.hash();
    case ScriptCredential sc -> sc.hash();
}
```

1. For each constructor in the `SumType`, find the matching switch case
2. Extract binding names from pattern variables
3. Order branches by constructor tag (tag 0, tag 1, ...)
4. Fill missing branches with `Error`
5. Build `DataMatch(scrutinee, orderedBranches)`

### UPLC Lowering of DataMatch

`UplcGenerator` lowers `DataMatch` to:

```
Let(pair, UnConstrData(scrutinee),
  Let(tag, FstPair(pair),
    Let(fields, SndPair(pair),
      IfThenElse(tag == 0,
        Let(binding0, decode(HeadList(fields)),
          Let(__rest, TailList(fields),
            Let(binding1, decode(HeadList(__rest)),
              body0))),
        IfThenElse(tag == 1,
          ... body1 ...,
          Error)))))
```

### Instanceof Chains

```java
if (cred instanceof PubKeyCredential pkc) {
    return pkc.hash();
} else if (cred instanceof ScriptCredential sc) {
    return sc.hash();
}
```

Converted to `DataMatch` with the same structure. Missing variants are filled with the else-branch or `Error`.

---

## 11. Phase 6: Validator Wrapping

`ValidatorWrapper` (in `codegen/`) wraps the compiled entrypoint with ScriptContext decoding and bool-to-unit/error conversion.

### Script Purposes and Annotations

| Purpose | Annotations | Param count |
|---------|-------------|-------------|
| `SPENDING` | `@Validator`, `@SpendingValidator` | 2 or 3 |
| `MINTING` | `@MintingPolicy`, `@MintingValidator` | 2 |
| `WITHDRAW` | `@WithdrawValidator` | 2 |
| `CERTIFYING` | `@CertifyingValidator` | 2 |
| `VOTING` | `@VotingValidator` | 2 |
| `PROPOSING` | `@ProposingValidator` | 2 |

### 2-Param Wrapper (Non-spending or spending without datum)

```
\scriptContextData ->
  let ctxFields = SndPair(UnConstrData(scriptContextData))
  let redeemer = HeadList(TailList(ctxFields))       // field 1
  let result = validate(redeemer, scriptContextData)
  in IfThenElse(result, Unit, Error)
```

### 3-Param Spending Wrapper (with datum)

Extracts datum from `ScriptInfo.SpendingScript`:

```
\scriptContextData ->
  let ctxFields = SndPair(UnConstrData(scriptContextData))
  let redeemer = HeadList(TailList(ctxFields))              // field 1
  let scriptInfo = HeadList(TailList(TailList(ctxFields)))  // field 2
  let siFields = SndPair(UnConstrData(scriptInfo))
  let optDatum = HeadList(TailList(siFields))               // SpendingScript field 1
  let datum = HeadList(SndPair(UnConstrData(optDatum)))     // unwrap Some
  let result = validate(datum, redeemer, scriptContextData)
  in IfThenElse(result, Unit, Error)
```

### @Param Lambda Wrapping

Each `@Param` field gets an outer lambda that accepts raw Data and decodes it:

```
\param1__raw -> Let(param1, UnIData(param1__raw),
  \param2__raw -> Let(param2, UnBData(param2__raw),
    <validator body>))
```

### Bool → Unit/Error

All validators return boolean in Java. The wrapper converts:
- `true` → `Unit` (script succeeds)
- `false` → `Error` (script fails, transaction rejected)

---

## 12. Phase 7: UPLC Generation and Optimization

### 12.1 UplcGenerator

`UplcGenerator` lowers PIR to UPLC by eliminating named variables, Let bindings, LetRec, and typed constructs.

**Let → Application:**
```
Let(x, val, body)  →  Apply(Lam("x", body'), val')
```

**LetRec → Z-combinator:**

The Z-combinator (strict fixed-point combinator) enables self-recursion:

```
fix = \f -> (\x -> f (\v -> x x v)) (\x -> f (\v -> x x v))
```

For `LetRec([name = body], expr)`:
1. Create the Z-combinator term
2. Create recursive lambda: `Lam(name, body')` where body references `name`
3. Apply: `Apply(Lam(name, expr'), Apply(fix, Lam(name, body')))`

**Multi-binding LetRec (Bekic's theorem):**

`UplcGenerator` handles multi-binding `LetRec` via dependency analysis:

1. Build a dependency graph between bindings
2. Topological sort: non-mutual bindings are nested as single `LetRec`/`Let` in topo order
3. For 2-binding mutual recursion, apply Bekic's theorem to decompose into nested single-binding `LetRec`s
4. Limitation: mutual recursion with >2 bindings is not yet supported

**IfThenElse → Delay/Force:**
```
IfThenElse(c, t, e)  →  Force(Apply(Apply(Apply(Force(Builtin(IfThenElse)), c'),
                                          Delay(t')),
                                    Delay(e')))
```

Both branches are `Delay`ed to prevent premature evaluation (UPLC is strict). The outer `Force` evaluates the selected branch.

**DataConstr → ConstrData:**
```
DataConstr(tag, type, [f1, f2])
→ ConstrData(tag, MkCons(encode(f1), MkCons(encode(f2), MkNilData)))
```

**DataMatch → UnConstrData + tag dispatch:** (See Section 10)

**De Bruijn computation:**
Variables are converted from named to De Bruijn indexed. A scope stack tracks active lambda bindings. Index 1 = innermost lambda:
```java
int deBruijnIndex(String name) {
    int index = 1;
    for (var n : scope) {
        if (n.equals(name)) return index;
        index++;
    }
    throw new CompilerException("Unbound variable: " + name);
}
```

**Force count table:**

Polymorphic builtins need `Force` wrappers to instantiate type variables:

| Forces | Builtins |
|--------|----------|
| 2 (∀ a b) | `FstPair`, `SndPair`, `ChooseList` |
| 1 (∀ a) | `IfThenElse`, `ChooseUnit`, `Trace`, `ChooseData`, `SerialiseData`, `MkCons`, `HeadList`, `TailList`, `NullList` |
| 0 (mono) | All arithmetic, comparisons, Data encode/decode, crypto, string/bytestring ops |

### 12.2 UplcOptimizer

The optimizer runs 6 passes in a fixpoint loop (max 20 iterations, stops when term is unchanged):

**Pass 1: Force/Delay cancellation**
```
Force(Delay(t)) → t
```

**Pass 2: Constant folding**
```
Apply(Apply(Builtin(AddInteger), Const(3)), Const(4)) → Const(7)
```
Supported: `AddInteger`, `SubtractInteger`, `MultiplyInteger`, `EqualsInteger`, `LessThanInteger`, `LessThanEqualsInteger`, `EqualsByteString`, `AppendByteString`.

**Pass 3: Dead code elimination**
```
Apply(Lam(x, body), val) → body  (when x unused in body and val has no side effects)
```
Side-effect safety: `Trace` is considered side-effecting. Dead code with trace calls is preserved.

**Pass 4: Beta reduction**
```
Apply(Lam(x, body), arg) → substitute(body, arg)  (when x used exactly once and arg is simple)
```
"Simple" = `Const`, `Var`, `Builtin`, or `Force` of these. Complex args (applications) are not inlined to avoid duplicating computation.

**Pass 5: Eta reduction**
```
Lam(x, Apply(f, Var(1))) → f  (when x not free in f and f is a value)
```
The "f is a value" check prevents breaking the Z-combinator. Values = `Var`, `Const`, `Builtin`, `Lam`, `Delay`. Non-values = `Apply` (unevaluated computation).

**Pass 6: Constr/Case reduction**
```
Case(Constr(tag, [a, b]), [br0, br1, br2]) → Apply(Apply(br_tag, a), b)
```

---

## 13. Phase 8: Library Compilation

### Multi-Pass Retry Strategy

Library methods may depend on each other across compilation units. Library compilation was extracted into `LibraryCompiler.java` (138 lines) as part of ADR-018. `JulcCompiler` delegates via `new LibraryCompiler(options).compile(...)`. The strategy is multi-pass:

```
1. Create progressive LibraryMethodRegistry (starts empty)
2. Create CompositeStdlibLookup(stdlibRegistry, libraryMethodRegistry)
3. For each pass:
   a. For each library CU not yet compiled:
      - Try to compile all static methods
      - If successful, register methods in LibraryMethodRegistry
      - If fails (unresolved reference), skip for this pass
   b. If no progress this pass (no new CUs compiled), stop
4. Report errors for any remaining uncompiled CUs
```

This ensures that if library A depends on library B, B will be compiled first (possibly in an earlier pass), and A will succeed in a later pass.

### LibraryMethodRegistry

Each compiled library method is stored as a `LibraryMethod(className, methodName, type, body)`. When looked up during validator compilation, it returns a `Var` reference. The actual PIR body is emitted as a `Let` (or `LetRec` for self-recursive methods) binding that wraps the validator term.

**Type-aware coercion:** When the caller's argument type doesn't match the library method's parameter type, `LibraryMethodRegistry.lookup(className, method, args, argTypes)` inserts decode wrappers. For example, if the caller has `DataType` but the library expects `IntegerType`, it wraps with `UnIData`.

### Topological Sorting of Library Methods

Library methods are topologically sorted before wrapping as Let bindings:
1. Build a dependency graph: method A depends on method B if A's PIR body contains `Var("B.methodName")`
2. Kahn's algorithm sorts dependencies first
3. Self-recursive methods (detected via `containsVarRef`) are wrapped in `LetRec` instead of `Let`
4. The outermost binding has no dependencies; the innermost is the validator

### LibrarySourceResolver

`LibrarySourceResolver` handles automatic discovery of library sources:

1. **`scanClasspathSources(ClassLoader)`** — Scans `META-INF/plutus-sources/index.txt` for library entries. Falls back to filesystem directory scan in development.
2. **`resolve(String source, Map<String,String> availableLibraries)`** — BFS traversal from root source's imports, transitively resolving all referenced libraries.

### CompositeStdlibLookup

Chains multiple `StdlibLookup` implementations. First match wins:

```java
new CompositeStdlibLookup(stdlibRegistry, libraryMethodRegistry)
```

When the compiler encounters `SomeClass.method(args)`:
1. Try `stdlibRegistry` first (builtins, HOFs, math)
2. Try `libraryMethodRegistry` (compiled @OnchainLibrary methods)
3. If neither matches, fall through to helper method / error handling

---

# Part III: Deep Dives

## 14. The Symbol Table

`SymbolTable` (`resolve/SymbolTable.java`) manages lexical scoping during PIR generation.

### Data Structure

```java
Deque<Map<String, PirType>> scopes;   // Variable scope stack
Map<String, MethodInfo> methods;       // Helper method registry
```

### Scope Lifecycle

| Event | Operation |
|-------|-----------|
| Constructor | Push global scope |
| Method entry | `pushScope()` → define parameters |
| Block statement | `pushScope()` → compile statements → `popScope()` |
| Switch case | `pushScope()` → define pattern variable + fields → `popScope()` |
| Lambda expression | `pushScope()` → define parameters → `popScope()` |
| For-each loop | `pushScope()` → define item + accumulators → `popScope()` |

The global scope is never popped.

### lookup vs lookupCurrentScope

- **`lookup(name)`** — Searches from innermost scope outward. Returns `Optional<PirType>`.
- **`lookupCurrentScope(name)`** — Searches only the innermost scope.

This distinction is critical for the **field name collision fix**: When accessing `txOut.datum()`, if there's an outer parameter named `datum`, `lookup("datum")` would find the parameter instead of the record field. `lookupCurrentScope` ensures only pattern-destructured fields in the current scope are matched.

### Method Registration

```java
defineMethod(String name, PirType type, PirTerm body)
```

Registers a helper method in the `methods` map and also adds it to the global scope (methods are first-class values in PIR). `allMethods()` returns all registered methods for final `Let` wrapping.

---

## 15. The Type Method Registry

`TypeMethodRegistry` (`pir/TypeMethodRegistry.java`) maps `(PirType, methodName)` pairs to instance method handlers.

### InstanceMethodHandler Pattern

```java
@FunctionalInterface
interface InstanceMethodHandler {
    PirTerm handle(PirTerm scope, List<PirTerm> args,
                   PirType scopeType, List<PirType> argTypes);
}
```

Each handler receives:
- `scope` — The compiled receiver (e.g., the list, the integer)
- `args` — Compiled argument terms
- `scopeType` — The receiver's PIR type
- `argTypes` — Argument PIR types

### ReturnTypeResolver

```java
@FunctionalInterface
interface ReturnTypeResolver {
    PirType resolve(PirType scopeType);
}
```

Used for type inference in chained method calls. For example, `list.tail()` returns the same `ListType` as the receiver.

### Registered Instance Methods

**IntegerType (15 methods):**

| Method | Implementation |
|--------|---------------|
| `abs()` | `if x < 0 then 0 - x else x` |
| `negate()` | `0 - x` |
| `max(other)` | `if a < b then b else a` |
| `min(other)` | `if a <= b then a else b` |
| `equals(other)` | `EqualsInteger` (with Data→Integer coercion) |
| `add(other)` | `AddInteger` |
| `subtract(other)` | `SubtractInteger` |
| `multiply(other)` | `MultiplyInteger` |
| `divide(other)` | `DivideInteger` |
| `remainder(other)` | `RemainderInteger` |
| `mod(other)` | `ModInteger` |
| `signum()` | `if x < 0 then -1 else if x == 0 then 0 else 1` |
| `compareTo(other)` | `if a < b then -1 else if a == b then 0 else 1` |
| `intValue()` | Identity |
| `longValue()` | Identity |

**ByteStringType (2 methods):**

| Method | Implementation |
|--------|---------------|
| `length()` | `LengthOfByteString` |
| `equals(other)` | `EqualsByteString` (with Data→ByteString coercion) |

**StringType (2 methods):**

| Method | Implementation |
|--------|---------------|
| `length()` | `LengthOfByteString(EncodeUtf8(s))` |
| `equals(other)` | `EqualsString` |

**DataType / RecordType / SumType (1 method each):**

| Method | Implementation |
|--------|---------------|
| `equals(other)` | `EqualsData` |

**ListType (16 methods):**

| Method | Implementation | Return Type |
|--------|---------------|-------------|
| `size()` | Foldl-based length | IntegerType |
| `isEmpty()` | `NullList` | BoolType |
| `head()` | `wrapDecode(HeadList(list), elemType)` | elemType |
| `tail()` | `TailList(list)` | same ListType |
| `get(index)` | LetRec nth traversal | elemType |
| `contains(target)` | LetRec recursive search | BoolType |
| `prepend(elem)` | `MkCons(wrapEncode(elem), list)` | same ListType |
| `reverse()` | Foldl-based reversal | same ListType |
| `concat(other)` | Recursive append | same ListType |
| `take(n)` | LetRec traversal | same ListType |
| `drop(n)` | LetRec traversal | same ListType |
| `map(f)` | Delegates to StdlibRegistry HOF builder via lambda inference | ListType(DataType) |
| `filter(pred)` | Delegates to StdlibRegistry HOF builder via lambda inference | same ListType |
| `any(pred)` | Delegates to StdlibRegistry HOF builder via lambda inference | BoolType |
| `all(pred)` | Delegates to StdlibRegistry HOF builder via lambda inference | BoolType |
| `find(pred)` | Delegates to StdlibRegistry HOF builder via lambda inference | elemType |

**OptionalType (3 methods):**

| Method | Implementation | Return Type |
|--------|---------------|-------------|
| `isPresent()` | `FstPair(UnConstrData(x)) == 0` (Some tag) | BoolType |
| `isEmpty()` | `FstPair(UnConstrData(x)) == 1` (None tag) | BoolType |
| `get()` | `wrapDecode(HeadList(SndPair(UnConstrData(x))), elemType)` | elemType |

**MapType (8 methods):**

| Method | Implementation | Return Type |
|--------|---------------|-------------|
| `get(key)` | LetRec lookup, returns Optional | OptionalType(valueType) |
| `containsKey(key)` | LetRec search | BoolType |
| `size()` | `generateListLength(UnMapData(map))` | IntegerType |
| `isEmpty()` | `NullList(map)` | BoolType |
| `keys()` | Foldl-based key extraction | ListType(keyType) |
| `values()` | Foldl-based value extraction | ListType(valueType) |
| `insert(k, v)` | `MkCons(MkPairData(k, v), map)` | same MapType |
| `delete(k)` | LetRec filtering | same MapType |

---

## 16. The Stdlib Registry

`StdlibRegistry` (`julc-stdlib/.../StdlibRegistry.java`) provides PIR term builders for ~65 standard library methods.

### StdlibLookup Interface

```java
@FunctionalInterface
public interface StdlibLookup {
    Optional<PirTerm> lookup(String className, String methodName, List<PirTerm> args);

    default Optional<PirTerm> lookup(String className, String methodName,
                                      List<PirTerm> args, List<PirType> argTypes) {
        return lookup(className, methodName, args);
    }
}
```

### Registered Methods

**Builtins (raw UPLC operations):**
- List: `headList`, `tailList`, `nullList`, `mkCons`, `mkNilData`
- Pair: `fstPair`, `sndPair`, `mkPairData`, `mkNilPairData`
- Data encode: `constrData`, `iData`, `bData`, `listData`, `mapData`
- Data decode: `unConstrData`, `unIData`, `unBData`, `unListData`, `unMapData`
- Comparison: `equalsData`
- Error/trace: `error`, `trace`
- ByteString: `indexByteString`, `consByteString`, `sliceByteString`, `lengthOfByteString`, `appendByteString`, `equalsByteString`, `lessThanByteString`, `lessThanEqualsByteString`, `integerToByteString`, `byteStringToInteger`, `encodeUtf8`, `decodeUtf8`, `serialiseData`, `replicateByte`, `emptyByteString`
- Crypto: `sha2_256`, `blake2b_256`, `verifyEd25519Signature`, `sha3_256`, `blake2b_224`, `keccak_256`, `verifyEcdsaSecp256k1Signature`, `verifySchnorrSecp256k1Signature`, `ripemd_160`
- Bitwise: `andByteString`, `orByteString`, `xorByteString`, `complementByteString`, `readBit`, `writeBits`, `shiftByteString`, `rotateByteString`, `countSetBits`, `findFirstSetBit`
- Data decomposition: `constrTag`, `constrFields`
- Math: `expModInteger`

**ListsLib HOF methods (require lambda/LetRec — cannot be compiled from Java source):**

| Method | Signature | Implementation |
|--------|-----------|----------------|
| `any(predicate, list)` | `(T→Bool, List<T>) → Bool` | Left fold, short-circuit |
| `all(predicate, list)` | `(T→Bool, List<T>) → Bool` | Left fold, short-circuit |
| `find(predicate, list)` | `(T→Bool, List<T>) → Optional<T>` | LetRec recursion |
| `foldl(f, init, list)` | `((A,T)→A, A, List<T>) → A` | LetRec recursion |
| `map(f, list)` | `(T→U, List<T>) → List<U>` | `reverse(foldl(...))` |
| `filter(pred, list)` | `(T→Bool, List<T>) → List<T>` | `reverse(foldl(...))` |
| `zip(a, b)` | `(List<T>, List<U>) → List<Pair<T,U>>` | LetRec, stops at shorter list |

**Math delegates (allows `Math.abs(x)` syntax):**

| Method | Implementation |
|--------|---------------|
| `Math.abs(x)` | `if x < 0 then 0 - x else x` |
| `Math.max(a, b)` | `if a < b then b else a` |
| `Math.min(a, b)` | `if a <= b then a else b` |

### Tracing a Stdlib Call End-to-End

Example: `ListsLib.foldl(fn, init, list)` in user Java code:

1. **PirGenerator.generateMethodCall()** detects scope = `ListsLib` (class name)
2. **Level 2 dispatch:** Calls `stdlibLookup.lookup("ListsLib", "foldl", compiledArgs)`
3. **StdlibRegistry** finds the registered `PirTermBuilder` for `ListsLib.foldl`
4. Builder generates a LetRec PIR pattern:
   ```
   LetRec([go = \acc \lst ->
       IfThenElse(NullList(lst), acc,
         go(App(App(fn, acc), HeadList(lst)), TailList(lst)))
   ], App(App(go, init), list))
   ```
5. **UplcGenerator** lowers LetRec using Z-combinator
6. **UplcOptimizer** applies Force/Delay cancellation, potentially beta-reduces

### HOF Compilation Pipeline (Instance Methods)

Instance HOF calls like `list.map(x -> x + 1)` are compiled via `PirHofBuilders.java`:

1. **TypeMethodRegistry** detects HOF method (`map`, `filter`, `any`, `all`, `find`) on a `ListType` variable
2. **Lambda inference:** Lambda parameter type is auto-inferred from the list's element type
3. **PirHofBuilders** generates the PIR pattern (same builders as `StdlibRegistry` static HOFs)
4. **hofUnwrappedVars:** For `ByteStringType` elements (e.g., `JulcList<PubKeyHash>`), the lambda param is tracked as pre-unwrapped to prevent double `UnBData` in `.hash()` calls
5. Result: `map` wraps results to Data; `filter`/`any`/`all`/`find` preserve element types

`foldl` is only available as `ListsLib.foldl(...)` (static), not as an instance method, due to the complexity of 2-parameter lambdas with an init value.

---

## 17. Error Reporting

### CompilerDiagnostic

```java
public record CompilerDiagnostic(
    Level level,        // ERROR, WARNING, INFO
    String message,
    String fileName,
    int line,
    int column,
    String suggestion   // optional
)
```

### Error Collection Strategies

**Non-fatal errors** (recoverable — compilation continues):
```java
// In PirGenerator:
private PirTerm collectError(String message, String suggestion, Node node) {
    collectedErrors.add(new CompilerDiagnostic(
        Level.ERROR, message, fileName, line, column, suggestion));
    return new PirTerm.Error(new PirType.DataType());
}
```
Returns `PirTerm.Error` as a placeholder. Multiple errors can be collected and reported to the user in a single compilation run.

**Fatal errors** (structural — compilation aborts):
```java
private CompilerException enrichedError(String message, String suggestion, Node node) {
    throw new CompilerException(message + " at " + fileName + ":" + line + ":" + column
        + (suggestion != null ? " (suggestion: " + suggestion + ")" : ""));
}
```

### Suggestion Generation

**Fuzzy method name matching:** When a method is not found, the compiler computes Levenshtein edit distance against all known methods and suggests the closest match:
```
ERROR MyValidator.java:15:8 - Unknown method 'filterr'. Did you mean 'filter'?
```

**Construct-specific suggestions:** Each rejected construct in `SubsetValidator` has a tailored suggestion (e.g., "Use `Optional<T>` to represent absence of a value" for `null`).

---

# Part IV: How-To Recipes

## 18. How to Add a New Instance Method

**Goal:** Add `list.last()` returning the last element of a list.

**Step 1: Register in TypeMethodRegistry** (`pir/TypeMethodRegistry.java`):

Find the `ListType` section in `defaultRegistry()` and add:

```java
reg.register("ListType", "last", (scope, args, scopeType, argTypes) -> {
    var lt = (PirType.ListType) scopeType;
    // LetRec: go(lst) = if NullList(TailList(lst)) then HeadList(lst) else go(TailList(lst))
    var goName = "go__last";
    var lstParam = new PirTerm.Var("__lst", lt);
    var tail = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), lstParam);
    var head = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), lstParam);
    var nullTail = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), tail);
    var recurse = new PirTerm.App(new PirTerm.Var(goName, new PirType.FunType(lt, lt.elemType())), tail);
    var body = new PirTerm.IfThenElse(nullTail, PirHelpers.wrapDecode(head, lt.elemType()), recurse);
    var goLam = new PirTerm.Lam("__lst", lt, body);
    return new PirTerm.LetRec(
        List.of(new PirTerm.Binding(goName, goLam)),
        new PirTerm.App(new PirTerm.Var(goName, new PirType.FunType(lt, lt.elemType())), scope));
}, scopeType -> ((PirType.ListType) scopeType).elemType());
```

**Step 2: Add off-chain stub** (`julc-onchain-api/.../Builtins.java` or the relevant stub class):

```java
public static <T> T last(List<T> list) {
    // Off-chain stub for IDE support
    return list.get(list.size() - 1);
}
```

**Step 3: Write a test** in `TypeMethodRegistryTest.java` or `TypeMethodsTest.java`:

```java
@Test
void testListLast() {
    var source = """
        @Validator
        public class Test {
            @Entrypoint
            public static boolean validate(PlutusData redeemer, PlutusData ctx) {
                var items = Builtins.mkNilData(Builtins.unit());
                items = Builtins.mkCons(Builtins.iData(10), items);
                items = Builtins.mkCons(Builtins.iData(20), items);
                items = Builtins.mkCons(Builtins.iData(30), items);
                return Builtins.unIData(items.last()) == 10;
            }
        }
        """;
    compileAndAssertTrue(source);
}
```

---

## 19. How to Add a New Builtin Function

**Goal:** Register a new Plutus builtin (e.g., from a Plutus version upgrade).

**Step 1: Add to DefaultFun enum** (`julc-core/.../DefaultFun.java`):

```java
NewBuiltin(102),  // CIP-XXX: description
```

The FLAT code must match the Plutus specification exactly.

**Step 2: Set force count** (`uplc/UplcGenerator.java`):

In the `forceCount()` method, add:
```java
case NewBuiltin -> 0;  // or 1 or 2 depending on polymorphism
```

**Step 3: Register in StdlibRegistry** (`julc-stdlib/.../StdlibRegistry.java`):

In `registerBuiltins()`:
```java
reg.register("Builtins", "newBuiltin", args -> {
    requireArgs("newBuiltin", args, 2);
    return builtinApp2(DefaultFun.NewBuiltin, args.get(0), args.get(1));
});
```

**Step 4: Add off-chain stub** (`julc-onchain-api/.../Builtins.java`):

```java
public static PlutusData newBuiltin(PlutusData a, PlutusData b) {
    throw new UnsupportedOperationException("On-chain only");
}
```

**Step 5: Write a compile+eval test** verifying the builtin works end-to-end.

---

## 20. How to Add a New Java Type Mapping

**Goal:** Map a new Java type (e.g., `Rational`) to a PIR type.

**Step 1: Define the PirType variant** (if needed) in `pir/PirType.java`:

```java
record RationalType() implements PirType {}
```

Or use an existing type — `Rational` is currently mapped to `DataType` (opaque).

**Step 2: Register in TypeResolver** (`resolve/TypeResolver.java`):

In the `resolve()` method, add a case:
```java
case "Rational" -> new PirType.DataType();  // or your new type
```

**Step 3: Add wrapDecode/wrapEncode** (`pir/PirHelpers.java`):

```java
case RationalType() -> /* your decode logic */;
```

**Step 4: Handle in UplcGenerator** (`uplc/UplcGenerator.java`):

If the new type needs special DataConstr encoding or DataMatch decoding, add cases in `wrapDataEncode()` and the DataMatch lowering.

**Step 5: Write tests** covering construction, field access, and passing the type across function boundaries.

---

## 21. How to Add a New Stdlib Library

**Goal:** Create a new on-chain library (e.g., `SortLib`).

**Step 1: Write the on-chain Java source** (`julc-stdlib/src/main/java/.../onchain/SortLib.java`):

```java
@OnchainLibrary
public class SortLib {
    public static List<PlutusData> sort(List<PlutusData> items) {
        // Implementation using supported Java subset
        // Use while loops, for-each, Builtins.* calls
    }
}
```

**Step 2: Write the off-chain stub** (`julc-onchain-api/src/main/java/.../SortLib.java`):

```java
@OnchainLibrary
public class SortLib {
    public static List<PlutusData> sort(List<PlutusData> items) {
        throw new UnsupportedOperationException("On-chain only");
    }
}
```

**Step 3: If the library needs HOF methods** (lambdas as parameters), register PIR builders in `StdlibRegistry`:

```java
reg.register("SortLib", "sortBy", args -> {
    // Build PIR term with LetRec recursion
});
```

**Step 4: Write tests** in `StdlibCompileEvalTest.java` or `StdlibIntegrationTest.java`:

```java
@Test
void testSort() {
    var source = """
        @Validator
        public class Test {
            @Entrypoint
            public static boolean validate(PlutusData r, PlutusData ctx) {
                var items = /* build list */;
                var sorted = SortLib.sort(items);
                return /* verify sorted order */;
            }
        }
        """;
    compileAndAssertTrue(source);
}
```

**Step 5: Ensure the library source is discoverable** by adding it to `META-INF/plutus-sources/index.txt` in the stdlib jar.

---

## 22. How to Add a New Ledger Type

**Goal:** Register a new Cardano ledger type (e.g., from a hard fork).

**Step 1: Create the Java record** (`julc-ledger-api/src/main/java/.../`):

```java
public record NewLedgerType(BigInteger field1, byte[] field2) {}
```

**Step 2: Register in LedgerTypeRegistry** (`resolve/LedgerTypeRegistry.java`):

Add to the appropriate tier:
```java
typeResolver.registerLedgerRecord("NewLedgerType", List.of(
    new PirType.Field("field1", new PirType.IntegerType()),
    new PirType.Field("field2", new PirType.ByteStringType())
));
```

**Step 3: Create off-chain stub** (`julc-onchain-api/src/main/java/.../`):

```java
public record NewLedgerType(BigInteger field1, byte[] field2) {}
```

**Step 4: Write tests** verifying field access compiles and evaluates correctly:

```java
@Test
void testNewLedgerTypeAccess() {
    // Build ScriptContext with the new type, verify field extraction works
}
```

---

## 23. How to Add a New Optimization Pass

**Goal:** Add a new UPLC optimization pass.

**Step 1: Add the pass method** in `UplcOptimizer` (`uplc/UplcOptimizer.java`):

```java
private Term myNewOptimization(Term term) {
    return switch (term) {
        case Term.Apply(var f, var a) -> {
            var optF = myNewOptimization(f);
            var optA = myNewOptimization(a);
            // Your pattern matching logic here
            yield new Term.Apply(optF, optA);
        }
        // ... handle all Term variants recursively
        default -> term;
    };
}
```

**Step 2: Register in the pass list.** In `runAllPasses()`, add your pass in the appropriate position. Consider ordering:

- Passes that create opportunities for other passes should run earlier
- Passes that clean up artifacts should run later
- All passes run in a fixpoint loop, so cascading effects are handled

**Step 3: Preserve De Bruijn indices.** If your pass removes or adds lambda bindings, use `shiftDown()`/`shiftUp()` to adjust variable indices.

**Step 4: Check side-effect safety.** If your pass removes terms, ensure dropped terms don't contain `Trace` (use `hasSideEffect()`).

**Step 5: Write tests** in `UplcOptimizerTest.java`:

```java
@Test
void testMyNewOptimization() {
    var input = /* construct UPLC term matching the pattern */;
    var expected = /* expected optimized term */;
    var result = optimizer.optimize(input);
    assertEquals(expected, result);
}
```

---

## 24. How to Trace Through a Compilation

**Goal:** Understand what happens when a simple validator is compiled.

### Source

```java
@Validator
public class SimpleValidator {
    @Entrypoint
    public static boolean validate(PlutusData redeemer, PlutusData scriptContext) {
        int x = 5;
        int y = x + 3;
        return y == 8;
    }
}
```

### Phase 1: Parse

JavaParser produces an AST with:
- `ClassOrInterfaceDeclaration` "SimpleValidator" with `@Validator`
- `MethodDeclaration` "validate" with `@Entrypoint`
- Two parameters: `redeemer: PlutusData`, `scriptContext: PlutusData`
- Body: three statements (two var decls, one return)

### Phase 2: Validate

SubsetValidator walks the AST. No rejected constructs found.

### Phase 5: Script Purpose

`@Validator` → `SPENDING`. Two parameters → 2-param wrapper.

### Phase 6: Type Registration

No user-defined records or sealed interfaces. LedgerTypeRegistry pre-registers standard types.

### Phase 13-15: PIR Generation

Symbol table has: `redeemer: DataType`, `scriptContext: DataType`

Statement 1: `int x = 5;`
```
Let("x", Const(Integer(5)),
```

Statement 2: `int y = x + 3;`
```
  Let("y", App(App(Builtin(AddInteger), Var("x", IntegerType)), Const(Integer(3))),
```

Statement 3: `return y == 8;`
```
    App(App(Builtin(EqualsInteger), Var("y", IntegerType)), Const(Integer(8)))))
```

Wrapped in entrypoint lambdas:
```
Lam("redeemer", DataType,
  Lam("scriptContext", DataType,
    Let("x", Const(5),
      Let("y", App(App(Builtin(AddInteger), Var("x")), Const(3)),
        App(App(Builtin(EqualsInteger), Var("y")), Const(8))))))
```

### Phase 19: Validator Wrapping

2-param spending wrapper adds ScriptContext decoding and bool→unit/error:

```
Lam("__scriptContextData", DataType,
  Let("__ctxFields", SndPair(UnConstrData(Var("__scriptContextData"))),
    Let("__redeemer", HeadList(TailList(Var("__ctxFields"))),
      Let("__result",
        App(App(Var("validate"), Var("__redeemer")), Var("__scriptContextData")),
        IfThenElse(Var("__result"), Const(Unit), Error)))))
```

### Phase 21: UPLC Generation

All Let bindings become `Apply(Lam(...), ...)`. Variables become De Bruijn indices:

```
Lam("__scd",
  Apply(Lam("__ctxFields",
    Apply(Lam("__red",
      Apply(Lam("__result",
        Force(Apply(Apply(Apply(Force(Builtin(IfThenElse)), Var(1)),
                          Delay(Const(Unit))),
                    Delay(Error)))),
        Apply(Apply(
          Lam("redeemer", Lam("scriptContext",
            Apply(Lam("x",
              Apply(Lam("y",
                Apply(Apply(Builtin(EqualsInteger), Var(1)), Const(8))),
                Apply(Apply(Builtin(AddInteger), Var(1)), Const(3)))),
              Const(5)))),
          Var(1)),         // __red
          Var(3)))),       // __scd
      Apply(Apply(Force(Force(Builtin(HeadList))),  // ... extraction chain
```

### Phase 22: Optimization

The optimizer applies fixpoint passes:
- **Constant folding:** `AddInteger(5, 3)` → `8`
- **Constant folding:** `EqualsInteger(8, 8)` → `True`
- **Beta reduction:** inline single-use variables
- **Dead code elimination:** remove unused let bindings
- **Force/Delay cancellation:** `Force(Delay(True))` → `True`

After optimization, the validator becomes equivalent to: `\ctx -> Unit` (always succeeds).

---

# Part V: Troubleshooting and Reference

## 25. Known Compiler Limitations

### No Return Inside Multi-Accumulator While Body

**Root cause:** The multi-accumulator while loop compilation packs accumulators into a tuple and generates a single LetRec. A `return` inside the body would need to escape the entire loop + tuple unpacking, which the current LetRec pattern doesn't support.

**Workaround:** Use `break` to exit loops early, or restructure to avoid early return.

### Cross-Method Type Inference

**Root cause:** When method A calls helper method B with a `long` parameter, the compiler may generate `EqualsData` instead of `EqualsInteger` because the parameter type is inferred as `DataType` at the boundary.

**Workaround:** Use Data-level equality (`EqualsData` on wrapped values) or ensure helper methods use the same primitive types as callers.

### @Param BytesData Bug

**Root cause:** `@Param` values are always raw Data at runtime, but declaring `@Param PlutusData.BytesData` tells the compiler the value is already a ByteString. This causes double-wrapping with `bData()` and incorrect cross-library calls.

**Workaround:** Always use `@Param PlutusData` for `@Param` fields. Decode manually with `Builtins.unBData()` if needed.

### Post-While-Loop Variable Access

**Root cause:** Variables defined before a multi-accumulator while loop may be corrupted after the loop. The LetRec transformation overwrites variable bindings in the scope.

**Workaround:** Extract post-loop logic into a separate helper method that receives the needed values as parameters.

### Cross-Library BytesData Param Bug

**Root cause:** When calling a stdlib library method that takes `BytesData`-typed params, if the caller also has a `BytesData` variable, the compiler sees matching types and skips conversion. But compiled libraries expect Data args at the UPLC boundary.

**Workaround:** Pass `PlutusData` (not `BytesData`) args to cross-library calls. For stdlib methods taking `BytesData` params, use a same-project local wrapper or ensure call-site variables are typed as `PlutusData`.

---

## 26. Debugging Techniques

### Reading Compiler Diagnostics

Diagnostics follow the format:
```
ERROR fileName.java:15:8 - Message text (suggestion: hint text)
```

The `CompileResult` contains both the compiled `Program` (if successful) and a list of `CompilerDiagnostic` entries (errors + warnings).

### Common Error Patterns

| Error | Likely Cause | Fix |
|-------|-------------|-----|
| "Unknown method 'X'" | Method not in stdlib, TypeMethodRegistry, or helper methods | Check spelling, add import, or register method |
| "Unknown type: 'X'" | Type not registered in TypeResolver | Register record/sealed interface, add to LedgerTypeRegistry |
| "Unbound variable: 'X'" | Variable used outside its scope, or De Bruijn index wrong | Check scope push/pop lifecycle |
| "Unsupported statement" | Java construct not in supported subset | Rewrite using supported subset (see SubsetValidator) |
| "Cycle detected in type dependencies" | Mutually recursive record types | Break cycle with DataType fields |

### PIR Debugging

To inspect intermediate PIR, add a breakpoint or print statement after `generateMethod()` in `JulcCompiler`. PIR terms have a readable `toString()`:

```java
PirTerm pir = generator.generateMethod(entrypoint);
System.out.println(pir); // Prints PIR structure
```

### UPLC Evaluation

Use `julc-vm-scalus` to evaluate UPLC terms and inspect results:

```java
var result = compiler.compile(source, List.of());
var program = result.program();
// Use VM to evaluate with test data
```

### Size Regression Testing

Track script sizes across changes:
```java
var cbor = program.toCborHex();
System.out.println("Script size: " + cbor.length() / 2 + " bytes");
```

---

## 27. Testing Patterns

### Test Classes (25 in julc-compiler)

| Test Class | Coverage Area |
|------------|---------------|
| `JulcCompilerTest` | Full pipeline integration |
| `EndToEndTest` | Complete validator scenarios |
| `PirGeneratorTest` | PIR generation from Java AST |
| `PirTypeTest` | PirType equality and structure |
| `PirTermTest` | PirTerm construction |
| `SubsetValidatorTest` | Java subset validation |
| `TypeResolverTest` | Java-to-PIR type mapping |
| `TypeMethodRegistryTest` | Instance method dispatch |
| `TypeMethodsTest` | Instance method compilation + eval |
| `UplcGeneratorTest` | PIR-to-UPLC lowering |
| `UplcOptimizerTest` | Optimization passes |
| `SealedInterfaceTest` | Sum type compilation |
| `PatternMatchTest` | Switch/instanceof compilation |
| `HelperMethodTest` | Static helper methods |
| `RecursionTest` | Recursive method compilation |
| `LoopDesugarTest` | Loop desugaring |
| `StdlibIntegrationTest` | Stdlib method integration |
| `StdlibCompileEvalTest` | Stdlib compile + evaluate |
| `LedgerTypeAccessTest` | Ledger type field access |
| `LambdaExpressionTest` | Lambda expression compilation |
| `ByteStringLibTest` | ByteString operations |
| `BigIntegerCompatTest` | BigInteger method support |
| `ParameterizedValidatorTest` | @Param validators |
| `MultiFileCompilerTest` | Multi-file / library compilation |
| `LibrarySourceResolverTest` | Library source resolution |

### Standard Test Pattern

```java
@Test
void testFeature() {
    // 1. Define Java source
    var source = """
        @Validator
        public class Test {
            @Entrypoint
            public static boolean validate(PlutusData redeemer, PlutusData ctx) {
                // Test logic that should return true
                return someCondition;
            }
        }
        """;

    // 2. Compile
    var result = compiler.compile(source, List.of());

    // 3. Assert compilation succeeded
    assertNotNull(result.program());
    assertTrue(result.diagnostics().stream().noneMatch(CompilerDiagnostic::isError));

    // 4. Evaluate with test data
    var evalResult = vm.evaluate(result.program(), testScriptContext);

    // 5. Check result
    assertTrue(evalResult.isSuccess());
}
```

### Test Types

**Unit tests:** Test individual components (TypeResolver, SymbolTable, PirHelpers) in isolation.

**Integration tests:** Compile Java source → assert PIR structure or UPLC evaluation result.

**End-to-end tests:** Full lifecycle including ScriptContext construction, validator execution, and result verification (see `julc-e2e-tests/` for CIP-113 tests).

---

## 28. Architecture Decision Records

| ADR | Title | Status |
|-----|-------|--------|
| ADR-001 | Plutus-Java Architecture | Accepted |
| ADR-002 | Milestone 2 — V3 Ledger Types | Proposed |
| ADR-003 | Milestone 3 — Compiler MVP | Proposed |
| ADR-004 | Milestone 8 — Developer Experience | — |
| ADR-005 | Production Readiness & Stdlib Completion | Completed |
| ADR-006 | Library Discovery + Stdlib-in-Java Rewrite | Accepted |
| ADR-007 | Convert Legacy PIR to Java Source | Accepted |
| ADR-008 | Rename to JuLC | Proposed |
| ADR-009 | CIP-113 Compiler Fixes | Completed |
| ADR-010 | Beta Release Review | — |
| ADR-011 | Post-Review Fixes | — |
| ADR-012 | Compiler Core Simplification | Completed |
| ADR-013 | Beta Release Readiness Review | — |
| ADR-014 | Release Readiness Review | — |
| ADR-015 | CompileMethod Param Support | — |
| ADR-016 | Feature Roadmap Prioritization | — |
| ADR-017 | 1.0 Release Readiness Assessment | — |
| ADR-018 | PirGenerator Refactoring | Completed |

ADR files are in the `adr/` directory at the project root.

---

## 29. Glossary

| Term | Definition |
|------|------------|
| **UPLC** | Untyped Plutus Lambda Calculus — the on-chain execution language |
| **PIR** | Plutus Intermediate Representation — typed bridge between Java and UPLC |
| **De Bruijn** | Variable indexing scheme where variables reference lambda binders by distance (1 = innermost) |
| **Force** | Evaluate a delayed/polymorphic term |
| **Delay** | Defer evaluation (create a thunk) |
| **Data** | Universal on-chain value encoding with 5 constructors (Constr, Map, List, I, B) |
| **CEK** | Count-Evaluate-Kont — the Plutus virtual machine (abstract machine) |
| **ScriptContext** | Cardano ledger data passed to every validator (contains TxInfo, redeemer, script info) |
| **SumType** | Tagged union — a sealed interface with record variants |
| **RecordType** | Product type — a record with named typed fields |
| **LetRec** | Recursive let binding — used for loops and self-recursive functions |
| **Accumulator** | Variable modified across loop iterations (packed into Data tuples for multi-acc) |
| **wrapDecode** | `PirHelpers` method to extract a typed value from raw Data |
| **wrapEncode** | `PirHelpers` method to wrap a typed value back into Data |
| **Z-combinator** | Strict fixed-point combinator enabling recursion in UPLC |
| **Kahn's algorithm** | Topological sort algorithm used for type registration and library ordering |
| **StdlibLookup** | Interface for resolving static method calls to PIR terms |
| **TypeMethodRegistry** | Registry mapping `(PirType, method)` pairs to instance method handlers |
| **@OnchainLibrary** | Annotation marking a class as a reusable on-chain library |
| **@Param** | Annotation marking a validator field as a deployment-time parameter |
| **@Entrypoint** | Annotation marking the main validator method |
| **FLAT** | Binary encoding format for UPLC programs on-chain |
| **SOPs** | Sums of Products — Plutus V3 constructor/case terms |

---

## 30. Quick Reference: File Index

| File | Lines | Role |
|------|-------|------|
| `julc-compiler/.../JulcCompiler.java` | 1,221 | Main pipeline orchestrator — 24-phase compilation |
| `julc-compiler/.../LibraryCompiler.java` | 138 | Library compilation sub-pipeline (extracted from JulcCompiler, ADR-018) |
| `julc-compiler/.../CompileResult.java` | — | Compilation result (program + diagnostics + params) |
| `julc-compiler/.../CompilerException.java` | — | Fatal compiler error |
| `julc-compiler/.../CompilerOptions.java` | — | Compilation options |
| `julc-compiler/.../LibrarySourceResolver.java` | — | Classpath scanning + transitive BFS library resolution |
| `julc-compiler/.../codegen/ValidatorWrapper.java` | — | ScriptContext decoding + bool→unit/error wrapping |
| `julc-compiler/.../codegen/DataCodecGenerator.java` | — | Data codec generation |
| `julc-compiler/.../desugar/LoopDesugarer.java` | — | For-each/while → LetRec transformation |
| `julc-compiler/.../desugar/PatternMatchDesugarer.java` | — | Switch/instanceof → DataMatch transformation |
| `julc-compiler/.../error/CompilerDiagnostic.java` | — | Diagnostic record (level, message, location, suggestion) |
| `julc-compiler/.../error/DiagnosticCollector.java` | — | Structured error collection with source location |
| `julc-compiler/.../pir/PirGenerator.java` | 2,147 | Java AST → PIR — core expression/statement compilation |
| `julc-compiler/.../pir/LoopBodyGenerator.java` | 531 | Loop body compilation — 5 paths × break/no-break (extracted, ADR-018) |
| `julc-compiler/.../pir/AccumulatorTypeAnalyzer.java` | 432 | Accumulator type analysis — pair list detection heuristics (extracted, ADR-018) |
| `julc-compiler/.../pir/TypeInferenceHelper.java` | 282 | Read-only type inference — resolveExpressionType, inferPirType (extracted, ADR-018) |
| `julc-compiler/.../pir/TypeMethodRegistry.java` | 905 | Instance method dispatch (~50 methods across 11 types) |
| `julc-compiler/.../pir/PirHelpers.java` | 356 | wrapDecode/wrapEncode + list length/contains/foldl + blockStmts |
| `julc-compiler/.../pir/PirHofBuilders.java` | 244 | HOF PIR builders (map, filter, any, all, find, foldl, zip) |
| `julc-compiler/.../pir/CompositeStdlibLookup.java` | — | Chains multiple StdlibLookup instances (first match wins) |
| `julc-compiler/.../pir/PirTerm.java` | — | PIR term AST (12 variants) |
| `julc-compiler/.../pir/PirType.java` | — | PIR type system (13 variants) |
| `julc-compiler/.../pir/StdlibLookup.java` | — | Functional interface for stdlib method resolution |
| `julc-compiler/.../pir/PirFormatter.java` | — | PIR pretty-printing |
| `julc-compiler/.../pir/PirSubstitution.java` | — | PIR variable substitution |
| `julc-compiler/.../resolve/TypeResolver.java` | — | Java → PIR type mapping |
| `julc-compiler/.../resolve/TypeRegistrar.java` | — | Topological type registration (Kahn's algorithm) |
| `julc-compiler/.../resolve/SymbolTable.java` | — | Scope stack for variable/method management |
| `julc-compiler/.../resolve/LedgerSourceLoader.java` | — | Dynamic ledger type loading from META-INF |
| `julc-compiler/.../resolve/LibraryMethodRegistry.java` | — | Compiled library method storage + typed coercion |
| `julc-compiler/.../resolve/ImportResolver.java` | — | Import resolution |
| `julc-compiler/.../uplc/UplcGenerator.java` | — | PIR → UPLC lowering (Let→Apply, LetRec→Z-combinator) |
| `julc-compiler/.../uplc/UplcOptimizer.java` | — | 6-pass UPLC optimizer with fixpoint iteration |
| `julc-compiler/.../validate/SubsetValidator.java` | — | Java subset enforcement |
| `julc-compiler/.../util/MethodDependencyResolver.java` | — | Method dependency graph construction |
| `julc-compiler/.../util/StringUtils.java` | — | String utilities (Levenshtein distance, etc.) |
| `julc-core/.../DefaultFun.java` | — | 102 Plutus builtin functions with FLAT codes |
| `julc-core/.../Term.java` | — | UPLC term AST (10 variants) |
| `julc-stdlib/.../StdlibRegistry.java` | — | PIR term builders for ~65 stdlib methods |
