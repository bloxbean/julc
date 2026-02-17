# For-Each Loop Patterns

JuLC compiles `for (var item : list)` loops into recursive folds over Data lists. Since UPLC has no mutable state, the compiler detects variables assigned inside the loop body ("accumulators") and threads them through the fold as functional accumulator parameters.

This document covers every supported pattern, the compilation strategy behind each, and known limitations.

## How It Works

The compiler analyzes each for-each loop body to determine the compilation path:

| Accumulators Detected | Has `break` | Path |
|-----------------------|-------------|------|
| 0 | no | Unit-accumulator fold (side-effect only) |
| 1 | no | Single-accumulator fold |
| 1 | yes | Single-accumulator fold with break |
| 2+ | no | Multi-accumulator tuple fold |
| 2+ | yes | Multi-accumulator tuple fold with break |

An **accumulator** is any variable declared **before** the loop and **assigned inside** it.

## Pattern 1: Side-Effect Loop (No Accumulator)

When the loop body doesn't assign to any pre-loop variable, the compiler uses a unit accumulator. The body is evaluated for side-effects (e.g., trace logging) and the fold returns unit.

```java
for (var sig : txInfo.signatories()) {
    ContextsLib.trace(sig);
}
```

**Compiles to:**
```
LetRec([loop = \xs \acc -> if NullList(xs) then acc else loop(TailList(xs), body)],
       loop(signatories, Unit))
```

## Pattern 2: Single Accumulator (No Break)

The most common pattern. A single pre-loop variable is updated inside the loop. The compiler detects the assignment and threads the variable as a fold accumulator.

### Boolean accumulator

```java
boolean found = false;
for (var sig : txInfo.signatories()) {
    if (sig == redeemer) {
        found = true;
    }
}
return found;
```

### Integer accumulator

```java
BigInteger total = BigInteger.ZERO;
for (var output : txInfo.outputs()) {
    total = total + ValuesLib.lovelaceOf(output.value());
}
return total;
```

### Conditional update

```java
boolean found = false;
for (var sig : txInfo.signatories()) {
    found = found || sig == redeemer;
}
return found;
```

**Compiles to:**
```
LetRec([loop = \xs \acc -> if NullList(xs) then acc
                           else loop(TailList(xs), Let(item, HeadList(xs), bodyExpr))],
       loop(signatories, initAcc))
```

After the loop, the accumulator is rebound to the fold result for use in subsequent statements.

## Pattern 3: Single Accumulator with Break

`break` inside a for-each loop terminates iteration early. The compiler generates a break-aware fold where the loop body decides whether to recurse (continue) or return the accumulator directly (break).

### Assignment + break inside if

```java
boolean found = false;
for (var sig : txInfo.signatories()) {
    if (sig == redeemer) {
        found = true;
        break;
    }
}
return found;
```

### Assignment before if-with-break

The assignment can be a standalone statement before the `if`:

```java
boolean found = false;
for (var sig : txInfo.signatories()) {
    found = sig == redeemer;
    if (found) {
        break;
    }
}
return found;
```

### Integer accumulator with break

```java
BigInteger sum = BigInteger.ZERO;
for (var item : items) {
    sum = sum + item;
    if (sum > BigInteger.valueOf(100)) {
        break;
    }
}
return sum;
```

**Compiles to:**
```
LetRec([loop = \xs \acc -> if NullList(xs) then acc
                           else Let(item, HeadList(xs),
                                  ... if breakCond then accValue    // break: return directly
                                      else loop(TailList(xs), newAcc))], // continue: recurse
       loop(list, initAcc))
```

## Pattern 4: Multiple Accumulators (No Break)

When two or more pre-loop variables are assigned inside the loop, the compiler packs them into a Data list tuple `[encode(v1), encode(v2), ...]`, folds with this single tuple, and unpacks after the loop.

```java
boolean found = false;
BigInteger count = BigInteger.ZERO;
for (var sig : txInfo.signatories()) {
    found = found || sig == redeemer;
    count = count + BigInteger.ONE;
}
return found;
```

