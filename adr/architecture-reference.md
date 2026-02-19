# ADR-001: Consolidated Architecture Reference

**Status:** Accepted
**Date:** 2026-02-19


This document is the canonical architecture reference for JuLC. It consolidates decisions from previous ADRs and reflects what the code actually implements as of commit `2b0e4ee`.

---

## 1. Project Identity

**Name:** JuLC (Java UPLC Compiler). Renamed from `plutus-java`; some internal names retain backward compatibility (`PlutusData`, `META-INF/plutus-sources/`, `PlutusDataCodec`).

**Mission:** Write Cardano smart contracts in a Java subset, compiled to UPLC (Untyped Plutus Lambda Calculus) for on-chain execution.

**Root Gradle project:** `julc`
**Package prefix:** `com.bloxbean.cardano.julc`
**Plutus version:** V3 only (Conway era). No V1/V2 support.
**Java build toolchain:** 24 (Temurin)
**Gradle:** 9.2.0

---

## 2. Module Architecture

14 Gradle submodules organized in three tiers:

### Foundation

| Module | Purpose | Key classes |
|--------|---------|-------------|
| `julc-core` | UPLC AST, PlutusData, FLAT/CBOR serialization | `Term`, `PlutusData`, `DefaultFun`, `FlatWriter`, `FlatReader`, `PlutusDataCborEncoder` |
| `julc-vm` | VM facade + SPI interface | `JulcVm`, `JulcVmProvider`, `EvalResult`, `ExBudget` |
| `julc-vm-scalus` | Scalus VM backend (Scala 3) | `ScalusVmProvider` (priority 50) |
| `julc-ledger-api` | Cardano ledger types (ScriptContext, TxInfo, etc.) | Records: `ScriptContext`, `TxInfo`, `TxOut`, `Address`, `Value`, etc. |
| `julc-bom` | Bill of Materials POM for version alignment | (no code) |

### Compiler Stack

| Module | Purpose | Key classes |
|--------|---------|-------------|
| `julc-compiler` | Java source to UPLC compiler | `JulcCompiler`, `PirGenerator`, `UplcGenerator`, `UplcOptimizer`, `TypeResolver`, `TypeRegistrar`, `SubsetValidator` |
| `julc-stdlib` | On-chain standard library (11 modules) | `StdlibRegistry`, `ListsLib`, `ValuesLib`, `OutputLib`, `ContextsLib`, etc. |

### Integration and Tooling

| Module | Purpose | Key classes |
|--------|---------|-------------|
| `julc-testkit` | Testing framework | `ValidatorTest`, `SourceDiscovery`, `ScriptContextTestBuilder` |
| `julc-cardano-client-lib` | cardano-client-lib v0.7.1 bridge | `JulcScriptLoader`, `PlutusDataAdapter` |
| `julc-gradle-plugin` | Build-time compilation tasks | `JulcPlugin`, `CompileJulcTask`, `BundleJulcSourcesTask` |
| `julc-annotation-processor` | javac-time compilation to `.plutus.json` | `JulcAnnotationProcessor` |
| `julc-examples` | Example smart contracts | (test sources only) |
| `julc-e2e-tests` | End-to-end tests (opt-in, requires Yaci Devkit) | (test sources only) |
| `julc-plugin-test` | Gradle plugin integration tests | (test sources only) |

### Key Design Decisions

- **VM SPI:** `JulcVmProvider` is discovered via `ServiceLoader`. The highest-priority provider wins. This allows swapping the Scalus backend for a future pure-Java CEK machine without API changes.
- **Scala isolation:** Only `julc-vm-scalus` has a Scala dependency. All other modules are pure Java.
- **BOM:** `julc-bom` provides consistent version alignment across all modules for downstream consumers.

### Dependency Flow

```
julc-core
  ├── julc-vm ── julc-vm-scalus
  ├── julc-ledger-api
  │     └── julc-compiler ── julc-stdlib
  └── julc-cardano-client-lib

julc-compiler + julc-vm ── julc-testkit
julc-compiler ── julc-gradle-plugin
julc-compiler ── julc-annotation-processor
```

---

## 3. UPLC Core and Serialization

### Term AST

`Term` is a sealed interface with 10 record variants:

| Variant | Fields | Notes |
|---------|--------|-------|
| `Var` | `NamedDeBruijn name` | 1-based De Bruijn index |
| `Lam` | `String paramName, Term body` | Lambda abstraction |
| `Apply` | `Term function, Term argument` | Function application |
| `Force` | `Term term` | Type instantiation / thunk evaluation |
| `Delay` | `Term term` | Suspended computation |
| `Const` | `Constant value` | Literal value |
| `Builtin` | `DefaultFun fun` | Built-in function |
| `Error` | (none) | Halts evaluation |
| `Constr` | `long tag, List<Term> fields` | V3 SOP constructor |
| `Case` | `Term scrutinee, List<Term> branches` | V3 SOP pattern match |

