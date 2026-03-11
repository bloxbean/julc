# Getting Started with JuLC

Write Cardano smart contracts in Java and compile them to Plutus V3 UPLC.

JuLC compiles a safe subset of Java to Untyped Plutus Lambda Calculus (UPLC), the
on-chain execution language of the Cardano blockchain. You write validators as
ordinary Java classes with records, sealed interfaces, and switch expressions. The
compiler turns them into efficient Plutus scripts that run on the Cardano CEK
machine.

---

## 1. Prerequisites

- **Java 24+** (GraalVM recommended for best performance)
- **Gradle 9+** (or Maven 3.9+)
- Familiarity with Cardano's eUTxO model and the concept of validators, datums,
  and redeemers

---

## 2. Project Setup

> **Snapshot versions**: Snapshot builds include the Git commit hash in the version string
> (e.g. `0.1.0-e0f314e-SNAPSHOT`).
> The snapshot repository configuration below is only needed for snapshot versions available in snapshot repository.

### Build Artifacts
After a successful build, for a Gradle project, a validator-specific *.plutus.json file will be generated under the `build/classes/META-INF/plutus` directory.
This JSON file contains the compiled UPLC script along with other metadata. If you are writing off-chain code in Java, this file will be automatically loaded
by the `JulcScriptLoader.load(VestingValidator.class)` method.

There is a JuLC hello world example with `VestingValidator` at https://github.com/bloxbean/julc-helloworld. You can clone this repository and add your validators
to get started quickly.

### Gradle (annotation processor -- primary approach)

```groovy
plugins {
    id 'java'
}

group = 'com.example'
version = '1.0-SNAPSHOT'

ext {
    julcVersion = '0.1.0-e0f314e-SNAPSHOT'
    cardanoClientLibVersion = '0.7.1'
}

repositories {
    mavenCentral()
    maven {
        url "https://central.sonatype.com/repository/maven-snapshots"
    }
}

dependencies {
    // Core: stdlib + ledger types + annotations
    implementation "com.bloxbean.cardano:julc-stdlib:${julcVersion}"
    implementation "com.bloxbean.cardano:julc-ledger-api:${julcVersion}"

    // Annotation processor -- compiles validators during javac
    annotationProcessor "com.bloxbean.cardano:julc-annotation-processor:${julcVersion}"

    // Runtime -- load pre-compiled scripts from classpath
    implementation "com.bloxbean.cardano:julc-cardano-client-lib:${julcVersion}"
    implementation "com.bloxbean.cardano:cardano-client-lib:${cardanoClientLibVersion}"

    // Test: VM for local evaluation
    testImplementation "com.bloxbean.cardano:julc-testkit:${julcVersion}"
    testImplementation "com.bloxbean.cardano:julc-vm:${julcVersion}"
    testRuntimeOnly "com.bloxbean.cardano:julc-vm-scalus:${julcVersion}"

    testImplementation platform('org.junit:junit-bom:5.10.0')
    testImplementation 'org.junit.jupiter:junit-jupiter'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

test {
    useJUnitPlatform()
}

```

### Maven

```xml
<!-- Required for snapshot versions only -->
<repositories>
    <repository>
        <id>snapshots-repo</id>
        <url>https://central.sonatype.com/repository/maven-snapshots</url>
        <releases>
            <enabled>false</enabled>
        </releases>
        <snapshots>
            <enabled>true</enabled>
        </snapshots>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.bloxbean.cardano</groupId>
        <artifactId>julc-stdlib</artifactId>
        <version>${julc.version}</version>
    </dependency>
    <dependency>
        <groupId>com.bloxbean.cardano</groupId>
        <artifactId>julc-ledger-api</artifactId>
        <version>${julc.version}</version>
    </dependency>
    <dependency>
        <groupId>com.bloxbean.cardano</groupId>
        <artifactId>julc-cardano-client-lib</artifactId>
        <version>${julc.version}</version>
    </dependency>
    <dependency>
        <groupId>com.bloxbean.cardano</groupId>
        <artifactId>cardano-client-lib</artifactId>
        <version>${ccl.version}</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <version>3.13.0</version>
            <configuration>
                <release>24</release>
                <annotationProcessorPaths>
                    <path>
                        <groupId>com.bloxbean.cardano</groupId>
                        <artifactId>julc-annotation-processor</artifactId>
                        <version>${julc.version}</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

### Java version note

JuLC uses sealed interfaces, records, pattern matching, and switch expressions.
These features are fully standardized since Java 21, so no `--enable-preview` flag
is needed with Java 24+.

---

## 3. Your First Validator

### 3.1 Spending Validator

A spending validator guards UTxOs locked at a script address. It receives a datum
(the data stored with the UTxO), a redeemer (the data the spender provides), and
the full `ScriptContext`.

```java
package com.example;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.PubKeyHash;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.TxInfo;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;

import java.math.BigInteger;

@SpendingValidator
public class VestingValidator {

    record VestingDatum(byte[] beneficiary, BigInteger deadline) {}

    @Entrypoint
    static boolean validate(VestingDatum datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();

        // Check that the beneficiary signed the transaction
        boolean signed = txInfo.signatories().contains(PubKeyHash.of(datum.beneficiary()));

        // Check that the deadline has passed (lower bound of valid range > deadline)
        // Just a dummy check to demonstrate using the datum's deadline field.
        boolean pastDeadline = datum.deadline().compareTo(BigInteger.ZERO) > 0;

        return signed && pastDeadline;
    }
}

```

Key points:

- `@SpendingValidator` marks the class as a spending validator.
- `@Entrypoint` marks the single validation method. It must be `static` and return
  `boolean`.
- The method signature is `(DatumType, RedeemerType, ScriptContext)` for spending
  validators.
- `ctx.txInfo()` gives you typed access to all 16 fields of the V3 `TxInfo`.
- `txInfo.signatories()` returns a list of `PubKeyHash` that can be iterated or
  searched with `.contains()`.
- Records like `VestingDatum` compile to Plutus `ConstrData`. Field access
  (`.beneficiary()`, `.deadline()`) compiles to efficient Data navigation with
  automatic type unwrapping (`UnBData` for `byte[]`, `UnIData` for `BigInteger`).

### 3.2 Minting Validator with Sealed Interface Redeemer

A minting validator controls which tokens can be minted or burned under a given
policy. It receives a redeemer and the `ScriptContext`.

```java
package com.example;

