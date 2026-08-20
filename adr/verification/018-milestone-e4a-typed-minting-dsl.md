# ADR-018: Milestone E.4a Typed Minting Verification DSL

- **Status:** Implemented experimentally; pending manual review
- **Date:** 2026-08-20
- **Feature branch:** `feat/typed-verification-dsl-e4a-minting`
- **Parent:**
  [ADR-016 — Typed Verification DSL and Foundational Profile Catalog](016-typed-verification-dsl-and-profile-catalog.md)
- **Related:**
  [ADR-010 — Managed Verification Runner](010-milestone-c4-managed-verification-runner.md),
  [ADR-013 — Controlled Minting Profile](013-milestone-c7-controlled-minting-profile.md),
  [ADR-015 — Strict On-Chain Data Boundaries](015-strict-on-chain-data-boundaries.md),
  [ADR-017 — Purpose-Indexed Multi-Validator Blueprints](017-purpose-indexed-multivalidator-blueprints.md)

## Context

ADR-016 E.1 through E.3 established an experimental typed Java property DSL
for spending validators. The current implementation has several deliberate
limits:

- `DslPropertyValidator` accepts only a selected spending `ContractSchema`;
- `ContractMetamodelGenerator` generates only a spending model with a datum;
- `DslContractLoader` inspects a validator by exact title rather than selecting
  a purpose-indexed interface;
- the public symbolic types cover signatories, outputs, addresses, lovelace,
  datum primitive fields, Boolean composition, integer comparisons, and one
  output existential;
- `julc verify dsl` promotes only the reviewed seller-payment AST; and
- the schema-1 property AST is a closed inventory and cannot represent own
  policy identity, mint values, consumed inputs, or a minting ledger domain.

JuLC already has a separately implemented `@ControlledMint` annotation and
`julc.controlled-mint/v1` property. It checks a fixed authority, exact token
name and quantity, mint/burn direction, strict redeemer decoding, current-policy
linkage, and raw singleton asset shape. That profile provides useful minting
semantics and evidence, but it is not yet lowered through the DSL property AST.
Adding a second independent DSL-only Lean implementation of the same concepts
would create semantic drift.

The pinned `CardanoLedgerApiBlaster` V3 model contains the required minting
surface:

- `ScriptInfo.MintingScript` supplies the current currency symbol;
- `TxInfo.txInfoMint` is a raw association-list `Value`;
- `TxInfo.txInfoInputs` contains consumed `TxOutRef` values;
- `txSignedBy`, `utxoConsumed`, and `validMintingContext` are available; and
- `mintingInputs` applies exact UPLC only to minting contexts.

The representation is security-sensitive. A value is a raw list of policy
entries whose token values are raw maps. Lookup helpers can hide duplicate or
malformed entries. The existing C.7 profile intentionally checks the raw
association-list shape and permits entries for other policies. E.4a must
preserve those semantics rather than silently treating `Value` as a normalized
Java map.

The full pinned `validMintingContext` predicate also contains clauses that the
current Blaster translation cannot place directly in the main SMT premise,
including balance, voter-map, and treasury conditions. E.3 addressed the same
problem for spending by proving over a solver-compatible superset and
kernel-checking that every pinned ledger-valid context belongs to that
superset. E.4a must reuse that pattern deliberately.

## Problem statement

JuLC needs a typed minting property language that lets a Java developer state
contract-specific minting guarantees without writing Lean. It must:

1. select the exact minting interface and exact UPLC artifact;
2. expose a small reviewed minting vocabulary rather than raw Lean;
3. preserve raw value structure where uniqueness matters;
4. make ledger-domain assumptions explicit;
5. converge with the existing controlled-mint annotation semantics;
6. produce useful positive, refuted, malformed, and vacuous evidence; and
7. avoid any dependency or behavioral change in compiler lowering.

This milestone is a vertical slice, not a claim that every minting theorem or
every `CardanoLedgerApi` operation is supported.

## Goals

E.4a will:

- add a purpose-aware minting metamodel and exact artifact selection;
- extend the closed typed property IR with the minimum reviewed minting and
  consumed-input primitives described below;
- support both an unrestricted modeled-context theorem and an explicit pinned
  ledger-valid minting theorem;
- lower `@ControlledMint` and its equivalent DSL expression to one canonical
  semantic IR and one Lean semantics implementation;
