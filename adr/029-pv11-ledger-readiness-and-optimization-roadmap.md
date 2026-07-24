# ADR-029: Protocol Version 11 Ledger Readiness and Optimization Roadmap

**Date**: 2026-07-24
**Status**: Proposed (planning only; no implementation is authorized by this ADR)
**Target**: Cardano protocol version 11 (van Rossem)
**Supersedes**: The PV11 builtin classification and readiness conclusions in
[`plutus-vm-backends/006-phase5-pv11-builtins.md`](plutus-vm-backends/006-phase5-pv11-builtins.md),
[`plutus-vm-backends/007-pv11-cost-model-remaining-gaps.md`](plutus-vm-backends/007-pv11-cost-model-remaining-gaps.md),
and [`plutus-vm-backends/009-protocol-version-gating.md`](plutus-vm-backends/009-protocol-version-gating.md).
Those documents remain useful as implementation history.

---

## Context

The van Rossem hard fork enacted protocol version 11 on Cardano mainnet on
2026-07-18. PV11 adds a batch of Plutus builtins, makes all released builtins
available to every Plutus ledger language, enables UPLC 1.1 for Plutus V1/V2,
enables `Case` over selected builtin constant types, introduces builtin semantics
variants D/E, and adds deserialization limits.

JuLC already contains substantial PV11 work:

- AST and FLAT tags for the new builtins and constant types;
- Java and Truffle builtin implementations;
- compiler and stdlib entry points for arrays, BLS MSM, modular exponentiation,
  list dropping, and native Value operations;
- a 350-parameter Plutus V3 PV11 cost-model parser;
- passing Java and Truffle executions of the bundled Plutus conformance corpus.

An audit against the current authoritative Plutus release tables found that
functional builtin coverage is not equivalent to ledger-faithful PV11 support.
The current VM architecture does not retain enough protocol context to reproduce
the ledger's accept/reject and semantics decisions.

The audit also found one immediate compatibility hazard: JuLC advertises and
emits `MultiIndexArray` as a PV11 builtin even though it is not released in PV11.

This ADR establishes:

1. the authoritative PV11 feature baseline;
2. what “PV11 ready” means for JuLC;
3. the required VM/compiler architecture;
4. a phased correctness and optimization plan;
5. acceptance criteria for claiming PV11 support.

## Why a new ADR is required

The earlier PV11 ADRs contain conclusions that are no longer correct:

- They list codes 88–101 as the 14 PV11 builtins, omitting
  `ExpModInteger` (87) and including future-only `MultiIndexArray` (101).
- They associate `DropList` with CIP-158 and the base Array feature with
  CIP-156. The correct references are CIP-132 and CIP-138. CIP-156 is the
  separate, future `multiIndexArray` proposal.
- They assume that changing `DefaultFun.minLanguageVersion()` to return V3 is
  sufficient to make Batch 6 available to Plutus V1/V2. It is not: the current
  table still rejects those builtins in V1/V2.
- They treat builtin semantics variants as unchanged for V3 across PV9+.
  PV11 changes V1/V2 to semantics variant D and V3 to variant E.
- They treat accepting and ignoring longer future cost arrays as desirable
  forward compatibility. For a ledger evaluator this can silently apply the
  wrong prices and must not be the default behavior.

---

## Authoritative PV11 baseline

The source of truth for release activation is the Plutus
`PlutusLedgerApi.Common.Versions` table, not the existence of a FLAT tag,
implementation, conformance test, or CIP.

### Batch 6: the 14 new builtins