import com.bloxbean.cardano.julc.stdlib.annotation.MintingValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.TxInfo;
import com.bloxbean.cardano.julc.core.PlutusData;
import java.math.BigInteger;

@MintingValidator
public class TokenPolicy {

    sealed interface Action {
        record Mint(BigInteger amount) implements Action {}
        record Burn() implements Action {}
    }

    @Entrypoint
    static boolean validate(Action redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();

        return switch (redeemer) {
            case Action.Mint m -> {
                // Only mint positive amounts, and only if authorized
                boolean positive = m.amount().compareTo(BigInteger.ZERO) > 0;
                boolean signed = txInfo.signatories().contains(
                    new byte[]{/* authority pkh */});
                yield positive && signed;
            }
            case Action.Burn b -> {
                // Anyone can burn their own tokens
                yield true;
            }
        };
    }
}
```

The sealed interface `Action` compiles to a Plutus `SumType`. The `switch`
expression with pattern matching compiles to Data tag inspection with automatic
field extraction. The compiler checks exhaustiveness -- every case of the sealed
interface must be handled.

---

## 4. Conway Validator Types

JuLC supports all six Plutus V3 (Conway era) validator types through purpose-
specific annotations:

| Annotation | Purpose | Entrypoint Parameters |
|---|---|---|
| `@SpendingValidator` | Guards spending UTxOs from a script address | `(datum, redeemer, ctx)` or `(redeemer, ctx)` |
| `@MintingValidator` | Controls minting/burning of native tokens | `(redeemer, ctx)` |
| `@WithdrawValidator` | Authorizes staking reward withdrawals | `(redeemer, ctx)` |
| `@CertifyingValidator` | Authorizes delegation certificate operations | `(redeemer, ctx)` |
| `@VotingValidator` | Authorizes governance votes (DRep) | `(redeemer, ctx)` |
| `@ProposingValidator` | Authorizes governance proposals | `(redeemer, ctx)` |

All annotations live in `com.bloxbean.cardano.julc.stdlib.annotation`.

> **Deprecation note**: The old `@Validator` and `@MintingPolicy` annotations
> still compile but are deprecated. Use `@SpendingValidator` and
> `@MintingValidator` for all new code.

### 4.1 Multi-Validators (@MultiValidator)

When a single compiled script needs to handle multiple purposes (e.g. mint **and**
spend), annotate the class with `@MultiValidator` instead of a single-purpose
annotation. This produces one on-chain script that dispatches on `ScriptInfo` at
runtime.

**Two modes:**

| Mode | How it works |
|------|-------------|
| **Auto-dispatch** | Multiple `@Entrypoint` methods, each with an explicit `Purpose`. The compiler generates a `ScriptInfo` tag dispatch automatically. |
| **Manual dispatch** | A single `@Entrypoint` method with `Purpose.DEFAULT`. You switch on `ctx.scriptInfo()` yourself. |

**Purpose enum values:**

| Purpose | ScriptInfo tag | ScriptInfo variant |
|---------|---------------|-------------------|
| `MINT` | 0 | `MintingScript` |
| `SPEND` | 1 | `SpendingScript` |
| `WITHDRAW` | 2 | `RewardingScript` |
| `CERTIFY` | 3 | `CertifyingScript` |
| `VOTE` | 4 | `VotingScript` |
| `PROPOSE` | 5 | `ProposingScript` |
| `DEFAULT` | — | Manual dispatch (no auto-dispatch) |

**Entrypoint parameter rules:**

| Purpose | Parameters |
|---------|-----------|
| `SPEND` | 2 params `(redeemer, ctx)` or 3 params `(datum, redeemer, ctx)` — datum is `Optional<PlutusData>` or a record type |
| All others | 2 params `(redeemer, ctx)` |

#### Auto-dispatch example

```java
@MultiValidator
public class TokenManager {

    @Entrypoint(purpose = Purpose.MINT)
    static boolean mint(PlutusData redeemer, ScriptContext ctx) {
        // Minting logic
        return !ctx.txInfo().signatories().isEmpty();
    }

    @Entrypoint(purpose = Purpose.SPEND)
    static boolean spend(PlutusData redeemer, ScriptContext ctx) {
        // Spending logic (2-param — no datum)
        return true;
    }
}
```

The compiler generates ScriptInfo tag dispatch that routes `MintingScript` to
`mint()` and `SpendingScript` to `spend()`. Unhandled purposes cause a script
Error at runtime.

#### Auto-dispatch with datum (3-param SPEND)

```java
@MultiValidator
public class TokenManagerWithDatum {

    @Entrypoint(purpose = Purpose.MINT)
    static boolean mint(PlutusData redeemer, ScriptContext ctx) {
        return true;
    }

    @Entrypoint(purpose = Purpose.SPEND)
    static boolean spend(Optional<PlutusData> datum, PlutusData redeemer, ScriptContext ctx) {
        // datum is Optional — Some when present, None when absent
        return datum.isPresent();
    }
}
```

#### Manual dispatch example

```java
@MultiValidator
public class ManualRouter {

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        return switch (ctx.scriptInfo()) {
            case ScriptInfo.MintingScript m -> handleMint(redeemer, ctx);
            case ScriptInfo.SpendingScript s -> handleSpend(redeemer, ctx);
            case ScriptInfo.RewardingScript r -> handleWithdraw(redeemer, ctx);
            // Must cover all ScriptInfo variants you handle
        };
    }

    static boolean handleMint(PlutusData redeemer, ScriptContext ctx) { return true; }
    static boolean handleSpend(PlutusData redeemer, ScriptContext ctx) { return true; }
    static boolean handleWithdraw(PlutusData redeemer, ScriptContext ctx) { return true; }
}
```

**Validation rules:**
- Do not mix `Purpose.DEFAULT` with explicit purposes — use one mode or the other
- No duplicate purposes (two `@Entrypoint` methods with the same `Purpose`)
- Do not combine `@MultiValidator` with single-purpose annotations (`@SpendingValidator`, etc.)

---

## 5. Data Modeling

### 5.1 Records

Java records compile to Plutus `ConstrData` types. Field access compiles to
efficient Data navigation.

```java
record Payment(byte[] recipient, BigInteger amount) {}

// Construction: compiles to ConstrData(0, [BData(recipient), IData(amount)])
var p = new Payment(recipientBytes, BigInteger.valueOf(1000000));

