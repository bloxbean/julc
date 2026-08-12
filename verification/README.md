# Getting Started with JuLC Verification

This guide describes the verification functionality that is implemented in
this repository today. It deliberately separates working evidence from planned
automation.

## What is available today

There are two ways to explore the current integration:

1. `verification/blaster` is the committed Milestone A/B evidence suite. It
   verifies the repository's state-thread and controlled-mint fixtures against
   their exact compiled UPLC artifacts. This is the quickest way to reproduce
   an existing successful result.
2. `julc verify init` is the Milestone C adoption workflow. It takes a built
   JuLC validator and generates a pinned Lean/Blaster workspace for it. The
   generated security property is intentionally false and unproved: a contract
   author must replace it with a reviewed, contract-specific property and add a
   negative control.

The integration does not prove that every JuLC program is safe. It checks
explicit properties for one exact compiled artifact. Solver-valid Blaster
results are reported as `SMT-VALID`; only ordinary Lean proofs that do not rely
on Blaster are `KERNEL-PROVED`.

## Prerequisites

For the generated-workspace flow, install:

- JuLC built from this branch;
- Lean 4.24.0 and Lake, normally through `elan`;
- Z3 4.15.2;
- Git, ripgrep (`rg`), and `xxd`; and
- network access for the initial Lake dependency acquisition.

You do not install the three Blaster projects manually. The generated
`lakefile.lean` pins them and Lake fetches them. The generated verification
script checks tool versions, dependency revisions, and the artifact hash before
compiling any claim.

To build the JuLC CLI from this repository:

```bash
./gradlew :julc-cli:shadowJar
java -jar julc-cli/build/libs/julc.jar --help
```

The examples below use `julc`; substitute
`java -jar /path/to/julc.jar` when running the development JAR directly.

## Generate a workspace for a validator

From a JuLC project containing `julc.toml`:

```bash
julc build
julc verify init . --validator MyValidator --purpose spending
```

`julc build` derives the datum, redeemer, and parameter schemas from the same
resolved compiler type model that produces UPLC. Its generated blueprint can
faithfully describe nonrecursive records and sealed variants containing
integers, byte strings, strings, booleans, lists, maps, optional values, and
nested combinations of those types. A successful build validates the document
offline against the repository-pinned CIP-57 meta-schema.

For a minting policy, use `--purpose minting`. The validator title must exactly
match its title in `build/plutus/plutus.json`.

The command validates the Plutus V3 artifact and supported-builtin inventory,
then creates:

```text
verification/<artifact-id>/
├── artifacts/                  exact compiledCode bytes
├── GeneratedSchemas.lean      strict datum/redeemer encodings
├── CheckedExecution.lean      fixed evaluation semantics and fuel handling
├── PropertyTemplates.lean     reusable predicates
├── SecurityProperty.lean      user-owned property
├── *Verification.lean         artifact-specific harness
├── lakefile.lean              pinned Blaster dependencies
├── lean-toolchain             pinned Lean version
└── scripts/verify.sh          fail-closed verification driver
```

Acquire the pinned dependencies and compile the generated workspace:

```bash
cd verification/<artifact-id>
lake update
scripts/verify.sh
```

The initial result is expected to be:

```text
COULD-NOT-EVALUATE: workspace compiles; specialize securityProperty and add a theorem plus negative control
```

That message is a safety property of the workflow, not a failed proof. The
generator will not turn a generic template into a security claim.

## Authoring the actual property

Edit the generated, user-owned `SecurityProperty.lean` and express the
validator's threat-model property over the Cardano `ScriptContext`. Add both:

- a theorem for the intended validator; and
- a negative control that produces a counterexample for a deliberately broken
  validator or property.

Regeneration with `--force` preserves `SecurityProperty.lean`, but review the
generated diff and artifact identity whenever the Java source or compiler
changes.

At present this step requires Lean and Cardano ledger-model knowledge. A future
`julc verify run` or containerized backend is intended to provision and execute
the pinned toolchain, but it is not implemented yet.

## Supported boundary today

There are currently two related but different support boundaries.

