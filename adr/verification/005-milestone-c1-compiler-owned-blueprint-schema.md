# ADR-005: Milestone C.1 — Compiler-Owned Blueprint Schemas

- **Status:** Accepted
- **Date:** 2026-08-12
- **Parent:**
  [ADR-004 — Milestone C Reusable Verification Integration](004-milestone-c-reusable-verification-integration.md)
- **Scope:** Make `plutus.json` a faithful, fail-closed description of the
  datum, redeemer, and parameter encodings selected by the JuLC compiler.

## Context

Milestone C generated a reusable Lean/Blaster workspace from a CIP-57
blueprint, but the blueprint schema is currently produced by an independent
best-effort parser in `julc-blueprint`:

- Java source is parsed a second time after successful compilation;
- types are recognized through strings such as `"Optional<"`;
- generic list and map arguments are lost;
- the spending datum's top-level `Optional<T>` is unwrapped by source-text
  convention rather than by compiler/ledger semantics;
- unknown types silently become opaque `Data`;
- a schema error is swallowed and the build still emits a blueprint; and
- the schema model cannot serialize CIP-57 `items`, `keys`, or `values`.

This is both a verification problem and a general artifact-quality problem.
UPLC is untyped, so a verifier cannot recover the lost Java contract interface
from `compiledCode` later.

The compiler already resolves Java types into `PirType`, including integer,
bytes, string, boolean, list, map, optional, record, and sum types. Schema
metadata can therefore be captured from the same successful compilation
without changing UPLC generation.

## Decision

### 1. Add an opt-in schema-aware compiler result

Keep the existing `JulcCompiler.compile` API and semantics unchanged. Add a
schema-aware compilation API returning:

```text
ContractCompileResult
├── CompileResult       existing Program, diagnostics, parameters, source map
└── ContractSchema      compiler-owned interface metadata
    ├── datum root, when present
    ├── redeemer root
    └── compile-time parameter roots
```

Each root has its source name, resolved `PirType`, and source location. The
schema object is immutable and contains no CIP-57 or Lean-specific classes.
`julc-blueprint` converts it to CIP-57; it does not parse Java source.

Schema capture occurs after the compiler has registered and resolved types and
validated the entrypoint, but it is observational: it cannot feed values back
into PIR or UPLC generation.

Explicit multi-purpose validators require purpose-indexed schema roots. If the
first implementation cannot describe those truthfully, schema-aware
compilation rejects them with a source diagnostic while ordinary compilation
remains available. It must not select an arbitrary first entrypoint.

### 2. Use one portable artifact boundary

The implemented pipeline is:

```text
Java source
  → JuLC compiler
      ├─ exact UPLC Program
      └─ ContractSchema
             ↓
        CIP-57 serializer
             ↓
        build/plutus/plutus.json
          ├─ compiledCode + script hash
          └─ complete schemas
                    ↓
             julc verify init .
                    ↓
             generated Lean workspace
```

There is no direct Java-to-Lean path in C.1. `julc verify init` consumes the
same `plutus.json` that is published and deployed.

### 3. Map resolved types to actual Data encodings

The CIP-57 converter uses these mappings:

| Resolved JuLC type | CIP-57 schema | Actual boundary encoding |
|---|---|---|
| `IntegerType` | `dataType: integer` | `IData` |
| `ByteStringType` | `dataType: bytes` | `BData` |
| `StringType` | `dataType: bytes` | UTF-8 followed by `BData` |
| `DataType` | schema without `dataType` | already `Data` |
| `ListType(T)` | `dataType: list`, `items: T` | `ListData` |
| `MapType(K,V)` | `dataType: map`, `keys: K`, `values: V` | `MapData` |
| `OptionalType(T)` | two constructors | `Some = Constr 0 [T]`, `None = Constr 1 []` |
| `BoolType` | two constructors | `False = Constr 0 []`, `True = Constr 1 []` |
| `RecordType` | one constructor at index 0 | positional encoded fields |
| `SumType` | one constructor per compiler tag | positional encoded fields |

CIP-57 `#boolean` describes the UPLC builtin boolean, not JuLC's outward-facing
Data encoding, and therefore is not used for Java `boolean` at a datum,
redeemer, or parameter boundary.

Nested compositions are recursive. For example,
`Optional<List<Map<byte[], BigInteger>>>` retains every layer. Named records and
sum types become definitions and field references. Definition names are stable,
deterministic, and collision-checked.

