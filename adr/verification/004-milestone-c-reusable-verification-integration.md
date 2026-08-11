# ADR-004: Milestone C — Reusable Verification Integration

- **Status:** Implemented (bounded supported subset)
- **Date:** 2026-08-12
- **Parent:**
  [ADR-001 — Verification Strategy for JuLC Using IOG Blaster](001-iog-blaster-verification-strategy.md)
- **Builds on:**
  [ADR-003 — Milestone B Useful Contract Verification](003-milestone-b-useful-contract-verification.md)
- **Scope:** Generate a pinned, exact-artifact Blaster verification workspace
  from a JuLC CIP-57 blueprint without hand-authoring Lean data encodings or
  project plumbing

## Context

Milestones A and B established meaningful evidence, but their adoption cost is
too high for ordinary validator development. A developer currently has to copy
the exact artifact, create and pin a Lake project, translate CIP-57 schemas into
Lean `IsData` instances, choose script-purpose inputs, make semantics and fuel
explicit, and remember the stale-`.olean` and tri-state reporting safeguards.
That repeated manual work is both a usability problem and a security risk.

ADR-001 describes a broader two-to-four-month milestone including upstream
PV11 builtin support and eventual alignment with IOG's Universal Annotation
Language. Neither the UAL nor the complete upstream builtin implementation is
stable enough to make an unversioned dependency of JuLC's first reusable
surface. This sub-ADR defines the locally deliverable foundation and preserves
fail-closed extension points for those later integrations.

## Decision

Add a production CLI workflow:

```text
julc build <project>
julc verify init <project> \
  --validator <exact-blueprint-title> \
  --purpose spending|minting \
  [--out-dir <directory>] [--fuel <positive-number>]
```

`julc verify init` reads the generated CIP-57 blueprint and emits a standalone,
pinned verification workspace. It does not claim a security theorem for the
user. It generates a typed schema boundary, exact artifact import, reusable
property predicates, and an explicit obligation for the developer to refine.
This separation prevents scaffolding from being reported as proof.

The generated workspace contains:

- the exact `compiledCode` bytes selected by exact validator title;
- an artifact manifest with JuLC/compiler identity, hashes, purpose, Plutus V3,
  protocol version 11, semantics variant E, explicit fuel, supported builtins,
  dependency pins, and initial tri-state property status;
- `GeneratedSchemas.lean`, containing strict schema-derived types and `IsData`
  instances;
- reusable authorization, first-input value-preservation, and
  output-reference-commitment predicate templates;
- a purpose-specific validator module using `spendingInputs` or
  `mintingInputs`, `double_cbor_hex`, and the configured preprocessing fuel;
- a pinned `lean-toolchain`, `lakefile.lean`, README, and fail-closed build
  script which directly recompiles the artifact-importing module; and
- a local builtin coverage file matching the Blaster profile used to generate
  the workspace.

## Schema generation contract

The first generator version supports the schema forms JuLC currently emits for
record and sealed-variant entrypoint data:

- `integer` as `PlutusCore.Integer.Integer`;
- `bytes` as `PlutusCore.ByteString.ByteString`; and
- named `constructor` definitions whose fields reference supported named
  definitions.

A single-constructor definition becomes a Lean structure. A multi-constructor
definition becomes a Lean inductive type. Generated `fromData` functions are
strict: constructor index, field count, field order, and field types must all
match the blueprint. This is intentionally stronger than JuLC's currently
permissive generated record decoder and makes the representational boundary
visible to property authors.

Lists, maps, pairs, optional encodings, recursive definitions, inline anonymous
constructors, and ledger-specific aliases are rejected with
`COULD-NOT-EVALUATE` until their encodings are implemented and tested. The
generator must never silently substitute raw `Data` for an unsupported schema.

Names are normalized to valid Lean identifiers and collisions are rejected.
The manifest records the source definition name and generated Lean name.

## Artifact and builtin policy

Artifact selection reuses the exact-title, independent hash validation added in
Milestone A. The generator accepts only `v3` blueprints and the Plutus V3/PV11
profile. Semantics variant E and preprocessing fuel are written into the
manifest and generated source.

The generator scans the artifact's builtin tags. Tags absent from the pinned
Blaster coverage set fail generation before a Lean workspace is written. This
is the local containment strategy for missing PV11 builtins. Implementing tags
89–91, 94–100 remains an upstream/local decoder-and-semantics workstream; it is
not represented as complete merely because the CLI exists.

Fuel must be positive. Runtime/step exhaustion remains a distinct
`COULD-NOT-EVALUATE` outcome in generated reporting; it must never be converted
to validator failure or a successful property result.

## Reusable property surface

The generated `PropertyTemplates.lean` provides composable predicates rather
than pre-proved theorems:

- first-signatory authorization;
- first resolved-input/first-output value equality; and
- first-output inline datum commitment to a spending `TxOutRef` and state.

The generated user-owned `SecurityProperty.lean` defines the initial
`securityProperty`, while the validator module defines
`verificationObligation`; neither contains a `sorry`, axiom, or automatic
theorem claim. The initial manifest result is `COULD-NOT-EVALUATE` with reason
`property-not-specialized`. Once a developer specializes the property and adds
a Blaster theorem plus negative control, the existing tri-state conventions
apply.

This is a deliberate trust boundary: automation removes encoding and project
boilerplate, while threat-model selection remains a reviewed human decision.