// Field access: compiles to HeadList/TailList chains + type unwrapping
byte[] who = p.recipient();     // UnBData(HeadList(SndPair(UnConstrData(p))))
BigInteger how = p.amount();    // UnIData(HeadList(TailList(SndPair(UnConstrData(p)))))
```

Records can be nested:

```java
record Proposal(Payment payment, BigInteger votesNeeded) {}

// Access nested fields
byte[] target = proposal.payment().recipient();
```

### 5.2 Sealed Interfaces

Sealed interfaces compile to Plutus `SumType` (tagged union). Each permitted
record variant gets a constructor tag starting from 0.

```java
sealed interface Shape {
    record Circle(BigInteger radius) implements Shape {}       // tag 0
    record Rectangle(BigInteger w, BigInteger h) implements Shape {}  // tag 1
}
```

Use `switch` expressions for exhaustive pattern matching:

```java
BigInteger area = switch (shape) {
    case Shape.Circle c -> c.radius().multiply(c.radius()).multiply(BigInteger.valueOf(3));
    case Shape.Rectangle r -> r.w().multiply(r.h());
};
```

The compiler checks exhaustiveness: if you omit a case for any variant, you get a
compile error listing the missing cases.

You can also use `instanceof` for type-checking:

```java
if (shape instanceof Shape.Circle c) {
    // c is bound and typed as Circle
    BigInteger r = c.radius();
}
```

### 5.3 @NewType

The `@NewType` annotation creates a zero-cost type alias for a single-field record.
On-chain, the constructor and `.of()` factory method compile to identity (no
`ConstrData` wrapping).

```java
import com.bloxbean.cardano.julc.stdlib.annotation.NewType;

@NewType
record AssetId(byte[] hash) {}

// AssetId.of(bytes) compiles to identity -- no ConstrData overhead
AssetId id = AssetId.of(someBytes);
```

`@NewType` is `@Retention(SOURCE)`, `@Target(TYPE)`. The single field must be one
of the supported primitive types:

- `byte[]` (compiles to `ByteStringType`)
- `BigInteger` (compiles to `IntegerType`)
- `String` (compiles to `TextType`)
- `boolean` (compiles to `BoolType`)

Multi-field records or unsupported field types produce a compiler error.

### 5.4 Tuple2 and Tuple3

Generic tuples are provided in `com.bloxbean.cardano.julc.core.types`:

```java
import com.bloxbean.cardano.julc.core.types.Tuple2;
import com.bloxbean.cardano.julc.core.types.Tuple3;

// Generic type parameters enable auto-unwrap on field access
Tuple2<BigInteger, byte[]> pair = new Tuple2<>(someInt, someBytes);
BigInteger first = pair.first();   // auto-generates UnIData
byte[] second = pair.second();     // auto-generates UnBData

Tuple3<BigInteger, byte[], BigInteger> triple = new Tuple3<>(a, b, c);
BigInteger third = triple.third(); // auto-generates UnIData
```

On-chain, tuples compile to `ConstrData(0, [first, second, ...])`. With type
arguments, field access auto-unwraps based on the generic type (`BigInteger` yields
`UnIData`, `byte[]` yields `UnBData`).

Raw `Tuple2` or `Tuple3` (without type arguments) defaults to `DataType` for
backward compatibility -- no auto-unwrap occurs.

**Note**: Tuple2/Tuple3 cannot be used in `switch` expressions because they are
registered as `RecordType`, not `SumType`. Use `.first()` and `.second()` field
access instead of pattern matching.

### 5.5 Type.of() Factories

Seven ledger hash types provide `.of(byte[])` factory methods that compile to
identity on-chain:

| Type | Factory |
|---|---|
| `PubKeyHash` | `PubKeyHash.of(bytes)` |
| `ScriptHash` | `ScriptHash.of(bytes)` |
| `ValidatorHash` | `ValidatorHash.of(bytes)` |
| `PolicyId` | `PolicyId.of(bytes)` |
| `TokenName` | `TokenName.of(bytes)` |
| `DatumHash` | `DatumHash.of(bytes)` |
| `TxId` | `TxId.of(bytes)` |

These replace the older `(PubKeyHash)(Object) bytes` cast pattern:

```java
// Old pattern (still works but ugly)
PubKeyHash pkh = (PubKeyHash)(Object) beneficiaryBytes;

