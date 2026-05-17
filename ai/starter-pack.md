<!-- julcVersion: 0.1.0-pre12 -->

> **Read this entire document before generating any JuLC code.** It distills the JuLC subset of Java, the on-chain idioms, the compiler's known limitations, and the error patterns that AI agents most commonly get wrong. Following the rules here will save many compile-fail-retry cycles.

This pack is **not** a tutorial for humans — it is optimized for AI ingestion. For the human-friendly tutorial, see [Write Your First Contract](/first-contract/).

---

## 1. What is JuLC?

JuLC is a compiler from a **safe subset of Java 25** to **Plutus V3 UPLC** (Untyped Plutus Lambda Calculus), the on-chain execution language of Cardano. You write validators as ordinary Java classes — using `record`s, sealed interfaces, switch expressions, and `static` methods — and the JuLC compiler produces an on-chain script.

Key facts:

- **On-chain only.** JuLC code runs on the Cardano CEK machine. It is the validator/minting-policy code, not the off-chain transaction-building code.
- **Off-chain Java** (using cardano-client-lib `0.7.1`) is a separate concern; you can call the *same* JVM classes (records) off-chain to construct datums and redeemers, but the bodies of `@OnchainLibrary` and validator methods compile to UPLC, not run on the JVM.
- **Plutus V3 only** (Conway era). No V1/V2 support.
- **The Java source is the authoring surface; UPLC is what runs.** Many "weird" rules below exist because UPLC has no notion of mutable state, no exceptions (except `error`), and no general recursion (only fixed-point combinators).

## 2. Project structure (what `julc new` produces)

A typical JuLC project looks like:

```
my-contract/
├── build.gradle                # Gradle config with julc-annotation-processor
├── src/main/java/
│   └── com/example/
│       ├── MyValidator.java    # @SpendingValidator class
│       └── MyLib.java          # @OnchainLibrary class (optional)
└── src/test/java/              # julc-testkit tests
```

Build artifact: `build/classes/META-INF/plutus/MyValidator.plutus.json` — the compiled UPLC + CIP-57 blueprint, loadable off-chain via `JulcScriptLoader.load(MyValidator.class)`.

## 3. The Java subset that compiles to UPLC

### 3.1 Allowed

- **Static methods on classes.** Validator entrypoints and helpers are all `static`.
- **`record`s** as datum/redeemer types. Records are compiled to `ConstrData` with field access compiled to `unConstrData → indexed access`.
- **Sealed interfaces with permitted records** as ADTs (algebraic data types). Compiled to `ConstrData` with the constructor index discriminating variants.
- **`switch` expressions** over sealed interfaces (pattern matching on permitted records). The compiler **requires exhaustiveness** unless a `default ->` branch is present.
- **`if` / `else if` / `else`**, **ternary `? :`**.
- **`for-each` loops** over `JulcList<T>`, `JulcMap<K,V>`, and ledger list types. Auto-desugared to recursive UPLC.
- **`while` loops** with **explicit accumulators** (single or multi-variable). Desugared to `LetRec` fixed points.
- **Local variables** declared with `var` or explicit type, **must be initialized at declaration**, and **are immutable after assignment** (single-assignment).
- **Method calls** to `@OnchainLibrary` static methods, stdlib (`ContextsLib`, `ListsLib`, etc.), instance methods on `JulcList`/`JulcMap`/`String`/`byte[]`/`BigInteger`, and `Builtins.*` (Plutus builtins).
- **Lambdas as HOF arguments** to `list.map(...)`, `list.filter(...)`, `list.any(...)`, `list.all(...)`, `list.find(...)`, `ListsLib.foldl(...)`. Must be passed inline; cannot be stored in a variable and called via `.apply()`.
- **Annotations**: `@SpendingValidator`, `@MintingValidator` (or legacy `@MintingPolicy`), `@CertifyingValidator`, `@WithdrawValidator`, `@VotingValidator`, `@ProposingValidator` on the class; `@Entrypoint` on the entrypoint method; `@Param` on `static` fields for parameterized validators; `@OnchainLibrary` on library classes; `@NewType` on single-field record wrappers.
- **`BigInteger`** for integers (NOT `int`/`long` — those are accepted but converted; prefer `BigInteger` for clarity).
- **`byte[]`** for bytestrings.
- **`String`** is treated as `byte[]` UTF-8 in most contexts; use sparingly.
- **`boolean`**.

### 3.2 Forbidden / breaks compilation

- ❌ **Mutation after declaration.** `x = x + 1` outside a while-loop accumulator is rejected. Use a new `var y = x + 1` instead.
- ❌ **Uninitialized variables.** `var x;` then `x = 5;` does NOT work. Initialize at declaration: `var x = BigInteger.ZERO;`.
- ❌ **`return` inside a `while` loop.** Use a boolean accumulator and return after the loop. Compile-time error with a fix hint.
- ❌ **`return` inside a switch case** (use `yield`).
- ❌ **Lambdas stored in variables.** `Function<X, Y> f = x -> ...; f.apply(...)` does not compile. Pass lambdas inline to HOFs.
- ❌ **`throw new Exception(...)`.** Use `Builtins.error()` or simply `return false` — there are no exceptions in UPLC.
- ❌ **Reflection, I/O, threading, JNI, instance fields, instance methods on user types** (only methods on `record`s and ledger types are dispatched).
- ❌ **Generics with non-`PlutusData`-compatible bounds.** Generic methods on user code are limited; prefer concrete types.
- ❌ **`null`.** Use `Optional<T>` / sealed interfaces with explicit "none" variants.
- ❌ **Try/catch.** No exceptions to catch.

### 3.3 Strongly discouraged (anti-patterns)

- 🚫 **Constructing raw `PlutusData` (`ConstrData`, `IntData`, `BytesData`, `MapData`, `ListData`) in on-chain code.** This is the #1 mistake AI agents make. **See section 5.**
- 🚫 **`@Param PlutusData.BytesData/MapData/ListData/IntData`.** Use `byte[]`, `BigInteger`, typed records, or redeemers instead.
- 🚫 **Same parameter name as a sealed-interface constructor field.** See limitation 6.4.
- 🚫 **Calling `.hash()` on a value that is already a hash type** (double-unwrap).

---

## 4. The `@Validator` family — entrypoint shapes

| Annotation | Entrypoint signature | Purpose |
|---|---|---|
| `@SpendingValidator` | `static boolean validate(<Redeemer> r, ScriptContext ctx)` **or** `static boolean validate(<Datum> d, <Redeemer> r, ScriptContext ctx)` | Validate spending a UTxO; use the 2-param form when the script does not need a datum. |
| `@MintingValidator` (or `@MintingPolicy`) | `static boolean validate(<Redeemer> r, ScriptContext ctx)` | Validate minting/burning. |
| `@CertifyingValidator` | `static boolean validate(<Redeemer> r, ScriptContext ctx)` | Validate stake cert actions. |
| `@WithdrawValidator` | `static boolean validate(<Redeemer> r, ScriptContext ctx)` | Validate reward withdrawal. |
| `@VotingValidator` | `static boolean validate(<Redeemer> r, ScriptContext ctx)` | Conway voting validator. |
| `@ProposingValidator` | `static boolean validate(<Redeemer> r, ScriptContext ctx)` | Conway proposal validator. |

Always:
- The class is `public`.
- The entrypoint is `public static boolean` and annotated `@Entrypoint`.
- `@Param` `static` fields become script parameters baked in at deploy time.

## 5. **CRITICAL: Use type classes, NOT raw PlutusData**

This is the single most important rule. **An AI agent that ignores this rule produces verbose, brittle, non-idiomatic JuLC.**

### 5.1 The rule

In on-chain code, **always** prefer high-level ledger type classes:

| ✅ Use this | ❌ NOT this |
|---|---|
| `TxOut`, `TxOutRef`, `TxInfo`, `ScriptContext`, `Address`, `Value`, `OutputDatum`, `Credential`, `IntervalBound`, `Interval` | `PlutusData.ConstrData(...)` for txouts/contexts |
| `JulcList<T>`, `JulcMap<K,V>` | `PlutusData.ListData(...)`, `PlutusData.MapData(...)` |
| `Optional<T>` (with `.isPresent()` / `.get()` etc.) | `null`, raw `ConstrData` for None/Some |
| `Tuple2<A,B>`, `Tuple3<A,B,C>` | Raw `ConstrData` 2-tuples |
| Sealed interface with `record` variants (`switch` on it) | `ConstrData` with manual constructor-index checks |
| `BigInteger`, `byte[]`, `boolean`, `String` | `PlutusData.IntData(...)`, `PlutusData.BytesData(...)` for values you own |

