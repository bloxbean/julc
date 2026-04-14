---
title: "JuLC Advanced Guide: Low-Level Patterns"
description: "JuLC Advanced Guide: Low-Level Patterns - JuLC documentation"
---

This guide covers low-level programming patterns for developers who need to go beyond
the typed API provided by JuLC's ledger types and stdlib libraries. It assumes
familiarity with basic JuLC validator development (annotations, typed field access,
stdlib usage) as covered in the getting-started guide.

---


## 1. Introduction

The typed API (`ScriptContext`, `TxInfo`, `Value`, etc.) and stdlib libraries
(`OutputLib`, `ValuesLib`, `ListsLib`, etc.) handle most smart contract needs.
However, there are situations where you must drop down to low-level `Builtins`
and raw `PlutusData` manipulation:

- **Complex data manipulation** -- Building custom data structures, manually
  constructing Values, or working with nested maps/lists that the typed API
  does not cover.

- **Performance optimization** -- Minimizing script budget by avoiding redundant
  field extractions, short-circuiting early, or inlining operations that a
  library call would make more expensive.

- **Testing** -- Constructing mock `ScriptContext` and `TxInfo` as raw
  `PlutusData` for UPLC-level evaluation tests without using the full ledger
  record constructors.

- **Working with raw PlutusData** -- Governance types, custom datum layouts,
  protocol-specific data formats, or any case where the typed API does not
  yet have a record definition.

- **Cross-library interop** -- Understanding how compiled libraries receive
  and return Data at the UPLC boundary, and how to work around type-boundary
  mismatches.

---

## 2. PlutusData Encoding/Decoding

### The Data Hierarchy

All on-chain values are represented as `PlutusData`, a sealed interface with
exactly five variants:

| Variant | Java Type | On-Chain Representation |
|---------|-----------|------------------------|
| `ConstrData(tag, fields)` | `PlutusData.ConstrData` | Constructor application |
| `IntData(value)` | `PlutusData.IntData` | Arbitrary-precision integer |
| `BytesData(value)` | `PlutusData.BytesData` | Byte string |
| `ListData(items)` | `PlutusData.ListData` | List of Data values |
| `MapData(entries)` | `PlutusData.MapData` | Association list of key-value pairs |

### Encoding Builtins (Java -> Data)

These `Builtins` methods wrap primitive values into their Data representation:

```java
import com.bloxbean.cardano.julc.stdlib.Builtins;

// Wrap an integer as IntData
PlutusData wrapped = Builtins.iData(42);
PlutusData wrappedBig = Builtins.iData(BigInteger.valueOf(1000000));

// Wrap a byte array as BytesData
PlutusData wrappedBytes = Builtins.bData(new byte[]{0x01, 0x02, 0x03});

// Construct a ConstrData from tag + fields list
PlutusData.ListData fields = Builtins.mkCons(Builtins.iData(1), Builtins.mkNilData());
PlutusData.ConstrData constr = Builtins.constrData(0, fields);

// Wrap a ListData
PlutusData.ListData listWrapped = Builtins.listData(someList);

// Wrap a MapData
PlutusData.MapData mapWrapped = Builtins.mapData(somePairList);
```

### Decoding Builtins (Data -> Java)

These `Builtins` methods extract primitive values from their Data wrappers:

```java
// Extract integer from IntData
BigInteger n = Builtins.unIData(someData);      // throws if not IntData

// Extract BytesData from Data
PlutusData.BytesData bs = Builtins.unBData(someData);  // throws if not BytesData

// Deconstruct ConstrData into (tag, fields) pair
PlutusData.ConstrData pair = Builtins.unConstrData(someData);
// pair is ConstrData(0, [IntData(tag), ListData(fields)])
long tag = Builtins.constrTag(someData);               // shortcut: extract tag
PlutusData.ListData flds = Builtins.constrFields(someData); // shortcut: extract fields

// Extract ListData
PlutusData.ListData ld = Builtins.unListData(someData);

// Extract MapData
PlutusData.MapData md = Builtins.unMapData(someData);
```

### Record Encoding

Records (product types) are encoded as `ConstrData` with tag 0 and fields
in declaration order:

```
record Datum(BigInteger deadline, byte[] beneficiary)
  -> Constr(0, [IntData(deadline), BytesData(beneficiary)])
```

### Sealed Interface (Sum Type) Encoding

Sealed interface variants use ascending constructor tags starting from 0:

```
sealed interface Credential {
    record PubKeyCredential(PubKeyHash hash) ...  // tag 0
    record ScriptCredential(ScriptHash hash) ...  // tag 1
}

PubKeyCredential(pkh) -> Constr(0, [BytesData(pkh)])
ScriptCredential(sh)  -> Constr(1, [BytesData(sh)])
```

### Boolean Encoding

Booleans are encoded as constructors with no fields:

```
true  -> Constr(1, [])   // tag 1 = True
false -> Constr(0, [])   // tag 0 = False
```

### Optional Encoding

Optional values follow Haskell convention:

```
Some(x) -> Constr(0, [x])   // tag 0 = Just
None    -> Constr(1, [])     // tag 1 = Nothing
```

Example -- extracting an Optional datum:

```java
@SpendingValidator
class OptionalExample {
    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        // Get the optional datum from ScriptInfo
        PlutusData optDatum = (PlutusData)(Object) ctx.scriptInfo();
        var scriptFields = Builtins.constrFields(optDatum);
        // SpendingScript is tag 1: Constr(1, [txOutRef, optionalDatum])
        var optField = Builtins.headList(Builtins.tailList(scriptFields));

        // Check if Some (tag 0) or None (tag 1)
        if (Builtins.constrTag(optField) == 0) {
            // Some: extract the datum value
            PlutusData datum = Builtins.headList(Builtins.constrFields(optField));
            return Builtins.unIData(datum).compareTo(BigInteger.ZERO) > 0;
        } else {
            // None: no datum provided
            return false;
        }
    }
}
```

---

## 3. Type Casting Patterns

JuLC compiles all types down to `Data` at the UPLC level. Casts in Java source
are no-ops on-chain but serve as type hints for the compiler.

### Cast PlutusData to a Ledger Type

When you have raw `PlutusData` and need to treat it as a ledger type:

```java
// Recommended: PlutusData.cast()
PlutusData rawData = Builtins.headList(someList);
TxOut txOut = PlutusData.cast(rawData, TxOut.class);

// Also works: double-cast
TxOut txOut2 = (TxOut)(Object) rawData;

// Hash types
PlutusData rawHash = Builtins.headList(credentialFields);
PubKeyHash pkh = PlutusData.cast(rawHash, PubKeyHash.class);
```