// New pattern (recommended)
PubKeyHash pkh = PubKeyHash.of(beneficiaryBytes);
```

On-chain, `.of()` is identity (the bytes pass through). Off-chain, it delegates to
the record constructor with validation (e.g., `PubKeyHash` checks for exactly 28
bytes).

---

## 6. Collections

### 6.1 Lists

Lists (typed as `List<T>` or `JulcList<T>`) support the following instance methods:

| Method | Return Type | Description |
|---|---|---|
| `list.isEmpty()` | `boolean` | True if the list has no elements |
| `list.size()` | `long` | Number of elements |
| `list.head()` | `T` | First element (error if empty) |
| `list.tail()` | `List<T>` | All elements except the first |
| `list.get(index)` | `T` | Element at 0-based index |
| `list.contains(target)` | `boolean` | True if target is in the list (via EqualsData) |
| `list.reverse()` | `List<T>` | Reversed copy |
| `list.concat(other)` | `List<T>` | Concatenation of two lists |
| `list.take(n)` | `List<T>` | First n elements |
| `list.drop(n)` | `List<T>` | All elements after the first n |
| `list.prepend(elem)` | `List<T>` | New list with elem at the front |
| `list.map(x -> f(x))` | `JulcList<PlutusData>` | Apply function to each element |
| `list.filter(x -> pred(x))` | `JulcList<T>` | Keep elements matching predicate |
| `list.any(x -> pred(x))` | `boolean` | True if any element matches |
| `list.all(x -> pred(x))` | `boolean` | True if all elements match |
| `list.find(x -> pred(x))` | `T` | First matching element (error if none) |

The `prepend` method auto-wraps the element: if you prepend a `BigInteger`, it is
automatically wrapped with `IData`; a `byte[]` is wrapped with `BData`.

The `map` method wraps each lambda result to `Data`, so the returned list has
`PlutusData` elements regardless of input type. To extract typed values from a
mapped list, use `Builtins.unIData()` or `Builtins.unBData()` on each element.

```java
// Iterate a list with for-each
for (TxOut out : ctx.txInfo().outputs()) {
    // out is typed as TxOut with full field access
    Value v = out.value();
}
```

### 6.2 Maps

Maps (typed as `Map<K,V>` or `JulcMap<K,V>`) are association lists on-chain. They
support the following instance methods:

| Method | Return Type | Description |
|---|---|---|
| `map.get(key)` | `V` | Value associated with key (or error) |
| `map.containsKey(key)` | `boolean` | True if key exists |
| `map.size()` | `long` | Number of entries |
| `map.isEmpty()` | `boolean` | True if map has no entries |
| `map.keys()` | `List<K>` | All keys as a list |
| `map.values()` | `List<V>` | All values as a list |
| `map.insert(k, v)` | `Map<K,V>` | New map with entry added (shadows existing) |
| `map.delete(k)` | `Map<K,V>` | New map with key removed |

Internally, `MapType` variables always hold pair lists (not `MapData`-wrapped
values). The `insert` and `delete` operations return pair lists.

Iterating over a map with `for-each` yields pairs:

```java
// For-each on a map yields PairType entries with .key() and .value()
for (var entry : ctx.txInfo().withdrawals()) {
    Credential cred = entry.key();
    BigInteger amount = entry.value();
}
```

### 6.3 Optional

`Optional<T>` is supported for fields like `TxOut.referenceScript()` and
`ScriptInfo.SpendingScript.datum()`:

| Method | Description |
|---|---|
| `opt.isPresent()` | True if a value is present |
| `opt.isEmpty()` | True if no value is present |
| `opt.get()` | The contained value (error if empty) |

On-chain, `Optional` compiles to `Constr(0, [value])` for `Some` and
`Constr(1, [])` for `None`.

---

## 7. Typed Ledger Access

### 7.1 ScriptContext / TxInfo / TxOut

The Plutus V3 ScriptContext gives typed access to all transaction fields.

**ScriptContext fields:**

| Field | Type | Description |
|---|---|---|
| `ctx.txInfo()` | `TxInfo` | The transaction information |
| `ctx.redeemer()` | `PlutusData` | The redeemer provided by the spender |
| `ctx.scriptInfo()` | `ScriptInfo` | Information about the executing script |

**TxInfo fields (all 16):**

| Field | Type | Description |
|---|---|---|
| `txInfo.inputs()` | `JulcList<TxInInfo>` | Inputs being consumed |
| `txInfo.referenceInputs()` | `JulcList<TxInInfo>` | Reference inputs (read-only) |
| `txInfo.outputs()` | `JulcList<TxOut>` | Transaction outputs |
| `txInfo.fee()` | `BigInteger` | Transaction fee in lovelace |
| `txInfo.mint()` | `Value` | Minted/burned tokens |
| `txInfo.certificates()` | `JulcList<TxCert>` | Delegation certificates |
| `txInfo.withdrawals()` | `JulcMap<Credential, BigInteger>` | Staking withdrawals |
| `txInfo.validRange()` | `Interval` | Validity time range |
| `txInfo.signatories()` | `JulcList<PubKeyHash>` | Transaction signers |
| `txInfo.redeemers()` | `JulcMap<ScriptPurpose, PlutusData>` | All redeemers |
| `txInfo.datums()` | `JulcMap<DatumHash, PlutusData>` | Datum witness table |
| `txInfo.id()` | `TxId` | Transaction hash |
| `txInfo.votes()` | `JulcMap<Voter, JulcMap<GovernanceActionId, Vote>>` | Governance votes |
| `txInfo.proposalProcedures()` | `JulcList<ProposalProcedure>` | Governance proposals |
| `txInfo.currentTreasuryAmount()` | `Optional<BigInteger>` | Current treasury |
| `txInfo.treasuryDonation()` | `Optional<BigInteger>` | Treasury donation |

**TxOut fields:**

| Field | Type | Description |
|---|---|---|
| `txOut.address()` | `Address` | Destination address |
| `txOut.value()` | `Value` | The value carried |
| `txOut.datum()` | `OutputDatum` | Attached datum (None, Hash, or Inline) |
| `txOut.referenceScript()` | `Optional<ScriptHash>` | Optional reference script |

### 7.2 Value

A `Value` represents a multi-asset value: `Map<PolicyId, Map<TokenName, BigInteger>>`.

Instance methods available on-chain:

| Method | Return Type | Description |
|---|---|---|
| `value.lovelaceOf()` | `BigInteger` | ADA amount (in lovelace) |
| `value.isEmpty()` | `boolean` | True if value has no entries |
| `value.assetOf(policy, token)` | `BigInteger` | Amount of a specific token |

**Caveat**: `Value.assetOf()` uses `EqualsData` internally. If you pass `byte[]`
arguments for policy/token, they must be wrapped with `Builtins.bData()` first.
Otherwise `EqualsData(BData(...), ByteString(...))` fails at runtime:

```java
// Correct: wrap byte[] args with bData
BigInteger amount = value.assetOf(
    Builtins.bData(policyIdBytes),
    Builtins.bData(tokenNameBytes));

// Or use ValuesLib.assetOf which handles wrapping for you:
BigInteger amount = ValuesLib.assetOf(value, policyIdBytes, tokenNameBytes);
```

### 7.3 ScriptInfo

`ScriptInfo` is a sealed interface describing the currently executing script:

| Variant | Fields | Constructor Tag |
|---|---|---|
| `MintingScript` | `policyId: PolicyId` | 0 |
| `SpendingScript` | `txOutRef: TxOutRef, datum: Optional<PlutusData>` | 1 |
| `RewardingScript` | `credential: Credential` | 2 |
| `CertifyingScript` | `index: BigInteger, cert: TxCert` | 3 |
| `VotingScript` | `voter: Voter` | 4 |
| `ProposingScript` | `index: BigInteger, procedure: ProposalProcedure` | 5 |

Use switch to dispatch:

```java
return switch (ctx.scriptInfo()) {
    case ScriptInfo.MintingScript ms -> handleMint(ms.policyId());
    case ScriptInfo.SpendingScript ss -> handleSpend(ss.txOutRef());
    case ScriptInfo.RewardingScript rs -> handleReward(rs.credential());
    case ScriptInfo.CertifyingScript cs -> handleCert(cs.cert());
    case ScriptInfo.VotingScript vs -> handleVote(vs.voter());
    case ScriptInfo.ProposingScript ps -> handlePropose(ps.procedure());
};
```

### 7.4 Address and Credential

`Address` is a record with a payment credential and an optional staking credential:

```java
// Address fields
Credential paymentCred = address.credential();
Optional<StakingCredential> stakingCred = address.stakingCredential();
```

`Credential` is a sealed interface with two variants:

| Variant | Fields | Tag |
|---|---|---|
| `PubKeyCredential` | `hash: PubKeyHash` | 0 |
| `ScriptCredential` | `hash: ScriptHash` | 1 |

```java
return switch (address.credential()) {
    case Credential.PubKeyCredential pk -> {
        byte[] pkh = (byte[])(Object) pk.hash();
        yield checkSigner(pkh);
    }
    case Credential.ScriptCredential sc -> {
        byte[] sh = (byte[])(Object) sc.hash();
        yield checkScript(sh);
    }
};
```

---

## 8. Control Flow

### 8.1 If/Else and Ternary

Standard if/else compiles to Plutus `IfThenElse`:

```java
if (amount.compareTo(BigInteger.ZERO) > 0) {
    return true;
} else {
    return false;
}

