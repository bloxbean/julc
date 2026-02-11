# Plutus-Java API Reference

This document covers all supported Java operations in the Plutus-Java compiler, the standard library functions, and the typed ledger access API.

## Supported Types

| Java Type | UPLC Representation | Notes |
|-----------|-------------------|-------|
| `int`, `long`, `BigInteger` | Integer | All mapped to arbitrary-precision integers |
| `boolean` | Bool (Constr 0/1) | `false` = Constr(0,[]), `true` = Constr(1,[]) |
| `byte[]` | ByteString | Raw bytes |
| `String` | String (UTF-8) | Converted via EncodeUtf8/DecodeUtf8 |
| `PlutusData` | Data | Opaque on-chain data |
| `List<T>` | BuiltinList | Builtin list of Data |
| `Map<K,V>` | BuiltinList(Pair) | Map encoded as list of pairs |
| `Optional<T>` | Constr 0/1 | Some = Constr(0,[x]), None = Constr(1,[]) |
| Records | Constr(0, fields) | Each field is a Data element |
| Sealed interfaces | Constr(tag, fields) | Tag based on permit order |

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

The `==` and `!=` operators are type-aware: the compiler infers the operand type from the expression and dispatches to the correct equality builtin.

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
Desugared to a recursive fold over the list.

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

C-style `for(;;)`, `do-while`, `try-catch-finally`, `throw`, `break`, `continue`.

## Variable Declarations

All variables are **immutable** (functional semantics). Once declared, a variable cannot be reassigned.

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

Static methods in the validator class (without `@Entrypoint`) are compiled as helper functions.

```java
@Validator
class MyValidator {
    static boolean isPositive(BigInteger x) {
        return x > 0;
    }

    @Entrypoint
    static boolean validate(BigInteger redeemer, BigInteger ctx) {
        return isPositive(redeemer);
    }
}
```

Recursive helper methods are supported via Z-combinator transformation.

## Lambda Expressions

```java
ListsLib.any(list, x -> x > 0)
```

Lambda bodies compile to UPLC lambda abstractions.

## Typed Ledger Access (Milestone 6)

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
| `txInfo.certificates()` | `List<PlutusData>` | 5 | Certificates |
| `txInfo.withdrawals()` | `Map<PlutusData,BigInteger>` | 6 | Reward withdrawals |
| `txInfo.validRange()` | `Interval` | 7 | Validity time range |
| `txInfo.signatories()` | `List<byte[]>` | 8 | Required signers |
| `txInfo.redeemers()` | `Map<PlutusData,PlutusData>` | 9 | All redeemers |
| `txInfo.datums()` | `Map<byte[],PlutusData>` | 10 | Datum hash map |
| `txInfo.txId()` | `byte[]` | 11 | Transaction hash |
| `txInfo.votes()` | `Map<PlutusData,PlutusData>` | 12 | Governance votes |
| `txInfo.proposalProcedures()` | `List<PlutusData>` | 13 | Proposal procedures |
| `txInfo.treasury()` | `Optional<BigInteger>` | 14 | Current treasury |
| `txInfo.donation()` | `Optional<BigInteger>` | 15 | Current donation |

### Chained Calls

```java
// Full typed chain from context to list methods
boolean hasSigner = ctx.txInfo().signatories().contains(datum.beneficiary());
```

### List Instance Methods

When the compiler knows a variable is a `List<T>`, these methods are available:

| Method | Return Type | UPLC Builtin | Description |
|--------|------------|-------------|-------------|
| `.isEmpty()` | `boolean` | `NullList` | Returns true if empty |
| `.size()` | `BigInteger` | foldl | Returns the number of elements |
| `.head()` | `T` | `HeadList` + decode | Returns the first element (decoded) |
| `.tail()` | `List<T>` | `TailList` | Returns the list without the first element |
| `.contains(target)` | `boolean` | recursive search | Returns true if target is in the list |

The element type is tracked for `.contains()` to generate the correct equality comparison (EqualsByteString for `byte[]`, EqualsInteger for integers, EqualsData for general Data).

Chaining is supported: `sigs.tail().isEmpty()`, `sigs.tail().contains(pkh)`.

### BigInteger Instance Methods

