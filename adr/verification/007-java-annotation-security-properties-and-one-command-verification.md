# ADR-007: Java-Annotation Security Properties and One-Command Verification

- **Status:** Proposed — staged for delivery by ADR-009 after Milestone C.4
- **Date:** 2026-08-12
- **Related:**
  [ADR-001 — IOG Blaster Verification Strategy](001-iog-blaster-verification-strategy.md),
  [ADR-004 — Milestone C Reusable Verification Integration](004-milestone-c-reusable-verification-integration.md),
  [ADR-006 — Lean Containers and Optional Values](006-milestone-c2-lean-containers-and-optionals.md),
  [ADR-009 — Verification Product Roadmap](009-verification-product-roadmap.md)

## Context

Milestones C.1 and C.2 establish the technical foundation for generating a
typed Lean/Blaster workspace from the exact UPLC and CIP-57 schemas emitted by
JuLC. A contract developer must still understand the Cardano ledger model and
write the contract-specific security property and proof harness in Lean.

Many important Cardano security requirements repeat across contracts:

- a required signer is stored in the datum or a validator parameter;
- a state value must increase or otherwise satisfy a transition relation;
- value must be preserved in one or more continuing outputs;
- a minting policy must restrict authority, policy ID, asset name, or quantity;
- a continuing output must commit to the consumed input and next datum; and
- a spending validator must resist double satisfaction.

JuLC should offer these as typed security properties so Java developers can
formally verify common requirements without writing Lean.

## Decision direction

JuLC should implement a no-Lean verification layer built around a typed
**security property IR** derived from Java annotations. This ADR records the
intended property architecture. ADR-009 sequences its implementation after the
managed verification runner in Milestone C.4.

The proposed architecture is:

```text
Java security-property annotations
                 ↓
      JuLC compiler type resolution
                 ↓
       typed security property IR
                 ↓
 Lean theorem + reviewed negative controls
                 ↓
 exact JuLC UPLC evaluated by pinned Blaster
                 ↓
 structured result and verification certificate
```

Lean remains an implementation and extension language, but users of supported
property templates should not need to read or write it.

## Property specification interfaces

The primary user interface is Java annotations on a JuLC validator:

```java
@RequiresSigner("datum.owner")
@Monotonic(
        current = "datum.state",
        next = "redeemer.nextState",
        relation = Relation.GREATER_THAN)
@PreservesValue(output = OutputSelection.SINGLE_CONTINUING_OUTPUT)
@SpendingValidator
class StateValidator {
    // ...
}
```

Annotations provide source-local discoverability and compiler diagnostics.
They must be compiled into the property IR; annotation strings must never be
concatenated directly into Lean source. The generated verification report must
render the full interpreted theorem and its ledger assumptions so reviewers do
not need to infer their meaning from a short annotation name.

The target command is deliberately simple:

```text
julc verify
```

It builds or selects the exact artifact, resolves annotations, generates the
Lean workspace internally, runs the proof and controls, and reports the result.
Advanced diagnostic subcommands may exist, but the normal supported workflow
must not require users to run `lake`, install Blaster manually, or edit Lean.

## Typed property IR

The IR must represent properties and assumptions separately. Initial property
forms should include:

- required signer membership;
- integer and byte-string equality/inequality constraints;
- monotonic and bounded state transitions;
- input/output value equality or explicitly selected asset preservation;
- continuing-output cardinality and selection;
- output datum commitment;
- mint policy, token-name, and quantity restrictions; and
- consumed-input/output-reference linkage for double-satisfaction defenses.

Field paths such as `datum.owner` must resolve through compiler-owned JuLC type
metadata. Invalid paths, incompatible types, ambiguous output selection, and unsupported schema
forms must fail closed at specification time.

Domain assumptions must not be allowed to restate the desired guarantee. For
example, an authorization theorem may assume which input is the current
spending input, but it must not assume that the owner signed. The generated
report must list every assumption prominently.

## Verification profiles

An unqualified claim that a contract is “safe” is not precise enough. JuLC may
define versioned profiles that bundle a reviewed threat model, such as:

```text
JuLC Stateful Spending Security Profile v1
```

Such a profile could require authorization, datum continuity, state-transition
rules, value preservation, continuing-output uniqueness, input/output
commitment, non-vacuity, and negative controls.