**Compiles to:**
```
// Init: pack [BoolToData(false), IData(0)]
accInit = MkCons(ConstrData(0, []), MkCons(IData(0), MkNilData))

// Fold body: unpack, compute, repack
loop = \xs \__acc_tuple ->
  if NullList(xs) then __acc_tuple
  else Let(item, HeadList(xs),
    Let(found, UnConstrData(HeadList(__acc_tuple)),
    Let(count, UnIData(HeadList(TailList(__acc_tuple))),
      ... compute new found, new count ...
      MkCons(encode(found'), MkCons(encode(count'), MkNilData)))))

// After loop: unpack final state
Let(__acc_tuple, loop(sigs, accInit),
  Let(found, decode(HeadList(__acc_tuple)),
  Let(count, decode(HeadList(TailList(__acc_tuple))),
    ... rest of validator ...)))
```

### Encoding/Decoding Types

Each accumulator is encoded to Data and decoded back based on its type:

| Type | Encode | Decode |
|------|--------|--------|
| `BigInteger` | `IData(value)` | `UnIData(data)` |
| `byte[]` | `BData(value)` | `UnBData(data)` |
| `boolean` | `ConstrData(tag, [])` | `FstPair(UnConstrData(data)) == 1` |
| `String` | `BData(EncodeUtf8(value))` | `DecodeUtf8(UnBData(data))` |
| `List<T>` | `ListData(value)` | `UnListData(data)` |
| `Map<K,V>` | `MapData(value)` | `UnMapData(data)` |
| `PlutusData`, records | passthrough | passthrough |

## Pattern 5: Multiple Accumulators with Break

Combines multi-accumulator tuple packing with break-aware fold generation.

```java
boolean found = false;
BigInteger index = BigInteger.ZERO;
for (var sig : txInfo.signatories()) {
    found = sig == redeemer;
    index = index + BigInteger.ONE;
    if (found) {
        break;
    }
}
return found;
```

At `break`, the current accumulator values are packed and returned (no recursion). At body end, they are packed and passed to the continue function (recursion).

## Pattern 6: Nested For-Each Loops

For-each loops can be nested. The compiler saves and restores the accumulator context when entering an inner loop, so each loop operates independently.

```java
BigInteger total = BigInteger.ZERO;
for (var output : txInfo.outputs()) {
    boolean match = false;
    for (var sig : txInfo.signatories()) {
        if (sig == redeemer) {
            match = true;
            break;
        }
    }
    // match is available here from the inner loop
    total = total + BigInteger.ONE;
}
return total == BigInteger.ZERO;
```

The inner loop compiles as a single-accumulator fold with break. The outer loop sees `match` as a local variable and `total` as its own accumulator.

## While Loops

While loops use the same accumulator detection as for-each loops. The compiler analyzes the while body for assignments to pre-loop variables and threads them as functional accumulator parameters through the recursive call.

| Accumulators Detected | Has `break` | Path |
|-----------------------|-------------|------|
| 0 | no | Side-effect only (unit recursion) |
| 1 | no | Single-accumulator recursion |
| 1 | yes | Single-accumulator with break |
| 2+ | no | Multi-accumulator tuple recursion |
| 2+ | yes | Multi-accumulator tuple with break |

### While Pattern 1: Side-Effect Loop (No Accumulator)

When the while body doesn't assign to any pre-loop variable, the compiler uses a unit-based recursion. The body is evaluated for side-effects and the loop returns unit.

```java
while (condition) {
    ContextsLib.trace(someValue);
}
```

**Compiles to:**
```
LetRec([loop = \_ -> if cond then Let(_, body, loop(Unit)) else Unit], loop(Unit))
```

### While Pattern 2: Single Accumulator (No Break)

The most common while loop pattern. A single pre-loop variable is updated inside the loop, and the condition typically references the same variable.

#### Countdown

```java
BigInteger k = BigInteger.valueOf(10);
while (k > BigInteger.ZERO) {
    k = k - BigInteger.ONE;
}
// k is now 0
```

