# ADR-002: Milestone 2 — V3 Ledger Types (`plutus-ledger-api`)

**Status**: Proposed
**Date**: 2026-02-07
**Authors**: BloxBean Team
**Depends on**: ADR-001

---

## Context

With Milestone 1 complete (UPLC Core + VM SPI + Scalus Backend, 1,216 tests passing), we need Cardano V3 (Conway era) ledger types — the types that smart contracts interact with at runtime. These represent the `ScriptContext` that validators receive on-chain.

## Decision

Create a `plutus-ledger-api` module containing all V3 ledger types as Java sealed interfaces and records, with bidirectional PlutusData serialization. Target V3 only — no V1/V2 backward compatibility.

---

## Module Setup

```groovy
// plutus-ledger-api/build.gradle
plugins { id 'java-library' }
description = 'Cardano V3 ledger types (ScriptContext, TxInfo, etc.) with PlutusData codec'
dependencies {
    api project(':plutus-core')
}
```

Package: `com.bloxbean.cardano.plutus.ledger`

---

## PlutusData Codec Interface

Each ledger type implements `PlutusDataConvertible` and provides a static `fromPlutusData()`:

```java
public interface PlutusDataConvertible {
    PlutusData toPlutusData();
}

public interface PlutusDataCodec<T> {
    PlutusData toPlutusData(T value);
    T fromPlutusData(PlutusData data);
}
```

### Encoding Conventions (matching Haskell/Scalus)

| Java Type | PlutusData Encoding |
|-----------|-------------------|
| Record (product type) | `Constr(0, [field1, field2, ...])` |
| Sealed interface variant N | `Constr(N, [fields...])` where N = constructor ordinal |
| `Optional<T>` present | `Constr(0, [toData(value)])` |
| `Optional<T>` empty | `Constr(1, [])` |
| `boolean true` | `Constr(1, [])` |
| `boolean false` | `Constr(0, [])` |
| `List<T>` | `PlutusData.ListData([toData(elem)...])` |
| `Map<K,V>` | `PlutusData.Map([Pair(toData(k), toData(v))...])` |
| Hash wrapper (PubKeyHash, etc.) | `PlutusData.BytesData(bytes)` (raw, not Constr-wrapped) |
| `BigInteger` / Lovelace | `PlutusData.IntData(value)` |

**Critical**: The Optional encoding follows Haskell's `Maybe` type: `Just x = Constr(0, [x])`, `Nothing = Constr(1, [])`. This is the opposite of what you might expect (Some = 0, None = 1).

---

## Type Catalog

### Hash-based Wrappers

All are records wrapping `byte[]` with defensive copy, custom equals/hashCode, hex toString. They serialize as `PlutusData.BytesData(bytes)` directly (not wrapped in Constr).

| Type | Size Constraint | Notes |
|------|----------------|-------|
| `PubKeyHash` | 28 bytes | Payment/staking key hash |
| `ScriptHash` | 28 bytes | Script hash |
| `ValidatorHash` | 28 bytes | Alias for ScriptHash semantically |
| `PolicyId` | 28 bytes or 0 (ADA) | Minting policy hash |
| `TokenName` | 0-32 bytes | Asset name |
| `DatumHash` | 32 bytes | Hash of datum |
| `TxId` | 32 bytes | Transaction ID |

### Credential Types

```java
public sealed interface Credential extends PlutusDataConvertible {
    record PubKeyCredential(PubKeyHash hash) implements Credential {}    // Constr(0, [B(hash)])
    record ScriptCredential(ScriptHash hash) implements Credential {}    // Constr(1, [B(hash)])
}

public sealed interface StakingCredential extends PlutusDataConvertible {
    record StakingHash(Credential credential) implements StakingCredential {}           // Constr(0, [toData(cred)])
    record StakingPtr(BigInteger a, BigInteger b, BigInteger c) implements StakingCredential {} // Constr(1, [I(a), I(b), I(c)])
}
```

### Address

```java
public record Address(Credential credential, Optional<StakingCredential> stakingCredential)
    implements PlutusDataConvertible {}
// -> Constr(0, [toData(credential), encodeOptional(stakingCredential)])
```

