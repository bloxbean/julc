# ADR-001: Plutus-Java Architecture

**Status**: Accepted
**Date**: 2026-02-06
**Authors**: BloxBean Team

---

## Context

Java is one of the most widely used programming languages, yet Java developers have no native path to writing Cardano Plutus smart contracts. Existing solutions target Haskell (PlutusTx), Python (Opshin), Scala (Scalus), TypeScript (Pebble), and Rust (Aiken) — but not Java.

## Decision

Build two things:
1. A **Plutus VM (CEK machine)** in Java that can evaluate any UPLC program for Cardano
2. A **Java-to-UPLC compiler** that lets Java developers write smart contracts using regular Java syntax — records, if/else, loops, annotations — and compiles them to Plutus scripts

**Approach**: Java subset compiler (like Opshin's approach for Python). Developers write valid Java 25 code with annotations. The compiler analyzes the source and compiles a restricted subset to UPLC. Target: Plutus V3 (Conway era). First-class integration with cardano-client-lib 0.7.1.

**Reference implementations studied**: Scalus (Scala), Opshin (Python), Pebble (TypeScript)

**Scalus reference code**: `/Users/satya/work/cardano-comm-projects/scalus`

### Key Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Contract authoring | Java subset compiler | Most natural for Java devs (like Opshin for Python) |
| Java version | Java 25 | Records, sealed classes, pattern matching, string templates |
| Plutus version | V3 only (Conway era) | Latest, covers all current needs, V1/V2 are legacy |
| VM strategy | Pluggable SPI: Scalus first, pure Java later | Ship fast with proven VM, swap later without API changes |
| cardano-client-lib | First-class integration (v0.7.1) | Seamless on-chain/off-chain development |
| CBOR library | cbor-java (co.nstant.in:cbor) | Lightweight, well-maintained |
| Java parser | JavaParser (javaparser-core) | Mature AST library for Java source analysis |

---

## Module Structure

```
plutus-java/
├── settings.gradle
├── build.gradle                        # Root build config
├── plutus-core/                        # UPLC AST + serialization (pure Java)
│   └── com.bloxbean.cardano.plutus.core
├── plutus-vm/                          # VM API + SPI interface (pure Java, no Scala)
│   └── com.bloxbean.cardano.plutus.vm
├── plutus-vm-scalus/                   # Scalus VM backend (wraps Scalus, has Scala dep)
│   └── com.bloxbean.cardano.plutus.vm.scalus
├── plutus-vm-java/                     # Pure Java CEK machine (built later, no Scala)
│   └── com.bloxbean.cardano.plutus.vm.java
├── plutus-ledger-api/                  # Cardano ledger types (ScriptContext, TxInfo, etc.)
│   └── com.bloxbean.cardano.plutus.ledger
├── plutus-compiler/                    # Java source → UPLC compiler
│   └── com.bloxbean.cardano.plutus.compiler
├── plutus-stdlib/                      # On-chain standard library
│   └── com.bloxbean.cardano.plutus.stdlib
├── plutus-testkit/                     # Testing framework for validators
│   └── com.bloxbean.cardano.plutus.testkit
├── plutus-cardano-client-lib/          # Integration with cardano-client-lib
│   └── com.bloxbean.cardano.plutus.client
├── plutus-gradle-plugin/               # Build-time compilation Gradle plugin
│   └── com.bloxbean.cardano.plutus.gradle
└── plutus-examples/                    # Example smart contracts
    └── com.bloxbean.cardano.plutus.examples
```

### Module Dependency Graph

```
plutus-core  (foundation — zero external deps except cbor-java)
    ↑
plutus-vm  (API + SPI — depends only on plutus-core, no Scala)
    ↑
    ├── plutus-vm-scalus  (Scalus backend — depends on org.scalus:scalus)
    └── plutus-vm-java    (Pure Java backend — BouncyCastle for crypto)
    ↑
plutus-ledger-api  (depends on plutus-core)
    ↑
plutus-compiler  (depends on plutus-core, plutus-ledger-api)
    ↑
plutus-stdlib  (depends on plutus-ledger-api, compiled by plutus-compiler)
    ↑
plutus-testkit  (depends on plutus-vm, plutus-compiler, plutus-ledger-api)
    ↑
plutus-cardano-client-lib  (depends on plutus-core, plutus-vm, cardano-client-lib)
    ↑
plutus-gradle-plugin  (depends on plutus-compiler)
    ↑
plutus-examples  (depends on all above)
```

---

## Phase 1: UPLC Core (`plutus-core`)

The foundation. Represents, parses, and serializes UPLC programs.

### 1.1 UPLC Term AST

Use Java 25 sealed interfaces and records for a type-safe, pattern-matchable AST:

```java
package com.bloxbean.cardano.plutus.core.term;

public sealed interface Term {
    record Var(DeBruijn index)                          implements Term {}
    record Lam(String name, Term body)                  implements Term {}
    record App(Term function, Term argument)             implements Term {}
    record Force(Term term)                              implements Term {}
    record Delay(Term term)                              implements Term {}
    record Const(Constant value)                         implements Term {}
    record Builtin(DefaultFun fun)                       implements Term {}
    record Error()                                       implements Term {}
    // Plutus V3 (SOPs - Sums of Products)
    record Constr(int tag, List<Term> fields)            implements Term {}
    record Case(Term scrutinee, List<Term> branches)     implements Term {}
}
```

### 1.2 Constants & Types (DefaultUni)

```java
public sealed interface Constant {
    record IntegerConst(BigInteger value)    implements Constant {}
    record ByteStringConst(byte[] value)     implements Constant {}
    record StringConst(String value)         implements Constant {}
    record UnitConst()                       implements Constant {}
    record BoolConst(boolean value)          implements Constant {}
    record DataConst(PlutusData value)       implements Constant {}
    record ListConst(Type elemType, List<Constant> values) implements Constant {}
    record PairConst(Constant first, Constant second)      implements Constant {}
    // BLS12-381 types
    record Bls12_381_G1(byte[] point)        implements Constant {}
    record Bls12_381_G2(byte[] point)        implements Constant {}
    record Bls12_381_MlResult(byte[] result) implements Constant {}
}
```

### 1.3 DefaultFun (Built-in Function Enum)

All ~90 Plutus V3 built-in functions as a Java enum:

```java
public enum DefaultFun {
    // Integer
    AddInteger, SubtractInteger, MultiplyInteger, DivideInteger,
    ModInteger, QuotientInteger, RemainderInteger, EqualsInteger,
    LessThanInteger, LessThanEqualsInteger,
    // ByteString
    AppendByteString, ConsByteString, SliceByteString, LengthOfByteString,
    IndexByteString, EqualsByteString, LessThanByteString, LessThanEqualsByteString,
    // Crypto
    Sha2_256, Sha3_256, Blake2b_256, Blake2b_224,
    Keccak_256, Ripemd_160,
    VerifyEd25519Signature, VerifyEcdsaSecp256k1Signature, VerifySchnorrSecp256k1Signature,
    // BLS12-381
    Bls12_381_G1_add, Bls12_381_G1_neg, Bls12_381_G1_scalarMul,
    Bls12_381_G1_equal, Bls12_381_G1_hashToGroup, Bls12_381_G1_compress,
    Bls12_381_G1_uncompress,
    Bls12_381_G2_add, Bls12_381_G2_neg, Bls12_381_G2_scalarMul,
    Bls12_381_G2_equal, Bls12_381_G2_hashToGroup, Bls12_381_G2_compress,
    Bls12_381_G2_uncompress,
    Bls12_381_millerLoop, Bls12_381_mulMlResult, Bls12_381_finalVerify,
    // String
    AppendString, EqualsString, EncodeUtf8, DecodeUtf8,
    // Data
    ConstrData, MapData, ListData, IData, BData, UnConstrData,
    UnMapData, UnListData, UnIData, UnBData,
    ChooseData, EqualsData, SerialiseData,
    MkPairData, MkNilData, MkNilPairData,
    // List
    MkCons, HeadList, TailList, NullList, ChooseList,
    // Pair
    FstPair, SndPair,
    // Control
    IfThenElse, ChooseUnit, Trace,
    // Bitwise (V3)
    IntegerToByteString, ByteStringToInteger,
    AndByteString, OrByteString, XorByteString, ComplementByteString,
    ReadBit, WriteBits, ReplicateByte, ShiftByteString, RotateByteString,
    CountSetBits, FindFirstSetBit,
    // Misc
    CaseList, CaseData;
}
```

### 1.4 Serialization

- **FLAT encoding/decoding**: Cardano's compact binary format for on-chain scripts. Bit-level encoding. Custom implementation (no existing Java library).
- **CBOR serialization**: For PlutusData and script wrapping (double CBOR encoding for Cardano). Using `cbor-java`.
- **Text format parser/printer**: Human-readable UPLC text format for debugging.

Key classes:
```
com.bloxbean.cardano.plutus.core.flat.FlatEncoder
com.bloxbean.cardano.plutus.core.flat.FlatDecoder
com.bloxbean.cardano.plutus.core.cbor.CborSerializer
com.bloxbean.cardano.plutus.core.text.UplcParser       // Parse UPLC text format
com.bloxbean.cardano.plutus.core.text.UplcPrinter       // Pretty-print UPLC
com.bloxbean.cardano.plutus.core.PlutusScript            // Versioned script wrapper (V3)
```

### 1.5 PlutusData

```java
public sealed interface PlutusData {
    record Constr(int tag, List<PlutusData> fields) implements PlutusData {}
    record MapData(List<Pair<PlutusData, PlutusData>> entries) implements PlutusData {}
    record ListData(List<PlutusData> items) implements PlutusData {}
    record IntData(BigInteger value) implements PlutusData {}
    record BytesData(byte[] value) implements PlutusData {}
}
```

---

## Phase 2: Plutus VM (`plutus-vm`) — Pluggable Architecture

The VM is designed with a **pluggable provider pattern (SPI)** so we can start with Scalus as the VM backend and swap in a pure Java CEK machine later — at runtime, without changing any user-facing API.

### 2.1 Pluggable VM Architecture

```
plutus-vm/                              # API module (pure Java, no Scala)
├── com.bloxbean.cardano.plutus.vm
│   ├── PlutusVm.java                  # Main facade — what users call
│   ├── PlutusVmProvider.java          # SPI interface — what backends implement
│   ├── EvalResult.java                # Result types
│   └── ExBudget.java                  # Cost tracking
│
plutus-vm-scalus/                       # Scalus backend (depends on Scalus)
├── com.bloxbean.cardano.plutus.vm.scalus
│   └── ScalusVmProvider.java          # Adapts Scalus PlutusVM to our SPI
│
plutus-vm-java/                         # Pure Java backend (built later)
├── com.bloxbean.cardano.plutus.vm.java
│   ├── JavaCekMachine.java            # Pure Java CEK machine
│   ├── JavaVmProvider.java            # Adapts our CEK machine to SPI
│   ├── BuiltinRuntime.java            # Built-in function implementations
│   └── CostModel.java                 # Cost model parameters
```

### 2.2 SPI Interface

```java
package com.bloxbean.cardano.plutus.vm;

/**
 * Service Provider Interface for Plutus VM backends.
 * Implementations are discovered via ServiceLoader.
 */
public interface PlutusVmProvider {

    /** Evaluate a UPLC term with the given budget. */
    EvalResult evaluate(Term term, ExBudget initialBudget);

    /** Evaluate a serialized Plutus script (FLAT-encoded). */
    EvalResult evaluateScript(byte[] flatEncodedScript, PlutusData scriptArg, ExBudget budget);

    /** Return the provider name (for logging/debugging). */
    String name();

    /** Priority: higher = preferred. JavaCekMachine returns 100, Scalus returns 50. */
    int priority();
}
```

### 2.3 PlutusVm Facade (What Users Call)

```java
package com.bloxbean.cardano.plutus.vm;

public final class PlutusVm {

    private final PlutusVmProvider provider;

    /** Auto-detect best available provider via ServiceLoader (highest priority wins). */
    public static PlutusVm create() {
        return ServiceLoader.load(PlutusVmProvider.class)
            .stream()
            .map(ServiceLoader.Provider::get)
            .max(Comparator.comparingInt(PlutusVmProvider::priority))
            .map(PlutusVm::new)
            .orElseThrow(() -> new IllegalStateException(
                "No PlutusVmProvider found. Add plutus-vm-scalus or plutus-vm-java to classpath."));
    }

    /** Explicitly choose a provider. */
    public static PlutusVm withProvider(PlutusVmProvider provider) {
        return new PlutusVm(provider);
    }

    public EvalResult evaluate(Term term, ExBudget budget) {
        return provider.evaluate(term, budget);
    }

    public EvalResult evaluateScript(byte[] script, PlutusData arg, ExBudget budget) {
        return provider.evaluateScript(script, arg, budget);
    }

    public String providerName() { return provider.name(); }
}
```

### 2.4 Scalus Backend (Initial Implementation)

```java
package com.bloxbean.cardano.plutus.vm.scalus;

public class ScalusVmProvider implements PlutusVmProvider {

    @Override
    public EvalResult evaluate(Term term, ExBudget budget) {
        // 1. Convert our Term → Scalus Term
        scalus.uplc.Term scalusTerm = TermConverter.toScalus(term);
        // 2. Call Scalus PlutusVM
        // 3. Convert Scalus result → our EvalResult
    }

    @Override public String name() { return "Scalus"; }
    @Override public int priority() { return 50; }
}
```

**ServiceLoader registration** (`META-INF/services/com.bloxbean.cardano.plutus.vm.PlutusVmProvider`):
```
com.bloxbean.cardano.plutus.vm.scalus.ScalusVmProvider
```

### 2.5 Pure Java Backend (Built Later — Milestone 6)

When we build the pure Java CEK machine, it registers with higher priority:

```java
public class JavaVmProvider implements PlutusVmProvider {
    @Override public String name() { return "Java-CEK"; }
    @Override public int priority() { return 100; }  // Higher than Scalus → auto-selected
}
```

Users who add `plutus-vm-java` to their classpath automatically get the pure Java VM.
Users with only `plutus-vm-scalus` get the Scalus backend. **No code changes needed.**

### 2.6 Runtime Swap Behavior

```groovy
// Gradle — use Scalus backend
dependencies {
    implementation 'com.bloxbean.cardano:plutus-vm'
    runtimeOnly 'com.bloxbean.cardano:plutus-vm-scalus'
}

// Later — swap to pure Java (just change one line)
dependencies {
    implementation 'com.bloxbean.cardano:plutus-vm'
    runtimeOnly 'com.bloxbean.cardano:plutus-vm-java'
}

// Or use both (Java auto-selected due to higher priority, Scalus as fallback)
dependencies {
    implementation 'com.bloxbean.cardano:plutus-vm'
    runtimeOnly 'com.bloxbean.cardano:plutus-vm-scalus'
    runtimeOnly 'com.bloxbean.cardano:plutus-vm-java'
}
```

### 2.7 EvalResult & ExBudget (in plutus-vm API module)

```java
public record ExBudget(long cpuSteps, long memoryUnits) {
    public ExBudget add(ExBudget other) { ... }
    public boolean isExhausted() { ... }
}

public sealed interface EvalResult {
    record Success(CekValue value, ExBudget consumed, List<String> traces) implements EvalResult {}
    record Failure(String error, ExBudget consumed, List<String> traces)    implements EvalResult {}
    record BudgetExhausted(ExBudget consumed, List<String> traces)         implements EvalResult {}

    default boolean isSuccess() { return this instanceof Success; }
    default ExBudget budgetConsumed() {
        return switch (this) {
            case Success s -> s.consumed();
            case Failure f -> f.consumed();
            case BudgetExhausted b -> b.consumed();
        };
    }
}
```

### 2.8 Pure Java CEK Machine Design (for `plutus-vm-java`, built in Milestone 6)

When we build the pure Java implementation, the internal architecture will be:

**Machine states** (sealed interface):
```java
sealed interface CekState {
    record Compute(CekEnv env, Term term, CekStack stack)     implements CekState {}
    record Return(CekStack stack, CekValue value)              implements CekState {}
    record Done(CekValue value)                                implements CekState {}
    record CekError(String message)                            implements CekState {}
}
```

**Continuation frames**:
```java
sealed interface CekFrame {
    record FrameApplyFun(CekValue fun)                    implements CekFrame {}
    record FrameApplyArg(CekEnv env, Term arg)            implements CekFrame {}
    record FrameForce()                                    implements CekFrame {}
    record FrameConstr(CekEnv env, int tag,
                       List<CekValue> evaluated,
                       List<Term> remaining)               implements CekFrame {}
    record FrameCase(List<Term> branches)                  implements CekFrame {}
}
```

---

## Phase 3: Ledger API (`plutus-ledger-api`)

Cardano ledger types that smart contracts interact with. These are the "ScriptContext" types passed to validators.

### 3.1 Core Ledger Types

All as Java records implementing `PlutusData` serialization:

```java
// Script purpose
public sealed interface ScriptPurpose {
    record Minting(PolicyId policyId)                              implements ScriptPurpose {}
    record Spending(TxOutRef ref)                                  implements ScriptPurpose {}
    record Rewarding(Credential credential)                        implements ScriptPurpose {}
    record Certifying(int index, TxCert cert)                      implements ScriptPurpose {}
    record Voting(Voter voter)                                     implements ScriptPurpose {}
    record Proposing(int index, ProposalProcedure proposal)        implements ScriptPurpose {}
}

// V3 ScriptContext
public record ScriptContext(
    TxInfo txInfo,
    Redeemer redeemer,
    ScriptInfo scriptInfo
) { ... }

// Transaction info
public record TxInfo(
    List<TxInInfo> inputs,
    List<TxInInfo> referenceInputs,
    List<TxOut> outputs,
    Value fee,
    Value mint,
    List<TxCert> certificates,
    Map<Credential, Lovelace> withdrawals,
    POSIXTimeRange validRange,
    List<PubKeyHash> signatories,
    Map<ScriptPurpose, Redeemer> redeemers,
    Map<DatumHash, Datum> datums,
    TxId id,
    Map<Voter, Map<GovernanceActionId, Vote>> votes,
    List<ProposalProcedure> proposalProcedures,
    Optional<Lovelace> currentTreasuryAmount,
    Optional<Lovelace> treasuryDonation
) { ... }

// Value (multi-asset)
public record Value(Map<PolicyId, Map<AssetName, BigInteger>> inner) { ... }

// Address types
public record PubKeyHash(byte[] hash) { ... }
public record ScriptHash(byte[] hash) { ... }
public record TxId(byte[] hash) { ... }
public record TxOutRef(TxId txId, int index) { ... }
// ... etc
```

### 3.2 PlutusData Codec

Automatic conversion between Java records and PlutusData:

```java
public interface PlutusDataCodec<T> {
    PlutusData toData(T value);
    T fromData(PlutusData data);
}
```

Use code generation or reflection to auto-derive codecs for records.

---

## Phase 4: Java-to-UPLC Compiler (`plutus-compiler`)

The heart of the developer experience. Compiles a restricted Java subset to UPLC.

### 4.1 Supported Java Subset

**Supported constructs**:
| Java Feature | Maps To |
|---|---|
| `record` with `implements PlutusData` | Plutus Constr (data constructor) |
| `sealed interface` | Sum type (tagged union) |
| `int`, `long`, `BigInteger` | Plutus Integer |
| `byte[]`, `ByteString` | Plutus ByteString |
| `String` | Plutus String |
| `boolean` | Plutus Bool |
| `void` / `Unit` | Plutus Unit |
| `List<T>` | Plutus BuiltinList |
| `Map<K,V>` | Plutus BuiltinList of Pairs |
| `Optional<T>` | Maybe (Constr 0/1) |
| `if/else` | IfThenElse / Case |
| `switch` expression | Case |
| `pattern matching (instanceof)` | Case + Constr destructuring |
| `for-each` loop | Recursive fold |
| `while` loop | Recursive function |
| Local variables (`var`, `final`) | Let bindings (lambda application) |
| Methods | Lambda abstractions |
| Lambda expressions | Lambda abstractions |
| Arithmetic operators | Plutus Integer builtins |
| Comparison operators | Plutus comparison builtins |
| Boolean operators (`&&`, `\|\|`, `!`) | Short-circuit via IfThenElse |
| Method calls on builtins | Plutus builtin applications |
| `==` on data types | EqualsData |

**Not supported** (compile-time error):
- Mutable fields / `var` reassignment (no mutation on-chain)
- `try/catch/finally` (no exceptions on-chain)
- `null` (use `Optional`)
- Threads / concurrency
- Reflection
- IO / System calls
- Inheritance (use sealed interfaces instead)
- Generics (limited — only `List<T>`, `Map<K,V>`, `Optional<T>`)
- `new` on non-record classes
- Static mutable state
- Floating point (`float`, `double`)
- Arrays (use `List` instead)

### 4.2 Contract Annotations

```java
@Target(ElementType.TYPE)
public @interface Validator {}          // Marks a spending validator

@Target(ElementType.TYPE)
public @interface MintingPolicy {}      // Marks a minting policy

@Target(ElementType.TYPE)
public @interface StakeValidator {}     // Marks a stake validator

@Target(ElementType.METHOD)
public @interface Entrypoint {}         // The main validation method

@Target(ElementType.TYPE)
public @interface Datum {}              // Marks a record as datum type

@Target(ElementType.TYPE)
public @interface Redeemer {}           // Marks a record as redeemer type
```

### 4.3 Example: What a Java Developer Writes

```java
package com.example.contracts;

import com.bloxbean.cardano.plutus.stdlib.*;
import com.bloxbean.cardano.plutus.ledger.*;

@Validator
public class VestingValidator {

    record VestingDatum(PubKeyHash beneficiary, POSIXTime deadline) implements PlutusData {}

    @Entrypoint
    public static boolean validate(VestingDatum datum, Unit redeemer, ScriptContext ctx) {
        var txInfo = ctx.txInfo();

        boolean signedByBeneficiary = txInfo.signatories().contains(datum.beneficiary());
        boolean deadlinePassed = Interval.contains(txInfo.validRange(), datum.deadline());

        return signedByBeneficiary && deadlinePassed;
    }
}
```

### 4.4 Compiler Pipeline

```
                   ┌─────────────────────────────────────────────┐
                   │            Java Source (.java)               │
                   └─────────────────┬───────────────────────────┘
                                     │
                          ┌──────────▼──────────┐
                   Step 1 │   JavaParser Parse   │  (external lib: javaparser)
                          └──────────┬──────────┘
                                     │
                          ┌──────────▼──────────┐
                   Step 2 │  Subset Validation   │  reject unsupported constructs
                          └──────────┬──────────┘
                                     │
                          ┌──────────▼──────────┐
                   Step 3 │  Type Resolution     │  resolve all types to Plutus types
                          └──────────┬──────────┘
                                     │
                          ┌──────────▼──────────┐
                   Step 4 │  Desugaring          │  loops→recursion, operators→builtins,
                          │                      │  pattern match→case, &&/||→IfThenElse
                          └──────────┬──────────┘
                                     │
                          ┌──────────▼──────────┐
                   Step 5 │  PIR Generation      │  emit Plutus Intermediate Repr
                          └──────────┬──────────┘
                                     │
                          ┌──────────▼──────────┐
                   Step 6 │  PIR → UPLC Lowering │  erase types, compile let-bindings
                          └──────────┬──────────┘
                                     │
                          ┌──────────▼──────────┐
                   Step 7 │  UPLC Optimization   │  dead code elim, constant folding,
                          │                      │  eta reduction, inlining
                          └──────────┬──────────┘
                                     │
                          ┌──────────▼──────────┐
                   Step 8 │  FLAT Serialization  │  → .plutus script file
                          └──────────┬──────────┘
                                     │
                   ┌─────────────────▼───────────────────────────┐
                   │     Compiled Plutus Script (CBOR-wrapped)   │
                   └─────────────────────────────────────────────┘
```

### 4.5 Key Compiler Components

```
com.bloxbean.cardano.plutus.compiler
├── parse/
│   └── SourceParser.java            // JavaParser integration, produces Java AST
├── validate/
│   └── SubsetValidator.java         // Reject unsupported Java features
├── resolve/
│   ├── TypeResolver.java            // Map Java types → Plutus types
│   └── SymbolTable.java             // Track variable/method declarations
├── desugar/
│   ├── LoopDesugarer.java           // for/while → recursive functions
│   ├── OperatorDesugarer.java       // +,-,*,/ → builtin calls
│   ├── PatternMatchDesugarer.java   // instanceof/switch → Case terms
│   ├── BooleanDesugarer.java        // &&, || → nested IfThenElse
│   └── LetBindingDesugarer.java     // var x = e → (\x -> ...) e
├── pir/
│   ├── PirTerm.java                 // Typed intermediate representation
│   ├── PirGenerator.java            // Java AST → PIR
│   └── PirOptimizer.java            // PIR-level optimizations
├── uplc/
│   ├── UplcGenerator.java           // PIR → UPLC (type erasure)
│   └── UplcOptimizer.java           // UPLC-level optimizations
├── codegen/
│   ├── DataCodecGenerator.java      // Generate PlutusData encode/decode for records
│   └── ValidatorWrapper.java        // Wrap entrypoint for on-chain (apply args from Data)
├── error/
│   └── CompilerDiagnostic.java      // Error messages with source locations
└── PlutusCompiler.java              // Main compiler facade
```

### 4.6 PIR (Plutus Intermediate Representation)

A typed lambda calculus between Java and UPLC:

```java
public sealed interface PirTerm {
    record Var(String name, PirType type)                                    implements PirTerm {}
    record Let(String name, PirTerm value, PirTerm body)                     implements PirTerm {}
    record LetRec(List<Binding> bindings, PirTerm body)                      implements PirTerm {}
    record Lam(String param, PirType paramType, PirTerm body)                implements PirTerm {}
    record App(PirTerm function, PirTerm argument)                           implements PirTerm {}
    record Const(Constant value)                                             implements PirTerm {}
    record Builtin(DefaultFun fun)                                           implements PirTerm {}
    record IfThenElse(PirTerm cond, PirTerm thenBranch, PirTerm elseBranch)  implements PirTerm {}
    record DataConstr(int tag, PirType dataType, List<PirTerm> fields)       implements PirTerm {}
    record DataMatch(PirTerm scrutinee, List<MatchBranch> branches)          implements PirTerm {}
    record Error(PirType type)                                               implements PirTerm {}
    record Trace(PirTerm message, PirTerm body)                              implements PirTerm {}
}
```

### 4.7 Validator Wrapping

On-chain, a V3 validator receives a single argument: `ScriptContext` as `Data`. The compiler wraps the user's entrypoint:

```
Generated UPLC structure:
  \scriptContextData ->
    let ctx = decodeScriptContext(scriptContextData)
    let datum = decodeDatum(lookupDatum(ctx))     -- for spending
    let redeemer = decodeRedeemer(lookupRedeemer(ctx))
    in
      if validate(datum, redeemer, ctx)
        then ()        -- success
        else ERROR      -- validation failure
```

---

## Phase 5: Standard Library (`plutus-stdlib`)

On-chain utility functions that developers import. These compile to UPLC along with the validator.

```java
package com.bloxbean.cardano.plutus.stdlib;

// Value operations
public class Values {
    public static boolean geq(Value a, Value b) { ... }
    public static Value merge(Value a, Value b) { ... }
    public static BigInteger lovelaceOf(Value v) { ... }
    public static BigInteger assetOf(Value v, PolicyId policy, AssetName name) { ... }
}

// Interval operations
public class Interval {
    public static boolean contains(POSIXTimeRange range, POSIXTime time) { ... }
    public static boolean overlaps(POSIXTimeRange a, POSIXTimeRange b) { ... }
    public static POSIXTimeRange after(POSIXTime time) { ... }
    public static POSIXTimeRange before(POSIXTime time) { ... }
}

// Crypto utilities
public class Crypto {
    public static ByteString sha2_256(ByteString data) { ... }
    public static ByteString blake2b_256(ByteString data) { ... }
    public static boolean verifySignature(PubKeyHash pkh, ByteString msg, ByteString sig) { ... }
}

// List utilities
public class Lists {
    public static <T> boolean any(List<T> list, Predicate<T> pred) { ... }
    public static <T> boolean all(List<T> list, Predicate<T> pred) { ... }
    public static <T> Optional<T> find(List<T> list, Predicate<T> pred) { ... }
    public static <T, R> List<R> map(List<T> list, Function<T, R> f) { ... }
    public static <T> List<T> filter(List<T> list, Predicate<T> pred) { ... }
}

// Context helpers
public class Contexts {
    public static boolean signedBy(TxInfo txInfo, PubKeyHash pkh) { ... }
    public static Optional<TxOut> findOwnInput(ScriptContext ctx) { ... }
    public static Value valueSpent(TxInfo txInfo) { ... }
    public static Value valueProduced(TxInfo txInfo) { ... }
}
```

The stdlib functions are themselves written in the compilable Java subset — so the compiler compiles them inline into the validator UPLC.

---

## Phase 6: Test Kit (`plutus-testkit`)

A testing framework so developers can unit-test validators in JVM before deploying.

```java
// Builder for test ScriptContext
var ctx = ScriptContextBuilder.spending()
    .input(utxo1)
    .input(utxo2)
    .output(txOut1)
    .signer(beneficiaryPkh)
    .validRange(Interval.after(deadline))
    .build();

// Evaluate compiled validator
var result = ValidatorTest.evaluate(
    compiledScript,
    datum,
    redeemer,
    ctx
);

assertTrue(result.isSuccess());
assertEquals(expectedBudget, result.budgetConsumed());
```

Key features:
- `ScriptContextBuilder`: Fluent API to construct test contexts
- `ValidatorTest.evaluate()`: Run compiled UPLC against test data using CEK machine
- Budget assertions: Verify execution cost
- Trace capture: Assert on trace messages for debugging
- Property-based testing integration with jqwik

---

## Phase 7: cardano-client-lib Integration (`plutus-cardano-client-lib`)

Bridge between compiled validators and cardano-client-lib (v0.7.1) for transaction building & submission.

```java
// Compile the validator
PlutusScript script = PlutusCompiler.compile(VestingValidator.class);

// Use with cardano-client-lib to build a transaction
var tx = new Tx()
    .payToContract(script.address(network), datum, amount)
    .from(senderAddress);

var result = quickTxProvider.submitTx(tx);
```

- Share `PlutusData` serialization between on-chain and off-chain
- Compile validators to `PlutusScript` objects compatible with cardano-client-lib
- Script address derivation
- Datum/redeemer encoding for transaction building

---

## Phase 8: Gradle Plugin (`plutus-gradle-plugin`)

Automate compilation as part of the build:

```groovy
plugins {
    id 'com.bloxbean.cardano.plutus' version '1.0'
}

plutus {
    sourceDir = 'src/main/plutus'   // Java files to compile to UPLC
    outputDir = 'build/plutus'       // Output compiled scripts
    traceEnabled = true              // Include trace messages (for testnet)
}
```

- Compile `.java` validator files to `.plutus` scripts at build time
- Generate script hashes and addresses
- Strip traces for mainnet builds (smaller scripts)
- Report script sizes and estimated costs

---

## Implementation Roadmap

### Milestone 1: UPLC Core + VM SPI + Scalus Backend (Phases 1-2)
**Deliverable**: A working UPLC evaluator that passes Plutus conformance tests

1. ~~Set up Gradle multi-module project (settings.gradle, root build.gradle)~~ **DONE**
2. ~~`plutus-core`: UPLC Term AST (sealed interfaces + records) + PlutusData + Constants + DefaultFun + DefaultUni + Program~~ **DONE** (170 tests passing)
3. ~~`plutus-core`: PlutusData sealed interface~~ **DONE** (included in step 2)
4. ~~`plutus-core`: FLAT decoder (parse existing on-chain scripts)~~ **DONE** (306 tests passing)
5. ~~`plutus-core`: FLAT encoder~~ **DONE** (included in step 4)
6. ~~`plutus-core`: CBOR serialization for PlutusData (using cbor-java)~~ **DONE** (340 tests passing)
7. ~~`plutus-core`: UPLC text format parser and printer~~ **DONE** (468 tests passing)
8. ~~`plutus-vm`: PlutusVmProvider SPI interface + PlutusVm facade + EvalResult types~~ **DONE** (32 tests passing)
9. ~~`plutus-vm-scalus`: ScalusVmProvider — Term converter (our Term <-> Scalus Term) + delegation~~ **DONE**
10. ~~`plutus-vm-scalus`: ServiceLoader registration~~ **DONE**
11. ~~Run Plutus conformance test suite via Scalus backend~~ **DONE** (672 passed, 327 skipped, 0 failures)
12. Benchmark evaluation speed

**Milestone 1 test totals**: 468 (plutus-core) + 32 (plutus-vm) + 716 (plutus-vm-scalus) = **1,216 tests, 0 failures**

**Conformance test details** (999 test cases from IntersectMBO/plutus):
- 672 passed (all V3-supported features)
- 327 skipped:
  - ~200 BLS12-381 tests (Scalus 0.15.0 FLAT codec can't encode/decode BLS type tags)
  - ~90 V4 builtin/type tests (dropList, lengthOfArray, array, value, etc. — out of scope for V3)
  - ~15 constant-case tests (Scalus 0.15.0 doesn't support case-on-builtin-types)
- 0 failures

### Milestone 2: Ledger Types (Phase 3)
**Deliverable**: Complete V3 ScriptContext types with PlutusData codecs

1. Core types (PubKeyHash, ScriptHash, TxId, Address, Value, etc.)
2. Transaction types (TxInfo, TxInInfo, TxOut, TxCert)
3. Governance types (Voter, Vote, GovernanceActionId, ProposalProcedure)
4. ScriptContext, ScriptPurpose, ScriptInfo
5. PlutusData codec generation for all types
6. Serialization conformance tests

### Milestone 3: Compiler MVP (Phase 4)
**Deliverable**: Compile simple Java validators to working UPLC

1. JavaParser integration + subset validation
2. Type resolver (Java types -> Plutus types)
3. Simple expression compilation (arithmetic, comparisons, boolean)
4. Record -> PlutusData codec generation
5. If/else -> IfThenElse
6. Method calls -> lambda application
7. Validator wrapping (datum/redeemer/context decoding)
8. PIR -> UPLC lowering
9. End-to-end: compile VestingValidator -> evaluate via PlutusVm -> verify

### Milestone 4: Full Compiler (Phase 4 continued)
**Deliverable**: Complete Java subset support

1. Sealed interface -> sum type compilation
2. Pattern matching (instanceof, switch) -> Case
3. Loop desugaring (for-each, while -> recursion)
4. Lambda expressions
5. Let bindings and scoping
6. UPLC optimizations (dead code, constant folding, eta reduction, inlining)
7. Error diagnostics with source locations
8. Compile more complex contracts (multi-sig, time-lock, NFT minting, DEX)

### Milestone 5: Ecosystem (Phases 5-8)
**Deliverable**: Complete developer toolkit

1. Standard library (Values, Interval, Crypto, Lists, Contexts)
2. Test kit (ScriptContextBuilder, ValidatorTest)
3. cardano-client-lib integration (v0.7.1)
4. Gradle plugin
5. Example contracts (vesting, multi-sig, NFT, escrow, DEX swap)
6. Documentation and getting-started guide

### Milestone 6: Pure Java VM (`plutus-vm-java`) — Optional / Future
**Deliverable**: Drop-in replacement for Scalus backend, zero Scala dependency

1. Pure Java CEK machine (compute/return loop, environment, continuation stack)
2. Built-in function runtime (~90 builtins, BouncyCastle for crypto)
3. Cost model (V3 parameters, per-builtin cost functions)
4. Run same conformance test suite — must match Scalus results exactly
5. Performance benchmarks: compare against Scalus backend
6. ServiceLoader registration with priority=100 (auto-selected over Scalus)

**Why separate milestone**: The Scalus backend gives us a working VM immediately. The pure Java VM is a quality-of-life improvement (smaller JAR, no Scala dep) but not blocking for the compiler or developer experience.

---

## Verification Strategy

### At each milestone:
- **Milestone 1**: Run official Plutus conformance test suite. Evaluate ~1000 real mainnet scripts. Compare costs against reference.
- **Milestone 2**: Serialize/deserialize ledger types and compare CBOR output against Haskell reference.
- **Milestone 3-4**: Compile example contracts -> evaluate with CEK machine -> verify pass/fail. Compare script sizes against Aiken/Scalus equivalents.
- **Milestone 5**: End-to-end integration test: write contract in Java -> compile -> build transaction with cardano-client-lib -> submit to preview testnet.

### Conformance test source
Clone `https://github.com/IntersectMBO/plutus` and use `plutus-conformance/test-cases/uplc/evaluation/` for VM conformance testing.

---

## Key External Dependencies

| Dependency | Module | Purpose |
|---|---|---|
| `co.nstant.in:cbor` (cbor-java) | plutus-core | CBOR encoding/decoding |
| `org.scalus:scalus_3:0.15.0` | plutus-vm-scalus | Scalus CEK machine (initial VM backend) |
| `com.github.javaparser:javaparser-core` | plutus-compiler | Parse Java source to AST |
| `org.bouncycastle:bcprov-jdk18on` | plutus-vm-java (future) | BLS12-381, SECP256k1 crypto for pure Java VM |
| JUnit 5 + jqwik | all test scopes | Testing + property-based testing |
| `com.bloxbean.cardano:cardano-client-lib:0.7.1` | plutus-cardano-client-lib | Cardano integration |

**Dependency isolation**:
- `plutus-core` — only cbor-java (lightweight)
- `plutus-vm` — pure Java API, zero dependencies beyond plutus-core
- `plutus-vm-scalus` — Scalus + Scala runtime (isolated from user code)
- `plutus-vm-java` (future) — BouncyCastle only, no Scala at all
- End users never import Scalus types — they interact only with `plutus-vm` API
