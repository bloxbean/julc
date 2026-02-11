# Standard Type Method Compilation: Opshin vs Scalus vs Plutus-Java

## Context

Plutus-Java currently supports ~15 instance methods on standard Java types (`BigInteger`, `String`, `byte[]`, `List`, `Optional`). Each method is hand-wired in `PirGenerator.generateMethodCall()` as a chain of `if (methodName.equals("X"))` blocks. This report compares our approach with **Opshin** (Python→UPLC) and **Scalus** (Scala→UPLC) to understand how they handle standard library type methods, what architectural patterns they use, and how we can improve scalability.

**Core question**: *To support any new standard method, we must write a transformer that produces equivalent UPLC. Is this true for all three projects, and how do they organize these transformers?*

**Answer**: Yes — all three projects must ultimately produce UPLC builtins or composite PIR/UPLC terms for every supported method. The difference is **how they organize and dispatch** these transformers.

---

## 1. Opshin (Python → UPLC)

**Repo**: https://github.com/OpShin/opshin
**Pipeline**: `Python Source → AST → 20+ Rewrite Passes → Type Inference → Typed AST → Pluthon (IR) → UPLC`

### Architecture: OOP Polymorphic Dispatch

Opshin uses a **type-class hierarchy** where each type knows its own methods:

```python
# Each type implements attribute() and attribute_type()
class ByteStringType:
    def attribute_type(self, attr):
        if attr == "hex":
            return FunctionType([], StringInstanceType)
        if attr == "decode":
            return FunctionType(["encoding"], StringInstanceType)

    def attribute(self, attr):
        if attr == "hex":
            return OLambda(["x", "_"], <UPLC for hex>)
        if attr == "decode":
            return OLambda(["x", "enc"], <UPLC for decode>)
```

### How Methods Are Added

To add a new method (e.g., `bytes.rjust()`):
1. Add `attribute_type("rjust")` → return signature (1 file, ~3 lines)
2. Add `attribute("rjust")` → return UPLC lambda (1 file, ~5-15 lines)
3. Done. No changes to compiler core, parser, or dispatch logic.

### Operator Dispatch

Binary operators are also type-polymorphic:
```python
class IntegerType:
    def _binop_return_type(self, op, other):
        if op in ["+", "-", "*"] and isinstance(other, IntegerType):
            return IntegerInstanceType

    def _binop_bin_fun(self, op, other):
        return {"+" : BuiltIn.AddInteger, "-": BuiltIn.SubtractInteger, ...}[op]
```

### Key Design Decisions
- **No central dispatch table** — each type class owns its methods
- **Virtual dispatch** — compiler calls `type.attribute(name)` without knowing the type
- **Rewrite passes** (20+) normalize Python idioms before compilation
- **Type inference** runs before compilation, so types are always known

### Supported Methods
- `bytes`: `.hex()`, `.decode()`, `.fromhex()`, `.ljust()`, `.rjust()`
- `str`: `.encode()`
- `list`: `.index()`
- `dict`: `.get()`, `.keys()`, `.values()`, `.items()`
- Builtins: `len()`, `abs()`, `print()`, `isinstance()`, `reversed()`, `range()`, `min()`, `max()`

### Strengths
- Adding a method = modifying ONE type class, ONE file
- Types own their methods → high cohesion, low coupling
- Operator dispatch is polymorphic (e.g., `5 * "abc"` works)

### Weaknesses
- Python's dynamic typing requires a full type inference pass
- 20+ rewrite passes add compilation complexity
- Type classes are large files (~500+ lines for complex types)

---

## 2. Scalus (Scala → UPLC)

**Repo**: https://github.com/scalus3/scalus
**Pipeline**: `Scala Source → Scala 3 Compiler Plugin (TASTy) → SIR → UPLC`

### Architecture: Three-Layer Hybrid

Scalus uses **three complementary strategies**, not a single pattern:

#### Layer 1: Inline Extension Methods (Zero-Cost Convenience)

```scala
// ByteString.scala — no compiler changes needed!
extension (self: ByteString)
  inline infix def <(inline that: ByteString): Boolean =
    Builtins.lessThanByteString(self, that)

  inline def slice(from: BigInt, len: BigInt): ByteString =
    Builtins.sliceByteString(from, len, self)
```