### Transaction Types

```java
public record TxOutRef(TxId txId, BigInteger index) implements PlutusDataConvertible {}
// -> Constr(0, [toData(txId), I(index)])

public sealed interface OutputDatum extends PlutusDataConvertible {
    record NoOutputDatum() implements OutputDatum {}                    // Constr(0, [])
    record OutputDatumHash(DatumHash datumHash) implements OutputDatum {} // Constr(1, [B(hash)])
    record OutputDatumInline(PlutusData datum) implements OutputDatum {}  // Constr(2, [datum])
}

public record TxOut(Address address, Value value, OutputDatum datum, Optional<ScriptHash> referenceScript)
    implements PlutusDataConvertible {}
// -> Constr(0, [toData(address), toData(value), toData(datum), encodeOptional(refScript)])

public record TxInInfo(TxOutRef outRef, TxOut resolved) implements PlutusDataConvertible {}
// -> Constr(0, [toData(outRef), toData(resolved)])
```

### Value (Multi-Asset)

```java
public record Value(Map<PolicyId, Map<TokenName, BigInteger>> inner) implements PlutusDataConvertible {
    // -> PlutusData.Map([Pair(B(policyId), Map([Pair(B(tokenName), I(qty))...]))...])

    public static Value zero() { ... }
    public static Value lovelace(BigInteger amount) { ... }
    public static Value singleton(PolicyId policyId, TokenName tokenName, BigInteger quantity) { ... }
    public Value merge(Value other) { ... }
    public BigInteger getLovelace() { ... }
    public BigInteger getAsset(PolicyId policyId, TokenName tokenName) { ... }
}
```

ADA is represented with empty PolicyId (`new byte[0]`) and empty TokenName (`new byte[0]`).

### Interval Types

```java
public sealed interface IntervalBoundType extends PlutusDataConvertible {
    record NegInf() implements IntervalBoundType {}     // Constr(0, [])
    record Finite(BigInteger time) implements IntervalBoundType {} // Constr(1, [I(time)])
    record PosInf() implements IntervalBoundType {}     // Constr(2, [])
}

public record IntervalBound(IntervalBoundType boundType, boolean isInclusive)
    implements PlutusDataConvertible {}
// -> Constr(0, [toData(boundType), encodeBool(isInclusive)])

public record Interval(IntervalBound from, IntervalBound to) implements PlutusDataConvertible {
    // -> Constr(0, [toData(from), toData(to)])
    public static Interval always() { ... }
    public static Interval after(BigInteger time) { ... }
    public static Interval before(BigInteger time) { ... }
    public static Interval between(BigInteger from, BigInteger to) { ... }
}
```

### Governance Types (V3/Conway)

```java
public sealed interface DRep extends PlutusDataConvertible {
    record DRepCredential(Credential credential) implements DRep {}  // Constr(0, [toData(cred)])
    record AlwaysAbstain() implements DRep {}                         // Constr(1, [])
    record AlwaysNoConfidence() implements DRep {}                    // Constr(2, [])
}

public sealed interface Delegatee extends PlutusDataConvertible {
    record Stake(PubKeyHash poolId) implements Delegatee {}                          // Constr(0)
    record Vote(DRep dRep) implements Delegatee {}                                   // Constr(1)
    record StakeVote(PubKeyHash poolId, DRep dRep) implements Delegatee {}           // Constr(2)
}

public sealed interface Vote extends PlutusDataConvertible {
    record VoteNo() implements Vote {}      // Constr(0, [])
    record VoteYes() implements Vote {}     // Constr(1, [])
    record Abstain() implements Vote {}     // Constr(2, [])
}

public sealed interface Voter extends PlutusDataConvertible {
    record CommitteeVoter(Credential credential) implements Voter {}      // Constr(0)
    record DRepVoter(Credential credential) implements Voter {}           // Constr(1)
    record StakePoolVoter(PubKeyHash pubKeyHash) implements Voter {}      // Constr(2)
}

public record GovernanceActionId(TxId txId, BigInteger govActionIx) implements PlutusDataConvertible {}
public record Rational(BigInteger numerator, BigInteger denominator) implements PlutusDataConvertible {}
public record ProtocolVersion(BigInteger major, BigInteger minor) implements PlutusDataConvertible {}
public record Committee(Map<Credential, BigInteger> members, Rational quorum) implements PlutusDataConvertible {}
```

