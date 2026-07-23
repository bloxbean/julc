# ADR-027: Stdlib Variadic Intrinsics, Unique Token Name Helpers, and the Typed-Lookup Bypass Fix

**Date**: 2026-07-21 (review findings folded in 2026-07-22)
**Status**: Proposed (under review; core implementation staged locally, review follow-ups R1–R6 pending — see Implementation summary)

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

  **Decision (review, 2026-07-22): introduce `JulcPair<K,V>`**, a core-level
  Java type whose contract is *always a native UPLC pair*:

  ```java
  /** Native-pair view. On-chain: .key()/.value() → FstPair/SndPair (+ wrapDecode per K/V). */
  public interface JulcPair<K, V> { K key(); V value(); }
  ```

  - `JulcMap.entries()` returns `JulcList<JulcPair<K,V>>` — on-chain identity
    (the map IS a pair list), typed `ListType(PairType(K,V))`; off-chain
    `JulcAssocMap` exposes its ordered internal pair list. Order and duplicate
    keys preserved.
  - `JulcMap.head()` is retyped from `Object` to `JulcPair<K,V>` — fixing the
    existing cast-based traversal (`MapType.head()` already returns `PairType`
    at the PIR level; the Java-side type was the gap).
  - **Audit required**: `JulcList` operations on native-pair lists. Safe:
    structural ops (`head`, `tail`, `size`, `isEmpty`, `take`, `drop`;
    `MkCons`-based prepend of a pair). Unsafe and to be rejected or excluded:
    Data-assuming ops (`contains`/`equalsData`, `toPlutusData`/`ListData`
    wrapping, element auto-wrap) — native pairs are not Data.
  - `JulcMap.toPlutusData()`: declared on the interface; on-chain the existing
    `wrapEncode` fallback already maps `MapType` → `MapData` (no compiler
    change, same as lists); off-chain `JulcAssocMap` implements it by walking
    its entries and wrapping each key/value with the element rules.
  - A callback-style `iterate(...)` remains rejected (lambdas do not compile
    on-chain); making `JulcMap` `Iterable` is deferred (it interacts with the
    existing for-each-on-MapType desugaring).
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
documents arity ≥ 2 (enforced by javac at Gradle-build call sites), but the PIR
registration currently accepts 0 args (→ empty bytes) and 1 arg (→ identity).
Source-string compilation paths (`JulcEval.forSource`, CLI check, playground)
never run javac, so a 1-arg `concat` would silently no-op there. The registry
builder will reject fewer than 2 arguments (same `requireArgs` convention as other
registrations), with negative compilation tests.

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
  `long` width straight to `int` (overflow for huge widths, no 8192 cap). So
  the JVM/UPLC-parity claim above currently holds only for indices ≤ 65535 with
  sane widths. Fix in the implementation PR: validate `0 ≤ width ≤ 8192` before
  casting/allocating and throw when the value does not fit a positive width;
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

The testkit paths (`ValidatorTest`, `SourceDiscovery`, `JulcEval`) passed the
registry object correctly, which is why in-repo stdlib tests never caught it — the
exact "works via JulcCompiler directly, broken under Gradle" split ADR-023/024
warned about in a different subsystem.

### Fix

Mechanical: `new JulcCompiler(stdlib::lookup)` → `new JulcCompiler(stdlib)` at all
~40 **code** sites (`StdlibRegistry` implements `StdlibLookup`; passing the object
preserves the override). `CompositeStdlibLookup` already propagated the 4-arg call
correctly.

**Review finding (R2) — documentation still teaches the broken pattern**: the
migration covered `.java` sources only; `README.md:235` and
`docs/src/content/docs/getting-started.md:1112` still show
`new JulcCompiler(stdlib::lookup)`, and `getting-started.md:1168` shows
`ValidatorTest.compile(javaSource, stdlib::lookup)`. Every user who copies these
snippets reproduces the bug.

**Decision (review, 2026-07-22): harden the interface now.** Remove
`@FunctionalInterface` from `StdlibLookup` and make the 4-arg `lookup` abstract,
so `::lookup` (and any lambda) stops compiling **anywhere** a `StdlibLookup` is
expected — the entire bug class becomes a compile error, at every current and
future API, with no CI guard to maintain. (A grep guard was rejected: a pattern
like `JulcCompiler(.*::lookup` would already have missed the
`ValidatorTest.compile(..., stdlib::lookup)` form.) Migration cost is zero
today: a repo-wide grep confirms no lambda or method-reference implementations
of `StdlibLookup` exist, and all three implementing classes (`StdlibRegistry`,
`LibraryMethodRegistry`, `CompositeStdlibLookup`) already override both
methods. The three documentation snippets are corrected as part of the same
change. Pre-1.0 is the time for this breaking interface change.

