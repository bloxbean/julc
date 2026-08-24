---
title: Purpose-indexed multi-validator blueprints
description: Use CIP-57 metadata for one JuLC script with multiple ledger purposes.
---

An explicit `@MultiValidator` compiles all of its purpose handlers into one
UPLC program and one Cardano script hash. JuLC publishes one CIP-57 validator
entry per supported purpose so each datum/redeemer schema remains unambiguous.

```java
@MultiValidator
class Protocol {
    record State(long counter) {}
    record Spend(long next) {}
    record Mint(byte[] tokenName) {}

    @Entrypoint(purpose = Purpose.SPEND)
    static boolean spend(State datum, Spend redeemer, ScriptContext ctx) {
        return true;
    }

    @Entrypoint(purpose = Purpose.MINT)
    static boolean mint(Mint redeemer, ScriptContext ctx) {
        return true;
    }
}
```

`julc build` emits these entries:

| Blueprint title | Purpose | Datum | Redeemer |
|---|---|---|---|
| `Protocol.mint` | `mint` | none | `Mint` |
| `Protocol.spend` | `spend` | `State` | `Spend` |

Both entries contain byte-identical `compiledCode` and the same `hash`. The
suffix identifies an interface, not a different deployed script. Entries are
ordered by JuLC's ledger-purpose tag, independent of Java declaration order.

JuLC publishes `SPEND` as `spend`, `MINT` as `mint`, `WITHDRAW` as `withdraw`,
and `CERTIFY` as `publish`. The last mapping follows Aiken's terminology for
the ledger certificate purpose; JuLC's Java API continues to call it
`CERTIFY`. The pinned CIP-57 vocabulary has no truthful name for `VOTE` or
`PROPOSE`, so a strict blueprint build containing either purpose fails as a
whole. Use the documented blueprint opt-out only if you deliberately accept
having no interface metadata.

A default/manual-dispatch `@MultiValidator` also remains unsupported because
arbitrary Java control flow does not expose a reliable purpose-to-schema map.
Use explicit `@Entrypoint(purpose = ...)` methods to publish a blueprint.

## Select an interface

Low-level artifact commands use the full blueprint title. Verification uses
the Java validator's base title plus an explicit purpose:

```bash
julc verify init . --validator Protocol --purpose spending \
  --out-dir verification/protocol-spend
julc verify init . --validator Protocol --purpose minting \
  --out-dir verification/protocol-mint
```

The generated manifest records both `validatorTitle` (the base Java identity)
and `blueprintEntryTitle` (for example `Protocol.spend`). It also binds the
selected entry's compiled-code digest and Cardano script hash. Missing or
ambiguous matches fail closed; no command selects the first array entry.

For annotation-driven `julc verify`, pass `--purpose spending` or
`--purpose minting` when the annotated class exposes more than one interface.
Single-purpose validators remain source-compatible and do not use a suffix.

## Off-chain construction

Use the selected entry's schema to choose the correct Java type, then
`PlutusDataAdapter.convert(value)` to construct cardano-client-lib
`PlutusData`. The same deployed script is attached for every purpose; only the
transaction's script purpose and corresponding datum/redeemer differ.

Definitions for a purpose-indexed script are namespaced by the base validator
title. When two Java packages use the same simple type name with different
shapes, JuLC additionally retains their stable qualified identities rather
than merging them.

## Migration

Rebuild the blueprint and update consumers that previously selected an
explicit multi-validator by its unsuffixed class name. Artifact-level consumers
must now select the exact purpose-qualified entry such as `Protocol.spend`;
verification commands continue to take `Protocol` plus `--purpose`. Do not
deploy each repeated entry as a separate script: their identical code and hash
identify one deployable artifact.

Single-purpose validator titles and script bytes are unchanged. A strict build
that also contains `VOTE`, `PROPOSE`, or manual-dispatch interfaces will fail
without replacing the previous complete blueprint; use
`--no-blueprint` only as an explicit metadata opt-out while migrating.

See [Formal Verification](/guides/formal-verification/) for annotation and
typed-DSL workflows that consume these exact purpose-indexed interfaces.
