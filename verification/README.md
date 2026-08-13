# Getting Started with JuLC Verification

This guide describes the verification functionality that is implemented in
this repository today. It deliberately separates working evidence from planned
automation.

## What is available today

There are seven ways to explore the current integration. `julc verify run`
provides the managed execution path for both a generated workspace and the
committed evidence suite:

1. `verification/blaster` is the committed Milestone A/B evidence suite. It
   verifies the repository's state-thread and controlled-mint fixtures against
   their exact compiled UPLC artifacts. This is the quickest way to reproduce
   an existing successful result.
2. `julc verify init` is the Milestone C adoption workflow. It takes a built
   JuLC validator and generates a pinned Lean/Blaster workspace for it. The
   generated security property is intentionally false and unproved: a contract
   author must replace it with a reviewed, contract-specific property and add a
   negative control.
3. `verification/c2` is the committed Milestone C.2 codec evidence. It
   generates real spending and minting workspaces and checks the exact boolean,
   optional, list, map, and nested decoders emitted by `julc verify init`.
4. `verification/c3` is the Milestone C.3 productive-recursion evidence. It
   builds recursive Java contracts, generates recursive CIP-57 and Lean codecs,
   and kernel-checks concrete round trips plus an unbounded codec-composition
   theorem proved by induction.
5. `julc verify --validator <Name>` is the Milestone C.5 Java-only vertical
   slice for `@RequiresSigner("datum.owner")`. It builds the exact artifact,
   resolves the annotation through compiler-owned types, generates the Lean
   theorem and non-vacuity control, runs Blaster, and writes a certificate.
   `verification/c5` contains authorized, vulnerable, and vacuous controls.
6. `verification/c6` is the C.6 stateful-spending profile. It composes signer
   authorization, strict state increase, one continuing output, successor
   datum commitment, authority retention, and structural value preservation.
7. `verification/c7` is the C.7 controlled-mint profile. It proves fixed
   authority, current-policy linkage, exact token/quantity/action, and no extra
   raw assets under the current policy for both mint and burn fixtures.

The integration does not prove that every JuLC program is safe. It checks
explicit properties for one exact compiled artifact. Solver-valid Blaster
results are reported as `SMT-VALID`; only ordinary Lean proofs that do not rely
on Blaster are `KERNEL-PROVED`.

## Prerequisites

The native local path and optional Docker path use the same authenticated
runner plan and result classification. The Docker backend installs the pinned
Lean/Z3 toolchain in its image and runs proof steps with container networking
disabled.

For a native local run, install:

- JuLC built from this branch;
- Lean 4.24.0 and Lake, normally through `elan`;
- Z3 4.15.2, or allow JuLC to provision its checksum-pinned release into the
  workspace-local tool cache;
- Git, ripgrep (`rg`), and `xxd`; and
- network access for the initial Lake dependency acquisition.

You do not install the three Blaster projects manually with either backend. The generated
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

## Verify a required signer without writing Lean

Add the optional verification module to the validator project's Java
dependencies and annotate one spending validator:

```groovy
dependencies {
    implementation "com.bloxbean.cardano:julc-verification:${julcVersion}"
}
```

```java
import com.bloxbean.cardano.julc.verification.annotation.RequiresSigner;

@RequiresSigner("datum.owner")
@SpendingValidator
class AuthorizedStateValidator {
    record Datum(byte[] owner) {}
    // ...
}
```

Then run from the project directory:

```bash
julc verify --validator AuthorizedStateValidator --backend local
```

The normal command performs the build, property resolution, deterministic
workspace generation, non-vacuity check, proof/counterexample query, and
certificate generation. Developers do not edit Lean or invoke Lake. The
result is written under `verification/<artifact-id>/verification-result.json`.
The manifest binds the exact artifact, typed property IR, runner scripts, and
generated Lean source hash; post-generation edits fail closed.

C.5 supports exactly one direct datum field that resolves to `byte[]` or a
compiler key-hash type. Nested paths, optionals, and multiple authorities fail
closed for this template. Stateful spending and fixed controlled minting use
their separately versioned C.6 and C.7 profiles below.

