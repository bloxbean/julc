# ADR-011: Milestone C.5 `@RequiresSigner` Vertical Slice

- **Status:** Implemented; awaiting manual review
- **Date:** 2026-08-13
- **Related:**
  [ADR-007 — Java-Annotation Security Properties](007-java-annotation-security-properties-and-one-command-verification.md),
  [ADR-009 — Verification Product Roadmap](009-verification-product-roadmap.md),
  [ADR-010 — Managed Verification Runner](010-milestone-c4-managed-verification-runner.md)

## Context

Milestone C.4 can generate and run a pinned Lean/Blaster workspace for exact
JuLC UPLC, but the contract-specific theorem is still written in Lean. C.5 must
deliver the first complete Java-only property: an annotated spending validator
whose datum owner is required to occur anywhere in the transaction signatory
list.

This is security-critical product surface. The annotation must not participate
in UPLC lowering, an invalid field path must not turn into untyped Lean text,
and success must mean that the named property was checked against the exact
built artifact. It must not be confused with compilation success or a claim
that the whole contract is safe.

The optional Docker backend from C.4 remains useful, but full Docker runtime
validation is explicitly deferred until after C.5. The local pinned backend is
the C.5 acceptance path; backend selection does not change theorem semantics.

### Implementation finding: strict schema versus current record projection

The first end-to-end authorized fixture was solver-refuted. Blaster produced a
context whose owner was present in `txInfo.signatories`, but whose attached
datum used an unexpected constructor tag and a trailing field. The exact JuLC
artifact succeeded because the current record projection extracts the expected
leading fields without checking the constructor tag or exact arity. The
generated schema decoder correctly rejected the same datum.

C.5 does not hide this discrepancy by weakening the property or assuming a
well-formed datum, and it does not change core compiler lowering. Its positive
fixture explicitly validates the raw attached datum's tag and arity before
checking the signer. A normal signer-only fixture is therefore a useful
refutation until JuLC separately adopts strict on-chain record decoding. Such a
compiler change would alter script bytes and requires its own compatibility and
regression decision; it is not smuggled into the annotation milestone.

## Decision

### Module boundary

Create a published `julc-verification` module containing:

- the Java property annotations;
- a javac annotation processor for immediate placement and syntax diagnostics;
- the versioned typed property IR; and
- the command-time source and `ContractSchema` resolver.

`julc-verification` may depend on `julc-compiler` to consume its observational
schema model. Neither `julc-core`, `julc-compiler`, `julc-stdlib`, nor UPLC
lowering may depend on `julc-verification`. The CLI is the composition root and
depends on both. This one-way dependency is the primary regression boundary:
the compiler parses the source exactly as it did before and ignores the
verification annotation when generating PIR and UPLC.

The javac processor performs fast source diagnostics, while `julc verify`
always repeats the authoritative resolution against the exact compiler-owned
`ContractSchema`. A javac processor success is not verification evidence.

### Initial Java interface

The only C.5 property is:

```java
import com.bloxbean.cardano.julc.verification.annotation.RequiresSigner;

@RequiresSigner("datum.owner")
@SpendingValidator
class AuthorizedStateValidator {
    record Datum(byte[] owner) {}
    // ...
}
```

`@RequiresSigner` is a source-level type annotation and is not repeatable in
C.5. It is valid only on a spending validator with the three-argument spending
entrypoint and an attached datum. Its value must be exactly `datum.<field>`.
Nested paths, redeemer paths, validator parameters, optional owners, multiple
authorities, and minting policies are rejected until their semantics are
specified by later milestones.

The selected field must resolve through `ContractSchema.datum`,
`PirType.RecordType`/`NamedTypeRef`, and `namedDefinitions` to a
`PirType.ByteStringType`. Java `byte[]` and compiler-supported nominal key-hash
types that resolve to that PIR type are wire-compatible. Java `String`, raw
`Data`, containers, optionals, and ambiguous or missing fields are rejected.

All failures name the validator, property path, and Java annotation source
location. Property strings are parsed into path segments and never copied into
Lean source.

### Typed property IR

The resolved property is serialized as canonical `verification-property.json`
with schema version 1. It records at least:

- property ID and kind `requires-signer`;
- exact validator title and spending purpose;
- original path and typed path segments;
- resolved datum and owner PIR/Lean types;
- annotation source location;
- template version `julc.requires-signer/v1`; and
- explicit domain and trust statements.

The IR file hash is included in the verification manifest and final result.
Generation consumes the typed IR only; it does not reparse the annotation.
The specialized manifest also locks the complete generated Lean source tree;
editing a generated predicate or obligation after generation fails before any
verification command executes.

### Exact Lean meaning

For a generated datum type `D` and selected byte-string field `owner`, the
property has this meaning:

```text
for every V3 ScriptContext ctx,
  if exact compiled UPLC succeeds for spendingInputs(ctx),
  then ctx is SpendingScript with Some datumData,
       strict IsData decoding of datumData as D succeeds,
       and txSignedBy(decoded.owner, ctx.txInfo) is true
```

The generated predicate is false for a non-spending `ScriptInfo`, absent datum,
or malformed datum. Datum presence and successful strict decoding are
therefore guaranteed by the implication; they are not assumptions.

The signatory check uses the pinned V3 ledger API's complete-list
`txSignedBy` predicate. It must not use `firstSignerAuthorized` or inspect only
the first signatory.

No ledger-validity predicate is assumed in C.5. The result and certificate
must state `ledgerValidityModeled: false`; a solver witness is a model witness,
not necessarily a ledger-admissible transaction.