// Ternary also works
boolean valid = amount.compareTo(BigInteger.ZERO) > 0 ? true : false;
```

Both branches must be present -- an `if` without `else` is supported only as a
statement (not an expression).

### 8.2 Switch Expressions

Switch expressions on sealed interfaces compile to Data tag inspection with
automatic field extraction:

```java
BigInteger result = switch (action) {
    case Action.Mint m -> m.amount();
    case Action.Burn b -> BigInteger.ZERO;
};
```

The compiler checks exhaustiveness: if you omit a case, you get a compile error
listing the missing variants.

**Known limitation**: The `default ->` branch body is never compiled. Use explicit
cases for all variants instead.

### 8.3 instanceof Pattern Matching

```java
if (datum instanceof OutputDatum.OutputDatumInline inline) {
    PlutusData d = inline.datum();
    // use d
}
```

### 8.4 For-Each Loops

For-each loops over lists are desugared into tail-recursive functions with
accumulators. The loop body can update one or more accumulator variables.

```java
// Single accumulator
long count = 0;
for (TxOut out : ctx.txInfo().outputs()) {
    count = count + 1;
}

// Multi-accumulator
long total = 0;
boolean found = false;
for (TxInInfo input : ctx.txInfo().inputs()) {
    total = total + 1;
    if (someCondition(input)) {
        found = true;
    } else {
        found = found;
    }
}

// Break: set the cursor to empty list to exit early
boolean exists = false;
for (PubKeyHash sig : txInfo.signatories()) {
    if (Builtins.equalsByteString(sig.hash(), targetPkh)) {
        exists = true;
    } else {
        exists = exists;
    }
}
```

For full loop patterns, accumulator rules, and examples, see
[for-loop-patterns.md](for-loop-patterns.md).

### 8.5 While Loops

While loops also desugar to tail-recursive functions:

```java
var current = list;
long count = 0;
while (!Builtins.nullList(current)) {
    count = count + 1;
    current = Builtins.tailList(current);
}
```

For details, see [for-loop-patterns.md](for-loop-patterns.md).

### 8.6 Nested Loops

Nested loops are supported: while-in-while, for-each-in-for-each, and mixed
nesting all work. Each loop gets a unique LetRec name, and inner-loop results are
correctly rebound into outer-loop accumulators.

```java
long totalOutputs = 0;
for (TxInInfo input : ctx.txInfo().inputs()) {
    long innerCount = 0;
    for (TxOut out : ctx.txInfo().outputs()) {
        innerCount = innerCount + 1;
    }
    totalOutputs = totalOutputs + innerCount;
}
```

### 8.7 What Does Not Work

- **No `continue` statement** -- every branch must assign all accumulators
- **No C-style `for(init; cond; step)`** -- use `while` or for-each
- **No `do-while`** -- use `while` with an initial check
- **No `return` inside multi-accumulator loop body** -- the loop must complete
  naturally; use a boolean accumulator for early-exit logic

---

## 9. Standard Library

All standard library classes live in `com.bloxbean.cardano.julc.stdlib.lib` and are
annotated with `@OnchainLibrary`. They are automatically discovered and compiled
when your validator references them.

| Library | Description |
|---|---|
| `ContextsLib` | ScriptContext/TxInfo field accessors, `signedBy`, `findOwnInput`, `getContinuingOutputs`, `findDatum`, `ownHash`, `trace` |
| `ListsLib` | `empty`, `prepend`, `length`, `isEmpty`, `head`, `tail`, `reverse`, `concat`, `nth`, `take`, `drop`, `contains`, `containsInt`, `containsBytes`, `hasDuplicateInts`, `hasDuplicateBytes` + PIR HOFs (`any`, `all`, `find`, `foldl`, `map`, `filter`, `zip`) |
| `ValuesLib` | `lovelaceOf`, `assetOf`, `containsPolicy`, `geq`, `geqMultiAsset`, `leq`, `eq`, `isZero`, `singleton`, `negate`, `flatten`, `flattenTyped`, `add`, `subtract`, `countTokensWithQty`, `findTokenName` |
| `MapLib` | `lookup`, `member`, `insert`, `delete`, `keys`, `values`, `toList`, `fromList`, `size` |
| `OutputLib` | `txOutAddress`, `txOutValue`, `txOutDatum`, `outputsAt`, `countOutputsAt`, `uniqueOutputAt`, `outputsWithToken`, `valueHasToken`, `lovelacePaidTo`, `paidAtLeast`, `getInlineDatum`, `resolveDatum`, `findOutputWithToken`, `findInputWithToken` |
| `MathLib` | `abs`, `max`, `min`, `divMod`, `quotRem`, `pow`, `sign`, `expMod` |
| `IntervalLib` | `contains`, `always`, `after`, `before`, `between`, `never`, `isEmpty`, `finiteUpperBound`, `finiteLowerBound` |
| `CryptoLib` | `sha2_256`, `blake2b_256`, `sha3_256`, `blake2b_224`, `keccak_256`, `verifyEd25519Signature`, `verifyEcdsaSecp256k1`, `verifySchnorrSecp256k1`, `ripemd_160` (all hash functions also available via `Builtins.*`) |
| `ByteStringLib` | `at`, `cons`, `slice`, `length`, `drop`, `take`, `append`, `empty`, `zeros`, `equals`, `lessThan`, `lessThanEquals`, `integerToByteString`, `byteStringToInteger`, `encodeUtf8`, `decodeUtf8`, `serialiseData`, `hexNibble`, `toHex`, `intToDecimalString`, `utf8ToInteger` |
| `BitwiseLib` | `andByteString`, `orByteString`, `xorByteString`, `complementByteString`, `readBit`, `writeBits`, `shiftByteString`, `rotateByteString`, `countSetBits`, `findFirstSetBit` |
| `AddressLib` | `credentialHash`, `isScriptAddress`, `isPubKeyAddress`, `paymentCredential` |

For full method signatures and usage examples, see [stdlib-guide.md](stdlib-guide.md).

---

## 10. User Libraries (@OnchainLibrary)

You can write your own on-chain libraries that are auto-discovered by the compiler.

### Creating a library

```java
package com.example.lib;

