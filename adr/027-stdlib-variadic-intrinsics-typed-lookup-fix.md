# ADR-027: Stdlib Variadic Intrinsics, Unique Token Name Helpers, and the Typed-Lookup Bypass Fix

**Date**: 2026-07-21
**Status**: Proposed (implementation complete locally; PR to follow after ADR review)

---

## Context

User-written on-chain code kept repeating three verbose, error-prone idioms:

**1. Fixed-size Data-list construction via nested `mkCons` chains** (e.g. ZK public
inputs, script parameter lists):

```java
PlutusData publicInputs = Builtins.listData(Builtins.mkCons(
        Builtins.iData(pub0),
        Builtins.mkCons(Builtins.iData(pub1),
                Builtins.mkCons(Builtins.iData(pub2), Builtins.mkNilData()))));
```

**2. Nested 2-arg `appendByteString` calls to concatenate 3+ bytestrings**:

```java
Builtins.blake2b_256(
        Builtins.appendByteString(
                Builtins.appendByteString(ref.txId().hash(),
                        Builtins.integerToByteString(true, 8L, ref.index())),
                recipientPkh));
```

**3. Unique-token-name derivation from a `TxOutRef`** — `hash(txId ++ indexBytes)`.
A survey of julc-examples found this at three independent on-chain sites
(`UVerifyProxy`, `UVerifyTxLib`, `CfProxyValidator`), each hand-rolling a slightly
different variant (sha2_256 vs sha3_256, minimal-width vs 2-byte vs decimal-string
index encoding).

### Compiler constraints that shaped the design

- **No varargs**: `LibraryCompiler.computeMethodType` models a varargs parameter as
  arity-1 of the element type; array creation/access is rejected by `SubsetValidator`
  (only `new byte[]{literals}` is allowed). A Java-source `of(PlutusData... items)`
  cannot work.
- **No overloading**: `StdlibRegistry`, `LibraryMethodRegistry`, and
  `TypeMethodRegistry` all key methods by `"ClassName.methodName"` — a second
  registration silently overwrites the first. `concat(a,b)` + `concat(a,b,c)` cannot
  coexist as Java-source overloads.
- **Variadic IS possible at the PIR layer**: `StdlibRegistry` builders receive the
  already-compiled `List<PirTerm>` args of any arity (the pattern `JulcList.of` was
  already registered with, though undocumented and without element wrapping).

---

## Decisions

### D1. `JulcList.of(...)` auto-wraps elements (typed-lookup branch)

`JulcList.of` was already reachable from on-chain code as a variadic PIR intrinsic,
but it consed elements raw — `JulcList.of(bigInt1, bigInt2)` produced an ill-typed
`MkCons(Integer, Data[])` that failed at eval, and the `JulcList` javadoc wrongly
claimed the factories were "off-chain only". Rather than add a new API, we fixed the
existing one:

- **New typed branch in `StdlibRegistry.lookup(className, method, args, argTypes)`**:
  each element passes through `PirHelpers.wrapEncode(arg, argType)` before `MkCons` —
  `BigInteger` → `IData`, `byte[]` → `BData`, `boolean` → `ConstrData(0/1)`,
  `String` → `BData(EncodeUtf8)`, `PlutusData`/records → pass-through. This is the
  same mechanism `list.prepend(elem)` already used.
- **`isJulcListClass()`** accepts both `"JulcList"` and the FQCN, future-proofing
  against `knownFqcns` changes.
- **New JVM overload `Builtins.listData(JulcList<?>)`** so
  `Builtins.listData(JulcList.of(pkh, recipient))` is javac-legal and behaves
  identically off-chain (element wrapping via a private `elementToData`, throwing
  `IllegalArgumentException` for unsupported element types).
- **Javadoc corrected** in `JulcList.java`: the factories ARE the on-chain
  intrinsics; auto-wrap rules documented.

Pattern 1 becomes:

```java
PlutusData inputs = Builtins.listData(JulcList.of(pkh, recipient));
PlutusData publicInputs = Builtins.listData(JulcList.of(pub0, pub1, pub2, pub3, pub4));
```

#### D1 addendum (review, 2026-07-22): `.toPlutusData()` as the preferred surface

ADR review asked whether `JulcList.of(pub0, ..., pub4).toPlutusData()` is possible
instead of wrapping with `Builtins.listData(...)`. It is, and it becomes the
preferred idiom; the implementation PR will include it:

- **On-chain**: one `TypeMethodRegistry` registration per container type —
  `ListType.toPlutusData` → apply the `ListData` builtin to the scope,
  `MapType.toPlutusData` → `MapData`. This is the same instance-dispatch path
  `list.head()` / `list.take(n)` already use.
- **Off-chain**: a `toPlutusData()` default method on `JulcList` (mirroring the
  `Builtins.listData(JulcList<?>)` element-wrapping rules) so the same source is
  javac-legal and JVM-consistent. The name matches the existing
  `PlutusDataConvertible.toPlutusData()` convention used by ledger records;
  `JulcList` may extend `PlutusDataConvertible`, which additionally lets
  `Builtins.asPlutusData(Object)` accept lists.
- **Bonus**: the method works on *any* `JulcList`/`JulcMap` value, not just `of(...)`
  literals — it retires the `Builtins.listData((PlutusData)(Object) list)` re-wrap
  cast idiom found in the survey (`CfIdentityValidator`).

Implementation notes to verify:

1. **Chained-scope type inference**: dispatching `JulcList.of(...).toPlutusData()`
   requires the `MkCons` chain to infer as `ListType`. If `MkCons` is missing from
   `inferBuiltinReturnType`, the scope falls back to `DataType` and dispatch misses —
   same bug class as the earlier `UnIData`/`UnBData` inference fixes; add the table
   entry if needed.
2. **JVM wrapping location**: `JulcList` lives in `julc-core`, which cannot call
   `julc-stdlib`'s `Builtins` — the default method needs its own copy of the (small)
   element-wrapping table, or the helper moves to `julc-core` and `Builtins.listData`
   delegates to it.

Both spellings coexist: `Builtins.listData(...)` stays as the builtin-parity form;
docs and examples lead with `.toPlutusData()`.

```java
PlutusData publicInputs = JulcList.of(pub0, pub1, pub2, pub3, pub4).toPlutusData();
```

### D2. Variadic `Builtins.concat(byte[], byte[], byte[]...)`

- **PIR side**: registered in `StdlibRegistry.registerBuiltins` special-cases as a
  right-fold of `AppendByteString` over any arity.
- **JVM side**: a real varargs implementation in `Builtins.java` (javac enforces
  arity ≥ 2 at call sites).
- **Why `Builtins` and not `ByteStringLib`**: `ByteStringLib` is `@OnchainLibrary`,
  so `LibraryCompiler` compiles every static method — a varargs body with array
  iteration would break stdlib compilation (see constraints above). `Builtins` is
  never source-compiled; all its calls resolve through registry keys.
  `ByteStringLib.append(a, b)` stays for the 2-arg case and its javadoc now points
  to `concat` for 3+ parts.

Pattern 2 becomes `Builtins.blake2b_256(Builtins.concat(a, b, c))`.

### D3. `ValuesLib.refBytes(ref)` + `ValuesLib.uniqueTokenName(ref)`

Plain Java-source stdlib methods (ordinary subset logic — no registry work needed):

```java
public static byte[] refBytes(TxOutRef ref) {
    byte[] idxBytes = Builtins.integerToByteString(true, 2, ref.index());
    return Builtins.appendByteString(ref.txId().hash(), idxBytes);
}

public static byte[] uniqueTokenName(TxOutRef ref) {
    return Builtins.blake2b_256(refBytes(ref));
}
```

Design choices, made deliberately and documented in javadoc + stdlib guide:

- **blake2b_256** — cheapest Plutus hash; output is exactly 32 bytes, the maximum
  asset-name length.
- **Fixed 2-byte big-endian index** (`integerToByteString(true, 2, index)`) instead
  of minimal-width (`width 0`): minimal-width encodes index 0 as *empty bytes*
  (with a historical JVM/UPLC divergence on that exact case), whereas width 2 is
  deterministic, JVM/UPLC-identical, and index > 65535 (impossible for real tx
  outputs) fails loudly.
- **`refBytes` exposed separately** so protocols that need a different hash keep the
  concat-and-encode part: `CryptoLib.sha2_256(ValuesLib.refBytes(ref))`. The three
  existing julc-examples derivations are protocol-fixed and are NOT migrated; the
  helpers target new contracts.