The implication is also relative to the recorded CEK fuel. `SMT-VALID` covers
only executions completing within that bound; a path that exhausts fuel is
outside the claim. Non-vacuity prevents total under-fueling from becoming a
success, but cannot by itself detect a slower violating path beyond the bound.

### Obligations and classification

Each generated workspace contains two independent obligations:

1. **Non-vacuity:** the universal claim that no input succeeds must be
   refuted. If it is valid, verification stops with
   `COULD-NOT-EVALUATE/property-vacuous`.
2. **Required signer:** successful exact execution implies the generated
   signer predicate. A valid solver result is `SMT-VALID`; a solver model for
   the negation is `REFUTED` and its complete log is retained as the
   source-readable counterexample.

The runner protocol is extended in a backward-compatible versioned form so a
verification step can map authenticated exit-code/output-marker pairs to a
typed result. The execution plan cannot declare a result independently of the
observed marker. Unknown exits, absent markers, timeouts, tool failures, or
solver uncertainty fail closed.

The negative-control classification relies on the pinned Blaster command's
observable exit/marker contract for generated counterexamples. The tracked
vacuous and vulnerable fixtures are regression controls for both sides of that
contract; an upstream behavior change becomes a non-success result rather than
being reinterpreted as proof evidence.

Blaster's current `blaster` tactic closes valid results through its trusted
solver bridge, so established properties are reported as `SMT-VALID`, not
`KERNEL-PROVED`.

### One-command workflow

The supported command is:

```bash
julc verify --validator AuthorizedStateValidator
```

Optional arguments select the project, local/Docker backend, fuel, recursive
depth, output directory, and regeneration. The command:

1. performs an ordinary blueprint-enabled JuLC build;
2. selects exactly one Java validator and recompiles observational metadata;
3. resolves and writes the typed property IR;
4. verifies the observational compile has the same compiled-code hash as the
   blueprint entry;
5. generates the deterministic property workspace;
6. runs C.4's managed runner; and
7. reports the structured result and certificate path.

`verify init` remains the expert workflow for untemplated properties and
`verify run` remains the workspace runner. C.5 does not silently verify every
validator in a multi-validator project.

### Certificate and evidence

`verification-result.json` is the C.5 certificate. In addition to C.4 data it
must bind:

- compiled-code SHA-256 and Cardano script hash;
- canonical property-IR SHA-256 and template version;
- generated Lean tree SHA-256;
- validator, purpose, property path, and property ID;
- Lean, Blaster, PlutusCore, Cardano ledger API, and Z3 pins;
- protocol version, Plutus version, semantics variant, and fuel;
- the absence of ledger-validity modeling;
- non-vacuity and property results; and
- hashed logs containing any counterexample.

Tracked C.5 evidence includes an authorized fixture, a vulnerable fixture that
accepts without the datum owner, and an always-failing fixture. The vulnerable
witness must visibly contain a decoded owner and a signatory list that does not
contain that owner, or the report must conservatively label it as the raw
Blaster model rather than claim a successful translation.

Generated Lean is scanned for `sorry`, `admit`, project `axiom`, `unsafe`, and
`partial` declarations before execution.

## Implementation evidence

The tracked `verification/c5` suite drives the public shaded CLI through three
real Java projects and the pinned local Lean/Blaster/Z3 toolchain:

- `AuthorizedStateValidator` is `SMT-VALID` with a successful-input witness;
- `VulnerableStateValidator` is `REFUTED` and retains the complete raw Blaster
  model; and
- `VacuousStateValidator` is `COULD-NOT-EVALUATE/property-vacuous`.

Fresh regression runs pass the verification-module, compiler, blueprint, and
CLI suites. C.2 and C.3 regenerate and kernel-check successfully through their
existing evidence drivers. Tests also establish byte-identical UPLC with and
without the annotation, source-local path/type diagnostics, exact annotation
identity, deterministic generation, property-IR and Lean-source tamper
rejection, authenticated dynamic result classification, and native-image
metadata reachability.

The end-to-end command is:

```bash
verification/c5/scripts/verify.sh
```

The local backend is the acceptance backend. Full Docker-runtime validation
remains the explicitly agreed post-C.5 task.

## Rejected alternatives

- **Teach the core compiler the annotation.** This enlarges the trusted and
  regression-sensitive compiler path without changing on-chain semantics.
- **Resolve paths from CIP-57 alone.** CIP-57 is an output format and loses the
  Java source location needed for authoritative diagnostics.
- **Insert annotation strings into Lean.** This is both untyped and an
  injection risk.
- **Treat absent/malformed datum as an assumption.** That weakens the promised
  authorization property and can hide an accepting malformed-datum path.
- **Check only the first signer.** This is not Cardano required-signer
  membership and rejects valid ordering while missing the intended API.
- **Call every successful Lean build verified.** Compilation does not establish
  the generated theorem.

## Acceptance criteria

C.5 is complete only when:

- annotation and processor code reside outside the compiler/core modules;
- adding/removing the annotation produces byte-identical UPLC;
- valid and invalid paths have source-local Java tests;
- canonical IR and deterministic Lean generation are tested;
- the authorized fixture is `SMT-VALID` through `julc verify`;
- the vulnerable fixture is `REFUTED` with retained model evidence;
- the always-failing fixture is identified as vacuous;
- exact artifact/property hashes and assumptions appear in the certificate;
- admission and tampering checks fail closed; and
- compiler, blueprint, CLI, and verification-module regression suites pass.

No C.5 result may be presented as “the contract is safe.” The permitted claim
is that the exact named validator satisfies `julc.requires-signer/v1` under the
recorded execution bounds, dependency pins, trust model, and absence of ledger
validity modeling.
