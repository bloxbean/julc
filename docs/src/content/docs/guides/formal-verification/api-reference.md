---
title: "API and DSL Reference"
description: "Experimental JuLC formal-verification annotations, typed Java DSL, and CLI reference"
---

:::caution[Experimental verification feature]
The documented Java construction API and canonical schema-1 meanings are
stable as API v1. The verifier, compiler, pinned ledger model, Blaster
translation, and solver integration remain experimental and are not a general
contract-safety certification.
:::

This page covers the off-chain APIs in `julc-verification`. None of these
annotations or property builders changes compiler lowering or emitted UPLC.

## Verification annotations

| Annotation | Purpose | Admitted form |
|---|---|---|
| `@RequiresSigner("datum.<field>")` | spending | The selected datum field resolves to a supported byte-string/key-hash authority. |
| `@ControlledMint(authority=..., tokenName=..., quantity=..., action=...)` | minting | Fixed 28-byte authority, token name up to 32 bytes, and a strictly positive magnitude interpreted according to `MINT` or `BURN`. |
| `@PreservesValue(output=SINGLE_CONTINUING_OUTPUT)` | spending | Accepted only as part of the complete stateful profile. |
| `@Monotonic(current=..., next=..., relation=GREATER_THAN)` | spending | Accepted only as part of the complete stateful profile. |

The complete stateful profile is `@RequiresSigner + @PreservesValue +
@Monotonic`. Partial combinations fail closed. Every profile lowers to the
same canonical typed DSL IR used by a direct Java specification; annotations
do not own separate Lean security formulas.

See [Annotation Profiles](../annotation-profiles/) for complete examples and
profile-specific limitations.

## Stable construction API v1

Canonical property documents use:

```json
{
  "format": "julc.verification.dsl",
  "schemaVersion": 1
}
```

Earlier E.2–E.4 schema numbers were unreleased milestone gates. They are not
selectable, generated, or accepted as current property input.

| Type or surface | Role |
|---|---|
| `VerificationSpecification` | Trusted Java property-builder entry point. |
| `VerificationDsl` | Property, integer, boolean, bytes, key-hash, token-name, policy-ID, and output-reference factories. |
| Typed wrappers in `com.bloxbean.cardano.julc.verification.dsl` | Closed expressions for booleans, integers, bytes, options, lists, maps, contract types, ledger data, authorization, certificates, values, and governance data. |
| `DslProperty` | One named guarantee with an explicit modeled domain. |
| `DslPropertySet` | Canonical schema-1 envelope; generated models normally construct it through `contract.properties(...)`. |
| `DslPurpose` | `SPENDING`, `MINTING`, `REWARDING`, or `CERTIFYING`. |
| `DslDomain` | `NONE` or one of the four pinned V3 ledger domains. |
| Generated contract metamodel | Compiler-owned datum/redeemer types and purpose-specific context roots. |

Concrete node classes under `verification.dsl.ir` are serialization
infrastructure, except for the documented property envelope and enums.
Renderers, validators, promotion internals, worker protocols, arbitrary Lean,
and user-defined AST node kinds are not supported construction APIs.

## Property factories

Common static imports from `VerificationDsl` include:

| Factory | Result |
|---|---|
| `property(id, domain, guarantee)` | A named, domain-qualified property. |
| `bool(value)` | Boolean literal expression. |
| `integer(value)` | Canonical bounded integer literal expression. |
| `bytes(hex)` | Byte-string literal expression. |
| `keyHash(hex)` | Fixed public-key-hash literal for admitted signer operations. |
| `tokenName(hex)` | Token-name literal. |
| `policyId(hex)` | Policy-ID literal where the selected operation admits a fixed policy. |
| `txOutRef(transactionIdHex, index)` | Transaction-output-reference literal. |

Generated wrappers expose only operations admitted for their compiler-owned
type. Options require `exists`, `isPresent`, or `isEmpty`; sealed variants
require the generated guarded eliminator; list and map operations preserve
order and duplicates unless a method explicitly states different semantics.

See [Typed Java DSL](../typed-dsl/) for composition examples and the supported
Cardano surface.

## CLI

| Command | Purpose |
|---|---|
| `julc verify . --validator <name>` | Build and verify a supported annotation profile. |
| `julc verify dsl-init . --validator <name> ...` | Generate the API-v1/schema-1 contract metamodel. |
| `julc verify dsl . --validator <name> ...` | Execute a trusted Java specification, admit its canonical IR, and verify it. |
| `julc verify run <workspace>` | Re-run an existing current hash-bound workspace without rebuilding the contract. |

For a purpose-indexed validator, pass one of `spending`, `minting`,
`rewarding`, or `certifying` through `--purpose`. Voting and proposing
verification selection currently fails closed.

The native CLI still launches a bounded child JVM for
`VerificationSpecification`, because the specification is trusted project
Java. Supply the compiled specification, generated model, and JuLC JAR through
`--spec-classpath`.

## Result contract

`SMT-VALID`, `REFUTED`, and `COULD-NOT-EVALUATE` are distinct outcomes. Always
read `verification-result.json` for the exact artifact, property, modeled
domain, fuel/decode bounds, backend inputs, and counterexample qualifications.
An SMT-valid property is not a claim that the complete contract is safe.

See [Troubleshooting](../troubleshooting/) for workspace, toolchain, solver, and
classification failures.