### Blueprint generation

Normal `julc build` supports:

- explicitly opaque `PlutusData`;
- integers, byte strings, strings, and booleans;
- lists, maps, optional values, and arbitrary nonrecursive nesting;
- named single-constructor records and sealed multi-constructor variants; and
- compiler-supported references between named, nonrecursive definitions.

Unsupported or ambiguous boundary types fail blueprint generation at their
Java source location. They are never silently described as opaque data.

If a project deliberately does not need a blueprint—for example, Java
off-chain code will deploy the raw script and no schema-dependent tooling will
consume it—the explicit escape hatch is:

```bash
julc build --no-blueprint
```

`--skip-blueprint` is an alias. This produces `.uplc`,
`.compiledCode.hex`, and `.script-hash` files under `build/plutus`, removes any
stale `plutus.json`, and preserves the exact compiled script bytes. It cannot
be used with `julc verify init`, typed-client generation, or any other workflow
that requires CIP-57 metadata.

The same deliberate opt-out is available outside the CLI:

```groovy
// build.gradle
julc {
    blueprint = false
}
```

For direct javac/annotation-processor use, pass
`-Ajulc.blueprint=false`. For the Playground compile endpoint, send
`"blueprint": false` in the request. These modes still compile and return or
write deployable script bytes; they omit the aggregate CIP-57 document. They
therefore cannot initialize a verification workspace.

When changing a direct javac or Maven annotation-processor build from blueprint
enabled to `-Ajulc.blueprint=false`, clean the class-output directory first.
The standard annotation-processing `Filer` API cannot delete a `plutus.json`
written by an earlier compiler invocation. The JuLC Gradle plugin performs this
cleanup at its task boundary; direct processor users must use a clean build.

Normal strict builds are fail-closed and transactional at the CLI artifact
boundary: JuLC validates all contract schemas before publishing new raw files
or `plutus.json`. If compilation or schema generation fails, an existing
complete last-good build is retained. A successful build after all validator
sources are removed clears the generated raw artifacts and blueprint.

### Generated Lean workspace

The generated-workspace flow currently supports:

- Plutus V3 spending validators and minting policies;
- named single-constructor records and sealed multi-constructor variants;
- integer and byte-string fields, plus supported references between named
  nonrecursive definitions;
- the exact UPLC stored in `build/plutus/plutus.json`; and
- UPLC builtin tags 0–88 and 92–93 under the pinned Blaster profile.

It currently rejects or cannot evaluate:

- lists, maps, optional values, booleans in generated Lean schemas, and
  recursive schemas;
- unnamed or opaque datum/redeemer schema shapes;
- builtin tags outside the pinned coverage set;
- non-V3 artifacts; and
- any workspace whose artifact hash, dependency revision, or tool version does
  not match its manifest.

Blueprint support for those containers is complete; translating them into
strict Lean encoders/decoders is Milestone C.2. Productive recursion remains
Milestone C.3. Both are tracked in
[`ADR-004`](../adr/verification/004-milestone-c-reusable-verification-integration.md).

## Reproduce the committed evidence suite

The Milestone A/B suite is separate from a generated user workspace and already
contains reviewed properties and negative controls:

```bash
verification/blaster/scripts/acquire-dependencies.sh
verification/blaster/scripts/verify-offline.sh
```

See [`verification/blaster/README.md`](blaster/README.md) for its additional
JDK and `jq` prerequisites, the individual commands, verified properties, and
known limitations.

## Interpreting results

- `SMT-VALID`: Blaster translated the property to SMT and Z3 found it valid.
  This trusts Blaster's translation and Z3; it is not a reconstructed Lean
  kernel proof.
- `KERNEL-PROVED`: an ordinary Lean proof was checked without the Blaster
  solver axiom.
- `REFUTED`: a concrete counterexample violates the proposed property.
- `COULD-NOT-EVALUATE`: the property is unfinished, fuel was exhausted, or a
  tool, artifact, schema, builtin, or dependency was outside the pinned gate.

Never interpret `COULD-NOT-EVALUATE` as success, or the absence of a
counterexample as proof.
