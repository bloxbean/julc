# ADR-003: Milestone B — Useful Contract Verification

- **Status:** Implemented
- **Date:** 2026-08-12
- **Parent:**
  [ADR-001 — Verification Strategy for JuLC Using IOG Blaster](001-iog-blaster-verification-strategy.md)
- **Builds on:**
  [ADR-002 — Milestone A JuLC Blaster Compatibility PoC](002-milestone-a-blaster-poc.md)
- **Scope:** Release evidence for production-shaped spending and minting
  validators, including authorization, value/state preservation, and
  double-satisfaction defenses

## Context

Milestone A proved that exact JuLC-generated artifacts can be identity-checked,
imported into the pinned Blaster stack, and subjected to both successful and
falsified SMT queries. It also exposed two limits that shape Milestone B:

1. unbounded list traversal can exceed the practical symbolic-execution
   envelope; and
2. JuLC's generated record decoders currently accept unexpected constructor
   tags and trailing fields.

Milestone B is not a request to hide those limits behind larger fuel values.
It must demonstrate useful security claims over production-shaped contracts,
retain counterexamples as regressions, and make the exact verified domain
clear enough for a reviewer to decide whether the evidence is release-relevant.

ADR-001 requires two or three validators across at least two script purposes,
covering authorization, value/state preservation, and double satisfaction. It
also requires a CI suite without unpinned network dependencies.

## Decision

Milestone B will verify two bounded-shape validators and paired vulnerable
mutations:

1. **State-thread spending validator** — an owner-authorized state transition
   that preserves the locked value and commits its continuing state output to
   the exact consumed `TxOutRef`.
2. **Controlled minting validator** — a fixed-authority policy that binds the
   mint field to the redeemer's declared token and quantity. A separate
   kernel-checked lemma composes the exact singleton shape with ledger currency
   membership to identify the policy's own currency symbol.

The contracts intentionally use a bounded transaction-shape discipline: the
state-thread contract uses the first input/output and first signatory, while the
minting policy uses the first signatory and an exact singleton mint map. This
avoids claiming universal coverage for recursive list searches that Milestone A
could not establish. The restriction is part of the contract, property, and
manifest—not an unstated solver workaround.

Every security property is split into:

- an **artifact theorem/query**, showing what successful execution of the
  exact UPLC bytes implies; and
- where useful, a **kernel-checked composition lemma**, deriving a higher-level
  security consequence from the artifact implication.

Blaster results remain labeled `SMT-VALID`. Only ordinary Lean proofs that do
not invoke Blaster are labeled `KERNEL-PROVED`.

## Verification subjects

### State-thread spending validator

The datum contains:

- `owner : byte[]`; and
- `state : BigInteger`.

The redeemer contains `nextState : BigInteger`. The exact-artifact property
uses an explicit bounded ledger domain: the first input must be the current
spending input. Within that domain, successful execution requires:

1. the transaction has at least one signatory and the first signatory equals
   `owner`;
2. the transaction has at least one input and output;
3. the domain's first input is the current spending `TxOutRef`;
4. the first output value is Data-equal to the first input's resolved value;
5. the first output carries an inline datum encoding the current `TxOutRef`
   and `nextState`; and
6. `nextState` is strictly greater than the datum's current state.

The first-input rule is a verification/protocol domain restriction rather than
an artifact-enforced ordering check; the off-chain transaction builder must
place the state input first. The output-reference commitment is the
double-satisfaction defense. If two
distinct spending executions try to justify themselves with the same state
output, that output cannot be committed to both distinct input references.

The vulnerable mutation retains only the increasing-state check, omitting
authorization, value preservation, and the output-reference commitment.
Blaster must produce a source-linked counterexample showing success without a
committed successor output.

### Controlled minting validator

The fixed authority is a byte-string constant in the verification fixture.
The redeemer contains:

- `tokenName : byte[]`; and
- `quantity : BigInteger`.

Successful execution requires:

1. the transaction's first signatory is the fixed authority;
2. the script purpose is minting and supplies the current currency symbol;
3. `quantity` is exactly one; and
4. the transaction mint field is exactly one singleton policy/token map. Under
   CardanoLedgerApiBlaster's `validScriptInfo` ledger premise, the singleton
   policy is necessarily `ownCurrencySymbol`.

This exact-map rule prevents hidden extra assets or quantities from being
minted under the same transaction while satisfying the policy.

The vulnerable mutation checks the quantity and purpose but omits the fixed
authority. Blaster must produce a source-linked counterexample showing success
with a first signatory different from the authority.

## Property catalogue

