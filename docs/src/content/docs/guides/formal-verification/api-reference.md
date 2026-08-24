---
title: "API and DSL Reference"
description: "Experimental JuLC formal-verification annotations, typed Java DSL, and CLI reference"
---

:::caution[Experimental verification feature]
The documented Java construction API and canonical schema-1 meanings are
stable as API v1. The verifier, compiler, pinned ledger model, Blaster
translation, and solver integration remain experimental and are not a general
contract-safety certification.
:::

This page covers the off-chain APIs in `julc-verification`. None of these
annotations or property builders changes compiler lowering or emitted UPLC.

## Verification annotations

| Annotation | Purpose | Admitted form |
|---|---|---|
| `@RequiresSigner("datum.<field>")` | spending | The selected datum field resolves to a supported byte-string/key-hash authority. |
| `@ControlledMint(authority=..., tokenName=..., quantity=..., action=...)` | minting | Fixed 28-byte authority, token name up to 32 bytes, and a strictly positive magnitude interpreted according to `MINT` or `BURN`. |
| `@PreservesValue(output=SINGLE_CONTINUING_OUTPUT)` | spending | Accepted only as part of the complete stateful profile. |
| `@Monotonic(current=..., next=..., relation=GREATER_THAN)` | spending | Accepted only as part of the complete stateful profile. |

The complete stateful profile is `@RequiresSigner + @PreservesValue +
@Monotonic`. Partial combinations fail closed. Every profile lowers to the
same canonical typed DSL IR used by a direct Java specification; annotations
do not own separate Lean security formulas.

See [Annotation Profiles](../annotation-profiles/) for complete examples and
profile-specific limitations.

## Stable construction API v1

Canonical property documents use:

```json
{
  "format": "julc.verification.dsl",
  "schemaVersion": 1
}
```

Earlier E.2–E.4 schema numbers were unreleased milestone gates. They are not
selectable, generated, or accepted as current property input.

Do not confuse that public schema number with the `schemaVersion` on an
internal workspace property record such as `ComposedDslProperty`. The public
canonical DSL document above is always format `julc.verification.dsl`, schema
1. Workspace manifests, promoted-property records, runner plans, and
certificates have separate versioned protocols and may contain a different
`schemaVersion` field.

| Type or surface | Role |
|---|---|
| `VerificationSpecification` | Trusted Java property-builder entry point. |
| `VerificationDsl` | Property, integer, boolean, bytes, key-hash, token-name, policy-ID, and output-reference factories. |
| Typed wrappers in `com.bloxbean.cardano.julc.verification.dsl` | Closed expressions for booleans, integers, bytes, options, lists, maps, contract types, ledger data, authorization, certificates, values, and governance data. |
| `DslProperty` | One named guarantee with an explicit modeled domain. |
| `DslPropertySet` | Canonical schema-1 envelope; generated models normally construct it through `contract.properties(...)`. |
| `DslPurpose` | `SPENDING`, `MINTING`, `REWARDING`, or `CERTIFYING`. |
| `DslDomain` | `NONE` or one of the four pinned V3 ledger domains. |
| Generated contract metamodel | Compiler-owned datum/redeemer types and purpose-specific context roots. |

Concrete node classes under `verification.dsl.ir` are serialization
infrastructure, except for the documented property envelope and enums.
Renderers, validators, promotion internals, worker protocols, arbitrary Lean,
and user-defined AST node kinds are not supported construction APIs.

## Property factories

Common static imports from `VerificationDsl` include:

| Factory | Result |
|---|---|
| `property(id, domain, guarantee)` | A named, domain-qualified property. |
| `bool(value)` | Boolean literal expression. |
| `integer(value)` | Canonical bounded integer literal expression. |
| `bytes(hex)` | Byte-string literal expression. |
| `keyHash(hex)` | Fixed public-key-hash literal for admitted signer operations. |
| `tokenName(hex)` | Token-name literal. |
| `policyId(hex)` | Policy-ID literal where the selected operation admits a fixed policy. |
| `txOutRef(transactionIdHex, index)` | Transaction-output-reference literal. |

