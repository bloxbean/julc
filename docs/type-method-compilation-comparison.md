# Standard Type Method Compilation: Opshin vs Scalus vs JuLC

## Context

JuLC now supports ~50 instance methods on standard Java types (`BigInteger`, `String`, `byte[]`, `List`, `Map`, `Optional`, `Value`, `Pair`) via the `TypeMethodRegistry`. This report compares our approach with **Opshin** (Python→UPLC) and **Scalus** (Scala→UPLC) to understand how they handle standard library type methods, what architectural patterns they use, and where each project sits architecturally.

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

## 3. JuLC (Current)

**Pipeline**: `Java Source → JavaParser → SubsetValidator → TypeResolver → PirGenerator → UplcGenerator → Program`

### Architecture: Registry-Based Dispatch (TypeMethodRegistry)

Instance method dispatch uses `TypeMethodRegistry` — a clean registry pattern mapping `(PirType, methodName)` to handlers:

```java
// TypeMethodRegistry.java — registry-based dispatch
reg.register("IntegerType", "abs", (scope, args, scopeType, argTypes) -> {
    return PirHelpers.builtinApp2(IfThenElse,
        PirHelpers.builtinApp2(LessThanInteger, scope, Const(0)),
        PirHelpers.builtinApp2(SubtractInteger, Const(0), scope),
        scope);
}, scopeType -> new PirType.IntegerType());

reg.register("ListType", "contains", (scope, args, scopeType, argTypes) -> {
    var lt = (PirType.ListType) scopeType;
    return PirHelpers.generateListContains(scope, args.get(0), lt);
}, scopeType -> new PirType.BoolType());
```

`PirGenerator.generateMethodCall()` is now ~15 lines of registry dispatch instead of the original ~170 lines of if/else chains.

### How Methods Are Added

To add a new method (e.g., `list.last()`):
1. Add `reg.register("ListType", "last", handler, returnTypeResolver)` in `TypeMethodRegistry.defaultRegistry()` (~10-20 lines)
2. Add test in `TypeMethodRegistryTest.java` or `TypeMethodsTest.java` (~20 lines)
3. Total: **30-40 lines in 1 file** + test. No changes to compiler core.

### Unified Dispatch Systems

| Call Style | Dispatch Mechanism | Example |
|-----------|-------------------|---------|
| Static: `ContextsLib.signedBy(x, y)` | `StdlibRegistry` (registry pattern) | Decoupled, scalable |
| Instance: `myList.contains(x)` | `TypeMethodRegistry` (registry pattern) | Decoupled, scalable |

Both static and instance method dispatch now use the registry pattern.

### Strengths
- Registry-based dispatch: adding a method = one `reg.register()` call
- ~50 methods registered across 11 type keys
- Named RecordType dispatch for domain types (e.g., `Value.lovelaceOf()`)
- `StdlibRegistry` + `TypeMethodRegistry` provide uniform extensibility
- Type-aware `==`/`!=`/`+` dispatch in `generateBinaryExpr()` is elegant

### Weaknesses
- `resolveExpressionType()` is incomplete (falls back to DataType too easily)
- No equivalent of Scalus's `inline` extension methods (cannot pre-expand before compilation)

---

## 4. Comparison Matrix

| Dimension | Opshin | Scalus | JuLC |
|-----------|--------|--------|------|
| **Dispatch pattern** | Type-class polymorphism | 3-layer hybrid (inline + macro + pattern match) | Registry-based (TypeMethodRegistry) |
| **Method-to-UPLC coupling** | Type class owns methods | Extension methods + compiler | Registry owns methods |
| **Files to modify for new method** | 1 (type class) | 1 (extension method) or 1 (compiler) | 1 (TypeMethodRegistry) |
| **Lines to add per method** | 5-15 | 3-5 (inline) or 10-20 (compiler) | 10-20 |
| **Type system** | Custom inference (pre-compile pass) | Leverages Scala 3 compiler | JavaParser + manual resolution |
| **IR typed?** | Yes (Pluthon is typed) | Yes (SIR is typed) | Yes (PIR has PirType) |
| **Operator dispatch** | Type-polymorphic (`_binop_bin_fun`) | Type-directed pattern match | Type-directed switch in `generateBinaryExpr` |
| **Static method dispatch** | N/A (Python uses builtins) | Macro-generated map | StdlibRegistry (Map<String, Builder>) |
| **Instance method dispatch** | `type.attribute(name)` | `inline` extension + pattern match | TypeMethodRegistry (Map<String, Handler>) |
| **Scalability (current)** | ~20 methods (elegant) | ~50+ methods (very scalable) | ~50 methods (scalable) |
| **Scalability (to 100+)** | Easy | Easy | Easy (registry pattern) |

---

## 5. Key Insights

### 5.1 All Three Must Write Transformers
Every supported method in every project ultimately becomes a handwritten UPLC builtin call or composite term. There is no magic. The question is purely **organizational**: where do these transformers live and how are they discovered?