#### Boolean accumulator

```java
boolean done = false;
while (!done) {
    done = true;
}
return done;
```

**Compiles to:**
```
LetRec([loop = \acc -> if cond(acc) then loop(body(acc)) else acc], loop(initAcc))
```

Both `cond` and `body` reference `acc` as a free variable. When the desugarer wraps them in `\acc -> ...`, the variable references bind to the lambda parameter. Each recursive call passes the new accumulator value.

After the loop, the accumulator is rebound to the loop result for use in subsequent statements:

```java
BigInteger k = BigInteger.valueOf(3);
while (k > BigInteger.ZERO) {
    k = k - BigInteger.ONE;
}
BigInteger result = k + BigInteger.valueOf(100);
// result is 100 (k was rebound to 0 after the loop)
```

### While Pattern 3: Single Accumulator with Break

`break` inside a while loop terminates iteration early. The compiler generates a break-aware loop where the body decides whether to recurse (continue) or return the accumulator directly (break).

```java
BigInteger k = BigInteger.valueOf(10);
while (k > BigInteger.ZERO) {
    if (k == BigInteger.valueOf(5)) {
        break;
    }
    k = k - BigInteger.ONE;
}
// k is now 5
```

**Compiles to:**
```
LetRec([loop = \acc -> if cond(acc) then bodyTerm(loop, acc) else acc], loop(initAcc))
```

Where `bodyTerm` can either:
- Call `loop(newAcc)` to continue iterating
- Return `acc` directly to break out of the loop

### While Pattern 4: Multiple Accumulators

When two or more pre-loop variables are assigned inside the while body, the compiler packs them into a Data list tuple (same infrastructure as for-each multi-accumulator).

```java
BigInteger sum = BigInteger.ZERO;
BigInteger k = BigInteger.valueOf(5);
while (k > BigInteger.ZERO) {
    sum = sum + k;
    k = k - BigInteger.ONE;
}
// sum is 15, k is 0
```

The condition is wrapped with unpack logic so it can access individual accumulator values from the tuple. After the loop, the final tuple is unpacked back into the individual variables.

### While Pattern 5: Multiple Accumulators with Break

Combines multi-accumulator tuple packing with break-aware recursion.

```java
BigInteger sum = BigInteger.ZERO;
BigInteger k = BigInteger.valueOf(10);
while (k > BigInteger.ZERO) {
    sum = sum + k;
    if (sum > BigInteger.valueOf(20)) {
        break;
    }
    k = k - BigInteger.ONE;
}
// sum > 20, k stopped early
```

At `break`, the current accumulator values are packed and returned (no recursion). At body end, they are packed and passed to the continue function (recursion).

## For-Each on MapType

When iterating over a `Map<K,V>` variable, the compiler auto-detects the `MapType`, prepends an `UnMapData` to convert to a pair list, and types each element as `PairType`. Use `.key()` and `.value()` to access pair elements:

```java
// Iterate over withdrawals map
BigInteger totalWithdrawn = BigInteger.ZERO;
for (var entry : txInfo.withdrawals()) {
    // entry is a PairType — use .key() and .value()
    byte[] credHash = entry.key();   // auto-decoded
    BigInteger amount = entry.value(); // auto-decoded
    totalWithdrawn = totalWithdrawn + amount;
}
```

## Nested Loop Examples

### While-in-While

```java
BigInteger total = BigInteger.ZERO;
BigInteger i = BigInteger.ZERO;
while (i < BigInteger.valueOf(3)) {
    BigInteger j = BigInteger.ZERO;
    while (j < BigInteger.valueOf(4)) {
        total = total + BigInteger.ONE;
        j = j + BigInteger.ONE;
    }
    i = i + BigInteger.ONE;
}
// total is 12
```

### For-Each-in-For-Each

```java
BigInteger matchCount = BigInteger.ZERO;
for (var output : txInfo.outputs()) {
    for (var sig : txInfo.signatories()) {
        if (output.address().credential() == sig) {
            matchCount = matchCount + BigInteger.ONE;
        }
    }
}
```