- add a one-shot minting property that is not expressible by C.7;
- retain deterministic IR, Lean, manifest, plan, and certificate hashes;
- retain non-vacuity, admission, timeout, tamper, and unsupported-result gates;
- update the capability inventory only for operations actually covered by
  executable evidence; and
- keep verification source and dependencies outside compiler/core lowering.

## Non-goals

E.4a does not:

- stabilize the experimental DSL as a public API;
- add a new verification annotation;
- expose arbitrary Lean, arbitrary assumptions, arbitrary recursive
  predicates, or unpinned imports;
- provide general normalized or extensional `Value` equality;
- expose all token-map folds, sums, ranges, or multi-token policies;
- select authority from an untrusted redeemer and call that fixed authority;
- add typed validator-parameter expressions;
- cover rewarding, certifying, voting, or proposing;
- prove compiler semantic preservation;
- reconstruct Blaster/Z3 proofs in the Lean kernel;
- remove the trusted-source boundary of the Java property worker; or
- claim that a proved named minting property makes the whole policy safe.

Broader value operations, parameter roots, multi-token constraints, NFT
profiles, and threshold authorities remain later reviewed additions built on
the same IR.

## Invariants

The implementation must preserve all of the following.

### Exact artifact

- The theorem executes the `compiledCode` selected from the current
  purpose-indexed blueprint entry.
- Compiled bytes and Cardano script hash must match an observational compiler
  result before the worker runs.
- A multi-validator minting interface must remain bound to the same shared
  program and script hash as its other interfaces.
- Neither generated Java names nor worker output may choose the artifact.

### Zero on-chain effect

- Adding, removing, or changing a DSL specification must produce byte-identical
  validator UPLC.
- Migrating `@ControlledMint` to the shared property IR must not change
  validator UPLC.
- `julc-compiler`, PIR, optimizer, wrappers, strict boundaries, and ordinary
  blueprint generation must not depend on the DSL.

### Closed semantic input

- Every admitted expression is a sealed data node with an authoritative
  parent-process validator.
- No node carries Lean source, Java source, imports, theorem names, arbitrary
  function names, shell commands, or unchecked assumptions.
- All identifiers, literals, binder scopes, result types, purposes, and
  contract paths are revalidated after worker execution.
- Unknown node kinds, schema versions, fields, types, purposes, or capability
  states fail before Lean generation.

### Honest domain and result

- Exact UPLC success is always a premise of a promoted property.
- The only E.4a domain choices are no additional domain and the reviewed pinned
  V3 minting domain.
- The ledger-domain choice is never inferred from a convenient expression or
  inserted silently.
- `SMT-VALID` remains scoped to exact UPLC, the named formula, the selected
  domain, pinned model, and recorded CEK/solver bounds.
- Vacuous, timed-out, unsupported, under-fueled, malformed, or tampered work is
  never success.

### Raw mint semantics

- Policy and token entries retain list order and duplicates.
- Exact own-policy asset shape counts all raw entries whose key equals the
  current policy, including entries with malformed values.
- Exactly one matching policy entry must exist; its value must be a raw map
  containing exactly one token entry of the required name and quantity.
- Other policy IDs remain permitted.
- A lookup that returns the first matching quantity is not interchangeable
  with this structural predicate.

## Decision

### 1. Keep E.4a in the optional verification boundary

Implementation belongs in:

- `julc-verification` for symbolic Java types, sealed property IR, canonical
  codec, authoritative validation, annotation equivalence, and deterministic
  Lean expression lowering;
- `julc-cli` for project compilation, purpose/artifact selection, bounded
  worker execution, workspace generation, runner execution, progress, and
  certificate publication; and
- `verification/e4a` for committed source, controls, scripts, manifests, and
  documentation.

No E.4a implementation change is expected in `julc-core`, `julc-compiler`,
`julc-ledger-api`, `julc-stdlib`, the optimizer, or validator wrappers. If
implementation discovers that a compiler change is required, that part stops
and this ADR is revised before code is changed.

The existing compiler-owned `ContractSchema`, strict boundary metadata, and
purpose-indexed CIP-57 entries are inputs, not DSL-owned models.

### 2. Select a minting interface explicitly

`dsl-init` and `dsl` gain purpose-aware selection. The intended command shape
is:

```bash
julc verify dsl-init . --validator TokenPolicy --purpose minting \
  --package evidence --class TokenPolicyModel \
  --out build/verification-dsl/src/evidence/TokenPolicyModel.java

julc verify dsl . --validator TokenPolicy --purpose minting \
  --spec-class evidence.TokenPolicySpec \
  --spec-classpath "build/verification-dsl/classes:/path/to/julc.jar" \
  --source TokenPolicySpec.java --backend local --force
```

