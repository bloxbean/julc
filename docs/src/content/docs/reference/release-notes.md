---
title: "Release Notes"
description: "JuLC release notes and migration guidance"
---

## Upcoming preview: strict typed datum/redeemer boundaries

Typed validator boundaries now reject non-canonical `Data` before user code
runs. Records and variants require exact constructor tags and arities;
primitive, optional, list, map, nested, and productive recursive fields are
checked eagerly. Explicit `PlutusData` roots remain raw.

Typed, non-record entrypoint arguments now reach validator code in their
declared representation. In older compiler versions, a root declared as
`BigInteger`, `byte[]`, `String`, `boolean`, `List`, or `Map` could remain raw
`Data` at runtime even though the PIR type said otherwise. Strict-boundary
lowering fixes that compiler bug by decoding the root before invocation. Remove
workarounds such as an explicit `Builtins.unIData(redeemer)`, `unBData`,
`asList`, or `asMap` when the entrypoint argument is already declared with the
corresponding typed Java form. This change concerns transaction-time entrypoint
arguments; deployer-applied `@Param` fields are a separate pipeline.

A concrete record that implements a sealed interface cannot currently be used
as an independent typed datum/redeemer root or nested boundary field. Declare
the sealed interface instead. JuLC now rejects the concrete form at its Java
parameter because constructor lowering uses the variant's sum tag while the
record schema does not yet carry that nominal tag.

This is an intentional preview-language breaking change. Recompiling an
affected validator changes its UPLC and script hash. There is no permissive
mode: retain the old compiler to reproduce an old artifact. Before moving state
to a new script address, decode and canonically re-encode existing datums so
wrong-tag or trailing-field values accepted by a permissive-era script are not
carried to the strict address.

Strict traversal has a real execution cost. In the activation measurements, a
250-element `List<BigInteger>` guard used approximately 502 million CPU units
(about 2 million per element and about 5% of the 10-billion transaction
ceiling), compared with approximately 0.9 million for the legacy no-op path.
Measure production maximum-size inputs; use an explicitly raw `PlutusData`
boundary with a reviewed manual check when full traversal is unsuitable.

Blueprint compiler identities include `+boundary.strict-data-v1`, and formal
verification certificates record `boundarySemantics: strict-data-v1`. See the
[strict data boundary guide](../guides/strict-data-boundaries/) for the exact
coverage, cost guidance, raw-data escape, and migration checklist.

## Upcoming preview: purpose-indexed multi-validator blueprints

An explicit `@MultiValidator` with supported `SPEND`, `MINT`, `WITHDRAW`, or
`CERTIFY` entrypoints now publish one standard CIP-57 validator entry per
purpose. The titles are purpose-qualified—for example `Protocol.spend` and
`Protocol.mint`—while every entry retains byte-identical compiled code and the
same Cardano script hash because they describe one deployed script.

Artifact consumers must migrate from the unsuffixed multi-validator title to
the exact purpose-qualified entry. `julc verify` and `julc verify init` instead
take the base Java title plus `--purpose`. Normal single-purpose titles and
script bytes are unchanged. Every newly generated blueprint now records an
explicit purpose on its datum, redeemer, and parameter arguments, so its JSON
changes even for an ordinary single-purpose validator.

`CERTIFY` is emitted as CIP-57 `publish`, following Aiken's mapping of the
ledger certificate purpose; the JuLC source-level name remains `CERTIFY`.
Manual dispatch remains fail-closed. Single-purpose and multi-purpose `VOTE`
or `PROPOSE` validators now also fail strict blueprint generation: older
previews emitted incomplete purpose-free metadata for these single-purpose
validators, but the pinned CIP-57 vocabulary cannot name them truthfully. Use
the blueprint opt-out to compile without metadata when needed. See the
[purpose-indexed blueprint guide](../guides/purpose-indexed-blueprints/) for
examples and limitations.

## Upcoming release: PV11 builtin contract correction

JuLC's current compiler targets the Plutus V3/PV11 feature set. This contract
was verified against Plutus 1.63.0.0
(`f92b7d7d82622a26caf456a6be33859f697e2cfc`), as shipped by cardano-node 11.0.1.

The released PV11 Batch 6 is exactly:

| Tags | Builtins | Specification |
|---|---|---|
| 87 | `ExpModInteger` | CIP-109 |
| 88 | `DropList` | CIP-132 |
| 89-91 | `LengthOfArray`, `ListToArray`, `IndexArray` | CIP-138 |
| 92-93 | BLS12-381 G1/G2 multi-scalar multiplication | CIP-133 |
| 94-100 | Native MaryEraValue operations | CIP-153 |

### `multiIndexArray` migration warning

Earlier JuLC previews exposed `Builtins.multiIndexArray` as if it were a PV11
builtin and could generate FLAT tag 101. Tag 101 is the future CIP-156 operation;
it is not part of PV11, and scripts containing it are not valid for that target.

The current compiler now rejects `multiIndexArray` with a compile-time diagnostic.
Recompile affected scripts and replace the call with repeated `IndexArray`
operations (for typed arrays, repeated `array.get(index)` calls). There is no
automatic bytecode migration for an already-generated tag-101 script.

The tag remains in JuLC's AST/FLAT support and Java VM for forward-development
experiments only. That experimental implementation does not make it ledger-valid
for PV11. It also retains JuLC's legacy `(array, indices)` argument order rather
than CIP-156's proposed indices-first signature, so it is not a conformant preview
of the future builtin.