- **Hosted in `ValuesLib`** (token domain, already ledger-aware) — avoids adding a
  new lib class to the 5 class-list registration sites (`STDLIB_FQCNS`,
  `stdlibClassFqcns`, `StdlibCatalog`, REPL, tools).

### D4. Rejected alternatives

- `ListsLib.of(...)` as Java-source varargs — impossible (no varargs support).
- `ByteStringLib.concat3/concat4` distinct-name methods — works but scales badly;
  the PIR variadic builder covers every arity with one registration.
- A dedicated `TokenLib` — unnecessary surface; `ValuesLib` is the natural home.

---

## Regression found: the `::lookup` typed-lookup bypass (production bug)

### Symptom

The first version of the auto-wrap tests passed under `JulcEval` (julc-stdlib
harness) but failed under `StdlibCompileEvalTest` (julc-compiler harness) with
`MkCons: element type Integer[] does not match list element type Data[]` — elements
were not being wrapped even though the typed branch existed.

### Root cause

`StdlibLookup` is a `@FunctionalInterface` whose single abstract method is the
**3-arg** `lookup(className, method, args)`; the **4-arg** typed
`lookup(..., argTypes)` is a default method that delegates to the 3-arg one.
`StdlibRegistry` overrides the 4-arg default with the typed coercion logic.

Constructing the compiler as `new JulcCompiler(stdlib::lookup)` creates a **lambda
that implements only the 3-arg SAM**. The lambda inherits the interface's default
4-arg method — which delegates to the 3-arg — so `StdlibRegistry`'s typed override
is silently discarded. Every typed coercion registered there never ran:

- `Optional.of(x)` element-type encoding
- `ListsLib.prepend` / `ListsLib.contains` element wrapping
- `MapLib.insert` / `member` / `lookup` / `delete` key/value wrapping
- (and the new `JulcList.of` auto-wrap)

### Blast radius

`grep 'JulcCompiler(.*::lookup'` found **~40 call sites**, including four
**production** paths:

| Site | Impact |
|---|---|
| `julc-gradle-plugin/CompileJulcTask.java:60` | ALL user Gradle builds compiled without typed coercions |
| `julc-annotation-processor/JulcAnnotationProcessor.java:190` | ALL annotation-processor builds (the primary user path) |
| `julc-playground/JulcPlaygroundServer.java:43` | Playground compilations |
| `julc-e2e-tests`, `julc-plugin-test`, ~12 julc-compiler test classes | Test harnesses exercised the untyped path, masking the gap |

The testkit paths (`ValidatorTest`, `SourceDiscovery`, `JulcEval`) passed the
registry object correctly, which is why in-repo stdlib tests never caught it — the
exact "works via JulcCompiler directly, broken under Gradle" split ADR-023/024
warned about in a different subsystem.

### Fix

Mechanical: `new JulcCompiler(stdlib::lookup)` → `new JulcCompiler(stdlib)` at all
~40 sites (`StdlibRegistry` implements `StdlibLookup`; passing the object preserves
the override). `CompositeStdlibLookup` already propagated the 4-arg call correctly.

**Rule going forward: never pass `registry::lookup` where a `StdlibLookup` is
expected — pass the registry object.** A future hardening option is to remove
`@FunctionalInterface` and make the 4-arg method abstract so the method-reference
form no longer compiles; deferred because it is a breaking interface change.

### Second latent bug exposed by the fix

With the typed path now active in the julc-compiler harness, one pre-existing test
regressed: `StdlibCompileEvalTest$MapLibEval.memberTrue` —
`MapLib.member(redeemer, key)` where the map argument is raw `PlutusData`. The
typed MapLib/ListsLib branches assumed the container argument was already a UPLC
pair list / list (the `MapType`/`ListType` variable convention), and crashed with
`NullList: expected list, got Data` on raw `MapData` Data.

Fix: two coercion helpers in `StdlibRegistry` —

```java
asPairList(map, type)  // DataType → UnMapData(map), MapType → pass-through
asUplcList(list, type) // DataType → UnListData(list), ListType → pass-through
```

applied to the container argument of `ListsLib.prepend`, `ListsLib.contains`,
`MapLib.insert`, `member`, `lookup`, `delete`. Static lib calls now accept both
typed (`JulcMap`/`JulcList` variables) and raw-Data (`redeemer`) arguments.

### Compatibility note