### TxCert (11 V3 Certificate Types)

```java
public sealed interface TxCert extends PlutusDataConvertible {
    record RegStaking(Credential credential, Optional<BigInteger> deposit) implements TxCert {}       // Constr(0)
    record UnRegStaking(Credential credential, Optional<BigInteger> refund) implements TxCert {}      // Constr(1)
    record DelegStaking(Credential credential, Delegatee delegatee) implements TxCert {}              // Constr(2)
    record RegDeleg(Credential credential, Delegatee delegatee, BigInteger deposit) implements TxCert {} // Constr(3)
    record RegDRep(Credential credential, BigInteger deposit) implements TxCert {}                    // Constr(4)
    record UpdateDRep(Credential credential) implements TxCert {}                                     // Constr(5)
    record UnRegDRep(Credential credential, BigInteger refund) implements TxCert {}                   // Constr(6)
    record PoolRegister(PubKeyHash poolId, PubKeyHash poolVfr) implements TxCert {}                   // Constr(7)
    record PoolRetire(PubKeyHash pubKeyHash, BigInteger epoch) implements TxCert {}                   // Constr(8)
    record AuthHotCommittee(Credential cold, Credential hot) implements TxCert {}                     // Constr(9)
    record ResignColdCommittee(Credential cold) implements TxCert {}                                  // Constr(10)
}
```

### GovernanceAction (7 Variants)

```java
public sealed interface GovernanceAction extends PlutusDataConvertible {
    record ParameterChange(Optional<GovernanceActionId> id, PlutusData parameters, Optional<ScriptHash> constitutionScript) implements GovernanceAction {} // Constr(0)
    record HardForkInitiation(Optional<GovernanceActionId> id, ProtocolVersion protocolVersion) implements GovernanceAction {} // Constr(1)
    record TreasuryWithdrawals(Map<Credential, BigInteger> withdrawals, Optional<ScriptHash> constitutionScript) implements GovernanceAction {} // Constr(2)
    record NoConfidence(Optional<GovernanceActionId> id) implements GovernanceAction {} // Constr(3)
    record UpdateCommittee(Optional<GovernanceActionId> id, List<Credential> removedMembers, Map<Credential, BigInteger> addedMembers, Rational newQuorum) implements GovernanceAction {} // Constr(4)
    record NewConstitution(Optional<GovernanceActionId> id, Optional<ScriptHash> constitution) implements GovernanceAction {} // Constr(5)
    record InfoAction() implements GovernanceAction {} // Constr(6, [])
}

public record ProposalProcedure(BigInteger deposit, Credential returnAddress, GovernanceAction governanceAction) implements PlutusDataConvertible {}
```

### ScriptPurpose & ScriptInfo

```java
public sealed interface ScriptPurpose extends PlutusDataConvertible {
    record Minting(PolicyId policyId) implements ScriptPurpose {}                                           // Constr(0)
    record Spending(TxOutRef txOutRef) implements ScriptPurpose {}                                          // Constr(1)
    record Rewarding(Credential credential) implements ScriptPurpose {}                                     // Constr(2)
    record Certifying(BigInteger index, TxCert cert) implements ScriptPurpose {}                            // Constr(3)
    record Voting(Voter voter) implements ScriptPurpose {}                                                  // Constr(4)
    record Proposing(BigInteger index, ProposalProcedure procedure) implements ScriptPurpose {}             // Constr(5)
}

public sealed interface ScriptInfo extends PlutusDataConvertible {
    record MintingScript(PolicyId policyId) implements ScriptInfo {}                                        // Constr(0)
    record SpendingScript(TxOutRef txOutRef, Optional<PlutusData> datum) implements ScriptInfo {}           // Constr(1)
    record RewardingScript(Credential credential) implements ScriptInfo {}                                  // Constr(2)
    record CertifyingScript(BigInteger index, TxCert cert) implements ScriptInfo {}                         // Constr(3)
    record VotingScript(Voter voter) implements ScriptInfo {}                                               // Constr(4)
    record ProposingScript(BigInteger index, ProposalProcedure procedure) implements ScriptInfo {}          // Constr(5)
}
```