import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import java.math.BigInteger;

@OnchainLibrary
public class MyLib {

    public static BigInteger doubleAmount(BigInteger x) {
        return x.add(x);
    }

    public static boolean isPositive(BigInteger x) {
        return x.compareTo(BigInteger.ZERO) > 0;
    }
}
```

### Using a library from a validator

```java
@SpendingValidator
public class MyValidator {
    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        BigInteger doubled = MyLib.doubleAmount(BigInteger.valueOf(21));
        return MyLib.isPositive(doubled);
    }
}
```

### Static field initializers

Static fields with initializers in `@OnchainLibrary` classes compile as `Let`
bindings:

```java
@OnchainLibrary
public class Constants {
    static BigInteger THRESHOLD = BigInteger.valueOf(1000000);

    public static boolean meetsThreshold(BigInteger amount) {
        return amount.compareTo(THRESHOLD) >= 0;
    }
}
```

### Cross-library calls

Libraries can call other libraries. The compiler resolves dependencies
transitively. When publishing a library as a JAR, the Gradle plugin bundles the
Java source under `META-INF/plutus-sources/` so consumers can auto-discover and
compile it.

For full details, see [library-developer-guide.md](library-developer-guide.md).

---

## 11. Parameterized Validators (@Param)

Parameterized validators have fields that are "baked in" at deploy time via UPLC
partial application. Each unique set of parameter values produces a different script
hash/address.

```java
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.core.PlutusData;
import java.math.BigInteger;

@SpendingValidator
public class ParameterizedVesting {

    @Param PlutusData beneficiary;
    @Param PlutusData deadline;

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        return ctx.txInfo().signatories().contains(beneficiary);
    }
}
```

**Critical**: `@Param` fields must always use `PlutusData` as their type, never
`PlutusData.BytesData`, `byte[]`, or other specific types. `@Param` values are
always raw `Data` at runtime, and using a specific type causes the compiler to
generate incorrect conversion code.

Parameters are applied in declaration order when loading:

```java
PlutusV3Script script = JulcScriptLoader.load(ParameterizedVesting.class,
    BytesPlutusData.of(ownerPkh),       // beneficiary
    BigIntPlutusData.of(deadlineMs));    // deadline
```

---

## 12. Lambda Expressions and HOFs

The standard library provides higher-order functions that accept lambda expressions.
These are registered as PIR-level functions in the `StdlibRegistry`.

### ListsLib HOFs

```java
import com.bloxbean.cardano.julc.stdlib.lib.ListsLib;

// map: transform each element
var doubled = ListsLib.map(amounts, x -> x.multiply(BigInteger.TWO));

// filter: keep elements matching a predicate
var positives = ListsLib.filter(amounts, x -> x.compareTo(BigInteger.ZERO) > 0);

// foldl: left fold with accumulator
BigInteger sum = ListsLib.foldl(amounts, BigInteger.ZERO,
    (acc, x) -> acc.add(x));

// any: true if any element matches
boolean hasLarge = ListsLib.any(amounts, x -> x.compareTo(BigInteger.valueOf(1000)) > 0);

// all: true if all elements match
boolean allPositive = ListsLib.all(amounts, x -> x.compareTo(BigInteger.ZERO) > 0);

// find: return first matching element (as Optional-encoded Data)
PlutusData found = ListsLib.find(items, x -> someCondition(x));

// zip: combine two lists pairwise
var zipped = ListsLib.zip(listA, listB);
```

### Instance HOF methods

Lists also support HOF methods as instance calls. Lambda parameter types are
auto-inferred from the list element type:

```java
// Instance methods -- equivalent to the static calls above
var doubled = amounts.map(x -> x.multiply(BigInteger.TWO));
var positives = amounts.filter(x -> x.compareTo(BigInteger.ZERO) > 0);
boolean hasLarge = amounts.any(x -> x.compareTo(BigInteger.valueOf(1000)) > 0);
boolean allPositive = amounts.all(x -> x.compareTo(BigInteger.ZERO) > 0);

// Chaining is supported
var result = outputs.filter(out -> someCondition(out)).map(out -> transform(out));

// Block-body lambdas work too
var processed = items.map(item -> {
    BigInteger doubled = item.multiply(BigInteger.TWO);
    return doubled.add(BigInteger.ONE);
});
```

`foldl` is only available as a static call (`ListsLib.foldl`) because it takes
two lambda parameters plus an initial value.

### Lambda syntax

Single-expression lambdas:

```java
x -> x.add(BigInteger.ONE)
```

Multi-statement lambdas (must have an explicit `return`):

```java
(acc, x) -> {
    BigInteger doubled = x.multiply(BigInteger.TWO);
    return acc.add(doubled);
}
```

**Note**: Lambda `.apply()` is not supported -- you cannot store a lambda in a
variable and call it later. Lambdas can only be passed directly to HOF methods.

**Note**: Instance HOFs work on lists of `ByteStringType`-mapped types
(`JulcList<PubKeyHash>`, `JulcList<ScriptHash>`, etc.) with both untyped and
explicitly-typed lambdas. The compiler automatically avoids double-unwrapping:

```java
// Both styles work:
signatories.any(sig -> Builtins.equalsByteString((byte[])(Object) sig.hash(), targetPkh));
signatories.any((PubKeyHash sig) -> Builtins.equalsByteString((byte[])(Object) sig.hash(), targetPkh));
```

---

## 13. Compiling

### JulcCompiler (programmatic)

```java
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;