When all required properties are established, JuLC may report:

```text
FORMALLY VERIFIED AGAINST JULC STATEFUL SPENDING PROFILE V1
```

It must also name the exact script hash, property/profile version, ledger model,
protocol version, dependency revisions, and trust classification.

Until Blaster reconstructs or checks a proof term in Lean, solver-established
properties are labeled `SMT-VALID`, not `KERNEL-PROVED`. The current pinned
Blaster tactic closes a valid Z3 result through `blasterProven`, so its trusted
computing base includes the Lean-to-SMT translation, optimizer, and Z3. Ordinary
Lean proofs whose axiom audit contains no project-specific admission may be
labeled `KERNEL-PROVED` separately.

## Automated controls and results

`julc verify` should eventually:

1. Build or select one exact validator artifact.
2. Validate its hash, Plutus version, builtin inventory, and schema profile.
3. Resolve and type-check the property specification.
4. Generate deterministic Lean properties and execution harnesses.
5. Run non-vacuity checks.
6. Run property-specific negative controls or reviewed vulnerable mutations.
7. Execute the pinned Blaster/Z3 stack.
8. Translate counterexamples back to datum, redeemer, and transaction terms.
9. Emit human-readable and machine-readable results.

A certificate should contain at least:

- compiled-code hash and Cardano script hash;
- property IR and profile hashes;
- generated Lean source hash;
- JuLC compiler, Lean, Blaster, Z3, PlutusCore, and ledger-model versions;
- script purpose, protocol version, semantics variant, and resource bounds;
- explicit domain assumptions;
- per-property result classification;
- negative-control results; and
- known unsupported constructs or coverage limitations.

`SMT-VALID`, `KERNEL-PROVED`, `FALSIFIED`, `UNDETERMINED`, and
`COULD-NOT-EVALUATE` remain distinct outcomes.

## Security requirements

- Generated Lean must be deterministic and protected from code injection.
- Property field paths and literals must be parsed into typed IR nodes.
- User-friendly templates must expose, not conceal, ledger-domain assumptions.
- A successful workspace build must never be reported as a successful proof.
- A successful negative control is necessary evidence against vacuity but is
  not a substitute for the positive theorem.
- Counterexamples must be presented as model witnesses, not guaranteed
  ledger-valid transactions unless full ledger validity was included.
- Certificate verification should be possible independently of the original
  source repository.

## Suggested delivery sequence

### Phase 1: one polished JuLC vertical slice

- `RequiresSigner` property over a typed datum/parameter field;
- one explicit spending-domain model;
- `julc verify` with pinned provisioning;
- non-vacuity control;
- Java/Cardano-readable counterexample; and
- structured certificate tied to the exact UPLC hash.

### Phase 2: stateful spending profile

- monotonic state;
- value preservation;
- continuing-output selection and uniqueness;
- datum/input-reference commitment; and
- double-satisfaction controls.

### Phase 3: minting profile

- signer authority;
- own-policy linkage;
- token-name and quantity constraints; and
- mint/burn-specific controls.

## Open questions

- How should repeatable Java annotations compose into one theorem without
  hiding conflicts or redundancies?
- Which ledger assumptions belong in each versioned profile?
- How should continuing outputs and asset subsets be selected without
  ambiguity?
- Which negative mutations are trustworthy and useful per property?
- Can Blaster add proof reconstruction or a proof artifact checked by Lean?
- Should JuLC preserve the resolved property IR as JSON alongside the
  certificate for independent review and reproducibility?

## Consequences

If implemented successfully, common JuLC security properties could be formally
verified without asking Java developers to learn Lean, while expert users
retain a Lean extension path for novel properties.

The cost is that JuLC becomes responsible for the meaning of every property
template, its ledger assumptions, counterexample translation, profile
versioning, and certificate claims. This is security-critical product surface,
not merely code generation, and therefore requires independent review and
negative controls for every supported template.

## Current decision

No implementation is started by this ADR alone. ADR-009 stages the first
vertical slice as Milestone C.5 after the C.4 managed runner, followed by the
stateful spending and controlled minting profiles. C.2 and C.3 remain
independently useful without the annotation layer.