Generated wrappers expose only operations admitted for their compiler-owned
type. Options require `exists`, `isPresent`, or `isEmpty`; sealed variants
require the generated guarded eliminator; list and map operations preserve
order and duplicates unless a method explicitly states different semantics.

See [Typed Java DSL](../typed-dsl/) for composition examples and the supported
Cardano surface.

## Core expression operations

Do not construct wrappers from raw `PropertyNode` values. Obtain them from
`VerificationDsl`, the generated model, or another documented wrapper method.
The public operations are:

| Wrapper | Supported operations |
|---|---|
| `BoolExpr` | `and`, `or`, `implies`, `not`, `eq`, `ne` |
| `IntegerExpr` | `eq`, `ne`, `ge`, `gt`, `le`, `lt`, `negate`, `add`, `subtract`, `times` |
| `ByteStringExpr`, `StringExpr` | `eq`, `ne` |
| `TypedValueExpr` | type-checked `eq`, `ne`, and `asInteger` when its compiler-owned type is integer |
| `TypedOptionExpr` | `exists`, `isPresent`, `isEmpty` |
| `TypedListExpr` | `isEmpty`, `isNotEmpty`, `contains`, `exists`, `all`, `none`, `whenSingleton`, `count`, `exactlyOne`, `at`, `structurallyEquals`, `structurallyNotEquals` |
| `TypedAssocMapExpr` | `existsEntry`, `allEntries`, `noneEntries`, `countEntry`, `containsKey`, `countKey`, `lookupFirst`, `lookupAll`, `structurallyEquals`, `structurallyNotEquals` |

`at` returns an option and is empty for a negative or out-of-range index.
Lists and association maps are ordered and duplicate-preserving. Structural
equality is therefore not mathematical set or map equality.

## Generated contract metamodel

Every generated model exposes the operations applicable to its selected
interface and compiler-owned schema:

| Generated surface | Meaning |
|---|---|
| `properties(DslProperty...)` | Creates the schema-1 envelope with the selected purpose and contract-schema hash. |
| `datum()`, `redeemer()` | Strictly decoded optional roots; datum is present only for a spending interface. |
| `decodeDatum(raw, predicate)` | Strictly decodes raw `Data` with the compiler-projected datum type. |
| `context()` | Returns the selected script's `LedgerContextExpr`. |
| `authorization()` | Returns the authorization builder. |
| `currentOutputRef()`, `ownInput()`, `continuingOutputs()` | Spending-only helpers. |
| `ownPolicy()` | Minting-only policy root. |
| `rewardingCredential()` | Rewarding-only credential root. |
| `certificate()`, `certificateIndex()` | Certifying-only current-certificate roots. |

Generated record wrappers expose field accessors and structural equality.
Generated sealed variants expose `is<Constructor>()` and guarded
`when<Constructor>(...)` eliminators. Generated optional, list, and map wrappers
mirror the core operations with schema-specific Java types; generated
`List<byte[]>` additionally exposes `asAuthorities()`.