The JuLC JAR entry is required for the native CLI because the trusted property
builder remains a child JVM. JVM CLI invocation may rely on its host classpath,
but accepting the explicit JAR in both modes keeps the command portable.

For a single-interface validator, omission of `--purpose` may infer the sole
purpose to preserve the E.3 workflow. For a multi-interface validator,
omission is an error listing available purposes. A supplied purpose must match
exactly one compiler interface and exactly one blueprint entry. Selection uses
ADR-017's base-title plus purpose resolver and records:

- base validator title;
- selected blueprint entry title;
- entrypoint name;
- JuLC purpose;
- CIP-57 purpose;
- shared compiled-code hash; and
- Cardano script hash.

No path selects the first schema or first blueprint entry by order.

### 3. Introduce canonical property-IR schema 2

The worker protocol remains strict canonical JSON. Minting expressions use
property-IR schema 2 because the closed node and type inventories expand.
Schema 1 remains supported for existing E.2/E.3 evidence; regeneration must
not silently reinterpret a schema-1 property with schema-2 semantics.

Schema 2 retains a Boolean formula but requires one of two normalized
top-level forms:

```text
exactUplcSucceeds -> guarantee

(validMintingContext && exactUplcSucceeds) -> guarantee
```

Operand order is canonical. `exactUplcSucceeds` and
`validMintingContext` may not appear elsewhere in the formula. This makes the
execution and domain contract mechanically inspectable while avoiding an
unchecked general `assume` operation.

E.4a promotes exactly one minting property per command. The worker may continue
returning a `DslPropertySet`, but zero or multiple properties fail before
workspace generation until aggregation has a separately reviewed certificate
and runner protocol.

The canonical certificate representation includes:

- property-IR schema and canonical JSON hash;
- normalized formula and property ID;
- selected purpose and interface identities;
- derived domain assumption list;
- capability-manifest revision;
- exact artifact and generated Lean hashes; and
- all ordinary runner trust inputs and bounds.

### 4. Add a minimal reviewed minting vocabulary

Exact public class and method spelling remains experimental until ADR-016 E.6,
but the following semantic operations are fixed by this ADR.

#### Existing reusable operations

- Boolean `and`, `or`, and `implies`;
- integer equality and ordering;
- byte-string equality;
- complete-list signatory membership;
- canonical integer literals; and
- exact UPLC success.

#### New roots and fields

| Symbolic operation | Type | Semantics |
|---|---|---|
| `context` | `SCRIPT_CONTEXT` | Pinned V3 script context |
| `context.txInfo` | `TX_INFO` | Full transaction information |
| `context.txInfo.inputs` | `LIST_TX_IN_INFO` | Raw ordered consumed inputs |
| `context.txInfo.mint` | `MINT_VALUE` | Raw ordered mint association list |
| `context.txInfo.signatories` | `LIST_BYTE_STRING` | Complete signatory list |
| `ownPolicy` | `POLICY_ID` | Currency symbol from `MintingScript` only |
| `redeemerStrictlyDecodes` | `BOOL` | Strict generated `IsData` decode of the selected redeemer schema |
| `validMintingContext` | `BOOL` | Explicit pinned V3 domain selector |

The generated `MintingContractModel` exposes no datum root. Attempting to use a
datum expression for a minting interface is a parent-process validation error.

E.4a exposes the selected redeemer's schema identity and strict-decode
predicate. Direct symbolic access to redeemer fields is deferred until an
explicit optional-decode expression is admitted; field access must never invent
a default value for malformed data.

#### New literals and predicates

| Operation | Required validation | Lean meaning |
|---|---|---|
| byte-string literal | canonical even-length lowercase hex | exact `ByteString` bytes |
| token-name literal | at most 32 bytes | token-name bytes |
| policy/key-hash literal | context-specific exact length where required | policy or key-hash bytes |
| transaction-output reference literal | 32-byte transaction ID plus canonical nonnegative index | `V3.TxOutRef` |
| `inputs.consumes(ref)` | input/ref types must match | pinned `utxoConsumed` semantics |
| `mint.exactOwnPolicyAsset(policy, token, quantity)` | mint/policy/token/integer types must match | raw structural predicate defined below |

