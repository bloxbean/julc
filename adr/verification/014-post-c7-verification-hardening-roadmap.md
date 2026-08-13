# ADR-014: Post-C.7 Verification Hardening Roadmap

- **Status:** Proposed
- **Date:** 2026-08-13
- **Related:**
  [ADR-001 — IOG Blaster Verification Strategy](001-iog-blaster-verification-strategy.md),
  [ADR-009 — Verification Product Roadmap](009-verification-product-roadmap.md),
  [ADR-010 — Managed Verification Runner](010-milestone-c4-managed-verification-runner.md),
  [ADR-012 — Stateful Spending Profile](012-milestone-c6-stateful-spending-profile.md),
  [ADR-013 — Controlled Minting Profile](013-milestone-c7-controlled-minting-profile.md),
  [ADR-015 — Strict On-Chain Data Boundaries](015-strict-on-chain-data-boundaries.md),
  [ADR-016 — Typed Verification DSL and Profile Catalog](016-typed-verification-dsl-and-profile-catalog.md)

## Context

Milestones C.4 through C.7 make three Java-authored verification profiles
usable through one command. JuLC can compile a validator, resolve a typed
property, generate Lean, run the pinned Blaster stack locally or through
Docker, check non-vacuity, retain counterexamples, and issue a certificate
bound to the exact UPLC.

Adding annotations indefinitely is not the highest-value next step. The
existing product needs a stable release surface, ordinary validators need a
clear route to strict boundary decoding, reusable Java helpers need
specification conformance, and Blaster's builtin coverage still excludes some
JuLC artifacts. These items differ in compatibility risk and should not be
combined into one compiler change or one verification claim.

This ADR establishes priorities after C.7. It does not itself authorize a
breaking compiler change or promote an SMT result into whole-contract safety.

## Decision

Work after C.7 proceeds through the following ordered highlights. Parallel
work is allowed when it does not bypass an earlier release gate.

### H.1: Stabilize and release C.5–C.7

The first priority is to make the implemented profiles reproducible for users
and CI systems.

Deliverables are:

- independent review of C.6 and C.7 theorem semantics and certificates;
- full pull-request CI for the affected Java modules and evidence drivers;
- a from-scratch user guide for local and Docker backends;
- certificate-schema compatibility tests and documented process exit codes;
- Docker runtime evidence on both `linux/amd64` and `linux/arm64`;
- a clean-cache run that acquires every pinned dependency; and
- a release checklist that names Lean, Z3, Blaster, PlutusCore, ledger-model,
  builtin-semantics, fuel, and exact artifact hashes as trust inputs.

H.1 is complete when a user can start with a fresh JuLC project, obtain a
classified result through either supported backend, and understand the claim
without reading generated Lean.

### H.2: Provide opt-in strict on-chain data boundaries

The generated Lean properties use exact constructor tags and arities. Current
JuLC record projection reads the expected leading fields but does not, by
itself, reject every wrong record tag or trailing field. Natural validators can
therefore be correctly refuted on malformed `Data` even when their business
logic looks reasonable.

[ADR-015](015-strict-on-chain-data-boundaries.md) specifies the proposed
compatibility boundary. The important roadmap constraints are:

- existing contracts retain byte-identical UPLC by default;
- strict decoding is an explicit compiler-owned feature, not a hidden effect
  of verification annotations;
- unsupported strict schemas fail at compilation rather than falling back to
  permissive projection; and
- formal verification continues checking exact UPLC instead of trusting the
  strict-mode declaration.

H.2 is complete only after positive and malformed-input VM tests, golden-byte
compatibility tests, budget measurements, and C.5–C.7 verification evidence.

### H.3: Verify JuLC standard-library helper conformance

Define a standalone conformance artifact and prove that selected compiled Java
helpers implement reviewed Lean specifications. Initial candidates are:

1. `ContextsLib.signedBy`;
2. `ContextsLib.findOwnInput`;
3. continuing-output selection helpers;
4. value comparison and preservation helpers; and
5. mint-policy and token lookup helpers.

