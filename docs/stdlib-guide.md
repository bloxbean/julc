# JuLC Standard Library Usage Guide

The JuLC standard library provides 11 on-chain libraries in the `com.bloxbean.cardano.julc.stdlib.lib` package. Each library is annotated with `@OnchainLibrary` and compiled from Java source to UPLC. All methods are `static` and can be called directly from your validator code.

## Table of Contents

- [Overview](#overview)
- [Quick Reference](#quick-reference)
- [ContextsLib -- Script Context Operations](#contextslib----script-context-operations)
- [ListsLib -- List Operations](#listslib----list-operations)
- [ValuesLib -- Value Manipulation](#valueslib----value-manipulation)
- [MapLib -- Association List Operations](#maplib----association-list-operations)
- [OutputLib -- Transaction Output Utilities](#outputlib----transaction-output-utilities)
- [MathLib -- Mathematical Operations](#mathlib----mathematical-operations)
- [IntervalLib -- Time Interval Operations](#intervallib----time-interval-operations)
- [CryptoLib -- Cryptographic Operations](#cryptolib----cryptographic-operations)
- [ByteStringLib -- ByteString Operations](#bytestringlib----bytestring-operations)
- [BitwiseLib -- Bitwise Operations](#bitwiselib----bitwise-operations)
- [AddressLib -- Address Operations](#addresslib----address-operations)
- [Important Notes and Caveats](#important-notes-and-caveats)

---

## Overview

| Library | Import Path | Purpose |
|---------|-------------|---------|
| **ContextsLib** | `com.bloxbean.cardano.julc.stdlib.lib.ContextsLib` | Script context, TxInfo field access, signatory checks, datum lookup |
| **ListsLib** | `com.bloxbean.cardano.julc.stdlib.lib.ListsLib` | List construction, traversal, search, and higher-order functions |
| **ValuesLib** | `com.bloxbean.cardano.julc.stdlib.lib.ValuesLib` | Multi-asset Value comparison, arithmetic, and extraction |
| **MapLib** | `com.bloxbean.cardano.julc.stdlib.lib.MapLib` | Association list (map) lookup, insert, delete, keys/values |
| **OutputLib** | `com.bloxbean.cardano.julc.stdlib.lib.OutputLib` | Output filtering by address/token, lovelace summation, datum extraction |
| **MathLib** | `com.bloxbean.cardano.julc.stdlib.lib.MathLib` | abs, max, min, pow, divMod, quotRem, expMod, sign |
| **IntervalLib** | `com.bloxbean.cardano.julc.stdlib.lib.IntervalLib` | Time interval construction, containment, bound extraction |
| **CryptoLib** | `com.bloxbean.cardano.julc.stdlib.lib.CryptoLib` | Hash functions and signature verification |
| **ByteStringLib** | `com.bloxbean.cardano.julc.stdlib.lib.ByteStringLib` | ByteString slicing, comparison, encoding, serialization |
| **BitwiseLib** | `com.bloxbean.cardano.julc.stdlib.lib.BitwiseLib` | Bitwise AND/OR/XOR, shift, rotate, bit read/write |
| **AddressLib** | `com.bloxbean.cardano.julc.stdlib.lib.AddressLib` | Credential extraction, address type checks |

---

## Quick Reference

### ContextsLib

| Method | Description |
|--------|-------------|
| `signedBy(txInfo, pkh)` | Check if a PubKeyHash is in the signatories |
| `getTxInfo(ctx)` | Extract TxInfo from ScriptContext (legacy; prefer `ctx.txInfo()`) |
| `getRedeemer(ctx)` | Extract redeemer from ScriptContext |
| `getSpendingDatum(ctx)` | Extract optional spending datum |
| `txInfoInputs(txInfo)` | Get list of inputs |
| `txInfoOutputs(txInfo)` | Get list of outputs |
| `txInfoSignatories(txInfo)` | Get signatories list |
| `txInfoValidRange(txInfo)` | Get valid time range |
| `txInfoMint(txInfo)` | Get minted value |
| `txInfoFee(txInfo)` | Get transaction fee |
| `txInfoId(txInfo)` | Get transaction ID |
| `txInfoRefInputs(txInfo)` | Get reference inputs |
| `txInfoWithdrawals(txInfo)` | Get withdrawals map |
| `txInfoRedeemers(txInfo)` | Get redeemers map |
| `findOwnInput(ctx)` | Find the input being validated |
| `getContinuingOutputs(ctx)` | Get outputs to the same script address |
| `findDatum(txInfo, hash)` | Look up datum by hash |
| `valueSpent(txInfo)` | Total value of all inputs |
| `valuePaid(txInfo, addr)` | Values paid to an address |
| `ownHash(ctx)` | Get own script hash |
| `scriptOutputsAt(txInfo, hash)` | Get outputs at a script hash |
| `listIndex(list, index)` | Get element at index |
| `trace(msg)` | Emit a trace message |

### ListsLib

| Method | Description |
|--------|-------------|
| `empty()` | Create an empty list |
| `prepend(list, elem)` | Prepend element to list |
| `length(list)` | Number of elements |
| `isEmpty(list)` | Check if list is empty |
| `head(list)` | First element |
| `tail(list)` | All elements except first |
| `reverse(list)` | Reverse a list |
| `concat(a, b)` | Concatenate two lists |
| `nth(list, n)` | Element at index n |
| `take(list, n)` | First n elements |
| `drop(list, n)` | Drop first n elements |
| `contains(list, elem)` | Check if list contains element (EqualsData) |
| `containsInt(list, target)` | Check if integer list contains value (EqualsInteger) |
| `containsBytes(list, target)` | Check if bytestring list contains value (EqualsByteString) |
| `hasDuplicateInts(list)` | Check for duplicate integers |
| `hasDuplicateBytes(list)` | Check for duplicate bytestrings |
| `any(list, pred)` | True if any element matches predicate (HOF) |
| `all(list, pred)` | True if all elements match predicate (HOF) |
| `find(list, pred)` | Find first matching element (HOF) |
| `foldl(f, init, list)` | Left fold (HOF) |
| `map(list, f)` | Transform each element (HOF) |
| `filter(list, pred)` | Keep elements matching predicate (HOF) |
| `zip(a, b)` | Zip two lists into pairs (HOF) |

### ValuesLib

| Method | Description |
|--------|-------------|
| `lovelaceOf(value)` | Extract lovelace amount |
| `assetOf(value, policy, token)` | Extract specific asset amount |
| `containsPolicy(value, policy)` | Check if policy exists in value |
| `geq(a, b)` | Lovelace-only >= comparison |
| `geqMultiAsset(a, b)` | Multi-asset >= comparison |
| `leq(a, b)` | Multi-asset <= comparison |
| `eq(a, b)` | Multi-asset equality |
| `isZero(value)` | Check if all amounts are zero |
| `singleton(policy, token, amount)` | Create single-asset Value |
| `negate(value)` | Negate all amounts |
| `flatten(value)` | Flatten to list of (policy, token, amount) triples |
| `flattenTyped(value)` | Flatten to typed `JulcList<AssetEntry>` with `.policyId()`, `.tokenName()`, `.amount()` |
| `add(a, b)` | Add two Values |
| `subtract(a, b)` | Subtract value b from value a |
| `countTokensWithQty(mint, policy, qty)` | Count tokens with exact quantity under policy |
| `findTokenName(mint, policy, qty)` | Find token name with exact quantity under policy |

### MapLib

| Method | Description |
|--------|-------------|
| `lookup(map, key)` | Look up key; returns Optional (Constr 0/1) |
| `member(map, key)` | Check if key exists |
| `insert(map, key, value)` | Insert key-value pair |
| `delete(map, key)` | Remove key from map |
| `keys(map)` | Extract all keys as list |
| `values(map)` | Extract all values as list |
| `toList(map)` | Convert map to pair list |
| `fromList(list)` | Create map from pair list |
| `size(map)` | Number of entries |

### OutputLib

| Method | Description |
|--------|-------------|
| `txOutAddress(txOut)` | Extract address from output |
| `txOutValue(txOut)` | Extract value from output |
| `txOutDatum(txOut)` | Extract datum from output |
| `outputsAt(outputs, address)` | Filter outputs by address |
| `countOutputsAt(outputs, address)` | Count outputs at address |
| `uniqueOutputAt(outputs, address)` | Exactly one output at address (aborts otherwise) |
| `outputsWithToken(outputs, policy, token)` | Filter outputs by token |
| `valueHasToken(value, policy, token)` | Check if value contains token |
| `lovelacePaidTo(outputs, address)` | Sum lovelace paid to address |
| `paidAtLeast(outputs, address, min)` | Check minimum lovelace at address |
| `getInlineDatum(txOut)` | Get inline datum (aborts if not inline) |
| `resolveDatum(txOut, datumsMap)` | Resolve datum from inline or hash lookup |
| `findOutputWithToken(outputs, scriptHash, policy, token)` | Find output at script address with specific token |
| `findInputWithToken(inputs, scriptHash, policy, token)` | Find input at script address with specific token |

### MathLib

| Method | Description |
|--------|-------------|
| `abs(x)` | Absolute value |
| `max(a, b)` | Maximum of two integers |
| `min(a, b)` | Minimum of two integers |
| `pow(base, exp)` | Exponentiation |
| `sign(x)` | Sign: -1, 0, or 1 |
| `divMod(a, b)` | Division and modulo as Tuple2 |
| `quotRem(a, b)` | Quotient and remainder as Tuple2 |
| `expMod(base, exp, mod)` | Modular exponentiation |

### IntervalLib

| Method | Description |
|--------|-------------|
| `contains(interval, point)` | Check if point is within interval |
| `always()` | The (-inf, +inf) interval |
| `after(t)` | The [t, +inf) interval |
| `before(t)` | The (-inf, t] interval |
| `between(low, high)` | The [low, high] interval |
| `never()` | The empty interval |
| `isEmpty(interval)` | Check if interval is empty |
| `finiteUpperBound(interval)` | Extract finite upper bound (-1 if infinite) |
| `finiteLowerBound(interval)` | Extract finite lower bound (-1 if infinite) |

### CryptoLib

| Method | Description |
|--------|-------------|
| `sha2_256(bs)` | SHA2-256 hash |
| `sha3_256(bs)` | SHA3-256 hash |
| `blake2b_256(bs)` | Blake2b-256 hash |
| `blake2b_224(bs)` | Blake2b-224 hash (key hashes) |
| `keccak_256(bs)` | Keccak-256 hash |
| `verifyEd25519Signature(key, msg, sig)` | Verify Ed25519 signature |
| `verifyEcdsaSecp256k1(key, msg, sig)` | Verify ECDSA secp256k1 signature |
| `verifySchnorrSecp256k1(key, msg, sig)` | Verify Schnorr secp256k1 signature |
| `ripemd_160(bs)` | RIPEMD-160 hash |

### ByteStringLib

| Method | Description |
|--------|-------------|
| `at(bs, index)` | Get byte at index |
| `cons(byte_, bs)` | Prepend a byte |
| `slice(bs, start, length)` | Extract a slice |
| `length(bs)` | Length of bytestring |
| `drop(bs, n)` | Drop first n bytes |
| `take(bs, n)` | Take first n bytes |
| `append(a, b)` | Concatenate two bytestrings |
| `empty()` | Empty bytestring |
| `zeros(n)` | Bytestring of n zero bytes |
| `equals(a, b)` | Equality check |
| `lessThan(a, b)` | Lexicographic a < b |
| `lessThanEquals(a, b)` | Lexicographic a <= b |
| `integerToByteString(endian, width, i)` | Convert integer to bytestring |
| `byteStringToInteger(endian, bs)` | Convert bytestring to integer |
| `encodeUtf8(s)` | Encode string as UTF-8 bytes |
| `decodeUtf8(bs)` | Decode UTF-8 bytes to string |
| `serialiseData(d)` | Serialize Data to CBOR bytes |
| `hexNibble(n)` | Convert nibble (0-15) to hex ASCII code |
| `toHex(bs)` | Convert bytestring to hex-encoded bytestring |
| `intToDecimalString(n)` | Convert integer to decimal digit bytestring |
| `utf8ToInteger(bs)` | Parse UTF-8 decimal string to integer (inverse of `intToDecimalString`) |

### BitwiseLib

| Method | Description |
|--------|-------------|
| `andByteString(padding, a, b)` | Bitwise AND |
| `orByteString(padding, a, b)` | Bitwise OR |
| `xorByteString(padding, a, b)` | Bitwise XOR |
| `complementByteString(bs)` | Bitwise complement |
| `readBit(bs, index)` | Read bit at index |
| `writeBits(bs, indices, value)` | Write bits at indices |
| `shiftByteString(bs, n)` | Shift by n bits |
| `rotateByteString(bs, n)` | Rotate by n bits |
| `countSetBits(bs)` | Count set bits (popcount) |
| `findFirstSetBit(bs)` | Index of first set bit (-1 if none) |

### AddressLib

| Method | Description |
|--------|-------------|
| `credentialHash(address)` | Extract payment credential hash bytes |
| `isScriptAddress(address)` | Check if address has ScriptCredential |
| `isPubKeyAddress(address)` | Check if address has PubKeyCredential |
| `paymentCredential(address)` | Extract payment Credential |

---

## ContextsLib -- Script Context Operations

**Import**: `com.bloxbean.cardano.julc.stdlib.lib.ContextsLib`

ContextsLib provides access to the Plutus V3 `ScriptContext`, `TxInfo`, and `ScriptInfo` types. For modern validators using typed `ScriptContext`, you can access fields directly (e.g., `ctx.txInfo()`) instead of using the legacy accessor methods.

### Signatory Check

The most common operation: verify that a specific public key hash signed the transaction.

```java
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import java.math.BigInteger;

@SpendingValidator
class AuthorizedSpend {
    record Datum(byte[] owner) {}

    @Entrypoint
    static boolean validate(Datum datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        return ContextsLib.signedBy(txInfo, datum.owner());
    }
}
```

### TxInfo Field Access

You can use either the typed field access on `TxInfo` directly or the ContextsLib accessor methods:

```java
@SpendingValidator
class FieldAccessExample {
    @Entrypoint
    static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();

        // Direct typed field access (preferred)
        JulcList<TxInInfo> inputs = txInfo.inputs();
        JulcList<TxOut> outputs = txInfo.outputs();
        JulcList<PubKeyHash> signatories = txInfo.signatories();
        Interval validRange = txInfo.validRange();
        Value mint = txInfo.mint();
        BigInteger fee = txInfo.fee();
        TxId txId = txInfo.id();

        // Or via ContextsLib (equivalent, legacy style)
        JulcList<TxInInfo> inputs2 = ContextsLib.txInfoInputs(txInfo);
        Value mint2 = ContextsLib.txInfoMint(txInfo);

        return true;
    }
}
```

### Finding Own Input and Continuing Outputs

For spending validators that need to identify their own UTxO and find outputs returning to the same script address:

```java
@SpendingValidator
class StatefulValidator {
    record State(BigInteger counter) {}

    @Entrypoint
    static boolean validate(State datum, PlutusData redeemer, ScriptContext ctx) {
        // findOwnInput returns Optional encoded as Constr(0, [txInInfo]) or Constr(1, [])
        PlutusData.ConstrData ownInputOpt = ContextsLib.findOwnInput(ctx);

        // getContinuingOutputs finds outputs to the same script address
        PlutusData.ListData continuingOutputs = ContextsLib.getContinuingOutputs(ctx);

        // Get own script hash (works for both minting and spending)
        PlutusData.BytesData ownHash = ContextsLib.ownHash(ctx);

        return !Builtins.nullList(continuingOutputs);
    }
}
```

### Trace Messages

Emit debug trace messages that appear in transaction evaluation logs:

```java
@SpendingValidator
class TracingValidator {
    @Entrypoint
    static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
        ContextsLib.trace("Starting validation");
        TxInfo txInfo = ctx.txInfo();

        if (ListsLib.isEmpty(txInfo.inputs())) {
            ContextsLib.trace("No inputs found");
            return false;
        }

        ContextsLib.trace("Validation passed");
        return true;
    }
}
```

---

## ListsLib -- List Operations

**Import**: `com.bloxbean.cardano.julc.stdlib.lib.ListsLib`

ListsLib provides list construction, traversal, searching, and higher-order functions. In Plutus, lists are singly-linked (cons lists). Most operations are O(n).

### Basic List Operations

```java
@SpendingValidator
class ListExample {
    @Entrypoint
    static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        JulcList<TxOut> outputs = txInfo.outputs();

        // Size and emptiness
        long count = outputs.size();       // or ListsLib.length(outputs)
        boolean empty = outputs.isEmpty();  // or ListsLib.isEmpty(outputs)

        // Element access
        TxOut first = outputs.head();       // or ListsLib.head(outputs)
        JulcList<TxOut> rest = outputs.tail(); // or ListsLib.tail(outputs)

        return count > 0;
    }
}
```

### Search Operations

```java
@SpendingValidator
class ListSearchExample {
    @Entrypoint
    static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        JulcList<PubKeyHash> signatories = txInfo.signatories();

        // Check for duplicates (O(n^2))
        boolean hasDups = ListsLib.hasDuplicateBytes(signatories);

        return !hasDups;
    }
}
```

### Higher-Order Functions (Lambda Required)

The HOF methods (`any`, `all`, `find`, `foldl`, `map`, `filter`, `zip`) accept lambda expressions. These are compiled via PIR and require lambda support.

```java
@SpendingValidator
class HofExample {
    @Entrypoint
    static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        JulcList<TxOut> outputs = txInfo.outputs();

        // any: true if any output has more than 5 ADA
        boolean hasLargeOutput = ListsLib.any(outputs,
            out -> ValuesLib.lovelaceOf(out.value()) > 5_000_000);

        // all: true if all outputs go to pub key addresses
        boolean allPubKey = ListsLib.all(outputs,
            out -> AddressLib.isPubKeyAddress(out.address()));

        // filter: keep only outputs above 2 ADA
        JulcList<TxOut> largeOutputs = ListsLib.filter(outputs,
            out -> ValuesLib.lovelaceOf(out.value()) > 2_000_000);

        // foldl: sum all output lovelace
        BigInteger totalLovelace = ListsLib.foldl(
            (acc, out) -> acc + ValuesLib.lovelaceOf(out.value()),
            BigInteger.ZERO,
            outputs);

        return hasLargeOutput;
    }
}
```

These HOF methods are also available as instance methods on `JulcList`. Lambda
parameter types are auto-inferred from the list element type:

```java
// Instance method equivalents
boolean hasLargeOutput = outputs.any(
    out -> ValuesLib.lovelaceOf(out.value()) > 5_000_000);

JulcList<TxOut> largeOutputs = outputs.filter(
    out -> ValuesLib.lovelaceOf(out.value()) > 2_000_000);

// Chaining is supported
var result = outputs.filter(out -> isLarge(out)).map(out -> transform(out));
```

`foldl` is only available as a static call (`ListsLib.foldl`) because it takes
two lambda parameters plus an initial value.

### For-Each Loops

JuLC supports for-each iteration over lists directly, which is often more readable than HOFs:

```java
@SpendingValidator
class ForEachExample {
    @Entrypoint
    static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        BigInteger total = BigInteger.ZERO;

        for (TxOut out : txInfo.outputs()) {
            total = total.add(ValuesLib.lovelaceOf(out.value()));
        }

        return total.compareTo(BigInteger.valueOf(10_000_000)) > 0;
    }
}
```

---

## ValuesLib -- Value Manipulation

**Import**: `com.bloxbean.cardano.julc.stdlib.lib.ValuesLib`

ValuesLib operates on Plutus `Value` types, which are nested maps: `Map<PolicyId, Map<TokenName, Integer>>`. Lovelace is stored under the empty bytestring policy and token name.

### Extracting Amounts

```java
@SpendingValidator
class ValueExample {
    @Entrypoint
    static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        TxOut firstOutput = txInfo.outputs().head();
        Value outputValue = firstOutput.value();

        // Extract lovelace
        BigInteger lovelace = ValuesLib.lovelaceOf(outputValue);

        // Extract a specific native token amount
        byte[] policyId = new byte[]{/* ... */};
        byte[] tokenName = new byte[]{/* ... */};
        BigInteger tokenAmount = ValuesLib.assetOf(outputValue, policyId, tokenName);

        // Check if a policy exists
        boolean hasPolicy = ValuesLib.containsPolicy(outputValue, policyId);

        return lovelace.compareTo(BigInteger.valueOf(2_000_000)) >= 0;
    }
}
```

### Value Comparisons

```java
@SpendingValidator
class ValueComparisonExample {
    @Entrypoint
    static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        TxOut out1 = txInfo.outputs().head();
        TxOut out2 = txInfo.outputs().tail().head();

        Value v1 = out1.value();
        Value v2 = out2.value();

        // Multi-asset comparison (checks ALL policy/token pairs)
        boolean v1GreaterOrEqual = ValuesLib.geqMultiAsset(v1, v2);
        boolean v1LessOrEqual = ValuesLib.leq(v1, v2);
        boolean valuesEqual = ValuesLib.eq(v1, v2);
        boolean valueIsZero = ValuesLib.isZero(v1);

        // Lovelace-only comparison
        boolean lovelaceGeq = ValuesLib.geq(v1, v2);

        return v1GreaterOrEqual;
    }
}
```

### Value Arithmetic

```java
@SpendingValidator
class ValueArithmeticExample {
    @Entrypoint
    static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        Value inputValue = txInfo.inputs().head().resolved().value();
        Value outputValue = txInfo.outputs().head().value();

        // Create a singleton value
        byte[] policy = new byte[]{/* ... */};
        byte[] token = new byte[]{/* ... */};
        Value fee = ValuesLib.singleton(policy, token, BigInteger.valueOf(100));

        // Add and subtract values
        Value combined = ValuesLib.add(inputValue, fee);
        Value difference = ValuesLib.subtract(inputValue, outputValue);

        // Negate a value (flip all amounts)
        Value negated = ValuesLib.negate(fee);

        // Flatten to inspect all assets
        PlutusData.ListData triples = ValuesLib.flatten(inputValue);

        return ValuesLib.geqMultiAsset(inputValue, outputValue);
    }
}
```

### Typed Asset Iteration

`ValuesLib.flattenTyped()` returns a `JulcList<AssetEntry>` for type-safe iteration over all assets in a Value. Each `AssetEntry` provides `.policyId()`, `.tokenName()`, and `.amount()` field access without manual destructuring.

```java
@SpendingValidator
class TokenLeakCheck {
    @Entrypoint
    static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
        TxOut output = ctx.txInfo().outputs().head();
        long nonAdaCount = 0;
        for (AssetEntry asset : ValuesLib.flattenTyped(output.value())) {
            byte[] policy = asset.policyId();
            byte[] name = asset.tokenName();
            BigInteger amount = asset.amount();
            if (Builtins.lengthOfByteString(policy) > 0) {
                nonAdaCount = nonAdaCount + 1;
            }
        }
        return nonAdaCount == 1;
    }
}
```

> **Tip:** Use `flattenTyped()` instead of `flatten()` whenever you need to inspect individual assets. The raw `flatten()` returns `PlutusData.ListData` requiring manual `Builtins.constrFields()` + `headList`/`tailList` destructuring.

---

## MapLib -- Association List Operations

**Import**: `com.bloxbean.cardano.julc.stdlib.lib.MapLib`

In Plutus, maps are association lists (`List<Pair<Data, Data>>`), not hash maps. Lookups are O(n). Insert prepends (shadowing existing keys).

### Basic Map Operations

```java
@SpendingValidator
class MapExample {
    @Entrypoint
    static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();

        // Withdrawals is a Map<Credential, BigInteger>
        JulcMap<Credential, BigInteger> withdrawals = txInfo.withdrawals();

        // Check membership and lookup
        PlutusData key = /* some key */;
        boolean exists = withdrawals.containsKey(key);

        // Size
        long mapSize = withdrawals.size();

        return exists;
    }
}
```

### Map Modification

```java
@SpendingValidator
class MapModifyExample {
    @Entrypoint
    static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
        PlutusData.MapData myMap = Builtins.mapData(Builtins.mkNilPairData());

        // Insert entries
        PlutusData key1 = Builtins.iData(BigInteger.ONE);
        PlutusData val1 = Builtins.iData(BigInteger.valueOf(100));
        myMap = MapLib.insert(myMap, key1, val1);

        PlutusData key2 = Builtins.iData(BigInteger.valueOf(2));
        PlutusData val2 = Builtins.iData(BigInteger.valueOf(200));
        myMap = MapLib.insert(myMap, key2, val2);

        // Lookup returns Optional: Constr(0, [value]) or Constr(1, [])
        PlutusData.ConstrData result = MapLib.lookup(myMap, key1);
        boolean found = Builtins.constrTag(result) == 0;

        // Delete a key
        myMap = MapLib.delete(myMap, key1);

        // Extract keys and values as lists
        PlutusData.ListData allKeys = MapLib.keys(myMap);
        PlutusData.ListData allValues = MapLib.values(myMap);

        return MapLib.size(myMap) == 1;
    }
}
```

### Iterating Over Maps

Use for-each with `JulcMap` or iterate over the pair list:

```java
@SpendingValidator
class MapIterateExample {
    @Entrypoint
    static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        JulcMap<Credential, BigInteger> withdrawals = txInfo.withdrawals();

        // For-each on a map iterates over key-value pairs
        BigInteger totalWithdrawn = BigInteger.ZERO;
        for (var entry : withdrawals) {
            BigInteger amount = entry.value();
            totalWithdrawn = totalWithdrawn.add(amount);
        }

        return totalWithdrawn.compareTo(BigInteger.ZERO) > 0;
    }
}
```

---

## OutputLib -- Transaction Output Utilities

**Import**: `com.bloxbean.cardano.julc.stdlib.lib.OutputLib`

OutputLib provides high-level operations for filtering and inspecting transaction outputs. It uses typed ledger types (`TxOut`, `Address`, `Value`, `OutputDatum`).

### Filtering Outputs

```java
@SpendingValidator
class OutputFilterExample {
    record Datum(Address recipient) {}

    @Entrypoint
    static boolean validate(Datum datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        JulcList<TxOut> outputs = txInfo.outputs();

        // Filter outputs by address
        JulcList<TxOut> recipientOutputs = OutputLib.outputsAt(outputs, datum.recipient());

        // Count outputs at address
        long count = OutputLib.countOutputsAt(outputs, datum.recipient());

        // Get the unique output at an address (aborts if != 1)
        TxOut uniqueOutput = OutputLib.uniqueOutputAt(outputs, datum.recipient());

        return count >= 1;
    }
}
```

### Token Filtering

```java
@SpendingValidator
class TokenFilterExample {
    record Datum(byte[] policyId, byte[] tokenName) {}

    @Entrypoint
    static boolean validate(Datum datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        JulcList<TxOut> outputs = txInfo.outputs();

        // Find all outputs containing a specific token
        JulcList<TxOut> tokenOutputs = OutputLib.outputsWithToken(
            outputs, datum.policyId(), datum.tokenName());

        // Check if a specific output has a token
        TxOut firstOutput = outputs.head();
        boolean hasToken = OutputLib.valueHasToken(
            firstOutput.value(), datum.policyId(), datum.tokenName());

        return !tokenOutputs.isEmpty();
    }
}
```

### Lovelace Payment Checks

```java
@SpendingValidator
class PaymentCheckExample {
    record Datum(Address recipient, BigInteger minPayment) {}

    @Entrypoint
    static boolean validate(Datum datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        JulcList<TxOut> outputs = txInfo.outputs();

        // Sum lovelace paid to an address
        BigInteger totalPaid = OutputLib.lovelacePaidTo(outputs, datum.recipient());

        // Check if minimum payment is met
        boolean sufficient = OutputLib.paidAtLeast(
            outputs, datum.recipient(), datum.minPayment());

        return sufficient;
    }
}
```

### Datum Extraction

```java
@SpendingValidator
class DatumExample {
    record MyDatum(BigInteger value) {}

    @Entrypoint
    static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        TxOut output = txInfo.outputs().head();

        // Get inline datum directly (aborts if not inline)
        PlutusData inlineDatum = OutputLib.getInlineDatum(output);

        // Or resolve datum (handles both inline and hash-based)
        // Pass the datums map from TxInfo
        PlutusData.MapData datumsMap = (PlutusData.MapData)(Object) txInfo.datums();
        PlutusData resolvedDatum = OutputLib.resolveDatum(output, datumsMap);

        return true;
    }
}
```

---

## MathLib -- Mathematical Operations

**Import**: `com.bloxbean.cardano.julc.stdlib.lib.MathLib`

MathLib provides common mathematical functions operating on `BigInteger`. All computations use Plutus integer arithmetic.

### Basic Math

```java
@SpendingValidator
class MathExample {
    @Entrypoint
    static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
        BigInteger a = BigInteger.valueOf(42);
        BigInteger b = BigInteger.valueOf(-10);

        BigInteger absVal = MathLib.abs(b);          // 10
        BigInteger maxVal = MathLib.max(a, b);        // 42
        BigInteger minVal = MathLib.min(a, b);        // -10
        BigInteger signVal = MathLib.sign(b);         // -1
        BigInteger powVal = MathLib.pow(a, BigInteger.valueOf(3)); // 42^3

        return absVal.compareTo(BigInteger.ZERO) > 0;
    }
}
```

### Division and Modular Arithmetic

`divMod` and `quotRem` return `Tuple2<BigInteger, BigInteger>`. Use `.first()` and `.second()` to access results.

```java
import com.bloxbean.cardano.julc.core.types.Tuple2;

@SpendingValidator
class DivModExample {
    @Entrypoint
    static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
        BigInteger a = BigInteger.valueOf(17);
        BigInteger b = BigInteger.valueOf(5);

        // divMod returns (quotient, remainder)
        Tuple2<BigInteger, BigInteger> dm = MathLib.divMod(a, b);
        BigInteger quotient = dm.first();    // 3
        BigInteger remainder = dm.second();  // 2

        // Modular exponentiation: base^exp mod modulus
        BigInteger result = MathLib.expMod(
            BigInteger.valueOf(2),
            BigInteger.valueOf(10),
            BigInteger.valueOf(1000)); // 1024 mod 1000 = 24

        return remainder.compareTo(BigInteger.ZERO) >= 0;
    }
}
```

---

## IntervalLib -- Time Interval Operations

**Import**: `com.bloxbean.cardano.julc.stdlib.lib.IntervalLib`

IntervalLib operates on Plutus `Interval` (POSIXTimeRange) types. Use these for time-locked validators. Time values are POSIX milliseconds as `BigInteger`.

### Time-Locked Validator

```java
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.lib.IntervalLib;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;

@SpendingValidator
class TimeLockValidator {
    record Datum(byte[] beneficiary, BigInteger deadline) {}

    @Entrypoint
    static boolean validate(Datum datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        Interval validRange = txInfo.validRange();

        // Check if the transaction's valid range falls after the deadline
        BigInteger lowerBound = IntervalLib.finiteLowerBound(validRange);
        boolean pastDeadline = lowerBound.compareTo(datum.deadline()) >= 0;

        // Check if beneficiary signed
        boolean signed = ContextsLib.signedBy(txInfo, datum.beneficiary());

        return pastDeadline && signed;
    }
}
```

### Interval Construction and Checks

```java
@SpendingValidator
class IntervalExample {
    @Entrypoint
    static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        Interval txRange = txInfo.validRange();

        // Check if a specific point is within the transaction's valid range
        BigInteger checkTime = BigInteger.valueOf(1700000000000L);
        boolean timeInRange = IntervalLib.contains(txRange, checkTime);

        // Construct intervals
        Interval alwaysValid = IntervalLib.always();
        Interval neverValid = IntervalLib.never();
        Interval afterNoon = IntervalLib.after(BigInteger.valueOf(1700000000000L));
        Interval beforeMidnight = IntervalLib.before(BigInteger.valueOf(1700100000000L));
        Interval window = IntervalLib.between(
            BigInteger.valueOf(1700000000000L),
            BigInteger.valueOf(1700100000000L));

        // Check emptiness
        boolean empty = IntervalLib.isEmpty(neverValid);

        // Extract bounds
        BigInteger upper = IntervalLib.finiteUpperBound(txRange);
        BigInteger lower = IntervalLib.finiteLowerBound(txRange);

        return timeInRange;
    }
}
```

---

## CryptoLib -- Cryptographic Operations

**Import**: `com.bloxbean.cardano.julc.stdlib.lib.CryptoLib`

CryptoLib wraps Plutus cryptographic builtins for hashing and signature verification. These are also available directly via `Builtins`.

### Hash Functions

```java
@SpendingValidator
class HashExample {
    @Entrypoint
    static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
        byte[] data = new byte[]{1, 2, 3};

        byte[] sha256 = CryptoLib.sha2_256(data);
        byte[] sha3 = CryptoLib.sha3_256(data);
        byte[] blake256 = CryptoLib.blake2b_256(data);
        byte[] blake224 = CryptoLib.blake2b_224(data);  // Used for key hashes
        byte[] keccak = CryptoLib.keccak_256(data);
        byte[] ripemd = CryptoLib.ripemd_160(data);

        return Builtins.lengthOfByteString(sha256) == 32;
    }
}
```

### Signature Verification

```java
@SpendingValidator
class SigVerifyExample {
    record Datum(byte[] pubKey, byte[] message) {}

    @Entrypoint
    static boolean validate(Datum datum, byte[] signature, ScriptContext ctx) {
        // Ed25519 signature verification
        boolean valid = CryptoLib.verifyEd25519Signature(
            datum.pubKey(), datum.message(), signature);

        return valid;
    }
}
```

### ECDSA and Schnorr (secp256k1)

```java
@SpendingValidator
class Secp256k1Example {
    record Datum(byte[] key, byte[] msgHash) {}

    @Entrypoint
    static boolean validate(Datum datum, byte[] sig, ScriptContext ctx) {
        // ECDSA secp256k1 (Ethereum-compatible)
        boolean ecdsaValid = CryptoLib.verifyEcdsaSecp256k1(
            datum.key(), datum.msgHash(), sig);

        // Schnorr secp256k1 (Bitcoin Taproot compatible)
        boolean schnorrValid = CryptoLib.verifySchnorrSecp256k1(
            datum.key(), datum.msgHash(), sig);

        return ecdsaValid || schnorrValid;
    }
}
```

---

## ByteStringLib -- ByteString Operations

**Import**: `com.bloxbean.cardano.julc.stdlib.lib.ByteStringLib`

ByteStringLib provides operations on `byte[]` (ByteString in Plutus). Includes slicing, comparison, and encoding/serialization.

### Slicing and Manipulation

```java
@SpendingValidator
class ByteStringExample {
    @Entrypoint
    static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
        byte[] data = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};

        // Length
        long len = ByteStringLib.length(data);           // 5

        // Element access
        long firstByte = ByteStringLib.at(data, 0);      // 1

        // Slicing
        byte[] firstTwo = ByteStringLib.take(data, 2);    // [0x01, 0x02]
        byte[] lastThree = ByteStringLib.drop(data, 2);   // [0x03, 0x04, 0x05]
        byte[] middle = ByteStringLib.slice(data, 1, 3);  // [0x02, 0x03, 0x04]

        // Construction
        byte[] withPrefix = ByteStringLib.cons(0xFF, data);
        byte[] combined = ByteStringLib.append(firstTwo, lastThree);
        byte[] emptyBs = ByteStringLib.empty();
        byte[] zeroes = ByteStringLib.zeros(32);

        return len == 5;
    }
}
```

### Comparison and Conversion

```java
@SpendingValidator
class ByteStringCompareExample {
    @Entrypoint
    static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
        byte[] a = new byte[]{0x01, 0x02};
        byte[] b = new byte[]{0x01, 0x03};

        // Comparison
        boolean eq = ByteStringLib.equals(a, b);
        boolean lt = ByteStringLib.lessThan(a, b);
        boolean lte = ByteStringLib.lessThanEquals(a, b);

        // Integer <-> ByteString conversion
        byte[] encoded = ByteStringLib.integerToByteString(true, 8, 256);
        long decoded = ByteStringLib.byteStringToInteger(true, encoded);

        // Data serialization (to CBOR)
        PlutusData someData = Builtins.iData(BigInteger.valueOf(42));
        byte[] cbor = ByteStringLib.serialiseData(someData);

        return lt;
    }
}
```

### UTF-8 Decimal Parsing

`ByteStringLib.utf8ToInteger()` parses a UTF-8-encoded decimal string into an integer. This is the inverse of `intToDecimalString()`.

```java
// Parse "42" bytes → integer 42
byte[] bs = "42".getBytes();
BigInteger n = ByteStringLib.utf8ToInteger(bs);  // 42

// Roundtrip property:
// ByteStringLib.utf8ToInteger(ByteStringLib.intToDecimalString(n)) == n
```

---

## BitwiseLib -- Bitwise Operations

**Import**: `com.bloxbean.cardano.julc.stdlib.lib.BitwiseLib`

BitwiseLib provides bit-level operations on `byte[]`. The `padding` parameter in AND/OR/XOR controls behavior when bytestrings have different lengths (`true` = zero-extend shorter, `false` = truncate longer).

### Bitwise Logical Operations

```java
@SpendingValidator
class BitwiseExample {
    @Entrypoint
    static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
        byte[] a = new byte[]{(byte) 0xFF, (byte) 0x0F};
        byte[] b = new byte[]{(byte) 0x0F, (byte) 0xF0};

        // Bitwise operations (padding=false truncates to shorter length)
        byte[] andResult = BitwiseLib.andByteString(false, a, b);   // [0x0F, 0x00]
        byte[] orResult = BitwiseLib.orByteString(false, a, b);     // [0xFF, 0xFF]
        byte[] xorResult = BitwiseLib.xorByteString(false, a, b);   // [0xF0, 0xFF]
        byte[] complement = BitwiseLib.complementByteString(a);      // [0x00, 0xF0]

        return true;
    }
}
```

### Bit Manipulation and Counting

```java
@SpendingValidator
class BitManipExample {
    @Entrypoint
    static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
        byte[] data = new byte[]{(byte) 0b10110100};

        // Read individual bits
        boolean bit0 = BitwiseLib.readBit(data, 0);

        // Count set bits (popcount)
        long popcount = BitwiseLib.countSetBits(data);   // 4

        // Find first set bit
        long firstSet = BitwiseLib.findFirstSetBit(data);

        // Shift and rotate
        byte[] shifted = BitwiseLib.shiftByteString(data, 2);
        byte[] rotated = BitwiseLib.rotateByteString(data, 2);

        return popcount > 0;
    }
}
```

---

## AddressLib -- Address Operations

**Import**: `com.bloxbean.cardano.julc.stdlib.lib.AddressLib`

AddressLib inspects Plutus `Address` types: extracting credential hashes and checking whether an address is a script or public key address.

### Address Inspection

```java
@SpendingValidator
class AddressExample {
    @Entrypoint
    static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        TxOut output = txInfo.outputs().head();
        Address addr = output.address();

        // Extract the payment credential hash (works for both PubKey and Script)
        byte[] credHash = AddressLib.credentialHash(addr);

        // Check address type
        boolean isScript = AddressLib.isScriptAddress(addr);
        boolean isPubKey = AddressLib.isPubKeyAddress(addr);

        // Extract the full credential (for pattern matching)
        Credential cred = AddressLib.paymentCredential(addr);

        return isPubKey;
    }
}
```

### Enforcing Output Destination

```java
@SpendingValidator
class DestinationCheckExample {
    record Datum(byte[] allowedRecipient) {}

    @Entrypoint
    static boolean validate(Datum datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        boolean allOutputsValid = true;

        for (TxOut out : txInfo.outputs()) {
            Address addr = out.address();
            if (AddressLib.isPubKeyAddress(addr)) {
                byte[] hash = AddressLib.credentialHash(addr);
                if (!Builtins.equalsByteString(hash, datum.allowedRecipient())) {
                    allOutputsValid = false;
                } else {
                    allOutputsValid = allOutputsValid;
                }
            } else {
                allOutputsValid = allOutputsValid;
            }
        }

        return allOutputsValid;
    }
}
```

---

## Important Notes and Caveats

### On-Chain vs Off-Chain

All stdlib libraries are annotated with `@OnchainLibrary` and compile to UPLC for on-chain execution. Some methods use casts like `(PlutusData)(Object)` that are no-ops on-chain but may throw `ClassCastException` off-chain. Methods that work off-chain are noted in the source Javadoc.

For off-chain testing, use UPLC evaluation via the JuLC testkit rather than calling library methods directly in JVM code.

### Loop Patterns

JuLC requires immutable variable semantics. In for-each and while loops used as accumulators, both branches of an `if` must assign the accumulator variable:

```java
// Correct: both branches assign result
for (TxOut out : outputs) {
    if (someCondition) {
        result = result.prepend(out);
    } else {
        result = result;           // identity assignment required
    }
}
```

### Cross-Library BytesData Parameter Bug

When calling stdlib methods that take `BytesData`/`MapData` typed parameters from user code, pass `PlutusData` (not the specific subtype) to avoid type confusion at the UPLC boundary. See the project MEMORY.md for details.

### Value.assetOf() Needs BData Arguments

When constructing policy IDs or token names to pass to `ValuesLib.assetOf()`, use the `byte[]` overload rather than manually wrapping with `Builtins.bData()`. The library handles wrapping internally:

```java
// Correct: pass byte[] directly
BigInteger amount = ValuesLib.assetOf(value, policyId, tokenName);

// The library internally wraps with bData for the UPLC comparison
```

### ownHash + containsPolicy Pattern

When checking if a minting policy's own token exists in a value (common in minting validators), use the `(byte[])(Object)` cast on `ContextsLib.ownHash()`:

```java
@MintingValidator
class MyTokenPolicy {
    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        byte[] ownPolicy = (byte[])(Object) ContextsLib.ownHash(ctx);

        // Check if minted value contains our policy
        boolean hasMint = ValuesLib.containsPolicy(txInfo.mint(), ownPolicy);

        // Or get a specific token amount
        BigInteger qty = ValuesLib.assetOf(txInfo.mint(), ownPolicy, "TOKEN".getBytes());

        return hasMint && qty.equals(BigInteger.ONE);
    }
}
```

The `(byte[])(Object)` cast is required because `ownHash()` returns a `ValidatorHash` (which is `ByteStringType` at UPLC level but a different Java type at source level). The cast is a no-op at UPLC level.

### MapLib.lookup Returns Optional Encoding

`MapLib.lookup()` returns an Optional encoded as Plutus Data:
- `Constr(0, [value])` for Some (found)
- `Constr(1, [])` for None (not found)

Check the tag with `Builtins.constrTag(result) == 0` to determine if the lookup succeeded:

```java
PlutusData.ConstrData result = MapLib.lookup(myMap, key);
if (Builtins.constrTag(result) == 0) {
    PlutusData value = Builtins.headList(Builtins.constrFields(result));
    // use value
}
```

### map() Returns JulcList<PlutusData>

The `map` HOF wraps each lambda result to Data, so the returned list always has
`PlutusData` elements regardless of input type. Use `Builtins.unIData()` or
`Builtins.unBData()` to extract typed values from mapped results.

### Typed Field Access vs Library Methods

For `ScriptContext` and `TxInfo`, prefer direct typed field access over the ContextsLib wrapper methods:

```java
// Preferred: direct access
TxInfo txInfo = ctx.txInfo();
JulcList<TxOut> outputs = txInfo.outputs();

// Legacy (still works)
TxInfo txInfo = ContextsLib.getTxInfo(ctx);
JulcList<TxOut> outputs = ContextsLib.txInfoOutputs(txInfo);
```

### Methods Also Available via Instance Methods

Many list and map operations are available as instance methods on `JulcList` and `JulcMap`, which can be more readable:

```java
JulcList<TxOut> outputs = txInfo.outputs();

// Library style
long count = ListsLib.length(outputs);
boolean empty = ListsLib.isEmpty(outputs);
TxOut first = ListsLib.head(outputs);

// Instance method style (equivalent)
long count = outputs.size();
boolean empty = outputs.isEmpty();
TxOut first = outputs.head();

// HOF methods are also available as instance calls
boolean anyLarge = outputs.any(out -> isLarge(out));
JulcList<TxOut> filtered = outputs.filter(out -> isLarge(out));
JulcList<PlutusData> mapped = outputs.map(out -> transform(out));
```