E.4a promotion accepts `exactOwnPolicyAsset` only when its policy argument is
the typed `ownPolicy` root obtained from the current `MintingScript`. The
public `policyId(hex)` literal is retained for typed composition and future
reviewed profiles, but cannot substitute an arbitrary policy in either E.4a
proof template.

The raw asset predicate is true exactly when filtering the complete raw mint
list by key equality with `Data.B policy` yields:

```text
[(Data.B policy,
  Data.Map [(Data.B token, Data.I quantity)])]
```

The filter compares the raw key before inspecting the value. A malformed or
duplicate entry with the same policy key therefore prevents success rather
than disappearing. Entries for different policies are ignored and permitted.

The signed quantity is an arbitrary canonical integer in the DSL. The
`@ControlledMint` lowering separately preserves its current positive-magnitude
and explicit mint/burn validation. E.4a does not silently infer a mint/burn
profile merely from a quantity sign.

#### Bounds and scoping

- The existing 10,000-node limit remains.
- Binder depth is limited to 32 when collection binders are generalized.
- A binder cannot shadow a root name, generated Lean name, or another active
  binder.
- Hex and decimal literal lengths have explicit bounds before allocation or
  Lean rendering.
- Every user-visible identifier must pass the existing canonical identifier
  policy.

Although E.4a can implement `inputs.consumes` and exact own-policy asset shape
as dedicated semantic nodes, their Java wrappers must remain compatible with a
later reviewed general collection API. E.4a does not need to expose raw
`Data` pairs merely to appear general.

### 5. Use one semantics for `@ControlledMint` and DSL composition

Add a controlled-mint DSL lowering analogous to the existing
`RequiresSignerDslLowering`. For configured authority `A`, token `N`, signed
quantity `Q`, action, and redeemer schema `R`, its normalized guarantee is:

```text
redeemerStrictlyDecodes(R)
&& signatories.contains(A)
&& mint.exactOwnPolicyAsset(ownPolicy, N, Q)
&& direction(Q, action)
```

with no additional ledger-domain premise. This is the exact C.7 meaning: it is
a statement over every modeled minting context in which the exact artifact
succeeds within fuel.

The annotation lowering and an equivalent DSL builder must produce:

- byte-identical canonical property IR;
- identical normalized Lean property text;
- identical exact-artifact obligation semantics; and
- identical property result for every C.7 positive, refuted, and vacuous
  fixture.

The existing template ID `julc.controlled-mint/v1`, annotation syntax, and
certificate claim remain stable. Generated file hashes may intentionally
change when the annotation generator is migrated to the shared renderer; that
is evidence regeneration, not an on-chain change. The migration is incomplete
if two independent Lean definitions remain authoritative.

### 6. Add a domain-aware one-shot minting vertical slice

E.4a must demonstrate a useful property beyond C.7. The committed slice uses
fixed property literals for:

- an authority key hash;
- an anchor `TxOutRef`;
- a token name; and
- a signed quantity of exactly one.

Conceptually, the user-authored typed Java expression is:

```java
var policy = new TokenPolicyModel();
var guarantee = policy.redeemer().strictlyDecodes()
    .and(policy.context().txInfo().signatories().contains(AUTHORITY))
    .and(policy.context().txInfo().inputs().consumes(ANCHOR))
    .and(policy.context().txInfo().mint().exactOwnPolicyAsset(
        policy.ownPolicy(), TOKEN_NAME, integer(1)));

return properties(mintingProperty(
    "one-shot-authorized-mint",
    validV3MintingContext(),
    guarantee));
```

The exact Java spelling may be refined during implementation, but the formula
and literal meanings may not.

The generated obligation is:

```text
for every V3 ScriptContext ctx,
  blasterValidMintingContext(ctx)
  && exact artifact execution succeeds within pinned CEK fuel
  -> oneShotAuthorizedMint(ctx)
```

The property does not say that consuming an anchor alone makes a policy safe.
It states the complete named conjunction above. It also does not claim global
protocol uniqueness after the anchor is consumed; Cardano's ledger enforces
that one UTxO cannot be consumed twice, while the theorem checks that this
policy requires the configured input in every successful mint.

### 7. Bridge the solver domain to pinned ledger validity

Define a deterministic `blasterValidMintingContext` using the same reviewed
solver-compatible transaction clauses as E.3 plus minting purpose and
`validScriptInfo`. It intentionally omits only clauses that the pinned solver
path cannot translate. Therefore it admits a superset of contexts satisfying
the pinned `validMintingContext`.

