# JuLC Compiler Design

A progressive guide to the JuLC compiler architecture — from high-level overview to internal implementation details. Intended for anyone from first-time contributors to seasoned compiler engineers.

## Table of Contents

**Level 1: The Big Picture**
1. [What is JuLC?](#1-what-is-julc)
2. [How Cardano Smart Contracts Work](#2-how-cardano-smart-contracts-work)
3. [From Java to On-Chain: The 10,000-Foot View](#3-from-java-to-on-chain-the-10000-foot-view)

**Level 2: Module Architecture**
4. [Project Modules](#4-project-modules)
5. [Module Dependency Graph](#5-module-dependency-graph)
6. [Key Data Types Across Modules](#6-key-data-types-across-modules)

**Level 3: The Compilation Pipeline**
7. [Pipeline Overview](#7-pipeline-overview)
8. [Phase 1: Parsing and Validation](#8-phase-1-parsing-and-validation)
9. [Phase 2: Type Registration](#9-phase-2-type-registration)
10. [Phase 3: PIR Generation](#10-phase-3-pir-generation)
11. [Phase 4: Loop Desugaring](#11-phase-4-loop-desugaring)
12. [Phase 5: UPLC Generation](#12-phase-5-uplc-generation)
13. [Phase 6: Optimization](#13-phase-6-optimization)
14. [Phase 7: Wrapping and Encoding](#14-phase-7-wrapping-and-encoding)

**Level 4: Compiler Internals**
15. [The PIR Intermediate Representation](#15-the-pir-intermediate-representation)
16. [The Type System](#16-the-type-system)
17. [Data Encoding: The Bridge Between Java and Plutus](#17-data-encoding-the-bridge-between-java-and-plutus)
18. [PirGenerator and Its Extracted Helpers](#18-pirgenerator-and-its-extracted-helpers)
19. [Method Call Dispatch](#19-method-call-dispatch)
20. [The Standard Library](#20-the-standard-library)
21. [Library Compilation](#21-library-compilation)

**Level 5: Execution and Testing**
22. [The Virtual Machine](#22-the-virtual-machine)
23. [The Testing Framework](#23-the-testing-framework)
24. [End-to-End: A Validator's Journey](#24-end-to-end-a-validators-journey)

**Appendices**
- [A. File Index](#appendix-a-file-index)
- [B. Glossary](#appendix-b-glossary)

---

# Level 1: The Big Picture

## 1. What is JuLC?

JuLC (Java UPLC Compiler) compiles a subset of Java into Untyped Plutus Lambda Calculus (UPLC) — the on-chain execution language for Cardano smart contracts. It lets Java developers write Cardano validators using familiar syntax, types, and tooling (IDEs, Gradle, JUnit) while producing the same on-chain bytecode as Haskell-based Plutus or Aiken.

```
┌─────────────────┐          ┌─────────────────┐          ┌──────────────┐
│   Java Source    │  ──────► │  JuLC Compiler   │  ──────► │  UPLC Program │
│  (@Validator)    │          │  (julc-compiler)  │          │  (on-chain)   │
└─────────────────┘          └─────────────────┘          └──────────────┘
```

**What JuLC is:**
- A **source-level compiler** (Java source code in, UPLC bytecode out)
- A **subset compiler** — only a safe, deterministic subset of Java is allowed
- A **Plutus V3** compiler targeting the Conway era

**What JuLC is NOT:**
- Not a JVM bytecode compiler (it reads `.java` files, not `.class` files)
- Not a full Java compiler (no classes, inheritance, exceptions, threads, I/O)
- Not a general-purpose transpiler (output is specifically UPLC for Cardano)

### The Java Subset

JuLC accepts a functional subset of Java designed for on-chain safety:

| Supported | Not Supported |
|-----------|---------------|
| `record` types | `class` with mutable fields |
| `sealed interface` + pattern matching | Class inheritance |
| `for-each` loops, `while` loops | `for(;;)` C-style loops, `do-while` |
| `if/else`, `switch` expressions | `try/catch`, `throw` |
| `Optional<T>` | `null` |
| `BigInteger`, `byte[]`, `String`, `boolean` | `float`, `double`, arrays |
| Static methods | Instance methods, `this`, `super` |
| Immutable variables (`final` semantics) | Reassignment (except loop accumulators) |

---

## 2. How Cardano Smart Contracts Work

Cardano uses the **Extended UTXO (eUTxO)** model. Each unspent transaction output (UTxO) can optionally be locked by a **validator script**. To spend that UTxO, a transaction must provide a **redeemer** (the "proof" or "action") and the validator script must return `true`.

```
┌──────────────────────────────────────────────────────────────┐
│                        Transaction                           │
│                                                              │
│  Inputs:                        Outputs:                     │
│  ┌──────────────────┐           ┌─────────────────┐         │
│  │ UTxO (locked by   │           │ UTxO (new, may   │         │
│  │  validator script)│           │  be locked too)  │         │
│  │ + Redeemer        │           └─────────────────┘         │
│  └────────┬─────────┘                                        │
│           │                                                  │
│           ▼                                                  │
│  ┌──────────────────────────────────────┐                    │
│  │  Validator Script (UPLC program)      │                    │
│  │  Input: ScriptContext (tx data)       │                    │
│  │  Output: true (accept) / false (fail) │                    │
│  └──────────────────────────────────────┘                    │
└──────────────────────────────────────────────────────────────┘
```

The **ScriptContext** passed to every validator contains:
- **TxInfo** — the full transaction (inputs, outputs, fees, minting, signatories, etc.)
- **Redeemer** — the action data provided by the transaction submitter
- **ScriptInfo** — identifies which script purpose triggered this execution (spending, minting, etc.)

### Script Purposes

| Purpose | When Triggered | Java Annotation |
|---------|---------------|-----------------|
| Spending | Consuming a UTxO locked at a script address | `@SpendingValidator` |
| Minting | Minting or burning tokens under this policy | `@MintingValidator` |
| Withdrawing | Withdrawing staking rewards | `@WithdrawValidator` |
| Certifying | Publishing delegation certificates | `@CertifyingValidator` |
| Voting | Casting governance votes | `@VotingValidator` |
| Proposing | Submitting governance proposals | `@ProposingValidator` |

---

## 3. From Java to On-Chain: The 10,000-Foot View

The JuLC compilation pipeline transforms Java source through three intermediate representations:

```
Java Source                     (human-readable)
     │
     │  JavaParser
     ▼
Java AST                        (abstract syntax tree — JavaParser nodes)
     │
     │  PirGenerator
     ▼
PIR (Plutus Intermediate Rep)   (typed, named variables, high-level constructs)
     │
     │  UplcGenerator
     ▼
UPLC (Untyped Plutus LC)        (untyped, De Bruijn indices, minimal)
     │
     │  FlatEncoder + CborEncoder
     ▼
On-chain bytecode               (binary, embedded in transactions)
```

**Why three stages?**

- **Java AST → PIR**: Translates Java constructs (records, loops, method calls) into a typed lambda calculus with `Let` bindings, `LetRec` for recursion, and `DataMatch` for pattern matching. Types are preserved for correct encode/decode insertion.

- **PIR → UPLC**: Erases types, converts named variables to De Bruijn indices, lowers `Let` to function application, `LetRec` to the Z-combinator, and `DataMatch` to tag-based dispatch. This is the "compilation" step.

- **UPLC → Binary**: Serializes using FLAT encoding (compact bit-level format) wrapped in CBOR. This is what goes on-chain.

---

# Level 2: Module Architecture

## 4. Project Modules

JuLC is organized into focused modules. Here are the key ones grouped by role:

### Core (the foundation)

| Module | Role | Key Types |
|--------|------|-----------|
| `julc-core` | UPLC AST, constants, serialization | `Term` (10 variants), `DefaultFun` (102 builtins), `PlutusData`, `Program` |
| `julc-ledger-api` | Java records for all Cardano V3 ledger types | `ScriptContext`, `TxInfo`, `TxOut`, `Value`, `Address`, `Credential` + 35 more |

### Compiler (the main event)

| Module | Role | Key Types |
|--------|------|-----------|
| `julc-compiler` | Java source → UPLC compilation (36 Java files) | `JulcCompiler`, `PirGenerator`, `PirTerm`, `PirType`, `UplcGenerator` |
| `julc-stdlib` | On-chain standard library (13 libraries, ~65 methods) | `StdlibRegistry`, `ListsLib`, `MapLib`, `ValuesLib`, etc. |

### Runtime (execution)

| Module | Role | Key Types |
|--------|------|-----------|
| `julc-vm` | VM SPI — pluggable execution backend | `JulcVm`, `JulcVmProvider`, `EvalResult` |
| `julc-vm-scalus` | Default VM backend (wraps Scalus CEK machine) | `ScalusVmProvider` |
| `julc-vm-java` | Future pure-Java VM backend | (in development) |

### Developer Experience

| Module | Role |
|--------|------|
| `julc-onchain-api` | Annotations (`@Validator`, `@Entrypoint`, `@Param`, `@OnchainLibrary`) + off-chain stubs for IDE support |
| `julc-testkit` | `ValidatorTest` base class for JUnit-based validator testing |
| `julc-testkit-jqwik` | Property-based testing support |
| `julc-annotation-processor` | Java annotation processor — compiles validators at build time |
| `julc-gradle-plugin` | Gradle plugin wrapping the annotation processor |
| `julc-blueprint` | Plutus blueprint (CIP-57) generation |
| `julc-cli` | Command-line compiler interface |

### Integration

| Module | Role |
|--------|------|
| `julc-cardano-client-lib` | Integration with cardano-client-lib (transaction building) |
| `julc-e2e-tests` | End-to-end integration tests (CIP-113, etc.) |
| `julc-examples` | Example validators and library code |
| `julc-bom` | Bill of Materials for dependency management |

### Advanced / Experimental

| Module | Role |
|--------|------|
| `julc-analysis` | Static analysis tooling |
| `julc-decompiler` | UPLC → human-readable decompilation |
| `julc-benchmark` | Performance benchmarking |
| `julc-bls` | BLS12-381 cryptographic operations |
| `julc-vm-truffle` | GraalVM Truffle-based VM backend |
| `julc-playground` | Web-based compiler playground |

---

## 5. Module Dependency Graph

```
                          ┌────────────────┐
                          │   julc-bom     │  (Bill of Materials)
                          └────────────────┘

                    ┌─────────────────────────┐
                    │       julc-core          │
                    │  Term, DefaultFun,       │
                    │  PlutusData, Program     │
                    └────────┬────────────────┘
                             │
              ┌──────────────┼──────────────────┐
              │              │                  │
              ▼              ▼                  ▼
     ┌────────────┐  ┌──────────────┐  ┌───────────────┐
     │  julc-vm   │  │julc-ledger-  │  │julc-onchain-  │
     │  (VM SPI)  │  │    api       │  │    api        │
     └─────┬──────┘  │ (40 types)   │  │ (Annotations) │
           │         └──────┬───────┘  └───────┬───────┘
           │                │                  │
     ┌─────┴──────┐  ┌─────┴──────────────────┴────────┐
     │julc-vm-    │  │         julc-compiler             │
     │  scalus    │  │  (Java → PIR → UPLC pipeline)     │
     └────────────┘  │  36 files, 7 packages              │
                     └──────────┬───────────────────────┘
                                │
              ┌─────────────────┼────────────────────┐
              │                 │                    │
              ▼                 ▼                    ▼
     ┌──────────────┐  ┌───────────────┐   ┌───────────────┐
     │ julc-stdlib  │  │ julc-testkit  │   │julc-annotation│
     │ (13 libs)    │  │(ValidatorTest)│   │  -processor   │
     └──────────────┘  └───────────────┘   └───────────────┘
                                │
                       ┌────────┴────────┐
                       │ julc-gradle-    │
                       │   plugin        │
                       └─────────────────┘
```

**Key dependency rules:**
- `julc-core` has **no** dependency on other julc modules (only cbor-java)
- `julc-compiler` depends on `julc-core` and `julc-ledger-api` but **not** on `julc-vm`
- `julc-vm` depends only on `julc-core`
- `julc-stdlib` provides `StdlibRegistry` used by `julc-compiler` at compile time
- `julc-testkit` composes `julc-compiler` + `julc-vm` for test-time compile-and-execute

---

## 6. Key Data Types Across Modules

### UPLC Terms (`julc-core`)

The on-chain language has 10 term variants:

```java
sealed interface Term {
    record Var(NamedDeBruijn name)                    // Variable (De Bruijn indexed)
    record Lam(String name, Term body)                // Lambda abstraction
    record Apply(Term function, Term argument)        // Function application
    record Force(Term term)                           // Force polymorphic term
    record Delay(Term term)                           // Delay evaluation (thunk)
    record Const(Constant value)                      // Constant value
    record Builtin(DefaultFun fun)                    // Built-in function (102 total)
    record Error()                                    // Halt execution
    record Constr(long tag, List<Term> fields)        // Constructor (Plutus V3)
    record Case(Term scrutinee, List<Term> branches)  // Case match (Plutus V3)
}
```

### PIR Terms (`julc-compiler`)

The intermediate representation adds types and high-level constructs:

```java
sealed interface PirTerm {
    record Var(String name, PirType type)                              // Named, typed variable
    record Let(String name, PirTerm value, PirTerm body)              // Let binding
    record LetRec(List<Binding> bindings, PirTerm body)               // Recursive let (loops)
    record Lam(String param, PirType paramType, PirTerm body)         // Typed lambda
    record App(PirTerm function, PirTerm argument)                    // Application
    record Const(Constant value)                                      // Constant
    record Builtin(DefaultFun fun)                                    // Builtin
    record IfThenElse(PirTerm cond, PirTerm then_, PirTerm else_)    // Conditional
    record DataConstr(int tag, PirType type, List<PirTerm> fields)   // Data constructor
    record DataMatch(PirTerm scrutinee, List<MatchBranch> branches)  // Pattern match
    record Error(PirType type)                                        // Typed error
    record Trace(PirTerm message, PirTerm body)                      // Debug trace
}
```

### PIR Types (`julc-compiler`)

```java
sealed interface PirType {
    // Primitives
    record IntegerType()
    record ByteStringType()
    record StringType()
    record BoolType()
    record UnitType()
    record DataType()           // Raw untyped Data (escape hatch)

    // Containers
    record ListType(PirType elemType)
    record PairType(PirType first, PirType second)
    record MapType(PirType keyType, PirType valueType)
    record OptionalType(PirType elemType)
    record ArrayType(PirType elemType)    // PV11, CIP-156

    // Functions
    record FunType(PirType paramType, PirType returnType)

    // Algebraic data types
    record RecordType(String name, List<Field> fields)
    record SumType(String name, List<Constructor> constructors)
}
```

### Plutus Data (`julc-core`)

The universal on-chain value encoding:

```java
sealed interface PlutusData {
    record ConstrData(long tag, List<PlutusData> fields)
    record MapData(List<Map.Entry<PlutusData, PlutusData>> entries)
    record ListData(List<PlutusData> items)
    record IntData(BigInteger value)
    record BytesData(byte[] value)
}
```

Every Java value eventually becomes `PlutusData` on-chain. The compiler inserts encode/decode operations at type boundaries.

---

# Level 3: The Compilation Pipeline

## 7. Pipeline Overview

`JulcCompiler.compile()` orchestrates a 24-step pipeline. Here is the complete flow:

```
Java Source(s)
     │
     ▼
┌─────────────────────────────────────────┐
│  1. Parse (JavaParser → AST)            │
│  2. Validate (SubsetValidator)          │
│  3. Library check                       │
│  4. Annotated class discovery           │
│  5. Script purpose detection            │
│  6. Type registration (ledger + user)   │
│  7. @Param field detection              │
│  8. Static field detection              │
│  9. Entrypoint discovery                │
│ 10. Parameter validation                │
│ 11. Library compilation (multi-pass)    │
│ 12. Compose stdlib + library lookups    │
│ 13. Symbol table setup                  │
│ 14. Helper method PIR generation        │
│ 15. Entrypoint PIR generation           │
│ 16. Helper method wrapping (Let)        │
│ 17. Static field wrapping (Let)         │
│ 18. Library method wrapping (Let/LetRec)│
│ 19. Validator wrapping (ScriptContext)   │
│ 20. @Param wrapping (outer lambdas)     │
│ 21. UPLC generation                     │
│ 22. Optimization (6 passes, fixpoint)   │
│ 23. Program creation (PlutusV3)         │
│ 24. ParamInfo creation                  │
└─────────────────────────────────────────┘
     │
     ▼
  CompileResult(program, params, diagnostics)
```

The pipeline can be divided into four major phases:

| Phase | Steps | Input → Output |
|-------|-------|----------------|
| **Frontend** | 1-10 | Java source → validated AST + metadata |
| **Middle-end** | 11-18 | AST → PIR term tree |
| **Backend** | 19-22 | PIR → optimized UPLC |
| **Output** | 23-24 | UPLC → serialized Program |

---

## 8. Phase 1: Parsing and Validation

### Parsing

JuLC uses [JavaParser](https://javaparser.org/) to parse Java source into an AST:

```java
StaticJavaParser.getParserConfiguration()
    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
CompilationUnit cu = StaticJavaParser.parse(source);
```

The parser handles Java 21 features: records, sealed interfaces, pattern matching in `switch`, `instanceof` patterns.

### SubsetValidator

After parsing, `SubsetValidator` walks the AST to reject unsupported Java constructs. It extends JavaParser's `VoidVisitorAdapter` and collects multiple errors:

```
SubsetValidator rejects:
  try/catch, throw, synchronized, for(;;), do-while,
  null, this, super, new T[], float/double, class inheritance

SubsetValidator allows:
  for-each, while, break (in loops), records, sealed interfaces,
  switch expressions, instanceof patterns, Optional<T>
```

Each rejection includes a **suggestion** pointing to the on-chain alternative:
```
ERROR: null is not supported on-chain
Suggestion: Use Optional<T> to represent absence
```

### Annotation Discovery

The compiler discovers:
1. The **validator class** — annotated with `@SpendingValidator`, `@MintingValidator`, etc.
2. The **entrypoint method** — annotated with `@Entrypoint`
3. **@Param fields** — deployment-time parameters
4. **Static fields** — compile-time constants

---

## 9. Phase 2: Type Registration

Before PIR generation can resolve types, all record and sealed interface types must be registered.

### Two-Stage Registration

**Stage 1: Ledger Types** (`LedgerTypeRegistry`)

Pre-registers ~40 Cardano ledger types in 4 tiers (dependency order):

```
Tier 1: Leaf records       → TxOutRef, Value, IntervalBound
Tier 2: Sealed interfaces  → Credential, OutputDatum, ScriptInfo, Vote, DRep
Tier 3: Composite records  → Address, TxOut, TxInInfo, Interval
Tier 4: Top-level          → TxInfo (16 fields), ScriptContext
```

Governance types (Conway era): `Vote`, `DRep`, `Voter`, `StakingCredential`, `Delegatee`, `TxCert`, `GovernanceAction`, `ProposalProcedure`, `Committee`, `ScriptPurpose`.

**Stage 2: User Types** (`TypeRegistrar`)

Processes all compilation units (validator + libraries) together:

```
1. Collect all record and sealed interface declarations
2. Validate no duplicate type names
3. Build dependency graph (field types create edges)
4. Topological sort (Kahn's algorithm)
5. Register in dependency order
```

Circular dependencies are detected and reported as errors.

### TypeResolver

`TypeResolver` maps Java types to PIR types:

| Java Type | PIR Type |
|-----------|----------|
| `int`, `long`, `BigInteger` | `IntegerType` |
| `byte[]`, `PubKeyHash`, `TxId`, `PolicyId`, ... | `ByteStringType` |
| `boolean` | `BoolType` |
| `String` | `StringType` |
| `void` | `UnitType` |
| `PlutusData` and subtypes | `DataType` |
| `List<T>` / `JulcList<T>` | `ListType(resolve(T))` |
| `Map<K,V>` / `JulcMap<K,V>` | `MapType(resolve(K), resolve(V))` |
| `Optional<T>` | `OptionalType(resolve(T))` |
| User records | `RecordType(name, fields)` |
| User sealed interfaces | `SumType(name, constructors)` |

---

## 10. Phase 3: PIR Generation

This is the heart of the compiler. `PirGenerator` (2,147 lines) transforms Java AST nodes into PIR terms, assisted by three extracted helper classes.

### Architecture After ADR-018 Refactoring

```
┌──────────────────────────────────────────────────────────┐
│                    PirGenerator (2,147 lines)             │
│  Entry points: generateMethod(), generateExpression()    │
│  Owns: SymbolTable, TypeResolver, StdlibLookup           │
│                                                          │
│  Delegates to:                                           │
│  ┌────────────────────────────────────────────────┐      │
│  │  AccumulatorTypeAnalyzer (432 lines)            │      │
│  │  Pure AST analysis — detects loop accumulator   │      │
│  │  types (List vs Map) from usage patterns        │      │
│  └────────────────────────────────────────────────┘      │
│  ┌────────────────────────────────────────────────┐      │
│  │  TypeInferenceHelper (282 lines)                │      │
│  │  Read-only type inference —                     │      │
│  │  resolveExpressionType, inferPirType,           │      │
│  │  inferBuiltinReturnType                         │      │
│  └────────────────────────────────────────────────┘      │
│  ┌────────────────────────────────────────────────┐      │
│  │  LoopBodyGenerator (531 lines)                  │      │
│  │  Loop body compilation — 5 paths:               │      │
│  │  single/multi-acc × break/no-break + zero-acc   │      │
│  │  Pack/unpack accumulators, nested loop handling  │      │
│  └────────────────────────────────────────────────┘      │
│                                                          │
│  Also uses:                                              │
│  ┌────────────────────────────────────────────────┐      │
│  │  TypeMethodRegistry (905 lines)                 │      │
│  │  Instance method dispatch (~50 methods)         │      │
│  └────────────────────────────────────────────────┘      │
│  ┌────────────────────────────────────────────────┐      │
│  │  PirHelpers (356 lines)                         │      │
│  │  wrapDecode, wrapEncode, list utilities          │      │
│  └────────────────────────────────────────────────┘      │
│  ┌────────────────────────────────────────────────┐      │
│  │  PirHofBuilders (244 lines)                     │      │
│  │  HOF PIR builders (map, filter, any, all, find) │      │
│  └────────────────────────────────────────────────┘      │
└──────────────────────────────────────────────────────────┘
```

### Expression Compilation

| Java Expression | PIR Term |
|-----------------|----------|
| `42` | `Const(Integer(42))` |
| `true` | `Const(Bool(true))` |
| `"hello"` | `Const(String("hello"))` |
| `x` | `Var("x", type)` |
| `a + b` | `App(App(Builtin(AddInteger), a), b)` |
| `a == b` | `App(App(Builtin(EqualsInteger), a), b)` (type-aware) |
| `!x` | `IfThenElse(x, false, true)` |
| `cond ? a : b` | `IfThenElse(cond, a, b)` |
| `new Point(x, y)` | `DataConstr(0, RecordType, [x, y])` |
| `point.x()` | `wrapDecode(HeadList(SndPair(UnConstrData(point))), fieldType)` |

### Statement Compilation

| Java Statement | PIR Term |
|----------------|----------|
| `int x = 5; rest...` | `Let("x", Const(5), rest)` |
| `return expr` | `generateExpression(expr)` (becomes final term) |
| `someCall(); rest...` | `Let("_", someCall, rest)` (evaluate and discard) |
| `if (c) { t } else { e }` | `IfThenElse(c, t, e)` |
| `switch (s) { case A a -> ... }` | `DataMatch(s, [branches...])` |

### Binary Operators (Type-Aware)

| Op | Integer | String | ByteString | Data |
|----|---------|--------|------------|------|
| `+` | `AddInteger` | `AppendString` | `AppendByteString` | — |
| `==` | `EqualsInteger` | `EqualsString` | `EqualsByteString` | `EqualsData` |
| `<` | `LessThanInteger` | — | `LessThanByteString` | — |

---

## 11. Phase 4: Loop Desugaring

Since UPLC has no loops, all loops are transformed into recursive `LetRec` patterns.

### For-Each Loop

```java
long sum = 0;
for (var item : items) {
    sum = sum + item;
}
```

Becomes:

```
LetRec([loop__forEach__0 = \xs \acc ->
    IfThenElse(NullList(xs),
        acc,                                        // base case: return accumulator
        Let(item, wrapDecode(HeadList(xs), elemType),
            loop__forEach__0(TailList(xs), acc + item)))  // recursive case
], loop__forEach__0(items, 0))                      // initial call
```

### While Loop

```java
long n = x;
while (n > 0) {
    n = n - 1;
}
```

Becomes:

```
LetRec([loop__while__0 = \n ->
    IfThenElse(n > 0,
        loop__while__0(n - 1),    // recursive case
        n)                         // base case: return accumulator
], loop__while__0(x))
```

### Five Compilation Paths

| Path | Accumulators | Break? | Strategy |
|------|-------------|--------|----------|
| A | 1 | No | Simple LetRec with single accumulator parameter |
| B | 1 | Yes | Break returns accumulator directly; continue recurses |
| C | 2+ | No | Pack accumulators into Data list tuple |
| D | 0 | — | Unit accumulator, discard result |
| E | 2+ | Yes | Tuple packing + break-aware body |

### Accumulator Type Refinement

`AccumulatorTypeAnalyzer` distinguishes `ListType` vs `MapType` accumulators by scanning the loop body for evidence:

| Evidence Found | Inferred Type |
|---------------|---------------|
| `mkNilPairData()` initialization | MapType |
| `fstPair()`/`sndPair()` on cursor element | MapType |
| `mkCons()` with `mkPairData()` items | MapType |
| `mkNilData()` without pair evidence | ListType |

---

## 12. Phase 5: UPLC Generation

`UplcGenerator` lowers PIR to UPLC by erasing types and eliminating high-level constructs.

### Key Transformations

**Let → Application:**
```
Let(x, val, body)  →  Apply(Lam("x", body'), val')
```

**LetRec → Z-combinator (strict fixed-point):**
```
Z = \f -> (\x -> f (\v -> x x v)) (\x -> f (\v -> x x v))

LetRec([name = body], expr)
→ Apply(Lam(name, expr'), Apply(Z, Lam(name, body')))
```

**IfThenElse → Force/Delay (lazy branches):**
```
IfThenElse(c, t, e)
→ Force(Apply(Apply(Apply(Force(Builtin(IfThenElse)), c'),
                    Delay(t')),    ← prevent eager evaluation
              Delay(e')))
```

**DataConstr → ConstrData:**
```
DataConstr(tag, type, [f1, f2])
→ ConstrData(tag, MkCons(encode(f1), MkCons(encode(f2), MkNilData)))
```

**DataMatch → Tag dispatch:**
```
DataMatch(scrutinee, branches)
→ Let(pair, UnConstrData(scrutinee),
    Let(tag, FstPair(pair),
      Let(fields, SndPair(pair),
        IfThenElse(tag == 0, branch0(fields),
          IfThenElse(tag == 1, branch1(fields),
            Error)))))
```

**De Bruijn Indexing:** Named variables become integer indices counting lambda binders outward:
```
\x -> \y -> x   →   Lam("x", Lam("y", Var(2)))
                                          ^^^ cross y(1) then x(2)
```

### Force Counts for Polymorphic Builtins

| Forces | Builtins |
|--------|----------|
| 2 (∀ a b) | `FstPair`, `SndPair`, `ChooseList` |
| 1 (∀ a) | `IfThenElse`, `Trace`, `MkCons`, `HeadList`, `TailList`, `NullList`, ... |
| 0 (monomorphic) | Arithmetic, comparisons, crypto, encode/decode |

---

## 13. Phase 6: Optimization

`UplcOptimizer` runs 6 passes in a fixpoint loop (max 20 iterations, stops when unchanged):

### Pass 1: Force/Delay Cancellation
```
Force(Delay(t)) → t
```
Undoes the lazy wrapping when the result is immediately forced.

### Pass 2: Constant Folding
```
AddInteger(3, 4) → 7
EqualsInteger(8, 8) → True
```
Supports: `AddInteger`, `SubtractInteger`, `MultiplyInteger`, `EqualsInteger`, `LessThanInteger`, `LessThanEqualsInteger`, `EqualsByteString`, `AppendByteString`.

### Pass 3: Dead Code Elimination
```
Apply(Lam(x, body), val) → body   (when x unused in body, val has no side effects)
```
`Trace` calls are considered side-effecting and preserved.

### Pass 4: Beta Reduction
```
Apply(Lam(x, body), arg) → substitute(body, arg)   (when x used once, arg is "simple")
```
"Simple" = `Const`, `Var`, `Builtin`, or `Force` of these. Complex args are not inlined.

### Pass 5: Eta Reduction
```
Lam(x, Apply(f, Var(1))) → f   (when x not free in f, f is a value)
```

### Pass 6: Constr/Case Reduction
```
Case(Constr(tag, fields), branches) → Apply(branches[tag], fields...)
```

---

## 14. Phase 7: Wrapping and Encoding

### Validator Wrapping

`ValidatorWrapper` adds ScriptContext decoding and bool→unit/error conversion:

```
\scriptContextData ->
  let ctxFields = SndPair(UnConstrData(scriptContextData))
  let redeemer = HeadList(TailList(ctxFields))       // field 1
  let result = validate(redeemer, scriptContextData)
  in IfThenElse(result, Unit, Error)
```

For spending validators with datum (3-param), the wrapper also extracts the datum from `ScriptInfo.SpendingScript`.

### @Param Wrapping

Each `@Param` field adds an outer lambda:

```
\param1__raw -> Let(param1, UnIData(param1__raw),
  \param2__raw -> Let(param2, UnBData(param2__raw),
    <validator body>))
```

### Serialization

The final UPLC term is wrapped in `Program.plutusV3(term)` and serialized:
1. **FLAT encoding** — compact bit-level binary format
2. **CBOR wrapping** — standard Cardano on-chain format

---

# Level 4: Compiler Internals

## 15. The PIR Intermediate Representation

PIR serves as the **typed bridge** between Java and UPLC. It preserves type information that UPLC lacks, enabling the compiler to insert correct encode/decode operations.

### Why PIR Exists

UPLC is too low-level to compile to directly:
- No named variables (De Bruijn indices only)
- No types (everything is untyped)
- No `Let` bindings (only lambda application)
- No loops (only recursion via combinators)
- No pattern matching (only tag extraction)

PIR adds these as first-class constructs, making compilation from Java AST straightforward:

| Java Construct | PIR Construct | UPLC Lowering |
|---------------|---------------|---------------|
| Variable declaration | `Let(name, value, body)` | `Apply(Lam(name, body), value)` |
| Loop | `LetRec([binding], body)` | Z-combinator application |
| Record construction | `DataConstr(tag, type, fields)` | `ConstrData(tag, fieldList)` |
| Pattern matching | `DataMatch(scrutinee, branches)` | Tag dispatch chain |
| Method call | `App(function, argument)` | `Apply(function, argument)` |
| Conditional | `IfThenElse(c, t, e)` | `Force(Apply(Apply(Apply(Force(IfThenElse), c), Delay(t)), Delay(e)))` |

---

## 16. The Type System

### The Data Encoding Problem

On the Cardano ledger, all values are encoded as **Data** — a universal 5-constructor representation:

| Data Constructor | Encodes | Encode Builtin | Decode Builtin |
|-----------------|---------|----------------|----------------|
| `I(integer)` | `int`, `long`, `BigInteger` | `IData` | `UnIData` |
| `B(bytestring)` | `byte[]`, hash types | `BData` | `UnBData` |
| `Constr(tag, fields)` | Records, sealed interfaces, booleans, Optional | `ConstrData` | `UnConstrData` |
| `Map(pairs)` | `Map<K,V>` | `MapData` | `UnMapData` |
| `List(items)` | `List<T>` | `ListData` | `UnListData` |

The compiler must insert encode/decode wrappers at every type boundary. Two key helpers in `PirHelpers.java`:

**`wrapDecode(data, targetType)`** — Extract typed value from raw Data:
```
IntegerType     → UnIData(data)
ByteStringType  → UnBData(data)
ListType        → UnListData(data)
MapType         → UnMapData(data)
BoolType        → EqualsInteger(FstPair(UnConstrData(data)), 1)
StringType      → DecodeUtf8(UnBData(data))
DataType        → data  (pass through)
```

**`wrapEncode(value, type)`** — Wrap typed value back to Data:
```
IntegerType     → IData(value)
ByteStringType  → BData(value)
BoolType        → IfThenElse(value, ConstrData(1,[]), ConstrData(0,[]))
StringType      → BData(EncodeUtf8(value))
ListType        → ListData(value)
MapType         → MapData(value)
DataType        → value  (pass through)
```

### Record and Sum Type Encoding

```java
// Record: tag 0, fields in order
record Point(int x, int y) {}
// Point(10, 20) → Constr(0, [I(10), I(20)])

// Sealed interface: ascending tags per variant
sealed interface Shape {
    record Circle(int radius) implements Shape {}    // tag 0
    record Rect(int w, int h) implements Shape {}    // tag 1
}
// Circle(5) → Constr(0, [I(5)])
// Rect(3,4) → Constr(1, [I(3), I(4)])
```

### Boolean Encoding

Booleans map to `Constr(1, [])` (True) and `Constr(0, [])` (False), matching the Haskell Plutus convention.

---

## 17. Data Encoding: The Bridge Between Java and Plutus

Understanding encode/decode insertion is key to understanding the compiler. Here's when each happens:

| Situation | Direction | Example |
|-----------|-----------|---------|
| Record field access | Decode | `point.x()` → `UnIData(HeadList(fields))` |
| Record construction | Encode | `new Point(x, y)` → `ConstrData(0, [IData(x), IData(y)])` |
| List element access | Decode | `list.head()` → `wrapDecode(HeadList(list), elemType)` |
| List prepend | Encode | `list.prepend(elem)` → `MkCons(wrapEncode(elem), list)` |
| Method parameter | Pass-through | Entrypoint params are raw Data |
| `@Param` decode | Decode | `@Param int fee` → `UnIData(param__raw)` |
| HOF lambda argument | Decode | `list.map(x -> ...)` → unwrap x from Data |
| HOF lambda result | Encode | `list.map(x -> x+1)` → wrap result to Data |

---

## 18. PirGenerator and Its Extracted Helpers

### Responsibility Split (ADR-018)

Before the ADR-018 refactoring, PirGenerator was a 3,369-line monolith. It was decomposed into focused classes:

| Class | Lines | Responsibility |
|-------|-------|----------------|
| `PirGenerator` | 2,147 | Core: expressions, statements, method calls, record access, control flow |
| `LoopBodyGenerator` | 531 | Loop body compilation across 5 paths (single/multi-acc × break/no-break) |
| `AccumulatorTypeAnalyzer` | 432 | Pure AST analysis: detect accumulator types from usage patterns |
| `TypeInferenceHelper` | 282 | Read-only type queries: resolve expression types, infer PIR types |
| `TypeMethodRegistry` | 905 | Instance method dispatch: 50+ methods across 11 type categories |
| `PirHelpers` | 356 | Static utilities: wrapDecode, wrapEncode, list operations |
| `PirHofBuilders` | 244 | HOF PIR construction: map, filter, any, all, find, foldl, zip |

### How They Interact

```
PirGenerator
  │
  ├── calls ──► AccumulatorTypeAnalyzer.refineAccumulatorTypes()
  │              (before loop compilation, to determine accumulator types)
  │
  ├── calls ──► TypeInferenceHelper.resolveExpressionType()
  │              (during expression compilation, for type-aware dispatch)
  │
  ├── calls ──► LoopBodyGenerator.generateSingleAccBody() / etc.
  │              (loop body compilation; LoopBodyGenerator calls BACK to
  │               PirGenerator for nested expressions/statements)
  │
  ├── calls ──► TypeMethodRegistry.resolve(type, method, args)
  │              (instance method dispatch: list.head(), map.get(), etc.)
  │
  ├── calls ──► PirHelpers.wrapDecode() / wrapEncode()
  │              (encode/decode insertion at type boundaries)
  │
  └── calls ──► PirHofBuilders.buildMap() / buildFilter() / etc.
                 (HOF lambda compilation for list.map(), list.filter(), etc.)
```

---

## 19. Method Call Dispatch

`PirGenerator.generateMethodCall()` implements a 5-level cascading dispatch. After the ADR-018 refactoring, each level is a named method:

```java
private PirTerm generateMethodCall(MethodCallExpr mce) {
    // Level 1: BigInteger.valueOf(n) → identity
    result = tryBigIntegerConstant(mce, scope, method, args);
    if (result != null) return result;

    // Level 2: PlutusData.fromPlutusData() → identity
    result = tryFromPlutusDataIdentity(mce, scope, method, args);
    if (result != null) return result;

    // Level 3: (Type)(Object) cast patterns
    result = tryPlutusDataCast(mce, scope, method, args);
    if (result != null) return result;

    // Level 4: Stdlib static methods (Builtins.*, ListsLib.*, Math.*)
    result = tryStaticStdlibCall(mce, scope, method, args);
    if (result != null) return result;

    // Level 5: Instance methods via TypeMethodRegistry
    return resolveInstanceMethodCall(mce, scope, method, args);
    // Falls through to: record field access → helper method → error
}
```

### Instance Method Examples

| Java Call | Dispatch | PIR Output |
|-----------|----------|------------|
| `list.head()` | TypeMethodRegistry → ListType.head | `wrapDecode(HeadList(list), elemType)` |
| `list.map(x -> x+1)` | TypeMethodRegistry → ListType.map → PirHofBuilders | LetRec fold with lambda |
| `map.get(key)` | TypeMethodRegistry → MapType.get | LetRec pair list search |
| `value.lovelaceOf()` | TypeMethodRegistry → "Value.lovelaceOf" (named dispatch) | Nested map lookup |
| `n.abs()` | TypeMethodRegistry → IntegerType.abs | `IfThenElse(n < 0, 0-n, n)` |

---

## 20. The Standard Library

### StdlibRegistry

`StdlibRegistry` (in `julc-stdlib`) provides PIR term builders for ~65 methods. These are methods that cannot be compiled from Java source because they require low-level PIR/UPLC constructs (lambdas as values, LetRec builders, raw builtin chaining).

**Categories:**

| Category | Examples |
|----------|---------|
| Raw builtins | `Builtins.headList()`, `Builtins.iData()`, `Builtins.sha2_256()` |
| HOF builders | `ListsLib.map()`, `ListsLib.filter()`, `ListsLib.foldl()` |
| Math delegates | `Math.abs()`, `Math.max()`, `Math.min()` |
| Factory methods | `Optional.of()`, `Optional.empty()`, `PubKeyHash.of()` |

### 13 On-Chain Libraries

These are `@OnchainLibrary`-annotated Java classes compiled from source:

| Library | Methods | Focus |
|---------|---------|-------|
| `ListsLib` | 17+ | List operations + HOFs (map, filter, any, all, find, foldl, zip) |
| `MapLib` | 9 | Map operations (lookup, insert, delete, keys, values) |
| `ValuesLib` | 9 | Multi-asset Value operations (add, subtract, compare) |
| `ContextsLib` | 13 | ScriptContext/TxInfo field extraction helpers |
| `OutputLib` | 8 | TxOut querying (outputsAt, lovelacePaidTo, etc.) |
| `MathLib` | 8 | Math utilities (abs, pow, divMod, quotRem, expMod) |
| `IntervalLib` | 5 | Time interval operations |
| `CryptoLib` | 3 | ECDSA, Schnorr, RIPEMD-160 |
| `ByteStringLib` | 8 | ByteString manipulation |
| `BitwiseLib` | 10 | Bitwise operations |
| `AddressLib` | 3 | Address/credential utilities |
| `BlsLib` | — | BLS12-381 operations (PV11) |
| `NativeValueLib` | — | Native Value operations (PV11) |

### StdlibLookup Chain

When the compiler encounters `SomeClass.method(args)`:

```
CompositeStdlibLookup
  ├── 1. StdlibRegistry        (builtins, HOFs, math — ~65 methods)
  └── 2. LibraryMethodRegistry  (compiled @OnchainLibrary methods)
```

First match wins. If neither matches, the compiler falls through to helper method lookup or reports an error.

---

## 21. Library Compilation

Library methods may depend on each other across files. `LibraryCompiler` (extracted from JulcCompiler in ADR-018) uses a multi-pass retry strategy:

```
Pass 1: Try compiling all library CUs
  → LibA succeeds (no dependencies)
  → LibB fails (depends on LibA, not yet available)

Pass 2: Try remaining CUs
  → LibB succeeds (LibA now in registry)

Pass 3: No progress → report errors for any remaining
```

### Topological Sorting

Before wrapping library methods as `Let` bindings around the validator:

1. Build dependency graph: method A depends on method B if A's PIR body references B
2. Kahn's algorithm sorts dependencies first
3. Self-recursive methods (detected via `PirHelpers.containsVarRef()`) use `LetRec` instead of `Let`

---

# Level 5: Execution and Testing

## 22. The Virtual Machine

### SPI Architecture

The VM uses Java's `ServiceLoader` pattern for pluggable backends:

```java
// SPI interface
public interface JulcVmProvider {
    EvalResult evaluate(Program program, PlutusLanguage language,
                        ExBudget budget, PlutusData... args);
    int priority();  // higher = preferred
}

// Facade (auto-discovers best provider)
JulcVm vm = new JulcVm();
EvalResult result = vm.evaluate(program, scriptContext);
```

### Available Backends

| Backend | Module | Priority | Status |
|---------|--------|----------|--------|
| Scalus | `julc-vm-scalus` | 50 | Default, wraps Scalus 0.16.0 (Scala) |
| Pure Java | `julc-vm-java` | — | In development |
| Truffle | `julc-vm-truffle` | — | Experimental (GraalVM) |

### EvalResult

```java
sealed interface EvalResult {
    record Success(Term result, ExBudget budget)
    record Failure(String message)
    record BudgetExhausted(ExBudget consumed)
}
```

---

## 23. The Testing Framework

### ValidatorTest Base Class

`julc-testkit` provides `ValidatorTest` for JUnit-based testing:

```java
class MyValidatorTest extends ValidatorTest {
    @Test
    void testValidator() {
        // Compile
        var result = compile("MyValidator.java source...");
        assertNotNull(result.program());

        // Evaluate
        var evalResult = evaluate(result.program(), testScriptContext);
        assertTrue(evalResult instanceof EvalResult.Success);
    }
}
```

### Test Pyramid

```
┌─────────────────────────────┐
│     E2E Tests               │  julc-e2e-tests (CIP-113, on-chain deploy)
│     (Yaci DevKit)           │  Requires external devnet
├─────────────────────────────┤
│   Integration Tests         │  julc-compiler tests (compile + evaluate)
│   (Compile → Evaluate)      │  3,442+ tests, 0 failures
├─────────────────────────────┤
│     Unit Tests              │  Individual component tests
│  (TypeResolver, SymbolTable,│  (PirHelpers, UplcOptimizer, etc.)
│   SubsetValidator, etc.)    │
└─────────────────────────────┘
```

### Standard Test Pattern

```java
@Test
void testFeature() {
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

    // Compile and evaluate in one step
    compileAndAssertTrue(source);
}
```

### Golden File Testing

`GoldenUplcTest` captures compiled UPLC hex for 8 representative validators. After any refactoring, golden files must match byte-for-byte — ensuring that internal restructuring produces identical output.

---

## 24. End-to-End: A Validator's Journey

Let's trace a simple validator through the entire pipeline:

### Source

```java
@SpendingValidator
public class AlwaysSucceeds {
    @Entrypoint
    public static boolean validate(PlutusData redeemer, PlutusData scriptContext) {
        int x = 5;
        int y = x + 3;
        return y == 8;
    }
}
```

### Step 1: Parse → Java AST

JavaParser produces a `CompilationUnit` with one class declaration containing one method with three statements.

### Step 2: Validate

SubsetValidator walks the AST. No rejected constructs.

### Step 3: Type Registration

No user-defined types. LedgerTypeRegistry pre-registers `ScriptContext`, `TxInfo`, etc.

### Step 4: PIR Generation

```
Lam("redeemer", DataType,
  Lam("scriptContext", DataType,
    Let("x", Const(Integer(5)),
      Let("y", App(App(Builtin(AddInteger), Var("x", IntegerType)), Const(Integer(3))),
        App(App(Builtin(EqualsInteger), Var("y", IntegerType)), Const(Integer(8)))))))
```

### Step 5: Validator Wrapping

Adds ScriptContext decoding and bool→unit/error:

```
Lam("__scriptContextData", DataType,
  Let("__ctxFields", SndPair(UnConstrData(Var("__scriptContextData"))),
    Let("__redeemer", HeadList(TailList(Var("__ctxFields"))),
      Let("__result",
        App(App(entrypoint, Var("__redeemer")), Var("__scriptContextData")),
        IfThenElse(Var("__result"), Const(Unit), Error)))))
```

### Step 6: UPLC Generation

`Let` → `Apply(Lam(...), ...)`, variables → De Bruijn indices, `IfThenElse` → `Force/Delay`.

### Step 7: Optimization

- **Constant folding:** `AddInteger(5, 3)` → `8`
- **Constant folding:** `EqualsInteger(8, 8)` → `True`
- **Beta reduction:** inline single-use variables
- **Dead code elimination:** remove unused bindings
- **Force/Delay cancellation:** `Force(Delay(True))` → `True`

After optimization, the validator becomes equivalent to `\ctx -> Unit` (always succeeds).

### Step 8: Serialization

`Program.plutusV3(optimizedTerm)` → FLAT encoding → CBOR wrapping → hex string ready for on-chain submission.

---

# Appendices

## Appendix A: File Index

### julc-compiler (36 files)

**Top-level:**

| File | Lines | Role |
|------|-------|------|
| `JulcCompiler.java` | 1,221 | Main pipeline orchestrator — 24-phase compilation |
| `LibraryCompiler.java` | 138 | Library compilation sub-pipeline |
| `CompileResult.java` | — | Compilation result (program + diagnostics + params) |
| `CompilerException.java` | — | Fatal compiler error |
| `CompilerOptions.java` | — | Compilation options |
| `LibrarySourceResolver.java` | — | Classpath scanning + transitive BFS library resolution |

**pir/ — PIR generation subsystem:**

| File | Lines | Role |
|------|-------|------|
| `PirGenerator.java` | 2,147 | Core Java AST → PIR transformer |
| `LoopBodyGenerator.java` | 531 | Loop body compilation (5 paths × break/no-break) |
| `AccumulatorTypeAnalyzer.java` | 432 | Accumulator type analysis (pair list detection) |
| `TypeInferenceHelper.java` | 282 | Read-only type inference |
| `TypeMethodRegistry.java` | 905 | Instance method dispatch (~50 methods across 11 types) |
| `PirHelpers.java` | 356 | wrapDecode/wrapEncode + list utilities |
| `PirHofBuilders.java` | 244 | HOF PIR builders (map, filter, any, all, find, foldl, zip) |
| `PirTerm.java` | — | PIR term AST (12 variants) |
| `PirType.java` | — | PIR type system (13+ variants) |
| `PirFormatter.java` | — | PIR pretty-printing |
| `PirSubstitution.java` | — | PIR variable substitution |
| `StdlibLookup.java` | — | Functional interface for stdlib resolution |
| `CompositeStdlibLookup.java` | — | Chains multiple StdlibLookup instances |

**resolve/ — Type resolution:**

| File | Role |
|------|------|
| `TypeResolver.java` | Java → PIR type mapping |
| `TypeRegistrar.java` | Topological type registration (Kahn's algorithm) |
| `SymbolTable.java` | Scope stack for variable/method management |
| `LedgerSourceLoader.java` | Dynamic ledger type loading from META-INF |
| `LibraryMethodRegistry.java` | Compiled library method storage + typed coercion |
| `ImportResolver.java` | Import resolution |

**Other packages:**

| File | Role |
|------|------|
| `codegen/ValidatorWrapper.java` | ScriptContext decoding + bool→unit/error wrapping |
| `codegen/DataCodecGenerator.java` | Data codec generation |
| `desugar/LoopDesugarer.java` | For-each/while → LetRec transformation |
| `desugar/PatternMatchDesugarer.java` | Switch/instanceof → DataMatch transformation |
| `error/CompilerDiagnostic.java` | Diagnostic record (level, message, location) |
| `error/DiagnosticCollector.java` | Structured error collection |
| `uplc/UplcGenerator.java` | PIR → UPLC lowering |
| `uplc/UplcOptimizer.java` | 6-pass UPLC optimizer with fixpoint iteration |
| `validate/SubsetValidator.java` | Java subset enforcement |
| `util/MethodDependencyResolver.java` | Method dependency graph construction |
| `util/StringUtils.java` | String utilities (Levenshtein distance) |

### Other Key Files

| File | Role |
|------|------|
| `julc-core/.../Term.java` | UPLC term AST (10 variants) |
| `julc-core/.../DefaultFun.java` | 102 Plutus builtin functions |
| `julc-core/.../PlutusData.java` | Universal on-chain data encoding |
| `julc-stdlib/.../StdlibRegistry.java` | PIR term builders for ~65 stdlib methods |
| `julc-vm/.../JulcVmProvider.java` | VM SPI interface |
| `julc-testkit/.../ValidatorTest.java` | Testing base class |

---

## Appendix B: Glossary

| Term | Definition |
|------|------------|
| **UPLC** | Untyped Plutus Lambda Calculus — the on-chain execution language |
| **PIR** | Plutus Intermediate Representation — typed bridge between Java and UPLC |
| **De Bruijn index** | Variable indexing where variables reference lambda binders by distance (1 = innermost) |
| **Data** | Universal on-chain value encoding (5 constructors: Constr, Map, List, I, B) |
| **CEK machine** | Count-Evaluate-Kont — the Plutus virtual machine |
| **ScriptContext** | Ledger data passed to every validator (TxInfo + redeemer + script info) |
| **eUTxO** | Extended Unspent Transaction Output — Cardano's accounting model |
| **Force/Delay** | UPLC constructs for lazy evaluation (needed because UPLC is strict/call-by-value) |
| **Z-combinator** | Strict fixed-point combinator enabling recursion in UPLC |
| **wrapDecode** | `PirHelpers` method to extract a typed value from raw Data |
| **wrapEncode** | `PirHelpers` method to wrap a typed value back into Data |
| **SumType** | Tagged union — a sealed interface with record variants |
| **RecordType** | Product type — a record with named typed fields |
| **LetRec** | Recursive let binding — used for loops and self-recursive functions |
| **Accumulator** | Variable modified across loop iterations (packed into Data tuples for multi-acc) |
| **Kahn's algorithm** | Topological sort used for type registration and library ordering |
| **StdlibLookup** | Interface for resolving static method calls to PIR terms |
| **TypeMethodRegistry** | Registry mapping (PirType, method) pairs to instance method handlers |
| **@OnchainLibrary** | Annotation marking a class as a reusable on-chain library |
| **@Param** | Annotation marking a validator field as a deployment-time parameter |
| **@Entrypoint** | Annotation marking the main validator method |
| **FLAT** | Binary encoding format for UPLC programs on-chain |
| **SOPs** | Sums of Products — Plutus V3 constructor/case terms |
| **Golden test** | Test that compares output byte-for-byte against a saved reference file |
| **Conway era** | Current Cardano era with governance features (CIP-1694) |
