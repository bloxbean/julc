# JRL (JuLC Rule Language) Guide

Write Cardano smart contracts using a simple, declarative rule language — no Java
knowledge required. JRL compiles to UPLC through JuLC, producing the same
efficient Plutus V3 scripts as hand-written Java validators.

---

## What is JRL?

JRL is a domain-specific language inspired by business rule engines like Drools.
Instead of writing imperative code, you declare **rules** with **conditions** and
**actions**. The JRL compiler translates your rules into Java, then compiles that
Java to UPLC via the JuLC compiler pipeline.

**Key design choices:**
- **Non-Turing-complete** — no loops, no recursion, no mutable variables
- **Declarative** — say *what* should happen, not *how*
- **Safe by default** — the type checker catches errors before compilation
- **Familiar syntax** — reads like English: `when ... then allow`

**Compilation pipeline:**

```
YourContract.jrl → parse → type check → Java source → JulcCompiler → UPLC bytecode
```

---

## Quick Start with julc CLI

The fastest way to start writing JRL contracts is with the `julc` command-line tool.

### 1. Install julc

```bash
# macOS / Linux
brew install bloxbean/tap/julc

# Or download from GitHub Releases:
# https://github.com/bloxbean/julc/releases
```

### 2. Create a new JRL project

```bash
julc new my-contract --jrl
cd my-contract
```

This creates:

```
my-contract/
├── julc.toml              # project config
├── src/
│   └── AlwaysSucceeds.jrl # starter validator
├── test/
│   └── AlwaysSucceedsTest.java
└── .julc/
    └── stdlib/            # auto-installed standard library
```

The generated `AlwaysSucceeds.jrl`:

```
contract "AlwaysSucceeds"
version  "1.0"
purpose  spending

rule "Always allow"
when
    Condition( true )
then
    allow

default: deny
```

### 3. Build

```bash
julc build
```

Output:

```
Building my-contract ...
  Compiling AlwaysSucceeds.jrl ... OK [67 bytes, a1b2c3d4...]

Build successful: 1 validator(s) compiled to build/plutus/
```

The compiled script is in `build/plutus/AlwaysSucceeds.uplc`, and a CIP-57
blueprint is generated at `build/plutus/plutus.json`.

---

## Contract Structure

Every JRL file follows this structure:

```
contract "<name>"
version  "<version>"
purpose  <spending | minting | withdraw | certifying | voting | proposing>

[params: ...]           -- optional: compile-time parameters
[datum <Type>: ...]     -- optional: datum schema (spending only)
[record <Type>: ...]    -- optional: helper record types
[redeemer <Type>: ...]  -- optional: redeemer schema

rule "<name>"           -- one or more rules
when
    <patterns>
then
    <allow | deny>

default: <allow | deny> -- fallback when no rule matches
```

### Header

The header declares the contract's name, version, and purpose:

```
contract "Vesting" version "1.0" purpose spending
```

Purpose determines what kind of Cardano script is generated:

| Purpose      | Cardano Script Type | Has Datum? |
|-------------|---------------------|------------|
| `spending`  | Spending validator  | Yes        |
| `minting`   | Minting policy      | No         |
| `withdraw`  | Withdrawal validator | No        |
| `certifying`| Certifying validator | No        |
| `voting`    | Voting validator    | No         |
| `proposing` | Proposing validator | No         |

---

## Types

JRL has a focused set of types that map to Cardano on-chain representations:

| JRL Type       | Description                  | Java Equivalent |
|---------------|------------------------------|-----------------|
| `Integer`     | Arbitrary-precision integer  | `BigInteger`    |
| `Lovelace`    | ADA amount (1 ADA = 1M)     | `BigInteger`    |
| `POSIXTime`   | Unix timestamp (milliseconds)| `BigInteger`   |
| `ByteString`  | Raw bytes                    | `byte[]`        |
| `PubKeyHash`  | Public key hash              | `byte[]`        |
| `PolicyId`    | Minting policy ID            | `byte[]`        |
| `TokenName`   | Token/asset name             | `byte[]`        |
| `ScriptHash`  | Script hash                  | `byte[]`        |
| `DatumHash`   | Datum hash                   | `byte[]`        |
| `TxId`        | Transaction ID               | `byte[]`        |
| `Address`     | Cardano address              | `Address`       |
| `Text`        | UTF-8 string                 | `String`        |
| `Boolean`     | True/false                   | `boolean`       |
| `List <T>`    | Typed list                   | `List<T>`       |
| `Optional <T>`| Optional value               | `Optional<T>`   |