### Extract Raw Hash Bytes

Ledger hash types (`PubKeyHash`, `TxId`, `ScriptHash`, `DatumHash`, `PolicyId`,
`TokenName`, `ValidatorHash`) map to `ByteStringType` on-chain. Calling `.hash()`
already extracts the raw `ByteString` via `UnBData(HeadList(...))`.

```java
// CORRECT: single .hash() extracts the byte[]
byte[] rawBytes = (byte[])(Object) pk.hash();

// WRONG: double .hash() applies UnBData on an already-unwrapped ByteString
// byte[] broken = pk.hash().hash();  // Runtime error: UnBData(ByteString)

// For TxId, same pattern applies
// CORRECT:
byte[] txIdBytes = (byte[])(Object) ref.txId();
// WRONG:
// byte[] broken = ref.txId().hash();  // Runtime error
```

### When to Use Type.of() vs Casts

The `Type.of(byte[])` factory methods are for creating ledger types from raw
`byte[]` values:

```java
// Use Type.of() when you have byte[] and want a ledger type
byte[] hashBytes = Builtins.sha2_256(someData);
PubKeyHash pkh = PubKeyHash.of(hashBytes);
PolicyId pid = PolicyId.of(policyBytes);

// Use PlutusData.cast() when you have PlutusData and want a ledger type
PlutusData rawFromList = Builtins.headList(signatories);
PubKeyHash pkh2 = PlutusData.cast(rawFromList, PubKeyHash.class);

// Also works: double-cast
PubKeyHash pkh3 = (PubKeyHash)(Object) rawFromList;
```

Key rule: `Type.of(byte[])` is for `byte[]` arguments. `PlutusData.cast()` or double-casts are for `PlutusData` arguments. `PlutusData.cast()` is preferred for readability.

### PlutusData.cast() — Clean Type Casting

Instead of the double-cast pattern, use `PlutusData.cast()`:

```java
// Old pattern (still works)
MyDatum datum = (MyDatum)(Object) rawData;

// New pattern (recommended)
MyDatum datum = PlutusData.cast(rawData, MyDatum.class);
```

Works with all target types — records, sealed interfaces, ledger types, `JulcMap`, `byte[]`, hash types:

```java
// Cast to custom record
var datum = PlutusData.cast(datumData, AuctionDatum.class);

// Cast to sealed interface (use in switch)
var action = PlutusData.cast(redeemer, Action.class);
return switch (action) {
    case Mint m -> m.amount() > 0;
    case Burn b -> b.amount() > 0;
};

// Cast to ledger type
var val = PlutusData.cast(rawValue, Value.class);

// Cast hash types
byte[] policyBytes = PlutusData.cast(mintInfo.policyId(), byte[].class);
ScriptHash sh = PlutusData.cast(policyBytes, ScriptHash.class);

// Chained field access
boolean ok = PlutusData.cast(redeemer, MyDatum.class).amount() == 42;
```

On-chain: zero cost (compiles to identity, same as the double-cast).
Off-chain: unchecked cast at JVM level.

**Note:** The second argument must be a literal `ClassName.class` expression. A variable holding a `Class<?>` is not supported on-chain.

#### Generic Collections: JulcList and JulcMap

Java class literals cannot carry generic type parameters (`JulcList.class` not `JulcList<MyRecord>.class`). When casting to generic collections, use an **explicit type declaration** on the left side — the compiler reads the generic info from the declared type, not from the `PlutusData.cast()` return:

```java
// CORRECT: explicit type preserves generics — element type is MyRecord
JulcList<MyRecord> records = PlutusData.cast(data, JulcList.class);

// CORRECT: explicit type preserves key/value types
JulcMap<BigInteger, MyRecord> lookup = PlutusData.cast(data, JulcMap.class);

// AVOID: var loses generic info — element type defaults to DataType
var records = PlutusData.cast(data, JulcList.class);  // JulcList<PlutusData>
```

This works because the JuLC compiler resolves the variable type from the declared type (`JulcList<MyRecord>`) rather than inferring it from the right-hand side. The generic parameters give the compiler the element/key/value types needed for typed access on list and map elements.

For nested generics, the same rule applies:
```java
// Map with typed values
JulcMap<byte[], JulcList<BigInteger>> nested = PlutusData.cast(data, JulcMap.class);
```

### The Double .hash() Bug Explained

The `.hash()` accessor on hash types generates UPLC code that does
`UnBData(HeadList(fields))`. The result is already a raw `ByteString`. Calling
`.hash()` again generates `UnBData(ByteString)` which crashes at runtime with a
`DeserializationError` because `ByteString` is not `BytesData`.

```java
@SpendingValidator
class HashExample {
    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        PubKeyHash signer = (PubKeyHash)(Object) Builtins.headList(
            (PlutusData)(Object) txInfo.signatories()
        );

        // CORRECT: extract raw bytes with cast
        byte[] signerBytes = (byte[])(Object) signer.hash();

        // Use the raw bytes for comparison
        byte[] expected = new byte[28];
        return Builtins.equalsByteString(signerBytes, expected);
    }
}
```

---

## 4. Low-Level List Manipulation

When the typed `JulcList` or `ListsLib` API is not sufficient, you can build and
traverse lists using raw `Builtins`.

### Building Lists

Lists are built from the end by prepending elements onto an empty list:

```java
@SpendingValidator
class ListBuilder {
    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        // Build a list [3, 2, 1] by prepending in reverse order
        PlutusData.ListData list = Builtins.mkNilData();
        list = Builtins.mkCons(Builtins.iData(1), list);
        list = Builtins.mkCons(Builtins.iData(2), list);
        list = Builtins.mkCons(Builtins.iData(3), list);

        // list is now [3, 2, 1]
        BigInteger first = Builtins.unIData(Builtins.headList(list));
        return first.equals(BigInteger.valueOf(3));
    }
}
```

### Manual List Traversal

The standard pattern for traversing a list uses `nullList`, `headList`, and
`tailList`:

```java
@SpendingValidator
class ListTraversal {
    // Sum all integers in a list
    static BigInteger sumList(PlutusData list) {
        BigInteger total = BigInteger.ZERO;
        PlutusData cursor = list;
        while (!Builtins.nullList(cursor)) {
            var item = Builtins.headList(cursor);
            total = total.add(Builtins.unIData(item));
            cursor = Builtins.tailList(cursor);
        }
        return total;
    }

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        PlutusData.ListData nums = Builtins.mkNilData();
        nums = Builtins.mkCons(Builtins.iData(10), nums);
        nums = Builtins.mkCons(Builtins.iData(20), nums);
        nums = Builtins.mkCons(Builtins.iData(30), nums);

        BigInteger sum = sumList(nums);
        return sum.equals(BigInteger.valueOf(60));
    }
}
```