### 5.2 Opshin's Pattern Is Most Elegant
Each type owns its methods. The compiler calls `type.attribute("methodName")` and gets back the UPLC implementation. Zero coupling between compiler dispatch logic and method implementations. This is the **Strategy pattern** applied per-type.

### 5.3 Our Proposed Approach Mirrors Opshin's Philosophy in a Java-Idiomatic Way

Opshin uses **OOP polymorphism**: each type class has virtual `attribute()` methods. In Python, types are classes you can subclass freely.

In JuLC, `PirType` is a **sealed interface with records** (`IntegerType()`, `StringType()`, etc.) — you can't add methods to records without breaking the sealed hierarchy. So we use the Java-idiomatic equivalent: a **single registry** (`TypeMethodRegistry`) that maps `(PirType, methodName) → handler`. This achieves the same decoupling:

| Concept | Opshin | JuLC |
|---------|--------|------|
| "Type owns its methods" | `IntegerType.attribute("abs")` returns lambda | `registry.dispatch(scope, "abs", args, IntegerType)` returns PIR |
| Where implementations live | Inside type class (Python OOP) | In registry factory method (Java FunctionalInterface) |
| Adding a method | Add to one type class | Call `reg.register(...)` in one place |
| Compiler coupling | Zero — compiler calls `type.attribute()` | Zero — compiler calls `registry.dispatch()` |

Both approaches decouple method implementations from the compiler core. The difference is purely the idiom: Python uses virtual dispatch on classes, Java uses map lookup with functional interfaces. Our existing `StdlibRegistry` already proves this pattern works in our codebase.

### 5.4 Scalus's `inline` Is Most Pragmatic
By leveraging `inline` extension methods, most methods compile to builtin calls **before the Scalus plugin even runs**. The compiler plugin never needs to know about `ByteString.slice()` — Scala's own compiler eliminates it. This is impossible in JavaParser (which doesn't execute code), but the concept of **pre-expansion** is powerful.

### 5.5 JuLC's Registry Pattern Is Complete
Both `StdlibRegistry` (static methods) and `TypeMethodRegistry` (instance methods) now use the registry pattern, providing a unified, extensible architecture for all method dispatch.

---

## 6. Implementation Status

### Phase 1: Instance Method Registry -- IMPLEMENTED

`TypeMethodRegistry` has been implemented in `julc-compiler/src/main/java/.../pir/TypeMethodRegistry.java` with:

- **~50 method registrations** across 11 type keys (IntegerType, ByteStringType, StringType, DataType, RecordType, SumType, ListType, OptionalType, MapType, PairType, named Value)
- **`InstanceMethodHandler`** functional interface: `(scope, args, scopeType, argTypes) -> PirTerm`
- **`ReturnTypeResolver`** functional interface: `(scopeType) -> PirType`
- **`defaultRegistry()`** factory method registers all methods
- Key scheme: `"TypeSimpleName.methodName"` (e.g., `"IntegerType.abs"`, `"ListType.contains"`)

Helper methods extracted to `PirHelpers.java`: `builtinApp2`, `wrapDecode`, `generateListLength`, `generateListContains`, `buildContainsEquality`, `generateFoldl`.

`PirGenerator.generateMethodCall()` reduced from ~170 lines of if/else to ~15 lines of registry dispatch. `resolveMethodCallReturnType()` type-specific switches replaced with `typeMethodRegistry.resolveReturnType()`.

**Tests**: 898 compiler tests pass (including TypeMethodRegistryTest, TypeMethodsTest, and all existing tests).

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

**Every standard type method requires an equivalent UPLC transformer.** This is universal across Opshin, Scalus, and JuLC.

With `TypeMethodRegistry` fully implemented (~50 methods), the comparison table is now:

| Project | Transformers Live In... | Scaling Cost |
|---------|------------------------|--------------|
| Opshin | Type classes (each type owns its methods) | O(1) per type |
| Scalus | Extension methods + compiler plugin | O(1) for inline, O(n) for compiler |
| JuLC | `TypeMethodRegistry` (registry + functional interfaces) | O(1) per method |

Adding a new instance method requires a single `reg.register(...)` call in `TypeMethodRegistry.defaultRegistry()` -- no changes to `PirGenerator` or any other compiler core file. This aligns with Opshin's philosophy (types own their methods) using Java-idiomatic patterns (registry + functional interfaces).

### Method Count Breakdown

| Type Key | Methods | Examples |
|----------|---------|---------|
| IntegerType | 15 | abs, negate, max, min, equals, add, subtract, multiply, divide, remainder, mod, signum, compareTo, intValue, longValue |
| ByteStringType | 3 | length, equals, hash |
| StringType | 2 | length, equals |
| ListType | 11 | size, isEmpty, head, tail, get, contains, reverse, concat, take, drop, prepend |
| MapType | 8 | get, containsKey, size, isEmpty, keys, values, insert, delete |
| Value (named) | 3 | lovelaceOf, isEmpty, assetOf |
| PairType | 2 | key, value |
| OptionalType | 3 | isPresent, isEmpty, get |
| DataType | 1 | equals |
| RecordType | 1 | equals |
| SumType | 1 | equals |
