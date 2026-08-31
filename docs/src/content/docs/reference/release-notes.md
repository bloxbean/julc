---
title: "Release Notes"
description: "JuLC release notes and migration guidance"
---

## Upcoming preview: stable typed formal-verification API v1

JuLC now provides a stable construction API for exact-artifact formal
verification. API version 1 uses canonical property schema 1 and supports
typed composition over compiler-projected contract data, four standard Plutus
purposes (spending, minting, rewarding, and certifying), transaction context,
authorization, certificates, multi-asset values, governance data, and reviewed
raw-data adapters.

`julc verify dsl-init` emits only schema 1. The unreleased E.2–E.4 milestone
formats previously numbered 1 through 10 have been removed from public
generation and replay rather than carried as compatibility aliases. Regenerate
those experimental workspaces with the current CLI. Historical certificates
remain hash-bound records of their original runs, but they are not accepted as
current replay inputs. New semantic vocabulary requires a new schema rather
than changing schema-1 meanings.

`@RequiresSigner`, `@ControlledMint`, and the complete
`@RequiresSigner + @PreservesValue + @Monotonic` stateful profile now lower to
the same canonical DSL IR used by explicit Java specifications. The former
annotation-specific duplicate Lean security formulas have been removed. These
annotations remain UPLC-neutral: they state properties and do not add checks to
validator execution.

The stable API covers documented Java construction and canonical property
semantics. It does not promise solver termination, complete CardanoLedgerApi
coverage, general compiler correctness, or that a verified validator is safe
under every unspecified property. Voting/proposing validator selection,
parameter-derived authorities, arbitrary Lean in the DSL, and temporal
verification are not included. The E.5 bounded temporal experiment did not
meet its solver-calibration gate and was not promoted.

See the [formal verification guide](/guides/formal-verification/) for annotation
and DSL workflows, local/Docker backends, exact certificate scope, outcome
classification, and CI guidance.

## Upcoming preview: explicit PV11 compiler target

JuLC compilation now resolves one explicit, immutable compiler profile:
`plutus-v3-pv11-uplc-1.1.0`. The profile covers the Plutus language, ledger
protocol version, and UPLC version together. Existing compile entry points
select this named profile by default; unknown profiles, future protocol
versions, and a `latest` alias are rejected rather than interpreted as PV11.

`CompileResult` now has a public `target()` record component containing this
provenance. Compatibility constructors preserve existing constructor call
sites by assigning the documented PV11 target, but code that inspects the
record shape, uses record patterns, or depends on generated record equality or
string representation must account for the additional component. Compiler
target plumbing and validation alone do not change generated UPLC, FLAT bytes,
or script hashes.

Testkit operations that retain a `CompileResult` now pass its exact ledger
target to the selected VM. With the Java VM, this means source-based validator
and method evaluation uses explicit PV11 semantics and costs instead of the
legacy language-only path, which defaults to PV10 when no protocol-aware cost
model is configured. A caller may also provide an explicit evaluation target;
it must match `CompileResult.target()` or evaluation fails before VM execution.