The guarantee is strict: if the exact artifact succeeds, the context must have
an attached datum that strictly matches the CIP-57 constructor and arity, and
its owner must occur anywhere in `txInfo.signatories`. JuLC's current on-chain
record projection is more permissive about constructor tags and trailing
fields. Consequently, merely calling `ContextsLib.signedBy` can be refuted on a
malformed datum. The positive C.5 fixture explicitly validates its raw attached
datum shape; see
[`AuthorizedStateValidator.java`](c5/fixtures/authorized/src/AuthorizedStateValidator.java).
The tool does not assume malformed inputs away, and C.5 does not change the
core compiler or emitted UPLC merely because the annotation is present.

Reproduce all C.5 controls with:

```bash
verification/c5/scripts/verify.sh
```

An `SMT-VALID` result establishes only `julc.requires-signer/v1` for the exact
artifact under the recorded bounds and trust model. Ledger validity is not
modeled, and the certificate does not claim that the entire contract is safe.
In particular, it covers only executions that complete within the certificate's
pinned CEK `fuel` bound. Paths that exhaust that bound are outside the claim.

## Verify a complete state transition without writing Lean

Use the three-annotation profile shown in
[`verification/c6/README.md`](c6/README.md), then run:

```bash
julc verify --validator StateMachine --backend local --fuel 3000
```

The datum must directly contain byte-string authority and integer current-state
selections; the redeemer must directly contain the integer next-state
selection. The v1 profile fixes `GREATER_THAN` and
`SINGLE_CONTINUING_OUTPUT`. It proves strict decoding, complete-list signing,
one same-full-address successor, structural input/output value equality,
authority retention, successor/redeemer commitment, and strict increase.

Run all positive, refuted, and vacuous C.6 controls with:

```bash
verification/c6/scripts/verify.sh
```

## Verify a controlled mint or burn without writing Lean

Declare fixed property literals rather than choosing authority from an
untrusted redeemer:

```java
@ControlledMint(
    authority="4a554c435f5645524946595f415554484f524954595f303030303031",
    tokenName="4a554c43", quantity=1, action=MintAction.MINT)
@MintingValidator
class ControlledTokenPolicy { /* ... */ }
```

Then run `julc verify --validator ControlledTokenPolicy --fuel 5000`. The v1
profile requires exactly one raw entry for the `MintingScript` policy and one
configured token beneath it; entries for other policies remain permitted.
Use `MintAction.BURN` with the same positive magnitude to generate a negative
expected quantity. See [`verification/c7/README.md`](c7/README.md) and run:

```bash
verification/c7/scripts/verify.sh
```

## Generate a workspace for a validator

From a JuLC project containing `julc.toml`:

```bash
julc build
julc verify init . --validator MyValidator --purpose spending \
  --recursive-depth 4
```

`julc build` derives the datum, redeemer, and parameter schemas from the same
resolved compiler type model that produces UPLC. Its generated blueprint can
faithfully describe records and sealed variants containing
integers, byte strings, strings, booleans, lists, maps, optional values, and
nested combinations of those types. Productive self and mutual recursion is
supported. A successful build validates the document offline against the
repository-pinned CIP-57 meta-schema.

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
├── verification-runner.json   versioned managed execution plan
└── scripts/verify.sh          fail-closed expert/audit driver
```

Run the managed workflow from the project root. `auto` uses the exact local
toolchain when available and otherwise uses Docker:

```bash
julc verify run verification/<artifact-id>
```

Choose a backend explicitly when needed:

```bash
julc verify run verification/<artifact-id> --backend local
julc verify run verification/<artifact-id> --backend docker
```

The command writes `verification-result.json` plus acquisition and proof logs
under `verification-results/`. The initial generated workspace truthfully exits
2 with:

```text
COULD-NOT-EVALUATE: workspace compiles; specialize securityProperty and add a theorem plus negative control
```

For auditing or debugging, the transparent low-level sequence remains:

```bash
cd verification/<artifact-id>
lake update
scripts/verify.sh
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

