# JuLC API Reference

This document covers all supported Java operations in the JuLC compiler, the standard library functions, and the typed ledger access API.

## Table of Contents

- [Supported Types](#supported-types) — primitives, collections, PlutusData
- [Operators](#operators) — arithmetic, comparison, logical
- [Control Flow](#control-flow) — if/else, switch, loops
- [Variable Declarations](#variable-declarations) — var, final
- [Records](#records) — data modeling
- [Helper Methods](#helper-methods) — static methods, recursion
- [Lambda Expressions](#lambda-expressions) — HOFs, map/filter/fold
- [Typed Ledger Access](#typed-ledger-access) — ScriptContext, TxInfo, Value
- [Instance Methods](#instance-methods) — list, map, value, pair methods
- [Standard Library Reference](#standard-library-reference) — all stdlib modules
- [Annotations Reference](#annotations-reference) — @Validator, @Param, @OnchainLibrary
- [Testing Utilities](#testing-utilities) — ValidatorTest, JulcEval, BudgetAssertions
- [Complete Example](#complete-example) — full validator walkthrough

---

## Supported Types

| Java Type | UPLC Representation | Notes |
|-----------|-------------------|-------|
| `int`, `long`, `BigInteger` | Integer | All mapped to arbitrary-precision integers |
| `boolean` | Bool (Constr 0/1) | `false` = Constr(0,[]), `true` = Constr(1,[]) |
| `byte[]` | ByteString | Raw bytes |
| `String` | String (UTF-8) | Converted via EncodeUtf8/DecodeUtf8 |
| `PlutusData` | Data | Opaque on-chain data |
| `List<T>`, `JulcList<T>` | BuiltinList | Builtin list of Data |
| `Map<K,V>`, `JulcMap<K,V>` | BuiltinList(Pair) | Map encoded as list of pairs |
| `Optional<T>` | Constr 0/1 | Some = Constr(0,[x]), None = Constr(1,[]) |
| `Tuple2<A,B>` | Constr(0, [a, b]) | Generic pair with auto-unwrap |
| `Tuple3<A,B,C>` | Constr(0, [a, b, c]) | Generic triple with auto-unwrap |
| Records | Constr(0, fields) | Each field is a Data element |
| Sealed interfaces | Constr(tag, fields) | Tag based on permit order |
| `@NewType` records | Underlying type | Zero-cost alias (identity on-chain) |
| `PubKeyHash`, `PolicyId`, `TokenName`, `TxId`, `ScriptHash`, `ValidatorHash`, `DatumHash` | ByteString | Ledger hash types |
| `Value` | Map(Pair) | Named RecordType with instance methods |

### JulcList and JulcMap

`JulcList<T>` and `JulcMap<K,V>` are interfaces in `julc-core/types/` that resolve to the same `ListType`/`MapType` as `List`/`Map`. They provide IDE autocomplete for on-chain methods (`.contains()`, `.size()`, `.get()`, etc.).

```java
JulcList<PubKeyHash> signers = txInfo.signatories();  // typed list of PubKeyHash
JulcMap<Credential, BigInteger> withdrawals = txInfo.withdrawals();
```

### Tuple2 and Tuple3

Generic tuples with auto-unwrapping field access based on type arguments.

```java
Tuple2<BigInteger, byte[]> result = MathLib.divMod(a, b);
BigInteger quotient = result.first();   // auto-generates UnIData
byte[] remainder = result.second();     // auto-generates UnBData

// Construction auto-wraps
var t = new Tuple2<BigInteger, BigInteger>(val1, val2);  // auto-wraps via IData
```

Raw `Tuple2` (no type args) defaults to `DataType` for backward compatibility. Tuple2/Tuple3 are **not switchable** (registered as RecordType, but switch requires SumType). Use field access instead.

### @NewType

Zero-cost type aliases for single-field records with a supported underlying type (`byte[]`, `BigInteger`, `String`, `boolean`).

```java
@NewType
public record AssetClass(byte[] policyId) {}

// On-chain: identity (no ConstrData wrap)
// AssetClass.of(bytes) is auto-registered
```

### Type.of() Factory Methods

Seven ledger hash types have `.of(byte[])` factory methods:

| Type | Usage | On-chain |
|------|-------|----------|
| `PubKeyHash.of(bytes)` | Create from raw bytes | Identity |
| `ScriptHash.of(bytes)` | | Identity |
| `ValidatorHash.of(bytes)` | | Identity |
| `PolicyId.of(bytes)` | | Identity |
| `TokenName.of(bytes)` | | Identity |
| `DatumHash.of(bytes)` | | Identity |
| `TxId.of(bytes)` | | Identity |

These replace the ugly `(PubKeyHash)(Object) bytes` casts in user code. Note: `(PubKeyHash)(Object) plutusData` is still needed when the argument is `PlutusData` (not `byte[]`).

### Not Supported

`float`, `double`, `char`, arrays (`T[]`), `null`, collections other than `List`/`Map`/`Optional`, generic classes, inheritance (use sealed interfaces instead).

## Operators

### Arithmetic

| Operator | Java Example | UPLC Builtin | Notes |
|----------|-------------|-------------|-------|
| `+` | `a + b` | `AddInteger` | For `BigInteger`/`int`/`long` operands |
| `+` | `s1 + s2` | `AppendString` | For `String` operands |
| `+` | `b1 + b2` | `AppendByteString` | For `byte[]` operands |
| `-` | `a - b` | `SubtractInteger` | |
| `*` | `a * b` | `MultiplyInteger` | |
| `/` | `a / b` | `DivideInteger` | |
| `%` | `a % b` | `RemainderInteger` | |

The `+` operator is type-aware: the compiler infers the operand type and dispatches to the correct builtin.

### Comparison

| Operator | Java Example | UPLC Builtin | Notes |
|----------|-------------|-------------|-------|
| `==` | `a == b` | `EqualsInteger` | For `BigInteger`/`int`/`long` (default) |
| `==` | `s1 == s2` | `EqualsString` | For `String` operands |
| `==` | `b1 == b2` | `EqualsByteString` | For `byte[]` operands |
| `==` | `d1 == d2` | `EqualsData` | For `PlutusData`, records, sealed interfaces |
| `==` | `x == y` | `IfThenElse` | For `boolean` operands |
| `!=` | `a != b` | negated equality | Same type dispatch as `==` |
| `<` | `a < b` | `LessThanInteger` | |
| `<=` | `a <= b` | `LessThanEqualsInteger` | |
| `>` | `a > b` | `LessThanInteger` (swapped) | |
| `>=` | `a >= b` | `LessThanEqualsInteger` (swapped) | |

### Boolean

| Operator | Java Example | UPLC Translation |
|----------|-------------|-----------------|
| `&&` | `a && b` | `IfThenElse(a, b, false)` (short-circuit) |
| `\|\|` | `a \|\| b` | `IfThenElse(a, true, b)` (short-circuit) |
| `!` | `!a` | `IfThenElse(a, false, true)` |

## Control Flow

### If/Else
```java
if (condition) {
    return x;
} else {
    return y;
}
```

### Ternary
```java
return condition ? x : y;
```

### Switch Pattern Matching (Sealed Interfaces)
```java
sealed interface Action permits Deposit, Withdraw {}
record Deposit(BigInteger amount) implements Action {}
record Withdraw(BigInteger amount) implements Action {}

// In validator:
return switch (action) {
    case Deposit d -> d.amount() > 0;
    case Withdraw w -> w.amount() > 0 && hasSigner;
};
```

Switch expressions check exhaustiveness at compile time. All variants of the sealed interface must be covered. The `default ->` branch can be used as a catch-all for uncovered variants, but prefer explicit cases for clarity.

### instanceof Pattern Matching
```java
if (action instanceof Deposit d) {
    return d.amount() > 0;
}
```

### For-Each Loop
```java
boolean found = false;
for (var item : list) {
    if (item == target) {
        found = true;
    }
}
```
Desugared to a recursive fold. Supports single/multi-accumulator, break, and nesting. See [For-Loop Patterns](for-loop-patterns.md).

### While Loop
```java
BigInteger sum = 0;
BigInteger i = 0;
while (i < 10) {
    sum = sum + i;
    i = i + 1;
}
```
Desugared to tail-recursive call via Z-combinator.

### Not Supported

C-style `for(;;)`, `do-while`, `try-catch-finally`, `throw`, `continue`.

## Variable Declarations

All variables are **immutable** (functional semantics). Reassignment is only supported inside for-each and while loop bodies (accumulator pattern).

```java
// OK
BigInteger x = 42;
var y = x + 1;

// NOT OK - assignment not supported
x = x + 1;
```

The `var` keyword is supported and types are inferred from the initializer.

## Records

Records compile to Constr-encoded Data with fields in declaration order.

```java
record VestingDatum(byte[] beneficiary, BigInteger deadline) {}

// Construction
var datum = new VestingDatum(pkh, 1000);

// Field access
byte[] b = datum.beneficiary();
BigInteger d = datum.deadline();
```

## Helper Methods

Static methods in the validator class (without `@Entrypoint`) are compiled as helper functions. Recursive helper methods use Z-combinator transformation.

```java
@SpendingValidator
class MyValidator {
    static boolean isPositive(BigInteger x) {
        return x > 0;
    }

    @Entrypoint
    static boolean validate(BigInteger redeemer, PlutusData ctx) {
        return isPositive(redeemer);
    }
}
```

## Lambda Expressions

```java
ListsLib.any(list, x -> x > 0)
ListsLib.filter(list, x -> Builtins.unIData(x) > 100)
ListsLib.foldl((acc, x) -> acc + Builtins.unIData(x), BigInteger.ZERO, list)
```

Lambda bodies can be a single expression or a block:

```java
ListsLib.filter(items, item -> {
    var threshold = new BigInteger("100");
    return Builtins.unIData(item) > threshold;
});
```

Equivalent instance method syntax (lambda types auto-inferred):

```java
list.any(x -> x.compareTo(BigInteger.ZERO) > 0)
list.filter(x -> Builtins.unIData(x).compareTo(BigInteger.valueOf(100)) > 0)
// foldl is only available as ListsLib.foldl (no instance method)
```

## Typed Ledger Access

When using `ScriptContext` as a parameter type, the compiler provides typed access to all ledger fields without manual Data decoding.

### ScriptContext

```java
@Entrypoint
static boolean validate(MyDatum datum, PlutusData redeemer, ScriptContext ctx) {
    TxInfo txInfo = ctx.txInfo();
    PlutusData redeemer2 = ctx.redeemer();
    ScriptInfo scriptInfo = ctx.scriptInfo();
    // ...
}
```

### TxInfo Fields

| Method | Return Type | Field Index | Description |
|--------|------------|-------------|-------------|
| `txInfo.inputs()` | `List<TxInInfo>` | 0 | Transaction inputs |
| `txInfo.referenceInputs()` | `List<TxInInfo>` | 1 | Reference inputs |
| `txInfo.outputs()` | `List<TxOut>` | 2 | Transaction outputs |
| `txInfo.fee()` | `BigInteger` | 3 | Transaction fee (lovelace) |
| `txInfo.mint()` | `Value` | 4 | Minted/burned value |
| `txInfo.certificates()` | `JulcList<TxCert>` | 5 | Certificates |
| `txInfo.withdrawals()` | `JulcMap<Credential, BigInteger>` | 6 | Reward withdrawals |
| `txInfo.validRange()` | `Interval` | 7 | Validity time range |
| `txInfo.signatories()` | `JulcList<PubKeyHash>` | 8 | Required signers |
| `txInfo.redeemers()` | `JulcMap<ScriptPurpose, PlutusData>` | 9 | All redeemers |
| `txInfo.datums()` | `JulcMap<DatumHash, PlutusData>` | 10 | Datum hash map |
| `txInfo.txId()` | `TxId` | 11 | Transaction hash |
| `txInfo.votes()` | `JulcMap<Voter, JulcMap<GovernanceActionId, Vote>>` | 12 | Governance votes |
| `txInfo.proposalProcedures()` | `JulcList<ProposalProcedure>` | 13 | Proposal procedures |
| `txInfo.currentTreasuryAmount()` | `Optional<BigInteger>` | 14 | Current treasury |
| `txInfo.treasuryDonation()` | `Optional<BigInteger>` | 15 | Treasury donation |

### Other Ledger Types

- **TxInInfo**: `outRef()` (TxOutRef), `resolved()` (TxOut)
- **TxOut**: `address()` (Address), `value()` (Value), `datum()` (OutputDatum), `referenceScript()` (Optional)
- **TxOutRef**: `txId()` (byte[]), `outputIndex()` (BigInteger)
- **Address**: `credential()` (Credential), `stakingCredential()` (Optional)
- **Credential**: sealed interface with `PubKeyCredential(byte[] hash)` and `ScriptCredential(byte[] hash)`
- **OutputDatum**: sealed interface with `NoOutputDatum`, `OutputDatumHash(byte[])`, `OutputDatum(PlutusData)`
- **ScriptInfo**: sealed interface with `MintingScript(byte[])`, `SpendingScript(TxOutRef, Optional)`, `RewardingScript(Credential)`, `CertifyingScript(BigInteger, TxCert)`, `VotingScript(Voter)`, `ProposingScript(BigInteger, ProposalProcedure)`
- **Interval**: `from()` (IntervalBound), `to()` (IntervalBound)
- **IntervalBound**: `boundType()` (IntervalBoundType), `isInclusive()` (boolean)
- **IntervalBoundType**: sealed with `NegInf`, `Finite(BigInteger)`, `PosInf`

### Chained Calls

```java
// Full typed chain from context to list methods
boolean hasSigner = ctx.txInfo().signatories().contains(datum.beneficiary());
```

## Instance Methods

### BigInteger Instance Methods

| Method | Return Type | UPLC Translation |
|--------|------------|-----------------|
| `.abs()` | `BigInteger` | `IfThenElse(x < 0, 0 - x, x)` |
| `.negate()` | `BigInteger` | `SubtractInteger(0, x)` |
| `.max(other)` | `BigInteger` | `IfThenElse(a < b, b, a)` |
| `.min(other)` | `BigInteger` | `IfThenElse(a <= b, a, b)` |
| `.equals(other)` | `boolean` | `EqualsInteger` |
| `.add(other)` | `BigInteger` | `AddInteger` |
| `.subtract(other)` | `BigInteger` | `SubtractInteger` |
| `.multiply(other)` | `BigInteger` | `MultiplyInteger` |
| `.divide(other)` | `BigInteger` | `DivideInteger` |
| `.remainder(other)` | `BigInteger` | `RemainderInteger` |
| `.mod(other)` | `BigInteger` | `ModInteger` |
| `.signum()` | `BigInteger` | `IfThenElse` chain |
| `.compareTo(other)` | `BigInteger` | `IfThenElse` chain |
| `.intValue()` | `int` | Identity |
| `.longValue()` | `long` | Identity |

### List Instance Methods

| Method | Return Type | Description |
|--------|------------|-------------|
| `.isEmpty()` | `boolean` | Returns true if empty |
| `.size()` | `BigInteger` | Returns the number of elements |
| `.head()` | `T` | Returns the first element (decoded) |
| `.tail()` | `List<T>` | Returns the list without the first element |
| `.get(index)` | `T` | Returns element at index (decoded) |
| `.contains(target)` | `boolean` | Recursive search with type-aware equality |
| `.reverse()` | `List<T>` | Returns a reversed copy |
| `.concat(other)` | `List<T>` | Concatenates two lists |
| `.take(n)` | `List<T>` | Returns first n elements |
| `.drop(n)` | `List<T>` | Returns list after dropping first n elements |
| `.prepend(elem)` | `List<T>` | Prepends element with auto-wrap (BigInteger->IData, byte[]->BData, etc.) |
| `.map(f)` | `JulcList<PlutusData>` | Apply function to each element (wraps results to Data) |
| `.filter(pred)` | `JulcList<T>` | Keep elements matching predicate |
| `.any(pred)` | `boolean` | True if any element matches |
| `.all(pred)` | `boolean` | True if all elements match |
| `.find(pred)` | `T` | First matching element (error if none) |

Chaining is supported: `sigs.tail().isEmpty()`, `sigs.tail().contains(pkh)`.

`foldl` is only available as a static call (`ListsLib.foldl`), not as an instance method.

### Map Instance Methods

| Method | Return Type | Description |
|--------|------------|-------------|
| `.get(key)` | `Optional<V>` | Lookup value by key |
| `.containsKey(key)` | `boolean` | Check if key exists |
| `.size()` | `BigInteger` | Number of entries |
| `.isEmpty()` | `boolean` | True if no entries |
| `.keys()` | `List<K>` | All keys as list |
| `.values()` | `List<V>` | All values as list |
| `.insert(key, value)` | `Map<K,V>` | Insert/update entry (returns pair list) |
| `.delete(key)` | `Map<K,V>` | Remove entry (returns pair list) |

MapType variables always hold pair lists internally. `insert` and `delete` return pair lists (not MapData-wrapped).

### Value Instance Methods

| Method | Return Type | Description |
|--------|------------|-------------|
| `.lovelaceOf()` | `BigInteger` | Extract ADA amount |
| `.isEmpty()` | `boolean` | True if value is empty |
| `.assetOf(policy, token)` | `BigInteger` | Extract specific token amount |

**Caveat**: `value.assetOf(policyId, tokenName)` uses `EqualsData` internally. If `policyId`/`tokenName` are `byte[]` (ByteStringType), wrap with `Builtins.bData()` before passing.

### Pair Instance Methods

| Method | Return Type | Description |
|--------|------------|-------------|
| `.key()` | `K` | First element with auto-decode |
| `.value()` | `V` | Second element with auto-decode |

### String Instance Methods

| Method | Return Type | UPLC Translation |
|--------|------------|-----------------|
| `.equals(other)` | `boolean` | `EqualsString` |
| `.length()` | `BigInteger` | `LengthOfByteString(EncodeUtf8(s))` |

### ByteString (byte[]) Instance Methods

| Method | Return Type | UPLC Translation |
|--------|------------|-----------------|
| `.equals(other)` | `boolean` | `EqualsByteString` |
| `.length()` | `BigInteger` | `LengthOfByteString` |
| `.length` | `BigInteger` | `LengthOfByteString` (field access form) |
| `.hash()` | `byte[]` | `UnBData` (for list iteration context) |

### Optional Instance Methods

| Method | Return Type | Description |
|--------|------------|-------------|
| `.isPresent()` | `boolean` | True if Some (tag == 0) |
| `.isEmpty()` | `boolean` | True if None (tag == 1) |
| `.get()` | `T` | Unwrap the inner value (decoded) |

### PlutusData Equality

Raw `PlutusData` variables support `.equals()` and `==`/`!=` operators using `EqualsData`.

## Standard Library Reference

Import from `com.bloxbean.cardano.julc.stdlib.lib.*` in validators. See [Standard Library Guide](stdlib-guide.md) for comprehensive documentation.

### ContextsLib

| Method | Args | Description |
|--------|------|-------------|
| `signedBy(txInfo, pkh)` | TxInfo, ByteString | Check if pkh is in signatories |
| `getTxInfo(ctx)` | ScriptContext | Extract TxInfo (legacy — prefer `ctx.txInfo()`) |
| `getRedeemer(ctx)` | ScriptContext | Extract redeemer |
| `getSpendingDatum(ctx)` | ScriptContext | Extract optional spending datum |
| `txInfoInputs(txInfo)` | TxInfo | Get inputs list |
| `txInfoOutputs(txInfo)` | TxInfo | Get outputs list |
| `txInfoSignatories(txInfo)` | TxInfo | Get signatories list |
| `txInfoValidRange(txInfo)` | TxInfo | Get validity time range |
| `txInfoMint(txInfo)` | TxInfo | Get minted/burned value |
| `txInfoFee(txInfo)` | TxInfo | Get transaction fee |
| `txInfoId(txInfo)` | TxInfo | Get transaction hash |
| `txInfoRefInputs(txInfo)` | TxInfo | Get reference inputs |
| `txInfoWithdrawals(txInfo)` | TxInfo | Get withdrawals map |
| `txInfoRedeemers(txInfo)` | TxInfo | Get redeemers map |
| `findOwnInput(ctx)` | ScriptContext | Find the input being validated → `Optional<TxInInfo>` |
| `getContinuingOutputs(ctx)` | ScriptContext | Get outputs to same script → `JulcList<TxOut>` |
| `ownInputScriptHash(ctx)` | ScriptContext | Get script hash of own input → `byte[]` |
| `findDatum(txInfo, datumHash)` | TxInfo, ByteString | Lookup datum by hash |
| `valueSpent(txInfo)` | TxInfo | Total value of all inputs |
| `valuePaid(txInfo, address)` | TxInfo, Address | Value paid to an address |
| `ownHash(scriptInfo)` | ScriptInfo | Get own script hash |
| `scriptOutputsAt(txInfo, hash)` | TxInfo, ByteString | Outputs at script hash |
| `listIndex(list, index)` | List, Integer | Get element at index |
| `trace(msg)` | String | Emit trace message |

### ListsLib

| Method | Args | Description |
|--------|------|-------------|
| `isEmpty(list)` | List | Check if list is empty |
| `length(list)` | List | Count elements |
| `head(list)` | List | First element |
| `tail(list)` | List | Rest of list |
| `reverse(list)` | List | Reverse a list |
| `concat(a, b)` | List, List | Concatenate two lists |
| `nth(list, n)` | List, Integer | Get element at index |
| `take(list, n)` | List, Integer | First n elements |
| `drop(list, n)` | List, Integer | Drop first n elements |
| `contains(list, elem)` | List, Data | Check membership (EqualsData) |
| `containsInt(list, n)` | List, Integer | Check integer membership |
| `containsBytes(list, bs)` | List, ByteString | Check bytestring membership |
| `hasDuplicateInts(list)` | List | Check for duplicate integers |
| `hasDuplicateBytes(list)` | List | Check for duplicate bytestrings |
| `empty()` | (none) | Create empty list |
| `prepend(elem, list)` | Data, List | Prepend element to list |
| `any(list, pred)` | List, Lambda | True if any element matches |
| `all(list, pred)` | List, Lambda | True if all elements match |
| `find(list, pred)` | List, Lambda | Find first matching (Optional) |
| `foldl(f, init, list)` | Lambda, init, List | Left fold |
| `map(list, f)` | List, Lambda | Transform elements |
| `filter(list, pred)` | List, Lambda | Keep matching elements |
| `zip(a, b)` | List, List | Pair elements from two lists |

### ValuesLib

| Method | Args | Description |
|--------|------|-------------|
| `geqMultiAsset(a, b)` | Value, Value | Multi-asset >= comparison |
| `leq(a, b)` | Value, Value | Multi-asset <= comparison |
| `eq(a, b)` | Value, Value | Multi-asset equality |
| `isZero(value)` | Value | Check if value is zero |
| `singleton(policy, token, amount)` | BS, BS, Integer | Create single-token value |
| `negate(value)` | Value | Negate all amounts |
| `flatten(value)` | Value | Flatten to list of triples |
| `flattenTyped(value)` | Value | Flatten to typed `JulcList<AssetEntry>` |
| `add(a, b)` | Value, Value | Add two values |
| `subtract(a, b)` | Value, Value | Subtract values |
| `countTokensWithQty(mint, policy, qty)` | Value, BS, Integer | Count tokens with exact quantity under policy |
| `findTokenName(mint, policy, qty)` | Value, BS, Integer | Find token name with exact quantity under policy |

### MapLib

| Method | Args | Description |
|--------|------|-------------|
| `lookup(map, key)` | Map, Data | Lookup value by key |
| `member(map, key)` | Map, Data | Check key membership |
| `insert(map, key, value)` | Map, Data, Data | Insert/update entry |
| `delete(map, key)` | Map, Data | Remove entry by key |
| `keys(map)` | Map | Get all keys |
| `values(map)` | Map | Get all values |
| `toList(map)` | Map | Convert to pair list |
| `fromList(list)` | List | Convert pair list to map |
| `size(map)` | Map | Count entries |

### OutputLib

| Method | Args | Description |
|--------|------|-------------|
| `txOutAddress(txOut)` | TxOut | Get output address |
| `txOutValue(txOut)` | TxOut | Get output value |
| `txOutDatum(txOut)` | TxOut | Get output datum |
| `outputsAt(outputs, address)` | List, Address | Filter outputs by address |
| `countOutputsAt(outputs, addr)` | List, Address | Count outputs at address |
| `uniqueOutputAt(outputs, addr)` | List, Address | Get exactly one output at address |
| `outputsWithToken(outs, pol, tn)` | List, BS, BS | Filter by token |
| `valueHasToken(val, pol, tn)` | Value, BS, BS | Check if value has token |
| `lovelacePaidTo(outputs, addr)` | List, Address | Total lovelace to address |
| `paidAtLeast(outs, addr, min)` | List, Address, Integer | Check minimum payment |
| `getInlineDatum(txOut)` | TxOut | Get inline datum |
| `resolveDatum(txOut, datumsMap)` | TxOut, Map | Resolve datum (inline or by hash) |
| `findOutputWithToken(outputs, scriptHash, policy, token)` | List, BS, BS, BS | Find output at script address with specific token |
| `findInputWithToken(inputs, scriptHash, policy, token)` | List, BS, BS, BS | Find input at script address with specific token |

### MathLib

| Method | Args | Description |
|--------|------|-------------|
| `abs(x)` | Integer | Absolute value |
| `max(a, b)` | Integer, Integer | Maximum |
| `min(a, b)` | Integer, Integer | Minimum |
| `pow(base, exp)` | Integer, Integer | Exponentiation |
| `sign(x)` | Integer | Sign (-1, 0, or 1) |
| `divMod(a, b)` | Integer, Integer | Returns Tuple2(quotient, remainder) |
| `quotRem(a, b)` | Integer, Integer | Returns Tuple2(quotient, remainder) |
| `expMod(base, exp, mod)` | Integer, Integer, Integer | Modular exponentiation |

### IntervalLib

| Method | Args | Description |
|--------|------|-------------|
| `between(interval, lower, upper)` | Interval, Integer, Integer | Check if interval is within bounds |
| `never()` | (none) | Empty interval |
| `isEmpty(interval)` | Interval | Check if interval is empty |
| `finiteUpperBound(interval)` | Interval | Extract upper bound (if finite) |
| `finiteLowerBound(interval)` | Interval | Extract lower bound (if finite) |

### CryptoLib

| Method | Args | Description |
|--------|------|-------------|
| `verifyEcdsaSecp256k1(key, msg, sig)` | BS, BS, BS | Verify ECDSA secp256k1 signature |
| `verifySchnorrSecp256k1(key, msg, sig)` | BS, BS, BS | Verify Schnorr secp256k1 signature |
| `ripemd_160(bs)` | BS | RIPEMD-160 hash |

Note: `sha2_256`, `sha3_256`, `blake2b_256`, `blake2b_224`, `keccak_256`, and `verifyEd25519Signature` are available directly via `Builtins.*`.

### ByteStringLib

| Method | Args | Description |
|--------|------|-------------|
| `take(bs, n)` | BS, Integer | Take first n bytes |
| `lessThan(a, b)` | BS, BS | Lexicographic less-than |
| `lessThanEquals(a, b)` | BS, BS | Lexicographic less-than-or-equal |
| `integerToByteString(be, w, i)` | Bool, Integer, Integer | Integer to byte string |
| `byteStringToInteger(be, bs)` | Bool, BS | Byte string to integer |
| `at(bs, index)` | BS, Integer | Get byte at index |
| `cons(byte_, bs)` | Integer, BS | Prepend a byte |
| `slice(bs, start, length)` | BS, Integer, Integer | Extract a slice |
| `length(bs)` | BS | Length of bytestring |
| `drop(bs, n)` | BS, Integer | Drop first n bytes |
| `append(a, b)` | BS, BS | Concatenate two bytestrings |
| `empty()` | (none) | Empty bytestring |
| `zeros(n)` | Integer | Bytestring of n zero bytes |
| `equals(a, b)` | BS, BS | Equality check |
| `encodeUtf8(s)` | String | Encode string to UTF-8 bytes |
| `decodeUtf8(bs)` | BS | Decode UTF-8 bytes to string |
| `serialiseData(d)` | Data | Serialize data to CBOR bytes |
| `hexNibble(n)` | Integer | Convert nibble (0-15) to hex ASCII code |
| `toHex(bs)` | BS | Convert bytestring to hex-encoded bytestring |
| `intToDecimalString(n)` | Integer | Convert integer to decimal digit bytestring |
| `utf8ToInteger(bs)` | BS | Parse UTF-8 decimal string to integer |

### BitwiseLib

| Method | Args | Description |
|--------|------|-------------|
| `andByteString(pad, a, b)` | Bool, BS, BS | Bitwise AND |
| `orByteString(pad, a, b)` | Bool, BS, BS | Bitwise OR |
| `xorByteString(pad, a, b)` | Bool, BS, BS | Bitwise XOR |
| `complementByteString(bs)` | BS | Bitwise complement |
| `readBit(bs, index)` | BS, Integer | Read single bit |
| `writeBits(bs, indices, val)` | BS, List, Bool | Write bits at indices |
| `shiftByteString(bs, n)` | BS, Integer | Shift by n bits |
| `rotateByteString(bs, n)` | BS, Integer | Rotate by n bits |
| `countSetBits(bs)` | BS | Count set (1) bits |
| `findFirstSetBit(bs)` | BS | Index of first set bit |

### AddressLib

| Method | Args | Description |
|--------|------|-------------|
| `credentialHash(cred)` | Credential | Extract hash from credential |
| `isScriptAddress(addr)` | Address | True if script address |
| `isPubKeyAddress(addr)` | Address | True if pub key address |
| `paymentCredential(addr)` | Address | Extract payment credential |

## Annotations Reference

### Validator Annotations

| Annotation | Target | Description |
|-----------|--------|-------------|
| `@SpendingValidator` | Class | Single-purpose spending validator |
| `@MintingValidator` | Class | Single-purpose minting validator |
| `@WithdrawValidator` | Class | Single-purpose withdrawal validator |
| `@CertifyingValidator` | Class | Single-purpose certifying validator |
| `@VotingValidator` | Class | Single-purpose voting validator |
| `@ProposingValidator` | Class | Single-purpose proposing validator |
| `@MultiValidator` | Class | Multi-purpose validator (handles multiple script purposes) |
| `@Entrypoint` | Method | Marks the validator entrypoint method |
| `@Entrypoint(purpose = Purpose.MINT)` | Method | Purpose-specific entrypoint for multi-validators |
| `@Param` | Field | Parameterized field applied at deployment |
| `@OnchainLibrary` | Class | Reusable library class (auto-discovered from classpath) |
| `@NewType` | Class | Zero-cost type alias for single-field records |

### Purpose Enum

The `Purpose` enum controls dispatch in `@MultiValidator` classes:

| Value | ScriptInfo Tag | Description |
|-------|---------------|-------------|
| `Purpose.DEFAULT` | — | Manual dispatch (user switches on ScriptInfo) |
| `Purpose.MINT` | 0 | MintingScript |
| `Purpose.SPEND` | 1 | SpendingScript |
| `Purpose.WITHDRAW` | 2 | RewardingScript |
| `Purpose.CERTIFY` | 3 | CertifyingScript |
| `Purpose.VOTE` | 4 | VotingScript |
| `Purpose.PROPOSE` | 5 | ProposingScript |

## Testing Utilities

### JulcEval

Type-safe evaluator for testing individual on-chain methods without a full ScriptContext.

**Factory methods:**

| Method | Description |
|--------|-------------|
| `JulcEval.forClass(Class<?>)` | Load source from `src/main/java` |
| `JulcEval.forClass(Class<?>, Path)` | Load source from custom root |
| `JulcEval.forSource(String)` | Use inline Java source |

**Proxy mode:**

```java
var proxy = JulcEval.forClass(MyHelper.class).create(MyInterface.class);
```

**Fluent call mode:**

```java
var result = JulcEval.forClass(MyHelper.class).call("methodName", arg1, arg2);
```

**CallResult extraction:**

| Method | Return Type |
|--------|------------|
| `.asInteger()` | `BigInteger` |
| `.asLong()` | `long` |
| `.asInt()` | `int` |
| `.asByteString()` | `byte[]` |
| `.asBoolean()` | `boolean` |
| `.asString()` | `String` |
| `.asData()` | `PlutusData` |
| `.asOptional()` | `Optional<PlutusData>` |
| `.asList()` | `List<PlutusData>` |
| `.as(Class<T>)` | `T` |
| `.auto()` | `Object` |
| `.rawTerm()` | `Term` |

**Supported argument types:** `BigInteger`, `int`, `long`, `boolean`, `byte[]`, `String`, `PlutusData`, `PlutusDataConvertible`

## Complete Example

```java
import com.bloxbean.cardano.julc.onchain.annotation.*;
import com.bloxbean.cardano.julc.onchain.ledger.*;
import com.bloxbean.cardano.julc.core.PlutusData;
import java.math.BigInteger;

@SpendingValidator
class VestingValidator {
    record VestingDatum(byte[] beneficiary, BigInteger deadline) {}

    @Entrypoint
    static boolean validate(VestingDatum datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        boolean hasSigner = txInfo.signatories().contains(datum.beneficiary());
        boolean pastDeadline = datum.deadline() > 0;
        return hasSigner && pastDeadline;
    }
}
```
