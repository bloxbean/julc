---
title: "Release Notes"
description: "JuLC release notes and migration guidance"
---

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
for PV11.
