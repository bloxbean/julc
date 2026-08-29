# ADR-031: PV11-First Compiler Target and Future Protocol Evolution

**Date:** 2026-08-29

**Status:** Proposed (design only; implementation requires a separate reviewed PR)

**Related issue:** [#76 — Add an explicit V3/PV11 compiler target and diagnostics](https://github.com/bloxbean/julc/issues/76)

**Related roadmap:** [ADR-029 — PV11 ledger readiness and optimization roadmap](029-pv11-ledger-readiness-and-optimization-roadmap.md)

**Evaluator foundation:** [ADR-030 — Protocol version propagation and PV11 builtin semantics](030-protocol-version-propagation-and-pv11-builtin-semantics.md)

**Normative initial baseline:** cardano-node 11.0.1 / Plutus 1.63.0.0 at
[`f92b7d7d82622a26caf456a6be33859f697e2cfc`](https://github.com/IntersectMBO/plutus/tree/f92b7d7d82622a26caf456a6be33859f697e2cfc)

---

## Context

JuLC currently compiles one effective ledger profile: Plutus V3, protocol
version 11, UPLC 1.1.0. That profile is encoded as implementation assumptions
rather than represented as a first-class compiler value:

- `CompilerOptions` has no language, protocol, or UPLC target;
- `UplcGenerator` accepts builtins through tag 100 and rejects later tags with
  a fixed `LAST_RELEASED_PV11_BUILTIN_TAG` boundary;
- final programs are always constructed as Plutus V3 / UPLC 1.1.0;
- `CompileResult` does not retain the ledger target that justified the output;
- the CLI, Gradle plugin, and annotation processor do not report a resolved
  target;
- optimizer output is not checked against the protocol feature registry.

The fixed checks produce legal output for the current V3/PV11 contract, but
they are not a durable compiler invariant. Adding a later protocol version by
changing the tag ceiling would allow compiler stages, stdlib mappings, and
optimizers to disagree about which features are legal.

ADR-030 already established a canonical evaluator-side model:

```text
LedgerEvaluationTarget
    -> ProtocolFeatureRegistry
    -> ProtocolFeatureProfile
         - available builtins
         - available UPLC versions
         - case-on-builtin availability
         - semantics variant
         - decode limits
         - cost-model schema
```

The compiler must consume that source of truth rather than create an
independent protocol table.

## Problem

A successful compilation must establish all of the following:

1. the requested ledger language is supported for compilation;
2. the requested protocol version is a pinned, known compiler profile;
3. the selected UPLC version is legal for that ledger target;
4. every emitted builtin and term form is legal for that target;
5. every optimizer rewrite remains legal for that target;
6. downstream tooling can discover and reuse the resolved target;
7. an unknown future version fails closed instead of inheriting the newest
   behavior known to this build of JuLC.

An evaluator knowing how to execute a profile does not imply that the compiler
knows how to generate it. Evaluation and compilation have different support
matrices. For example, JuLC can evaluate V1/V2 profiles without promising V1/V2
source compilation or alternate lowerings.

## Goals

1. Make the initial compiler profile explicitly V3/PV11/UPLC 1.1.0.
2. Preserve existing source-level compile entry points with a documented PV11
   default.
3. Use the canonical `ProtocolFeatureRegistry` for feature legality.
4. Reject all unsupported targets before source lowering begins.
5. Carry one immutable resolved target through every stage that can select or
   introduce UPLC features.
6. Validate the final `Program`, including optimized output, against the same
   target.
7. Record target provenance without changing UPLC bytes or script hashes.
8. Define an explicit, repeatable process for supporting the next protocol
   version and later ledger languages.

## Non-goals

- Implementing PV10 fallbacks.
- Compiling Plutus V1 or V2 validators.
- Treating evaluator support as compiler support.
- Enabling a future builtin merely because its enum tag or runtime
  implementation exists.
- Selecting builtin semantics independently in the compiler; the evaluator
  profile remains authoritative.
- Implementing Phase 5 optimizations. Their target contract is defined here,
  while optimization decisions are defined by ADR-032.
- Changing serialized UPLC solely to embed provenance.

## Compiler invariants

The implementation must preserve these invariants:

1. **One target per compilation.** A compilation resolves exactly one ledger
   language, protocol version, and UPLC version.
2. **Resolve once.** The requested target is validated once at the compiler
   boundary and passed as an immutable value. Stages do not reconstruct it.
3. **Separate support from legality.** The protocol registry answers whether a
   feature is ledger-legal; a compiler support policy answers whether JuLC has
   implemented all required lowerings for that target.
4. **No implicit future aliases.** PV12 or later never behaves as PV11 unless a
   reviewed profile explicitly says so. Unknown targets fail closed.
5. **One feature registry.** Compiler, VM, stdlib feature metadata, and final
   validation do not maintain competing builtin-availability tables.
6. **Optimizer containment.** An optimizer may remove features or introduce
   target-authorized features only. Its output is validated again.
7. **Deterministic output.** The same source, compiler version, target, options,
   and bundled libraries produce the same program bytes.
8. **Provenance is out-of-band.** Recording the target in results or artifact
   metadata does not alter UPLC encoding.
9. **No silent fallback.** An unsupported requested target reports an
   actionable error; it never compiles using the default target.
10. **Evaluation handoff is exact.** Downstream evaluation uses the
    `LedgerEvaluationTarget` represented by the compiler target.

## Decision

### D1. Introduce an immutable `CompilerTarget`

Add a public compiler value with the conceptual shape:

```java
public record CompilerTarget(
        LedgerEvaluationTarget ledgerTarget,
        UplcVersion uplcVersion) {

    public static final CompilerTarget PLUTUS_V3_PV11 =
            new CompilerTarget(
                    LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3),
                    UplcVersion.V1_1_0);

    public String profileId() {
        return "plutus-v3-pv11-uplc-1.1.0";
    }
}
```

The final naming may follow local Java conventions, but the value must contain
the complete triple. A protocol number or UPLC version alone is not a compiler
target.

`profileId()` is stable, lower-case metadata for logs, build output, test
fixtures, and future artifact sidecars. It is not serialized into the UPLC
program.

### D2. Reuse the VM protocol contract layer

`julc-vm` is a pure-Java SPI and protocol-contract module; it has no dependency
on the Java, Truffle, or Scalus backends. `julc-compiler` will add an API
dependency on `julc-vm` and reuse:

- `PlutusLanguage`;
- `ProtocolVersion`;
- `UplcVersion`;
- `LedgerEvaluationTarget`;
- `ProtocolFeatureProfile`;
- `ProtocolFeatureRegistry`.

This avoids parallel compiler copies of protocol data. A new shared module is
not justified for the initial change. If the protocol contract later gains
additional non-VM consumers or `julc-vm` layering becomes a concrete problem,
extracting these existing types intact can be proposed separately.

### D3. Add a compiler support policy distinct from the feature registry

The protocol registry contains every profile the VM can evaluate. The compiler
needs a narrower table of profiles it can generate.

Conceptually:

```java
public final class CompilerTargetRegistry {
    private static final Set<CompilerTarget> SUPPORTED =
            Set.of(CompilerTarget.PLUTUS_V3_PV11);

    public static ResolvedCompilerTarget resolve(CompilerTarget requested) {
        // 1. exact compiler-support lookup
        // 2. ProtocolFeatureRegistry.resolve(requested.ledgerTarget())
        // 3. verify requested.uplcVersion() is available
        // 4. return immutable target + feature profile
    }
}
```

`ResolvedCompilerTarget` is an internal compiler value containing the public
target and its canonical `ProtocolFeatureProfile`. Compiler stages receive the
resolved value, not loose booleans or version numbers.

The initial matrix is intentionally narrow:

| Requested target | Compiler result |
|---|---|
| V3 / PV11 / UPLC 1.1.0 | Supported |
| V3 / PV11 / UPLC 1.0.0 | Rejected: compiler lowering targets UPLC 1.1.0 |
| V3 / PV10 / any UPLC | Rejected: no PV10 compiler lowerings |
| V1 or V2 / any PV | Rejected: no V1/V2 compiler output |
| PV12 or later | Rejected until a pinned compiler profile is added |
| PV11 with `MultiIndexArray` | Rejected: feature is not released for PV11 |

### D4. Default existing entry points to the named PV11 target

`CompilerOptions` gains:

```java
private CompilerTarget target = CompilerTarget.PLUTUS_V3_PV11;

public CompilerOptions setTarget(CompilerTarget target) { ... }
public CompilerTarget getTarget() { ... }
```

Existing source-compatible entry points continue to compile against
`PLUTUS_V3_PV11`. The default is documented as a pinned target, not “current,”
“latest,” or “mainnet.”

Passing an unsupported explicit target fails before parsing or library
discovery performs target-sensitive work. Explicit input is never replaced by
the default.

### D5. Carry a compilation context through target-sensitive stages

Create an internal immutable `CompilationContext` containing at least:

```text
ResolvedCompilerTarget
CompilerOptions snapshot
diagnostic sink
```

It is created per compilation and passed explicitly. Target information must
reach:

```text
JulcCompiler orchestration
    -> subset/type validation where feature-bearing APIs are recognized
    -> PirGenerator
    -> TypeMethodRegistry and StdlibRegistry lowering
    -> UplcGenerator
    -> UplcOptimizer
    -> final Program construction
    -> UplcTargetValidator
    -> CompileResult
```

No static mutable “current target” or thread-local target is allowed.

Parsing itself is target-independent, but target resolution occurs before
parsing so invalid configurations fail deterministically and cheaply.

### D6. Replace tag ceilings with capability checks

Every `PirTerm.Builtin` lowered to UPLC is checked with:

```java
resolvedTarget.featureProfile().isBuiltinAvailable(fun)
```

The current tag-100 ceiling is removed once all emission paths use the
capability check. `MultiIndexArray` remains in the AST/runtime for future work,
but the PV11 profile rejects it because it is absent from the canonical
available-builtin set.

Term-form validation also checks:

- the program UPLC version;
- `Constr`/`Case` availability;
- case-on-builtin use when the compiler emits that form;
- future constant universes or term constructors added to the compiler.

Raw checks such as `protocolMajor >= 11` are not permitted in compiler passes.
Passes ask the resolved profile about named capabilities.

### D7. Attach requirements to feature-bearing lowerings

Stdlib and type-method mappings that can emit protocol-gated features declare
requirements in metadata rather than relying only on late failure. The
conceptual form is:

```java
record LoweringRequirements(
        Set<DefaultFun> builtins,
        Set<CompilerFeature> features) {}
```

This metadata provides early, source-located diagnostics. Final validation is
still mandatory because direct PIR, public `Builtins` calls, and optimizer
rewrites can bypass a high-level mapping.

The initial metadata covers at least:

- `DropList`;
- base Array operations;
- BLS MSM;
- `ExpModInteger`;
- native Value operations;
- case-on-builtin lowerings introduced by ADR-032.

### D8. Validate final optimized output

Add a target validator that walks the final `Program` after optimization and
before `CompileResult` construction. It validates:

1. the program version equals the compiler target UPLC version;
2. every builtin is in the target profile;
3. every term form is legal for the target;
4. compiler-known case-on-builtin forms are authorized;
5. no future/unreleased feature survives a lower-level route.

Validation runs whether optimization is enabled or disabled. Source-map builds
that currently skip optimization are not exempt.

An illegal optimizer result is reported as an internal compiler invariant
failure with the optimizer rule/pass identity. An illegal user-requested
feature is reported as a source diagnostic. These are different error classes.

### D9. Record target provenance

`CompileResult` gains the resolved public `CompilerTarget`. All constructors
must either accept the target or use the documented PV11 default only in
backward-compatible overloads.

Tooling reports the stable profile ID:

- verbose compiler output;
- `julc build` success output;
- Gradle task lifecycle output;
- annotation-processor notes when verbose diagnostics are enabled;
- MCP compile results;
- generated artifact metadata where the format permits extension.

The standard script envelope, CBOR, FLAT, and script hash do not change merely
to carry this metadata. If a standard artifact format has no target field,
JuLC may add a deterministic adjacent metadata file in a later tooling slice;
it must not insert non-standard fields without an explicit schema decision.

### D10. Add catalog-backed target diagnostics

ADR-021's diagnostic catalog remains authoritative. Add stable catalog entries
for at least:

| Diagnostic family | Required content |
|---|---|
| Unsupported compiler target | requested target and supported targets |
| UPLC version unavailable | requested UPLC version and legal versions |
| Builtin unavailable | builtin, required capability, selected target |
| Term form unavailable | term/feature and selected target |
| Future/unreleased feature | feature, selected target, migration guidance |
| Optimizer invariant violation | rule/pass and illegal emitted feature |
| Compile/evaluate target mismatch | compiled target and requested evaluation target |

Where a Java source node exists, the diagnostic includes file, line, column,
and remediation. Lower-level validation without a source mapping still reports
the feature and stable profile ID.

## Supporting the next protocol version

“Support the next version” means adding a new pinned profile, not increasing a
maximum integer. For a future protocol major `N`, the following sequence is
required.

### 1. Pin the authoritative ledger baseline

Record:

- cardano-node release;
- Plutus package version and commit;
- protocol major/minor used as provenance;
- supported ledger languages;
- UPLC versions;
- builtin batches and exact tags;
- semantics variant mapping;
- decode bounds;
- cost-model schemas.

Later Plutus `master` is not a substitute for the revision shipped by the
supported node release.

### 2. Extend and verify the protocol feature registry

Add an exact profile entry and positive/negative matrix tests. Remove or raise
`MAX_SUPPORTED_PROTOCOL_MAJOR` only in the same change. The registry must not
use an open-ended `protocol >= N` branch that treats all later versions as the
same profile.

If the next protocol introduces no Plutus change, it still receives an
explicit alias entry backed by evidence that its feature profile is identical.

### 3. Decide compiler support independently

Audit every compiler emission route and Phase 5 optimization against the new
profile. Then either:

- add the exact target to `CompilerTargetRegistry`; or
- keep it evaluator-only and return an unsupported compiler target diagnostic.

This prevents a newly evaluatable profile from becoming compilable by
accident.

### 4. Define lowering compatibility

Each target-sensitive lowering chooses one of:

```text
same lowering remains legal
new target-specific lowering
portable fallback
feature rejected for this target
```

The choice is encoded in a target-aware lowering registry or pass, not spread
across source visitors as raw version comparisons.

### 5. Revalidate optimizations and profitability

An optimization legal at PV11 may be illegal, semantically different, or no
longer profitable under the next profile. ADR-032 rules are enabled by named
capabilities and, where cost-directed, an explicit cost profile. No PV11
profitability threshold is inherited silently.

### 6. Add conformance, differential, and golden tests

Required evidence includes:

- valid/invalid feature matrices;
- final compiler-output validation;
- Java/Truffle evaluation of emitted programs under the same target;
- exact failure behavior at target boundaries;
- deterministic output and recorded script-hash changes;
- example and plugin compilation.

### 7. Make default-target changes explicit

Adding support for protocol `N` does not change existing no-target compile
calls. `PLUTUS_V3_PV11` remains the default until a separate release decision
changes it.

A default change requires:

- release notes and migration guidance;
- before/after script bytes and hashes for representative contracts;
- an explicit compatibility statement;
- a documented way to pin the old target while it remains supported;
- versioning appropriate to JuLC's public API stability at that time.

There is never a `LATEST` target because it makes builds depend on library
upgrade timing rather than explicit configuration.

## Affected modules and stages

| Module | Change |
|---|---|
| `julc-vm` | Remains canonical protocol feature/profile layer; may gain named capability queries needed by compiler validation |
| `julc-compiler` | `CompilerTarget`, target registry, compilation context, target-aware lowering/optimizer, final validator, result provenance, diagnostics |
| `julc-stdlib` | Requirement metadata for target-gated intrinsics and libraries |
| `julc-cli` | Target selection/reporting and metadata output |
| `julc-gradle-plugin` | Target property and resolved-target logging |
| `julc-annotation-processor` | Processor option and target provenance in generated build information |
| `julc-testkit` | Compile/evaluate target handoff helpers |
| `julc-cardano-client-lib` | Use the compiled target for canonical evaluation where available |
| examples/docs | Document PV11 default, explicit pinning, and future-target migration |

## Issue #76 traceability

| Issue task | ADR decisions and milestones |
|---|---|
| PV11-040 — compiler target/configuration | D1–D5; Milestones A and B |
| PV11-041 — compile-time feature enforcement | D6–D8; Milestone C |
| PV11-042 — feature metadata and diagnostics | D7 and D10; Milestone C |
| PV11-043 — target provenance | D9; Milestones B and D |

The first implementation is not complete if it adds only the public target
record. Resolution, propagation, final validation, and provenance are parts of
the same correctness contract even when delivered as separate reviewable PRs.

## Compatibility

### Source compatibility

Existing compile entry points continue working and resolve to the named
V3/PV11 target. New overloads/options are additive.

### Binary compatibility

Changing the canonical components of the public `CompileResult` record is a
binary and source compatibility concern. Implementation must assess one of:

1. add a target component and retain compatibility constructors; or
2. convert provenance to an additive method backed by a private field/class
   redesign in a versioned API change.

Because Java record accessors are public API, this choice must be documented in
the implementation PR and release notes.

### Generated scripts

Introducing target representation and validation alone must not change script
bytes. ADR-032 optimizations can change bytes and hashes, and carry their own
rollout policy.

### Unsupported requests

Code that previously had no way to request PV10, V1/V2, or a future protocol
can now request it and receive a deterministic diagnostic. No fallback is
provided.

## Alternatives considered

### Keep V3/PV11 implicit until a second target exists

Rejected. Phase 5 passes need a legality contract now, and retrofitting it
after target-specific rewrites exist would make auditing harder.

### Replace the tag ceiling with a higher constant for each fork

Rejected. Tags do not describe UPLC forms, case-on-builtin behavior, semantics,
or compiler support, and the existence of a tag does not establish release
availability.

### Duplicate a feature table in `julc-compiler`

Rejected. Compiler and evaluator acceptance could drift, creating locally
accepted scripts that the selected evaluator rejects.

### Make `julc-compiler` depend only on `julc-core` by copying protocol enums

Rejected. Parallel representations violate the single-profile invariant. The
existing `julc-vm` contract module is backend-neutral and already has two
concrete consumers once the compiler becomes target-aware.

### Move protocol contracts into a new module immediately

Deferred. It adds module and public-package churn without changing semantics.
Extraction remains possible if more consumers justify it.

### Infer the compiler target from emitted builtins

Rejected. Programs without PV11-only builtins would be ambiguous, and target
selection also controls UPLC forms and optimization legality.

### Default to the newest profile known to the installed JuLC version

Rejected. A dependency upgrade could change script bytes, hashes, failure
behavior, or deployment validity without a source/configuration change.

### Support PV10 fallbacks in the initial target change

Rejected. It multiplies lowering and test scope before PV11 target enforcement
is established. A later target ADR may add PV10 deliberately if there is a
concrete user need.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Compiler gains a public dependency on `julc-vm` | `julc-vm` is backend-neutral and depends only on `julc-core`; expose only protocol-contract types |
| Target is resolved differently by stages | Resolve once into immutable `CompilationContext` |
| Optimizer introduces an unavailable feature | Require target-aware passes and final validation |
| Unknown future PV is treated as PV11 | Exact supported-target table and fail-closed registry |
| Diagnostics lose source locations at final validation | Early lowering metadata plus PIR/UPLC source maps; final validator remains a safety net |
| Provenance alters script hashes | Keep target metadata out of UPLC encoding |
| Default changes unexpectedly in a later release | No `LATEST`; default change requires a separate release decision |
| Existing `CompileResult` consumers break | Compatibility constructors or a separately reviewed public API migration |

## Implementation milestones

### Milestone A — Target values and policy

- Add the compiler dependency on the protocol contract layer.
- Add `CompilerTarget`, `CompilerTargetRegistry`, and internal resolved target.
- Add PV11 default to `CompilerOptions`.
- Add unsupported-target diagnostics and matrix tests.

### Milestone B — Propagation and provenance

- Add immutable per-compilation context.
- Thread it through PIR, stdlib/type-method lowering, UPLC generation, and
  optimization.
- Add target to `CompileResult` and compiler logs.
- Preserve generated program bytes for existing fixtures.

### Milestone C — Feature metadata and final enforcement

- Replace the hard-coded tag ceiling with canonical capability checks.
- Add lowering requirement metadata.
- Add final `Program` target validation after optimization.
- Cover direct builtin, stdlib, type-method, optimizer, and low-level PIR paths.

### Milestone D — Tooling integration

- Add CLI target selection/reporting.
- Add Gradle and annotation-processor target options.
- Add MCP and testkit provenance/handoff.
- Update public docs and examples.

Each milestone requires focused tests and independent review. Milestone C must
land before ADR-032 Phase 5 optimizations are enabled.

## Verification strategy

### Target resolution tests

- V3/PV11/UPLC 1.1.0 resolves successfully.
- V3/PV10, V1, V2, future PV, UPLC 1.0.0, and unknown UPLC versions fail with
  stable diagnostics.
- Explicit unsupported requests never fall back to PV11.

### Feature-route tests

For every target-gated feature, cover:

- Java syntax or typed method route;
- stdlib mapping;
- direct `Builtins` call;
- direct PIR construction;
- optimizer-introduced term;
- final-program validator.

Tags 87–100 must compile for V3/PV11. Tag 101 must fail through every public
entry path.

### Semantic and integration tests

- Evaluate emitted scripts with Java and Truffle using
  `result.target().ledgerTarget()`.
- Test success, failure, evaluation order, and boundary behavior for
  target-gated constructs.
- Verify source-located diagnostics.
- Verify CLI, Gradle, annotation-processor, MCP, and testkit target provenance.

### Determinism and compatibility tests

- Golden existing script bytes and hashes before target plumbing.
- Compile the same input repeatedly and across entry points.
- Verify target metadata does not alter CBOR/FLAT.
- Run focused module tests, affected integration tests, examples, and the full
  repository build.

## Open questions

1. Should `CompileResult` remain a record with an added component, or migrate to
   a class to make future provenance fields additive?
2. Which standard generated artifact, if any, can carry target metadata without
   violating its schema? Otherwise, should tooling emit a sidecar file?
3. Should Gradle and annotation-processor target configuration initially accept
   only the stable profile ID or also structured language/PV/UPLC components?
4. Which named capability representation best covers term forms without
   duplicating `ProtocolFeatureProfile` fields?
5. Should a future protocol-contract module extraction preserve the existing
   package names or use a versioned public API migration?

These questions do not change the initial support matrix or fail-closed future
version policy.