| Property ID | Threat | Expected result |
|---|---|---|
| `state-thread.owner-authorization` | Unauthorized state transition | `ESTABLISHED (SMT-VALID)` |
| `state-thread.value-preservation` | Locked-value loss or substitution | `ESTABLISHED (SMT-VALID)` |
| `state-thread.output-ref-commitment` | One continuing output satisfying distinct inputs | `ESTABLISHED (SMT-VALID)` |
| `state-thread.double-satisfaction` | Shared successor state for distinct inputs | `ESTABLISHED (KERNEL-PROVED)` from the commitment theorem |
| `state-thread-broken.output-ref-commitment` | Missing input/output binding | `REFUTED` with counterexample |
| `controlled-mint.authority` | Unauthorized minting | `ESTABLISHED (SMT-VALID)` |
| `controlled-mint.exact-mint` | Extra token/quantity minting | `ESTABLISHED (SMT-VALID)` |
| `controlled-mint.own-policy-composition` | Singleton shape attributed to the wrong policy | `ESTABLISHED (KERNEL-PROVED)` under ledger currency membership |
| `controlled-mint-broken.authority` | Missing signer check | `REFUTED` with counterexample |

An `ESTABLISHED` artifact claim is acceptable only if its paired negative
control is also `REFUTED`. An unexpected valid negative control or an
unexpectedly falsified positive claim fails the suite.

## Property domain and assumptions

The quantified domain is the typed CardanoLedgerApiBlaster V3 `ScriptContext`.
Each property makes script-purpose and nonempty-list shape assumptions explicit
when those assumptions are needed to state the intended execution domain.

The suite does not claim:

- that every typed context satisfies all Cardano ledger rules;
- that the first-input/output discipline is appropriate for every protocol;
- termination for arbitrarily large recursive transaction collections;
- compiler-wide semantic preservation; or
- strict record constructor/arity validation until the Milestone A decoder
  finding is fixed.

The fixtures use only first-field access and exact constructor patterns in the
Lean predicates. Property names and documentation must not imply that the
current generated record decoder enforces strict CIP-57 schema shape.

## Counterexample regression contract

Counterexamples are evidence, not console decoration. Each vulnerable fixture
has a checked-in JSON regression file containing:

- property ID;
- source fixture and exact artifact identity;
- vulnerable source line or named omitted check;
- minimal semantic witness fields;
- expected result `REFUTED`; and
- the pinned solver/profile identity.

The Lean query remains the executable source of the counterexample. The JSON
fixture is a stable, reviewable abstraction of the witness and must be updated
only with the artifact lock. CI verifies that every expected refutation has a
matching regression entry.

## Artifact and manifest changes

The artifact lock advances to schema version 2 and includes `scriptPurpose` for
every fixture. Artifact preparation becomes data-driven from a checked-in
fixture catalogue rather than a hard-coded sequence of shell calls.

The run manifest records:

- the property catalogue and tri-state results;
- evidence type (`SMT-VALID`, `KERNEL-PROVED`, or `COUNTEREXAMPLE`);
- script purpose;
- threat class;
- explicit domain notes;
- counterexample regression path for refuted properties; and
- toolchain/dependency identities inherited from Milestone A.

The verification command exits successfully only when all required positive
properties and all required negative controls have their expected outcomes.
Any missing, indeterminate, stale, or unsupported result fails closed.

## CI and dependency policy

CI runs in two phases:

1. **Pinned acquisition:** install the exact Lean toolchain, exact Z3 archive
   with SHA-256 verification, and fetch the exact Lake manifest revisions.
2. **Offline evidence:** run Gradle with `--offline`, run Lake without updating
   dependencies, reproduce all artifact locks, execute the Lean suite, validate
   regression metadata, and emit the run manifest.

All GitHub Actions are pinned by commit SHA. Gradle uses the committed wrapper
and lockable repository inputs. Lake uses the committed manifest and exact Git
revisions. Z3 uses the versioned archive and committed checksum map. The
offline evidence phase must not call download helpers.

The generated run manifest is uploaded as a CI artifact. Committed UPLC and
counterexample regression files remain the review baseline.

## Implementation finding: typed ledger coercion ambiguity

The first controlled-mint artifact used a typed `MintingScript` accessor, and
the first state-thread artifact passed typed `Value` and `TxOutRef` records to
the generic `Builtins.equalsData(Object, Object)` overload. Blaster refuted both
intended positive properties. One witness compared only the first byte of the
policy identifier; another succeeded with different current/input references
and different input/output values.

