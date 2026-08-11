# ADR-001: Verification Strategy for JuLC Using IOG Blaster

- **Status:** Accepted — source of truth for JuLC's Blaster verification
  strategy (2026-08-11)
- **Date:** 2026-08-11
- **Author:** Codex
- **Cross-review:** Independently explored and source-verified by Fable,
  2026-08-11 — all load-bearing claims confirmed; see the
  [appendix](#appendix-cross-review-record-and-program-context-fable-2026-08-11)
  for the verification record, ecosystem/program context, and the CIP-57
  side-finding
- **Scope:** JuLC-generated UPLC, compiler transformations, and supporting
  assurance infrastructure
- **Decision horizon:** Begin with artifact-level verification; revisit
  compiler certification after the pilot

## Context

JuLC compiles a deliberately restricted Java source language into Plutus IR
(PIR), lowers PIR into Untyped Plutus Core (UPLC), optimizes the UPLC term, and
serializes the final program for Cardano. A defect in any of those stages can
cause the deployed validator to differ from the behavior intended by its Java
source.

The current compilation and deployment path is approximately:

```text
Java source
    -> source validation and type registration
    -> PIR generation
    -> validator wrapper generation
    -> PIR-to-UPLC lowering
    -> UPLC optimization
    -> Plutus V3 Program
    -> FLAT serialization
    -> inner CBOR byte string
    -> outer CBOR byte string
    -> blueprint compiledCode / deployed script
```

Important implementation boundaries are:

- `julc-compiler/.../JulcCompiler.java`, which owns the compilation pipeline;
- `julc-compiler/.../CompileResult.java`, which retains PIR and final UPLC for
  detailed compilation results;
- `julc-compiler/.../uplc/UplcOptimizer.java`, which owns the UPLC optimization
  passes;
- `julc-cardano-client-lib/.../JulcScriptAdapter.java`, which performs FLAT and
  CBOR serialization;
- `julc-core/.../DefaultFun.java`, which defines the builtin surface JuLC can
  emit.

IOG's Blaster projects provide a possible way to reason about the final
artifact independently of the Java implementation:

| Project | Role |
|---|---|
| [Lean-blaster](https://github.com/input-output-hk/Lean-blaster) | Simplifies Lean propositions, translates them to SMT, invokes Z3, and reports validity or counterexamples. |
| [PlutusCoreBlaster](https://github.com/input-output-hk/PlutusCoreBlaster) | Models UPLC, decodes text/FLAT/CBOR scripts, and symbolically executes programs with a CEK machine. |
| [CardanoLedgerApiBlaster](https://github.com/input-output-hk/CardanoLedgerApiBlaster) | Models Cardano V1/V2/V3 script contexts and provides ledger-validity predicates and validator-input preparation. |

The workflow proposed in Paolino's
[Aiken verification guide](https://gist.github.com/paolino/3d9b79baffc075606bdd1ba4f9002f81)
is not inherently Aiken-specific. It operates on compiled UPLC, so it can be
adapted to JuLC as long as artifact encoding, protocol semantics, builtin
coverage, and evaluation outcomes are handled correctly.

IOG describes the same end-to-end objective: import actual UPLC, state
properties over ledger-valid contexts, prove them automatically where
possible, and obtain counterexamples when a claim is false. See
[Automated formal verification for Cardano smart contracts](https://www.iog.io/news/automated-formal-verification-for-cardano-smart-contracts).

## Decision

JuLC should pursue formal assurance in two distinct tracks:

1. **Start with verification of exact, deployable UPLC artifacts.**
   Build a pinned Blaster integration that imports the `compiledCode` emitted
   by the normal JuLC build, proves contract-specific security properties, and
   binds every result to the precise script hash and toolchain identity.
2. **Treat compiler verification as a staged, longer-term effort.**
   Begin with optimizer transformations, then PIR-to-UPLC lowering, and only
   then consider source-to-PIR semantic preservation. Prefer a small
   translation validator or proof-certificate checker over attempting to
   verify the entire Java implementation immediately.

Blaster results must be described as **SMT-validated**, not as Lean
kernel-checked proofs, until the tool reconstructs solver proofs inside Lean.
Unsupported builtins, step exhaustion, model limitations, or solver
indeterminacy must fail closed as `COULD-NOT-EVALUATE`.

This ADR does not claim that a Blaster result alone makes a validator safe.
Safety is a layered argument combining exact-artifact properties, compiler and
VM conformance, differential tests, cost/budget tests, protocol-version gates,
and conventional review or audit.

## Why artifact-level verification comes first

Proving a useful security property about the final UPLC closes more of the
deployment gap than proving the same property about a handwritten Lean model
of the Java source. It covers the behavior produced by:

- source lowering;
- wrapper construction;
- data encodings;
- optimizer transformations;
- FLAT decoding, provided the imported bytes are the deployable artifact.

It does not prove the compiler correct for other programs. It proves a stated
property of one exact artifact under the modeled UPLC and ledger semantics.
Each newly compiled artifact must be verified again.

Whole-compiler verification requires, at minimum:

- a precise semantics for JuLC's supported Java subset;
- a semantics for JuLC PIR;
- a semantics for the generated UPLC;
- proofs for Java-to-PIR lowering, recursion, data encodings, builtin lowering,
  De Bruijn conversion, wrappers, and every optimizer transformation;
- a connection from verified terms to serialized bytes.

That remains valuable, but it is substantially larger than an artifact pilot
and should not delay immediate contract-level assurance.

## Compatibility findings

### JuLC blueprint encoding

A build of a simple validator from the current JuLC tree confirmed that
blueprint `compiledCode` is FLAT wrapped in two nested CBOR byte strings. The
JuLC import format is therefore:

```lean
#import_uplc validator PlutusV3 double_cbor_hex \
  "artifacts/Validator.compiledCode.hex"
```

The Aiken example's `single_cbor_hex` must not be copied unchanged. The
verification job should extract a validator by its blueprint title and import
the exact `compiledCode`, rather than relying on an independently regenerated
UPLC text file.

The job must verify all of the following identities:

```text
selected blueprint validator
    -> compiledCode bytes
    -> decoded UPLC program
    -> expected Cardano script hash
    -> deployment artifact
```

### Ledger context compatibility

JuLC's Plutus V3 wrapper consumes the V3 `ScriptContext` data shape, including
`TxInfo`, redeemer, and `ScriptInfo`. This aligns with the V3 types and
spending-input preparation exposed by CardanoLedgerApiBlaster.

Properties should normally quantify over a purpose-specific ledger-validity
predicate, for example `validSpendingContext`, rather than over arbitrary data.
Separate proof harnesses should be used for spending, minting, rewarding,
certifying, voting, and proposing purposes.

### Protocol and builtin-semantics identity

At the reviewed revisions, PlutusCoreBlaster maps Plutus V3 after Conway to
builtin-semantics variant **E**. This is the intended profile for current JuLC
Plutus V3 / protocol-version-11 output.

The current preparation path appears to select its default variant rather than
taking the artifact's declared language as a complete semantics selector. The
integration must therefore record and, preferably, pass the variant
explicitly. A proof claim without an identified variant is not established.

The required identity is:

```text
JuLC source commit
    + JuLC compiler/toolchain version
    + final script hash
    + ledger language
    + protocol version / BuiltinSemanticsVariant
    + Blaster dependency commits
    + Lean and Z3 versions
```

### Builtin coverage gap

At the revisions inspected for this ADR, PlutusCoreBlaster imports most of the
earlier Plutus builtins and the BLS multi-scalar multiplication builtins, but
does not decode all builtins that JuLC can emit for PV11.

Known gaps are:

| Tags | JuLC capability | Reviewed Blaster status |
|---:|---|---|
| 89-91 | Array builtins | Not decoded/supported |
| 94-100 | Mary-era value builtins | Not decoded/supported |

This means the first pilot must use a validator inside a mechanically checked
supported subset. Contracts using JuLC array helpers or native-value helpers
must be reported as `COULD-NOT-EVALUATE` until support is added and tested.

A verification preflight must traverse the final UPLC or decoded FLAT program,
enumerate its builtin tags, and reject any unsupported tag. It must not infer
coverage merely because importing or simplifying part of a program succeeds.

### CEK fuel-exhaustion risk

The reviewed `#prep_uplc` path evaluates with a caller-supplied natural-number
step limit. Exhausting this limit reaches a CEK error state that can be
observationally confused with ordinary validator evaluation failure.

That creates a vacuity risk for the common theorem shape:

```lean
isSuccessful (validator context) -> securityInvariant context
```

If evaluation exhausts its steps and `isSuccessful` becomes false, the
implication can appear true without establishing the invariant for that
context.

Production use is conditional on one of the following safeguards:

1. modify or extend the preparation path so timeout/budget exhaustion is a
   distinct result and causes the proof command or CI job to fail;
2. use a budget-aware evaluator result that preserves exhaustion separately
   from UPLC evaluation error; or
3. prove a sufficient execution bound under explicit transaction-size and
   ledger constraints.

The first or second option is preferred. Increasing the fuel number without
detecting exhaustion is not a sound fix.

## Trust model

### Current solver result is not a kernel proof

Lean-blaster currently does not reconstruct Z3 proofs. When Z3 declares the
translated proposition valid, the tactic closes the Lean goal through a
custom axiom. The source warns that the result is SMT-verified and has no proof
term.

The trusted computing base therefore includes:

- the Lean encoding of UPLC and Cardano ledger data;
- UPLC/FLAT/CBOR decoding;
- the CEK implementation and builtin semantics;
- preprocessing and symbolic execution;
- the Lean-to-SMT translation;
- Z3;
- the absence of timeout/error conflation and vacuous premises;
- JuLC's extraction of the exact artifact and script-hash binding.

The output vocabulary must be:

| Outcome | Meaning |
|---|---|
| `ESTABLISHED (SMT-VALID)` | The pinned model and solver validated the property for the exact artifact under the stated assumptions. |
| `REFUTED` | A reproducible counterexample violates the property. |
| `COULD-NOT-EVALUATE` | Coverage, timeout, solver, decoding, semantics, or modeling conditions prevented a valid claim. |

`COULD-NOT-EVALUATE` must never be converted to success by CI.

### Model fidelity

A theorem is only as reliable as the correspondence between the Lean CEK and
Cardano's executable Plutus semantics. JuLC should retain independent evidence:

- official Plutus conformance cases in `julc-vm-java`;
- differential evaluation against the Haskell evaluator or cardano-node
  reference behavior;
- comparison with PlutusCoreBlaster on imported programs;
- pinned cost-model and protocol-version fixtures.

The [Cardano formal specifications](https://github.com/IntersectMBO/cardano-formal-specifications)
provide an additional independent semantic reference. They do not directly
verify JuLC, but can help validate assumptions and guide a future certified
compiler model.

## Proof-of-concept design

### Initial validator selection

Select one production-shaped Plutus V3 spending validator that:

- has authorization and value-preservation requirements;
- uses only Blaster-supported builtins;
- contains at least one collection traversal, so symbolic tractability is
  measured early;
- has an intentionally vulnerable mutation that should produce a
  counterexample.

After the spending-validator pilot, add one minting policy and one
state-machine-style validator.

### Suggested properties

The property set should be derived from each contract's threat model, not from
a generic assertion that the validator is "correct." Candidate properties
include:

- successful spending requires an authorized signer;
- value cannot leave the script except through an allowed transition;
- state tokens are preserved or minted/burned only by the intended policy;
- output datum and value agree with the state-transition specification;
- validity intervals enforce the intended deadline;
- one transaction input cannot discharge two independent obligations;
- a validator cannot succeed under the wrong script purpose;
- malformed datum/redeemer encodings cannot cause unintended success;
- mint quantities and asset names satisfy the policy invariant.

### Lean harness shape

An initial harness is expected to resemble:

```lean
import PlutusCore.UPLC
import CardanoLedgerApi.V3
import Blaster

open CardanoLedgerApi.V3 (spendingInputs validSpendingContext)

#import_uplc validator PlutusV3 double_cbor_hex \
  "artifacts/Validator.compiledCode.hex"

#prep_uplc appliedValidator validator spendingInputs 20000

theorem accepts_only_if_authorized :
    forall context,
      validSpendingContext context ->
      isSuccessful (appliedValidator.prop context) ->
      authorized context := by
  blaster
```

This is illustrative. The pilot cannot be promoted to a release gate until
fuel exhaustion is distinguished from evaluation failure.

### Negative controls

Every verification gate must demonstrate that it is capable of failing. At
least one of the following must be checked in a separate negative-control job:

- remove the signer check;
- weaken an output-value comparison;
- omit a token-preservation condition;
- remove purpose isolation;
- replace an equality with an inequality;
- weaken the theorem conclusion.

The modified validator must be rebuilt through the normal JuLC pipeline and
Blaster must return a concrete counterexample or refutation. Merely mutating a
handwritten Lean model does not test artifact integration.

## Verification manifest and CI policy

Each verification run should produce a machine-readable manifest containing:

```yaml
sourceCommit: <git commit>
julcVersion: <version>
compilerCommit: <commit>
validatorTitle: <blueprint title>
compiledCodeSha256: <hash>
cardanoScriptHash: <hash>
plutusLanguage: PlutusV3
protocolVersion: 11
builtinSemanticsVariant: E
leanVersion: 4.24.0
z3Version: 4.15.2
leanBlasterCommit: <commit>
plutusCoreBlasterCommit: <commit>
cardanoLedgerApiBlasterCommit: <commit>
fuelOrBudget: <configured value>
supportedBuiltinSet: <version/hash>
properties:
  - id: <stable property id>
    sourceHash: <hash>
    result: ESTABLISHED | REFUTED | COULD-NOT-EVALUATE
```

As of the research snapshot for this ADR, the inspected repository revisions
were:

- Lean-blaster: `083bae7971414d894b56b5bbf4108c63e17bc42a`;
- PlutusCoreBlaster: `7cf5a78c54b9694ef093bf49edb5d3799b2a49c9`;
- CardanoLedgerApiBlaster:
  `5dab3c43f042b8735b6d067223baaa8d32ed28a1`.

These are research pins, not endorsements or permanent dependency choices.
The PoC must capture its own exact Lake manifest and must not follow moving
`main` branches in a release gate.

The CI job should execute the following fail-closed gates:

1. Build the validator through the normal production path.
2. Select the blueprint entry by exact title.
3. Extract and hash `compiledCode`.
4. Recompute the Cardano script hash.
5. Scan for unsupported term forms and builtins.
6. Import with `PlutusV3 double_cbor_hex`.
7. Assert the explicit protocol/builtin-semantics variant.
8. Run all properties and preserve counterexamples.
9. Treat timeout, fuel exhaustion, solver `unknown`, unsupported decoding, or
   missing identity metadata as `COULD-NOT-EVALUATE` and fail the gate.
10. Publish the manifest and proof/counterexample logs with the artifact.

## Compiler-verification roadmap

### Stage 1: expose optimization boundaries

For verification work, `CompileResult` should make both unoptimized and
optimized UPLC available as stable artifacts. This permits direct comparison
and avoids reconstructing an intermediate term from logs.

### Stage 2: verify optimizer passes

The optimizer is the smallest high-value compiler surface. Its passes include
force/delay simplification, constant folding, dead-code elimination, beta
reduction, eta reduction, and constructor/case simplification.

For each pass, distinguish two claims:

1. **Behavioral preservation:** success, returned constant/data, failure, and
   observable traces are preserved under a clearly defined semantics.
2. **Cost property:** execution cost does not increase, or satisfies another
   explicit bound.

Equal cost must not be part of the behavioral theorem because optimization is
expected to change budgets. Divergence and strictness must be modeled
explicitly; a rule that preserves results only for terminating pure terms is
not automatically valid for arbitrary UPLC.

A practical intermediate deliverable is a Lean checker that consumes the
before/after term and a pass-specific certificate emitted by Java.

### Stage 3: certify PIR-to-UPLC lowering

Formalize the JuLC PIR subset and prove or validate:

- variable indexing and De Bruijn conversion;
- lambda/application and let lowering;
- recursive bindings and fixed-point/Bekic transformations;
- builtin type instantiation and force/delay insertion;
- constructors, case expressions, and data encoding;
- validator wrappers and multi-purpose dispatch.

This stage can use translation validation: the untrusted Java compiler emits a
candidate UPLC term, and a much smaller trusted checker validates that it
corresponds to the PIR input.

### Stage 4: define JuLC source semantics

Full compiler verification requires a formal definition of the supported Java
subset. Attempting to model general Java would make the trusted language much
larger than JuLC's actual surface. A smaller explicit `JuLC Core` language is
preferred, with defined semantics for:

- primitive and data types;
- records and sealed variants;
- control flow;
- method calls and supported recursion;
- equality and numeric behavior;
- allowed standard-library and on-chain-library operations;
- rejected Java constructs.

The Java front end could then elaborate into JuLC Core and emit a certificate
checked independently. This reduces trust in JavaParser and compiler control
flow without requiring Lean to execute the Java compiler itself.

## Related projects and complementary evidence

- [UPLC-CAPE](https://github.com/IntersectMBO/UPLC-CAPE) can provide
  reproducible compiler-output, cost, and regression benchmarks. It is useful
  defense in depth, but not a proof system.
- [IntersectMBO/plutus](https://github.com/IntersectMBO/plutus) remains the
  reference implementation and source of conformance vectors and protocol
  behavior.
- JuLC's official Plutus conformance suite should remain mandatory even after
  Blaster integration. Artifact verification avoids trusting JuLC's VM for a
  theorem, while conformance testing independently checks the VM and compiler
  tooling used during development.

## Consequences

### Positive

- Security properties apply to the exact bytes intended for deployment.
- Compiler defects affecting a proved property can be caught without first
  proving the whole compiler.
- Counterexamples can improve contract tests and threat models.
- A pinned manifest makes assurance claims reproducible and auditable.
- The work establishes concrete requirements for missing builtin support and
  future proof-producing compiler components.

### Negative

- The current result depends on Z3 and the SMT translation rather than only on
  the Lean kernel.
- Proof authoring requires Lean and detailed knowledge of ledger contexts.
- Symbolic execution may not scale to recursive or collection-heavy
  validators without decomposition and supporting lemmas.
- Every artifact change requires re-verification.
- Current Blaster builtin coverage does not include all PV11 programs JuLC can
  emit.
- A robust integration requires changes around step/budget exhaustion before
  it can be a release gate.

### Risks

- A weak or incomplete property can be valid while the contract remains
  exploitable in another way.
- An inconsistent ledger predicate can make the quantified input domain empty
  or unrealistically narrow.
- Incorrect datum/record `IsData` instances can disconnect typed properties
  from the artifact's encoding.
- Moving dependencies can silently change semantics or proof outcomes.
- Reporting SMT validity as a kernel theorem would overstate the assurance.

## Milestones and exit criteria

### Milestone A: compatibility PoC

Expected duration: one to two weeks.

- Import a current JuLC blueprint artifact as `double_cbor_hex`.
- Confirm script-hash identity.
- Add builtin and term-form preflight checks.
- Pin Lean 4.24.0, Z3 4.15.2, and exact dependency commits.
- Establish one nontrivial property and refute one vulnerable mutation.
- Demonstrate that fuel exhaustion cannot be reported as success.

Exit criterion: a reproducible manifest with correct tri-state outcomes.

### Milestone B: useful contract verification

Expected duration: three to six additional weeks.

- Verify two or three production-shaped validators across at least two script
  purposes.
- Add authorization, value/state preservation, and double-satisfaction
  properties.
- Preserve source-linked counterexamples as regression fixtures.
- Run the suite in CI without unpinned network dependencies.

Exit criterion: the team accepts the property definitions and threat-model
coverage as meaningful release evidence.

### Milestone C: reusable integration

Expected duration: two to four months, depending on upstream compatibility.

- Generate Lean `IsData` definitions from JuLC/CIP-57 schemas.
- Add reusable property templates and artifact manifests.
- Upstream or locally implement missing PV11 builtins.
- Make semantics variant and exhaustion explicit in APIs.
- Provide a `julc verify` CLI or Gradle workflow.

Exit criterion: new validators can adopt verification without constructing a
Lean project and data encodings manually.

### Milestone D: compiler certification research

Expected duration: six months onward.

- Expose before/after optimizer artifacts.
- Build a checker for at least one optimizer pass.
- Define the PIR subset used by JuLC and validate a nontrivial lowering path.
- Decide whether to pursue proof-producing compilation or full semantic
  preservation for JuLC Core.

Exit criterion: a separate ADR can make a concrete compiler-certification
decision based on measured engineering cost and achieved trust reduction.

## Decision summary

JuLC should proceed with a pinned, fail-closed Blaster proof of concept against
the exact double-CBOR UPLC artifact emitted in its blueprint. This offers the
best near-term security return and directly exercises deployed behavior.

Before promotion to a production gate, JuLC must close or explicitly contain
the builtin-coverage gap, make the Plutus semantics variant explicit, and
distinguish CEK fuel exhaustion from validator failure. Results must be labeled
SMT-valid rather than kernel-proved.

Compiler verification should proceed separately, beginning with
certificate-based validation of optimizer passes and PIR-to-UPLC lowering.
Full verification of the Java-to-UPLC compiler is a long-term goal, not a
prerequisite for obtaining meaningful artifact-level assurance now.

## Appendix: cross-review record and program context (Fable, 2026-08-11)

A parallel independent exploration reached the same strategy and verified this
ADR's load-bearing claims directly against source. This appendix preserves that
record and the ecosystem context this spec otherwise omits.

### Verification record

- **Builtin gap (confirmed, refined):** JuLC-side emission of tags 89–100 is
  wired through `Builtins.java`, `NativeValueLib`, `StdlibRegistry`,
  `TypeMethodRegistry`, and `UplcGenerator`. In PlutusCoreBlaster's FLAT
  decode table (`PlutusCore/UPLC/FlatEncoding/Basic.lean`), tags 89–91 and
  94–99 are commented out (TODO: "implement array for batch 6") and tag 100
  (`ScaleValue`) is absent entirely; tags 87–88 (`ExpModInteger`, `DropList`)
  and the BLS multi-scalar-mul builtins (92–93) are decoded.
- **Fuel-exhaustion conflation (confirmed):** `runSteps` in
  `PlutusCore/UPLC/CekMachine.lean` returns `State.Error` when the step count
  reaches 0 — indistinguishable from genuine evaluation failure, producing the
  vacuity risk described above. The same file defines a budget-aware
  `EvaluationResult` with a distinct `BudgetExhausted` constructor, a concrete
  fix path for safeguard option 2.
- **Semantics variant (confirmed at entry point):**
  `PlutusCore/Default/Basic.lean` maps `plutusV3 × postConway →
  defaultFunSemanticsVariantE`, and the `Inhabited` default is E, used by the
  plain `cekExecuteProgram` entry point. `#prep_uplc`'s exact call path in
  CardanoLedgerApiBlaster has not been traced — confirm during the PoC. Note
  the model's binary pre/postConway split is coarser than JuLC's V3/PV10→C vs
  V3/PV11→E conformance profiles; a PV10-targeted check must select C
  explicitly.

### Program context relevant to Milestone C

IOG's treasury-funded [Cardano High Assurance](https://www.iog.io/news/cardano-high-assurance-formal-verification)
program is productizing this stack: a **Universal Annotation Language (UAL)**
is being specified as an open integration protocol for language teams; funded
integrations are Aiken (Midgard Labs), Pebble (Harmonic Labs), **Scalus
(Lantr)** — JuLC's VM backend, making it the closest reference
implementation to track — and Futura (SAIB). A Container-Based Developer
Environment ships pre-release **Q4 2026**, v1.0 **Q1 2027**, with a VS Code
counterexample explorer and a Common Vulnerability Library. Milestone C's
property-annotation and `julc verify` surface should track the UAL spec
rather than invent a parallel convention, and the CBDE's pinned toolchain
versions are the natural source for this ADR's manifest pins.

### Side-finding: blueprint `compiledCode` deviates from CIP-57

CIP-57 specifies `compiledCode` as "the full compiled and cbor-encoded
serialized flat script" — a single CBOR wrap (Aiken's convention). JuLC emits
a double wrap because `BlueprintGenerator` reuses `JulcScriptAdapter`'s
transport-oriented `cborHex`. Harmless for this verification pipeline
(`double_cbor_hex` is supported) but an interop risk for ecosystem blueprint
consumers applying the standard wrap-once convention. To be triaged as its own
issue, independent of this ADR.