| Tag | Builtin | Signature (UPLC argument order) | Proposal |
|---:|---|---|---|
| 87 | `ExpModInteger` | `Integer -> Integer -> Integer -> Integer` | CIP-109 |
| 88 | `DropList` | `Integer -> List a -> List a` | CIP-132 |
| 89 | `LengthOfArray` | `Array a -> Integer` | CIP-138 |
| 90 | `ListToArray` | `List a -> Array a` | CIP-138 |
| 91 | `IndexArray` | `Array a -> Integer -> a` | CIP-138 |
| 92 | `Bls12_381_G1_multiScalarMul` | `List Integer -> List G1 -> G1` | CIP-133 |
| 93 | `Bls12_381_G2_multiScalarMul` | `List Integer -> List G2 -> G2` | CIP-133 |
| 94 | `InsertCoin` | `ByteString -> ByteString -> Integer -> Value -> Value` | CIP-153 |
| 95 | `LookupCoin` | `ByteString -> ByteString -> Value -> Integer` | CIP-153 |
| 96 | `UnionValue` | `Value -> Value -> Value` | CIP-153 |
| 97 | `ValueContains` | `Value -> Value -> Bool` | CIP-153 |
| 98 | `ValueData` | `Value -> Data` | CIP-153 |
| 99 | `UnValueData` | `Data -> Value` | CIP-153 |
| 100 | `ScaleValue` | `Integer -> Value -> Value` | CIP-153 |

### Explicitly not PV11

`MultiIndexArray` (tag 101) is implemented upstream but is in the unreleased
future batch together with `Policies`. It must be rejected for a PV11 target.
JuLC may retain its AST/FLAT representation for forward development, but the
compiler and VM must not present it as ledger-valid at PV11.

### Availability by ledger language

At PV11 all released builtins are available to all three ledger languages:

| Ledger language | Builtins newly enabled at PV11 |
|---|---|
| Plutus V1 | Batches 2, 3, 4, 5, and 6 |
| Plutus V2 | Batch 4a, Batch 5, and Batch 6 |
| Plutus V3 | Batch 6 |

This means builtin availability is a function of
`(ledger language, protocol version)`, not a single “minimum Plutus language”
number.

### Other PV11 Plutus features

1. **UPLC 1.1 in Plutus V1/V2.** `Constr` and `Case` become valid AST terms for
   V1/V2 when the protocol target is PV11.
2. **Case over builtin constants.** At PV11, `Case` may scrutinize `Unit`,
   `Bool`, `Integer`, `List`, and `Pair` constants. It remains unavailable for
   older protocol versions.
3. **Semantics variants D/E.**
   - Plutus V1/V2 at PV11 use variant D.
   - Plutus V3 at PV11 uses variant E.
   - These variants introduce Cardano integer/ByteString bounds and updated
     string costing; D and E retain different `ConsByteString` behavior.
4. **Deserialization bounds.**
   - constant-type header bound: 32;
   - constructor field-count bound: 1024.
5. **PV11 cost-model schemas.**
   - Plutus V1: 332 parameters;
   - Plutus V2: 332 parameters;
   - Plutus V3: 350 parameters.

Ledger and consensus changes such as VRF key uniqueness are outside JuLC's
Plutus compiler/VM scope.

---

## Audit findings

### Builtin implementation coverage

All 14 official PV11 builtins are present in `DefaultFun`, registered by the
Java runtime, exposed through compiler/stdlib entry points, and executed by the
Java and Truffle backends.

The principal surfaces are:

- `MathLib.expMod`;
- `Builtins.dropList`;
- `JulcArray` / `JulcList.toArray`;
- `BlsLib.g1MultiScalarMul` / `g2MultiScalarMul`;
- `NativeValueLib`.

### Verification baseline on 2026-07-24

The following audits passed without source changes:

```text
./gradlew :julc-core:test :julc-vm-java:test :julc-stdlib:test \
  --rerun-tasks --no-daemon

./gradlew :julc-vm-truffle:test :julc-vm-scalus:test \
  --rerun-tasks --no-daemon
```

- Java VM bundled Plutus conformance: 999 tests, 0 skipped, 0 failures.
- Truffle VM bundled Plutus conformance: 999 tests, 0 skipped, 0 failures.
- Scalus unit/integration tests pass, but its Plutus conformance class is
  disabled and skips PV11 Array, Value, BLS, and case-on-constant paths.

These results establish strong functional evidence for the Java/Truffle
builtin implementations. They do not establish protocol-version gating,
semantics-variant fidelity, or ledger budget parity because the conformance
runner evaluates all programs as Plutus V3 without a protocol-version matrix.

