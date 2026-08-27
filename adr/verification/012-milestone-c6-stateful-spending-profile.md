# ADR-012: Milestone C.6 Stateful Spending Profile v1

- **Status:** Implemented, manually reviewed, and integrated
- **Date:** 2026-08-13
- **Related:**
  [ADR-007 — Java-Annotation Security Properties](007-java-annotation-security-properties-and-one-command-verification.md),
  [ADR-009 — Verification Product Roadmap](009-verification-product-roadmap.md),
  [ADR-011 — `@RequiresSigner` Vertical Slice](011-milestone-c5-requires-signer.md)

## Context

C.5 proves one authorization property over the exact compiled UPLC. Stateful
contracts need a profile rather than an isolated comparison: checking only that
a number increases does not ensure that the transaction creates the intended
successor at the same script, commits the new datum, preserves locked value, or
retains the authority.

C.6 must remain Java-authored and outside compiler lowering. It must compose
with C.5, resolve every field through compiler-owned types, generate one exact
mathematical profile, and refuse ambiguous output selection. It must not claim
ledger-validity modeling that is not present in the pinned Lean model.

## Decision

### Java interface

C.6 adds the following verification-only declarations:

```java
@RequiresSigner("datum.owner")
@Monotonic(
    current = "datum.state",
    next = "redeemer.nextState",
    relation = Relation.GREATER_THAN)
@PreservesValue(output = OutputSelection.SINGLE_CONTINUING_OUTPUT)
@SpendingValidator
class StateMachine { ... }
```

The C.6 profile requires exactly these three annotations. Partial combinations
fail at the annotation source location rather than silently proving a weaker
property. `@Monotonic` is not repeatable in v1. The supported relation is
strict `GREATER_THAN`; later relations require independently versioned
semantics. The only output selection is `SINGLE_CONTINUING_OUTPUT`.

Paths are intentionally narrow:

- authority: `datum.<direct-byte-field>`;
- current state: `datum.<direct-integer-field>`; and
- next state: `redeemer.<direct-integer-field>`.

Nested, optional, container, raw `Data`, and validator-parameter selections
fail closed in C.6. Datum and redeemer roots may otherwise use any schema that
the C.3 generator can strictly decode, including productive recursive fields;
the three selected fields themselves must be the direct primitive fields
listed above. Solver inability on a supported shape remains a non-success
result. Annotation text is parsed into typed path segments and is never copied
into Lean.

### Typed property IR

The optional `julc-verification` module owns
`StatefulSpendingProperty` with template
`julc.stateful-spending/v1`. It records:

- validator/purpose/property identity;
- typed authority, current-state, and next-state selections;
- datum and redeemer nominal types;
- strict monotonic relation;
- explicit continuing-output selection;
- mandatory guarantees and zero domain assumptions;
- Java source references for all annotations; and
- `ledgerValidityModeled: false`.

The compiler and core modules do not depend on these annotations or the IR.
Presence or absence of all C.6 annotations must produce byte-identical UPLC.

### Exact profile semantics

For datum type `D`, redeemer type `R`, and context `ctx`, define the C.6
security predicate only when all of the following hold:

1. `ctx.scriptContextScriptInfo` is `SpendingScript ownRef (some datumData)`.
2. `datumData` strictly decodes as `D`.
3. `ctx.scriptContextRedeemer` strictly decodes as `R`.
4. `findOwnInput ctx` returns an input whose resolved output address and value
   identify the state being consumed.
5. Exactly one transaction output has the same complete address as that own
   input. Address equality includes credential and staking credential.
6. That output carries an inline datum which strictly decodes as `D`.
7. The selected current authority occurs anywhere in `txInfo.signatories`.
8. The continuing output value equals the own input value structurally.
9. The continuing output preserves the selected authority.
10. The continuing datum state equals the selected redeemer next state.
11. `currentState < nextState`.

The exact imported UPLC obligation is:

```text
for every V3 ScriptContext ctx,
  exact artifact execution succeeds within pinned CEK fuel
  → statefulSpendingV1(ctx)
```

Missing inputs, absent/malformed datums, hashed rather than inline successor
datums, zero or multiple continuing outputs, mismatched values/owners/states,
or absent authorization make the predicate false. They are conclusions of the
profile, not assumptions.

Structural `Value` equality is deliberate in v1. It is stronger than
asset-quantity equivalence and avoids assuming canonical map normalization for
arbitrary solver values. A future ledger-valid profile may use validated,
extensional values once those rules are explicitly included.

### Selection and double-satisfaction boundary

A continuing output is any output whose full address equals the resolved own
input address. Requiring exactly one prevents first-output ambiguity and the
common double-satisfaction shape where one input can be paired with several
candidate successor outputs. C.6 does not claim global one-to-one matching
across multiple script inputs; that requires transaction-level quantified
linkage and remains a later profile.

### Evidence, bounds, and result classification

C.6 reuses C.5's authenticated result protocol, generated-source/property-IR
hashes, admission scan, non-vacuity gate, and five result classes. A vacuous
result skips the main theorem. The certificate must identify every component
of the profile and state:

- `ledgerValidityModeled: false`;
- `fuelBounded: true` and the exact CEK fuel;
- structural value equality;
- single-continuing-output selection; and
- the absence of global multi-input transaction linkage.

`SMT-VALID` covers only paths completing within the recorded fuel. It is not a
claim that the entire contract or every ledger-valid transaction is safe.

Tracked evidence contains:

- a conforming state machine;
- a validator that omits signer enforcement;
- a validator that accepts a decreasing transition;
- a validator that accepts value leakage or ambiguous continuing outputs; and
- an always-failing vacuity control.

Each vulnerable control must be `REFUTED`; unknown solver behavior remains a
non-success result.

## Rejected alternatives

- **Prove only `datum.state < redeemer.nextState`.** This does not connect the
  redeemer to a committed successor output.
- **Select the first matching output.** Ordering is not a security boundary and
  hides ambiguous/double-satisfaction transactions.
- **Assume one continuing output.** The validator must enforce it; assuming it
  would remove the property most state machines need.
- **Use extensional value equality without validity rules.** The pinned model
  represents values as association lists, including malformed shapes.
- **Change compiler record decoding in this milestone.** That changes deployed
  script bytes and needs its own compatibility decision.

## Acceptance criteria

C.6 is complete when:

- all annotations and property IR remain in `julc-verification`;
- partial/invalid profiles fail with Java source diagnostics;
- annotations have zero UPLC effect;
- generated Lean implements all eleven clauses above over the pinned V3 API;
- the authorized fixture is `SMT-VALID` and non-vacuous;
- every vulnerable fixture is `REFUTED` with retained raw models;
- the always-failing fixture skips the main theorem as vacuous;
- certificate and tamper bindings include the exact profile and bounds;
- earlier C.2–C.5 evidence still passes; and
- affected Java module suites pass with no compiler/core source changes.
