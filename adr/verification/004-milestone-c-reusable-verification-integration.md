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

## Type-information authority and artifact boundary

JuLC obtains datum, redeemer, and parameter types while compiling Java source.
As implemented in C.1, `julc-blueprint` consumes the compiler-owned
`ContractSchema`; it no longer reparses validator Java. It serializes that
resolved information into `build/plutus/plutus.json` beside the compiled UPLC.

The long-term source of truth should be a compiler-owned on-chain schema IR,
not a second best-effort source parser inside `julc-blueprint`. The compiler has
already resolved the JuLC subset, aliases, generic arguments, record layouts,
sealed variants, and actual `PlutusData` encoding decisions. The existing
`PirType` hierarchy already represents primitives, collections, optionals,
records, and sum types and is a useful input, although the public schema graph
also needs stable named references and source diagnostics.

Milestone C.1 introduces or reuses that structured type graph from the
successful compilation and uses it to complete the CIP-57 definitions in
`plutus.json`. `julc verify init` then generates Lean only from the serialized
CIP-57 boundary. Schema-generation failure for datum, redeemer, or parameter
types must fail the build instead of being silently discarded.

The accepted initial pipeline is:

```text
Java source
  → JuLC parsing, type resolution, and encoding selection
      ├─ UPLC Program
      └─ compiler-owned on-chain schema graph
            ↓
        build/plutus/plutus.json
          ├─ compiledCode and script hash
          └─ complete CIP-57 schemas
                    ↓
             julc verify init .
                    ↓
        generated Lean types, IsData, and artifact harness
```

There must not initially be a second Java-to-Lean route. Making the verifier
consume the same serialized artifact that external users receive exercises the
portable boundary and prevents source and blueprint behavior from drifting.

### Compiler non-regression contract

The schema graph is observational compiler metadata. Introducing it must not
change Java subset validation, PIR/UPLC lowering, optimization, builtin
selection, parameter application, cost behavior, or generated script bytes.

Prefer a new schema-aware compilation result or wrapper over changing the
semantics of the existing `JulcCompiler.compile` API. Existing compiler callers
that do not request blueprint metadata must continue to compile exactly as
before. `julc build`, which promises to emit a deployable blueprint, may fail
closed when a datum, redeemer, or parameter encoding cannot be represented
truthfully; that is an intentional artifact-generation diagnostic, not a
code-generation change.

C.1 cannot exit unless:

- the existing compiler, VM, CLI, blueprint, and end-to-end suites pass;
- every committed Milestone A/B artifact retains its exact compiledCode bytes,
  script hash, builtin inventory, and artifact lock;
- representative validators compiled with and without schema capture produce
  byte-identical UPLC;
- schema extraction is deterministic and has no effect on diagnostics unrelated
  to schemas; and
- tests prove that a schema failure cannot leave a partial or apparently valid
  blueprint while the standalone compiler API remains usable.

### Explicit schema escape hatch

Users must be able to compile while schema support catches up with a valid JuLC
type, but the escape hatch must not manufacture evidence. The CLI is:

```text
julc build --no-blueprint
```

`--skip-blueprint` may be provided as a discoverable alias. This mode performs
normal compilation and emits per-validator UPLC, compiledCode, and script-hash
metadata, but skips schema extraction and does not create the standard
`build/plutus/plutus.json`. If a blueprint from an earlier build exists, the
command must remove or unmistakably invalidate that generated file, or place
the no-blueprint outputs in a distinct directory, so it cannot be mistaken for
the new artifact.

Strict schema generation remains the default. There is deliberately no
`--treat-unknown-as-data` mode: replacing an unsupported type with an opaque
schema would allow transaction builders and verification properties to assume
the wrong encoding. A Java entrypoint explicitly typed as `PlutusData` is
different—it intentionally requests the CIP-57 unconstrained `Data` boundary
and remains supported.

Artifacts produced through `--no-blueprint` are deployable raw compiler outputs
but are not schema-verified. `julc verify init` and any future verification or
typed-client generator must reject them until a complete blueprint is produced.