### Readiness scorecard

| Area | State | Finding |
|---|---|---|
| AST tags and constant types | Ready for Batch 6 | Official tags 87–100 represented |
| Java builtin functions | Functionally ready | All 14 registered and conformance-tested |
| Truffle builtin functions | Functionally ready | Shares runtime semantics; conformance-tested |
| Compiler/stdlib API | Partial | All official builtins exposed, but no target gate; tag 101 exposed as PV11 |
| Protocol-version gating | Not ready | Java/Truffle discard PV after cost parsing |
| V1/V2 PV11 availability | Not ready | Language-only gate rejects PV11-enabled builtins |
| UPLC version validation | Not ready | Evaluators discard `Program` version and evaluate only the term |
| PV11 `Case` gating | Not ready | Enabled for V3 regardless of PV; disabled for V1/V2 |
| Semantics variants D/E | Not ready | Runtime has no semantics-variant context |
| PV11 decode bounds | Not ready | FLAT decoder does not enforce them |
| V3 PV11 cost parsing | Implemented, parity unproven | Parses 350 parameters and Batch 6 cost shapes |
| V1/V2 PV11 cost parsing | Not ready | Accepts longer arrays but ignores new values |
| Scalus backend | Not verified | PV11 conformance suite disabled/skip-heavy |

### Current false-positive and false-negative behavior

The language-only gate can disagree with the ledger in both directions:

- **False positive:** a V3/PV10 evaluation can execute Batch 6 even though the
  ledger would reject it.
- **False negative:** a V1 or V2/PV11 evaluation rejects Batch 6 even though the
  ledger permits it.
- **False positive:** a V3/PV11 evaluation can execute `MultiIndexArray`, which
  PV11 does not release.
- **False positive:** V3 can use case-on-builtin constants below PV11.
- **False negative:** V1/V2 cannot use UPLC 1.1 `Constr`/`Case` at PV11.

There are also semantic/budget discrepancies:

- Java always range-checks `ConsByteString`, which matches variant E but not
  variant D's legacy behavior.
- Java does not apply the PV11 Cardano integer and ByteString bounds.
- Java measures strings using Java character count rather than the PV11
  UTF-8-byte costing behavior.
- V1/V2 `Constr` and `Case` machine costs are set to zero instead of being
  parsed from PV11 parameters.

---

## Decision

### D1. Separate “functional builtin coverage” from “ledger-ready PV11”

JuLC documentation and release notes must use two distinct claims:

- **PV11 builtin implementation coverage:** the runtime can compute the 14
  Batch 6 operations.
- **PV11 ledger-conformant evaluation:** the VM matches the node's validation,
  semantics, and budget decisions for a specific language/PV pair.

The second claim must not be made until all P0 acceptance criteria in this ADR
pass.

### D2. Introduce an immutable ledger evaluation context

Evaluation must receive a single immutable context rather than reconstructing
behavior from `PlutusLanguage` plus mutable provider fields.

The conceptual shape is:

```java
record LedgerEvaluationContext(
        PlutusLanguage ledgerLanguage,
        ProtocolVersion protocolVersion,
        UplcVersion uplcVersion,
        BuiltinSemanticsVariant semanticsVariant,
        Set<DefaultFun> availableBuiltins,
        DecodeLimits decodeLimits,
        MachineCosts machineCosts,
        BuiltinCostModel builtinCosts) {}
```

The final API names may differ, but the context must preserve all of these
decisions through deserialization and CEK execution.

Provider state must not silently mix a cost model loaded for one protocol
version with evaluation behavior from another. A compatibility overload may
construct the context for existing callers, but the protocol-aware path is the
canonical one.

### D3. Use one table-driven protocol feature registry

A common registry must answer:

```text
availableBuiltins(ledgerLanguage, protocolVersion)
availableUplcVersions(ledgerLanguage, protocolVersion)
semanticsVariant(ledgerLanguage, protocolVersion)
caseOnBuiltinEnabled(protocolVersion)
decodeLimits(protocolVersion)
costModelSchema(ledgerLanguage, protocolVersion)
```