---

## Parameters

Parameters are compile-time values baked into the script. They make your contract
reusable — deploy the same logic with different keys, deadlines, or thresholds.

```
contract "TimeLock" version "1.0" purpose spending
params:
    owner    : PubKeyHash
    lockTime : POSIXTime
```

Parameters are referenced by name in rules:

```
rule "Owner can spend after lock"
when
    Transaction( signedBy: owner )
    Transaction( validAfter: lockTime )
then allow
```

When building with `julc build`, parameter values are applied at deployment time
via the CIP-57 blueprint.

---

## Datum Declaration

Spending validators can declare a **datum** — structured data stored at the UTxO:

```
datum VestingDatum:
    owner       : PubKeyHash
    beneficiary : PubKeyHash
    deadline    : POSIXTime
```

This declares a record type. In rules, you extract fields using the `Datum`
pattern with `$variable` bindings:

```
rule "Beneficiary withdraws after deadline"
when
    Datum( VestingDatum( beneficiary: $ben, deadline: $dl ) )
    Transaction( signedBy: $ben )
    Transaction( validAfter: $dl )
then allow
```

---

## Record Declarations

You can declare helper record types for organizing complex data:

```
record Payment:
    recipient : PubKeyHash
    amount    : Lovelace
```

Records can be used as datum field types, redeemer field types, or anywhere
a structured type is needed.

---

## Redeemer Declaration

The redeemer tells the validator *what action* the transaction wants to perform.
JRL supports two styles:

### Record redeemer (single action with data)

```
redeemer SpendAction:
    amount   : Lovelace
    deadline : POSIXTime
```

### Variant redeemer (multiple actions)

```
redeemer MintAction:
    | Mint
    | Burn
    | Transfer:
        recipient : PubKeyHash
        amount    : Integer
```

Variant redeemers use `|` to declare alternatives. Each variant can have zero or
more fields. In rules, you match specific variants:

```
rule "Authority can mint"
when
    Redeemer( Mint )
    Transaction( signedBy: authority )
then allow

rule "Owner can burn"
when
    Redeemer( Burn )
    Transaction( signedBy: owner )
then allow
```

---

## Rules

Rules are the heart of JRL. Each rule has:
1. A **name** (for documentation and tracing)
2. **Patterns** in the `when` clause (conditions that must all be true)
3. An **action** (`allow` or `deny`)

```
rule "Owner can always withdraw"
when
    Datum( VestingDatum( owner: $owner ) )
    Transaction( signedBy: $owner )
then allow
```

Rules are evaluated **in order**. The first rule whose conditions all match
determines the result. If no rule matches, the `default` action applies.

### Evaluation semantics

```
for each rule (in declaration order):
    if ALL patterns in the rule match:
        return the rule's action (allow → true, deny → false)
return default action
```

---

## Fact Patterns

Patterns appear in a rule's `when` clause. All patterns in a rule must match for
the rule to fire. JRL provides five pattern types:

### Datum( ... )

Extract and match datum fields (spending validators only):

```
-- Bind a field to a variable
Datum( MyDatum( owner: $owner ) )

-- Match a literal value
Datum( MyDatum( flag: true ) )

-- Bind multiple fields
Datum( MyDatum( owner: $o, amount: $a, deadline: $d ) )
```

### Redeemer( ... )

Match redeemer type and extract fields:

```
-- Simple variant match (no fields)
Redeemer( Mint )

-- Variant with field extraction
Redeemer( Transfer( recipient: $r, amount: $a ) )

-- Record redeemer field extraction
Redeemer( SpendAction( amount: $amt ) )
```

### Transaction( field: expr )

Check transaction properties:

```
-- Check if a public key signed the transaction
Transaction( signedBy: owner )
Transaction( signedBy: $boundVar )

-- Check transaction validity interval
Transaction( validAfter: lockTime )    -- tx valid range starts after lockTime
Transaction( validBefore: deadline )   -- tx valid range ends before deadline
```

### Condition( expr )