**Rule going forward: pass the registry object, never a method reference** —
enforced by the compiler once R2 lands.

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

### Review follow-ups (2026-07-22), to land in the implementation PR

| # | Item |
|---|---|
| R1 | JVM `Builtins.integerToByteString`: validate the full VM contract — `0 ≤ width ≤ 8192` before casting/allocating, throw when the value does not fit a positive width (currently: oversized values silently returned, negative width treated as minimal, long→int cast overflow). Direct-JVM tests for width −1/8192/8193, oversize value, plus `refBytes`/`uniqueTokenName` JVM-vs-UPLC parity. |
| R2 | **Decided: harden `StdlibLookup`** — drop `@FunctionalInterface`, make the 4-arg `lookup` abstract; `::lookup`/lambda forms stop compiling everywhere (zero migration cost: no such implementations exist; all three implementing classes already override both methods). Fix the three doc snippets (`README.md:235`, `getting-started.md:1112`, `:1168`) in the same change. No CI grep guard needed. |
| R3 | `.toPlutusData()` JVM side: core-level conversion interface in `julc-core` (extended by ledger-api's `PlutusDataConvertible` — direct extension is a circular dependency); element-wrapping table in julc-core with `Builtins.listData(JulcList<?>)` delegating. **Decided: new `JulcPair<K,V>`** core type mapped to native `PairType` (`.key()`/`.value()` → FstPair/SndPair); `JulcMap.entries()` → `JulcList<JulcPair<K,V>>` (on-chain identity), `JulcMap.head()` retyped `Object` → `JulcPair<K,V>`; audit `JulcList` ops on native-pair lists (structural ops safe; Data-assuming ops rejected); `JulcMap.toPlutusData()` on the interface, `JulcAssocMap` impl walks entries (order + duplicate keys preserved). |
| R4 | `.toPlutusData()` on-chain: NO compiler changes (PirGenerator.java:1001 wrapEncode fallback + MkCons ListType inference already cover it) — add the chained-call regression test first; drop the previously proposed TypeMethodRegistry registrations. |
| R5 | `Builtins.concat` PIR registration: require ≥ 2 args (source-string paths bypass javac); negative compilation tests. |
| R6 | Wording: javadoc + stdlib-guide say "collision-resistant, deterministically tied to the consumed ref" instead of "guaranteed unique"; note the policy must enforce the ref is spent. |

Docs updated to lead with `.toPlutusData()` once R3/R4 land.

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

## Verification (staged implementation only — pre-R1–R6)

The checks below cover the staged core implementation; the R1–R6 follow-ups get
their own verification in the implementation PR.

- Full repo test suite green (`./gradlew test`, all modules, incl. the previously
  bypassed harnesses now exercising the typed path).
- `julc-test-quick` composite gate: publish-local → gradle-plugin smoke →
  stdlib-usage smoke → error-paths (6 negative subcases) — **all PASS** against the
  freshly published `0.1.0-pre14` artifacts.
- R4 verified empirically: chained `JulcList.of(...).toPlutusData()` and
  variable `.toPlutusData()` compile and evaluate correctly today (scratch test
  through `JulcEval.forSource`, to be added permanently in the implementation PR).
- Follow-up regression pre-checks: `JulcAssocMap` is the sole `JulcMap`
  implementor (R3 additions touch one class); no `"entries"`/`"toPlutusData"`
  registrations exist in `TypeMethodRegistry` (no dispatch collisions); no
  width>0 JVM callers of `integerToByteString` rely on the lenient behavior
  (R1 throw affects only values already broken on-chain); every existing
  `concat` call passes ≥ 2 args (R5); no lambda/method-ref `StdlibLookup`
  implementations exist (R2 hardening is non-breaking in-repo).
- Operational note: the `julc-smoke-gradle-stdlib-usage` skill's sample validator
  uses stale `OutputLib.outputsAt(TxInfo, ...)` signatures (current API:
  `outputsAt(JulcList<TxOut>, Address)`). The JuLC compilation succeeded; only
  javac rejected the sample. The skill fixture should be updated.

## Consequences (once R1–R6 land with the implementation PR)

- The three verbose idioms have one-line replacements; docs lead with them.
- Typed-lookup coercions are active in every compilation path for the first time;
  the `::lookup` anti-pattern becomes a **compile error** (R2 hardening).
- Static `MapLib`/`ListsLib` calls uniformly accept raw-Data and typed containers.
- `JulcPair` gives map iteration a real Java type (`entries()`, typed `head()`).
- Script-hash compatibility caveat above applies to the affected constructs.