The top-level `Optional<T>` accepted for the datum of a three-argument spending
validator is different: it represents the ledger's `Maybe` datum in
`ScriptInfo`, not data attached to the output. Its blueprint root is therefore
`T`. An `Optional<T>` nested inside a datum/redeemer record, or used at another
true Data boundary, retains its two-constructor encoding.

Generated builtin/container definitions use an internal `@julc:` namespace,
which cannot collide with a Java simple type name. Named record and sum
definitions are namespaced by validator in an aggregate blueprint, so two
validators may each declare their own differently shaped `Datum` or `Redeemer`.
Ambiguous same-simple-name types within one validator remain a fail-closed
compiler-schema limitation until the schema IR carries qualified identities.

Direct and mutual recursion remain a C.3 compiler limitation. The C.1 schema
model may represent named references, but C.1 does not relax the compiler's
current circular-type rejection.

### 4. Validate and fail closed

`BlueprintGenerator.generate` no longer catches and discards schema failures.
Normal `julc build` either emits a complete blueprint or reports a source-linked
error and emits no new `plutus.json`.

Generated documents include the official CIP-57 `$schema` identifier and are
validated against a pinned copy/version of the official meta-schema. Validation
must be local and deterministic; builds do not fetch a moving schema over the
network. Because the official meta-schema deliberately permits broad schema
bodies, JuLC additionally validates every generated definition and argument
body against the smaller set of Data schema forms it emits.

An explicitly declared `PlutusData` remains a valid opaque boundary. An unknown
or unsupported Java type never becomes opaque `Data` implicitly.

### 5. Provide a deliberate no-blueprint mode

The escape hatch is:

```text
julc build --no-blueprint
```

`--skip-blueprint` is an alias. This mode uses the existing compiler API, skips
schema capture, and emits per-validator text UPLC, deployable compiledCode, and
script-hash metadata. It does not emit `plutus.json`.

Equivalent opt-outs are available at every compiler integration boundary:

| Integration | Opt-out |
|---|---|
| CLI | `julc build --no-blueprint` |
| Gradle plugin | `julc { blueprint = false }` |
| Annotation processor | `-Ajulc.blueprint=false` |
| Playground API | request field `"blueprint": false` |

The Playground UI exposes the API option as a default-enabled Blueprint
checkbox. Direct annotation-processor users must clean their class-output
directory when changing from enabled to disabled because the standard `Filer`
API cannot delete an aggregate written by an earlier javac invocation. The
Gradle plugin performs that cleanup at its task boundary.

No-blueprint output must be isolated from or atomically invalidate a previous
`plutus.json`; stale blueprint bytes must not appear to describe the new UPLC.
Schema-dependent tools, including `julc verify init`, reject no-blueprint
artifacts.

## Implementation plan

1. Add immutable `ContractSchema` and `ContractCompileResult` types to
   `julc-compiler`.
2. Add schema-aware string/path compilation entrypoints while preserving every
   existing compiler entrypoint.
3. Capture entrypoint and `@Param` roots from the live `TypeResolver` after
   successful resolution.
4. Replace `SchemaGenerator.extract(String)` with a pure
   `ContractSchema`-to-CIP-57 converter.
5. Extend the blueprint schema model and serializer with nested subschemas.
6. Add pinned, offline blueprint validation.
7. Change `BuildCommand` to request schema-aware results in strict mode.
8. Add `--no-blueprint`/`--skip-blueprint`, raw compiledCode output, and stale
   artifact protection.
9. Keep `julc verify init` consuming `build/plutus/plutus.json`; collection
   translation to Lean begins in C.2.

## Verification plan

### Compiler non-regression

- Run the complete `julc-compiler` suite, including golden UPLC tests.
- Compile representative validators through ordinary and schema-aware APIs and
  assert byte-identical FLAT/UPLC, script hash, size, and builtin inventory.
- Reproduce Milestone A/B artifact locks without changes.
- Assert schema failure cannot affect ordinary `JulcCompiler.compile`.

### Schema conformance

- Records and sealed variants retain compiler constructor order and field order.
- `List<BigInteger>`, `Map<byte[], BigInteger>`, `Optional<byte[]>`, boolean,
  and nested combinations produce exact expected CIP-57 shapes.
- Library-defined datum/redeemer types use the compiler-resolved type rather
  than source-name guessing.
- Unknown types, definition-name collisions, unsupported arrays, and explicit
  multi-purpose limitations produce source-linked failures.
- Every successful blueprint validates offline against the pinned official
  meta-schema.