Each conformance certificate must bind:

- exact helper UPLC;
- the Java method and compiler version;
- the Lean specification and dependency commits;
- the equality or observational relation being checked;
- fuel and other bounds; and
- positive, malformed, and vulnerable controls.

Helper conformance is reusable evidence. It does not prove arbitrary callers,
the full compiler, or the ledger-validity of a transaction.

### H.4: Complete PV11 builtin compatibility

Artifacts containing currently unsupported builtin tags 89–91 or 94–100
remain `COULD-NOT-EVALUATE` at verification preflight. Support should be added
upstream where practical and must include:

- FLAT decoding and malformed-input tests;
- evaluator behavior for the pinned semantics variant;
- SMT translation or an explicit unsupported symbolic result;
- comparison with the canonical evaluator; and
- positive, negative, and exhaustion evidence.

Preflight coverage must never be broadened merely because decoding succeeds.
The symbolic semantics used by the theorem must also be supported.

### H.5: Add narrowly versioned security profiles

New profiles follow only after H.1 and must use the same typed-IR,
non-vacuity, counterexample, certificate, and module-boundary discipline.
High-value candidates are:

- several acceptable signers and threshold authorization;
- transaction validity-range constraints;
- required payments to an address or credential;
- NFT uniqueness and one-shot minting;
- terminal and permitted state transitions;
- asset-specific rather than structural whole-value preservation; and
- global multi-input/output linkage against double satisfaction.

Every profile requires an exact semantic ADR and a vulnerable negative
control. Similar-looking annotations must not silently reuse a weaker
template version.

### H.6: Pursue compiler certification separately

Milestone D remains a later, separate track:

1. expose stable pre- and post-optimization UPLC artifacts;
2. translation-validate selected optimizer passes;
3. specify and validate a nontrivial PIR-to-UPLC subset; and
4. define a deliberately small JuLC Core source semantics.

Compiler certification reduces trust in lowering. C.5–C.7 instead verify a
property of the exact deployable UPLC. The two certificate types must remain
distinct.

## Cross-cutting release rules

All roadmap items preserve these rules:

- Unknown, unsupported, timed-out, under-provisioned, or tampered work is not
  success.
- `SMT-VALID` is scoped to the named property, exact artifact, model, and
  recorded execution bounds.
- Verification annotations remain outside core lowering unless a separate
  compiler ADR explicitly introduces a compiler-owned annotation.
- No result claims complete Cardano ledger validity unless that predicate is
  part of the checked obligation and certificate.
- Existing deployed script bytes are compatibility inputs, not disposable
  implementation details.

## Suggested execution sequence

```text
H.1 release hardening
       |
       +--> H.2 strict boundaries --> simpler natural profile examples
       |
       +--> H.3 stdlib conformance
       |
       +--> H.4 builtin coverage
                         |
                         +--> H.5 additional profiles

H.6 compiler certification remains a separately reviewed track.
```

H.2, H.3, and H.4 may proceed concurrently after the H.1 review baseline, but
none may weaken fail-closed behavior to make a demonstration pass.

## Rejected alternatives

- **Immediately add many annotations.** Breadth before release and semantic
  hardening would make supported claims harder to understand and maintain.
- **Make all record decoding strict without an opt-in.** That silently changes
  existing UPLC, size, cost, and possibly validation outcomes.
- **Treat helper names as specifications.** A Java method and a similarly named
  Lean predicate are not equivalent until their behavior is checked.
- **Wait for full compiler verification before shipping profiles.** Exact-UPLC
  property verification already provides useful, separately scoped evidence.
- **Call unsupported builtins assumptions.** Symbolic absence is a reason to
  stop, not a domain assumption that can be hidden in a certificate.

## Exit condition

This roadmap is complete when C.5–C.7 have a reproducible release surface,
strict-boundary users no longer need handwritten raw-shape checks for the
supported subset, high-value stdlib helpers have conformance certificates,
the targeted PV11 builtin gap is closed, and at least one subsequent profile
is admitted under the same evidence standard.