// With stdlib support (recommended)
var stdlib = StdlibRegistry.defaultRegistry();
var compiler = new JulcCompiler(stdlib::lookup);

CompileResult result = compiler.compile(javaSource);
if (result.hasErrors()) {
    System.err.println("Errors: " + result.diagnostics());
} else {
    var program = result.program();
    System.out.println("Script size: " + result.scriptSizeFormatted());
}
```

### Annotation Processor (primary approach)

The annotation processor compiles validators during `javac`. Add it as shown in
Section 2. The processor:

1. Finds classes annotated with `@SpendingValidator`, `@MintingValidator`, `@MultiValidator`, etc.
2. Reads the source file via the compiler's `Trees` API
3. Compiles to UPLC, FLAT-encodes, and double-CBOR-wraps
4. Writes to `META-INF/plutus/<ClassName>.plutus.json`

The compiled script ends up on the classpath alongside `.class` files and can be
loaded at runtime with `JulcScriptLoader`.

### Gradle Plugin

For projects that prefer separate validator source files, apply the Gradle plugin:

```groovy
plugins {
    id 'com.bloxbean.cardano.julc' version '0.1.0-SNAPSHOT'
}
```

Validators in `src/main/plutus/` are compiled during `gradle build` and output to
`build/plutus/`.

---

## 14. Testing

The `julc-testkit` module provides utilities for compiling and evaluating validators
locally without a blockchain.

### ValidatorTest

```java
import com.bloxbean.cardano.julc.testkit.ValidatorTest;
import com.bloxbean.cardano.julc.testkit.BudgetAssertions;
import com.bloxbean.cardano.julc.core.PlutusData;

// Compile from source string
var program = ValidatorTest.compile(javaSource);

// Compile with stdlib
var stdlib = StdlibRegistry.defaultRegistry();
var program = ValidatorTest.compile(javaSource, stdlib::lookup);

// Compile a validator class with auto-discovered dependencies
var result = ValidatorTest.compileValidator(MyValidator.class);

// Evaluate
var evalResult = ValidatorTest.evaluate(program, datum, redeemer, ctx);

// Assert
ValidatorTest.assertValidates(program, datum, redeemer, ctx);
ValidatorTest.assertRejects(program, datum, redeemer, ctx);
```

### ScriptContextTestBuilder

The `ScriptContextTestBuilder` provides a fluent API for constructing test
ScriptContexts:

```java
import com.bloxbean.cardano.julc.testkit.ScriptContextTestBuilder;
import com.bloxbean.cardano.julc.ledger.*;

var ref = new TxOutRef(TxId.of(txHashBytes), BigInteger.ZERO);
var ctx = ScriptContextTestBuilder.spending(ref)
    .signer(PubKeyHash.of(pkhBytes))
    .input(new TxInInfo(ref, new TxOut(address, value, datum, Optional.empty())))
    .output(new TxOut(destAddress, destValue, new OutputDatum.NoOutputDatum(), Optional.empty()))
    .fee(BigInteger.valueOf(200_000))
    .buildPlutusData();
```

The builder supports three output modes:

- `.build()` -- returns a ledger-api `ScriptContext`
- `.buildOnchain()` -- returns an on-chain-api `ScriptContext`
- `.buildPlutusData()` -- returns `PlutusData` (for direct UPLC evaluation)

### BudgetAssertions

```java
import com.bloxbean.cardano.julc.testkit.BudgetAssertions;

var result = ValidatorTest.evaluate(program, ctx);

// Check success/failure
BudgetAssertions.assertSuccess(result);
BudgetAssertions.assertFailure(result);

// Check execution budget limits
BudgetAssertions.assertBudgetUnder(result, 1_000_000L, 500_000L);

// Check trace messages
BudgetAssertions.assertTrace(result, "expected message");
BudgetAssertions.assertTraceExact(result, "msg1", "msg2");
BudgetAssertions.assertNoTraces(result);

// Check script size
var compileResult = ValidatorTest.compileWithDetails(source);
BudgetAssertions.assertScriptSizeUnder(compileResult, 16_384); // 16 KB limit
```

### JulcEval

`JulcEval` lets you test individual helper methods in isolation — no
`ScriptContext` required. Use it when you want to verify a single function's logic
without building a full validator test scenario.

**Factory methods:**

| Method | Description |
|--------|-------------|
| `JulcEval.forClass(Class<?>)` | Load source from `src/main/java` by class |
| `JulcEval.forClass(Class<?>, Path)` | Load source from a custom source root |
| `JulcEval.forSource(String)` | Use inline Java source |

**Mode 1: Interface proxy** — define an interface matching the on-chain methods,
and call them with Java types:

```java
interface MathProxy {
    BigInteger doubleIt(long x);
    boolean isPositive(long x);
}

var proxy = JulcEval.forClass(MathHelper.class)
                    .create(MathProxy.class);

assertEquals(BigInteger.valueOf(42), proxy.doubleIt(21));
assertTrue(proxy.isPositive(1));
```

**Mode 2: Fluent `call()` API** — one-off calls with string method names:

```java
var eval = JulcEval.forClass(MathHelper.class);

assertEquals(BigInteger.valueOf(42), eval.call("doubleIt", 21).asInteger());
assertTrue(eval.call("isPositive", 1).asBoolean());
```

**Supported argument types** (auto-converted to PlutusData):

`BigInteger`, `int`, `long`, `boolean`, `byte[]`, `String`, `PlutusData`, `PlutusDataConvertible`

**CallResult extraction methods:**

| Method | Return type |
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
| `.as(Class<T>)` | `T` (supports ledger types and primitives) |
| `.auto()` | `Object` (auto-detected) |
| `.rawTerm()` | `Term` (raw UPLC term) |

**When to use which:**

| Scenario | Use |
|----------|-----|
| Test a single helper method (math, string, logic) | `JulcEval` |
| Test a full validator with datum + redeemer + ScriptContext | `ValidatorTest` |
| End-to-end with budget checks and trace messages | `ValidatorTest` + `BudgetAssertions` |

---

## 15. Deploying

### JulcScriptLoader

Load pre-compiled scripts from the classpath (produced by the annotation
processor):

```java
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;

// Non-parameterized
PlutusV3Script script = JulcScriptLoader.load(VestingValidator.class);
String hash = JulcScriptLoader.scriptHash(VestingValidator.class);