The Java VM, Truffle VM, Scalus bridge, compiler diagnostics, FLAT
deserialization, CLI, and tests must consume the same registry.

`DefaultFun.minLanguageVersion()` is insufficient and should no longer decide
ledger validity.

### D4. Make the compiler target protocol-aware

Compilation must have an explicit target containing at least:

```text
ledger language + protocol version + UPLC version
```

The compiler must:

- reject builtins unavailable for the target;
- reject `MultiIndexArray` for PV11;
- select only legal term forms and semantics-sensitive rewrites;
- attach actionable diagnostics such as:
  `ListToArray requires protocol version 11; current target is 10`;
- expose the target in CLI/Gradle configuration and compilation metadata.

Stdlib APIs may use metadata such as `@RequiresProtocolVersion(11)`, but the
canonical decision remains the shared feature registry.

### D5. Implement semantics variants D/E and exact PV11 bounds

Builtin execution and memory sizing must be selected from the evaluation
context. At minimum, PV11 implementation must cover:

- bounded integer and ByteString inputs/results;
- variant-D versus variant-E `ConsByteString`;
- UTF-8-byte-based string sizing/costing;
- case-on-builtin availability and decomposition;
- PV11 FLAT decode limits.

Errors must match the reference evaluator's success/failure class even when
diagnostic text differs.

### D6. Parse exact cost-model schemas

- Implement complete 332-parameter V1 and V2 parsers.
- Keep the existing 350-parameter V3 parser, but validate it against reference
  parameter names, live protocol parameters, and golden budgets.
- Reject an unexpected schema by default. Supporting a future protocol version
  requires a known schema/feature profile; extra values must not be silently
  ignored as PV11.
- Parse non-zero `Constr`/`Case` costs for V1/V2 PV11.

### D7. Correctness precedes automatic optimization

PV11-specific optimizer passes may be introduced only after the compiler target
and evaluation context exist. Every automatic rewrite must:

- be legal for the selected target;
- preserve failure behavior and strictness;
- show a budget and/or script-size improvement under the target cost model;
- have a non-rewritten comparison test;
- be disableable during initial rollout if it changes generated script hashes.

---

## Implementation roadmap

### Phase 0 — Correct the public PV11 contract

| ID | Work | Priority | Effort |
|---|---|---:|---:|
| PV11-001 | Correct Batch 6 to tags 87–100 | P0 | S |
| PV11-002 | Mark tag 101 as future/unreleased; prevent PV11 compilation | P0 | S |
| PV11-003 | Correct CIP references: 109/132/138/133/153 | P0 | S |
| PV11-004 | Update docs/API labels and add migration warning for `multiIndexArray` | P0 | S |

`MultiIndexArray` need not be removed from the enum or experimental VM code.
It must be impossible to emit or accept it under a PV11 ledger target.

### Phase 1 — Protocol-aware evaluation foundation

| ID | Work | Priority | Effort |
|---|---|---:|---:|
| PV11-010 | Add immutable `LedgerEvaluationContext` | P0 | L |
| PV11-011 | Add the shared language/PV feature registry | P0 | M |
| PV11-012 | Pass the context through Java CEK and Truffle execution | P0 | M |
| PV11-013 | Validate the UPLC program version before evaluation | P0 | M |
| PV11-014 | Gate builtin availability from the shared registry | P0 | M |
| PV11-015 | Gate case-on-builtin constants at PV11 | P0 | S |
| PV11-016 | Enable UPLC 1.1 `Constr`/`Case` in V1/V2 at PV11 | P0 | M |
| PV11-017 | Enforce PV11 deserialization bounds | P0 | M |

The provider must evaluate a `Program`, not discard its version and evaluate
only its `Term`.

### Phase 2 — Semantics and cost fidelity