Arbitrary boolean expression:

```
Condition( true )
Condition( $amount > 1000000 )
Condition( sha2_256($secret) == expectedHash )
Condition( $a + $b >= $threshold )
```

### Output( ... )

Check transaction outputs:

```
-- Output must go to address with minimum ADA
Output( to: $recipient, value: minADA( $amount ) )

-- Output must contain a specific token
Output( to: $addr, value: contains( $policy, $token, 1 ) )
```

---

## Expressions

JRL expressions appear inside patterns and conditions. They support:

### Arithmetic

```
$a + $b
$a - $b
$a * $b
```

### Comparison

```
$a == $b       -- equality
$a != $b       -- inequality
$a > $b        -- greater than
$a >= $b       -- greater or equal
$a < $b        -- less than
$a <= $b       -- less or equal
```

### Logical

```
$a && $b       -- logical AND
$a || $b       -- logical OR
!$a            -- logical NOT
```

### Field access

```
$datum.owner
$datum.deadline
```

### Variable references

Variables are bound in `Datum` and `Redeemer` patterns using `$name` syntax.
Parameters are referenced by their plain name (no `$` prefix).

```
-- $owner is a bound variable from Datum pattern
-- authority is a parameter
Transaction( signedBy: $owner )
Transaction( signedBy: authority )
```

### Literals

```
42              -- integer
"hello"         -- string
0xFF            -- hex bytes
true / false    -- boolean
```

### Built-in functions

| Function        | Description              | Example                         |
|----------------|--------------------------|----------------------------------|
| `sha2_256`     | SHA-256 hash             | `sha2_256($secret)`             |
| `blake2b_256`  | BLAKE2b-256 hash         | `blake2b_256($data)`            |
| `sha3_256`     | SHA3-256 hash            | `sha3_256($input)`              |
| `length`       | List/bytestring length   | `length($signatories)`          |

### Special references

| Reference      | Description                          |
|---------------|--------------------------------------|
| `ownAddress`  | The script's own address             |
| `ownPolicyId` | The script's own policy ID (minting) |

---

## Default Action

Every contract (or purpose section) must end with a default action:

```
default: deny     -- reject if no rule matched (most common)
default: allow    -- accept if no rule matched
```

**Best practice:** Use `default: deny` to fail-closed. Only use `default: allow`
when you explicitly want permissive behavior.

---

## Trace Messages

Add optional trace messages to rules for debugging:

```
rule "Owner can withdraw"
when
    Transaction( signedBy: owner )
then allow trace "owner-withdraw"
```

Trace messages appear in transaction logs during script evaluation, useful for
debugging failed validations.

---

## Comments

```
-- This is a single-line comment

/* This is a
   multi-line comment */
```

---

## Complete Examples

### Example 1: Simple Transfer (Beginner)

The simplest useful validator — only the designated receiver can spend the UTxO:

```
contract "SimpleTransfer" version "1.0" purpose spending
params:
    receiver : PubKeyHash

rule "Receiver can spend"
when
    Transaction( signedBy: receiver )
then allow

default: deny
```

### Example 2: Time Lock (Parameters + Time)

Lock funds until a specific time. Only the owner can spend, and only after the
lock expires:

```
contract "TimeLock" version "1.0" purpose spending
params:
    owner    : PubKeyHash
    lockTime : POSIXTime

rule "Owner can spend after lock time"
when
    Transaction( signedBy: owner )
    Transaction( validAfter: lockTime )
then allow

default: deny
```

### Example 3: Vesting (Datum + Multiple Rules)

Two spending paths: the owner can always withdraw, but the beneficiary can only
withdraw after the deadline:

```
contract "Vesting" version "1.0" purpose spending

datum VestingDatum:
    owner       : PubKeyHash
    beneficiary : PubKeyHash
    deadline    : POSIXTime

rule "Owner can always withdraw"
when
    Datum( VestingDatum( owner: $owner ) )
    Transaction( signedBy: $owner )
then allow

rule "Beneficiary can withdraw after deadline"
when
    Datum( VestingDatum( beneficiary: $ben, deadline: $deadline ) )
    Transaction( signedBy: $ben )
    Transaction( validAfter: $deadline )
then allow

default: deny
```

### Example 4: Multi-Signature Treasury (Two Signers)

