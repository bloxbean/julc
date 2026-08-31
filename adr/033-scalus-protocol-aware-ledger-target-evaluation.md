# ADR-033: Scalus Protocol-Aware Ledger-Target Evaluation

**Status:** Proposed

**Date:** 2026-08-30

**Revised:** 2026-08-31 after review evidence audit

**Authors:** JuLC team

**Implementation issue:** [#74 — Implement and certify protocol-aware `LedgerEvaluationTarget` evaluation](https://github.com/bloxbean/julc/issues/74)

**Related roadmap:** [#65 — ADR-029 PV11 ledger-conformant evaluation readiness tracker](https://github.com/bloxbean/julc/issues/65)

**Release tracker:** [#121 — 0.1.0-pre17 plan](https://github.com/bloxbean/julc/issues/121)

**Governing decision:** [ADR-030 — Protocol Version Propagation and PV11 Builtin Semantics](030-protocol-version-propagation-and-pv11-builtin-semantics.md)

**Draft planning context:** [ADR-029 / PR #58 — Protocol Version 11 Ledger Readiness and Optimization Roadmap](https://github.com/bloxbean/julc/pull/58), which is not merged and is therefore informative rather than governing

**Normative compatibility baseline:** [cardano-node 11.0.1](https://github.com/IntersectMBO/cardano-node/releases/tag/11.0.1) / Plutus `1.63.0.0` at [`f92b7d7d82622a26caf456a6be33859f697e2cfc`](https://github.com/IntersectMBO/plutus/tree/f92b7d7d82622a26caf456a6be33859f697e2cfc)

**Scalus dependency under evaluation:** [`org.scalus:scalus_3:1.1.0`](https://github.com/nau/scalus/tree/v1.1.0)

---

## Context

ADR-030 made `LedgerEvaluationTarget` the canonical ledger-validation input.
The target binds the ledger language and protocol version used to select:

- builtin availability;
- the builtin-semantics variant;
- legal UPLC versions and term forms;
- protocol-dependent runtime behavior and bounds;
- the cost-model schema and supplied prices.

The Java and Truffle providers implement the explicit-target API and have a
pinned V3/PV10/C and V3/PV11/E conformance matrix. `ScalusVmProvider` does not
override the target-aware methods, so the default VM SPI implementation throws
`UnsupportedOperationException`. Scalus is therefore intentionally excluded
from ADR-030's ledger-parity claim.

This exclusion is correct. The ability to execute a builtin is not evidence
that a backend selected the right ledger profile or charged the exact reference
budget.

### Current Scalus provider behavior

The provider uses a FLAT serialization bridge:

```text
JuLC Program
    -> UplcFlatEncoder
    -> Scalus ProgramFlatCodec
    -> Scalus DeBruijnedProgram
    -> Scalus PlutusVM
    -> TermConverter
    -> JuLC EvalResult
```

Its current configuration consists of two independently published fields:

```java
volatile MachineParams machineParams;
volatile MajorProtocolVersion protocolVersion;
```

`setCostModelParams` stores the protocol version for every ledger language,
but constructs custom `MachineParams` only for V3. The language-only V3 path
uses both values when they are non-null. V1 and V2 continue to use Scalus's
no-argument factories and ignore the supplied parameter array.

The resulting behavior is:

| API path | Current behavior |
|---|---|
| Language-only V3 after matching configuration | Uses the configured V3 machine parameters and retained protocol major |
| Language-only V3 without configuration | Uses Scalus's bundled mainnet defaults |
| Language-only V1/V2 without configuration | Uses Scalus's bundled defaults |
| Language-only V1/V2 after configuration | Still uses bundled defaults; the supplied arrays are silently ignored |
| Explicit `LedgerEvaluationTarget` | Fails closed through the VM SPI default |
| Any path with a non-null `ExBudget` | Counts costs but does not enforce the limit |

In Scalus 1.1.0, the bundled `CardanoInfo.mainnet` snapshot is protocol 11.0.
Consequently, an unconfigured language-only V3 call selects PV11/E plus that
snapshot's mainnet cost model. This differs from Java and Truffle, whose
unconfigured language-only compatibility target is PV10. That divergence is
current behavior, not a cross-backend default contract.

The separate mutable fields also admit a torn configuration snapshot: one
evaluation can observe `MachineParams` from one update and a protocol version
from another. Preventing that specific invalid combination is required by
ADR-030 even though the broader thread-safety hardening tracked in issue #40
remains separate and non-blocking for this issue.

### Repository reality differs from the old adapter assumption

The current provider says that only V3 supports custom `MachineParams`. That
was an adapter assumption, not a limitation of the pinned Scalus 1.1.0 API.
Scalus 1.1.0 exposes all of the following:

```text
makePlutusV1VM(MachineParams, MajorProtocolVersion)
makePlutusV2VM(MachineParams, MajorProtocolVersion)
makePlutusV3VM(MachineParams, MajorProtocolVersion)

MachineParams.fromCostModels(CostModels, Language, MajorProtocolVersion)
```

The relevant pinned upstream implementations are
[`PlutusVM.scala`](https://github.com/nau/scalus/blob/v1.1.0/scalus-core/shared/src/main/scala/scalus/uplc/eval/PlutusVM.scala)
and
[`Cek.scala`](https://github.com/nau/scalus/blob/v1.1.0/scalus-core/shared/src/main/scala/scalus/uplc/eval/Cek.scala).

Its factory derives variants A/B/D for V1/V2 and C/E for V3 from the ledger
language and protocol major. It also enables case-on-builtin behavior at PV11.
Therefore V1/V2 custom configuration is technically reachable.

Reachability is not certification. There are two distinct fallback mechanisms
in Scalus 1.1.0:

- the V1/V2 parameter adapters do not carry the PV11-appended `Constr` and
  `Case` machine costs, so Scalus substitutes its reference machine costs;
- for any D/E profile, including V3/PV11/E, a
  `dropList-cpu-arguments-intercept` value of `300_000_000` is treated as a
  missing-parameter sentinel and causes the new PV11 builtin costs to be
  replaced with Scalus's vendored D/E reference costs.

The second mechanism does not replace machine costs and normally does not fire
for a complete V3/PV11 model. It is nevertheless ambiguous if a caller
legitimately supplies `300_000_000` at that position. Before JuLC claims a
supplied model is in use, it must prevent either mechanism from silently
substituting a different cost.

### Existing verification gap

`julc-vm-scalus` has useful unit tests, but its `PlutusConformanceTest` is
disabled. The disabled runner also:

- treats the corpus effectively as one language-only V3 profile;
- skips Array and Value cases;
- skips BLS-related cases;
- does not assert exact CPU and memory budgets;
- does not assert the expected corpus and overlay counts.

By contrast, Java and Truffle run a pinned profile matrix with 999 V3/PV11
cases, 724 numeric PV11 budget assertions, 737 applicable V3/PV10 cases, 545
numeric PV10 budget assertions, and an asserted 65-file PV11 overlay. The
Scalus support claim must be based on an equally reviewable inventory, not a
disabled or open-ended skip list.

### Review evidence audited on 2026-08-31

The Java conformance class was rerun from this branch and passed its complete
asserted V3/PV10 and V3/PV11 matrices. This confirms that the reference corpus
and JuLC representation are not the source of the Scalus coverage gap.

An exploratory Scalus 1.1.0 run supplied during ADR review removed the disabled
annotation and existing skip lists. It reported 999 cases, 174 failures, and
zero skips. A separate PV11 budget probe reported exact CPU and memory for all
572 bridge-representable numeric cases it reached. These are useful triage
results, but they were produced by temporary test edits; the implementation
must reproduce and assert the final counts in committed tests.

The failures divide into materially different categories:

- BLS G1, G2, and MlResult literal constants cannot cross Scalus's FLAT codec.
  This is not merely a Scalus omission. The pinned Plutus `Flat` instances also
  deliberately reject encoding and decoding these values; ledger scripts must
  carry compressed ByteStrings and call the uncompress builtins. A direct AST
  bridge would bypass that ledger serialization rule and is not required for a
  ledger-target claim. See the pinned
  [`G1`](https://github.com/IntersectMBO/plutus/blob/f92b7d7d82622a26caf456a6be33859f697e2cfc/plutus-core/plutus-core/src/PlutusCore/Crypto/BLS12_381/G1.hs),
  [`G2`](https://github.com/IntersectMBO/plutus/blob/f92b7d7d82622a26caf456a6be33859f697e2cfc/plutus-core/plutus-core/src/PlutusCore/Crypto/BLS12_381/G2.hs),
  and
  [`MlResult`](https://github.com/IntersectMBO/plutus/blob/f92b7d7d82622a26caf456a6be33859f697e2cfc/plutus-core/plutus-core/src/PlutusCore/Crypto/BLS12_381/Pairing.hs)
  instances.
- Array and Value constants do cross the Scalus input bridge. Their observed
  failures are missing Scalus-to-JuLC result cases in `TermConverter` and are
  adapter work for this issue.
- Four shift/rotate result differences came from running Scalus's unconfigured
  PV11/E default against PV10 base expectations. They confirm the legacy
  default divergence above, rather than a PV11 semantic failure.
- The G1 and G2 `hashToGroup/hash-dst-len-255` cases had exact budgets but
  different results. This is a potential semantic blocker. It must be
  reproduced with ledger-serializable programs that compress or compare the
  internally produced point; a persistent mismatch blocks the affected target
  instead of changing the pinned golden result.

## Problem statement

The current Scalus adapter cannot establish this invariant:

```text
requested ledger target
    == validated feature profile
    == Scalus language and protocol
    == builtin-semantics variant
    == configured cost-model target
    == exact budget profile
```

Adding overloads that merely pass a protocol number to Scalus would leave
several false-positive and false-negative risks:

1. a program could reach Scalus without JuLC's canonical builtin and UPLC
   validation;
2. a PV11 machine could be paired with PV10 prices, or the reverse;
3. a configuration update could publish costs and protocol separately;
4. Scalus defaults or fallback costs could be mistaken for caller-supplied
   costs;
5. FLAT/result-conversion limitations could remain hidden behind skipped
   conformance cases;
6. a non-null execution budget could be silently ignored;
7. future protocol versions could inherit the newest behavior understood by
   Scalus even though JuLC has not certified them.

## Goals

1. Implement the canonical explicit-target `evaluate` and
   `evaluateWithArgs` paths for certified Scalus profiles.
2. Bind one requested target to JuLC validation, Scalus semantics, and one
   cost model for the entire evaluation.
3. Certify V3/PV10/C and V3/PV11/E against the pinned node/Plutus baseline
   only if every applicable gate passes; otherwise retain fail-closed behavior
   and record the blocking evidence.
4. Use a matching caller-supplied V3 model when one is configured.
5. Reject configured-target mismatches before serialization or CEK execution.
6. Publish configuration atomically as immutable per-language records.
7. Preserve the existing configured language-only V3 behavior.
8. Replace the disabled/skip-heavy Scalus conformance test with an explicit
   ledger-serializable matrix, exact budget assertions, and reason-coded
   classification of non-serializable corpus fixtures.
9. Define a fail-closed, evidence-based path for later V1/V2 certification.
10. Enforce a caller-supplied execution budget and report budget exhaustion as
    `EvalResult.BudgetExhausted`.

## Non-goals

- Changing JuLC compiler targets or generated UPLC.
- Changing ledger language, datum, redeemer, or `ScriptContext` encoding.
- Upgrading Scalus for reasons unrelated to a reproduced certification
  blocker. If a reproduced upstream semantic defect blocks certification, a
  minimal newer pinned release may be proposed, but its dependency change and
  complete matrix require review before this ADR can be accepted as
  implemented.
- Claiming support for protocol versions newer than PV11.
- Certifying Plutus V4.
- Redesigning the shared VM SPI.
- Resolving all provider lifecycle and concurrency concerns from issue #40.
- Adding source maps, CEK step tracing, or builtin tracing to Scalus.
- Changing the general-evaluation API to enforce CIP-117 validator return
  values. The existing provider intentionally uses
  `evaluateDeBruijnedTerm`.
- Starting or mutating an external Yaci DevKit network. The required evidence
  is evaluator/conformance evidence, not chain submission.

## Invariants

### I1. One target per evaluation

Language, protocol semantics, feature validation, machine parameters, and
costs come from one explicit `LedgerEvaluationTarget`. No component may infer
the target from a cost-array length, a program version, or Scalus defaults.

### I2. Fail before execution on a mismatch

If the configuration selected for the requested language contains a different
language, or its target has a different protocol major, evaluation returns a
deterministic failure with zero consumed budget before FLAT encoding, argument
conversion, or CEK execution. Per-language slots normally make a language
mismatch impossible; retaining the check protects the record invariant.

Protocol minor is retained as provenance. As specified by ADR-030,
`hasSamePlutusSemantics` compares ledger language and protocol major because
the pinned semantics table changes at major-version boundaries.

### I3. Validate through JuLC's canonical registry

Every explicit-target call resolves `ProtocolFeatureRegistry` and runs
`ProgramValidator` before crossing the Scalus bridge. Scalus capability does
not override JuLC's supported-protocol boundary.

### I4. No mixed configuration snapshots

An evaluation snapshots one immutable configuration record once. It must not
read machine parameters and protocol version from independently mutable
fields.

### I5. Supplied means supplied

When documentation or a test says a caller-supplied model is used, all costs
available to that certified profile must come from the normalized supplied
array. Silent replacement with a Scalus bundled/reference cost is not allowed
under that claim.

### I6. Exact budgets are part of correctness

For every certified profile, successful and failing reference vectors must
match the pinned evaluator's CPU and memory budgets exactly. Positive or
approximately equal budgets are insufficient.

### I7. No accidental conformance skips

Every pinned vector is classified as applicable, inapplicable to the profile,
or non-ledger-serializable under the pinned Plutus FLAT rules. The runner
asserts category counts. Adding an unclassified file fails the suite.

Non-serializable BLS literal fixtures do not excuse missing BLS builtin
coverage. The corresponding builtins must be exercised through compressed
ByteStrings and intermediate G1/G2/MlResult values, with a ledger-serializable
final result such as ByteString, Bool, or Unit.

### I8. Legacy V3 remains compatible

After `setCostModelParams(values, PLUTUS_V3, major, minor)`, the language-only
V3 API continues to use the configured parameters and corresponding protocol
major. Without configuration, it preserves the existing Scalus default path.

The preserved unconfigured default is explicitly Scalus-specific and currently
means PV11/E, unlike the Java/Truffle PV10 compatibility default. It is not a
ledger-parity claim and must not be used to choose semantics for the canonical
explicit-target path.

### I9. Budget limits are not advisory

A non-null `ExBudget` must restrict execution. Exhaustion is distinguished
from ordinary evaluation failure and includes the budget consumed through the
failing charge.

### I10. Unsupported profiles fail closed

No profile is enabled because an upstream factory exists. It is enabled only
after the profile's feature, result, failure, and exact-budget gates pass.

## Decision

### D1. Initial candidate support matrix

Issue #74 will establish the following initial explicit-target matrix:

| Ledger target | Initial status | Required semantics | Cost source |
|---|---|---|---|
| V3/PV10 | Candidate; enable only after D11 passes | C | Matching configured model; bundled-default mode is a separate evidence gate |
| V3/PV11 | Candidate; enable only after D11 passes | E | Matching configured model; bundled-default mode is a separate evidence gate |
| V1/PV10 | Explicitly unsupported initially | B | Not claimed |
| V1/PV11 | Explicitly unsupported initially | D | Not claimed |
| V2/PV10 | Explicitly unsupported initially | B | Not claimed |
| V2/PV11 | Explicitly unsupported initially | D | Not claimed |
| Any target above PV11 | Unsupported | Unknown to JuLC | Rejected by `ProtocolFeatureRegistry` |
| Other language/protocol pairs | Unsupported by this ADR | Not certified here | Rejected by the Scalus support gate |

V1/V2 are excluded initially because the existing Scalus suite contains no
equivalent pinned profile corpus and the supplied-cost path needs proof that
PV11 values are not replaced by adapter fallback costs. Their upstream
factories make a future extension plausible, but not implicit.

The target rows are all-or-nothing ledger claims. A reproducible semantic
mismatch in an available builtin leaves the target unsupported; JuLC will not
market a target as certified while silently blacklisting that builtin. Corpus
fixtures containing BLS literal values are classified separately because such
values cannot occur in a serialized ledger script, but the BLS builtins remain
inside the target claim and require ledger-serializable coverage.

Promoting V1 or V2 requires all gates in D11 and an update to this table,
ADR-030's backend support statement, and backend Javadocs. It must not happen
as an incidental side effect of the common implementation.

### D2. Introduce one immutable Scalus configuration value per language

Replace the two V3-oriented fields with an immutable per-language
configuration state:

```java
sealed interface ScalusConfiguration {}

record ReadyScalusConfiguration(
    LedgerEvaluationTarget target,
    ProtocolFeatureProfile profile,
    Language scalusLanguage,
    MajorProtocolVersion scalusProtocol,
    MachineParams machineParams
) implements ScalusConfiguration {}

record UnsupportedScalusConfiguration(
    LedgerEvaluationTarget target,
    ProtocolFeatureProfile profile,
    String reason
) implements ScalusConfiguration {}
```

The provider holds one `volatile ScalusConfiguration` slot for V1, V2, and V3.
Construction happens entirely in local variables. A ready state is assigned
only after target resolution, parameter normalization, Scalus model
construction, and consistency checks all succeed.

Configuration for a profile that this adapter cannot faithfully honor is
recorded as unsupported instead of being silently ignored. This is important
for the language-only consumer path: `JulcTransactionEvaluator` configures
V1/V2/V3 before it knows which redeemer language will execute. Recording an
unsupported V1/V2 state allows a V3 transaction to proceed while making a
later V1/V2 evaluation fail closed rather than use unrelated bundled prices.

An evaluation reads the relevant slot exactly once and passes that local
snapshot through the remaining call chain. It either uses a complete ready
state or rejects the unsupported state. This removes torn target/model reads.
It does not declare the provider fully thread-safe or close issue #40.

### D3. Map targets explicitly and verify the mapping

The adapter maps JuLC languages without ordinals or fall-through behavior:

| JuLC | Scalus |
|---|---|
| `PLUTUS_V1` | `Language.PlutusV1` |
| `PLUTUS_V2` | `Language.PlutusV2` |
| `PLUTUS_V3` | `Language.PlutusV3` |

The protocol major maps directly to
`new MajorProtocolVersion(target.protocolVersion().major())`. PV11 must never
be collapsed to Scalus's PV10/Plomin value.

For a certified configured profile, VM construction uses the language-specific
two-argument factory:

```text
makePlutusV3VM(machineParams, majorProtocolVersion)
```

Tests inspect the constructed VM's language, protocol, and
`semanticVariant()` so a mapping error fails independently of behavioral
vectors.

### D4. Resolve and validate before the bridge

Both target-aware overloads delegate to one internal pipeline:

```text
target
  -> ProtocolFeatureRegistry.resolve(target)
  -> Scalus support-matrix gate
  -> ProgramValidator.validate(program, profile)
  -> reject constants that the pinned ledger FLAT format cannot deserialize
  -> snapshot matching configuration
  -> reject semantic mismatch
  -> select enabled bundled default or configured MachineParams
  -> FLAT bridge / Data argument application
  -> construct target-bound PlutusVM
  -> evaluate with counting or restricting spender
  -> convert result
```

Argument application occurs after validation and target selection. The plain
and argument-bearing overloads therefore cannot drift in target handling.
Arguments remain applied as Scalus `Data` constants to preserve the existing
avoidance of the 64-byte ByteString limitation in the CBOR route.

The FLAT bridge remains the program boundary because it exercises the same
serializability constraint as a ledger script. In particular, a direct
JuLC-term-to-Scalus-term bridge will not be introduced merely to inject BLS
literal constants that the pinned Plutus decoder itself rejects. The adapter
will report those constants as non-ledger-serializable before invoking Scalus.

### D5. Keep target selection independent from cost parsing

`setCostModelParams(values, target)` first resolves the target through the
canonical registry. The target selects the expected schema; array length never
selects protocol semantics.

For the initial V3 candidate profiles, the active target schemas are:

| Target | Active serialized parameter count |
|---|---:|
| V3/PV10 | 297 |
| V3/PV11 | 350 |

The adapter must reproduce ADR-030's pinned `tagWithParamNames` behavior where
the SPI permits short or excess arrays: missing values are `Long.MAX_VALUE`
padded and excess values are truncated with an explicit warning. It must not
allow Scalus's internal placeholder/reference fallback to silently change the
meaning of a caller-supplied model.

For V3/PV11 specifically, a normalized supplied value of `300_000_000` for
`dropList-cpu-arguments-intercept` is rejected before
`MachineParams.fromCostModels`. Scalus 1.1.0 treats that otherwise valid value
as its missing-parameter sentinel and substitutes vendored reference prices
for all new PV11 builtins. Rejecting the ambiguous input is the only way this
adapter can preserve I5 without reconstructing Scalus's builtin cost model
independently. A focused test must use exactly that value and prove that
configuration fails without replacing a previously published ready state.
ADR-030's `Long.MAX_VALUE` padding for a short array does not equal this
sentinel and therefore must remain intact rather than being rewritten to
`300_000_000`.

For V1/V2/PV11, Scalus's parameter types themselves do not carry all supplied
PV11 machine and builtin values. Those profiles therefore produce an
`UnsupportedScalusConfiguration` in the initial implementation; JuLC does not
publish the partially substituted `MachineParams` as ready.

Before publication, the implementation constructs `CostModels` under the
selected Scalus language id and calls
`MachineParams.fromCostModels(costModels, scalusLanguage, scalusProtocol)`.
Focused perturbation tests then prove that a changed supplied parameter changes
the cost of its corresponding operation and no unrelated target is affected.

If exact normalization cannot be implemented without duplicating the shared
cost-model contract, the backend-neutral normalization step will be extracted
into `julc-vm` and reused by Java and Scalus. A second independent definition
of pinned parameter tagging is not acceptable.

### D6. Define configured and default explicit-target behavior

For a certified explicit target:

1. If the language's configuration slot is non-null and has the same Plutus
   semantics as the request and is ready, use that exact record.
2. If the slot is non-null but targets a different protocol major, fail with
   zero consumed budget.
3. If the slot is unsupported, fail with its stable reason and zero consumed
   budget.
4. If the slot is null, use Scalus 1.1.0's single bundled
   `CardanoInfo.mainnet` cost-model map, interpreted with the requested
   protocol semantics, only if the separate bundled-default matrix has passed
   for that target.
5. If that bundled-default matrix has not passed, fail with an instruction to
   configure a matching cost model.
6. Never fall back from an unsupported profile to the newest certified one.

`MachineParams.defaultParamsFor(language, protocol)` is target-specific in
semantics selection, not in cost provenance: Scalus 1.1.0 reads one bundled
mainnet parameter snapshot and interprets it for the requested language and
major. The no-configuration path is deterministic only while the dependency
version remains pinned. It is not a claim that the provider loaded the current
network's governance parameters.

Certification therefore has two explicit cost-source runs:

1. **Supplied-profile run:** configure the pinned array for the target and run
   the entire applicable result/failure/budget matrix. This run is mandatory
   for target support.
2. **Bundled-default run:** run the same matrix without configuration and
   assert exact equality with the pinned expectations. This independently
   decides whether unconfigured explicit evaluation is enabled for that
   target. Equality of a subset is not enough.

Callers performing live ledger validation should configure the cost model
obtained with the transaction's protocol parameters even when a bundled-default
run happens to pass.

### D7. Preserve and isolate the legacy language-only API

The language-only API remains a compatibility surface with deliberately
specified behavior:

| Language-only path | Decision |
|---|---|
| Configured V3 with a ready state | Use the retained V3 target and supplied model |
| Configured V3 with an unsupported state | Fail with zero budget; never use bundled defaults |
| Unconfigured V3 | Preserve Scalus's no-argument factory; in 1.1.0 this is PV11/E plus the bundled epoch-645 mainnet model |
| Configured V1/V2 while those profiles are uncertified | Record unsupported configuration and fail evaluation with zero budget |
| Unconfigured V1/V2 | Preserve the existing no-argument factories for experimental compatibility; do not describe them as configured or ledger-certified |

`setCostModelParams` for an uncertified V1/V2 profile does not throw
immediately because `JulcTransactionEvaluator` configures every language before
resolving the redeemer's actual language. Publishing an unsupported state lets
unrelated V3 scripts continue, while a V1/V2 script fails when selected instead
of silently using bundled costs.

Configuring V1/V2 must no longer mutate protocol state later observed by a V3
evaluation. Language-only calls do not expand the explicit certified matrix.
The unconfigured Scalus V3 default intentionally remains different from the
Java/Truffle PV10 compatibility default to avoid an unrelated behavior change;
the divergence is now explicit and tested. New ledger-validation integrations
should migrate to the explicit-target API.

### D8. Enforce execution budgets

When `ExBudget` is null, evaluation uses Scalus `CountingBudgetSpender`. When
it is non-null, evaluation uses `RestrictingBudgetSpender` initialized with an
explicit CPU/steps and memory mapping.

The adapter catches Scalus `OutOfExBudgetError` separately and returns
`EvalResult.BudgetExhausted`. Other Scalus and bridge errors remain
`EvalResult.Failure`. Tests pin the CPU/memory orientation because Scalus
`ExUnits` exposes memory and steps in an order that is easy to transpose at the
Java interop boundary.

This correction applies to both explicit and legacy paths so callers do not
receive backend-dependent budget-limit behavior.

This is observable in Cardano Client Lib: `JulcTransactionEvaluator` currently
uses the language-only overload with a non-null maximum transaction budget.
After this change, an over-budget Scalus evaluation returns
`BudgetExhausted`, which that consumer already handles distinctly from script
failure. The previous behavior could run beyond the supplied maximum.

### D9. Treat the bridge as part of the certified backend

The support matrix covers the entire JuLC-to-Scalus adapter, not only the
upstream CEK machine. `UplcFlatEncoder`, Scalus FLAT decoding, `DataConverter`,
and `TermConverter` are therefore in scope for conformance.

Array and Value are representable and serializable, and the audited failures
are missing `TermConverter` cases. Those conversions must be implemented and
their tests must run.

BLS requires a different classification. G1, G2, and MlResult types and their
builtins are legal, but literal values of those types are deliberately not FLAT
serializable in the pinned Plutus implementation. Corpus fixtures that inject
such literal values are classified as `NON_LEDGER_SERIALIZABLE_BLS_LITERAL`,
not as Scalus passes or generic skips. The classification requires:

- a named reason code;
- an asserted fixed count;
- a check that the fixture actually contains a non-serializable BLS literal;
- replacement coverage for the same builtin family using compressed
  ByteStrings, uncompress/hash operations, intermediate BLS values, and a
  serializable final result;
- exact result and budget comparison for that replacement coverage.

This classification does not remove BLS builtins from a certified target. A
semantic mismatch in a serializable BLS program, including the observed
255-byte DST `hashToGroup` cases if reproduced after compression, blocks target
certification.

Negative constructor tags rejected by `DataConverter` remain a documented
bridge constraint unless issue #74 changes the representation safely. Tests
must distinguish a bridge failure from a ledger semantic failure.

### D10. Keep errors deterministic and target-aware

Pre-execution failures use stable messages containing the requested target and
the rejected condition. At minimum, tests cover:

- unsupported Scalus profile;
- protocol newer than PV11;
- language unavailable at the requested protocol;
- configured/requested major mismatch;
- unavailable builtin;
- illegal UPLC version or `Constr`/`Case` form;
- invalid cost configuration.

These failures consume `ExBudget.ZERO`. Once CEK execution begins, failures
report Scalus's consumed budget and logs.

This ADR chooses `EvalResult.Failure` rather than throwing for an unsupported
Scalus target. Java and Truffle already convert registry/target validation
errors into zero-budget `Failure` results, and matching that behavior avoids a
provider-specific exception path. Scalus-specific unsupported-profile messages
use a stable `Unsupported Scalus ledger target:` prefix. The current
`EvalResult` type has no typed configuration-error variant, so callers must not
interpret every `Failure` as a CEK script failure; a future typed SPI result is
outside this issue.

### D11. Certification gates

A profile is added to the supported table only when all gates pass:

1. **Target mapping:** language, protocol major, semantics variant, and
   case-on-builtin behavior are asserted.
2. **Availability:** every builtin batch has acceptance-at-boundary and
   rejection-below-boundary coverage.
3. **UPLC legality:** applicable UPLC 1.0.0/1.1.0, `Constr`, and `Case` rules
   match `ProtocolFeatureRegistry`.
4. **Runtime semantics:** pinned C/D/E-sensitive integer, ByteString,
   division, BLS, Array, and Value behavior matches the reference for every
   ledger-serializable program. Non-serializable BLS literal fixtures have the
   specific classification and replacement coverage required by D9.
5. **Cost provenance:** the complete supplied-profile run passes; parameter
   perturbation proves the supplied path is active; V3/PV11 explicitly rejects
   the `300_000_000` new-builtin sentinel instead of allowing substitution.
6. **Exact budgets:** every numeric reference vector in the applicable
   supplied-profile matrix matches CPU and memory exactly. The complete
   bundled-default run is asserted separately and controls only whether
   unconfigured explicit evaluation is enabled.
7. **Failure budgets:** reference failure results and the budget consumed
   before failure match.
8. **Budget limits:** below, exactly-at, and above required budget tests return
   the correct `EvalResult` variant.
9. **Bridge coverage:** Array/Value conversions pass; every structural
   classification is reason-coded and count-asserted; there are no
   directory-name skips.
10. **Repeatability:** rerunning the suite from a clean checkout selects the
    same pinned corpus and produces the same counts.

Failure of any gate leaves that profile unsupported. A result-only pass cannot
substitute for budget evidence. If a failure is proven upstream, JuLC opens an
upstream issue with the minimal serializable reproducer and keeps the target
disabled. Resolution requires either an upstream fix in a reviewed pinned
release or an independently specified JuLC implementation; pinned goldens are
never changed to match the backend.

## Affected modules and stages

| Area | Planned effect |
|---|---|
| `julc-vm-scalus` provider | Immutable configuration, explicit-target overloads, target-bound VM construction, budget enforcement |
| `julc-vm-scalus` bridge | Complete Array/Value result conversion; reject non-serializable BLS literals; verify serializable BLS builtin paths |
| `julc-vm-scalus` tests | Replace disabled runner with asserted target-aware conformance and budget matrix |
| `julc-vm-scalus` test runtime | Add `testRuntimeOnly project(':julc-bls')` so textual BLS fixtures are validated consistently |
| `julc-vm` | Reuse registry/validator; extract backend-neutral cost-array normalization only if necessary |
| `julc-vm-java` | No semantic change; regression tests only if normalization is extracted |
| `julc-vm-truffle` | No semantic change; regression/cross-provider checks only |
| ADR-030 and issue #65 | Record the final certified Scalus matrix and remaining blockers; update draft ADR-029/PR #58 only if it is still active |
| Provider documentation | State explicit versus compatibility behavior and cost-model provenance |

No compiler lowering, AST representation, serialized program, or validator
argument interpretation is intended to change.

## Detailed implementation plan

### Milestone 1 — Freeze the current behavior and reference inventory

- Convert the existing explicit-target failure test into profile-specific
  expectations so the behavior change is deliberate.
- Record current language-only V1/V2/V3 behavior and configured V3 budgets.
- Port the Java/Truffle corpus inventory assertions to a Scalus test fixture.
- Re-audit every existing Scalus skip against version 1.1.0 and assign a reason
  code before changing the provider.
- Add `testRuntimeOnly project(':julc-bls')` to `julc-vm-scalus` so the
  ServiceLoader-based BLS constant validator is present while classifying the
  textual conformance fixtures.
- Add direct Scalus factory probes for language, protocol, semantics variant,
  and default machine parameters.
- Reproduce the review's exploratory failure and PV11 budget counts in a
  committed diagnostic test before relying on them as acceptance evidence.

### Milestone 2 — Atomic configuration and exact cost adaptation

- Add ready/unsupported `ScalusConfiguration` states and per-language volatile
  slots.
- Centralize explicit Java-to-Scalus language mapping.
- Normalize the target-selected parameter array according to ADR-030.
- Construct and publish a record only after all validation succeeds.
- Add V3/PV10 and V3/PV11 parameter-perturbation and mismatch tests.
- Reject the exact V3/PV11 `300_000_000` new-builtin sentinel and prove a
  failed update leaves the previous ready state intact.
- Add a regression proving V1/V2 configuration cannot alter a V3 snapshot.
- Add a Cardano Client Lib-shaped regression proving that configuring all
  three languages permits a ready V3 evaluation while configured V1/V2 fail
  explicitly until certified.

### Milestone 3 — One explicit-target evaluation pipeline

- Implement both explicit-target overloads.
- Resolve the profile and run `ProgramValidator` before FLAT serialization.
- Add the certified-profile gate.
- Share target selection and VM creation between plain and argument-bearing
  evaluation.
- Preserve the configured language-only V3 route through the same immutable
  configuration record.
- Add deterministic zero-budget failure tests for all pre-execution rejections.

### Milestone 4 — Budget enforcement and failure mapping

- Select counting versus restricting Scalus spenders from the supplied
  `ExBudget`.
- Map CPU to Scalus steps and memory to Scalus memory explicitly.
- Map out-of-budget errors to `EvalResult.BudgetExhausted`.
- Test below-limit, exact-limit, and above-limit execution on machine-step and
  builtin-heavy programs.
- Preserve partial consumed budgets and logs for non-budget CEK failures.

### Milestone 5 — Protocol behavior matrix

- Add V3/PV10/C and V3/PV11/E target-selection tests.
- Test Batch 6 rejection at V3/PV10 and acceptance at V3/PV11.
- Test `MultiIndexArray` rejection at PV11.
- Test UPLC 1.1.0 and `Constr`/`Case` legality at both targets.
- Test case-on-`Bool` branch order, laziness, and the below-boundary rejection.
- Test variant-sensitive integer/ByteString limits and division shapes.
- Add ledger-serializable BLS tests that create points from ByteStrings or
  `hashToGroup`, use them as intermediate values, and return compressed bytes,
  Bool, or Unit.
- Reproduce both 255-byte DST `hashToGroup` results in that serializable form;
  treat any pinned result mismatch as a certification blocker.
- Test plain and argument-bearing paths with the same table-driven cases.

### Milestone 6 — Bridge completion and pinned conformance

- Enable the Scalus conformance runner.
- Select V3/PV10 base expectations and the exact 65-file V3/PV11 overlay by
  explicit target.
- Assert corpus, applicable, numeric-budget, semantic-overlay, and exclusion
  counts.
- Remove directory-name skips, complete Array/Value result conversions, and
  classify only verified BLS-literal fixtures as
  `NON_LEDGER_SERIALIZABLE_BLS_LITERAL`.
- Compare every numeric CPU and memory expectation exactly.
- Run the complete matrix once with the pinned supplied profile and again with
  no configuration. The first controls target certification; the second
  controls bundled-default availability for that target.
- Investigate any difference at the first mismatching CEK category or builtin;
  do not update goldens to match Scalus.
- If a mismatch is upstream, keep the target disabled and file the minimal
  ledger-serializable reproducer upstream. A dependency bump or local semantic
  implementation requires separate review and a full matrix rerun.

### Milestone 7 — V1/V2 evidence audit

- Build focused V1/V2/PV10 and V1/V2/PV11 factory and supplied-cost probes.
- Verify whether every active 332-entry PV11 price reaches Scalus unchanged.
- Compare representative machine, legacy builtin, and Batch 6 budgets with the
  pinned Plutus evaluator.
- Keep the profiles rejected if upstream fallback or missing corpus evidence
  prevents an exact claim.
- If every D11 gate passes, update this ADR through review before enabling the
  profile.

### Milestone 8 — Integration and documentation

- Update `ScalusVmProvider` Javadocs with the certified matrix and compatibility
  behavior.
- Update ADR-030's Scalus exclusion with the exact resulting matrix.
- Update issues #65, #74, and #121 with commands, counts, and any blockers.
- Update draft ADR-029/PR #58 only if that planning document is still active or
  has merged; issue #65 is the authoritative tracker in the meantime.
- Run `JulcVm` and testkit explicit-target consumer tests.
- Run Cardano Client Lib tests for its actual language-only evaluator path:
  configured V3, configured-unsupported V1/V2, and non-null budget exhaustion.
  Its current explicit target is used for script decoding, not provider
  evaluation.

## Verification strategy

### Focused tests

At minimum, the implementation must add tests for:

- explicit V3/PV10 and V3/PV11 success;
- unsupported V1/V2 targets returning deterministic failures;
- target/configured-model mismatch before bridge work;
- protocol minor differences retaining the same major-version semantics;
- PV12 and later rejection;
- matching configured model use for plain and argument-bearing evaluation;
- custom parameter perturbation changing the expected budget component;
- failed configuration leaving the previous record intact;
- no cross-language configuration leakage;
- configured-unsupported V1/V2 behavior through the language-only consumer
  path;
- rejection of the exact V3/PV11 `300_000_000` fallback sentinel;
- non-null budget enforcement and CPU/memory orientation;
- Batch 6, UPLC 1.1.0, `Constr`/`Case`, case-on-builtin, and PV11 bounds;
- Array/Value result conversion;
- reason-coded rejection of BLS literal constants that the ledger cannot
  deserialize;
- ledger-serializable BLS builtin behavior, including both 255-byte DST
  `hashToGroup` cases.

### Pinned conformance

The Scalus runner will consume the same immutable corpus resources and profile
metadata used by Java and Truffle:

| Profile | Expected inventory before Scalus-specific structural classification |
|---|---:|
| V3/PV10/C | 737 applicable cases; 545 numeric budgets |
| V3/PV11/E | 999 applicable cases; 724 numeric budgets |
| PV11 overlay | 65 files: 61 budget changes and 4 result changes |

Those numbers are an input-integrity baseline, not permission to skip cases.
The final ADR-030 update must record the actual Scalus pass and structural
classification counts. Both the supplied-profile run and bundled-default run
report their own result, failure, and numeric-budget counts. Every exact-budget
assertion is against the pinned Plutus expectation. Java and Truffle results
are useful cross-checks but are not the normative source.

### Build sequence

Validation widens with impact:

```bash
./gradlew :julc-vm-scalus:test --rerun-tasks --no-daemon

./gradlew :julc-vm:test :julc-vm-java:test :julc-vm-truffle:test \
  :julc-vm-scalus:test --rerun-tasks --no-daemon

./gradlew :julc-testkit:test :julc-cardano-client-lib:test \
  --rerun-tasks --no-daemon

./gradlew build --no-daemon
```

If cost normalization moves into `julc-vm`, the Java and Truffle suites are
mandatory even if their provider source is otherwise unchanged.

## Compatibility

### Source and binary compatibility

No public signature changes are planned. The explicit-target methods already
exist in `JulcVmProvider`; Scalus will override them.

### Behavioral compatibility

The configured language-only V3 path remains supported. Unconfigured
language-only calls retain Scalus's current defaults, including the documented
Scalus 1.1.0 V3 PV11/E versus Java/Truffle PV10 divergence.

Intentional corrections are:

- explicit V3/PV10 and V3/PV11 no longer throw the SPI's unsupported-method
  exception once certified;
- target/model mismatches fail deterministically;
- V1/V2 configuration no longer leaks protocol state into V3;
- configured V1/V2 evaluation fails explicitly while those profiles are
  uncertified instead of silently ignoring the supplied model;
- a supplied budget is enforced instead of ignored.

These changes affect evaluation behavior, not compiled script bytes or hashes.

### Release compatibility

Scalus remains an experimental optional backend. Documentation must name the
exact certified profiles and pinned dependency/baseline. It must not generalize
V3/PV10 and V3/PV11 evidence to V1/V2, future protocols, or production safety.

## Alternatives considered

### Delegate directly to Scalus from the new overloads

Rejected. This bypasses JuLC's canonical feature registry, leaves configured
model mismatches possible, and turns upstream capabilities into an unsupported
ledger-parity claim.

### Infer the target from the cost-model length

Rejected by ADR-030. Cost schema and runtime semantics are related inputs, not
interchangeable selectors.

### Advertise every Scalus 1.1.0 factory immediately

Rejected. The V1/V2 factories prove API reachability, not exact supplied-cost
or conformance parity. Scalus's documented fallback behavior makes this
distinction material.

### Support only configured explicit calls

Rejected as an unconditional rule. It would make Scalus's canonical API
unexpectedly narrower than Java and Truffle. However, unconfigured explicit
evaluation is enabled per target only after the separate complete
bundled-default matrix passes; otherwise that target does require
configuration.

### Replace the FLAT bridge with direct AST conversion for BLS fixtures

Rejected for the ledger-target path. The pinned Plutus `Flat` instances
deliberately reject literal G1, G2, and MlResult values and require compressed
ByteStrings plus runtime uncompression. A direct bridge would admit programs
that cannot appear on the ledger and would turn a corpus-fixture concern into
a second program-conversion architecture. Serializable BLS builtin behavior is
tested without literal injection as specified by D9.

### Keep separate volatile machine-parameter and protocol fields

Rejected. Even if individual references are visible, their pair is not an
atomic evaluation configuration.

### Synchronize all provider evaluation

Rejected for this issue. Atomic immutable snapshots solve the configuration
invariant without serializing independent evaluations. Full thread-safety
analysis remains in issue #40.

### Preserve ignored execution budgets as an unrelated limitation

Rejected. A canonical ledger evaluation API must not accept a limit and then
silently execute beyond it when Scalus already provides a restricting spender.

### Keep the disabled conformance suite and add focused smoke tests

Rejected. Smoke tests cannot justify a profile-wide exact-budget claim, and
an open-ended skip list cannot detect newly added or accidentally omitted
vectors.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Scalus semantics selection drifts in a dependency update | Pin the exact reviewed release and assert VM language/protocol/variant plus the complete matrix before any upgrade |
| Caller-supplied costs are replaced by Scalus fallback values | Normalize explicitly, reject the V3 sentinel, publish V1/V2 as unsupported, and perturb individual parameters |
| CPU and memory are transposed at Scala/Java boundaries | Exact asymmetric budget vectors and restricting-budget tests |
| Bridge conversion is mistaken for CEK correctness | Classify bridge failures separately and test the entire adapter path |
| New corpus files are silently skipped | Assert complete inventory and fixed reason-coded classification counts |
| Configuration changes race with evaluation | Construct immutable records locally, publish one volatile reference, and snapshot once |
| Fixing Scalus accidentally changes Java/Truffle behavior | Keep changes module-local where possible and run cross-provider regression suites |
| Scalus defaults differ from the pinned evaluator | Certify each default profile exactly or fail closed for unconfigured explicit calls |
| A Scalus upgrade silently moves the legacy language-only default | Document the current PV11/E epoch-645 snapshot and pin a regression; do not treat legacy defaults as canonical semantics |
| An upstream builtin returns a non-reference result | Reproduce with a ledger-serializable program, file upstream, and keep the whole target disabled until reviewed resolution |
| V1/V2 become enabled accidentally through shared code | Enforce an explicit support table before VM construction |
| Future protocol versions inherit PV11 behavior | Resolve through JuLC's fail-closed registry before invoking Scalus |

## Consequences

### Positive

- Scalus can participate in canonical V3/PV10 and V3/PV11 evaluation if the
  evidence gates pass, with unsupported targets remaining explicit.
- Configuration cannot mix machine parameters and protocol semantics from
  different updates.
- Plain and argument-bearing evaluation share one target-selection path.
- Exact budget and bridge coverage become visible and reviewable.
- V1/V2 expansion has a concrete route without overstating current support.

### Negative

- The Scalus test suite becomes substantially larger and slower.
- Bridge gaps exposed by the pinned corpus may require more implementation than
  the two target-aware overloads alone suggest.
- Scalus may support upstream features that JuLC deliberately rejects until
  certification is complete.
- Enforcing `ExBudget` changes a previously ignored argument into observable
  behavior.
- Configured language-only V1/V2 evaluation changes from silently using
  unrelated defaults to an explicit failure until those profiles are
  certified.

## Acceptance criteria

- [ ] Explicit V3/PV10 and V3/PV11 calls select Scalus variants C and E,
      respectively.
- [ ] Both explicit overloads resolve and validate the same JuLC profile before
      the bridge.
- [ ] A matching configured V3 model is used by plain and argument-bearing
      evaluation.
- [ ] A configured/requested major mismatch fails deterministically with zero
      budget consumed.
- [ ] Configuration is atomically published as one immutable ready or
      unsupported per-language state.
- [ ] Existing configured language-only V3 tests remain green.
- [ ] V1/V2 explicit targets fail with a documented unsupported-profile result
      until separately certified.
- [ ] Configured language-only V1/V2 calls also fail explicitly, while their
      state does not prevent configured V3 evaluation in Cardano Client Lib.
- [ ] Unconfigured language-only V3 remains pinned to the documented Scalus
      1.1.0 PV11/E default and its divergence from Java/Truffle is tested.
- [ ] V3/PV11 configuration rejects the exact `300_000_000`
      `dropList-cpu-arguments-intercept` sentinel without replacing prior
      configuration.
- [ ] A non-null execution budget is enforced and exhaustion is reported as
      `EvalResult.BudgetExhausted`.
- [ ] PV-sensitive tests cover builtin availability, UPLC feature gating,
      case-on-builtin behavior, PV11 bounds, and Scalus variants C/E.
- [ ] Exact reference results and budgets pass for the complete supplied-model
      matrix of every certified target.
- [ ] The complete bundled-default matrix separately determines whether an
      unconfigured explicit target is enabled.
- [ ] The disabled Scalus conformance runner is replaced; all structural
      classifications have fixed reason codes and asserted counts.
- [ ] Array and Value result conversions pass, BLS literal fixtures are
      classified as non-ledger-serializable, and serializable BLS builtin
      coverage includes both 255-byte DST `hashToGroup` cases.
- [ ] Any negative-constructor or other bridge limitation is precisely
      documented and cannot be confused with a successful ledger-profile case.
- [ ] ADR-030, provider Javadocs, and issues #65/#74/#121 state the final
      verified scope; draft ADR-029/PR #58 is updated only if still applicable.
- [ ] Focused, affected-module, consumer, and full builds pass.

## Open evidence questions

These questions do not weaken the fail-closed design. They determine how much
work is required before a profile can pass the gates:

1. Does the complete bundled-mainnet default run match the pinned V3/PV10 and
   V3/PV11 matrices? The exploratory PV11 subset matched 572/572 reachable
   numeric budgets, but PV10 and the formerly blocked cases remain unproven.
2. Do the two 255-byte DST `hashToGroup` result differences persist when the
   point is consumed and compressed by a ledger-serializable program? If so,
   which upstream Scalus fix or independently reviewed implementation resolves
   them?
3. Can a later Scalus release consume every supplied V1/V2 PV11 machine and
   builtin cost without fallback? Scalus 1.1.0 cannot support that claim.
4. Does Scalus expose enough structured information on out-of-budget failure
   to preserve a useful failed term, or must Scalus initially return null for
   that optional `EvalResult` field?

Until the corresponding evidence is recorded, the conservative behavior is to
reject the affected profile or capability rather than infer parity.