## File ownership and regeneration

Generation refuses a non-empty output directory unless `--force` is supplied.
`--force` overwrites only the generator-owned file set and does not delete
unknown user files or an existing `SecurityProperty.lean`. Generator-owned
source/config files start with a generated-file marker; the user property has
a created-file ownership marker, the raw artifact remains valid hexadecimal,
and JSON carries a `generatedBy` field instead of a comment. The manifest
includes a generator schema version so future JuLC releases can migrate safely.

Regeneration is deterministic for the same blueprint, validator title,
purpose, fuel, and JuLC version. Tests compare complete output files, not only
selected fields.

## Testing strategy

The implementation must include:

1. unit tests for strict record and variant `IsData` generation;
2. rejection tests for unsupported schema forms, duplicate/invalid normalized
   names, non-V3 blueprints, nonpositive fuel, unsupported builtins, ambiguous
   validator titles, and unsafe output replacement;
3. CLI integration tests showing the command in root help and successful
   generation from a production-built JuLC fixture;
4. a Lean compile test for the generated state-thread schema and harness using
   the pinned Milestone B dependency set;
5. deterministic regeneration and exact artifact/hash checks;
6. existing `julc-cli` tests and the Milestone B offline suite; and
7. `git diff --check` plus review for accidental theorem claims or unsupported
   schema coercions.

## CLI exit behavior

- `0`: workspace generated and all preflight checks passed;
- `1`: invalid user input or an existing output directory requires `--force`;
- `2`: verification cannot be evaluated safely because of unsupported schema,
  builtin, language/profile, or missing identity information.

Errors identify the exact schema definition or builtin tag and do not leave a
partially generated workspace. Files are assembled in a temporary sibling
directory and moved into place only after all validation succeeds.

## UAL and CBDE compatibility

The manifest uses stable property IDs, explicit artifact/profile identity, and
tri-state results so it can be mapped to UAL annotations when the specification
is versioned. No `@ual` syntax is invented in this milestone. The generator and
manifest schema versions provide the compatibility boundary for a future CBDE
backend or containerized `julc verify run` command.

## Exit criteria

This sub-milestone is complete when a newly built JuLC validator using the
supported CIP-57 schema subset can run one CLI command to obtain:

- strict Lean `IsData` definitions without manual encoding work;
- exact imported UPLC and artifact identity metadata;
- pinned Blaster project/dependency configuration;
- explicit purpose, semantics variant, and exhaustion/fuel policy;
- reusable property templates and a truthful unproved obligation; and
- a buildable verification workspace that fails closed for every unsupported
  schema or builtin encountered.

Complete PV11 builtin semantics and UAL/CBDE execution are follow-up work, not
hidden exit requirements of this bounded implementation.

## Implementation result

The bounded integration is implemented in the production CLI as
`julc verify init`. It reuses the exact-title artifact inspector, validates the
blueprint as Plutus V3, checks the selected purpose against the presence of a
datum schema, rejects builtin tags outside the pinned Blaster set, generates
strict record and variant encodings in dependency order, and writes the
workspace through a temporary sibling directory.

The generated workspace includes the exact UPLC, manifest, schema instances,
property templates, and a checked CEK API fixed to semantics variant E with a
separate `stepExhausted` result. Its script validates Lean, Z3, and all three
Lake dependency revisions, builds support modules, then directly recompiles the
artifact-importing module to avoid stale `.olean` evidence. Until a property is
specialized, the manifest and script both report `COULD-NOT-EVALUATE`; the
script exits 2 after a successful scaffold compilation.

Implementation iteration found that a fresh generated project cannot directly
invoke Lean before Lake has built the dependency graph. The final script first
builds a support library, then uses direct Lean compilation only for freshness.
It also discovers an `elan` installation explicitly so behavior does not depend
on an interactive shell. Final ownership review also split the editable
security property from generator-owned plumbing so `--force` cannot erase a
developer's specialized property.

Validation covered single-constructor records, multi-constructor variants,
deterministic forced regeneration, preservation of unknown user files,
recursive/unsupported schemas, normalized-name collisions, non-V3 blueprints,
nonpositive fuel, purpose mismatch, and unsupported builtin tags. Freshly
generated state-thread spending and controlled-mint workspaces both compiled
their schemas, checked-execution API, templates, and exact artifact imports,
then exited with the expected truthful result 2. The complete `julc-cli` test
suite and Milestone B offline evidence suite also passed.

This status does not mean Blaster supports PV11 tags 89–91 or 94–100. The CLI
turns those artifacts into an explicit fail-closed result. Completing their
Lean decoding, evaluation semantics, SMT translation, and conformance tests is
a separate upstream/local compatibility milestone, as is versioned UAL/CBDE
integration.

## Consequences

### Positive

- New validators no longer require hand-built Lean project plumbing or manual
  record/variant encodings.
- Strict generated decoding prevents properties from accidentally inheriting
  JuLC's permissive record decoder behavior.
- Artifact identity, semantics, fuel, and builtin coverage are consistent with
  the working Milestone B gate.
- Unsupported cases fail before misleading proof work begins.

### Negative

- The initial schema subset excludes common collection and recursive shapes.
- The generated workspace still requires a property author who understands the
  contract's threat model and Cardano ledger context.
- Blaster/Z3 results retain the Milestone B trusted base.
- Builtins missing from the pinned Blaster revision remain unavailable until
  implemented upstream or locally.
