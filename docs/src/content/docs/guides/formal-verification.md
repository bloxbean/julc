---
title: "Formal Verification (Experimental)"
description: "Verify exact JuLC validator artifacts with the stable typed Java DSL, Lean, and IOG Blaster"
---

:::caution[Experimental verification feature]
JuLC's formal-verification integration remains experimental and is not a
production-safety certification. API v1 stabilizes the documented Java DSL and
schema-10 canonical meanings; it does not remove the compiler, model, solver,
or coverage limitations described below.
:::

JuLC can check a reviewed security property against the exact Plutus V3 UPLC
artifact produced by the compiler. The verification frontend is a closed typed
Java DSL; JuLC turns its canonical property IR into Lean, runs the pinned IOG
Blaster model, and writes a hash-bound result certificate.

The typed verification DSL is stable API version 1. Its current canonical
property schema is version 10. JuLC itself remains preview software: a stable
property API does not make every compiler feature production-ready and does not
mean that a successful property proves a contract generally safe.

## What verification establishes

For an `SMT-VALID` property, the core claim is:

> For the exact recorded UPLC artifact, every successful execution in the
> selected modeled domain and premises that completes within the recorded CEK
> fuel satisfies the versioned property. Recursive-schema experiments are also
> relative to their recorded decode depth.

`SMT-VALID` is a solver-validated result, not a theorem proved solely by Lean's
kernel. Blaster translates the modeled obligation to SMT and closes it through
a custom solver axiom. The result therefore trusts the pinned UPLC execution
and ledger models, JuLC's artifact binding and property translation, Blaster's
symbolic/Lean-to-SMT translation, and Z3. Separately generated codec, domain
bridge, and corollary lemmas are checked by Lean's kernel where the certificate
records them. A solver timeout changes whether JuLC can establish a result; it
does not define additional logical coverage.

The certificate binds the selected blueprint interface, compiled-code digest,
Cardano script hash, strict-boundary semantics, canonical property IR,
generated Lean tree, dependency commits, runner plan, backend identity, fuel,
and observed outcome.

It does **not** establish that:

- every relevant security property was specified;
- every transaction is Cardano-ledger-valid unless the selected property domain
  says so;
- fuel-exhausted paths are covered;
- Blaster supports every Plutus builtin;
- the compiler is generally semantics-preserving; or
- the contract is safe in every protocol or off-chain integration.

Use formal verification together with VM tests, property-based tests, budget
tests, integration tests, review, and deployment controls.

## Supported frontends

### Annotation profiles

Annotations are concise frontends over the same canonical typed DSL used by an
explicit specification. They do not change generated UPLC.

| Annotation/profile | Purpose | Reviewed property |
|---|---|---|
| `@RequiresSigner("datum.owner")` | spending | successful validation implies that the strictly decoded datum owner occurs in the complete transaction signatory list |
| `@ControlledMint(...)` | minting | the fixed authority signed and the current policy contains the configured token, quantity, and direction with no additional asset under that policy |
| `@RequiresSigner` + `@PreservesValue` + `@Monotonic` | spending | authorization, exactly one full-address continuing output, structural value and authority preservation, redeemer-committed successor state, and strict state increase |

The stateful annotations are an all-or-nothing profile. JuLC rejects a partial
combination instead of silently proving a weaker theorem.

### Typed Java DSL

The stable schema-10 DSL supports freely composed, admitted expressions over:

- compiler-projected datum and redeemer records, sealed variants, optionals,
  lists, maps, nested values, and productive recursion;
- spending, minting, rewarding, and certifying contexts;
- ordered transaction inputs, reference inputs, outputs, signatories,
  certificates, withdrawals, datums, and redeemers;
- duplicate-preserving list and association-map operations;
- distinct-identity authorization relations;
- certificate payloads, V3 governance transaction data, and reviewed raw-data
  adapters;
- explicit first-match, strict-summed, structural, and extensional multi-asset
  value meanings; and
- closed ledger-domain choices for the pinned CardanoLedgerApi model.

Voting and proposing validator **selection** is not supported yet. Properties
for those purposes fail closed rather than borrowing a misleading blueprint
interface. Arbitrary Lean text and user-defined AST nodes are not part of the
DSL.

## Install the CLI and choose a backend

JuLC's JVM tools require Java 25 or newer. Install the CLI with Homebrew:

```bash
brew install bloxbean/tap/julc
julc --version
```

For a source checkout, build and use the JVM CLI directly:

```bash
./gradlew :julc-cli:shadowJar
java -jar julc-cli/build/libs/julc.jar --version
```