A separate ordinary Lean theorem must establish:

```text
validMintingContext(ctx) = true
  -> blasterValidMintingContext(ctx) = true
```

The theorem must compile without `blaster`, `sorry`, `admit`, project axioms,
`unsafe`, or `partial`. A kernel-checked corollary composes this inclusion with
the SMT result to state the property for the pinned ledger-valid minting
domain.

E.4a should extract the common solver-compatible transaction predicate and
bridge structure shared with E.3 instead of adding a third textually copied
domain definition. Any upstream conjunction-layout dependence is pinned by
the capability revision/signature gate and covered by a kernel compilation
test.

`ledgerValidityModeled` is true only for the domain-aware slice. The
controlled-mint equivalence remains false because changing that field would
change the C.7 theorem.

### 8. Distinguish proof and counterexample domains

Proving a property over the solver-compatible superset is stronger than
proving it only for ledger-valid contexts. A refutation over that superset,
however, is not automatically a real ledger-admissible exploit because the
counterexample may violate one of the omitted ledger clauses.

The certificate must therefore record for `REFUTED` results:

- the exact counterexample domain;
- whether full pinned ledger validity was established for the witness; and
- whether a concrete VM/ledger-valid witness was independently reproduced.

The CLI may say "property refuted in the recorded solver domain." It may say
"ledger-valid counterexample" only when the additional witness check passes.
Failure to establish witness ledger validity does not turn a refutation into a
proof; it limits the interpretation of the counterexample.

### 9. Strengthen non-vacuity evidence

For a domain-aware property, non-vacuity asks whether a successful input exists
inside the recorded solver domain. The positive E.4a fixture must additionally
include a concrete context that:

- strictly encodes the redeemer;
- has `MintingScript` for the configured policy;
- satisfies the pinned `validMintingContext` predicate;
- succeeds under the exact compiled UPLC; and
- satisfies the intended guarantee.

Where the pinned model permits, this witness is checked by ordinary Lean
evaluation/kernel compilation as well as the JuLC VM. If a full ledger-valid
witness cannot be constructed, the evidence and certificate must say
`solver-domain-non-vacuous` rather than implying ledger-valid non-vacuity.

Always-failing policies remain
`COULD-NOT-EVALUATE/property-vacuous`, and the main proof step is skipped.

### 10. Preserve worker and runner trust boundaries

The property builder remains trusted project Java executed only by explicit
`dsl` invocation in a bounded worker JVM. E.4a retains:

- sanitized environment;
- fixed memory limit;
- positive timeout;
- bounded output size and AST nodes;
- no shell command construction;
- authoritative parent-process validation; and
- atomic generated workspace publication.

The bounded JVM is not an OS sandbox. Hosted execution of untrusted property
builders remains out of scope until a networkless Docker worker or another
accepted isolation design exists. The local/Docker verification backend choice
applies to Lean/Blaster execution and does not erase the Java worker trust
warning.

## Capability inventory changes

Only after the relevant tests and evidence pass, E.4a changes these entries:

| Capability | E.1 state | E.4a target |
|---|---|---|
| `purpose.minting` | `TYPED` | retained and exercised by DSL |
| `field.txInfo.inputs` | `TYPED` | retained and exposed by DSL |
| `field.txInfo.mint` | `TYPED` | retained with documented raw semantics |
| `helper.ownCurrencySymbol` | `TYPED` | retained and exposed by DSL |
| `helper.utxoConsumed` | `UNSUPPORTED_IR` | `TYPED` |
| `ledger.validMintingContext` | `UNSUPPORTED_IR` | `TYPED`, with kernel bridge |

The inventory must not mark general value equality, quantity lookup, all
minting fields, validator parameters, or multi-token composition as typed
merely because the exact-own-policy predicate exists.

## Implementation milestones

### E.4a.1 — Purpose-aware IR and metamodel foundation

- Add schema-2 type and node inventory for minting roots, literals, consumed
  input, exact own-policy asset shape, and strict redeemer decoding.
- Add normalized top-level formula validation and single-property admission.
- Add canonical codec, hash-stability, unknown-node, wrong-schema, wrong-type,
  wrong-purpose, literal-bound, binder-shadowing, and AST-limit tests.
- Generate `MintingContractModel` from a selected compiler interface.
- Make `DslContractLoader`, `dsl-init`, and `dsl` select single and
  purpose-indexed minting interfaces exactly.