Purpose helper models are also part of API v1 where a reviewed profile needs
special roots. In particular, `MintingContractModel` exposes `context()`,
`ownPolicy()`, `redeemerStrictlyDecodes()`, `exactUplcSucceeds()`, and
`validMintingContext()`. Prefer generated roots for ordinary custom properties;
the [minting example](../dsl-examples/#minting-authority-anchor-and-exact-own-policy-asset)
shows the reviewed exact-own-policy operation.

The complete purpose-helper surface is:

| Helper | Roots and operations |
|---|---|
| `SpendingContractModel` | `datum`, `context`, `exactUplcSucceeds`, `validSpendingContext` |
| `MintingContractModel` | `context`, `ownPolicy`, `redeemerStrictlyDecodes`, `exactUplcSucceeds`, `validMintingContext` |
| `RewardingContractModel` | `context`, `rewardingCredential`, `redeemerStrictlyDecodes` |
| `CertifyingContractModel` | `context`, `certificate`, `certificateIndex`, `redeemerStrictlyDecodes` |
| `ContextExpr` | `txInfo` |
| `DatumExpr` | reviewed `bytesField(name)` and `integerField(name)` access used by supported profile roots |
| `TxInfoExpr` | `signatories`, `outputs`, `inputs`, `mint`, `withdrawals`, `certificates` |
| `ByteStringListExpr` | `contains` |
| `TxInInfoListExpr` | `consumes` |
| `MintValueExpr` | `exactOwnPolicyAsset` |
| `TxOutListExpr` | `exists` |
| `TxOutExpr` | `address`, `value` |
| `AddressExpr` | `credential` |
| `CredentialExpr` | `eq`, `matchesKeyHash` |
| `ValueExpr` | `lovelace` |
| `WithdrawalsExpr` | ordered duplicate-preserving `exists` over `credential` and `amount` |
| `TxCertListExpr` | indexed `containsAt` |

The generated model still owns `properties(...)` and the contract-schema hash;
purpose helpers supply only closed reviewed expression roots.

### Two expression families

The API contains two deliberately different families:

- the generated/current-ledger family starts at a generated model's
  `LedgerContextExpr` and exposes the broader, compiler-bound
  `LedgerTxInfoExpr` surface used by custom properties;
- the purpose-helper family starts at `ContextExpr` and exposes the smaller
  reviewed `TxInfoExpr` surface used by established profile operations.

For example, withdrawals are a generic typed association map on the first
path and a specialized `WithdrawalsExpr` on the second. Likewise,
`VerificationDsl.tokenName(hex)` is a generic byte-string literal, while
`LedgerExpressions.tokenName(bytes(...))` is a role-preserving ledger token
name. Prefer the generated family for custom properties and use a purpose
helper only when its documented operation requires it. Similar names do not
make wrappers interchangeable; use their public factory or adapter methods.

## Ledger context and transaction operations

| Wrapper | Supported operations |
|---|---|
| `LedgerContextExpr` | `txInfo`, `scriptPurpose`, `valueSpent`, `valueProduced`, `isBalanced` |
| `LedgerTxInfoExpr` | `inputs`, `referenceInputs`, `outputs`, `fee`, `mint`, `id`, `certificates`, `datums`, `redeemers`, `withdrawals`, `votes`, `proposals`, `signatories`, `validityRangeReviewed`, `currentTreasuryStrict`, `treasuryDonationStrict` |
| `LedgerTxInInfoExpr` | `outRef`, `resolved` |
| `LedgerTxInInfoListExpr` | `isEmpty`, `isNotEmpty`, `exists`, `all`, `none`, `count`, `exactlyOne`, `at`, `resolve`, `valueSpent`, `forPaymentKey`, `forScript`, structural equality/inequality |
| `LedgerTxInInfoOptionExpr` | `exists`, `isPresent`, `isEmpty` |
| `LedgerTxOutExpr` | `address`, `value`, `datum`, `referenceScript` |
| `LedgerTxOutListExpr` | `isEmpty`, `isNotEmpty`, `exists`, `all`, `none`, `whenSingleton`, `count`, `exactlyOne`, `at`, `valueProduced`, `toAddress`, `toPaymentCredential`, structural equality/inequality |
| `LedgerTxOutOptionExpr` | `exists`, `isPresent`, `isEmpty` |
| `LedgerAddressExpr` | `paymentCredential`, `stakingCredential` |
| `LedgerTxOutRefExpr` | `id`, `index`, `eq`, `ne` |
| `LedgerTxIdExpr` | equality/inequality with another transaction ID or matching ledger byte alias |
| `LedgerScriptPurposeExpr` | `isMinting`, `isSpending`, `isRewarding`, `isCertifying`, `isVoting`, `isProposing`, `eq`, `ne`, `typed` |

`toAddress` compares the complete address. `toPaymentCredential` intentionally
ignores the staking credential and is a weaker aggregation scope.

### Credentials and output datum

| Wrapper | Supported operations |
|---|---|
| `LedgerCredentialExpr` | `typed`, `isPubKey`, `isScript`, `whenPubKey`, `whenScript` |
| `LedgerStakingCredentialExpr` | `isHash`, `isPointer`, `whenHash`, `whenPointer` |
| `LedgerStakingCredentialOptionExpr` | `exists`, `isPresent`, `isEmpty` |
| `LedgerOutputDatumExpr` | `isNone`, `isHash`, `isInline`, `whenHash`, `whenInline`, `whenInlineDecoded`; generated `decodeDatum` is the preferred contract-datum decoder |
| `LedgerByteAliasExpr` | role-preserving `typed`, `eq`, `ne` |

`LedgerExpressions.context()` creates the closed pinned V3 ledger-context
root. `LedgerExpressions` also creates reviewed ledger aliases with `transactionId`,
`datumHash`, `scriptHash`, `publicKeyHash`, `currencySymbol`, and `tokenName`.
It also supplies the current certificate/index, rewarding credential, and
`singletonValueDelta` helpers used by generated or purpose-specific models.

## Authorization operations

| Surface | Supported operations |
|---|---|
| `AuthorizationDsl` | `fixed`, `fromContractBytes(ByteStringExpr)`, `fromContractBytes(TypedListExpr)`, `authorities`, `noSigners` |
| `AuthoritySetExpr` | `anySigned`, `allSigned`, `noneSigned`, `atLeastSigned`, `exactlySigned`, `noUnexpectedSigners`, `exactSignerSet` |

Authorization uses distinct identities. A threshold does not exclude unrelated
signers; compose `noUnexpectedSigners` when that is required. Static authority
sets contain 1–16 unique fixed authorities. Parameter-derived roots and fixed
hashes containing byte `00` currently fail closed.

## Certificate operations

`LedgerTxCertListExpr` supports `isEmpty`, `isNotEmpty`, `exists`, `all`,
`none`, `count`, `at`, `containsAt`, and structural equality/inequality.
`LedgerTxCertOptionExpr` supports `exists`, `isPresent`, and `isEmpty`.

`TxCertExpr` supports typed `eq` and `ne`. Its `isKind` method accepts the
closed `TxCertKind` enum:

```text
REG_STAKING, UNREG_STAKING, DELEG_STAKING, REG_DELEG,
REG_DREP, UPDATE_DREP, UNREG_DREP, POOL_REGISTER, POOL_RETIRE,
AUTH_HOT_COMMITTEE, RESIGN_COLD_COMMITTEE
```

Payloads are accessible only through the corresponding guarded eliminator:
`whenRegStaking`, `whenUnRegStaking`, `whenDelegStaking`, `whenRegDeleg`,
`whenRegDRep`, `whenUpdateDRep`, `whenUnRegDRep`, `whenPoolRegister`,
`whenPoolRetire`, `whenAuthHotCommittee`, and `whenResignColdCommittee`.

Nested `LedgerDRepExpr` supports credential, always-abstain, and
always-no-confidence cases. `LedgerDelegateeExpr` supports stake, vote, and
stake-plus-vote cases. DRep and committee credential wrappers retain their
roles while exposing the credential guard operations.

## Multi-asset value operations

Output `LedgerValueExpr`, transaction `LedgerMintValueExpr`, and checked
`ValueDeltaExpr` remain different Java types even where encodings overlap.

| Operation | Meaning |
|---|---|
| `lovelace()` | Ada quantity for an output value. |
| `rawPolicies()` | Strict guarded traversal of raw policy and token entries. |
| `quantityFirst(policy, token)` | Pinned first-match lookup. |
| `quantitySumStrict(policy, token)` | Sums duplicate matches; returns empty for absence or malformed data. |
| `structurallyEquals` | Exact ordered nested association-list equality. |
| `extensionallyEquals` | Strict finite-support summed-quantity equality. |
| `pointwiseLe/Lt/Ge/Gt` | Strict finite-support pointwise ordering. |
| `checkedDelta()` | Validates and converts a value or mint value to optional `ValueDeltaExpr`. |
| `ValueDeltaExpr.plus`, `negate`, `scale` | Checked arithmetic returning `ValueDeltaOptionExpr`. |

`ValuePolicyEntriesExpr` exposes `exists`, `all`, and `none` with
`ValuePolicyEntryExpr.whenWellFormed`. Token entries expose `exists`, `all`,
and `whenWellFormed`. `ValueDeltaOptionExpr` exposes `exists`, `isPresent`, and
`isEmpty`.

## Governance transaction-data operations

These operations inspect governance fields from an already supported spending,
minting, rewarding, or certifying interface. They do not enable voting or
proposing validator selection.

| Wrapper | Supported operations |
|---|---|
| `VoterMapExpr` | `existsEntry`, `isKnown`, `lookupFirst`, `countKey`, `typed` |
| `VoterExpr` | guarded `whenCommittee`, `whenDRep`, `whenStakePool`, plus `typed` |
| `GovernanceVoteMapExpr` | `existsEntry`, `lookupFirst`, `lookupAll`, `containsKey`, `countKey`, `typed` |
| `VoteExpr` | `isNo`, `isYes`, `isAbstain`, `typed` |
| `ProposalProcedureListExpr` | `exists`, `all`, `none`, `count`, `at`, `isKnown`, `typed` |
| `ProposalProcedureExpr` | `deposit`, `returnAddress`, strict `actionStrict`, `typed` |
| `GovernanceActionIdExpr` | `txId`, `index`, `typed`, `eq`, `ne` |
| `ProtocolVersionExpr` | `major`, `minor`, `typed` |

`GovernanceActionExpr` exposes guarded `whenParameterChange`, `whenHardFork`,
`whenTreasuryWithdrawals`, `whenNoConfidence`, `whenUpdateCommittee`,
`whenNewConstitution`, and `isInfo`. No action payload is available outside its
matching guard.

## Reviewed raw-data adapters

| Adapter | Supported operations |
|---|---|
| `ValidityRangeExpr` | `decoderValid`, `canonicalEncoding`, `isEmpty`, `contains`, `includes`, `isEntirelyBefore`, `isEntirelyAfter` |
| `StrictTreasuryExpr` | `isWellFormed`, `isAbsent`, `isMalformed`, `whenPresent` |
| `ChangedParametersExpr` | `isWellFormed`, `isNonEmpty`, `isStrictlyAscendingUnique`, `containsId`, `countIdEquals` |
| `QuorumExpr` | `decoderValid`, `canonicalEncoding`, `isUnitInterval`, `whenDecoded` |

Changed parameters are supplied only by the three-argument
`whenParameterChange` guard. Quorum is supplied only by the four-argument
`whenUpdateCommittee` guard. The raw payload and arbitrary decoder selection
remain inaccessible.

See [DSL Examples](../dsl-examples/) for complete compositions covering every
operation group above.

## CLI

| Command | Purpose |
|---|---|
| `julc verify . --validator <name>` | Build and verify a supported annotation profile. |
| `julc verify init . --validator <name> --purpose <purpose>` | Generate a pinned but unspecialized workspace from an already built project's blueprint. |
| `julc verify dsl-init . --validator <name> ...` | Generate the API-v1/schema-1 contract metamodel. |
| `julc verify dsl . --validator <name> ...` | Execute a trusted Java specification, admit its canonical IR, and verify it. |
| `julc verify run <workspace>` | Re-run an existing current hash-bound workspace without rebuilding the contract. |

For a purpose-indexed validator, pass one of `spending`, `minting`,
`rewarding`, or `certifying` through `--purpose`. Voting and proposing
verification selection currently fails closed.

The annotation command defaults to `--fuel 1000` and
`--recursive-depth 4`. The typed DSL command defaults to `--fuel 1500`,
`--recursive-depth 4`, and `--worker-timeout 30` seconds; raise the worker
timeout only when trusted specification Java itself needs longer. The
lower-level `verify init` command uses `--fuel 20000` because it creates the
older unspecialized proof workspace rather than running a typed property.

The native CLI still launches a bounded child JVM for
`VerificationSpecification`, because the specification is trusted project
Java. Supply the compiled specification, generated model, and JuLC JAR through
`--spec-classpath`.

## Result contract

`SMT-VALID`, `REFUTED`, and `COULD-NOT-EVALUATE` are distinct outcomes. Always
read `verification-result.json` for the exact artifact, property, modeled
domain, fuel/decode bounds, backend inputs, and counterexample qualifications.
An SMT-valid property is not a claim that the complete contract is safe.

See [Troubleshooting](../troubleshooting/) for workspace, toolchain, solver, and
classification failures.