The `inline` keyword causes Scala compiler to expand these **before** the Scalus plugin sees the code. So `myBytes.slice(0, 5)` becomes `Builtins.sliceByteString(0, 5, myBytes)` at compilation time. The Scalus plugin never needs to know about `.slice()`.

#### Layer 2: Macro-Generated Builtin Map

```scala
// Macros.scala — auto-generates mapping from method name to SIR builtin
inline def generateBuiltinsMap(ctx: Context): Map[Symbol, SIR.Builtin] = {
  Map(
    Builtins.addInteger -> SIRBuiltins.addInteger,
    Builtins.equalsString -> SIRBuiltins.equalsString,
    // ... all ~100 builtins auto-mapped
  )
}
```

Convention: `DefaultFun.AddInteger` → `Builtins.addInteger` (lowercase first letter). New builtin = add method to `Builtins.scala`, macro auto-discovers it.

#### Layer 3: Pattern Matching in SIRCompiler (for Operators & Special Forms)

```scala
// SIRCompiler.scala (3832 lines) — pattern matches on typed Scala AST
case Apply(Select(lhs, op), List(rhs))
  if lhs.tpe.widen =:= BigIntClassSymbol.typeRef =>
  op match
    case nme.PLUS  => SIR.Apply(SIRBuiltins.addInteger, compiledLhs, compiledRhs)
    case nme.MINUS => SIR.Apply(SIRBuiltins.subtractInteger, compiledLhs, compiledRhs)
```

### How Methods Are Added

Three paths depending on method type:

| Method Type | Effort | Example |
|------------|--------|---------|
| Direct UPLC builtin wrapper | **Very Low** — add `inline` extension method | `ByteString.slice()` |
| New UPLC builtin | **Low** — add to `Builtins.scala` + `DefaultFun` enum, macro maps it | New Plutus V4 builtins |
| Composite operation | **Medium** — add pattern match case in `SIRCompiler.scala` | `BigInt` operators, `List.map()` |

### Key Design Decisions
- **Leverages Scala 3's type system** — compiler plugin operates on fully-typed AST (TASTy)
- **No type inference needed** — Scala compiler does it all before the plugin runs
- **`inline` keyword** eliminates most method dispatch — methods become builtins before compilation
- **SIR preserves types** — intermediate representation keeps type info for code generation

### Supported Methods
- `BigInt`: `+`, `-`, `*`, `/`, `%`, `<`, `<=`, `>`, `>=`, `==`, `!=`, unary `-`
- `ByteString`: comparison ops, `.slice()`, `.take()`, `.drop()`, `.at()`, `.length`, `+:` (cons)
- `String`: `+`, `==`, `!=`, UTF-8 encode/decode
- `Boolean`: `!`, `&&`, `||` (short-circuit)
- `BuiltinList[A]`: `::`, `.head`, `.tail`, `.isEmpty`, `List(a,b,c)`, `List.empty`
- `BuiltinPair[A,B]`: `.fst`, `.snd`
- `Data`: construction, pattern matching, `FromData`/`ToData` type classes
- Stdlib: `AssocMap`, `Math`, `Prelude`, `Show`, `Ord`

### Strengths
- `inline` extension methods = zero compiler coupling for most methods
- Scala's type system does all the heavy lifting
- Macro-generated builtin map eliminates boilerplate
- SIR is a proper typed IR (serializable, analyzable)

### Weaknesses
- Scala 3 compiler plugin is complex (3832-line SIRCompiler.scala)
- Tightly coupled to Scala 3's TASTy internal representation
- No equivalent of Opshin's type-owns-methods pattern for custom operations

---

## 3. Plutus-Java (Current)

**Pipeline**: `Java Source → JavaParser → SubsetValidator → TypeResolver → PirGenerator → UplcGenerator → Program`

### Architecture: Centralized If/Else Dispatch

All instance method dispatch is in **one method** — `PirGenerator.generateMethodCall()` (164 lines):

```java
// PirGenerator.java — linear chain of method name checks
if (methodName.equals("equals") && args.size() == 1) {
    var scopeType = resolveExpressionType(scopeExpr);
    if (scopeType instanceof PirType.IntegerType) { ... }
    if (scopeType instanceof PirType.ByteStringType) { ... }
    if (scopeType instanceof PirType.StringType) { ... }
}
if (methodName.equals("length") && args.isEmpty()) { ... }
if (methodName.equals("abs") && args.isEmpty()) { ... }
// ... 12 more if blocks
```