The implemented equivalents are `julc { blueprint = false }` for the Gradle
plugin, `-Ajulc.blueprint=false` for the annotation processor, and
`"blueprint": false` for the Playground compile request. Strict blueprint
generation remains the default in every integration.

CIP-57 remains the default input boundary for `julc verify init` because it:

- travels with the exact deployable `compiledCode` and script hash;
- permits verification when Java source is unavailable;
- gives external tools a standard, language-neutral schema; and
- prevents the verification generator from independently guessing compiler
  encoding rules.

An optional source-aware verification command may invoke the compiler directly
for convenience, but it must produce the same schema IR and compare the
resulting artifact hash with the artifact being verified. UPLC alone is not a
replacement for this information: UPLC is untyped, so high-level record,
variant, generic-container, and field names cannot in general be recovered
reliably from `compiledCode`.

## Collection, optional, and recursive-schema roadmap

The current limitation is not inherent to CIP-57. CIP-57 defines `list`, `map`,
`integer`, `bytes`, and `constructor` schemas, supports composition through
subschemas, and provides reusable `definitions` and `$ref` references. See the
[CIP-57 core vocabulary](https://cips.cardano.org/cip/CIP-57).

There are two independent implementation boundaries:

1. `julc-blueprint` must faithfully describe the Java type and its actual
   on-chain encoding; and
2. `julc verify init` must translate that schema into a strict Lean type and
   `IsData` implementation.

The blueprint boundary had to be fixed first. A correct Lean generator cannot
recover type structure that a blueprint has already reduced to opaque `Data`.
C.1 added `items`, `keys`, and `values`, retained nested `Optional<T>`, removed
implicit unknown-type fallback, and made the spending datum's ledger-level
top-level `Optional<T>` an explicit compiler-semantic exception rather than a
source-text heuristic.

### Milestone C.1: faithful JuLC CIP-57 schemas

Extend `julc-blueprint` before expanding the Lean generator.

Required implementation:

- define a compiler-owned, immutable on-chain schema graph, seeded from the
  resolved `PirType` information, with primitives, containers, optionals,
  constructors, named definitions/references, and source locations;
- attach the entrypoint datum, redeemer, and parameter schema roots to a
  successful compilation result so `julc-blueprint` does not reparse Java or
  repeat type resolution;
- replace the current `SchemaGenerator` string-prefix/generic-name matching
  with conversion from the compiler-owned graph; any transitional source
  traversal must use structured JavaParser `Type` nodes and must be removed
  before C.1 exits;
- extend the internal schema model and JSON writer with CIP-57 `items`, `keys`,
  `values`, and the applicable size/shape keywords;
- emit `List<T>`/JuLC list types as `dataType: list` with an `items` subschema;
- emit `Map<K,V>`/JuLC map types as `dataType: map` with `keys` and `values`
  subschemas;
- emit nested `Optional<T>` as its actual Plutus constructor encoding:
  `Some x = Constr 0 [x]` and `None = Constr 1 []`;
- reserve named definitions before traversing their fields so self and mutual
  references become `$ref` edges rather than unbounded Java recursion;
- remove the unknown-type-to-opaque-`Data` fallback for typed datum, redeemer,
  and parameter positions; unsupported types must fail blueprint generation
  with a precise diagnostic; and
- validate every generated blueprint against the official CIP-57 meta-schema.

`julc verify init .` remains a consumer of
`build/plutus/plutus.json`; it need not parse Java. A later convenience change
may run `julc build` automatically when the blueprint is missing or stale, but
that command must use the same compiler/schema pipeline and verify the selected
artifact hash. It is not a prerequisite for collection-schema correctness.

Encoding conformance tests must compare the schema with JuLC's compiler and VM,
not merely compare JSON text. For each supported type, tests construct Java
values, compile/encode them through JuLC, and assert that the resulting
`PlutusData` has exactly the constructor/list/map layout advertised by the
blueprint.

The initial compatibility examples are:

```java
record ListDatum(List<BigInteger> values) {}
record MapDatum(Map<byte[], BigInteger> balances) {}
record OptionalDatum(Optional<byte[]> owner) {}
```

If JuLC's supported on-chain API uses `JulcList` or `JulcMap` rather than the
JDK interfaces in a given position, the schema generator must recognize the
actual supported type and reject the unsupported spelling. It must not imply
compiler support merely because CIP-57 can describe the data.

Milestone C.1 exits when these types produce meta-schema-valid blueprints that
round-trip against the compiler's real encoding, while unknown types fail
closed without an opaque substitution.

### Milestone C.2: Lean containers and optional values

After C.1, extend `julc verify init` to consume the new schema forms.

Required implementation:

- consume JuLC's constructor-based boolean schema and generate a strict Lean
  decoder for `False = Constr 0 []` and `True = Constr 1 []`;
- generate strict `Option α` encoding compatible with JuLC's `Some`/`None`
  constructors;
- generate list encoders/decoders that require every `Data.List` element to
  satisfy the item schema;
- generate map encoders/decoders over Plutus `Data.Map`, preserving its
  association-list representation and requiring every key and value to satisfy
  its schema;
- explicitly specify whether duplicate map keys are rejected, preserved, or
  normalized, based on the compiled JuLC decoder rather than Java `Map`
  intuition;
- support arbitrary nonrecursive nesting such as
  `Optional<List<Map<byte[], BigInteger>>>`; and
- keep generated decoders strict about constructor indexes, arity, and
  primitive shape.

Tests must cover successful round trips and malformed values: wrong list item,
wrong map key/value, invalid optional tag/arity, duplicate keys under the chosen
policy, and nested failure paths. Generated spending and minting workspaces
using each container form must compile against the pinned Blaster stack.

Milestone C.2 exits when supported collection/optional contracts require no
handwritten Lean encodings and the generated representation has been checked
against the exact JuLC artifact encoding.

### Milestone C.3: productive recursive ADTs

Recursive data requires a separate milestone because schema generation,
termination, and symbolic verification all become recursive.

This direct record is not a valid initial target:

```java
record Node(BigInteger value, Node next) {}
```

Without `null`, it has no finite base inhabitant. On-chain recursive data must
be productive, for example:

```java
sealed interface Node permits End, Cons {}
record End() implements Node {}
record Cons(BigInteger value, Node next) implements Node {}
```

Required implementation:

- accept recursive or mutually recursive named definitions only when their
  constructor graph contains a finite base case;
- reject direct nonproductive cycles with a source diagnostic;
- generate recursive Lean inductive declarations in dependency groups;
- generate structurally terminating `toData` and `fromData` functions and
  prove or test strict round-trip behaviour;
- provide an explicit verification-depth setting for Blaster symbolic
  execution over recursive values; and
- separate bounded artifact evidence from general kernel-checked induction
  lemmas, without reporting the former as an unbounded proof.

Tests must cover a recursive list/tree with a base constructor, mutual
recursion, malformed recursive encodings, nonproductive-cycle rejection,
depth exhaustion as `COULD-NOT-EVALUATE`, and at least one composition lemma
proved by Lean induction.

Milestone C.3 exits when productive recursive datum/redeemer types can be
generated without handwritten encodings, bounded symbolic coverage is
reported honestly, and unbounded claims require explicit inductive proofs.

### Documentation deliverable

Each milestone must update
[`verification/README.md`](../../verification/README.md) so a new user can see:

- prerequisites and exact commands;
- the boundary implemented in the current revision;
- a minimal supported example and its expected result;
- unsupported schemas and builtins;
- how to author a property and negative control; and
- how to distinguish `SMT-VALID`, `KERNEL-PROVED`, `REFUTED`, and
  `COULD-NOT-EVALUATE`.

Documentation accuracy is part of each milestone's exit criteria. Planned
`julc verify run` or container automation must not be described as available
until an end-to-end test exercises it.

### Sequencing and status

The milestones are ordered dependencies:

```text
C.1 faithful blueprint
  → C.2 Lean containers/optionals
    → C.3 productive recursion and induction
```

C.1 and C.2 may be delivered type-by-type, starting with `Optional`, then
lists, then maps. C.3 must remain separate because increasing Blaster fuel is
not a substitute for recursive reasoning. Until each increment reaches its
exit criterion, `julc verify init` continues to reject the corresponding schema
with `COULD-NOT-EVALUATE`.

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
