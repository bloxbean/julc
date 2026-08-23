---
title: "Formal Verification"
description: "Verify exact JuLC validator artifacts with the stable typed Java DSL, Lean, and IOG Blaster"
---

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

## Verify an annotation profile

The shortest example is a spending validator whose datum owner must sign:

```java
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import com.bloxbean.cardano.julc.verification.annotation.RequiresSigner;

@RequiresSigner("datum.owner")
@SpendingValidator
class AuthorizedStateValidator {
    record Datum(byte[] owner) {}
    record Redeemer() {}

    @Entrypoint
    static boolean validate(Datum datum, Redeemer redeemer, ScriptContext ctx) {
        return ContextsLib.signedBy(ctx.txInfo(), datum.owner());
    }
}
```

From the project containing `julc.toml` and `src/`:

```bash
julc verify . \
  --validator AuthorizedStateValidator \
  --backend docker
```

`julc verify` performs an exact build, resolves the annotation against the
compiler-owned contract schema, generates a managed workspace, validates its
hashes, checks property non-vacuity, runs the proof, and writes
`verification-result.json`.

The annotation states a property; it does not enforce it. If the validator
returns `true` without checking the signer, the expected result is `REFUTED`
with a retained Blaster counterexample.

### Stateful spending profile

Use all three annotations together. This declaration excerpt omits the
entrypoint body because that body must independently implement every clause:

```java
@RequiresSigner("datum.owner")
@Monotonic(
    current = "datum.state",
    next = "redeemer.nextState",
    relation = Relation.GREATER_THAN)
@PreservesValue(output = OutputSelection.SINGLE_CONTINUING_OUTPUT)
@SpendingValidator
class StateMachine {
    record Datum(byte[] owner, BigInteger state) {}
    record Redeemer(BigInteger nextState) {}

    // @Entrypoint implementation omitted: it must enforce authorization,
    // continuing-output/value rules, and the declared transition.
}
```

The generated theorem strictly decodes the current datum, redeemer, and inline
successor datum. It also requires the continuing output to share the complete
address of the resolved own input; matching only the payment credential is not
equivalent.

### Controlled mint profile

The same rule applies to controlled minting; this is a property declaration
excerpt, not the validator implementation:

```java
@ControlledMint(
    authority = "4a554c435f5645524946595f415554484f524954595f303030303031",
    tokenName = "4a554c43",
    quantity = 1,
    action = MintAction.MINT)
@MintingValidator
class TokenPolicy {
    // The validator must independently check the authority and exact
    // current-policy token shape described by the annotation.
}
```

The annotation's `quantity` is always a strictly positive magnitude. `MINT`
uses that magnitude and `BURN` lowers it to the corresponding negative on-chain
quantity. Authority hashes are 28 bytes and token names are at most 32 bytes.
Invalid or ambiguous literals fail before Lean generation.

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

## Write a typed DSL property

The DSL workflow has three stages: generate a contract metamodel, compile a
trusted Java specification, and run the bounded worker and verifier.

Use one exact JuLC version for the compiler, `julc-verification`, generated
metamodel, property worker, and executing CLI. The commands below use the JVM
shadow JAR built from a source checkout so that one artifact supplies both the
CLI and the worker classpath:

```bash
JULC_SOURCE=/absolute/path/to/julc
"$JULC_SOURCE/gradlew" -p "$JULC_SOURCE" :julc-cli:shadowJar
JULC_JAR="$JULC_SOURCE/julc-cli/build/libs/julc.jar"
cd /absolute/path/to/your-contract-project
```

The Homebrew native executable does not currently install this worker JAR. A
native-CLI DSL run therefore still needs a matching source-built shadow JAR or
a complete runtime classpath containing the same-version
`julc-verification` artifact and its dependencies.

### 1. Generate the schema-10 model

```bash
java -jar "$JULC_JAR" verify dsl-init . \
  --validator AuthorizedStateValidator \
  --purpose spending \
  --package verification \
  --class AuthorizedStateModel \
  --out build/verification-dsl/src/verification/AuthorizedStateModel.java
```