Builds through the Gradle plugin / annotation processor now emit different (correct)
UPLC for constructs that previously hit the untyped path — e.g.
`ListsLib.prepend(list, bigInteger)` now wraps the element with `IData`. Previously
those constructs produced ill-typed terms that failed at eval time, so this is
strictly a fix, but **compiled script hashes may change** for validators using the
affected constructs. Anyone pinning script hashes should recompile and re-derive
addresses.

---

## Implementation summary

| File | Change |
|---|---|
| `julc-stdlib/.../StdlibRegistry.java` | `JulcList.of` typed auto-wrap branch; variadic `Builtins.concat` registration; `asPairList`/`asUplcList` helpers applied to 6 typed branches; `isJulcListClass` |
| `julc-stdlib/.../Builtins.java` | `concat(byte[], byte[], byte[]...)` JVM impl; `listData(JulcList<?>)` overload + `elementToData` |
| `julc-stdlib/.../lib/ValuesLib.java` | `refBytes(TxOutRef)`, `uniqueTokenName(TxOutRef)` (+ `TxOutRef` import) |
| `julc-stdlib/.../lib/ByteStringLib.java` | `append` javadoc cross-reference to `concat` |
| `julc-core/.../types/JulcList.java` | Factory javadoc corrected (on-chain intrinsics, auto-wrap rules) |
| `julc-gradle-plugin/.../CompileJulcTask.java` | `::lookup` → registry object (production fix) |
| `julc-annotation-processor/.../JulcAnnotationProcessor.java` | `::lookup` → registry object (production fix) |
| `julc-playground/.../JulcPlaygroundServer.java` | `::lookup` → registry object (production fix) |
| ~14 test classes (julc-compiler, julc-e2e-tests, julc-plugin-test) | `::lookup` → registry object |
| `docs/.../stdlib/stdlib-guide.md` | ValuesLib quick-ref rows; "Building Lists", "Unique Token Names" sections; `concat` in ByteStringLib example |

Pending (D1 addendum, to land in the implementation PR):
`JulcList.toPlutusData()` / `JulcMap.toPlutusData()` — TypeMethodRegistry
registrations (`ListType`/`MapType` → `ListData`/`MapData`), `JulcList` default
method + element wrapping in `julc-core`, `MkCons` entry in
`inferBuiltinReturnType` if missing, docs updated to lead with this form.

## Tests

- **`StdlibCompileEvalTest$JulcListTests`** (+4): auto-wrap for `BigInteger` and
  `byte[]` elements; `listData(JulcList.of(a,b))` equals a hand-built `ListData`
  end-to-end; 5-element ZK-public-inputs shape.
- **`VariadicIntrinsicsTest`** (new, julc-stdlib, 13 tests): `concat` with 2/3/5
  parts, empty parts, equivalence vs nested `appendByteString`, JVM parity;
  `listData(JulcList.of(...))` composition on-chain + JVM, mixed-element JVM
  wrapping, unsupported-element error. Uses `JulcEval.forSource` (PIR intrinsics
  are not reachable via `forClass`).
- **`ValuesLibTest$UniqueTokenNameTests`** (+9): `refBytes` byte-exact for indices
  0/1/256/65535 (index 0 is the parity-critical case), failure for 65536;
  `uniqueTokenName` == `blake2b_256(refBytes)` (cross-checked through a
  `CryptoLib` eval), 32-byte length, differs by index and by txId.

## Verification

- Full repo test suite green (`./gradlew test`, all modules, incl. the previously
  bypassed harnesses now exercising the typed path).
- `julc-test-quick` composite gate: publish-local → gradle-plugin smoke →
  stdlib-usage smoke → error-paths (6 negative subcases) — **all PASS** against the
  freshly published `0.1.0-pre14` artifacts.
- Operational note: the `julc-smoke-gradle-stdlib-usage` skill's sample validator
  uses stale `OutputLib.outputsAt(TxInfo, ...)` signatures (current API:
  `outputsAt(JulcList<TxOut>, Address)`). The JuLC compilation succeeded; only
  javac rejected the sample. The skill fixture should be updated.

## Consequences

- The three verbose idioms have one-line replacements; docs lead with them.
- Typed-lookup coercions are active in every compilation path for the first time;
  the `::lookup` anti-pattern is documented (and a compile-time guard via making
  the 4-arg method abstract remains an option).
- Static `MapLib`/`ListsLib` calls uniformly accept raw-Data and typed containers.
- Script-hash compatibility caveat above applies to the affected constructs.
