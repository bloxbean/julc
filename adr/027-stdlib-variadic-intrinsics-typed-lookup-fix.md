# ADR-027: Stdlib Variadic Intrinsics, Unique Token Name Helpers, and the Typed-Lookup Bypass Fix

**Date**: 2026-07-21 (review findings folded in 2026-07-22)
**Status**: Accepted and implemented (2026-07-23)

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

> Naming note — there are TWO julc-examples trees, and this ADR references
> both: (1) the **external repo**
> [`github.com/bloxbean/julc-examples`](https://github.com/bloxbean/julc-examples)
> (`main` @ `5ead7918d14635c9238f1699e3a58a8aa4d7b054`, verified 2026-07-23) —
> source of the idiom survey above
> (`UVerifyProxy`, `UVerifyTxLib`, `CfProxyValidator`); (2) the **in-repo
> Gradle module** `julc-examples/` (settings.gradle) — location of the three
> `ValidatorTest.compile(source, stdlib::lookup)` test sites and the README
> snippet cited under R2. Earlier review rounds talked past each other by each
> searching only one of the two trees.

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
  identically off-chain (delegating to the shared core conversion table, which
  throws `IllegalArgumentException` for unsupported element types).
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
preferred idiom; the implementation includes it:

- **On-chain: no compiler changes needed** (verified during review, R4). Two earlier
  assumptions in this addendum were wrong:
  - `PirGenerator` already auto-recognizes any no-arg `.toPlutusData()` call and
    routes it through `PirHelpers.wrapEncode(scope, scopeType)`
    (PirGenerator.java:1001-1004), which maps `ListType` → `ListData` and
    `MapType` → `MapData`. No `TypeMethodRegistry` registrations are required.
  - `MkCons` already infers as `ListType(DataType)` in `inferBuiltinReturnType`
    (TypeInferenceHelper.java:265), so the chained scope
    `JulcList.of(...).toPlutusData()` dispatches correctly. No inference entry is
    required.

  The first implementation step is therefore a **chained-call regression test**
  proving `JulcList.of(...).toPlutusData()` compiles and evaluates today, locking
  the behavior in before any JVM-side work.
- **Off-chain**: a `toPlutusData()` default method on `JulcList` (mirroring the
  `Builtins.listData(JulcList<?>)` element-wrapping rules) so the same source is
  javac-legal and JVM-consistent. **Module boundary (R3)**:
  `PlutusDataConvertible` lives in `julc-ledger-api`, which declares
  `api project(':julc-core')` — so `JulcList` (julc-core) CANNOT extend it without
  a circular dependency. Instead, introduce a core-level conversion interface
  (e.g. `com.bloxbean.cardano.julc.core.ToPlutusData`) that
  `PlutusDataConvertible` extends; the element-wrapping table lives in julc-core
  and `Builtins.listData(JulcList<?>)` delegates to it. `Builtins.asPlutusData`
  then accepts both.
- **`JulcMap` iteration + `toPlutusData()` (R3)**: on-chain assoc maps (pair
  lists) may legally contain duplicate keys, and `JulcMap`'s `keys()` + `get()`
  surface collapses duplicates (`get` returns the first match).

  An earlier revision of this addendum specified
  `entries(): JulcList<Tuple2<K,V>>` as an *identity* operation — **retracted as
  unimplementable**: `Tuple2` is registered as a `RecordType`
  (TypeResolver.java:316), i.e. its on-chain representation is
  `ConstrData(0, [first, second])` — a Data value accessed via `UnConstrData` —
  and that contract is already load-bearing (`MathLib.divMod`/`quotRem`
  construct it). Map entries, by contrast, are **native UPLC pairs**
  (`PairType`, accessed via `FstPair`/`SndPair` — see the `MapType.head()`
  registration, TypeMethodRegistry.java:678: "returns native pair"). One Java
  type cannot carry both representations: the compiler lowers `t.first()` from
  the static type alone, so either lowering crashes on the other's values.

  **Decision (2026-07-22): the iteration surface is split out to
  [ADR-028](028-julcpair-native-pair-type.md)** — a dedicated `JulcPair<K,V>`
  native-pair type with `JulcMap.entries()`, a retyped `head()`, and an
  explicit allowlist of `JulcList` operations on native-pair lists. ADR-028 is
  design-only and will be implemented in a separate, later PR; no `entries()`
  method ships with this ADR.

  Within THIS ADR's scope, `JulcMap.toPlutusData()` needs no public iteration
  API at all: it is declared on the interface; on-chain the existing
  `wrapEncode` fallback already maps `MapType` → `MapData` (no compiler
  change, same as lists); off-chain `JulcAssocMap` implements it by walking
  its **private** ordered entry list, wrapping each key/value with the element
  rules — order and duplicate keys preserved without exposing new API.
- **Bonus**: the method works on *any* `JulcList`/`JulcMap` value, not just `of(...)`
  literals — it retires the `Builtins.listData((PlutusData)(Object) list)` re-wrap
  cast idiom found in the survey (`CfIdentityValidator`).

Both spellings coexist: `Builtins.listData(...)` stays as the builtin-parity form;
docs and examples lead with `.toPlutusData()`.

```java
PlutusData publicInputs = JulcList.of(pub0, pub1, pub2, pub3, pub4).toPlutusData();
```

### D2. Variadic `Builtins.concat(byte[], byte[], byte[]...)`

- **PIR side**: registered in `StdlibRegistry.registerBuiltins` special-cases as a
  right-fold of `AppendByteString` over the call-site arity (≥ 2, per R5 below).
- **JVM side**: a real varargs implementation in `Builtins.java` (javac enforces
  arity ≥ 2 at call sites).
- **Why `Builtins` and not `ByteStringLib`**: `ByteStringLib` is `@OnchainLibrary`,
  so `LibraryCompiler` compiles every static method — a varargs body with array
  iteration would break stdlib compilation (see constraints above). `Builtins` is
  never source-compiled; all its calls resolve through registry keys.
  `ByteStringLib.append(a, b)` stays for the 2-arg case and its javadoc now points
  to `concat` for 3+ parts.

Pattern 2 becomes `Builtins.blake2b_256(Builtins.concat(a, b, c))`.

**Review finding (R5) — enforce minimum arity in the registry**: the Java API
documents arity ≥ 2 (enforced by javac at Gradle-build call sites), but the
staged PIR registration accepted 0 args (→ empty bytes) and 1 arg (→ identity).
Source-string compilation paths (`JulcEval.forSource`, CLI check, playground)
never run javac, so a 1-arg `concat` would silently no-op there. The final
registry builder rejects fewer than 2 arguments, with negative compilation tests.

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
  asset-name length. Precise claim (R6): the name is **collision-resistant and
  deterministically tied to the consumed reference**, not mathematically
  "guaranteed unique" — uniqueness in practice relies on the minting policy
  actually enforcing that the seed `TxOutRef` is spent in the minting
  transaction. Javadoc and stdlib-guide wording to be aligned accordingly.
- **Fixed 2-byte big-endian index** (`integerToByteString(true, 2, index)`) instead
  of minimal-width (`width 0`): minimal-width encodes index 0 as *empty bytes*,
  whereas width 2 is deterministic and JVM/UPLC-identical for all valid indices
  (0–65535); index > 65535 (impossible for real tx outputs) fails loudly on-chain.
- **Review finding (R1) — JVM width validation incomplete**: the VM validates
  the full contract (BitwiseBuiltins.java:33): negative value → error, negative
  width → error, width > 8192 → error, value not fitting a positive width →
  error. The JVM `Builtins.integerToByteString` (Builtins.java:333) checks only
  the negative *value*; it pads undersized values but (a) silently returns
  *oversized* ones — `(true, 2, 65536)` returns 3 bytes while the VM throws —
  (b) treats negative width like width 0 instead of throwing, and (c) casts
  `long` width straight to `int` (overflow for huge widths, no 8192 cap). At
  review time, the JVM/UPLC-parity claim therefore held only for indices ≤ 65535
  with sane widths. The implementation validates `0 ≤ width ≤ 8192` before
  casting/allocating and throws when the value does not fit a positive width;
  **direct-JVM** tests for width −1, 8192, 8193, and an oversize value — the
  existing `ValuesLibTest` coverage exercises only the UPLC path via
  `JulcEval`, which is why this went unnoticed.
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

`grep 'JulcCompiler(.*::lookup'` found **~40 call sites**: three **production**
paths, plus the test harnesses that masked the gap:

| Site | Impact |
|---|---|
| `julc-gradle-plugin/CompileJulcTask.java:60` | ALL user Gradle builds compiled without typed coercions |
| `julc-annotation-processor/JulcAnnotationProcessor.java:190` | ALL annotation-processor builds (the primary user path) |
| `julc-playground/JulcPlaygroundServer.java:43` | Playground compilations |
| `julc-e2e-tests`, `julc-plugin-test`, ~12 julc-compiler test classes | Test harnesses exercised the untyped path, masking the gap |

At review time, the default testkit paths (`ValidatorTest`, `SourceDiscovery`,
`JulcEval`) passed
the registry object correctly, which is why in-repo stdlib tests never caught it
— the exact "works via JulcCompiler directly, broken under Gradle" split
ADR-023/024 warned about in a different subsystem. Explicit callers of
`ValidatorTest.compile(source, stdlib::lookup)` still reproduced the bypass.

### Fix

Mechanical: `new JulcCompiler(stdlib::lookup)` → `new JulcCompiler(stdlib)` at all
~40 direct-constructor sites (`StdlibRegistry` implements `StdlibLookup`; passing
the object preserves the override). `CompositeStdlibLookup` already propagated
the 4-arg call correctly.

**Review finding (R2) — remaining method-reference sites**: documentation still
taught the broken pattern at review time: `README.md:235` and
`docs/src/content/docs/getting-started.md:1112` show
`new JulcCompiler(stdlib::lookup)`, while `getting-started.md:1168` and
`julc-examples/README.md:35` show
`ValidatorTest.compile(javaSource, stdlib::lookup)`. Three tracked example tests
also pass the method reference through that overload:
`RealisticMintingTest.java:66`, `OutputValueCheckTest.java:95`, and
`RealisticVestingTest.java:77`. Before R2 landed, every copied snippet or example
reproduced the bypass.

**Decision (review, 2026-07-22): harden the interface now.** Remove
`@FunctionalInterface` from `StdlibLookup` and make the 4-arg `lookup` abstract,
so `::lookup` (and any lambda, and any partial implementation) stops compiling
**anywhere** a `StdlibLookup` is expected — the entire bug class becomes a
compile error, at every current and future API, with no CI guard to maintain.
(A grep guard was rejected: a pattern like `JulcCompiler(.*::lookup` would
already have missed the `ValidatorTest.compile(..., stdlib::lookup)` form.)

Migration cost in-repo is small and mechanically enforced. The three named
implementing classes (`StdlibRegistry`, `LibraryMethodRegistry`,
`CompositeStdlibLookup`) already override both methods. Review round 3 found a
**fourth, anonymous implementation** that did not — see "Third bypass" below.
The hardened interface also exposed two test-fixture lambdas missed by the
original grep audit; both are now explicit implementations of the typed and
untyped forms. The three tracked julc-examples method-reference call sites now
pass the registry object, and the four documentation snippets above are
corrected. Pre-1.0 is the time for this breaking interface change.

**Rule going forward: pass the registry object, never a method reference** —
now enforced by the compiler.

### Third bypass found in review: the `@NewType` adapter

`JulcCompiler.wrapWithNewTypeLookup` (JulcCompiler.java:952-970) wraps the
stdlib lookup in an **anonymous `StdlibLookup`** that overrides only the
**3-arg** method (plus `hasMethodsForClass`). It is applied — in both the
`compile()` and `compileMethod()` paths (JulcCompiler.java:245, 670) —
whenever the compilation declares at least one `@NewType`.

Before the fix, for any `@NewType`-containing compilation, the inherited default
4-arg lookup delegated to the adapter's 3-arg method, which delegated to the
base registry's **untyped** lookup — reintroducing the exact bypass this ADR
fixes, scoped to those compilations. All typed coercions (Optional.of,
`JulcList.of` auto-wrap, MapLib key wrapping, …) silently degraded there.

The implementation adds a typed 4-arg override to the adapter that
handles `NewType.of` identity and otherwise delegates
`base.lookup(className, methodName, args, argTypes)`. Regression test: a
compilation combining an `@NewType` declaration with a construct requiring
typed coercion (e.g. `JulcList.of(BigInteger)` or `MapLib.insert` with a
primitive key). R2's hardening and this adapter fix landed together because the
hardened interface makes an adapter without both lookup forms fail compilation.

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

### Compatibility notes

- **Script hashes**: builds through the Gradle plugin / annotation processor now
  emit different (correct) UPLC for constructs that previously hit the untyped
  path — e.g. `ListsLib.prepend(list, bigInteger)` now wraps the element with
  `IData`. Previously those constructs produced ill-typed terms that failed at
  eval time, so this is strictly a fix, but **compiled script hashes may
  change** for validators using the affected constructs. Anyone pinning script
  hashes should recompile and re-derive addresses.
- **Java source compatibility (R2, intentional)**: making the 4-arg `lookup`
  abstract breaks any **external** lambda, method-reference, or partial
  implementation of `StdlibLookup` at compile time — by design, since every
  such implementation silently drops typed coercions. External implementors
  must override both methods. An implementation that shares behavior should
  pass an explicit `PirType.DataType` entry for each argument when adapting an
  untyped call to the typed form; `null` is not a portable `argTypes` contract
  because not every lookup implementation accepts it. Acceptable pre-1.0;
  release-noted.
- **Java source/runtime compatibility (R3)**: declaring
  `JulcMap.toPlutusData()` requires external `JulcMap` implementations to
  implement the new method; invoking it against an older compiled
  implementation may produce `AbstractMethodError`. `JulcAssocMap` is the sole
  in-repo implementation and is updated in the same PR. Acceptable pre-1.0;
  release-noted.

---

## Implementation summary

| File | Change |
|---|---|
| `julc-stdlib/.../StdlibRegistry.java` | `JulcList.of` typed auto-wrap branch; variadic `Builtins.concat` registration; `asPairList`/`asUplcList` helpers applied to 6 typed branches; `isJulcListClass` |
| `julc-stdlib/.../Builtins.java` | Overflow-checked `concat(byte[], byte[], byte[]...)`; strict `integerToByteString` width/fit checks; `listData(JulcList<?>)` delegates to the core converter; object overloads accept `ToPlutusData` |
| `julc-stdlib/.../lib/ValuesLib.java` | `refBytes(TxOutRef)`, `uniqueTokenName(TxOutRef)` (+ `TxOutRef` import) |
| `julc-stdlib/.../lib/ByteStringLib.java` | `append` javadoc cross-reference to `concat` |
| `julc-core/.../ToPlutusData.java`, `PlutusDataConversions.java` | Core conversion contract and one shared, recursive element-encoding table |
| `julc-core/.../types/JulcList.java` | `toPlutusData()` default method; factory javadoc corrected (on-chain intrinsics, auto-wrap rules) |
| `julc-core/.../types/JulcMap.java`, `JulcAssocMap.java` | `toPlutusData()` contract and ordered, duplicate-preserving implementation over private entries |
| `julc-ledger-api/.../PlutusDataConvertible.java` | Extends the core `ToPlutusData` contract |
| `julc-compiler/.../StdlibLookup.java` | Typed 4-arg lookup is abstract; lambdas/method references/partial implementations no longer compile |
| `julc-compiler/.../JulcCompiler.java` | `@NewType` adapter propagates typed lookup |
| `julc-gradle-plugin/.../CompileJulcTask.java` | `::lookup` → registry object (production fix) |
| `julc-annotation-processor/.../JulcAnnotationProcessor.java` | `::lookup` → registry object (production fix) |
| `julc-playground/.../JulcPlaygroundServer.java` | `::lookup` → registry object (production fix) |
| test/example call sites | `::lookup` → registry object; two custom lookup lambdas made explicit |
| `julc-testkit/.../ArgConverter.java` | Delegates to the core conversion table, including `JulcList`/`JulcMap` |
| `julc-vm-java/.../BitwiseBuiltins.java` | Validates arbitrary-precision widths before narrowing, closing a discovered `BigInteger.intValue()` overflow bypass |
| `docs/.../stdlib/stdlib-guide.md` | ValuesLib quick-ref rows; "Building Lists", "Unique Token Names" sections; `concat` in ByteStringLib example |

### Review follow-ups (implemented 2026-07-23)

| # | Item |
|---|---|
| R1 | Implemented full JVM `integerToByteString` contract: `0 ≤ width ≤ 8192`, bounded/unbounded output-size checks, and JVM-vs-UPLC parity tests. |
| R2 | Hardened `StdlibLookup`, fixed the `@NewType` adapter, migrated all call sites/docs, and converted the two test-fixture lambdas exposed by compilation. |
| R3 | Added the core conversion interface/table plus list/map conversion; map order and duplicates are preserved. `JulcPair` remains solely in [ADR-028](028-julcpair-native-pair-type.md). |
| R4 | Added chained, variable-list, and variable-map `.toPlutusData()` regressions; no type-method registrations or other compiler changes were needed. |
| R5 | Enforced `concat` arity ≥ 2 in the PIR registry with zero/one-argument source-compilation tests. |
| R6 | Aligned javadoc and guide wording on collision resistance and policy enforcement. |

Docs now lead with `.toPlutusData()`.

## Tests

- **`StdlibCompileEvalTest$JulcListTests`** (+4): auto-wrap for `BigInteger` and
  `byte[]` elements; `listData(JulcList.of(a,b))` equals a hand-built `ListData`
  end-to-end; 5-element ZK-public-inputs shape.
- **`VariadicIntrinsicsTest`** (new, julc-stdlib, 18 tests): `concat` with 2/3/5
  parts, empty parts, equivalence vs nested `appendByteString`, JVM parity, and
  zero/one-argument rejection; list/map `.toPlutusData()` on chained and variable
  values; `listData(JulcList.of(...))` composition on-chain + JVM, mixed-element
  JVM wrapping, unsupported-element error. Uses `JulcEval.forSource` (PIR
  intrinsics are not reachable via `forClass`).
- **`ValuesLibTest$UniqueTokenNameTests`** (+11): `refBytes` byte-exact for indices
  0/1/256/65535 (index 0 is the parity-critical case), failure for 65536;
  `uniqueTokenName` == `blake2b_256(refBytes)` (cross-checked through a
  `CryptoLib` eval), 32-byte length, differs by index and by txId, and direct
  JVM-vs-UPLC parity.
- **Core conversion tests** (+6): supported scalar types, UTF-8 strings, nested
  lists/maps, unsupported values, and duplicate-key/order preservation.
- **Security and lookup tests**: 9 direct-JVM builtin tests, an
  `@NewType`-plus-typed-coercion regression, testkit collection conversion, and
  2 VM arbitrary-width narrowing regressions.

## Verification (post-implementation)

- `./gradlew test` — **PASS** across all enabled modules (111 tasks; explicit
  network E2E modules remain skipped by their normal opt-in configuration).
- Focused core/compiler/stdlib/testkit/VM ADR tests — **PASS**.
- Official Plutus UPLC conformance runner — **999/999 PASS**, including all 32
  `integerToByteString` cases.
- Every bundled `integerToByteString` `.uplc` input and `.uplc.expected` result
  was Git-blob compared with current
  [`IntersectMBO/plutus`](https://github.com/IntersectMBO/plutus/tree/master/plutus-conformance/test-cases/uplc/evaluation/builtin/semantics/integerToByteString)
  master (`6e7944f422f9a22fb3e78a8e2cfa25adc373522b`); all match. Upstream's
  2026-07-22 update added flat-format companions without changing these UPLC
  vectors.
- `git diff --check` — **PASS**.

## Consequences

- The three verbose idioms have one-line replacements; docs lead with them.
- Typed-lookup coercions are active in every compilation path for the first time,
  including `@NewType` compilations; the
  `::lookup` anti-pattern (and any partial `StdlibLookup` implementation)
  is a **compile error** (R2 hardening).
- Static `MapLib`/`ListsLib` calls uniformly accept raw-Data and typed containers.
- Map iteration surface (`JulcPair`/`entries()`) deferred to
  [ADR-028](028-julcpair-native-pair-type.md).
- Script-hash and Java-compatibility caveats above apply.