The compiler infrastructure (TypeRegistrar, sealed-interface dispatch, named-record method dispatch, `JulcList`/`JulcMap` builtins) exists *precisely so you don't touch `PlutusData` directly*.

### 5.2 When raw `PlutusData` is acceptable

Only when interop genuinely requires it:

- **Forwarding an opaque payload** from one contract to another where the payload type is not part of your contract.
- **Generic redeemer slot** when the entrypoint accepts arbitrary data (e.g. `@Entrypoint validate(PlutusData redeemer, ScriptContext ctx)`).
- **Datum-by-hash lookup** where the datum type is unknown at compile time.

In all other cases, define a `record` and let the compiler handle encoding/decoding.

### 5.3 Examples — bad vs good

**❌ BAD: raw PlutusData**

```java
@Entrypoint
public static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
    PlutusData.ConstrData d = (PlutusData.ConstrData) datum;
    PlutusData beneficiary = d.fields().get(0);
    byte[] beneficiaryBytes = ((PlutusData.BytesData) beneficiary).bytes();
    // ... hand-decoding, error-prone, no compiler help
}
```

**✅ GOOD: type-class style**

```java
record VestingDatum(byte[] beneficiary) {}
record VestingRedeemer(BigInteger amount) {}

@Entrypoint
public static boolean validate(VestingDatum datum, VestingRedeemer redeemer, ScriptContext ctx) {
    var sigs = ctx.txInfo().signatories();
    return sigs.contains(PubKeyHash.of(datum.beneficiary()));
}
```

The compiler emits the same UPLC for both, but the second form is type-safe, refactor-friendly, and idiomatic.

---

## 6. Known compiler limitations (READ EVERY ONE)

These are real failure modes that have come up in JuLC's test suite. AI agents that ignore them produce code that compiles but behaves incorrectly, or fails to compile with cryptic errors.

### 6.1 No `return` inside a `while` loop

```java
// ❌ BAD — compile error
while (i.compareTo(n) < 0) {
    if (matches(i)) return true;
    i = i.add(BigInteger.ONE);
}
return false;

// ✅ GOOD — boolean accumulator
boolean found = false;
var i = BigInteger.ZERO;
while (i.compareTo(n) < 0 && !found) {
    if (matches(i)) {
        found = true;
    }
    i = i.add(BigInteger.ONE);
}
return found;
```

`break` is allowed in `for-each`, but `return` inside `while` is rejected with an explicit error.

### 6.2 Tuple2/Tuple3 are NOT switchable

`Tuple2<A,B>` is a `record` (a `RecordType`), not a sealed interface. You cannot `switch` on it. Use field access:

```java
// ❌ BAD
switch (pair) { case Tuple2(var a, var b) -> ... }

// ✅ GOOD
A a = pair.first();
B b = pair.second();
```

### 6.3 `map()` returns `JulcList<PlutusData>`

`list.map(x -> ...)` always wraps the lambda's return in `Data` to keep the list element type uniform. So:

```java
JulcList<BigInteger> nums = ...;
JulcList<PlutusData> doubled = nums.map(n -> n.multiply(BigInteger.TWO));
// To use as integer:
BigInteger first = Builtins.unIData(doubled.head());
```

If you only need to *consume* the mapped list immediately (e.g. in a fold or any/all), use `any`/`all` directly with a predicate that includes the transformation.

### 6.4 Switch case field name shadows method parameter

If a sealed-interface variant has a field with the same name as a method parameter, the field binding **silently shadows** the parameter inside the switch case body. Always use distinct names.

```java
// ❌ BAD — `time` parameter is shadowed by Finite's `time` field
boolean check(IntervalBound bound, BigInteger time) {
    return switch (bound) {
        case Finite f -> f.time().compareTo(time) > 0;  // `time` here = field, not param
    };
}

// ✅ GOOD — rename param
boolean check(IntervalBound bound, BigInteger point) {
    return switch (bound) {
        case Finite f -> f.time().compareTo(point) > 0;
    };
}
```

### 6.5 `@Param` type rules

`@Param BigInteger` and `@Param byte[]` are supported and decoded by the parameter wrapper, so arithmetic and byte-string operations work normally. Do **not** use typed raw `PlutusData` subtypes as params: `PlutusData.BytesData`, `PlutusData.MapData`, `PlutusData.ListData`, and `PlutusData.IntData` are rejected with `JULC0013`. Use plain `PlutusData` only when the value must remain opaque.

### 6.6 No double `.hash()`

`PubKeyHash`, `ScriptHash`, etc. already store the hash bytes. Calling `.hash()` returns the bytes; calling `.hash().hash()` is wrong. The compiler usually catches this via the `hofUnwrappedVars` mechanism but be careful in HOF lambdas.

### 6.7 `byte[]` casts off-chain in stdlib stubs

`ByteStringLib.zeros()`, `ByteStringLib.empty()`, `ByteStringLib.integerToByteString()`, `ByteStringLib.serialiseData()` use `(byte[])(Object) Builtins.replicateByte(...)` etc. — these casts work on-chain but **fail off-chain at runtime** with `ClassCastException`. For off-chain testing, call `Builtins.*` methods directly, or test through UPLC evaluation.

### 6.8 Building pair lists requires `MkNilPairData`

When constructing a `Map`-style accumulator manually:

```java
// ❌ BAD — element type mismatch at runtime
var pairs = Builtins.mkNilData();
// Scalus VM rejects pair-list ops on this

// ✅ GOOD
var pairs = Builtins.mkNilPairData();
// Type the var as ListType(PairType(DataType, DataType))
```

The compiler's `refineAccumulatorTypes()` auto-detects this in many cases, but if you build pair lists by hand, use `mkNilPairData`.

### 6.9 BannedParamTypes

`PlutusData.BytesData`, `PlutusData.MapData`, `PlutusData.ListData`, and `PlutusData.IntData` are **banned** from `@Param`. Compile-time error.

### 6.10 No mutual recursion with >2 functions