// Parameterized
PlutusV3Script script = JulcScriptLoader.load(ParameterizedVesting.class,
    BytesPlutusData.of(ownerPkh),
    BigIntPlutusData.of(deadlineMs));
```

### JulcScriptAdapter

Convert a `Program` (from programmatic compilation) to a cardano-client-lib
`PlutusV3Script`:

```java
import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;

var program = compiler.compile(source).program();
PlutusV3Script script = JulcScriptAdapter.fromProgram(program);
String hash = JulcScriptAdapter.scriptHash(program);
```

### cardano-client-lib integration

Once you have a `PlutusV3Script`, use cardano-client-lib to build transactions:

```java
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.function.helper.SignerProviders;

// Derive the script address
String scriptAddress = AddressProvider
    .getEntAddress(script, Networks.testnet())
    .toBech32();

// Lock ADA to the script
var lockTx = new Tx()
    .payToContract(scriptAddress, Amount.ada(5), datum)
    .from(account.baseAddress());

var result = quickTxBuilder.compose(lockTx)
    .withSigner(SignerProviders.signerFrom(account))
    .completeAndWait();

// Unlock from the script
var unlockTx = new ScriptTx()
    .collectFrom(scriptUtxo, redeemer)
    .payToAddress(account.baseAddress(), Amount.ada(4))
    .attachSpendingValidator(script);

var result = quickTxBuilder.compose(unlockTx)
    .withSigner(SignerProviders.signerFrom(account))
    .feePayer(account.baseAddress())
    .collateralPayer(account.baseAddress())
    .completeAndWait();

// Mint tokens
var asset = new Asset("MyToken", BigInteger.valueOf(100));
var mintTx = new ScriptTx()
    .mintAsset(script, asset, redeemer);

var result = quickTxBuilder.compose(mintTx)
    .withSigner(SignerProviders.signerFrom(account))
    .feePayer(account.baseAddress())
    .collateralPayer(account.baseAddress())
    .completeAndWait();
```

---

## 16. Compiler Limitations

The JuLC compiler supports a safe subset of Java for on-chain execution. The
following limitations apply:

### Variables and Assignment

- **Immutable variables**: Variables cannot be reassigned after initialization. The
  only exception is loop accumulator variables in `while` and `for-each` loops.
- **No uninitialized variables**: All variables must be initialized at declaration.

### Types

- **Supported types**: `BigInteger`, `boolean`, `byte[]`, `long`, `String`,
  `PlutusData`, records, sealed interfaces, `List<T>`, `Map<K,V>`, `Optional<T>`,
  `Tuple2<A,B>`, `Tuple3<A,B,C>`.
- **No float/double**: Floating-point types do not exist on-chain.
- **No arrays** (except `byte[]`): Use `List<T>` for collections. `byte[]` literal arrays (`new byte[]{0x48, 0x45}`) and `"TOKEN".getBytes()` are supported as compile-time constants.
- **No class inheritance**: Only records and sealed interfaces are supported for
  data types.

### Control Flow

- **No `continue` statement**: Every branch in a loop body must assign all
  accumulator variables.
- **No C-style `for(init; cond; step)`**: Use `while` or for-each.
- **No `do-while`**: Use a `while` loop with an initial check.
- **No `return` inside multi-accumulator loop body**: The loop must complete
  naturally.
- **No `try`/`catch`/`throw`**: Errors are expressed via `Builtins.error()` which
  halts the CEK machine.
- **No `null`**: There is no null concept on-chain. Use `Optional<T>` where
  needed.
- **No `this`/`super`**: All methods must be `static`.

### Functions

- **No lambda `.apply()`**: Lambda expressions can only be passed directly to HOF
  methods (like `ListsLib.map`). You cannot store a lambda in a variable and invoke
  it later.
- **Multi-binding LetRec**: Mutual recursion is supported for up to 2 mutually
  recursive methods (via Bekic's theorem). More than 2 mutually recursive bindings
  are not supported.
- **`map()` returns `JulcList<PlutusData>`**: The `map` HOF wraps each lambda
  result to Data, so the returned list has `DataType` elements regardless of
  input. Use `Builtins.unIData()` or `Builtins.unBData()` to extract typed
  values from mapped results.

### Type System Caveats

- **`@Param` must use `PlutusData`**: Never use `PlutusData.BytesData`, `byte[]`,
  or other specific types for `@Param` fields. Param values are always raw Data at
  runtime.
- **Cross-method type inference**: Calling a helper method with a `long` parameter
  from another method may generate `EqualsData` instead of `EqualsInteger`. Use
  Data-level equality as a workaround.
- **Cross-library `BytesData` param bug**: When calling a stdlib method that takes
  `BytesData`-typed parameters from user code, if the caller has a `BytesData`
  variable of matching type, the compiler skips the needed conversion. Workaround:
  pass `PlutusData` (not `BytesData`) arguments to cross-library calls.
- **Tuple2/Tuple3 not switchable**: These are registered as `RecordType`, not
  `SumType`. Use `.first()` and `.second()` field access instead of pattern
  matching.

### Value and Ledger Types

- **`Value.assetOf()` needs BData args**: Arguments must be wrapped with
  `Builtins.bData()` when passing `byte[]` to avoid `EqualsData` mismatches.
- **Double `.hash()` on ledger hash types**: Types like `PubKeyHash`, `TxId`,
  `ScriptHash` map to `ByteStringType`. Calling `.hash()` extracts the raw
  ByteString. Calling `.hash()` again generates a second `UnBData` on an already-
  unwrapped value, which fails. Use `(byte[])(Object) pk.hash()` instead of
  `pk.hash().hash()`.

### Switch Expressions

- **`default` branch**: The `default ->` branch acts as a catch-all for uncovered
  variants. Prefer explicit cases for all variants of a sealed interface for clarity.
  The compiler checks exhaustiveness at compile time: if you omit a case and have
  no `default` branch, you get a compile error listing the missing variants.
- **Field name shadows parameter**: In `case Variant f -> body`, the compiler
  binds the variant's field names in scope. If a method parameter has the same name
  as a field, the field binding shadows the parameter. Use different parameter
  names.

### Not Supported

- No standard Java library classes (only `BigInteger` and the JuLC stdlib)
- No `new` for non-record classes
- No generics beyond Tuple2/Tuple3 and built-in collections
- No method references (`MyClass::method`)
- No annotations beyond the JuLC-provided ones