- Prove observational compile bytes/hash match the selected blueprint entry.
- Keep schema-1 spending behavior and E.3 evidence unchanged.

### E.4a.2 — Minting semantic library and annotation convergence

- Implement deterministic Lean lowering for every admitted E.4a node.
- Implement raw exact-own-policy asset semantics with malformed and duplicate
  cases.
- Lower `@ControlledMint` into the canonical DSL IR.
- Make annotation and DSL forms canonical-IR-identical and Lean-identical.
- Migrate workspace generation to the shared minting semantic renderer.
- Reproduce C.7 mint, burn, unauthorized, wrong-asset, wrong-quantity, and
  vacuous classifications.
- Recheck annotation zero-UPLC effect and exact C.7 certificate fields.

### E.4a.3 — Ledger-domain bridge and one-shot slice

- Add the explicit `validMintingContext` domain node.
- Reuse or extract the solver-compatible V3 transaction-domain definition.
- Kernel-check `validMintingContext -> blasterValidMintingContext`.
- Generate the exact-artifact one-shot authorized mint obligation.
- Add domain-aware non-vacuity and witness classification.
- Add positive, missing-anchor, missing-authority, wrong-asset/quantity,
  malformed-redeemer, duplicate-entry, unrelated-other-policy, and vacuous
  controls.
- Retain raw Blaster counterexamples and state whether each is ledger-valid or
  only solver-domain-valid.

### E.4a.4 — Product integration and reproducibility

- Add CLI progress, actionable purpose/schema diagnostics, and local/Docker
  documentation.
- Add a mixed spending/minting `@MultiValidator` selection fixture proving
  both entries share exact code/hash while E.4a verifies only minting.
- Bind property IR, metamodel, capability inventory, generated Lean, runner
  plan, exact artifact, domain bridge, tools, pins, and bounds into the
  certificate.
- Run a clean-cache local positive proof and one Docker positive proof with
  proof-phase networking disabled.
- Record script size, CEK fuel, solver time, and warm/cold dependency timing as
  diagnostics rather than semantic certificate inputs.
- Update ADR-016, the getting-started guide, DSL README, integration-branch
  ledger, and roadmap issue.

## Required tests

### Property IR and Java API

- every new node has positive and invalid-type tests;
- schema-1 spending IR remains canonical and readable;
- schema-2 canonical JSON is byte-stable across repeated runs;
- unknown fields and subtypes fail deserialization;
- raw Lean, invalid hex, oversized literals, negative output indexes, root
  shadowing, wrong purposes, and forged result types fail in the parent;
- no datum root is generated or accepted for minting;
- malformed redeemer field access cannot receive a default value; and
- worker crash, timeout, oversized output, and nondeterministic/tampered output
  remain failures.

### Purpose and artifact selection

- a normal minting validator selects its sole interface;
- a mixed multi-validator requires or honors explicit `minting` selection;
- absent, ambiguous, stale, unsupported, or mismatched purposes fail closed;
- selected compiler schema and blueprint entry agree;
- observational and blueprint compiled bytes/hash are identical; and
- changing only DSL/annotation source leaves UPLC byte-identical.

### Raw mint semantics

- empty mint;
- one correct policy/token entry;
- wrong policy, token, quantity, and sign;
- duplicate current-policy entries;
- duplicate token entries;
- malformed policy key, policy value, token key, and quantity;
- unrelated entries before and after the current policy;
- zero quantity;
- mint and burn quantities; and
- structural result differs from first-match lookup on duplicate data.

### Domain and Lean evidence

- `MintingScript` purpose and `mintingInputs` are used;
- strict redeemer decode is a conclusion, not a hidden assumption;
- exact UPLC success is present once in canonical premise position;
- no-domain and valid-minting-domain formulas produce different explicit
  certificate metadata;
- the domain-inclusion theorem kernel-compiles without admissions or Blaster;
- breaking one included solver-domain clause breaks the bridge/corollary test;
- positive, refuted, undetermined/unsupported, and vacuous protocols remain
  distinguishable; and
- fuel exhaustion and timeout never become success.

### Regression and evidence

- all `julc-verification` and `julc-cli` tests;
- C.5, C.6, C.7, and E.3 evidence classifications;
- annotation/DSL controlled-mint semantic equivalence;
- strict-boundary malformed redeemer rejection in the VM;
- local and Docker E.4a positive runs;
- generated-source/property/artifact tamper tests;
- capability revision/signature gate; and
- `git diff --check` plus focused review for compiler/core changes.