### How Methods Are Added

To add a new method (e.g., `String.substring(int, int)`):
1. Add `if (methodName.equals("substring") ...)` block in `PirGenerator.generateMethodCall()` (~15-30 lines)
2. Add return type case in `resolveMethodCallReturnType()` (~3 lines)
3. Add test in `TypeMethodsTest.java` (~20-30 lines)
4. Total: **40-60 lines across 2 files**, all touching compiler core

### Two Dispatch Systems (Inconsistency)

| Call Style | Dispatch Mechanism | Example |
|-----------|-------------------|---------|
| Static: `ContextsLib.signedBy(x, y)` | `StdlibRegistry` (registry pattern) | Decoupled, scalable |
| Instance: `myList.contains(x)` | Hardcoded in `PirGenerator` | Coupled, doesn't scale |

The `StdlibRegistry` is a clean, extensible registry with `Map<String, PirTermBuilder>` — but it only handles static calls (`ClassName.method(args)`). Instance methods (`scope.method(args)`) bypass it entirely.

### Strengths
- Simple and easy to understand
- Works correctly for all 15 supported methods
- `StdlibRegistry` pattern proves the extensible registry approach works
- Type-aware `==`/`!=`/`+` dispatch in `generateBinaryExpr()` is elegant

### Weaknesses
- Instance methods hardcoded in compiler core (every new method = edit PirGenerator)
- No separation of concerns (method impl mixed with compiler logic)
- `resolveExpressionType()` is incomplete (falls back to DataType too easily)
- Dual dispatch systems (registry for static, hardcoded for instance)

---

## 4. Comparison Matrix

| Dimension | Opshin | Scalus | Plutus-Java |
|-----------|--------|--------|-------------|
| **Dispatch pattern** | Type-class polymorphism | 3-layer hybrid (inline + macro + pattern match) | Centralized if/else |
| **Method-to-UPLC coupling** | Type class owns methods | Extension methods + compiler | Compiler core owns everything |
| **Files to modify for new method** | 1 (type class) | 1 (extension method) or 1 (compiler) | 2 (PirGenerator + return types) |
| **Lines to add per method** | 5-15 | 3-5 (inline) or 10-20 (compiler) | 15-50 |
| **Type system** | Custom inference (pre-compile pass) | Leverages Scala 3 compiler | JavaParser + manual resolution |
| **IR typed?** | Yes (Pluthon is typed) | Yes (SIR is typed) | Yes (PIR has PirType) |
| **Operator dispatch** | Type-polymorphic (`_binop_bin_fun`) | Type-directed pattern match | Type-directed switch in `generateBinaryExpr` |
| **Static method dispatch** | N/A (Python uses builtins) | Macro-generated map | StdlibRegistry (Map<String, Builder>) |
| **Instance method dispatch** | `type.attribute(name)` | `inline` extension + pattern match | Hardcoded if/else in PirGenerator |
| **Scalability (current)** | ~20 methods (elegant) | ~50+ methods (very scalable) | ~15 methods (functional) |
| **Scalability (to 100+)** | Easy | Easy | Difficult without refactoring |

---

## 5. Key Insights

### 5.1 All Three Must Write Transformers
Every supported method in every project ultimately becomes a handwritten UPLC builtin call or composite term. There is no magic. The question is purely **organizational**: where do these transformers live and how are they discovered?

### 5.2 Opshin's Pattern Is Most Elegant
Each type owns its methods. The compiler calls `type.attribute("methodName")` and gets back the UPLC implementation. Zero coupling between compiler dispatch logic and method implementations. This is the **Strategy pattern** applied per-type.

### 5.3 Our Proposed Approach Mirrors Opshin's Philosophy in a Java-Idiomatic Way

Opshin uses **OOP polymorphism**: each type class has virtual `attribute()` methods. In Python, types are classes you can subclass freely.

In Plutus-Java, `PirType` is a **sealed interface with records** (`IntegerType()`, `StringType()`, etc.) — you can't add methods to records without breaking the sealed hierarchy. So we use the Java-idiomatic equivalent: a **single registry** (`TypeMethodRegistry`) that maps `(PirType, methodName) → handler`. This achieves the same decoupling:

| Concept | Opshin | Plutus-Java (proposed) |
|---------|--------|----------------------|
| "Type owns its methods" | `IntegerType.attribute("abs")` returns lambda | `registry.dispatch(scope, "abs", args, IntegerType)` returns PIR |
| Where implementations live | Inside type class (Python OOP) | In registry factory method (Java FunctionalInterface) |
| Adding a method | Add to one type class | Call `reg.register(...)` in one place |
| Compiler coupling | Zero — compiler calls `type.attribute()` | Zero — compiler calls `registry.dispatch()` |

Both approaches decouple method implementations from the compiler core. The difference is purely the idiom: Python uses virtual dispatch on classes, Java uses map lookup with functional interfaces. Our existing `StdlibRegistry` already proves this pattern works in our codebase.

### 5.4 Scalus's `inline` Is Most Pragmatic
By leveraging `inline` extension methods, most methods compile to builtin calls **before the Scalus plugin even runs**. The compiler plugin never needs to know about `ByteString.slice()` — Scala's own compiler eliminates it. This is impossible in JavaParser (which doesn't execute code), but the concept of **pre-expansion** is powerful.

### 5.5 Plutus-Java's StdlibRegistry Is the Right Foundation
The `StdlibRegistry` already implements the registry pattern for static methods. It just needs to be **extended to instance methods**. The infrastructure is there.

---

## 6. Implementation Status

### Phase 1: Instance Method Registry -- IMPLEMENTED

`TypeMethodRegistry` has been implemented in `julc-compiler/src/main/java/.../pir/TypeMethodRegistry.java` with:

- **20 method registrations** across 8 type keys (IntegerType, ByteStringType, StringType, DataType, RecordType, SumType, ListType, OptionalType)
- **`InstanceMethodHandler`** functional interface: `(scope, args, scopeType, argTypes) -> PirTerm`
- **`ReturnTypeResolver`** functional interface: `(scopeType) -> PirType`
- **`defaultRegistry()`** factory method registers all methods
- Key scheme: `"TypeSimpleName.methodName"` (e.g., `"IntegerType.abs"`, `"ListType.contains"`)

Helper methods extracted to `PirHelpers.java`: `builtinApp2`, `wrapDecode`, `generateListLength`, `generateListContains`, `buildContainsEquality`, `generateFoldl`.

`PirGenerator.generateMethodCall()` reduced from ~170 lines of if/else to ~15 lines of registry dispatch. `resolveMethodCallReturnType()` type-specific switches replaced with `typeMethodRegistry.resolveReturnType()`.

**Tests**: 365 compiler tests pass (35 new TypeMethodRegistryTest + 12 new edge-case tests + 318 existing).

### Phase 2: Return Type Registry -- IMPLEMENTED

Bundled with Phase 1. Each `MethodRegistration` record contains both the handler and a `ReturnTypeResolver`. Generic return types (e.g., `Optional.get()` returns `elemType`, `List.head()` returns `elemType`) are handled via the resolver receiving the `scopeType`.

### Phase 3: Operator Registry (Optional, Lower Priority)

Not yet implemented. `generateBinaryExpr()` still uses a switch statement. This is lower priority since operators change less frequently than methods.

### Summary

| Phase | What | Status |
|-------|------|--------|
| 1 | `TypeMethodRegistry` for instance methods | **DONE** |
| 2 | Return types bundled with method registration | **DONE** (bundled with Phase 1) |
| 3 | Operator registry (optional) | Not started |

---

## 7. Conclusion

**Every standard type method requires an equivalent UPLC transformer.** This is universal across Opshin, Scalus, and Plutus-Java.

With `TypeMethodRegistry` implemented, the comparison table is now:

| Project | Transformers Live In... | Scaling Cost |
|---------|------------------------|--------------|
| Opshin | Type classes (each type owns its methods) | O(1) per type |
| Scalus | Extension methods + compiler plugin | O(1) for inline, O(n) for compiler |
| Plutus-Java | `TypeMethodRegistry` (registry + functional interfaces) | O(1) per method |

Adding a new instance method now requires a single `reg.register(...)` call in `TypeMethodRegistry.defaultRegistry()` -- no changes to `PirGenerator` or any other compiler core file. This aligns with Opshin's philosophy (types own their methods) using Java-idiomatic patterns (registry + functional interfaces).