JuLC supports self-recursion and mutual recursion between exactly 2 helpers (via Bekic's theorem). Three or more mutually recursive functions are not supported. Refactor into a single function with an accumulator if needed.

### 6.11 `String.equals(...)` works; `==` does not

For `String` and `byte[]` comparison, use `.equals(...)` (compiles to `EqualsByteString`). `==` checks reference identity which is not meaningful in UPLC.

### 6.12 `BigInteger` comparison: use `.compareTo(...)`

`a == b` on `BigInteger` is reference equality. Use `a.equals(b)` or `a.compareTo(b) == 0`. For ordering, always `.compareTo(...)`.

---

## 7. Common errors — root cause and fix

| Error message | Likely cause | Fix |
|---|---|---|
| `Method must have a body: <name>` | Abstract method in validator class | Provide a body for every method. |
| `Variable must be initialized: <name>` | `var x;` without initializer | Initialize at declaration (`var x = BigInteger.ZERO;`). |
| `Cannot return inside while loop` | `return` inside `while` body | Use a boolean accumulator; return after the loop. |
| `Switch is not exhaustive on <Type>: missing <variants>` | Sealed-interface switch missing cases | Add the missing case branches or a `default ->`. |
| `Method does not return on all paths` | Missing return in some branch | Make every path return; void methods are exempt. |
| `Type mismatch: expected IntegerType, got DataType` | Comparing `Builtins.unIData(...)` with raw `Data` field | Apply `unIData`/`unBData`/etc. to the right operand too. |
| `Lambda cannot be stored in a variable` | Java `Function<X,Y> f = x -> ...; f.apply(...)` | Pass lambdas inline to HOFs; do not store. |
| `Unknown method <name> on <Type>` | Stdlib method that does not exist | Check `ListsLib`, `ValuesLib`, etc.; check `JulcList`/`JulcMap` instance methods. |
| `Banned param type: <Type>` | `@Param PlutusData.BytesData/MapData/ListData/IntData` | Use `byte[]` for byte params, `BigInteger` for integer params, and typed records or redeemers for structured data. |
| `Mutually recursive group too large` | Three+ mutually recursive helpers | Refactor; combine into one function with accumulators. |

For the full troubleshooting guide, see [/reference/troubleshooting/](/reference/troubleshooting/).

---

## 8. Stdlib API surface (one-line signatures)

All imports are from `com.bloxbean.cardano.julc.stdlib.lib.*`.

### ContextsLib
`signedBy(txInfo, pkh)`, `findOwnInput(ctx)`, `getContinuingOutputs(ctx)`, `findDatum(txInfo, hash)`, `valueSpent(txInfo)`, `valuePaid(txInfo, addr)`, `ownHash(ctx)`, `scriptOutputsAt(txInfo, hash)`, `listIndex(list, n)`, `trace(msg)`. Field shorthands: `txInfoInputs`, `txInfoOutputs`, `txInfoSignatories`, `txInfoValidRange`, `txInfoMint`, `txInfoFee`, `txInfoId`, `txInfoRefInputs`, `txInfoWithdrawals`, `txInfoRedeemers`. **Prefer `ctx.txInfo().outputs()` etc. via type-class field access.**

### ListsLib
`empty()`, `prepend(list, x)`, `length(list)`, `isEmpty(list)`, `head(list)`, `tail(list)`, `reverse(list)`, `concat(a, b)`, `nth(list, n)`, `take(list, n)`, `drop(list, n)`, `contains(list, x)`, `containsInt(list, n)`, `containsBytes(list, bs)`, `hasDuplicateInts(list)`, `hasDuplicateBytes(list)`, plus HOFs: `any(list, pred)`, `all(list, pred)`, `find(list, pred)`, `foldl(f, init, list)`, `map(list, f)`, `filter(list, pred)`, `zip(a, b)`. **Most of these are also instance methods on `JulcList<T>`** — prefer `list.map(...)` over `ListsLib.map(list, ...)`.

### ValuesLib
`lovelaceOf(value)`, `assetOf(value, policy, token)`, `containsPolicy(value, policy)`, `geq(a, b)`, `geqMultiAsset(a, b)`, `leq(a, b)`, `eq(a, b)`, `isZero(value)`, `singleton(policy, token, amount)`, `negate(value)`, `flatten(value)`, `add(a, b)`, `subtract(a, b)`. **Prefer `value.lovelaceOf()`, `value.assetOf(policy, token)`, `value.isEmpty()` instance methods where available.**

### MapLib
`lookup(map, key)` (returns `Optional<V>`), `member(map, key)`, `insert(map, k, v)`, `delete(map, k)`, `keys(map)`, `values(map)`, `toList(map)`, `fromList(list)`, `size(map)`. **Prefer instance methods on `JulcMap<K,V>`**: `map.lookup(k)`, `map.get(k)`, `map.containsKey(k)`, `map.size()`, `map.isEmpty()`, `map.keys()`, `map.values()`, `map.insert(k, v)`, `map.delete(k)`.

### OutputLib
`txOutAddress(out)`, `txOutValue(out)`, `txOutDatum(out)`, `outputsAt(outputs, addr)`, `countOutputsAt(outputs, addr)`, `uniqueOutputAt(outputs, addr)`, `outputsWithToken(outputs, policy, token)`, `valueHasToken(value, policy, token)`, `lovelacePaidTo(outputs, addr)`, `paidAtLeast(outputs, addr, amount)`, `getInlineDatum(out)`, `resolveDatum(txInfo, out)`. **Prefer field access**: `out.address()`, `out.value()`, `out.datum()`.

### MathLib
`abs(n)`, `max(a, b)`, `min(a, b)`, `divMod(a, b)` → `Tuple2`, `quotRem(a, b)` → `Tuple2`, `pow(b, e)`, `sign(n)`, `expMod(b, e, m)`.

### IntervalLib
`between(lo, hi)`, `never()`, `isEmpty(interval)`, `finiteUpperBound(interval)`, `finiteLowerBound(interval)`, `contains(interval, point)`.

### CryptoLib
`verifyEcdsaSecp256k1(pk, msg, sig)`, `verifySchnorrSecp256k1(pk, msg, sig)`, `ripemd_160(bytes)`. Plus the SHA-2/SHA-3/Blake2b family via `Builtins.*`.

### ByteStringLib
`take(bs, n)`, `lessThan(a, b)`, `lessThanEquals(a, b)`, `integerToByteString(big, len, value)`, `byteStringToInteger(big, bs)`, `encodeUtf8(s)`, `decodeUtf8(bs)`, `serialiseData(d)`. ⚠ Off-chain casts may throw — see limitation 6.7.

### BitwiseLib
`andByteString(pad, a, b)`, `orByteString(...)`, `xorByteString(...)`, `complementByteString(bs)`, `readBit(bs, i)`, `writeBits(bs, ...)`, `shiftByteString(bs, n)`, `rotateByteString(bs, n)`, `countSetBits(bs)`, `findFirstSetBit(bs)`.

### AddressLib
`credentialHash(addr)` → `byte[]`, `isScriptAddress(addr)`, `isPubKeyAddress(addr)`, `paymentCredential(addr)` → `Credential`.

### BlsLib (Plutus V3)
G1/G2 add/scale/neg, pairing, MSM, and `bls12_381_finalVerify`.

### NativeValueLib (PV11)
Native Mary-era `Value` operations.

### Builtins (`com.bloxbean.cardano.julc.stdlib.Builtins`)
Plutus builtins exposed by the Java API include `equalsByteString`, `equalsData`, `unBData`, `unIData`, `unMapData`, `unListData`, `unConstrData`, `iData`, `bData`, `mapData`, `listData`, `constrData`, `mkCons`, `mkNilData`, `mkNilPairData`, `nullList`, `headList`, `tailList`, `fstPair`, `sndPair`, `constrTag`, `constrFields`, `error`, `trace`, `replicateByte`, `serialiseData`, byte-string helpers such as `appendByteString`, `sliceByteString`, `integerToByteString`, `byteStringToInteger`, hashing (`sha2_256`, `sha3_256`, `blake2b_256`, `blake2b_224`, `keccak_256`, `ripemd_160`), and BLS helpers. Use Java operators / `BigInteger` methods for integer arithmetic; there is no public `Builtins.addInteger(...)` API.

<!-- catalog:stdlib-start -->

### Full machine-readable stdlib reference

*Auto-generated from `julc-stdlib/.../lib/*.java`. Source of truth: [/ai/catalog.json](/ai/catalog.json).*

#### AddressLib

*Address operations compiled from Java source to UPLC.*

- `byte[] credentialHash(Address address)` — Extract the hash from the payment credential of an address. Works for both PubKeyCredential and ScriptCredential. Uses chained field access: Credential variant -> hash wrapper -> byte[].
- `boolean isScriptAddress(Address address)` — Check if an address has a ScriptCredential (tag == 1).
- `boolean isPubKeyAddress(Address address)` — Check if an address has a PubKeyCredential (tag == 0).
- `Credential paymentCredential(Address address)` — Extract the payment credential from an address.

#### BitwiseLib

*Bitwise operations on ByteStrings compiled from Java source to UPLC.*

- `byte[] andByteString(boolean padding, byte[] a, byte[] b)` — Bitwise AND of two bytestrings with padding semantics.
- `byte[] orByteString(boolean padding, byte[] a, byte[] b)` — Bitwise OR of two bytestrings with padding semantics.
- `byte[] xorByteString(boolean padding, byte[] a, byte[] b)` — Bitwise XOR of two bytestrings with padding semantics.
- `byte[] complementByteString(byte[] bs)` — Bitwise complement of a bytestring.
- `boolean readBit(byte[] bs, long index)` — Read a bit at a given index.
- `byte[] shiftByteString(byte[] bs, long n)` — Shift a bytestring by n bits.
- `byte[] rotateByteString(byte[] bs, long n)` — Rotate a bytestring by n bits.
- `long countSetBits(byte[] bs)` — Count the number of set bits (1s) in a bytestring.
- `long findFirstSetBit(byte[] bs)` — Find the index of the first set bit, or -1 if none.

#### BlsLib

*BLS12-381 elliptic curve operations compiled from Java source to UPLC.*

- `byte[] g1Add(byte[] a, byte[] b)` — Add two G1 elements.
- `byte[] g1Neg(byte[] a)` — Negate a G1 element.
- `byte[] g1ScalarMul(BigInteger scalar, byte[] g1)` — Scalar multiplication of a G1 element.
- `boolean g1Equal(byte[] a, byte[] b)` — Check equality of two G1 elements.
- `byte[] g1Compress(byte[] g1)` — Compress a G1 element to 48 bytes.
- `byte[] g1Uncompress(byte[] compressed)` — Uncompress a 48-byte compressed G1 element.
- `byte[] g1HashToGroup(byte[] msg, byte[] dst)` — Hash a message to a G1 element using the given domain separation tag.
- `byte[] g2Add(byte[] a, byte[] b)` — Add two G2 elements.
- `byte[] g2Neg(byte[] a)` — Negate a G2 element.
- `byte[] g2ScalarMul(BigInteger scalar, byte[] g2)` — Scalar multiplication of a G2 element.
- `boolean g2Equal(byte[] a, byte[] b)` — Check equality of two G2 elements.
- `byte[] g2Compress(byte[] g2)` — Compress a G2 element to 96 bytes.
- `byte[] g2Uncompress(byte[] compressed)` — Uncompress a 96-byte compressed G2 element.
- `byte[] g2HashToGroup(byte[] msg, byte[] dst)` — Hash a message to a G2 element using the given domain separation tag.
- `byte[] millerLoop(byte[] g1, byte[] g2)` — Compute the Miller loop pairing of a G1 and G2 element.
- `byte[] mulMlResult(byte[] a, byte[] b)` — Multiply two Miller loop results.
- `boolean finalVerify(byte[] a, byte[] b)` — Final verification of two Miller loop results. Returns true if the pairing check passes.
- `byte[] g1MultiScalarMul(PlutusData scalars, PlutusData points)` — Multi-scalar multiplication on G1. Takes a list of scalars and a list of G1 elements.
- `byte[] g2MultiScalarMul(PlutusData scalars, PlutusData points)` — Multi-scalar multiplication on G2. Takes a list of scalars and a list of G2 elements.

#### ByteStringLib

*ByteString operations compiled from Java source to UPLC.*

- `long at(byte[] bs, long index)` — Get the byte at a given index (0-255).
- `byte[] cons(long byte_, byte[] bs)` — Prepend a byte (0-255) to a bytestring.
- `byte[] slice(byte[] bs, long start, long length)` — Extract a slice: slice(bs, start, length).
- `long length(byte[] bs)` — Get the length of a bytestring.
- `byte[] drop(byte[] bs, long n)` — Drop the first n bytes from a bytestring.
- `byte[] take(byte[] bs, long n)` — Take the first n bytes from a bytestring.
- `byte[] append(byte[] a, byte[] b)` — Concatenate two bytestrings.
- `byte[] empty()` — An empty bytestring.
- `byte[] zeros(long n)` — Create a bytestring of n zero bytes.
- `boolean equals(byte[] a, byte[] b)` — Compare two bytestrings for equality.
- `boolean lessThan(byte[] a, byte[] b)` — Lexicographic comparison: a < b.
- `boolean lessThanEquals(byte[] a, byte[] b)` — Lexicographic comparison: a <= b.
- `byte[] integerToByteString(boolean endian, long width, long i)` — Convert integer to bytestring.
- `byte[] integerToByteString(boolean endian, long width, BigInteger i)` — Convert integer to bytestring. Accepts arbitrary-precision BigInteger.
- `BigInteger byteStringToInteger(boolean endian, byte[] bs)` — Convert bytestring to integer. Returns arbitrary-precision BigInteger.
- `byte[] encodeUtf8(PlutusData s)` — Encode a string as UTF-8 bytestring.
- `PlutusData decodeUtf8(byte[] bs)` — Decode a UTF-8 bytestring.
- `byte[] serialiseData(PlutusData d)` — Serialise a Data value to CBOR bytes.
- `long hexNibble(long n)` — Map a nibble (0-15) to its lowercase ASCII hex char ('0'-'9', 'a'-'f').
- `byte[] toHex(byte[] bs)` — Convert a bytestring to its lowercase hex representation. Each byte becomes two hex characters (e.g. 0xDE → "de").
- `byte[] toHexStep(byte[] bs, long idx, byte[] acc)` — Recursive helper: process bytes from index down to 0, prepending hex chars.
- `byte[] intToDecimalString(BigInteger n)` — Convert a non-negative integer to its decimal string representation as UTF-8 bytes. E.g. 42 → "42" (as byte[]{52, 50}).
- `byte[] intToDecimalStep(BigInteger n, byte[] acc)` — Recursive helper: divmod by 10, prepend digit char.
- `BigInteger utf8ToInteger(byte[] bs)` — Parse a UTF-8 decimal string (e.g. bytes of "42") to an integer. Inverse of #intToDecimalString(BigInteger). Assumes all bytes are ASCII digits ('0'-'9'). No sign handling.
- `BigInteger utf8ToIntegerStep(byte[] bs, long idx, long len, BigInteger acc)` — Recursive helper: accumulate decimal value left-to-right.

#### ContextsLib

*Script context operations compiled from Java source to UPLC.*

- `void trace(String message)` — Emits a trace message. On-chain becomes UPLC Trace builtin. Body is a no-op — the compiler uses the PIR-registered version.
- `TxInfo getTxInfo(ScriptContext ctx)` — Extracts the TxInfo from a ScriptContext.
- `PlutusData getRedeemer(ScriptContext ctx)` — Extracts the redeemer from a ScriptContext.
- `JulcList<TxInInfo> txInfoInputs(TxInfo txInfo)` — Extracts the list of inputs from a TxInfo.
- `JulcList<TxOut> txInfoOutputs(TxInfo txInfo)` — Extracts the list of outputs from a TxInfo.
- `JulcList<PubKeyHash> txInfoSignatories(TxInfo txInfo)` — Extracts the signatories list from a TxInfo.
- `Interval txInfoValidRange(TxInfo txInfo)` — Extracts the valid range from a TxInfo.
- `Value txInfoMint(TxInfo txInfo)` — Extracts the mint field from a TxInfo.
- `BigInteger txInfoFee(TxInfo txInfo)` — Extracts the fee from a TxInfo.
- `TxId txInfoId(TxInfo txInfo)` — Extracts the txId from a TxInfo.
- `JulcList<TxInInfo> txInfoRefInputs(TxInfo txInfo)` — Extracts reference inputs from TxInfo.
- `JulcMap<Credential, BigInteger> txInfoWithdrawals(TxInfo txInfo)` — Extracts withdrawals map from TxInfo.
- `JulcMap<ScriptPurpose, PlutusData> txInfoRedeemers(TxInfo txInfo)` — Extracts redeemers map from TxInfo.
- `boolean signedBy(TxInfo txInfo, byte[] pkh)` — Checks whether a given PubKeyHash is in the signatories list.
- `Optional<PlutusData> getSpendingDatum(ScriptContext ctx)` — Extracts the optional datum from a spending ScriptContext. Returns Optional.of(datum) for SpendingScript with datum, Optional.empty() otherwise.
- `Optional<TxInInfo> findOwnInput(ScriptContext ctx)` — Finds the own input for a spending validator. Returns Optional.
- `byte[] ownInputScriptHash(ScriptContext ctx)` — Extracts the script credential hash from the own input's address.
- `byte[] ownHash(ScriptContext ctx)` — Extracts the own script hash from the ScriptContext's ScriptInfo. Minting -> policyId. Others -> script credential hash from own input.
- `JulcList<TxOut> getContinuingOutputs(ScriptContext ctx)` — Returns outputs that pay to the same address as the own spending input.
- `JulcList<Value> valueSpent(TxInfo txInfo)` — Collects the values of all inputs as a list.
- `JulcList<Value> valuePaid(TxInfo txInfo, Address addr)` — Filters outputs by address and returns their values as a list.
- `JulcList<TxOut> scriptOutputsAt(TxInfo txInfo, byte[] scriptHash)` — Filters outputs whose address has a ScriptCredential matching the given hash.
- `Optional<PlutusData> findDatum(TxInfo txInfo, PlutusData hash)` — Searches the txInfo datums map for a datum matching the given hash. Returns Optional.

#### CryptoLib

*Cryptographic hash and signature verification operations compiled from Java source to UPLC.*

- `byte[] sha2_256(byte[] bs)` — SHA2-256 hash.
- `byte[] blake2b_256(byte[] bs)` — Blake2b-256 hash.
- `boolean verifyEd25519Signature(byte[] key, byte[] msg, byte[] sig)` — Verify an Ed25519 signature.
- `byte[] sha3_256(byte[] bs)` — SHA3-256 hash.
- `byte[] blake2b_224(byte[] bs)` — Blake2b-224 hash (commonly used for key hashes).
- `byte[] keccak_256(byte[] bs)` — Keccak-256 hash.
- `boolean verifyEcdsaSecp256k1(byte[] vk, byte[] msg, byte[] sig)` — Verify ECDSA secp256k1 signature.
- `boolean verifySchnorrSecp256k1(byte[] vk, byte[] msg, byte[] sig)` — Verify Schnorr secp256k1 signature.
- `byte[] ripemd_160(byte[] bs)` — RIPEMD-160 hash.

#### IntervalLib

*Interval / POSIXTimeRange operations compiled from Java source to UPLC.*

- `boolean contains(Interval interval, BigInteger point)` — Checks whether a point in time is contained within an interval.
- `Interval always()` — Builds the "always" interval: (-inf, +inf).
- `Interval after(BigInteger t)` — Builds the interval [t, +inf).
- `Interval before(BigInteger t)` — Builds the interval (-inf, t].
- `Interval between(BigInteger low, BigInteger high)` — Builds the interval [low, high] (both inclusive).
- `Interval never()` — Builds the empty interval (PosInf, NegInf).
- `boolean isEmpty(Interval interval)` — Checks if an interval is empty (lower is PosInf or upper is NegInf).
- `BigInteger finiteUpperBound(Interval interval)` — Extract the finite upper bound time, or return -1 if not finite.
- `BigInteger finiteLowerBound(Interval interval)` — Extract the finite lower bound time, or return -1 if not finite.

#### ListsLib

*List operations compiled from Java source to UPLC.*

- `JulcList<PlutusData> empty()` — Return an empty list.
- `JulcList<PlutusData> prepend(JulcList<PlutusData> list, PlutusData element)` — Prepend an element to the front of a list.
- `long length(JulcList<PlutusData> list)` — Return the number of elements in the list.
- `boolean isEmpty(JulcList<PlutusData> list)` — Return true if the list is empty.
- `PlutusData head(JulcList<PlutusData> list)` — Return the first element of the list.
- `JulcList<PlutusData> tail(JulcList<PlutusData> list)` — Return all elements except the first.
- `JulcList<PlutusData> reverse(JulcList<PlutusData> list)` — Reverse a list.
- `JulcList<PlutusData> concat(JulcList<PlutusData> a, JulcList<PlutusData> b)` — Concatenate two lists.
- `PlutusData nth(JulcList<PlutusData> list, long n)` — Get element at index n (0-based).
- `JulcList<PlutusData> take(JulcList<PlutusData> list, long n)` — Take the first n elements from a list.
- `JulcList<PlutusData> drop(JulcList<PlutusData> list, long n)` — Drop the first n elements from a list.
- `boolean contains(JulcList<PlutusData> list, PlutusData target)` — Return true if list contains target (using EqualsData).
- `boolean containsInt(JulcList<PlutusData> list, BigInteger target)` — Check if a list of integers contains the given value. Uses EqualsInteger.
- `boolean hasDuplicateInts(JulcList<PlutusData> list)` — Check if a list of integers contains any duplicate. O(n^2). Delegates to #hasDuplicates.
- `boolean hasDuplicateBytes(JulcList<PlutusData> list)` — Check if a list of bytestrings contains any duplicate. O(n^2). Delegates to #hasDuplicates.
- `boolean hasDuplicates(JulcList<PlutusData> list)` — Check if a list contains any duplicate element. O(n^2). For each element, searches the tail using EqualsData.
- `boolean containsBytes(JulcList<PlutusData> list, byte[] target)` — Check if a list of bytestrings contains the given target. Uses EqualsByteString.

#### MapLib

*Map (association list) operations compiled from Java source to UPLC.*

- `JulcMap<PlutusData, PlutusData> empty()` — Return an empty map.
- `Optional<PlutusData> lookup(JulcMap<PlutusData, PlutusData> map, PlutusData key)` — Look up a key. Returns Optional.of(value) if found, Optional.empty() if not.
- `boolean member(JulcMap<PlutusData, PlutusData> map, PlutusData key)` — Check if key exists in map.
- `JulcMap<PlutusData, PlutusData> insert(JulcMap<PlutusData, PlutusData> map, PlutusData key, PlutusData value)` — Insert key-value pair (prepends; shadows existing).
- `JulcMap<PlutusData, PlutusData> delete(JulcMap<PlutusData, PlutusData> map, PlutusData key)` — Delete a key from the map.
- `JulcList<PlutusData> keys(JulcMap<PlutusData, PlutusData> map)` — Extract all keys from a map as a list.
- `JulcList<PlutusData> values(JulcMap<PlutusData, PlutusData> map)` — Extract all values from a map as a list.
- `JulcMap<PlutusData, PlutusData> toList(JulcMap<PlutusData, PlutusData> map)` — Convert map to pair list (identity — MapType vars already hold pair lists).
- `JulcMap<PlutusData, PlutusData> fromList(PlutusData list)` — Construct map from pair list (MapData).
- `long size(JulcMap<PlutusData, PlutusData> map)` — Count entries in the map.

#### MathLib

*Mathematical operations compiled from Java source to UPLC.*

- `BigInteger abs(BigInteger x)` — Returns the absolute value of an integer.
- `BigInteger max(BigInteger a, BigInteger b)` — Returns the maximum of two integers.
- `BigInteger min(BigInteger a, BigInteger b)` — Returns the minimum of two integers.
- `Tuple2<BigInteger, BigInteger> divMod(BigInteger a, BigInteger b)` — Returns division and modulo as a Tuple2.
- `Tuple2<BigInteger, BigInteger> quotRem(BigInteger a, BigInteger b)` — Returns quotient and remainder as a Tuple2.
- `BigInteger pow(BigInteger base, BigInteger exp)` — Returns base raised to the power of exp.
- `BigInteger expMod(BigInteger base, BigInteger exp, BigInteger mod)` — Returns (base^exp) mod modulus using the builtin ExpModInteger operation.
- `BigInteger sign(BigInteger x)` — Returns -1 if negative, 0 if zero, 1 if positive.

#### NativeValueLib

*Native MaryEra Value operations using PV11 builtins (CIP-153).*

- `PlutusData fromData(PlutusData mapData)` — Convert Map-encoded PlutusData to native Value.
- `PlutusData toData(PlutusData value)` — Convert native Value back to Map-encoded PlutusData.
- `PlutusData insertCoin(byte[] policyId, byte[] tokenName, BigInteger amount, PlutusData value)` — Insert or update a token quantity in a Value.
- `BigInteger lookupCoin(byte[] policyId, byte[] tokenName, PlutusData value)` — Look up a token quantity. Returns 0 if absent.
- `PlutusData union(PlutusData a, PlutusData b)` — Merge two Values by adding quantities.
- `boolean contains(PlutusData a, PlutusData b)` — Check if Value a contains at least Value b (a >= b element-wise).
- `PlutusData scale(BigInteger scalar, PlutusData value)` — Scale all quantities by a scalar.

#### OutputLib

*Transaction output utility operations compiled from Java source to UPLC.*

- `Address txOutAddress(TxOut txOut)` — Extract the address from a TxOut.
- `Value txOutValue(TxOut txOut)` — Extract the value from a TxOut.
- `OutputDatum txOutDatum(TxOut txOut)` — Extract the datum from a TxOut.
- `JulcList<TxOut> outputsAt(JulcList<TxOut> outputs, Address address)` — Return all outputs sent to the given address.
- `long countOutputsAt(JulcList<TxOut> outputs, Address address)` — Count the number of outputs sent to the given address.
- `TxOut uniqueOutputAt(JulcList<TxOut> outputs, Address address)` — Return the unique output at the given address. Aborts if not exactly one match.
- `JulcList<TxOut> outputsWithToken(JulcList<TxOut> outputs, byte[] policyId, byte[] tokenName)` — Return all outputs containing the specified token (amount > 0).
- `boolean valueHasToken(Value value, byte[] policyId, byte[] tokenName)` — Check if a value contains any amount of the specified token.
- `BigInteger lovelacePaidTo(JulcList<TxOut> outputs, Address address)` — Sum the lovelace in all outputs sent to the given address.
- `boolean paidAtLeast(JulcList<TxOut> outputs, Address address, BigInteger minLovelace)` — Check if the total lovelace paid to the address meets the minimum threshold.
- `PlutusData getInlineDatum(TxOut txOut)` — Extract the inline datum from a TxOut. Aborts if the datum is not inline.
- `PlutusData resolveDatum(TxOut txOut, JulcMap<PlutusData, PlutusData> datumsMap)` — Resolve the datum from a TxOut: inline datum is returned directly, datum hash is looked up in the datums map, NoDatum aborts.
- `TxOut findOutputWithToken(JulcList<TxOut> outputs, byte[] scriptHash, byte[] policyId, byte[] tokenName)` — Find the first output at a script address (identified by scriptHash) containing the specified token with inline datum. Aborts if not found.
- `TxInInfo findInputWithToken(JulcList<TxInInfo> inputs, byte[] scriptHash, byte[] policyId, byte[] tokenName)` — Find the first input at a script address containing the specified token with inline datum. Aborts if not found.

#### ValuesLib

*Value manipulation operations compiled from Java source to UPLC.*

- `BigInteger lovelaceOf(Value value)` — Extracts the lovelace (ADA) amount from a Value.
- `boolean containsPolicy(Value value, byte[] policyId)` — Check if a policy ID (as bytes) exists in a Value.
- `boolean geq(Value a, Value b)` — Checks if lovelaceOf(a) >= lovelaceOf(b).
- `BigInteger assetOf(Value value, byte[] policyId, byte[] tokenName)` — Extracts the amount of a specific asset. Returns 0 if not found.
- `boolean geqMultiAsset(Value a, Value b)` — Checks if value a >= value b for ALL policy/token pairs (multi-asset).
- `boolean leq(Value a, Value b)` — Checks if value a <= value b (multi-asset).
- `boolean eq(Value a, Value b)` — Checks if two values are equal (multi-asset).
- `boolean isZero(Value value)` — Checks if a value is zero (all amounts == 0).
- `Value singleton(byte[] policyId, byte[] tokenName, BigInteger amount)` — Constructs a Value containing a single asset: Map[(policy, Map[(token, amount)])].
- `Value negate(Value value)` — Negates all amounts in a value.
- `JulcList<PlutusData> flatten(Value value)` — Flattens a Value into a list of (policy, token, amount) triples as ConstrData(0, [p, t, amt]).
- `JulcList<AssetEntry> flattenTyped(Value value)` — Typed variant of #flatten(Value) that returns `JulcList`. Each element provides typed field access: `entry.policyId()`, `entry.tokenName()`, `entry.amount()`.
- `Value add(Value a, Value b)` — Adds two Values together (union, adding amounts for matching policy/token).
- `Value subtract(Value a, Value b)` — Subtracts value b from value a: add(a, negate(b)).
- `BigInteger countTokensWithQty(Value mint, byte[] policyId, BigInteger expectedQty)` — Count tokens with a specific quantity under a specific policy in a Value/mint. E.g., count how many tokens were minted with qty=1 under a given policy.
- `byte[] findTokenName(Value mint, byte[] policyId, BigInteger expectedQty)` — Find the first token name with a specific quantity under a specific policy. Returns empty bytestring if not found.

<!-- catalog:stdlib-end -->

---

## 9. Ledger types (always prefer over raw PlutusData)

All ledger types live in `com.bloxbean.cardano.julc.ledger.*`.

### Core context
- **`ScriptContext`** — `txInfo() : TxInfo`, `redeemer() : PlutusData`, `scriptInfo() : ScriptInfo`.
- **`TxInfo`** — `inputs() : JulcList<TxInInfo>`, `referenceInputs()`, `outputs() : JulcList<TxOut>`, `fee() : BigInteger`, `mint() : Value`, `certificates() : JulcList<TxCert>`, `withdrawals() : JulcMap<Credential, BigInteger>`, `validRange() : Interval`, `signatories() : JulcList<PubKeyHash>`, `redeemers() : JulcMap<ScriptPurpose, PlutusData>`, `datums() : JulcMap<DatumHash, PlutusData>`, `id() : TxId`, `votes()`, `proposalProcedures()`.
- **`TxInInfo`** — `outRef() : TxOutRef`, `resolved() : TxOut`.
- **`TxOut`** — `address() : Address`, `value() : Value`, `datum() : OutputDatum`, `referenceScript() : Optional<ScriptHash>`.
- **`TxOutRef`** — `txId() : TxId`, `index() : BigInteger`.
- **`ScriptInfo`** sealed interface: `MintingScript(policyId)`, `SpendingScript(txOutRef, datum)`, `RewardingScript(credential)`, `CertifyingScript(index, cert)`, `VotingScript(voter)`, `ProposingScript(index, procedure)`.

### Address & credentials
- **`Address`** — `credential() : Credential`, `stakingCredential() : Optional<StakingCredential>`.
- **`Credential`** sealed: `PubKeyCredential(hash)`, `ScriptCredential(hash)`. Switch on it for address-type checks.
- **`StakingCredential`** sealed: `StakingHash(credential)`, `StakingPtr(slot, txIndex, certIndex)`.

### Hashes (newtype wrappers around `byte[]`)
- `PubKeyHash`, `ScriptHash`, `ValidatorHash`, `PolicyId`, `TokenName`, `DatumHash`, `TxId` — each has `.hash() : byte[]` and a `.of(byte[])` factory: `PubKeyHash.of(bytes)`. **Never use `(PubKeyHash)(Object) bytes` casts** — use `.of(...)`.

### Value & assets
- **`Value`** — Multi-asset value. Methods: `lovelaceOf() : BigInteger`, `assetOf(policy, token) : BigInteger`, `isEmpty() : boolean`. For arithmetic use `ValuesLib.add/subtract/eq/leq/geq`.

### Datum
- **`OutputDatum`** sealed: `NoOutputDatum()`, `OutputDatumHash(hash)`, `OutputDatumInline(datum)`. Switch on it to extract.

### Time
- **`Interval`** — `from() : IntervalBound`, `to() : IntervalBound`.
- **`IntervalBound`** — `boundType() : IntervalBoundType`, `isInclusive() : boolean`.
- **`IntervalBoundType`** sealed: `NegInf()`, `Finite(time)`, `PosInf()`. Watch for **limitation 6.4** if you switch on this.

### Conway governance
- **`Vote`** sealed: `Yes()`, `No()`, `Abstain()`.
- **`DRep`** sealed: `DRepCredential(credential)`, `AlwaysAbstain()`, `AlwaysNoConfidence()`.
- **`Voter`** sealed: `CommitteeVoter(credential)`, `DRepVoter(credential)`, `StakePoolVoter(pkh)`.
- **`GovernanceAction`** sealed (many variants): `ParameterChange`, `HardForkInitiation`, `TreasuryWithdrawals`, `NoConfidence`, `UpdateCommittee`, `NewConstitution`, `InfoAction`.
- **`TxCert`** sealed: many cert variants.
- **`ProposalProcedure`** record.
- **`ScriptPurpose`** sealed: `MintingPurpose`, `SpendingPurpose`, `RewardingPurpose`, `CertifyingPurpose`, `VotingPurpose`, `ProposingPurpose`.

### Generic
- **`Optional<T>`** — `Optional.of(x)` / `Optional.empty()` factories, `.isPresent()`, `.get()`, `.orElse(x)`. Compiled to `ConstrData(0, [x])` / `ConstrData(1, [])`.
- **`Tuple2<A,B>`** — `.first()`, `.second()`. Use field access; **not switchable** (limitation 6.2).
- **`Tuple3<A,B,C>`** — `.first()`, `.second()`, `.third()`.
- **`JulcList<T>`** — On-chain list. Instance methods: `head()`, `tail()`, `get(i)`, `size()`, `isEmpty()`, `contains(x)`, `prepend(x)`, `reverse()`, `concat(other)`, `take(n)`, `drop(n)`, `map(f)`, `filter(p)`, `any(p)`, `all(p)`, `find(p)`. **Iterable**: `for (var x : list) { ... }`.
- **`JulcMap<K,V>`** — On-chain map (association list of pairs). Instance methods: `get(k)`, `lookup(k) : Optional<V>`, `containsKey(k)`, `size()`, `isEmpty()`, `keys()`, `values()`, `insert(k, v)`, `delete(k)`. **Iterable as pairs**: `for (var entry : map) { K k = entry.key(); V v = entry.value(); }`.

<!-- catalog:ledger-start -->

### Full machine-readable ledger types reference

*Auto-generated from `julc-ledger-api/.../ledger/*.java`. Source of truth: [/ai/catalog.json](/ai/catalog.json).*

#### Records

- **`Address`** — A Cardano address: a payment credential and optional staking credential.
  - Fields: `Credential credential`, `Optional<StakingCredential> stakingCredential`
- **`Committee`** — A governance committee.
  - Fields: `JulcMap<Credential, BigInteger> members`, `Rational quorum`
- **`DatumHash`** — A datum hash (typically 32 bytes). No byte-length validation — length enforcement is a ledger rule, not a type invariant.
  - Fields: `byte[] hash`
  - Methods: `byte[] hash()`, `DatumHash of(byte[] hash)`
- **`GovernanceActionId`** — A governance action identifier.
  - Fields: `TxId txId`, `BigInteger govActionIx`
- **`Interval`** — A time interval with lower and upper bounds.
  - Fields: `IntervalBound from`, `IntervalBound to`
  - Methods: `Interval always()`, `Interval never()`, `Interval after(BigInteger time)`, `Interval before(BigInteger time)`, `Interval between(BigInteger from, BigInteger to)`
- **`IntervalBound`** — A bound of an interval with inclusivity flag.
  - Fields: `IntervalBoundType boundType`, `boolean isInclusive`
- **`PolicyId`** — A minting policy ID (typically 28 bytes, or 0 bytes for ADA). No byte-length validation — length enforcement is a ledger rule, not a type invariant.
  - Fields: `byte[] hash`
  - Methods: `byte[] hash()`, `PolicyId of(byte[] hash)`
- **`ProposalProcedure`** — A governance proposal procedure.
  - Fields: `BigInteger deposit`, `Credential returnAddress`, `GovernanceAction governanceAction`
- **`ProtocolVersion`** — A protocol version (major, minor).
  - Fields: `BigInteger major`, `BigInteger minor`
- **`PubKeyHash`** — A public key hash (typically 28 bytes). No byte-length validation — length enforcement is a ledger rule, not a type invariant.
  - Fields: `byte[] hash`
  - Methods: `byte[] hash()`, `PubKeyHash of(byte[] hash)`
- **`Rational`** — A rational number (numerator / denominator).
  - Fields: `BigInteger numerator`, `BigInteger denominator`
- **`ScriptContext`** — V3 script context: transaction info + redeemer + script info.
  - Fields: `TxInfo txInfo`, `PlutusData redeemer`, `ScriptInfo scriptInfo`
- **`ScriptHash`** — A script hash (typically 28 bytes). No byte-length validation — length enforcement is a ledger rule, not a type invariant.
  - Fields: `byte[] hash`
  - Methods: `byte[] hash()`, `ScriptHash of(byte[] hash)`
- **`TokenName`** — A token name (typically 0-32 bytes). No byte-length validation — length enforcement is a ledger rule, not a type invariant.
  - Fields: `byte[] name`
  - Methods: `byte[] name()`, `TokenName of(byte[] name)`
- **`TxId`** — A transaction ID (typically 32 bytes). No byte-length validation — length enforcement is a ledger rule, not a type invariant.
  - Fields: `byte[] hash`
  - Methods: `byte[] hash()`, `TxId of(byte[] hash)`
- **`TxInInfo`** — An input being consumed or referenced by a transaction.
  - Fields: `TxOutRef outRef`, `TxOut resolved`
- **`TxInfo`** — V3 (Conway) transaction info with 16 fields.
  - Fields: `JulcList<TxInInfo> inputs`, `JulcList<TxInInfo> referenceInputs`, `JulcList<TxOut> outputs`, `BigInteger fee`, `Value mint`, `JulcList<TxCert> certificates`, `JulcMap<Credential, BigInteger> withdrawals`, `Interval validRange`, `JulcList<PubKeyHash> signatories`, `JulcMap<ScriptPurpose, PlutusData> redeemers`, `JulcMap<DatumHash, PlutusData> datums`, `TxId id`, `JulcMap<Voter, JulcMap<GovernanceActionId, Vote>> votes`, `JulcList<ProposalProcedure> proposalProcedures`, `Optional<BigInteger> currentTreasuryAmount`, `Optional<BigInteger> treasuryDonation`
- **`TxOut`** — A transaction output.
  - Fields: `Address address`, `Value value`, `OutputDatum datum`, `Optional<ScriptHash> referenceScript`
- **`TxOutRef`** — A reference to a transaction output (TxId + output index).
  - Fields: `TxId txId`, `BigInteger index`
- **`ValidatorHash`** — A validator hash (typically 28 bytes, semantically an alias for ScriptHash). No byte-length validation — length enforcement is a ledger rule, not a type invariant.
  - Fields: `byte[] hash`
  - Methods: `byte[] hash()`, `ValidatorHash of(byte[] hash)`
- **`Value`** — A multi-asset value: JulcMap&lt;PolicyId, JulcMap&lt;TokenName, BigInteger&gt;&gt;.
  - Fields: `JulcMap<PolicyId, JulcMap<TokenName, BigInteger>> inner`
  - Methods: `Value zero()`, `Value lovelace(BigInteger amount)`, `Value singleton(PolicyId policyId, TokenName tokenName, BigInteger quantity)`, `BigInteger lovelaceOf()`, `boolean containsPolicy(PolicyId policyId)`, `BigInteger assetOf(PolicyId policyId, TokenName tokenName)`, `boolean isEmpty()`, `Value merge(Value other)`

#### Sealed interfaces

- **`Credential`** — A credential: either a public key hash or a script hash.
  - `PubKeyCredential(PubKeyHash hash)`
  - `ScriptCredential(ScriptHash hash)`
- **`DRep`** — A delegated representative (DRep).
  - `DRepCredential(Credential credential)`
  - `AlwaysAbstain()`
  - `AlwaysNoConfidence()`
- **`Delegatee`** — A delegation target.
  - `Stake(PubKeyHash poolId)`
  - `Vote(DRep dRep)`
  - `StakeVote(PubKeyHash poolId, DRep dRep)`
- **`GovernanceAction`** — A governance action (7 variants).
  - `ParameterChange(Optional<GovernanceActionId> id, PlutusData parameters, Optional<ScriptHash> constitutionScript)`
  - `HardForkInitiation(Optional<GovernanceActionId> id, ProtocolVersion protocolVersion)`
  - `TreasuryWithdrawals(JulcMap<Credential, BigInteger> withdrawals, Optional<ScriptHash> constitutionScript)`
  - `NoConfidence(Optional<GovernanceActionId> id)`
  - `UpdateCommittee(Optional<GovernanceActionId> id, JulcList<Credential> removedMembers, JulcMap<Credential, BigInteger> addedMembers, Rational newQuorum)`
  - `NewConstitution(Optional<GovernanceActionId> id, Optional<ScriptHash> constitution)`
  - `InfoAction()`
- **`IntervalBoundType`** — The type of an interval bound: negative infinity, finite, or positive infinity.
  - `NegInf()`
  - `Finite(BigInteger time)`
  - `PosInf()`
- **`OutputDatum`** — Datum attached to a transaction output.
  - `NoOutputDatum()`
  - `OutputDatumHash(DatumHash hash)`
  - `OutputDatumInline(PlutusData datum)`
- **`ScriptInfo`** — Information about the currently executing script (6 variants).
  - `MintingScript(PolicyId policyId)`
  - `SpendingScript(TxOutRef txOutRef, Optional<PlutusData> datum)`
  - `RewardingScript(Credential credential)`
  - `CertifyingScript(BigInteger index, TxCert cert)`
  - `VotingScript(Voter voter)`
  - `ProposingScript(BigInteger index, ProposalProcedure procedure)`
- **`ScriptPurpose`** — The purpose of a script execution (6 variants).
  - `Minting(PolicyId policyId)`
  - `Spending(TxOutRef txOutRef)`
  - `Rewarding(Credential credential)`
  - `Certifying(BigInteger index, TxCert cert)`
  - `Voting(Voter voter)`
  - `Proposing(BigInteger index, ProposalProcedure procedure)`
- **`StakingCredential`** — A staking credential: either a hash-based credential or a pointer.
  - `StakingHash(Credential credential)`
  - `StakingPtr(BigInteger slot, BigInteger txIndex, BigInteger certIndex)`
- **`TxCert`** — A transaction certificate (V3 Conway era, 11 variants).
  - `RegStaking(Credential credential, Optional<BigInteger> deposit)`
  - `UnRegStaking(Credential credential, Optional<BigInteger> refund)`
  - `DelegStaking(Credential credential, Delegatee delegatee)`
  - `RegDeleg(Credential credential, Delegatee delegatee, BigInteger deposit)`
  - `RegDRep(Credential credential, BigInteger deposit)`
  - `UpdateDRep(Credential credential)`
  - `UnRegDRep(Credential credential, BigInteger refund)`
  - `PoolRegister(PubKeyHash poolId, PubKeyHash poolVfr)`
  - `PoolRetire(PubKeyHash pubKeyHash, BigInteger epoch)`
  - `AuthHotCommittee(Credential cold, Credential hot)`
  - `ResignColdCommittee(Credential cold)`
- **`Vote`** — A governance vote.
  - `VoteNo()`
  - `VoteYes()`
  - `Abstain()`
- **`Voter`** — A governance voter.
  - `CommitteeVoter(Credential credential)`
  - `DRepVoter(Credential credential)`
  - `StakePoolVoter(PubKeyHash pubKeyHash)`

<!-- catalog:ledger-end -->

---

## 10. Canonical examples (idiomatic JuLC)

These are real, tested validators from [`julc-examples`](https://github.com/bloxbean/julc-examples). **Use these as templates.**

### 10.1 Spending validator with typed datum/redeemer (vesting)

```java
package com.example.validators;

import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.TxInfo;
import com.bloxbean.cardano.julc.ledger.PubKeyHash;

import java.math.BigInteger;

@SpendingValidator
public class VestingValidator {

    record VestingDatum(byte[] beneficiary, BigInteger deadline) {}
    record VestingRedeemer(BigInteger action) {}

    @Entrypoint
    public static boolean validate(VestingDatum datum, VestingRedeemer redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        return txInfo.signatories().contains(PubKeyHash.of(datum.beneficiary()));
    }
}
```

### 10.2 Sealed-interface redeemer with switch (auction)

```java
@SpendingValidator
public class AuctionValidator {

    record AuctionDatum(byte[] seller, BigInteger reservePrice) {}

    sealed interface AuctionAction permits Bid, Close {}
    record Bid(byte[] bidder, BigInteger amount) implements AuctionAction {}
    record Close() implements AuctionAction {}

    @Entrypoint
    public static boolean validate(AuctionDatum datum, AuctionAction redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        return switch (redeemer) {
            case Bid b -> {
                boolean bidderSigned = txInfo.signatories().contains(PubKeyHash.of(b.bidder()));
                boolean meetsReserve = b.amount().compareTo(datum.reservePrice()) >= 0;
                yield bidderSigned && meetsReserve;
            }
            case Close c -> txInfo.signatories().contains(PubKeyHash.of(datum.seller()));
        };
    }
}
```

### 10.3 Parameterized minting policy (one-shot mint)

```java
import com.bloxbean.cardano.julc.stdlib.annotation.MintingValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.Builtins;

@MintingValidator
public class OneShotMintPolicy {

    @Param static byte[] utxoTxId;
    @Param static BigInteger utxoIndex;

    @Entrypoint
    public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        boolean found = false;
        for (var input : txInfo.inputs()) {
            var ref = input.outRef();
            byte[] refTxIdBytes = Builtins.toByteString(ref.txId());
            if (Builtins.equalsByteString(refTxIdBytes, utxoTxId)
                && ref.index().compareTo(utxoIndex) == 0) {
                found = true;
                break;
            }
        }
        return found;
    }
}
```

### 10.4 Output checking with OutputLib (escrow)

```java
@SpendingValidator
public class OutputCheckValidator {
    record PaymentDatum(Address recipientAddress, BigInteger minAmount) {}

    @Entrypoint
    public static boolean validate(PaymentDatum datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        JulcList<TxOut> outputs = txInfo.outputs();
        BigInteger paid = OutputLib.lovelacePaidTo(outputs, datum.recipientAddress());
        return paid.compareTo(datum.minAmount()) >= 0;
    }
}
```

### 10.5 HOF with lambda + variable capture (token distribution)

```java
record BeneficiaryEntry(byte[] pkh, BigInteger amount) {}
record DistributionDatum(JulcList<BeneficiaryEntry> beneficiaries) {}

static boolean allBeneficiariesPaid(JulcList<BeneficiaryEntry> beneficiaries,
                                     JulcList<TxOut> outputs) {
    return beneficiaries.all(entry -> {
        BigInteger paid = totalPaidTo(outputs, entry.pkh());
        return paid.compareTo(entry.amount()) >= 0;
    });
}

static BigInteger totalPaidTo(JulcList<TxOut> outputs, byte[] targetPkh) {
    JulcList<TxOut> matching = outputs.filter(out -> {
        Credential cred = out.address().credential();
        return switch (cred) {
            case Credential.PubKeyCredential pk ->
                Builtins.equalsByteString(Builtins.toByteString(pk.hash()), targetPkh);
            case Credential.ScriptCredential sc -> false;
        };
    });
    BigInteger total = BigInteger.ZERO;
    for (var out : matching) {
        total = total.add(out.value().lovelaceOf());
    }
    return total;
}
```

### 10.6 Reusable on-chain library

```java
@OnchainLibrary
public class ValidationUtils {
    public static boolean isAfterDeadline(TxInfo txInfo, BigInteger deadline) {
        return IntervalLib.contains(txInfo.validRange(), deadline);
    }

    public static boolean hasSigner(TxInfo txInfo, byte[] pkh) {
        return txInfo.signatories().contains(PubKeyHash.of(pkh));
    }

    public static boolean hasMinLovelace(Value value, BigInteger minLovelace) {
        return value.lovelaceOf().compareTo(minLovelace) >= 0;
    }
}
```

### 10.7 More real-world examples

For deeper patterns, consult [`julc-examples`](https://github.com/bloxbean/julc-examples):

- `cftemplates/` — Cardano Foundation template patterns (treasury, etc.).
- `nft/Cip68Nft.java` — CIP-68 NFT minting with metadata.
- `uverify/` — multi-script UVerify protocol.
- `mpf/MerklePatriciaForestry.java` — Merkle Patricia Forestry verification (recursive helpers).
- `linkedlist/` — on-chain linked list pattern.
- `swap/` — atomic swap.
- `lending/` — lending protocol primitives.

**Machine-readable index for AI retrieval:** [`/ai/examples.json`](/ai/examples.json) — every example tagged with `id`, `difficulty`, `concepts`, `cipRelevance`, and `kind`. Use this to deterministically answer "show me X-pattern examples" without prose-matching.

---

## 11. Anti-patterns (do NOT do these)

- ❌ **Constructing `PlutusData.ConstrData/IntData/BytesData/MapData/ListData` in on-chain code.** Use records, sealed interfaces, `BigInteger`, `byte[]`, `JulcList`, `JulcMap`.
- ❌ **`(PubKeyHash)(Object) bytes`** — use `PubKeyHash.of(bytes)`.
- ❌ **Storing lambdas in variables.** Pass them inline to HOFs.
- ❌ **`return` inside a `while` loop.** Use a boolean flag.
- ❌ **`var x;` then `x = 5;`.** Initialize at declaration.
- ❌ **Same name for switch case field and method parameter.** Rename one.
- ❌ **`a == b` for `BigInteger` or `String`.** Use `.compareTo` or `.equals`.
- ❌ **`null`.** Use `Optional<T>` or sealed interfaces.
- ❌ **`throw new Exception(...)`.** Use `Builtins.error()` or return `false`.
- ❌ **`@Param PlutusData.BytesData/MapData/ListData/IntData`.** Banned.
- ❌ **Three-way mutual recursion.** Refactor.
- ❌ **Calling `.hash()` on a value that is already hash bytes.** Causes double-unwrap.

---

## 12. Tooling (for the agent's awareness)

- **`julc` CLI** — `julc new`, `julc build`, `julc check`, `julc repl`, `julc eval`.
- **Gradle plugin** — `julc-annotation-processor` is the primary build path; produces `META-INF/plutus/<Validator>.plutus.json`.
- **julc-testkit** — `ValidatorTest.compileValidatorByName(fqcn)`, `evaluate(...)`, `assertSuccess(...)`, plus property-based fuzzing.
- **Off-chain integration** — `JulcScriptLoader.load(MyValidator.class)` returns a `PlutusV3Script` usable with cardano-client-lib's `QuickTxBuilder`.

For a full reference, see [/getting-started/](/getting-started/) and [/reference/api-reference/](/reference/api-reference/).

---

## 13. Quick checklist before generating JuLC code

Before writing any JuLC code, verify the agent can answer "yes" to each:

1. ✅ Am I using `record`s and sealed interfaces, **not** raw `PlutusData.ConstrData/IntData/BytesData/MapData/ListData`?
2. ✅ Are all my variables initialized at declaration?
3. ✅ Have I avoided `return` inside `while` loops?
4. ✅ Are my switch cases exhaustive (or do I have a `default ->` branch)?
5. ✅ Do I use `BigInteger.compareTo` / `BigInteger.equals`, not `==`?
6. ✅ Am I passing lambdas inline to HOFs (not storing them)?
7. ✅ Have I used `PubKeyHash.of(bytes)` style factories instead of `(PubKeyHash)(Object) bytes` casts?
8. ✅ Are switch case binding names different from method parameter names?
9. ✅ Am I using `@SpendingValidator` / `@MintingValidator` etc. correctly with a `static @Entrypoint` method?
10. ✅ Have I imported from `com.bloxbean.cardano.julc.ledger.*` and `com.bloxbean.cardano.julc.stdlib.lib.*`?

If yes to all → write the code. If unsure on any → re-read the relevant section above.

---

*This pack is generated and maintained as part of [ADR-020](https://github.com/bloxbean/julc/blob/main/adr/020-ai-ready-developer-experience.md). Failures in the wild should result in updates here.*