| ID | Work | Priority | Effort |
|---|---|---:|---:|
| PV11-020 | Implement builtin semantics variant D | P0 | L |
| PV11-021 | Implement builtin semantics variant E | P0 | L |
| PV11-022 | Implement exact V1 PV11 332-parameter parsing | P0 | L |
| PV11-023 | Implement exact V2 PV11 332-parameter parsing | P0 | L |
| PV11-024 | Validate V3 350-parameter parsing against reference names/budgets | P0 | M |
| PV11-025 | Make string/integer/ByteString memory sizing variant-correct | P0 | M |

Variants D and E should share common bounded primitives where possible while
retaining the required `ConsByteString` difference.

### Phase 3 — Conformance and differential validation

| ID | Work | Priority | Effort |
|---|---|---:|---:|
| PV11-030 | Add V1/V2/V3 × PV10/PV11 availability tests | P0 | M |
| PV11-031 | Add UPLC 1.0/1.1 positive and negative matrix tests | P0 | M |
| PV11-032 | Add semantics D/E boundary and oversized-value tests | P0 | M |
| PV11-033 | Add PV11 decode-bound tests | P0 | S |
| PV11-034 | Add exact-budget golden tests against the Plutus reference | P0 | L |
| PV11-035 | Pin/import the conformance corpus used by the supported node release | P1 | M |
| PV11-036 | Verify Scalus PV11 paths or exclude Scalus from the readiness claim | P1 | M |

Every gate requires both rejection-below-boundary and
acceptance-at-boundary coverage. Testing only the successful PV11 path is
insufficient.

### Phase 4 — Compiler target and diagnostics

| ID | Work | Priority | Effort |
|---|---|---:|---:|
| PV11-040 | Add protocol version to compiler target/configuration | P0 | M |
| PV11-041 | Reject unavailable and future builtins at compile time | P0 | M |
| PV11-042 | Add stdlib feature metadata and actionable diagnostics | P1 | M |
| PV11-043 | Record target profile in compile result/build metadata | P1 | S |

The first supported compiler profile may remain Plutus V3/PV11. Supporting
V1/V2 code generation is optional, but V1/V2 evaluation must still be correct
if the VM API claims to support those languages.

### Phase 5 — PV11 performance enhancements

#### PV11-050: Lower list `drop` to `DropList` — P1 / S

Current `JulcList.drop(n)` generates a recursive `TailList` loop even though
the direct builtin is registered. For a PV11 target it should lower directly
to `DropList`.

Acceptance:

- same results and failures as the reference builtin;
- smaller generated UPLC;
- lower budget for representative `n`;
- compiler continues using the legacy lowering for targets where the builtin
  is unavailable, if such targets remain supported.

#### PV11-051: Case-on-builtin lowering — P1 / L

Evaluate cost-directed lowerings for:

- Java boolean conditionals to `Case Bool`;
- list pattern matching/loops to `Case List`, binding head and tail in one
  operation;
- integer switches to `Case Integer`;
- native pair destructuring to `Case Pair`.

This can remove forced chooser builtins and repeated
`NullList`/`HeadList`/`TailList` or equality dispatch. It must be benchmarked
against the target PV11 costs rather than assumed cheaper.

#### PV11-052: Typed native Value API and lowering — P1 / M

Introduce a dedicated `JulcValue` type instead of representing both `Data` and
native `Value` as `PlutusData`.

Desired flow:

```text
Data-encoded Value
    -> UnValueData once
    -> native lookup/union/contains/scale operations
    -> ValueData only at an external Data boundary
```

This prevents accidental type mixing and enables the compiler to eliminate
repeated nested-map scans and redundant conversions.

#### PV11-053: Cost-directed list-to-array promotion — P2 / L

Detect multiple indexed accesses to the same immutable list:

```text
list traversal per access
    -> ListToArray once
    -> IndexArray for subsequent accesses
```

Because `ListToArray` is linear while `IndexArray` is constant-time, promotion
must use cost-model-derived break-even thresholds and escape/use-count
analysis. Do not rewrite single or cheap sequential accesses blindly.

#### PV11-054: BLS MSM fusion and typed API — P2 / M

Recognize or expose a typed representation of:

```text
s1 * P1 + s2 * P2 + ... + sn * Pn
```

and lower it to the G1/G2 multi-scalar multiplication builtin when list
length/failure semantics match. Replace the current `PlutusData`-shaped list
surface with typed lists where compiler support permits.

#### PV11-055: `ExpModInteger` lowering and constant folding — P2 / M

- Provide a target-aware mapping for modular-exponentiation idioms.
- Constant-fold calls with literal arguments when doing so preserves failure
  semantics.
- Do not rewrite ordinary exponentiation without an explicit modulus.

#### PV11-056: Target-aware Value/Array constant folding — P2 / M

Fold pure literal operations such as length/index/drop/value lookup where the
result or failure is statically known and legal for the target profile.

---

## Acceptance criteria for “PV11 ledger ready”

JuLC may claim PV11 ledger-conformant evaluation only when all of the following
hold:

### Builtin and feature gates

- [ ] Tags 87–100 fail below PV11 and succeed at PV11 where valid.
- [ ] Tag 101 fails for PV11 in V1, V2, and V3.
- [ ] All released builtins are accepted in V1/V2/V3 at PV11.
- [ ] Case-on-builtin constants fails below PV11 and succeeds at PV11.
- [ ] UPLC 1.1 fails for V1/V2 below PV11 and succeeds for V1/V2 at PV11.
- [ ] UPLC 1.0 programs remain backwards compatible.

### Semantics and decoding

- [ ] V1/V2 PV11 select semantics variant D.
- [ ] V3 PV11 selects semantics variant E.
- [ ] Variant-D and variant-E `ConsByteString` behavior matches Plutus.
- [ ] Cardano integer and ByteString bounds match Plutus success/failure.
- [ ] Non-ASCII string costing matches UTF-8-byte-based reference budgets.
- [ ] Constant-type headers beyond 32 and constructors beyond 1024 fields are
  rejected at the same validation phase as the ledger.

### Cost models

- [ ] V1 and V2 require and consume the expected 332 parameters.
- [ ] V3 requires and consumes the expected 350 parameters.
- [ ] `Constr`/`Case` costs are non-zero and loaded for V1/V2 PV11.
- [ ] Representative programs for every Batch 6 builtin match reference CPU and
  memory budgets exactly.
- [ ] Unknown/future schemas fail clearly instead of silently ignoring values.

### Backends and compiler

- [ ] Java passes the full PV11 protocol matrix.
- [ ] Truffle passes the same matrix and exact Java budget-parity tests.
- [ ] Scalus either passes the supported matrix or is documented and selected
  as non-PV11-conformant.
- [ ] The compiler rejects PV11-only features for a PV10 target.
- [ ] The compiler rejects `MultiIndexArray` for a PV11 target.
- [ ] Compiler output is validated using the same feature registry as the VM.

Passing result-only conformance tests without these boundary and budget tests
does not satisfy the readiness claim.

---

## Consequences

### Positive

- Eliminates false-positive local validation before transaction submission.
- Enables correct V1/V2 evaluation under PV11.
- Establishes one extensible model for future protocol upgrades.
- Prevents future-only builtins from leaking into current compiler targets.
- Creates the foundation for safe, cost-aware PV11 optimizations.
- Makes backend parity testable rather than implicit.

### Negative / cost

- Evaluation APIs will need a protocol-aware context or compatibility overload.
- Previously accepted but ledger-invalid programs will begin failing locally.
- V1/V2 cost parsing and semantics variants are substantial implementation work.
- Optimizer changes can alter script hashes and budgets; rollout may require an
  opt-in flag or compiler-version pinning.
- Existing users of the experimental `multiIndexArray` API need a migration
  warning and cannot deploy that operation to PV11.

### Risks and mitigations

| Risk | Mitigation |
|---|---|
| Duplicated feature tables drift | One shared registry consumed everywhere |
| Mutable provider state mixes network profiles | Immutable per-evaluation context |
| Reference behavior changes upstream | Pin supported node/Plutus release in tests |
| Optimization worsens cost for small inputs | Cost-model thresholds and A/B budget tests |
| Script hashes change unexpectedly | Version/flag optimizer changes and release-note them |
| Scalus support is assumed from dependency version | Explicit backend-specific PV11 suite |