Untemplated properties still require Lean and Cardano ledger-model knowledge.
`julc verify run` manages their execution and classification; it does not
invent a threat model. The C.5–C.7 annotation profiles are the reviewed,
versioned exceptions with generated theorems.

## Supported boundary today

There are currently two related but different support boundaries.

### Blueprint generation

Normal `julc build` supports:

- explicitly opaque `PlutusData`;
- integers, byte strings, strings, and booleans;
- lists, maps, optional values, and arbitrary nesting;
- named single-constructor records and sealed multi-constructor variants; and
- productive self and mutual references between named definitions.

A recursive type must have a finite construction path. This is supported:

```java
sealed interface Node permits End, Cons {}
record End() implements Node {}
record Cons(BigInteger value, Node next) implements Node {}
```

This is rejected at its Java source location because it has no finite value
without relying on `null`:

```java
record Bad(BigInteger value, Bad next) {}
```

Recursion under `Optional<T>`, `List<T>`, and `Map<K,V>` is also supported;
their empty constructors provide a finite base value.

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
- integers, byte strings, booleans, optional values, lists, maps, and arbitrary
  nesting of those forms;
- productive self-recursive and mutually recursive named definitions;
- the exact UPLC stored in `build/plutus/plutus.json`; and
- UPLC builtin tags 0–88 and 92–93 under the pinned Blaster profile.

It currently rejects or cannot evaluate:

- nonproductive recursive cycles and recursive aliases that are not named
  constructor definitions;
- unnamed or opaque datum/redeemer schema shapes;
- builtin tags outside the pinned coverage set;
- non-V3 artifacts; and
- any workspace whose artifact hash, dependency revision, or tool version does
  not match its manifest.

Generated Lean uses `JulcList α` for Plutus `Data.List` and `JulcMap κ υ` for
Plutus `Data.Map`. `JulcMap` is an ordered association list: duplicate keys are
validated and preserved, matching JuLC's compiler/runtime behavior rather than
adding a Java `Map` uniqueness assumption. Equality is structural
association-list equality, so entry order and duplicates matter even when two
values have the same lookup behavior.

`--recursive-depth` is a positive bound for generated recursive-domain
experiments. It is recorded separately from `--fuel`, which controls CEK
preprocessing/evaluation. Reaching either limit is `COULD-NOT-EVALUATE`, not a
validator rejection or a proof. General recursive claims still require a Lean
induction theorem and must be reported separately from bounded Blaster results.

## Reproduce the Milestone C.2 codec evidence

The C.2 suite builds real JuLC container contracts, runs `julc verify init`,
checks dependency revisions, compiles spending and minting harnesses, and
compiles strict positive and malformed codec examples:

```bash
verification/c2/scripts/verify.sh
```

See [`verification/c2/README.md`](c2/README.md) for the tested cases and map
semantics.

## Reproduce the Milestone C.3 recursion evidence

The C.3 suite builds real productive recursive spending and minting contracts,
runs `julc verify init --recursive-depth 4`, compiles both pinned Lean/Blaster
workspaces, checks strict malformed/depth-exhaustion cases, preserves duplicate
map entries, and kernel-checks unbounded `decode(encode(value))` theorems,
including the actual generated `IsData` instance path:

```bash
verification/c3/scripts/verify.sh
```

The expected terminal result is:

```text
ESTABLISHED: Milestone C.3 recursive schemas, codecs, depth, and induction compile
```

See [`verification/c3/README.md`](c3/README.md) and
[`ADR-008`](../adr/verification/008-milestone-c3-productive-recursive-adts.md).

## Reproduce the committed evidence suite

The Milestone A/B suite is separate from a generated user workspace and already
contains reviewed properties and negative controls:

```bash
verification/blaster/scripts/acquire-dependencies.sh
verification/blaster/scripts/verify-offline.sh
```

Or run the same reviewed suite through the managed local runner:

```bash
julc verify run verification/blaster --backend local
```

This legacy repository suite rebuilds Java fixtures outside a standalone
verification workspace, so its Docker backend is intentionally disabled. Newly
generated workspaces support both local and Docker execution.

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