Schema 10 is the API-v1 default and the only schema covered by the API-v1
canonical-semantics freeze. `--schema-version 3` through `9` remains available
for compatibility and evidence reproduction. Historical certificates remain
hash-bound records, but an old workspace can require regeneration when the
reviewed capability inventory or dependency pins change. Generation refuses to
overwrite the output file.

For an explicit `@MultiValidator`, `--validator` is the base Java class and
`--purpose` selects one exact interface. Supported purpose values are
`spending`, `minting`, `rewarding`, and `certifying`.

### 2. Implement `VerificationSpecification`

```java
package verification;

import com.bloxbean.cardano.julc.verification.dsl.VerificationSpecification;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslDomain;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;

import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.property;

public final class AuthorizedStateSpec implements VerificationSpecification {
    @Override
    public DslPropertySet properties() {
        var contract = new AuthorizedStateModel();
        var authorized = contract.datum().exists(datum ->
            contract.context().txInfo().signatories().contains(datum.owner()));

        return contract.properties(property(
            "authorized-state.owner-signed",
            DslDomain.NONE,
            authorized));
    }
}
```

The generated model supplies the purpose, contract-schema hash, nominal types,
and field accessors. Prefer its `contract.properties(...)` factory over
constructing property envelopes manually.

`VerificationSpecification` executes project Java. Treat specification source
and every class on `--spec-classpath` as trusted code. The worker is bounded and
the parent process revalidates its returned closed AST, but it is not a sandbox
for hostile Java.

### 3. Compile and verify

The JVM CLI shadow JAR contains the stable DSL API and its dependencies:

```bash
mkdir -p build/verification-dsl/classes

javac -cp "$JULC_JAR" \
  -d build/verification-dsl/classes \
  build/verification-dsl/src/verification/AuthorizedStateModel.java \
  AuthorizedStateSpec.java

java -jar "$JULC_JAR" verify dsl . \
  --validator AuthorizedStateValidator \
  --purpose spending \
  --spec-class verification.AuthorizedStateSpec \
  --spec-classpath "build/verification-dsl/classes:$JULC_JAR" \
  --source AuthorizedStateSpec.java \
  --backend docker \
  --fuel 1500 \
  --force
```

On Windows, use `;` instead of `:` in the classpath. If you substitute a native
`julc` executable for `java -jar "$JULC_JAR"`, it still starts a child JVM for
the trusted property specification, so Java and the exact matching JuLC worker
classpath must remain available on `--spec-classpath`.

For Gradle or Maven, add `com.bloxbean.cardano:julc-verification` at the same
version as the compiler and pass the compiled specification's complete runtime
classpath to `--spec-classpath`.

## Stable construction API

The API-v1 construction surface consists of:

- `VerificationSpecification`;
- `VerificationDsl` literal and property factories;
- typed expression wrappers in
  `com.bloxbean.cardano.julc.verification.dsl`;
- `DslProperty`, `DslPropertySet`, `DslPurpose`, and `DslDomain`; and
- reproducible schema-10 generated contract metamodels.

Concrete classes in `verification.dsl.ir` are serialization infrastructure
unless listed above. Renderers, validators, worker protocol classes, semantic
dependency planners, arbitrary Lean, and custom node kinds are not public DSL
construction APIs merely because some classes need Java-public visibility.

Schema-10 canonical meanings and serialization are frozen for API v1. New
semantic vocabulary requires a new reviewed property schema.

## Property domains

`DslDomain.NONE` asks only about the exact UPLC execution relation. Reviewed
ledger domains add a pinned premise:

| Purpose | Domain |
|---|---|
| spending | `VALID_SPENDING_V3_PINNED` |
| minting | `VALID_MINTING_V3_PINNED` |
| rewarding | `VALID_REWARDING_V3_PINNED` |
| certifying | `VALID_CERTIFYING_V3_PINNED` |

The certificate records the selected domain and whether a kernel-checked bridge
to the pinned ledger model was established. A counterexample in a Blaster
superset is not automatically a ledger-valid Cardano transaction; inspect the
certificate's counterexample qualification.

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