### PlutusData

`PlutusData` is a sealed interface with 5 record variants: `ConstrData(int tag, List<PlutusData> fields)`, `MapData(List<Pair> entries)`, `ListData(List<PlutusData> items)`, `IntData(BigInteger value)`, `BytesData(byte[] value)`. All collections are defensively copied; `BytesData` clones on construction and access.

### DefaultFun (Built-in Functions)

102 enum values (codes 0-101) covering Plutus V1 through V4. JuLC targets V3 (codes 0-87). V4 builtins (88-101) are defined in the enum but not used by the compiler.

### FLAT Serialization

Custom implementation in `FlatWriter` / `FlatReader`. MSB-first bit orientation.

- **VLI-7:** LEB128 variable-length integer encoding (7 data bits per byte, MSB continuation flag)
- **Zigzag:** Signed integers encoded as non-negative via `n >= 0 ? n << 1 : (-n << 1) - 1`
- **ByteString:** Filler to byte boundary, then 255-byte chunks with 1-byte length headers, terminated by `0x00`
- **DoS protection:** `MAX_VLI_BYTES = 128` (896 bits). Reader throws `FlatDecodingException` beyond this limit.

### CBOR Encoding

`PlutusDataCborEncoder` — direct CBOR encoding (no external library for the encoder) with canonical Cardano rules:

- Chunked bytestrings for data > 64 bytes (`MAX_BYTESTRING_CHUNK = 64`)
- ConstrData tag ranges: tags 0-6 use CBOR tag `121+tag`; tags 7-127 use CBOR tag `1280+(tag-7)`; tags 128+ use CBOR tag 102 with `[tag, fields]` array
- `PlutusDataCborDecoder` uses `co.nstant.in:cbor` (v0.9) for parsing

---

## 4. Type System

### PirType Hierarchy

`PirType` is a sealed interface with 13 record variants:

**Primitives (6):** `IntegerType`, `ByteStringType`, `StringType`, `BoolType`, `UnitType`, `DataType`

**Containers (4):** `ListType(elemType)`, `PairType(first, second)`, `MapType(keyType, valueType)`, `OptionalType(elemType)`

**Function (1):** `FunType(paramType, returnType)`

**Algebraic (2):** `RecordType(name, List<Field>)`, `SumType(name, List<Constructor>)`

`DataType` is the fallback/opaque type for unresolved or generic Plutus Data.

### PirTerm Hierarchy

