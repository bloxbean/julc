# ADR-030: Protocol Version Propagation and PV11 Builtin Semantics

**Status:** Accepted and implemented
**Date:** 2026-08-01
**Accepted:** 2026-08-02
**Authors:** JuLC team
**Related issue:** [#61 — `julc-vm-java` discards protocol version and cannot select PV11 builtin semantics](https://github.com/bloxbean/julc/issues/61)
**Related ADR:** [ADR-029 — Protocol Version 11 Ledger Readiness and Optimization Roadmap](029-pv11-ledger-readiness-and-optimization-roadmap.md)
**Roadmap issue:** [#65 — ADR-029 PV11 ledger-conformant evaluation readiness tracker](https://github.com/bloxbean/julc/issues/65)
**Related follow-ups:** [#62 — exact V1/V2 cost-model parsing](https://github.com/bloxbean/julc/issues/62),
[#63 — short parameter arrays](https://github.com/bloxbean/julc/issues/63), and
[#64 — fail closed for missing builtin costs](https://github.com/bloxbean/julc/issues/64)
**Normative compatibility baseline:** [cardano-node 11.0.1](https://github.com/IntersectMBO/cardano-node/releases/tag/11.0.1) /
Plutus `1.63.0.0` at
[`f92b7d7d82622a26caf456a6be33859f697e2cfc`](https://github.com/IntersectMBO/plutus/tree/f92b7d7d82622a26caf456a6be33859f697e2cfc)
**Supersedes:** The protocol-propagation and semantics-variant decisions in
[`plutus-vm-backends/009-protocol-version-gating.md`](plutus-vm-backends/009-protocol-version-gating.md)

---

## Context

JuLC VM providers accept a protocol version when cost-model parameters are
configured:

```java
setCostModelParams(
    long[] costModelValues,
    PlutusLanguage language,
    int protocolMajorVersion,
    int protocolMinorVersion
)
```

In the Java VM, that version is currently used only by `CostModelParser` to
select the expected parameter layout. The provider then stores only the parsed
machine and builtin costs:

```text
protocol version + parameters
             |
             v
      CostModelParser
             |
             v
      ParsedCostModel       protocol version is lost here
             |
             v
 CostTracker + CekMachine   receive costs and ledger language only
```

The current fields make the loss explicit:

```java
private volatile CostModelParser.ParsedCostModel customV1CostModel;
private volatile CostModelParser.ParsedCostModel customV2CostModel;
private volatile CostModelParser.ParsedCostModel customV3CostModel;
```

`evaluateInternal` subsequently constructs `CostTracker` and `CekMachine`
without a protocol version or a builtin-semantics variant. The program's UPLC
version is also discarded because the provider evaluates `program.term()`.

Consequently, a PV11-shaped cost-model array does not make an evaluation fully
PV11-compatible. JuLC can parse PV11 prices while still applying PV10 argument
sizing, runtime behavior, builtin availability, and term-form rules.

This is distinct from [issue #60](https://github.com/bloxbean/julc/issues/60).
The #60 correction applies Haskell's `NumBytesCostedAsNumWords` wrapper to the
requested lengths used by `ReplicateByte` and `IntegerToByteString`. That
wrapper is required in both PV10 and PV11. Issue #61 concerns the additional
costing and runtime behavior that really does change with the protocol version.

## Problem statement

Ledger-faithful Plutus evaluation is a function of more than a cost-model
array:

```text
ledger language
    + protocol version
    + UPLC program version
    + cost-model parameters
    = one evaluation profile
```

Using only `PlutusLanguage` and `ParsedCostModel` cannot answer all of the
questions needed by the evaluator:

- Which Haskell builtin-semantics variant applies?
- Which builtins are available for this language/protocol pair?
- Is `Case` over a builtin constant enabled?
- Which argument representation must be used for builtin costing?
- Which integer and ByteString bounds apply?
- Is the program's UPLC version valid for this ledger target?
- Which deserialization limits apply?

The result can be a false positive, where JuLC accepts a program that the node
rejects, or a budget mismatch, where JuLC and the Haskell evaluator execute the
same program with different CPU or memory costs.

## Haskell reference behavior

The normative source for this decision is the Plutus revision shipped by
cardano-node 11.0.1: Plutus `1.63.0.0`, commit
[`f92b7d7d82622a26caf456a6be33859f697e2cfc`](https://github.com/IntersectMBO/plutus/tree/f92b7d7d82622a26caf456a6be33859f697e2cfc).
All evaluator semantics, builtin availability, cost-model schemas, and
conformance expectations in this ADR target that revision. Later Plutus
`master` changes are not normative and remain out of scope until JuLC
deliberately upgrades its supported cardano-node baseline.

At that pinned revision, ledger language and protocol version select the
following builtin-semantics variants:

| Ledger language | Before PV9 | PV9/PV10 | PV11+ |
|---|---:|---:|---:|
| Plutus V1 | A | B | D |
| Plutus V2 | A | B | D |
| Plutus V3 | Unavailable | C | E |

The distinction is behavioral, not merely a choice of coefficients.

### PV11 string costing

Variants A/B/C use the normal `Text` memory representation, equivalent to
Unicode code-point length.

Variants D/E use `TextCostedByByteLength` for the arguments of:

- `AppendString`;
- `EqualsString`;
- `EncodeUtf8`.

For those arguments, memory usage is:

```text
UTF-8 byte length quot 4
```

The current Java implementation always applies code-point count to every
`StringConst`, so it matches the pre-PV11 representation but cannot produce
PV11 budgets for these builtins.

A concrete upstream conformance case is:

```uplc
[(builtin appendString) (con string "Ola")] (con string " mundo!")
```

| Evaluation profile | CPU | Memory |
|---|---:|---:|
| Current bundled/PV10-style expectation | 680670 | 614 |
| Haskell PV11 expectation | 141057 | 605 |

The difference cannot be selected correctly while the protocol version is
discarded.

### Other protocol-dependent behavior

The same evaluation profile is needed for:

- PV11 `Case` over supported builtin constants;
- Cardano-bounded integer and ByteString unlifting paths;
- variant-specific `ConsByteString` behavior;
- the argument representation of `ShiftByteString` and `RotateByteString`;
- builtin availability for each ledger-language/protocol pair;
- PV11 Batch 6 builtin activation;
- UPLC version validation and PV11 decoding limits.

A string-only conditional would therefore fix one observed budget mismatch but
would not fix the evaluator architecture.

### Verified against cardano-node 11.0.1 (mainnet PV11)

The scale of the gap was measured against the exact Plutus revision shipped by
the mainnet PV11 node. cardano-node `11.0.1` depends on Plutus `1.63.0.0` at
commit [`f92b7d7d8`](https://github.com/IntersectMBO/plutus/tree/f92b7d7d82622a26caf456a6be33859f697e2cfc)
(release commit dated 2026-05-02, the calendar day after the semantics-variant
D/E work landed; cardano-node 11.0.1 was published 2026-05-05). A
blob-level comparison (2026-08-01) of JuLC's bundled conformance snapshot
against that commit's `plutus-conformance/test-cases/uplc/evaluation` found:

| Measure | Result |
|---|---|
| File set | Identical — 3001/3001, nothing missing, nothing extra |
| Content differences | 65 files, all attributable to the PV11 semantics/cost-model changes tracked here |
| Regenerated budgets | 61 `.budget.expected` |
| Semantic flips | 4 `.uplc.expected` |

Of the 61 regenerated budgets, 57 were updated by
[plutus #7756](https://github.com/IntersectMBO/plutus/pull/7756): D/E string
costing (`appendString` cpu 680670 → 141057, `equalsString`, `encodeUtf8`;
3 files), recalibrated division-family constants (`divideInteger`/
`modInteger`/`quotientInteger`/`remainderInteger`; 20 files),
`expModInteger` (17 files), direct hash cases (`blake2b_224/256`, `sha2_256`,
`sha3_256`, `keccak_256`, `ripemd_160`; 12 files), ByteString
comparison/complement cases (4 files), and the composite `IfIntegers` case
(1 file). The other 4 budget files and their corresponding 4
`.uplc.expected` files changed under
[plutus #7754](https://github.com/IntersectMBO/plutus/pull/7754):
`shiftByteString`/`rotateByteString` by more than `maxBound::Int64` now fail.

This confirms both halves of the problem statement: the node 11.0.1 Plutus
baseline differs from JuLC's variant-C implementation in exactly the areas
this ADR covers, and the bundled snapshot (deliberately pinned to pre-D/E,
variant-C expectations — see
`julc-vm/src/test/resources/conformance/README.md`) is self-consistent with
the current implementation. It must remain the PV10 profile rather than being
overwritten by the PV11 data. The 65 files above are the concrete
V3/PV11/semantics-E acceptance target for D6–D8 and should be added as a
separate profile once profile selection exists.

## Decision drivers

The design must:

1. match the Haskell evaluator for both PV10 and PV11;
2. keep costs, runtime semantics, and availability on the same protocol
   profile;
3. prevent a cost model for one protocol from being evaluated with another
   protocol's semantics;
4. preserve the existing SPI during migration;
5. avoid scattered `protocolMajorVersion >= 11` checks;
6. remain extensible for later protocol versions and semantics variants;
7. allow conformance tests to state exactly which profile they exercise.

## Decision

### D1. Make protocol version a first-class immutable value

Introduce a validated protocol-version type and retain both components even
when a current rule depends only on the major version:

```java
public record ProtocolVersion(int major, int minor) {
    public ProtocolVersion {
        if (major < 0 || minor < 0) {
            throw new IllegalArgumentException("Protocol version must be non-negative");
        }
    }
}
```

Raw integers may remain at external integration boundaries, but must be
converted once and not passed independently through the evaluator.

### D2. Resolve one immutable evaluation profile

Each evaluation must use a profile derived from the complete ledger target.
The conceptual API is:

```java
public record LedgerEvaluationTarget(
    PlutusLanguage ledgerLanguage,
    ProtocolVersion protocolVersion
) {}

public enum BuiltinSemanticsVariant { A, B, C, D, E }

public record ProtocolFeatureProfile(
    LedgerEvaluationTarget target,
    BuiltinSemanticsVariant semanticsVariant,
    Set<DefaultFun> availableBuiltins,
    boolean caseOnBuiltinConstants,
    DecodeLimits decodeLimits,
    CostModelSchema costModelSchema
) {}
```

Names and package boundaries may change during implementation. The invariant
is that all protocol-sensitive evaluator decisions come from one immutable
profile resolved from `(ledger language, protocol version)`.

The profile resolver must be table-driven and shared by all VM backends that
claim ledger-compatible behavior. `DefaultFun.minLanguageVersion()` alone must
not decide ledger availability.

### D3. Keep protocol selection independent from cost parsing

Protocol version and cost parameters are related but are not interchangeable.
The length of a cost-model array must not be used to infer runtime semantics.

The canonical provider path will accept an explicit ledger evaluation target
for evaluation. A representative SPI shape is:

```java
EvalResult evaluate(
    Program program,
    LedgerEvaluationTarget target,
    ExBudget budget,
    EvalOptions options
);
```

Cost-model configuration must be associated with the same target. A backend
may cache a parsed model, but the cached value must retain its target and
resolved profile:

```java
record ConfiguredCostModel(
    ParsedCostModel costModel,
    LedgerEvaluationTarget target,
    ProtocolFeatureProfile profile
) {}
```

At evaluation time, a configured cost model whose ledger language or protocol
major differs from the requested target must fail clearly. The protocol minor
is retained as provenance but is not a Plutus semantics key: cardano-node
supplies only `MajorProtocolVersion` to Plutus. The evaluator must never
silently combine PV11 prices with PV10 semantics or the reverse.

### D4. Provide a compatibility path with an explicit PV10 default

The current `JulcVmProvider` API cannot require an explicit target without a
migration period. Existing overloads will remain as compatibility entry
points:

- if `setCostModelParams` has configured a model for the selected ledger
  language, the compatibility path uses the protocol version stored with that
  model;
- if no protocol has been configured, the compatibility path uses a documented
  PV10 profile and the built-in PV10 cost model;
- use of the compatibility default should be observable in diagnostics or
  tracing and documented as not selecting PV11;
- the explicit-target overload is the canonical API for ledger validation.

PV10 is selected as the compatibility default because it preserves the
pre-PV11 behavior of existing callers. Silently changing the legacy overload
to PV11 would enable new builtins and semantics without the caller choosing a
new ledger target.

The provider's per-language mutable fields will store complete immutable
`ConfiguredCostModel` records rather than bare `ParsedCostModel` values. A
single volatile-record replacement keeps each update atomic and prevents
partially updated protocol/cost state.

### D5. Pass the profile through every protocol-sensitive layer

For the Java VM, the resolved profile must reach at least:

```text
JavaVmProvider
    |
    +-- program-version validation
    +-- CekMachine
    |     +-- Case behavior
    |     +-- builtin runtime selection
    |
    +-- BuiltinTable
    |     +-- builtin availability
    |
    +-- CostTracker
          +-- BuiltinCostModel argument sizing
```

The corresponding Truffle and Scalus paths must consume the same target or
prove an equivalent mapping. A backend must not advertise PV11 ledger parity
merely because its builtin functions can compute PV11 operations.

`JavaVmProvider` must pass the `Program`, not only `program.term()`, into the
validation/evaluation boundary so the UPLC version remains available.

### D6. Select argument sizing by builtin and semantics variant

Generic constant memory usage remains useful, but it is insufficient for
wrapped Haskell builtin arguments. The costing boundary must accept both the
builtin and the resolved variant, for example:

```java
long[] argSizes(
    BuiltinSemanticsVariant variant,
    DefaultFun builtin,
    List<CekValue> args
)
```

Required behavior includes:

| Behavior | Variants A/B/C | Variants D/E |
|---|---|---|
| Normal string representation | Unicode code-point length | Unicode code-point length |
| `AppendString` arguments | Normal string representation | UTF-8 byte length `quot 4` |
| `EqualsString` arguments | Normal string representation | UTF-8 byte length `quot 4` |
| `EncodeUtf8` argument | Normal string representation | UTF-8 byte length `quot 4` |
| #60 literal-byte length wrappers | `NumBytesCostedAsNumWords` | `NumBytesCostedAsNumWords` |

The #60 sizing rule must remain protocol-independent. It must not be placed
inside a PV11 branch.

Argument wrappers should be represented by named policies or functions, such
as `TextCostedByByteLength`, rather than repeated switch statements. This makes
the Java structure auditable against Haskell's builtin meaning declarations.

### D7. Use the same profile for runtime semantics and availability

Costing must not be upgraded independently from execution behavior. The
resolved profile also selects:

- runtime/unlifting behavior for variants D/E;
- integer and ByteString bounds;
- the correct `ConsByteString` implementation;
- case-on-builtin support;
- builtin availability;
- protocol-specific decode limits.

All gates require tests on both sides of the boundary. A PV11 success test
without a corresponding PV10 rejection or PV10-behavior test is insufficient.

The pinned Haskell source also distinguishes constructor tags by semantics
variant. `ConstrData` takes an arbitrary `Integer` in A/B/C, while D/E unlift
the argument as `Word64` and then store it in the underlying
`Data.Constr Integer`. JuLC therefore preserves arbitrary constructor integers
in its in-memory `PlutusData`, applies the `Word64` gate only for D/E builtin
execution, and accepts the complete unsigned 64-bit domain when decoding ledger
CBOR. Values outside `Word64` can be produced and serialized under B/C but, as
in the pinned Haskell implementation, are not round-trippable through the
ledger CBOR decoder.

### D8. Version and pin conformance budgets

Bundled conformance resources must record:

- the upstream Plutus commit;
- ledger language;
- protocol version;
- builtin-semantics variant;
- cost-model schema or source.

Where PV10 and PV11 budgets differ, resources must be split by profile or the
test harness must generate/select the expected budget from explicit metadata.
The term and result alone may be shared; a stale budget must not be described
as the latest upstream expectation.

For this ADR, the normative PV11 conformance source is exactly Plutus
`1.63.0.0` at `f92b7d7d8`, as shipped by cardano-node 11.0.1. A later Plutus
revision must not be substituted without a separate, explicit decision to
upgrade the supported node baseline.

## Invariants

The implementation must maintain the following invariants:

1. An evaluation has exactly one ledger language and protocol version.
2. The semantics variant is derived, never independently supplied by callers.
3. Costs and semantics belong to the same evaluation target.
4. Program-version validation occurs before CEK execution.
5. Builtin availability and builtin execution use the same feature profile.
6. The protocol version cannot change during an evaluation.
7. Legacy evaluation has a documented target; it is never "latest" implicitly.

## Alternatives considered

### Patch only the three string builtins

Rejected. This fixes the demonstrated PV11 budget case but leaves runtime
semantics, bounds, builtin availability, `Case`, and later protocol changes
without a reliable selection mechanism.

### Infer PV11 from a 350-parameter array

Rejected. Parameter schemas and runtime semantics are separate ledger inputs.
Inference is ambiguous for other ledger languages and unsafe for future schema
changes. It also fails when built-in default costs are used.

### Store a `pv11` boolean only in `CostTracker`

Rejected. Costing is only one consumer. `CekMachine`, `BuiltinTable`, program
validation, and deserialization need the same decision.

### Store protocol version only in `CekMachine`

Rejected. This enables runtime checks but does not make cost sizing or model
selection safe, and encourages duplicate mappings in separate components.

### Always use the newest semantics

Rejected. Local validation must match the transaction's target protocol, not
the newest behavior known to the library. Automatically using PV11 would
create false positives when evaluating PV10 scripts.

### Delegate protocol behavior entirely to the Scalus backend

Rejected. The Java backend is the preferred provider and independently claims
evaluation support. Backend selection must not change ledger validity or exact
budgets.

## Consequences

### Positive

- PV10 behavior remains stable for compatibility callers.
- PV11 costs and runtime behavior can match Haskell intentionally.
- Incorrect cross-protocol combinations fail instead of producing plausible
  but invalid results.
- Future protocol upgrades have one extension point.
- Java, Truffle, and Scalus parity can be expressed as the same test matrix.
- Conformance results become evidence for a named ledger target.

### Negative

- The VM SPI gains a protocol-aware evaluation path.
- Protocol context must be threaded through several Java and Truffle layers.
- Existing tests that relied on implicit "latest" behavior must declare a
  target or accept the documented PV10 compatibility default.
- Cost-model caches must be keyed by the complete target rather than only the
  ledger language if multiple protocol profiles coexist.
- Semantics variants D/E require runtime work beyond the sizing correction.

### Risks and mitigations

| Risk | Mitigation |
|---|---|
| Feature tables drift between backends | One shared table-driven resolver |
| A cost model is paired with the wrong semantics | Store and validate the target with parsed costs |
| Mutable provider configuration changes mid-evaluation | Snapshot one immutable configuration record before execution |
| Compatibility calls unknowingly use PV11 | Default explicitly to PV10 and expose the selected target |
| Stale conformance budgets hide a mismatch | Pin upstream revision and profile metadata |
| Raw version checks proliferate | Pass `ProtocolFeatureProfile`, not loose booleans |

## Implementation sequence

1. Add `ProtocolVersion`, `LedgerEvaluationTarget`,
   `BuiltinSemanticsVariant`, and the profile resolver with mapping tests.
2. Replace bare Java/Truffle cached cost models with immutable configured
   records that retain their target.
3. Add the explicit-target provider API and retain the PV10 compatibility
   overload.
4. Pass the resolved profile through program validation, `CekMachine`,
   `BuiltinTable`, `CostTracker`, and `BuiltinCostModel` without changing
   behavior initially.
5. Implement D/E argument representations and exact string budgets.
6. Implement remaining D/E runtime and bound differences.
7. Apply protocol-aware builtin, `Case`, UPLC-version, and decode-limit gates.
8. Pin and split the conformance corpus by evaluation profile.
9. Run the same language/protocol matrix across every supported backend.

Each step should be reviewable independently. The propagation step should land
before individual PV11 behavior switches so later changes cannot bypass the
shared context.

## Implementation outcome

The implementation follows the sequence above and is pinned to the normative
cardano-node 11.0.1 / Plutus `f92b7d7d8` baseline:

- Java and Truffle resolve and retain one immutable profile for explicit
  `(ledger language, protocol version)` targets. Their compatibility overloads
  retain a configured target or select PV10 when no model is configured.
- Cost sizing, runtime unlifting/denotations, builtin availability, `Case`,
  UPLC-version validation, and default/model selection consume that same
  profile. A configured model cannot be evaluated under a different ledger
  language or protocol major; minor-only differences remain compatible with
  Plutus's major-version interface.
- Production transaction script decoding receives the transaction's protocol
  version and ledger language. PV11 enforces a maximum constant-universe header
  size of 32 nodes and at most 1,024 fields on a `Constr` term; earlier profiles
  remain unrestricted. The 1,024 limit does not apply to `Case` branches.
- `PlutusData.ConstrData` preserves the Haskell `Integer` constructor tag. D/E
  execution checks `Word64`; UPLC `Constr` extraction preserves all unsigned
  64 bits; CBOR encoding/decoding and `serialiseData` cover the pinned boundary
  behavior without narrowing to Java `int`.
- The frozen V3/PV10/C corpus remains the base profile. The exact 65-file
  difference from `f92b7d7d8` is a V3/PV11/E overlay. Java and Truffle each run
  999 PV11 cases with 724 numeric budget assertions and 737 applicable PV10
  cases with 545 numeric budget assertions; the runner asserts all counts and
  the 65 overlay files.
- Scalus does not implement the explicit-target SPI and fails closed on it. It
  is therefore explicitly excluded from this ADR's PV10/PV11 ledger-parity
  claim until it passes the same pinned matrix. Its current `Data.Constr`
  representation also rejects negative constructor integers that pinned
  pre-D/E Plutus can construct; JuLC reports that bridge limitation explicitly.

### Post-implementation exactness audit

An adversarial audit of the completed #61–#64 stack found additional edge
cases. They are fixed in the #65 readiness branch so the claim remains tied to
the same node/Plutus pin:

- Model E uses `above_and_below_diagonal` only for `divideInteger` and
  `modInteger`; `quotientInteger` and `remainderInteger` retain
  `const_above_diagonal`. Tests use the wide `(1,16)` size case where the node
  charges `85848` for quotient/remainder and `187016` for divide/mod.
- Cost arrays are tagged against the complete pinned `ParamName` enum
  (V1/V2: 332, V3: 350), independently of the active protocol prefix. Missing
  values are `maxBound`-padded and excess values are truncated, with the same
  non-fatal warnings as `tagWithParamNames`. Parameters registered before a
  hard fork are retained without enabling the corresponding builtin early.
- Integer-to-`Int` and integer-to-`Word8` unlifting is checked before Java
  narrowing for `consByteString`, `sliceByteString`, `readBit`, `writeBits`,
  and `replicateByte`. BLS multi-scalar multiplication validates every scalar
  before applying list-zip semantics.
- V3 rejects bytes remaining after the inner serialized-script CBOR item;
  V1/V2 retain the pinned historical tolerance. Restricting-budget subtraction
  uses `SatInt`-equivalent saturation.
- Historical variant A defaults and CEK costs, variant D's canonical
  `linear_in_y2` representation, minor-version compatibility, and explicit
  PV11 debugger profile selection are covered by pinned regression tests.

The Java/Truffle evaluator is exact against node 11.0.1 within this scope; the
sole known decode-boundary exclusion is the adversarial-input phase-1 CBOR
wrapper divergence tracked in [#67](https://github.com/bloxbean/julc/issues/67).

Later Plutus `master` behavior remains intentionally excluded.

## Acceptance criteria

### Context propagation

- [x] Protocol major and minor versions survive cost-model parsing.
- [x] The evaluator derives the Haskell-compatible semantics variant from
      ledger language and protocol version.
- [x] One immutable profile reaches cost sizing, runtime semantics, builtin
      availability, and program validation.
- [x] A configured cost model cannot be used with a different requested ledger
      language or protocol major; protocol-minor-only differences are accepted.
- [x] The legacy no-target API has a documented and tested PV10 default.
- [x] The program's UPLC version is validated instead of discarded.

### PV10 and PV11 costing

- [x] V3/PV10 selects variant C.
- [x] V3/PV11 selects variant E.
- [x] V1/V2 at PV10 select variant B.
- [x] V1/V2 at PV11 select variant D.
- [x] PV10 string budgets remain unchanged.
- [x] PV11 `AppendString`, `EqualsString`, and `EncodeUtf8` budgets match
      Haskell for ASCII and multibyte Unicode.
- [x] The upstream `appendString` example costs exactly `cpu=141057,
      mem=605` under the pinned PV11 profile.
- [x] `ReplicateByte` and fixed-width `IntegerToByteString` preserve the #60
      `NumBytesCostedAsNumWords` correction under both PV10 and PV11.

### Runtime and availability

- [x] PV11-only builtins fail below PV11 and succeed at PV11 when valid for the
      selected ledger language.
- [x] Plutus V2/PV10 accepts `IntegerToByteString` and
      `ByteStringToInteger`; their exact node-provided prices are tracked by
      [#62](https://github.com/bloxbean/julc/issues/62).
- [x] Case-on-builtin behavior is tested below and at PV11.
- [x] D/E integer and ByteString bounds match Haskell success/failure behavior.
- [x] Variant-D and variant-E `ConsByteString` behavior is tested separately.
- [x] PV11 FLAT decoding enforces the pinned 32-node constant-type header and
      1,024-constructor-field limits without applying the latter to `Case`;
      PV10 decoding remains unrestricted.
- [x] Java and Truffle pass the same explicit profile matrix.
- [x] Scalus either passes the matrix or is excluded from the corresponding
      ledger-parity claim.
- [x] Model-E division shapes distinguish divide/mod from quotient/remainder,
      including the pinned wide-gap budget counterexample.
- [x] Checked `Int`/`Word8` unlifting and full-list BLS scalar validation reject
      values that the pinned Haskell evaluator rejects without narrowing.
- [x] V3 serialized-script remainders fail while V1/V2 historical remainders
      remain accepted.

### Conformance provenance

- [x] Every budget snapshot records its upstream Plutus commit and evaluation
      profile.
- [x] PV10 and PV11 expectations cannot be selected implicitly by whichever
      resource happens to be bundled.
- [x] The conformance runner asserts a nonzero, exact count of budget files
      compared per profile, so resource-discovery drift cannot silently disable
      every budget assertion while results stay green.
- [x] The 65 files that diverge from plutus `1.63.0.0` (`f92b7d7d8`, the
      revision shipped by cardano-node 11.0.1) — 61 regenerated budgets and 4
      `shiftByteString`/`rotateByteString` semantic flips — pass under the
      V3/PV11/semantics-E profile.
- [x] Later Plutus `master` changes are excluded unless JuLC explicitly adopts
      a newer cardano-node/Plutus compatibility baseline.

## Non-goals

- Changing the protocol-independent #60 sizing correction.
- Adopting cost coefficients or semantics newer than the pinned node baseline.
- Defining compiler optimization policy; ADR-029 covers the broader PV11
  compiler and optimization roadmap.
- Treating an unreleased future protocol version as equivalent to PV11.
- Adopting Plutus changes newer than cardano-node 11.0.1's pinned
  `f92b7d7d8` dependency.

## References

- [JuLC issue #61](https://github.com/bloxbean/julc/issues/61)
- [JuLC issue #60](https://github.com/bloxbean/julc/issues/60)
- [ADR-029 — PV11 ledger readiness and optimization roadmap](029-pv11-ledger-readiness-and-optimization-roadmap.md)
- [Plutus protocol-version to semantics-variant mapping](https://github.com/IntersectMBO/plutus/blob/f92b7d7d82622a26caf456a6be33859f697e2cfc/plutus-ledger-api/src/PlutusLedgerApi/Common/ProtocolVersions.hs#L121-L139)
- [Plutus machine-parameter semantics variants](https://github.com/IntersectMBO/plutus/blob/f92b7d7d82622a26caf456a6be33859f697e2cfc/plutus-ledger-api/src/PlutusLedgerApi/MachineParameters.hs#L14-L38)
- [Plutus `TextCostedByByteLength` memory usage](https://github.com/IntersectMBO/plutus/blob/f92b7d7d82622a26caf456a6be33859f697e2cfc/plutus-core/plutus-core/src/PlutusCore/Evaluation/Machine/ExMemoryUsage.hs#L335-L340)
- [Plutus string builtin meanings](https://github.com/IntersectMBO/plutus/blob/f92b7d7d82622a26caf456a6be33859f697e2cfc/plutus-core/plutus-core/src/PlutusCore/Default/Builtins.hs#L1498-L1582)
- [Pinned V3/PV11 `appendString` budget](https://github.com/IntersectMBO/plutus/blob/f92b7d7d82622a26caf456a6be33859f697e2cfc/plutus-conformance/test-cases/uplc/evaluation/builtin/semantics/appendString/appendString.uplc.budget.expected)
- [cardano-node 11.0.1 release (ships plutus-core 1.63.0.0)](https://github.com/IntersectMBO/cardano-node/releases/tag/11.0.1)
- [Plutus 1.63.0.0 conformance suite as shipped by node 11.0.1](https://github.com/IntersectMBO/plutus/tree/f92b7d7d82622a26caf456a6be33859f697e2cfc/plutus-conformance/test-cases/uplc/evaluation)
- [Bundled snapshot provenance and drift list](../julc-vm/src/test/resources/conformance/README.md)