### Pair List Typing: mkNilPairData vs mkNilData

When building lists of pairs (for map construction), you **must** use
`Builtins.mkNilPairData()` instead of `Builtins.mkNilData()`. The Scalus VM
strictly checks element types, and a list created with `mkNilData` is typed
as `List[Data]`, not `List[Pair[Data, Data]]`.

```java
// CORRECT: use mkNilPairData for pair lists
PlutusData pairList = Builtins.mkNilPairData();
var pair = Builtins.mkPairData(Builtins.bData(key), Builtins.iData(42));
pairList = Builtins.mkCons(pair, pairList);

// WRONG: using mkNilData for pair lists
// PlutusData pairList = Builtins.mkNilData();  // VM type error!
```

---

## 5. Map Construction and Pair Manipulation

Maps on-chain are association lists of pairs: `List[Pair[Data, Data]]`.

### Building Maps from Pairs

```java
@SpendingValidator
class MapBuilder {
    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        // Build a map { "alice" -> 100, "bob" -> 200 }
        PlutusData emptyPairList = Builtins.mkNilPairData();

        var bobPair = Builtins.mkPairData(
            Builtins.bData("bob".getBytes()),
            Builtins.iData(200)
        );
        var alicePair = Builtins.mkPairData(
            Builtins.bData("alice".getBytes()),
            Builtins.iData(100)
        );

        PlutusData myMap = Builtins.mkCons(alicePair,
            Builtins.mkCons(bobPair, emptyPairList));

        // Wrap as MapData
        PlutusData.MapData mapData = Builtins.mapData(myMap);

        // Lookup "alice": traverse the pair list
        BigInteger aliceAmount = lookupAmount(
            Builtins.unMapData(mapData), Builtins.bData("alice".getBytes()));
        return aliceAmount.equals(BigInteger.valueOf(100));
    }

    static BigInteger lookupAmount(PlutusData pairs, PlutusData key) {
        BigInteger result = BigInteger.ZERO;
        PlutusData cursor = pairs;
        while (!Builtins.nullList(cursor)) {
            var pair = Builtins.headList(cursor);
            if (Builtins.equalsData(Builtins.fstPair(pair), key)) {
                result = Builtins.unIData(Builtins.sndPair(pair));
                cursor = Builtins.mkNilPairData();  // break
            } else {
                cursor = Builtins.tailList(cursor);
            }
        }
        return result;
    }
}
```

### Traversing Maps

To traverse a map, first unwrap it with `unMapData` to get the pair list, then
iterate using `fstPair`/`sndPair` on each element:

```java
@SpendingValidator
class MapTraversal {
    // Count entries in a map
    static long countEntries(PlutusData mapValue) {
        var pairs = Builtins.unMapData(mapValue);
        long count = 0;
        PlutusData cursor = pairs;
        while (!Builtins.nullList(cursor)) {
            var pair = Builtins.headList(cursor);
            PlutusData key = Builtins.fstPair(pair);
            PlutusData value = Builtins.sndPair(pair);
            // Process key/value...
            count = count + 1;
            cursor = Builtins.tailList(cursor);
        }
        return count;
    }

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        PlutusData withdrawals = (PlutusData)(Object) txInfo.withdrawals();
        return countEntries(withdrawals) > 0;
    }
}
```

### MapType Always Holds Pair Lists

Internally, JuLC represents `MapType` variables as pair lists (not wrapped
`MapData`). This means:

- Field access like `txInfo.withdrawals()` already returns a pair list.
- Do NOT call `Builtins.unMapData()` on the result of a field access that the
  compiler already knows is a map -- this would double-unwrap.
- When you receive a `PlutusData.MapData` from an external source (e.g., a
  datum or parameter), you DO need `Builtins.unMapData()` to get the pair list.

### For-Each on Maps

The `for (var entry : map)` syntax on a `MapType` variable automatically
prepends `UnMapData` and yields `PairType` elements:

```java
@SpendingValidator
class MapForEach {
    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        var withdrawals = txInfo.withdrawals();

        BigInteger totalWithdrawn = BigInteger.ZERO;
        for (var entry : withdrawals) {
            // entry is PairType: entry.key() gives Credential, entry.value() gives amount
            BigInteger amount = (BigInteger)(Object) entry.value();
            totalWithdrawn = totalWithdrawn.add(amount);
        }
        return totalWithdrawn.compareTo(BigInteger.ZERO) > 0;
    }
}
```

---

## 6. Raw Value Manipulation

### Value Structure

A Cardano `Value` is a nested map:

```
Map<ByteString, Map<ByteString, Integer>>
 ^                ^                ^
 |                |                |
 policy ID        token name       quantity
```

Lovelace (ADA) is stored under the **empty bytestring** policy ID and the
**empty bytestring** token name.

### Manual Lovelace Extraction

This is what `ValuesLib.lovelaceOf()` does under the hood:

```java
@SpendingValidator
class LovelaceExtraction {
    static BigInteger extractLovelace(PlutusData value) {
        // Value is MapData: first entry is the ADA policy (empty ByteString)
        var outerPairs = Builtins.unMapData(value);
        var firstOuterPair = Builtins.headList(outerPairs);

        // The value of the first pair is the inner token map
        var innerMap = Builtins.sndPair(firstOuterPair);
        var innerPairs = Builtins.unMapData(innerMap);
        var firstInnerPair = Builtins.headList(innerPairs);

        // The value of the first inner pair is the lovelace amount
        return Builtins.unIData(Builtins.sndPair(firstInnerPair));
    }

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        var outputs = txInfo.outputs();
        TxOut firstOutput = (TxOut)(Object) Builtins.headList(
            (PlutusData)(Object) outputs);
        PlutusData outputValue = (PlutusData)(Object) firstOutput.value();

        BigInteger lovelace = extractLovelace(outputValue);
        return lovelace.compareTo(BigInteger.valueOf(2_000_000)) >= 0;
    }
}
```

### Manual Asset Lookup

Looking up a specific native token amount requires traversing both the outer
map (by policy ID) and the inner map (by token name):