### Mixed Nesting (For-Each with While)

```java
boolean found = false;
for (var input : txInfo.inputs()) {
    var pairs = Builtins.unMapData(input.resolved().value());
    PlutusData cursor = pairs;
    while (!Builtins.nullList(cursor)) {
        var pair = Builtins.headList(cursor);
        if (Builtins.equalsData(Builtins.fstPair(pair), targetPolicy)) {
            found = true;
        }
        cursor = Builtins.tailList(cursor);
    }
}
```

Each loop gets a unique counter-based name (`loop__forEach__0`, `loop__while__1`, etc.) to prevent naming collisions. Inner loop accumulators are correctly rebound into the outer loop's scope.

## Supported Accumulator Types

Any type supported by the compiler can be used as a loop accumulator:

- `boolean` — for search/match patterns
- `BigInteger` (and `int`, `long`) — for counters, sums, products
- `byte[]` — for hash accumulation
- `String` — for string building
- `PlutusData` — for opaque data threading
- Records and sealed interfaces — for complex state

## Limitations

| Pattern | Status | Notes |
|---------|--------|-------|
| `for (var x : list)` | Supported | Enhanced for-each only |
| `while (cond) { ... }` | Supported | With accumulator threading |
| `break` in for-each | Supported | Single and multi-accumulator |
| `break` in while | Supported | Single and multi-accumulator |
| `continue` | Not supported | Use conditional logic instead |
| `for (int i = 0; ...)` | Rejected | C-style for loops not allowed |
| `do { } while (cond)` | Rejected | Use `while` instead |
| `break` outside loops | Rejected | Compile-time error |
| Nested loops | Supported | While-in-while, for-each-in-for-each, mixed |
| For-each on MapType | Supported | Elements are PairType with `.key()`/`.value()` |
| Accumulator reassignment outside if | Supported | `acc = expr;` at any statement position |
| Variable declaration inside loop | Supported | Local vars are scoped to the iteration |

### Workaround for `continue`

Instead of `continue`, use an `if` to skip the rest of the body:

```java
// Instead of: if (cond) { continue; }
// Use:
BigInteger sum = BigInteger.ZERO;
for (var item : items) {
    if (!skipCondition) {
        sum = sum + item;
    }
}
```

### Immutability Reminder

Variables in JuLC are immutable. The `acc = expr` syntax inside loops is special — the compiler recognizes it as a fold accumulator update, not a true mutation. Outside of loop bodies, assignment (`x = x + 1`) is not supported.

### Post-Loop Variable Access in Multi-Accumulator Loops (FIXED)

This bug has been fixed. Variables defined before a multi-accumulator while or for-each loop are now correctly accessible after the loop completes. The fix snapshots pre-loop variables via `SymbolTable.allVisibleVariables()` and re-binds them after accumulator unpacking using `rebindPreLoopVars()`.

Previously, the LetRec transformation restructured the variable binding environment, causing outer-scope bindings to be lost. This no longer occurs for either single-accumulator or multi-accumulator loops.

### No `return` Inside Multi-Accumulator Loop Body

The compiler does not support `return` statements inside the body of a multi-accumulator loop. The LetRec transformation wraps the loop body into a fold function, and an early `return` would exit the fold lambda rather than the enclosing method.

**Not supported:**

```java
BigInteger sum = BigInteger.ZERO;
boolean found = false;
for (var item : items) {
    sum = sum + item;
    if (sum > BigInteger.valueOf(100)) {
        found = true;
        return found;  // ERROR: return inside multi-acc loop body
    }
}
```

**Workaround:** Use `break` to exit the loop early, then `return` after the loop:

```java
BigInteger sum = BigInteger.ZERO;
boolean found = false;
for (var item : items) {
    sum = sum + item;
    if (sum > BigInteger.valueOf(100)) {
        found = true;
        break;
    }
}
return found;
```

### Cross-Method Type Inference for Primitives