## Compatibility and migration

- The DSL remains explicitly experimental; schema-2 Java API changes are not a
  stable public compatibility promise.
- Existing schema-1 E.3 workspaces and certificates remain interpretable and
  are not rewritten silently.
- Existing `@ControlledMint` Java source and template ID remain compatible.
- Controlled-mint generated Lean and certificate hashes may change when
  regenerated through the shared renderer, but the theorem semantics and
  expected classifications must be demonstrated equivalent.
- No validator script hash changes are expected because verification metadata
  remains observational.
- Purpose-indexed multi-validator title and artifact conventions remain those
  of ADR-017.
- A future schema-3 expansion must declare whether schema-2 nodes are retained,
  migrated, or read-only; it may not reinterpret their semantics in place.

## Risks and mitigations

### False confidence from a user-written property

A well-proved weak property may omit the real threat. Certificates therefore
name and serialize the exact formula and never summarize a custom DSL result as
"safe contract."

### Duplicate-map semantic drift

High-level lookup could hide data the exact profile rejects. E.4a pins the raw
structural predicate, tests duplicates and malformed values, and does not add a
general extensional map claim.

### Domain laundering

Allowing arbitrary assumptions could make a theorem trivial. E.4a admits only
the normalized no-domain or pinned-valid-minting-domain premise and derives
certificate assumptions from validated IR.

### Solver-domain counterexamples misread as ledger exploits

The solver-compatible domain is a superset. Certificates and CLI output
separate solver-domain refutation from independently confirmed ledger-valid
witnesses.

### Worker execution of untrusted Java

Process bounds are not a sandbox. The command remains explicit and warns that
project Java executes. Hosted untrusted execution remains disabled.

### Solver cost and instability

Raw list reasoning and exact UPLC can increase query size. Fuel, timeout, and
wall time are recorded; thresholds use representative fixtures; timeout and
undetermined remain non-success. E.4a does not broaden the grammar merely to
make demonstrations impressive.

### Semantic duplication during migration

Keeping both C.7 and E.4a Lean generators authoritative would drift. The
milestone is not complete until controlled minting uses one canonical IR and
one semantic renderer, with old/new evidence compared.

## Alternatives considered

### Add more minting annotations first

Rejected for E.4a. One annotation per theorem variation does not scale and
would postpone the shared semantic vocabulary ADR-016 is intended to test.

### Reuse `@ControlledMint` as the entire minting DSL

Rejected. It would provide no contract-specific composition and could not state
the consumed-anchor one-shot property.

### Expose raw Lean strings

Rejected. This bypasses Java typing, IR validation, admission controls,
deterministic semantics, and meaningful certificate review.

### Treat mint as a Java `Map`

Rejected. The on-chain and pinned Lean representation is a raw association
list. Java-map uniqueness would make malformed and duplicate states
unrepresentable and could prove a different property.

### Use only `valueOf`

Rejected for exact own-policy shape. First-match lookup does not establish
unique policy/token entries or absence of unrelated tokens under the policy.

### Assume full ledger validity directly in SMT

Rejected while the pinned translator cannot handle every clause. Dropping
clauses without a kernel bridge would overstate the domain. The reviewed
superset plus inclusion theorem is explicit and sound for positive results.

### Infer desired constants from UPLC

Rejected. Verification states an independent desired property and checks that
the exact artifact enforces it. Reverse inference can merely restate an
unintended implementation.

### Generalize every collection and value operation in E.4a

Rejected under KISS. E.4a admits only operations required by controlled-mint
equivalence and the one-shot slice. Broader primitives require their own
semantics and evidence before admission.

## Resolved implementation decisions

1. **Schema representation:** one version-aware `DslPropertySet` accepts
   schema 1 and schema 2. Schema-1 canonical bytes remain unchanged. The
   template-specific certificate property remains a separate schema-1
   envelope and embeds the canonical schema-2 JSON; the manifest independently
   records and validates the schema-2 hash.
2. **Collection shape:** E.4a uses dedicated `ConsumesNode` and
   `ExactOwnPolicyAssetNode` nodes. A general binder is deferred until multiple
   admitted operations need it.
3. **Non-vacuity witness:** the SMT negative control establishes successful
   execution inside `BLASTER_VALID_MINTING_SUPERSET`, and a separate JuLC VM
   test reproduces a concrete successful exact-artifact execution. E.4a does
   not claim that this concrete VM context independently satisfies the complete
   pinned `validMintingContext`. Certificates record `nonVacuityDomain`,
   `ledgerValidNonVacuityWitnessEstablished`, and
   `concreteVmSuccessfulWitnessReproduced` explicitly. The last field remains
   false in the general runner because the repository-only VM test is not a
   certificate-bound runner step.