Both signers stored in the datum must sign the transaction:

```
contract "MultiSigTreasury" version "1.0" purpose spending

datum TreasuryDatum:
    signer1 : PubKeyHash
    signer2 : PubKeyHash

rule "Both signers required"
when
    Datum( TreasuryDatum( signer1: $s1, signer2: $s2 ) )
    Transaction( signedBy: $s1 )
    Transaction( signedBy: $s2 )
then allow

default: deny
```

### Example 5: HTLC — Hash Time-Locked Contract (Variant Redeemer + Crypto)

Funds can be claimed by guessing a secret (hash preimage), or reclaimed by the
owner after expiry:

```
contract "HTLC" version "1.0" purpose spending
params:
    secretHash : ByteString
    expiration : POSIXTime
    owner      : PubKeyHash

redeemer HtlcAction:
    | Guess:
        answer : ByteString
    | Withdraw

rule "Correct guess unlocks"
when
    Redeemer( Guess( answer: $answer ) )
    Condition( sha2_256($answer) == secretHash )
then allow

rule "Owner can withdraw after expiry"
when
    Redeemer( Withdraw )
    Transaction( signedBy: owner )
    Transaction( validAfter: expiration )
then allow

default: deny
```

### Example 6: Multi-Sig Minting Policy (Minting + Variant Redeemer)

A minting policy with three paths: authority mint, owner burn, or multi-sig mint:

```
contract "MultiSigMinting" version "1.0" purpose minting
params:
    authority : PubKeyHash
    owner     : PubKeyHash
    cosigner1 : PubKeyHash
    cosigner2 : PubKeyHash

redeemer MintAction:
    | MintByAuthority
    | BurnByOwner
    | MintByMultiSig

rule "Authority can mint"
when
    Redeemer( MintByAuthority )
    Transaction( signedBy: authority )
then allow

rule "Owner can burn"
when
    Redeemer( BurnByOwner )
    Transaction( signedBy: owner )
then allow

rule "Multi-sig can mint"
when
    Redeemer( MintByMultiSig )
    Transaction( signedBy: cosigner1 )
    Transaction( signedBy: cosigner2 )
then allow

default: deny
```

### Example 7: Output Checking (Value Constraints)

Ensure the transaction pays the right amount to the right address:

```
contract "OutputCheck" version "1.0" purpose spending

datum PaymentDatum:
    recipient : ByteString
    minAmount : Lovelace

rule "Payment meets minimum"
when
    Datum( PaymentDatum( recipient: $addr, minAmount: $min ) )
    Output( to: $addr, value: minADA( $min ) )
then allow

default: deny
```

---

## Multi-Validator Contracts

A single JRL file can define multiple validator purposes using `purpose` sections.
This creates a multi-validator script that handles spending, minting, or other
purposes in one contract:

```
contract "TokenMarket" version "1.0"

purpose spending:
    redeemer SpendAction:
        | Buy
        | Withdraw

    rule "Anyone can buy"
    when
        Redeemer( Buy )
        Condition( true )
    then allow

    rule "Owner withdraws"
    when
        Redeemer( Withdraw )
        Transaction( signedBy: owner )
    then allow

    default: deny

purpose minting:
    redeemer MintAction:
        | Mint
        | Burn

    rule "Authority mints"
    when
        Redeemer( Mint )
        Transaction( signedBy: authority )
    then allow

    default: deny
```

Each purpose section has its own redeemer, rules, and default action. Shared
parameters and datum declarations go at the top level.

---

## Error Messages

The JRL compiler provides clear error messages with source locations and
suggestions. Errors are prefixed with codes like `JRL001`:

| Code    | Category        | Example                                    |
|---------|-----------------|---------------------------------------------|
| `JRL000`| Syntax error    | Missing keyword or unexpected token         |
| `JRL001`| Missing default | Contract has no `default:` action           |
| `JRL002`| Missing rules   | Contract or section has no rules            |
| `JRL003`| Duplicate name  | Two rules with the same name                |
| `JRL005`| Unknown type    | Field uses an undefined type                |
| `JRL007`| Duplicate field | Record has two fields with the same name    |
| `JRL008`| Duplicate variant| Redeemer has two variants with the same name|
| `JRL009`| Missing header  | Contract name or version not specified      |
| `JRL010`| Missing purpose | No purpose in header and no purpose sections|
| `JRL011`| Unbound variable| `$var` used in condition but never bound    |