`PirTerm` is a sealed interface with 12 record variants: `Var`, `Let`, `LetRec`, `Lam`, `App`, `Const`, `Builtin`, `IfThenElse`, `DataConstr`, `DataMatch`, `Error`, `Trace`. `LetRec` supports multiple bindings (used for mutual recursion via Bekic's theorem).

### Type Resolution

`TypeResolver` maintains 4 internal registries:

1. **recordTypes** — FQCN to `RecordType` (user records + ledger records)
2. **sumTypes** — FQCN to `SumType` (sealed interfaces)
3. **variantToSumType** — variant FQCN to enclosing `SumType`
4. **newTypes** — `@NewType` FQCN to underlying `PirType`

Resolution priority for class types: built-in shortcuts (`BigInteger` -> `IntegerType`, `String` -> `StringType`, etc.) -> `PlutusData` variants -> `List`/`JulcList`/`Map`/`JulcMap` -> `Tuple2`/`Tuple3` -> `Optional` -> FQCN lookup (ledger hash names -> `@NewType` -> registered record -> registered sum type -> variant -> ledger data names).

Seven ledger hash types (`PubKeyHash`, `ScriptHash`, `ValidatorHash`, `PolicyId`, `TokenName`, `DatumHash`, `TxId`) resolve to `ByteStringType`. Thirteen governance-era types (`StakingCredential`, `Vote`, `Voter`, `DRep`, etc.) resolve to opaque `DataType`.

### TypeRegistrar (Topological Sort)

`TypeRegistrar.registerAll()` processes all compilation units together:

1. Collect all record declarations and sealed interfaces with FQCNs
2. Build dependency graph from field types and permitted variants
3. Topological sort via Kahn's algorithm (circular dependencies are a compile error)
4. Register in dependency order, detecting `@NewType` annotations during registration

### @NewType Zero-Cost Aliases

Single-field records annotated `@NewType` compile as identity (no `ConstrData` wrapping). Underlying type must be `byte[]`, `BigInteger`, `String`, or `boolean`. The compiler auto-registers a `.of()` factory method as identity for each `@NewType`.

### Tuple2 and Tuple3

Generic records registered in `LedgerTypeRegistry`. Field access auto-unwraps based on type arguments: `Tuple2<BigInteger, byte[]>.first()` generates `UnIData`. Constructors auto-wrap: `new Tuple2<BigInteger, BigInteger>(a, b)` generates `IData` for each argument. Raw `Tuple2` (no type args) defaults to `DataType` fields.

### TypeMethodRegistry (Instance Method Dispatch)

Keyed on `"TypeClassName.methodName"`. For named `RecordType`, tries `"TypeName.method"` (e.g., `"Value.lovelaceOf"`) before falling back to `"RecordType.method"`. Registered method groups:

| Type | Methods |
|------|---------|
| `IntegerType` | `abs`, `negate`, `max`, `min`, `equals`, `add`, `subtract`, `multiply`, `divide`, `remainder`, `mod`, `signum`, `compareTo`, `intValue`/`longValue`/`value` |
| `ByteStringType` | `hash`, `length`, `equals`, `append`, `prepend`, `value` |
| `StringType` | `length`, `equals` |
| `DataType`/`RecordType`/`SumType` | `equals` |
| `ListType` | `size`, `isEmpty`, `head`, `tail`, `get`, `prepend`, `contains`, `reverse`, `concat`, `take`, `drop` + HOFs: `map`, `filter`, `any`, `all`, `find` |
| `OptionalType` | `isPresent`, `isEmpty`, `get` |
| `MapType` | `get`, `containsKey`, `size`, `isEmpty`, `keys`, `values`, `insert`, `delete` |
| `PairType` | `key`, `value` |
| `Value` (named) | `lovelaceOf`, `isEmpty`, `containsPolicy`, `assetOf` |

### MapType Pair-List Convention

MapType variables always hold pair lists (not `MapData`-wrapped values). All 8 `MapType` method handlers operate directly on pair lists without applying `UnMapData`. `UnMapData` is only applied at the point of casting to `MapType` in `PirGenerator` and in `wrapDecode`.

---

## 5. Compiler Pipeline

### Overview

`JulcCompiler.doCompile()` implements a 22-step pipeline:

**Phase 1 — Parse and Validate:**
1. Parse all sources via JavaParser (language level `JAVA_21`)
2. `SubsetValidator` rejects unsupported Java constructs
3. Validate library CUs don't contain validator annotations

**Phase 2 — Type Registration:**
4. Find annotated validator class, determine `ScriptPurpose`
5. Register types from all sources via `TypeRegistrar` (topological sort)
5b. Auto-register `.of()` for `@NewType` records
5c-d. Build FQCN index, set `ImportResolver`

**Phase 3 — Method Discovery:**
6. Detect `@Param` fields and static field initializers
7. Find `@Entrypoint` method, validate parameter count

**Phase 4 — PIR Generation:**
8. Compile library static methods to PIR (multi-pass progressive)
9. Compose `StdlibLookup` (stdlib registry + library methods)
10-12. Set up `SymbolTable`, generate PIR for helpers and entrypoint
13-14. Wrap as `Let`/`LetRec` bindings (topologically sorted)

**Phase 5 — Lowering and Output:**
15. `ValidatorWrapper` wraps entrypoint for on-chain script purpose
16. Wrap with outer `@Param` lambdas
17-18. Lower PIR to UPLC via `UplcGenerator`
19. Optimize UPLC via `UplcOptimizer`
20-22. Create `Program.plutusV3(uplcTerm)`, build `CompileResult`

### Key Pipeline Classes

| Class | Role |
|-------|------|
| `JulcCompiler` | Main facade, orchestrates all phases |
| `SubsetValidator` | Rejects unsupported Java constructs (see Section 6) |
| `TypeResolver` | Java types to `PirType` mapping |
| `TypeRegistrar` | Topological type registration |
| `PirGenerator` | Java AST to PIR term generation (largest compiler class) |
| `UplcGenerator` | PIR to De Bruijn-indexed UPLC lowering |
| `UplcOptimizer` | Multi-pass UPLC optimization |
| `ValidatorWrapper` | Script purpose wrapping for on-chain execution |

### Recursion Handling

- **Self-recursion:** Z-combinator in `UplcGenerator`: `fix = \f -> (\x -> f (\v -> x x v)) (\x -> f (\v -> x x v))`
- **Mutual recursion (2 bindings):** Bekic's theorem — decomposes 2-binding mutual `LetRec` into nested single-binding `LetRec`s with substitution
- **Non-mutual multi-binding:** Topological sort + dependency analysis; each binding is nested as `Let` (non-recursive) or single `LetRec` (self-recursive) in dependency order
- **Mutual recursion (3+ bindings):** Not supported (compiler error)

### De Bruijn Indexing

`UplcGenerator` maintains a `Deque<String>` scope stack. Variable lookup produces a 1-based De Bruijn index by scanning from the top of the stack.

### ValidatorWrapper

Generates the on-chain script wrapper per `ScriptPurpose`. All V3 scripts receive a single `ScriptContext` as raw `Data`:

| Purpose | Wrapper behavior |
|---------|-----------------|
| Spending (2-param) | Extract redeemer from `ctxFields[1]`, call `validate(redeemer, scriptContextData)` |
| Spending (3-param) | Extract redeemer from `ctxFields[1]`, extract `ScriptInfo` from `ctxFields[2]`, extract datum from `ScriptInfo.fields[1]` (unwrap Optional), call `validate(datum, redeemer, scriptContextData)` |
| Minting | Same structure as 2-param spending |
| Withdraw, Certifying, Voting, Proposing | Same structure as 2-param spending |

Result: `IfThenElse(result, Unit, Error)` — `true` accepts the transaction, `false` rejects.

### UPLC Optimizer

6 optimization passes run iteratively until fixpoint (max 20 iterations):

| Pass | Transformation |
|------|---------------|
| Force-Delay cancel | `Force(Delay(t))` -> `t` |
| Constant folding | `Add/Sub/Mul/Eq/Lt/LtEq` on constants -> constant result |
| Dead code elimination | `Apply(Lam(x, body), val)` where `x` unused and `val` pure -> `body` |
| Beta reduction | `Apply(Lam(x, body), arg)` where `x` used once and `arg` is simple -> substitute |
| Eta reduction | `Lam(x, Apply(f, Var(1)))` where `x` not free in `f` and `f` is a value -> `f` |
| Constr-Case reduction | `Case(Constr(tag, fields), branches)` -> `branches[tag]` applied to fields |

Side effects (`Trace` builtin) are preserved and never eliminated. `Apply` is explicitly excluded from the value check to avoid divergence with the Z-combinator.

### Multi-File Compilation

Library compilation uses a multi-pass retry loop: remaining CUs are attempted each pass; CUs that fail due to unresolved cross-library references are retried in subsequent passes until all succeed or progress stalls.

Library method ordering uses Kahn's algorithm on the dependency graph, with self-recursive methods detected via free variable analysis (`Let` vs `LetRec`).

---

## 6. Supported Java Subset

### Supported Constructs

| Java Construct | On-chain compilation |
|---------------|---------------------|
| Records | `RecordType` with `ConstrData` encoding |
| Sealed interfaces | `SumType` with tag-based dispatch |
| `int`, `long`, `BigInteger` | `IntegerType` (arbitrary precision on-chain) |
| `byte[]` | `ByteStringType` |
| `String` | `StringType` (UTF-8 encoded bytestring) |
| `boolean` | `BoolType` (`ConstrData(0/1, [])`) |
| `void` | `UnitType` |
| `List<T>`, `JulcList<T>` | `ListType` |
| `Map<K,V>`, `JulcMap<K,V>` | `MapType` (pair-list representation) |
| `Optional<T>` | `OptionalType` (resolved, but limited encode/decode) |
| `if`/`else` | `IfThenElse` |
| Switch expressions | `DataMatch` (on sealed interfaces) |
| Pattern matching (`case Type t ->`) | `DataMatch` with field binding extraction |
| For-each loops | Desugared to recursive fold |
| While loops (including nested) | Desugared to `LetRec` recursion |
| Local variables (`var`, `final`) | `Let` bindings (immutable) |
| Static methods | `Lam`/`Let` bindings |
| Lambdas (in HOFs) | Inlined at call site |
| Arithmetic operators | Mapped to Plutus integer/bytestring builtins |
| `==` on data types | `EqualsData` / `EqualsInteger` / `EqualsByteString` |
| `&&`, `||`, `!` | Desugared to `IfThenElse` |
| `Builtins.*` calls | Direct UPLC builtin references |
| `break` in loops | Supported inside for-each and while loops |

### Explicitly Unsupported (Compile-Time Error)

| Construct | Reason | Alternative |
|-----------|--------|-------------|
| `try`/`catch`/`finally` | No exception model on-chain | Use `if`/`else` |
| `throw` | No exception model on-chain | Return `false` to reject |
| `null` | No null on-chain | Use `Optional<T>` |
| `float`, `double` | No floating-point on-chain | Use `BigInteger` or `Rational` |
| Arrays (`T[]`, except `byte[]`) | Not supported | Use `List<T>` |
| Class inheritance (`extends`) | Not supported | Use sealed interfaces + records |
| `synchronized` | Single-threaded on-chain | Remove |
| C-style `for(;;)` | Not supported | Use for-each or while |
| `do-while` | Not supported | Use while |
| Method references (`Class::method`) | Not supported | Use static method call |
| `this`, `super` | Stateless on-chain | Use static methods |
| Functional interface `.apply()`/`.test()` | Not supported | Call method directly |

### Caveats

| Construct | Behavior |
|-----------|----------|
| `default ->` in switch | **Silently skipped** — branch body is never compiled. Use explicit cases for all constructors. |
| `Optional<T>` | Type resolves, but encoding/decoding support is incomplete. |
| String `+` concatenation | Compiles to `AppendByteString` (raw byte concat, not Java string semantics). |
| `var` type inference | Falls back to `DataType` on failure (no warning). |

---

## 7. Standard Library

### Modules

11 library modules providing ~128 methods:

| Module | Key methods |
|--------|------------|
| `ListsLib` | `length`, `isEmpty`, `head`, `tail`, `reverse`, `concat`, `nth`, `take`, `drop`, `contains`, `containsInt`, `containsBytes`, `hasDuplicateInts`, `hasDuplicateBytes`, `empty`, `prepend` + HOFs |
| `MapLib` | `lookup`, `member`, `insert`, `delete`, `keys`, `values`, `toList`, `fromList`, `size` |
| `ValuesLib` | `geqMultiAsset`, `leq`, `eq`, `isZero`, `singleton`, `negate`, `flatten`, `add`, `subtract` |
| `OutputLib` | `txOutAddress`, `txOutValue`, `txOutDatum`, `outputsAt`, `countOutputsAt`, `uniqueOutputAt`, `outputsWithToken`, `valueHasToken`, `lovelacePaidTo`, `paidAtLeast`, `getInlineDatum`, `resolveDatum` |
| `ContextsLib` | `txInfoMint`, `txInfoFee`, `txInfoId`, `txInfoRefInputs`, `txInfoWithdrawals`, `txInfoRedeemers`, `findOwnInput`, `getContinuingOutputs`, `findDatum`, `valueSpent`, `valuePaid`, `ownHash`, `scriptOutputsAt`, `listIndex`, `trace` |
| `MathLib` | `abs`, `max`, `min`, `divMod`, `quotRem`, `pow`, `sign`, `expMod` |
| `IntervalLib` | `between`, `never`, `isEmpty`, `finiteUpperBound`, `finiteLowerBound` |
| `CryptoLib` | `verifyEcdsaSecp256k1`, `verifySchnorrSecp256k1`, `ripemd_160` |
| `ByteStringLib` | `take`, `lessThan`, `lessThanEquals`, `integerToByteString`, `byteStringToInteger`, `encodeUtf8`, `decodeUtf8`, `serialiseData` |
| `BitwiseLib` | `andByteString`, `orByteString`, `xorByteString`, `complementByteString`, `readBit`, `writeBits`, `shiftByteString`, `rotateByteString`, `countSetBits`, `findFirstSetBit` |
| `AddressLib` | `credentialHash`, `isScriptAddress`, `isPubKeyAddress`, `paymentCredential` |

### Two Compilation Strategies

1. **Java-source libraries (`@OnchainLibrary`):** Written as Java source files. Compiled to PIR alongside the validator. Multi-pass progressive compilation handles cross-library dependencies. Source files are bundled at `META-INF/plutus-sources/` for classpath distribution.

2. **PIR-registered methods:** Registered in `StdlibRegistry` as `PirTermBuilder` lambdas that directly produce `PirTerm` AST nodes. Used for:
   - `Builtins.*` mappings (~50 entries) — direct UPLC builtin references
   - `ListsLib` HOFs (`any`, `all`, `find`, `foldl`, `map`, `filter`, `zip`) — built via `PirHofBuilders`
   - `Math.abs/max/min` delegates — inline PIR arithmetic
   - `ContextsLib.trace` — `PirTerm.Trace`
   - `JulcList.empty/of` — `MkNilData`/`MkCons` chains
   - Ledger type `.of()` factories — identity (pass-through)

### Architecture: Three Method Registries

| Registry | Location | Scope |
|----------|----------|-------|
| `StdlibRegistry` | `julc-stdlib` | Static method calls (`ClassName.method(args)`) |
| `TypeMethodRegistry` | `julc-compiler` | Instance method calls (`object.method(args)`) |
| `PirHofBuilders` | `julc-compiler` | Shared PIR builders for HOFs (used by both registries) |

### Instance HOF Support

List methods `map`, `filter`, `any`, `all`, `find` are available as both static calls (`ListsLib.map(list, fn)`) and instance calls (`list.map(fn)`). Lambda parameter types are auto-inferred from the list element type. Variable capture and chaining (`list.filter(...).map(...)`) are supported. Block-body lambdas are supported.

`foldl` is only available as a static call (`ListsLib.foldl(f, init, list)`) — the 2-parameter lambda + initial value makes instance dispatch complex.

### Notable Behaviors

- `map()` wraps lambda results to Data, so the returned list has `DataType` elements regardless of input. Use `Builtins.unIData(mapped.head())` for integer elements.
- `Value.lovelaceOf()` assumes ADA (empty policy ID) is the first entry in the Value map. This matches standard Cardano transaction construction but is not guaranteed by the protocol.
- `MapType.insert()` prepends without deduplication.
- `MapType.delete()` removes only the first matching key.

---

## 8. Ledger API

### V3-Only Decision

JuLC targets Plutus V3 exclusively. V3 scripts receive a single `ScriptContext` argument as `Data` (rather than V1/V2's three separate arguments). This simplifies the wrapper and aligns with the Conway era.

### Registered Ledger Types

**Records:** `ScriptContext`, `TxInfo` (16 fields), `TxOut`, `TxInInfo`, `TxOutRef`, `Address`, `Value`, `Interval`, `IntervalBound`, `Tuple2`, `Tuple3`

**Sealed interfaces (SumTypes):**

| Interface | Constructors |
|-----------|-------------|
| `Credential` | `PubKeyCredential(0)`, `ScriptCredential(1)` |
| `OutputDatum` | `NoOutputDatum(0)`, `OutputDatumHash(1)`, `OutputDatumInline(2)` |
| `ScriptInfo` | `MintingScript(0)`, `SpendingScript(1)`, `RewardingScript(2)`, `CertifyingScript(3)`, `VotingScript(4)`, `ProposingScript(5)` |
| `IntervalBoundType` | `NegInf(0)`, `Finite(1)`, `PosInf(2)` |

### Hash Wrapper Types

Seven hash types resolve to `ByteStringType`: `PubKeyHash`, `ScriptHash`, `ValidatorHash`, `PolicyId`, `TokenName`, `DatumHash`, `TxId`. Each has a `.of(byte[])` factory method that is identity on-chain and delegates to the constructor off-chain.

### Opaque DataType Types

Thirteen governance-era types resolve to opaque `DataType` (no field access): `StakingCredential`, `ScriptPurpose`, `Vote`, `Voter`, `DRep`, `Delegatee`, `GovernanceActionId`, `GovernanceAction`, `ProposalProcedure`, `TxCert`, `Rational`, `ProtocolVersion`, `Committee`.

### PlutusDataConvertible / PlutusDataCodec

Ledger record types implement `PlutusDataConvertible` for off-chain serialization. `PlutusDataCodec<T>` provides bidirectional conversion between Java records and `PlutusData`.

---

## 9. Developer Tooling

### Annotations

12 annotations in `com.bloxbean.cardano.julc.stdlib.annotation`:

| Annotation | Target | Retention | Purpose |
|-----------|--------|-----------|---------|
| `@SpendingValidator` | TYPE | RUNTIME | Spending validator |
| `@MintingValidator` | TYPE | RUNTIME | Minting policy |
| `@WithdrawValidator` | TYPE | RUNTIME | Staking reward withdrawal |
| `@CertifyingValidator` | TYPE | RUNTIME | Delegation certificate |
| `@VotingValidator` | TYPE | RUNTIME | Governance vote |
| `@ProposingValidator` | TYPE | RUNTIME | Governance proposal |
| `@Validator` | TYPE | RUNTIME | Spending (deprecated, use `@SpendingValidator`) |
| `@MintingPolicy` | TYPE | RUNTIME | Minting (deprecated, use `@MintingValidator`) |
| `@Entrypoint` | METHOD | RUNTIME | Validator entry method (static, returns boolean) |
| `@Param` | FIELD | RUNTIME | Deploy-time parameter (applied via UPLC partial application) |
| `@OnchainLibrary` | TYPE | RUNTIME | On-chain library class |
| `@NewType` | TYPE | SOURCE | Zero-cost type alias |

### Testkit

`ValidatorTest` provides a static utility API:

- **Compile:** `compile(source)`, `compile(source, libs...)`, `compile(Path)`, `compileWithDetails(source)`
- **Class-based compile:** `compileValidator(Class<?>)`, `compileValidatorByName(fqcn)` — auto-discovers `@OnchainLibrary` dependencies via `SourceDiscovery`
- **Evaluate:** `evaluate(Program, PlutusData...)`, `evaluate(Program, ExBudget, PlutusData...)`, `evaluate(source, PlutusData...)`
- **Assert:** `assertValidates(Program, PlutusData...)`, `assertRejects(Program, PlutusData...)`

`ScriptContextTestBuilder` constructs `ScriptContext` `PlutusData` for testing. `JvmCryptoProvider` provides off-chain implementations of crypto builtins (Ed25519, ECDSA, Schnorr, RIPEMD-160 via BouncyCastle).

### Gradle Plugin

Plugin ID: `com.bloxbean.cardano.julc`

| Task | Purpose |
|------|---------|
| `compileJulc` | Compile validators from `src/main/plutus` to `build/plutus` |
| `bundleJulcSources` | Bundle `@OnchainLibrary` sources into `META-INF/plutus-sources/` |

The plugin adds `src/main/plutus` to IDE source sets but excludes it from `compileJava`. `bundleJulcSources` runs before `jar`; `compileJulc` is wired to `build`.

### Annotation Processor

`JulcAnnotationProcessor` runs during `javac` and processes all 8 validator annotations plus `@OnchainLibrary`. For each validator:

1. Reads source via Trees API
2. Resolves library sources (same-project `@OnchainLibrary` classes + classpath `META-INF/plutus-sources/`)
3. Compiles via `JulcCompiler`
4. Writes `META-INF/plutus/<ClassName>.plutus.json` (CBOR hex, script hash, params, size)

### Library Source Distribution

`@OnchainLibrary` sources are distributed via JAR at `META-INF/plutus-sources/`. An `index.txt` manifest lists all entries. `LibrarySourceResolver.scanClasspathSources()` reads this manifest (primary) or falls back to file-system directory scan (development). Transitive resolution uses BFS over imports and referenced class names.

### cardano-client-lib Bridge

- `JulcScriptLoader.load(Class<?>)` — loads pre-compiled script from `META-INF/plutus/`
- `JulcScriptLoader.load(Class<?>, PlutusData... params)` — loads and applies parameters
- `PlutusDataAdapter.toClientLib()` / `fromClientLib()` — bidirectional `PlutusData` conversion between JuLC core and cardano-client-lib types
- Double-CBOR wrapping is handled by the adapter for script argument application

---

## 10. Compiler Safety Features

### SubsetValidator

Walks the JavaParser AST and rejects unsupported constructs with `CompilerDiagnostic.Level.ERROR`. Rejected constructs include: `try/catch`, `throw`, `null`, `float`/`double`, arrays, inheritance, `synchronized`, C-style `for`, `do-while`, method references, functional interface `.apply()`, `this`/`super`. See Section 6 for the full list.

Emits `WARNING` for unreachable code after `return`, `break`, or `Builtins.error()`.

### Switch Exhaustiveness (S4)

Non-exhaustive switches on sealed interfaces produce a compile error listing missing cases. The check runs in `PirGenerator.generateSwitchExpr()` before `buildDataMatch()`. Switches with a `default` branch bypass the check.

### Return Path Analysis (S5)

Methods that don't return on all execution paths produce a compile error. `allPathsReturn()` checks if/else completeness, fallthrough returns, and loop-as-return patterns. Void methods are skipped.

### Post-While-Loop Variable Re-Binding (S1 Fix)

Variables defined before a multi-accumulator while loop are re-bound after the loop. The fix snapshots pre-loop variables via `SymbolTable.allVisibleVariables()` and re-binds them after `unpackAccumulators()`.

### Banned @Param Types

`JulcCompiler` rejects `@Param` fields with types `PlutusData.BytesData`, `BytesData`, `PlutusData.MapData`, `MapData`, `PlutusData.ListData`, `ListData`, `PlutusData.IntData`, `IntData` — these cause double-wrapping at runtime. Always use `PlutusData`.

### @OnchainLibrary Compile-Time Validation

The annotation processor validates `@OnchainLibrary` classes compile successfully during `javac`. Compilation errors surface as annotation processing errors.

---

## 11. Known Limitations

### Tier A — Silently Wrong UPLC

These produce valid UPLC that behaves incorrectly at runtime without any compile-time warning:

| ID | Issue | Workaround |
|----|-------|------------|
| S2 | Cross-method type inference: callee may use `EqualsData` instead of `EqualsInteger` for `long` params | Use Data-level equality on wrapped values |
| S3 | `@Entrypoint BigInteger` params typed as `IntegerType` but hold raw Data | Use `PlutusData` for entrypoint parameters |
| — | `default ->` switch branch body is never compiled | Use explicit cases for all constructors |
| — | Cross-library `BytesData` param: compiler skips encode direction | Pass `PlutusData` (not `BytesData`) to cross-library calls |
| — | `@Param PlutusData.BytesData` broken: double-wraps | Always use `@Param PlutusData` |

### Tier B — Missing Validation (Should Be Compile Errors)

| ID | Issue |
|----|-------|
| S7 | Lambda `.apply()` on non-standard functional types not caught by `SubsetValidator` (standard types like `Function`, `Predicate` are caught) |
| — | `OptionalType` resolved but has no encode/decode support |
| — | Unregistered ledger types (`StakingCredential`, `ScriptPurpose`, `Vote`, `Voter`, `DRep`, etc.) silently fall through to `DataType` |
| — | `var` type inference failure silently falls back to `DataType` |
| — | No line numbers in `CompilerDiagnostic` |
| S11 | Duplicate method names silently shadow (second wins) |

### Tier C — Off-Chain Divergence

| Issue | Detail |
|-------|--------|
| `Builtins.byteStringToInteger` | Off-chain truncates to 64-bit via `BigInteger.longValue()`; on-chain is arbitrary precision |
| `ByteStringLib` off-chain casts | `zeros()`, `empty()`, `integerToByteString()`, `serialiseData()` use `(byte[])(Object)` casts that fail off-chain |
| `MathLib.pow` negative exponent | Silently returns 1 off-chain |
| `MathLib` division by zero | `ArithmeticException` off-chain, script failure on-chain |

### Tier D — Documented Compiler Constraints

| Constraint | Detail |
|-----------|--------|
| Mutual recursion | Supported for 2 bindings (Bekic's theorem); 3+ bindings is a compile error |
| `Tuple2`/`Tuple3` | Not switchable (registered as `RecordType`, not `SumType`); use field access |
| `switch` field name shadows | Constructor field names shadow method parameters of the same name |
| `Value.assetOf()` | Requires `BData`-wrapped arguments; raw `byte[]` causes `DeserializationError` |

---

## 12. Build, CI, and Release

### Build Configuration

- **Gradle:** 9.2.0
- **Java toolchain:** 24 (Temurin)
- **Source/target compatibility:** Java 24

### Key Dependencies

| Artifact | Version |
|----------|---------|
| `co.nstant.in:cbor` | 0.9 |
| `com.github.javaparser:javaparser-core` | 3.26.3 |
| `org.scalus:scalus_3` | 0.15.0 |
| `com.bloxbean.cardano:cardano-client-lib` | 0.7.1 |
| `org.junit:junit-bom` | 5.11.4 |
| `org.bouncycastle:bcprov-jdk18on` | 1.83 |

### GitHub Actions Workflows

| Workflow | Trigger | Purpose |
|----------|---------|---------|
| `build.yml` | Push/PR to `main`, `develop`, `release/*` | Build + test (signing skipped) |
| `release.yml` | Push tag `v*` | Build + GPG sign + publish to Maven Central via Sonatype |
| `snapshot.yml` | Manual (`workflow_dispatch`) | Build + publish snapshot (signing skipped) |

All workflows use `actions/checkout@v4`, `actions/setup-java@v4` (Java 24, Temurin), `gradle/actions/setup-gradle@v4`.

### Publishing

- **Release:** Sonatype OSSRH staging (`ossrh-staging-api.central.sonatype.com`). GPG-signed.
- **Snapshot:** `central.sonatype.com/repository/maven-snapshots/`. SNAPSHOT versions include short Git commit hash: `X.Y.Z-<hash>-SNAPSHOT`.
- **Non-publishable modules:** `julc-examples`, `julc-e2e-tests`, `julc-plugin-test`.

### E2E Tests

`julc-e2e-tests` and `julc-plugin-test` are opt-in (`-Pe2e`). They require an externally-started Yaci Devkit instance and are not run in CI.

---

## 13. Not Yet Implemented

| Feature | Notes |
|---------|-------|
| Pure Java VM backend (`julc-vm-java`) | not started |
| Mutual recursion 3+ bindings | Currently a compile error |
| `Optional<T>` on-chain encoding/decoding | Type resolves but operations are incomplete |
| `default ->` switch branch compilation | Branch body is silently skipped |
| Line numbers in `CompilerDiagnostic` | `CompilerDiagnostic` has `message` and `level` but no source location |
| CI E2E integration | Requires Yaci Devkit; tests are manual opt-in |

---

## Codebase Metrics (as of review cutoff)

| Metric | Value |
|--------|-------|
| Main source files | 156 |
| Test source files | 92 |
| Main LOC | 25,818 |
| Test LOC | 33,938 |
| Test-to-production ratio | 1.31:1 |
| Test annotations (`@Test`) | 1,920 |
| Executed test cases | 2,998 |
| Test failures | 0 |

---