When a method calls a helper that accepts a `long` parameter, the compiler may generate `EqualsData` instead of `EqualsInteger` for comparisons inside the helper. This happens because at the UPLC level, cross-method values are passed as generic Data, and the compiler does not always recover the primitive type.

**Problem pattern:**

```java
boolean validate(PlutusData datum, PlutusData redeemer) {
    long amount = Builtins.unIData(Builtins.headList(Builtins.constrFields(datum)));
    return checkAmount(amount);
}

static boolean checkAmount(long amount) {
    // May generate EqualsData instead of EqualsInteger
    return amount > 0;
}
```

**Workaround:** Keep primitive comparisons in the same method, or use Data-level equality when crossing method boundaries:

```java
boolean validate(PlutusData datum, PlutusData redeemer) {
    long amount = Builtins.unIData(Builtins.headList(Builtins.constrFields(datum)));
    // Compare directly here — same method, correct type inference
    return amount > 0;
}
```

### `@Param` Fields Must Use `PlutusData` Type

`@Param` values are **always** raw Data at runtime, regardless of the declared type. Using `PlutusData.BytesData` (or `PlutusData.MapData`, etc.) on a `@Param` field tells the compiler the value is already a ByteString, which causes double-wrapping and incorrect cross-library calls.

**Broken pattern:**

```java
@Param PlutusData.BytesData myPolicyId;  // WRONG — compiler thinks it's a ByteString

boolean validate(PlutusData datum, PlutusData redeemer) {
    // Builtins.bData(myPolicyId) double-wraps: bData applied to Data, not ByteString
    byte[] pid = Builtins.unBData(myPolicyId);  // Also fails — unBData on raw Data
    return true;
}
```

**Correct pattern:**

```java
@Param PlutusData myPolicyId;  // CORRECT — raw Data, as it actually is at runtime

boolean validate(PlutusData datum, PlutusData redeemer) {
    byte[] pid = Builtins.unBData(myPolicyId);  // Works — unBData on Data
    return true;
}
```

Always use `@Param PlutusData` for parameterized fields.

### Cross-Library `BytesData`/`MapData` Parameter Bug

When calling a stdlib library method that accepts `BytesData` or `MapData` typed parameters from user code, the compiler may skip the necessary Data encoding at the call boundary. This happens because the compiler sees matching types and assumes no conversion is needed, but compiled libraries always expect raw Data arguments at the UPLC boundary.

**Problem pattern:**

```java
PlutusData.BytesData myPolicy = ...;  // typed as BytesData in user code
// ValuesLib.assetOf expects BytesData, compiler sees matching types, skips encoding
// But at UPLC level, the library expects raw Data -> type mismatch
long amount = ValuesLib.assetOf(value, myPolicy, tokenName);
```

**Workaround:** Use `PlutusData` typed variables (not `BytesData`/`MapData`) when passing arguments to stdlib library methods, so the compiler passes Data as-is:

```java
PlutusData myPolicy = ...;  // typed as PlutusData — compiler passes raw Data
long amount = ValuesLib.assetOf(value, myPolicy, tokenName);  // Works correctly
```

Alternatively, create a local wrapper method in the same project that calls the stdlib method:

```java
// Local wrapper — same compilation unit, no cross-library boundary
static long localAssetOf(PlutusData value, PlutusData policyId, PlutusData tokenName) {
    return ValuesLib.assetOf(value, policyId, tokenName);
}
```

## Comparison with Other Cardano Languages

| Feature | JuLC (Java) | Opshin (Python) | Aiken | Scalus (Scala) |
|---------|-------------|-----------------|-------|----------------|
| For-each | `for (var x : list)` | `for x in list:` | `list.fold(...)` | `list.foldLeft(...)` |
| While with accumulator | Supported | Supported | N/A (no loops) | N/A (no loops) |
| Multi-accumulator | Auto-detected tuple | Auto-detected tuple | Manual tuple | Manual tuple |
| `break` in for-each | Supported | Not supported | N/A | N/A |
| `break` in while | Supported | Not supported | N/A | N/A |
| `continue` | Not supported | Not supported | N/A | N/A |