---

## JRL vs Java — When to Use Which

| Use JRL when...                          | Use Java when...                          |
|-----------------------------------------|------------------------------------------|
| Simple authorization logic               | Complex business logic with loops         |
| Signature + time checks                  | Custom data transformations               |
| Pattern matching on datum/redeemer       | Multiple helper methods                   |
| Quick prototyping                        | Fine-grained budget optimization          |
| Non-developers writing validators        | Integration with off-chain Java code      |
| Clear, auditable rule sets               | Advanced stdlib usage (maps, lists, HOFs) |

JRL is perfect for validators that follow the pattern: "check conditions,
allow or deny." For complex logic involving iteration, accumulation, or deep
data manipulation, use Java directly.

You can mix both in the same project — `julc build` compiles `.java` and `.jrl`
files side by side.

---

## How JRL Compiles

Understanding the compilation pipeline helps with debugging:

```
                     ┌─────────────┐
   Vesting.jrl  ───→│  JRL Parser  │───→ AST (ContractNode)
                     └──────┬──────┘
                            │
                     ┌──────▼──────┐
                     │ Type Checker │───→ Diagnostics (errors/warnings)
                     └──────┬──────┘
                            │
                     ┌──────▼──────────┐
                     │ Java Transpiler  │───→ Vesting.java (generated)
                     └──────┬──────────┘
                            │
                     ┌──────▼──────────┐
                     │  JulcCompiler   │───→ UPLC Program
                     └──────┬──────────┘
                            │
                     ┌──────▼──────────┐
                     │  CBOR + FLAT    │───→ Plutus script bytes
                     └─────────────────┘
```

1. **Parser**: ANTLR4-based parser converts JRL syntax into an AST
2. **Type Checker**: Validates structure, types, names, and variable scoping
3. **Java Transpiler**: Generates a JuLC-compatible Java validator class
4. **JulcCompiler**: Compiles the generated Java to UPLC bytecode
5. **Serialization**: UPLC is serialized to on-chain format

The intermediate Java source is available for inspection — useful for debugging
or understanding what JRL generates.

---

## Programmatic Usage

You can use the JRL compiler directly from Java code:

### Full compilation (JRL → UPLC)

```java
var compiler = new JrlCompiler();
var result = compiler.compile(jrlSource, "MyContract.jrl");

if (result.hasErrors()) {
    for (var diag : result.jrlDiagnostics()) {
        System.err.println(diag);
    }
} else {
    var program = result.compileResult().program();
    // Use the UPLC program...
}
```

### Transpile only (JRL → Java source)

```java
var compiler = new JrlCompiler();
var transpiled = compiler.transpile(jrlSource, "MyContract.jrl");

if (!transpiled.hasErrors()) {
    String javaSource = transpiled.javaSource();
    System.out.println(javaSource);
}
```

### Parse and type check

```java
var compiler = new JrlCompiler();
var diagnostics = compiler.check(jrlSource, "MyContract.jrl");

for (var diag : diagnostics) {
    if (diag.isError()) {
        System.err.println(diag.code() + ": " + diag.message());
    }
}
```

### Gradle/Maven dependency

```groovy
implementation 'com.bloxbean.cardano:julc-jrl-core:<version>'
```

---

## Tips and Best Practices

1. **Start with `default: deny`** — fail-closed is safer. Only allow what you
   explicitly permit.

2. **Order rules by specificity** — put more specific rules (variant redeemer
   matches) before general ones (plain conditions).

3. **Use parameters for keys and deadlines** — don't hardcode public key hashes
   or timestamps. Parameters make your contract reusable.

4. **Bind only what you need** — in datum/redeemer patterns, only bind fields
   you actually use in conditions.

5. **One rule per logical path** — each rule should represent one clear spending
   or minting scenario. Multiple patterns in a rule mean AND; multiple rules
   mean OR.

6. **Test with julc** — the CLI compiles and validates your contract instantly,
   catching type errors and structural issues before deployment.

7. **Inspect generated Java** — use the `transpile` API to see what Java code
   your JRL produces. This helps understand the mapping and debug issues.