4. **Shared domain support:** E.3 and E.4a generation call one Java-owned
   `blasterValidTxInfoDefinition` emitter. Each standalone workspace still
   contains its own generated definition, avoiding an import or compatibility
   change while eliminating authoritative textual duplication in the
   generator.
5. **Counterexample fields:** one-shot certificates record
   `counterexampleDomain`, `ledgerValidCounterexampleEstablished`, and
   `concreteVmCounterexampleReproduced`; no absent field defaults to a
   ledger-valid claim.
6. **Purpose UX:** a sole supported interface is inferred. A multi-interface
   validator requires `--purpose`, and selection is tested against shared exact
   code/hash with distinct purpose-specific blueprint identities.
7. **Next minting surface:** quantity ranges, parameters, and multi-token
   traversal remain deferred to a separate reviewed milestone.

## Implementation outcome

E.4a.1 through E.4a.4 are implemented on
`feat/typed-verification-dsl-e4a-minting`. The implementation adds no
compiler/core lowering changes. It includes:

- strict schema-2 decoding and parent validation, including unknown-field and
  unknown-subtype rejection;
- purpose-aware normal and mixed-multi-validator artifact selection;
- shared controlled-mint annotation/DSL IR and Lean semantics;
- native-image reachability metadata for the property envelope and sealed DSL
  node inventory, exercised by a real GraalVM native build and complete native
  DSL verification through both the local and Docker proof backends;
- raw mint semantic controls checked by Lean evaluation and kernel
  elaboration;
- the minting-domain bridge, exact one-shot proof, separate concrete VM
  success, and refuted/vacuous controls; and
- manifest preflight bindings for the canonical DSL, capability inventory,
  generated Lean tree, runner plan, exact UPLC, pins, and bounds.

The positive 632-byte artifact is `SMT-VALID` at CEK fuel 5000 through both a
clean local workspace and Docker. The local and Docker certificates have
identical compiled-code, template-property, schema-2 DSL, and generated-Lean
hashes. Docker records immutable image identity
`sha256:e4fd68fd9a03e1d91bd7af14dc2cdb149a7f3e98600e5934447aef005b7df4da`;
proof commands use the runner's network-disabled phase. Timings are recorded in
`verification/e4a/README.md` as diagnostics rather than claim inputs.

The finite raw-mint executable controls use `native_decide`: the pinned
`ByteString` equality implementation does not reduce with `decide`. This adds
Lean's native evaluator/compiler to those controls' trust base. It does not
replace the separately kernel-checked ledger bridge and property corollary or
the Blaster obligation over the exact artifact.

## Acceptance criteria

E.4a is complete only when:

- a typed minting metamodel is generated from an exactly selected compiler
  interface;
- normal and purpose-indexed minting artifacts are bound to exact UPLC/hash;
- every admitted schema-2 node is closed, typed, canonical, and parent-validated;
- the DSL cannot introduce raw Lean or unchecked assumptions;
- raw own-policy singleton semantics handle duplicates and malformed entries
  exactly as specified;
- `@ControlledMint` and equivalent DSL source lower to identical canonical IR
  and Lean semantics;
- all existing C.7 classifications are reproduced through the shared renderer;
- the one-shot authorized mint positive fixture is non-vacuous and
  `SMT-VALID` under its recorded domain and bounds;
- missing-anchor, missing-authority, and wrong-asset controls are refuted with
  honestly classified witnesses;
- the always-failing control is reported as vacuous and does not run the main
  proof;
- the pinned minting-domain inclusion theorem and final corollary are
  kernel-checked;
- local and Docker positive evidence passes with identical semantic inputs;
- capability inventory and documentation match only demonstrated coverage;
- adding or changing verification source has zero UPLC effect;
- no compiler/core lowering source changes; and
- the complete diff, generated formula, certificates, counterexamples,
  timings, and trust claims receive manual review before commit.

## Result claim

The strongest permitted successful E.4a statement is:

> The pinned JuLC verification stack established the named typed minting
> property for the exact recorded UPLC artifact, under the recorded Cardano
> model, domain, tool revisions, and execution bounds.

E.4a does not permit:

> This minting policy is formally verified and safe.