| Method | Return Type | UPLC Translation | Description |
|--------|------------|-----------------|-------------|
| `.abs()` | `BigInteger` | `IfThenElse(x < 0, 0 - x, x)` | Absolute value |
| `.negate()` | `BigInteger` | `SubtractInteger(0, x)` | Negation |
| `.max(other)` | `BigInteger` | `IfThenElse(a < b, b, a)` | Maximum of two values |
| `.min(other)` | `BigInteger` | `IfThenElse(a <= b, a, b)` | Minimum of two values |
| `.equals(other)` | `boolean` | `EqualsInteger` | Equality check |
| `.add(other)` | `BigInteger` | `AddInteger` | Addition |
| `.subtract(other)` | `BigInteger` | `SubtractInteger` | Subtraction |
| `.multiply(other)` | `BigInteger` | `MultiplyInteger` | Multiplication |
| `.divide(other)` | `BigInteger` | `DivideInteger` | Integer division |
| `.remainder(other)` | `BigInteger` | `RemainderInteger` | Remainder after division |
| `.mod(other)` | `BigInteger` | `ModInteger` | Modulus (always non-negative) |
| `.signum()` | `BigInteger` | `IfThenElse` chain | Returns -1, 0, or 1 |
| `.compareTo(other)` | `BigInteger` | `IfThenElse` chain | Returns -1, 0, or 1 |

```java
BigInteger x = BigInteger.valueOf(-5);
BigInteger absX = x.abs();          // 5
BigInteger neg = x.negate();        // 5
BigInteger m = x.max(BigInteger.ZERO); // 0
BigInteger n = x.min(BigInteger.ZERO); // -5
BigInteger sum = x.add(BigInteger.TEN);       // 5
BigInteger diff = x.subtract(BigInteger.ONE);  // -6
BigInteger prod = x.multiply(BigInteger.TWO);  // -10
BigInteger quot = BigInteger.valueOf(17).divide(BigInteger.valueOf(5));    // 3
BigInteger rem = BigInteger.valueOf(17).remainder(BigInteger.valueOf(5));  // 2
BigInteger modVal = BigInteger.valueOf(17).mod(BigInteger.valueOf(5));     // 2
BigInteger sign = x.signum();       // -1
BigInteger cmp = x.compareTo(BigInteger.ZERO); // -1
```

### String Instance Methods

| Method | Return Type | UPLC Translation | Description |
|--------|------------|-----------------|-------------|
| `.equals(other)` | `boolean` | `EqualsString` | String equality |
| `.length()` | `BigInteger` | `LengthOfByteString(EncodeUtf8(s))` | String length in bytes |

String operators `==`, `!=`, and `+` are also supported (see Operators section).

```java
String a = "hello";
String b = "world";
boolean eq = a.equals(b);     // false
boolean same = a == "hello";   // true
String c = a + b;              // "helloworld"
BigInteger len = a.length();   // 5
```

### ByteString (byte[]) Instance Methods

| Method | Return Type | UPLC Translation | Description |
|--------|------------|-----------------|-------------|
| `.equals(other)` | `boolean` | `EqualsByteString` | Byte array equality |
| `.length()` | `BigInteger` | `LengthOfByteString` | Length in bytes |
| `.length` | `BigInteger` | `LengthOfByteString` | Length as field access (Java array style) |

ByteString operators `==`, `!=`, and `+` are also supported (see Operators section).

```java
byte[] hash = datum.hash();
BigInteger len1 = hash.length();  // method call form
BigInteger len2 = hash.length;    // field access form (both work)
byte[] combined = hash1 + hash2;  // AppendByteString
boolean same = hash1 == hash2;    // EqualsByteString
```

### Optional Instance Methods

Optional is encoded as `Constr(0, [value])` (Some) or `Constr(1, [])` (None).

| Method | Return Type | UPLC Translation | Description |
|--------|------------|-----------------|-------------|
| `.isPresent()` | `boolean` | `FstPair(UnConstrData(x)) == 0` | True if Some |
| `.isEmpty()` | `boolean` | `FstPair(UnConstrData(x)) == 1` | True if None |
| `.get()` | `T` | `HeadList(SndPair(UnConstrData(x)))` + decode | Unwrap the inner value |

```java
record MyDatum(Optional<BigInteger> reward) {}

// In validator:
if (datum.reward().isPresent()) {
    BigInteger val = datum.reward().get();
    return val > 0;
}
return datum.reward().isEmpty();
```

### PlutusData Equality

Raw `PlutusData` variables support `.equals()` and `==`/`!=` operators using `EqualsData`:

```java
PlutusData a = redeemer;
PlutusData b = redeemer;
boolean eq1 = a.equals(b);  // EqualsData
boolean eq2 = a == b;       // EqualsData
boolean ne = a != b;         // negated EqualsData
```

Record and sealed interface instances also use `EqualsData` when compared with `==`/`!=`.

### Other Ledger Types

- **TxInInfo**: `outRef()` (TxOutRef), `resolved()` (TxOut)
- **TxOut**: `address()` (Address), `value()` (Value), `datum()` (OutputDatum), `referenceScript()` (Optional)
- **TxOutRef**: `txId()` (byte[]), `outputIndex()` (BigInteger)
- **Address**: `credential()` (Credential), `stakingCredential()` (Optional)
- **Credential**: sealed interface with `PubKeyCredential(byte[] hash)` and `ScriptCredential(byte[] hash)`
- **OutputDatum**: sealed interface with `NoOutputDatum`, `OutputDatumHash(byte[])`, `OutputDatum(PlutusData)`
- **ScriptInfo**: sealed interface with `MintingScript(byte[])`, `SpendingScript(TxOutRef, Optional)`, `RewardingScript(Credential)`, etc.
- **Interval**: `from()` (IntervalBound), `to()` (IntervalBound)
- **IntervalBound**: `boundType()` (IntervalBoundType), `isInclusive()` (boolean)
- **IntervalBoundType**: sealed with `NegInf`, `Finite(BigInteger)`, `PosInf`

## Standard Library Reference

Import from `com.bloxbean.cardano.julc.onchain.stdlib.*` in validators.

### ContextsLib

| Method | Args | Description |
|--------|------|-------------|
| `signedBy(txInfo, pkh)` | TxInfo, ByteString | Check if pkh is in signatories |
| `getTxInfo(ctx)` | ScriptContext | Extract TxInfo from context |
| `getRedeemer(ctx)` | ScriptContext | Extract redeemer from context |
| `getSpendingDatum(ctx)` | ScriptContext | Extract optional spending datum |
| `txInfoInputs(txInfo)` | TxInfo | Get inputs list |
| `txInfoOutputs(txInfo)` | TxInfo | Get outputs list |
| `txInfoSignatories(txInfo)` | TxInfo | Get signatories list |
| `txInfoValidRange(txInfo)` | TxInfo | Get validity time range |

### ListsLib

| Method | Args | Description |
|--------|------|-------------|
| `isEmpty(list)` | List | Check if list is empty |
| `length(list)` | List | Count elements |
| `any(list, predicate)` | List, Lambda | True if any element matches |
| `all(list, predicate)` | List, Lambda | True if all elements match |
| `find(list, predicate)` | List, Lambda | Find first matching (Optional) |
| `foldl(f, init, list)` | Lambda, init, List | Left fold |

### ValuesLib

| Method | Args | Description |
|--------|------|-------------|
| `lovelaceOf(value)` | Value | Extract ADA amount |
| `geq(a, b)` | Value, Value | Compare lovelace: a >= b |
| `assetOf(value, policy, name)` | Value, BS, BS | Extract specific token amount |

### CryptoLib

| Method | Args | Description |
|--------|------|-------------|
| `sha2_256(bs)` | ByteString | SHA2-256 hash (32 bytes) |
| `sha3_256(bs)` | ByteString | SHA3-256 hash (32 bytes) |
| `blake2b_256(bs)` | ByteString | Blake2b-256 hash (32 bytes) |
| `blake2b_224(bs)` | ByteString | Blake2b-224 hash (28 bytes) |
| `keccak_256(bs)` | ByteString | Keccak-256 hash (32 bytes) |
| `verifyEd25519Signature(pk, msg, sig)` | BS, BS, BS | Verify Ed25519 signature |

### IntervalLib

| Method | Args | Description |
|--------|------|-------------|
| `contains(interval, time)` | Interval, Integer | Check if time is in interval |
| `always()` | (none) | The (-inf, +inf) interval |
| `after(time)` | Integer | The [time, +inf) interval |
| `before(time)` | Integer | The (-inf, time] interval |

## Complete Example

```java
import com.bloxbean.cardano.julc.onchain.annotation.*;
import com.bloxbean.cardano.julc.onchain.ledger.*;
import com.bloxbean.cardano.julc.core.PlutusData;
import java.math.BigInteger;

@Validator
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
