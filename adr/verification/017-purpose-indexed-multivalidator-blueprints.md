# ADR-017: Purpose-Indexed CIP-57 Blueprints for `@MultiValidator`

- **Status:** Implemented (pending manual review and commit)
- **Date:** 2026-08-13
- **Parent:**
  [ADR-005 — Compiler-Owned Blueprint Schemas](005-milestone-c1-compiler-owned-blueprint-schema.md)
- **Related:**
  [ADR-015 — Strict On-Chain Data Boundaries](015-strict-on-chain-data-boundaries.md),
  ADR-016 — Typed Verified DSL (maintained on `feat/typed_verified_dsl`)
- **Scope:** Generate truthful, standard CIP-57 interfaces for explicit
  purpose-indexed `@MultiValidator` entrypoints without changing compiled UPLC.

## Context

JuLC can compile one `@MultiValidator` script with explicit handlers such as:

```java
@MultiValidator
public class ProtocolValidator {
    @Entrypoint(purpose = Purpose.SPEND)
    public static boolean spend(State datum, Spend redeemer, ScriptContext ctx) {
        // ...
    }

    @Entrypoint(purpose = Purpose.MINT)
    public static boolean mint(Mint redeemer, ScriptContext ctx) {
        // ...
    }
}
```

Both handlers are dispatched by one compiled UPLC program and therefore share
one script hash. Their on-chain interfaces are nevertheless different:

- spending has a `State` datum and a `Spend` redeemer;
- minting has no datum and has a `Mint` redeemer; and
- future purposes may have still other redeemer schemas.

The compiler currently represents `ContractSchema` as one purpose, one optional
datum root, and one redeemer root. Schema-aware compilation therefore rejects
both explicit purpose-indexed and manual-dispatch `@MultiValidator` classes.
Every blueprint-enabled frontend inherits that rejection. Ordinary compilation
still works through the explicit no-blueprint option.

This surfaced while validating `julc-examples`: the Gradle annotation processor
could compile the script with blueprint generation disabled, but could not
publish a truthful `plutus.json` for the same source.

This is not a strict-boundary defect. It is a compiler interface-metadata and
CIP-57 serialization limitation that also affects wallets, transaction builders,
documentation generators, and verification tooling.

## Standards constraints

