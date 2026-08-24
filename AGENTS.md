# AGENTS.md — JuLC

## Mission

JuLC is an experimental Java-subset-to-Plutus-V3-UPLC compiler and toolchain for Cardano.

When working in this repository, optimize for:
1. semantic correctness
2. explicit compiler invariants
3. deterministic generated behavior
4. strong tests
5. maintainable Java APIs and compiler architecture
6. developer ergonomics only after correctness

Do not treat "compiles" or "tests pass" as sufficient evidence that a compiler transformation is correct.

## Project Status

This is an experimental/research project. AI-assisted implementation is expected, but changes require human-assisted design, testing, and verification.

Do not represent experimental behavior as production-safe unless repository documentation explicitly changes that status.

## Read Before Significant Work

Before substantial changes, inspect:
- `README.md`
- `CONTRIBUTING.md`
- `SECURITY.md` where relevant
- the relevant ADR/design docs
- the relevant module README/docs
- nearby tests
- the current `CLAUDE.md` when using Claude Code

For ADR-governed work, read the entire ADR before editing.

## Architecture

Key modules currently include:

- `julc-core` — UPLC AST and CBOR/FLAT serialization
- `julc-vm` — VM SPI
- `julc-vm-scalus` — Scalus-backed evaluation
- `julc-ledger-api` — typed ledger/ScriptContext model
- `julc-compiler` — Java source to UPLC compiler
- `julc-stdlib` — on-chain standard library
- `julc-testkit` — validator testing
- `julc-cardano-client-lib` — Cardano Client Lib integration
- `julc-gradle-plugin` — Gradle integration
- `julc-annotation-processor` — validator compile-time generation
- `julc-verification` — typed security-property annotations/processors

Do not assume this list is exhaustive. Verify `settings.gradle` and repository structure before cross-module changes.

## Core Compiler Invariants

Treat these as defaults unless an accepted ADR explicitly changes them.

### Semantic preservation
A supported JuLC source construct must compile to UPLC with the behavior defined by JuLC's source-language semantics.

Do not change:
- evaluation order
- branch behavior
- data representation
- failure behavior
- equality/comparison semantics
- collection semantics
- validator argument interpretation

without explicitly documenting and testing the change.

### Safe Java subset
JuLC intentionally supports a subset of Java.

Do not "fix" unsupported Java by silently accepting it. A new Java construct requires:
- defined semantics
- explicit lowering
- validation
- diagnostics
- tests
- usually an ADR when non-trivial

### Typed boundaries
Datum/redeemer/ledger types and generated boundaries are correctness-critical.

Be especially careful around:
- canonical constructors/tags
- arity and field ordering
- lists/maps
- records and sealed interfaces
- `@NewType`
- `@Param`
- datum/redeemer conversion
- ScriptContext access

### Determinism
Compilation and generated artifacts should be deterministic for the same inputs/configuration.

### On-chain consequences
When a change affects generated UPLC:
- consider script size
- consider execution cost
- avoid unnecessary duplication
- verify behavior through the VM/testkit when practical

## Coding Rules

- Follow existing Java style and local patterns.
- Prefer clear explicit compiler transformations over clever shortcuts.
- Reuse existing AST/type/lowering abstractions.
- Avoid parallel representations for the same concept.
- Do not introduce generic abstractions without at least two concrete needs.
- Keep public API changes deliberate and documented.
- Preserve backward compatibility unless the task/ADR explicitly permits a break.
- Do not silently swallow compiler errors; diagnostics are part of the developer experience.
- Prefer actionable diagnostics that identify the unsupported construct and remediation.

## Change Discipline

For significant work:

1. identify the governing ADR, or create one
2. list affected modules and compiler stages
3. state invariants before editing
4. implement one coherent milestone
5. run focused tests
6. run affected-module tests
7. run cross-module/integration tests where necessary
8. review the diff against the ADR
9. document any divergence before continuing

If repository reality contradicts the ADR, stop that part and propose an ADR update. Do not silently redesign.

## Testing

Every behavioral change should include tests.

Use as applicable:
- parser/frontend/compiler unit tests
- type/validation error tests
- lowering tests
- generated UPLC evaluation via supported VM
- stdlib tests
- annotation processor compile tests
- testkit tests
- regression tests
- example-project tests
- Yaci DevKit integration tests for material on-chain behavior

### Test quality

Include:
- happy path
- boundary values
- invalid inputs
- unsupported constructs
- nested/composed constructs
- previously failing regressions

Avoid tests that simply restate implementation details if a semantic assertion is possible.

## Build and Validation

Use the repository's Gradle wrapper.

Start with the narrowest useful command, for example:

```bash
./gradlew :<module>:test
```

Then widen validation based on impact.

For repository-wide changes:

```bash
./gradlew build
```

Inspect `settings.gradle`, build scripts, and CI before assuming all optional/integration tasks are part of the default build.

## Yaci DevKit

For integration/on-chain testing, Yaci DevKit is started externally by the developer.

When an existing local DevKit is available, its admin API may be used for operations such as reset/top-up according to repository test guidance.

Do not start, stop, reset, or mutate an external devnet unless the task requires it and the developer has made that environment available.

## External Examples

Related examples may exist in sibling repositories such as `julc-examples` and `julc-helloworld`.

When a public behavior changes, check whether examples or documentation must be updated.

## ADR Guidance

Create/update an ADR for:
- language-semantic changes
- new compiler stages or IR concepts
- cross-module architectural changes
- public API conventions with lasting impact
- substantial lowering strategy changes
- formal-verification architecture
- major performance architecture
- breaking changes

An ADR must include:
- context/problem
- goals/non-goals
- current behavior
- explicit invariants
- decision
- alternatives
- affected stages/modules
- compatibility
- risks
- implementation milestones
- verification strategy
- open questions

Rejected alternatives matter: record why they were rejected so future agents do not unknowingly reintroduce them.

## Review Checklist

Before declaring completion ask:

- What observable semantics changed?
- Which compiler stage owns this behavior?
- Could evaluation order have changed?
- Could datum/redeemer/ledger encoding have changed?
- Is generated UPLC behavior tested?
- Are failure paths tested?
- Did public APIs change?
- Are old contracts/scripts affected?
- Are examples/docs affected?
- Does the implementation still match the ADR?