The examples below use `julc`. Replace it with the `java -jar ...` command when
testing a checkout.

### Docker backend

Docker needs no host Lean or Z3 installation:

```bash
docker version
julc verify . --validator MyValidator --backend docker
```

The first run builds from a digest-pinned base with checksum-pinned Lean and Z3
inputs, records the resulting image ID, and acquires exact Lean dependencies,
so it can take several minutes. Distribution packages installed during the
image build are not individually version-pinned. Proof commands run with the
container network disabled after acquisition. Docker supports Linux container
targets `amd64` and `arm64`.

### Local backend

Install Git, `xxd`, and Lean/Lake 4.24.0, normally through `elan`:

```bash
export PATH="$HOME/.elan/bin:$PATH"
elan toolchain install leanprover/lean4:v4.24.0
lean --version
lake --version
git --version
xxd -h 2>&1 | head -n 1
```

JuLC accepts a system Z3 only at the pinned version. Otherwise it downloads the
official archive into the workspace-local `.julc/tools` cache, verifies its
SHA-256, and checks the executable version. Dependency acquisition requires
network access on the first run.

`--backend auto` prefers an exact local toolchain and otherwise tries Docker.
Use an explicit backend for CI and release evidence.

## Continue with a frontend

- [Annotation profiles](/guides/formal-verification/annotation-profiles/)
  provide the shortest path for reviewed signer, state-transition, and
  controlled-mint properties.
- [Typed Java DSL](/guides/formal-verification/typed-dsl/) covers custom
  composition, generated contract metamodels, stable API boundaries, and
  ledger domains.

## Re-run or regenerate a workspace

The default output is `verification/<artifact-id>`. JuLC refuses to overwrite a
non-empty workspace unless regeneration is explicit:

```bash
# Rebuild the contract and regenerate generator-owned files.
julc verify . --validator AuthorizedStateValidator --backend local --force

# Re-run the already generated and hash-bound workspace without rebuilding.
julc verify run verification/authorized-state-validator --backend local
```

Use `--force` after source, compiler, annotation, property, or generation-input
changes. Use `verify run` to reproduce the same workspace and plan.

## Outcomes and exit codes

| Outcome | Exit | Meaning |
|---|---:|---|
| `SMT-VALID` | 0 | Blaster established the bounded solver obligation |
| `KERNEL-PROVED` | 0 | Lean's kernel checked the classified theorem |
| `REFUTED` | 3 | Blaster found a symbolic countermodel to the translated modeled obligation |
| `UNDETERMINED` | 2 | the bounded procedure did not determine the property |
| `COULD-NOT-EVALUATE` | 2 | the property was vacuous, unsupported, incomplete, or otherwise not established |

CLI argument, build, and workspace-generation failures exit with code 1. Once
the managed runner starts, fail-closed preflight, backend, tool acquisition,
timeout, unsupported-result, and similar failures normally produce
`COULD-NOT-EVALUATE` and exit 2. Do not reinterpret exit 2 as success. An
expected negative control is evidence about the verification pipeline, not a
certificate for a vulnerable validator.

## Fuel, recursion, and performance

`--fuel` bounds exact UPLC preprocessing and execution inside the obligation.
An `SMT-VALID` result covers only successful paths completing within the
recorded bound. `--recursive-depth` controls generated recursive-schema
experiments; it is separate from UPLC fuel and is not a validator rejection
condition.

The first run is normally dominated by pinned Lean dependency acquisition and
build. Later runs reuse the workspace cache. Solver time can still vary widely
with formula structure: a supported property may legitimately end as
`UNDETERMINED` or `COULD-NOT-EVALUATE` rather than completing a proof.

## CI evidence

Use explicit inputs and archive the complete result workspace or at least the
certificate and referenced logs:

```bash
julc verify . \
  --validator AuthorizedStateValidator \
  --backend docker \
  --fuel 1000 \
  --out-dir verification/ci-authorized \
  --force
```

Review `verification-result.json`, not only console text. It is the
machine-readable statement of the artifact, property, domain, bounds, backend,
phase outcomes, counterexample qualification, and generated-source hashes.

## Related guides

- [Strict data boundaries](/guides/strict-data-boundaries/) explains the
  canonical datum/redeemer semantics assumed by generated contract types.
- [Purpose-indexed multi-validator blueprints](/guides/purpose-indexed-blueprints/)
  explains exact interface selection for a shared script.
- [Testing](/guides/testing-guide/) covers VM, property-based, budget, and
  integration tests that complement formal verification.
- [Troubleshooting](/reference/troubleshooting/#7-formal-verification) covers
  common toolchain, workspace, solver, and result issues.