---

## Non-goals

- Implementing any code as part of accepting this planning ADR.
- Treating unreleased Batch 7 builtins as PV11.
- Adding Plutus V4; PV11 introduces no new ledger-language version.
- Committing JuLC to V1/V2 compiler output. VM correctness and compiler targets
  are separate decisions.
- Modeling non-Plutus ledger/consensus features such as VRF uniqueness.
- Automatically applying optimizations before their budget and semantics
  evidence exists.

---

## Recommended delivery sequence

```text
Public contract corrections (Phase 0)
    -> shared EvaluationContext/feature registry (Phase 1)
    -> semantics + exact cost models (Phase 2)
    -> protocol/reference validation (Phase 3)
    -> compiler target enforcement (Phase 4)
    -> PV11 optimizer work (Phase 5)
```

The first implementation PR should cover Phase 0 plus the shared feature-table
tests. The first architectural PR should add the immutable evaluation context
without changing semantics. Gating, semantics variants, cost models, and
optimizations should then land in reviewable slices.

---

## References

### Cardano and Plutus

- [Cardano hard-fork history — van Rossem / PV11](https://cardano.org/hardforks/)
- [Plutus ledger language, UPLC version, builtin batch, and bounds table](https://plutus.cardano.intersectmbo.org/haddock/master/plutus-ledger-api/src/PlutusLedgerApi.Common.Versions.html)
- [Pinned Plutus source used by this audit](https://github.com/IntersectMBO/plutus/blob/fdbe32b20bd02a4f27a9654ecc3648a2c8fa2968/plutus-ledger-api/src/PlutusLedgerApi/Common/Versions.hs)
- [Plutus semantics-variant mapping](https://plutus.cardano.intersectmbo.org/haddock/master/plutus-ledger-api/src/PlutusLedgerApi.Common.ProtocolVersions.html)
- [Plutus V1 evaluation context](https://plutus.cardano.intersectmbo.org/haddock/master/plutus-ledger-api/src/PlutusLedgerApi.V1.EvaluationContext.html)
- [Plutus V2 evaluation context](https://plutus.cardano.intersectmbo.org/haddock/master/plutus-ledger-api/src/PlutusLedgerApi.V2.EvaluationContext.html)
- [Plutus V3 evaluation context](https://plutus.cardano.intersectmbo.org/haddock/master/plutus-ledger-api/src/PlutusLedgerApi.V3.EvaluationContext.html)
- [CIP-109: modular exponentiation](https://cips.cardano.org/cip/CIP-109)
- [CIP-132: `dropList`](https://cips.cardano.org/cip/CIP-132)
- [CIP-133: BLS12-381 MSM](https://cips.cardano.org/cip/CIP-133)
- [CIP-138: Array type and base operations](https://cips.cardano.org/cip/CIP-138)
- [CIP-153: MaryEraValue](https://cips.cardano.org/cip/CIP-153)
- [CIP-156: future `multiIndexArray`](https://cips.cardano.org/cip/CIP-156)

### JuLC implementation surfaces

- `julc-core/.../DefaultFun.java`
- `julc-core/.../DefaultUni.java`
- `julc-core/.../Constant.java`
- `julc-core/.../flat/UplcFlatDecoder.java`
- `julc-vm/.../PlutusLanguage.java`
- `julc-vm-java/.../JavaVmProvider.java`
- `julc-vm-java/.../CekMachine.java`
- `julc-vm-java/.../builtins/BuiltinTable.java`
- `julc-vm-java/.../cost/CostModelParser.java`
- `julc-vm-truffle/.../TruffleVmProvider.java`
- `julc-vm-scalus/.../ScalusVmProvider.java`
- `julc-compiler/.../pir/TypeMethodRegistry.java`
- `julc-compiler/.../uplc/UplcGenerator.java`
- `julc-stdlib/.../lib/NativeValueLib.java`