The corrected fixtures make the representation boundary explicit. Value
preservation compares raw `PlutusData` values, and exact singleton mint shape
is composed with CardanoLedgerApiBlaster's currency-membership premise instead
of directly projecting the minting policy newtype. The strong security
properties are retained with these explicit domains and pass against the
corrected artifacts. This is release-relevant evidence that exact-artifact
verification can find source
expressions which compile but do not enforce the source author's apparent
intent. General typed-ledger coercion and accessor handling remains a
compiler-hardening item; Milestone B does not claim to fix it globally.

Two further iteration findings changed the executable design:

1. equality expressed as `BigInteger.compareTo(constant) == 0` for decoded
   constructor tags made the state-thread success path unsatisfiable, as caught
   by the mandatory non-vacuity query; and
2. Lake did not invalidate an `.olean` when only a `#import_uplc` hex file
   changed.

The final state fixture compares the complete expected output-datum structure
with `equalsData`, avoiding the problematic tag equality and stating the
commitment more directly. The verification driver invokes Lean directly for
every artifact-importing module and writes fresh `.olean` files, preventing a
cached proof from being replayed against changed UPLC bytes.

## Implementation result

The exit criteria are implemented by the property catalogue, paired correct
and broken fixtures, source-linked counterexample files, direct Lean build
driver, pinned acquisition script, offline evidence script, and SHA-pinned CI
workflow. Both correct fixtures have solver-produced successful witnesses;
their safety implications are SMT-valid. Both vulnerable mutations are
refuted with counterexamples. The two composition lemmas are ordinary Lean
proofs and are kernel-checked.

## Implementation sequence

1. Add the fixture catalogue and make artifact preparation data-driven.
2. Add the state-thread validator and its vulnerable mutation.
3. Establish authorization, value preservation, and output-reference
   commitment properties.
4. Add the kernel-checked double-satisfaction composition lemma.
5. Add the controlled-mint policy and its vulnerable mutation.
6. Establish authority and exact-mint properties.
7. Check in source-linked counterexample regressions and validate their
   artifact identities.
8. Replace Milestone A's provisional manifest assembly with a fail-closed
   property catalogue.
9. Add pinned acquisition and offline CI scripts/workflow.
10. Run tests, review property strength and vacuity, minimize witnesses where
    practical, and document all findings.

## Testing and review requirements

Milestone B is not complete until all of the following pass:

- Java/CLI unit tests, including artifact catalogue validation;
- clean artifact reproduction without `--update-lock`;
- builtin preflight for every fixture;
- Lean build with no project-owned `sorry` or admitted theorem;
- every positive property reports its expected established result;
- every negative control reports its expected refutation;
- counterexample regression metadata matches the locked artifact hashes;
- deliberate zero-fuel execution remains `COULD-NOT-EVALUATE`;
- the offline evidence script succeeds after acquisition; and
- `git diff --check` and a targeted human review find no overstated claims.

The upstream PlutusCore dependency currently contains a known `sorry` in its
budget-aware CEK termination proof. The suite must report this upstream trusted
base fact, while prohibiting new `sorry` declarations under
`verification/blaster/JulcVerification`.

## Exit criteria

Milestone B is complete when a clean Linux CI run, bound to exact artifacts and
pinned tools, demonstrates:

- two production-shaped validators across spending and minting purposes;
- accepted authorization, value/state preservation, and double-satisfaction
  property definitions;
- successful positive evidence and paired vulnerable counterexamples;
- source-linked counterexample regression fixtures;
- fail-closed machine-readable reporting; and
- an offline evidence phase with no unpinned network dependency.

If any positive property remains `COULD-NOT-EVALUATE`, Milestone B remains in
progress. Unlike Milestone A, a truthful indeterminate result is not sufficient
for this milestone's exit criterion.

## Consequences

### Positive

- Verification evidence covers concrete Cardano threat classes rather than
  only pipeline compatibility.
- Bounded transaction-shape contracts keep the claims solver-tractable and
  auditable.
- Paired mutations continuously test that the properties can detect the
  vulnerabilities they name.
- The double-satisfaction argument demonstrates composition between artifact
  evidence and kernel-checked reasoning.
- CI results are reproducible from exact dependency and artifact identities.

### Negative

- The bounded first-element discipline is restrictive and must be reflected in
  protocol design and off-chain transaction construction.
- Exact mint-map equality forbids batched minting and must be relaxed only with
  a correspondingly stronger property.
- Checked-in witness abstractions can drift unless artifact-identity validation
  remains mandatory.
- The result still trusts Blaster's SMT translation and Z3 for artifact
  theorems.
