---
title: "Strict Data Boundaries"
description: "Canonical datum and redeemer decoding, costs, and migration"
---

JuLC validates every typed datum and redeemer before invoking validator code.
The declared Java type is the complete accepted on-chain representation, not
only a convenient view over the first few fields.

```java
@SpendingValidator
class StateMachine {
    record Datum(byte[] owner, BigInteger state) {}
    record Redeemer(BigInteger nextState) {}

    @Entrypoint
    static boolean validate(Datum datum, Redeemer redeemer, ScriptContext ctx) {
        // datum and redeemer are already known to have canonical shapes.
        return redeemer.nextState().compareTo(datum.state()) > 0;
    }
}
```

There is no `@StrictDataBoundary` annotation and no permissive compiler flag.
Strict decoding is the default language behavior.

Primitive and container roots are decoded before invocation. Code should use a
declared `BigInteger`, `byte[]`, `String`, `boolean`, `List`, or `Map` argument
directly; do not apply raw-`Data` destructors such as `Builtins.unIData`,
`unBData`, `asList`, or `asMap` to it. Those explicit destructors are only for
values deliberately declared as raw `PlutusData`. Deployer-applied `@Param`
fields are not transaction-time boundary roots and are handled separately.

## What is checked

- records: constructor index 0, exact arity, and every field;
- sealed variants: a known constructor index, exact constructor arity, and
  every field;
- integers, byte strings, UTF-8 strings, and canonical booleans;
- `Optional` tags and arities;
- every list item and every raw map key/value, including duplicate entries;
- nested combinations; and
- productive direct, mutual, and container recursion.

Use the sealed-interface type when a datum/redeemer value is a variant. A
concrete implementing record cannot yet be used independently as a boundary
root or nested field because its schema does not carry the parent sum's nominal
constructor tag; JuLC rejects that ambiguous form at compile time.

Malformed data fails before user validator code runs, even when the malformed
field is unused. Unsupported typed boundaries fail compilation at the Java
parameter location instead of falling back to opaque data.

`ScriptContext` is not recursively checked again: it is constructed by the
ledger. Deployer-applied `@Param` values use the parameter-application path and
are outside the transaction-time datum/redeemer guard.

## Raw data is explicit

Use `PlutusData` when a contract intentionally needs a custom encoding or a
smaller manual check:

```java
@Entrypoint
static boolean validate(PlutusData redeemer, ScriptContext ctx) {
    return Builtins.constrTag(redeemer) == 0;
}
```

JuLC does not invent a schema for that value. The contract—and any formal
property—must model the manual validation accurately.

## Artifact and cost changes

Strict guards change affected UPLC bytes, execution cost, and script hashes.
The generated CIP-57 blueprint records the semantics in
`preamble.compiler.version` as SemVer build metadata:

```json
{"name":"julc","version":"<version>+boundary.strict-data-v1"}
```

`julc verify` also records `boundarySemantics: strict-data-v1` in its manifest
and certificate. Measure the compiled script with representative maximum-size
inputs; recursive or container-heavy types may approach transaction ExUnit
limits even when the validator body is small.

## Migrating an existing validator

1. Keep the old compiler version available until the migration is complete;
   the new compiler does not reproduce permissive-era hashes.
2. Build and record the new script hash, size, and representative execution
   budgets.
3. Decode every state datum at the old address using the declared schema and
   re-encode it canonically. Do not copy raw bytes blindly: wrong tags or
   trailing fields accepted by an old script are rejected by the new one.
4. Test spending the re-encoded state with the exact new artifact.
5. Deploy the new script and move state according to the contract's reviewed
   migration transaction/protocol.
6. Update off-chain configuration, cached script hashes, generated blueprints,
   verification workspaces, and monitoring.

Strict decoding does not prove contract-specific authorization, value
preservation, state-transition, or minting rules. Use normal VM tests and, when
applicable, `julc verify` for those independent claims.