```java
@SpendingValidator
class AssetLookup {
    static BigInteger findAssetAmount(PlutusData value,
                                      PlutusData policyId,
                                      PlutusData tokenName) {
        var outerPairs = Builtins.unMapData(value);
        BigInteger result = BigInteger.ZERO;
        PlutusData outerCursor = outerPairs;

        // Search outer map for matching policy
        while (!Builtins.nullList(outerCursor)) {
            var outerPair = Builtins.headList(outerCursor);
            if (Builtins.equalsData(Builtins.fstPair(outerPair), policyId)) {
                // Found policy, search inner map for token name
                var innerPairs = Builtins.unMapData(Builtins.sndPair(outerPair));
                PlutusData innerCursor = innerPairs;
                while (!Builtins.nullList(innerCursor)) {
                    var innerPair = Builtins.headList(innerCursor);
                    if (Builtins.equalsData(Builtins.fstPair(innerPair), tokenName)) {
                        result = Builtins.unIData(Builtins.sndPair(innerPair));
                        innerCursor = Builtins.mkNilPairData();  // break
                    } else {
                        innerCursor = Builtins.tailList(innerCursor);
                    }
                }
                outerCursor = Builtins.mkNilPairData();  // break
            } else {
                outerCursor = Builtins.tailList(outerCursor);
            }
        }
        return result;
    }

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        PlutusData mintValue = (PlutusData)(Object) txInfo.mint();
        byte[] myPolicy = new byte[28];  // your policy ID
        byte[] myToken = "MyToken".getBytes();

        BigInteger minted = findAssetAmount(mintValue,
            Builtins.bData(myPolicy), Builtins.bData(myToken));
        return minted.equals(BigInteger.ONE);
    }
}
```

### Building a Value Manually

```java
// Build a Value containing 5 ADA + 1 MyToken
static PlutusData buildValue(byte[] policyId, byte[] tokenName) {
    var emptyPairList = Builtins.mkNilPairData();
    var emptyBs = Builtins.bData(new byte[0]);

    // Inner map for ADA: { "" -> 5000000 }
    var adaTokenPair = Builtins.mkPairData(emptyBs, Builtins.iData(5_000_000));
    var adaInnerMap = Builtins.mapData(
        Builtins.mkCons(adaTokenPair, emptyPairList));

    // Inner map for native token: { tokenName -> 1 }
    var tokenPair = Builtins.mkPairData(
        Builtins.bData(tokenName), Builtins.iData(1));
    var tokenInnerMap = Builtins.mapData(
        Builtins.mkCons(tokenPair, emptyPairList));

    // Outer map: { "" -> adaInnerMap, policyId -> tokenInnerMap }
    var adaOuterPair = Builtins.mkPairData(emptyBs, adaInnerMap);
    var tokenOuterPair = Builtins.mkPairData(
        Builtins.bData(policyId), tokenInnerMap);

    var outerList = Builtins.mkCons(adaOuterPair,
        Builtins.mkCons(tokenOuterPair, emptyPairList));

    return Builtins.mapData(outerList);
}
```

---

## 7. Working with Raw PlutusData

### When Typed Access Is Not Enough

The typed API covers most standard Plutus V3 types, but you may need raw
`PlutusData` for:

- **Governance types** that are not fully modeled yet.
- **Custom datum layouts** from other smart contract protocols.
- **Test fixtures** where you want to build ScriptContext directly as Data.
- **Generic validators** that must handle arbitrary datum/redeemer shapes.

### Building ScriptContext for Tests

The `PlutusData` interface provides convenience factory methods:

```java
import com.bloxbean.cardano.julc.core.PlutusData;

// V3 ScriptContext structure: Constr(0, [txInfo, redeemer, scriptInfo])
PlutusData scriptContext = PlutusData.constr(0,
    buildTxInfo(),
    PlutusData.integer(42),     // redeemer
    buildScriptInfo()
);

PlutusData buildScriptInfo() {
    // SpendingScript = Constr(1, [txOutRef, optionalDatum])
    PlutusData txOutRef = PlutusData.constr(0,
        PlutusData.constr(0, PlutusData.bytes(new byte[32])),  // TxId
        PlutusData.integer(0)                                   // index
    );
    PlutusData noDatum = PlutusData.constr(1);  // None
    return PlutusData.constr(1, txOutRef, noDatum);
}
```

### V3 ScriptContext Structure

```
ScriptContext = Constr(0, [txInfo, redeemer, scriptInfo])
```

### TxInfo Structure (16 fields, all under Constr tag 0)

```
TxInfo = Constr(0, [
    inputs,           // 0:  List[TxInInfo]
    referenceInputs,  // 1:  List[TxInInfo]
    outputs,          // 2:  List[TxOut]
    fee,              // 3:  Integer (lovelace)
    mint,             // 4:  Value (Map)
    certificates,     // 5:  List[TxCert]
    withdrawals,      // 6:  Map[Credential, Integer]
    validRange,       // 7:  Interval
    signatories,      // 8:  List[PubKeyHash (ByteString)]
    redeemers,        // 9:  Map[ScriptPurpose, Data]
    datums,           // 10: Map[DatumHash, Data]
    txId,             // 11: TxId = Constr(0, [ByteString])
    votes,            // 12: Map[Voter, Map[GovernanceActionId, Vote]]
    proposalProcedures, // 13: List[ProposalProcedure]
    currentTreasuryAmount, // 14: Optional Integer
    treasuryDonation  // 15: Optional Integer
])
```

### Building a Minimal TxInfo

```java
PlutusData buildMinimalTxInfo(byte[] signerPkh) {
    PlutusData emptyList = PlutusData.list();
    PlutusData emptyMap = PlutusData.map();
    byte[] emptyBs = new byte[0];

    // Interval: always valid = (NegInf inclusive, PosInf inclusive)
    PlutusData negInf = PlutusData.constr(0,                     // NegInf = tag 0
        PlutusData.constr(0));                                    // inclusive = true (Constr 1 [] would be false)
    PlutusData posInf = PlutusData.constr(0,
        PlutusData.constr(2));                                    // PosInf = tag 2
    // Actually, IntervalBound = Constr(0, [boundType, isInclusive])
    // where isInclusive is Boolean: Constr(1,[])=True, Constr(0,[])=False
    PlutusData trueVal = PlutusData.constr(1);
    PlutusData lowerBound = PlutusData.constr(0,
        PlutusData.constr(0),   // NegInf
        trueVal);
    PlutusData upperBound = PlutusData.constr(0,
        PlutusData.constr(2),   // PosInf
        trueVal);
    PlutusData validRange = PlutusData.constr(0, lowerBound, upperBound);

    // Signatories: list of PubKeyHash (just ByteStrings)
    PlutusData signatories = PlutusData.list(PlutusData.bytes(signerPkh));

    // Optional None for treasury fields
    PlutusData none = PlutusData.constr(1);

    // TxId
    PlutusData txId = PlutusData.constr(0, PlutusData.bytes(new byte[32]));

    return PlutusData.constr(0,
        emptyList,      // inputs
        emptyList,      // referenceInputs
        emptyList,      // outputs
        PlutusData.integer(200_000),  // fee
        emptyMap,       // mint
        emptyList,      // certificates
        emptyMap,       // withdrawals
        validRange,     // validRange
        signatories,    // signatories
        emptyMap,       // redeemers
        emptyMap,       // datums
        txId,           // txId
        emptyMap,       // votes
        emptyList,      // proposalProcedures
        none,           // currentTreasuryAmount
        none            // treasuryDonation
    );
}
```