The [CIP-57 specification](https://cips.cardano.org/cip/CIP-57) says that
purposes discriminate datum/redeemer schemas and describes an argument-level
`oneOf` form for different purpose-specific interfaces. It currently names
`spend`, `mint`, `withdraw`, and `publish`.

JuLC pins the official meta-schemas at CIPs revision
`0ed8837a02ed78b64847e5646f9572ee1830c7ba`. At that revision:

- an argument object can carry one purpose or `purpose.oneOf`;
- its schema can carry `schema.oneOf`; but
- the meta-schema does not accept the prose's top-level `oneOf` of complete
  argument objects.

Using independent purpose and schema alternatives would lose their correlation:
consumers could not know that `Spend` belongs only to `spend` and `Mint` only to
`mint`. JuLC must not publish that ambiguous cross-product.

The pinned CIP vocabulary has no names for the Plutus V3 `VOTE` or `PROPOSE`
purposes. Its `publish` purpose does correspond to certificate validation:
Aiken names its certificate handler `publish` and maps ledger
`RedeemerTag::Cert` to `Publish`, while lowering that handler to Plutus V3
`ScriptInfo.Certifying`. JuLC therefore uses the same established
`CERTIFY`-to-`publish` mapping. Aiken's generated blueprints currently omit the
optional purpose field, so they do not provide a standard mapping for `VOTE`
or `PROPOSE` that JuLC can adopt without losing purpose/schema correlation.
Scalus independently keeps the same boundary: its Plutus V3 ledger model has
`Certifying`, `Voting`, and `Proposing`, while its separate CIP-57 blueprint
`Purpose` enum contains only `Spend`, `Mint`, `Withdraw`, and `Publish`.
Scalus's generic blueprint builders currently leave derived argument purposes
unset; they do not define additional CIP-57 values for voting or proposing.

## Decision

### 1. Make the compiler-owned schema purpose-indexed

Evolve the observational compiler schema to represent a list of on-chain
interfaces:

```text
ContractSchema
├── interfaces[]
│   ├── entrypoint name
│   ├── strongly typed JuLC purpose
│   ├── optional datum root
│   ├── redeemer root
│   └── source location
├── compile-time parameters[]
└── named definitions
```

A normal validator contributes one interface. An explicit auto-dispatch
`@MultiValidator` contributes one interface for every `@Entrypoint`, ordered
deterministically by the compiler's ledger-purpose tag and then source order.

The compiler resolves each interface from the same registered `PirType` graph
used by strict boundary lowering. Spending's ledger-level top `Optional<T>` is
unwrapped only at that spending datum root, exactly as in ADR-005. Other
purposes have no datum root.

Schema capture remains observational. Enabling it must not feed metadata into
PIR/UPLC generation or change dispatch. Ordinary and schema-aware compilation
must remain byte-identical.

### 2. Publish one standard validator entry per supported purpose

For the pinned meta-schema, an explicit multi-purpose class is serialized as
one CIP-57 validator entry per supported purpose:

```text
ProtocolValidator.spend  ─┐
ProtocolValidator.mint   ─┼─ same compiledCode, same script hash
ProtocolValidator.withdraw┘
```

Each entry contains only its own correlated datum/redeemer schemas and carries
an explicit standard purpose on every runtime argument. Compile-time parameters
are repeated because they apply the same script before purpose dispatch.

The deterministic title is `<validator-title>.<cip57-purpose>`. A collision
with another emitted title is a build error. Definitions remain namespaced by
the base validator title and are shared when structurally identical.

This representation:

- validates with the already pinned official meta-schema;
- uses only standard CIP-57 fields;
- gives simple consumers one unambiguous interface per entry;
- preserves one exact deployable artifact through identical code and hash; and
- avoids inventing a JuLC-only extension.

The first implementation supports the exact mappings:

| JuLC purpose | CIP-57 purpose | Support |
|---|---|---|
| `SPEND` | `spend` | yes |
| `MINT` | `mint` | yes |
| `WITHDRAW` | `withdraw` | yes |
| `CERTIFY` | `publish` | yes |
| `VOTE` | none in pinned vocabulary | fail closed |
| `PROPOSE` | none in pinned vocabulary | fail closed |

The `publish` spelling is an artifact-format mapping only. JuLC's Java and
compiler-owned purpose remains `CERTIFY`.

If a later CIP-57 revision makes a single-entry discriminated argument form
both valid and interoperable, changing the publication shape requires a
separate compatibility decision. It is not silently introduced in this work.

### 3. Keep manual dispatch fail-closed

A single default `@Entrypoint(Redeemer, ScriptContext)` manually inspecting
`ScriptInfo` does not expose enough compiler-owned information to associate
each purpose with its datum/redeemer types. JuLC will continue rejecting
blueprint generation for that form and will keep the explicit no-blueprint
build escape hatch.

The compiler must not infer purpose schemas from arbitrary method bodies or
advertise one shared opaque interface by accident. A future explicit interface
declaration for manual dispatch would require its own design.

### 4. Make consumers select an interface explicitly

Build frontends publish the complete blueprint atomically only after all
purpose interfaces and definitions validate.

`julc verify init` and higher-level verification workflows accept the base
validator title plus a purpose, for example:

```text
julc verify init . --validator ProtocolValidator --purpose spending
```

The resolver selects exactly `ProtocolValidator.spend`, verifies that the
entry's declared purpose matches the requested purpose, and binds to that
entry's exact `compiledCode` and hash. Zero or multiple matches fail closed.
The verification manifest records both the base validator identity and the
selected blueprint entry title.

Commands that inspect raw blueprint entries may also accept the full title.
No consumer selects the first entry by array order or guesses purpose from the
presence of a datum.

### 5. Preserve frontend and artifact behavior

The CLI, Gradle plugin, annotation processor, and Playground use the same
schema-aware compiler result and serializer. They do not implement separate
purpose mapping logic.

Publication retains ADR-005's transactionality:

- one unsupported interface fails the whole strict blueprint build;
- no partial purpose subset is published;
- a failed build preserves the complete last-good blueprint;
- `--no-blueprint`/the equivalent frontend option remains available; and
- successful ordinary and blueprint builds produce identical script bytes.

## Milestones

### P.1 — Compiler interface model and standards fixture

- Introduce a strongly typed schema purpose rather than passing arbitrary
  strings through `ContractSchema`.
- Represent all explicit auto-dispatch entrypoints and their source locations.
- Resolve distinct datum/redeemer roots from the live compiler type graph.
- Add a minimal `SPEND` + `MINT` fixture and commit the proposed CIP-57 shape.
- Validate that shape against the pinned offline meta-schema and at least one
  real Java/Cardano blueprint consumer.
- Lock ordinary versus schema-aware UPLC byte identity.

P.1 is a decision gate. If the repeated-entry shape proves incompatible with a
major consumer, stop and revise this ADR; do not fall back to ambiguous JSON.

### P.2 — CIP-57 serializer and validation

- Add purpose-aware argument and validator-entry models.
- Emit deterministic purpose-suffixed entries with shared code/hash.
- Merge definitions without collisions across interfaces and validators.
- Extend body validation to check purpose values, uniqueness, and the
  datum-only-for-spending invariant.
- Fail at the relevant `@Entrypoint` source location for an unsupported purpose
  or schema.
- Add positive `SPEND`/`MINT`/`WITHDRAW`/`CERTIFY` and negative
  `VOTE`/`PROPOSE` tests.

### P.3 — Build and verification integrations

- Enable blueprint generation for explicit supported-purpose
  `@MultiValidator` contracts through CLI, Gradle, annotation processing, and
  Playground.
- Make annotation-processor aggregation all-or-nothing after compilation
  errors.
- Teach artifact inspection and `julc verify init` to resolve base title plus
  purpose without ambiguity.
- Bind generated Lean workspaces and certificates to the selected interface
  and the shared exact artifact.
- Keep manual dispatch and unsupported purposes fail-closed with actionable
  diagnostics and the documented no-blueprint option.

### P.4 — Ecosystem evidence and migration

- Remove temporary `blueprint = false` workarounds from supported
  `julc-examples` projects.
- Build and test a real mixed spending/minting example through its normal
  Gradle and annotation-processor path.
- Parse the emitted blueprint with cardano-client-lib and exercise off-chain
  datum/redeemer construction for both purposes.
- Generate separate spending and minting verification workspaces from the same
  script and prove their artifact hashes are identical.
- Document title conventions, supported purposes, manual-dispatch limitations,
  and consumer selection.

## Required tests

### Compiler

- single-purpose `ContractSchema` behavior remains compatible;
- explicit `SPEND` + `MINT` produces two correctly typed interfaces;
- same Java type name with different purpose-local shapes remains distinct;
- spending datum optional unwrapping occurs only on the spending root;
- entrypoint ordering is deterministic;
- an unsupported purpose reports its exact source location; and
- ordinary/schema-aware compile results are byte-identical.

### Blueprint

- every generated document passes the pinned official meta-schema and JuLC's
  strict body validator;
- purpose-suffixed entries have byte-identical `compiledCode` and hash;
- datum appears only in the spending entry;
- each redeemer has the correct purpose and `$ref` graph;
- parameter schemas are identical and correctly purpose-qualified;
- definition and title collisions fail closed; and
- no partial blueprint is produced when one interface fails.

### Frontends and verification

- CLI, Gradle, annotation processor, and Playground produce equivalent JSON;
- base-title plus purpose selection is exact and rejects absent/ambiguous
  matches;
- full-title inspection remains deterministic;
- verification manifests record both identities and exact artifact hashes;
- spending and minting workspaces generated from one multi-validator compile;
- stale artifacts are not left after switching between blueprint and
  no-blueprint builds; and
- existing single-purpose verification evidence remains unchanged except for
  intentional metadata additions.

### Runtime evidence

- testkit evaluates every auto-dispatch branch using correctly encoded inputs;
- malformed input for each selected branch is rejected by strict boundaries;
- a purpose tag cannot reach another purpose's handler; and
- the shared program/hash asserted in the blueprint is the program evaluated by
  every runtime test.

## Consequences

### Positive

- Explicit `@MultiValidator` contracts can use ordinary blueprint-enabled
  builds instead of disabling artifact metadata.
- Wallets and off-chain Java code receive an unambiguous schema per purpose.
- Verification can select the correct interface while remaining bound to the
  same deployed UPLC.
- The compiler, blueprint, strict boundary guard, and generated Lean codec all
  continue deriving types from one `PirType` graph.

### Costs and limitations

- A single script appears as several blueprint validator entries. Consumers may
  display those entries separately even though their hashes match.
- Manual-dispatch multi-validators remain unsupported for blueprint generation.
- Plutus V3 voting and proposing interfaces are unavailable in strict
  blueprints until CIP-57 provides truthful vocabulary or a separately reviewed
  interoperability strategy is adopted. This is a preview behavior change for
  single-purpose validators: older JuLC versions emitted incomplete
  purpose-free metadata for those forms. Compilation remains available through
  the explicit blueprint opt-out.
- Purpose-qualified entry titles become a public artifact convention and must
  be migration-tested before release.

## Rejected alternatives

### Select the first `@Entrypoint`

Rejected. Source or reflection order is not an interface contract and would
silently publish the wrong schema for other purposes.

### Emit `purpose.oneOf` and `schema.oneOf` independently

Rejected. It loses the association between a purpose and its schema and permits
consumers to interpret a false cross-product.

### Emit opaque `PlutusData` for the combined redeemer

Rejected. It discards information the compiler already owns, weakens off-chain
tooling, and prevents generated strict Lean codecs.

### Add a JuLC-only JSON extension immediately

Rejected. The supported purposes can be represented with standard CIP-57
validator entries. Extensions would reduce interoperability and require every
consumer to understand JuLC conventions.

### Infer manual-dispatch schemas from method bodies

Rejected. General control-flow analysis would be fragile and could make a
plausible but false interface claim. Manual dispatch stays explicit and
fail-closed.

## Exit criteria

ADR-017 is complete only when:

- supported explicit `@MultiValidator` contracts build with blueprints enabled
  through every frontend;
- the blueprint truthfully associates each supported purpose with its exact
  datum/redeemer schema;
- all purpose entries bind to one byte-identical compiled program and hash;
- official offline validation and at least one real consumer accept the shape;
- `julc verify init` selects and records an exact purpose interface;
- unsupported and manual forms fail closed without partial artifacts;
- the normal `julc-examples` build no longer needs the temporary blueprint
  opt-out for supported multi-purpose contracts; and
- no core compiler or single-validator UPLC regression is introduced.

## Implementation notes and lessons

- The repeated-entry fixture passes the repository-pinned official CIP-57
  meta-schema. A separate compatibility test feeds real `BlueprintGenerator`
  output—including colon-namespaced definition references—into
  cardano-client-lib's concrete `PlutusBlueprintLoader` from
  `cardano-client-plutus:0.8.0-pre2`. JuLC's runtime dependency remains
  unchanged; this newer consumer is test-only.
- The compiler schema retains a `purposeIndexed` marker. Counting interfaces is
  insufficient because a `@MultiValidator` with one explicit entrypoint must
  still publish `Name.spend` rather than masquerading as a normal validator.
- Purpose-local named types use stable compiler identities only when simple
  names collide. Existing single-validator definition keys therefore remain
  compatible.
- cardano-client-lib 0.7.2 supplies JuLC's deployed-script and Plutus-data
  bridge but predates its blueprint model. Compatibility evidence consequently
  uses the first published CCL line with a real loader, while separate adapter
  tests exercise off-chain datum/redeemer construction.
- The external `julc-examples` checkout contains no `blueprint = false`
  workaround to remove. Its mixed-purpose validators use `CERTIFY`, which is
  now published as standard CIP-57 `publish`. Supported mixed-purpose
  publication is also covered through the normal Gradle plugin and
  annotation-processor paths.
- Aiken v1.1.21 was checked both at source level and by building its Plutus V3
  script-context acceptance project. It emits `.publish`, `.vote`, and
  `.propose` handler titles but no argument `purpose` fields. JuLC adopts its
  explicit ledger `Cert` to `Publish` mapping; it does not copy the
  purpose-omission fallback for voting or proposing.
- Scalus commit `68449438e409f7e666315369e462d24273c1e7e6` was checked at
  source level. Its on-chain Plutus V3 model includes certifying, voting, and
  proposing, but its CIP-57 `Purpose` codec deliberately exposes only the four
  standard values. This independently corroborates failing closed for
  `VOTE`/`PROPOSE`; Scalus does not supply an extension vocabulary JuLC could
  adopt.
- Purpose resolution diagnoses a matching pre-ADR-017 entry with no redeemer
  purpose as a stale blueprint and asks the user to rebuild, rather than
  incorrectly claiming that the requested interface does not exist.
