---
title: "Verification Pipeline Design"
description: "How JuLC binds typed Java properties to exact UPLC and verifies them with Lean, IOG Blaster, and Z3"
---

:::caution[Experimental verification feature]
This architecture verifies named properties of one exact generated artifact
under recorded models and bounds. It is not a general proof that the JuLC
compiler is correct or that a contract is safe against every threat.
:::

JuLC joins two independently produced inputs: the exact UPLC selected from the
contract blueprint and a closed, canonical security-property IR. It generates
a reproducible Lean workspace, runs the pinned Blaster and Z3 toolchain, and
classifies the observed result in a hash-bound certificate.

![JuLC formal-verification pipeline, from Java contract and typed property to a hash-bound Lean workspace, Blaster and Z3 execution, and a classified certificate](/verification-pipeline.svg)

## 1. Compile and identify one exact artifact

The ordinary JuLC compiler turns the supported Java contract into Plutus V3
UPLC. Blueprint generation supplies the compiler-owned datum/redeemer schema
and purpose-indexed validator interfaces.

Verification selects exactly one interface by validator and purpose. JuLC
checks both the compiled-code digest and Cardano script hash. The proof is
therefore about those exact bytes—not about all possible outputs of the Java
source or the compiler in general.

## 2. Build one canonical property

A developer can choose either frontend:

- reviewed annotations such as `@RequiresSigner`; or
- an explicit specification written with the typed Java DSL.

Annotations lower into the same stable schema-1 DSL IR as explicit
specifications. The DSL worker executes trusted project Java, but its output is
not trusted as an arbitrary program: the parent process decodes and validates a
closed AST, rechecks contract and ledger types, purpose compatibility, domain
selection, and capability admission, then canonicalizes and hashes the result.

Arbitrary Lean text and user-defined AST nodes are not accepted. Adding new
proof vocabulary requires reviewed Java IR, validation, Lean rendering, model
semantics, and controls.

## 3. Join artifact and property

Workspace generation binds the selected artifact, canonical property, schema,
ledger domain, execution fuel, recursive decode depth, dependency revisions,
and runner plan. Generated Lean includes, as needed:

- the exact UPLC program and execution premise;
- strict contract types and `Data` codecs derived from the blueprint;
- the typed property obligation;
- pinned Cardano ledger-domain predicates;
- kernel-checked inclusion bridges and corollaries; and
- non-vacuity and negative-control checks.

Changing a bound, property, generated Lean file, runner script, selected
interface, or artifact changes a recorded hash or fails preflight.

## 4. Run Lean, Blaster, and Z3

The same generated workspace runs through either backend:

- **local** uses the exact supported Lean/Lake and Z3 toolchain on the host;
- **Docker** builds the reviewed pinned environment, then runs proof commands
  without network access after dependency acquisition.

Lean is the language and checking environment for the generated models and
lemmas. IOG Blaster symbolically models UPLC execution and translates the
admitted obligation to SMT. Z3 searches that bounded model for validity or a
counterexample.

These roles matter: `SMT-VALID` relies on Blaster's translation and Z3 through
a solver axiom; it is not a theorem reconstructed solely by Lean's kernel.
Codec controls, domain bridges, and corollaries identified as kernel-checked do
receive ordinary Lean kernel checking.

## 5. Classify and bind the result

JuLC authenticates each expected runner step using its exit code and output
marker. Unknown, missing, timed-out, or inconsistent observations fail closed.
The main classifications are:

- `SMT-VALID` — the bounded translated obligation was established;
- `KERNEL-PROVED` — an admitted ordinary Lean theorem was kernel-checked;
- `REFUTED` — Blaster found a countermodel in the recorded modeled domain; and
- `COULD-NOT-EVALUATE` or `UNDETERMINED` — JuLC did not establish the property.

The certificate records the exact artifact, property, domain, assumptions,
bounds, backend, tool revisions, generated-source hashes, phase outcomes, and
counterexample qualification. It is the statement to review and archive; the
terminal summary is only a convenience view.

## Trust boundary

| Component | Role | What remains trusted or bounded |
|---|---|---|
| JuLC compiler | Produces the selected UPLC and contract schema | The pipeline does not prove general Java-to-UPLC compiler correctness |
| Strict boundaries and codecs | Give datum/redeemer types their canonical on-chain meaning | Only admitted types and recorded recursive depth are covered |
| Typed DSL and promotion | Define and validate the named property | Missing security properties remain the developer's responsibility |
| Pinned CardanoLedgerApi model | Defines the selected ledger-domain premise | Coverage is limited to the pinned model and explicit domain bridge |
| Lean | Elaborates generated definitions and kernel-checks ordinary lemmas | `SMT-VALID` is not solely a Lean-kernel result |
| Blaster | Symbolically models UPLC and translates obligations | Translation and supported builtin coverage are trusted limitations |
| Z3 | Establishes validity or finds a bounded countermodel | Results are relative to the recorded formula, fuel, and solver behavior |
| JuLC runner | Enforces hashes, plans, timeouts, and result classification | A certificate covers only the named properties it records |

## Typical flow

```bash
# 1. Build the contract and its purpose-indexed blueprint.
julc build .

# 2a. Verify a reviewed annotation profile.
julc verify . --validator AuthorizedStateValidator --backend docker

# 2b. Or generate a typed model and verify an explicit DSL specification.
julc verify dsl-init . --validator AuthorizedStateValidator \
  --purpose spending --package verification --class AuthorizedStateModel \
  --out build/verification-dsl/src/verification/AuthorizedStateModel.java

julc verify dsl . --validator AuthorizedStateValidator \
  --purpose spending --spec-class verification.AuthorizedStateSpec \
  --spec-classpath "build/verification-dsl/classes:$JULC_JAR" \
  --backend docker --force

# 3. Reproduce an already generated, hash-bound workspace.
julc verify run verification/authorized-state-validator --backend docker
```

Continue with [Annotation Profiles](../annotation-profiles/) for the shortest
reviewed workflow or [Typed Java DSL](../typed-dsl/) for custom composition.