The Scalus adapter now implements JuLC's explicit-target VM SPI. Scalus 1.1.0
has no certified target in JuLC, so `ValidatorTest.evaluate(result, ...)` with
Scalus as the only provider returns a deterministic zero-budget
`EvalResult.Failure` prefixed `Unsupported Scalus ledger target:` rather than
throwing `UnsupportedOperationException`. For a Scalus compatibility
cross-check, evaluate `result.program()` through the language-only overload.
The Java VM supports both forms, although the raw `Program` form cannot carry
compiler-target provenance. See
[ADR-033's certification evidence](https://github.com/bloxbean/julc/blob/main/adr/033-scalus-protocol-aware-ledger-target-evaluation.md#certification-evidence).

The CLI, Gradle plugin, annotation processor, JRL compiler, and MCP tools accept
and report the same stable profile ID. Supporting a later protocol version will
add and verify a separate pinned profile; it will not silently change the PV11
default.

## Upcoming preview: Scalus protocol-aware evaluation (ADR-033)

The Scalus adapter now has atomic per-language configurations, target-bound
V1/V2/V3 VM construction, and one validated explicit-target pipeline. Public
explicit-target calls remain fail-closed because five reason-coded upstream
Scalus 1.1.0 divergences prevent V3/PV10 and V3/PV11 certification.

Cardano Client Lib continues to supply current protocol-parameter cost arrays
for V1, V2, and V3. Configured Scalus language-only evaluation now passes all
three arrays to `MachineParams.fromCostModels` with the matching language and
protocol. Compared with `main`, this changes configured V1/V2 transaction
evaluation from Scalus's bundled mainnet defaults to the current live model.
Scalus consumes the mapped live V1/V2 prices; its 1.1.0 V1/V2
adapters still reference-fill PV11-only builtin costs and ignore audited
Constr/Case positions, so that compatibility behavior is not a complete
ledger-parity claim. Pinned conformance vectors are test evidence only and are
never substituted for runtime protocol parameters.

A non-null `ExBudget` is now enforced; exceeding it returns
`EvalResult.BudgetExhausted`. Array and Value results now cross the Scalus
bridge. Unconfigured language-only V3 retains Scalus 1.1.0's PV11/E bundled
default, which differs from Java/Truffle's PV10 compatibility default. These VM
changes do not modify compiled UPLC, FLAT bytes, or script hashes.

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

## Upcoming preview: target-aware PV11 optimizations

ADR-032 adds an explicit optimizer rollout boundary without adding another
compiler target. The legal output target remains
`plutus-v3-pv11-uplc-1.1.0`. After the initial opt-in review window and full
semantic, benchmark, repository, and hosted-verification gates, `pv11-safe`
is now the default and enables these reviewed rules:

- `JulcList.drop(...)` lowers directly to PV11 `DropList`;
- typed boolean conditionals lower to UPLC 1.1.0 `Case Bool`;
- successful all-literal `ExpModInteger` calls fold at compile time while
  invalid literal calls retain their exact runtime failure.

The experimental native Value surface now uses a distinct `JulcValue` type.
Conversion from and to ledger `Data` is explicit, and the compiler rejects
native/Data mixing at assignments, equality, Data-backed containers/records,
and external method or validator boundaries. These checks apply at every
optimization level, including `baseline`. This type correction adds no UPLC
cost by itself and does not automatically rewrite existing Data-encoded ledger
Value operations.

The Scalus backend is pinned to the stable 1.1.0 release. Focused PV11
cross-checks now cover `Case Bool` false/true branch order and confirm that an
unselected failing branch is not evaluated. Scalus remains a language-only
compatibility backend in JuLC; protocol/cost provenance still belongs to the
canonical target-aware evaluation path. Cross-backend budget tests therefore
configure one explicit cost profile on both VMs rather than treating Scalus's
version-dependent built-in default as ledger evidence.

Measurements use the immutable
`cardano-node-11.0.1-plutus-v3-pv11` cost profile (parameter SHA-256
`40ea9e0b7df77a7bd2cb7d4e4d9da040f8bee7ff0324a7cdb7e51702330e43a8`).
Java and Truffle produced identical results, failures, traces, and ledger
budgets for these fixtures:

| Fixture | FLAT bytes | CPU | Memory |
|---|---:|---:|---:|
| one-element `drop` path | 162 → 109 | 3,886,301 → 2,311,844 | 17,931 → 10,864 |
| three-condition Bool fixture | 134 → 117 | 2,251,555 → 1,687,408 | 9,694 → 7,591 |
| three literal ExpMod calls | 42 → 6 | 3,406,498 → 16,100 | 3,407 → 200 |
| aggregate validator-like accepted path | 169 → 88 | 5,346,583 → 1,935,936 | 19,605 → 8,533 |

The aggregate fixture's script hash changes from
`3d1e9ac3561e68d3d0864705686adca6105ff5fc15f5a43e904de1f2` to
`2f2ea7f79a5dd083ed51e573dc247267ae7cb978ecc615692479c594`.
These are exact fixture results rather than general percentage guarantees.
Default recompilation now produces a new deployment artifact; it does not
change an already deployed script. Select `baseline` explicitly when
reproducing the earlier ADR-031 lowering and hash.

Raw `JulcVm` language-only evaluation overloads retain the deliberate PV10
compatibility default from ADR-030. Because default compiler output may now
contain PV11-only `Case Bool` or `DropList`, evaluate a `CompileResult` through
its `result.target().ledgerTarget()`. JuLC testkit and CLI evaluation propagate
that target automatically; MCP test/evaluate and the playground do the same.
The decompiler also recovers the PV11 `Case Bool` form as a Java conditional
only when its untyped UPLC scrutinee is provably Boolean, leaving ambiguous SOP
cases as switches.

Array promotion/folding, native Value algebra, BLS fusion, list/pair/integer/
unit Case rewrites, and general conversion sharing remain explicitly deferred:
the current compiler lacks the typed literal, representation, or use-analysis
proof needed to preserve failures and strict evaluation. A future protocol
target starts with ADR-032 rules disabled and enables each rule only after its
legality and semantics are revalidated; cost-directed rules additionally need
a new pinned cost profile.

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