### Extracting Fields by Position

When you cannot use the typed API, you can extract fields by position:

```java
@SpendingValidator
class RawFieldAccess {
    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        // Access ScriptContext fields by position (0=txInfo, 1=redeemer, 2=scriptInfo)
        PlutusData rawCtx = (PlutusData)(Object) ctx;
        var ctxFields = Builtins.constrFields(rawCtx);
        PlutusData txInfo = Builtins.headList(ctxFields);

        // Access TxInfo field 8 (signatories) by chaining tailList
        var txFields = Builtins.constrFields(txInfo);
        PlutusData signatories = txFields;
        // Skip first 8 fields
        long idx = 8;
        PlutusData cursor = txFields;
        while (idx > 0) {
            cursor = Builtins.tailList(cursor);
            idx = idx - 1;
        }
        PlutusData sigs = Builtins.headList(cursor);

        // Check at least one signatory
        return !Builtins.nullList(sigs);
    }
}
```

---

## 8. Debugging and Tracing

### Builtins.trace

The `Builtins.trace` method emits a trace message and returns the second
argument unchanged. This is compiled to the UPLC `Trace` builtin:

```java
@SpendingValidator
class TracingValidator {
    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        BigInteger fee = txInfo.fee();

        // Trace a message and continue
        Builtins.trace("Checking fee amount", PlutusData.UNIT);

        if (fee.compareTo(BigInteger.valueOf(1_000_000)) > 0) {
            Builtins.trace("Fee is above threshold", PlutusData.UNIT);
            return true;
        } else {
            Builtins.trace("Fee too low, rejecting", PlutusData.UNIT);
            return false;
        }
    }
}
```

### ContextsLib.trace

A shorter form that does not require a return value argument:

```java
@SpendingValidator
class ShortTrace {
    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        ContextsLib.trace("Validator entered");
        // ... validation logic
        return true;
    }
}
```

### Collecting Traces in Tests

When evaluating a compiled program, traces are collected in the `EvalResult`:

```java
var program = ValidatorTest.compile(validatorSource);
var result = ValidatorTest.evaluate(validatorSource, scriptContext);

// Access traces
if (result instanceof EvalResult.Success success) {
    List<String> traces = success.traces();
    for (String trace : traces) {
        System.out.println("TRACE: " + trace);
    }
}
if (result instanceof EvalResult.Failure failure) {
    List<String> traces = failure.traces();
    // Traces emitted before the failure point are still collected
}
```

### Common Runtime Errors

| Error Message | Cause | Fix |
|---|---|---|
| `DeserializationError` | Type mismatch -- applying `UnIData` to non-integer, `UnBData` to non-bytes, etc. | Check that your Data value actually has the expected type. Often caused by double `.hash()` or missing `wrapEncode`/`wrapDecode`. |
| `NonPositiveInteger` | Passing zero or negative values to crypto operations that require positive integers. | Validate inputs before passing to crypto builtins. |
| `BudgetExhausted` | Script exceeded CPU or memory budget. | Optimize with techniques from Section 9. |
| `headList: empty list` | Calling `headList` on an empty list. | Check `!nullList(cursor)` before accessing head. |
| `Expected ListData, got ...` | Passing a non-list value to a list operation. | Ensure the value is properly wrapped as `ListData`. |

---

## 9. Budget Measurement and Optimization

### Understanding Per-Operation Costs

Approximate CPU costs for common operations:

| Operation | CPU Cost (approx.) |
|---|---|
| Field access (`HeadList`, `TailList`) | ~5,000 |
| Arithmetic (`AddInteger`, `MultiplyInteger`) | ~10,000 |
| Comparison (`EqualsInteger`, `LessThanInteger`) | ~10,000 |
| Data equality (`EqualsData`) | ~50,000+ (depends on structure size) |
| Crypto (`Sha2_256`, `Blake2b_256`) | ~100,000+ |
| Signature verification (`VerifyEd25519Signature`) | ~5,000,000+ |

### Fail-Fast with Builtins.error()

Exit immediately when a validation check fails, saving budget on the
remaining operations:

```java
@SpendingValidator
class FailFast {
    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();

        // Check cheapest condition first
        if (txInfo.fee().compareTo(BigInteger.ZERO) <= 0) {
            Builtins.error();
        }

        // More expensive check: only reached if fee check passes
        if (!ContextsLib.signedBy(txInfo, new byte[28])) {
            Builtins.error();
        }

        return true;
    }
}
```

### Cache Repeated Field Access

Each field access generates `HeadList(TailList(...))` chains. Extract fields
once and reuse the local variable:

```java
@SpendingValidator
class CachedAccess {
    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        // BAD: accesses txInfo.outputs() three times, each time
        // re-extracting from the Constr fields
        // long count1 = OutputLib.countOutputsAt(ctx.txInfo().outputs(), addr);
        // long count2 = OutputLib.countOutputsAt(ctx.txInfo().outputs(), addr2);
        // var outs = ctx.txInfo().outputs();

        // GOOD: extract once, reuse
        TxInfo txInfo = ctx.txInfo();
        var outputs = txInfo.outputs();
        var signatories = txInfo.signatories();

        // Now use the cached variables
        return !Builtins.nullList((PlutusData)(Object) outputs)
            && !Builtins.nullList((PlutusData)(Object) signatories);
    }
}
```

### Short-Circuit: Check Cheapest Conditions First

Order your validation checks from cheapest to most expensive:

```java
@SpendingValidator
class ShortCircuit {
    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();

        // 1. Integer comparison (~10K CPU) -- cheapest
        if (txInfo.fee().compareTo(BigInteger.valueOf(200_000)) < 0) {
            return false;
        }

        // 2. Data equality (~50K CPU) -- moderate
        byte[] expectedSigner = new byte[28];
        if (!ContextsLib.signedBy(txInfo, expectedSigner)) {
            return false;
        }

        // 3. Crypto verification (~5M CPU) -- most expensive, checked last
        // Only reached if both cheap checks pass
        return true;
    }
}
```

