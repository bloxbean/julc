# ADR-002: Milestone A — JuLC Blaster Compatibility PoC

- **Status:** Accepted for implementation
- **Date:** 2026-08-11
- **Parent:**
  [ADR-001 — Verification Strategy for JuLC Using IOG Blaster](001-iog-blaster-verification-strategy.md)
- **Scope:** A reproducible, fail-closed proof of concept over exact
  JuLC-generated Plutus V3 artifacts

## Context

ADR-001 selects exact-artifact verification as JuLC's first formal-assurance
track. Before contract verification can become a product or release gate, JuLC
must demonstrate that it can reproducibly:

1. build a validator through the production compiler path;
2. select the intended blueprint entry without ambiguity;
3. bind a verification result to the exact deployed bytes and script hash;
4. import JuLC's double-CBOR artifact into PlutusCoreBlaster;
5. evaluate it under an explicit Plutus V3/PV11 semantics profile;
6. reject unsupported builtins before claiming coverage;
7. distinguish evaluator exhaustion from validator failure;
8. establish a nontrivial property and refute a deliberately broken artifact.

The current Blaster stack is fast-moving. Lean-blaster also trusts Z3 results
without reconstructing a Lean proof term. Milestone A is therefore a
compatibility and assurance PoC, not a claim that JuLC or its compiler has been
fully verified.

## Decision

Implement Milestone A as a self-contained project at
`verification/blaster/`. It will use the normal JuLC CLI to compile dedicated
fixture projects, import their exact blueprint `compiledCode`, and run pinned
Lean/Blaster properties.

Milestone A will use a two-validator ladder:

- **Datum gate smoke validator:** fixed-shape datum/redeemer equality. This
  diagnoses artifact, wrapper, evaluator, and solver integration without an
  unbounded collection traversal.
- **Typed multisig validator:** requires both datum keys in the transaction
  signatories. This is the security pilot and deliberately exercises typed
  datum decoding and list traversal.

A separate broken multisig fixture will omit the second signer requirement.
The same authorization property must be refuted against that artifact.

No public `julc verify` command will be introduced in this milestone. Narrow,
reusable artifact inspection may be added to existing blueprint tooling when
needed, but orchestration remains inside `verification/blaster/` until the PoC
defines a stable interface.

## Toolchain pins

The initial compatibility baseline is:

| Component | Pin |
|---|---|
| Lean | `v4.24.0` |
| Z3 | `4.15.2` |
| Lean-blaster | `083bae7971414d894b56b5bbf4108c63e17bc42a` |
| PlutusCoreBlaster | `7cf5a78c54b9694ef093bf49edb5d3799b2a49c9` |
| CardanoLedgerApiBlaster | `5dab3c43f042b8735b6d067223baaa8d32ed28a1` |
| Ledger language | Plutus V3 |
| Protocol profile | PV11 / post-Conway / semantics variant E |
| Artifact format | `double_cbor_hex` |

The root Lake project will directly pin all three Blaster packages and commit
its resolved `lake-manifest.json`. Moving branches are not valid release or
verification inputs.

## Repository layout

```text
verification/blaster/
├── README.md
├── lean-toolchain
├── lakefile.lean
├── lake-manifest.json
├── config/
│   ├── blaster-builtins.txt
│   └── verification-profile.json
├── fixtures/
│   ├── smoke/
│   ├── typed-multisig/
│   └── typed-multisig-broken/
├── artifacts/
│   └── artifact-lock.json
├── JulcVerification/
│   ├── CheckedExecution.lean
│   ├── Smoke.lean
│   ├── TypedMultiSig.lean
│   └── NegativeControl.lean
└── scripts/
    ├── prepare-artifacts.sh
    └── verify.sh
```

Generated build output and run manifests are ignored. The imported hex
artifacts and an artifact lock are committed after the first successful run so
changes to the proof subject are visible in review.

## Artifact preparation contract

Artifact preparation must fail unless all checks succeed:

1. The production JuLC CLI builds successfully from the current checkout.
2. The fixture builds successfully through `julc build`.
3. Exactly one blueprint entry matches the configured validator title.
4. `compiledCode` is nonempty, even-length hexadecimal.
5. The outer and inner CBOR byte-string wrappers decode successfully.
6. The FLAT bytes decode to a Plutus V3 `Program`.
7. Recomputed script hash equals the blueprint hash.
8. The final decoded UPLC builtin inventory is a subset of the pinned
   PlutusCoreBlaster coverage set.
9. Artifact hashes match `artifact-lock.json`, unless an explicit lock-update
   operation is requested.

The artifact identity includes both SHA-256 of the outer `compiledCode` bytes
and the Cardano Plutus V3 script hash. These are different hashes and must not
be conflated.

## Checked execution contract

The upstream step-bounded executor maps step exhaustion to `State.Error`.
Milestone A will introduce a local checked execution representation:

```text
Finished(State.Halt value)
Finished(State.Error)
StepExhausted(nonterminal state)
```

The checked evaluator will select builtin-semantics variant E explicitly.
`StepExhausted` maps to `COULD-NOT-EVALUATE`; it is never treated as validator
failure.

Proof reporting distinguishes:

- **Safety:** successful completed execution implies the property.
- **Coverage:** the execution completes for the stated input domain and bound.

If safety succeeds but coverage cannot be established, the unqualified
validator claim remains `COULD-NOT-EVALUATE`. A narrower bounded result may be
reported with its assumptions.

The typed multisig search traverses a symbolic signatory list. Because the
current ledger model does not directly express a maximum serialized
transaction size, Milestone A must not assume that an arbitrary step count
covers every ledger-valid context. This is an intentional tractability test.

## Initial properties

### Smoke property

For every valid V3 spending context, the datum gate succeeds if and only if its
datum and redeemer contain equal integer secrets with the expected constructor
shape.

This must be established together with a checked-execution coverage result.

### Multisig safety property

For every valid V3 spending context in the reported execution domain, if the
validator succeeds, both keys decoded from the datum occur in
`txInfoSignatories`.

The proof result must include the execution-domain/fuel assumptions. Missing
coverage is not silently promoted to a universal result.

### Negative control

Compile the broken multisig through the same JuLC pipeline. Blaster must refute
the authorization property with a context containing the first signer but not
the second signer.

A solver invocation that cannot produce the expected refutation is a failed
negative control.

### Exhaustion control

Run checked preparation with deliberately insufficient fuel. It must return
`StepExhausted`, and the verification driver must emit
`COULD-NOT-EVALUATE: STEP_EXHAUSTED` with a nonzero exit status.

## Outcome model

| Outcome | Required meaning |
|---|---|
| `ESTABLISHED (SMT-VALID)` | Exact pinned artifact and model satisfy the property under all recorded assumptions. |
| `REFUTED` | A reproducible counterexample violates the property. |
| `COULD-NOT-EVALUATE` | Toolchain, import, coverage, exhaustion, solver, or model conditions prevent the claim. |

Lean-blaster currently closes solver-valid goals through a trusted axiom. The
manifest and human report must say `SMT-VALID`, never `KERNEL-PROVED`.

## Run manifest

Each run produces, at minimum:

```json
{
  "sourceCommit": "...",
  "dirtyWorktree": false,
  "julcVersion": "...",
  "validatorTitle": "...",
  "compiledCodeSha256": "...",
  "cardanoScriptHash": "...",
  "plutusLanguage": "PlutusV3",
  "protocolVersion": 11,
  "builtinSemanticsVariant": "E",
  "leanVersion": "4.24.0",
  "z3Version": "4.15.2",
  "dependencyCommits": {},
  "fuel": 0,
  "builtinInventory": [],
  "properties": []
}
```

The manifest is generated after the build rather than committed because
embedding the containing Git commit in a committed file is self-referential.
The committed artifact lock contains stable artifact and toolchain hashes.

## Implementation sequence

1. Scaffold the pinned Lake project and build upstream dependencies.
2. Add smoke, correct multisig, and broken multisig JuLC fixtures.
3. Add exact-title artifact extraction and lock generation.
4. Add double-CBOR/FLAT decoding, hash recomputation, and builtin inventory.
5. Implement checked execution with explicit variant E.
6. Establish the smoke property and exhaustion control.
7. Attempt the multisig property and record tractability honestly.
8. Establish the broken-artifact counterexample control.
9. Add one-command local execution and a separate CI workflow.

## Exit criteria

Milestone A is complete when a clean Linux checkout can demonstrate:

- exact JuLC blueprint import using `PlutusV3 double_cbor_hex`;
- script-hash and artifact-lock identity;
- automated unsupported-builtin rejection;
- pinned Lean, Z3, Lake manifest, and dependency commits;
- explicit semantics variant E;
- exhaustion reported only as `COULD-NOT-EVALUATE`;
- one nontrivial, non-vacuous smoke property reported `SMT-VALID`;
- a truthful result for the typed multisig property, including coverage;
- a counterexample for the broken multisig;
- a complete machine-readable run manifest.

Until every exit criterion passes, the work remains a PoC and is not a release
gate.

## Implementation finding: permissive record decoding

The first smoke proof refuted the initially intended strict schema property.
The exact JuLC artifact accepted datum and redeemer constructors with arbitrary
constructor tags and trailing fields as long as the first field decoded to the
expected integer. This matches the current generated record decoder, which
projects fields after `unConstrData` without first establishing the expected
constructor tag and exact arity.

Milestone A preserves this as a solver-refuted negative control and separately
proves the artifact's actual first-field behavior. The compiler/schema
discrepancy requires separate security triage; it must not be hidden by stating
the weaker property as if it were the source-level encoding contract.

## Consequences

### Positive

- JuLC gains an auditable path from Java source to the exact verified bytes.
- Tool/model failures cannot be confused with successful verification.
- The pilot measures real V3 wrapper, typed-data, and collection behavior.
- The artifact and toolchain contracts can later underpin `julc verify`.

### Negative

- Initial setup is heavyweight and version-sensitive.
- Checked symbolic execution may require a local Blaster extension before it
  can be proposed upstream.
- The multisig proof may expose an input-bound or induction limitation and
  legitimately remain `COULD-NOT-EVALUATE` during the PoC.
- Solver-valid results still trust Z3 and the translation pipeline.