### Encoding conformance

For each supported shape, compile a validator whose entrypoint consumes the
type and compare the advertised schema with the existing compiler/VM encoding:

- booleans use constructor indexes 0/1;
- optionals use constructor indexes 0/1 and arities 1/0;
- list elements are encoded under `ListData`;
- map keys and values are encoded under `MapData`; and
- record/sum constructor indexes and positional fields match compiler codegen.

### CLI behavior

- Strict build writes a complete blueprint atomically.
- Strict schema failure publishes no partial output and preserves a complete
  last-good build, if one exists.
- `--no-blueprint` and its alias compile the same script bytes, emit raw
  artifact metadata, and cannot be consumed by `julc verify init`.
- Existing build, blueprint inspection, and generated verification tests pass.

## Exit criteria

Milestone C.1 is complete only when:

- `julc-blueprint` no longer reparses validator Java;
- supported compiler types produce complete, offline-validated CIP-57 schemas;
- unknown schema types fail closed unless the source explicitly uses
  `PlutusData`;
- ordinary and schema-aware compilation are byte-identical;
- the Milestone A/B artifact locks are unchanged;
- the no-blueprint escape hatch is tested and documented; and
- [`verification/README.md`](../../verification/README.md) accurately describes
  the new supported boundary and commands.

## Non-goals

- Generating Lean list/map/optional encoders and decoders; that is C.2.
- Productive recursive Java ADTs or Lean induction; that is C.3.
- Automatically authoring a contract-specific security property.
- Installing Lean, Z3, or Blaster through `julc verify init`.
- Implementing the future containerized `julc verify run` command.

## Implementation outcome

Milestone C.1 was implemented on 2026-08-12.

- `JulcCompiler.compileContract` now returns byte-identical ordinary compiler
  output plus immutable `ContractSchema` metadata from the live `TypeResolver`.
- All blueprint-producing integrations use that schema-aware API;
  `julc-blueprint` no longer parses Java source.
- CIP-57 conversion preserves nested list `items`, map `keys`/`values`,
  optional and boolean constructor encodings, named record fields, sealed
  variant tags, and compile-time parameters.
- Blueprint documents are validated offline against the official schema files
  pinned at CIPs commit
  `0ed8837a02ed78b64847e5646f9572ee1830c7ba`.
- Unsupported arrays, definition-name collisions, and the current explicit
  multi-purpose limitation fail closed with source-linked diagnostics.
- Spending-datum ledger optionals are removed only at the datum root; nested
  optionals retain their actual Data constructor encoding.
- Synthetic definition keys cannot collide with Java names, and per-validator
  namespaces allow conventional `Datum`/`Redeemer` names in aggregate
  multi-validator blueprints.
- CLI, Gradle, annotation-processor, and Playground users can all deliberately
  disable blueprint generation without disabling ordinary compilation.
- CLI strict builds publish raw artifacts and the blueprint only after all
  compilation and schema validation succeeds. Failure preserves the complete
  last-good build; a successful no-blueprint or no-validator build removes
  stale schema-dependent artifacts.
- Atomic blueprint publication follows the active process umask and preserves
  existing permissions. Native-image resource metadata is owned by the
  `julc-blueprint` library so every native consumer, including the CLI and
  Playground, includes the pinned `cip57/**` resources.
- The Gradle task validates all contract schemas before publishing individual
  validator JSON and cleans outputs belonging to removed validators after a
  successful build.

The tests include byte-identity checks between ordinary and schema-aware
compilation, top-level ledger-optional semantics, exact nested schema
assertions, an end-to-end compiler/VM encoding check, collision cases,
definition-body validation, frontend opt-out parity, annotation-processor
partial-build protection, CLI transactional publication, readable atomic file
permissions, official meta-schema validation, and the pre-existing Milestone
A/B artifact locks.

## Lessons for C.2

- CIP-57's builtin `#boolean` is not JuLC's outward Data representation; the
  Lean generator must consume the two-constructor schema emitted by C.1.
- Schema completeness and verification support are separate gates. A normal
  build can now publish container schemas even though `julc verify init` must
  continue rejecting them until C.2 adds strict Lean decoders.
- Keeping CIP-57 as the only portable Java-to-verifier boundary made the
  compiler, deployment artifact, and generated verification workspace agree
  without introducing a second Java-to-Lean code path.
- A raw-script escape hatch is safe only when it removes stale blueprint state
  and is visibly incompatible with schema-dependent tooling.