### Script Size: Avoid Unused Methods

Every helper method in your validator class gets compiled into the UPLC output,
even if it is not called. Remove unused methods to reduce script size.

Prefer using stdlib library methods (`ListsLib`, `ValuesLib`, etc.) over
reimplementing common operations inline -- the compiled library code is shared
and the compiler avoids duplicating it.

---

## 10. Cross-Library Call Patterns

### Data Boundary Semantics

When your validator calls a method from an `@OnchainLibrary` class, the compiled
library expects **raw Data** values at the UPLC boundary. The compiler normally
handles the necessary encode/decode conversions, but there are edge cases.

### The BytesData Parameter Bug

If the caller has a variable typed as `BytesData` (or `MapData`, etc.) and the
library method also has a parameter typed as `BytesData`, the compiler sees
matching types and **skips the conversion**. But the compiled library expects
raw `Data` at the UPLC boundary, not already-unwrapped `ByteString`.

```java
@SpendingValidator
class CrossLibraryBug {
    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        // BAD: policyId is typed as byte[] (ByteStringType)
        // Calling ValuesLib.containsBytes() with a ByteStringType arg
        // may skip the necessary bData() wrapping
        byte[] policyId = new byte[28];
        // ListsLib.containsBytes(list, policyId) -- potential type mismatch!

        // GOOD: use PlutusData typed variable
        PlutusData policyIdData = Builtins.bData(policyId);
        // Now the compiler sees PlutusData (DataType) and passes it through correctly
        return true;
    }
}
```

### Workaround: Use PlutusData Variables

When calling stdlib methods that take typed params (`BytesData`, `MapData`, etc.),
declare your variables as `PlutusData` instead:

```java
@SpendingValidator
class SafeCrossLibrary {
    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();

        // Use PlutusData instead of byte[] for cross-library calls
        PlutusData policyId = Builtins.bData(new byte[28]);
        PlutusData tokenName = Builtins.bData(new byte[0]);

        // ValuesLib.assetOf expects Data at the boundary -- PlutusData passes through safely
        BigInteger amount = ValuesLib._assetOf(
            txInfo.mint(),
            (PlutusData.BytesData) policyId,
            (PlutusData.BytesData) tokenName
        );

        return amount.compareTo(BigInteger.ZERO) > 0;
    }
}
```

### @Param Must Be PlutusData

The `@Param` annotation marks fields that are baked into the script at deploy
time. `@Param` values are **always raw Data at runtime**, regardless of their
declared type.

```java
@SpendingValidator
class ParameterizedValidator {
    // CORRECT: always use PlutusData for @Param
    @Param PlutusData ownerPkh;
    @Param PlutusData minAmount;

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        BigInteger amount = Builtins.unIData(minAmount);
        return amount.compareTo(BigInteger.ZERO) > 0;
    }
}

// WRONG: using typed params causes issues
// @Param PlutusData.BytesData ownerPkh;  // broken: double-wraps with bData()
// @Param byte[] tokenPolicy;              // broken: type mismatch at runtime
```

### Local Wrapper Method Pattern

If you need to call a stdlib method with typed params frequently, create a
local wrapper in your validator:

```java
@SpendingValidator
class WrapperPattern {
    // Local wrapper avoids cross-library type boundary issues
    static BigInteger getAssetAmount(Value value, PlutusData policy, PlutusData token) {
        // Extract the amount manually, avoiding the cross-library boundary
        var outerPairs = Builtins.unMapData(value);
        BigInteger result = BigInteger.ZERO;
        PlutusData cursor = outerPairs;
        while (!Builtins.nullList(cursor)) {
            var pair = Builtins.headList(cursor);
            if (Builtins.equalsData(Builtins.fstPair(pair), policy)) {
                var innerPairs = Builtins.unMapData(Builtins.sndPair(pair));
                result = ValuesLib.findTokenAmount(
                    (PlutusData.MapData) Builtins.sndPair(pair),
                    (PlutusData.BytesData) token);
                cursor = Builtins.mkNilPairData();
            } else {
                cursor = Builtins.tailList(cursor);
            }
        }
        return result;
    }

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        PlutusData policy = Builtins.bData(new byte[28]);
        PlutusData token = Builtins.bData("Token".getBytes());
        BigInteger minted = getAssetAmount(ctx.txInfo().mint(), policy, token);
        return minted.equals(BigInteger.ONE);
    }
}
```

---

## 11. Complex Sealed Interface Hierarchies

### Multi-Level Nesting

Sealed interfaces can be nested. For example, `ScriptInfo` has six variants,
and `Credential` has two variants:

```java
@SpendingValidator
class NestedSwitch {
    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        // First-level switch: ScriptInfo variants
        return switch (ctx.scriptInfo()) {
            case ScriptInfo.SpendingScript ss -> handleSpending(ctx, ss);
            case ScriptInfo.MintingScript ms -> false;
            case ScriptInfo.RewardingScript rs -> false;
            case ScriptInfo.CertifyingScript cs -> false;
            case ScriptInfo.VotingScript vs -> false;
            case ScriptInfo.ProposingScript ps -> false;
        };
    }

    static boolean handleSpending(ScriptContext ctx, ScriptInfo.SpendingScript ss) {
        TxInfo txInfo = ctx.txInfo();
        TxOut firstOutput = (TxOut)(Object) Builtins.headList(
            (PlutusData)(Object) txInfo.outputs());

        // Second-level switch: Credential variants within Address
        return switch (firstOutput.address().credential()) {
            case Credential.PubKeyCredential pk -> {
                byte[] hash = (byte[])(Object) pk.hash();
                yield Builtins.lengthOfByteString(hash) == 28;
            }
            case Credential.ScriptCredential sc -> {
                byte[] hash = (byte[])(Object) sc.hash();
                yield Builtins.lengthOfByteString(hash) == 28;
            }
        };
    }
}
```

### Switch Best Practices

**Exhaust all cases explicitly.** The `default` branch works as a catch-all for
uncovered variants, but prefer listing every variant explicitly for clarity:

```java
// OK but not recommended: default catches all unlisted variants
return switch (ctx.scriptInfo()) {
    case ScriptInfo.SpendingScript ss -> true;
    default -> false;  // compiled — covers all other ScriptInfo variants
};

// RECOMMENDED: list all cases explicitly
return switch (ctx.scriptInfo()) {
    case ScriptInfo.SpendingScript ss -> true;
    case ScriptInfo.MintingScript ms -> false;
    case ScriptInfo.RewardingScript rs -> false;
    case ScriptInfo.CertifyingScript cs -> false;
    case ScriptInfo.VotingScript vs -> false;
    case ScriptInfo.ProposingScript ps -> false;
};
```

The compiler enforces exhaustiveness: if you omit a case (and have no `default`),
you will get a compile error listing the missing cases.

### Field Name Collision

When a switch case destructures a variant, the compiler binds the constructor's
field names in scope. If a method parameter has the same name as a field, the
field binding **shadows** the parameter.

```java
// WRONG: parameter "time" is shadowed by Finite's field "time"
static boolean checkBound(IntervalBound bound, BigInteger time) {
    return switch (bound.boundType()) {
        case IntervalBoundType.Finite f ->
            // f.time() returns the Finite field, but "time" (the parameter)
            // is also bound to f.time() due to shadowing!
            // This comparison always returns true (self-comparison).
            time.compareTo(f.time()) >= 0;
        case IntervalBoundType.NegInf ignored -> true;
        case IntervalBoundType.PosInf ignored -> false;
    };
}

// CORRECT: use a different parameter name
static boolean checkBound(IntervalBound bound, BigInteger point) {
    return switch (bound.boundType()) {
        case IntervalBoundType.Finite f ->
            point.compareTo(f.time()) >= 0;  // "point" != "time", no shadowing
        case IntervalBoundType.NegInf ignored -> true;
        case IntervalBoundType.PosInf ignored -> false;
    };
}
```

This applies to all sealed interface switch cases. The rule: **never name a
method parameter the same as any field in the variants you are matching on.**

---

## 12. Recursion Patterns

### Automatic Z-Combinator Wrapping

Helper methods in JuLC validators automatically support self-recursion. The
compiler wraps recursive helper methods with the Z-combinator at the UPLC level,
so you can write natural recursive code:

```java
@SpendingValidator
class RecursiveValidator {
    // Recursive factorial -- compiled with Z-combinator
    static BigInteger factorial(BigInteger n) {
        if (n.equals(BigInteger.ZERO)) {
            return BigInteger.ONE;
        } else {
            return n.multiply(factorial(n.subtract(BigInteger.ONE)));
        }
    }

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        BigInteger n = Builtins.unIData(redeemer);
        BigInteger result = factorial(n);
        return result.equals(BigInteger.valueOf(120)); // 5! = 120
    }
}
```

### List Traversal with Recursion

Recursive list operations are a natural fit:

```java
@SpendingValidator
class RecursiveList {
    // Count elements in a list recursively
    static long countElements(PlutusData list) {
        if (Builtins.nullList(list)) {
            return 0;
        } else {
            return 1 + countElements(Builtins.tailList(list));
        }
    }

    // Check if any element satisfies a condition (value > threshold)
    static boolean anyGreaterThan(PlutusData list, BigInteger threshold) {
        if (Builtins.nullList(list)) {
            return false;
        } else {
            BigInteger head = Builtins.unIData(Builtins.headList(list));
            if (head.compareTo(threshold) > 0) {
                return true;
            } else {
                return anyGreaterThan(Builtins.tailList(list), threshold);
            }
        }
    }

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        PlutusData.ListData nums = Builtins.mkNilData();
        nums = Builtins.mkCons(Builtins.iData(10), nums);
        nums = Builtins.mkCons(Builtins.iData(50), nums);
        nums = Builtins.mkCons(Builtins.iData(3), nums);

        long count = countElements(nums);
        boolean hasLarge = anyGreaterThan(nums, BigInteger.valueOf(25));
        return count == 3 && hasLarge;
    }
}
```

### GCD: Two-Parameter Recursion

Recursive functions with multiple parameters work naturally:

```java
@SpendingValidator
class GcdValidator {
    static BigInteger gcd(BigInteger a, BigInteger b) {
        if (b.equals(BigInteger.ZERO)) {
            return a;
        } else {
            return gcd(b, a.remainder(b));
        }
    }

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        BigInteger a = BigInteger.valueOf(48);
        BigInteger b = BigInteger.valueOf(18);
        BigInteger result = gcd(a, b);
        return result.equals(BigInteger.valueOf(6));
    }
}
```

### Multi-Binding LetRec (Mutual Recursion)

JuLC supports mutual recursion between **two** methods via Bekic's theorem.
This enables patterns like `isEven`/`isOdd`:

```java
@SpendingValidator
class MutualRecursion {
    // These two methods are mutually recursive
    static boolean isEven(BigInteger n) {
        if (n.equals(BigInteger.ZERO)) {
            return true;
        } else {
            return isOdd(n.subtract(BigInteger.ONE));
        }
    }

    static boolean isOdd(BigInteger n) {
        if (n.equals(BigInteger.ZERO)) {
            return false;
        } else {
            return isEven(n.subtract(BigInteger.ONE));
        }
    }

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        BigInteger n = Builtins.unIData(redeemer);
        return isEven(n);
    }
}
```

### Limitations

- **Self-recursion**: Fully supported for any number of helper methods.
- **2-binding mutual recursion**: Supported via Bekic's theorem (e.g.,
  `isEven`/`isOdd` above).
- **>2-binding mutual recursion**: **Not supported.** If three or more methods
  form a mutual recursion cycle, the compiler will fail. Restructure your code
  to use at most two mutually recursive methods, or refactor into self-recursive
  form with a mode parameter:

```java
// WORKAROUND for 3-way mutual recursion:
// Combine into a single self-recursive function with a mode tag
static BigInteger dispatch(BigInteger mode, BigInteger n) {
    if (mode.equals(BigInteger.ZERO)) {
        // was functionA
        return dispatch(BigInteger.ONE, n.subtract(BigInteger.ONE));
    } else if (mode.equals(BigInteger.ONE)) {
        // was functionB
        return dispatch(BigInteger.TWO, n.subtract(BigInteger.ONE));
    } else {
        // was functionC -- base case
        if (n.equals(BigInteger.ZERO)) {
            return BigInteger.ONE;
        } else {
            return dispatch(BigInteger.ZERO, n.subtract(BigInteger.ONE));
        }
    }
}
```

### Tail-Recursive Style with Accumulators

For better budget efficiency, prefer tail-recursive accumulator style over
naive recursion. While JuLC does not perform tail-call optimization, the
accumulator pattern avoids building up a deep chain of deferred multiplications
or additions:

```java
@SpendingValidator
class TailRecursive {
    // Tail-recursive factorial with accumulator
    static BigInteger factAcc(BigInteger n, BigInteger acc) {
        if (n.equals(BigInteger.ZERO)) {
            return acc;
        } else {
            return factAcc(n.subtract(BigInteger.ONE), acc.multiply(n));
        }
    }

    // Tail-recursive list sum with accumulator
    static BigInteger sumAcc(PlutusData list, BigInteger acc) {
        if (Builtins.nullList(list)) {
            return acc;
        } else {
            BigInteger head = Builtins.unIData(Builtins.headList(list));
            return sumAcc(Builtins.tailList(list), acc.add(head));
        }
    }

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        BigInteger fact5 = factAcc(BigInteger.valueOf(5), BigInteger.ONE);

        PlutusData.ListData nums = Builtins.mkNilData();
        nums = Builtins.mkCons(Builtins.iData(10), nums);
        nums = Builtins.mkCons(Builtins.iData(20), nums);
        BigInteger sum = sumAcc(nums, BigInteger.ZERO);

        return fact5.equals(BigInteger.valueOf(120)) && sum.equals(BigInteger.valueOf(30));
    }
}
```

Note: For iterative patterns (where you do not need true recursion), prefer
`while` loops. The compiler desugars `while` loops into efficient `LetRec`
with accumulator unpacking, which is often more budget-friendly than manual
recursion for simple traversals.

---

## 13. @NewType Zero-Cost Type Aliases

The `@NewType` annotation creates zero-cost type aliases for single-field records. On-chain, the constructor compiles to identity — no `ConstrData` wrapping is generated.

```java
@NewType
public record AssetClass(byte[] policyId) {}

// Usage in validator:
AssetClass ac = AssetClass.of(myBytes);  // .of() auto-registered
```

**Constraints:**
- Must be a `record` with exactly one field
- Underlying type must be `byte[]`, `BigInteger`, `String`, or `boolean`
- Multi-field or unsupported-type records produce a compiler error

**On-chain behavior:** `AssetClass.of(bytes)` compiles to identity — the bytes are passed through as-is. Field access `ac.policyId()` is also identity.

---

## 14. Optional Support

`Optional<T>` is supported as a first-class on-chain type. It maps to `ConstrData`:

- `Optional.of(x)` → `ConstrData(0, [encode(x)])`
- `Optional.empty()` → `ConstrData(1, [])`

```java
Optional<BigInteger> maybe = Optional.of(BigInteger.valueOf(42));
if (maybe.isPresent()) {
    BigInteger val = maybe.get();  // auto-decoded from Data
}
```

**Instance methods:**
| Method | Description |
|--------|-------------|
| `.isPresent()` | True if Some (tag == 0) |
| `.isEmpty()` | True if None (tag == 1) |
| `.get()` | Unwrap the inner value (decoded based on type arg) |

Use `import java.util.Optional` or bare `Optional` in your validator code.

---

## 15. Tuple2/Tuple3 Generic Support

`Tuple2<A,B>` and `Tuple3<A,B,C>` provide generic tuples with auto-unwrapping field access based on type arguments.

```java
Tuple2<BigInteger, byte[]> result = MathLib.divMod(a, b);
BigInteger quotient = result.first();   // auto-generates UnIData
byte[] remainder = result.second();     // auto-generates UnBData

// Construction auto-wraps
var t = new Tuple2<BigInteger, BigInteger>(val1, val2);  // auto-wraps via IData
```

**Important:** Tuple2/Tuple3 are **not switchable** — they are registered as `RecordType`, but `switch` requires `SumType` (sealed interface). Use `.first()`, `.second()`, `.third()` field access instead of pattern matching.

Raw `Tuple2` (no type args) defaults to `DataType` for backward compatibility.

---

## 16. Nested Loops

Nested loops are fully supported: while-in-while, for-each-in-for-each, and mixed nesting. The `LoopDesugarer` assigns unique names to each loop function (`loop__forEach__0`, `loop__while__1`, etc.) to prevent name collisions.

```java
BigInteger total = 0;
for (var group : groups) {
    for (var item : group.items()) {
        total = total + item.amount();
    }
}
```

For detailed patterns including multi-accumulator nested loops, see [For-Loop Patterns](/guides/for-loop-patterns/).

---

## 17. Higher-Order Functions (HOFs)

Higher-order functions are available as both instance methods and static calls. Lambda parameter types are auto-inferred from the list element type.

```java
// Instance method syntax (preferred)
boolean hasLargeAmount = outputs.any(o -> o.value().lovelaceOf() > 1000000);
var filtered = list.filter(x -> x > 0);
var mapped = list.map(x -> x + 1);  // returns JulcList<PlutusData>

// Static syntax
boolean found = ListsLib.any(list, x -> x > threshold);
var sum = ListsLib.foldl((acc, x) -> acc + Builtins.unIData(x), BigInteger.ZERO, list);
```

**Available HOFs:** `map`, `filter`, `any`, `all`, `find` (instance + static), `foldl`, `zip` (static only).

Variable capture is supported. Block body lambdas and chaining (`list.filter(...).map(...)`) work as expected.

Note: `map()` wraps lambda results to Data — the returned list has `DataType` elements. Use `Builtins.unIData()` etc. when extracting values from mapped results.

For the full HOF reference, see [Standard Library Guide — HOFs](/stdlib/stdlib-guide/).

---

## 18. Byte Array Constants

JuLC supports `byte[]` constants in on-chain code using two syntaxes:

### String.getBytes()

```java
static final byte[] FACTORY_MARKER = "FACTORY_MARKER".getBytes();
static final byte[] LOTTERY_TOKEN = "LOTTERY_TOKEN".getBytes();
```

Compiles to `EncodeUtf8("FACTORY_MARKER")` — produces the UTF-8 encoded bytes at UPLC level.

### Literal byte[] Initializers

```java
static final byte[] TOKEN_PREFIX = new byte[]{0x46, 0x41, 0x43, 0x54};
```

Compiles to a `ByteString` constant. All array elements must be integer literals (no variables or expressions).

### Usage in Validators

```java
@MintingValidator
class FactoryPolicy {
    static final byte[] FACTORY_MARKER = "FACTORY_MARKER".getBytes();

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        byte[] ownPolicy = (byte[])(Object) ContextsLib.ownHash(ctx);
        BigInteger qty = ValuesLib.assetOf(txInfo.mint(), ownPolicy, FACTORY_MARKER);
        return qty.equals(BigInteger.ONE);
    }
}
```