### TxInfo (16 Fields)

```java
public record TxInfo(
    List<TxInInfo> inputs,
    List<TxInInfo> referenceInputs,
    List<TxOut> outputs,
    BigInteger fee,                                          // Lovelace
    Value mint,
    List<TxCert> certificates,
    Map<Credential, BigInteger> withdrawals,                 // Credential -> Lovelace
    Interval validRange,
    List<PubKeyHash> signatories,
    Map<ScriptPurpose, PlutusData> redeemers,               // ScriptPurpose -> Redeemer (raw Data)
    Map<DatumHash, PlutusData> datums,                       // DatumHash -> Datum (raw Data)
    TxId id,
    Map<Voter, Map<GovernanceActionId, Vote>> votes,         // V3: governance votes
    List<ProposalProcedure> proposalProcedures,              // V3: proposals
    Optional<BigInteger> currentTreasuryAmount,              // V3: treasury balance
    Optional<BigInteger> treasuryDonation                    // V3: treasury donation
) implements PlutusDataConvertible {}
// -> Constr(0, [all 16 fields encoded])
```

### ScriptContext

```java
public record ScriptContext(
    TxInfo txInfo,
    PlutusData redeemer,    // Raw Data (not decoded)
    ScriptInfo scriptInfo
) implements PlutusDataConvertible {}
// -> Constr(0, [toData(txInfo), redeemer, toData(scriptInfo)])
```

---

## ScriptContextBuilder (Testing Utility)

```java
public class ScriptContextBuilder {
    public static ScriptContextBuilder spending(TxOutRef ref) { ... }
    public static ScriptContextBuilder minting(PolicyId policyId) { ... }
    public ScriptContextBuilder input(TxInInfo input) { ... }
    public ScriptContextBuilder output(TxOut output) { ... }
    public ScriptContextBuilder signer(PubKeyHash pkh) { ... }
    public ScriptContextBuilder validRange(Interval range) { ... }
    public ScriptContextBuilder fee(BigInteger fee) { ... }
    public ScriptContextBuilder redeemer(PlutusData redeemer) { ... }
    public ScriptContextBuilder txId(TxId txId) { ... }
    public ScriptContext build() { ... }
}
```

---

## Testing Strategy

1. **Unit tests per type**: Construction, validation, equality, toString
2. **PlutusData round-trip**: `toPlutusData()` -> `fromPlutusData()` -> assert equals
3. **CBOR conformance**: Encode PlutusData via `PlutusDataCborEncoder`, decode, verify equality
4. **ScriptContext integration**: Build full V3 context, pass to PlutusVm via `evaluateWithArgs()`
5. **Edge cases**: Empty lists, empty maps, boundary values, ADA-only Value

**Estimated total: ~282 tests across 12 tasks**

---

## Reference

- Scalus V3 contexts: `/Users/satya/work/cardano-comm-projects/scalus/scalus-core/shared/src/main/scala/scalus/cardano/onchain/plutus/v3/Contexts.scala`
- Scalus V1 base types: `/Users/satya/work/cardano-comm-projects/scalus/scalus-core/shared/src/main/scala/scalus/cardano/onchain/plutus/v1/Contexts.scala`
- Scalus Value: `/Users/satya/work/cardano-comm-projects/scalus/scalus-core/shared/src/main/scala/scalus/cardano/onchain/plutus/v1/Value.scala`
- Scalus Option: `/Users/satya/work/cardano-comm-projects/scalus/scalus-core/shared/src/main/scala/scalus/cardano/onchain/plutus/prelude/Option.scala`
